import shutil
from collections.abc import Iterator
from pathlib import Path

import psycopg
import pytest
from pgvector.psycopg import register_vector
from testcontainers.community.postgres import PostgresContainer

from app.core.config import Settings, get_settings
from app.core.migrations import apply_migrations
from tests.rag_helpers import PGVECTOR_IMAGE, REPO_DATA_DIR


@pytest.fixture(autouse=True)
def clean_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """Remove every settings variable from the environment before each test.

    Otherwise a key exported in the developer's shell (e.g. ANTHROPIC_API_KEY)
    would leak into the tests and make them depend on the machine.
    """
    for field_name in Settings.model_fields:
        monkeypatch.delenv(field_name.upper(), raising=False)
    get_settings.cache_clear()


@pytest.fixture
def settings() -> Settings:
    """Settings built in code, with fake values only."""
    return Settings(database_url="postgresql://test:secret-password@db-host:5432/test")


# --- real PostgreSQL + pgvector (needs Docker) ------------------------------


@pytest.fixture(scope="session")
def database_url() -> Iterator[str]:
    """Start one pgvector container for the whole test run (only if a test asks for it)."""
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
