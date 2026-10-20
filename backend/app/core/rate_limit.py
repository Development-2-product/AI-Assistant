"""Per-user rate limiting. Uses Redis when REDIS_URL is set (shared across instances), else memory."""
import time
from collections import defaultdict, deque

from fastapi import Depends, HTTPException, Request, status

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

        # A limiter outage must not leave request handlers hanging behind a dead Redis socket.
        self._r = redis.from_url(url, socket_connect_timeout=3, socket_timeout=3, health_check_interval=30)

    async def allow(self, key: str, limit: int, window: int = 60) -> bool:
        try:
            bucket = f"rl:{key}:{int(time.time() // window)}"
            count = await self._r.incr(bucket)
            if count == 1:
                await self._r.expire(bucket, window + 5)
            return count <= limit
        except Exception as exc:  # Redis/network client exceptions are provider-specific.
            raise RateLimitUnavailable from exc

    async def ready(self) -> None:
        """Verify the shared limiter used by production requests is reachable."""
        try:
            await self._r.ping()
        except Exception as exc:  # Redis/network client exceptions are provider-specific.
            raise RateLimitUnavailable from exc


_limiter: InMemoryLimiter | RedisLimiter | None = None


def _get_limiter():
    global _limiter
    if _limiter is None:
        url = get_settings().redis_url
        _limiter = RedisLimiter(url) if url else InMemoryLimiter()
    return _limiter


async def rate_limiter_ready() -> bool:
    """Return whether the limiter needed for this deployment can serve traffic.

    Development intentionally permits the in-memory limiter. Production validation
    already requires Redis, so a failed ping here makes the readiness endpoint fail
    instead of advertising an instance that will reject every protected request.
    """
    limiter = _get_limiter()
    if isinstance(limiter, RedisLimiter):
        try:
            await limiter.ready()
        except RateLimitUnavailable:
            return False
    return True


async def rate_limited_user(
    request: Request,
    user: AuthUser = Depends(get_current_user),
) -> AuthUser:
    """Apply separate account and network limits before an AI call.

    Account-only limits are easy to evade when anonymous Firebase sign-in is in
    use. The client address is intentionally only a second coarse limit: mobile
    networks often share addresses, so it is set much higher than the user cap.
    """
    try:
        settings = get_settings()
        limiter = _get_limiter()
        # Do not place a raw Firebase uid in Redis keys.
        user_allowed = await limiter.allow(f"user:{user.uid_hash}", settings.rate_limit_per_minute)
        client_ip = request.client.host if request.client else "unknown"
        ip_allowed = await limiter.allow(f"ip:{client_ip}", settings.rate_limit_per_ip_per_minute)
    except RateLimitUnavailable:
        # Fail closed instead of letting an unreachable Redis instance become an opaque 500 or
        # silently disabling a production-wide rate limit.
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Rate limiting is temporarily unavailable")
    if not user_allowed or not ip_allowed:
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Too many requests, slow down")
    return user
