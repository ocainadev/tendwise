"""PostgresSaver memory in the `agent` schema (needs Docker, like the retriever tests)."""

from uuid import uuid4

import psycopg

from app.agent.checkpointer import create_postgres_checkpointer
from app.agent.deps import AgentDeps
from app.agent.graph import build_graph
from app.agent.service import AgentService
from tests.agent.agent_fakes import BUSINESS, FakeGateway, FakeLLM, FakeRetriever, classification


def test_postgres_checkpointer_keeps_memory_across_service_instances(database_url: str) -> None:
    gateway = FakeGateway()
    llm = FakeLLM(
        [
            classification(intent="order_status", order_code="JP-1042"),
            classification(intent="order_status", phone_last4="5678"),
            "Your order is on its way.",
        ]
    )
    deps = AgentDeps(llm=llm, retriever=FakeRetriever([]), gateway=gateway)
    conversation_id = uuid4()

    # Two separate services (like two restarts) sharing only the database.
    first = AgentService(build_graph(deps, create_postgres_checkpointer(database_url)))
    first.handle_turn(conversation_id, BUSINESS, "Where is JP-1042?")
    second = AgentService(build_graph(deps, create_postgres_checkpointer(database_url)))
    result = second.handle_turn(conversation_id, BUSINESS, "5678")

    assert result.reply == "Your order is on its way."
    assert gateway.order_calls == [("joes-pizza", "JP-1042", "5678")]
    with psycopg.connect(database_url) as conn:
        schemas = conn.execute(
            "SELECT table_schema FROM information_schema.tables WHERE table_name = 'checkpoints'"
        ).fetchall()
    assert schemas == [("agent",)]
