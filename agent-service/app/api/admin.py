"""POST /api/v1/admin/ingest: re-index data/businesses (admin key required)."""

import hmac
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, status
from pydantic import BaseModel

from app.core.config import Settings, get_settings
from app.rag.ingest import run_ingestion

router = APIRouter(prefix="/api/v1/admin")


class IngestResponse(BaseModel):
    added: int
    updated: int
    skipped: int
    deleted: int


def require_admin_key(
    settings: Annotated[Settings, Depends(get_settings)],
    x_admin_key: Annotated[str | None, Header()] = None,
) -> None:
    """Allow the request only when the X-Admin-Key header matches ADMIN_API_KEY.

    503 when no key is configured (the endpoint is off), 401 for a missing or
    wrong key. `hmac.compare_digest` takes the same time wherever the strings
    differ, so the key cannot be guessed character by character from timings.
    """
    if settings.admin_api_key is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "ADMIN_API_KEY is not configured")
    expected = settings.admin_api_key.get_secret_value().encode()
    if x_admin_key is None or not hmac.compare_digest(x_admin_key.encode(), expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing or invalid X-Admin-Key")


# A plain `def`: FastAPI runs it in a thread pool, so the slow embedding work
# does not block other requests.
@router.post("/ingest", response_model=IngestResponse, dependencies=[Depends(require_admin_key)])
def ingest(settings: Annotated[Settings, Depends(get_settings)]) -> IngestResponse:
    """Sync the vector index with the .md files and return what changed."""
    summary = run_ingestion(settings)
    return IngestResponse(
        added=summary.added,
        updated=summary.updated,
        skipped=summary.skipped,
        deleted=summary.deleted,
    )
