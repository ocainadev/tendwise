import pytest

from app.core.config import Settings
from app.llm.anthropic_client import AnthropicClient
from app.llm.factory import UnknownLLMProviderError, create_llm_client


def test_factory_returns_anthropic_adapter(settings: Settings) -> None:
    settings.llm_provider = "anthropic"

    client = create_llm_client(settings)

    assert isinstance(client, AnthropicClient)


def test_factory_accepts_provider_in_any_case(settings: Settings) -> None:
    settings.llm_provider = " Anthropic "

    assert isinstance(create_llm_client(settings), AnthropicClient)


def test_factory_raises_for_unknown_provider(settings: Settings) -> None:
    settings.llm_provider = "unknown-llm"

    with pytest.raises(UnknownLLMProviderError, match="unknown-llm"):
        create_llm_client(settings)
