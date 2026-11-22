"""Detection, abuse-control and retention parameters.

Defaults live here. Any value can be overridden globally or per neighborhood through the
nw.system_configuration table (administrators use PUT /api/v1/admin/configuration).

These are initial experimental parameters for a pilot, not validated detection thresholds.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, fields, replace
from typing import Any
from uuid import UUID


@dataclass(frozen=True)
class DetectionConfig:
    # --- correlation geometry and timing
    correlation_radius_m: int = 500
    correlation_window_s: int = 60  # largest gap between consecutive household onsets in one cluster
    max_cluster_span_s: int = 300  # a cluster never grows beyond this from its first onset
    contradiction_window_s: int = 90  # another device of the household healthy on home Wi-Fi this soon cancels an onset

    # --- promotion thresholds
    min_affected_households: int = 3
    min_affected_fraction: float = 0.10  # of households with confirmed monitoring activity
    correlated_score_threshold: float = 40.0
    high_priority_score_threshold: float = 75.0
    high_priority_min_households: int = 4
    power_evidence_fraction: float = 0.5  # share of power-reporting devices that lost external power
    power_evidence_min_devices: int = 2
    isp_outage_same_provider_fraction: float = 0.8

    # --- score weights (sum of positive weights = 100)
    weight_households: float = 20.0
    weight_participation: float = 10.0
    weight_time_tightness: float = 10.0
    weight_isp_diversity: float = 15.0
    weight_cellular_available: float = 15.0
    weight_wifi_loss_signature: float = 10.0
    weight_strong_signal_loss: float = 5.0
    weight_persistence: float = 15.0
    penalty_power_evidence: float = 30.0
    penalty_recurring_time_of_day: float = 10.0
    households_saturation: int = 6  # affected households that earn the full household weight
    participation_saturation: float = 0.5  # affected fraction that earns the full participation weight
    strong_signal_dbm: int = -70  # last RSSI at or above this counts as "lost at good signal"
    persistence_seconds: int = 120  # an outage must last this long to count as persistent
    recurring_incident_count: int = 2  # earlier incidents at a similar time of day that trigger the penalty
    recurring_lookback_days: int = 14
    recurring_time_of_day_tolerance_min: int = 30

    # --- household classification
    household_repeat_count: int = 3  # onsets within the repeat window that suggest more than a blip
    household_repeat_window_min: int = 30
    household_alert_delay_s: int = 180  # a single-household interruption must last this long before notifying
    household_alert_cooldown_min: int = 60

    # --- monitoring coverage
    active_window_min: int = 45  # telemetry newer than this = confirmed monitoring activity
    min_active_for_full_confidence: int = 5

    # --- incident lifecycle
    resolve_fraction: float = 0.6  # share of affected households that must report recovery
    incident_expiry_min: int = 60  # close an unresolved incident after this long without new evidence

    # --- abuse controls
    max_events_per_device_per_minute: int = 60
    max_events_per_device_per_hour: int = 600
    max_events_per_household_per_hour: int = 1500
    max_onsets_per_device_per_hour: int = 6  # failure onsets beyond this are stored but not correlated
    max_event_age_hours: int = 168  # older events are rejected
    max_future_skew_s: int = 120  # events time-stamped further in the future are rejected
    delayed_report_threshold_s: int = 120  # reports slower than this are flagged as delayed

    # --- notifications
    notification_ttl_min: int = 20
    notification_max_attempts: int = 5
    notification_retry_base_s: int = 30

    # --- retention (days)
    retention_raw_events_days: int = 30
    retention_heartbeat_days: int = 3  # routine "still monitoring" heartbeats are bulky and only useful briefly
    retention_incidents_days: int = 90
    retention_audit_days: int = 90
    retention_notification_deliveries_days: int = 30

    def with_overrides(self, overrides: dict[str, Any]) -> DetectionConfig:
        valid = {f.name: f.type for f in fields(self)}
        clean: dict[str, Any] = {}
        for key, value in overrides.items():
            if key not in valid:
                continue
            current = getattr(self, key)
            if isinstance(current, bool) or isinstance(value, bool):
                continue
            if isinstance(current, int):
                clean[key] = int(value)
            elif isinstance(current, float):
                clean[key] = float(value)
        return replace(self, **clean)

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


DEFAULTS = DetectionConfig()

# Bounds enforced on administrator changes, so a typo cannot disable the safeguards.
BOUNDS: dict[str, tuple[float, float]] = {
    "correlation_radius_m": (50, 5000),
    "correlation_window_s": (10, 600),
    "max_cluster_span_s": (30, 1800),
    "contradiction_window_s": (0, 600),
    "min_affected_households": (2, 50),  # never below 2: one household can never raise a neighborhood alert
    "min_affected_fraction": (0.0, 1.0),
    "correlated_score_threshold": (10, 100),
    "high_priority_score_threshold": (20, 100),
    "high_priority_min_households": (2, 50),
    "power_evidence_fraction": (0.1, 1.0),
    "power_evidence_min_devices": (1, 20),
    "isp_outage_same_provider_fraction": (0.5, 1.0),
    "households_saturation": (2, 100),
    "participation_saturation": (0.05, 1.0),
    "strong_signal_dbm": (-90, -30),
    "persistence_seconds": (30, 3600),
    "recurring_incident_count": (1, 20),
    "recurring_lookback_days": (1, 90),
    "recurring_time_of_day_tolerance_min": (5, 180),
    "household_repeat_count": (2, 20),
    "household_repeat_window_min": (5, 240),
    "household_alert_delay_s": (0, 3600),
    "household_alert_cooldown_min": (0, 1440),
    "active_window_min": (5, 1440),
    "min_active_for_full_confidence": (1, 100),
    "resolve_fraction": (0.1, 1.0),
    "incident_expiry_min": (5, 1440),
    "max_events_per_device_per_minute": (5, 600),
    "max_events_per_device_per_hour": (20, 6000),
    "max_events_per_household_per_hour": (20, 20000),
    "max_onsets_per_device_per_hour": (1, 60),
    "max_event_age_hours": (1, 720),
    "max_future_skew_s": (0, 600),
    "delayed_report_threshold_s": (10, 3600),
    "notification_ttl_min": (1, 240),
    "notification_max_attempts": (1, 10),
    "notification_retry_base_s": (5, 600),
    "retention_raw_events_days": (1, 365),
    "retention_heartbeat_days": (1, 365),
    "retention_incidents_days": (7, 730),
    "retention_audit_days": (30, 730),
    "retention_notification_deliveries_days": (1, 365),
}
for _f in fields(DetectionConfig):
    if _f.name.startswith(("weight_", "penalty_")):
        BOUNDS[_f.name] = (0.0, 100.0)


def validate_overrides(values: dict[str, Any]) -> dict[str, float | int]:
    """Return cleaned overrides or raise ValueError naming the first bad key."""
    clean: dict[str, float | int] = {}
    for key, value in values.items():
        if key not in BOUNDS:
            raise ValueError(f"Unknown configuration key: {key}")
        if isinstance(value, bool) or not isinstance(value, int | float):
            raise ValueError(f"{key} must be a number")
        low, high = BOUNDS[key]
        if not low <= value <= high:
            raise ValueError(f"{key} must be between {low:g} and {high:g}")
        clean[key] = int(value) if isinstance(getattr(DEFAULTS, key), int) else float(value)
    merged = DEFAULTS.with_overrides(clean)
    if merged.high_priority_score_threshold < merged.correlated_score_threshold:
        raise ValueError("high_priority_score_threshold must not be below correlated_score_threshold")
    return clean


def load_config(conn, neighborhood_id: UUID | None) -> DetectionConfig:
    """Defaults, then global overrides, then this neighborhood's overrides."""
    rows = conn.execute(
        "SELECT neighborhood_id, key, value FROM nw.system_configuration WHERE neighborhood_id IS NULL OR neighborhood_id = %s",
        [neighborhood_id],
    ).fetchall()
    global_values = {r["key"]: r["value"] for r in rows if r["neighborhood_id"] is None}
    local_values = {r["key"]: r["value"] for r in rows if r["neighborhood_id"] is not None}
    return DEFAULTS.with_overrides(global_values).with_overrides(local_values)
