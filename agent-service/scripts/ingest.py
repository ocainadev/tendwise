"""Re-index data/businesses into pgvector.

Run from agent-service/: `uv run python scripts/ingest.py`
(in Docker: `docker compose exec agent-service python scripts/ingest.py`).
"""

import logging
import sys
from pathlib import Path

# `python scripts/ingest.py` puts scripts/ (not agent-service/) on the import
# path, so add agent-service/ to be able to import the `app` package.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import get_settings  # noqa: E402
from app.rag.ingest import run_ingestion  # noqa: E402


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    # The first run downloads the model; hide one log line per HTTP request.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    summary = run_ingestion(get_settings())
    print(
        f"Ingestion done: added={summary.added} updated={summary.updated} "
        f"skipped={summary.skipped} deleted={summary.deleted}"
    )


if __name__ == "__main__":
    main()
