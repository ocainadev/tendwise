"""The exceptions an LLM adapter can raise, in one place.

Callers (the chat API, the eval runner) catch these tuples instead of
importing each provider's SDK, so adding a provider only changes this file.
"""

import anthropic
import openai

# Any failure of the LLM provider: network, wrong key, overload, rate limit, ...
LLM_ERRORS: tuple[type[Exception], ...] = (anthropic.AnthropicError, openai.OpenAIError)

# The provider rejected the API key.
LLM_AUTH_ERRORS: tuple[type[Exception], ...] = (
    anthropic.AuthenticationError,
    openai.AuthenticationError,
)
