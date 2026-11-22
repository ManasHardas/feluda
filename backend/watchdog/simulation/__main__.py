"""Play a scenario against a locally running API.

    make docker-up migrate seed        # database with the simulated neighborhood
    make run                           # API on http://localhost:8000 with the local auth emulator
    python -m watchdog.simulation --list
    python -m watchdog.simulation --scenario C

It signs in as the seeded residents by minting local development tokens with AUTH_JWT_SECRET, so it
only works against a development server that uses the same throwaway secret. Steps that lie in the
future of a scenario are sent immediately with their original occurrence times (as late reports
would be), so no waiting is needed; use --realtime to wait instead.
"""

from __future__ import annotations

import argparse
import time
from datetime import UTC, datetime, timedelta

import httpx
import jwt

from ..db import Database
from ..seed import NEIGHBORHOOD_NAME, SeededHousehold, SeededNeighborhood
from ..settings import get_settings
from .scenarios import build_scenarios


def load_seeded(db: Database) -> SeededNeighborhood:
    with db.service() as conn:
        hood = conn.execute("SELECT id FROM nw.neighborhoods WHERE name = %s", [NEIGHBORHOOD_NAME]).fetchone()
        if hood is None:
            raise SystemExit("Simulated neighborhood not found. Run `make seed` first.")
        rows = conn.execute(
            "SELECT h.id AS household_id, h.isp, h.normalized_house_number, h.normalized_street_name, u.id AS user_id,"
            " u.email, u.auth_subject, hm.role, d.id AS device_id, d.platform FROM nw.households h"
            " JOIN nw.household_members hm ON hm.household_id = h.id AND hm.status = 'active'"
            " JOIN nw.users u ON u.id = hm.user_id JOIN nw.devices d ON d.user_id = u.id"
            " WHERE h.neighborhood_id = %s ORDER BY u.email",
            [hood["id"]],
        ).fetchall()
    households = [
        SeededHousehold(
            i,
            r["household_id"],
            r["user_id"],
            r["device_id"],
            r["email"],
            r["auth_subject"],
            r["isp"],
            r["platform"],
            r["normalized_house_number"],
            r["normalized_street_name"],
            r["role"],
        )
        for i, r in enumerate(rows)
    ]
    return SeededNeighborhood(hood["id"], households)


def main() -> int:
    parser = argparse.ArgumentParser(prog="python -m watchdog.simulation")
    parser.add_argument("--scenario", choices=list("ABCDEFGHIJ"))
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--realtime", action="store_true", help="wait between steps instead of sending late reports")
    args = parser.parse_args()

    settings = get_settings()
    if settings.is_production_like:
        raise SystemExit("The simulator only runs against a development environment.")
    db = Database(settings.database_url, 2)
    db.open()
    try:
        hood = load_seeded(db)
    finally:
        db.close()

    # Without --realtime everything is placed in the recent past so that no step is "in the future".
    start = datetime.now(UTC) if args.realtime else datetime.now(UTC) - timedelta(minutes=35)
    scenarios = build_scenarios(hood, start)
    if args.list or not args.scenario:
        for key, scenario in scenarios.items():
            print(f"{key}  {scenario.title}\n   expected: {scenario.expectation}")
        return 0
    scenario = scenarios[args.scenario]
    print(f"Scenario {scenario.key}: {scenario.title}\nExpected: {scenario.expectation}\n")

    def token(h: SeededHousehold) -> str:
        now = int(time.time())
        return jwt.encode(
            {"sub": h.auth_subject, "email": h.email, "aud": settings.auth_jwt_audience, "iat": now, "exp": now + 600},
            settings.auth_jwt_secret,
            algorithm="HS256",
        )

    with httpx.Client(base_url=args.base_url, timeout=15) as client:
        for step in sorted(scenario.steps, key=lambda s: s.at_s):
            moment = start + timedelta(seconds=step.at_s)
            if args.realtime and (wait := (moment - datetime.now(UTC)).total_seconds()) > 0:
                time.sleep(wait)
            if step.household is None:
                continue
            response = client.post(
                "/api/v1/connectivity/events",
                headers={"Authorization": f"Bearer {token(step.household)}"},
                json={
                    "device_id": str(step.household.device_id),
                    "reported_via": step.reported_via,
                    "sent_at": datetime.now(UTC).isoformat(),
                    "events": step.events,
                },
            )
            results = [
                o["result"] + (f" ({o['reason']})" if o.get("reason") else "") for o in response.json().get("outcomes", [])
            ]
            summary = ", ".join(sorted(set(results))) if results else response.text[:120]
            if step.at_s >= 0:
                print(
                    f"t+{step.at_s:>6.0f}s  household {step.household.index:02d} ({step.household.isp:<8}) -> {response.status_code} {summary}"
                )
        admin = hood.admin
        incidents = client.get("/api/v1/admin/incidents?limit=10", headers={"Authorization": f"Bearer {token(admin)}"}).json()
        print("\nIncidents now on record (most recent first):")
        for incident in incidents.get("incidents", [])[:6]:
            print(
                f"  {incident['classification']:<38} level {incident['alert_level']}  {incident['status']:<8} "
                f"{incident['affected_household_count']} household(s)  started {incident['started_at']}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
