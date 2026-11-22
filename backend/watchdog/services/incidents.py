"""Read access to incidents for residents. Row-level security decides which incidents are visible."""

from __future__ import annotations

from uuid import UUID

from ..authentication.dependencies import Member
from ..correlation.engine import RECOMMENDED_ACTIONS
from ..db import Database
from ..enums import AlertLevel, Classification, IncidentStatus, StatusColor
from ..errors import not_found
from ..schemas import IncidentDetailOut, IncidentOut
from .status import COLOR_BY_LEVEL

SUMMARIES = {
    Classification.HOUSEHOLD_CONNECTIVITY_ISSUE: "Connectivity interruption at your household",
    Classification.POSSIBLE_ISP_OUTAGE: "Possible internet provider outage",
    Classification.POSSIBLE_ROUTER_OR_POWER_FAILURE: "Possible router or power failure",
    Classification.POSSIBLE_WIRELESS_INTERFERENCE: "Wireless connectivity anomaly at your household (suspected RF interference)",
    Classification.CORRELATED_NEIGHBORHOOD_DISRUPTION: "Correlated wireless disruption in the neighborhood",
    Classification.HIGH_PRIORITY_SUSPICIOUS_DISRUPTION: "Elevated neighborhood alert: suspicious correlated wireless disruption",
    Classification.NORMAL: "No significant connectivity anomaly",
}


def approximate_scope(row) -> str:
    if row["scope"] == "household":
        return "Your household only"
    extent = row["approximate_extent_m"]
    if extent is None:
        return "Several households in the neighborhood (distance between them is not known)"
    return f"Households within about {extent} metres of each other"


def _color(row) -> StatusColor:
    if row["status"] != IncidentStatus.OPEN:
        return StatusColor.GREY
    return COLOR_BY_LEVEL[AlertLevel(row["alert_level"])]


def _base(row, mine: bool) -> dict:
    classification = Classification(row["classification"])
    summary = SUMMARIES[classification]
    if row["status"] == IncidentStatus.EXPIRED:
        summary += " (closed without recovery reports; outcome unknown)"
    elif row["status"] == IncidentStatus.RESOLVED:
        summary += " (connectivity reported restored)"
    return {
        "incident_id": row["id"], "scope": row["scope"], "classification": classification,
        "alert_level": row["alert_level"], "status": row["status"], "status_color": _color(row),
        "started_at": row["started_at"], "last_event_at": row["last_event_at"], "resolved_at": row["resolved_at"],
        "affected_household_count": row["affected_household_count"],
        "active_household_count": row["active_household_count"], "approximate_scope": approximate_scope(row),
        "correlation_window_s": row["correlation_window_s"], "time_spread_s": row["time_spread_s"],
        "isp_diversity": row["isp_diversity"], "my_household_affected": mine, "summary": summary,
    }


def list_incidents(db: Database, member: Member, status: str, limit: int) -> list[IncidentOut]:
    with db.api(member.user_id) as conn:
        rows = conn.execute(
            "SELECT i.*, EXISTS (SELECT 1 FROM nw.incident_households ih WHERE ih.incident_id = i.id) AS mine"
            " FROM nw.incidents i WHERE i.status <> 'merged' AND (%s = 'all' OR i.status = 'open')"
            " ORDER BY (i.status = 'open') DESC, i.started_at DESC LIMIT %s",
            [status, limit],
        ).fetchall()
    return [IncidentOut(**_base(r, r["mine"])) for r in rows]


def get_incident(db: Database, member: Member, incident_id: UUID) -> IncidentDetailOut:
    with db.api(member.user_id) as conn:
        row = conn.execute(
            "SELECT i.*, EXISTS (SELECT 1 FROM nw.incident_households ih WHERE ih.incident_id = i.id) AS mine"
            " FROM nw.incidents i WHERE i.id = %s",
            [incident_id],
        ).fetchone()
    if row is None:
        raise not_found("Incident not found.")  # same answer for "does not exist" and "not yours to see"
    return IncidentDetailOut(
        **_base(row, row["mine"]), score=row["score"], score_breakdown=row["score_breakdown"],
        explanation=row["explanation"], supporting_evidence=row["supporting_evidence"],
        missing_evidence=row["missing_evidence"],
        recommended_actions=RECOMMENDED_ACTIONS.get(Classification(row["classification"]), []),
        delayed_report_count=row["delayed_report_count"], failure_modes=row["failure_modes"],
    )
