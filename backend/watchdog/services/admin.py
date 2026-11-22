"""Neighborhood administration. Every action is scoped to the administrator's own neighborhood,
taken from their membership record, and written to the audit log.

Administrators see addresses and membership (they manage the approved list) but never e-mail
addresses, push tokens, device details or another household's raw telemetry.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any
from uuid import UUID

from psycopg.errors import UniqueViolation
from psycopg.types.json import Jsonb

from ..authentication.dependencies import Member
from ..config import DEFAULTS, load_config, validate_overrides
from ..db import Database
from ..errors import ApiError, not_found
from ..invitation_tokens import anonymous_reference, hash_token, new_token
from ..normalization import normalize_house_number, normalize_street_name
from ..schemas import (
    AdminAuditLogOut,
    AdminConfigurationOut,
    AdminCreateHouseholdRequest,
    AdminCreateInvitationRequest,
    AdminDeliveryFailureOut,
    AdminHouseholdOut,
    AdminInvitationCreatedOut,
    AdminInvitationOut,
    AdminNeighborhoodSummaryOut,
    IncidentOut,
)
from ..settings import Settings
from . import devices as device_service
from .audit import audit
from .incidents import _base
from .status import device_monitoring_status

_HOUSEHOLD_SELECT = (
    "SELECT h.id, h.anonymous_reference, h.normalized_house_number, h.normalized_street_name, h.isp,"
    " h.membership_status, h.latitude IS NOT NULL AND h.longitude IS NOT NULL AS has_location, h.created_at,"
    " (SELECT count(*) FROM nw.household_members hm WHERE hm.household_id = h.id AND hm.status = 'active') AS member_count,"
    " (SELECT count(*) FROM nw.devices d WHERE d.household_id = h.id AND d.status = 'active') AS device_count"
    " FROM nw.households h"
)


def _household_out(row) -> AdminHouseholdOut:
    return AdminHouseholdOut(
        household_id=row["id"], anonymous_reference=row["anonymous_reference"],
        house_number=row["normalized_house_number"], street_name=row["normalized_street_name"], isp=row["isp"],
        membership_status=row["membership_status"], member_count=row["member_count"], device_count=row["device_count"],
        has_location=row["has_location"], created_at=row["created_at"],
    )


def create_household(db: Database, admin: Member, body: AdminCreateHouseholdRequest) -> AdminHouseholdOut:
    try:
        number, street = normalize_house_number(body.house_number), normalize_street_name(body.street_name)
    except ValueError as exc:
        raise ApiError(422, "validation_error", str(exc)) from exc
    if (body.latitude is None) != (body.longitude is None):
        raise ApiError(422, "validation_error", "Provide both latitude and longitude, or neither.")
    try:
        with db.service() as conn:
            row = conn.execute(
                "INSERT INTO nw.households (neighborhood_id, anonymous_reference, normalized_house_number,"
                " normalized_street_name, isp, latitude, longitude) VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING id",
                [admin.neighborhood_id, anonymous_reference(), number, street, body.isp.value, body.latitude, body.longitude],
            ).fetchone()
            audit(conn, action="household_approved", neighborhood_id=admin.neighborhood_id, actor_user_id=admin.user_id,
                  target_type="household", target_id=row["id"])
            return _household_out(conn.execute(_HOUSEHOLD_SELECT + " WHERE h.id = %s", [row["id"]]).fetchone())
    except UniqueViolation as exc:
        raise ApiError(409, "household_exists", "This address is already on the approved list.") from exc


def list_households(db: Database, admin: Member) -> list[AdminHouseholdOut]:
    with db.service() as conn:
        rows = conn.execute(
            _HOUSEHOLD_SELECT + " WHERE h.neighborhood_id = %s ORDER BY h.normalized_street_name, h.normalized_house_number",
            [admin.neighborhood_id],
        ).fetchall()
        audit(conn, action="household_list_viewed", neighborhood_id=admin.neighborhood_id, actor_user_id=admin.user_id)
    return [_household_out(r) for r in rows]


def _set_household_status(db: Database, admin: Member, household_id: UUID, new_status: str, action: str,
                          reason: str | None, now: datetime) -> None:
    with db.service() as conn:
        row = conn.execute(
            "SELECT id, membership_status FROM nw.households WHERE id = %s AND neighborhood_id = %s FOR UPDATE",
            [household_id, admin.neighborhood_id],
        ).fetchone()
        if row is None:
            raise not_found("Household not found.")  # also the answer for households of other neighborhoods
        if household_id == admin.household_id and new_status in ("suspended", "removed"):
            raise ApiError(409, "cannot_remove_self", "Administrators cannot suspend or remove their own household.")
        if new_status == "active" and row["membership_status"] != "suspended":
            raise ApiError(409, "invalid_state", "Only a suspended household can be reinstated.")
        conn.execute("UPDATE nw.households SET membership_status = %s WHERE id = %s", [new_status, household_id])
        if new_status == "removed":
            conn.execute("UPDATE nw.household_members SET status = 'removed' WHERE household_id = %s", [household_id])
            conn.execute("DELETE FROM nw.devices WHERE household_id = %s", [household_id])  # cascades to events and tokens
            conn.execute("DELETE FROM nw.connectivity_events WHERE household_id = %s", [household_id])
            conn.execute("UPDATE nw.households SET display_label = NULL WHERE id = %s", [household_id])
        if new_status in ("suspended", "removed"):
            conn.execute(
                "UPDATE nw.invitations SET status = 'revoked' WHERE household_id = %s AND status = 'active'", [household_id]
            )
            conn.execute(
                "UPDATE nw.device_sessions SET ended_at = %s, end_reason = %s WHERE ended_at IS NULL AND device_id IN"
                " (SELECT id FROM nw.devices WHERE household_id = %s)",
                [now, f"household_{new_status}", household_id],
            )
        audit(conn, action=action, neighborhood_id=admin.neighborhood_id, actor_user_id=admin.user_id,
              target_type="household", target_id=household_id, metadata={"reason": reason} if reason else None)


def suspend_household(db, admin, household_id, reason, now):
    _set_household_status(db, admin, household_id, "suspended", "household_suspended", reason, now)


def reinstate_household(db, admin, household_id, reason, now):
    _set_household_status(db, admin, household_id, "active", "household_reinstated", reason, now)


def remove_household(db, admin, household_id, reason, now):
    _set_household_status(db, admin, household_id, "removed", "household_removed", reason, now)


def create_invitation(db: Database, settings: Settings, admin: Member, body: AdminCreateInvitationRequest,
                      now: datetime) -> AdminInvitationCreatedOut:
    token = new_token()
    expires_at = now + timedelta(hours=body.expires_in_hours)
    with db.service() as conn:
        if body.household_id is not None:
            household = conn.execute(
                "SELECT membership_status FROM nw.households WHERE id = %s AND neighborhood_id = %s",
                [body.household_id, admin.neighborhood_id],
            ).fetchone()
            if household is None or household["membership_status"] in ("suspended", "removed"):
                raise not_found("Household not found.")
        row = conn.execute(
            "INSERT INTO nw.invitations (neighborhood_id, household_id, token_hash, role, expires_at, created_by)"
            " VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
            [admin.neighborhood_id, body.household_id, hash_token(token), body.role.value, expires_at, admin.user_id],
        ).fetchone()
        audit(conn, action="invitation_created", neighborhood_id=admin.neighborhood_id, actor_user_id=admin.user_id,
              target_type="invitation", target_id=row["id"],
              metadata={"role": body.role.value, "household_bound": body.household_id is not None,
                        "expires_in_hours": body.expires_in_hours})
    # The token travels in the URL fragment, which browsers and link-preview crawlers do not send to servers.
    return AdminInvitationCreatedOut(
        invitation_id=row["id"], invitation_url=f"{settings.public_base_url.rstrip('/')}/join#t={token}", expires_at=expires_at
    )


def list_invitations(db: Database, admin: Member, now: datetime) -> list[AdminInvitationOut]:
    with db.service() as conn:
        rows = conn.execute(
            "SELECT id, CASE WHEN status = 'active' AND expires_at <= %s THEN 'revoked' ELSE status END AS status,"
            " household_id IS NOT NULL AS household_bound, role, expires_at, used_at, created_at"
            " FROM nw.invitations WHERE neighborhood_id = %s ORDER BY created_at DESC LIMIT 500",
            [now, admin.neighborhood_id],
        ).fetchall()
    return [AdminInvitationOut(invitation_id=r["id"], **{k: r[k] for k in r if k != "id"}) for r in rows]


def revoke_invitation(db: Database, admin: Member, invitation_id: UUID) -> None:
    with db.service() as conn:
        row = conn.execute(
            "UPDATE nw.invitations SET status = 'revoked' WHERE id = %s AND neighborhood_id = %s AND status = 'active'"
            " RETURNING id",
            [invitation_id, admin.neighborhood_id],
        ).fetchone()
        if row is None:
            raise not_found("Invitation not found or no longer active.")
        audit(conn, action="invitation_revoked", neighborhood_id=admin.neighborhood_id, actor_user_id=admin.user_id,
              target_type="invitation", target_id=invitation_id)


def revoke_device(db: Database, admin: Member, device_id: UUID, reason: str | None, now: datetime) -> None:
    with db.service() as conn:
        row = conn.execute(
            "SELECT d.id FROM nw.devices d JOIN nw.households h ON h.id = d.household_id"
            " WHERE d.id = %s AND h.neighborhood_id = %s",
            [device_id, admin.neighborhood_id],
        ).fetchone()
        if row is None:
            raise not_found("Device not found.")
        audit(conn, action="device_revoked", neighborhood_id=admin.neighborhood_id, actor_user_id=admin.user_id,
              target_type="device", target_id=device_id, metadata={"reason": reason} if reason else None)
    device_service.revoke_as_service(db, device_id, now)


def summary(db: Database, admin: Member, now: datetime) -> AdminNeighborhoodSummaryOut:
    with db.service() as conn:
        cfg = load_config(conn, admin.neighborhood_id)
        hood = conn.execute("SELECT id, name, status FROM nw.neighborhoods WHERE id = %s", [admin.neighborhood_id]).fetchone()
        by_status = {
            r["membership_status"]: r["n"]
            for r in conn.execute(
                "SELECT membership_status, count(*) AS n FROM nw.households WHERE neighborhood_id = %s GROUP BY 1",
                [admin.neighborhood_id],
            )
        }
        devices = conn.execute(
            "SELECT d.household_id, d.platform, d.status, d.monitoring_enabled, d.last_state, d.last_seen_at,"
            " EXISTS (SELECT 1 FROM nw.device_sessions s WHERE s.device_id = d.id AND s.ended_at IS NULL) AS open_session"
            " FROM nw.devices d JOIN nw.households h ON h.id = d.household_id"
            " WHERE h.neighborhood_id = %s AND h.membership_status = 'active' AND d.status = 'active'",
            [admin.neighborhood_id],
        ).fetchall()
        monitoring: dict[str, int] = {}
        platforms: dict[str, int] = {}
        for device in devices:
            status = device_monitoring_status(device, now, cfg).value
            monitoring[status] = monitoring.get(status, 0) + 1
            platforms[device["platform"]] = platforms.get(device["platform"], 0) + 1
        counts = conn.execute(
            "SELECT (SELECT count(*) FROM nw.incidents WHERE neighborhood_id = %(n)s AND status = 'open') AS open,"
            " (SELECT count(*) FROM nw.incidents WHERE neighborhood_id = %(n)s AND status <> 'merged' AND started_at >= %(m)s) AS month,"
            " (SELECT count(*) FROM nw.notification_deliveries WHERE neighborhood_id = %(n)s"
            "   AND status IN ('failed', 'skipped_not_configured') AND created_at >= %(w)s) AS failures",
            {"n": admin.neighborhood_id, "m": now - timedelta(days=30), "w": now - timedelta(days=7)},
        ).fetchone()
    return AdminNeighborhoodSummaryOut(
        neighborhood_id=hood["id"], name=hood["name"], status=hood["status"], households_by_status=by_status,
        monitoring_by_status=monitoring, devices_by_platform=platforms, open_incidents=counts["open"],
        incidents_last_30_days=counts["month"], notification_failures_last_7_days=counts["failures"],
    )


def incident_history(db: Database, admin: Member, limit: int) -> list[IncidentOut]:
    """All incidents of the neighborhood, without saying which households were involved."""
    with db.service() as conn:
        rows = conn.execute(
            "SELECT * FROM nw.incidents WHERE neighborhood_id = %s AND status <> 'merged' ORDER BY started_at DESC LIMIT %s",
            [admin.neighborhood_id, limit],
        ).fetchall()
    out = []
    for row in rows:
        data = _base(row, False)
        if row["scope"] == "household":
            data["approximate_scope"] = "One household (not identified)"
            data["summary"] = data["summary"].replace("at your household", "at one household").replace("at home", "at one household")
        out.append(IncidentOut(**data))
    return out


def export_incidents(db: Database, admin: Member, now: datetime) -> list[dict[str, Any]]:
    """Anonymized incident data: no household, device or user identifiers, no coordinates."""
    with db.service() as conn:
        rows = conn.execute(
            "SELECT scope, classification, alert_level, peak_alert_level, status, score, score_breakdown, started_at,"
            " last_event_at, resolved_at, affected_household_count, active_household_count, time_spread_s,"
            " approximate_extent_m, isp_diversity, failure_modes, delayed_report_count, correlation_window_s"
            " FROM nw.incidents WHERE neighborhood_id = %s AND status <> 'merged' ORDER BY started_at",
            [admin.neighborhood_id],
        ).fetchall()
        audit(conn, action="incidents_exported", neighborhood_id=admin.neighborhood_id, actor_user_id=admin.user_id,
              metadata={"rows": len(rows)})
    return [dict(r) for r in rows]


def get_configuration(db: Database, admin: Member) -> AdminConfigurationOut:
    with db.service() as conn:
        cfg = load_config(conn, admin.neighborhood_id)
    return AdminConfigurationOut(values=cfg.as_dict(), defaults=DEFAULTS.as_dict())


def update_configuration(db: Database, admin: Member, values: dict[str, Any]) -> AdminConfigurationOut:
    try:
        clean = validate_overrides(values)
    except ValueError as exc:
        raise ApiError(422, "validation_error", str(exc)) from exc
    with db.service() as conn:
        for key, value in clean.items():
            conn.execute(
                "INSERT INTO nw.system_configuration (neighborhood_id, key, value, updated_by) VALUES (%s, %s, %s, %s)"
                " ON CONFLICT (COALESCE(neighborhood_id, '00000000-0000-0000-0000-000000000000'::uuid), key)"
                " DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by",
                [admin.neighborhood_id, key, Jsonb(value), admin.user_id],
            )
        audit(conn, action="configuration_changed", neighborhood_id=admin.neighborhood_id, actor_user_id=admin.user_id,
              metadata={"values": clean})
    return get_configuration(db, admin)


def delivery_failures(db: Database, admin: Member, limit: int) -> list[AdminDeliveryFailureOut]:
    with db.service() as conn:
        rows = conn.execute(
            "SELECT id, incident_id, provider, status, attempts, last_error, created_at FROM nw.notification_deliveries"
            " WHERE neighborhood_id = %s AND status IN ('failed', 'skipped_not_configured', 'expired')"
            " ORDER BY created_at DESC LIMIT %s",
            [admin.neighborhood_id, limit],
        ).fetchall()
    return [AdminDeliveryFailureOut(delivery_id=r["id"], **{k: r[k] for k in r if k != "id"}) for r in rows]


def audit_logs(db: Database, admin: Member, limit: int) -> list[AdminAuditLogOut]:
    with db.service() as conn:
        rows = conn.execute(
            "SELECT a.id, a.action, a.target_type, a.target_id, a.actor_type, a.created_at, a.metadata,"
            " h.anonymous_reference FROM nw.admin_audit_logs a"
            " LEFT JOIN nw.household_members hm ON hm.user_id = a.actor_user_id AND hm.status = 'active'"
            " LEFT JOIN nw.households h ON h.id = hm.household_id"
            " WHERE a.neighborhood_id = %s ORDER BY a.created_at DESC LIMIT %s",
            [admin.neighborhood_id, limit],
        ).fetchall()
    return [
        AdminAuditLogOut(
            id=r["id"], action=r["action"], target_type=r["target_type"], target_id=r["target_id"],
            actor_reference=r["anonymous_reference"] or r["actor_type"], created_at=r["created_at"], metadata=r["metadata"],
        )
        for r in rows
    ]
