"""Deterministic incident simulator: scenarios A to J from the build brief.

A scenario is a list of timed steps (events one simulated phone uploads, or a pause). The same
scenario objects are used by the automated tests (with an injected clock) and by
`python -m watchdog.simulation` against a locally running API seeded with `python -m watchdog.seed`.

Scenarios H and I are about behaviour on the phone (queueing without connectivity, iOS suspension).
Here they are simulated as the uploads such a phone produces afterwards; the on-device behaviour is
covered by the Android and iOS unit tests.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

from ..seed import SeededHousehold, SeededNeighborhood

_NS = uuid.UUID("0d5c1b1e-58a3-4d7c-8a43-6a4a2f6b9e77")


@dataclass
class Step:
    at_s: float  # when the upload reaches the backend, seconds after scenario start
    household: SeededHousehold | None = None  # None = just let time pass (and let the maintenance tick run)
    events: list[dict[str, Any]] = field(default_factory=list)
    reported_via: str = "cellular"


@dataclass
class Scenario:
    key: str
    title: str
    expectation: str
    steps: list[Step]


def make_event(scenario: str, household: SeededHousehold, seq: int, start: datetime, offset_s: float, event_type: str,
               state: str, **diagnostics: Any) -> dict[str, Any]:
    occurred = start + timedelta(seconds=offset_s)
    confidence = diagnostics.pop("confidence", "OBSERVED")
    return {
        "event_id": str(uuid.uuid5(_NS, f"{scenario}:{start.isoformat()}:{household.device_id}:{seq}")),
        "sequence": seq,
        "event_type": event_type,
        "connectivity_state": state,
        "occurred_at": occurred.isoformat(),
        "os_version": "15" if household.platform == "android" else "18.2",
        "app_version": "0.1.0",
        "confidence": confidence,
        "diagnostics": {k: (v.isoformat() if isinstance(v, datetime) else v) for k, v in diagnostics.items() if v is not None},
    }


def heartbeat(scenario, h, seq, start, offset_s=-300.0):
    return make_event(scenario, h, seq, start, offset_s, "HEARTBEAT", "HEALTHY", wifi_associated=True, wifi_validated=True,
                      home_network_match=True, internet_reachable=True, cellular_available=True, default_transport="wifi")


def wifi_lost(scenario, h, seq, start, offset_s, *, cellular=True, power_lost=None, rssi=None):
    android = h.platform == "android"
    return make_event(
        scenario, h, seq, start, offset_s, "WIFI_LOST", "WIFI_DISCONNECTED" if cellular else "INTERNET_UNAVAILABLE",
        wifi_associated=False, home_network_match=True, internet_reachable=cellular, cellular_available=cellular,
        default_transport="cellular" if cellular else "none", external_power_lost_recently=power_lost,
        last_wifi_rssi_dbm=(rssi if rssi is not None else -52) if android else None,
        wifi_radio_enabled=True if android else None, airplane_mode=False if android else None,
        band_evidence="MULTI_BAND_FAILURE_NOT_VERIFIED",
    )


def no_internet(scenario, h, seq, start, offset_s):
    return make_event(
        scenario, h, seq, start, offset_s, "INTERNET_REACHABILITY_CHANGED", "WIFI_CONNECTED_NO_INTERNET",
        wifi_associated=True, wifi_validated=False, home_network_match=True, internet_reachable=False,
        local_gateway_reachable=True if h.platform == "android" else None, cellular_available=True, default_transport="cellular",
    )


def wifi_restored(scenario, h, seq, start, offset_s):
    return make_event(scenario, h, seq, start, offset_s, "WIFI_RESTORED", "HEALTHY", wifi_associated=True, wifi_validated=True,
                      home_network_match=True, internet_reachable=True, cellular_available=True, default_transport="wifi")


def _baseline(key: str, hood: SeededNeighborhood, start: datetime) -> list[Step]:
    """Every household reports a healthy heartbeat five minutes before the scenario begins."""
    return [Step(-300.0 + i * 0.01, h, [heartbeat(key, h, 1, start)], reported_via="wifi") for i, h in enumerate(hood.households)]


def build_scenarios(hood: SeededNeighborhood, start: datetime) -> dict[str, Scenario]:
    att, xfinity = hood.by_isp("att"), hood.by_isp("xfinity")
    near = hood.households[:-1]  # the last seeded house is outside the correlation radius
    scenarios: dict[str, Scenario] = {}

    def add(key: str, title: str, expectation: str, steps: list[Step]) -> None:
        scenarios[key] = Scenario(key, title, expectation, _baseline(key, hood, start) + steps)

    h = att[0]
    add("A", "One household loses Wi-Fi", "Household connectivity warning only (level 1), no neighborhood incident.",
        [Step(2, h, [wifi_lost("A", h, 2, start, 0)]), Step(200, None)])

    add("B", "Five Xfinity households lose internet while Wi-Fi stays associated",
        "POSSIBLE_ISP_OUTAGE; not a wireless-disruption classification.",
        [Step(3 + i * 4, x, [no_internet("B", x, 2, start, i * 4)]) for i, x in enumerate(xfinity[:5])])

    c_members = att[:3] + xfinity[:3]
    add("C", "Three AT&T and three Xfinity households lose Wi-Fi, cellular stays up",
        "CORRELATED_NEIGHBORHOOD_DISRUPTION (level 2); escalates to HIGH_PRIORITY only if it persists.",
        [Step(2 + i * 6, m, [wifi_lost("C", m, 2, start, i * 6)]) for i, m in enumerate(c_members)])

    d_members = near[:10]
    add("D", "Ten households fail during a neighborhood power cut",
        "POSSIBLE_ROUTER_OR_POWER_FAILURE: charging phones lost external power at the same time.",
        [Step(2 + i * 3, m, [wifi_lost("D", m, 2, start, i * 3, power_lost=True if i % 2 == 0 else None)])
         for i, m in enumerate(d_members)])

    bad = xfinity[6]
    add("E", "One malicious device submits 100 false events",
        "Rate limiting; no neighborhood incident can come from one device.",
        [Step(1 + b, bad, [wifi_lost("E", bad, 2 + b * 25 + j, start, b * 0.5 + j * 0.01) for j in range(25)]) for b in range(4)])

    f_members = att[3:6] + xfinity[3:5]
    add("F", "Events arrive ten minutes late",
        "Correlated by occurrence time, with delayed-report metadata on the incident.",
        [Step(600 + i, m, [wifi_lost("F", m, 2, start, i * 5)]) for i, m in enumerate(f_members)])

    g = att[6]
    add("G", "A resident disables monitoring",
        "Monitoring status becomes PAUSED / GREY, never healthy.",
        [Step(5, g, [make_event("G", g, 2, start, 4, "MONITORING_DISABLED_BY_USER", "MONITORING_PAUSED", user_initiated=True)],
              reported_via="wifi")])

    hh = xfinity[7]
    add("H", "A household has no cellular connection",
        "The event was queued on the phone and is accepted later with its original time.",
        [Step(900, hh, [wifi_lost("H", hh, 2, start, 0, cellular=False), wifi_restored("H", hh, 3, start, 880)], reported_via="wifi")])

    ios = next(x for x in hood.households if x.platform == "ios")
    add("I", "iOS suspends the application",
        "A monitoring gap is recorded and state is reconciled on resume; nothing is back-dated.",
        [Step(1500, ios, [
            make_event("I", ios, 2, start, 1495, "MONITORING_GAP_DETECTED", "UNKNOWN", gap_started_at=start + timedelta(seconds=60),
                       gap_ended_at=start + timedelta(seconds=1495), gap_reason="app_suspended"),
            make_event("I", ios, 3, start, 1496, "MONITORING_RESUMED", "HEALTHY", wifi_associated=True, home_network_match=True,
                       internet_reachable=True, cellular_available=True, default_transport="wifi"),
            make_event("I", ios, 4, start, 1496, "WIFI_LOST", "WIFI_DISCONNECTED", confidence="RECONCILED", wifi_associated=False,
                       cellular_available=True),
        ], reported_via="wifi")])

    j1, j2 = att[7], xfinity[8]
    add("J", "Two unrelated households report failures 30 minutes apart",
        "No correlated incident.",
        [Step(2, j1, [wifi_lost("J", j1, 2, start, 0)]), Step(1802, j2, [wifi_lost("J", j2, 2, start, 1800)])])
    return scenarios


def run(scenario: Scenario, start: datetime, submit: Callable[[Step, datetime], Any],
        advance: Callable[[datetime], None]) -> list[Any]:
    """Play a scenario. `advance(t)` moves time to t; `submit(step, sent_at)` uploads one batch."""
    results = []
    for step in sorted(scenario.steps, key=lambda s: s.at_s):
        moment = start + timedelta(seconds=step.at_s)
        advance(moment)
        if step.household is not None:
            results.append(submit(step, moment))
    return results
