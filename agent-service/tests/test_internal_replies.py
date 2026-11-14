"""POST /internal/v1/replies (T37): business-service asks for the AI reply.

The real FastAPI app and the real agent graph, with the outside world faked:
the LLM (queued replies), the retriever, business-service and the checkpointer
(in memory).
"""

from uuid import UUID, uuid4

import anthropic
import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.agent.checkpointer import make_serializer
from app.agent.nodes.remember_turn import MAX_HISTORY_MESSAGES
from app.agent.prompt_input import history_text
from app.agent.service import AgentService
from app.agent.staff import StaffMessage
from app.api.chat import get_agent_service, get_gateway, get_rate_limiter
from app.api.rate_limit import SlidingWindowRateLimiter
from app.core.config import Settings, get_settings
from app.gateways.business_gateway import BusinessInfo
from app.llm.client import LLMResponse, Message
from app.main import create_app
from tests.agent.agent_fakes import (
    BUSINESS,
    CHUNK,
    FakeGateway,
    FakeLLM,
    FakeRetriever,
    classification,
    make_service,
)

SERVICE_KEY = "test-service-key"
HEADERS = {"X-Api-Key": SERVICE_KEY}

OTHER_BUSINESS = BusinessInfo(
    slug="denim-corner",
    name="Denim Corner",
    category="clothing",
    default_language="es",
    timezone="Europe/Madrid",
)


class TwoBusinessGateway(FakeGateway):
    """Knows joes-pizza and denim-corner."""

    def get_business(self, slug: str) -> BusinessInfo | None:
        return {b.slug: b for b in (BUSINESS, OTHER_BUSINESS)}.get(slug)


class DownGateway(FakeGateway):
    """business-service is unreachable."""

    def get_business(self, slug: str) -> BusinessInfo | None:
        raise httpx.ConnectError("connection refused by http://business-service:8080")


class FailingLLM:
    """An LLM provider that is down."""

    def complete(
        self, system: str, messages: list[Message], max_tokens: int | None = None
    ) -> LLMResponse:
        raise anthropic.AnthropicError("invalid x-api-key sk-ant-secret")


def make_client(
    service: AgentService | None,
    gateway: FakeGateway | None,
    service_key: str | None = SERVICE_KEY,
) -> TestClient:
    app = create_app()
    settings = Settings(
        database_url="postgresql://test:secret-password@db-host:5432/test",
        business_service_api_key=SecretStr(service_key) if service_key else None,
    )
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_agent_service] = lambda: service
    app.dependency_overrides[get_gateway] = lambda: gateway
    # /api/v1/chat allows one message per conversation here; the internal route has no limit.
    app.dependency_overrides[get_rate_limiter] = lambda: SlidingWindowRateLimiter(1, 600)
    return TestClient(app)


def reply_body(conversation_id: UUID, message: str, **extra: object) -> dict:
    return {
        "conversation_id": str(conversation_id),
        "business_slug": "joes-pizza",
        "message": message,
    } | extra


# --- end to end --------------------------------------------------------------


def test_catalog_question_returns_the_reply_and_its_sources() -> None:
    llm = FakeLLM([classification(intent="catalog_question"), "Delivery is $3."])
    client = make_client(make_service(llm, FakeRetriever([CHUNK])), FakeGateway())

    response = client.post(
        "/internal/v1/replies", json=reply_body(uuid4(), "Delivery fee?"), headers=HEADERS
    )

    assert response.status_code == 200
    assert response.json() == {
        "reply": "Delivery is $3.",
        "intent": "catalog_question",
        "language": "en",
        "sources": [
            {"title": CHUNK.title, "source_path": CHUNK.source_path, "section": CHUNK.section}
        ],
        "handoff": False,
        "order_code": None,
    }


def test_staff_messages_reach_the_prompts_and_stay_in_the_history() -> None:
    llm = FakeLLM([classification(), "You're welcome!", classification(), "See you soon!"])
    service = make_service(llm)
    client = make_client(service, FakeGateway())
    conversation_id = uuid4()
    staff = [
        {"author": "Ana", "body": "We will refund the cold pizza today."},
        {"author": "Ana", "body": "Anything else?"},
    ]

    first = client.post(
        "/internal/v1/replies",
        json=reply_body(conversation_id, "Thanks!", staff_messages=staff),
        headers=HEADERS,
    )
    second = client.post(
        "/internal/v1/replies", json=reply_body(conversation_id, "Bye"), headers=HEADERS
    )

    assert first.status_code == 200
    assert second.status_code == 200
    staff_lines = "Staff (Ana): We will refund the cold pizza today.\nStaff (Ana): Anything else?"
    classifier_input = llm.calls[0][1][0].content
    answer_input = llm.calls[1][1][0].content
    assert staff_lines in classifier_input
    assert staff_lines in answer_input
    # The next turn (no new staff messages) still sees them, before the first AI turn.
    assert (
        staff_lines + "\nCustomer: Thanks!\nAssistant: You're welcome!"
        in llm.calls[3][1][0].content
    )

    history = service._graph.get_state(
        {"configurable": {"thread_id": str(conversation_id)}}
    ).values["history"]
    assert [(m.role, m.content) for m in history] == [
        ("staff", "We will refund the cold pizza today."),
        ("staff", "Anything else?"),
        ("user", "Thanks!"),
        ("assistant", "You're welcome!"),
        ("user", "Bye"),
        ("assistant", "See you soon!"),
    ]


def test_staff_messages_are_added_after_the_earlier_turns() -> None:
    llm = FakeLLM([classification(), "Hi!", classification(), "Great!"])
    client = make_client(make_service(llm), FakeGateway())
    conversation_id = uuid4()

    client.post("/internal/v1/replies", json=reply_body(conversation_id, "Hi"), headers=HEADERS)
    client.post(
        "/internal/v1/replies",
        json=reply_body(
            conversation_id,
            "Ok, thanks",
            staff_messages=[{"author": "Ana", "body": "Your new pizza leaves at 8 pm."}],
        ),
        headers=HEADERS,
    )

    assert (
        "Customer: Hi\nAssistant: Hi!\nStaff (Ana): Your new pizza leaves at 8 pm."
        in llm.calls[3][1][0].content
    )


def test_order_lookup_returns_the_order_code() -> None:
    llm = FakeLLM(
        [
            classification(intent="order_status", order_code="JP-1042", phone_last4="5678"),
            "Your order JP-1042 is on its way.",
        ]
    )
    gateway = FakeGateway()
    client = make_client(make_service(llm, gateway=gateway), gateway)

    response = client.post(
        "/internal/v1/replies",
        json=reply_body(uuid4(), "Where is JP-1042? Phone ends 5678"),
        headers=HEADERS,
    )

    assert response.status_code == 200
    assert response.json()["order_code"] == "JP-1042"
    assert gateway.order_calls == [("joes-pizza", "JP-1042", "5678")]


def test_order_code_given_without_the_phone_is_returned_too() -> None:
    llm = FakeLLM([classification(intent="order_status", order_code="JP-1042")])
    client = make_client(make_service(llm), FakeGateway())

    response = client.post(
        "/internal/v1/replies", json=reply_body(uuid4(), "Where is JP-1042?"), headers=HEADERS
    )

    assert response.json()["order_code"] == "JP-1042"


def test_complaint_hands_off_with_the_business_service_conversation_id() -> None:
    llm = FakeLLM([classification(intent="complaint", sentiment="negative"), "Cold pizza."])
    gateway = FakeGateway()
    client = make_client(make_service(llm, gateway=gateway), gateway)
    conversation_id = uuid4()

    response = client.post(
        "/internal/v1/replies", json=reply_body(conversation_id, "Cold pizza!"), headers=HEADERS
    )

    assert response.json()["handoff"] is True
    assert gateway.handoff_calls == [("joes-pizza", conversation_id, "COMPLAINT", "Cold pizza.")]


def test_there_is_no_per_conversation_rate_limit() -> None:
    llm = FakeLLM([classification(), "Hi!", classification(), "Hi again!"])
    client = make_client(make_service(llm), FakeGateway())  # chat would allow only 1
    conversation_id = uuid4()

    statuses = [
        client.post(
            "/internal/v1/replies", json=reply_body(conversation_id, "Hi"), headers=HEADERS
        ).status_code
        for _ in range(2)
    ]

    assert statuses == [200, 200]


# --- service key -------------------------------------------------------------


@pytest.mark.parametrize("headers", [{}, {"X-Api-Key": "wrong-key"}, {"X-Api-Key": ""}])
def test_missing_or_wrong_service_key_returns_401(headers: dict) -> None:
    llm = FakeLLM([])
    client = make_client(make_service(llm), FakeGateway())

    response = client.post("/internal/v1/replies", json=reply_body(uuid4(), "Hi"), headers=headers)

    assert response.status_code == 401
    assert llm.calls == []


def test_service_key_not_configured_returns_503() -> None:
    llm = FakeLLM([])
    client = make_client(make_service(llm), FakeGateway(), service_key=None)

    response = client.post("/internal/v1/replies", json=reply_body(uuid4(), "Hi"), headers=HEADERS)

    assert response.status_code == 503
    assert llm.calls == []


def test_chat_api_does_not_need_the_service_key() -> None:
    llm = FakeLLM([classification(), "Hi!"])
    client = make_client(make_service(llm), FakeGateway(), service_key=None)

    response = client.post("/api/v1/chat", json={"business_slug": "joes-pizza", "message": "Hi"})

    assert response.status_code == 200


# --- errors, same as /api/v1/chat --------------------------------------------


def test_conversation_of_another_business_returns_409() -> None:
    llm = FakeLLM([classification(), "Hi!"])
    client = make_client(make_service(llm), TwoBusinessGateway())
    conversation_id = uuid4()
    client.post("/internal/v1/replies", json=reply_body(conversation_id, "Hi"), headers=HEADERS)

    response = client.post(
        "/internal/v1/replies",
        json=reply_body(conversation_id, "Hola", business_slug="denim-corner"),
        headers=HEADERS,
    )

    assert response.status_code == 409
    assert len(llm.calls) == 2  # only the first turn reached the LLM


def test_unknown_business_returns_404() -> None:
    client = make_client(make_service(FakeLLM([])), FakeGateway())

    response = client.post(
        "/internal/v1/replies",
        json=reply_body(uuid4(), "Hi", business_slug="no-such-shop"),
        headers=HEADERS,
    )

    assert response.status_code == 404


def test_unreachable_business_service_returns_503() -> None:
    client = make_client(make_service(FakeLLM([])), DownGateway())

    response = client.post("/internal/v1/replies", json=reply_body(uuid4(), "Hi"), headers=HEADERS)

    assert response.status_code == 503
    assert "business-service:8080" not in response.text


def test_llm_error_returns_503_without_leaking_the_error() -> None:
    client = make_client(make_service(FailingLLM()), FakeGateway())  # type: ignore[arg-type]

    response = client.post("/internal/v1/replies", json=reply_body(uuid4(), "Hi"), headers=HEADERS)

    assert response.status_code == 503
    assert "sk-ant-secret" not in response.text


def test_no_agent_service_returns_503() -> None:
    client = make_client(None, FakeGateway())

    response = client.post("/internal/v1/replies", json=reply_body(uuid4(), "Hi"), headers=HEADERS)

    assert response.status_code == 503


@pytest.mark.parametrize(
    "body",
    [
        {"business_slug": "joes-pizza", "message": "Hi"},  # no conversation_id
        reply_body(uuid4(), "Hi", staff_messages=[{"author": "", "body": "Hello"}]),
        reply_body(uuid4(), "Hi", staff_messages=[{"author": "Ana", "body": ""}]),
        reply_body(uuid4(), "Hi", staff_messages=[{"author": "Ana", "body": "x" * 4001}]),
        reply_body(uuid4(), "Hi", staff_messages=[{"author": "Ana", "body": "Hi"}] * 51),
    ],
)
def test_invalid_body_returns_422(body: dict) -> None:
    client = make_client(make_service(FakeLLM([])), FakeGateway())

    response = client.post("/internal/v1/replies", json=body, headers=HEADERS)

    assert response.status_code == 422


# --- history helpers ---------------------------------------------------------


def test_history_text_marks_staff_messages() -> None:
    history = [
        Message(role="user", content="My pizza was cold"),
        StaffMessage(author="Ana", content="Sorry! A new one is on its way."),
        Message(role="assistant", content="Anything else?"),
    ]

    assert history_text(history) == (
        "Customer: My pizza was cold\n"
        "Staff (Ana): Sorry! A new one is on its way.\n"
        "Assistant: Anything else?"
    )


def test_history_with_many_staff_messages_is_capped() -> None:
    llm = FakeLLM([classification(), "Ok!"])
    service = make_service(llm)
    conversation_id = uuid4()
    staff = [StaffMessage(author="Ana", content=f"message {i}") for i in range(50)]

    service.handle_turn(conversation_id, BUSINESS, "Hi", staff)

    history = service._graph.get_state(
        {"configurable": {"thread_id": str(conversation_id)}}
    ).values["history"]
    assert len(history) == MAX_HISTORY_MESSAGES
    assert history[-1].content == "Ok!"


def test_staff_messages_survive_a_checkpoint_round_trip() -> None:
    serializer = make_serializer()
    history = [StaffMessage(author="Ana", content="On its way"), Message(role="user", content="Hi")]

    restored = serializer.loads_typed(serializer.dumps_typed(history))

    assert restored == history
    assert isinstance(restored[0], StaffMessage)
