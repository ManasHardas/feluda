"""Operator commands that are deliberately not exposed through the API.

    python -m watchdog.ops create-neighborhood --name "Oak Ridge" --admin-email a@example.org \\
        --house-number 12 --street "Oak Ridge Road" [--latitude 33.1 --longitude -97.1]
    python -m watchdog.ops set-neighborhood-status --neighborhood-id <uuid> --status disabled|active
    python -m watchdog.ops list-neighborhoods
    python -m watchdog.ops purge                      # run data retention now
    python -m watchdog.ops pending-identity-deletions # identity records the operator must delete by hand

Each command writes an audit record with actor_type "operator".
"""

from __future__ import annotations

import argparse
from uuid import UUID

from .clock import Clock
from .db import Database
from .invitation_tokens import anonymous_reference
from .normalization import normalize_house_number, normalize_street_name
from .services import retention
from .services.audit import audit
from .settings import get_settings


def create_neighborhood(
    db: Database,
    name: str,
    admin_email: str,
    house_number: str,
    street: str,
    latitude: float | None = None,
    longitude: float | None = None,
    isp: str = "unknown",
) -> UUID:
    email = admin_email.strip().lower()
    with db.service() as conn:
        neighborhood_id = conn.execute("INSERT INTO nw.neighborhoods (name) VALUES (%s) RETURNING id", [name]).fetchone()["id"]
        household_id = conn.execute(
            "INSERT INTO nw.households (neighborhood_id, anonymous_reference, normalized_house_number,"
            " normalized_street_name, isp, latitude, longitude, membership_status)"
            " VALUES (%s, %s, %s, %s, %s, %s, %s, 'active') RETURNING id",
            [
                neighborhood_id,
                anonymous_reference(),
                normalize_house_number(house_number),
                normalize_street_name(street),
                isp,
                latitude,
                longitude,
            ],
        ).fetchone()["id"]
        user = conn.execute("SELECT id FROM nw.users WHERE email = %s AND status <> 'deleted'", [email]).fetchone()
        if user is None:
            user = conn.execute("INSERT INTO nw.users (email) VALUES (%s) RETURNING id", [email]).fetchone()
        conn.execute(
            "INSERT INTO nw.household_members (household_id, user_id, role) VALUES (%s, %s, 'admin')", [household_id, user["id"]]
        )
        audit(
            conn,
            action="neighborhood_created",
            neighborhood_id=neighborhood_id,
            actor_type="operator",
            target_type="neighborhood",
            target_id=neighborhood_id,
        )
    return neighborhood_id


def set_neighborhood_status(db: Database, neighborhood_id: UUID, status: str) -> bool:
    with db.service() as conn:
        row = conn.execute(
            "UPDATE nw.neighborhoods SET status = %s WHERE id = %s RETURNING id", [status, neighborhood_id]
        ).fetchone()
        if row:
            audit(
                conn,
                action=f"neighborhood_{status}",
                neighborhood_id=neighborhood_id,
                actor_type="operator",
                target_type="neighborhood",
                target_id=neighborhood_id,
            )
    return row is not None


def main() -> int:
    parser = argparse.ArgumentParser(prog="python -m watchdog.ops")
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("create-neighborhood")
    create.add_argument("--name", required=True)
    create.add_argument("--admin-email", required=True)
    create.add_argument("--house-number", required=True)
    create.add_argument("--street", required=True)
    create.add_argument("--latitude", type=float)
    create.add_argument("--longitude", type=float)
    create.add_argument("--isp", default="unknown")
    status = sub.add_parser("set-neighborhood-status")
    status.add_argument("--neighborhood-id", type=UUID, required=True)
    status.add_argument("--status", choices=["active", "disabled"], required=True)
    sub.add_parser("list-neighborhoods")
    sub.add_parser("purge")
    sub.add_parser("pending-identity-deletions")
    args = parser.parse_args()

    settings = get_settings()
    db = Database(settings.database_url, 2)
    db.open()
    try:
        if args.command == "create-neighborhood":
            neighborhood_id = create_neighborhood(
                db, args.name, args.admin_email, args.house_number, args.street, args.latitude, args.longitude, args.isp
            )
            print(f"Created neighborhood {neighborhood_id}. The administrator signs in with the e-mail address given.")
        elif args.command == "set-neighborhood-status":
            ok = set_neighborhood_status(db, args.neighborhood_id, args.status)
            print("updated" if ok else "neighborhood not found")
            return 0 if ok else 1
        elif args.command == "list-neighborhoods":
            with db.service() as conn:
                for row in conn.execute(
                    "SELECT n.id, n.name, n.status, (SELECT count(*) FROM nw.households h WHERE h.neighborhood_id = n.id"
                    " AND h.membership_status = 'active') AS households FROM nw.neighborhoods n ORDER BY n.created_at"
                ):
                    print(f"{row['id']}  {row['status']:<8}  {row['households']:>4} households  {row['name']}")
        elif args.command == "purge":
            print(retention.purge(db, Clock().now()))
        elif args.command == "pending-identity-deletions":
            with db.service() as conn:
                for row in conn.execute(
                    "SELECT created_at, detail->>'auth_subject' AS subject FROM nw.security_events"
                    " WHERE kind = 'identity_deletion_pending' ORDER BY created_at"
                ):
                    print(f"{row['created_at'].isoformat()}  {row['subject']}")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
