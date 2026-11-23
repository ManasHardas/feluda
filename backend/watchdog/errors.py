"""API errors with stable machine-readable codes."""

from __future__ import annotations


class ApiError(Exception):
    def __init__(self, status_code: int, code: str, message: str, headers: dict[str, str] | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.headers = headers or {}


def unauthorized(message: str = "Authentication is required.") -> ApiError:
    return ApiError(401, "unauthorized", message, {"WWW-Authenticate": "Bearer"})


def forbidden(message: str = "You are not allowed to do this.") -> ApiError:
    return ApiError(403, "forbidden", message)


def household_suspended() -> ApiError:
    return ApiError(403, "household_suspended", "This household's membership is suspended. Contact your neighborhood organizer.")


def not_found(message: str = "Not found.") -> ApiError:
    return ApiError(404, "not_found", message)


def invitation_invalid() -> ApiError:
    # One answer for unknown, expired, used and revoked invitations, so they cannot be told apart.
    return ApiError(404, "invitation_invalid", "This invitation is not valid. Ask your neighborhood organizer for a new one.")


def rate_limited(retry_after: int) -> ApiError:
    return ApiError(429, "rate_limited", "Too many requests. Try again later.", {"Retry-After": str(max(1, retry_after))})
