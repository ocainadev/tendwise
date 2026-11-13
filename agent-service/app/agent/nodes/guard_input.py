"""guard_input: checks the customer's message before anything else runs.

Deterministic checks only (length, prompt injection, abuse), so this node is
free, instant and makes no LLM call. A blocked message gets a fixed refusal and
the graph skips straight to guard_output; `guard_reason` tells the turn log
(T12) why, without storing the message text.
"""

import logging

from app.agent.deps import AgentDeps
from app.agent.state import AgentState
from app.guardrails.input import check_message
from app.guardrails.language import guess_language
from app.guardrails.replies import refusal

logger = logging.getLogger(__name__)


def run(state: AgentState, deps: AgentDeps) -> dict:
    block = check_message(state["message"])
    if block is None:
        return {"blocked": False, "guard_reason": None}

    # Only the rule name is logged: the message itself may hold personal data.
    logger.info("guard_input blocked a message: %s", block.reason)
    language = guess_language(state["message"], default=state["default_language"])
    return {
        "blocked": True,
        "guard_reason": block.reason,
        "reply": refusal(block.kind, language, state["business_name"]),
    }
