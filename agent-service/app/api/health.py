"""GET /health: is the service up and can it reach the database?"""

import logging
from typing import Annotated, Literal

import psycopg
from fastapi import APIRouter, Depends, Response, status
from pydantic import BaseModel

from app.core.config import Settings, get_settings
from app.core.db import check_database

logger = logging.getLogger(__name__)

router = APIRouter()


class HealthResponse(BaseModel):
    status: Literal["ok", "error"]
    database: Literal["ok", "error"]


# A plain `def` (not `async def`): FastAPI runs it in a thread pool, so the
# blocking database call does not block other requests.
@router.get("/health", response_model=HealthResponse)
def health(
    response: Response,
    settings: Annotated[Settings, Depends(get_settings)],
) -> HealthResponse:
    """Return 200 when `SELECT 1` works, 503 when the database is unreachable."""
    try:
        check_database(settings.database_url.get_secret_value())
    except psycopg.Error as error:
        # Log only the error type: the message can contain the host or user name.
        logger.warning("Database health check failed: %s", type(error).__name__)
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return HealthResponse(status="error", database="error")
    return HealthResponse(status="ok", database="ok")
