"""Runtime configuration, read from environment variables. No secret has a usable default."""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    environment: str = Field("development", pattern="^(development|test|staging|production)$")
    database_url: str = "postgresql://nw_login:nw_local_dev_only@localhost:5432/watchdog"
    database_pool_max: int = 5

    # --- access-token verification (Supabase Auth or any OIDC-style issuer)
    auth_jwt_secret: str = ""  # HS256 shared secret (Supabase "JWT secret"); leave empty when using JWKS
    auth_jwks_url: str = ""  # e.g. https://<project>.supabase.co/auth/v1/.well-known/jwks.json
    auth_jwt_audience: str = "authenticated"
    auth_jwt_issuer: str = ""  # checked when set
    admin_require_mfa: bool = False  # require the "aal2" assurance level for /admin endpoints
    dev_auth_enabled: bool = False  # local emulator of the auth endpoints; refused outside development/test
    dev_auth_fixed_code: str = ""  # emulator only: always issue this code, so automated app tests can sign in
    # Optional: lets "delete account" also delete the identity record at the provider (Supabase Auth admin API).
    auth_admin_url: str = ""  # e.g. https://<project>.supabase.co/auth/v1
    auth_service_role_key: str = ""  # server-side secret; never shipped in the mobile apps

    # --- public URLs shown to residents (no secrets)
    public_base_url: str = "http://localhost:8000"
    android_install_url: str = ""
    ios_install_url: str = ""
    source_repo_url: str = ""
    support_url: str = ""
    security_contact: str = ""

    # --- push notifications
    fcm_project_id: str = ""
    fcm_service_account_json: str = ""  # the service-account JSON document itself (from a secret manager)
    apns_key_id: str = ""
    apns_team_id: str = ""
    apns_private_key: str = ""  # contents of the .p8 key
    apns_bundle_id: str = ""

    # --- operations
    background_worker_enabled: bool = True
    tick_interval_seconds: int = 20
    internal_tick_token: str = ""  # enables POST /internal/tick for an external scheduler; empty = endpoint disabled
    trust_proxy_headers: bool = False  # set true only behind a proxy that overwrites X-Forwarded-For
    ip_hash_salt: str = "change-me"  # salt for hashing client IPs in security events
    sentry_dsn: str = ""
    log_level: str = "INFO"
    cors_allowed_origins: str = ""

    @property
    def is_production_like(self) -> bool:
        return self.environment in ("staging", "production")

    @model_validator(mode="after")
    def _refuse_unsafe_production(self) -> Settings:
        if self.is_production_like:
            if self.dev_auth_enabled:
                raise ValueError("DEV_AUTH_ENABLED must be false in staging and production")
            if not (self.auth_jwt_secret or self.auth_jwks_url):
                raise ValueError("AUTH_JWT_SECRET or AUTH_JWKS_URL is required in staging and production")
            if self.auth_jwt_secret and len(self.auth_jwt_secret) < 32:
                raise ValueError("AUTH_JWT_SECRET must be at least 32 characters")
            if self.ip_hash_salt == "change-me":
                raise ValueError("IP_HASH_SALT must be set in staging and production")
            if self.internal_tick_token and len(self.internal_tick_token) < 32:
                raise ValueError("INTERNAL_TICK_TOKEN must be at least 32 characters")
            if not self.public_base_url.startswith("https://"):
                raise ValueError("PUBLIC_BASE_URL must use https in staging and production")
        if self.dev_auth_enabled and not self.auth_jwt_secret:
            raise ValueError("DEV_AUTH_ENABLED needs AUTH_JWT_SECRET to sign local tokens")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
