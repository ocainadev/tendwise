import pytest

from app.core.config import Settings, get_settings


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
