"""Panel auth dependencies (T35), through a tiny route mounted only in these tests.

The real app with a fake JWKS endpoint (httpx.MockTransport): keys and tokens
are generated here, so nothing calls business-service.
"""

from typing import Annotated

import jwt
import pytest
from fastapi import APIRouter, Depends, FastAPI
from fastapi.testclient import TestClient

from app.auth.jwks import MIN_REFRESH_SECONDS
from app.auth.panel import (
    ACCESS_COOKIE,
    CSRF_COOKIE,
    CSRF_HEADER,
    PanelAccess,
    PanelUser,
    get_current_user,
    require_panel_role,
)
from app.main import create_app
from tests.auth.auth_fakes import FakeClock, FakeJwksServer, SigningKey, claims, make_cache

PROBE = "/api/v1/panel/{slug}/probe"
CSRF = "csrf-token-value"

probe_router = APIRouter()


@probe_router.get(PROBE)
def read_probe(access: Annotated[PanelAccess, Depends(require_panel_role("AGENT"))]) -> dict:
    return {"slug": access.business_slug, "role": access.role, "userId": access.user.user_id}


@probe_router.post(PROBE)
def write_probe(access: Annotated[PanelAccess, Depends(require_panel_role("OWNER"))]) -> dict:
    return {"slug": access.business_slug}


@probe_router.get("/probe/me")
def me(user: Annotated[PanelUser, Depends(get_current_user)]) -> dict:
    return {"userId": user.user_id, "platformRole": user.platform_role}


KEY = SigningKey("key-1")


@pytest.fixture
def server() -> FakeJwksServer:
    return FakeJwksServer(KEY)


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def app(server: FakeJwksServer, clock: FakeClock) -> FastAPI:
    app = create_app()
    app.include_router(probe_router)
    app.state.jwks_cache = make_cache(server, clock)
    return app


def client_with(app: FastAPI, token: str | None, csrf: str | None = CSRF) -> TestClient:
    """A client that sends the session cookies, as the browser does."""
    client = TestClient(app)
    if token is not None:
        client.cookies.set(ACCESS_COOKIE, token)
    if csrf is not None:
        client.cookies.set(CSRF_COOKIE, csrf)
    return client


def url(slug: str = "joes-pizza") -> str:
    return PROBE.format(slug=slug)


# --- the access token --------------------------------------------------------


def test_a_valid_token_gives_the_user_and_the_business(app: FastAPI) -> None:
    response = client_with(app, KEY.sign(claims())).get(url())

    assert response.status_code == 200
    assert response.json() == {"slug": "joes-pizza", "role": "OWNER", "userId": 42}


def test_get_current_user_reads_the_platform_role(app: FastAPI) -> None:
    token = KEY.sign(claims(memberships=[], platformRole="ADMIN"))

    response = client_with(app, token).get("/probe/me")

    assert response.json() == {"userId": 42, "platformRole": "ADMIN"}


def test_missing_cookie_is_401(app: FastAPI) -> None:
    response = client_with(app, None).get(url())

    assert response.status_code == 401
    assert response.json() == {"detail": "Not logged in."}


def test_expired_token_is_401(app: FastAPI) -> None:
    token = KEY.sign(claims(expires_in=-1))

    assert client_with(app, token).get(url()).status_code == 401


def test_token_signed_with_another_key_is_401(app: FastAPI) -> None:
    # Same kid as the published key, but signed by someone else's private key.
    forged = SigningKey("key-1").sign(claims())

    assert client_with(app, forged).get(url()).status_code == 401


def test_tampered_claims_are_401(app: FastAPI) -> None:
    header, payload, signature = KEY.sign(claims()).split(".")
    other_payload = KEY.sign(claims(sub="1")).split(".")[1]
    tampered = ".".join([header, other_payload, signature])

    assert payload != other_payload
    assert client_with(app, tampered).get(url()).status_code == 401


@pytest.mark.parametrize(
    "bad_claims",
    [
        claims(iss="someone-else"),
        {k: v for k, v in claims().items() if k != "exp"},
        claims(aud="another-service"),
        claims(memberships=[{"slug": "joes-pizza", "role": "SUPERUSER"}]),
        claims(sub="not-a-number"),
    ],
    ids=["wrong-issuer", "no-exp", "has-audience", "unknown-role", "bad-sub"],
)
def test_wrong_or_missing_claims_are_401(app: FastAPI, bad_claims: dict) -> None:
    assert client_with(app, KEY.sign(bad_claims)).get(url()).status_code == 401


def test_a_token_with_another_algorithm_is_401(app: FastAPI) -> None:
    # HS256 "signed" with the public key's text: the classic algorithm confusion attack.
    token = jwt.encode(claims(), KEY.jwk()["x"], algorithm="HS256", headers={"kid": "key-1"})

    assert client_with(app, token).get(url()).status_code == 401


def test_garbage_token_is_401(app: FastAPI) -> None:
    assert client_with(app, "not-a-jwt").get(url()).status_code == 401


def test_unknown_kid_triggers_one_jwks_refresh(
    app: FastAPI, server: FakeJwksServer, clock: FakeClock
) -> None:
    client = client_with(app, KEY.sign(claims()))
    assert client.get(url()).status_code == 200

    # business-service starts signing with a new key and publishes both.
    new_key = SigningKey("key-2")
    server.keys.append(new_key)
    client.cookies.set(ACCESS_COOKIE, new_key.sign(claims()))

    # A refetch is allowed once the minimum interval since the last one has passed.
    clock.now += MIN_REFRESH_SECONDS
    assert client.get(url()).status_code == 200
    assert server.requests == 2


def test_jwks_unreachable_is_503(app: FastAPI, server: FakeJwksServer) -> None:
    server.down = True

    response = client_with(app, KEY.sign(claims())).get(url())

    assert response.status_code == 503


# --- the business and the role ----------------------------------------------


def test_a_business_the_user_does_not_belong_to_is_404(app: FastAPI) -> None:
    response = client_with(app, KEY.sign(claims())).get(url("denim-corner"))

    assert response.status_code == 404
    assert response.json() == {"detail": "Business not found"}


def test_a_platform_admin_without_membership_is_404(app: FastAPI) -> None:
    token = KEY.sign(claims(memberships=[], platformRole="ADMIN"))

    assert client_with(app, token).get(url()).status_code == 404


def test_the_slug_picks_the_right_membership(app: FastAPI) -> None:
    memberships = [
        {"slug": "joes-pizza", "role": "AGENT"},
        {"slug": "denim-corner", "role": "OWNER"},
    ]
    client = client_with(app, KEY.sign(claims(memberships=memberships)))

    assert client.get(url("joes-pizza")).json()["role"] == "AGENT"
    assert client.get(url("denim-corner")).json()["role"] == "OWNER"


def test_an_agent_cannot_use_an_owner_route(app: FastAPI) -> None:
    token = KEY.sign(claims(memberships=[{"slug": "joes-pizza", "role": "AGENT"}]))

    response = client_with(app, token).post(url(), headers={CSRF_HEADER: CSRF})

    assert response.status_code == 403
    assert response.json() == {"detail": "Access denied."}


def test_an_owner_can_use_an_owner_route(app: FastAPI) -> None:
    response = client_with(app, KEY.sign(claims())).post(url(), headers={CSRF_HEADER: CSRF})

    assert response.status_code == 200


# --- CSRF ----------------------------------------------------------------------


def test_post_without_csrf_header_is_403(app: FastAPI) -> None:
    response = client_with(app, KEY.sign(claims())).post(url())

    assert response.status_code == 403
    assert response.json() == {"detail": "Missing or invalid CSRF token."}


def test_post_with_a_different_csrf_header_is_403(app: FastAPI) -> None:
    client = client_with(app, KEY.sign(claims()))

    assert client.post(url(), headers={CSRF_HEADER: "something-else"}).status_code == 403


def test_post_without_csrf_cookie_is_403(app: FastAPI) -> None:
    client = client_with(app, KEY.sign(claims()), csrf=None)

    assert client.post(url(), headers={CSRF_HEADER: CSRF}).status_code == 403


def test_csrf_is_checked_before_the_login_like_business_service(app: FastAPI) -> None:
    # No cookies at all: a forged cross-site POST is refused for its CSRF first.
    assert client_with(app, None, csrf=None).post(url()).status_code == 403


def test_get_needs_no_csrf_header(app: FastAPI) -> None:
    client = client_with(app, KEY.sign(claims()), csrf=None)

    assert client.get(url()).status_code == 200
