import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.api import admin
from app.core.config import Settings, get_settings
from app.main import create_app
from app.rag.ingest import IngestSummary

ADMIN_KEY = "test-admin-key"


@pytest.fixture
def calls(monkeypatch: pytest.MonkeyPatch) -> list[Settings]:
    """Replace the real ingestion with a fake that records its calls."""
    recorded: list[Settings] = []

    def fake_run_ingestion(settings: Settings) -> IngestSummary:
        recorded.append(settings)
        return IngestSummary(added=2, updated=1, skipped=3, deleted=0)

    monkeypatch.setattr(admin, "run_ingestion", fake_run_ingestion)
    return recorded


def make_client(settings: Settings) -> TestClient:
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    return TestClient(app)


@pytest.fixture
def client(settings: Settings) -> TestClient:
    settings.admin_api_key = SecretStr(ADMIN_KEY)
    return make_client(settings)


def test_ingest_with_the_right_key_returns_the_summary(
    client: TestClient, calls: list[Settings]
) -> None:
    response = client.post("/api/v1/admin/ingest", headers={"X-Admin-Key": ADMIN_KEY})

    assert response.status_code == 200
    assert response.json() == {"added": 2, "updated": 1, "skipped": 3, "deleted": 0}
    assert len(calls) == 1


def test_ingest_without_key_returns_401(client: TestClient, calls: list[Settings]) -> None:
    response = client.post("/api/v1/admin/ingest")

    assert response.status_code == 401
    assert calls == []


def test_ingest_with_wrong_key_returns_401(client: TestClient, calls: list[Settings]) -> None:
    response = client.post("/api/v1/admin/ingest", headers={"X-Admin-Key": "wrong-key"})

    assert response.status_code == 401
    assert calls == []


def test_ingest_returns_503_when_no_admin_key_is_configured(
    settings: Settings, calls: list[Settings]
) -> None:
    # The `settings` fixture has no ADMIN_API_KEY.
    response = make_client(settings).post(
        "/api/v1/admin/ingest", headers={"X-Admin-Key": "anything"}
    )

    assert response.status_code == 503
    assert calls == []
