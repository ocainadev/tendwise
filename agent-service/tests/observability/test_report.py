"""Report aggregation on seeded turns (needs Docker)."""

import re
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import uuid4

import pytest

from app.core.db import ConnectionFactory
from app.observability.report import TurnReport, build_report, format_report


def seed_turn(
    connect: ConnectionFactory,
    conversation_id: object,
    business_id: str,
    latency_ms: int,
    intent: str | None,
    cost_usd: str = "0.001",
    handoff: bool = False,
    blocked: bool = False,
    days_ago: int = 0,
) -> None:
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO agent.conversations (id, business_id) VALUES (%s, %s)
            ON CONFLICT (id) DO NOTHING
            """,
            (conversation_id, business_id),
        )
        conn.execute(
            """
            INSERT INTO agent.turns
                (conversation_id, role, content, intent, latency_ms, cost_usd,
                 handoff, blocked, created_at)
            VALUES (%s, 'assistant', 'reply', %s, %s, %s, %s, %s, now() - make_interval(days => %s))
            """,
            (conversation_id, intent, latency_ms, Decimal(cost_usd), handoff, blocked, days_ago),
        )


@pytest.fixture
def seeded(connect: ConnectionFactory) -> ConnectionFactory:
    """Joe's Pizza: 2 conversations, 5 turns. Another business: 1 turn that must not count."""
    first, second = uuid4(), uuid4()
    seed_turn(connect, first, "joes-pizza", 100, "catalog_question")
    seed_turn(connect, first, "joes-pizza", 200, "catalog_question")
    seed_turn(connect, first, "joes-pizza", 300, "complaint", handoff=True)
    seed_turn(connect, second, "joes-pizza", 400, None, cost_usd="0", blocked=True)
    seed_turn(connect, second, "joes-pizza", 1_000, "order_status", cost_usd="0.002", days_ago=10)
    seed_turn(connect, uuid4(), "bella-moda", 9_000, "other", cost_usd="1")
    return connect


def test_report_aggregates_one_business(seeded: ConnectionFactory) -> None:
    report = build_report(seeded, business="joes-pizza")

    assert report.turns == 5
    assert report.conversations == 2
    assert report.latency_p50_ms == 300
    # Linear interpolation between 400 and 1000 at 80%: 400 + 0.8 * 600.
    assert report.latency_p95_ms == pytest.approx(880)
    assert report.total_cost_usd == Decimal("0.005")
    assert report.avg_cost_per_conversation_usd == Decimal("0.0025")
    assert report.intent_counts == {
        "catalog_question": 2,
        "complaint": 1,
        "none": 1,
        "order_status": 1,
    }
    assert report.handoff_rate == pytest.approx(0.2)
    assert report.blocked_rate == pytest.approx(0.2)


def test_without_a_business_every_business_counts(seeded: ConnectionFactory) -> None:
    report = build_report(seeded)

    assert report.turns == 6
    assert report.conversations == 3


def test_since_keeps_only_recent_turns(seeded: ConnectionFactory) -> None:
    report = build_report(
        seeded, business="joes-pizza", since=datetime.now(UTC) - timedelta(days=1)
    )

    assert report.turns == 4
    assert "order_status" not in report.intent_counts


def test_no_turns_gives_an_empty_report(connect: ConnectionFactory) -> None:
    report = build_report(connect)

    assert report.turns == 0
    assert report.latency_p95_ms == 0.0
    assert report.avg_cost_per_conversation_usd == 0
    assert report.intent_counts == {}


def test_format_report_prints_one_metric_per_line() -> None:
    report = TurnReport(
        turns=4,
        conversations=2,
        latency_p50_ms=812.4,
        latency_p95_ms=2_310.0,
        total_cost_usd=Decimal("0.0008"),
        avg_cost_per_conversation_usd=Decimal("0.0004"),
        intent_counts={"catalog_question": 3, "none": 1},
        handoff_rate=0.25,
        blocked_rate=0.25,
    )

    # Each line is "<name>  <value>", the names padded to the same width.
    lines = format_report(report).splitlines()
    table = dict(re.split(r"\s{2,}", line) for line in lines)

    assert table["Latency p50"] == "812 ms"
    assert table["Avg cost / conversation"] == "$0.000400"
    assert table["Handoff rate"] == "25.0%"
    assert table["Intent: catalog_question"] == "3 (75.0%)"
