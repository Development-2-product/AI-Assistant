"""Per-user rate limiting. Uses Redis when REDIS_URL is set (shared across instances), else memory."""
import time
from collections import defaultdict, deque

from fastapi import Depends, HTTPException, status

from app.core.auth import AuthUser, get_current_user
from app.core.config import get_settings


class RateLimitUnavailable(Exception):
    """The configured shared limiter cannot be contacted."""


class InMemoryLimiter:
    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    async def allow(self, key: str, limit: int, window: int = 60) -> bool:
        now = time.monotonic()
        q = self._hits[key]
        while q and now - q[0] > window:
            q.popleft()
        if len(q) >= limit:
            return False
        q.append(now)
        return True


class RedisLimiter:
    def __init__(self, url: str) -> None:
        import redis.asyncio as redis

        self._r = redis.from_url(url)

    async def allow(self, key: str, limit: int, window: int = 60) -> bool:
        try:
            bucket = f"rl:{key}:{int(time.time() // window)}"
            count = await self._r.incr(bucket)
            if count == 1:
                await self._r.expire(bucket, window + 5)
            return count <= limit
        except Exception as exc:  # Redis/network client exceptions are provider-specific.
            raise RateLimitUnavailable from exc


_limiter: InMemoryLimiter | RedisLimiter | None = None


def _get_limiter():
    global _limiter
    if _limiter is None:
        url = get_settings().redis_url
        _limiter = RedisLimiter(url) if url else InMemoryLimiter()
    return _limiter


async def rate_limited_user(user: AuthUser = Depends(get_current_user)) -> AuthUser:
    try:
        allowed = await _get_limiter().allow(user.uid, get_settings().rate_limit_per_minute)
    except RateLimitUnavailable:
        # Fail closed instead of letting an unreachable Redis instance become an opaque 500 or
        # silently disabling a production-wide rate limit.
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Rate limiting is temporarily unavailable")
    if not allowed:
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Too many requests, slow down")
    return user
