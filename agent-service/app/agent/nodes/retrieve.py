"""retrieve: finds this business's chunks that match the customer's question (T08).

The retriever always filters by the business slug, so another business's
documents can never reach the answer.

We search twice when the classifier gave a `search_query` (T27): once with the
customer's own message and once with that query, which is written in the
business's language. A short Spanish question ("¿Cuál es la política de
cambios?") is far from Portuguese documents in embedding space, but its
Portuguese rewrite is close. Keeping the original search too means the result
is never worse than searching with the message alone.

Knowledge gaps (T58): when nothing passed RAG_MIN_SIMILARITY, the question is
stored so the business owner can see what the knowledge base is missing. Only
catalog questions reach this node (blocked messages and `other` never do), and
playground chats are left out: they are the owner testing, not customers.
Storing is best effort, like the turn log: a failure never breaks the reply.
"""

import logging

from app.agent.deps import AgentDeps
from app.agent.state import AgentState
from app.rag.retriever import RetrievedChunk

logger = logging.getLogger(__name__)


def run(state: AgentState, deps: AgentDeps) -> dict:
    queries = [state["message"]]
    intent = state["intent"]
    if intent is not None and intent.search_query and intent.search_query != state["message"]:
        queries.append(intent.search_query)

    found: list[RetrievedChunk] = []
    for query in queries:
        found += deps.retriever.retrieve(state["business_slug"], query, top_k=deps.top_k)
    chunks = best_chunks(found, deps.top_k)
    if not chunks and is_gap_worth_recording(state):
        record_gap(state, deps)
    return {"chunks": chunks}


def is_gap_worth_recording(state: AgentState) -> bool:
    """A real customer's catalog question (not blocked, not the playground)."""
    intent = state["intent"]
    return (
        not state["blocked"]
        and intent is not None
        and intent.intent == "catalog_question"
        and state.get("channel", "web") != "playground"
    )


def record_gap(state: AgentState, deps: AgentDeps) -> None:
    try:
        deps.gap_recorder.record(state["business_slug"], state["message"])
    except Exception as error:
        # Only the error type: its message could contain the question.
        logger.warning("Could not store the knowledge gap: %s", type(error).__name__)


def best_chunks(chunks: list[RetrievedChunk], top_k: int) -> list[RetrievedChunk]:
    """Drop duplicates (keeping the higher similarity), best first, at most `top_k`."""
    best: dict[tuple[str, str | None, str], RetrievedChunk] = {}
    for chunk in chunks:
        key = (chunk.source_path, chunk.section, chunk.content)
        if key not in best or chunk.similarity > best[key].similarity:
            best[key] = chunk
    ranked = sorted(best.values(), key=lambda chunk: chunk.similarity, reverse=True)
    return ranked[:top_k]
