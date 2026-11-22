"""Device registration and revocation. A device id is a random install identifier, not hardware."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from psycopg.types.json import Jsonb

from ..authentication.dependencies import Member
from ..db import Database
from ..errors import ApiError, not_found
from ..schemas import DeviceOut, RegisterDeviceRequest

MAX_DEVICES_PER_USER = 5


def _out(row) -> DeviceOut:
    return DeviceOut(
        device_id=row["id"], platform=row["platform"], os_version=row["os_version"], app_version=row["app_version"],
        status=row["status"], monitoring_enabled=row["monitoring_enabled"], last_seen_at=row["last_seen_at"],
        created_at=row["created_at"],
    )


def register(db: Database, member: Member, body: RegisterDeviceRequest) -> DeviceOut:
    capabilities = Jsonb(body.capabilities.model_dump(mode="json"))
    with db.api(member.user_id) as conn:
        existing = conn.execute("SELECT * FROM nw.devices WHERE id = %s FOR UPDATE", [body.device_id]).fetchone()
        if existing is not None:
            if existing["status"] == "revoked":
                raise ApiError(403, "device_revoked", "This device registration was revoked. Reinstall the app to register again.")
            if existing["household_id"] != member.household_id or existing["platform"] != body.platform:
                raise ApiError(409, "device_conflict", "This device identifier cannot be registered. Reinstall the app.")
            row = conn.execute(
                "UPDATE nw.devices SET os_version = %s, app_version = %s, capabilities = %s WHERE id = %s RETURNING *",
                [body.os_version, body.app_version, capabilities, body.device_id],
            ).fetchone()
            return _out(row)
        count = conn.execute("SELECT count(*) AS n FROM nw.devices WHERE status = 'active'").fetchone()["n"]
        if count >= MAX_DEVICES_PER_USER:
            raise ApiError(409, "device_limit", f"At most {MAX_DEVICES_PER_USER} devices can be registered per person. Revoke one first.")
        row = conn.execute(
            "INSERT INTO nw.devices (id, user_id, household_id, platform, os_version, app_version, capabilities)"
            " VALUES (%s, %s, %s, %s, %s, %s, %s) ON CONFLICT (id) DO NOTHING RETURNING *",
            [body.device_id, member.user_id, member.household_id, body.platform, body.os_version, body.app_version,
             capabilities],
        ).fetchone()
        if row is None:  # the identifier exists but belongs to someone else (invisible under row-level security)
            raise ApiError(409, "device_conflict", "This device identifier cannot be registered. Reinstall the app.")
        return _out(row)


def list_devices(db: Database, member: Member) -> list[DeviceOut]:
    with db.api(member.user_id) as conn:
        return [_out(r) for r in conn.execute("SELECT * FROM nw.devices ORDER BY created_at")]


def revoke(db: Database, user_id: UUID, device_id: UUID, now: datetime) -> None:
    with db.api(user_id) as conn:
        owned = conn.execute("SELECT id FROM nw.devices WHERE id = %s", [device_id]).fetchone()
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
        conn.execute(
            "UPDATE nw.device_sessions SET ended_at = %s, end_reason = 'device_revoked' WHERE device_id = %s AND ended_at IS NULL",
            [now, device_id],
        )
    return True
