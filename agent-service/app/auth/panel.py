"""Who is calling a panel route, and may they? (T35, ADR 0003)

The panel calls agent-service's knowledge base routes (`/api/v1/panel/{slug}/…`,
T50) directly, with the same session cookies business-service set at login:

- `tendwise_access`: the access token, a JWT signed with Ed25519 (alg EdDSA).
  We check its signature with business-service's public key (see `jwks.py`),
  its issuer and its expiry, and read the user from its claims.
- `tendwise_csrf`: the CSRF token. A state-changing request must also send it
  in the `X-CSRF-Token` header (double submit).

A panel route declares the role it needs:

    @router.get("/api/v1/panel/{slug}/documents")
    def list_documents(access: Annotated[PanelAccess, Depends(require_panel_role("AGENT"))]):
        ...  # access.business_slug is a business the user belongs to

The checks run in the same order as in business-service, each with its status:

1. CSRF header on POST/PUT/PATCH/DELETE (403);
2. a valid access token (401);
3. the slug is one of the user's businesses (404, the same as a business that
   does not exist, so a user cannot find out which other businesses exist);
4. the user's role in that business (403). OWNER can do everything AGENT can.

A platform admin has no access to a business through this guard; support mode
gets its own token (T64).
"""

import hmac
from collections.abc import Callable
from dataclasses import dataclass
from typing import Annotated, Literal

import jwt
from fastapi import Depends, HTTPException, Request, status
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.auth.jwks import JwksCache, JwksUnavailableError

# Names set by business-service (AuthCookies.java).
ACCESS_COOKIE = "tendwise_access"
CSRF_COOKIE = "tendwise_csrf"
CSRF_HEADER = "X-CSRF-Token"

# The `iss` claim business-service puts in every access token (AccessTokens.java).
ISSUER = "tendwise-business-service"

STATE_CHANGING_METHODS = {"POST", "PUT", "PATCH", "DELETE"}

# The same details business-service sends.
CSRF_INVALID = "Missing or invalid CSRF token."
NOT_LOGGED_IN = "Not logged in."
ACCESS_DENIED = "Access denied."
BUSINESS_NOT_FOUND = "Business not found"
AUTH_UNAVAILABLE = "Login cannot be checked right now. Please try again shortly."

MembershipRole = Literal["OWNER", "AGENT"]

# A higher rank can do everything a lower one can.
ROLE_RANK: dict[str, int] = {"AGENT": 1, "OWNER": 2}


class Membership(BaseModel):
    """One business the user belongs to, as carried in the token."""

    model_config = ConfigDict(frozen=True)

    slug: str
    role: MembershipRole


class PanelUser(BaseModel):
    """The user in a valid access token. It holds no email or name (no personal data)."""

    model_config = ConfigDict(frozen=True, populate_by_name=True)

    # The claim names are business-service's: `sub`, `platformRole`, `memberships`.
    user_id: int = Field(alias="sub")
    # "ADMIN" for the platform operator, None for business staff.
    platform_role: Literal["ADMIN"] | None = Field(default=None, alias="platformRole")
    memberships: tuple[Membership, ...]

    def membership(self, slug: str) -> Membership | None:
        return next((m for m in self.memberships if m.slug == slug), None)


@dataclass(frozen=True)
class PanelAccess:
    """What a panel route gets: the user, and the business they are working on."""

    user: PanelUser
    business_slug: str
    role: MembershipRole


def verify_access_token(token: str, jwks: JwksCache) -> PanelUser | None:
    """The user in the token, or None if it is expired, tampered with or malformed.

    Raises JwksUnavailableError when business-service's keys cannot be fetched.
    The token has no `aud` claim (business-service sets none), so none is
    checked; PyJWT rejects a token that does carry one.
    """
    try:
        kid = jwt.get_unverified_header(token).get("kid")
    except jwt.InvalidTokenError:
        return None
    if not isinstance(kid, str):
        return None
    key = jwks.key_for(kid)
    if key is None:
        return None
    try:
        claims = jwt.decode(
            token,
            key.key,
            # Only EdDSA: never trust the `alg` the token itself names.
            algorithms=["EdDSA"],
            issuer=ISSUER,
            options={"require": ["exp", "iss", "sub"]},
        )
        return PanelUser.model_validate(claims)
    except (jwt.InvalidTokenError, ValidationError):
        return None


# --- FastAPI dependencies ----------------------------------------------------


def get_jwks_cache(request: Request) -> JwksCache:
    """The key cache built at startup (`app.state.jwks_cache`)."""
    return request.app.state.jwks_cache


def check_csrf(request: Request) -> None:
    """403 unless a state-changing request sends the CSRF cookie's value in X-CSRF-Token."""
    if request.method not in STATE_CHANGING_METHODS:
        return
    cookie = request.cookies.get(CSRF_COOKIE)
    header = request.headers.get(CSRF_HEADER)
    # compare_digest takes the same time wherever the strings differ.
    if not cookie or not header or not hmac.compare_digest(cookie.encode(), header.encode()):
        raise HTTPException(status.HTTP_403_FORBIDDEN, CSRF_INVALID)


def get_current_user(
    request: Request, jwks: Annotated[JwksCache, Depends(get_jwks_cache)]
) -> PanelUser:
    """The user in the access token cookie: 401 without a valid one.

    503 when business-service's keys cannot be fetched (we cannot tell a good
    token from a bad one).
    """
    token = request.cookies.get(ACCESS_COOKIE)
    if not token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, NOT_LOGGED_IN)
    try:
        user = verify_access_token(token, jwks)
    except JwksUnavailableError:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, AUTH_UNAVAILABLE) from None
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, NOT_LOGGED_IN)
    return user


def require_panel_role(required: MembershipRole) -> Callable[..., PanelAccess]:
    """A dependency for `/api/v1/panel/{slug}/…` routes that need at least `required`."""

    def check_panel_access(
        slug: str, request: Request, jwks: Annotated[JwksCache, Depends(get_jwks_cache)]
    ) -> PanelAccess:
        # Called in this order on purpose (see the module docstring).
        check_csrf(request)
        user = get_current_user(request, jwks)
        membership = user.membership(slug)
        if membership is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, BUSINESS_NOT_FOUND)
        if ROLE_RANK[membership.role] < ROLE_RANK[required]:
            raise HTTPException(status.HTTP_403_FORBIDDEN, ACCESS_DENIED)
        return PanelAccess(user=user, business_slug=slug, role=membership.role)

    return check_panel_access
