"""A small in-memory sliding-window rate limiter (per-conversation cost control).

Each key (a conversation id) may make at most `max_events` calls in any window
of `window_seconds`. We keep the times of the recent calls per key and drop the
ones older than the window, so the limit "slides" with the clock instead of
resetting at fixed boundaries.

The counts live in this process's memory: with several agent-service
processes each one counts on its own, and a restart forgets them. That is fine
for the MVP (one process); a shared store such as Redis would fix both later.
"""

import threading
import time
from collections import deque
from collections.abc import Callable

from app.core.config import Settings

# Above this many tracked keys, `allow` drops the idle ones so memory stays small.
MAX_TRACKED_KEYS = 10_000


class SlidingWindowRateLimiter:
    def __init__(
        self,
        max_events: int,
        window_seconds: float,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        """`clock` returns the current time in seconds; tests pass a fake one."""
        self._max_events = max_events
        self._window_seconds = window_seconds
        self._clock = clock
        # key -> times of its calls inside the window, oldest first.
        self._events: dict[str, deque[float]] = {}
        # FastAPI runs `def` routes in a thread pool, so two requests can call
        # `allow` at the same time: the lock makes each call one atomic step.
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        """Record a call for `key` and return True, or return False if `key` is over the limit.

        A rejected call is not recorded, so a client that keeps retrying is
        let in again as soon as its oldest call leaves the window.
        """
        with self._lock:
            now = self._clock()
            if len(self._events) > MAX_TRACKED_KEYS:
                self._forget_idle_keys(now)
            events = self._events.setdefault(key, deque())
            self._drop_old(events, now)
            if len(events) >= self._max_events:
                return False
            events.append(now)
            return True

    def _drop_old(self, events: deque[float], now: float) -> None:
        """Remove the call times that are no longer inside the window."""
        while events and events[0] <= now - self._window_seconds:
            events.popleft()

    def _forget_idle_keys(self, now: float) -> None:
        """Delete the keys that have no call inside the window."""
        for key in list(self._events):
            self._drop_old(self._events[key], now)
            if not self._events[key]:
                del self._events[key]


def create_rate_limiter(settings: Settings) -> SlidingWindowRateLimiter:
    """The chat limiter, from CHAT_RATE_LIMIT_MESSAGES and CHAT_RATE_LIMIT_WINDOW_SECONDS."""
    return SlidingWindowRateLimiter(
        max_events=settings.chat_rate_limit_messages,
        window_seconds=settings.chat_rate_limit_window_seconds,
    )
