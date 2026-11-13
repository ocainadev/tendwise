"""Summary of the turn log: latency, cost, intents, handoff and blocked rates.

Spec section 10. The database does the aggregation (percentiles, sums, counts);
this module only runs the queries and formats the result as a text table for
`scripts/report.py`.
"""

from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel

from app.core.db import ConnectionFactory

# Both filters are optional: a NULL parameter turns its condition off.
FILTERS = """
    FROM agent.turns t
    JOIN agent.conversations c ON c.id = t.conversation_id
    WHERE (%(business)s::text IS NULL OR c.business_id = %(business)s::text)
      AND (%(since)s::timestamptz IS NULL OR t.created_at >= %(since)s::timestamptz)
"""

TOTALS_QUERY = f"""
    SELECT
        count(*),
        count(DISTINCT t.conversation_id),
        percentile_cont(0.5) WITHIN GROUP (ORDER BY t.latency_ms),
        percentile_cont(0.95) WITHIN GROUP (ORDER BY t.latency_ms),
        coalesce(sum(t.cost_usd), 0),
        coalesce(avg(t.handoff::int), 0),
        coalesce(avg(t.blocked::int), 0)
    {FILTERS}
"""

# Blocked turns have no intent; they are counted as 'none'.
INTENTS_QUERY = f"""
    SELECT coalesce(t.intent, 'none') AS intent, count(*) AS turns
    {FILTERS}
    GROUP BY 1
    ORDER BY 2 DESC, 1
"""


class TurnReport(BaseModel):
    turns: int
    conversations: int
    latency_p50_ms: float
    latency_p95_ms: float
    total_cost_usd: Decimal
    avg_cost_per_conversation_usd: Decimal
    intent_counts: dict[str, int]
    handoff_rate: float  # share of turns that created a handoff, 0-1
    blocked_rate: float  # share of turns stopped by guard_input, 0-1


def build_report(
    connect: ConnectionFactory, business: str | None = None, since: datetime | None = None
) -> TurnReport:
    """Aggregate the turns of one business (or all) created at or after `since` (or ever)."""
    params = {"business": business, "since": since}
    with connect() as conn:
        totals = conn.execute(TOTALS_QUERY, params).fetchone()
        intents = conn.execute(INTENTS_QUERY, params).fetchall()

    turns, conversations, p50, p95, total_cost, handoff_rate, blocked_rate = totals
    return TurnReport(
        turns=turns,
        conversations=conversations,
        # percentile_cont returns NULL when there are no turns.
        latency_p50_ms=p50 or 0.0,
        latency_p95_ms=p95 or 0.0,
        total_cost_usd=total_cost,
        avg_cost_per_conversation_usd=total_cost / conversations if conversations else Decimal(0),
        intent_counts={intent: count for intent, count in intents},
        handoff_rate=float(handoff_rate),
        blocked_rate=float(blocked_rate),
    )


def format_report(report: TurnReport) -> str:
    """The report as a plain text table, one metric per line."""
    rows = [
        ("Turns", str(report.turns)),
        ("Conversations", str(report.conversations)),
        ("Latency p50", f"{report.latency_p50_ms:.0f} ms"),
        ("Latency p95", f"{report.latency_p95_ms:.0f} ms"),
        ("Total cost", f"${report.total_cost_usd:.6f}"),
        ("Avg cost / conversation", f"${report.avg_cost_per_conversation_usd:.6f}"),
        ("Handoff rate", f"{report.handoff_rate:.1%}"),
        ("Blocked rate", f"{report.blocked_rate:.1%}"),
    ]
    for intent, count in report.intent_counts.items():
        share = count / report.turns if report.turns else 0.0
        rows.append((f"Intent: {intent}", f"{count} ({share:.1%})"))

    width = max(len(name) for name, _ in rows)
    return "\n".join(f"{name.ljust(width)}  {value}" for name, value in rows)
