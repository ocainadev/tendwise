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

import anthropic
import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.agent.intent import Intent, Language
from app.agent.service import AgentService
from app.agent.state import Source
from app.api.rate_limit import SlidingWindowRateLimiter
from app.gateways.business_gateway import BusinessGateway

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

    if gateway is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, BUSINESS_UNAVAILABLE)
    try:
        business = gateway.get_business(body.business_slug)
    except httpx.HTTPError as error:
        log_error("Business lookup failed", error, conversation_id)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, BUSINESS_UNAVAILABLE) from None
    if business is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, BUSINESS_NOT_FOUND)

    if agent_service is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, ASSISTANT_UNAVAILABLE)

    # Tenant isolation: the stored state says which business started this conversation.
    if body.conversation_id is not None:
        owner = agent_service.conversation_business(body.conversation_id)
        if owner is not None and owner != business.slug:
            raise HTTPException(status.HTTP_409_CONFLICT, OTHER_BUSINESS)

    if not rate_limiter.allow(str(conversation_id)):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, TOO_MANY_MESSAGES)

    try:
        result = agent_service.handle_turn(conversation_id, business, body.message)
    except httpx.HTTPError as error:
        # A tool (order status, handoff) could not reach business-service.
        log_error("Business service call failed", error, conversation_id)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, BUSINESS_UNAVAILABLE) from None
    except anthropic.AnthropicError as error:
        # The LLM provider failed: network, wrong key, overload, rate limit, ...
        log_error("LLM call failed", error, conversation_id)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, ASSISTANT_UNAVAILABLE) from None

    return ChatResponse(
        conversation_id=conversation_id,
        reply=result.reply,
        intent=result.intent,
        language=result.language,
        sources=result.sources,
        handoff=result.handoff,
    )


def log_error(what: str, error: Exception, conversation_id: UUID) -> None:
    """Log only the exception type and the conversation id.

    The exception text can contain URLs, keys or the customer's message, so it
    never reaches the logs or the response.
    """
    logger.warning("%s: %s (conversation %s)", what, type(error).__name__, conversation_id)
