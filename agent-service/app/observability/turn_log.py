"""Stores one row in `agent.turns` per answered turn (the turn log).

The agent depends on the `TurnLogger` interface; `PostgresTurnLogger` writes to
the database, and `NoOpTurnLogger` (the default in `AgentDeps`) writes nothing,
so tests and local experiments need no database.
"""

from decimal import Decimal
from typing import Literal, Protocol
from uuid import UUID

from psycopg.types.json import Jsonb
from pydantic import BaseModel

from app.core.db import ConnectionFactory


class TurnRecord(BaseModel):
    """Everything we store about one turn: what was answered and what it cost."""

    conversation_id: UUID
    business_id: str  # the business slug
    channel: Literal["web", "whatsapp"] = "web"
    language: str | None  # en | es | pt
    content: str  # the final reply, phone numbers already masked
    intent: str | None  # None when guard_input blocked the message
    tool_calls: list[dict]  # phone digits already masked
    prompt_tokens: int
    completion_tokens: int
    latency_ms: int
    cost_usd: Decimal
    model: str | None  # None when the turn made no LLM call
    blocked: bool
    handoff: bool


class TurnLogger(Protocol):
    """Anything with this `log` method can store turns (structural typing)."""

    def log(self, record: TurnRecord) -> None: ...


class NoOpTurnLogger:
    """Stores nothing: the default, so building the graph never needs a database."""

    def log(self, record: TurnRecord) -> None:
        return None


# The conversation row is created by its first turn; later turns change nothing.
INSERT_CONVERSATION = """
    INSERT INTO agent.conversations (id, business_id, channel, language)
    VALUES (%(conversation_id)s, %(business_id)s, %(channel)s, %(language)s)
    ON CONFLICT (id) DO NOTHING
"""

# role is always 'assistant': we store one row per turn, holding the final reply.
INSERT_TURN = """
    INSERT INTO agent.turns (
        conversation_id, role, content, intent, tool_calls,
        prompt_tokens, completion_tokens, latency_ms, cost_usd, model, blocked, handoff
    )
    VALUES (
        %(conversation_id)s, 'assistant', %(content)s, %(intent)s, %(tool_calls)s,
        %(prompt_tokens)s, %(completion_tokens)s, %(latency_ms)s, %(cost_usd)s, %(model)s,
        %(blocked)s, %(handoff)s
    )
"""


class PostgresTurnLogger:
    """Writes each turn to `agent.turns`, creating its conversation row if needed."""

    def __init__(self, connect: ConnectionFactory) -> None:
        self._connect = connect

    def log(self, record: TurnRecord) -> None:
        """Insert the conversation (first turn only) and the turn in one transaction."""
        params = record.model_dump()
        # Jsonb tells psycopg to send the list as a JSONB value.
        params["tool_calls"] = Jsonb(record.tool_calls)
        with self._connect() as conn, conn.transaction():
            conn.execute(INSERT_CONVERSATION, params)
            conn.execute(INSERT_TURN, params)
