"""Application settings, read from environment variables.

Settings come only from the process environment (docker compose passes the
values from the root `.env`). We do not read a `.env` file here, so tests never
pick up a developer's real keys by accident.
"""

from functools import lru_cache

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# Per LLM provider: the model used when LLM_MODEL is empty (a small, fast,
# low-cost one; prices in app/observability/pricing.py) ...
PROVIDER_DEFAULT_MODELS = {
    "anthropic": "claude-haiku-5-5",
    "openai": "gpt-6-luna",
}
# ... and the variable that holds its API key.
PROVIDER_API_KEY_VARIABLES = {
    "anthropic": "ANTHROPIC_API_KEY",
    "openai": "OPENAI_API_KEY",
}


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
    # Empty means the provider's default (PROVIDER_DEFAULT_MODELS), filled in below.
    llm_model: str = ""
    llm_max_tokens: int = 512
    # Optional so the service starts without a key; only an LLM call fails.
    # Only the key of the selected provider is needed.
    anthropic_api_key: SecretStr | None = None
    openai_api_key: SecretStr | None = None

    # business-service (used by the tools in later tasks)
    business_service_url: str = "http://business-service:8080"
    business_service_api_key: SecretStr | None = None

    # Key required by POST /api/v1/admin/ingest (T07). Without it the endpoint
    # answers 503, so ingestion over HTTP is off unless you set a key.
    admin_api_key: SecretStr | None = None

    # RAG (T07). The model runs locally (fastembed, ONNX), so it needs no API
    # key. It is multilingual because content and questions are in en/es/pt.
    # EMBEDDING_DIMENSIONS must match the vector(N) column in migrations/.
    embedding_model: str = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
    embedding_dimensions: int = 384
    # Where the model files are downloaded on first use (relative to the
    # working directory: agent-service/ locally, /app in Docker).
    embedding_cache_dir: str = ".cache/fastembed"
    # Folder that holds `businesses/<slug>/*.md` (the repo's data/ folder).
    data_dir: str = "../data"

    # Retrieval (T08): how many chunks the agent gets per question, and the
    # lowest cosine similarity a chunk needs to count as relevant. Chunks under
    # the threshold are dropped, so an off-topic question gets no context at all.
    # 0.30 was measured with the real model; see "Retrieval" in docs/architecture.md.
    rag_top_k: int = Field(default=5, ge=1)
    rag_min_similarity: float = Field(default=0.30, ge=0.0, le=1.0)

    # Retention (T12, spec section 9): `scripts/report.py --purge` deletes
    # conversations older than this, with their turns and their agent memory.
    conversation_retention_days: int = Field(default=90, ge=1)

    # Chat rate limit (T13, cost control): at most CHAT_RATE_LIMIT_MESSAGES
    # messages per conversation in any CHAT_RATE_LIMIT_WINDOW_SECONDS window.
    chat_rate_limit_messages: int = Field(default=20, ge=1)
    chat_rate_limit_window_seconds: float = Field(default=600.0, gt=0)

    @model_validator(mode="after")
    def use_provider_default_model(self) -> "Settings":
        """Without LLM_MODEL, use the selected provider's default model."""
        if not self.llm_model:
            self.llm_model = PROVIDER_DEFAULT_MODELS.get(self.provider(), "")
        return self

    def provider(self) -> str:
        """LLM_PROVIDER, normalized: " OpenAI " -> "openai"."""
        return self.llm_provider.strip().lower()

    def llm_api_key_variable(self) -> str | None:
        """The variable that holds the selected provider's key; None for an unknown provider."""
        return PROVIDER_API_KEY_VARIABLES.get(self.provider())

    def llm_api_key(self) -> SecretStr | None:
        """The selected provider's API key, or None when it is not set."""
        keys = {"anthropic": self.anthropic_api_key, "openai": self.openai_api_key}
        return keys.get(self.provider())


@lru_cache
def get_settings() -> Settings:
    """Load the settings once and reuse them (also a FastAPI dependency)."""
    return Settings()
