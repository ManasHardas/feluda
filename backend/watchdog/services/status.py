"""Monitoring status for a household and a neighborhood.

The central rule: absence of reports is never presented as safety. Only recent telemetry from a
device that says it is monitoring earns GREEN. Everything else is GREY (unknown) or an alert colour.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from ..authentication.dependencies import Member
from ..config import DetectionConfig, load_config
from ..db import Database
from ..enums import AlertLevel, ConnectivityState, MonitoringStatus, StatusColor
from ..schemas import ConnectivityStatusOut, DeviceMonitoringOut, NeighborhoodOut

_FAILURE_STATES = {
    ConnectivityState.WIFI_DISCONNECTED, ConnectivityState.WIFI_CONNECTED_NO_INTERNET,
    ConnectivityState.INTERNET_UNAVAILABLE,
}

COLOR_BY_LEVEL = {
    AlertLevel.NORMAL: StatusColor.GREEN,
    AlertLevel.HOUSEHOLD_WARNING: StatusColor.YELLOW,
    AlertLevel.NEIGHBORHOOD_ANOMALY: StatusColor.ORANGE,
    AlertLevel.HIGH_PRIORITY: StatusColor.RED,
}

EXPLANATIONS = {
    StatusColor.GREEN: "Green: no current connectivity anomaly has been reported.",
    StatusColor.YELLOW: "Yellow: a connectivity interruption was reported for a household.",
    StatusColor.ORANGE: "Orange: multiple nearby households reported connectivity disruptions at about the same time.",
    StatusColor.RED: "Red: a high-priority suspicious neighborhood disruption is in progress. Deliberate interference "
                     "has not been confirmed.",
    StatusColor.GREY: "Grey: monitoring is not active or has not reported recently. This does not mean everything is fine.",
}


def device_monitoring_status(device, now: datetime, cfg: DetectionConfig) -> MonitoringStatus:
    if device["status"] != "active":
        return MonitoringStatus.PAUSED
    if device["last_seen_at"] is None:
        return MonitoringStatus.NEVER_REPORTED
    if not device["monitoring_enabled"]:
        return MonitoringStatus.PAUSED
    recent = now - device["last_seen_at"] <= timedelta(minutes=cfg.active_window_min)
    if device["last_state"] in _FAILURE_STATES and recent:
        return MonitoringStatus.FAILURE_REPORTED
    if recent:
        return MonitoringStatus.CONFIRMED_ACTIVE
    return MonitoringStatus.KNOWN_GAP if device.get("open_session") is False else MonitoringStatus.NO_RECENT_TELEMETRY


_PRIORITY = [
    MonitoringStatus.CONFIRMED_ACTIVE, MonitoringStatus.FAILURE_REPORTED, MonitoringStatus.KNOWN_GAP,
    MonitoringStatus.PAUSED, MonitoringStatus.NO_RECENT_TELEMETRY, MonitoringStatus.NEVER_REPORTED,
]


def connectivity_status(db: Database, member: Member, now: datetime) -> ConnectivityStatusOut:
    with db.service() as conn:
        cfg = load_config(conn, member.neighborhood_id)
        # All devices of the household count toward the household status; only the caller's own are listed.
        household_devices = conn.execute(
            "SELECT d.id, d.user_id, d.platform, d.status, d.monitoring_enabled, d.last_state, d.last_event_at,"
            " d.last_seen_at, EXISTS (SELECT 1 FROM nw.device_sessions s WHERE s.device_id = d.id AND s.ended_at IS NULL)"
            " AS open_session FROM nw.devices d WHERE d.household_id = %s AND d.status = 'active'",
            [member.household_id],
        ).fetchall()
    with db.api(member.user_id) as conn:
        incidents = conn.execute(
            "SELECT id, alert_level FROM nw.incidents WHERE status = 'open' ORDER BY alert_level DESC, started_at DESC"
        ).fetchall()

    statuses = [device_monitoring_status(d, now, cfg) for d in household_devices]
    household_status = next((s for s in _PRIORITY if s in statuses), MonitoringStatus.NEVER_REPORTED)
    level = AlertLevel(max((i["alert_level"] for i in incidents), default=0))
    if level > AlertLevel.NORMAL:
        color = COLOR_BY_LEVEL[level]
    elif household_status == MonitoringStatus.CONFIRMED_ACTIVE:
        color = StatusColor.GREEN
    elif household_status == MonitoringStatus.FAILURE_REPORTED:
        color = StatusColor.YELLOW
    else:
        color = StatusColor.GREY
    devices = [
        DeviceMonitoringOut(
            device_id=d["id"], platform=d["platform"], monitoring_status=s,
            connectivity_state=d["last_state"] if s not in (MonitoringStatus.PAUSED,) else ConnectivityState.MONITORING_PAUSED,
            last_event_at=d["last_event_at"], last_received_at=d["last_seen_at"],
        )
        for d, s in zip(household_devices, statuses, strict=True)
        if d["user_id"] == member.user_id
    ]
    return ConnectivityStatusOut(
        household_monitoring_status=household_status, status_color=color, status_explanation=EXPLANATIONS[color],
        highest_alert_level=level, open_incident_ids=[i["id"] for i in incidents], devices=devices, server_time=now,
    )


def neighborhood_overview(db: Database, member: Member, now: datetime) -> NeighborhoodOut:
    with db.service() as conn:
        cfg = load_config(conn, member.neighborhood_id)
        row = conn.execute(
            "SELECT n.id, n.name,"
            " (SELECT count(*) FROM nw.households h WHERE h.neighborhood_id = n.id AND h.membership_status = 'active') AS participating,"
            " (SELECT count(DISTINCT d.household_id) FROM nw.devices d JOIN nw.households h ON h.id = d.household_id"
            "   WHERE h.neighborhood_id = n.id AND h.membership_status = 'active' AND d.status = 'active'"
            "   AND d.monitoring_enabled AND d.last_seen_at >= %s) AS active"
            " FROM nw.neighborhoods n WHERE n.id = %s",
            [now - timedelta(minutes=cfg.active_window_min), member.neighborhood_id],
        ).fetchone()
    with db.api(member.user_id) as conn:
        incidents = conn.execute(
            "SELECT alert_level FROM nw.incidents WHERE status = 'open' AND scope = 'neighborhood'"
        ).fetchall()
    level = AlertLevel(max((i["alert_level"] for i in incidents), default=0))
    if level > AlertLevel.NORMAL:
        color = COLOR_BY_LEVEL[level]
    elif row["active"] > 0:
        color = StatusColor.GREEN
    else:
        color = StatusColor.GREY
    explanation = EXPLANATIONS[color]
    if color == StatusColor.GREEN:
        explanation += (
            f" {row['active']} of {row['participating']} participating households reported recently; "
            "the others are unknown, not confirmed safe."
        )
    return NeighborhoodOut(
        neighborhood_id=row["id"], name=row["name"], participating_households=row["participating"],
        confirmed_active_households=row["active"], open_incident_count=len(incidents), highest_alert_level=level,
        status_color=color, status_explanation=explanation, correlation_radius_m=cfg.correlation_radius_m,
        correlation_window_s=cfg.correlation_window_s,
    )
