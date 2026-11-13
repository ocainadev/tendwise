"""FastAPI entry point: `uvicorn app.main:app`."""

from fastapi import FastAPI

from app.api import health


def create_app() -> FastAPI:
    """Build the FastAPI app and register its routes.

    A factory (instead of building the app at import time only) lets tests
    create a fresh app whenever they need one.
    """
    app = FastAPI(title="Tendwise agent-service", version="0.1.0")
    app.include_router(health.router)
    return app


app = create_app()
