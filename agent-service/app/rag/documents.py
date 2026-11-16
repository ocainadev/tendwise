"""The knowledge base documents of one business, as the panel edits them (T50).

Every function takes the business slug and every query filters by it, so a
document id of another business behaves exactly like an id that does not exist.

A save is instant: it stores the text with status PENDING and enqueues an
INDEX_DOCUMENT job in the same transaction (app/rag/indexing.py rebuilds the
chunks in the background). Until the job finishes, the retriever keeps using
the document's previous chunks, if it had any.
"""

import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime

import psycopg

from app.rag.indexing import enqueue_indexing
from app.rag.ingest import content_hash

# Limits (docs/architecture.md, "Knowledge base"). At about 800 characters per
# chunk, one full document is ~60 chunks and a full business ~600: re-embedding
# a whole business stays a matter of seconds on a laptop.
MAX_TITLE_CHARACTERS = 200
MAX_DOCUMENT_CHARACTERS = 50_000
MAX_DOCUMENTS_PER_BUSINESS = 50
MAX_BUSINESS_CHARACTERS = 500_000

# The first number of the two-number advisory lock that lets one save at a
# time change a business's documents (the second is the slug's hash), so two
# parallel saves cannot both pass the limits.
SAVE_LOCK_ID = 7_003

TOO_MANY_DOCUMENTS = f"A business can have at most {MAX_DOCUMENTS_PER_BUSINESS} documents."
TOO_MUCH_TEXT = (
    f"The documents of a business can hold at most {MAX_BUSINESS_CHARACTERS:,} characters in all."
)


class KnowledgeLimitError(ValueError):
    """A save would go over a business limit. The message is safe to show the owner."""


@dataclass(frozen=True)
class Document:
    id: int
    source_path: str  # unique name within the business, e.g. 'joes-pizza/faq.md'
    title: str
    source: str  # FILE (seed file) or EDITOR (written or edited in the panel)
    status: str  # PENDING, INDEXED or FAILED
    characters: int
    updated_at: datetime
    updated_by: int | None  # panel user id; None for seed files
    content: str | None  # None in lists, which leave the text out


SUMMARY_COLUMNS = (
    "id, source_path, title, source, status, length(content), updated_at, updated_by"
)


def list_documents(conn: psycopg.Connection, business_id: str) -> list[Document]:
    """The business's documents, without their text, by title."""
    rows = conn.execute(
        f"""
        SELECT {SUMMARY_COLUMNS}, NULL FROM agent.documents
        WHERE business_id = %s
        ORDER BY lower(title), id
        """,
        (business_id,),
    ).fetchall()
    return [Document(*row) for row in rows]


def get_document(conn: psycopg.Connection, business_id: str, document_id: int) -> Document | None:
    """One document with its text, or None (also when it belongs to another business)."""
    row = conn.execute(
        f"""
        SELECT {SUMMARY_COLUMNS}, content FROM agent.documents
        WHERE id = %s AND business_id = %s
        """,
        (document_id, business_id),
    ).fetchone()
    return Document(*row) if row else None


def create_document(
    conn: psycopg.Connection, business_id: str, title: str, content: str, user_id: int
) -> Document:
    """Store a new EDITOR document as PENDING and enqueue its indexing."""
    with conn.transaction():
        _lock_business(conn, business_id)
        count, total = _business_totals(conn, business_id)
        if count >= MAX_DOCUMENTS_PER_BUSINESS:
            raise KnowledgeLimitError(TOO_MANY_DOCUMENTS)
        if total + len(content) > MAX_BUSINESS_CHARACTERS:
            raise KnowledgeLimitError(TOO_MUCH_TEXT)

        new_hash = content_hash(content)
        row = conn.execute(
            """
            INSERT INTO agent.documents
                (business_id, source_path, title, content_hash, content, source, status,
                 updated_by)
            VALUES (%s, %s, %s, %s, %s, 'EDITOR', 'PENDING', %s)
            RETURNING id
            """,
            (
                business_id,
                _free_source_path(conn, business_id, title),
                title,
                new_hash,
                content,
                user_id,
            ),
        ).fetchone()
        assert row is not None  # RETURNING always gives a row
        enqueue_indexing(conn, business_id, row[0], new_hash)
        document = get_document(conn, business_id, row[0])
    assert document is not None
    return document


def update_document(
    conn: psycopg.Connection,
    business_id: str,
    document_id: int,
    title: str,
    content: str,
    user_id: int,
) -> Document | None:
    """Replace a document's title and text; None when it does not exist in this business.

    The document becomes EDITOR, so the startup seed never overwrites it with
    its file again. Only a change of text needs new chunks (the title is read
    from `documents` when retrieving), so only then is it PENDING and re-indexed.
    """
    with conn.transaction():
        _lock_business(conn, business_id)
        row = conn.execute(
            """
            SELECT content_hash, length(content) FROM agent.documents
            WHERE id = %s AND business_id = %s
            FOR UPDATE
            """,
            (document_id, business_id),
        ).fetchone()
        if row is None:
            return None
        old_hash, old_characters = row
        _, total = _business_totals(conn, business_id)
        if total - old_characters + len(content) > MAX_BUSINESS_CHARACTERS:
            raise KnowledgeLimitError(TOO_MUCH_TEXT)

        new_hash = content_hash(content)
        text_changed = new_hash != old_hash
        conn.execute(
            """
            UPDATE agent.documents
            SET title = %s, content = %s, content_hash = %s, source = 'EDITOR',
                updated_by = %s, updated_at = now(),
                status = CASE WHEN %s THEN 'PENDING' ELSE status END
            WHERE id = %s AND business_id = %s
            """,
            (title, content, new_hash, user_id, text_changed, document_id, business_id),
        )
        if text_changed:
            enqueue_indexing(conn, business_id, document_id, new_hash)
        return get_document(conn, business_id, document_id)


def delete_document(conn: psycopg.Connection, business_id: str, document_id: int) -> bool:
    """Delete a document and its chunks (ON DELETE CASCADE). False when it does not exist.

    A pending INDEX_DOCUMENT job for it finds nothing and does nothing.
    """
    with conn.transaction():
        row = conn.execute(
            "DELETE FROM agent.documents WHERE id = %s AND business_id = %s RETURNING id",
            (document_id, business_id),
        ).fetchone()
    return row is not None


def reindex_business(conn: psycopg.Connection, business_id: str) -> int:
    """Set every document of the business PENDING and enqueue its indexing. Returns how many."""
    with conn.transaction():
        rows = conn.execute(
            """
            UPDATE agent.documents SET status = 'PENDING'
            WHERE business_id = %s
            RETURNING id, content_hash
            """,
            (business_id,),
        ).fetchall()
        for document_id, document_hash in rows:
            enqueue_indexing(conn, business_id, document_id, document_hash)
    return len(rows)


def _lock_business(conn: psycopg.Connection, business_id: str) -> None:
    """Wait for other saves of this business; released when the transaction ends."""
    conn.execute("SELECT pg_advisory_xact_lock(%s, hashtext(%s))", (SAVE_LOCK_ID, business_id))


def _business_totals(conn: psycopg.Connection, business_id: str) -> tuple[int, int]:
    """How many documents the business has, and how many characters they hold in all."""
    row = conn.execute(
        """
        SELECT count(*), coalesce(sum(length(content)), 0) FROM agent.documents
        WHERE business_id = %s
        """,
        (business_id,),
    ).fetchone()
    assert row is not None  # an aggregate always gives a row
    return row[0], row[1]


def _free_source_path(conn: psycopg.Connection, business_id: str, title: str) -> str:
    """A unique name made from the title: 'Horário & entrega' -> '<slug>/horario-entrega.md'.

    If that name is taken, '-2', '-3', ... is added. The name never changes
    afterwards, even when the title does.
    """
    ascii_title = unicodedata.normalize("NFKD", title).encode("ascii", "ignore").decode()
    base = re.sub(r"[^a-z0-9]+", "-", ascii_title.lower()).strip("-")[:60].strip("-")
    base = base or "document"
    rows = conn.execute(
        "SELECT source_path FROM agent.documents WHERE business_id = %s", (business_id,)
    ).fetchall()
    taken = {row[0] for row in rows}
    candidate = f"{business_id}/{base}.md"
    number = 2
    while candidate in taken:
        candidate = f"{business_id}/{base}-{number}.md"
        number += 1
    return candidate
