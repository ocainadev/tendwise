"""What the nodes need from the outside world, passed in explicitly (no globals).

`graph.py` binds one `AgentDeps` to every node, so tests can build the same
graph with a fake LLM, a fake retriever and a fake gateway.
"""

from dataclasses import dataclass, field

from app.gateways.business_gateway import BusinessGateway
from app.llm.client import LLMClient
from app.observability.turn_log import NoOpTurnLogger, TurnLogger
from app.rag.retriever import Retriever


@dataclass(frozen=True)
class AgentDeps:
    llm: LLMClient
    retriever: Retriever
    gateway: BusinessGateway
    # How many chunks `retrieve` asks for (RAG_TOP_K).
    top_k: int = 5
    # Where `log_turn` stores each turn (T12). Stores nothing unless one is passed.
    turn_logger: TurnLogger = field(default_factory=NoOpTurnLogger)
