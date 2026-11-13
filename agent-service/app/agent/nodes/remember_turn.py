"""remember_turn: adds this turn's message and final reply to the conversation history.

It runs after guard_output, so the history holds exactly what the customer saw.
A blocked message is not remembered: an injection attempt must not sit in the
context of later turns.
"""

from app.agent.deps import AgentDeps
from app.agent.state import AgentState
from app.llm.client import Message

# Enough for the classifier and the handoff summary; keeps checkpoints small.
MAX_HISTORY_MESSAGES = 40


def run(state: AgentState, deps: AgentDeps) -> dict:
    if state["blocked"]:
        return {}
    history = state["history"] + [
        Message(role="user", content=state["message"]),
        Message(role="assistant", content=state["reply"]),
    ]
    return {"history": history[-MAX_HISTORY_MESSAGES:]}
