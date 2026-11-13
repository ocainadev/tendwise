"""guard_input: checks the customer's message before anything else runs.

Placeholder: implemented in T11 (length cap, prompt-injection and abuse checks).
To block a message, return `{"blocked": True, "reply": "<polite refusal>"}`:
the graph then skips straight to guard_output with that reply.
"""

from app.agent.deps import AgentDeps
from app.agent.state import AgentState


def run(state: AgentState, deps: AgentDeps) -> dict:
    """Let every message through for now."""
    return {"blocked": False}
