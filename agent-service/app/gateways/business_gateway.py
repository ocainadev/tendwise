"""The BusinessGateway interface (a "port") and its HTTP adapter for business-service.

The agent never reads the `core` schema: everything it knows about a business,
its orders and its handoffs comes through this gateway, which calls the
business-service REST API with the `X-Api-Key` header. Tests use a fake
gateway (or `httpx.MockTransport`), so no network is needed.
"""

import logging
from datetime import datetime
from typing import Protocol
from urllib.parse import quote
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

from app.agent.intent import Language
from app.core.config import Settings

logger = logging.getLogger(__name__)

# httpx logs every request URL at INFO level, and the order status URL carries
# `phoneLast4` in its query string. Only warnings and errors from httpx, so the
# digits never reach the logs.
logging.getLogger("httpx").setLevel(logging.WARNING)

# A chat reply should not hang on a slow backend: fail after a few seconds.
TIMEOUT_SECONDS = 5.0


class _CamelModel(BaseModel):
    """Reads business-service's camelCase JSON into snake_case fields (`defaultLanguage`)."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


# The same caps business-service enforces (T53), applied again here: a longer
# text is cut, so a bug or an old row there can never blow up a prompt.
MAX_ASSISTANT_NAME_CHARS = 60
MAX_TONE_CHARS = 200
MAX_GREETING_CHARS = 500
MAX_EXTRA_INSTRUCTIONS_CHARS = 1000
MAX_OUT_OF_HOURS_MESSAGE_CHARS = 500
ALL_LANGUAGES: list[Language] = ["en", "es", "pt"]


def _capped(text: str | None, limit: int) -> str | None:
    """`text` stripped and cut to `limit` characters; None when it is empty."""
    if text is None:
        return None
    text = text.strip()[:limit].strip()
    return text or None


class AssistantSettings(_CamelModel):
    """`assistantSettings` of GET /businesses/{slug} (T53), written by the owner in the panel.

    Every field is optional, so a payload from before T53 (no `assistantSettings`
    at all) gives the default settings and today's behaviour.
    """

    name: str | None = None
    tone: str | None = None
    # Shown by business-service when a conversation starts; the agent does not use it.
    greeting: str | None = None
    extra_instructions: str | None = None
    out_of_hours_message: str | None = None
    # The languages the assistant may reply in (all three until the owner chooses).
    languages: list[Language] = Field(default_factory=lambda: list(ALL_LANGUAGES))

    @field_validator("name")
    @classmethod
    def _cap_name(cls, value: str | None) -> str | None:
        return _capped(value, MAX_ASSISTANT_NAME_CHARS)

    @field_validator("tone")
    @classmethod
    def _cap_tone(cls, value: str | None) -> str | None:
        return _capped(value, MAX_TONE_CHARS)

    @field_validator("greeting")
    @classmethod
    def _cap_greeting(cls, value: str | None) -> str | None:
        return _capped(value, MAX_GREETING_CHARS)

    @field_validator("extra_instructions")
    @classmethod
    def _cap_extra_instructions(cls, value: str | None) -> str | None:
        return _capped(value, MAX_EXTRA_INSTRUCTIONS_CHARS)

    @field_validator("out_of_hours_message")
    @classmethod
    def _cap_out_of_hours_message(cls, value: str | None) -> str | None:
        return _capped(value, MAX_OUT_OF_HOURS_MESSAGE_CHARS)

    @field_validator("languages", mode="before")
    @classmethod
    def _all_languages_when_missing(cls, value: object) -> object:
        # Unknown codes are dropped; null or [] would leave the assistant with no
        # language, so all three are used instead.
        if not isinstance(value, list):
            return list(ALL_LANGUAGES)
        known = [language for language in value if language in ALL_LANGUAGES]
        return known or list(ALL_LANGUAGES)


class BusinessInfo(_CamelModel):
    """GET /businesses/{slug}: what the agent needs to know about the business."""

    slug: str
    name: str
    category: str
    default_language: Language  # en, es or pt
    timezone: str
    # e.g. {"tue": [{"open": "11:00", "close": "22:00"}], "mon": []}
    opening_hours: dict[str, list[dict[str, str]]] | None = None
    assistant_settings: AssistantSettings = Field(default_factory=AssistantSettings)

    @field_validator("assistant_settings", mode="before")
    @classmethod
    def _default_when_null(cls, value: object) -> object:
        return AssistantSettings() if value is None else value


class OrderStatusInfo(_CamelModel):
    """GET /orders/{code}/status: status, fulfilment and ETA (no customer data)."""

    code: str
    status: str
    fulfilment: str
    eta: datetime | None = None
    updated_at: datetime


class BusinessGateway(Protocol):
    """What the agent can ask business-service. Every call is scoped to one business."""

    def get_business(self, slug: str) -> BusinessInfo | None:
        """The business, or None if it does not exist."""
        ...

    def get_order_status(self, slug: str, code: str, phone_last4: str) -> OrderStatusInfo | None:
        """The order status, or None if the code, phone or business does not match."""
        ...

    def create_handoff(self, slug: str, conversation_id: UUID, reason: str, summary: str) -> int:
        """Open a handoff for staff and return its id."""
        ...


class HttpBusinessGateway:
    """BusinessGateway that calls the business-service REST API with httpx."""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        # `transport` lets tests plug in `httpx.MockTransport`; None = real network.
        self._client = httpx.Client(
            base_url=base_url,
            headers={"X-Api-Key": api_key},
            timeout=TIMEOUT_SECONDS,
            transport=transport,
        )

    @classmethod
    def from_settings(cls, settings: Settings) -> "HttpBusinessGateway":
        """Build the gateway from BUSINESS_SERVICE_URL and BUSINESS_SERVICE_API_KEY."""
        if settings.business_service_api_key is None:
            raise ValueError("BUSINESS_SERVICE_API_KEY is not set")
        return cls(
            base_url=settings.business_service_url,
            api_key=settings.business_service_api_key.get_secret_value(),
        )

    def get_business(self, slug: str) -> BusinessInfo | None:
        response = self._client.get(f"/api/v1/businesses/{quote(slug, safe='')}")
        if response.status_code == 404:
            return None
        response.raise_for_status()
        return BusinessInfo.model_validate(response.json())

    def get_order_status(self, slug: str, code: str, phone_last4: str) -> OrderStatusInfo | None:
        response = self._client.get(
            f"/api/v1/orders/{quote(code, safe='')}/status",
            params={"businessSlug": slug, "phoneLast4": phone_last4},
        )
        # Never log the phone digits; whether the order was found is enough.
        logger.info("Order status lookup for %s: HTTP %d", slug, response.status_code)
        if response.status_code == 404:
            return None
        response.raise_for_status()
        return OrderStatusInfo.model_validate(response.json())

    def create_handoff(self, slug: str, conversation_id: UUID, reason: str, summary: str) -> int:
        response = self._client.post(
            "/api/v1/handoffs",
            json={
                "businessSlug": slug,
                "conversationId": str(conversation_id),
                "reason": reason,
                "summary": summary,
            },
        )
        response.raise_for_status()
        handoff_id = int(response.json()["id"])
        logger.info("Created handoff %d for %s (reason %s)", handoff_id, slug, reason)
        return handoff_id
