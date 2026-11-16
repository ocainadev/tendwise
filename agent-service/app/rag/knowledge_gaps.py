"""Knowledge gaps (T58): catalog questions the knowledge base could not answer.

Recording: the `retrieve` node calls a `GapRecorder` when a catalog question
found no chunk above RAG_MIN_SIMILARITY. `PostgresGapRecorder` masks phone
numbers and emails, embeds the masked question once, and stores one row in
`agent.knowledge_gaps` with the best similarity it reached.

Grouping (read by the panel): many customers ask the same thing in different
words ("do you deliver to Brooklyn?", "Do you deliver in Brooklyn"). We group
by meaning, with the embedding stored in each row:

1. take the business's gaps of the period, newest first;
2. a question joins the first group whose representative (that group's first,
   so newest, question) has a cosine similarity of at least GROUP_SIMILARITY;
3. otherwise it starts a new group.

Why this and not exact text matching: two wordings of one question almost
never match letter by letter, so exact matching would leave the list as long
as before. Why not a clustering library: this greedy pass is a few lines, has
one knob, and every grouping can be explained ("they were 0.9 similar").
"""

import math
from dataclasses import dataclass, field
from datetime import datetime
from typing import Protocol

import psycopg
from pgvector import Vector
from pgvector.psycopg import register_vector

from app.agent.privacy import mask_phone_numbers
from app.core.db import ConnectionFactory
from app.guardrails.output import mask_emails
from app.rag.embeddings import Embedder

# Two questions at least this similar are "the same question". Paraphrases
# with the multilingual MiniLM model score about 0.85-0.95; different
# questions about the same business usually score 0.4-0.7.
GROUP_SIMILARITY = 0.85
# The panel groups at most this many recent gaps per request, so one busy
# business cannot make the request slow (the grouping is O(gaps x groups)).
MAX_GAPS_GROUPED = 500
# How many other wordings of a group the panel shows next to its representative.
MAX_EXAMPLES = 3


def mask_question(text: str) -> str:
    """The question as we store it: phone numbers and emails masked."""
    return mask_emails(mask_phone_numbers(text)).strip()


class GapRecorder(Protocol):
    """Anything with this `record` method can store knowledge gaps."""

    def record(self, business_id: str, question: str) -> None: ...


class NoOpGapRecorder:
    """Stores nothing: the default in `AgentDeps`, so tests need no database."""

    def record(self, business_id: str, question: str) -> None:
        return None


# The closest chunk of this business (the same cosine similarity as the retriever).
BEST_SIMILARITY_SQL = """
    SELECT max(1 - (embedding <=> %(embedding)s))
    FROM agent.chunks
    WHERE business_id = %(business_id)s
"""

INSERT_GAP_SQL = """
    INSERT INTO agent.knowledge_gaps (business_id, question, best_similarity, embedding)
    VALUES (%(business_id)s, %(question)s, %(best_similarity)s, %(embedding)s)
"""


class PostgresGapRecorder:
    """Writes each gap to `agent.knowledge_gaps`."""

    def __init__(self, embedder: Embedder, connect: ConnectionFactory) -> None:
        self._embedder = embedder
        self._connect = connect

    def record(self, business_id: str, question: str) -> None:
        masked = mask_question(question)
        if not masked:
            return
        embedding = Vector(self._embedder.embed([masked])[0])
        params = {"business_id": business_id, "question": masked, "embedding": embedding}
        with self._connect() as conn, conn.transaction():
            register_vector(conn)
            row = conn.execute(BEST_SIMILARITY_SQL, params).fetchone()
            params["best_similarity"] = row[0] if row else None
            conn.execute(INSERT_GAP_SQL, params)


@dataclass
class GapGroup:
    """Similar unanswered questions, shown as one line in the panel."""

    representative: str  # the newest question of the group
    count: int
    last_asked: datetime
    examples: list[str] = field(default_factory=list)  # other wordings, newest first
    # Not returned by the API: the representative's vector, for the comparisons.
    embedding: list[float] = field(default_factory=list, repr=False)


@dataclass(frozen=True)
class Gap:
    question: str
    embedding: list[float]
    created_at: datetime


def cosine_similarity(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    norms = math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b))
    return dot / norms if norms else 0.0


def group_gaps(gaps: list[Gap], min_similarity: float = GROUP_SIMILARITY) -> list[GapGroup]:
    """Group gaps (given newest first) by meaning; the most asked groups first."""
    groups: list[GapGroup] = []
    for gap in gaps:
        group = next(
            (g for g in groups if cosine_similarity(g.embedding, gap.embedding) >= min_similarity),
            None,
        )
        if group is None:
            groups.append(
                GapGroup(
                    representative=gap.question,
                    count=1,
                    last_asked=gap.created_at,
                    embedding=gap.embedding,
                )
            )
            continue
        group.count += 1
        is_new_wording = gap.question != group.representative
        if is_new_wording and gap.question not in group.examples:
            if len(group.examples) < MAX_EXAMPLES:
                group.examples.append(gap.question)
    # Stable sort: equal counts keep the newest group first.
    return sorted(groups, key=lambda g: g.count, reverse=True)


LIST_GAPS_SQL = """
    SELECT question, embedding, created_at
    FROM agent.knowledge_gaps
    WHERE business_id = %(business_id)s
      AND created_at >= %(start)s AND created_at < %(end)s
    ORDER BY created_at DESC, id DESC
    LIMIT %(limit)s
"""


def list_gap_groups(
    conn: psycopg.Connection, business_id: str, start: datetime, end: datetime
) -> list[GapGroup]:
    """The grouped gaps of one business asked in [start, end)."""
    register_vector(conn)
    rows = conn.execute(
        LIST_GAPS_SQL,
        {"business_id": business_id, "start": start, "end": end, "limit": MAX_GAPS_GROUPED},
    ).fetchall()
    gaps = [Gap(question, vector.to_list(), created_at) for question, vector, created_at in rows]
    return group_gaps(gaps)
