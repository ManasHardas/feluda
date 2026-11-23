"""Operator commands that are deliberately not exposed through the API.

    python -m watchdog.ops create-neighborhood --name "Oak Ridge"
    python -m watchdog.ops set-neighborhood-status --neighborhood-id <uuid> --status disabled|active
    python -m watchdog.ops list-neighborhoods
    python -m watchdog.ops purge                      # run data retention now

Each command writes an audit record with actor_type "operator".
"""

from __future__ import annotations

import argparse
from uuid import UUID

from .clock import Clock
from .db import Database
from .services import retention
from .services.audit import audit
from .settings import get_settings


def create_neighborhood(db: Database, name: str) -> UUID:
    """Create an empty neighborhood. Phones join it through invitation links (see apps/admin)."""
    with db.service() as conn:
        neighborhood_id = conn.execute("INSERT INTO nw.neighborhoods (name) VALUES (%s) RETURNING id", [name]).fetchone()["id"]
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
    status = sub.add_parser("set-neighborhood-status")
    status.add_argument("--neighborhood-id", type=UUID, required=True)
    status.add_argument("--status", choices=["active", "disabled"], required=True)
    sub.add_parser("list-neighborhoods")
    sub.add_parser("purge")
    args = parser.parse_args()

    settings = get_settings()
    db = Database(settings.database_url, 2)
    db.open()
    try:
        if args.command == "create-neighborhood":
            neighborhood_id = create_neighborhood(db, args.name)
            print(f"Created neighborhood {neighborhood_id}. Create invitation links with apps/admin/nwadmin.py.")
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
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
