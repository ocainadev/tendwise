"""Knowledge gaps (T58): storing, grouping, and the panel route.

Storing and the route use a real PostgreSQL + pgvector (needs Docker), the
fake bag-of-words embedder, and a fake JWKS endpoint (no business-service).
"""

from datetime import UTC, datetime, timedelta
from pathlib import Path

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.auth.panel import ACCESS_COOKIE
from app.core.db import connection_factory
from app.main import create_app
from app.rag.ingest import ingest_all
from app.rag.knowledge_gaps import Gap, PostgresGapRecorder, group_gaps
from tests.auth.auth_fakes import FakeJwksServer, SigningKey, claims, make_cache
from tests.rag_helpers import BagOfWordsEmbedder

KEY = SigningKey("key-1")
BASE = "/api/v1/panel/joes-pizza/knowledge-gaps"
NOW = datetime(2026, 11, 16, 12, 0, tzinfo=UTC)


# --- grouping (no database) -------------------------------------------------


def gap(question: str, vector: list[float], minutes_ago: int = 0) -> Gap:
    return Gap(question, vector, NOW - timedelta(minutes=minutes_ago))


def test_grouping_merges_near_duplicates_and_keeps_different_questions_apart() -> None:
    gaps = [  # newest first, as the database returns them
        gap("Do you deliver to Brooklyn?", [1.0, 0.0, 0.0], 1),
        gap("Do you have vegan cheese?", [0.0, 1.0, 0.0], 2),
        gap("do you deliver in brooklyn", [0.95, 0.1, 0.0], 3),
        gap("Deliver to Brooklyn??", [0.9, 0.0, 0.2], 4),
        gap("Do you deliver to Brooklyn?", [1.0, 0.0, 0.0], 5),
    ]

    groups = group_gaps(gaps)

    assert [(g.representative, g.count) for g in groups] == [
        ("Do you deliver to Brooklyn?", 4),
        ("Do you have vegan cheese?", 1),
    ]
    delivery = groups[0]
    assert delivery.last_asked == NOW - timedelta(minutes=1)
    # Other wordings only, each once.
    assert delivery.examples == ["do you deliver in brooklyn", "Deliver to Brooklyn??"]
    assert groups[1].examples == []


def test_grouping_with_the_embedder_merges_rewordings() -> None:
    embedder = BagOfWordsEmbedder()
    questions = [
        "Do you deliver to Brooklyn?",
        "Do you deliver to Brooklyn please?",
        "Is there a gluten free crust?",
    ]
    vectors = embedder.embed(questions)

    groups = group_gaps([gap(q, v) for q, v in zip(questions, vectors, strict=True)])

    assert [g.count for g in groups] == [2, 1]


# --- storing ----------------------------------------------------------------


def stored_gaps(conn: psycopg.Connection) -> list[tuple]:
    return conn.execute(
        "SELECT business_id, question, best_similarity FROM agent.knowledge_gaps ORDER BY id"
    ).fetchall()


def test_recorder_masks_phones_and_emails_and_stores_the_best_similarity(
    conn: psycopg.Connection, businesses_dir: Path, database_url: str
) -> None:
    ingest_all(conn, businesses_dir, BagOfWordsEmbedder())
    conn.commit()
    recorder = PostgresGapRecorder(BagOfWordsEmbedder(), connection_factory(database_url))

    recorder.record("joes-pizza", "Can you call me at 11 98765-4321 or ana.souza@gmail.com?")

    [(business_id, question, best_similarity)] = stored_gaps(conn)
    assert business_id == "joes-pizza"
    assert question == "Can you call me at ****4321 or ****@gmail.com?"
    assert best_similarity is not None and best_similarity < 1


def test_recorder_stores_no_similarity_for_a_business_without_chunks(
    conn: psycopg.Connection, database_url: str
) -> None:
    recorder = PostgresGapRecorder(BagOfWordsEmbedder(), connection_factory(database_url))

    recorder.record("new-shop", "Do you open on Sundays?")

    assert stored_gaps(conn) == [("new-shop", "Do you open on Sundays?", None)]


# --- the panel route --------------------------------------------------------


@pytest.fixture
def client(database_url: str) -> TestClient:
    app = create_app()
    app.state.jwks_cache = make_cache(FakeJwksServer(KEY))
    app.state.connect_db = connection_factory(database_url)
    return TestClient(app)


def log_in(client: TestClient, slug: str = "joes-pizza", role: str = "OWNER") -> None:
    client.cookies.set(ACCESS_COOKIE, KEY.sign(claims([{"slug": slug, "role": role}])))


def add_gap(conn: psycopg.Connection, business_id: str, question: str, at: datetime) -> None:
    vector = BagOfWordsEmbedder().embed([question])[0]
    conn.execute(
        "INSERT INTO agent.knowledge_gaps (business_id, question, embedding, created_at)"
        " VALUES (%s, %s, %s::vector, %s)",
        (business_id, question, str(vector), at),
    )
    conn.commit()


def test_owner_gets_the_grouped_gaps_of_the_period(
    client: TestClient, conn: psycopg.Connection
) -> None:
    add_gap(conn, "joes-pizza", "Do you deliver to Brooklyn?", datetime(2026, 11, 10, tzinfo=UTC))
    add_gap(
        conn, "joes-pizza", "Do you deliver to Brooklyn please?", datetime(2026, 11, 11, tzinfo=UTC)
    )
    add_gap(conn, "joes-pizza", "Is there a gluten free crust?", datetime(2026, 11, 9, tzinfo=UTC))
    add_gap(conn, "joes-pizza", "Too old to show", datetime(2026, 10, 1, tzinfo=UTC))
    log_in(client)

    response = client.get(BASE, params={"from": "2026-11-01", "to": "2026-11-11"})

    assert response.status_code == 200
    assert response.json() == [
        {
            "question": "Do you deliver to Brooklyn please?",
            "count": 2,
            "lastAsked": "2026-11-11T00:00:00Z",
            "examples": ["Do you deliver to Brooklyn?"],
        },
        {
            "question": "Is there a gluten free crust?",
            "count": 1,
            "lastAsked": "2026-11-09T00:00:00Z",
            "examples": [],
        },
    ]


def test_gaps_of_another_business_never_show(client: TestClient, conn: psycopg.Connection) -> None:
    add_gap(conn, "bella-moda", "Do you ship to Lisbon?", datetime.now(UTC))
    log_in(client)

    assert client.get(BASE).json() == []


def test_a_member_of_another_business_gets_404(
    client: TestClient, conn: psycopg.Connection
) -> None:
    add_gap(conn, "joes-pizza", "Do you deliver to Brooklyn?", datetime.now(UTC))
    log_in(client, slug="bella-moda")

    assert client.get(BASE).status_code == 404


def test_an_agent_cannot_read_the_gaps(client: TestClient) -> None:
    log_in(client, role="AGENT")

    assert client.get(BASE).status_code == 403


def test_from_after_to_is_rejected(client: TestClient, conn: psycopg.Connection) -> None:
    log_in(client)

    response = client.get(BASE, params={"from": "2026-11-12", "to": "2026-11-01"})

    assert response.status_code == 422
