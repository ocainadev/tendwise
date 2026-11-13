"""The turn log against a real PostgreSQL + pgvector (needs Docker, like the retriever tests)."""

from collections.abc import Callable
from decimal import Decimal
from uuid import UUID, uuid4

import pytest

from app.agent.checkpointer import create_memory_checkpointer
from app.agent.deps import AgentDeps
from app.agent.graph import build_graph
from app.agent.service import AgentService
from app.core.db import ConnectionFactory
from app.observability.turn_log import PostgresTurnLogger, TurnLogger, TurnRecord
from tests.agent.agent_fakes import BUSINESS, FakeGateway, FakeLLM, FakeRetriever, classification

CountRows = Callable[[str], int]


def make_record(conversation_id: UUID, **overrides: object) -> TurnRecord:
    fields: dict = {
        "conversation_id": conversation_id,
        "business_id": "joes-pizza",
        "language": "pt",
        "content": "Seu pedido está a caminho.",
        "intent": "order_status",
        "tool_calls": [{"name": "get_order_status", "args": {"phone_last4": "****5678"}}],
        "prompt_tokens": 1_200,
        "completion_tokens": 150,
        "latency_ms": 840,
        "cost_usd": Decimal("0.000195"),
        "model": "claude-haiku-5-5",
        "blocked": False,
        "handoff": False,
    }
    fields.update(overrides)
    return TurnRecord(**fields)


def test_migration_creates_both_tables_with_the_flag_columns(connect: ConnectionFactory) -> None:
    with connect() as conn:
        columns = conn.execute(
            """
            SELECT table_name, column_name FROM information_schema.columns
            WHERE table_schema = 'agent' AND table_name IN ('conversations', 'turns')
            """
        ).fetchall()

    assert ("conversations", "channel") in columns
    assert ("turns", "cost_usd") in columns
    assert ("turns", "blocked") in columns
    assert ("turns", "handoff") in columns


def test_first_turn_creates_the_conversation_and_one_turn_row(
    connect: ConnectionFactory, count_rows: CountRows
) -> None:
    conversation_id = uuid4()

    PostgresTurnLogger(connect).log(make_record(conversation_id))

    with connect() as conn:
        conversation = conn.execute(
            "SELECT business_id, channel, language, status FROM agent.conversations WHERE id = %s",
            (conversation_id,),
        ).fetchone()
        turn = conn.execute(
            """
            SELECT role, content, intent, tool_calls, prompt_tokens, completion_tokens,
                   latency_ms, cost_usd, model, blocked, handoff
            FROM agent.turns WHERE conversation_id = %s
            """,
            (conversation_id,),
        ).fetchone()

    assert conversation == ("joes-pizza", "web", "pt", "active")
    assert turn == (
        "assistant",
        "Seu pedido está a caminho.",
        "order_status",
        [{"name": "get_order_status", "args": {"phone_last4": "****5678"}}],
        1_200,
        150,
        840,
        Decimal("0.000195"),
        "claude-haiku-5-5",
        False,
        False,
    )
    assert count_rows("turns") == 1


def test_second_turn_adds_one_row_and_no_second_conversation(
    connect: ConnectionFactory, count_rows: CountRows
) -> None:
    conversation_id = uuid4()
    turn_logger = PostgresTurnLogger(connect)

    turn_logger.log(make_record(conversation_id))
    turn_logger.log(make_record(conversation_id, language="en", handoff=True))

    assert count_rows("conversations") == 1
    assert count_rows("turns") == 2
    with connect() as conn:
        language = conn.execute(
            "SELECT language FROM agent.conversations WHERE id = %s", (conversation_id,)
        ).fetchone()[0]
    assert language == "pt"  # the first turn's language is kept


def service_with(turn_logger: TurnLogger, llm: FakeLLM) -> AgentService:
    deps = AgentDeps(
        llm=llm, retriever=FakeRetriever([]), gateway=FakeGateway(), turn_logger=turn_logger
    )
    return AgentService(build_graph(deps, create_memory_checkpointer()))


def test_every_handle_turn_call_writes_exactly_one_row(
    connect: ConnectionFactory, count_rows: CountRows
) -> None:
    # Each "other" turn makes two LLM calls: classify_intent and answer_generic.
    llm = FakeLLM([classification(), "Hi! How can I help?", classification(), "We open at 11."])
    service = service_with(PostgresTurnLogger(connect), llm)
    conversation_id = uuid4()

    service.handle_turn(conversation_id, BUSINESS, "Hello")
    service.handle_turn(conversation_id, BUSINESS, "When do you open?")

    assert count_rows("conversations") == 1
    assert count_rows("turns") == 2
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT content, intent, prompt_tokens, completion_tokens, model
            FROM agent.turns ORDER BY id
            """
        ).fetchall()
    # FakeLLM reports 50 prompt + 10 completion tokens per call.
    assert rows == [
        ("Hi! How can I help?", "other", 100, 20, "fake-model"),
        ("We open at 11.", "other", 100, 20, "fake-model"),
    ]


class BrokenLogger:
    def log(self, record: TurnRecord) -> None:
        raise ConnectionError("database unreachable")


def test_a_failing_turn_logger_does_not_break_handle_turn(caplog: pytest.LogCaptureFixture) -> None:
    service = service_with(BrokenLogger(), FakeLLM([classification(), "Hi there!"]))

    result = service.handle_turn(uuid4(), BUSINESS, "Hello")

    assert result.reply == "Hi there!"
    assert "Could not store the turn log: ConnectionError" in caplog.text
