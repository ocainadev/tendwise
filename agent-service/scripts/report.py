"""Print the turn log summary, or purge old conversations.

Run from agent-service/:
    uv run python scripts/report.py                       # every business, all time
    uv run python scripts/report.py --business joes-pizza --since 2026-11-01
    uv run python scripts/report.py --purge               # apply CONVERSATION_RETENTION_DAYS
(in Docker: `docker compose exec agent-service python scripts/report.py`).
"""

import argparse
import sys
from datetime import datetime
from pathlib import Path

# `python scripts/report.py` puts scripts/ (not agent-service/) on the import
# path, so add agent-service/ to be able to import the `app` package.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.agent.checkpointer import create_postgres_checkpointer  # noqa: E402
from app.core.config import get_settings  # noqa: E402
from app.core.db import connection_factory  # noqa: E402
from app.observability.report import build_report, format_report  # noqa: E402
from app.observability.retention import delete_old_conversations  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--business", help="only this business slug, e.g. joes-pizza")
    parser.add_argument(
        "--since",
        type=datetime.fromisoformat,
        help="only turns at or after this date/time (ISO, e.g. 2026-11-01)",
    )
    parser.add_argument(
        "--purge",
        action="store_true",
        help="delete conversations older than CONVERSATION_RETENTION_DAYS instead",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings = get_settings()
    database_url = settings.database_url.get_secret_value()
    connect = connection_factory(database_url)

    if args.purge:
        days = settings.conversation_retention_days
        deleted = delete_old_conversations(
            connect, days, checkpointer=create_postgres_checkpointer(database_url)
        )
        print(f"Deleted {deleted} conversations older than {days} days.")
        return

    print(format_report(build_report(connect, business=args.business, since=args.since)))


if __name__ == "__main__":
    main()
