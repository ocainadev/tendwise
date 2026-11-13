"""The classify_intent step: one LLM call that labels the customer's latest message.

The model replies with one JSON object, which we validate with Pydantic. Bad
output (not JSON, or JSON that breaks the schema) gets one retry; if that also
fails we return a safe fallback with confidence 0.0, so the router (T10) hands
the conversation to a human instead of guessing.
"""

import logging
import re
from typing import Literal

from pydantic import BaseModel, Field, ValidationError, field_validator

from app.guardrails.normalize import normalize
from app.llm.client import LLMClient, LLMResponse, Message
from app.prompts import load_prompt

logger = logging.getLogger(__name__)

Intent = Literal["catalog_question", "order_status", "complaint", "human_request", "other"]
Sentiment = Literal["positive", "neutral", "negative"]
Language = Literal["en", "es", "pt"]

# Only the last few turns are sent: enough context, fewer tokens.
HISTORY_TURNS = 6
# The JSON reply is short; a low cap keeps a misbehaving model cheap.
CLASSIFY_MAX_TOKENS = 200

ORDER_CODE_PATTERN = re.compile(r"[A-Z]{2}-[0-9]+")
PHONE_LAST4_PATTERN = re.compile(r"[0-9]{4}")
# A search query longer than this is cut: it is only used to find chunks.
MAX_SEARCH_QUERY_CHARS = 200
# Phone digits the customer wrote after a cue word, in en/es/pt (text normalised:
# lowercase, no accents): "last digits 0102", "ends in 0102", "final 0102",
# "termina em 0102", "ultimos 4 digitos ... 0102". The 4 digits must stand alone:
# not part of an order code (`JP-1042`) or of a longer number (`555-0102`).
PHONE_CUE_PATTERN = re.compile(
    r"\b(?:digits?|digitos?|ending|ends?|last|ultimos?|final|"
    r"termina\w*|phone|telefone|telefono|celular)\b"
    r"[^0-9]{0,30}?(?<![0-9-])([0-9]{4})(?![0-9-])"
)
# A reply wrapped in a Markdown fence: ```json ... ``` (the "json" tag is optional).
CODE_FENCE_PATTERN = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.DOTALL | re.IGNORECASE)


class IntentResult(BaseModel):
    """What the classifier says about the customer's latest message."""

    intent: Intent
    confidence: float = Field(ge=0.0, le=1.0)
    sentiment: Sentiment
    language: Language
    order_code: str | None = None
    phone_last4: str | None = None
    # For catalog questions: the question rewritten as a short search query in
    # the business's default language (the language its documents are written
    # in), so a Spanish question still finds Portuguese documents.
    search_query: str | None = None

    @field_validator("order_code", mode="before")
    @classmethod
    def normalise_order_code(cls, value: object) -> str | None:
        """Upper-case the code (`jp-1042` -> `JP-1042`); drop anything not shaped like one.

        A wrong-looking code becomes None instead of an error: the next step
        then asks the customer for it, which beats handing off to a human.
        """
        if not isinstance(value, str):
            return None
        code = value.strip().upper()
        return code if ORDER_CODE_PATTERN.fullmatch(code) else None

    @field_validator("phone_last4", mode="before")
    @classmethod
    def keep_only_four_digits(cls, value: object) -> str | None:
        """Keep the value only if it is exactly 4 digits; otherwise None (we ask again)."""
        if not isinstance(value, str):
            return None
        digits = value.strip()
        return digits if PHONE_LAST4_PATTERN.fullmatch(digits) else None

    @field_validator("search_query", mode="before")
    @classmethod
    def clean_search_query(cls, value: object) -> str | None:
        """Keep a non-empty string, cut to MAX_SEARCH_QUERY_CHARS; anything else is None.

        A bad value never fails the classification: retrieval then simply
        searches with the customer's own message.
        """
        if not isinstance(value, str) or not value.strip():
            return None
        return value.strip()[:MAX_SEARCH_QUERY_CHARS]


class ClassificationOutcome(BaseModel):
    """The classification plus what it cost, for routing (T10) and cost logging (T12)."""

    result: IntentResult
    # One entry per LLM call: one normally, two after a retry.
    llm_responses: list[LLMResponse]
    # True when the model never gave valid output and `result` is the fallback.
    used_fallback: bool

    @property
    def prompt_tokens(self) -> int:
        return sum(response.prompt_tokens for response in self.llm_responses)

    @property
    def completion_tokens(self) -> int:
        return sum(response.completion_tokens for response in self.llm_responses)


def phone_last4_in(message: str) -> str | None:
    """The 4 phone digits the customer gave after a cue word, or None.

    A deterministic backstop for the classifier, which sometimes misses digits
    written in the same message as the order code ("JP-1042, final 0102").
    """
    match = PHONE_CUE_PATTERN.search(normalize(message))
    return match.group(1) if match else None


def classify_intent(
    llm: LLMClient,
    message: str,
    history: list[Message],
    default_language: Language = "en",
) -> ClassificationOutcome:
    """Classify `message` (the customer's latest message) given the recent `history`.

    Never raises for bad model output: after one failed retry it returns the
    fallback. Errors from the LLM provider itself (network, auth) do propagate.
    """
    system = load_prompt("classify_intent")
    messages = [Message(role="user", content=_build_input(message, history, default_language))]
    responses: list[LLMResponse] = []

    for attempt in (1, 2):
        response = llm.complete(system, messages, max_tokens=CLASSIFY_MAX_TOKENS)
        responses.append(response)
        try:
            result = IntentResult.model_validate_json(_strip_code_fence(response.text))
            return ClassificationOutcome(
                result=result, llm_responses=responses, used_fallback=False
            )
        except ValidationError as error:
            # Log only what was wrong, never the text: it may echo the customer's message.
            logger.warning(
                "Invalid intent classification output (attempt %d): %s",
                attempt,
                _describe(error),
            )
        # Show the model its bad reply and ask once more for valid JSON.
        messages = messages + [
            Message(role="assistant", content=response.text),
            Message(role="user", content=load_prompt("classify_intent_retry")),
        ]

    fallback = IntentResult(
        intent="other", confidence=0.0, sentiment="neutral", language=default_language
    )
    return ClassificationOutcome(result=fallback, llm_responses=responses, used_fallback=True)


def _build_input(message: str, history: list[Message], default_language: str) -> str:
    """Lay out the recent turns and the latest message in the tags the prompt describes.

    Everything goes in one user turn, so the latest customer message stays
    clearly marked even after the retry adds more turns, and no provider
    complains about the order of roles.
    """
    lines = [f"Business default language: {default_language}", "", "<history>"]
    for turn in history[-HISTORY_TURNS:]:
        speaker = "Customer" if turn.role == "user" else "Assistant"
        lines.append(f"{speaker}: {turn.content}")
    lines += ["</history>", "", "<latest_customer_message>", message, "</latest_customer_message>"]
    return "\n".join(lines)


def _strip_code_fence(text: str) -> str:
    """Return the JSON inside a ```json fence, or the text itself if there is no fence."""
    match = CODE_FENCE_PATTERN.fullmatch(text.strip())
    return match.group(1) if match else text.strip()


def _describe(error: ValidationError) -> str:
    """Summarise a validation error as `field: error type` pairs, without the input values."""
    parts = []
    for detail in error.errors():
        field = ".".join(str(part) for part in detail["loc"]) or "<root>"
        parts.append(f"{field}: {detail['type']}")
    return ", ".join(parts)
