"""Knowledge base routes of the panel (T50): `/api/v1/panel/{slug}/documents`.

AGENT can read, OWNER can write. `require_panel_role` (T35) checks the CSRF
header on writes, the access token cookie, the membership of `{slug}` and the
role; the queries in app/rag/documents.py then filter by that slug. JSON field
names are camelCase, like the rest of the panel API (business-service).

Plain `def` routes: FastAPI runs them in a thread pool, so the (synchronous)
database calls do not block the server.
"""

import logging
from collections.abc import Iterator
from datetime import datetime
from typing import Annotated

import psycopg
from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from pydantic.alias_generators import to_camel

from app.auth.panel import PanelAccess, require_panel_role
from app.rag import documents
from app.rag.documents import MAX_DOCUMENT_CHARACTERS, MAX_TITLE_CHARACTERS, KnowledgeLimitError

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/panel/{slug}/documents")

DOCUMENT_NOT_FOUND = "Document not found"
KNOWLEDGE_BASE_UNAVAILABLE = (
    "The knowledge base is unavailable right now. Please try again shortly."
)

Reader = Annotated[PanelAccess, Depends(require_panel_role("AGENT"))]
Owner = Annotated[PanelAccess, Depends(require_panel_role("OWNER"))]


def get_db(request: Request) -> Iterator[psycopg.Connection]:
    """A connection from `app.state.connect_db` (set at startup), closed after the request."""
    try:
        conn = request.app.state.connect_db()
    except psycopg.OperationalError as error:
        # Error type only: the message can contain the host or user name.
        logger.warning("Knowledge base database unreachable: %s", type(error).__name__)
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, KNOWLEDGE_BASE_UNAVAILABLE
        ) from None
    with conn:
        yield conn


Db = Annotated[psycopg.Connection, Depends(get_db)]


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class DocumentSummary(CamelModel):
    id: int
    source_path: str
    title: str
    source: str
    status: str
    characters: int
    updated_at: datetime
    updated_by: int | None


class DocumentDetail(DocumentSummary):
    content: str


class DocumentInput(CamelModel):
    """Body of POST and PUT. Too long a title or text is a 422."""

    title: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=MAX_TITLE_CHARACTERS)
    ]
    content: Annotated[str, Field(min_length=1, max_length=MAX_DOCUMENT_CHARACTERS)]


class ReindexResponse(CamelModel):
    queued: int


def summary(document: documents.Document) -> DocumentSummary:
    return DocumentSummary(
        id=document.id,
        source_path=document.source_path,
        title=document.title,
        source=document.source,
        status=document.status,
        characters=document.characters,
        updated_at=document.updated_at,
        updated_by=document.updated_by,
    )


def detail(document: documents.Document) -> DocumentDetail:
    return DocumentDetail(**summary(document).model_dump(), content=document.content or "")


@router.get("", response_model=list[DocumentSummary], response_model_by_alias=True)
def list_documents(access: Reader, conn: Db) -> list[DocumentSummary]:
    """The business's documents with their status, without their text."""
    return [summary(d) for d in documents.list_documents(conn, access.business_slug)]


@router.get("/{document_id}", response_model=DocumentDetail, response_model_by_alias=True)
def get_document(document_id: int, access: Reader, conn: Db) -> DocumentDetail:
    document = documents.get_document(conn, access.business_slug, document_id)
    if document is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, DOCUMENT_NOT_FOUND)
    return detail(document)


@router.post(
    "",
    response_model=DocumentDetail,
    response_model_by_alias=True,
    status_code=status.HTTP_201_CREATED,
)
def create_document(body: DocumentInput, access: Owner, conn: Db) -> DocumentDetail:
    """Store a new document as PENDING; it is indexed in the background."""
    try:
        document = documents.create_document(
            conn, access.business_slug, body.title, body.content, access.user.user_id
        )
    except KnowledgeLimitError as error:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, str(error)) from None
    # Ids and sizes only: never the document's text.
    logger.info(
        "Document %d of %s created (%d characters)",
        document.id,
        access.business_slug,
        document.characters,
    )
    return detail(document)


@router.put("/{document_id}", response_model=DocumentDetail, response_model_by_alias=True)
def update_document(
    document_id: int, body: DocumentInput, access: Owner, conn: Db
) -> DocumentDetail:
    """Replace the title and text. A seed document becomes EDITOR (a restart keeps it)."""
    try:
        document = documents.update_document(
            conn, access.business_slug, document_id, body.title, body.content, access.user.user_id
        )
    except KnowledgeLimitError as error:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, str(error)) from None
    if document is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, DOCUMENT_NOT_FOUND)
    logger.info(
        "Document %d of %s updated (%d characters)",
        document.id,
        access.business_slug,
        document.characters,
    )
    return detail(document)


@router.delete("/{document_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_document(document_id: int, access: Owner, conn: Db) -> Response:
    if not documents.delete_document(conn, access.business_slug, document_id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, DOCUMENT_NOT_FOUND)
    logger.info("Document %d of %s deleted", document_id, access.business_slug)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post(
    "/reindex",
    response_model=ReindexResponse,
    response_model_by_alias=True,
    status_code=status.HTTP_202_ACCEPTED,
)
def reindex(access: Owner, conn: Db) -> ReindexResponse:
    """Rebuild the chunks of every document of the business in the background."""
    queued = documents.reindex_business(conn, access.business_slug)
    logger.info("Reindex of %s: %d document(s) queued", access.business_slug, queued)
    return ReindexResponse(queued=queued)
