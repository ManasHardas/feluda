"""Seed a simulated neighborhood for local development, demos and the scenario simulator.

    python -m watchdog.seed            # create it if missing
    python -m watchdog.seed --reset    # delete and recreate it

Everything here is invented: addresses and coordinates. Each simulated phone gets a device secret
that the simulator can recompute (see `simulated_secret`), so it can report as that phone.
Never run this against real residents; the command refuses when ENVIRONMENT is staging or production.
"""

from __future__ import annotations

import hashlib
import sys
import uuid
from dataclasses import dataclass
from uuid import UUID

from psycopg.types.json import Jsonb

from .db import Database
from .invitation_tokens import anonymous_reference, hash_token
from .normalization import normalize_house_number, normalize_street_name

NEIGHBORHOOD_NAME = "Maple Hollow (simulated)"
_NS = uuid.UUID("6f1b0a52-2f65-4f0a-9d3e-6b6e1c0a7d11")
_CENTER = (33.0500, -97.0500)
_STREETS = ["Maple Hollow Drive", "Juniper Court", "Cedar Bend Lane"]
# 10 AT&T, 10 Xfinity, 2 other, 2 unknown
_ISPS = ["att", "xfinity"] * 10 + ["other", "other", "unknown", "unknown"]

ANDROID_CAPABILITIES = {
    "can_measure_rssi": True,
    "can_detect_band": True,
    "can_probe_gateway": True,
    "can_route_over_cellular": True,
    "can_detect_power_source": True,
    "can_detect_wifi_radio_state": True,
    "can_identify_home_network": True,
    "background_monitoring": "callback",
}
IOS_CAPABILITIES = {
    "can_measure_rssi": False,
    "can_detect_band": False,
    "can_probe_gateway": False,
    "can_route_over_cellular": False,
    "can_detect_power_source": True,
    "can_detect_wifi_radio_state": False,
    "can_identify_home_network": True,
    "background_monitoring": "opportunistic",
}


@dataclass(frozen=True)
class SeededHousehold:
    index: int
    household_id: UUID
    user_id: UUID
    device_id: UUID
    device_secret: str
    isp: str
    platform: str
    house_number: str
    street_name: str


@dataclass(frozen=True)
class SeededNeighborhood:
    neighborhood_id: UUID
    households: list[SeededHousehold]

    def by_isp(self, isp: str) -> list[SeededHousehold]:
        return [h for h in self.households if h.isp == isp]


def simulated_secret(device_id: UUID) -> str:
    """Device secret of a simulated phone. Predictable on purpose: simulated data only."""
    return "sim_" + hashlib.sha256(f"simulated-phone:{device_id}".encode()).hexdigest()


def seed_neighborhood(db: Database, name: str = NEIGHBORHOOD_NAME, count: int = 24) -> SeededNeighborhood:
    households: list[SeededHousehold] = []
    with db.service() as conn:
        neighborhood_id = conn.execute("INSERT INTO nw.neighborhoods (name) VALUES (%s) RETURNING id", [name]).fetchone()["id"]
        for i in range(count):
            n = i + 1
            street = _STREETS[i % len(_STREETS)]
            number = str(100 + 2 * n)
            # A grid with roughly 40 m spacing; the last house sits about 900 m away to exercise the radius.
            lat = _CENTER[0] + (i // 6) * 0.00036
            lon = _CENTER[1] + (i % 6) * 0.00043
            if i == count - 1 and count >= 20:
                lat += 0.0081
            isp = _ISPS[i % len(_ISPS)]
            household_id = conn.execute(
                "INSERT INTO nw.households (neighborhood_id, anonymous_reference, normalized_house_number,"
                " normalized_street_name, isp, latitude, longitude, membership_status)"
                " VALUES (%s, %s, %s, %s, %s, %s, %s, 'active') RETURNING id",
                [
                    neighborhood_id,
                    anonymous_reference(),
                    normalize_house_number(number),
                    normalize_street_name(street),
                    isp,
                    lat,
                    lon,
                ],
            ).fetchone()["id"]
            user_id = conn.execute("INSERT INTO nw.users (status) VALUES ('active') RETURNING id").fetchone()["id"]
            conn.execute("INSERT INTO nw.household_members (household_id, user_id) VALUES (%s, %s)", [household_id, user_id])
            platform = "android" if i % 2 == 0 else "ios"
            device_id = uuid.uuid5(_NS, f"{neighborhood_id}:{n}")
            secret = simulated_secret(device_id)
            conn.execute(
                "INSERT INTO nw.devices (id, user_id, household_id, platform, os_version, app_version, capabilities)"
                " VALUES (%s, %s, %s, %s, %s, %s, %s)",
                [
                    device_id,
                    user_id,
                    household_id,
                    platform,
                    "15" if platform == "android" else "18.2",
                    "0.1.0",
                    Jsonb(ANDROID_CAPABILITIES if platform == "android" else IOS_CAPABILITIES),
                ],
            )
            conn.execute(
                "INSERT INTO nw.device_credentials (device_id, secret_hash) VALUES (%s, %s)", [device_id, hash_token(secret)]
            )
            households.append(SeededHousehold(i, household_id, user_id, device_id, secret, isp, platform, number, street))
    return SeededNeighborhood(neighborhood_id, households)


def main() -> int:
    from .settings import get_settings

    settings = get_settings()
    if settings.is_production_like:
        print("Refusing to seed simulated data into a staging or production database.")
        return 2
    db = Database(settings.database_url, 2)
    db.open()
    try:
        with db.service() as conn:
            existing = conn.execute("SELECT id FROM nw.neighborhoods WHERE name = %s", [NEIGHBORHOOD_NAME]).fetchone()
            if existing and "--reset" in sys.argv:
                conn.execute(
                    "DELETE FROM nw.users WHERE id IN (SELECT hm.user_id FROM nw.household_members hm"
                    " JOIN nw.households h ON h.id = hm.household_id WHERE h.neighborhood_id = %s)",
                    [existing["id"]],
                )
                conn.execute("DELETE FROM nw.neighborhoods WHERE id = %s", [existing["id"]])
                existing = None
        if existing:
            print(f"'{NEIGHBORHOOD_NAME}' already exists. Use --reset to recreate it.")
            return 0
        seeded = seed_neighborhood(db)
        print(f"Seeded '{NEIGHBORHOOD_NAME}' with {len(seeded.households)} households.")
        print("Play a scenario with `make simulate SCENARIO=C`.")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
