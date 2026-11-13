"""Application settings, read from environment variables.

Settings come only from the process environment (docker compose passes the
values from the root `.env`). We do not read a `.env` file here, so tests never
pick up a developer's real keys by accident.
"""

from functools import lru_cache

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """All agent-service configuration in one typed place.

    Each field maps to the upper-case environment variable of the same name,
    e.g. `llm_model` <- `LLM_MODEL`. Secrets are `SecretStr`, so printing the
    settings shows `**********` instead of the value.
    """

    # Empty variables (e.g. `LLM_MAX_TOKENS=` in .env) fall back to the default
    # instead of failing validation.
    model_config = SettingsConfigDict(env_ignore_empty=True)

    # PostgreSQL connection string, e.g. postgresql://user:password@db:5432/tendwise.
    # Compose builds it from the POSTGRES_* variables. Secret because it holds
    # the password.
    database_url: SecretStr

    # LLM
    llm_provider: str = "anthropic"
    llm_model: str = "claude-haiku-5-5"
    llm_max_tokens: int = 512
    # Optional so the service starts without a key; only an LLM call fails.
    anthropic_api_key: SecretStr | None = None

    # business-service (used by the tools in later tasks)
    business_service_url: str = "http://business-service:8080"
    business_service_api_key: SecretStr | None = None

    # Key required by POST /api/v1/admin/ingest (T07)
    admin_api_key: SecretStr | None = None


@lru_cache
def get_settings() -> Settings:
    """Load the settings once and reuse them (also a FastAPI dependency)."""
    return Settings()
