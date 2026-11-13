"""retrieve: finds this business's chunks that match the customer's question (T08).

The retriever always filters by the business slug, so another business's
documents can never reach the answer.

We search twice when the classifier gave a `search_query` (T27): once with the
customer's own message and once with that query, which is written in the
business's language. A short Spanish question ("¿Cuál es la política de
cambios?") is far from Portuguese documents in embedding space, but its
Portuguese rewrite is close. Keeping the original search too means the result
is never worse than searching with the message alone.
"""

from app.agent.deps import AgentDeps
from app.agent.state import AgentState
from app.rag.retriever import RetrievedChunk


def run(state: AgentState, deps: AgentDeps) -> dict:
    queries = [state["message"]]
    intent = state["intent"]
    if intent is not None and intent.search_query and intent.search_query != state["message"]:
        queries.append(intent.search_query)

    found: list[RetrievedChunk] = []
    for query in queries:
        found += deps.retriever.retrieve(state["business_slug"], query, top_k=deps.top_k)
    return {"chunks": best_chunks(found, deps.top_k)}


def best_chunks(chunks: list[RetrievedChunk], top_k: int) -> list[RetrievedChunk]:
    """Drop duplicates (keeping the higher similarity), best first, at most `top_k`."""
    best: dict[tuple[str, str | None, str], RetrievedChunk] = {}
    for chunk in chunks:
        key = (chunk.source_path, chunk.section, chunk.content)
        if key not in best or chunk.similarity > best[key].similarity:
            best[key] = chunk
    ranked = sorted(best.values(), key=lambda chunk: chunk.similarity, reverse=True)
    return ranked[:top_k]
