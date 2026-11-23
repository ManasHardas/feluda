"""Security tests: authorization, neighborhood isolation, row-level security, abuse and input handling."""

from __future__ import annotations

import re
import subprocess
import uuid
from datetime import timedelta
from pathlib import Path

import psycopg
import pytest

from watchdog.settings import Settings

from .conftest import Actor, Admin, enroll_body, make_settings

pytestmark = pytest.mark.db
REPO = Path(__file__).resolve().parents[2]

PROTECTED_GETS = [
    "/api/v1/households/me",
    "/api/v1/neighborhoods/me",
    "/api/v1/connectivity/status",
    "/api/v1/incidents",
    "/api/v1/devices",
    "/api/v1/notifications/preferences",
    "/api/v1/admin/households",
    "/api/v1/admin/neighborhood/summary",
    "/api/v1/admin/audit-logs",
    "/api/v1/monitoring/metrics",
]


# ------------------------------------------------------------------ authentication


def test_every_protected_endpoint_requires_a_token(client, world):
    for path in PROTECTED_GETS:
        assert client.get(path).status_code == 401, path
        assert client.get(path, headers={"Authorization": "Bearer not-a-token"}).status_code == 401, path
    assert client.post("/api/v1/connectivity/events", json={}).status_code == 401
    assert client.post("/api/v1/admin/invitations", json={}).status_code == 401


def test_every_route_except_the_public_ones_declares_authentication(app):
    public = {
        "/health",
        "/api/v1/monitoring/health",
        "/api/v1/auth/verify-invitation",
        "/api/v1/enroll",
        "/join",
        "/privacy",
        "/robots.txt",
        "/openapi.json",
        "/docs",
        "/docs/oauth2-redirect",
        "/internal/tick",  # guarded by its own shared secret; see test_internal_tick_needs_its_secret
    }
    from fastapi.routing import APIRoute

    for route in app.routes:
        if not isinstance(route, APIRoute) or route.path in public:
            continue
        flat: set[str] = set()
        stack = list(route.dependant.dependencies)
        while stack:
            dep = stack.pop()
            flat.add(getattr(dep.call, "__name__", type(dep.call).__name__))
            stack.extend(dep.dependencies)
        # Every non-public route checks a device secret, or the operator secret for administration.
        expected = "get_operator" if "/admin/" in route.path or route.path.endswith("/metrics") else "get_member_any"
        assert expected in flat, route.path


def test_internal_tick_needs_its_secret(db, clock, senders, world):
    from fastapi.testclient import TestClient

    from watchdog.main import create_app

    # Not configured: the endpoint does not exist as far as callers can tell, whatever they send.
    assert world.client.post("/internal/tick").status_code == 404
    assert world.client.post("/internal/tick", headers={"Authorization": "Bearer anything"}).status_code == 404
    secret = "s" * 40
    app = create_app(make_settings(internal_tick_token=secret), clock=clock, senders=senders, database=db)
    with TestClient(app) as scheduled:
        assert scheduled.post("/internal/tick").status_code == 404
        assert scheduled.post("/internal/tick", headers={"Authorization": "Bearer wrong"}).status_code == 404
        resident = world.hood.households[3]
        for other in (resident.device_secret, world.admin.token):
            assert scheduled.post("/internal/tick", headers={"Authorization": f"Bearer {other}"}).status_code == 404
        ok = scheduled.post("/internal/tick", headers={"Authorization": f"Bearer {secret}"})
        assert ok.status_code == 200 and ok.json() == {"ran": True}
    with pytest.raises(ValueError, match="INTERNAL_TICK_TOKEN"):
        make_settings(environment="production", internal_tick_token="short")


def test_device_secrets_and_the_operator_secret_are_not_interchangeable(client, world):
    resident = world.hood.households[1]
    # A phone's secret opens nothing in administration, and the operator secret is not a phone.
    r = client.get("/api/v1/admin/households", headers={"Authorization": f"Bearer {resident.device_secret}"})
    assert r.status_code == 401
    assert client.get("/api/v1/households/me", headers={"Authorization": f"Bearer {world.admin.token}"}).status_code == 401
    # Near misses and other schemes are refused.
    for header in (f"Bearer {resident.device_secret}x", f"Basic {resident.device_secret}", resident.device_secret):
        assert client.get("/api/v1/households/me", headers={"Authorization": header}).status_code == 401


def test_administration_is_disabled_without_an_operator_secret(db, clock, senders, world):
    from fastapi.testclient import TestClient

    from watchdog.main import create_app

    app = create_app(make_settings(admin_api_token=""), clock=clock, senders=senders, database=db)
    with TestClient(app) as closed:
        for secret in ("", "anything", world.admin.token):
            r = closed.get("/api/v1/admin/households", headers={"Authorization": f"Bearer {secret}"})
            assert r.status_code == 401


def test_production_settings_refuse_unsafe_configuration():
    with pytest.raises(ValueError, match="ADMIN_API_TOKEN"):
        make_settings(environment="production", admin_api_token="short")
    with pytest.raises(ValueError, match="placeholder"):
        make_settings(environment="production", admin_api_token="local-development-admin-token-not-for-production")
    with pytest.raises(ValueError, match="https"):
        make_settings(environment="staging", public_base_url="http://insecure.example")
    with pytest.raises(ValueError, match="IP_HASH_SALT"):
        make_settings(environment="production", ip_hash_salt="change-me")
    with pytest.raises(ValueError, match="IP_HASH_SALT"):
        Settings(_env_file=None, environment="production", public_base_url="https://a.example")
    ok = make_settings(environment="production")
    assert ok.is_production_like


def test_api_documentation_is_not_served_in_production(db, clock, senders):
    from fastapi.testclient import TestClient

    from watchdog.main import create_app

    app = create_app(make_settings(environment="production"), clock=clock, senders=senders, database=db)
    with TestClient(app) as production:
        assert production.get("/docs").status_code == 404 and production.get("/openapi.json").status_code == 404
        assert production.post("/auth/v1/otp", json={"email": "a@example.org"}).status_code == 404  # no sign-in exists
        assert "max-age" in production.get("/health").headers["strict-transport-security"]


# ------------------------------------------------------------------ household and neighborhood isolation


def test_client_supplied_household_or_neighborhood_ids_are_refused(world, second_hood):
    resident = world.actor(3)
    body = {
        "device_id": str(resident.household.device_id),
        "reported_via": "wifi",
        "sent_at": world.clock.now().isoformat(),
        "events": [resident.heartbeat()],
    }
    for extra in (
        {"household_id": str(second_hood.households[0].household_id)},
        {"neighborhood_id": str(second_hood.neighborhood_id)},
    ):
        assert resident.post("/api/v1/connectivity/events", {**body, **extra}).status_code == 422
    event = {**resident.heartbeat(), "household_id": str(second_hood.households[0].household_id)}
    assert resident.post("/api/v1/connectivity/events", {**body, "events": [event]}).status_code == 422


def test_cannot_submit_events_for_someone_elses_device(world, second_hood):
    resident = world.actor(3)
    for foreign in (world.hood.households[4].device_id, second_hood.households[0].device_id, uuid.uuid4()):
        r = resident.submit([resident.heartbeat()], device_id=foreign)
        assert r.status_code == 404 and r.json()["error"]["code"] == "device_not_registered"
    with world.db.service() as conn:
        assert conn.execute("SELECT count(*) AS n FROM nw.connectivity_events").fetchone()["n"] == 0


def test_cross_neighborhood_incidents_are_invisible(world, second_hood, client, clock):
    # A correlated disruption in the first neighborhood...
    for household in world.hood.by_isp("att")[:3] + world.hood.by_isp("xfinity")[:3]:
        actor = world.actor(household)
        assert actor.submit([actor.wifi_lost()]).status_code == 200
    [incident] = world.incidents("neighborhood")
    insider = world.actor(10)
    assert len(insider.get("/api/v1/incidents").json()["incidents"]) == 1
    assert insider.get(f"/api/v1/incidents/{incident['id']}").status_code == 200
    # ...is invisible from the second neighborhood, by listing and by direct id.
    outsider = Actor(client, clock, second_hood.households[1])
    assert outsider.get("/api/v1/incidents?status=all").json()["incidents"] == []
    assert outsider.get(f"/api/v1/incidents/{incident['id']}").status_code == 404
    assert outsider.get("/api/v1/neighborhoods/me").json()["open_incident_count"] == 0
    assert outsider.get("/api/v1/connectivity/status").json()["open_incident_ids"] == []


def test_incident_responses_never_contain_addresses_or_household_identifiers(world):
    members = world.hood.by_isp("att")[:3] + world.hood.by_isp("xfinity")[:3]
    for household in members:
        actor = world.actor(household)
        actor.submit([actor.wifi_lost()])
    viewer = world.actor(15)
    listing = viewer.get("/api/v1/incidents").text
    [incident] = world.incidents("neighborhood")
    detail = viewer.get(f"/api/v1/incidents/{incident['id']}").text
    for household in world.hood.households:
        for secret in (
            str(household.household_id),
            str(household.device_id),
            str(household.user_id),
            household.device_secret,
            household.street_name.lower(),
        ):
            assert secret not in listing and secret not in detail
    assert "latitude" not in detail and "longitude" not in detail
    assert "metres of each other" in detail  # approximate scope only


def test_admin_of_one_neighborhood_cannot_touch_another(world, second_hood, client, clock):
    foreign_admin = Admin(client, second_hood.neighborhood_id)
    target = world.hood.households[5]
    for path in ("/api/v1/admin/households/suspend", "/api/v1/admin/households/remove", "/api/v1/admin/households/reinstate"):
        assert foreign_admin.post(path, {"household_id": str(target.household_id)}).status_code == 404
    assert foreign_admin.post("/api/v1/admin/devices/revoke", {"device_id": str(target.device_id)}).status_code == 404
    assert foreign_admin.post("/api/v1/admin/invitations", {"household_id": str(target.household_id)}).status_code == 404
    listed = {h["household_id"] for h in foreign_admin.get("/api/v1/admin/households").json()}
    assert listed == {str(h.household_id) for h in second_hood.households}
    assert world.actor(target).get("/api/v1/households/me").json()["membership_status"] == "active"
    # With more than one neighborhood the operator must name one, and it must exist.
    unnamed = {"Authorization": f"Bearer {world.admin.token}"}
    assert client.get("/api/v1/admin/households", headers=unnamed).status_code == 404
    for bogus in (str(uuid.uuid4()), "not-a-uuid"):
        assert client.get("/api/v1/admin/households", headers={**unnamed, "X-Neighborhood-Id": bogus}).status_code == 404


def test_residents_cannot_use_admin_endpoints(world):
    resident = world.actor(7)
    assert resident.get("/api/v1/admin/households").status_code == 401
    assert resident.get("/api/v1/admin/configuration").status_code == 401
    assert resident.post("/api/v1/admin/invitations", {}).status_code == 401
    assert (
        resident.post(
            "/api/v1/admin/households/suspend", {"household_id": str(world.hood.households[8].household_id)}
        ).status_code
        == 401
    )
    assert (
        resident.client.put(
            "/api/v1/admin/configuration", headers=resident.headers, json={"values": {"min_affected_households": 2}}
        ).status_code
        == 401
    )
    assert resident.get("/api/v1/monitoring/metrics").status_code == 401
    assert world.hood.households[8].household_id  # untouched
    assert world.actor(8).get("/api/v1/households/me").json()["membership_status"] == "active"


def test_suspended_household_loses_access(world):
    target = world.actor(9)
    assert (
        world.admin.post(
            "/api/v1/admin/households/suspend", {"household_id": str(target.household.household_id), "reason": "test"}
        ).status_code
        == 200
    )
    r = target.submit([target.heartbeat()])
    assert r.status_code == 403 and r.json()["error"]["code"] == "household_suspended"
    assert target.get("/api/v1/incidents").status_code == 403
    assert target.get("/api/v1/households/me").json()["membership_status"] == "suspended"  # can still see why
    assert (
        world.admin.post("/api/v1/admin/households/reinstate", {"household_id": str(target.household.household_id)}).status_code
        == 200
    )
    assert target.submit([target.heartbeat()]).status_code == 200


# ------------------------------------------------------------------ row-level security, straight at the database


def as_role(urls, role: str, user_id=None):
    conn = psycopg.connect(urls["app"], autocommit=False, row_factory=psycopg.rows.dict_row)
    conn.execute(f"SET ROLE {role}")
    conn.execute("SELECT set_config('app.user_id', %s, false)", [str(user_id) if user_id else ""])
    return conn


def test_login_role_has_no_access_of_its_own(world, database_urls):
    with psycopg.connect(database_urls["app"]) as conn, pytest.raises(psycopg.errors.InsufficientPrivilege):
        conn.execute("SELECT * FROM nw.households")


def test_rls_resident_sees_only_own_rows(world, second_hood, database_urls):
    me, other = world.hood.households[3], world.hood.households[4]
    for household in (me, other, second_hood.households[0]):
        actor = Actor(world.client, world.clock, household)
        assert actor.submit([actor.heartbeat()], reported_via="wifi").status_code == 200
    with as_role(database_urls, "nw_api", me.user_id) as conn:
        assert {r["id"] for r in conn.execute("SELECT id FROM nw.households")} == {me.household_id}
        assert {r["id"] for r in conn.execute("SELECT id FROM nw.users")} == {me.user_id}
        assert {r["id"] for r in conn.execute("SELECT id FROM nw.devices")} == {me.device_id}
        assert {r["household_id"] for r in conn.execute("SELECT household_id FROM nw.connectivity_events")} == {me.household_id}
        assert {r["id"] for r in conn.execute("SELECT id FROM nw.neighborhoods")} == {world.hood.neighborhood_id}
        assert conn.execute("SELECT count(*) AS n FROM nw.household_members").fetchone()["n"] == 1
        # Tables a resident has no business reading are closed entirely.
        for table in (
            "invitations",
            "admin_audit_logs",
            "security_events",
            "system_configuration",
            "notification_deliveries",
            "incident_candidates",
            "rate_limit_counters",
        ):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
                conn.execute(f"SELECT * FROM nw.{table}")


def test_rls_resident_cannot_write_outside_own_household(world, database_urls):
    me, other = world.hood.households[3], world.hood.households[4]
    event = (
        "INSERT INTO nw.connectivity_events (id, device_id, household_id, neighborhood_id, sequence, event_type,"
        " connectivity_state, occurred_at, received_at, reported_via, platform, os_version, app_version, confidence)"
        " VALUES (%s, %s, %s, %s, 1, 'WIFI_LOST', 'WIFI_DISCONNECTED', now(), now(), 'cellular', 'android', '15', '0.1.0', 'OBSERVED')"
    )
    with as_role(database_urls, "nw_api", me.user_id) as conn:
        # forged household, forged device, and own device attributed to another household
        for device, household in (
            (other.device_id, other.household_id),
            (other.device_id, me.household_id),
            (me.device_id, other.household_id),
        ):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
                conn.execute(event, [uuid.uuid4(), device, household, world.hood.neighborhood_id])
        with conn.transaction():
            conn.execute(event, [uuid.uuid4(), me.device_id, me.household_id, world.hood.neighborhood_id])  # the legitimate case
        # membership, role and status are not writable
        for statement in (
            "UPDATE nw.household_members SET role = 'admin'",
            "UPDATE nw.households SET membership_status = 'active'",
            "UPDATE nw.households SET neighborhood_id = gen_random_uuid()",
            "INSERT INTO nw.household_members (household_id, user_id) VALUES (gen_random_uuid(), gen_random_uuid())",
            "DELETE FROM nw.connectivity_events",
            "UPDATE nw.incidents SET alert_level = 3",
            "INSERT INTO nw.incidents (neighborhood_id, scope, classification, alert_level, explanation, started_at,"
            " last_event_at, affected_household_count, active_household_count, correlation_window_s)"
            " VALUES (gen_random_uuid(), 'neighborhood', 'X', 3, 'x', now(), now(), 9, 9, 60)",
        ):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
                conn.execute(statement)
        # allowed columns of another household are still protected by the row policy
        with conn.transaction():
            assert conn.execute("UPDATE nw.households SET display_label = 'x' WHERE id = %s", [other.household_id]).rowcount == 0


def test_rls_without_a_user_context_shows_nothing(world, database_urls):
    with as_role(database_urls, "nw_api") as conn:
        for table in ("households", "users", "devices", "connectivity_events", "incidents", "neighborhoods"):
            assert conn.execute(f"SELECT count(*) AS n FROM nw.{table}").fetchone()["n"] == 0, table


def test_correlation_role_cannot_read_personal_data(world, database_urls):
    with as_role(database_urls, "nw_correlation") as conn:
        assert conn.execute("SELECT count(*) AS n FROM (SELECT id, isp, latitude FROM nw.households) h").fetchone()["n"] == 24
        for statement in (
            "SELECT normalized_street_name FROM nw.households",
            "SELECT normalized_house_number FROM nw.households",
            "SELECT display_label FROM nw.households",
            "SELECT * FROM nw.households",
            "SELECT * FROM nw.users",
            "SELECT * FROM nw.device_credentials",
            "SELECT * FROM nw.notification_tokens",
            "SELECT * FROM nw.invitations",
            "SELECT user_id FROM nw.devices",
            "UPDATE nw.households SET isp = 'att'",
            "DELETE FROM nw.connectivity_events",
        ):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
                conn.execute(statement)


def test_every_table_has_row_level_security_enabled(owner_conn):
    rows = owner_conn.execute(
        "SELECT c.relname, c.relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
        " WHERE n.nspname = 'nw' AND c.relkind = 'r'"
    ).fetchall()
    assert len(rows) >= 18
    assert [r["relname"] for r in rows if not r["relrowsecurity"]] == []
    # Nothing is granted to PUBLIC (which Supabase's anon and authenticated roles would inherit).
    public = owner_conn.execute(
        "SELECT table_name FROM information_schema.role_table_grants WHERE table_schema = 'nw' AND grantee = 'PUBLIC'"
    ).fetchall()
    assert public == []


# ------------------------------------------------------------------ replay, duplicates, malformed input


def test_duplicate_and_replayed_events_are_stored_once(world):
    resident = world.actor(3)
    lost = resident.wifi_lost()
    assert resident.submit([lost, lost]).status_code == 200  # the same event twice in one batch is stored once
    replay = resident.submit([lost]).json()["outcomes"]
    assert [o["result"] for o in replay] == ["duplicate"]
    world.clock.advance(3600)
    assert [o["result"] for o in resident.submit([lost]).json()["outcomes"]] == ["duplicate"]
    with world.db.service() as conn:
        assert conn.execute("SELECT count(*) AS n FROM nw.connectivity_events").fetchone()["n"] == 1
    assert len(world.incidents()) == 1  # one event never produces a second incident


def test_another_households_event_id_cannot_be_overwritten(world):
    a, b = world.actor(3), world.actor(4)
    event = a.wifi_lost()
    assert a.submit([event]).json()["outcomes"][0]["result"] == "accepted"
    forged = {**b.heartbeat(), "event_id": event["event_id"]}
    assert b.submit([forged]).json()["outcomes"][0]["result"] == "duplicate"
    with world.db.service() as conn:
        row = conn.execute(
            "SELECT household_id, event_type FROM nw.connectivity_events WHERE id = %s", [event["event_id"]]
        ).fetchone()
    assert row == {"household_id": a.household.household_id, "event_type": "WIFI_LOST"}


def test_timestamps_outside_the_accepted_range_are_rejected(world):
    resident = world.actor(3)
    future = resident.wifi_lost(at=world.clock.now() + timedelta(minutes=10))
    ancient = resident.wifi_lost(at=world.clock.now() - timedelta(days=30))
    slightly_ahead = resident.heartbeat(at=world.clock.now() + timedelta(seconds=30))  # normal clock drift
    outcomes = {o["event_id"]: o for o in resident.submit([future, ancient, slightly_ahead]).json()["outcomes"]}
    assert outcomes[future["event_id"]]["reason"] == "timestamp_in_future"
    assert outcomes[ancient["event_id"]]["reason"] == "too_old"
    assert outcomes[slightly_ahead["event_id"]]["result"] == "accepted"
    assert world.incidents() == []


@pytest.mark.parametrize(
    "mutation",
    [
        {"event_type": "JAMMER_CONFIRMED"},
        {"connectivity_state": "BROKEN"},
        {"event_id": "not-a-uuid"},
        {"occurred_at": "yesterday"},
        {"occurred_at": "2026-03-10T20:00:00"},
        {"sequence": -1},
        {"confidence": "CERTAIN"},
        {"diagnostics": {"last_wifi_rssi_dbm": 40}},
        {"diagnostics": {"browsing_history": ["example.org"]}},
        {"diagnostics": {"ssid": "MyHomeWiFi"}},
        {"os_version": "x" * 200},
        {"app_version": "<script>"},
    ],
)
def test_malformed_events_are_rejected(world, mutation):
    resident = world.actor(3)
    r = resident.submit([{**resident.heartbeat(), **mutation}])
    assert r.status_code == 422, r.text
    assert r.json()["error"]["code"] == "validation_error"
    for value in mutation.values():  # submitted values are not echoed back
        if isinstance(value, str) and len(value) > 8:
            assert value not in r.text


def test_oversized_batches_and_garbage_bodies_are_rejected(world):
    resident = world.actor(3)
    assert resident.submit([resident.heartbeat() for _ in range(51)]).status_code == 422
    assert resident.submit([]).status_code == 422
    r = resident.client.post("/api/v1/connectivity/events", headers=resident.headers, content=b"{not json")
    assert r.status_code == 422
    r = resident.client.post("/api/v1/connectivity/events", headers=resident.headers, json=["a", "list"])
    assert r.status_code == 422


def test_request_flooding_is_rate_limited(world, client):
    resident = world.actor(3)
    codes = [resident.submit([resident.heartbeat()]).status_code for _ in range(70)]
    assert codes.count(429) >= 5 and codes[:50] == [200] * 50
    limited = resident.submit([resident.heartbeat()])
    assert limited.json()["error"]["code"] == "rate_limited" and int(limited.headers["retry-after"]) >= 1


def test_invitation_guessing_is_rate_limited_and_indistinguishable(world, client):
    answers = [
        client.post("/api/v1/auth/verify-invitation", json={"invitation_token": uuid.uuid4().hex + uuid.uuid4().hex})
        for _ in range(25)
    ]
    codes = [a.status_code for a in answers]
    assert codes[:20] == [404] * 20 and set(codes[20:]) == {429}
    assert len({a.text.split('"request_id"')[0] for a in answers[:20]}) == 1  # identical bodies apart from the request id
    assert client.post("/api/v1/auth/verify-invitation", json={"invitation_token": "short"}).status_code in (422, 429)


def test_household_hourly_event_budget_is_enforced(world, owner_conn):
    owner_conn.execute(
        "INSERT INTO nw.system_configuration (neighborhood_id, key, value) VALUES (%s, 'max_events_per_household_per_hour', '30')",
        [world.hood.neighborhood_id],
    )
    resident = world.actor(3)
    results = []
    for _ in range(2):
        outcomes = resident.submit([resident.heartbeat() for _ in range(20)]).json()
        results += [o["result"] for o in outcomes["outcomes"]]
    assert results.count("accepted") == 30 and results.count("rejected") == 10
    assert outcomes["retry_after_seconds"] and outcomes["retry_after_seconds"] <= 3601


# ------------------------------------------------------------------ secrets and logging


def test_logs_never_contain_secrets_or_addresses(world, client, caplog):
    import logging

    caplog.set_level(logging.DEBUG)
    token = world.admin.invite(max_uses=2)
    enrolled = client.post("/api/v1/enroll", json=enroll_body(token, "Secretive Crescent", "4242"))
    assert enrolled.status_code == 201
    device_secret = enrolled.json()["device_token"]
    assert client.get("/api/v1/households/me", headers={"Authorization": f"Bearer {device_secret}"}).status_code == 200
    client.post("/api/v1/enroll", json={**enroll_body(token, "Secretive Crescent", "4242"), "unexpected": "field"})
    resident = world.actor(3)
    resident.submit([resident.wifi_lost()])
    world.admin.get("/api/v1/admin/households")
    text = "\n".join(str(r.__dict__) for r in caplog.records if r.name.startswith("watchdog"))
    for secret in (token, device_secret, resident.household.device_secret, world.admin.token, "secretive", "4242"):
        assert secret not in text.lower() and secret not in text


def test_repository_contains_no_committed_secrets():
    if not (REPO / ".git").exists():
        pytest.skip("not running inside the git repository (for example in the test container)")
    files = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard"], cwd=REPO, capture_output=True, text=True, check=True
    ).stdout.split()
    patterns = [
        re.compile(r"-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        re.compile(r"eyJ[A-Za-z0-9_-]{20,}\.eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}"),  # a real JWT (e.g. a service-role key)
        re.compile(r"AIza[0-9A-Za-z_-]{35}"),  # Google API key
        re.compile(r"\"private_key\"\s*:\s*\"-----BEGIN"),
        re.compile(r"sb_secret_[A-Za-z0-9_]{20,}"),
    ]
    forbidden_names = re.compile(
        r"(google-services\.json|GoogleService-Info\.plist|\.p8|\.p12|\.jks|\.keystore|\.mobileprovision)$"
    )
    offenders = []
    for name in files:
        path = REPO / name
        if forbidden_names.search(name) or (path.name.startswith(".env") and path.name != ".env.example"):
            offenders.append(name)
            continue
        if not path.is_file() or path.stat().st_size > 2_000_000 or "/build/" in name or name.endswith((".png", ".jar", ".lock")):
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if name.startswith("backend/tests/"):
            continue  # tests build throwaway tokens at run time
        if any(p.search(content) for p in patterns):
            offenders.append(name)
    assert offenders == []
