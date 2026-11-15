"""The public keys that check the panel access token, fetched from business-service.

business-service signs the access token with its Ed25519 private key and
publishes the public key at `GET /auth/jwks.json` (a JSON Web Key Set,
RFC 7517). Each token header names its key in `kid`.

We keep the keys in memory and fetch them again:

- when they are older than `MAX_AGE_SECONDS` (business-service sends
  `Cache-Control: max-age=300`), so a key that was removed stops working;
- when a token names a `kid` we do not know, so a new key works at once
  after a rotation.

A refetch happens at most once every `MIN_REFRESH_SECONDS`: tokens with
made-up `kid` values must not make us call business-service on every request.
"""

import logging
import threading
import time
from collections.abc import Callable
from typing import Any

import httpx
from jwt import PyJWK, PyJWKError

logger = logging.getLogger(__name__)

MAX_AGE_SECONDS = 300.0
MIN_REFRESH_SECONDS = 30.0
TIMEOUT_SECONDS = 3.0


class JwksUnavailableError(Exception):
    """We have no keys at all, because business-service could not be reached."""


class JwksCache:
    """Ed25519 public keys by `kid`, refreshed as described in the module docstring.

    One instance is shared by all requests, so a lock guards the refresh.
    `http_client` and `clock` can be replaced in tests (no network, no waiting).
    """

    def __init__(
        self,
        url: str,
        http_client: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._url = url
        self._http = http_client or httpx.Client(timeout=TIMEOUT_SECONDS)
        self._clock = clock
        self._lock = threading.Lock()
        self._keys: dict[str, PyJWK] = {}
        # When the keys were last fetched successfully (None: never).
        self._fetched_at: float | None = None
        # When we last tried to fetch them, successfully or not (None: never).
        self._tried_at: float | None = None

    def key_for(self, kid: str) -> PyJWK | None:
        """The public key with this `kid`, or None if business-service does not publish it.

        Raises JwksUnavailableError when no keys could be fetched yet.
        """
        with self._lock:
            if self._needs_refresh(kid) and self._may_refresh():
                self._refresh()
            if self._fetched_at is None:
                raise JwksUnavailableError
            return self._keys.get(kid)

    def _needs_refresh(self, kid: str) -> bool:
        if self._fetched_at is None or kid not in self._keys:
            return True
        return self._clock() - self._fetched_at >= MAX_AGE_SECONDS

    def _may_refresh(self) -> bool:
        return self._tried_at is None or self._clock() - self._tried_at >= MIN_REFRESH_SECONDS

    def _refresh(self) -> None:
        """Fetch the key set. On failure keep the keys we have (if any) and log it."""
        self._tried_at = self._clock()
        try:
            response = self._http.get(self._url)
            response.raise_for_status()
            keys = parse_jwks(response.json())
        except (httpx.HTTPError, ValueError) as error:
            # Only the error type: the message can contain the host name.
            logger.warning("Could not fetch the JWKS: %s", type(error).__name__)
            return
        self._keys = keys
        self._fetched_at = self._tried_at


def parse_jwks(body: Any) -> dict[str, PyJWK]:
    """The Ed25519 keys of a JWKS body, by `kid`. Keys of another type are skipped.

    Raises ValueError when the body is not a key set at all.
    """
    if not isinstance(body, dict) or not isinstance(body.get("keys"), list):
        raise ValueError("Not a JSON Web Key Set")
    keys = {}
    for jwk in body["keys"]:
        if not isinstance(jwk, dict) or not isinstance(jwk.get("kid"), str):
            continue
        if jwk.get("kty") != "OKP" or jwk.get("crv") != "Ed25519":
            continue
        try:
            keys[jwk["kid"]] = PyJWK(jwk, algorithm="EdDSA")
        except PyJWKError:
            continue
    return keys
