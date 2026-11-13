"""A tiny migration runner for the `agent` schema.

SQL files in `agent-service/migrations/` run once each, in file-name order
(`001_...sql`, `002_...sql`). Applied file names are stored in
`agent.schema_migrations`, so running the runner again does nothing.
"""

from pathlib import Path

import psycopg

MIGRATIONS_DIR = Path(__file__).resolve().parents[2] / "migrations"

# Any fixed number works: it names the lock that keeps two processes (the app
# and the ingest script) from running migrations at the same time.
MIGRATIONS_LOCK_ID = 7_001


def apply_migrations(conn: psycopg.Connection, migrations_dir: Path = MIGRATIONS_DIR) -> list[str]:
    """Apply the pending migration files and return their names.

    Everything runs in one transaction: if a file fails, nothing is applied.
    """
    applied_now: list[str] = []
    with conn.transaction():
        # Waits here if another process is migrating; released at commit.
        conn.execute("SELECT pg_advisory_xact_lock(%s)", (MIGRATIONS_LOCK_ID,))
        conn.execute("CREATE SCHEMA IF NOT EXISTS agent")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS agent.schema_migrations (
                filename   TEXT PRIMARY KEY,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """
        )
        rows = conn.execute("SELECT filename FROM agent.schema_migrations").fetchall()
        already_applied = {row[0] for row in rows}

        for path in sorted(migrations_dir.glob("*.sql")):
            if path.name in already_applied:
                continue
            # A query without parameters may hold several statements.
            conn.execute(path.read_text(encoding="utf-8"))
            conn.execute("INSERT INTO agent.schema_migrations (filename) VALUES (%s)", (path.name,))
            applied_now.append(path.name)
    return applied_now
