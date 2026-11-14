"""FastAPI entry point: `uvicorn app.main:app`."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import psycopg
from fastapi import FastAPI

from app.agent.service import create_agent_service
from app.api import admin, chat, health, internal
from app.api.rate_limit import create_rate_limiter
from app.core.config import Settings, get_settings
from app.core.db import CONNECT_TIMEOUT_SECONDS
from app.core.migrations import apply_migrations
from app.gateways.business_gateway import HttpBusinessGateway
from app.rag.ingest import run_ingestion

logger = logging.getLogger(__name__)


def migrate_database() -> None:
    """Apply pending `agent` schema migrations.

    If the database is down we only log it: the service still starts and
    /health reports the problem (the ingest script migrates again anyway).
    """
    url = get_settings().database_url.get_secret_value()
    try:
        with psycopg.connect(url, connect_timeout=CONNECT_TIMEOUT_SECONDS) as conn:
            applied = apply_migrations(conn)
    except psycopg.Error as error:
        # Log only the error type: the message can contain the host or user name.
        logger.warning("Could not apply migrations: %s", type(error).__name__)
        return
    if applied:
        logger.info("Applied migrations: %s", ", ".join(applied))


def ingest_documents(settings: Settings) -> None:
    """Index `data/businesses` so a fresh `docker compose up` can answer catalog questions.

    Safe to run on every start: unchanged files are skipped (content hash) and
    an advisory lock stops two ingestions from running at once. If it fails
    (database down, folder missing, ...) we only log it: the service still
    starts, and `scripts/ingest.py` or POST /api/v1/admin/ingest can retry.
    """
    try:
        summary = run_ingestion(settings)
    except Exception as error:  # broad on purpose: whatever fails, the app must still start
        # Log only the error type: the message can contain the host or user name.
        logger.warning("Startup ingestion failed: %s", type(error).__name__)
        return
    logger.info(
        "Startup ingestion: added=%d updated=%d skipped=%d deleted=%d",
        summary.added,
        summary.updated,
        summary.skipped,
        summary.deleted,
    )


def llm_is_configured(settings: Settings) -> bool:
    """False when the selected provider's API key (e.g. OPENAI_API_KEY) is empty.

    Without a key every LLM call would fail, so we do not build the agent at
    all and /api/v1/chat answers a clean 503 instead. An unknown provider counts
    as configured here: building the agent then fails with a clear error.
    """
    if settings.llm_api_key_variable() is None:
        return True
    return settings.llm_api_key() is not None


def build_chat_services(app: FastAPI, settings: Settings) -> None:
    """Build what /api/v1/chat needs once and store it on `app.state`.

    A part that cannot be built (no API key, database down, ...) is left as
    None and only logged: the service still starts, /health still works, and
    /api/v1/chat answers 503 until the problem is fixed and the service restarts.
    """
    app.state.rate_limiter = create_rate_limiter(settings)
    app.state.gateway = None
    app.state.agent_service = None

    try:
        app.state.gateway = HttpBusinessGateway.from_settings(settings)
    except ValueError:
        logger.warning("BUSINESS_SERVICE_API_KEY is not set: /api/v1/chat will answer 503")

    if not llm_is_configured(settings):
        logger.warning(
            "%s is not set: /api/v1/chat will answer 503", settings.llm_api_key_variable()
        )
        return
    try:
        # Slow: loads the embedding model and connects to PostgreSQL.
        app.state.agent_service = create_agent_service(settings)
    except Exception as error:  # broad on purpose: whatever fails, the app must still start
        # Log only the error type: the message can contain the host or user name.
        logger.warning("Could not build the agent service: %s", type(error).__name__)


@asynccontextmanager
async def startup(app: FastAPI) -> AsyncIterator[None]:
    """FastAPI "lifespan": code before `yield` runs once when the server starts."""
    migrate_database()
    settings = get_settings()
    ingest_documents(settings)
    build_chat_services(app, settings)
    yield


def create_app(run_startup: bool = False) -> FastAPI:
    """Build the FastAPI app and register its routes.

    A factory (instead of building the app at import time only) lets tests
    create a fresh app whenever they need one. Tests keep `run_startup` off so
    they never need a database, an LLM or business-service (they put fakes on
    `app.state` or use `app.dependency_overrides`); the real server below
    turns it on.
    """
    app = FastAPI(
        title="Tendwise agent-service",
        version="0.1.0",
        lifespan=startup if run_startup else None,
    )
    app.include_router(health.router)
    app.include_router(admin.router)
    app.include_router(chat.router)
    app.include_router(internal.router)
    return app


app = create_app(run_startup=True)
