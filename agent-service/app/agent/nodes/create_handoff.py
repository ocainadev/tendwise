"""create_handoff: passes the conversation to a person.

The LLM writes a short summary for staff, the gateway opens the handoff in
business-service, and the customer gets a fixed "a person will continue" reply
(a fixed text: it must always be correct, so no LLM is needed for it).

Outside opening hours (T54) the reply says when the team is back, in the
business's time zone, and asks for a contact: the owner's out-of-hours message
when set, else a fixed sentence.

In the playground (T51) there is no business-service conversation to hand
over, so the handoff is only reported in the tool call, never created.
"""

from uuid import UUID

from app.agent.deps import AgentDeps
from app.agent.opening_hours import closed_until
from app.agent.prompt_input import history_text, tagged
from app.agent.replies import fixed_reply, reopening_text
from app.agent.state import AgentState
from app.llm.client import Message
from app.prompts import load_prompt

# business-service rejects summaries longer than this.
MAX_SUMMARY_CHARS = 2000
# Staff need the whole story, so the summary sees more history than an answer.
SUMMARY_HISTORY_MESSAGES = 20


def run(state: AgentState, deps: AgentDeps) -> dict:
    reason = state["handoff_reason"]
    user_input = "\n\n".join(
        [
            f"Business: {state['business_name']}\n"
            f"Handoff reason: {reason}\n"
            f"Summary language: {state['default_language']}",
            tagged("history", history_text(state["history"], last=SUMMARY_HISTORY_MESSAGES)),
            tagged("latest_customer_message", state["message"]),
        ]
    )
    response = deps.llm.complete(
        load_prompt("handoff_summary"), [Message(role="user", content=user_input)]
    )
    summary = response.text.strip()[:MAX_SUMMARY_CHARS]

    if state["channel"] == "playground":
        outcome = "playground: no handoff created"
    else:
        handoff_id = deps.gateway.create_handoff(
            state["business_slug"], UUID(state["conversation_id"]), reason, summary
        )
        outcome = f"created handoff {handoff_id}"
    tool_call = {"name": "create_handoff", "args": {"reason": reason}, "outcome": outcome}
    return {
        "reply": handoff_reply(state),
        "handoff": True,
        "tool_calls": state["tool_calls"] + [tool_call],
        "llm_usage": state["llm_usage"] + [response],
    }


def handoff_reply(state: AgentState) -> str:
    """What the customer reads after the handoff: "a person will continue", or when we are back."""
    language = state["intent"].language
    closed, reopens_at = closed_until(state["now"], state["timezone"], state["opening_hours"])
    if not closed:
        return fixed_reply("handoff", language)

    if reopens_at is None:
        closed_text = fixed_reply("handoff_closed_no_time", language)
    else:
        local_now = state["now"].astimezone(reopens_at.tzinfo)
        when = reopening_text(reopens_at, local_now, language)
        closed_text = fixed_reply("handoff_closed", language, reopening=when)
    contact_text = state["assistant"].out_of_hours_message or fixed_reply("leave_contact", language)
    return f"{closed_text} {contact_text}"
