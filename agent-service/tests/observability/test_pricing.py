from decimal import Decimal

import pytest

from app.observability.pricing import estimate_cost


def test_known_model_is_priced_per_million_tokens() -> None:
    # Haiku 5.5: $0.10 per million input tokens, $0.50 per million output tokens.
    cost = estimate_cost("claude-haiku-5-5", prompt_tokens=1_000_000, completion_tokens=2_000_000)
    assert cost == Decimal("1.10")


def test_a_typical_turn_costs_a_fraction_of_a_cent() -> None:
    cost = estimate_cost("claude-haiku-5-5", prompt_tokens=1_200, completion_tokens=150)
    assert cost == Decimal("0.000195")


def test_openai_model_is_priced_per_million_tokens() -> None:
    # gpt-6-luna: $0.10 per million input tokens, $0.50 per million output tokens.
    cost = estimate_cost("gpt-6-luna", prompt_tokens=1_000_000, completion_tokens=2_000_000)
    assert cost == Decimal("1.10")


def test_a_dated_snapshot_costs_the_same_as_its_alias() -> None:
    snapshot = estimate_cost("gpt-5.4-mini-2026-03-17", prompt_tokens=1_000, completion_tokens=100)
    alias = estimate_cost("gpt-5.4-mini", prompt_tokens=1_000, completion_tokens=100)
    assert snapshot == alias == Decimal("0.0012")


def test_unknown_model_costs_zero_and_warns_once(caplog: pytest.LogCaptureFixture) -> None:
    first = estimate_cost("some-new-model", prompt_tokens=100, completion_tokens=10)
    second = estimate_cost("some-new-model", prompt_tokens=100, completion_tokens=10)

    assert first == second == Decimal(0)
    warnings = [r for r in caplog.records if "some-new-model" in r.getMessage()]
    assert len(warnings) == 1
