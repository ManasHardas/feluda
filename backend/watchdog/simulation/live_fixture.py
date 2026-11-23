"""Prepare a locally running development backend for the apps' live contract tests.

    python -m watchdog.simulation.live_fixture --platform ios

Needs `make seed` and a running backend (see `make live-backend`). It creates an invitation link,
replays scenario C so that a neighborhood incident with a score breakdown exists, and prints the
values the app test needs as KEY=value lines. Development only.
"""

from __future__ import annotations

import argparse
import secrets
from datetime import UTC, datetime, timedelta

import httpx

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
    if not settings.admin_api_token:
        raise SystemExit("ADMIN_API_TOKEN is not set (the Makefile sets a local one).")
    db = Database(settings.database_url, 2)
    db.open()
    try:
        hood = load_seeded(db)
    finally:
        db.close()

    number, street = str(secrets.randbelow(8000) + 1000), "Fixtureway"
    with httpx.Client(base_url=args.base_url, timeout=20) as client:
        admin = {"Authorization": f"Bearer {settings.admin_api_token}", "X-Neighborhood-Id": str(hood.neighborhood_id)}
        invitation = client.post("/api/v1/admin/invitations", headers=admin, json={"max_uses": 2})
        invitation.raise_for_status()
        start = datetime.now(UTC) - timedelta(seconds=40)
        for step in sorted(build_scenarios(hood, start)["C"].steps, key=lambda s: s.at_s):
            if step.household is None or step.at_s < 0:
                continue
            client.post(
                "/api/v1/connectivity/events",
                headers={"Authorization": f"Bearer {step.household.device_secret}"},
                json={
                    "device_id": str(step.household.device_id),
                    "reported_via": step.reported_via,
                    "sent_at": datetime.now(UTC).isoformat(),
                    "events": step.events,
                },
            ).raise_for_status()
    print(f"WATCHDOG_LIVE_INVITATION={invitation.json()['invitation_url']}")
    print(f"WATCHDOG_LIVE_HOUSE_NUMBER={number}")
    print(f"WATCHDOG_LIVE_STREET={street}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
