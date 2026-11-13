"""route: picks the next node with fixed rules, not with the LLM.

Routing is plain code on purpose: the rules are predictable, free, testable
and easy to explain ("why did it hand off?" always has a one-line answer).
"""

from app.agent.deps import AgentDeps
from app.agent.intent import IntentResult
from app.agent.state import AgentState, HandoffReason, NextStep

# Below this confidence the label is a guess, so a person takes over.
MIN_CONFIDENCE = 0.6
# Negative sentiment this many turns in a row means the customer is upset.
NEGATIVE_TURNS_FOR_HANDOFF = 2

STEP_BY_INTENT: dict[str, NextStep] = {
    "catalog_question": "retrieve",
    "order_status": "ask_or_call_tool",
    "other": "answer_generic",
}


def decide_route(
    intent: IntentResult, negative_streak: int
) -> tuple[NextStep, HandoffReason | None]:
    """Return the next step and, for a handoff, its reason.

    Checked in this order: a low confidence first (then the label itself is not
    trusted), then an explicit request for a person, a complaint, and finally
    an upset customer.
    """
    if intent.confidence < MIN_CONFIDENCE:
        return "create_handoff", "LOW_CONFIDENCE"
    if intent.intent == "human_request":
        return "create_handoff", "HUMAN_REQUEST"
    if intent.intent == "complaint":
        return "create_handoff", "COMPLAINT"
    if negative_streak >= NEGATIVE_TURNS_FOR_HANDOFF:
        return "create_handoff", "NEGATIVE_SENTIMENT"
    return STEP_BY_INTENT[intent.intent], None


def run(state: AgentState, deps: AgentDeps) -> dict:
    next_step, reason = decide_route(state["intent"], state["negative_streak"])
    return {"next_step": next_step, "handoff_reason": reason}
