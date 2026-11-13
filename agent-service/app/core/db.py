"""Database access helpers (PostgreSQL via psycopg 3)."""

import psycopg

# Fail fast instead of hanging the health check when the database is down.
CONNECT_TIMEOUT_SECONDS = 3


def check_database(database_url: str) -> None:
    """Run `SELECT 1` to prove the database is reachable.

    Raises `psycopg.Error` when it is not.
    """
    with psycopg.connect(database_url, connect_timeout=CONNECT_TIMEOUT_SECONDS) as conn:
        conn.execute("SELECT 1")
