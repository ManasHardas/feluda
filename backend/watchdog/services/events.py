"""Connectivity event ingestion: validation, idempotency, abuse limits, device and session state."""

from __future__ import annotations

import logging
from datetime import datetime, timedelta

from psycopg.types.json import Jsonb

from .. import metrics, ratelimit
from ..authentication.dependencies import Member
from ..config import DetectionConfig, load_config
from ..db import Conn, Database
from ..enums import ConnectivityState, EventResult, EventType
from ..errors import ApiError
from ..schemas import ConnectivityEventIn, EventOutcome, SubmitEventsRequest, SubmitEventsResponse
from .audit import security_event

log = logging.getLogger(__name__)

_STOP_EVENTS = {EventType.MONITORING_STOPPED, EventType.MONITORING_DISABLED_BY_USER}
_START_EVENTS = {EventType.MONITORING_STARTED, EventType.MONITORING_RESUMED}
_NOT_MONITORING_STATES = {ConnectivityState.MONITORING_PAUSED, ConnectivityState.MONITORING_PERMISSION_REQUIRED}


def _is_onset(event: ConnectivityEventIn) -> bool:
    return event.event_type == EventType.WIFI_LOST or (
        event.event_type == EventType.INTERNET_REACHABILITY_CHANGED
        and event.connectivity_state == ConnectivityState.WIFI_CONNECTED_NO_INTERNET
    )


def ingest(db: Database, member: Member, body: SubmitEventsRequest, now: datetime) -> tuple[SubmitEventsResponse, int]:
    """Store a batch. Returns the response and the number of newly accepted events."""
    # ---- 1. device ownership and already-known event ids (resident role, row-level security applies)
    with db.api(member.user_id) as conn:
        device = conn.execute("SELECT * FROM nw.devices WHERE id = %s", [body.device_id]).fetchone()
        if device is None:
            raise ApiError(404, "device_not_registered", "This device is not registered. Register it first.")
        if device["status"] != "active":
            raise ApiError(403, "device_revoked", "This device registration was revoked.")
        if device["household_id"] != member.household_id:
            raise ApiError(403, "device_revoked", "This device belongs to a previous membership. Register it again.")
        known = {
            r["id"]
            for r in conn.execute("SELECT id FROM nw.connectivity_events WHERE id = ANY(%s)", [[e.event_id for e in body.events]])
        }

    outcomes: dict = {}
    candidates: list[ConnectivityEventIn] = []
    seen: set = set()
    # ---- 2. validation and limits (trusted role: reads configuration, updates counters)
    with db.service() as conn:
        cfg: DetectionConfig = load_config(conn, member.neighborhood_id)
        oldest = now - timedelta(hours=cfg.max_event_age_hours)
        newest = now + timedelta(seconds=cfg.max_future_skew_s)
        for event in sorted(body.events, key=lambda e: (e.occurred_at, e.sequence)):
            if event.event_id in known or event.event_id in seen:
                outcomes[event.event_id] = EventOutcome(event_id=event.event_id, result=EventResult.DUPLICATE)
                continue
            seen.add(event.event_id)
            if event.occurred_at > newest:
                outcomes[event.event_id] = EventOutcome(
                    event_id=event.event_id, result=EventResult.REJECTED, reason="timestamp_in_future"
                )
            elif event.occurred_at < oldest:
                outcomes[event.event_id] = EventOutcome(event_id=event.event_id, result=EventResult.REJECTED, reason="too_old")
            else:
                candidates.append(event)

        retry_after = 0
        granted = len(candidates)
        for bucket, limit, window in (
            (f"events:device:{body.device_id}:m", cfg.max_events_per_device_per_minute, 60),
            (f"events:device:{body.device_id}:h", cfg.max_events_per_device_per_hour, 3600),
            (f"events:household:{member.household_id}:h", cfg.max_events_per_household_per_hour, 3600),
        ):
            if granted == 0:
                break
            allowed, wait = ratelimit.consume(conn, bucket, limit, window, now, amount=granted)
            if allowed < granted:
                granted, retry_after = allowed, max(retry_after, wait)
        accepted, limited = candidates[:granted], candidates[granted:]
        for event in limited:
            outcomes[event.event_id] = EventOutcome(event_id=event.event_id, result=EventResult.REJECTED, reason="rate_limited")
        if limited:
            metrics.incr("events_rate_limited", len(limited))
            security_event(
                conn,
                kind="event_rate_limited",
                neighborhood_id=member.neighborhood_id,
                user_id=member.user_id,
                device_id=body.device_id,
                household_id=member.household_id,
                detail={"rejected": len(limited), "accepted": len(accepted)},
            )

        # Failure onsets beyond the hourly allowance are stored but kept out of correlation.
        excluded: set = set()
        onsets = [e for e in accepted if _is_onset(e)]
        if onsets:
            allowed, _ = ratelimit.consume(
                conn, f"onsets:device:{body.device_id}:h", cfg.max_onsets_per_device_per_hour, 3600, now, amount=len(onsets)
            )
            excluded = {e.event_id for e in onsets[allowed:]}
            if excluded:
                metrics.incr("onsets_excluded", len(excluded))
                security_event(
                    conn,
                    kind="suspicious_reporting",
                    neighborhood_id=member.neighborhood_id,
                    user_id=member.user_id,
                    device_id=body.device_id,
                    household_id=member.household_id,
                    detail={"excluded_onsets": len(excluded), "allowed_per_hour": cfg.max_onsets_per_device_per_hour},
                )

    # ---- 3. store (resident role again: the insert policy re-checks device, household and neighborhood)
    stored = 0
    if accepted:
        with db.api(member.user_id) as conn:
            for event in accepted:
                row = conn.execute(
                    "INSERT INTO nw.connectivity_events (id, device_id, household_id, neighborhood_id, sequence, event_type,"
                    " connectivity_state, occurred_at, received_at, reported_via, platform, os_version, app_version,"
                    " confidence, diagnostics, capabilities, excluded_reason)"
                    " VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)"
                    " ON CONFLICT (id) DO NOTHING RETURNING id",
                    [
                        event.event_id,
                        body.device_id,
                        member.household_id,
                        member.neighborhood_id,
                        event.sequence,
                        event.event_type.value,
                        event.connectivity_state.value,
                        event.occurred_at,
                        now,
                        body.reported_via.value,
                        device["platform"],
                        event.os_version,
                        event.app_version,
                        event.confidence.value,
                        Jsonb(event.diagnostics.model_dump(mode="json", exclude_none=True)),
                        Jsonb(device["capabilities"]),
                        "onset_rate_limit" if event.event_id in excluded else None,
                    ],
                ).fetchone()
                if row is None:
                    outcomes[event.event_id] = EventOutcome(event_id=event.event_id, result=EventResult.DUPLICATE)
                else:
                    stored += 1
                    outcomes[event.event_id] = EventOutcome(event_id=event.event_id, result=EventResult.ACCEPTED)
            _update_device_state(conn, device, [e for e in accepted if outcomes[e.event_id].result == EventResult.ACCEPTED], now)
    metrics.incr("events_accepted", stored)
    metrics.incr("events_duplicate", sum(1 for o in outcomes.values() if o.result == EventResult.DUPLICATE))
    response = SubmitEventsResponse(
        server_time=now,
        outcomes=[outcomes[e.event_id] for e in body.events],
        retry_after_seconds=retry_after or None,
    )
    return response, stored


def _update_device_state(conn: Conn, device, events: list[ConnectivityEventIn], now: datetime) -> None:
    if not events:
        conn.execute("UPDATE nw.devices SET last_seen_at = %s WHERE id = %s", [now, device["id"]])
        return
    events = sorted(events, key=lambda e: (e.occurred_at, e.sequence))
    newest = events[-1]
    is_newer = device["last_event_at"] is None or newest.occurred_at >= device["last_event_at"]
    monitoring = device["monitoring_enabled"]
    state = device["last_state"]
    if is_newer:
        state = newest.connectivity_state.value
        if newest.event_type in _STOP_EVENTS or newest.connectivity_state in _NOT_MONITORING_STATES:
            monitoring = False
        else:
            monitoring = True
    conn.execute(
        "UPDATE nw.devices SET last_seen_at = %s, last_state = %s, monitoring_enabled = %s,"
        " last_event_at = GREATEST(COALESCE(last_event_at, %s), %s), last_sequence = GREATEST(COALESCE(last_sequence, 0), %s),"
        " os_version = %s, app_version = %s WHERE id = %s",
        [
            now,
            state,
            monitoring,
            newest.occurred_at,
            newest.occurred_at,
            max(e.sequence for e in events),
            newest.os_version if is_newer else device["os_version"],
            newest.app_version if is_newer else device["app_version"],
            device["id"],
        ],
    )

    # Monitoring sessions: an uninterrupted run of monitoring on this device.
    for event in events:
        at = event.occurred_at
        if event.event_type == EventType.MONITORING_GAP_DETECTED:
            gap_start = event.diagnostics.gap_started_at or at
            conn.execute(
                "UPDATE nw.device_sessions SET ended_at = GREATEST(%s, started_at), end_reason = %s"
                " WHERE device_id = %s AND ended_at IS NULL",
                [gap_start, (event.diagnostics.gap_reason.value if event.diagnostics.gap_reason else "unknown"), device["id"]],
            )
        elif event.event_type in _STOP_EVENTS:
            conn.execute(
                "UPDATE nw.device_sessions SET ended_at = GREATEST(%s, started_at), end_reason = %s"
                " WHERE device_id = %s AND ended_at IS NULL",
                [at, event.event_type.value.lower(), device["id"]],
            )
        elif event.connectivity_state in _NOT_MONITORING_STATES:
            continue
        else:
            if event.event_type in _START_EVENTS:
                conn.execute(
                    "UPDATE nw.device_sessions SET ended_at = GREATEST(last_heartbeat_at, started_at), end_reason = 'restarted'"
                    " WHERE device_id = %s AND ended_at IS NULL AND started_at < %s",
                    [device["id"], at],
                )
            updated = conn.execute(
                "UPDATE nw.device_sessions SET last_heartbeat_at = GREATEST(last_heartbeat_at, %s)"
                " WHERE device_id = %s AND ended_at IS NULL RETURNING id",
                [at, device["id"]],
            ).fetchone()
            if updated is None:
                conn.execute(
                    "INSERT INTO nw.device_sessions (device_id, started_at, last_heartbeat_at) VALUES (%s, %s, %s)"
                    " ON CONFLICT DO NOTHING",
                    [device["id"], at, at],
                )
