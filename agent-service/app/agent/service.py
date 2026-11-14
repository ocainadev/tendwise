"""The entry point for one chat turn: what the chat API (T13) calls.

`AgentService.handle_turn` runs the graph once for the customer's message and
returns a `TurnResult`. Memory is handled by the checkpointer: the same
`conversation_id` continues the same conversation.

Both `POST /api/v1/chat` and `POST /internal/v1/replies` (T37) call it; the
internal route also passes the staff messages of a handoff.
"""

from uuid import UUID

from langgraph.graph.state import CompiledStateGraph
from pydantic import BaseModel

from app.agent.checkpointer import create_postgres_checkpointer
from app.agent.deps import AgentDeps
from app.agent.graph import build_graph
from app.agent.intent import Intent, Language
from app.agent.nodes.remember_turn import MAX_HISTORY_MESSAGES
from app.agent.staff import StaffMessage
from app.agent.state import AgentState, Source, empty_memory, new_turn_input, turn_language
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
    # The order code the agent saw this turn (looked up, or given by the customer), if any.
    order_code: str | None = None
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
        self,
        conversation_id: UUID,
        business: BusinessInfo,
        message: str,
        staff_messages: list[StaffMessage] | None = None,
    ) -> TurnResult:
        """Answer one customer message of the conversation `conversation_id`.

        `staff_messages` are what staff told the customer since the last AI turn.
        They are added to the history before the graph runs, so every prompt of
        this turn (and of later turns) sees them.
        """
        # The thread id tells the checkpointer which conversation's state to load and save.
        config = {"configurable": {"thread_id": str(conversation_id)}}
        turn_input = new_turn_input(
            business_slug=business.slug,
            business_name=business.name,
            default_language=business.default_language,
            conversation_id=str(conversation_id),
            message=message,
        )
        saved = self._graph.get_state(config).values
        # First turn of this conversation: start with empty memory.
        if not saved:
            turn_input |= empty_memory()
        if staff_messages:
            history = saved.get("history", []) + staff_messages
            turn_input["history"] = history[-MAX_HISTORY_MESSAGES:]

        state = self._graph.invoke(turn_input, config)

        intent = state["intent"]
        return TurnResult(
            reply=state["reply"],
            intent=intent.intent if intent else None,
            language=turn_language(state),
            sources=state["sources"],
            handoff=state["handoff"],
            order_code=seen_order_code(state),
            tool_calls=state["tool_calls"],
            llm_usage=state["llm_usage"],
        )


def seen_order_code(state: AgentState) -> str | None:
    """The order code of this turn: the one looked up, else the one the classifier found.

    The lookup comes first because the code may come from an earlier turn (the
    customer gave the code, then the phone digits).
    """
    for call in state["tool_calls"]:
        if call["name"] == "get_order_status":
            return call["args"]["order_code"]
    intent = state["intent"]
    return intent.order_code if intent else None


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
