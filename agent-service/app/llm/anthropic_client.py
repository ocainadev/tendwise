"""LLMClient adapter for Anthropic's Messages API (official `anthropic` SDK)."""

import anthropic
from pydantic import SecretStr

from app.llm.client import LLMResponse, Message


class AnthropicClient:
    """Implements `LLMClient` with Claude models."""

    def __init__(
        self,
        api_key: SecretStr | None,
        model: str,
        default_max_tokens: int,
        sdk_client: anthropic.Anthropic | None = None,
    ) -> None:
        """Create the adapter.

        `sdk_client` lets tests pass a fake SDK client, so no test calls the
        real API. Creating the SDK client makes no network call, so the service
        starts even without an API key; only `complete` would then fail.
        """
        if sdk_client is None:
            key = api_key.get_secret_value() if api_key else None
            sdk_client = anthropic.Anthropic(api_key=key)
        self._sdk = sdk_client
        self._model = model
        self._default_max_tokens = default_max_tokens

    def complete(
        self,
        system: str,
        messages: list[Message],
        max_tokens: int | None = None,
    ) -> LLMResponse:
        """Call the Messages API and map its reply to our `LLMResponse`."""
        if max_tokens is None:
            max_tokens = self._default_max_tokens

        response = self._sdk.messages.create(
            model=self._model,
            max_tokens=max_tokens,
            system=system,
            messages=[{"role": m.role, "content": m.content} for m in messages],
        )

        # The reply is a list of content blocks. Newer models may also return
        # `thinking` blocks, so we keep only the text blocks.
        text = "".join(block.text for block in response.content if block.type == "text")

        return LLMResponse(
            text=text,
            model=response.model,
            prompt_tokens=response.usage.input_tokens,
            completion_tokens=response.usage.output_tokens,
        )
