"""ask_or_call_tool: checks an order's status once we have the code and the phone digits.

The customer must give both (order code + last 4 phone digits) before we call
get_order_status: that is how we check it is their order. Whatever is missing
is asked for, and what was already given is remembered across turns.
"""

from app.agent.deps import AgentDeps
from app.agent.privacy import mask_phone_last4
from app.agent.prompt_input import tagged
from app.agent.replies import fixed_reply
from app.agent.state import AgentState
from app.llm.client import Message
from app.prompts import load_prompt


def run(state: AgentState, deps: AgentDeps) -> dict:
    language = state["intent"].language
    code = state["pending_order_code"]
    digits = state["pending_phone_last4"]

    if not code and not digits:
        return {"reply": fixed_reply("ask_order_code_and_phone", language)}
    if not code:
        return {"reply": fixed_reply("ask_order_code", language)}
    if not digits:
        return {"reply": fixed_reply("ask_phone_last4", language)}

    order = deps.gateway.get_order_status(state["business_slug"], code, digits)
    tool_call = {
        "name": "get_order_status",
        "args": {"order_code": code, "phone_last4": mask_phone_last4(digits)},
        "outcome": "found" if order else "not_found",
    }
    update: dict = {
        "tool_calls": state["tool_calls"] + [tool_call],
        # The tool was called: forget the data, so a wrong pair is asked for again.
        "pending_order_code": None,
        "pending_phone_last4": None,
    }
    if order is None:
        # Same answer for an unknown code and a wrong phone, like business-service.
        update["reply"] = fixed_reply("order_not_found", language)
        return update

    user_input = "\n\n".join(
        [
            f"Business: {state['business_name']}\nReply language: {language}",
            tagged("order_status", order.model_dump_json()),
            tagged("customer_message", state["message"]),
        ]
    )
    response = deps.llm.complete(
        load_prompt("answer_order_status"), [Message(role="user", content=user_input)]
    )
    update["reply"] = response.text.strip()
    update["llm_usage"] = state["llm_usage"] + [response]
    return update
