"""Unit tests for the pure detection engine and helpers. No database needed."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from watchdog.config import DEFAULTS, validate_overrides
from watchdog.correlation.engine import (
    ClusterContext,
    EventRecord,
    HouseholdInfo,
    Onset,
    assess_cluster,
    assess_household,
    cluster_onsets,
    evaluate,
    extract_onsets,
    failure_mode_of,
    haversine_m,
    interference_score,
)
from watchdog.enums import Classification, FailureMode
from watchdog.invitation_tokens import hash_token, new_token
from watchdog.normalization import normalize_house_number, normalize_street_name
from watchdog.ratelimit import SlidingWindowLimiter

T0 = datetime(2026, 3, 10, 20, 0, tzinfo=UTC)
CFG = DEFAULTS


def onset(
    seconds: float = 0,
    *,
    isp="att",
    lat=33.05,
    lon=-97.05,
    mode=FailureMode.WIFI_LOST,
    cellular=True,
    rssi=None,
    power=None,
    restored=None,
    delay=1.0,
    household=None,
) -> Onset:
    at = T0 + timedelta(seconds=seconds)
    return Onset(
        household_id=household or uuid.uuid4(),
        device_id=uuid.uuid4(),
        event_id=uuid.uuid4(),
        occurred_at=at,
        received_at=at + timedelta(seconds=delay),
        mode=mode,
        platform="android",
        isp=isp,
        latitude=lat,
        longitude=lon,
        cellular_available=cellular,
        gateway_reachable=None,
        last_rssi_dbm=rssi,
        power_lost=power,
        restored_at=(T0 + timedelta(seconds=restored)) if restored is not None else None,
    )


def ctx(active=20, now_s=40.0, **kw) -> ClusterContext:
    return ClusterContext(now=T0 + timedelta(seconds=now_s), active_household_count=active, **kw)


def record(household, device, seconds, event_type="WIFI_LOST", state="WIFI_DISCONNECTED", confidence="OBSERVED", **diag):
    at = T0 + timedelta(seconds=seconds)
    diag.setdefault("home_network_match", True)
    return EventRecord(
        id=uuid.uuid4(),
        device_id=device,
        household_id=household,
        event_type=event_type,
        connectivity_state=state,
        occurred_at=at,
        received_at=at,
        confidence=confidence,
        platform="android",
        diagnostics=diag,
    )


# ------------------------------------------------------------------ event classification


@pytest.mark.parametrize(
    ("event_type", "state", "diag", "expected"),
    [
        ("WIFI_LOST", "WIFI_DISCONNECTED", {"cellular_available": True}, FailureMode.WIFI_LOST),
        ("WIFI_LOST", "INTERNET_UNAVAILABLE", {"cellular_available": False}, FailureMode.ALL_CONNECTIVITY_LOST),
        ("INTERNET_REACHABILITY_CHANGED", "WIFI_CONNECTED_NO_INTERNET", {}, FailureMode.NO_INTERNET),
        ("INTERNET_REACHABILITY_CHANGED", "HEALTHY", {}, None),
        ("HEARTBEAT", "HEALTHY", {}, None),
        ("WIFI_LOST", "WIFI_DISCONNECTED", {"user_initiated": True}, None),
        ("WIFI_LOST", "WIFI_DISCONNECTED", {"wifi_radio_enabled": False}, None),
        ("WIFI_LOST", "WIFI_DISCONNECTED", {"airplane_mode": True}, None),
        ("WIFI_LOST", "WIFI_DISCONNECTED", {"home_network_match": False}, None),
        ("CELLULAR_UNAVAILABLE", "CELLULAR_UNAVAILABLE", {}, None),
        ("MONITORING_DISABLED_BY_USER", "MONITORING_PAUSED", {}, None),
    ],
)
def test_which_events_start_a_failure(event_type, state, diag, expected):
    assert failure_mode_of(record(uuid.uuid4(), uuid.uuid4(), 0, event_type, state, **diag)) == expected


def test_reconciled_and_excluded_events_never_start_a_failure():
    h, d = uuid.uuid4(), uuid.uuid4()
    assert failure_mode_of(record(h, d, 0, confidence="RECONCILED")) is None
    excluded = record(h, d, 0)
    excluded = EventRecord(**{**excluded.__dict__, "excluded_reason": "onset_rate_limit"})
    assert failure_mode_of(excluded) is None


def test_episode_runs_from_first_failure_to_recovery():
    h, d = uuid.uuid4(), uuid.uuid4()
    events = [
        record(h, d, 0, cellular_available=None),
        record(h, d, 5, cellular_available=True),
        record(h, d, 90, "WIFI_RESTORED", "HEALTHY"),
        record(h, d, 400),
    ]
    onsets = extract_onsets(events, {h: HouseholdInfo(h, "att", 33.0, -97.0)}, CFG)
    assert [(o.occurred_at - T0).total_seconds() for o in onsets] == [0, 400]
    assert onsets[0].restored_at == T0 + timedelta(seconds=90) and onsets[1].restored_at is None
    assert onsets[0].cellular_available is True  # evidence filled in from the later event of the episode


def test_other_phone_healthy_at_home_cancels_the_onset():
    h, leaving, at_home = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    events = [record(h, leaving, 0), record(h, at_home, 30, "HEARTBEAT", "HEALTHY")]
    assert extract_onsets(events, {h: HouseholdInfo(h)}, CFG) == []
    # ...but if that other phone lost Wi-Fi too, its recovery is a recovery, not a contradiction.
    events = [record(h, leaving, 0), record(h, at_home, 2), record(h, at_home, 30, "WIFI_RESTORED", "HEALTHY")]
    [only] = extract_onsets(events, {h: HouseholdInfo(h)}, CFG)
    assert only.restored_at is not None


def test_events_of_unknown_households_are_ignored():
    h = uuid.uuid4()
    assert extract_onsets([record(h, uuid.uuid4(), 0)], {}, CFG) == []


# ------------------------------------------------------------------ clustering


def test_haversine_matches_known_distance():
    assert 110_000 < haversine_m(33.0, -97.0, 34.0, -97.0) < 112_500


def test_clusters_respect_the_time_window():
    a, b, c = onset(0), onset(50), onset(50 + CFG.correlation_window_s + 1)
    clusters = cluster_onsets([c, a, b], CFG)
    assert [len(x) for x in clusters] == [2, 1]


def test_clusters_respect_the_maximum_span():
    chain = [onset(i * 55) for i in range(8)]  # each within the window of the previous one
    clusters = cluster_onsets(chain, CFG)
    assert max((x[-1].occurred_at - x[0].occurred_at).total_seconds() for x in clusters) <= CFG.max_cluster_span_s
    assert sum(len(x) for x in clusters) == 8


def test_clusters_respect_the_radius_and_allow_simultaneous_incidents():
    here = [onset(i, lat=33.05, lon=-97.05 + i * 0.0003) for i in range(3)]
    far = [onset(i, lat=33.20, lon=-97.05 + i * 0.0003) for i in range(3)]  # about 16 km away
    clusters = cluster_onsets(here + far, CFG)
    assert sorted(len(x) for x in clusters) == [3, 3]


def test_households_without_location_are_kept_and_flagged():
    members = [onset(i, lat=None, lon=None, isp="att" if i % 2 else "xfinity") for i in range(4)]
    [cluster] = cluster_onsets(members, CFG)
    result = assess_cluster(cluster, ctx(), CFG)
    assert result.promoted is not None and result.promoted.approximate_extent_m is None
    assert any("no registered location" in m for m in result.promoted.missing_evidence)


def test_one_household_counts_once_per_cluster():
    h = uuid.uuid4()
    clusters = cluster_onsets([onset(0, household=h), onset(10, household=h), onset(20)], CFG)
    assert sorted(len(x) for x in clusters) == [1, 2]


# ------------------------------------------------------------------ scoring and classification


def test_score_has_a_breakdown_that_adds_up_and_explains_itself():
    members = [onset(i * 5, isp="att" if i < 3 else "xfinity", rssi=-50) for i in range(6)]
    score, breakdown, supporting, missing = interference_score(members, ctx(active=24), CFG)
    assert score == pytest.approx(sum(c["points"] for c in breakdown), abs=0.11)
    assert all(c["detail"] and c["points"] <= c["max_points"] + 1e-9 for c in breakdown)
    assert {c["indicator"] for c in breakdown} >= {
        "affected_households",
        "participation",
        "time_tightness",
        "isp_diversity",
        "cellular_available",
        "wifi_loss_signature",
        "strong_signal_before_loss",
        "persistence",
        "power_loss_evidence",
        "recurring_time_of_day",
    }
    positive = sum(c["max_points"] for c in breakdown)
    assert positive == pytest.approx(100.0)


def test_isp_diversity_adds_suspicion_only_together_with_other_indicators():
    diverse = [onset(i * 5, isp="att" if i % 2 else "xfinity") for i in range(6)]
    same = [onset(i * 5, isp="att") for i in range(6)]
    s_diverse, *_ = interference_score(diverse, ctx(), CFG)
    s_same, *_ = interference_score(same, ctx(), CFG)
    assert s_diverse - s_same == pytest.approx(CFG.weight_isp_diversity)
    # Diversity alone is not enough: the internet-only signature is still an upstream problem.
    upstream = [onset(i * 5, isp="att" if i % 2 else "xfinity", mode=FailureMode.NO_INTERNET) for i in range(6)]
    result = assess_cluster(upstream, ctx(), CFG)
    assert result.promoted.classification == Classification.POSSIBLE_ISP_OUTAGE
    assert "wider upstream problem" in result.promoted.explanation


def test_below_minimum_households_is_never_a_neighborhood_incident():
    result = assess_cluster([onset(0, isp="att"), onset(5, isp="xfinity")], ctx(), CFG)
    assert result.promoted is None and "below the minimum" in result.reason


def test_minimum_share_of_active_households_is_enforced():
    cfg = CFG.with_overrides({"min_affected_fraction": 0.5})
    result = assess_cluster([onset(i) for i in range(3)], ctx(active=20), cfg)
    assert result.promoted is None and "share" in result.reason


def test_thresholds_are_configurable():
    members = [onset(i * 10, isp="unknown", cellular=None) for i in range(3)]
    assert assess_cluster(members, ctx(), CFG).promoted is None  # weak evidence, default threshold
    relaxed = CFG.with_overrides({"correlated_score_threshold": 10})
    assert assess_cluster(members, ctx(), relaxed).promoted.classification == Classification.CORRELATED_NEIGHBORHOOD_DISRUPTION


def test_high_priority_needs_persistence_and_score():
    members = [onset(i * 2, isp="att" if i % 2 else "xfinity", rssi=-48) for i in range(8)]
    early = assess_cluster(members, ctx(active=10, now_s=30), CFG).promoted
    late = assess_cluster(members, ctx(active=10, now_s=30 + CFG.persistence_seconds), CFG).promoted
    assert early.classification == Classification.CORRELATED_NEIGHBORHOOD_DISRUPTION
    assert late.classification == Classification.HIGH_PRIORITY_SUSPICIOUS_DISRUPTION
    assert late.score > early.score
    # A blip that everyone recovered from quickly never becomes high priority.
    blip = [onset(i * 2, isp="att" if i % 2 else "xfinity", rssi=-48, restored=20) for i in range(8)]
    assert (
        assess_cluster(blip, ctx(active=10, now_s=600), CFG).promoted.classification
        == Classification.CORRELATED_NEIGHBORHOOD_DISRUPTION
    )


def test_power_evidence_wins_over_interference():
    members = [onset(i, isp="att" if i % 2 else "xfinity", power=True if i < 3 else None) for i in range(6)]
    assert assess_cluster(members, ctx(), CFG).promoted.classification == Classification.POSSIBLE_ROUTER_OR_POWER_FAILURE
    # Phones that kept charging argue against a power cut.
    kept = [onset(i, isp="att" if i % 2 else "xfinity", power=False) for i in range(6)]
    promoted = assess_cluster(kept, ctx(), CFG).promoted
    assert promoted.classification == Classification.CORRELATED_NEIGHBORHOOD_DISRUPTION
    assert any("kept external power" in s for s in promoted.supporting_evidence)


def test_recurring_time_of_day_lowers_the_score():
    members = [onset(i * 5, isp="att" if i % 2 else "xfinity") for i in range(4)]
    plain, *_ = interference_score(members, ctx(), CFG)
    history = [T0 - timedelta(days=d, minutes=5) for d in (1, 2, 3)]
    recurring, breakdown, *_ = interference_score(members, ctx(past_incident_starts=history), CFG)
    assert plain - recurring == pytest.approx(CFG.penalty_recurring_time_of_day)
    assert any(c["indicator"] == "recurring_time_of_day" and c["points"] < 0 for c in breakdown)


def test_every_neighborhood_explanation_denies_confirmation_and_never_says_jamming():
    for members in (
        [onset(i * 5, isp="att" if i % 2 else "xfinity") for i in range(6)],
        [onset(i * 5, isp="att", power=True) for i in range(4)],
    ):
        text = assess_cluster(members, ctx(now_s=500), CFG).promoted.explanation
        assert "has not been confirmed" in text and "jamming" not in text.lower()


def test_household_classifications():
    def classify(o, prior=0):
        result = assess_household(o, ctx(prior_onsets_by_event={o.event_id: prior}), CFG)
        return result.classification if result else None

    assert classify(onset(mode=FailureMode.NO_INTERNET)) == Classification.POSSIBLE_ISP_OUTAGE
    assert classify(onset(power=True)) == Classification.POSSIBLE_ROUTER_OR_POWER_FAILURE
    assert classify(onset(rssi=-50)) == Classification.POSSIBLE_WIRELESS_INTERFERENCE
    assert classify(onset(rssi=None)) == Classification.HOUSEHOLD_CONNECTIVITY_ISSUE  # iPhone: cannot tell
    assert classify(onset(rssi=-88)) is None  # weak, fading signal on one phone: left the house
    assert classify(onset(rssi=None), prior=2) == Classification.POSSIBLE_WIRELESS_INTERFERENCE  # repeated
    assert classify(onset(cellular=False)) == Classification.HOUSEHOLD_CONNECTIVITY_ISSUE


def test_unpromoted_cluster_members_are_still_assessed_individually():
    members = [onset(0, rssi=-50), onset(5, rssi=-50)]
    assessments, clusters = evaluate(members, ctx(), CFG)
    assert len(clusters) == 1 and clusters[0].promoted is None
    assert [a.scope.value for a in assessments] == ["household", "household"]


# ------------------------------------------------------------------ helpers


@pytest.mark.parametrize(("raw", "expected"), [(" 12-B ", "12b"), ("0042", "42"), ("7 1/2", "712"), ("#9", "9")])
def test_house_number_normalization(raw, expected):
    assert normalize_house_number(raw) == expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("North Maple   Street.", "n maple st"),
        ("maple st", "maple st"),
        ("MAPLE STREET", "maple st"),
        ("Cedar Bend Lane", "cedar bend ln"),
        ("Avenue C", "avenue c"),
        ("Oak Drive West", "oak dr w"),
        ("Café Court", "cafe ct"),
        ("North Street", "n st"),
    ],
)
def test_street_name_normalization(raw, expected):
    assert normalize_street_name(raw) == expected


def test_empty_address_parts_are_rejected():
    with pytest.raises(ValueError):
        normalize_house_number(" - ")
    with pytest.raises(ValueError):
        normalize_street_name("...")


def test_invitation_tokens_are_long_random_and_hashed():
    tokens = {new_token() for _ in range(200)}
    assert len(tokens) == 200 and all(len(t) >= 43 for t in tokens)  # 32 random bytes, far above 128 bits
    token = tokens.pop()
    assert hash_token(token) == hash_token(token) and token not in hash_token(token) and len(hash_token(token)) == 64


def test_configuration_overrides_are_validated():
    assert validate_overrides({"correlation_radius_m": 300}) == {"correlation_radius_m": 300}
    for bad in (
        {"min_affected_households": 1},
        {"unknown_key": 1},
        {"correlation_window_s": "60"},
        {"correlation_radius_m": 10**6},
        {"correlated_score_threshold": 90, "high_priority_score_threshold": 50},
    ):
        with pytest.raises(ValueError):
            validate_overrides(bad)
    assert DEFAULTS.with_overrides({"correlation_radius_m": 300, "nonsense": 1}).correlation_radius_m == 300


def test_sliding_window_limiter():
    limiter = SlidingWindowLimiter()
    assert [limiter.check("k", 3, 60, T0) for _ in range(3)] == [0, 0, 0]
    assert limiter.check("k", 3, 60, T0 + timedelta(seconds=10)) > 0
    assert limiter.check("other", 3, 60, T0) == 0
    assert limiter.check("k", 3, 60, T0 + timedelta(seconds=61)) == 0


def test_timestamps_are_normalized_to_utc():
    from watchdog.schemas import ConnectivityEventIn

    event = ConnectivityEventIn.model_validate(
        {
            "event_id": str(uuid.uuid4()),
            "sequence": 1,
            "event_type": "HEARTBEAT",
            "connectivity_state": "HEALTHY",
            "occurred_at": "2026-03-10T15:00:00-05:00",
            "os_version": "15",
            "app_version": "0.1.0",
        }
    )
    assert event.occurred_at.astimezone(UTC) == T0
    with pytest.raises(ValueError):  # a timestamp without a time zone is ambiguous and is refused
        ConnectivityEventIn.model_validate(
            {
                "event_id": str(uuid.uuid4()),
                "sequence": 1,
                "event_type": "HEARTBEAT",
                "connectivity_state": "HEALTHY",
                "occurred_at": "2026-03-10T20:00:00",
                "os_version": "15",
                "app_version": "0.1.0",
            }
        )
