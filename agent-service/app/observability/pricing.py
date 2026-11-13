"""Estimated USD cost of an LLM call, from its token counts.

The provider bills by tokens, with one price for input (prompt) tokens and
another for output (completion) tokens. We keep a small table of the models we
use; the result is an estimate for the report, not an invoice.
"""

import logging
from decimal import Decimal

logger = logging.getLogger(__name__)

# USD per million tokens: (input, output).
# Source: Anthropic first-party API prices (claude-api skill model table, cached
# 2026-11-03), checked on 2026-11-13. Claude Haiku 5.5 costs more for prompts
# over 100K tokens ($0.50 / $2.50); our prompts are far smaller, so we ignore it.
PRICES_PER_MILLION_TOKENS: dict[str, tuple[Decimal, Decimal]] = {
    "claude-haiku-5-5": (Decimal("0.10"), Decimal("0.50")),
    "claude-sonnet-5-5": (Decimal("2.00"), Decimal("10.00")),
    "claude-opus-5-5": (Decimal("4.00"), Decimal("20.00")),
}

ONE_MILLION = Decimal(1_000_000)

# Models we already warned about, so an unknown model logs once, not every turn.
_warned_models: set[str] = set()


def estimate_cost(model: str, prompt_tokens: int, completion_tokens: int) -> Decimal:
    """Cost in USD of one call; 0 for a model missing from the table (and one warning)."""
    prices = PRICES_PER_MILLION_TOKENS.get(model)
    if prices is None:
        if model not in _warned_models:
            _warned_models.add(model)
            logger.warning("No price for model %r: its cost is logged as 0", model)
        return Decimal(0)
    input_price, output_price = prices
    return (prompt_tokens * input_price + completion_tokens * output_price) / ONE_MILLION
