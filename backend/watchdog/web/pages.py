"""The few web pages residents see before they have the app: invitation registration and privacy.

The pages are static HTML with a small inline script. A Content-Security-Policy with a per-response
nonce allows only that script, and no third-party resources are loaded.
"""

from __future__ import annotations

import html
import secrets
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import HTMLResponse

from ..settings import Settings

_DIR = Path(__file__).parent


def _page(name: str, settings: Settings) -> HTMLResponse:
    nonce = secrets.token_urlsafe(16)
    body = (_DIR / name).read_text(encoding="utf-8")
    replacements = {
        "{{NONCE}}": nonce,
        "{{ANDROID_URL}}": html.escape(settings.android_install_url, quote=True),
        "{{IOS_URL}}": html.escape(settings.ios_install_url, quote=True),
        "{{SOURCE_URL}}": html.escape(settings.source_repo_url, quote=True),
        "{{SUPPORT_URL}}": html.escape(settings.support_url, quote=True),
        "{{SECURITY_CONTACT}}": html.escape(settings.security_contact or "your neighborhood organizer", quote=True),
    }
    for key, value in replacements.items():
        body = body.replace(key, value)
    csp = (
        f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'nonce-{nonce}'; connect-src 'self'; "
        "img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    )
    return HTMLResponse(
        body,
        headers={
            "Content-Security-Policy": csp,
            "Cache-Control": "no-store",
            "Referrer-Policy": "no-referrer",
            "X-Robots-Tag": "noindex, nofollow",
        },
    )


def build_router(settings: Settings) -> APIRouter:
    router = APIRouter(include_in_schema=False)

    @router.get("/join", response_class=HTMLResponse)
    def join() -> HTMLResponse:
        return _page("join.html", settings)

    @router.get("/privacy", response_class=HTMLResponse)
    def privacy() -> HTMLResponse:
        return _page("privacy.html", settings)

    @router.get("/robots.txt")
    def robots():
        from fastapi.responses import PlainTextResponse

        return PlainTextResponse("User-agent: *\nDisallow: /\n")

    return router
