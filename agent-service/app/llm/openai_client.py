"""LLMClient adapter for OpenAI's Responses API (official `openai` SDK).

OpenAI recommends the Responses API over Chat Completions for new projects:
https://developers.openai.com/api/docs/guides/migrate-to-responses
"""

import openai
from pydantic import SecretStr

from app.llm.client import LLMResponse, Message

# Current small OpenAI models (gpt-6-luna, gpt-5.6-luna) are reasoning models
# that default to "medium" effort. Reasoning tokens count against
# `max_output_tokens`, so with our small budget (LLM_MAX_TOKENS=512) the model
# could spend it all thinking and return no text. Our tasks (classify a message,
# write a short grounded reply) need no reasoning, so we turn it off.
REASONING_EFFORT = "none"


class OpenAIClient:
    """Implements `LLMClient` with OpenAI models."""

    def __init__(
        self,
        api_key: SecretStr | None,
        model: str,
        default_max_tokens: int,
        sdk_client: openai.OpenAI | None = None,
    ) -> None:
        """Create the adapter.

        `sdk_client` lets tests pass a fake SDK client, so no test calls the
        real API. Unlike the Anthropic SDK, the OpenAI SDK raises as soon as it
        is created without a key, so we create it on the first call instead:
        the service still starts without a key, and only `complete` fails.
        """
        self._api_key = api_key
        self._sdk = sdk_client
        self._model = model
        self._default_max_tokens = default_max_tokens

    def _get_sdk(self) -> openai.OpenAI:
        """Return the SDK client, creating it the first time (no network call)."""
        if self._sdk is None:
            key = self._api_key.get_secret_value() if self._api_key else None
            self._sdk = openai.OpenAI(api_key=key)
        return self._sdk

    def complete(
        self,
        system: str,
        messages: list[Message],
        max_tokens: int | None = None,
    ) -> LLMResponse:
        """Call the Responses API and map its reply to our `LLMResponse`."""
        if max_tokens is None:
            max_tokens = self._default_max_tokens

        response = self._get_sdk().responses.create(
            model=self._model,
            instructions=system,
            input=[{"role": m.role, "content": m.content} for m in messages],
            max_output_tokens=max_tokens,
            reasoning={"effort": REASONING_EFFORT},
            # OpenAI stores responses by default. We keep the conversation
            # ourselves, so we do not leave customer messages on their side.
            store=False,
        )

        # `output_text` joins the text of every message in the output and skips
        # other items (e.g. reasoning), like the Anthropic adapter keeps only
        # its text blocks.
        usage = response.usage
        return LLMResponse(
            text=response.output_text,
            model=response.model,
            # Output tokens include reasoning tokens: OpenAI bills them as output.
            prompt_tokens=usage.input_tokens if usage else 0,
            completion_tokens=usage.output_tokens if usage else 0,
        )
