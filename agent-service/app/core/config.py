"""Application settings, read from environment variables.

Settings come only from the process environment (docker compose passes the
values from the root `.env`). We do not read a `.env` file here, so tests never
pick up a developer's real keys by accident.
"""

from functools import lru_cache

from pydantic import Field, SecretStr
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


@lru_cache
def get_settings() -> Settings:
    """Load the settings once and reuse them (also a FastAPI dependency)."""
    return Settings()
