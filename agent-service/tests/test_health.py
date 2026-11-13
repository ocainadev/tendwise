from collections.abc import Iterator

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.api import health
from app.core.config import Settings, get_settings
from app.main import create_app


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    with TestClient(app) as test_client:
        yield test_client


def test_health_returns_200_when_database_is_up(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(health, "check_database", lambda url: calls.append(url))

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "database": "ok"}
    assert calls == ["postgresql://test:secret-password@db-host:5432/test"]


def test_health_returns_503_when_database_is_down(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    def failing_check(url: str) -> None:
        raise psycopg.OperationalError("connection to db-host failed for user test")

    monkeypatch.setattr(health, "check_database", failing_check)

    response = client.get("/health")

    assert response.status_code == 503
    assert response.json() == {"status": "error", "database": "error"}


def test_health_never_leaks_connection_details(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    def failing_check(url: str) -> None:
        raise psycopg.OperationalError(f"could not connect using {url}")

    monkeypatch.setattr(health, "check_database", failing_check)

    response = client.get("/health")

    for text in (response.text, caplog.text):
        assert "secret-password" not in text
        assert "db-host" not in text
    assert "OperationalError" in caplog.text
