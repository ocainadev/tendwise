import pytest
from pydantic import ValidationError

from app.core.config import Settings


def test_settings_load_from_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://u:p@db:5432/tendwise")
    monkeypatch.setenv("LLM_PROVIDER", "anthropic")
    monkeypatch.setenv("LLM_MODEL", "some-model")
    monkeypatch.setenv("LLM_MAX_TOKENS", "256")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    monkeypatch.setenv("BUSINESS_SERVICE_URL", "http://business:8080")
    monkeypatch.setenv("BUSINESS_SERVICE_API_KEY", "biz-key")
    monkeypatch.setenv("ADMIN_API_KEY", "admin-key")

    settings = Settings()

    assert settings.database_url.get_secret_value() == "postgresql://u:p@db:5432/tendwise"
    assert settings.llm_provider == "anthropic"
    assert settings.llm_model == "some-model"
    assert settings.llm_max_tokens == 256
    assert settings.anthropic_api_key is not None
    assert settings.anthropic_api_key.get_secret_value() == "sk-test"
    assert settings.business_service_url == "http://business:8080"
    assert settings.business_service_api_key is not None
    assert settings.business_service_api_key.get_secret_value() == "biz-key"
    assert settings.admin_api_key is not None
    assert settings.admin_api_key.get_secret_value() == "admin-key"


def test_defaults_apply_and_api_keys_are_optional(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://u:p@db:5432/tendwise")
    # An empty value, as in a freshly copied .env, falls back to the default.
    monkeypatch.setenv("LLM_MAX_TOKENS", "")

    settings = Settings()

    assert settings.llm_provider == "anthropic"
    assert settings.llm_model == "claude-haiku-5-5"
    assert settings.llm_max_tokens == 512
    assert settings.anthropic_api_key is None


def test_database_url_is_required() -> None:
    with pytest.raises(ValidationError):
        Settings()


def test_secrets_are_hidden_when_printed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://u:db-password@db:5432/tendwise")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-secret")

    printed = repr(Settings())

    assert "db-password" not in printed
    assert "sk-test-secret" not in printed
