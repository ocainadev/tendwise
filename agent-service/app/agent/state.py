"""The graph state: the one dict every node reads and partly updates.

Why a TypedDict (and not a Pydantic model): LangGraph merges the partial dict a
node returns into the state, and a TypedDict is just a typed `dict`, so a node
is a plain function `state -> dict` that is easy to read and to test. The values
inside can still be Pydantic models (`Message`, `IntentResult`, ...).

There are no reducers: a key a node returns simply replaces the old value. A
node that adds to a list returns the old list plus the new items.

Two kinds of fields:

- Persistent fields (`history`, `negative_streak`, `pending_order_code`,
  `pending_phone_last4`) are saved by the checkpointer at the end of a turn and
  are still there in the next turn of the same conversation.
- Per-turn fields are reset by `new_turn_input()` at the start of every turn,
  so nothing from the previous answer leaks into the next one.
"""

import time
from typing import Literal, TypedDict

from pydantic import BaseModel

from app.agent.intent import IntentResult, Language
from app.agent.staff import StaffMessage
from app.llm.client import LLMResponse, Message
from app.rag.retriever import RetrievedChunk

# The four reasons business-service accepts for a handoff.
HandoffReason = Literal["COMPLAINT", "HUMAN_REQUEST", "NEGATIVE_SENTIMENT", "LOW_CONFIDENCE"]
# The node `route` sends the turn to (one per branch of the graph).
NextStep = Literal["retrieve", "ask_or_call_tool", "create_handoff", "answer_generic"]


class Source(BaseModel):
    """A document the answer was based on, shown to the customer under the reply."""

    title: str
    source_path: str
    section: str | None


class AgentState(TypedDict):
    # --- who we are talking for (set again every turn) ---
    business_slug: str
    business_name: str
    default_language: Language
    conversation_id: str

    # --- persistent: survive across turns of the same conversation ---
    # Every customer message and final reply, oldest first (user, assistant, ...),
    # plus the staff messages of a handoff (T37), in the order they were sent.
    history: list[Message | StaffMessage]
    # Consecutive turns with negative sentiment; back to 0 on any other sentiment.
    negative_streak: int
    # Order data the customer already gave, kept until get_order_status is called.
    pending_order_code: str | None
    pending_phone_last4: str | None

    # --- per-turn: reset at the start of every turn ---
    message: str  # the customer's message of this turn
    started_at: float  # time.monotonic() when the turn began, for latency (T12)
    intent: IntentResult | None
    next_step: NextStep | None
    handoff_reason: HandoffReason | None
    chunks: list[RetrievedChunk]
    reply: str
    sources: list[Source]
    handoff: bool
    blocked: bool  # set by guard_input (T11): skip straight to guard_output
    # The reply language guard_input guessed for a blocked message (it never
    # reaches the classifier). None when the message passed.
    blocked_language: Language | None
    # Why guard_input blocked the message, for the turn log (T12): "length:empty",
    # "length:too_long", "injection:<rule>" or "abuse". None when it passed.
    guard_reason: str | None
    # One dict per tool call: {"name", "args" (phone masked), "outcome"}.
    tool_calls: list[dict]
    # Every LLM call of this turn, for cost logging (T12).
    llm_usage: list[LLMResponse]


def new_turn_input(
    business_slug: str,
    business_name: str,
    default_language: Language,
    conversation_id: str,
    message: str,
) -> dict:
    """The input of one turn: business data, the new message, and every per-turn field reset.

    Persistent fields are left out on purpose, so the values saved by the
    checkpointer in the previous turn are kept.
    """
    return {
        "business_slug": business_slug,
        "business_name": business_name,
        "default_language": default_language,
        "conversation_id": conversation_id,
        "message": message,
        "started_at": time.monotonic(),
        "intent": None,
        "next_step": None,
        "handoff_reason": None,
        "chunks": [],
        "reply": "",
        "sources": [],
        "handoff": False,
        "blocked": False,
        "guard_reason": None,
        "blocked_language": None,
        "tool_calls": [],
        "llm_usage": [],
    }


def turn_language(state: AgentState) -> Language:
    """The language of this turn's reply: classified, guessed for a blocked message, or default."""
    if state["intent"] is not None:
        return state["intent"].language
    return state.get("blocked_language") or state["default_language"]


def empty_memory() -> dict:
    """The persistent fields of a conversation that has no turns yet."""
    return {
        "history": [],
        "negative_streak": 0,
        "pending_order_code": None,
        "pending_phone_last4": None,
    }
