"""Fakes for the agent graph tests: no LLM, no database, no network."""

import json
from datetime import UTC, datetime
from uuid import UUID

from app.agent.checkpointer import create_memory_checkpointer
from app.agent.deps import AgentDeps
from app.agent.graph import build_graph
from app.agent.service import AgentService
from app.gateways.business_gateway import BusinessInfo, OrderStatusInfo
from app.llm.client import LLMResponse, Message
from app.rag.retriever import RetrievedChunk

BUSINESS = BusinessInfo(
    slug="joes-pizza",
    name="Joe's Pizza",
    category="restaurant",
    default_language="en",
    timezone="America/New_York",
)

ORDER = OrderStatusInfo(
    code="JP-1042",
    status="OUT_FOR_DELIVERY",
    fulfilment="DELIVERY",
    eta=datetime(2026, 11, 12, 19, 30, tzinfo=UTC),
    updated_at=datetime(2026, 11, 12, 19, 0, tzinfo=UTC),
)

CHUNK = RetrievedChunk(
    content="Delivery costs $3 inside the city.",
    source_path="joes-pizza/faq.md",
    title="Joe's Pizza – FAQ",
    section="Joe's Pizza – FAQ > Delivery > Fees",
    similarity=0.71,
)


def classification(**overrides: object) -> str:
    """A valid classifier reply (JSON text), with some fields overridden."""
    data: dict[str, object] = {
        "intent": "other",
        "confidence": 0.9,
        "sentiment": "neutral",
        "language": "en",
        "order_code": None,
        "phone_last4": None,
    }
    data.update(overrides)
    return json.dumps(data)


class FakeLLM:
    """Returns queued replies in order and records every call (system prompt, messages)."""

    def __init__(self, replies: list[str]) -> None:
        self.replies = list(replies)
        self.calls: list[tuple[str, list[Message]]] = []

    def complete(
        self, system: str, messages: list[Message], max_tokens: int | None = None
    ) -> LLMResponse:
        self.calls.append((system, messages))
        return LLMResponse(
            text=self.replies.pop(0), model="fake-model", prompt_tokens=50, completion_tokens=10
        )


class FakeRetriever:
    def __init__(self, chunks: list[RetrievedChunk]) -> None:
        self.chunks = chunks
        self.calls: list[tuple[str, str, int]] = []

    def retrieve(self, business_slug: str, query: str, top_k: int = 5) -> list[RetrievedChunk]:
        self.calls.append((business_slug, query, top_k))
        return self.chunks


class FakeGateway:
    """Returns `order` for any lookup and records the order and handoff calls."""

    def __init__(self, order: OrderStatusInfo | None = ORDER) -> None:
        self.order = order
        self.order_calls: list[tuple[str, str, str]] = []
        self.handoff_calls: list[tuple[str, UUID, str, str]] = []

    def get_business(self, slug: str) -> BusinessInfo | None:
        return BUSINESS if slug == BUSINESS.slug else None

    def get_order_status(self, slug: str, code: str, phone_last4: str) -> OrderStatusInfo | None:
        self.order_calls.append((slug, code, phone_last4))
        return self.order

    def create_handoff(self, slug: str, conversation_id: UUID, reason: str, summary: str) -> int:
        self.handoff_calls.append((slug, conversation_id, reason, summary))
        return 42


def make_service(
    llm: FakeLLM,
    retriever: FakeRetriever | None = None,
    gateway: FakeGateway | None = None,
) -> AgentService:
    """An AgentService over the real graph, with fakes and in-memory checkpoints."""
    deps = AgentDeps(
        llm=llm,
        retriever=retriever or FakeRetriever([]),
        gateway=gateway or FakeGateway(),
        top_k=5,
    )
    return AgentService(build_graph(deps, create_memory_checkpointer()))
