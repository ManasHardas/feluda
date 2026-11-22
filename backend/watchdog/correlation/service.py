"""Correlation service: loads events, runs the pure engine, and stores incidents.

Runs under the restricted nw_correlation database role, which cannot read addresses, e-mail
addresses, labels or push tokens. Each run recomputes a bounded time range from stored events, so
late and out-of-order uploads are handled by construction and repeated runs are idempotent.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timedelta
from uuid import UUID

from psycopg.types.json import Jsonb

from .. import metrics
from ..config import DetectionConfig, load_config
from ..db import Conn, Database
from ..enums import IncidentScope, IncidentStatus
from .engine import Assessment, ClusterContext, EventRecord, HouseholdInfo, Onset, evaluate, extract_onsets

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class IncidentChange:
    incident_id: UUID
    change: str  # created | escalated | updated | resolved | expired | merged
    scope: str
    alert_level: int


def neighborhoods_needing_work(db: Database) -> list[UUID]:
    with db.correlation() as conn:
        rows = conn.execute(
            "SELECT DISTINCT neighborhood_id FROM nw.connectivity_events WHERE correlated_at IS NULL"
            " UNION SELECT DISTINCT neighborhood_id FROM nw.incidents WHERE status = 'open'"
        ).fetchall()
    return [r["neighborhood_id"] for r in rows]


def correlate_neighborhood(db: Database, neighborhood_id: UUID, now: datetime) -> list[IncidentChange]:
    with db.correlation() as conn:
        # One correlation run per neighborhood at a time, across all processes.
        conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 7272))", [f"correlate:{neighborhood_id}"])
        cfg = load_config(conn, neighborhood_id)
        changes = _run(conn, neighborhood_id, now, cfg)
    for change in changes:
        metrics.incr(f"incidents_{change.change}")
        log.info(
            "incident change",
            extra={
                "incident_id": str(change.incident_id),
                "change": change.change,
                "scope": change.scope,
                "alert_level": change.alert_level,
            },
        )
    metrics.incr("correlation_runs")
    return changes


def _run(conn: Conn, neighborhood_id: UUID, now: datetime, cfg: DetectionConfig) -> list[IncidentChange]:
    neighborhood = conn.execute("SELECT status FROM nw.neighborhoods WHERE id = %s", [neighborhood_id]).fetchone()
    pending = conn.execute(
        "SELECT min(occurred_at) AS first, count(*) AS n FROM nw.connectivity_events"
        " WHERE neighborhood_id = %s AND correlated_at IS NULL AND received_at <= %s",
        [neighborhood_id, now],
    ).fetchone()
    open_incidents = conn.execute(
        "SELECT * FROM nw.incidents WHERE neighborhood_id = %s AND status = 'open'", [neighborhood_id]
    ).fetchall()

    def mark_processed() -> None:
        conn.execute(
            "UPDATE nw.connectivity_events SET correlated_at = %s"
            " WHERE neighborhood_id = %s AND correlated_at IS NULL AND received_at <= %s",
            [now, neighborhood_id, now],
        )

    if neighborhood is None or neighborhood["status"] != "active":
        mark_processed()  # a disabled neighborhood produces no incidents
        return []
    if not pending["n"] and not open_incidents:
        return []

    margin = timedelta(seconds=cfg.max_cluster_span_s + cfg.contradiction_window_s, minutes=cfg.household_repeat_window_min)
    anchors = [i["started_at"] for i in open_incidents]
    if pending["first"] is not None:
        anchors.append(pending["first"])
    since = max(min(anchors) - margin, now - timedelta(hours=cfg.max_event_age_hours))

    households = {
        r["id"]: HouseholdInfo(id=r["id"], isp=r["isp"], latitude=r["latitude"], longitude=r["longitude"])
        for r in conn.execute(
            "SELECT id, isp, latitude, longitude FROM nw.households WHERE neighborhood_id = %s AND membership_status = 'active'",
            [neighborhood_id],
        )
    }
    events = [
        EventRecord(
            id=r["id"],
            device_id=r["device_id"],
            household_id=r["household_id"],
            event_type=r["event_type"],
            connectivity_state=r["connectivity_state"],
            occurred_at=r["occurred_at"],
            received_at=r["received_at"],
            confidence=r["confidence"],
            platform=r["platform"],
            diagnostics=r["diagnostics"],
            excluded_reason=r["excluded_reason"],
            sequence=r["sequence"],
        )
        for r in conn.execute(
            "SELECT id, device_id, household_id, event_type, connectivity_state, occurred_at, received_at,"
            " confidence, platform, diagnostics, excluded_reason, sequence FROM nw.connectivity_events"
            " WHERE neighborhood_id = %s AND occurred_at >= %s AND received_at <= %s ORDER BY occurred_at",
            [neighborhood_id, since, now],
        )
    ]
    onsets = extract_onsets(events, households, cfg)

    active_ids = {
        r["household_id"]
        for r in conn.execute(
            "SELECT DISTINCT d.household_id FROM nw.devices d JOIN nw.households h ON h.id = d.household_id"
            " WHERE h.neighborhood_id = %s AND h.membership_status = 'active' AND d.status = 'active'"
            " AND d.last_seen_at >= %s",
            [neighborhood_id, now - timedelta(minutes=cfg.active_window_min)],
        )
    }
    active_ids |= {o.household_id for o in onsets}
    past_starts = [
        r["started_at"]
        for r in conn.execute(
            "SELECT started_at FROM nw.incidents WHERE neighborhood_id = %s AND scope = 'neighborhood'"
            " AND peak_alert_level >= 2 AND started_at >= %s",
            [neighborhood_id, now - timedelta(days=cfg.recurring_lookback_days)],
        )
    ]
    ctx = ClusterContext(
        now=now,
        active_household_count=len(active_ids),
        past_incident_starts=past_starts,
        prior_onsets_by_event=_prior_onsets(onsets, cfg),
    )
    assessments, cluster_results = evaluate(onsets, ctx, cfg)

    for result in cluster_results:
        conn.execute(
            "INSERT INTO nw.incident_candidates (neighborhood_id, window_start, window_end, household_count, score,"
            " status, detail) VALUES (%s, %s, %s, %s, %s, %s, %s)"
            " ON CONFLICT (neighborhood_id, window_start) DO UPDATE SET window_end = EXCLUDED.window_end,"
            " household_count = EXCLUDED.household_count, score = EXCLUDED.score, status = EXCLUDED.status,"
            " detail = EXCLUDED.detail",
            [
                neighborhood_id,
                result.members[0].occurred_at,
                result.members[-1].occurred_at,
                len(result.members),
                result.score,
                "promoted" if result.promoted else "below_threshold",
                Jsonb({"reason": result.reason}),
            ],
        )

    changes: list[IncidentChange] = []
    touched: set[UUID] = set()
    # Neighborhood assessments first, so household incidents they absorb are merged before matching.
    for assessment in sorted(assessments, key=lambda a: (a.scope != IncidentScope.NEIGHBORHOOD, a.started_at)):
        change = _store(conn, neighborhood_id, assessment, cfg, now, touched)
        if change:
            changes.append(change)

    changes.extend(_lifecycle(conn, neighborhood_id, now, cfg, touched, since))
    mark_processed()
    return changes


def _prior_onsets(onsets: list[Onset], cfg: DetectionConfig) -> dict[UUID, int]:
    window = timedelta(minutes=cfg.household_repeat_window_min)
    by_household: dict[UUID, list[Onset]] = {}
    result: dict[UUID, int] = {}
    for onset in onsets:  # already time-ordered
        earlier = by_household.setdefault(onset.household_id, [])
        result[onset.event_id] = sum(1 for e in earlier if onset.occurred_at - e.occurred_at <= window)
        earlier.append(onset)
    return result


def _store(
    conn: Conn, neighborhood_id: UUID, a: Assessment, cfg: DetectionConfig, now: datetime, touched: set[UUID]
) -> IncidentChange | None:
    member_ids = [m.household_id for m in a.members]
    span = timedelta(seconds=cfg.max_cluster_span_s)
    existing = conn.execute(
        "SELECT i.* FROM nw.incidents i WHERE i.neighborhood_id = %s AND i.scope = %s AND i.status <> 'merged'"
        " AND i.started_at BETWEEN %s AND %s"
        " AND EXISTS (SELECT 1 FROM nw.incident_households ih WHERE ih.incident_id = i.id AND ih.household_id = ANY(%s))"
        " ORDER BY (i.status = 'open') DESC, i.started_at LIMIT 1",
        [neighborhood_id, a.scope.value, a.started_at - span, a.started_at + span, member_ids],
    ).fetchone()

    if a.scope == IncidentScope.HOUSEHOLD:
        # Already part of a neighborhood incident covering this moment: nothing separate to record.
        covered = conn.execute(
            "SELECT 1 FROM nw.incidents i JOIN nw.incident_households ih ON ih.incident_id = i.id"
            " WHERE i.neighborhood_id = %s AND i.scope = 'neighborhood' AND i.status = 'open'"
            " AND ih.household_id = %s AND i.started_at BETWEEN %s AND %s LIMIT 1",
            [neighborhood_id, member_ids[0], a.started_at - span, a.started_at + span],
        ).fetchone()
        if covered:
            return None

    fields = {
        "classification": a.classification.value,
        "alert_level": int(a.alert_level),
        "score": a.score,
        "score_breakdown": Jsonb(a.score_breakdown),
        "explanation": a.explanation,
        "supporting_evidence": Jsonb(a.supporting_evidence),
        "missing_evidence": Jsonb(a.missing_evidence),
        "last_event_at": a.last_event_at,
        "affected_household_count": a.affected_household_count,
        "active_household_count": a.active_household_count,
        "time_spread_s": a.time_spread_s,
        "approximate_extent_m": a.approximate_extent_m,
        "isp_diversity": Jsonb(a.isp_diversity),
        "failure_modes": Jsonb(a.failure_modes),
        "delayed_report_count": a.delayed_report_count,
        "correlation_window_s": cfg.correlation_window_s,
        "at_home_evidence": a.at_home_evidence,
    }
    change: str
    if existing is None:
        columns = ["neighborhood_id", "scope", "started_at", "peak_alert_level", *fields]
        values = [neighborhood_id, a.scope.value, a.started_at, int(a.alert_level), *fields.values()]
        row = conn.execute(
            f"INSERT INTO nw.incidents ({', '.join(columns)}) VALUES ({', '.join(['%s'] * len(columns))}) RETURNING id",  # noqa: S608
            values,
        ).fetchone()
        incident_id, change = row["id"], "created"
    else:
        incident_id = existing["id"]
        touched.add(incident_id)
        if existing["status"] != IncidentStatus.OPEN:
            return None  # closed incidents are history; late evidence does not reopen them
        peak = max(existing["peak_alert_level"], int(a.alert_level))
        change = "escalated" if int(a.alert_level) > existing["peak_alert_level"] else "updated"
        assignments = ", ".join(f"{name} = %s" for name in fields)
        conn.execute(
            f"UPDATE nw.incidents SET {assignments}, peak_alert_level = %s, started_at = LEAST(started_at, %s)"  # noqa: S608
            " WHERE id = %s",
            [*fields.values(), peak, a.started_at, incident_id],
        )
    touched.add(incident_id)

    for member in a.members:
        conn.execute(
            "INSERT INTO nw.incident_households (incident_id, household_id, failure_mode, first_event_at, restored_at)"
            " VALUES (%s, %s, %s, %s, %s) ON CONFLICT (incident_id, household_id) DO UPDATE SET"
            " failure_mode = EXCLUDED.failure_mode, first_event_at = LEAST(nw.incident_households.first_event_at,"
            " EXCLUDED.first_event_at), restored_at = EXCLUDED.restored_at",
            [incident_id, member.household_id, member.mode.value, member.occurred_at, member.restored_at],
        )

    if a.scope == IncidentScope.NEIGHBORHOOD:
        conn.execute(
            "UPDATE nw.incidents SET status = 'merged', resolved_at = %s WHERE neighborhood_id = %s"
            " AND scope = 'household' AND status = 'open' AND started_at BETWEEN %s AND %s"
            " AND id IN (SELECT incident_id FROM nw.incident_households WHERE household_id = ANY(%s))",
            [now, neighborhood_id, a.started_at - span, a.last_event_at + span, member_ids],
        )
    return IncidentChange(incident_id, change, a.scope.value, int(a.alert_level))


def _lifecycle(
    conn: Conn, neighborhood_id: UUID, now: datetime, cfg: DetectionConfig, touched: set[UUID], since: datetime
) -> list[IncidentChange]:
    changes: list[IncidentChange] = []
    rows = conn.execute(
        "SELECT i.id, i.scope, i.alert_level, i.started_at, i.last_event_at,"
        " count(ih.id) AS members, count(ih.restored_at) AS restored, max(ih.restored_at) AS last_restored"
        " FROM nw.incidents i LEFT JOIN nw.incident_households ih ON ih.incident_id = i.id"
        " WHERE i.neighborhood_id = %s AND i.status = 'open' GROUP BY i.id",
        [neighborhood_id],
    ).fetchall()
    for row in rows:
        members = row["members"] or 1
        status: str | None = None
        resolved_at = None
        if row["scope"] == "household" and row["id"] not in touched and row["started_at"] >= since:
            # The evidence no longer supports it (for example another phone of the household is
            # healthy on the home network). Close it.
            status, resolved_at = "resolved", now
        elif row["restored"] and row["restored"] / members >= (cfg.resolve_fraction if row["scope"] == "neighborhood" else 1.0):
            status, resolved_at = "resolved", row["last_restored"]
        elif now - max(row["last_event_at"], row["started_at"]) >= timedelta(minutes=cfg.incident_expiry_min):
            status, resolved_at = "expired", now
        if status:
            conn.execute("UPDATE nw.incidents SET status = %s, resolved_at = %s WHERE id = %s", [status, resolved_at, row["id"]])
            changes.append(IncidentChange(row["id"], status, row["scope"], row["alert_level"]))
    return changes
