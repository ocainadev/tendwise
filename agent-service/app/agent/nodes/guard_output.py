"""guard_output: checks the reply before the customer sees it.

Placeholder: implemented in T11 (no phone numbers or other customers' data in
the reply, length cap). It may return `{"reply": "<safe text>"}` to replace it.
"""

from app.agent.deps import AgentDeps
from app.agent.state import AgentState


def run(state: AgentState, deps: AgentDeps) -> dict:
    """Keep the reply as it is for now."""
    return {}
