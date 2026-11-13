import pytest
from pydantic import SecretStr

from app.core.config import Settings
from app.llm.anthropic_client import AnthropicClient
from app.llm.factory import UnknownLLMProviderError, create_llm_client
from app.llm.openai_client import OpenAIClient


def test_factory_returns_anthropic_adapter(settings: Settings) -> None:
    settings.llm_provider = "anthropic"

    client = create_llm_client(settings)

    assert isinstance(client, AnthropicClient)


def test_factory_accepts_provider_in_any_case(settings: Settings) -> None:
    settings.llm_provider = " Anthropic "

    assert isinstance(create_llm_client(settings), AnthropicClient)


def test_factory_returns_openai_adapter_with_its_key_and_model() -> None:
    settings = Settings(
        database_url="postgresql://test@db-host:5432/test",
        llm_provider="OpenAI",
        openai_api_key=SecretStr("sk-test-key"),
    )

    client = create_llm_client(settings)

    assert isinstance(client, OpenAIClient)
    assert client._model == "gpt-6-luna"
    assert client._get_sdk().api_key == "sk-test-key"


def test_factory_builds_openai_adapter_without_a_key(settings: Settings) -> None:
    # Like Anthropic: the service starts without a key; only the call fails.
    settings.llm_provider = "openai"

    assert isinstance(create_llm_client(settings), OpenAIClient)


def test_factory_raises_for_unknown_provider(settings: Settings) -> None:
    settings.llm_provider = "unknown-llm"

    with pytest.raises(UnknownLLMProviderError, match="unknown-llm") as error:
        create_llm_client(settings)
    assert "anthropic, openai" in str(error.value)
