"""POST /api/v1/panel/{slug}/playground (T51): the knowledge page's test chat.

The real app and the real agent graph; the LLM, retriever, business-service and
checkpointer are faked, and panel tokens are signed here (fake JWKS), so this
needs no database and no network.
"""

from uuid import UUID, uuid4

from fastapi.testclient import TestClient

from app.agent.checkpointer import create_memory_checkpointer
from app.agent.deps import AgentDeps
from app.agent.graph import build_graph
from app.agent.service import AgentService
from app.api.chat import get_agent_service, get_gateway, get_rate_limiter
from app.api.rate_limit import SlidingWindowRateLimiter
from app.auth.panel import ACCESS_COOKIE, CSRF_COOKIE, CSRF_HEADER
from app.main import create_app
from app.observability.turn_log import TurnRecord
from tests.agent.agent_fakes import (
    CHUNK,
    FakeGateway,
    FakeLLM,
    FakeRetriever,
    classification,
)
from tests.auth.auth_fakes import FakeJwksServer, SigningKey, claims, make_cache

KEY = SigningKey("key-1")
CSRF = "csrf-token-value"
URL = "/api/v1/panel/joes-pizza/playground"
WRITE = {CSRF_HEADER: CSRF}
OWNER = [{"slug": "joes-pizza", "role": "OWNER"}]
AGENT = [{"slug": "joes-pizza", "role": "AGENT"}]


class RecordingTurnLogger:
    def __init__(self) -> None:
        self.records: list[TurnRecord] = []

    def log(self, record: TurnRecord) -> None:
        self.records.append(record)


def make_client(
    service: AgentService, gateway: FakeGateway, memberships: list[dict[str, str]] = OWNER
) -> TestClient:
    app = create_app()
    app.state.jwks_cache = make_cache(FakeJwksServer(KEY))
    app.dependency_overrides[get_agent_service] = lambda: service
    app.dependency_overrides[get_gateway] = lambda: gateway
    app.dependency_overrides[get_rate_limiter] = lambda: SlidingWindowRateLimiter(10, 600)
    client = TestClient(app)
    client.cookies.set(ACCESS_COOKIE, KEY.sign(claims(memberships)))
    client.cookies.set(CSRF_COOKIE, CSRF)
    return client


def service_with(
    llm: FakeLLM, gateway: FakeGateway, logger: RecordingTurnLogger | None = None
) -> AgentService:
    """The real graph with fakes; `logger` records the stored turns."""
    deps = AgentDeps(
        llm=llm,
        retriever=FakeRetriever([CHUNK]),
        gateway=gateway,
        top_k=5,
        turn_logger=logger or RecordingTurnLogger(),
    )
    return AgentService(build_graph(deps, create_memory_checkpointer()))


def test_an_owner_gets_the_answer_and_its_sources() -> None:
    logger = RecordingTurnLogger()
    llm = FakeLLM([classification(intent="catalog_question"), "Delivery is $3."])
    gateway = FakeGateway()
    client = make_client(service_with(llm, gateway, logger), gateway)

    response = client.post(URL, json={"message": "Delivery fee?"}, headers=WRITE)

    assert response.status_code == 200
    body = response.json()
    UUID(body["conversationId"])
    assert body["reply"] == "Delivery is $3."
    assert body["sources"] == [
        {"title": CHUNK.title, "sourcePath": CHUNK.source_path, "section": CHUNK.section}
    ]
    assert body["handoff"] is False
    # Stored with the playground channel, which the usage report leaves out.
    assert [record.channel for record in logger.records] == ["playground"]


def test_a_playground_handoff_is_not_sent_to_business_service() -> None:
    llm = FakeLLM([classification(intent="human_request"), "Summary for staff."])
    gateway = FakeGateway()
    client = make_client(service_with(llm, gateway), gateway)

    response = client.post(URL, json={"message": "I want a person"}, headers=WRITE)

    assert response.status_code == 200
    assert response.json()["handoff"] is True
    assert gateway.handoff_calls == []


def test_the_conversation_continues_with_the_same_id() -> None:
    llm = FakeLLM([classification(), "Hi!", classification(), "Sure."])
    gateway = FakeGateway()
    client = make_client(service_with(llm, gateway), gateway)

    first = client.post(URL, json={"message": "Hi"}, headers=WRITE).json()
    second = client.post(
        URL, json={"conversationId": first["conversationId"], "message": "Ok"}, headers=WRITE
    )

    assert second.status_code == 200
    assert second.json()["conversationId"] == first["conversationId"]
    assert "Customer: Hi\nAssistant: Hi!" in llm.calls[3][1][0].content


def test_a_customer_conversation_cannot_be_continued_in_the_playground() -> None:
    llm = FakeLLM([classification(), "Hi!"])
    gateway = FakeGateway()
    service = service_with(llm, gateway)
    customer_conversation = uuid4()
    service.handle_turn(customer_conversation, gateway.get_business("joes-pizza"), "Hi")
    client = make_client(service, gateway)

    response = client.post(
        URL,
        json={"conversationId": str(customer_conversation), "message": "Hello"},
        headers=WRITE,
    )

    assert response.status_code == 409


def test_an_agent_cannot_use_the_playground() -> None:
    gateway = FakeGateway()
    client = make_client(service_with(FakeLLM([]), gateway), gateway, memberships=AGENT)

    response = client.post(URL, json={"message": "Hi"}, headers=WRITE)

    assert response.status_code == 403


def test_the_playground_needs_the_csrf_header() -> None:
    gateway = FakeGateway()
    client = make_client(service_with(FakeLLM([]), gateway), gateway)

    response = client.post(URL, json={"message": "Hi"})

    assert response.status_code == 403
