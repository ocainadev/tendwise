"""Panel knowledge base routes (T50): `/api/v1/panel/{slug}/documents`.

The real app with a real PostgreSQL + pgvector (needs Docker) and a fake JWKS
endpoint (tests/auth/auth_fakes.py): tokens are signed here, so nothing calls
business-service. The embedder is a fake.
"""

from pathlib import Path

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.auth.panel import ACCESS_COOKIE, CSRF_COOKIE, CSRF_HEADER, CSRF_INVALID
from app.core.db import connection_factory
from app.main import create_app
from app.rag import documents
from app.rag.ingest import ingest_all
from tests.auth.auth_fakes import FakeJwksServer, SigningKey, claims, make_cache
from tests.rag_helpers import BagOfWordsEmbedder

KEY = SigningKey("key-1")
CSRF = "csrf-token-value"
BASE = "/api/v1/panel/joes-pizza/documents"
OWNER = [{"slug": "joes-pizza", "role": "OWNER"}]
AGENT = [{"slug": "joes-pizza", "role": "AGENT"}]
NEW_DOCUMENT = {"title": "Opening hours", "content": "# Opening hours\n\nEvery day 11:00-23:00."}


@pytest.fixture
def client(conn: psycopg.Connection, businesses_dir: Path, database_url: str) -> TestClient:
    """The app on a database seeded with both demo businesses."""
    ingest_all(conn, businesses_dir, BagOfWordsEmbedder())
    conn.commit()
    app = create_app()
    app.state.jwks_cache = make_cache(FakeJwksServer(KEY))
    app.state.connect_db = connection_factory(database_url)
    return TestClient(app)


def log_in(client: TestClient, memberships: list[dict[str, str]]) -> None:
    """Set the session cookies business-service would set at login."""
    client.cookies.set(ACCESS_COOKIE, KEY.sign(claims(memberships)))
    client.cookies.set(CSRF_COOKIE, CSRF)


WRITE = {CSRF_HEADER: CSRF}


def document_id(conn: psycopg.Connection, source_path: str) -> int:
    row = conn.execute(
        "SELECT id FROM agent.documents WHERE source_path = %s", (source_path,)
    ).fetchone()
    assert row is not None
    return row[0]


def job_count(conn: psycopg.Connection) -> int:
    row = conn.execute("SELECT count(*) FROM agent.jobs").fetchone()
    assert row is not None
    return row[0]


# --- reading ----------------------------------------------------------------


@pytest.mark.parametrize("memberships", [OWNER, AGENT])
def test_staff_list_their_business_documents_without_the_text(
    client: TestClient, memberships: list[dict[str, str]]
) -> None:
    log_in(client, memberships)

    response = client.get(BASE)

    assert response.status_code == 200
    body = response.json()
    assert sorted(d["sourcePath"] for d in body) == [
        "joes-pizza/catalog.md",
        "joes-pizza/faq.md",
        "joes-pizza/policies.md",
    ]
    first = body[0]
    assert set(first) == {
        "id",
        "sourcePath",
        "title",
        "source",
        "status",
        "characters",
        "updatedAt",
        "updatedBy",
    }
    assert (first["source"], first["status"], first["updatedBy"]) == ("FILE", "INDEXED", None)


def test_an_agent_reads_one_document_with_its_text(
    client: TestClient, conn: psycopg.Connection, businesses_dir: Path
) -> None:
    log_in(client, AGENT)
    faq = document_id(conn, "joes-pizza/faq.md")

    response = client.get(f"{BASE}/{faq}")

    assert response.status_code == 200
    body = response.json()
    assert body["title"] == "Joe's Pizza – Frequently asked questions"
    assert body["content"] == (businesses_dir / "joes-pizza" / "faq.md").read_text()
    assert body["characters"] == len(body["content"])


def test_without_a_session_the_routes_answer_401(client: TestClient) -> None:
    assert client.get(BASE).status_code == 401


# --- tenant isolation -------------------------------------------------------


def test_a_business_the_user_does_not_belong_to_is_not_found(client: TestClient) -> None:
    log_in(client, OWNER)

    response = client.get("/api/v1/panel/denim-corner/documents")

    assert response.status_code == 404
    assert response.json() == {"detail": "Business not found"}


def test_a_document_of_another_business_is_not_found(
    client: TestClient, conn: psycopg.Connection
) -> None:
    log_in(client, OWNER)
    denim_faq = document_id(conn, "denim-corner/faq.md")
    before = documents.get_document(conn, "denim-corner", denim_faq)

    responses = [
        client.get(f"{BASE}/{denim_faq}"),
        client.put(f"{BASE}/{denim_faq}", json=NEW_DOCUMENT, headers=WRITE),
        client.delete(f"{BASE}/{denim_faq}", headers=WRITE),
    ]

    for response in responses:
        assert response.status_code == 404
        assert response.json() == {"detail": "Document not found"}
    conn.rollback()  # see the latest committed data
    assert documents.get_document(conn, "denim-corner", denim_faq) == before
    assert job_count(conn) == 0


def test_reindex_touches_only_the_business_in_the_path(
    client: TestClient, conn: psycopg.Connection
) -> None:
    log_in(client, OWNER)

    response = client.post(f"{BASE}/reindex", headers=WRITE)

    assert response.status_code == 202
    assert response.json() == {"queued": 3}
    rows = conn.execute(
        "SELECT business_id, status FROM agent.documents GROUP BY business_id, status"
    ).fetchall()
    assert sorted(rows) == [("denim-corner", "INDEXED"), ("joes-pizza", "PENDING")]


# --- writing ----------------------------------------------------------------


def test_an_owner_creates_a_document_that_waits_for_indexing(
    client: TestClient, conn: psycopg.Connection
) -> None:
    log_in(client, OWNER)

    response = client.post(BASE, json=NEW_DOCUMENT, headers=WRITE)

    assert response.status_code == 201
    body = response.json()
    assert body["sourcePath"] == "joes-pizza/opening-hours.md"
    assert (body["title"], body["content"]) == (NEW_DOCUMENT["title"], NEW_DOCUMENT["content"])
    assert (body["source"], body["status"], body["updatedBy"]) == ("EDITOR", "PENDING", 42)
    assert job_count(conn) == 1
    assert client.get(f"{BASE}/{body['id']}").json()["content"] == NEW_DOCUMENT["content"]


def test_names_come_from_the_title_and_never_collide(client: TestClient) -> None:
    log_in(client, OWNER)

    first = client.post(BASE, json={"title": "Horário & Entrega", "content": "a"}, headers=WRITE)
    second = client.post(BASE, json={"title": "horario entrega", "content": "b"}, headers=WRITE)

    assert first.json()["sourcePath"] == "joes-pizza/horario-entrega.md"
    assert second.json()["sourcePath"] == "joes-pizza/horario-entrega-2.md"


def test_editing_a_seed_document_makes_it_an_editor_document(
    client: TestClient, conn: psycopg.Connection
) -> None:
    log_in(client, OWNER)
    faq = document_id(conn, "joes-pizza/faq.md")

    response = client.put(f"{BASE}/{faq}", json=NEW_DOCUMENT, headers=WRITE)

    assert response.status_code == 200
    body = response.json()
    assert body["sourcePath"] == "joes-pizza/faq.md"  # the name stays
    assert (body["source"], body["status"], body["updatedBy"]) == ("EDITOR", "PENDING", 42)
    assert body["content"] == NEW_DOCUMENT["content"]
    assert job_count(conn) == 1


def test_an_owner_deletes_a_document_and_its_chunks(
    client: TestClient, conn: psycopg.Connection
) -> None:
    log_in(client, OWNER)
    faq = document_id(conn, "joes-pizza/faq.md")

    response = client.delete(f"{BASE}/{faq}", headers=WRITE)

    assert response.status_code == 204
    assert client.get(f"{BASE}/{faq}").status_code == 404
    row = conn.execute("SELECT count(*) FROM agent.chunks WHERE document_id = %s", (faq,))
    assert row.fetchone() == (0,)


def test_an_unknown_document_is_not_found(client: TestClient) -> None:
    log_in(client, OWNER)

    assert client.get(f"{BASE}/999999").status_code == 404
    assert client.put(f"{BASE}/999999", json=NEW_DOCUMENT, headers=WRITE).status_code == 404
    assert client.delete(f"{BASE}/999999", headers=WRITE).status_code == 404


# --- roles and CSRF ---------------------------------------------------------


def test_an_agent_cannot_write(client: TestClient, conn: psycopg.Connection) -> None:
    log_in(client, AGENT)
    faq = document_id(conn, "joes-pizza/faq.md")

    responses = [
        client.post(BASE, json=NEW_DOCUMENT, headers=WRITE),
        client.put(f"{BASE}/{faq}", json=NEW_DOCUMENT, headers=WRITE),
        client.delete(f"{BASE}/{faq}", headers=WRITE),
        client.post(f"{BASE}/reindex", headers=WRITE),
    ]

    for response in responses:
        assert response.status_code == 403
        assert response.json() == {"detail": "Access denied."}
    assert job_count(conn) == 0
    assert documents.get_document(conn, "joes-pizza", faq) is not None


@pytest.mark.parametrize("headers", [{}, {CSRF_HEADER: "wrong"}])
def test_writes_need_the_csrf_header(
    client: TestClient, conn: psycopg.Connection, headers: dict[str, str]
) -> None:
    log_in(client, OWNER)
    faq = document_id(conn, "joes-pizza/faq.md")

    responses = [
        client.post(BASE, json=NEW_DOCUMENT, headers=headers),
        client.put(f"{BASE}/{faq}", json=NEW_DOCUMENT, headers=headers),
        client.delete(f"{BASE}/{faq}", headers=headers),
        client.post(f"{BASE}/reindex", headers=headers),
    ]

    for response in responses:
        assert response.status_code == 403
        assert response.json() == {"detail": CSRF_INVALID}
    assert job_count(conn) == 0
    assert len(client.get(BASE).json()) == 3  # reads need no CSRF header


# --- limits -----------------------------------------------------------------


@pytest.mark.parametrize(
    "body",
    [
        {"title": "  ", "content": "text"},
        {"title": "x" * (documents.MAX_TITLE_CHARACTERS + 1), "content": "text"},
        {"title": "Empty", "content": ""},
        {"title": "Too long", "content": "x" * (documents.MAX_DOCUMENT_CHARACTERS + 1)},
    ],
)
def test_a_bad_title_or_text_is_rejected(
    client: TestClient, conn: psycopg.Connection, body: dict[str, str]
) -> None:
    log_in(client, OWNER)

    assert client.post(BASE, json=body, headers=WRITE).status_code == 422
    assert job_count(conn) == 0


def test_a_business_cannot_go_over_its_document_count(
    client: TestClient, conn: psycopg.Connection, monkeypatch: pytest.MonkeyPatch
) -> None:
    log_in(client, OWNER)
    monkeypatch.setattr(documents, "MAX_DOCUMENTS_PER_BUSINESS", 4)

    assert client.post(BASE, json=NEW_DOCUMENT, headers=WRITE).status_code == 201
    response = client.post(BASE, json=NEW_DOCUMENT, headers=WRITE)

    assert response.status_code == 422
    assert response.json() == {"detail": documents.TOO_MANY_DOCUMENTS}
    assert job_count(conn) == 1


def test_a_business_cannot_go_over_its_total_text(
    client: TestClient, conn: psycopg.Connection, monkeypatch: pytest.MonkeyPatch
) -> None:
    log_in(client, OWNER)
    seeded = sum(d["characters"] for d in client.get(BASE).json())
    monkeypatch.setattr(documents, "MAX_BUSINESS_CHARACTERS", seeded + 10)
    faq = document_id(conn, "joes-pizza/faq.md")

    too_much = client.post(BASE, json={"title": "Big", "content": "x" * 11}, headers=WRITE)
    # Replacing a document only counts the difference.
    shorter = client.put(f"{BASE}/{faq}", json=NEW_DOCUMENT, headers=WRITE)

    assert too_much.status_code == 422
    assert too_much.json() == {"detail": documents.TOO_MUCH_TEXT}
    assert shorter.status_code == 200


def test_an_unreachable_database_answers_503(client: TestClient) -> None:
    def database_down() -> psycopg.Connection:
        raise psycopg.OperationalError("connection to secret-host failed")

    client.app.state.connect_db = database_down  # type: ignore[attr-defined]
    log_in(client, OWNER)

    response = client.get(BASE)

    assert response.status_code == 503
    assert "secret-host" not in response.text
