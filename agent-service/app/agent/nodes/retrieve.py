"""retrieve: finds this business's chunks that match the customer's question (T08).

The retriever always filters by the business slug, so another business's
documents can never reach the answer.
"""

from app.agent.deps import AgentDeps
from app.agent.state import AgentState


def run(state: AgentState, deps: AgentDeps) -> dict:
    chunks = deps.retriever.retrieve(state["business_slug"], state["message"], top_k=deps.top_k)
    return {"chunks": chunks}
