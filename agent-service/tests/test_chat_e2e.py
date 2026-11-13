"""End-to-end tests of POST /api/v1/chat (T13 acceptance criterion).

The real FastAPI app and the real compiled agent graph, with only the outside
world faked: the LLM (queued replies), the retriever, business-service and the
checkpointer (in memory).
"""

from collections.abc import Iterator
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from app.api.chat import get_agent_service, get_gateway, get_rate_limiter
from app.api.rate_limit import SlidingWindowRateLimiter
from app.main import create_app
from app.rag.retriever import RetrievedChunk
from tests.agent.agent_fakes import (
    CHUNK,
    FakeGateway,
    FakeLLM,
    FakeRetriever,
    classification,
    make_service,
)

MENU_CHUNK = RetrievedChunk(
    content="Margherita pizza: $12.",
    source_path="joes-pizza/menu.md",
    title="Joe's Pizza – Menu",
    section="Joe's Pizza – Menu > Pizzas",
    similarity=0.65,
)


@pytest.fixture
def llm() -> FakeLLM:
    return FakeLLM([])


@pytest.fixture
def gateway() -> FakeGateway:
    return FakeGateway()


@pytest.fixture
def client(llm: FakeLLM, gateway: FakeGateway) -> Iterator[TestClient]:
    service = make_service(llm, retriever=FakeRetriever([CHUNK, MENU_CHUNK]), gateway=gateway)
    app = create_app()
    app.dependency_overrides[get_agent_service] = lambda: service
    app.dependency_overrides[get_gateway] = lambda: gateway
    app.dependency_overrides[get_rate_limiter] = lambda: SlidingWindowRateLimiter(20, 600)
    with TestClient(app) as test_client:
        yield test_client


def test_catalog_question_returns_the_reply_with_both_sources(
    client: TestClient, llm: FakeLLM
) -> None:
    llm.replies += [
        classification(intent="catalog_question"),
        "Delivery is $3 and a Margherita is $12.",
    ]

    response = client.post(
        "/api/v1/chat",
        json={"conversation_id": None, "business_slug": "joes-pizza", "message": "Prices?"},
    )

    assert response.status_code == 200
    body = response.json()
    UUID(body["conversation_id"])  # a new, valid id
    assert body["reply"] == "Delivery is $3 and a Margherita is $12."
    assert body["intent"] == "catalog_question"
    assert body["language"] == "en"
    assert body["handoff"] is False
    assert body["sources"] == [
        {"title": CHUNK.title, "source_path": CHUNK.source_path, "section": CHUNK.section},
        {
            "title": MENU_CHUNK.title,
            "source_path": MENU_CHUNK.source_path,
            "section": MENU_CHUNK.section,
        },
    ]


def test_second_message_with_the_returned_id_continues_the_conversation(
    client: TestClient, llm: FakeLLM, gateway: FakeGateway
) -> None:
    llm.replies += [
        classification(intent="order_status", order_code="JP-1042"),
        classification(intent="order_status", phone_last4="5678"),
        "Your order JP-1042 is on its way.",
    ]

    first = client.post(
        "/api/v1/chat",
        json={"business_slug": "joes-pizza", "message": "Where is my order JP-1042?"},
    ).json()
    second = client.post(
        "/api/v1/chat",
        json={
            "conversation_id": first["conversation_id"],
            "business_slug": "joes-pizza",
            "message": "5678",
        },
    ).json()

    assert second["conversation_id"] == first["conversation_id"]
    assert second["reply"] == "Your order JP-1042 is on its way."
    assert second["intent"] == "order_status"
    # The order code from message 1 was remembered and used with the digits of message 2.
    assert gateway.order_calls == [("joes-pizza", "JP-1042", "5678")]


def test_complaint_hands_off_to_a_human(
    client: TestClient, llm: FakeLLM, gateway: FakeGateway
) -> None:
    llm.replies += [
        classification(intent="complaint", sentiment="negative"),
        "Customer says the pizza arrived cold.",
    ]

    response = client.post(
        "/api/v1/chat", json={"business_slug": "joes-pizza", "message": "My pizza was cold!"}
    )

    assert response.status_code == 200
    body = response.json()
    assert body["handoff"] is True
    assert body["intent"] == "complaint"
    assert gateway.handoff_calls == [
        (
            "joes-pizza",
            UUID(body["conversation_id"]),
            "COMPLAINT",
            "Customer says the pizza arrived cold.",
        )
    ]
