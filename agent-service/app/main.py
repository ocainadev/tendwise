"""FastAPI entry point: `uvicorn app.main:app`."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import psycopg
from fastapi import FastAPI

from app.api import admin, health
from app.core.config import get_settings
from app.core.db import CONNECT_TIMEOUT_SECONDS
from app.core.migrations import apply_migrations

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


@asynccontextmanager
async def migrate_on_startup(app: FastAPI) -> AsyncIterator[None]:
    """FastAPI "lifespan": code before `yield` runs once when the server starts."""
    migrate_database()
    yield


def create_app(run_migrations: bool = False) -> FastAPI:
    """Build the FastAPI app and register its routes.

    A factory (instead of building the app at import time only) lets tests
    create a fresh app whenever they need one. Tests keep `run_migrations`
    off so they never need a database; the real server below turns it on.
    """
    app = FastAPI(
        title="Tendwise agent-service",
        version="0.1.0",
        lifespan=migrate_on_startup if run_migrations else None,
    )
    app.include_router(health.router)
    app.include_router(admin.router)
    return app


app = create_app(run_migrations=True)
