"""Purging old conversations (needs Docker)."""

from collections.abc import Callable
from uuid import uuid4

from app.agent.checkpointer import create_memory_checkpointer
from app.agent.deps import AgentDeps
from app.agent.graph import build_graph
from app.agent.service import AgentService
from app.core.db import ConnectionFactory
from app.observability.retention import delete_old_conversations
from app.observability.turn_log import PostgresTurnLogger
from tests.agent.agent_fakes import BUSINESS, FakeGateway, FakeLLM, FakeRetriever, classification


def test_purge_deletes_only_old_conversations_their_turns_and_their_memory(
    connect: ConnectionFactory, count_rows: Callable[[str], int]
) -> None:
    checkpointer = create_memory_checkpointer()
    llm = FakeLLM([classification(), "Hi!", classification(), "Hello again!"])
    deps = AgentDeps(
        llm=llm,
        retriever=FakeRetriever([]),
        gateway=FakeGateway(),
        turn_logger=PostgresTurnLogger(connect),
    )
    service = AgentService(build_graph(deps, checkpointer))
    old, recent = uuid4(), uuid4()
    service.handle_turn(old, BUSINESS, "Hi")
    service.handle_turn(recent, BUSINESS, "Hi")
    with connect() as conn:
        conn.execute(
            "UPDATE agent.conversations SET created_at = now() - interval '91 days' WHERE id = %s",
            (old,),
        )

    deleted = delete_old_conversations(connect, days=90, checkpointer=checkpointer)

    assert deleted == 1
    assert count_rows("conversations") == 1
    assert count_rows("turns") == 1
    with connect() as conn:
        remaining = conn.execute("SELECT conversation_id FROM agent.turns").fetchone()[0]
    assert remaining == recent
    assert checkpointer.get_tuple({"configurable": {"thread_id": str(old)}}) is None
    assert checkpointer.get_tuple({"configurable": {"thread_id": str(recent)}}) is not None


def test_purge_with_nothing_old_deletes_nothing(
    connect: ConnectionFactory, count_rows: Callable[[str], int]
) -> None:
    with connect() as conn:
        conn.execute(
            "INSERT INTO agent.conversations (id, business_id) VALUES (%s, 'joes-pizza')",
            (uuid4(),),
        )

    assert delete_old_conversations(connect, days=90) == 0
    assert count_rows("conversations") == 1
