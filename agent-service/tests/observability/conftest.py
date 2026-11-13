from collections.abc import Callable

import psycopg
import pytest

from app.core.db import ConnectionFactory, connection_factory
from app.core.migrations import apply_migrations


@pytest.fixture
def connect(database_url: str) -> ConnectionFactory:
    """Opens connections to a migrated database with no conversations or turns."""
    with psycopg.connect(database_url) as conn:
        apply_migrations(conn)
        conn.execute("TRUNCATE agent.conversations CASCADE")
    return connection_factory(database_url)


@pytest.fixture
def count_rows(connect: ConnectionFactory) -> Callable[[str], int]:
    """`count_rows("turns")` -> number of rows in agent.turns."""

    def count(table: str) -> int:
        with connect() as conn:
            return conn.execute(f"SELECT count(*) FROM agent.{table}").fetchone()[0]

    return count
