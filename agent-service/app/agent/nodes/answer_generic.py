"""answer_generic: greetings, thanks and anything that is not a catalog or order question.

It stays in the business's scope: it greets, says what it can help with, and
politely declines off-topic requests (poems, homework, other companies).
"""

from app.agent.deps import AgentDeps
from app.agent.prompt_input import history_text, tagged
from app.agent.state import AgentState
from app.llm.client import Message
from app.prompts import load_prompt


def run(state: AgentState, deps: AgentDeps) -> dict:
    user_input = "\n\n".join(
        [
            f"Business: {state['business_name']}\nReply language: {state['intent'].language}",
            tagged("history", history_text(state["history"])),
            tagged("customer_message", state["message"]),
        ]
    )
    response = deps.llm.complete(
        load_prompt("answer_generic"), [Message(role="user", content=user_input)]
    )
    return {"reply": response.text.strip(), "llm_usage": state["llm_usage"] + [response]}
