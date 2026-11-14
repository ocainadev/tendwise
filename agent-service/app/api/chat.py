"""POST /api/v1/chat: one customer message in, the agent's reply out.

The order of the checks matters:

1. the business must exist (404),
2. a known conversation must belong to that business (409, tenant isolation),
3. the conversation must be under its rate limit (429),
4. then the agent graph answers.

The agent service, the gateway and the rate limiter are built once at startup
(see `app/main.py`) and read from `app.state` by the dependencies below, so
tests replace them with `app.dependency_overrides`.
"""

import logging
from typing import Annotated
from uuid import UUID, uuid4

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.agent.intent import Intent, Language
from app.agent.service import AgentService, TurnResult
from app.agent.staff import StaffMessage
from app.agent.state import Source
from app.api.rate_limit import SlidingWindowRateLimiter
from app.gateways.business_gateway import BusinessGateway, BusinessInfo
from app.llm.errors import LLM_ERRORS

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1")

# Hard cap against abuse (422 above it). Shorter problems, such as an empty
# message or one over 1,000 characters, are not errors: guard_input (T11)
# answers them with a polite fixed reply, as a normal 200.
MAX_MESSAGE_LENGTH = 4000

BUSINESS_NOT_FOUND = "Business not found"
OTHER_BUSINESS = "Conversation belongs to another business"
TOO_MANY_MESSAGES = "Too many messages in this conversation. Please wait a few minutes."
BUSINESS_UNAVAILABLE = "Business service unavailable"
ASSISTANT_UNAVAILABLE = "Assistant temporarily unavailable"


class ChatRequest(BaseModel):
    # None (or absent) starts a new conversation.
    conversation_id: UUID | None = None
    business_slug: str = Field(pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$", max_length=64)
    message: str = Field(max_length=MAX_MESSAGE_LENGTH)


class ChatResponse(BaseModel):
    conversation_id: UUID
    reply: str
    # None when guard_input blocked the message before it was classified.
    intent: Intent | None
    language: Language
    sources: list[Source]
    handoff: bool


# --- dependencies: read what the lifespan stored on app.state ----------------


def get_agent_service(request: Request) -> AgentService | None:
    """The agent service, or None if it could not be built at startup."""
    return getattr(request.app.state, "agent_service", None)


def get_gateway(request: Request) -> BusinessGateway | None:
    """The business-service gateway, or None if it could not be built at startup."""
    return getattr(request.app.state, "gateway", None)


def get_rate_limiter(request: Request) -> SlidingWindowRateLimiter:
    return request.app.state.rate_limiter


# A plain `def` (not `async def`): the graph and the HTTP calls block, so
# FastAPI runs this in a thread pool and other requests are not blocked.
@router.post("/chat", response_model=ChatResponse)
def chat(
    body: ChatRequest,
    agent_service: Annotated[AgentService | None, Depends(get_agent_service)],
    gateway: Annotated[BusinessGateway | None, Depends(get_gateway)],
    rate_limiter: Annotated[SlidingWindowRateLimiter, Depends(get_rate_limiter)],
) -> ChatResponse:
    """Answer one message, starting a new conversation when no id is given."""
    conversation_id = body.conversation_id or uuid4()
    business = find_business(gateway, body.business_slug, conversation_id)
    service = require_agent_service(agent_service)
    if body.conversation_id is not None:
        check_conversation_owner(service, body.conversation_id, business)

    if not rate_limiter.allow(str(conversation_id)):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, TOO_MANY_MESSAGES)

    result = run_turn(service, conversation_id, business, body.message)
    return ChatResponse(
        conversation_id=conversation_id,
        reply=result.reply,
        intent=result.intent,
        language=result.language,
        sources=result.sources,
        handoff=result.handoff,
    )


# --- the steps of a turn, shared with POST /internal/v1/replies (T37) ---------


def find_business(
    gateway: BusinessGateway | None, business_slug: str, conversation_id: UUID
) -> BusinessInfo:
    """The business of this request: 404 if it does not exist, 503 if business-service is down."""
    if gateway is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, BUSINESS_UNAVAILABLE)
    try:
        business = gateway.get_business(business_slug)
    except httpx.HTTPError as error:
        log_error("Business lookup failed", error, conversation_id)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, BUSINESS_UNAVAILABLE) from None
    if business is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, BUSINESS_NOT_FOUND)
    return business


def require_agent_service(agent_service: AgentService | None) -> AgentService:
    """The agent service, or 503 when it could not be built at startup."""
    if agent_service is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, ASSISTANT_UNAVAILABLE)
    return agent_service


def check_conversation_owner(
    agent_service: AgentService, conversation_id: UUID, business: BusinessInfo
) -> None:
    """Tenant isolation: 409 when the stored conversation was started with another business."""
    owner = agent_service.conversation_business(conversation_id)
    if owner is not None and owner != business.slug:
        raise HTTPException(status.HTTP_409_CONFLICT, OTHER_BUSINESS)


def run_turn(
    agent_service: AgentService,
    conversation_id: UUID,
    business: BusinessInfo,
    message: str,
    staff_messages: list[StaffMessage] | None = None,
) -> TurnResult:
    """Run the agent graph once; 503 when the LLM or business-service fails."""
    try:
        return agent_service.handle_turn(conversation_id, business, message, staff_messages)
    except httpx.HTTPError as error:
        # A tool (order status, handoff) could not reach business-service.
        log_error("Business service call failed", error, conversation_id)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, BUSINESS_UNAVAILABLE) from None
    except LLM_ERRORS as error:
        # The LLM provider failed: network, wrong key, overload, rate limit, ...
        log_error("LLM call failed", error, conversation_id)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, ASSISTANT_UNAVAILABLE) from None


def log_error(what: str, error: Exception, conversation_id: UUID) -> None:
    """Log only the exception type and the conversation id.

    The exception text can contain URLs, keys or the customer's message, so it
    never reaches the logs or the response.
    """
    logger.warning("%s: %s (conversation %s)", what, type(error).__name__, conversation_id)
