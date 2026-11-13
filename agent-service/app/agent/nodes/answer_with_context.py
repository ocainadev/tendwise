"""answer_with_context: answers a catalog question only from the retrieved chunks.

With no chunks there is nothing to ground an answer on, so we skip the LLM and
say we do not know, offering a person instead of guessing.
"""

from app.agent.deps import AgentDeps
from app.agent.prompt_input import chunks_text, history_text, tagged
from app.agent.replies import fixed_reply
from app.agent.state import AgentState, Source
from app.llm.client import Message
from app.prompts import load_prompt
from app.rag.retriever import RetrievedChunk


def run(state: AgentState, deps: AgentDeps) -> dict:
    language = state["intent"].language
    chunks = state["chunks"]
    if not chunks:
        return {"reply": fixed_reply("no_context", language), "sources": []}

    user_input = "\n\n".join(
        [
            f"Business: {state['business_name']}\nReply language: {language}",
            tagged("history", history_text(state["history"])),
            tagged("context", chunks_text(chunks)),
            tagged("customer_message", state["message"]),
        ]
    )
    response = deps.llm.complete(
        load_prompt("answer_with_context"), [Message(role="user", content=user_input)]
    )
    return {
        "reply": response.text.strip(),
        "sources": sources_of(chunks),
        "llm_usage": state["llm_usage"] + [response],
    }


def sources_of(chunks: list[RetrievedChunk]) -> list[Source]:
    """One source per document section, in the chunks' order (best match first)."""
    sources: list[Source] = []
    for chunk in chunks:
        source = Source(title=chunk.title, source_path=chunk.source_path, section=chunk.section)
        if source not in sources:
            sources.append(source)
    return sources
