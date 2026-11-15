"""JwksCache (T35): business-service's public keys, cached and refreshed with care."""

import pytest

from app.auth.jwks import MAX_AGE_SECONDS, MIN_REFRESH_SECONDS, JwksUnavailableError, parse_jwks
from tests.auth.auth_fakes import FakeClock, FakeJwksServer, SigningKey, make_cache


def test_fetches_once_and_then_uses_the_cached_key() -> None:
    key = SigningKey("key-1")
    server = FakeJwksServer(key)
    cache = make_cache(server)

    assert cache.key_for("key-1") is not None
    assert cache.key_for("key-1") is not None
    assert server.requests == 1


def test_an_unknown_kid_triggers_one_refresh() -> None:
    old, new = SigningKey("old"), SigningKey("new")
    server = FakeJwksServer(old)
    clock = FakeClock()
    cache = make_cache(server, clock)
    cache.key_for("old")

    # business-service rotates its key: the JWKS now also publishes "new".
    server.keys.append(new)
    clock.now += MIN_REFRESH_SECONDS

    assert cache.key_for("new") is not None
    assert server.requests == 2


def test_unknown_kids_refresh_at_most_once_per_interval() -> None:
    server = FakeJwksServer(SigningKey("key-1"))
    clock = FakeClock()
    cache = make_cache(server, clock)
    cache.key_for("key-1")

    # Made-up kids within the interval: no new request to business-service.
    assert cache.key_for("made-up-1") is None
    clock.now += MIN_REFRESH_SECONDS - 1
    assert cache.key_for("made-up-2") is None
    assert server.requests == 1

    clock.now += 1
    assert cache.key_for("made-up-3") is None
    assert server.requests == 2


def test_keys_older_than_the_max_age_are_fetched_again() -> None:
    key = SigningKey("key-1")
    server = FakeJwksServer(key)
    clock = FakeClock()
    cache = make_cache(server, clock)
    cache.key_for("key-1")

    # The key was removed from the JWKS (e.g. after a leak): it stops working.
    server.keys = []
    clock.now += MAX_AGE_SECONDS

    assert cache.key_for("key-1") is None
    assert server.requests == 2


def test_no_keys_at_all_when_business_service_is_down() -> None:
    server = FakeJwksServer(SigningKey("key-1"))
    server.down = True
    cache = make_cache(server)

    with pytest.raises(JwksUnavailableError):
        cache.key_for("key-1")


def test_keeps_the_old_keys_when_a_refresh_fails() -> None:
    server = FakeJwksServer(SigningKey("key-1"))
    clock = FakeClock()
    cache = make_cache(server, clock)
    cache.key_for("key-1")

    server.down = True
    clock.now += MAX_AGE_SECONDS

    assert cache.key_for("key-1") is not None
    assert server.requests == 2


def test_parse_jwks_skips_keys_of_another_type() -> None:
    good = SigningKey("ed").jwk()
    rsa_like = {"kty": "RSA", "kid": "rsa", "n": "abc", "e": "AQAB"}
    no_kid = {k: v for k, v in SigningKey("x").jwk().items() if k != "kid"}

    keys = parse_jwks({"keys": [good, rsa_like, no_kid, "junk"]})

    assert list(keys) == ["ed"]


def test_parse_jwks_rejects_a_body_that_is_not_a_key_set() -> None:
    with pytest.raises(ValueError):
        parse_jwks({"error": "nope"})
