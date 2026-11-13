import json
import logging

import pytest

from app.agent.intent import HISTORY_TURNS, IntentResult, classify_intent
from app.llm.client import LLMResponse, Message
from app.prompts import load_prompt


class FakeLLM:
    """An LLMClient that returns queued replies and records every call."""

    def __init__(self, replies: list[str]) -> None:
        self.replies = list(replies)
        self.calls: list[tuple[str, list[Message], int | None]] = []

    def complete(
        self, system: str, messages: list[Message], max_tokens: int | None = None
    ) -> LLMResponse:
        self.calls.append((system, messages, max_tokens))
        return LLMResponse(
            text=self.replies.pop(0), model="fake-model", prompt_tokens=100, completion_tokens=20
        )


def reply(**overrides: object) -> str:
    """A valid classifier reply as JSON text, with some fields overridden."""
    data: dict[str, object] = {
        "intent": "other",
        "confidence": 0.9,
        "sentiment": "neutral",
        "language": "en",
        "order_code": None,
        "phone_last4": None,
    }
    data.update(overrides)
    return json.dumps(data)


@pytest.mark.parametrize(
    "intent", ["catalog_question", "order_status", "complaint", "human_request", "other"]
)
def test_parses_each_intent(intent: str) -> None:
    llm = FakeLLM([reply(intent=intent, confidence=0.8)])

    outcome = classify_intent(llm, "some message", history=[])

    assert outcome.result.intent == intent
    assert outcome.result.confidence == 0.8
    assert outcome.used_fallback is False
    assert len(llm.calls) == 1


@pytest.mark.parametrize("language", ["en", "es", "pt"])
def test_parses_language(language: str) -> None:
    llm = FakeLLM([reply(language=language)])

    outcome = classify_intent(llm, "hola", history=[])

    assert outcome.result.language == language


def test_extracts_and_normalises_order_code_and_phone_digits() -> None:
    llm = FakeLLM([reply(intent="order_status", order_code=" jp-1042 ", phone_last4="5678")])

    result = classify_intent(llm, "where is jp-1042? phone ends 5678", history=[]).result

    assert result.order_code == "JP-1042"
    assert result.phone_last4 == "5678"


@pytest.mark.parametrize("code", ["1042", "JP1042", "JPX-1042", "JP-", ""])
def test_drops_order_code_with_wrong_shape(code: str) -> None:
    result = IntentResult.model_validate_json(reply(order_code=code))

    assert result.order_code is None


@pytest.mark.parametrize("digits", ["567", "56789", "****5678", "abcd"])
def test_drops_phone_digits_that_are_not_exactly_four(digits: str) -> None:
    result = IntentResult.model_validate_json(reply(phone_last4=digits))

    assert result.phone_last4 is None


def test_missing_optional_fields_default_to_none() -> None:
    text = json.dumps(
        {"intent": "other", "confidence": 1, "sentiment": "neutral", "language": "en"}
    )

    result = IntentResult.model_validate_json(text)

    assert result.order_code is None
    assert result.phone_last4 is None


def test_accepts_json_inside_a_code_fence() -> None:
    llm = FakeLLM([f"```json\n{reply(intent='complaint')}\n```"])

    outcome = classify_intent(llm, "my pizza was cold", history=[])

    assert outcome.result.intent == "complaint"
    assert outcome.used_fallback is False


def test_invalid_json_then_valid_retry_uses_the_retry() -> None:
    llm = FakeLLM(["Sure! The intent is order_status.", reply(intent="order_status")])

    outcome = classify_intent(llm, "where is my order?", history=[])

    assert outcome.result.intent == "order_status"
    assert outcome.used_fallback is False
    assert len(llm.calls) == 2
    # The retry shows the model its bad reply and asks again.
    retry_messages = llm.calls[1][1]
    assert [m.role for m in retry_messages] == ["user", "assistant", "user"]
    assert retry_messages[1].content == "Sure! The intent is order_status."
    # Both calls are counted for cost logging (T12).
    assert outcome.prompt_tokens == 200
    assert outcome.completion_tokens == 40


def test_invalid_twice_returns_fallback_without_raising(caplog: pytest.LogCaptureFixture) -> None:
    llm = FakeLLM(["not json", "{still not json"])
    secret_message = "my phone is 11 98765-4321, where is DC-2001?"

    with caplog.at_level(logging.WARNING):
        outcome = classify_intent(llm, secret_message, history=[], default_language="pt")

    assert outcome.used_fallback is True
    assert outcome.result == IntentResult(
        intent="other", confidence=0.0, sentiment="neutral", language="pt"
    )
    assert len(llm.calls) == 2
    assert len(outcome.llm_responses) == 2
    # A warning per bad reply, without the customer's message.
    assert len(caplog.records) == 2
    assert "98765" not in caplog.text


@pytest.mark.parametrize(
    "bad_reply",
    [
        reply(confidence=1.7),
        reply(confidence=-0.1),
        reply(intent="refund"),
        reply(language="fr"),
        reply(sentiment="angry"),
        json.dumps({"intent": "other"}),
        json.dumps(["not", "an", "object"]),
    ],
)
def test_schema_violations_count_as_invalid_output(bad_reply: str) -> None:
    llm = FakeLLM([bad_reply, bad_reply])

    outcome = classify_intent(llm, "hello", history=[])

    assert outcome.used_fallback is True
    assert outcome.result.confidence == 0.0
    assert len(llm.calls) == 2


def test_sends_only_the_last_history_turns() -> None:
    history = [
        Message(role="user" if i % 2 == 0 else "assistant", content=f"turn-{i:02d}")
        for i in range(10)
    ]
    llm = FakeLLM([reply()])

    classify_intent(llm, "latest question", history=history)

    system, messages, max_tokens = llm.calls[0]
    assert len(messages) == 1
    sent = messages[0].content
    kept = range(10 - HISTORY_TURNS, 10)
    for i in range(10):
        assert (f"turn-{i:02d}" in sent) == (i in kept)
    assert "<latest_customer_message>\nlatest question\n</latest_customer_message>" in sent
    assert system == load_prompt("classify_intent")
    assert max_tokens is not None


def test_business_default_language_is_sent_to_the_model() -> None:
    llm = FakeLLM([reply(language="pt")])

    classify_intent(llm, "DC-2001", history=[], default_language="pt")

    assert "Business default language: pt" in llm.calls[0][1][0].content


def test_classifier_prompt_loads_and_lists_the_json_keys() -> None:
    prompt = load_prompt("classify_intent")

    assert prompt.startswith("<!-- version:")
    for key in IntentResult.model_fields:
        assert f'"{key}"' in prompt


def test_retry_prompt_loads() -> None:
    assert "JSON" in load_prompt("classify_intent_retry")
