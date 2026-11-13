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


def test_rag_settings_have_defaults_and_read_the_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://u:p@db:5432/tendwise")
    monkeypatch.setenv("EMBEDDING_DIMENSIONS", "")  # empty -> default

    defaults = Settings()

    assert defaults.embedding_model == "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
    assert defaults.embedding_dimensions == 384
    assert defaults.embedding_cache_dir == ".cache/fastembed"
    assert defaults.data_dir == "../data"

    monkeypatch.setenv("EMBEDDING_MODEL", "other-model")
    monkeypatch.setenv("EMBEDDING_DIMENSIONS", "768")
    monkeypatch.setenv("EMBEDDING_CACHE_DIR", "/models")
    monkeypatch.setenv("DATA_DIR", "/app/data")

    configured = Settings()

    assert configured.embedding_model == "other-model"
    assert configured.embedding_dimensions == 768
    assert configured.embedding_cache_dir == "/models"
    assert configured.data_dir == "/app/data"


def test_retrieval_settings_have_defaults_and_bounds(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://u:p@db:5432/tendwise")
    monkeypatch.setenv("RAG_MIN_SIMILARITY", "")  # empty -> default

    defaults = Settings()

    assert defaults.rag_top_k == 5
    assert defaults.rag_min_similarity == 0.30

    monkeypatch.setenv("RAG_TOP_K", "3")
    monkeypatch.setenv("RAG_MIN_SIMILARITY", "0.5")
    configured = Settings()
    assert configured.rag_top_k == 3
    assert configured.rag_min_similarity == 0.5

    monkeypatch.setenv("RAG_MIN_SIMILARITY", "1.5")  # a similarity is at most 1
    with pytest.raises(ValidationError):
        Settings()
