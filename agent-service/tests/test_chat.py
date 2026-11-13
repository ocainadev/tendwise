"""POST /api/v1/chat: status codes, error mapping and startup wiring."""

from collections.abc import Iterator
from uuid import uuid4

import anthropic
import httpx
import openai
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app import main
from app.agent import service as service_module
from app.agent.checkpointer import create_memory_checkpointer
from app.agent.deps import AgentDeps
from app.agent.graph import build_graph
from app.agent.service import AgentService
from app.api import health
from app.api.chat import get_agent_service, get_gateway, get_rate_limiter
from app.api.rate_limit import SlidingWindowRateLimiter, create_rate_limiter
from app.core.config import Settings, get_settings
from app.gateways.business_gateway import BusinessInfo, HttpBusinessGateway
from app.llm.client import LLMResponse, Message
from app.main import create_app
from app.observability.turn_log import PostgresTurnLogger
from tests.agent.agent_fakes import (
    BUSINESS,
    FakeGateway,
    FakeLLM,
    FakeRetriever,
    classification,
    make_service,
)

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


class OrderLookupDownGateway(FakeGateway):
    """The business lookup works, but the order status call fails."""

    def get_order_status(self, slug: str, code: str, phone_last4: str) -> None:
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
    limiter: SlidingWindowRateLimiter | None = None,
) -> TestClient:
    app = create_app()
    app.dependency_overrides[get_agent_service] = lambda: service
    app.dependency_overrides[get_gateway] = lambda: gateway
    app.dependency_overrides[get_rate_limiter] = lambda: (
        limiter or SlidingWindowRateLimiter(20, 600)
    )
    return TestClient(app)


def post(client: TestClient, message: str = "Hi", **fields: object) -> httpx.Response:
    return client.post(
        "/api/v1/chat", json={"business_slug": "joes-pizza", "message": message, **fields}
    )


# --- 404 / 409 / 429 / 422 -----------------------------------------------------


def test_unknown_business_returns_404() -> None:
    client = make_client(make_service(FakeLLM([])), FakeGateway())

    response = client.post("/api/v1/chat", json={"business_slug": "nope", "message": "Hi"})

    assert response.status_code == 404
    assert response.json() == {"detail": "Business not found"}


def test_unknown_business_returns_404_even_without_an_agent() -> None:
    # e.g. no ANTHROPIC_API_KEY: the business lookup still works.
    client = make_client(None, FakeGateway())

    response = client.post("/api/v1/chat", json={"business_slug": "nope", "message": "Hi"})

    assert response.status_code == 404


def test_conversation_of_another_business_returns_409() -> None:
    llm = FakeLLM([classification(), "Hi!"])
    client = make_client(make_service(llm), TwoBusinessGateway())
    conversation_id = post(client).json()["conversation_id"]

    response = client.post(
        "/api/v1/chat",
        json={
            "conversation_id": conversation_id,
            "business_slug": "denim-corner",
            "message": "Hola",
        },
    )

    assert response.status_code == 409
    assert response.json() == {"detail": "Conversation belongs to another business"}
    assert len(llm.calls) == 2  # only the first turn reached the LLM


def test_unknown_conversation_id_starts_a_conversation_with_that_id() -> None:
    client = make_client(make_service(FakeLLM([classification(), "Hi!"])), FakeGateway())
    conversation_id = str(uuid4())

    response = post(client, conversation_id=conversation_id)

    assert response.status_code == 200
    assert response.json()["conversation_id"] == conversation_id


def test_too_many_messages_in_a_conversation_returns_429(settings: Settings) -> None:
    small = settings.model_copy(
        update={"chat_rate_limit_messages": 2, "chat_rate_limit_window_seconds": 60}
    )
    llm = FakeLLM([classification(), "Hi!", classification(), "Hi again!"])
    client = make_client(make_service(llm), FakeGateway(), create_rate_limiter(small))

    conversation_id = post(client).json()["conversation_id"]
    second = post(client, conversation_id=conversation_id)
    third = post(client, conversation_id=conversation_id)

    assert second.status_code == 200
    assert third.status_code == 429
    assert "Too many messages" in third.json()["detail"]
    assert len(llm.calls) == 4  # the rejected message never reached the LLM


@pytest.mark.parametrize(
    "body",
    [
        {"message": "Hi"},  # no business_slug
        {"business_slug": "joes-pizza"},  # no message
        {"business_slug": "joes-pizza", "message": "x" * 4001},
        {"business_slug": "Joe's Pizza", "message": "Hi"},  # not a slug
        {"business_slug": "joes-pizza", "message": "Hi", "conversation_id": "not-a-uuid"},
    ],
)
def test_invalid_body_returns_422(body: dict) -> None:
    llm = FakeLLM([])
    client = make_client(make_service(llm), FakeGateway())

    response = client.post("/api/v1/chat", json=body)

    assert response.status_code == 422
    assert llm.calls == []


def test_message_of_exactly_4000_characters_is_accepted() -> None:
    client = make_client(make_service(FakeLLM([classification(), "Hi!"])), FakeGateway())

    assert post(client, message="x" * 4000).status_code == 200


# --- 503 mapping ---------------------------------------------------------------


def test_unreachable_business_service_returns_503(caplog: pytest.LogCaptureFixture) -> None:
    client = make_client(make_service(FakeLLM([])), DownGateway())

    response = post(client)

    assert response.status_code == 503
    assert response.json() == {"detail": "Business service unavailable"}
    assert "ConnectError" in caplog.text
    assert "business-service:8080" not in caplog.text + response.text


def test_business_service_failing_inside_a_tool_returns_503() -> None:
    llm = FakeLLM([classification(intent="order_status", order_code="JP-1042", phone_last4="5678")])
    gateway = OrderLookupDownGateway()
    client = make_client(make_service(llm, gateway=gateway), gateway)

    response = post(client, message="JP-1042, 5678")

    assert response.status_code == 503
    assert response.json() == {"detail": "Business service unavailable"}


def test_no_gateway_returns_503() -> None:
    client = make_client(make_service(FakeLLM([])), None)

    assert post(client).json() == {"detail": "Business service unavailable"}


def test_llm_error_returns_503_without_leaking_the_error(
    caplog: pytest.LogCaptureFixture,
) -> None:
    client = make_client(make_service(FailingLLM()), FakeGateway())  # type: ignore[arg-type]

    response = post(client, message="My phone is 555-0102")

    assert response.status_code == 503
    assert response.json() == {"detail": "Assistant temporarily unavailable"}
    assert "AnthropicError" in caplog.text
    for text in (caplog.text, response.text):
        assert "sk-ant-secret" not in text
        assert "555-0102" not in text


class FailingOpenAI:
    """The OpenAI provider is down."""

    def complete(
        self, system: str, messages: list[Message], max_tokens: int | None = None
    ) -> LLMResponse:
        raise openai.APIConnectionError(
            message="cannot reach api.openai.com with sk-proj-secret", request=None
        )


def test_openai_error_returns_503_without_leaking_the_error(
    caplog: pytest.LogCaptureFixture,
) -> None:
    client = make_client(make_service(FailingOpenAI()), FakeGateway())  # type: ignore[arg-type]

    response = post(client)

    assert response.status_code == 503
    assert response.json() == {"detail": "Assistant temporarily unavailable"}
    assert "APIConnectionError" in caplog.text
    for text in (caplog.text, response.text):
        assert "sk-proj-secret" not in text


def test_no_agent_service_returns_503() -> None:
    client = make_client(None, FakeGateway())

    response = post(client)

    assert response.status_code == 503
    assert response.json() == {"detail": "Assistant temporarily unavailable"}


# --- startup wiring --------------------------------------------------------------


def configured(settings: Settings) -> Settings:
    return settings.model_copy(
        update={
            "anthropic_api_key": SecretStr("test-llm-key"),
            "business_service_api_key": SecretStr("test-business-key"),
        }
    )


def test_startup_builds_the_agent_service_and_the_gateway(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    service = make_service(FakeLLM([]))
    monkeypatch.setattr(main, "create_agent_service", lambda s: service)
    app = FastAPI()

    main.build_chat_services(app, configured(settings))

    assert app.state.agent_service is service
    assert isinstance(app.state.gateway, HttpBusinessGateway)
    assert isinstance(app.state.rate_limiter, SlidingWindowRateLimiter)


def test_startup_agent_writes_turns_to_postgres(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The real create_agent_service, with only its slow parts replaced."""
    built: list[AgentDeps] = []

    def capture_graph(deps: AgentDeps, checkpointer: object) -> object:
        built.append(deps)
        return build_graph(deps, create_memory_checkpointer())

    monkeypatch.setattr(service_module, "create_retriever", lambda s: FakeRetriever([]))
    monkeypatch.setattr(service_module, "create_postgres_checkpointer", lambda url: None)
    monkeypatch.setattr(service_module, "build_graph", capture_graph)
    app = FastAPI()

    main.build_chat_services(app, configured(settings))

    assert isinstance(app.state.agent_service, AgentService)
    assert isinstance(built[0].turn_logger, PostgresTurnLogger)


def test_startup_without_llm_key_skips_the_agent_service(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    def must_not_be_called(s: Settings) -> AgentService:
        raise AssertionError("the agent must not be built without a key")

    monkeypatch.setattr(main, "create_agent_service", must_not_be_called)
    app = FastAPI()

    main.build_chat_services(app, settings)  # no keys at all

    assert app.state.agent_service is None
    assert app.state.gateway is None


def test_startup_with_openai_builds_the_agent_service_with_only_its_key(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    service = make_service(FakeLLM([]))
    monkeypatch.setattr(main, "create_agent_service", lambda s: service)
    app = FastAPI()
    openai_settings = settings.model_copy(
        update={
            "llm_provider": "openai",
            "openai_api_key": SecretStr("test-openai-key"),
            "business_service_api_key": SecretStr("test-business-key"),
        }
    )

    main.build_chat_services(app, openai_settings)

    assert app.state.agent_service is service


def test_startup_with_openai_but_no_openai_key_names_that_key(
    settings: Settings, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    def must_not_be_called(s: Settings) -> AgentService:
        raise AssertionError("the agent must not be built without a key")

    monkeypatch.setattr(main, "create_agent_service", must_not_be_called)
    app = FastAPI()
    # Only the Anthropic key is set: it does not count for OpenAI.
    openai_settings = configured(settings).model_copy(update={"llm_provider": "openai"})

    main.build_chat_services(app, openai_settings)

    assert app.state.agent_service is None
    assert "OPENAI_API_KEY is not set" in caplog.text


@pytest.fixture
def started_app(settings: Settings, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    """The real lifespan with a database that is down."""

    def database_down(s: Settings) -> AgentService:
        raise ConnectionRefusedError("db-host:5432")

    monkeypatch.setattr(main, "migrate_database", lambda: None)
    monkeypatch.setattr(main, "create_agent_service", database_down)
    monkeypatch.setattr(main, "get_settings", lambda: configured(settings))
    monkeypatch.setattr(main.HttpBusinessGateway, "from_settings", lambda s: FakeGateway())
    monkeypatch.setattr(health, "check_database", lambda url: None)
    app = create_app(run_startup=True)
    app.dependency_overrides[get_settings] = lambda: settings
    with TestClient(app) as client:
        yield client


def test_app_still_starts_when_the_agent_cannot_be_built(started_app: TestClient) -> None:
    assert started_app.get("/health").status_code == 200

    response = post(started_app)

    assert response.status_code == 503
    assert response.json() == {"detail": "Assistant temporarily unavailable"}
