"""Database access helpers (PostgreSQL via psycopg 3)."""

from collections.abc import Callable

import psycopg

# Fail fast instead of hanging the health check when the database is down.
CONNECT_TIMEOUT_SECONDS = 3

# Something that opens a new database connection when called. Code that needs
# the database (e.g. the retriever) takes one of these instead of a URL, so
# tests can hand it a connection to their own test database.
ConnectionFactory = Callable[[], psycopg.Connection]


def check_database(database_url: str) -> None:
    """Run `SELECT 1` to prove the database is reachable.

    Raises `psycopg.Error` when it is not.
    """
    with psycopg.connect(database_url, connect_timeout=CONNECT_TIMEOUT_SECONDS) as conn:
        conn.execute("SELECT 1")


def connection_factory(database_url: str) -> ConnectionFactory:
    """Return a function that opens a new connection to `database_url` on each call."""

    def connect() -> psycopg.Connection:
        return psycopg.connect(database_url, connect_timeout=CONNECT_TIMEOUT_SECONDS)

    return connect
