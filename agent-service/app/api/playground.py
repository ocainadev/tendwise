"""POST /api/v1/panel/{slug}/playground: the knowledge page's test chat (T51).

The owner asks a question right after editing a document and sees the answer
and the sources it used. It runs the same agent graph as `POST /api/v1/chat`,
with `channel = "playground"`:

- the conversation is stored in `agent.conversations` with that channel, and
  the usage report leaves it out;
- `create_handoff` creates nothing in business-service (there is no inbox
  conversation); the response only says a handoff would have happened.

The checks, each with its status: panel session, CSRF and OWNER role (401/403/
404, `require_panel_role`), a known conversation must be a playground one of the
same business (409), the per-conversation rate limit (429), then the turn (503
when the LLM or business-service is down). JSON is camelCase, like the rest of
the panel API.
"""

from typing import Annotated
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.agent.intent import Intent, Language
from app.agent.service import AgentService
from app.agent.state import Source
from app.api.chat import (
    MAX_MESSAGE_LENGTH,
    TOO_MANY_MESSAGES,
    check_conversation_owner,
    find_business,
    get_agent_service,
    get_gateway,
    get_rate_limiter,
    require_agent_service,
    run_turn,
)
from app.api.rate_limit import SlidingWindowRateLimiter
from app.auth.panel import PanelAccess, require_panel_role
from app.gateways.business_gateway import BusinessGateway

router = APIRouter(prefix="/api/v1/panel/{slug}/playground")

NOT_A_PLAYGROUND = "Conversation is not a playground conversation"

Owner = Annotated[PanelAccess, Depends(require_panel_role("OWNER"))]


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class PlaygroundRequest(CamelModel):
    # None (or absent) starts a new test conversation.
    conversation_id: UUID | None = None
    message: str = Field(max_length=MAX_MESSAGE_LENGTH)


class PlaygroundSource(CamelModel):
    title: str
    source_path: str
    section: str | None


class PlaygroundResponse(CamelModel):
    conversation_id: UUID
    reply: str
    # None when guard_input blocked the message before it was classified.
    intent: Intent | None
    language: Language
    sources: list[PlaygroundSource]
    # True when a real chat would have been handed to a person.
    handoff: bool


def playground_source(source: Source) -> PlaygroundSource:
    return PlaygroundSource(
        title=source.title, source_path=source.source_path, section=source.section
    )


# A plain `def`: the graph and the HTTP calls block, so FastAPI runs it in a thread pool.
@router.post("", response_model=PlaygroundResponse, response_model_by_alias=True)
def ask(
    body: PlaygroundRequest,
    access: Owner,
    agent_service: Annotated[AgentService | None, Depends(get_agent_service)],
    gateway: Annotated[BusinessGateway | None, Depends(get_gateway)],
    rate_limiter: Annotated[SlidingWindowRateLimiter, Depends(get_rate_limiter)],
) -> PlaygroundResponse:
    """Answer one test question as the assistant would answer a customer."""
    conversation_id = body.conversation_id or uuid4()
    business = find_business(gateway, access.business_slug, conversation_id)
    service = require_agent_service(agent_service)
    if body.conversation_id is not None:
        check_conversation_owner(service, body.conversation_id, business)
        channel = service.conversation_channel(body.conversation_id)
        if channel not in (None, "playground"):
            raise HTTPException(status.HTTP_409_CONFLICT, NOT_A_PLAYGROUND)

    if not rate_limiter.allow(str(conversation_id)):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, TOO_MANY_MESSAGES)

    result = run_turn(service, conversation_id, business, body.message, channel="playground")
    return PlaygroundResponse(
        conversation_id=conversation_id,
        reply=result.reply,
        intent=result.intent,
        language=result.language,
        sources=[playground_source(source) for source in result.sources],
        handoff=result.handoff,
    )
