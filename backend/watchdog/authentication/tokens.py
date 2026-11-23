"""Access-token verification.

Tokens are issued by the identity provider (Supabase Auth in production). The backend only verifies
them. Two verification modes, both using PyJWT:
* JWKS (asymmetric signing keys, preferred): AUTH_JWKS_URL
* HS256 shared secret (Supabase "JWT secret", and the local development emulator): AUTH_JWT_SECRET
"""

from __future__ import annotations

from dataclasses import dataclass

import jwt
from jwt import PyJWKClient

from ..errors import unauthorized
from ..settings import Settings

_ASYMMETRIC_ALGORITHMS = ["RS256", "ES256", "EdDSA"]


@dataclass(frozen=True)
class Principal:
    subject: str
    email: str | None
    assurance_level: str  # "aal1" or "aal2" (multi-factor)


class TokenVerifier:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._jwks = PyJWKClient(settings.auth_jwks_url, cache_keys=True, lifespan=3600) if settings.auth_jwks_url else None

    def verify(self, token: str) -> Principal:
        s = self._settings
        options = {"require": ["exp", "sub"], "verify_aud": bool(s.auth_jwt_audience)}
        kwargs: dict = {"options": options, "leeway": 10}
        if s.auth_jwt_audience:
            kwargs["audience"] = s.auth_jwt_audience
        if s.auth_jwt_issuer:
            kwargs["issuer"] = s.auth_jwt_issuer
        try:
            header = jwt.get_unverified_header(token)
            algorithm = header.get("alg", "")
            if algorithm in _ASYMMETRIC_ALGORITHMS and self._jwks is not None:
                key = self._jwks.get_signing_key_from_jwt(token).key
                claims = jwt.decode(token, key, algorithms=_ASYMMETRIC_ALGORITHMS, **kwargs)
            elif algorithm == "HS256" and s.auth_jwt_secret:
                claims = jwt.decode(token, s.auth_jwt_secret, algorithms=["HS256"], **kwargs)
            else:
                raise unauthorized("The access token uses an unsupported signing method.")
        except jwt.ExpiredSignatureError as exc:
            raise unauthorized("The access token has expired.") from exc
        except jwt.PyJWTError as exc:
            raise unauthorized("The access token is not valid.") from exc
        email = claims.get("email")
        return Principal(
            subject=str(claims["sub"]),
            email=email.strip().lower() if isinstance(email, str) and email.strip() else None,
            assurance_level=str(claims.get("aal", "aal1")),
        )
