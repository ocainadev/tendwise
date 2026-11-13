"""FastAPI entry point: `uvicorn app.main:app`."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import psycopg
from fastapi import FastAPI

from app.agent.service import create_agent_service
from app.api import admin, chat, health
from app.api.rate_limit import create_rate_limiter
from app.core.config import Settings, get_settings
from app.core.db import CONNECT_TIMEOUT_SECONDS
from app.core.migrations import apply_migrations
from app.gateways.business_gateway import HttpBusinessGateway

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


def llm_is_configured(settings: Settings) -> bool:
    """False when the Anthropic provider is selected but ANTHROPIC_API_KEY is empty.

    Without a key every LLM call would fail, so we do not build the agent at
    all and /api/v1/chat answers a clean 503 instead.
    """
    is_anthropic = settings.llm_provider.strip().lower() == "anthropic"
    return not is_anthropic or settings.anthropic_api_key is not None


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
        logger.warning("ANTHROPIC_API_KEY is not set: /api/v1/chat will answer 503")
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
    build_chat_services(app, get_settings())
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
    return app


app = create_app(run_startup=True)
