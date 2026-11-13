"""Ingestion on startup (T27): a fresh `docker compose up` must have an index.

`run_ingestion` is replaced by a fake, so no database or model is needed.
"""

import logging

import pytest
from fastapi.testclient import TestClient

from app import main
from app.api import health
from app.core.config import Settings, get_settings
from app.main import create_app, ingest_documents
from app.rag.ingest import IngestSummary


def test_ingest_documents_runs_ingestion_and_logs_the_counts(
    settings: Settings, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    calls: list[Settings] = []

    def fake_ingestion(s: Settings) -> IngestSummary:
        calls.append(s)
        return IngestSummary(added=6, updated=0, skipped=0, deleted=0)

    monkeypatch.setattr(main, "run_ingestion", fake_ingestion)

    with caplog.at_level(logging.INFO, logger="app.main"):
        ingest_documents(settings)

    assert calls == [settings]
    assert "Startup ingestion: added=6 updated=0 skipped=0 deleted=0" in caplog.text


def test_ingest_documents_only_logs_the_error_type_when_it_fails(
    settings: Settings, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    def database_down(s: Settings) -> IngestSummary:
        raise ConnectionRefusedError("secret-db-host:5432")

    monkeypatch.setattr(main, "run_ingestion", database_down)

    with caplog.at_level(logging.WARNING, logger="app.main"):
        ingest_documents(settings)  # must not raise

    assert "Startup ingestion failed: ConnectionRefusedError" in caplog.text
    assert "secret-db-host" not in caplog.text


def test_startup_runs_ingestion_and_still_starts_when_it_fails(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    order: list[str] = []

    def failing_ingestion(s: Settings) -> IngestSummary:
        order.append("ingest")
        raise FileNotFoundError("data/businesses")

    monkeypatch.setattr(main, "migrate_database", lambda: order.append("migrate"))
    monkeypatch.setattr(main, "run_ingestion", failing_ingestion)
    monkeypatch.setattr(main, "build_chat_services", lambda app, s: order.append("build"))
    monkeypatch.setattr(main, "get_settings", lambda: settings)
    monkeypatch.setattr(health, "check_database", lambda url: None)

    app = create_app(run_startup=True)
    app.dependency_overrides[get_settings] = lambda: settings
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200

    assert order == ["migrate", "ingest", "build"]
