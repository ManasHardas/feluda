"""Data retention: delete what is older than the configured periods."""

from __future__ import annotations

import logging
from datetime import datetime, timedelta

from .. import metrics
from ..config import load_config
from ..db import Database

log = logging.getLogger(__name__)


def purge(db: Database, now: datetime) -> dict[str, int]:
    deleted: dict[str, int] = {}
    with db.service() as conn:
        cfg = load_config(conn, None)  # retention is a platform-level setting (global overrides only)
        statements = {
            "connectivity_events": ("DELETE FROM nw.connectivity_events WHERE received_at < %s", cfg.retention_raw_events_days),
            "device_sessions": ("DELETE FROM nw.device_sessions WHERE COALESCE(ended_at, last_heartbeat_at) < %s",
                                cfg.retention_raw_events_days),
            "incidents": ("DELETE FROM nw.incidents WHERE status <> 'open' AND started_at < %s", cfg.retention_incidents_days),
            "incident_candidates": ("DELETE FROM nw.incident_candidates WHERE window_start < %s", cfg.retention_incidents_days),
            "notification_deliveries": ("DELETE FROM nw.notification_deliveries WHERE created_at < %s",
                                        cfg.retention_notification_deliveries_days),
            "admin_audit_logs": ("DELETE FROM nw.admin_audit_logs WHERE created_at < %s", cfg.retention_audit_days),
            "security_events": ("DELETE FROM nw.security_events WHERE created_at < %s", cfg.retention_audit_days),
            "invitations": ("DELETE FROM nw.invitations WHERE status <> 'active' AND updated_at < %s", cfg.retention_audit_days),
            "rate_limit_counters": ("DELETE FROM nw.rate_limit_counters WHERE window_start < %s", 2),
        }
        for table, (statement, days) in statements.items():
            deleted[table] = conn.execute(statement, [now - timedelta(days=days)]).rowcount
        # Expired invitations can no longer be used; mark them so listings are truthful.
        conn.execute("UPDATE nw.invitations SET status = 'revoked' WHERE status = 'active' AND expires_at < %s", [now])
    metrics.incr("retention_runs")
    log.info("retention purge", extra={"deleted": deleted})
    return deleted
