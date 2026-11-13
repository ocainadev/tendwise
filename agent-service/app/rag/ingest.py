"""Ingestion: read `data/businesses/<slug>/*.md`, chunk, embed, store in pgvector.

Idempotent: each document keeps the sha256 of its file. An unchanged file is
skipped, a changed file gets its chunks replaced, and a removed file loses its
document and chunks. So running ingestion twice creates no duplicate chunks.
"""

import hashlib
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import psycopg
from pgvector import Vector
from pgvector.psycopg import register_vector
from psycopg.types.json import Jsonb

from app.core.config import Settings
from app.core.migrations import apply_migrations
from app.rag.chunking import chunk_markdown
from app.rag.embeddings import Embedder, create_embedder

logger = logging.getLogger(__name__)

# Names the lock that stops two ingestions (script and endpoint) from running
# at once. Without it, both could see "no document yet" and insert it twice.
INGEST_LOCK_ID = 7_002

FileResult = Literal["added", "updated", "skipped"]


@dataclass
class IngestSummary:
    """How many documents each run added, updated, skipped and deleted."""

    added: int = 0
    updated: int = 0
    skipped: int = 0
    deleted: int = 0


def ingest_all(conn: psycopg.Connection, businesses_dir: Path, embedder: Embedder) -> IngestSummary:
    """Sync every business folder under `businesses_dir` with the database.

    The folder name is the business slug, stored as `business_id`. Everything
    runs in one transaction: if anything fails, the index stays as it was.
    """
    summary = IngestSummary()
    with conn.transaction():
        conn.execute("SELECT pg_advisory_xact_lock(%s)", (INGEST_LOCK_ID,))

        folders = {path.name: path for path in businesses_dir.iterdir() if path.is_dir()}
        # Businesses already in the index whose folder is gone must be cleaned up too.
        rows = conn.execute("SELECT DISTINCT business_id FROM agent.documents").fetchall()
        business_ids = sorted(set(folders) | {row[0] for row in rows})

        for business_id in business_ids:
            folder = folders.get(business_id)
            files = sorted(folder.glob("*.md")) if folder else []
            for file in files:
                result = _ingest_file(conn, business_id, file, embedder)
                if result == "added":
                    summary.added += 1
                elif result == "updated":
                    summary.updated += 1
                else:
                    summary.skipped += 1
            source_paths = [_source_path(business_id, file) for file in files]
            summary.deleted += _delete_missing(conn, business_id, source_paths)
    return summary


def run_ingestion(settings: Settings) -> IngestSummary:
    """Connect, apply migrations and ingest `<DATA_DIR>/businesses` (script and endpoint)."""
    businesses_dir = Path(settings.data_dir) / "businesses"
    if not businesses_dir.is_dir():
        raise FileNotFoundError(f"Businesses folder not found: {businesses_dir.resolve()}")
    embedder = create_embedder(settings)
    with psycopg.connect(settings.database_url.get_secret_value()) as conn:
        apply_migrations(conn)
        # Teach psycopg the `vector` type; needs the extension, so after migrations.
        register_vector(conn)
        return ingest_all(conn, businesses_dir, embedder)


def content_hash(text: str) -> str:
    """sha256 of the file content: same content, same hash."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _source_path(business_id: str, file: Path) -> str:
    """Path stored in `documents.source_path`, e.g. 'joes-pizza/faq.md'."""
    return f"{business_id}/{file.name}"


def _title(markdown: str, file: Path) -> str:
    """The first `# Heading`, or the file name ('faq') when there is none."""
    for line in markdown.splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return file.stem


def _ingest_file(
    conn: psycopg.Connection, business_id: str, file: Path, embedder: Embedder
) -> FileResult:
    """Add, update or skip one file (the caller's transaction makes it all-or-nothing)."""
    text = file.read_text(encoding="utf-8")
    source_path = _source_path(business_id, file)
    new_hash = content_hash(text)

    row = conn.execute(
        "SELECT id, content_hash FROM agent.documents WHERE business_id = %s AND source_path = %s",
        (business_id, source_path),
    ).fetchone()
    if row is not None and row[1] == new_hash:
        return "skipped"

    chunks = chunk_markdown(text)
    vectors = embedder.embed([chunk.content for chunk in chunks])

    result: FileResult
    if row is None:
        result = "added"
        inserted = conn.execute(
            """
            INSERT INTO agent.documents (business_id, source_path, title, content_hash)
            VALUES (%s, %s, %s, %s)
            RETURNING id
            """,
            (business_id, source_path, _title(text, file), new_hash),
        ).fetchone()
        assert inserted is not None  # RETURNING always gives a row
        document_id = inserted[0]
    else:
        result = "updated"
        document_id = row[0]
        conn.execute(
            """
            UPDATE agent.documents SET title = %s, content_hash = %s, updated_at = now()
            WHERE id = %s AND business_id = %s
            """,
            (_title(text, file), new_hash, document_id, business_id),
        )
        conn.execute(
            "DELETE FROM agent.chunks WHERE document_id = %s AND business_id = %s",
            (document_id, business_id),
        )

    with conn.cursor() as cursor:
        cursor.executemany(
            """
            INSERT INTO agent.chunks (document_id, business_id, content, embedding, metadata)
            VALUES (%s, %s, %s, %s, %s)
            """,
            [
                (document_id, business_id, chunk.content, Vector(vector), Jsonb(chunk.metadata))
                for chunk, vector in zip(chunks, vectors, strict=True)
            ],
        )
    logger.info("Ingested %s: %s, %d chunks", source_path, result, len(chunks))
    return result


def _delete_missing(conn: psycopg.Connection, business_id: str, source_paths: list[str]) -> int:
    """Delete this business's documents whose file is gone; chunks go by ON DELETE CASCADE."""
    deleted = conn.execute(
        """
        DELETE FROM agent.documents
        WHERE business_id = %s AND NOT (source_path = ANY(%s))
        RETURNING source_path
        """,
        (business_id, source_paths),
    ).fetchall()
    for row in deleted:
        logger.info("Deleted %s: its file was removed", row[0])
    return len(deleted)
