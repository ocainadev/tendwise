"""The LLMClient interface (a "port"): what the agent needs from any LLM provider.

The agent depends only on this interface. Each provider (Anthropic and OpenAI
today, Gemini later) is an adapter that implements it, so we can swap
providers in `.env` and use a fake LLM in tests.
"""

from typing import Literal, Protocol

from pydantic import BaseModel


class Message(BaseModel):
    """One turn of the conversation sent to the LLM."""

    role: Literal["user", "assistant"]
    content: str


class LLMResponse(BaseModel):
    """The model's reply plus the usage data we log for cost (T12)."""

    text: str
    model: str
    prompt_tokens: int
    completion_tokens: int


class LLMClient(Protocol):
    """Any object with this `complete` method is an LLMClient.

    A `Protocol` checks the shape (structural typing): adapters and test fakes
    do not need to inherit from it, they only need a matching method.
    """

    def complete(
        self,
        system: str,
        messages: list[Message],
        max_tokens: int | None = None,
    ) -> LLMResponse:
        """Send the system prompt and conversation, return the model's reply.

        `max_tokens=None` means "use the configured default" (LLM_MAX_TOKENS).
        """
        ...
