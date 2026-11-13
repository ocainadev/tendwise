"""Ingestion and migrations against a real PostgreSQL + pgvector (needs Docker).

testcontainers starts the same image as docker compose once for this module.
The embedder is a fake, so no model is downloaded.
"""

import hashlib
import shutil
from collections.abc import Iterator
from pathlib import Path

import psycopg
import pytest
from pgvector.psycopg import register_vector
from testcontainers.community.postgres import PostgresContainer

from app.core.config import Settings
from app.core.migrations import apply_migrations
from app.rag import ingest
from app.rag.ingest import IngestSummary, ingest_all, run_ingestion

PGVECTOR_IMAGE = "pgvector/pgvector:0.8.0-pg16"
DIMENSIONS = 384
REPO_DATA_DIR = Path(__file__).resolve().parents[2] / "data"


class FakeEmbedder:
    """Deterministic embedder: same text, same vector. Records every call."""

    dimensions = DIMENSIONS

    def __init__(self) -> None:
        self.calls: list[list[str]] = []

    def embed(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(texts)
        vectors = []
        for text in texts:
            first_byte = hashlib.sha256(text.encode()).digest()[0]
            vectors.append([1.0, first_byte / 255] + [0.0] * (DIMENSIONS - 2))
        return vectors


@pytest.fixture(scope="module")
def database_url() -> Iterator[str]:
    with PostgresContainer(PGVECTOR_IMAGE, driver=None) as postgres:
        yield postgres.get_connection_url()


@pytest.fixture
def conn(database_url: str) -> Iterator[psycopg.Connection]:
    """A connection to a migrated, empty `agent` schema."""
    with psycopg.connect(database_url) as connection:
        apply_migrations(connection)
        register_vector(connection)
        connection.execute("TRUNCATE agent.documents, agent.chunks")
        connection.commit()
        yield connection


@pytest.fixture
def businesses_dir(tmp_path: Path) -> Path:
    """A copy of the real data/businesses folder that tests may edit."""
    copy = tmp_path / "businesses"
    shutil.copytree(REPO_DATA_DIR / "businesses", copy)
    return copy


def count_chunks(conn: psycopg.Connection, business_id: str | None = None) -> int:
    if business_id is None:
        row = conn.execute("SELECT count(*) FROM agent.chunks").fetchone()
    else:
        row = conn.execute(
            "SELECT count(*) FROM agent.chunks WHERE business_id = %s", (business_id,)
        ).fetchone()
    assert row is not None
    return row[0]


def chunk_ids(conn: psycopg.Connection, source_path: str) -> set[int]:
    rows = conn.execute(
        """
        SELECT c.id FROM agent.chunks c JOIN agent.documents d ON d.id = c.document_id
        WHERE d.source_path = %s
        """,
        (source_path,),
    ).fetchall()
    return {row[0] for row in rows}


# --- migrations -------------------------------------------------------------


def test_migrations_create_the_tables_once(database_url: str) -> None:
    with psycopg.connect(database_url) as connection:
        apply_migrations(connection)  # may already be applied by another test

        assert apply_migrations(connection) == []
        rows = connection.execute(
            "SELECT filename FROM agent.schema_migrations ORDER BY filename"
        ).fetchall()
        assert [row[0] for row in rows] == ["001_documents_chunks.sql"]


def test_migrations_create_an_hnsw_index(conn: psycopg.Connection) -> None:
    row = conn.execute(
        "SELECT indexdef FROM pg_indexes WHERE indexname = 'chunks_embedding_hnsw_idx'"
    ).fetchone()

    assert row is not None
    assert "hnsw" in row[0] and "vector_cosine_ops" in row[0]


# --- ingestion --------------------------------------------------------------


def test_first_run_adds_every_file(conn: psycopg.Connection, businesses_dir: Path) -> None:
    summary = ingest_all(conn, businesses_dir, FakeEmbedder())

    assert summary == IngestSummary(added=6)
    assert count_chunks(conn, "joes-pizza") > 0
    assert count_chunks(conn, "denim-corner") > 0
    row = conn.execute(
        "SELECT title FROM agent.documents WHERE source_path = 'joes-pizza/faq.md'"
    ).fetchone()
    assert row == ("Joe's Pizza – Frequently asked questions",)


def test_second_run_creates_no_duplicate_chunks(
    conn: psycopg.Connection, businesses_dir: Path
) -> None:
    ingest_all(conn, businesses_dir, FakeEmbedder())
    chunks_after_first_run = count_chunks(conn)

    embedder = FakeEmbedder()
    summary = ingest_all(conn, businesses_dir, embedder)

    assert summary == IngestSummary(skipped=6)
    assert count_chunks(conn) == chunks_after_first_run
    assert embedder.calls == []  # unchanged files are not even embedded


def test_editing_a_file_replaces_only_its_chunks(
    conn: psycopg.Connection, businesses_dir: Path
) -> None:
    ingest_all(conn, businesses_dir, FakeEmbedder())
    faq_before = chunk_ids(conn, "joes-pizza/faq.md")
    catalog_before = chunk_ids(conn, "joes-pizza/catalog.md")

    faq = businesses_dir / "joes-pizza" / "faq.md"
    faq.write_text(faq.read_text() + "\n## Parking\n\nFree parking behind the shop.\n")
    summary = ingest_all(conn, businesses_dir, FakeEmbedder())

    assert summary == IngestSummary(updated=1, skipped=5)
    faq_after = chunk_ids(conn, "joes-pizza/faq.md")
    assert faq_after.isdisjoint(faq_before)  # old chunks deleted, new ones inserted
    assert len(faq_after) == len(faq_before) + 1
    assert chunk_ids(conn, "joes-pizza/catalog.md") == catalog_before
    row = conn.execute(
        "SELECT count(*) FROM agent.chunks WHERE content LIKE %s", ("%Free parking%",)
    ).fetchone()
    assert row == (1,)


def test_deleting_a_file_removes_its_document_and_chunks(
    conn: psycopg.Connection, businesses_dir: Path
) -> None:
    ingest_all(conn, businesses_dir, FakeEmbedder())
    policies_chunks = len(chunk_ids(conn, "denim-corner/policies.md"))
    total_before = count_chunks(conn)

    (businesses_dir / "denim-corner" / "policies.md").unlink()
    summary = ingest_all(conn, businesses_dir, FakeEmbedder())

    assert summary == IngestSummary(skipped=5, deleted=1)
    assert chunk_ids(conn, "denim-corner/policies.md") == set()
    assert count_chunks(conn) == total_before - policies_chunks
    row = conn.execute(
        "SELECT count(*) FROM agent.documents WHERE source_path = 'denim-corner/policies.md'"
    ).fetchone()
    assert row == (0,)


def test_deleting_a_business_folder_removes_all_its_documents(
    conn: psycopg.Connection, businesses_dir: Path
) -> None:
    ingest_all(conn, businesses_dir, FakeEmbedder())

    shutil.rmtree(businesses_dir / "denim-corner")
    summary = ingest_all(conn, businesses_dir, FakeEmbedder())

    assert summary == IngestSummary(skipped=3, deleted=3)
    assert count_chunks(conn, "denim-corner") == 0
    assert count_chunks(conn, "joes-pizza") > 0


def test_chunks_never_get_another_business_id(
    conn: psycopg.Connection, businesses_dir: Path
) -> None:
    ingest_all(conn, businesses_dir, FakeEmbedder())

    rows = conn.execute(
        """
        SELECT c.business_id, d.business_id, d.source_path, c.content
        FROM agent.chunks c JOIN agent.documents d ON d.id = c.document_id
        """
    ).fetchall()

    assert rows
    for chunk_business, document_business, source_path, _content in rows:
        assert chunk_business == document_business
        assert source_path.startswith(f"{chunk_business}/")
    pizza_text = " ".join(row[3] for row in rows if row[0] == "joes-pizza")
    denim_text = " ".join(row[3] for row in rows if row[0] == "denim-corner")
    assert "Denim" not in pizza_text
    assert "Pizza" not in denim_text


def test_chunks_store_vectors_and_section_metadata(
    conn: psycopg.Connection, businesses_dir: Path
) -> None:
    ingest_all(conn, businesses_dir, FakeEmbedder())

    row = conn.execute(
        """
        SELECT c.embedding, c.metadata FROM agent.chunks c
        JOIN agent.documents d ON d.id = c.document_id
        WHERE d.source_path = 'joes-pizza/faq.md' AND c.content LIKE %s
        """,
        ("%$3.99%",),
    ).fetchone()

    assert row is not None
    embedding, metadata = row
    assert len(embedding.to_list()) == DIMENSIONS
    assert metadata == {"section": "Joe's Pizza – Frequently asked questions > Delivery > Fees"}


def test_a_failing_run_changes_nothing(conn: psycopg.Connection, businesses_dir: Path) -> None:
    class BrokenEmbedder(FakeEmbedder):
        def embed(self, texts: list[str]) -> list[list[float]]:
            if any("Denim Corner" in text for text in texts):
                raise RuntimeError("model crashed")
            return super().embed(texts)

    with pytest.raises(RuntimeError):
        ingest_all(conn, businesses_dir, BrokenEmbedder())

    assert count_chunks(conn) == 0  # joes-pizza files were rolled back too


def test_run_ingestion_migrates_and_ingests(
    database_url: str, businesses_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    with psycopg.connect(database_url, autocommit=True) as connection:
        connection.execute("DROP SCHEMA IF EXISTS agent CASCADE")
    monkeypatch.setattr(ingest, "create_embedder", lambda settings: FakeEmbedder())
    settings = Settings(database_url=database_url, data_dir=str(businesses_dir.parent))

    first = run_ingestion(settings)
    second = run_ingestion(settings)

    assert first == IngestSummary(added=6)
    assert second == IngestSummary(skipped=6)


def test_run_ingestion_fails_clearly_without_data_folder(tmp_path: Path) -> None:
    settings = Settings(database_url="postgresql://unused", data_dir=str(tmp_path))

    with pytest.raises(FileNotFoundError, match="Businesses folder not found"):
        run_ingestion(settings)
