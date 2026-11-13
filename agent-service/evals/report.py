"""The Markdown report and the console summary of one eval run."""

from datetime import date
from decimal import Decimal

from evals.scoring import CaseScore, Metric, Summary

# Long replies are cut in the report; the failed check is what matters.
MAX_REPLY_CHARS = 200


def percent(value: float | None) -> str:
    return "–" if value is None else f"{value:.0%}"


def met_label(metric: Metric) -> str:
    if metric.met is None:
        return "–"
    return "yes" if metric.met else "**no**"


def usd(value: Decimal) -> str:
    return f"${value:.5f}"


def one_line(text: str, limit: int = MAX_REPLY_CHARS) -> str:
    """Single line, cut at `limit`, with `|` escaped so it cannot break a Markdown table."""
    flat = " ".join(text.split()).replace("|", "\\|")
    return flat if len(flat) <= limit else flat[: limit - 1] + "…"


def format_report(summary: Summary, scores: list[CaseScore], model: str, run_date: date) -> str:
    lines = [
        f"# Eval results: {model} ({run_date.isoformat()})",
        "",
        f"- Cases: {summary.cases} (errors: {summary.errors})",
        f"- Average latency: {summary.avg_latency_seconds:.2f} s per turn",
        f"- Average cost: {usd(summary.avg_cost_usd)} per turn "
        f"(total {usd(summary.total_cost_usd)}, estimated from T12's price table)",
        "",
        "| Metric | Target | Actual | Passed | Met |",
        "|---|---|---|---|---|",
    ]
    for metric in summary.metrics:
        lines.append(
            f"| {metric.name} | {percent(metric.target)} | {percent(metric.rate)} "
            f"| {metric.passed}/{metric.total} | {met_label(metric)} |"
        )

    failed = [score for score in scores if score.failed_checks]
    lines += ["", f"## Failed cases ({len(failed)})", ""]
    if not failed:
        lines.append("None.")
    else:
        lines += [
            "| Case | Failed checks | Expected | Got | Reply |",
            "|---|---|---|---|---|",
        ]
        for score in failed:
            case, outcome = score.case, score.outcome
            expected = f"{case.expected_intent or 'blocked'} / {case.expected_tool or 'no tool'}"
            if outcome.error:
                got = f"error: {outcome.error}"
            else:
                got = f"{outcome.intent or 'blocked'} / {', '.join(outcome.tools) or 'no tool'}"
            lines.append(
                f"| {case.id} | {', '.join(score.failed_checks)} | {expected} "
                f"| {one_line(got)} | {one_line(outcome.reply)} |"
            )
    return "\n".join(lines) + "\n"


def format_console_summary(summary: Summary, model: str) -> str:
    lines = [f"Evals for {model}: {summary.cases} cases, {summary.errors} errors"]
    for metric in summary.metrics:
        target = f" (target {percent(metric.target)})" if metric.target is not None else ""
        lines.append(
            f"  {metric.name:<26} {percent(metric.rate):>5}  {metric.passed}/{metric.total}{target}"
        )
    lines.append(f"  {'Average latency':<26} {summary.avg_latency_seconds:.2f} s")
    lines.append(f"  {'Average cost':<26} {usd(summary.avg_cost_usd)}")
    return "\n".join(lines)
