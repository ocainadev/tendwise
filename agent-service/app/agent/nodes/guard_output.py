"""guard_output: checks the reply before the customer sees it.

Masks phone numbers (`****0102`) and e-mail addresses (`****@example.com`) and
caps the length at 1,200 characters. It runs for every turn, blocked ones too.
"""

import logging

from app.agent.deps import AgentDeps
from app.agent.state import AgentState
from app.guardrails.output import make_reply_safe

logger = logging.getLogger(__name__)


def run(state: AgentState, deps: AgentDeps) -> dict:
    reply = state["reply"]
    safe_reply = make_reply_safe(reply)
    if safe_reply == reply:
        return {}
    logger.info("guard_output changed the reply (masked data or capped length)")
    return {"reply": safe_reply}
