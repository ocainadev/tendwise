"""Unanswered questions of the panel (T58): `GET /api/v1/panel/{slug}/knowledge-gaps`.

OWNER only, as listed in docs/architecture.md. Returns the catalog questions
the knowledge base could not answer, grouped by meaning
(app/rag/knowledge_gaps.py), the most asked first. The query filters by the
slug that `require_panel_role` checked, so another business's gaps never show.

`from` and `to` are dates (`2026-11-01`), both included, read in UTC. Without
them the last 30 days are returned.
"""

from datetime import UTC, date, datetime, time, timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.api.documents import CamelModel, Db
from app.auth.panel import PanelAccess, require_panel_role
from app.rag.knowledge_gaps import list_gap_groups

router = APIRouter(prefix="/api/v1/panel/{slug}/knowledge-gaps")

DEFAULT_DAYS = 30

Owner = Annotated[PanelAccess, Depends(require_panel_role("OWNER"))]


class GapGroupResponse(CamelModel):
    question: str  # the newest wording of the group
    count: int
    last_asked: datetime
    examples: list[str]  # up to 3 other wordings, newest first


def period(start: date | None, end: date | None) -> tuple[datetime, datetime]:
    """[start, end) as UTC datetimes: from the start of `start` to the end of `end`."""
    end = end or datetime.now(UTC).date()
    start = start or end - timedelta(days=DEFAULT_DAYS - 1)
    if start > end:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "'from' must not be after 'to'")
    return (
        datetime.combine(start, time.min, UTC),
        datetime.combine(end + timedelta(days=1), time.min, UTC),
    )


@router.get("", response_model=list[GapGroupResponse], response_model_by_alias=True)
def list_knowledge_gaps(
    access: Owner,
    conn: Db,
    start: Annotated[date | None, Query(alias="from")] = None,
    end: Annotated[date | None, Query(alias="to")] = None,
) -> list[GapGroupResponse]:
    period_start, period_end = period(start, end)
    groups = list_gap_groups(conn, access.business_slug, period_start, period_end)
    return [
        GapGroupResponse(
            question=group.representative,
            count=group.count,
            last_asked=group.last_asked,
            examples=group.examples,
        )
        for group in groups
    ]
