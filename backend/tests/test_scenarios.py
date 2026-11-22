"""Simulation tests: scenarios A to J from the build brief, played through the real API."""

from __future__ import annotations

from datetime import timedelta

import pytest

from watchdog.simulation.scenarios import Scenario, Step, build_scenarios, run

from .conftest import START, World

pytestmark = pytest.mark.db


def play(world: World, key: str) -> tuple[Scenario, list]:
    scenario = build_scenarios(world.hood, START)[key]
    world.clock.set(START - timedelta(seconds=310))  # the baseline heartbeats happen before the scenario starts

    def advance(moment):
        if moment > world.clock.now():
            world.clock.set(moment)
        world.tick()

    def submit(step: Step, sent_at):
        actor = world.actor(step.household)
        response = actor.submit(step.events, reported_via=step.reported_via)
        assert response.status_code == 200, response.text
        return response.json()

    results = run(scenario, START, submit, advance)
    world.tick()
    return scenario, results


def classifications(world: World, scope: str) -> list[str]:
    return [i["classification"] for i in world.incidents(scope)]


def test_a_single_household_wifi_loss_is_a_household_warning(world):
    world.add_push_tokens()
    scenario, _ = play(world, "A")
    assert world.incidents("neighborhood") == []
    [incident] = world.incidents("household")
    assert incident["alert_level"] == 1
    assert incident["classification"] in ("HOUSEHOLD_CONNECTIVITY_ISSUE", "POSSIBLE_WIRELESS_INTERFERENCE")
    assert "has not been confirmed" in incident["explanation"]
    # Only that household is told, and only its own devices.
    affected = scenario.steps[-2].household
    recipients = {d["user_id"] for d in world.deliveries()}
    assert recipients <= {affected.user_id}
    # Other residents cannot see it.
    other = world.actor(5)
    assert other.get("/api/v1/incidents?status=all").json()["incidents"] == []
    mine = world.actor(affected).get("/api/v1/incidents").json()["incidents"]
    assert len(mine) == 1 and mine[0]["my_household_affected"] is True


def test_b_single_provider_internet_loss_is_a_possible_isp_outage(world):
    play(world, "B")
    [incident] = world.incidents("neighborhood")
    assert incident["classification"] == "POSSIBLE_ISP_OUTAGE"
    assert incident["alert_level"] == 1  # not a neighborhood wireless alert
    assert incident["score"] is None
    assert "Xfinity" in incident["explanation"]
    assert "not the pattern expected from wireless interference" in incident["explanation"]
    assert incident["affected_household_count"] == 5


def test_c_mixed_provider_wifi_loss_is_a_correlated_disruption_then_escalates(world):
    world.add_push_tokens()
    play(world, "C")
    [incident] = world.incidents("neighborhood")
    assert incident["classification"] == "CORRELATED_NEIGHBORHOOD_DISRUPTION"
    assert incident["alert_level"] == 2
    assert incident["affected_household_count"] == 6
    assert incident["isp_diversity"] == {"att": 3, "xfinity": 3}
    assert "different internet providers" in incident["explanation"]
    assert incident["explanation"].endswith("Deliberate interference has not been confirmed.")
    indicators = {c["indicator"]: c for c in incident["score_breakdown"]}
    assert indicators["isp_diversity"]["points"] > 0 and indicators["cellular_available"]["points"] > 0
    assert abs(sum(c["points"] for c in incident["score_breakdown"]) - incident["score"]) < 0.2
    assert world.incidents("household") == []  # household incidents were absorbed
    # Everyone participating is alerted at level 2, once per device.
    world.client.app.state.pipeline.deliver(world.clock.now())
    alerts = [d for d in world.deliveries() if d["kind"] == "alert"]
    assert len(alerts) == len(world.hood.households) and {d["alert_level"] for d in alerts} == {2}
    assert all("does not confirm deliberate jamming" in d["body"] for d in alerts)

    # It persists: after the persistence period the same incident becomes high priority.
    world.tick(150)
    [incident] = world.incidents("neighborhood")
    assert incident["classification"] == "HIGH_PRIORITY_SUSPICIOUS_DISRUPTION"
    assert incident["alert_level"] == 3 and incident["peak_alert_level"] == 3
    escalations = [d for d in world.deliveries() if d["kind"] == "escalation"]
    assert len(escalations) == len(world.hood.households) and {d["alert_level"] for d in escalations} == {3}


def test_d_power_cut_is_not_labelled_interference(world):
    play(world, "D")
    [incident] = world.incidents("neighborhood")
    assert incident["classification"] == "POSSIBLE_ROUTER_OR_POWER_FAILURE"
    assert incident["alert_level"] == 1
    assert incident["affected_household_count"] == 10
    assert "power" in incident["explanation"].lower()
    world.tick(200)  # persistence must not turn a power cut into a wireless alert
    assert classifications(world, "neighborhood") == ["POSSIBLE_ROUTER_OR_POWER_FAILURE"]


def test_e_one_malicious_device_cannot_raise_a_neighborhood_alert(world):
    _, results = play(world, "E")
    outcomes = [o for batch in results[len(world.hood.households):] for o in batch["outcomes"]]
    assert len(outcomes) == 100
    accepted = [o for o in outcomes if o["result"] == "accepted"]
    limited = [o for o in outcomes if o.get("reason") == "rate_limited"]
    assert len(accepted) == 60 and len(limited) == 40  # default per-device minute limit
    assert world.incidents("neighborhood") == []
    assert all(i["alert_level"] <= 1 for i in world.incidents())
    with world.db.service() as conn:
        kinds = {r["kind"] for r in conn.execute("SELECT kind FROM nw.security_events")}
        excluded = conn.execute(
            "SELECT count(*) AS n FROM nw.connectivity_events WHERE excluded_reason = 'onset_rate_limit'").fetchone()["n"]
    assert {"event_rate_limited", "suspicious_reporting"} <= kinds
    assert excluded == 60 - 6  # only the hourly onset allowance may reach correlation


def test_f_late_events_correlate_by_occurrence_time(world):
    play(world, "F")
    [incident] = world.incidents("neighborhood")
    assert incident["classification"] in ("CORRELATED_NEIGHBORHOOD_DISRUPTION", "HIGH_PRIORITY_SUSPICIOUS_DISRUPTION")
    assert incident["started_at"] == START  # when it happened, not when it was reported
    assert incident["delayed_report_count"] == 5
    assert incident["time_spread_s"] == 20
    assert any("arrived late" in m for m in incident["missing_evidence"])
    with world.db.service() as conn:
        delays = [r["report_delay_s"] for r in conn.execute(
            "SELECT report_delay_s FROM nw.connectivity_events WHERE event_type = 'WIFI_LOST'")]
    assert all(d >= 580 for d in delays)


def test_g_disabled_monitoring_is_never_shown_as_healthy(world):
    scenario, _ = play(world, "G")
    resident = world.actor(scenario.steps[-1].household)
    status = resident.get("/api/v1/connectivity/status").json()
    assert status["household_monitoring_status"] == "PAUSED"
    assert status["status_color"] == "GREY"
    assert "does not mean everything is fine" in status["status_explanation"]
    assert status["devices"][0]["connectivity_state"] == "MONITORING_PAUSED"
    assert world.incidents() == []
    # ...and it stays that way however much time passes.
    world.tick(3 * 3600)
    assert resident.get("/api/v1/connectivity/status").json()["status_color"] == "GREY"


def test_h_event_queued_without_cellular_is_accepted_later_with_original_time(world):
    _, results = play(world, "H")
    assert [o["result"] for o in results[-1]["outcomes"]] == ["accepted", "accepted"]
    with world.db.service() as conn:
        row = conn.execute(
            "SELECT occurred_at, received_at, report_delay_s, connectivity_state FROM nw.connectivity_events"
            " WHERE event_type = 'WIFI_LOST'").fetchone()
    assert row["occurred_at"] == START and row["report_delay_s"] == 900
    assert row["connectivity_state"] == "INTERNET_UNAVAILABLE"
    # By the time it arrives the outage is over: recorded as history, already closed, nobody alerted now.
    [incident] = world.incidents("household")
    assert incident["status"] == "resolved" and incident["started_at"] == START
    assert world.incidents("neighborhood") == []


def test_i_ios_suspension_gap_is_recorded_and_reconciled(world):
    scenario, results = play(world, "I")
    assert [o["result"] for o in results[-1]["outcomes"]] == ["accepted"] * 3
    device = scenario.steps[-1].household.device_id
    with world.db.service() as conn:
        sessions = conn.execute(
            "SELECT started_at, ended_at, end_reason FROM nw.device_sessions WHERE device_id = %s ORDER BY started_at",
            [device]).fetchall()
        reconciled = conn.execute(
            "SELECT confidence, occurred_at FROM nw.connectivity_events WHERE device_id = %s AND event_type = 'WIFI_LOST'",
            [device]).fetchone()
    assert sessions[0]["end_reason"] == "app_suspended" and sessions[0]["ended_at"] is not None
    assert sessions[-1]["ended_at"] is None  # monitoring resumed
    # The reconciled loss carries the resume time and is not used as a correlation onset.
    assert reconciled["confidence"] == "RECONCILED"
    assert (reconciled["occurred_at"] - START).total_seconds() == 1496
    assert world.incidents() == []


def test_j_failures_thirty_minutes_apart_do_not_correlate(world):
    play(world, "J")
    assert world.incidents("neighborhood") == []
    household_incidents = world.incidents("household")
    assert len(household_incidents) == 2
    assert all(i["affected_household_count"] == 1 for i in household_incidents)


def test_every_scenario_is_covered():
    import inspect
    import sys

    tests = [name for name, _ in inspect.getmembers(sys.modules[__name__], inspect.isfunction)]
    for key in "abcdefghij":
        assert any(name.startswith(f"test_{key}_") for name in tests), key
