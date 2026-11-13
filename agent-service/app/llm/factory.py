"""Pick the LLMClient adapter that LLM_PROVIDER asks for."""

from app.core.config import PROVIDER_API_KEY_VARIABLES, Settings
from app.llm.anthropic_client import AnthropicClient
from app.llm.client import LLMClient
from app.llm.openai_client import OpenAIClient

SUPPORTED_PROVIDERS = tuple(PROVIDER_API_KEY_VARIABLES)


class UnknownLLMProviderError(ValueError):
    """LLM_PROVIDER names a provider we have no adapter for."""


def create_llm_client(settings: Settings) -> LLMClient:
    """Return the adapter for `settings.llm_provider`.

    This is the only place that knows the concrete adapter classes; the rest of
    the code only sees `LLMClient`.
    """
    provider = settings.provider()

    if provider == "anthropic":
        return AnthropicClient(
            api_key=settings.anthropic_api_key,
            model=settings.llm_model,
            default_max_tokens=settings.llm_max_tokens,
        )

    if provider == "openai":
        return OpenAIClient(
            api_key=settings.openai_api_key,
            model=settings.llm_model,
            default_max_tokens=settings.llm_max_tokens,
        )

    raise UnknownLLMProviderError(
        f"Unknown LLM_PROVIDER {settings.llm_provider!r}. "
        f"Supported providers: {', '.join(SUPPORTED_PROVIDERS)}."
    )
