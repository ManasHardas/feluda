"""Small request helpers shared by the routers."""

from __future__ import annotations

from datetime import datetime

from fastapi import Request

from ..errors import rate_limited
from ..services.audit import hash_ip


def now(request: Request) -> datetime:
    return request.app.state.clock.now()


def client_key(request: Request) -> str:
    """A salted hash of the client address, used only as a rate-limit key."""
    settings = request.app.state.settings
    ip = request.client.host if request.client else "unknown"
    if settings.trust_proxy_headers:
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            ip = forwarded.split(",")[-1].strip() or ip  # the entry added by our own proxy
    return hash_ip(ip, settings.ip_hash_salt)


def limit_by_client(request: Request, bucket: str, limit: int, window_s: int) -> None:
    wait = request.app.state.limiter.check(f"{bucket}:{client_key(request)}", limit, window_s, now(request))
    if wait:
        raise rate_limited(wait)


def limit_by_key(request: Request, bucket: str, key: str, limit: int, window_s: int) -> None:
    wait = request.app.state.limiter.check(f"{bucket}:{key}", limit, window_s, now(request))
    if wait:
        raise rate_limited(wait)
