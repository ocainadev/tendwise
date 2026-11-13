"""log_turn: stores the finished turn (intent, tool calls, tokens, latency, cost).

Placeholder: implemented in T12. Everything it needs is in the state:
`intent`, `tool_calls`, `llm_usage` (one LLMResponse per LLM call) and
`started_at` (time.monotonic() when the turn began).
"""

from app.agent.deps import AgentDeps
from app.agent.state import AgentState


def run(state: AgentState, deps: AgentDeps) -> dict:
    """Nothing to store yet."""
    return {}
