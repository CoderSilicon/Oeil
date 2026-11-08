"""Process-local rate limiting for authentication endpoints.

This is a lightweight, in-memory speed bump, not a distributed protection layer.
It is keyed by the immediate client address exposed by Starlette
(``request.client.host``). It intentionally does not trust ``X-Forwarded-For``
because that header is spoofable unless a trusted proxy sanitizes it.

Limitations:

* Counters are per process; multiple workers do not share them.
* Behind a reverse proxy, clients may all appear to use the proxy address.
* An attacker with many source addresses can still distribute attempts.

For public deployments, also configure rate limiting at the reverse proxy or use
a shared store such as Redis. Do not treat this module as complete protection.
"""

from __future__ import annotations

import threading
import time
from collections import deque
from dataclasses import dataclass

from fastapi import HTTPException, Request


@dataclass(frozen=True)
class Limit:
    max_requests: int
    window_seconds: int


SIGNUP_LIMIT = Limit(max_requests=10, window_seconds=15 * 60)
LOGIN_LIMIT = Limit(max_requests=20, window_seconds=5 * 60)
OAUTH_START_LIMIT = Limit(max_requests=20, window_seconds=10 * 60)
OAUTH_CALLBACK_LIMIT = Limit(max_requests=20, window_seconds=10 * 60)

MAX_WINDOW_SECONDS = max(
    SIGNUP_LIMIT.window_seconds,
    LOGIN_LIMIT.window_seconds,
    OAUTH_START_LIMIT.window_seconds,
    OAUTH_CALLBACK_LIMIT.window_seconds,
)


def _client_key(request: Request) -> str:
    client = request.client
    return client.host if client and client.host else "unknown"


class RateLimiter:
    def __init__(self) -> None:
        self._events: dict[tuple[str, str], deque[float]] = {}
        self._lock = threading.Lock()
        self._checks = 0

    def check(self, action: str, request: Request, limit: Limit) -> None:
        key = (action, _client_key(request))
        now = time.monotonic()
        with self._lock:
            events = self._events.setdefault(key, deque())
            cutoff = now - limit.window_seconds
            while events and events[0] <= cutoff:
                events.popleft()
            if len(events) >= limit.max_requests:
                retry_after = max(
                    1, int(limit.window_seconds - (now - events[0])) + 1
                )
                raise HTTPException(
                    status_code=429,
                    detail="Too many authentication attempts. Please try again later.",
                    headers={"Retry-After": str(retry_after)},
                )
            events.append(now)
            self._checks += 1
            if self._checks % 1000 == 0:
                self._prune(now)

    def _prune(self, now: float) -> None:
        cutoff = now - MAX_WINDOW_SECONDS
        stale = [
            key
            for key, events in self._events.items()
            if not events or events[-1] <= cutoff
        ]
        for key in stale:
            del self._events[key]

    def reset(self) -> None:
        with self._lock:
            self._events.clear()
            self._checks = 0


limiter = RateLimiter()


def reset() -> None:
    """Clear all counters. Intended for tests and controlled restarts."""
    limiter.reset()
