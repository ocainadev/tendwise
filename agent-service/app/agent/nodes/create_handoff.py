"""create_handoff: passes the conversation to a person.

The LLM writes a short summary for staff, the gateway opens the handoff in
business-service, and the customer gets a fixed "a person will continue" reply
(a fixed text: it must always be correct, so no LLM is needed for it).
"""

from uuid import UUID

from app.agent.deps import AgentDeps
from app.agent.prompt_input import history_text, tagged
from app.agent.replies import fixed_reply
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

    handoff_id = deps.gateway.create_handoff(
        state["business_slug"], UUID(state["conversation_id"]), reason, summary
    )
    tool_call = {
        "name": "create_handoff",
        "args": {"reason": reason},
        "outcome": f"created handoff {handoff_id}",
    }
    return {
        "reply": fixed_reply("handoff", state["intent"].language),
        "handoff": True,
        "tool_calls": state["tool_calls"] + [tool_call],
        "llm_usage": state["llm_usage"] + [response],
    }
