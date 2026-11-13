"""The entry point for one chat turn: what the chat API (T13) calls.

`AgentService.handle_turn` runs the graph once for the customer's message and
returns a `TurnResult`. Memory is handled by the checkpointer: the same
`conversation_id` continues the same conversation.
"""

from uuid import UUID

from langgraph.graph.state import CompiledStateGraph
from pydantic import BaseModel

from app.agent.checkpointer import create_postgres_checkpointer
from app.agent.deps import AgentDeps
from app.agent.graph import build_graph
from app.agent.intent import Intent, Language
from app.agent.state import Source, empty_memory, new_turn_input, turn_language
from app.core.config import Settings
from app.core.db import connection_factory
from app.gateways.business_gateway import BusinessInfo, HttpBusinessGateway
from app.llm.client import LLMResponse
from app.llm.factory import create_llm_client
from app.observability.turn_log import PostgresTurnLogger
from app.rag.retriever import create_retriever


class TurnResult(BaseModel):
    """Everything the chat API and the turn log need from one turn."""

    reply: str
    intent: Intent | None  # None when guard_input blocked the message
    language: Language
    sources: list[Source]
    handoff: bool
    tool_calls: list[dict]
    llm_usage: list[LLMResponse]


class AgentService:
    def __init__(self, graph: CompiledStateGraph) -> None:
        self._graph = graph

    def conversation_business(self, conversation_id: UUID) -> str | None:
        """The slug of the business this conversation belongs to, or None if it is new.

        The chat API uses it for tenant isolation: a conversation started with
        one business must never be continued with another.
        """
        config = {"configurable": {"thread_id": str(conversation_id)}}
        return self._graph.get_state(config).values.get("business_slug")

    def handle_turn(
        self, conversation_id: UUID, business: BusinessInfo, message: str
    ) -> TurnResult:
        """Answer one customer message of the conversation `conversation_id`."""
        # The thread id tells the checkpointer which conversation's state to load and save.
        config = {"configurable": {"thread_id": str(conversation_id)}}
        turn_input = new_turn_input(
            business_slug=business.slug,
            business_name=business.name,
            default_language=business.default_language,
            conversation_id=str(conversation_id),
            message=message,
        )
        # First turn of this conversation: start with empty memory.
        if not self._graph.get_state(config).values:
            turn_input |= empty_memory()

        state = self._graph.invoke(turn_input, config)

        intent = state["intent"]
        return TurnResult(
            reply=state["reply"],
            intent=intent.intent if intent else None,
            language=turn_language(state),
            sources=state["sources"],
            handoff=state["handoff"],
            tool_calls=state["tool_calls"],
            llm_usage=state["llm_usage"],
        )


def create_agent_service(settings: Settings) -> AgentService:
    """Build the production service: real LLM, pgvector retriever, HTTP gateway, Postgres memory.

    Slow (it loads the embedding model and connects to the database), so call
    it once at startup and reuse the service for every request.
    """
    deps = AgentDeps(
        llm=create_llm_client(settings),
        retriever=create_retriever(settings),
        gateway=HttpBusinessGateway.from_settings(settings),
        top_k=settings.rag_top_k,
        turn_logger=PostgresTurnLogger(
            connection_factory(settings.database_url.get_secret_value())
        ),
    )
    checkpointer = create_postgres_checkpointer(settings.database_url.get_secret_value())
    return AgentService(build_graph(deps, checkpointer))
