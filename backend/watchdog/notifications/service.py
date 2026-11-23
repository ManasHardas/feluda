"""Notification planning and delivery.

plan(): looks at incident state and creates the delivery rows that are still missing. It is
state-driven and idempotent: each (incident, kind, level, device token) has one unique key, so
re-running it never produces duplicates.

dispatch(): sends pending deliveries, with retry and backoff, expiry, and delivery tracking.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from psycopg.types.json import Jsonb

from .. import metrics
from ..config import load_config
from ..db import Conn, Database
from ..enums import Classification, DeliveryStatus, NotificationKind
from .content import compose
from .providers import PushMessage, PushSender

log = logging.getLogger(__name__)

RESOLUTION_LOOKBACK_MIN = 30  # how long after an incident closes a "restored" message is still worth sending


def _in_quiet_hours(pref, now: datetime) -> bool:
    if not pref["quiet_hours_enabled"]:
        return False
    try:
        local = now.astimezone(ZoneInfo(pref["timezone"])).time()
    except (ZoneInfoNotFoundError, ValueError):
        local = now.time()
    start, end = pref["quiet_hours_start"], pref["quiet_hours_end"]
    if start == end:
        return False
    return start <= local < end if start < end else (local >= start or local < end)


def _recipients(conn: Conn, neighborhood_id: UUID, household_ids: list[UUID] | None):
    return conn.execute(
        "SELECT t.id AS token_id, t.user_id, t.provider, hm.household_id,"
        " COALESCE(p.household_alerts, true) AS household_alerts, COALESCE(p.neighborhood_alerts, true) AS neighborhood_alerts,"
        " COALESCE(p.quiet_hours_enabled, false) AS quiet_hours_enabled, COALESCE(p.quiet_hours_start, '22:00') AS quiet_hours_start,"
        " COALESCE(p.quiet_hours_end, '07:00') AS quiet_hours_end, COALESCE(p.timezone, 'UTC') AS timezone"
        " FROM nw.notification_tokens t"
        " JOIN nw.devices d ON d.id = t.device_id AND d.status = 'active'"
        " JOIN nw.household_members hm ON hm.user_id = t.user_id AND hm.status = 'active'"
        " JOIN nw.households h ON h.id = hm.household_id AND h.membership_status = 'active' AND h.neighborhood_id = %s"
        " LEFT JOIN nw.notification_preferences p ON p.user_id = t.user_id"
        " WHERE t.status = 'active' AND (%s::uuid[] IS NULL OR hm.household_id = ANY(%s))",
        [neighborhood_id, household_ids, household_ids],
    ).fetchall()


def _create(conn: Conn, *, incident, recipient, kind: str, level: int, now: datetime, expires_at: datetime) -> bool:
    title, body = compose(kind, incident["classification"], level, incident["affected_household_count"], incident["scope"])
    household_scope = level <= 1
    if household_scope and not recipient["household_alerts"] or not household_scope and not recipient["neighborhood_alerts"]:
        status = DeliveryStatus.SKIPPED_PREFERENCE
    elif level <= 1 and _in_quiet_hours(recipient, now):
        status = DeliveryStatus.SKIPPED_QUIET_HOURS  # quiet hours apply to non-critical messages only
    elif expires_at <= now:
        status = DeliveryStatus.EXPIRED  # the event is already too old to be a useful alert
    else:
        status = DeliveryStatus.PENDING
    row = conn.execute(
        "INSERT INTO nw.notification_deliveries (neighborhood_id, incident_id, token_id, user_id, provider, kind,"
        " alert_level, dedupe_key, title, body, data, status, next_attempt_at, expires_at)"
        " VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) ON CONFLICT (dedupe_key) DO NOTHING RETURNING id",
        [
            incident["neighborhood_id"],
            incident["id"],
            recipient["token_id"],
            recipient["user_id"],
            recipient["provider"],
            kind,
            level,
            f"{incident['id']}:{kind}:{level}:{recipient['token_id']}",
            title,
            body,
            Jsonb(
                {
                    "incident_id": str(incident["id"]),
                    "alert_level": str(level),
                    "kind": kind,
                    "classification": incident["classification"],
                }
            ),
            status.value,
            now if status == DeliveryStatus.PENDING else None,
            expires_at,
        ],
    ).fetchone()
    return row is not None


def plan(db: Database, neighborhood_id: UUID, now: datetime) -> int:
    """Create missing deliveries for this neighborhood's incidents. Returns how many were created."""
    created = 0
    with db.service() as conn:
        cfg = load_config(conn, neighborhood_id)
        ttl = timedelta(minutes=cfg.notification_ttl_min)
        open_incidents = conn.execute(
            "SELECT * FROM nw.incidents WHERE neighborhood_id = %s AND status = 'open'", [neighborhood_id]
        ).fetchall()
        for incident in open_incidents:
            level = incident["peak_alert_level"]
            affected = [
                r["household_id"]
                for r in conn.execute("SELECT household_id FROM nw.incident_households WHERE incident_id = %s", [incident["id"]])
            ]
            if level >= 2:
                recipients = _recipients(conn, neighborhood_id, None)  # every participating household
            elif incident["scope"] == "neighborhood":
                recipients = _recipients(conn, neighborhood_id, affected)  # likely outage: affected households only
            else:
                if not _household_alert_due(conn, incident, affected, now, cfg):
                    continue
                recipients = _recipients(conn, neighborhood_id, affected)
            for recipient in recipients:
                previous = conn.execute(
                    "SELECT max(alert_level) AS level FROM nw.notification_deliveries WHERE incident_id = %s"
                    " AND token_id = %s AND kind IN ('alert', 'escalation')",
                    [incident["id"], recipient["token_id"]],
                ).fetchone()["level"]
                if previous is not None and previous >= level:
                    continue  # this device was already told about this incident at this level
                kind = NotificationKind.ESCALATION if previous is not None else NotificationKind.ALERT
                # Incidents built from very late reports are closed by the incident expiry rule before they get
                # here, so anything still open is recent enough to be worth an alert.
                created += _create(
                    conn, incident=incident, recipient=recipient, kind=kind.value, level=level, now=now, expires_at=now + ttl
                )

        resolved = conn.execute(
            "SELECT * FROM nw.incidents WHERE neighborhood_id = %s AND status = 'resolved' AND resolved_at >= %s",
            [neighborhood_id, now - timedelta(minutes=RESOLUTION_LOOKBACK_MIN)],
        ).fetchall()
        for incident in resolved:
            # Tell exactly the devices that were alerted about this incident.
            alerted = conn.execute(
                "SELECT DISTINCT d.token_id, d.user_id, d.provider FROM nw.notification_deliveries d"
                " JOIN nw.notification_tokens t ON t.id = d.token_id AND t.status = 'active'"
                " WHERE d.incident_id = %s AND d.kind IN ('alert', 'escalation') AND d.status = 'sent'",
                [incident["id"]],
            ).fetchall()
            for row in alerted:
                recipient = {**row, "household_alerts": True, "neighborhood_alerts": True, "quiet_hours_enabled": False}
                created += _create(
                    conn,
                    incident=incident,
                    recipient=recipient,
                    kind=NotificationKind.RESOLUTION.value,
                    level=min(incident["peak_alert_level"], 2) if incident["peak_alert_level"] >= 2 else 1,
                    now=now,
                    expires_at=now + ttl,
                )
    if created:
        metrics.incr("notifications_planned", created)
    return created


def _household_alert_due(conn: Conn, incident, affected: list[UUID], now: datetime, cfg) -> bool:
    """A single-household interruption is pushed only when it is worth interrupting someone for."""
    if not incident["at_home_evidence"] and incident["classification"] == Classification.HOUSEHOLD_CONNECTIVITY_ISSUE:
        return False  # most often a phone leaving the house; shown in the app, not pushed
    if (now - incident["started_at"]).total_seconds() < cfg.household_alert_delay_s:
        return False  # give a short blip time to recover
    if not affected:
        return False
    recent = conn.execute(
        "SELECT 1 FROM nw.notification_deliveries d JOIN nw.incidents i ON i.id = d.incident_id"
        " JOIN nw.incident_households ih ON ih.incident_id = i.id"
        " WHERE ih.household_id = ANY(%s) AND i.scope = 'household' AND i.id <> %s AND d.kind = 'alert'"
        " AND d.created_at >= %s LIMIT 1",
        [affected, incident["id"], now - timedelta(minutes=cfg.household_alert_cooldown_min)],
    ).fetchone()
    return recent is None


def dispatch(db: Database, senders: dict[str, PushSender], now: datetime, batch: int = 200) -> dict[str, int]:
    """Send due deliveries. Returns counts by resulting status."""
    counts: dict[str, int] = {}
    with db.service() as conn:
        due = conn.execute(
            "SELECT d.*, t.token, t.apns_environment, t.status AS token_status FROM nw.notification_deliveries d"
            " LEFT JOIN nw.notification_tokens t ON t.id = d.token_id"
            " WHERE d.status = 'pending' AND d.next_attempt_at <= %s ORDER BY d.alert_level DESC, d.created_at"
            " LIMIT %s FOR UPDATE OF d SKIP LOCKED",
            [now, batch],
        ).fetchall()
        configs: dict[UUID, object] = {}
        for delivery in due:
            cfg = configs.setdefault(delivery["neighborhood_id"], load_config(conn, delivery["neighborhood_id"]))
            sender = senders.get(delivery["provider"])
            status, error, message_id, next_attempt = DeliveryStatus.PENDING, None, None, None
            attempts = delivery["attempts"]
            if delivery["expires_at"] <= now:
                status = DeliveryStatus.EXPIRED
            elif delivery["token"] is None or delivery["token_status"] != "active":  # noqa: S105 - a status value, not a credential
                status, error = DeliveryStatus.FAILED, "push token no longer registered"
            elif sender is None or not sender.configured:
                status, error = DeliveryStatus.SKIPPED_NOT_CONFIGURED, f"{delivery['provider']} credentials are not configured"
            else:
                attempts += 1
                message = PushMessage(
                    title=delivery["title"],
                    body=delivery["body"],
                    data=delivery["data"],
                    alert_level=delivery["alert_level"],
                    expires_at_epoch=int(delivery["expires_at"].timestamp()),
                    collapse_key=str(delivery["incident_id"] or delivery["id"]),
                )
                result = sender.send(delivery["token"], message, delivery["apns_environment"])
                if result.ok:
                    status, message_id = DeliveryStatus.SENT, result.message_id
                else:
                    error = result.error
                    if result.invalid_token:
                        conn.execute("UPDATE nw.notification_tokens SET status = 'invalid' WHERE id = %s", [delivery["token_id"]])
                        status = DeliveryStatus.FAILED
                    elif result.retryable and attempts < cfg.notification_max_attempts:
                        next_attempt = now + timedelta(seconds=cfg.notification_retry_base_s * 2 ** (attempts - 1))
                        if next_attempt >= delivery["expires_at"]:
                            status, next_attempt = DeliveryStatus.EXPIRED, None
                    else:
                        status = DeliveryStatus.FAILED
            conn.execute(
                "UPDATE nw.notification_deliveries SET status = %s, attempts = %s, last_error = %s,"
                " provider_message_id = COALESCE(%s, provider_message_id), next_attempt_at = %s WHERE id = %s",
                [status.value, attempts, error, message_id, next_attempt, delivery["id"]],
            )
            counts[status.value] = counts.get(status.value, 0) + 1
    for status, count in counts.items():
        metrics.incr(f"notifications_{status}", count)
        if status in ("failed", "skipped_not_configured"):
            log.warning("notification deliveries not sent", extra={"status": status, "count": count})
    return counts
