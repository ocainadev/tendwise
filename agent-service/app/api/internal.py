"""POST /internal/v1/replies: the AI reply business-service asks for (T37).

From the control panels on, business-service owns conversations (ADR 0002).
When a customer message needs an AI answer, its job worker calls this route
with the conversation id it created and the messages staff sent the customer
since the last AI turn (`staff_messages`). Those go into the conversation
history before the turn, so the AI does not contradict what a person said.

It runs the same steps as `POST /api/v1/chat` (404 unknown business, 409
conversation of another business, 503 when the LLM or business-service is
down), with two differences:

- it needs the shared service key in `X-Api-Key` (`BUSINESS_SERVICE_API_KEY`,
  the key agent-service already sends to business-service), and nginx never
  routes `/internal/`;
- there is no per-conversation rate limit: business-service rate-limits the
  public API before it ever calls us.
"""

import hmac
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, status
from pydantic import BaseModel, Field

from app.agent.intent import Intent, Language
from app.agent.service import AgentService
from app.agent.staff import StaffMessage
from app.agent.state import Source
from app.api.chat import (
    MAX_MESSAGE_LENGTH,
    check_conversation_owner,
    find_business,
    get_agent_service,
    get_gateway,
    require_agent_service,
    run_turn,
)
from app.core.config import Settings, get_settings
from app.gateways.business_gateway import BusinessGateway

router = APIRouter(prefix="/internal/v1")

# More than a handoff ever needs; only a cap against a runaway request.
MAX_STAFF_MESSAGES = 50
MAX_AUTHOR_LENGTH = 100


def require_service_key(
    settings: Annotated[Settings, Depends(get_settings)],
    x_api_key: Annotated[str | None, Header()] = None,
) -> None:
    """Allow the request only when X-Api-Key matches BUSINESS_SERVICE_API_KEY.

    503 when no key is configured, 401 for a missing or wrong key. Compared
    with `hmac.compare_digest`, which takes the same time wherever the strings
    differ, so the key cannot be guessed from response timings.
    """
    if settings.business_service_api_key is None:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "BUSINESS_SERVICE_API_KEY is not configured"
        )
    expected = settings.business_service_api_key.get_secret_value().encode()
    if x_api_key is None or not hmac.compare_digest(x_api_key.encode(), expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing or invalid X-Api-Key")


class StaffMessageIn(BaseModel):
    author: str = Field(min_length=1, max_length=MAX_AUTHOR_LENGTH)
    body: str = Field(min_length=1, max_length=MAX_MESSAGE_LENGTH)


class ReplyRequest(BaseModel):
    # Always given: business-service created the conversation (same UUID as core.conversations).
    conversation_id: UUID
    business_slug: str = Field(pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$", max_length=64)
    message: str = Field(max_length=MAX_MESSAGE_LENGTH)
    # Exchanged with staff since the last AI turn, oldest first.
    staff_messages: list[StaffMessageIn] = Field(default=[], max_length=MAX_STAFF_MESSAGES)


class ReplyResponse(BaseModel):
    reply: str
    # None when guard_input blocked the message before it was classified.
    intent: Intent | None
    language: Language
    sources: list[Source]
    handoff: bool
    # The order code the agent saw this turn, for the inbox's "orders mentioned" panel.
    order_code: str | None


# A plain `def`: the graph and the HTTP calls block, so FastAPI runs it in a thread pool.
@router.post("/replies", response_model=ReplyResponse, dependencies=[Depends(require_service_key)])
def create_reply(
    body: ReplyRequest,
    agent_service: Annotated[AgentService | None, Depends(get_agent_service)],
    gateway: Annotated[BusinessGateway | None, Depends(get_gateway)],
) -> ReplyResponse:
    """Answer the customer's message, knowing what staff already told them."""
    business = find_business(gateway, body.business_slug, body.conversation_id)
    service = require_agent_service(agent_service)
    check_conversation_owner(service, body.conversation_id, business)

    staff_messages = [
        StaffMessage(author=staff.author, content=staff.body) for staff in body.staff_messages
    ]
    result = run_turn(service, body.conversation_id, business, body.message, staff_messages)
    return ReplyResponse(
        reply=result.reply,
        intent=result.intent,
        language=result.language,
        sources=result.sources,
        handoff=result.handoff,
        order_code=result.order_code,
    )
