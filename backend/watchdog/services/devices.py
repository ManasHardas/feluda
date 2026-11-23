"""Device registration and revocation. A device id is a random install identifier, not hardware."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from psycopg.types.json import Jsonb

from ..authentication.dependencies import Member
from ..db import Database
from ..errors import ApiError, not_found
from ..schemas import DeviceOut, RegisterDeviceRequest


def device_out(row) -> DeviceOut:
    return DeviceOut(
        device_id=row["id"],
        platform=row["platform"],
        os_version=row["os_version"],
        app_version=row["app_version"],
        status=row["status"],
        monitoring_enabled=row["monitoring_enabled"],
        last_seen_at=row["last_seen_at"],
        created_at=row["created_at"],
    )


def register(db: Database, member: Member, body: RegisterDeviceRequest) -> DeviceOut:
    """Update what the calling phone reports about itself (new OS or app version, capabilities).

    A phone is created only by enrolment; this never creates one and only ever touches the caller's own.
    """
    if body.device_id != member.device_id:
        raise ApiError(409, "device_conflict", "A phone can only update its own registration.")
    capabilities = Jsonb(body.capabilities.model_dump(mode="json"))
    with db.api(member.user_id) as conn:
        existing = conn.execute("SELECT platform FROM nw.devices WHERE id = %s FOR UPDATE", [body.device_id]).fetchone()
        if existing is None or existing["platform"] != body.platform:
            raise ApiError(409, "device_conflict", "This device identifier cannot be registered. Reinstall the app.")
        row = conn.execute(
            "UPDATE nw.devices SET os_version = %s, app_version = %s, capabilities = %s WHERE id = %s RETURNING *",
            [body.os_version, body.app_version, capabilities, body.device_id],
        ).fetchone()
        return device_out(row)


def list_devices(db: Database, member: Member) -> list[DeviceOut]:
    """The phones of the caller's household (platform and versions only; never another household's)."""
    with db.service() as conn:
        rows = conn.execute(
            "SELECT * FROM nw.devices WHERE household_id = %s AND status = 'active' ORDER BY created_at", [member.household_id]
        ).fetchall()
    return [device_out(r) for r in rows]


def revoke(db: Database, member: Member, device_id: UUID, now: datetime) -> None:
    """A phone can remove itself or another phone of its own household."""
    with db.service() as conn:
        owned = conn.execute(
            "SELECT id FROM nw.devices WHERE id = %s AND household_id = %s", [device_id, member.household_id]
        ).fetchone()
    if owned is None:
        raise not_found("Device not found.")
    revoke_as_service(db, device_id, now)


def revoke_as_service(db: Database, device_id: UUID, now: datetime) -> bool:
    with db.service() as conn:
        row = conn.execute(
            "UPDATE nw.devices SET status = 'revoked', monitoring_enabled = false WHERE id = %s RETURNING id", [device_id]
        ).fetchone()
        if row is None:
            return False
        conn.execute("DELETE FROM nw.notification_tokens WHERE device_id = %s", [device_id])
        conn.execute("DELETE FROM nw.device_credentials WHERE device_id = %s", [device_id])
        conn.execute(
            "UPDATE nw.device_sessions SET ended_at = %s, end_reason = 'device_revoked' WHERE device_id = %s AND ended_at IS NULL",
            [now, device_id],
        )
    return True
