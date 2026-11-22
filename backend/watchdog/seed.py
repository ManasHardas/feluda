"""Seed a simulated neighborhood for local development, demos and the scenario simulator.

    python -m watchdog.seed            # create it if missing
    python -m watchdog.seed --reset    # delete and recreate it

Everything here is invented: addresses, coordinates and @example.test e-mail addresses. The seeded
residents can sign in through the local development auth emulator (resident01@example.test ...).
Never run this against a production database; the command refuses when ENVIRONMENT is production.
"""

from __future__ import annotations

import sys
import uuid
from dataclasses import dataclass
from uuid import UUID

from psycopg.types.json import Jsonb

from .authentication.dev_provider import DevAuthState
from .db import Database
from .invitation_tokens import anonymous_reference
from .normalization import normalize_house_number, normalize_street_name

NEIGHBORHOOD_NAME = "Maple Hollow (simulated)"
_NS = uuid.UUID("6f1b0a52-2f65-4f0a-9d3e-6b6e1c0a7d11")
_CENTER = (33.0500, -97.0500)
_STREETS = ["Maple Hollow Drive", "Juniper Court", "Cedar Bend Lane"]
# 10 AT&T, 10 Xfinity, 2 other, 2 unknown
_ISPS = ["att", "xfinity"] * 10 + ["other", "other", "unknown", "unknown"]

ANDROID_CAPABILITIES = {
    "can_measure_rssi": True, "can_detect_band": True, "can_probe_gateway": True, "can_route_over_cellular": True,
    "can_detect_power_source": True, "can_detect_wifi_radio_state": True, "can_identify_home_network": True,
    "background_monitoring": "callback",
}
IOS_CAPABILITIES = {
    "can_measure_rssi": False, "can_detect_band": False, "can_probe_gateway": False, "can_route_over_cellular": False,
    "can_detect_power_source": True, "can_detect_wifi_radio_state": False, "can_identify_home_network": True,
    "background_monitoring": "opportunistic",
}


@dataclass(frozen=True)
class SeededHousehold:
    index: int
    household_id: UUID
    user_id: UUID
    device_id: UUID
    email: str
    auth_subject: str
    isp: str
    platform: str
    house_number: str
    street_name: str
    role: str


@dataclass(frozen=True)
class SeededNeighborhood:
    neighborhood_id: UUID
    households: list[SeededHousehold]

    def by_isp(self, isp: str) -> list[SeededHousehold]:
        return [h for h in self.households if h.isp == isp]

    @property
    def admin(self) -> SeededHousehold:
        return next(h for h in self.households if h.role == "admin")


def seed_neighborhood(db: Database, name: str = NEIGHBORHOOD_NAME, count: int = 24, *, with_devices: bool = True,
                      email_domain: str = "example.test") -> SeededNeighborhood:
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
            email = f"resident{n:02d}-{neighborhood_id.hex[:6]}@{email_domain}" if name != NEIGHBORHOOD_NAME else f"resident{n:02d}@{email_domain}"
            subject = DevAuthState.subject_for(email)
            role = "admin" if i == 0 else "resident"
            household_id = conn.execute(
                "INSERT INTO nw.households (neighborhood_id, anonymous_reference, normalized_house_number,"
                " normalized_street_name, isp, latitude, longitude, membership_status)"
                " VALUES (%s, %s, %s, %s, %s, %s, %s, 'active') RETURNING id",
                [neighborhood_id, anonymous_reference(), normalize_house_number(number), normalize_street_name(street),
                 isp, lat, lon],
            ).fetchone()["id"]
            user_id = conn.execute(
                "INSERT INTO nw.users (email, auth_subject, status) VALUES (%s, %s, 'active') RETURNING id", [email, subject]
            ).fetchone()["id"]
            conn.execute(
                "INSERT INTO nw.household_members (household_id, user_id, role) VALUES (%s, %s, %s)",
                [household_id, user_id, role],
            )
            platform = "android" if i % 2 == 0 else "ios"
            device_id = uuid.uuid5(_NS, f"{neighborhood_id}:{n}")
            if with_devices:
                conn.execute(
                    "INSERT INTO nw.devices (id, user_id, household_id, platform, os_version, app_version, capabilities)"
                    " VALUES (%s, %s, %s, %s, %s, %s, %s)",
                    [device_id, user_id, household_id, platform, "15" if platform == "android" else "18.2", "0.1.0",
                     Jsonb(ANDROID_CAPABILITIES if platform == "android" else IOS_CAPABILITIES)],
                )
            households.append(SeededHousehold(i, household_id, user_id, device_id, email, subject, isp, platform, number,
                                              street, role))
    return SeededNeighborhood(neighborhood_id, households)


def main() -> int:
    from .settings import get_settings

    settings = get_settings()
    if settings.environment == "production":
        print("Refusing to seed simulated data into a production database.")
        return 2
    db = Database(settings.database_url, 2)
    db.open()
    try:
        with db.service() as conn:
            existing = conn.execute("SELECT id FROM nw.neighborhoods WHERE name = %s", [NEIGHBORHOOD_NAME]).fetchone()
            if existing and "--reset" in sys.argv:
                conn.execute(
                    "DELETE FROM nw.users WHERE id IN (SELECT hm.user_id FROM nw.household_members hm"
                    " JOIN nw.households h ON h.id = hm.household_id WHERE h.neighborhood_id = %s)", [existing["id"]])
                conn.execute("DELETE FROM nw.neighborhoods WHERE id = %s", [existing["id"]])
                existing = None
        if existing:
            print(f"'{NEIGHBORHOOD_NAME}' already exists. Use --reset to recreate it.")
            return 0
        seeded = seed_neighborhood(db)
        print(f"Seeded '{NEIGHBORHOOD_NAME}' with {len(seeded.households)} households.")
        print(f"Administrator sign-in (local auth emulator): {seeded.admin.email}")
        print("Residents: resident01@example.test ... resident24@example.test")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
