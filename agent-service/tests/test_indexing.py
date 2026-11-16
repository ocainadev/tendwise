"""Knowledge base in the database (T50): saves, INDEX_DOCUMENT jobs and the seed.

Real PostgreSQL + pgvector (needs Docker); the embedder is a fake
(`BagOfWordsEmbedder`, texts sharing words are close), so no model is downloaded.
The job worker is driven by hand with `run_next`.
"""

import asyncio
from datetime import timedelta
from pathlib import Path

import psycopg
import pytest

from app.core import jobs as jobs_module
from app.core.db import connection_factory
from app.core.jobs import MAX_ATTEMPTS, JobWorker, backoff
from app.rag import documents
from app.rag.indexing import INDEX_DOCUMENT, IndexDocumentHandler
from app.rag.ingest import IngestSummary, ingest_all
from app.rag.retriever import PgVectorRetriever
from tests.rag_helpers import BagOfWordsEmbedder

OWNER_ID = 42
PARKING_QUESTION = "Is there free parking for customers?"
PARKING_TEXT = (
    "# Parking\n\n## Customers\n\nFree parking for customers behind the shop, every day."
)


class BrokenEmbedder(BagOfWordsEmbedder):
    def embed(self, texts: list[str]) -> list[list[float]]:
        raise RuntimeError("model crashed while reading: secret document text")


@pytest.fixture
def seeded(conn: psycopg.Connection, businesses_dir: Path) -> None:
    """Both demo businesses seeded from their files."""
    ingest_all(conn, businesses_dir, BagOfWordsEmbedder())
    conn.commit()


def make_worker(database_url: str, embedder: BagOfWordsEmbedder | None = None) -> JobWorker:
    connect = connection_factory(database_url)
    handler = IndexDocumentHandler(connect, embedder or BagOfWordsEmbedder())
    return JobWorker(connect, {INDEX_DOCUMENT: handler})


def run_all_jobs(worker: JobWorker) -> int:
    ran = 0
    while worker.run_next():
        ran += 1
    return ran


def retriever(database_url: str) -> PgVectorRetriever:
    return PgVectorRetriever(
        embedder=BagOfWordsEmbedder(),
        connect=connection_factory(database_url),
        min_similarity=0.0,
    )


def document_id(conn: psycopg.Connection, source_path: str) -> int:
    row = conn.execute(
        "SELECT id FROM agent.documents WHERE source_path = %s", (source_path,)
    ).fetchone()
    assert row is not None
    return row[0]


def document_row(conn: psycopg.Connection, doc_id: int) -> tuple:
    row = conn.execute(
        "SELECT source, status, content, updated_by FROM agent.documents WHERE id = %s",
        (doc_id,),
    ).fetchone()
    assert row is not None
    return row


def jobs(conn: psycopg.Connection) -> list[tuple]:
    return conn.execute(
        "SELECT type, status, attempts, last_error, payload FROM agent.jobs ORDER BY id"
    ).fetchall()


def chunk_texts(conn: psycopg.Connection, doc_id: int) -> list[str]:
    rows = conn.execute(
        "SELECT content FROM agent.chunks WHERE document_id = %s ORDER BY id", (doc_id,)
    ).fetchall()
    return [row[0] for row in rows]


def chunk_ids_of_business(conn: psycopg.Connection, business_id: str) -> set[int]:
    rows = conn.execute(
        "SELECT id FROM agent.chunks WHERE business_id = %s", (business_id,)
    ).fetchall()
    return {row[0] for row in rows}


# --- seed -------------------------------------------------------------------


@pytest.mark.usefixtures("seeded")
def test_the_seed_stores_the_file_text_as_indexed_file_documents(
    conn: psycopg.Connection, businesses_dir: Path
) -> None:
    faq = document_id(conn, "joes-pizza/faq.md")

    source, status, content, updated_by = document_row(conn, faq)

    assert (source, status, updated_by) == ("FILE", "INDEXED", None)
    assert content == (businesses_dir / "joes-pizza" / "faq.md").read_text()


@pytest.mark.usefixtures("seeded")
def test_the_seed_never_overwrites_a_document_edited_in_the_panel(
    conn: psycopg.Connection, businesses_dir: Path, database_url: str
) -> None:
    faq = document_id(conn, "joes-pizza/faq.md")
    documents.update_document(conn, "joes-pizza", faq, "FAQ", PARKING_TEXT, OWNER_ID)
    conn.commit()
    run_all_jobs(make_worker(database_url))

    # The file changes too, and the service restarts.
    faq_file = businesses_dir / "joes-pizza" / "faq.md"
    faq_file.write_text(faq_file.read_text() + "\n## New section\n\nFrom the file.\n")
    summary = ingest_all(conn, businesses_dir, BagOfWordsEmbedder())
    conn.commit()

    assert summary == IngestSummary(skipped=6)
    assert document_row(conn, faq) == ("EDITOR", "INDEXED", PARKING_TEXT, OWNER_ID)
    assert all("From the file" not in text for text in chunk_texts(conn, faq))


@pytest.mark.usefixtures("seeded")
def test_the_seed_does_not_delete_documents_written_in_the_panel(
    conn: psycopg.Connection, businesses_dir: Path
) -> None:
    created = documents.create_document(conn, "joes-pizza", "Parking", PARKING_TEXT, OWNER_ID)
    faq = document_id(conn, "joes-pizza/faq.md")
    documents.update_document(conn, "joes-pizza", faq, "FAQ", PARKING_TEXT, OWNER_ID)
    conn.commit()

    # Both files are gone: only seed documents without a file are deleted.
    (businesses_dir / "joes-pizza" / "faq.md").unlink()
    (businesses_dir / "joes-pizza" / "policies.md").unlink()
    summary = ingest_all(conn, businesses_dir, BagOfWordsEmbedder())
    conn.commit()

    assert summary.deleted == 1  # policies.md only
    assert documents.get_document(conn, "joes-pizza", created.id) is not None
    assert documents.get_document(conn, "joes-pizza", faq) is not None


# --- saving and indexing ----------------------------------------------------


@pytest.mark.usefixtures("seeded")
def test_a_new_document_is_pending_until_its_job_indexes_it(
    conn: psycopg.Connection, database_url: str
) -> None:
    created = documents.create_document(conn, "joes-pizza", "Parking", PARKING_TEXT, OWNER_ID)
    conn.commit()

    assert (created.source, created.status) == ("EDITOR", "PENDING")
    assert chunk_texts(conn, created.id) == []
    [(job_type, job_status, attempts, _error, payload)] = jobs(conn)
    assert (job_type, job_status, attempts) == ("INDEX_DOCUMENT", "PENDING", 0)
    assert payload["business_id"] == "joes-pizza"
    assert payload["document_id"] == created.id

    assert run_all_jobs(make_worker(database_url)) == 1

    assert document_row(conn, created.id)[1] == "INDEXED"
    assert [row[1] for row in jobs(conn)] == ["DONE"]
    assert any("Free parking" in text for text in chunk_texts(conn, created.id))


@pytest.mark.usefixtures("seeded")
def test_editing_a_document_changes_what_retrieve_returns_for_that_business_only(
    conn: psycopg.Connection, database_url: str
) -> None:
    denim_before = chunk_ids_of_business(conn, "denim-corner")
    pizza_before = retriever(database_url).retrieve("joes-pizza", PARKING_QUESTION, top_k=50)
    assert all("Free parking" not in chunk.content for chunk in pizza_before)

    faq = document_id(conn, "joes-pizza/faq.md")
    documents.update_document(conn, "joes-pizza", faq, "FAQ", PARKING_TEXT, OWNER_ID)
    conn.commit()
    run_all_jobs(make_worker(database_url))

    best = retriever(database_url).retrieve("joes-pizza", PARKING_QUESTION)[0]
    assert "Free parking" in best.content
    assert best.source_path == "joes-pizza/faq.md"
    assert best.title == "FAQ"
    # The other business is untouched: same chunks, and no parking anywhere.
    assert chunk_ids_of_business(conn, "denim-corner") == denim_before
    denim = retriever(database_url).retrieve("denim-corner", PARKING_QUESTION, top_k=50)
    assert denim
    assert all(chunk.source_path.startswith("denim-corner/") for chunk in denim)
    assert all("Free parking" not in chunk.content for chunk in denim)


@pytest.mark.usefixtures("seeded")
def test_a_job_for_an_older_version_does_nothing(
    conn: psycopg.Connection, database_url: str
) -> None:
    created = documents.create_document(conn, "joes-pizza", "Parking", "# Old\n\nOld text.", 1)
    documents.update_document(conn, "joes-pizza", created.id, "Parking", PARKING_TEXT, 1)
    conn.commit()

    assert run_all_jobs(make_worker(database_url)) == 2

    texts = chunk_texts(conn, created.id)
    assert texts and all("Old text" not in text for text in texts)
    assert document_row(conn, created.id)[1] == "INDEXED"
    assert [row[1] for row in jobs(conn)] == ["DONE", "DONE"]


@pytest.mark.usefixtures("seeded")
def test_saving_the_same_text_again_needs_no_new_job(conn: psycopg.Connection) -> None:
    faq = document_id(conn, "joes-pizza/faq.md")
    text = document_row(conn, faq)[2]

    updated = documents.update_document(conn, "joes-pizza", faq, "New title", text, OWNER_ID)
    conn.commit()

    assert updated is not None
    assert (updated.title, updated.source, updated.status) == ("New title", "EDITOR", "INDEXED")
    assert jobs(conn) == []


@pytest.mark.usefixtures("seeded")
def test_a_job_for_a_deleted_document_is_done_with_nothing_to_do(
    conn: psycopg.Connection, database_url: str
) -> None:
    created = documents.create_document(conn, "joes-pizza", "Parking", PARKING_TEXT, OWNER_ID)
    documents.delete_document(conn, "joes-pizza", created.id)
    conn.commit()

    assert run_all_jobs(make_worker(database_url)) == 1
    assert [row[1] for row in jobs(conn)] == ["DONE"]


@pytest.mark.usefixtures("seeded")
def test_a_failing_job_is_retried_then_the_document_is_failed(
    conn: psycopg.Connection, database_url: str
) -> None:
    created = documents.create_document(conn, "joes-pizza", "Parking", PARKING_TEXT, OWNER_ID)
    conn.commit()
    worker = make_worker(database_url, BrokenEmbedder())

    assert worker.run_next()
    [(_type, job_status, attempts, last_error, _payload)] = jobs(conn)
    assert (job_status, attempts, last_error) == ("PENDING", 1, "RuntimeError")
    assert document_row(conn, created.id)[1] == "PENDING"
    assert not worker.run_next()  # the retry waits for its run_at

    for _ in range(MAX_ATTEMPTS - 1):
        conn.execute("UPDATE agent.jobs SET run_at = now()")
        conn.commit()
        assert worker.run_next()

    [(_type, job_status, attempts, last_error, _payload)] = jobs(conn)
    assert (job_status, attempts, last_error) == ("FAILED", MAX_ATTEMPTS, "RuntimeError")
    assert document_row(conn, created.id)[1] == "FAILED"

    # A reindex with a working model fixes it.
    assert documents.reindex_business(conn, "joes-pizza") == 4
    conn.commit()
    run_all_jobs(make_worker(database_url))
    assert document_row(conn, created.id)[1] == "INDEXED"


@pytest.mark.usefixtures("seeded")
def test_a_job_left_running_by_a_dead_process_runs_again(
    conn: psycopg.Connection, database_url: str
) -> None:
    created = documents.create_document(conn, "joes-pizza", "Parking", PARKING_TEXT, OWNER_ID)
    conn.execute(
        """
        UPDATE agent.jobs
        SET status = 'RUNNING', attempts = 1, locked_at = now() - interval '10 minutes'
        """
    )
    conn.commit()

    assert make_worker(database_url).run_next()

    assert [row[1:3] for row in jobs(conn)] == [("DONE", 2)]
    assert document_row(conn, created.id)[1] == "INDEXED"


@pytest.mark.usefixtures("seeded")
def test_reindex_queues_only_the_documents_of_that_business(
    conn: psycopg.Connection, database_url: str
) -> None:
    denim_before = chunk_ids_of_business(conn, "denim-corner")
    pizza_before = chunk_ids_of_business(conn, "joes-pizza")

    assert documents.reindex_business(conn, "joes-pizza") == 3
    conn.commit()
    assert {row[4]["business_id"] for row in jobs(conn)} == {"joes-pizza"}
    assert run_all_jobs(make_worker(database_url)) == 3

    statuses = conn.execute(
        "SELECT business_id, status FROM agent.documents GROUP BY business_id, status"
    ).fetchall()
    assert sorted(statuses) == [("denim-corner", "INDEXED"), ("joes-pizza", "INDEXED")]
    assert chunk_ids_of_business(conn, "denim-corner") == denim_before
    pizza_after = chunk_ids_of_business(conn, "joes-pizza")
    assert pizza_after.isdisjoint(pizza_before)  # rebuilt
    assert len(pizza_after) == len(pizza_before)


def test_retries_wait_five_seconds_then_twice_as_long() -> None:
    assert [backoff(attempt) for attempt in (1, 2, 3)] == [
        timedelta(seconds=5),
        timedelta(seconds=10),
        timedelta(seconds=20),
    ]


def test_the_worker_loop_survives_a_failed_poll_and_stops_when_cancelled(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    monkeypatch.setattr(jobs_module, "POLL_INTERVAL_SECONDS", 0)
    polls: list[str] = []

    class FlakyWorker(JobWorker):
        def run_next(self) -> bool:
            polls.append("poll")
            if len(polls) == 1:
                raise psycopg.OperationalError("connection to secret-host failed")
            if len(polls) == 3:
                raise asyncio.CancelledError  # stands in for the shutdown
            return False

    worker = FlakyWorker(connect=lambda: None, handlers={})  # type: ignore[arg-type, return-value]

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(worker.run_forever())

    assert polls == ["poll", "poll", "poll"]
    assert "Job worker poll failed: OperationalError" in caplog.text
    assert "secret-host" not in caplog.text
