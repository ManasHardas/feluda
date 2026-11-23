"""Administration, configuration, audit, export, retention and the maintenance worker."""

from __future__ import annotations

from datetime import timedelta

import pytest

from watchdog import migrate
from watchdog.pipeline import run_tick_exclusively
from watchdog.services import retention

pytestmark = pytest.mark.db


def test_admin_manages_households_and_everything_is_audited(world):
    admin = world.admin
    created = admin.post(
        "/api/v1/admin/households",
        {"house_number": "500", "street_name": "Elm Street", "isp": "att", "latitude": 33.0502, "longitude": -97.0502},
    )
    assert created.status_code == 201 and created.json()["membership_status"] == "approved" and created.json()["has_location"]
    assert (
        admin.post("/api/v1/admin/households", {"house_number": "500", "street_name": "ELM ST."}).status_code == 409
    )  # same address
    assert admin.post("/api/v1/admin/households", {"house_number": "1", "street_name": "A", "latitude": 33.0}).status_code == 422

    listing = admin.get("/api/v1/admin/households").json()
    assert len(listing) == 25
    assert set(listing[0]) == {
        "household_id",
        "anonymous_reference",
        "house_number",
        "street_name",
        "isp",
        "membership_status",
        "device_count",
        "has_location",
        "created_at",
    }  # no device details, nothing personal

    target = world.hood.households[5]
    resident = world.actor(target)
    resident.submit([resident.heartbeat()])
    assert (
        admin.post(
            "/api/v1/admin/households/remove", {"household_id": str(target.household_id), "reason": "moved away"}
        ).status_code
        == 200
    )
    assert resident.get("/api/v1/households/me").status_code == 401
    with world.db.service() as conn:
        assert (
            conn.execute(
                "SELECT count(*) AS n FROM nw.connectivity_events WHERE household_id = %s", [target.household_id]
            ).fetchone()["n"]
            == 0
        )
        assert (
            conn.execute("SELECT count(*) AS n FROM nw.devices WHERE household_id = %s", [target.household_id]).fetchone()["n"]
            == 0
        )

    actions = [entry["action"] for entry in admin.get("/api/v1/admin/audit-logs").json()]
    for expected in ("household_approved", "household_list_viewed", "household_removed"):
        assert expected in actions
    removal = next(e for e in admin.get("/api/v1/admin/audit-logs").json() if e["action"] == "household_removed")
    assert removal["metadata"] == {"reason": "moved away"} and removal["actor_reference"] == "admin"


def test_admin_can_revoke_a_compromised_device(world):
    target = world.actor(8)
    assert (
        world.admin.post(
            "/api/v1/admin/devices/revoke", {"device_id": str(target.household.device_id), "reason": "lost phone"}
        ).status_code
        == 200
    )
    refused = target.submit([target.heartbeat()])
    assert refused.status_code == 401 and refused.json()["error"]["code"] == "unauthorized"
    with world.db.service() as conn:
        left = conn.execute(
            "SELECT count(*) AS n FROM nw.device_credentials WHERE device_id = %s", [target.household.device_id]
        ).fetchone()
    assert left["n"] == 0  # the phone's secret is destroyed, not just flagged


def test_detection_thresholds_can_be_changed_and_are_bounded(world):
    admin = world.admin
    config = admin.get("/api/v1/admin/configuration").json()
    assert config["values"]["correlation_radius_m"] == 500 and config["values"]["correlation_window_s"] == 60
    put = lambda values: admin.client.put("/api/v1/admin/configuration", headers=admin.headers, json={"values": values})  # noqa: E731
    assert put({"min_affected_households": 1}).status_code == 422  # one household may never raise a neighborhood alert
    assert put({"no_such_setting": 5}).status_code == 422
    updated = put({"min_affected_households": 2, "correlated_score_threshold": 30})
    assert updated.status_code == 200 and updated.json()["values"]["min_affected_households"] == 2
    assert updated.json()["defaults"]["min_affected_households"] == 3

    # Two households are now enough for a neighborhood incident in this neighborhood.
    for household in (world.hood.by_isp("att")[0], world.hood.by_isp("xfinity")[0]):
        actor = world.actor(household)
        actor.submit([actor.wifi_lost(last_wifi_rssi_dbm=-50)])
    assert [i["affected_household_count"] for i in world.incidents("neighborhood")] == [2]
    assert "configuration_changed" in [e["action"] for e in admin.get("/api/v1/admin/audit-logs").json()]


def test_summary_history_and_anonymized_export(world):
    world.all_heartbeat()
    paused = world.actor(9)
    paused.submit([paused.event("MONITORING_DISABLED_BY_USER", "MONITORING_PAUSED", user_initiated=True)])
    for household in world.hood.by_isp("att")[:3] + world.hood.by_isp("xfinity")[:3]:
        actor = world.actor(household)
        actor.submit([actor.wifi_lost()])
    loner = world.actor(world.hood.by_isp("other")[0])
    world.clock.advance(1200)
    loner.submit([loner.wifi_lost(last_wifi_rssi_dbm=-50)])

    summary = world.admin.get("/api/v1/admin/neighborhood/summary").json()
    assert summary["households_by_status"] == {"active": 24}
    assert summary["monitoring_by_status"]["PAUSED"] == 1 and summary["open_incidents"] == 2
    assert summary["devices_by_platform"] == {"android": 12, "ios": 12}

    history = world.admin.get("/api/v1/admin/incidents").json()["incidents"]
    assert {i["scope"] for i in history} == {"neighborhood", "household"}
    household_entry = next(i for i in history if i["scope"] == "household")
    assert (
        household_entry["approximate_scope"] == "One household (not identified)"
        and household_entry["my_household_affected"] is False
    )

    export = world.admin.get("/api/v1/admin/incidents/export")
    assert export.status_code == 200 and len(export.json()) == 2
    for h in world.hood.households:
        for secret in (str(h.household_id), str(h.device_id), str(h.user_id), h.device_secret):
            assert secret not in export.text
    assert "latitude" not in export.text and "normalized_street_name" not in export.text
    assert "incidents_exported" in [e["action"] for e in world.admin.get("/api/v1/admin/audit-logs").json()]


def test_metrics_are_available_to_admins(world):
    resident = world.actor(3)
    resident.submit([resident.heartbeat()])
    metrics = world.admin.get("/api/v1/monitoring/metrics").json()
    assert metrics["events_accepted"] >= 1 and metrics["correlation_runs"] >= 1 and metrics["http_2xx"] >= 1


def test_retention_deletes_old_data_and_keeps_recent(world, owner_conn):
    resident = world.actor(3)
    old = world.clock.now()
    resident.submit([resident.wifi_lost(last_wifi_rssi_dbm=-50)])
    world.tick(3700)  # the incident expires
    world.clock.advance(31 * 86400)
    resident.submit([resident.heartbeat()])
    deleted = retention.purge(world.db, world.clock.now())
    assert deleted["connectivity_events"] == 1
    remaining = owner_conn.execute("SELECT event_type FROM nw.connectivity_events").fetchall()
    assert [r["event_type"] for r in remaining] == ["HEARTBEAT"]
    assert len(world.incidents()) == 1  # incident summaries are kept for 90 days
    world.clock.advance(60 * 86400)
    deleted = retention.purge(world.db, world.clock.now())
    assert deleted["incidents"] == 1 and world.incidents() == []
    assert old < world.clock.now() - timedelta(days=90)


def test_expired_invitations_are_marked_by_retention(world, owner_conn):
    world.admin.post("/api/v1/admin/invitations", {"expires_in_hours": 1})
    world.clock.advance(7200)
    retention.purge(world.db, world.clock.now())
    assert owner_conn.execute("SELECT status FROM nw.invitations").fetchone()["status"] == "revoked"


def test_maintenance_tick_runs_exclusively_and_survives_errors(world, monkeypatch):
    pipeline = world.client.app.state.pipeline
    assert run_tick_exclusively(pipeline) is True
    monkeypatch.setattr(pipeline, "tick", lambda: (_ for _ in ()).throw(RuntimeError("boom")))
    assert run_tick_exclusively(pipeline) is False  # logged and counted, not raised


def test_migrations_are_complete_and_immutable(database_urls, tmp_path, monkeypatch):
    assert migrate.run(database_urls["owner"], check_only=True) == []  # nothing pending
    assert migrate.run(database_urls["owner"]) == []  # running again is a no-op
    # A changed, already-applied migration is refused.
    copy = tmp_path / "migrations"
    copy.mkdir()
    for path in migrate.MIGRATIONS_DIR.glob("*.sql"):
        (copy / path.name).write_text(path.read_text() + "\n-- edited after the fact\n")
    monkeypatch.setattr(migrate, "MIGRATIONS_DIR", copy)
    with pytest.raises(RuntimeError, match="modified after it was applied"):
        migrate.run(database_urls["owner"])


def test_schema_has_the_required_tables_keys_and_timestamps(owner_conn):
    tables = {r["tablename"] for r in owner_conn.execute("SELECT tablename FROM pg_tables WHERE schemaname = 'nw'")}
    required = {
        "users",
        "neighborhoods",
        "households",
        "household_members",
        "invitations",
        "devices",
        "device_sessions",
        "connectivity_events",
        "incident_candidates",
        "incidents",
        "incident_households",
        "notification_tokens",
        "notification_deliveries",
        "admin_audit_logs",
        "system_configuration",
    }
    assert required <= tables
    for table in required:
        columns = {
            r["column_name"]: r["data_type"]
            for r in owner_conn.execute(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'nw' AND table_name = %s",
                [table],
            )
        }
        assert columns.get("created_at") == columns.get("updated_at") == "timestamp with time zone", table
        primary = owner_conn.execute(
            "SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS type FROM pg_index i JOIN pg_attribute a"
            " ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) WHERE i.indrelid = %s::regclass AND i.indisprimary",
            [f"nw.{table}"],
        ).fetchall()
        assert [p["type"] for p in primary] == ["uuid"], table
