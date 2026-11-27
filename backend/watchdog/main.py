"""Application factory."""

from __future__ import annotations

import logging
import secrets
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__, app_versions, metrics
from .api import admin as admin_routes
from .api import routes
from .clock import Clock
from .db import Database
from .errors import ApiError
from .logging_setup import configure_logging, request_id_var
from .notifications.providers import PushSender, build_senders
from .pipeline import BackgroundWorker, Pipeline, run_tick_exclusively
from .ratelimit import SlidingWindowLimiter
from .settings import Settings, get_settings
from .web import pages

log = logging.getLogger("watchdog.api")

DESCRIPTION = """
Private, invitation-only API for the Neighborhood Watchdog apps.

This is a wireless **anomaly** detection system, not a certified jammer detector. Classifications are
operational inferences from connectivity reports and are never proof of deliberate interference.
"""

SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
    "Permissions-Policy": "geolocation=(), camera=(), microphone=()",
}


def _error(status_code: int, code: str, message: str, headers: dict[str, str] | None = None) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        headers=headers,
        content={"error": {"code": code, "message": message, "request_id": request_id_var.get()}},
    )


def create_app(
    settings: Settings | None = None,
    *,
    clock: Clock | None = None,
    senders: dict[str, PushSender] | None = None,
    database: Database | None = None,
) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)
    clock = clock or Clock()
    db = database or Database(settings.database_url, settings.database_pool_max)
    pipeline = Pipeline(db, senders if senders is not None else build_senders(settings), clock)

    if settings.sentry_dsn:  # optional error tracking; never sends request bodies or personal data
        try:
            import sentry_sdk

            sentry_sdk.init(
                dsn=settings.sentry_dsn,
                environment=settings.environment,
                send_default_pii=False,
                max_request_body_size="never",
                traces_sample_rate=0.0,
            )
        except ImportError:
            log.warning("SENTRY_DSN is set but sentry-sdk is not installed (install the 'errors' extra)")

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if database is None:
            db.open()
        worker = None
        if settings.background_worker_enabled:
            worker = BackgroundWorker(pipeline, settings.tick_interval_seconds)
            worker.start()
        log.info("started", extra={"environment": settings.environment, "version": __version__})
        try:
            yield
        finally:
            if worker:
                worker.stop()
            if database is None:
                db.close()

    production = settings.is_production_like
    app = FastAPI(
        title="Neighborhood Watchdog API",
        version=__version__,
        description=DESCRIPTION,
        lifespan=lifespan,
        docs_url=None if production else "/docs",
        redoc_url=None,
        openapi_url=None if production else "/openapi.json",
    )
    app.state.settings = settings
    app.state.clock = clock
    app.state.db = db
    app.state.pipeline = pipeline
    app.state.limiter = SlidingWindowLimiter()

    if settings.cors_allowed_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=[o.strip() for o in settings.cors_allowed_origins.split(",") if o.strip()],
            allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE"],
            allow_headers=["Authorization", "Content-Type", app_versions.HEADER],
        )

    @app.middleware("http")
    async def refuse_outdated_apps(request: Request, call_next):
        path = request.url.path
        if path.startswith("/api/v1/") and not path.startswith(app_versions.EXEMPT_PREFIXES):
            parsed = app_versions.parse_header(request.headers.get(app_versions.HEADER, ""))
            if parsed is not None:
                platform, version = parsed
                ios = platform.value == "ios"
                minimum = settings.min_app_version_ios if ios else settings.min_app_version_android
                if app_versions.is_below(version, minimum):
                    metrics.incr("app_update_required")
                    return _error(
                        426,
                        "update_required",
                        "This version of the app is no longer supported. Install the update to keep taking part.",
                    )
        return await call_next(request)

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = uuid.uuid4().hex
        token = request_id_var.set(request_id)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            metrics.incr("http_5xx")
            log.exception("unhandled error", extra={"method": request.method, "route": request.url.path})
            response = _error(500, "internal_error", "Something went wrong. Please try again.")
        for name, value in SECURITY_HEADERS.items():
            response.headers.setdefault(name, value)
        if production:
            response.headers.setdefault("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        response.headers["X-Request-ID"] = request_id
        route = request.scope.get("route")
        # Log the route template, never the raw path or query string (they may hold identifiers).
        log.info(
            "request",
            extra={
                "method": request.method,
                "route": getattr(route, "path", "unmatched"),
                "status": response.status_code,
                "duration_ms": round((time.perf_counter() - started) * 1000, 1),
            },
        )
        metrics.incr(f"http_{response.status_code // 100}xx")
        request_id_var.reset(token)
        return response

    @app.exception_handler(ApiError)
    async def api_error(_: Request, exc: ApiError):
        return _error(exc.status_code, exc.code, exc.message, exc.headers)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exc: RequestValidationError):
        # Field locations only: submitted values are not echoed back or logged.
        fields = sorted({".".join(str(p) for p in e["loc"] if p != "body") for e in exc.errors()})
        return _error(422, "validation_error", "Invalid request: " + ", ".join(f for f in fields if f)[:300])

    @app.exception_handler(StarletteHTTPException)
    async def http_error(_: Request, exc: StarletteHTTPException):
        code = {401: "unauthorized", 403: "forbidden", 404: "not_found", 405: "method_not_allowed"}.get(exc.status_code, "error")
        return _error(exc.status_code, code, str(exc.detail), getattr(exc, "headers", None))

    @app.get("/health", include_in_schema=False)
    def liveness() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/internal/tick", include_in_schema=False)
    def internal_tick(request: Request) -> dict[str, bool]:
        """Maintenance pass for hosts that put the process to sleep; called by a scheduler with a shared secret."""
        expected = settings.internal_tick_token
        supplied = request.headers.get("authorization", "").removeprefix("Bearer ").strip()
        if not expected or not secrets.compare_digest(supplied.encode(), expected.encode()):
            raise ApiError(404, "not_found", "Not found.")
        return {"ran": run_tick_exclusively(pipeline)}

    app.include_router(routes.router)
    app.include_router(admin_routes.router)
    app.include_router(pages.build_router(settings))
    return app
