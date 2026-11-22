"""Detection and correlation engine: pure functions, no database, no clock of its own.

Pipeline:
    raw events  ->  household episodes ("onsets")  ->  clusters in time and space  ->  assessments

Everything here is deterministic and rules-based so that each result can be explained and tested.
The output is an operational classification. It is never proof of deliberate interference.
"""

from __future__ import annotations

import math
from collections import Counter, defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any
from uuid import UUID

from ..config import DetectionConfig
from ..enums import (
    AlertLevel,
    Classification,
    ConnectivityState,
    EventType,
    FailureMode,
    IncidentScope,
    ObservationConfidence,
)

NOT_CONFIRMED = "Deliberate interference has not been confirmed."
ISP_NAMES = {
    "att": "AT&T", "xfinity": "Xfinity", "spectrum": "Spectrum", "verizon": "Verizon",
    "tmobile_home": "T-Mobile Home Internet", "google_fiber": "Google Fiber", "other": "another provider",
}
WEAK_SIGNAL_MARGIN_DB = 10


# --------------------------------------------------------------------------- inputs


@dataclass(frozen=True)
class EventRecord:
    id: UUID
    device_id: UUID
    household_id: UUID
    event_type: str
    connectivity_state: str
    occurred_at: datetime
    received_at: datetime
    confidence: str
    platform: str
    diagnostics: dict[str, Any]
    excluded_reason: str | None = None


@dataclass(frozen=True)
class HouseholdInfo:
    id: UUID
    isp: str = "unknown"
    latitude: float | None = None
    longitude: float | None = None


@dataclass(frozen=True)
class Onset:
    """The start of one connectivity-failure episode at one household."""

    household_id: UUID
    device_id: UUID
    event_id: UUID
    occurred_at: datetime
    received_at: datetime
    mode: FailureMode
    platform: str
    isp: str
    latitude: float | None
    longitude: float | None
    cellular_available: bool | None
    gateway_reachable: bool | None
    last_rssi_dbm: int | None
    power_lost: bool | None
    restored_at: datetime | None = None

    @property
    def report_delay_s(self) -> float:
        return (self.received_at - self.occurred_at).total_seconds()


@dataclass(frozen=True)
class ClusterContext:
    now: datetime
    active_household_count: int  # households with confirmed monitoring activity, including the affected ones
    past_incident_starts: Sequence[datetime] = ()  # earlier neighborhood incidents (for the recurrence check)
    prior_onsets_by_event: dict[UUID, int] = field(default_factory=dict)  # earlier recent onsets of the same household, keyed by onset event id


@dataclass
class Assessment:
    scope: IncidentScope
    classification: Classification
    alert_level: AlertLevel
    members: list[Onset]
    started_at: datetime
    last_event_at: datetime
    affected_household_count: int
    active_household_count: int
    time_spread_s: float
    explanation: str
    supporting_evidence: list[str]
    missing_evidence: list[str]
    score: float | None = None
    score_breakdown: list[dict[str, Any]] = field(default_factory=list)
    approximate_extent_m: int | None = None
    isp_diversity: dict[str, int] = field(default_factory=dict)
    failure_modes: dict[str, int] = field(default_factory=dict)
    delayed_report_count: int = 0
    at_home_evidence: bool = False  # household scope: evidence the phone was still at home when it failed


@dataclass
class ClusterResult:
    """A multi-household cluster and what became of it."""

    members: list[Onset]
    promoted: Assessment | None  # neighborhood-scope assessment when thresholds were met
    score: float | None
    reason: str


# --------------------------------------------------------------------------- geometry


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(a)))


def _distance(a: Onset, b: Onset) -> float | None:
    if None in (a.latitude, a.longitude, b.latitude, b.longitude):
        return None
    return haversine_m(a.latitude, a.longitude, b.latitude, b.longitude)  # type: ignore[arg-type]


def _extent_m(members: Sequence[Onset]) -> int | None:
    located = [m for m in members if m.latitude is not None and m.longitude is not None]
    if len(located) < 2:
        return None
    widest = max(_distance(a, b) or 0.0 for i, a in enumerate(located) for b in located[i + 1:])
    return int(math.ceil(widest / 50.0) * 50)  # rounded up to 50 m so it cannot pinpoint a house


# --------------------------------------------------------------------------- step 1: episodes


def failure_mode_of(event: EventRecord) -> FailureMode | None:
    """The failure mode this event starts, or None when it is not a usable failure onset."""
    if event.excluded_reason is not None:
        return None
    if event.confidence != ObservationConfidence.OBSERVED:
        return None  # reconciled after a gap: the real time of the change is unknown
    d = event.diagnostics
    # Explained by the phone or its user rather than by the home network:
    if d.get("user_initiated") is True or d.get("wifi_radio_enabled") is False or d.get("airplane_mode") is True:
        return None
    if d.get("home_network_match") is False:
        return None  # the phone was on some other Wi-Fi network
    if event.event_type == EventType.WIFI_LOST:
        if event.connectivity_state == ConnectivityState.INTERNET_UNAVAILABLE or d.get("cellular_available") is False:
            return FailureMode.ALL_CONNECTIVITY_LOST
        return FailureMode.WIFI_LOST
    if (
        event.event_type == EventType.INTERNET_REACHABILITY_CHANGED
        and event.connectivity_state == ConnectivityState.WIFI_CONNECTED_NO_INTERNET
    ):
        return FailureMode.NO_INTERNET
    return None


def _is_healthy_at_home(event: EventRecord) -> bool:
    return (
        event.connectivity_state == ConnectivityState.HEALTHY
        and event.diagnostics.get("home_network_match") is not False
        and event.excluded_reason is None
    )


def extract_onsets(
    events: Iterable[EventRecord], households: dict[UUID, HouseholdInfo], cfg: DetectionConfig
) -> list[Onset]:
    """Collapse raw events into one onset per household failure episode, ordered by occurrence time.

    An episode starts at the first usable failure event and ends when any device of the household
    reports HEALTHY on the home network. An onset is dropped when a *different* device of the same
    household, which did not itself report a failure, is healthy on the home network shortly
    afterwards: that points at one phone (for example leaving the house), not at the home network.
    """
    per_household: dict[UUID, list[EventRecord]] = defaultdict(list)
    for event in events:
        if event.household_id in households:
            per_household[event.household_id].append(event)

    onsets: list[Onset] = []
    for household_id, items in per_household.items():
        items.sort(key=lambda e: (e.occurred_at, e.received_at, str(e.id)))
        info = households[household_id]
        current: Onset | None = None
        failed_devices: set[UUID] = set()
        contradicted = False
        for event in items:
            mode = failure_mode_of(event)
            if mode is not None:
                if current is None:
                    d = event.diagnostics
                    current = Onset(
                        household_id=household_id, device_id=event.device_id, event_id=event.id,
                        occurred_at=event.occurred_at, received_at=event.received_at, mode=mode,
                        platform=event.platform, isp=info.isp, latitude=info.latitude, longitude=info.longitude,
                        cellular_available=d.get("cellular_available"),
                        gateway_reachable=d.get("local_gateway_reachable"),
                        last_rssi_dbm=d.get("last_wifi_rssi_dbm"),
                        power_lost=d.get("external_power_lost_recently"),
                    )
                    failed_devices = {event.device_id}
                    contradicted = False
                else:
                    failed_devices.add(event.device_id)
                    current = _merge_evidence(current, event)
                continue
            if current is not None and _is_healthy_at_home(event):
                since = (event.occurred_at - current.occurred_at).total_seconds()
                if event.device_id not in failed_devices and since <= cfg.contradiction_window_s:
                    contradicted = True
                if not contradicted:
                    onsets.append(_with(current, restored_at=event.occurred_at))
                current = None
        if current is not None and not contradicted:
            onsets.append(current)
    onsets.sort(key=lambda o: (o.occurred_at, str(o.household_id)))
    return onsets


def _with(onset: Onset, **changes: Any) -> Onset:
    data = {name: getattr(onset, name) for name in onset.__dataclass_fields__}
    data.update(changes)
    return Onset(**data)


def _merge_evidence(onset: Onset, event: EventRecord) -> Onset:
    """Fill evidence the first event did not have from later failure events of the same episode."""
    d = event.diagnostics

    def pick(current: Any, key: str) -> Any:
        return current if current is not None else d.get(key)

    return _with(
        onset,
        cellular_available=pick(onset.cellular_available, "cellular_available"),
        gateway_reachable=pick(onset.gateway_reachable, "local_gateway_reachable"),
        last_rssi_dbm=pick(onset.last_rssi_dbm, "last_wifi_rssi_dbm"),
        power_lost=onset.power_lost if onset.power_lost else d.get("external_power_lost_recently", onset.power_lost),
    )


# --------------------------------------------------------------------------- step 2: clusters


def cluster_onsets(onsets: Sequence[Onset], cfg: DetectionConfig) -> list[list[Onset]]:
    """Group onsets that are close in time and space. Each onset lands in exactly one cluster.

    A cluster grows while the next onset is within `correlation_window_s` of the previous member,
    within `max_cluster_span_s` of the first member, and within `correlation_radius_m` of the first
    member's household (households without a known location are treated as inside the radius; the
    assessment then lists location as missing evidence). One household appears at most once.
    """
    remaining = sorted(onsets, key=lambda o: (o.occurred_at, str(o.household_id)))
    clusters: list[list[Onset]] = []
    while remaining:
        seed = remaining.pop(0)
        members = [seed]
        seen = {seed.household_id}
        last_time = seed.occurred_at
        leftover: list[Onset] = []
        for index, onset in enumerate(remaining):
            if (onset.occurred_at - seed.occurred_at).total_seconds() > cfg.max_cluster_span_s or (
                onset.occurred_at - last_time
            ).total_seconds() > cfg.correlation_window_s:
                leftover.extend(remaining[index:])
                break
            distance = _distance(seed, onset)
            if onset.household_id in seen or (distance is not None and distance > cfg.correlation_radius_m):
                leftover.append(onset)
                continue
            members.append(onset)
            seen.add(onset.household_id)
            last_time = onset.occurred_at
        remaining = leftover
        clusters.append(members)
    return clusters


# --------------------------------------------------------------------------- step 3: assessments


def _plural(count: int, noun: str) -> str:
    return f"{count} {noun}" if count == 1 else f"{count} {noun}s"


def _number_word(count: int) -> str:
    words = ["Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve"]
    return words[count] if 0 <= count < len(words) else str(count)


def _fraction(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def _isp_counts(members: Sequence[Onset]) -> dict[str, int]:
    return dict(Counter(m.isp for m in members if m.isp and m.isp != "unknown"))


def _persistent(member: Onset, now: datetime, cfg: DetectionConfig) -> bool:
    end = member.restored_at or now
    return (end - member.occurred_at).total_seconds() >= cfg.persistence_seconds


def assess_household(onset: Onset, ctx: ClusterContext, cfg: DetectionConfig) -> Assessment | None:
    """Classify a failure that only one household reported. Returns None for 'nothing to report'."""
    supporting: list[str] = []
    missing: list[str] = []
    prior = ctx.prior_onsets_by_event.get(onset.event_id, 0)
    repeated = prior + 1 >= cfg.household_repeat_count
    strong = onset.last_rssi_dbm is not None and onset.last_rssi_dbm >= cfg.strong_signal_dbm
    weak = onset.last_rssi_dbm is not None and onset.last_rssi_dbm < cfg.strong_signal_dbm - WEAK_SIGNAL_MARGIN_DB
    at_home = False

    if onset.mode == FailureMode.NO_INTERNET:
        at_home = True
        supporting.append("The phone stayed connected to the home Wi-Fi network but could not reach the internet.")
        if onset.gateway_reachable is False:
            classification = Classification.POSSIBLE_ROUTER_OR_POWER_FAILURE
            supporting.append("The home router did not answer on the local network.")
            lead = "Your phone is connected to Wi-Fi but the home router is not responding."
        else:
            classification = Classification.POSSIBLE_ISP_OUTAGE
            if onset.gateway_reachable is True:
                supporting.append("The home router answered on the local network.")
            else:
                missing.append("Whether the home router answers locally could not be checked on this phone.")
            lead = "Your home Wi-Fi is up but the internet connection behind it appears to be down."
        if onset.cellular_available is True:
            supporting.append("Cellular data was available at the same time.")
    else:
        if onset.cellular_available is True:
            supporting.append("Wi-Fi was lost while cellular data stayed available.")
        elif onset.cellular_available is False:
            supporting.append("Wi-Fi and cellular data were both unavailable; this report arrived later.")
        else:
            missing.append("Cellular availability at the time is unknown.")
        if weak and not repeated and onset.power_lost is not True:
            # A fading signal on a single phone is what leaving the house looks like.
            return None
        if onset.power_lost is True:
            at_home = True
            classification = Classification.POSSIBLE_ROUTER_OR_POWER_FAILURE
            supporting.append("The phone stopped charging at the same moment, which suggests a power cut.")
            lead = "Home Wi-Fi was lost at the same moment the phone lost charging power."
        elif onset.cellular_available is True and (strong or repeated):
            at_home = True
            classification = Classification.POSSIBLE_WIRELESS_INTERFERENCE
            if strong:
                supporting.append(
                    f"The Wi-Fi signal was strong ({onset.last_rssi_dbm} dBm) just before it disappeared."
                )
            if repeated:
                supporting.append(
                    f"This is interruption number {prior + 1} within {cfg.household_repeat_window_min} minutes."
                )
            lead = "Home Wi-Fi dropped in a way that is not explained by the phone moving away or by a power cut."
        else:
            classification = Classification.HOUSEHOLD_CONNECTIVITY_ISSUE
            lead = "One phone in this household lost its home Wi-Fi connection."
            if onset.last_rssi_dbm is None:
                missing.append(
                    "Signal strength before the loss is not available, so a phone simply leaving the house "
                    "cannot be ruled out."
                )
        if onset.power_lost is None:
            missing.append("Whether mains power was lost is unknown (the phone was not charging).")
    missing.append("No other household reported a similar interruption at this time.")

    explanation = (
        f"{lead} Only this household reported an interruption, so this is treated as a household "
        f"connectivity issue. {NOT_CONFIRMED}"
    )
    return Assessment(
        scope=IncidentScope.HOUSEHOLD,
        classification=classification,
        alert_level=AlertLevel.HOUSEHOLD_WARNING,
        members=[onset],
        started_at=onset.occurred_at,
        last_event_at=onset.restored_at or onset.occurred_at,
        affected_household_count=1,
        active_household_count=ctx.active_household_count,
        time_spread_s=0.0,
        explanation=explanation,
        supporting_evidence=supporting,
        missing_evidence=missing,
        isp_diversity=_isp_counts([onset]),
        failure_modes={onset.mode.value: 1},
        delayed_report_count=1 if onset.report_delay_s > cfg.delayed_report_threshold_s else 0,
        at_home_evidence=at_home,
    )


def interference_score(
    members: Sequence[Onset], ctx: ClusterContext, cfg: DetectionConfig
) -> tuple[float, list[dict[str, Any]], list[str], list[str]]:
    """Transparent additive score (0-100) for 'coordinated wireless disruption' with its breakdown."""
    n = len(members)
    active = max(ctx.active_household_count, n)
    breakdown: list[dict[str, Any]] = []
    supporting: list[str] = []
    missing: list[str] = []

    def add(indicator: str, points: float, max_points: float, detail: str) -> None:
        breakdown.append(
            {"indicator": indicator, "points": round(points, 1), "max_points": round(max_points, 1), "detail": detail}
        )

    # 1. number of independent households
    add(
        "affected_households",
        min(n / cfg.households_saturation, 1.0) * cfg.weight_households,
        cfg.weight_households,
        f"{_plural(n, 'household')} reported an interruption (full weight at {cfg.households_saturation}).",
    )

    # 2. share of actively monitoring households
    share = _fraction(n, active)
    add(
        "participation",
        min(share / cfg.participation_saturation, 1.0) * cfg.weight_participation,
        cfg.weight_participation,
        f"{n} of {active} actively monitoring households ({share:.0%}) were affected.",
    )
    if active < cfg.min_active_for_full_confidence:
        missing.append(
            f"Only {_plural(active, 'household')} had confirmed monitoring activity; coverage is too thin "
            "to say how widespread this is."
        )

    # 3. time tightness
    spread = (members[-1].occurred_at - members[0].occurred_at).total_seconds() if n > 1 else 0.0
    tightness = max(0.0, 1.0 - spread / max(cfg.correlation_window_s, 1))
    add(
        "time_tightness",
        tightness * cfg.weight_time_tightness,
        cfg.weight_time_tightness,
        f"All interruptions began within {spread:.0f} seconds of each other.",
    )

    # 4. provider diversity
    isps = _isp_counts(members)
    if len(isps) >= 2:
        names = " and ".join(sorted(ISP_NAMES.get(k, k) for k in isps))
        add("isp_diversity", cfg.weight_isp_diversity, cfg.weight_isp_diversity,
            f"Affected households use different internet providers ({names}).")
        supporting.append("The affected households use different internet providers.")
    elif len(isps) == 1:
        name = ISP_NAMES.get(next(iter(isps)), "one provider")
        add("isp_diversity", 0.0, cfg.weight_isp_diversity,
            f"All affected households with a known provider use {name}; a provider fault cannot be ruled out.")
        missing.append("All affected households with a known provider use the same provider.")
    else:
        add("isp_diversity", 0.0, cfg.weight_isp_diversity, "No affected household has a known internet provider.")
        missing.append("Internet providers of the affected households are unknown.")

    # 5. cellular stayed up
    cell_known = [m for m in members if m.cellular_available is not None]
    cell_up = sum(1 for m in cell_known if m.cellular_available)
    if cell_known:
        add("cellular_available", _fraction(cell_up, len(cell_known)) * cfg.weight_cellular_available,
            cfg.weight_cellular_available,
            f"Cellular connectivity stayed available on {cell_up} of {len(cell_known)} reporting devices.")
        if cell_up:
            supporting.append(
                f"Cellular connectivity remained available for {cell_up} of {len(cell_known)} reporting devices."
            )
    else:
        add("cellular_available", 0.0, cfg.weight_cellular_available, "No device reported cellular availability.")
        missing.append("Cellular availability during the interruption is unknown.")

    # 6. Wi-Fi association lost (rather than Wi-Fi up without internet)
    wifi_lost = sum(1 for m in members if m.mode == FailureMode.WIFI_LOST)
    add("wifi_loss_signature", _fraction(wifi_lost, n) * cfg.weight_wifi_loss_signature,
        cfg.weight_wifi_loss_signature,
        f"{wifi_lost} of {n} households lost the Wi-Fi connection itself, not only the internet behind it.")

    # 7. lost at good signal (Android only)
    rssi_known = [m for m in members if m.last_rssi_dbm is not None]
    strong = sum(1 for m in rssi_known if m.last_rssi_dbm >= cfg.strong_signal_dbm)  # type: ignore[operator]
    if rssi_known:
        add("strong_signal_before_loss", _fraction(strong, len(rssi_known)) * cfg.weight_strong_signal_loss,
            cfg.weight_strong_signal_loss,
            f"{strong} of {len(rssi_known)} devices that report signal strength had a strong signal just before the loss.")
    else:
        add("strong_signal_before_loss", 0.0, cfg.weight_strong_signal_loss,
            "No device reported signal strength before the loss (iPhones never do).")
        missing.append("Signal strength before the loss is not available for any reporting device.")

    # 8. persistence
    persistent = sum(1 for m in members if _persistent(m, ctx.now, cfg))
    add("persistence", _fraction(persistent, n) * cfg.weight_persistence, cfg.weight_persistence,
        f"{persistent} of {n} households were still without Wi-Fi after {cfg.persistence_seconds} seconds.")
    if persistent:
        supporting.append(
            f"The interruption lasted longer than {cfg.persistence_seconds} seconds at {_plural(persistent, 'household')}."
        )

    # 9. penalty: mains power evidence
    power_known = [m for m in members if m.power_lost is not None]
    power_lost = sum(1 for m in power_known if m.power_lost)
    if power_known:
        add("power_loss_evidence", -_fraction(power_lost, len(power_known)) * cfg.penalty_power_evidence, 0.0,
            f"{power_lost} of {len(power_known)} charging phones lost external power at the same time.")
        if power_lost == 0:
            supporting.append(f"{_plural(len(power_known), 'charging phone')} kept external power, which argues against a power cut.")
    else:
        add("power_loss_evidence", 0.0, 0.0, "No reporting phone was charging, so a power cut cannot be checked.")
        missing.append("No reporting phone was charging, so a neighborhood power cut cannot be ruled out.")

    # 10. penalty: recurring at a similar time of day (scheduled maintenance pattern)
    start = members[0].occurred_at
    start_minute = start.hour * 60 + start.minute
    similar = 0
    for earlier in ctx.past_incident_starts:
        if earlier >= start or (start - earlier) > timedelta(days=cfg.recurring_lookback_days):
            continue
        diff = abs((earlier.hour * 60 + earlier.minute) - start_minute)
        if min(diff, 1440 - diff) <= cfg.recurring_time_of_day_tolerance_min:
            similar += 1
    if similar >= cfg.recurring_incident_count:
        add("recurring_time_of_day", -cfg.penalty_recurring_time_of_day, 0.0,
            f"{_plural(similar, 'similar incident')} happened at about this time of day in the last "
            f"{cfg.recurring_lookback_days} days, which is typical of scheduled maintenance.")
    else:
        add("recurring_time_of_day", 0.0, 0.0, "No pattern of earlier incidents at this time of day.")

    total = max(0.0, min(100.0, sum(item["points"] for item in breakdown)))
    return round(total, 1), breakdown, supporting, missing


def assess_cluster(members: Sequence[Onset], ctx: ClusterContext, cfg: DetectionConfig) -> ClusterResult:
    """Decide whether a multi-household cluster becomes a neighborhood incident, and which kind."""
    members = sorted(members, key=lambda o: (o.occurred_at, str(o.household_id)))
    n = len(members)
    active = max(ctx.active_household_count, n)
    share = _fraction(n, active)
    if n < max(2, cfg.min_affected_households):
        return ClusterResult(list(members), None, None, f"{n} households is below the minimum of {cfg.min_affected_households}")
    if share < cfg.min_affected_fraction:
        return ClusterResult(list(members), None, None, f"{share:.0%} of active households is below the minimum share")

    spread = (members[-1].occurred_at - members[0].occurred_at).total_seconds()
    modes = Counter(m.mode for m in members)
    isps = _isp_counts(members)
    delayed = sum(1 for m in members if m.report_delay_s > cfg.delayed_report_threshold_s)
    located = sum(1 for m in members if m.latitude is not None)
    cell_known = [m for m in members if m.cellular_available is not None]
    cell_up = sum(1 for m in cell_known if m.cellular_available)
    power_known = [m for m in members if m.power_lost is not None]
    power_lost = sum(1 for m in power_known if m.power_lost)

    opening = (
        f"{_number_word(n)} participating households reported connectivity interruptions within "
        f"{max(spread, 1):.0f} seconds."
    )
    common_missing: list[str] = []
    if located < n:
        common_missing.append(f"{_plural(n - located, 'affected household')} has no registered location; distance was not checked for it.")
    if delayed:
        common_missing.append(
            f"{_plural(delayed, 'report')} arrived late and was placed by the time it happened, not the time it arrived."
        )
    common_missing.append("Phones cannot measure radio interference directly; this assessment is an inference from connectivity reports.")

    def build(classification: Classification, level: AlertLevel, explanation: str, supporting: list[str],
              missing: list[str], score: float | None = None, breakdown: list[dict[str, Any]] | None = None) -> Assessment:
        return Assessment(
            scope=IncidentScope.NEIGHBORHOOD, classification=classification, alert_level=level,
            members=list(members), started_at=members[0].occurred_at,
            last_event_at=max([m.occurred_at for m in members] + [m.restored_at for m in members if m.restored_at]),
            affected_household_count=n, active_household_count=active, time_spread_s=spread,
            explanation=explanation, supporting_evidence=supporting, missing_evidence=missing + common_missing,
            score=score, score_breakdown=breakdown or [], approximate_extent_m=_extent_m(members),
            isp_diversity=isps, failure_modes={k.value: v for k, v in modes.items()}, delayed_report_count=delayed,
        )

    # --- A. Wi-Fi stayed connected, internet behind it failed: an upstream problem, not a radio problem.
    if modes[FailureMode.NO_INTERNET] * 2 > n:
        supporting = [f"{modes[FailureMode.NO_INTERNET]} of {n} households kept their Wi-Fi connection and lost only internet access."]
        missing: list[str] = []
        top_isp, top_count = (max(isps.items(), key=lambda kv: kv[1]) if isps else (None, 0))
        if top_isp and _fraction(top_count, sum(isps.values())) >= cfg.isp_outage_same_provider_fraction:
            name = ISP_NAMES.get(top_isp, top_isp)
            supporting.append(f"{top_count} of {sum(isps.values())} affected households with a known provider use {name}.")
            cause = f"This pattern is consistent with an outage at {name} or another upstream internet problem."
        elif len(isps) >= 2:
            supporting.append("Affected households use different providers.")
            cause = ("Because several providers are involved, a wider upstream problem such as a DNS or regional "
                     "network failure is more likely than a single provider outage.")
        else:
            missing.append("Internet providers of the affected households are unknown.")
            cause = "This pattern is consistent with an upstream internet problem."
        explanation = (
            f"{opening} Wi-Fi stayed connected in most of these homes while the internet became unreachable. "
            f"{cause} It is not the pattern expected from wireless interference. Security devices that depend "
            "on the internet may be offline until service returns."
        )
        return ClusterResult(list(members), build(Classification.POSSIBLE_ISP_OUTAGE, AlertLevel.HOUSEHOLD_WARNING,
                                                  explanation, supporting, missing), None, "upstream signature")

    # --- B. Several phones lost charging power at the same time: a power cut explains the Wi-Fi loss.
    if len(power_known) >= cfg.power_evidence_min_devices and _fraction(power_lost, len(power_known)) >= cfg.power_evidence_fraction:
        supporting = [f"{power_lost} of {len(power_known)} charging phones lost external power at the same time as the Wi-Fi loss."]
        explanation = (
            f"{opening} Several reporting phones stopped charging at the same moment, which points to a power "
            "cut or a local infrastructure failure rather than wireless interference. Security devices without "
            f"battery backup may be offline. {NOT_CONFIRMED}"
        )
        missing = [] if cell_known else ["Cellular availability during the interruption is unknown."]
        return ClusterResult(list(members), build(Classification.POSSIBLE_ROUTER_OR_POWER_FAILURE,
                                                  AlertLevel.HOUSEHOLD_WARNING, explanation, supporting, missing),
                             None, "power evidence")

    # --- C. Wireless-loss signature: score it.
    score, breakdown, supporting, missing = interference_score(members, ctx, cfg)
    if score < cfg.correlated_score_threshold:
        return ClusterResult(list(members), None, score, f"score {score} is below the threshold of {cfg.correlated_score_threshold:g}")

    # High priority needs a high score AND an outage that is still going on in most affected homes:
    # a brief simultaneous blip, however widespread, stays a level-2 anomaly.
    persistent = sum(1 for m in members if _persistent(m, ctx.now, cfg))
    high = (
        score >= cfg.high_priority_score_threshold
        and n >= cfg.high_priority_min_households
        and persistent * 2 >= n
    )
    sentences = [opening.replace("connectivity interruptions", "Wi-Fi connectivity interruptions")]
    if len(isps) >= 2:
        sentences.append("The affected households use different internet providers.")
    elif len(isps) == 1:
        sentences.append("The affected households with a known provider all use the same provider.")
    if cell_known:
        sentences.append(
            f"Cellular connectivity remained available for {_number_word(cell_up).lower()} of "
            f"{_number_word(len(cell_known)).lower()} reporting devices."
        )
    if high:
        sentences.append(
            "The interruption has persisted and several independent indicators agree. This pattern is "
            "consistent with a significant correlated wireless disruption."
        )
        classification, level = Classification.HIGH_PRIORITY_SUSPICIOUS_DISRUPTION, AlertLevel.HIGH_PRIORITY
    else:
        sentences.append("This pattern is consistent with a correlated wireless disruption.")
        classification, level = Classification.CORRELATED_NEIGHBORHOOD_DISRUPTION, AlertLevel.NEIGHBORHOOD_ANOMALY
    sentences.append(NOT_CONFIRMED)
    assessment = build(classification, level, " ".join(sentences), supporting, missing, score, breakdown)
    return ClusterResult(list(members), assessment, score, "promoted")


def evaluate(
    onsets: Sequence[Onset], ctx: ClusterContext, cfg: DetectionConfig
) -> tuple[list[Assessment], list[ClusterResult]]:
    """Cluster the onsets and assess every cluster.

    Returns (assessments, multi_household_cluster_results). Households in a cluster that was not
    promoted are assessed individually, so nothing a household reported is silently dropped.
    """
    assessments: list[Assessment] = []
    cluster_results: list[ClusterResult] = []
    for members in cluster_onsets(onsets, cfg):
        result = assess_cluster(members, ctx, cfg) if len(members) >= 2 else ClusterResult(list(members), None, None, "single household")
        if len(members) >= 2:
            cluster_results.append(result)
        if result.promoted is not None:
            assessments.append(result.promoted)
            continue
        for onset in members:
            single = assess_household(onset, ctx, cfg)
            if single is not None:
                assessments.append(single)
    return assessments, cluster_results


RECOMMENDED_ACTIONS: dict[Classification, list[str]] = {
    Classification.HOUSEHOLD_CONNECTIVITY_ISSUE: [
        "If you are at home, check that your router is powered on and its lights look normal.",
        "Open your camera and alarm apps and confirm the devices are online.",
    ],
    Classification.POSSIBLE_ISP_OUTAGE: [
        "Check your internet provider's outage page using cellular data.",
        "Confirm in your camera and alarm apps whether devices are online; internet-only devices may be offline until service returns.",
    ],
    Classification.POSSIBLE_ROUTER_OR_POWER_FAILURE: [
        "Check whether your home has power and whether the router is switched on.",
        "Confirm that your alarm system's battery and cellular backup are working.",
    ],
    Classification.POSSIBLE_WIRELESS_INTERFERENCE: [
        "Open your camera and alarm apps over cellular data and confirm the devices are online.",
        "Verify that your security system's backup protection (wired, battery or cellular) is operating.",
        "If you see anything suspicious around your home, keep a safe distance and call the police yourself. This app does not contact them.",
    ],
    Classification.CORRELATED_NEIGHBORHOOD_DISRUPTION: [
        "Check your alarm and camera connectivity through their own apps, using cellular data if Wi-Fi is down.",
        "Verify that your security system's backup protection is operating.",
        "Stay aware of your surroundings. If you see anything suspicious, call the police yourself. This app does not contact them.",
    ],
    Classification.HIGH_PRIORITY_SUSPICIOUS_DISRUPTION: [
        "Check your alarm and camera connectivity now through their own apps, using cellular data.",
        "Verify that your security system's backup protection is operating, and lock doors and windows.",
        "Do not confront anyone. If you see suspicious activity, call the police yourself. This app does not contact them.",
        "Let neighbors you trust know through your usual private channel.",
    ],
}
