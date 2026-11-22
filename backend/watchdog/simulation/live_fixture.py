"""Prepare a locally running development backend for the apps' live contract tests.

    python -m watchdog.simulation.live_fixture --platform ios

Needs `make seed` and a backend started with DEV_AUTH_FIXED_CODE set (see `make live-backend`).
It approves a fresh address, creates an invitation for it, replays scenario C so that a neighborhood
incident with a score breakdown exists, and prints the values the app test needs as KEY=value lines.
Development only: it signs in as seeded residents with the throwaway local secret.
"""

from __future__ import annotations

import argparse
import secrets
import time
from datetime import UTC, datetime, timedelta

import httpx
import jwt

from ..db import Database
from ..settings import get_settings
from .__main__ import load_seeded
from .scenarios import build_scenarios


def main() -> int:
    parser = argparse.ArgumentParser(prog="python -m watchdog.simulation.live_fixture")
    parser.add_argument("--platform", choices=["ios", "android"], required=True)
    parser.add_argument("--base-url", default="http://localhost:8000")
    args = parser.parse_args()
    settings = get_settings()
    if settings.is_production_like:
        raise SystemExit("Development only.")
    db = Database(settings.database_url, 2)
    db.open()
    try:
        hood = load_seeded(db)
    finally:
        db.close()

    def token(h) -> str:
        now = int(time.time())
        claims = {"sub": h.auth_subject, "email": h.email, "aud": settings.auth_jwt_audience, "iat": now, "exp": now + 600}
        return jwt.encode(claims, settings.auth_jwt_secret, algorithm="HS256")

    run = secrets.token_hex(3)
    number, street = str(secrets.randbelow(8000) + 1000), "Fixtureway"
    with httpx.Client(base_url=args.base_url, timeout=20) as client:
        admin = {"Authorization": f"Bearer {token(hood.admin)}"}
        household = client.post(
            "/api/v1/admin/households",
            headers=admin,
            json={"house_number": number, "street_name": street, "isp": "att", "latitude": 33.0503, "longitude": -97.0503},
        )
        household.raise_for_status()
        invitation = client.post(
            "/api/v1/admin/invitations", headers=admin, json={"household_id": household.json()["household_id"]}
        )
        invitation.raise_for_status()
        start = datetime.now(UTC) - timedelta(seconds=40)
        for step in sorted(build_scenarios(hood, start)["C"].steps, key=lambda s: s.at_s):
            if step.household is None or step.at_s < 0:
                continue
            client.post(
                "/api/v1/connectivity/events",
                headers={"Authorization": f"Bearer {token(step.household)}"},
                json={
                    "device_id": str(step.household.device_id),
                    "reported_via": step.reported_via,
                    "sent_at": datetime.now(UTC).isoformat(),
                    "events": step.events,
                },
            ).raise_for_status()
    print(f"WATCHDOG_LIVE_INVITATION={invitation.json()['invitation_url']}")
    print(f"WATCHDOG_LIVE_EMAIL=live-{args.platform}-{run}@example.org")
    print(f"WATCHDOG_LIVE_HOUSE_NUMBER={number}")
    print(f"WATCHDOG_LIVE_STREET={street}")
    print(f"WATCHDOG_LIVE_CODE={settings.dev_auth_fixed_code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
