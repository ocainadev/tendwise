"""HttpBusinessGateway against a fake business-service (httpx.MockTransport)."""

import json
import logging
from datetime import UTC, datetime
from uuid import UUID

import httpx
import pytest

from app.core.config import Settings
from app.gateways.business_gateway import HttpBusinessGateway

CONVERSATION_ID = UUID("6f1c2d3e-4b5a-4c6d-8e9f-0a1b2c3d4e5f")


def gateway_with(handler, requests: list[httpx.Request]) -> HttpBusinessGateway:
    """A gateway whose HTTP calls go to `handler`; every request is recorded."""

    def record(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return handler(request)

    return HttpBusinessGateway(
        base_url="http://business-service:8080",
        api_key="test-key",
        transport=httpx.MockTransport(record),
    )


def test_get_order_status_sends_key_and_params_and_maps_camel_case() -> None:
    requests: list[httpx.Request] = []
    body = {
        "code": "JP-1042",
        "status": "OUT_FOR_DELIVERY",
        "fulfilment": "DELIVERY",
        "eta": "2026-11-12T19:30:00Z",
        "updatedAt": "2026-11-12T19:00:00Z",
    }
    gateway = gateway_with(lambda request: httpx.Response(200, json=body), requests)

    order = gateway.get_order_status("joes-pizza", "JP-1042", "5678")

    request = requests[0]
    assert request.method == "GET"
    assert request.headers["X-Api-Key"] == "test-key"
    assert request.url.path == "/api/v1/orders/JP-1042/status"
    assert dict(request.url.params) == {"businessSlug": "joes-pizza", "phoneLast4": "5678"}
    assert order is not None
    assert order.status == "OUT_FOR_DELIVERY"
    assert order.updated_at == datetime(2026, 11, 12, 19, 0, tzinfo=UTC)


def test_get_order_status_returns_none_on_404() -> None:
    gateway = gateway_with(lambda request: httpx.Response(404, json={"title": "Not Found"}), [])

    assert gateway.get_order_status("joes-pizza", "JP-9999", "0000") is None


def test_get_order_status_raises_on_server_error() -> None:
    gateway = gateway_with(lambda request: httpx.Response(500), [])

    with pytest.raises(httpx.HTTPStatusError):
        gateway.get_order_status("joes-pizza", "JP-1042", "5678")


def test_get_order_status_never_logs_the_phone_digits(caplog: pytest.LogCaptureFixture) -> None:
    body = {"code": "JP-1042", "status": "READY", "fulfilment": "PICKUP", "eta": None,
            "updatedAt": "2026-11-12T19:00:00Z"}  # fmt: skip
    gateway = gateway_with(lambda request: httpx.Response(200, json=body), [])

    with caplog.at_level(logging.DEBUG):
        gateway.get_order_status("joes-pizza", "JP-1042", "5678")

    assert "5678" not in caplog.text


def test_get_business_maps_camel_case_and_returns_none_on_404() -> None:
    requests: list[httpx.Request] = []
    body = {
        "slug": "joes-pizza",
        "name": "Joe's Pizza",
        "category": "restaurant",
        "defaultLanguage": "en",
        "timezone": "America/New_York",
        "openingHours": {"mon": [], "tue": [{"open": "11:00", "close": "22:00"}]},
    }

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/businesses/joes-pizza":
            return httpx.Response(200, json=body)
        return httpx.Response(404)

    gateway = gateway_with(handler, requests)

    business = gateway.get_business("joes-pizza")
    assert business is not None
    assert business.default_language == "en"
    assert business.opening_hours["tue"] == [{"open": "11:00", "close": "22:00"}]
    assert requests[0].headers["X-Api-Key"] == "test-key"
    assert gateway.get_business("unknown") is None


def test_create_handoff_posts_camel_case_body_and_returns_the_id() -> None:
    requests: list[httpx.Request] = []
    response_body = {"id": 7, "conversationId": str(CONVERSATION_ID), "reason": "COMPLAINT",
                     "summary": "Cold pizza.", "status": "OPEN"}  # fmt: skip
    gateway = gateway_with(lambda request: httpx.Response(201, json=response_body), requests)

    handoff_id = gateway.create_handoff("joes-pizza", CONVERSATION_ID, "COMPLAINT", "Cold pizza.")

    assert handoff_id == 7
    request = requests[0]
    assert request.method == "POST"
    assert request.url.path == "/api/v1/handoffs"
    assert request.headers["X-Api-Key"] == "test-key"
    assert json.loads(request.content) == {
        "businessSlug": "joes-pizza",
        "conversationId": str(CONVERSATION_ID),
        "reason": "COMPLAINT",
        "summary": "Cold pizza.",
    }


def test_from_settings_requires_the_api_key(settings: Settings) -> None:
    with pytest.raises(ValueError, match="BUSINESS_SERVICE_API_KEY"):
        HttpBusinessGateway.from_settings(settings)

    with_key = Settings(database_url="postgresql://x@db/x", business_service_api_key="secret")
    assert isinstance(HttpBusinessGateway.from_settings(with_key), HttpBusinessGateway)
