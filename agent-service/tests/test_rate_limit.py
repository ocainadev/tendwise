from app.api import rate_limit
from app.api.rate_limit import SlidingWindowRateLimiter, create_rate_limiter
from app.core.config import Settings


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def test_allows_up_to_the_limit_then_rejects() -> None:
    limiter = SlidingWindowRateLimiter(3, 60, clock=FakeClock())

    assert [limiter.allow("a") for _ in range(4)] == [True, True, True, False]


def test_keys_are_counted_separately() -> None:
    limiter = SlidingWindowRateLimiter(1, 60, clock=FakeClock())

    assert limiter.allow("a") is True
    assert limiter.allow("b") is True
    assert limiter.allow("a") is False


def test_the_window_slides_with_the_clock() -> None:
    clock = FakeClock()
    limiter = SlidingWindowRateLimiter(2, 60, clock=clock)
    limiter.allow("a")  # t=1000
    clock.now = 1030
    limiter.allow("a")  # t=1030
    assert limiter.allow("a") is False

    clock.now = 1060  # the call at t=1000 is now exactly 60 s old: it left the window
    assert limiter.allow("a") is True
    assert limiter.allow("a") is False  # 1030 and 1060 are still inside

    clock.now = 1120
    assert limiter.allow("a") is True


def test_rejected_calls_are_not_counted() -> None:
    clock = FakeClock()
    limiter = SlidingWindowRateLimiter(1, 60, clock=clock)
    limiter.allow("a")
    for _ in range(5):
        limiter.allow("a")  # rejected retries

    clock.now += 60
    assert limiter.allow("a") is True


def test_idle_keys_are_forgotten_when_there_are_many(monkeypatch) -> None:
    monkeypatch.setattr(rate_limit, "MAX_TRACKED_KEYS", 2)
    clock = FakeClock()
    limiter = SlidingWindowRateLimiter(1, 60, clock=clock)
    for key in ("a", "b", "c"):
        limiter.allow(key)

    clock.now += 60
    limiter.allow("d")

    assert list(limiter._events) == ["d"]


def test_create_rate_limiter_reads_the_settings(settings: Settings) -> None:
    limiter = create_rate_limiter(
        settings.model_copy(
            update={"chat_rate_limit_messages": 1, "chat_rate_limit_window_seconds": 5}
        )
    )

    assert limiter.allow("a") is True
    assert limiter.allow("a") is False
