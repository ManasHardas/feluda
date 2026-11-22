"""Invitation tokens: random, single-use, stored only as a hash."""

from __future__ import annotations

import hashlib
import secrets

TOKEN_BYTES = 32  # 256 bits of entropy from the operating system's CSPRNG


def new_token() -> str:
    return secrets.token_urlsafe(TOKEN_BYTES)


def hash_token(token: str) -> str:
    # The token is high-entropy random data, so a plain SHA-256 is sufficient (no password-style stretching needed).
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def anonymous_reference() -> str:
    """Random household label such as 'H-7F3A2C'. Carries no information about the address."""
    return "H-" + secrets.token_hex(3).upper()
