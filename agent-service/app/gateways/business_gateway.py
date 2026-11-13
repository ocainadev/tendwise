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
from pydantic import BaseModel, ConfigDict
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


class BusinessInfo(_CamelModel):
    """GET /businesses/{slug}: what the agent needs to know about the business."""

    slug: str
    name: str
    category: str
    default_language: Language  # en, es or pt
    timezone: str
    # e.g. {"tue": [{"open": "11:00", "close": "22:00"}], "mon": []}
    opening_hours: dict[str, list[dict[str, str]]] | None = None


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
