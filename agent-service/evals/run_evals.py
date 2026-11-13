"""Run the eval dataset through the real agent and write a Markdown report.

Run from agent-service/, with the stack up (docker compose up) and the data ingested:
    uv run python evals/run_evals.py --env-file ../.env
    uv run python evals/run_evals.py --env-file ../.env --model claude-sonnet-5-5

It needs ANTHROPIC_API_KEY, BUSINESS_SERVICE_API_KEY and DATABASE_URL (see the
"Evals" section of agent-service/README.md). Every case is a new conversation
with the real AgentService: real LLM, pgvector retrieval, business-service
tools and Postgres memory, so the run also writes turns and handoffs to the
database, like real customers would.

The exit code is 0 even when targets are missed (the report says so); it is
non-zero only when the run itself could not happen.
"""

import argparse
import logging
import os
import sys
import time
from collections.abc import Callable
from datetime import date
from pathlib import Path
from uuid import uuid4

# `python evals/run_evals.py` puts evals/ (not agent-service/) on the import
# path, so add agent-service/ to be able to import `app` and `evals`.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import anthropic  # noqa: E402
from pydantic import ValidationError  # noqa: E402

from app.agent.service import AgentService  # noqa: E402
from app.core.config import Settings  # noqa: E402
from app.gateways.business_gateway import BusinessInfo  # noqa: E402
from evals.dataset import DATASET_PATH, EvalCase, load_dataset  # noqa: E402
from evals.report import format_console_summary, format_report  # noqa: E402
from evals.scoring import CaseOutcome, CaseScore, outcome_from_turn, score_case, summarize  # noqa: E402

RESULTS_DIR = Path(__file__).parent / "results"
# Error messages are cut in the report: the type and the start are enough.
MAX_ERROR_CHARS = 120

# Exit codes: the setup is broken (missing or rejected key) / it ran but every case failed.
EXIT_CONFIG_ERROR = 2
EXIT_ALL_CASES_FAILED = 1


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--model", help="LLM model to evaluate (default: LLM_MODEL, i.e. claude-haiku-5-5)"
    )
    parser.add_argument(
        "--env-file",
        type=Path,
        help="KEY=VALUE file to read, e.g. ../.env; variables already set win",
    )
    parser.add_argument("--dataset", type=Path, default=DATASET_PATH)
    parser.add_argument("--output-dir", type=Path, default=RESULTS_DIR)
    parser.add_argument("--limit", type=int, help="only run the first N cases (smoke test)")
    return parser.parse_args(argv)


def load_env_file(path: Path) -> None:
    """Copy KEY=VALUE lines into the environment, without overriding non-empty variables.

    A tiny reader on purpose (no new dependency): it skips comments and blank
    lines and strips optional quotes, which is all our `.env` uses.
    """
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip().strip("'\"")
        if value and not os.environ.get(key):
            os.environ[key] = value


def missing_configuration(settings: Settings) -> list[str]:
    """The variables the eval needs that are not set (DATABASE_URL is checked by Settings)."""
    missing = []
    if settings.anthropic_api_key is None or not settings.anthropic_api_key.get_secret_value():
        missing.append("ANTHROPIC_API_KEY")
    if settings.business_service_api_key is None:
        missing.append("BUSINESS_SERVICE_API_KEY")
    return missing


def load_settings(model: str | None) -> Settings | None:
    """The settings for the run, or None (after printing why) if something is missing.

    This runs before anything slow, so a missing key fails in a second instead
    of after loading the embedding model.
    """
    try:
        settings = Settings()
    except ValidationError as error:
        names = ", ".join(str(detail["loc"][0]).upper() for detail in error.errors())
        print(f"Cannot run the evals: missing or invalid {names}.", file=sys.stderr)
        return None
    missing = missing_configuration(settings)
    if missing:
        print(
            f"Cannot run the evals: {', '.join(missing)} is not set. Set it in the "
            "environment or pass --env-file ../.env (see agent-service/README.md, Evals).",
            file=sys.stderr,
        )
        return None
    if model:
        settings = settings.model_copy(update={"llm_model": model})
    return settings


def run_case(service: AgentService, business: BusinessInfo | None, case: EvalCase) -> CaseOutcome:
    """One case = one message in a brand-new conversation, so no case sees another's memory."""
    if business is None:
        return CaseOutcome(error=f"unknown business {case.business!r}")
    started = time.monotonic()
    try:
        result = service.handle_turn(uuid4(), business, case.input)
    except anthropic.AuthenticationError:
        # A rejected key is a broken setup, not a failed case: stop the whole run.
        raise
    except Exception as error:  # noqa: BLE001 - one broken case must not stop the run
        message = f"{type(error).__name__}: {error}"[:MAX_ERROR_CHARS]
        return CaseOutcome(error=message, latency_seconds=time.monotonic() - started)
    return outcome_from_turn(result, latency_seconds=time.monotonic() - started)


def run_cases(
    service: AgentService,
    get_business: Callable[[str], BusinessInfo | None],
    cases: list[EvalCase],
) -> list[CaseScore]:
    businesses: dict[str, BusinessInfo | None] = {}
    scores = []
    for number, case in enumerate(cases, start=1):
        if case.business not in businesses:
            businesses[case.business] = get_business(case.business)
        score = score_case(case, run_case(service, businesses[case.business], case))
        status = "ok" if not score.failed_checks else "FAILED " + ", ".join(score.failed_checks)
        print(f"[{number}/{len(cases)}] {case.id}: {status}", flush=True)
        scores.append(score)
    return scores


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s")
    if args.env_file:
        load_env_file(args.env_file)

    settings = load_settings(args.model)
    if settings is None:
        return EXIT_CONFIG_ERROR

    cases = load_dataset(args.dataset)[: args.limit]

    # Imported here so that a missing key fails before these heavy imports.
    from app.agent.service import create_agent_service
    from app.gateways.business_gateway import HttpBusinessGateway

    print(f"Running {len(cases)} cases with {settings.llm_model}...", flush=True)
    service = create_agent_service(settings)
    gateway = HttpBusinessGateway.from_settings(settings)
    try:
        scores = run_cases(service, gateway.get_business, cases)
    except anthropic.AuthenticationError:
        print("Cannot run the evals: the LLM provider rejected ANTHROPIC_API_KEY.", file=sys.stderr)
        return EXIT_CONFIG_ERROR
    summary = summarize(scores)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    today = date.today()
    report_path = args.output_dir / f"{settings.llm_model}-{today.isoformat()}.md"
    report_path.write_text(
        format_report(summary, scores, settings.llm_model, today), encoding="utf-8"
    )
    print()
    print(format_console_summary(summary, settings.llm_model))
    print(f"Report: {report_path}")

    if summary.errors == summary.cases:
        print("Every case raised an error: nothing was measured.", file=sys.stderr)
        return EXIT_ALL_CASES_FAILED
    return 0


if __name__ == "__main__":
    sys.exit(main())
