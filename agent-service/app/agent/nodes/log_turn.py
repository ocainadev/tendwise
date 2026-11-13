"""log_turn: stores the finished turn (intent, tool calls, tokens, latency, cost).

It is the last node, so the reply it stores is the one the customer sees.
Storing is best effort: if the database fails, the customer still gets the
reply and we only log a warning.
"""

import logging
import time
from decimal import Decimal
from uuid import UUID

from app.agent.deps import AgentDeps
from app.agent.privacy import mask_phone_numbers
from app.agent.state import AgentState, turn_language
from app.observability.pricing import estimate_cost
from app.observability.turn_log import TurnRecord

logger = logging.getLogger(__name__)


def build_turn_record(state: AgentState, now: float) -> TurnRecord:
    """Turn the state of a finished turn into the row we store.

    `now` is a `time.monotonic()` reading, compared with `started_at` for the latency.
    """
    usage = state["llm_usage"]
    intent = state["intent"]
    return TurnRecord(
        conversation_id=UUID(state["conversation_id"]),
        business_id=state["business_slug"],
        language=turn_language(state),
        # Defence in depth: guard_output (T11) also masks them in the reply.
        content=mask_phone_numbers(state["reply"]),
        intent=intent.intent if intent else None,
        tool_calls=state["tool_calls"],
        prompt_tokens=sum(call.prompt_tokens for call in usage),
        completion_tokens=sum(call.completion_tokens for call in usage),
        latency_ms=round((now - state["started_at"]) * 1000),
        # Priced call by call, in case one turn ever uses two models.
        cost_usd=sum(
            (estimate_cost(c.model, c.prompt_tokens, c.completion_tokens) for c in usage),
            Decimal(0),
        ),
        model=usage[-1].model if usage else None,
        blocked=state["blocked"],
        handoff=state["handoff"],
    )


def run(state: AgentState, deps: AgentDeps) -> dict:
    """Store the turn; never let a logging problem break the customer's reply."""
    try:
        deps.turn_logger.log(build_turn_record(state, now=time.monotonic()))
    except Exception as error:
        # Only the error type: its message could contain the reply or other personal data.
        logger.warning("Could not store the turn log: %s", type(error).__name__)
    return {}
