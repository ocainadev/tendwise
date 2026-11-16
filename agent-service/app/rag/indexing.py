"""The INDEX_DOCUMENT job (T50): rebuild one document's chunks after a panel save.

Saving a document in the panel only stores its text with status PENDING and
enqueues this job, so the save answers at once. The job worker
(app/core/jobs.py) then runs `IndexDocumentHandler.run`:

1. read the document's content (it must still have the hash the job was made for);
2. chunk and embed it, outside any transaction (the slow part);
3. in one transaction: replace the document's chunks and set it INDEXED.

Each job names the version it indexes (`content_hash`). If the owner saves
again meanwhile, the old job finds another hash and does nothing; the job of
the newer save indexes the newer text. So an old job can never overwrite newer
chunks, and running a job twice is harmless.
"""

import logging
from typing import Any

import psycopg
from pgvector.psycopg import register_vector

from app.core.db import ConnectionFactory
from app.core.jobs import Job, enqueue
from app.rag.chunking import chunk_markdown
from app.rag.embeddings import Embedder
from app.rag.ingest import replace_chunks

logger = logging.getLogger(__name__)

INDEX_DOCUMENT = "INDEX_DOCUMENT"


def enqueue_indexing(
    conn: psycopg.Connection, business_id: str, document_id: int, content_hash: str
) -> None:
    """Queue the indexing of this version of a document (commits with the caller's save)."""
    payload: dict[str, Any] = {
        "business_id": business_id,
        "document_id": document_id,
        "content_hash": content_hash,
    }
    enqueue(conn, INDEX_DOCUMENT, payload)


class IndexDocumentHandler:
    """Runs INDEX_DOCUMENT jobs: chunk, embed and replace one document's chunks."""

    def __init__(self, connect: ConnectionFactory, embedder: Embedder) -> None:
        self._connect = connect
        self._embedder = embedder

    def run(self, job: Job) -> None:
        business_id = job.payload["business_id"]
        document_id = job.payload["document_id"]
        expected_hash = job.payload["content_hash"]

        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT content FROM agent.documents
                WHERE id = %s AND business_id = %s AND content_hash = %s
                """,
                (document_id, business_id, expected_hash),
            ).fetchone()
        if row is None:
            # Deleted, or saved again since: nothing to do for this version.
            logger.info(
                "Index job %d: document %d changed or deleted, skipped", job.id, document_id
            )
            return

        chunks = chunk_markdown(row[0])
        vectors = self._embedder.embed([chunk.content for chunk in chunks])

        with self._connect() as conn:
            register_vector(conn)
            with conn.transaction():
                # Check the version again, now holding the row lock: a save
                # while we were embedding wins (its own job indexes it).
                still_current = conn.execute(
                    """
                    SELECT 1 FROM agent.documents
                    WHERE id = %s AND business_id = %s AND content_hash = %s
                    FOR UPDATE
                    """,
                    (document_id, business_id, expected_hash),
                ).fetchone()
                if still_current is None:
                    logger.info("Index job %d: document %d changed, skipped", job.id, document_id)
                    return
                replace_chunks(conn, business_id, document_id, chunks, vectors)
                conn.execute(
                    """
                    UPDATE agent.documents SET status = 'INDEXED'
                    WHERE id = %s AND business_id = %s
                    """,
                    (document_id, business_id),
                )
        # Counts only: never the document's text.
        logger.info(
            "Indexed document %d of %s: %d chunks", document_id, business_id, len(chunks)
        )

    def on_final_failure(self, job: Job) -> None:
        """Mark this version FAILED (the panel shows it; a later save or reindex retries)."""
        with self._connect() as conn:
            conn.execute(
                """
                UPDATE agent.documents SET status = 'FAILED'
                WHERE id = %s AND business_id = %s AND content_hash = %s
                """,
                (
                    job.payload["document_id"],
                    job.payload["business_id"],
                    job.payload["content_hash"],
                ),
            )
