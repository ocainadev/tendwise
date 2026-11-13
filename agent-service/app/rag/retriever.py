"""The Retriever interface (a "port") and its pgvector adapter.

Given a business and a customer question, find the chunks of that business's
documents that are closest in meaning. Two rules keep the answers grounded:

- Tenant isolation: the SQL always filters by `business_id`, so a pizzeria
  question can never return a clothing store's chunks.
- Relevance: chunks under `min_similarity` are dropped, so an off-topic
  question ("who won the world cup?") gets no context instead of noise.
"""

import logging
from typing import Protocol

from pgvector import Vector
from pgvector.psycopg import register_vector
from pydantic import BaseModel

from app.core.config import Settings
from app.core.db import ConnectionFactory, connection_factory
from app.rag.embeddings import Embedder, create_embedder

logger = logging.getLogger(__name__)


class RetrievedChunk(BaseModel):
    """One chunk found for a question, with where it came from."""

    content: str
    source_path: str  # e.g. 'joes-pizza/faq.md'
    title: str  # the document's first `# ` heading
    section: str | None  # heading path from the chunk metadata, e.g. '... > Delivery > Fees'
    # Cosine similarity: 1 = same meaning, 0 = unrelated. (It could go below 0
    # in theory, but such chunks are always under the threshold anyway.)
    similarity: float


class Retriever(Protocol):
    """Anything with this `retrieve` method is a Retriever (the agent depends only on this)."""

    def retrieve(self, business_slug: str, query: str, top_k: int = 5) -> list[RetrievedChunk]:
        """Return at most `top_k` relevant chunks of this business, best first."""
        ...


# One query: nearest chunks of ONE business by cosine distance (`<=>`).
# `1 - distance` turns the distance into a similarity. The join on both ids
# also checks that the document belongs to the same business as the chunk.
SEARCH_SQL = """
SELECT c.content,
       d.source_path,
       d.title,
       c.metadata ->> 'section' AS section,
       1 - (c.embedding <=> %(query)s) AS similarity
FROM agent.chunks c
JOIN agent.documents d ON d.id = c.document_id AND d.business_id = c.business_id
WHERE c.business_id = %(business_id)s
ORDER BY c.embedding <=> %(query)s
LIMIT %(top_k)s
"""


class PgVectorRetriever:
    """Retriever that embeds the question and searches `agent.chunks` with pgvector."""

    def __init__(self, embedder: Embedder, connect: ConnectionFactory, min_similarity: float):
        self._embedder = embedder
        self._connect = connect
        self._min_similarity = min_similarity

    def retrieve(self, business_slug: str, query: str, top_k: int = 5) -> list[RetrievedChunk]:
        if top_k < 1:
            raise ValueError(f"top_k must be at least 1, got {top_k}")
        if not query.strip():
            return []  # nothing to search for

        query_vector = Vector(self._embedder.embed([query])[0])
        with self._connect() as conn:
            # Teach psycopg the `vector` type so it can send `query_vector`.
            register_vector(conn)
            with conn.transaction():
                # Today PostgreSQL answers this with the business_id index and an
                # exact sort (one business is a small part of the table). When it
                # picks the HNSW index instead, that index finds the nearest chunks
                # of ALL businesses first and applies the business filter
                # afterwards, so it could return fewer than top_k rows. Iterative
                # scan (pgvector 0.8) keeps searching until LIMIT is filled.
                # "relaxed_order" may return rows slightly out of order, which is
                # why we sort again below. SET LOCAL lasts only for this transaction.
                conn.execute("SET LOCAL hnsw.iterative_scan = relaxed_order")
                rows = conn.execute(
                    SEARCH_SQL,
                    {"query": query_vector, "business_id": business_slug, "top_k": top_k},
                ).fetchall()

        chunks = [
            RetrievedChunk(
                content=content,
                source_path=source_path,
                title=title,
                section=section,
                similarity=similarity,
            )
            for content, source_path, title, section, similarity in rows
        ]
        # The threshold is applied here, not in SQL: a WHERE on the distance
        # would stop PostgreSQL from using the HNSW index for ORDER BY ... LIMIT.
        relevant = [chunk for chunk in chunks if chunk.similarity >= self._min_similarity]
        relevant.sort(key=lambda chunk: chunk.similarity, reverse=True)
        # Log counts only: the question itself may contain personal data.
        logger.info(
            "Retrieved %d of %d chunks for %s (min_similarity=%.2f)",
            len(relevant),
            len(chunks),
            business_slug,
            self._min_similarity,
        )
        return relevant


def create_retriever(settings: Settings) -> Retriever:
    """Build the retriever configured by the settings.

    Creating it loads the embedding model (a second or two), so build it once
    and reuse it for every question.
    """
    return PgVectorRetriever(
        embedder=create_embedder(settings),
        connect=connection_factory(settings.database_url.get_secret_value()),
        min_similarity=settings.rag_min_similarity,
    )
