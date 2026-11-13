"""Wires the nodes into the LangGraph agent graph.

    guard_input ─┬─ (blocked) ──────────────────────────────────────────┐
                 └─ classify_intent → route ─┬─ retrieve → answer_with_context ─┤
                                             ├─ ask_or_call_tool ───────────────┤
                                             ├─ create_handoff ─────────────────┤
                                             └─ answer_generic ─────────────────┤
                                                                                ▼
                                          guard_output → remember_turn → log_turn → END

Every node is a plain function `run(state, deps)`. `functools.partial` fixes
the `deps` argument here, so LangGraph calls each node with the state only.
"""

from functools import partial

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agent.deps import AgentDeps
from app.agent.nodes import (
    answer_generic,
    answer_with_context,
    ask_or_call_tool,
    classify_intent,
    create_handoff,
    guard_input,
    guard_output,
    log_turn,
    remember_turn,
    retrieve,
    route,
)
from app.agent.state import AgentState

NODES = {
    "guard_input": guard_input,
    "classify_intent": classify_intent,
    "route": route,
    "retrieve": retrieve,
    "answer_with_context": answer_with_context,
    "ask_or_call_tool": ask_or_call_tool,
    "create_handoff": create_handoff,
    "answer_generic": answer_generic,
    "guard_output": guard_output,
    "remember_turn": remember_turn,
    "log_turn": log_turn,
}


def after_guard_input(state: AgentState) -> str:
    """A blocked message gets no answer from the agent: go straight to guard_output."""
    return "guard_output" if state["blocked"] else "classify_intent"


def after_route(state: AgentState) -> str:
    """Follow the decision the `route` node stored in the state."""
    return state["next_step"]


def build_graph(deps: AgentDeps, checkpointer: BaseCheckpointSaver) -> CompiledStateGraph:
    """Build and compile the agent graph; the checkpointer gives it per-conversation memory."""
    graph = StateGraph(AgentState)
    for name, module in NODES.items():
        graph.add_node(name, partial(module.run, deps=deps))

    graph.add_edge(START, "guard_input")
    graph.add_conditional_edges(
        "guard_input", after_guard_input, ["classify_intent", "guard_output"]
    )
    graph.add_edge("classify_intent", "route")
    graph.add_conditional_edges(
        "route",
        after_route,
        ["retrieve", "ask_or_call_tool", "create_handoff", "answer_generic"],
    )
    graph.add_edge("retrieve", "answer_with_context")
    for answering_node in (
        "answer_with_context",
        "ask_or_call_tool",
        "create_handoff",
        "answer_generic",
    ):
        graph.add_edge(answering_node, "guard_output")
    graph.add_edge("guard_output", "remember_turn")
    graph.add_edge("remember_turn", "log_turn")
    graph.add_edge("log_turn", END)

    return graph.compile(checkpointer=checkpointer)
