"""Test helpers for panel auth: Ed25519 keys, signed tokens and a fake JWKS endpoint.

Everything is generated in the test process: no network, no business-service.
"""

import time
from typing import Any

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from jwt.algorithms import OKPAlgorithm

from app.auth.jwks import JwksCache
from app.auth.panel import ISSUER

JWKS_URL = "http://business-service:8080/auth/jwks.json"


class SigningKey:
    """An Ed25519 key pair with its `kid`, like business-service's SigningKey."""

    def __init__(self, kid: str) -> None:
        self.kid = kid
        self.private_key = Ed25519PrivateKey.generate()

    def jwk(self) -> dict[str, Any]:
        """The public key as business-service publishes it in /auth/jwks.json."""
        jwk = OKPAlgorithm.to_jwk(self.private_key.public_key(), as_dict=True)
        return {**jwk, "kid": self.kid, "alg": "EdDSA", "use": "sig"}

    def sign(self, claims: dict[str, Any], kid: str | None = None) -> str:
        return jwt.encode(
            claims, self.private_key, algorithm="EdDSA", headers={"kid": kid or self.kid}
        )


def claims(
    memberships: list[dict[str, str]] | None = None,
    expires_in: float = 900,
    **extra: Any,
) -> dict[str, Any]:
    """Claims shaped like business-service's access token (AccessTokens.issue)."""
    now = int(time.time())
    return {
        "iss": ISSUER,
        "sub": "42",
        "memberships": (
            memberships
            if memberships is not None
            else [{"slug": "joes-pizza", "role": "OWNER"}]
        ),
        "iat": now,
        "exp": int(now + expires_in),
        **extra,
    }


class FakeJwksServer:
    """Serves `keys` at JWKS_URL through httpx.MockTransport and counts the requests."""

    def __init__(self, *keys: SigningKey) -> None:
        self.keys = list(keys)
        self.requests = 0
        self.down = False

    def handle(self, request: httpx.Request) -> httpx.Response:
        assert str(request.url) == JWKS_URL
        self.requests += 1
        if self.down:
            raise httpx.ConnectError("connection refused")
        return httpx.Response(200, json={"keys": [key.jwk() for key in self.keys]})

    def client(self) -> httpx.Client:
        return httpx.Client(transport=httpx.MockTransport(self.handle))


class FakeClock:
    """A clock the test moves by hand (for the cache's max age and refresh interval)."""

    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def make_cache(server: FakeJwksServer, clock: FakeClock | None = None) -> JwksCache:
    return JwksCache(JWKS_URL, http_client=server.client(), clock=clock or FakeClock())
