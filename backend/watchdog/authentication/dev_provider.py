"""Local development stand-in for the identity provider's e-mail one-time-code endpoints.

It mimics the three Supabase Auth (GoTrue) calls the mobile apps use, so the apps can run against a
laptop without a Supabase project. It is mounted only when DEV_AUTH_ENABLED=true and the settings
loader refuses that flag in staging and production. Codes are written to the server log instead of
being e-mailed. State is kept in memory and lost on restart.
"""

from __future__ import annotations

import hashlib
import logging
import secrets
import threading
import uuid
from datetime import UTC, datetime, timedelta

import jwt
from fastapi import APIRouter, Query, Request
from pydantic import BaseModel, EmailStr

from ..errors import ApiError
from ..settings import Settings

log = logging.getLogger("watchdog.dev_auth")

ACCESS_TOKEN_SECONDS = 900
CODE_SECONDS = 600


class OtpRequest(BaseModel):
    email: EmailStr
    create_user: bool = True


class VerifyRequest(BaseModel):
    type: str = "email"
    email: EmailStr
    token: str


class RefreshRequest(BaseModel):
    refresh_token: str


class DevAuthState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.codes: dict[str, tuple[str, float]] = {}
        self.refresh_tokens: dict[str, str] = {}  # sha256(refresh token) -> email
        self.last_code: dict[str, str] = {}  # readable by tests and by the local operator

    @staticmethod
    def subject_for(email: str) -> str:
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"dev-auth:{email}"))


def build_router(settings: Settings, clock) -> APIRouter:
    if settings.is_production_like or not settings.dev_auth_enabled:
        raise RuntimeError("The development auth emulator must never be mounted outside development or test")
    router = APIRouter(prefix="/auth/v1", tags=["dev-auth (local only)"], include_in_schema=False)
    state = DevAuthState()
    router.state = state  # type: ignore[attr-defined]

    def issue(email: str) -> dict:
        now = datetime.now(UTC)  # real time: token expiry is checked against the real clock
        subject = state.subject_for(email)
        access = jwt.encode(
            {
                "sub": subject,
                "email": email,
                "aud": settings.auth_jwt_audience or "authenticated",
                "aal": "aal1",
                "iat": int(now.timestamp()),
                "exp": int((now + timedelta(seconds=ACCESS_TOKEN_SECONDS)).timestamp()),
            },
            settings.auth_jwt_secret,
            algorithm="HS256",
        )
        refresh = secrets.token_urlsafe(32)
        with state.lock:
            state.refresh_tokens[hashlib.sha256(refresh.encode()).hexdigest()] = email
        return {
            "access_token": access,
            "refresh_token": refresh,
            "expires_in": ACCESS_TOKEN_SECONDS,
            "token_type": "bearer",
            "user": {"id": subject, "email": email},
        }

    @router.post("/otp")
    def request_code(body: OtpRequest) -> dict:
        email = body.email.strip().lower()
        code = settings.dev_auth_fixed_code or f"{secrets.randbelow(1_000_000):06d}"
        with state.lock:
            state.codes[email] = (code, clock.now().timestamp() + CODE_SECONDS)
            state.last_code[email] = code
        # Local development only: the code goes to the log because there is no mail server.
        log.warning("DEV AUTH one-time code issued: %s", code, extra={"dev_only": True})
        return {}

    @router.post("/verify")
    def verify_code(body: VerifyRequest) -> dict:
        email = body.email.strip().lower()
        with state.lock:
            entry = state.codes.get(email)
            valid = bool(entry) and secrets.compare_digest(entry[0], body.token.strip()) and entry[1] >= clock.now().timestamp()
            if valid:
                del state.codes[email]
        if not valid:
            raise ApiError(403, "otp_invalid", "The code is not valid or has expired.")
        return issue(email)

    @router.post("/token")
    def refresh(body: RefreshRequest, grant_type: str = Query(...)) -> dict:
        if grant_type != "refresh_token":
            raise ApiError(400, "unsupported_grant_type", "Only refresh_token is supported.")
        digest = hashlib.sha256(body.refresh_token.encode()).hexdigest()
        with state.lock:
            email = state.refresh_tokens.pop(digest, None)  # rotation: each refresh token works once
        if email is None:
            raise ApiError(400, "refresh_token_not_found", "The refresh token is not valid.")
        return issue(email)

    @router.post("/logout")
    def logout(request: Request) -> dict:
        return {}

    return router
