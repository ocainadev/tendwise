"""classify_intent: labels the message (T09) and updates the conversation memory.

Besides the label, it keeps two things across turns: how many turns in a row
were negative (the router hands off on the second one), and any order code or
phone digits the customer gave, so they are not asked for again.

The classifier sometimes misses phone digits written in the same message as
the order code, so for order questions a small regex fills them in.
"""

from app.agent.deps import AgentDeps
from app.agent.intent import classify_intent, phone_last4_in
from app.agent.state import AgentState


def run(state: AgentState, deps: AgentDeps) -> dict:
    outcome = classify_intent(
        deps.llm, state["message"], state["history"], state["default_language"]
    )
    result = outcome.result

    if result.sentiment == "negative":
        negative_streak = state["negative_streak"] + 1
    else:
        negative_streak = 0

    update: dict = {
        "intent": result,
        "negative_streak": negative_streak,
        "llm_usage": state["llm_usage"] + outcome.llm_responses,
    }
    # Only overwrite what the customer gave this time; keep the rest remembered.
    if result.order_code:
        update["pending_order_code"] = result.order_code
    phone_last4 = result.phone_last4
    if not phone_last4 and result.intent == "order_status":
        phone_last4 = phone_last4_in(state["message"])
    if phone_last4:
        update["pending_phone_last4"] = phone_last4
    return update
