"""Integration tests: invitation, registration, sign-in, device registration, leaving, deletion."""

from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from watchdog.authentication.dev_provider import DevAuthState
from watchdog.invitation_tokens import hash_token
from watchdog.ops import create_neighborhood, set_neighborhood_status

from .conftest import token_for

pytestmark = pytest.mark.db


def invite(world, **body) -> str:
    r = world.admin.post("/api/v1/admin/invitations", body)
    assert r.status_code == 201, r.text
    url = r.json()["invitation_url"]
    assert "/join#t=" in url  # token in the fragment, never in the path or query
    return url.split("#t=")[1]


def approve(world, number="77", street="Willow Way", **extra) -> str:
    r = world.admin.post("/api/v1/admin/households", {"house_number": number, "street_name": street, **extra})
    assert r.status_code == 201, r.text
    return r.json()["household_id"]


def registration(token, email="newcomer@example.org", number="77", street="Willow Way", **extra):
    return {
        "invitation_token": token,
        "email": email,
        "house_number": number,
        "street_name": street,
        "privacy_acknowledged": True,
        **extra,
    }


def sign_in(client, email: str) -> dict[str, str]:
    assert client.post("/auth/v1/otp", json={"email": email}).status_code == 200
    code = client.app.state.dev_auth.last_code[email]
    session = client.post("/auth/v1/verify", json={"type": "email", "email": email, "token": code}).json()
    return {"Authorization": f"Bearer {session['access_token']}"}


def test_full_onboarding_journey(world, client, senders):
    approve(world, isp="xfinity", latitude=33.0501, longitude=-97.0501)
    token = invite(world)

    verified = client.post("/api/v1/auth/verify-invitation", json={"invitation_token": token})
    assert verified.status_code == 200 and verified.json()["neighborhood_name"] == "Maple Hollow (simulated)"

    registered = client.post("/api/v1/auth/register", json=registration(token, street="willow WAY ", display_label="Blue door"))
    assert registered.status_code == 201, registered.text
    assert registered.json()["android_install_url"].startswith("https://")

    headers = sign_in(client, "newcomer@example.org")
    me = client.get("/api/v1/households/me", headers=headers)
    assert me.status_code == 200, me.text
    assert me.json()["street_name"] == "willow way" and me.json()["role"] == "resident"
    assert me.json()["display_label"] == "Blue door" and me.json()["member_count"] == 1

    device_id = str(uuid.uuid4())
    device = client.post(
        "/api/v1/devices/register",
        headers=headers,
        json={
            "device_id": device_id,
            "platform": "ios",
            "os_version": "18.2",
            "app_version": "0.1.0",
            "capabilities": {"background_monitoring": "opportunistic", "can_identify_home_network": True},
        },
    )
    assert device.status_code == 200, device.text
    # Registering again is idempotent and updates versions.
    again = client.post(
        "/api/v1/devices/register",
        headers=headers,
        json={"device_id": device_id, "platform": "ios", "os_version": "18.3", "app_version": "0.1.1", "capabilities": {}},
    )
    assert again.status_code == 200 and again.json()["os_version"] == "18.3"

    push = client.post(
        "/api/v1/notifications/register",
        headers=headers,
        json={"device_id": device_id, "provider": "apns", "token": "a" * 64, "apns_environment": "sandbox"},
    )
    assert push.status_code == 200, push.text
    wrong = client.post(
        "/api/v1/notifications/register", headers=headers, json={"device_id": device_id, "provider": "fcm", "token": "b" * 64}
    )
    assert wrong.status_code == 422

    assert client.get("/api/v1/neighborhoods/me", headers=headers).json()["participating_households"] == 25
    status = client.get("/api/v1/connectivity/status", headers=headers).json()
    assert status["status_color"] == "GREY" and status["household_monitoring_status"] == "NEVER_REPORTED"


def test_invitation_is_single_use_and_stored_only_as_a_hash(world, client, owner_conn):
    approve(world)
    approve(world, number="79")
    token = invite(world)
    assert client.post("/api/v1/auth/register", json=registration(token)).status_code == 201
    row = owner_conn.execute(
        "SELECT token_hash, status FROM nw.invitations WHERE token_hash = %s", [hash_token(token)]
    ).fetchone()
    assert row["status"] == "used"
    dump = str(owner_conn.execute("SELECT * FROM nw.invitations").fetchall()) + str(
        owner_conn.execute("SELECT * FROM nw.admin_audit_logs").fetchall()
    )
    assert token not in dump
    # Second use: same generic answer as an unknown token.
    reuse = client.post("/api/v1/auth/register", json=registration(token, email="second@example.org", number="79"))
    unknown = client.post("/api/v1/auth/register", json=registration("A" * 43, email="second@example.org", number="79"))
    assert reuse.status_code == unknown.status_code == 404
    assert reuse.json()["error"]["code"] == unknown.json()["error"]["code"] == "invitation_invalid"
    assert reuse.json()["error"]["message"] == unknown.json()["error"]["message"]
    assert client.post("/api/v1/auth/verify-invitation", json={"invitation_token": token}).status_code == 404


def test_invitation_expiry_and_revocation(world, client):
    approve(world)
    expiring = invite(world, expires_in_hours=1)
    world.clock.advance(3601)
    assert client.post("/api/v1/auth/verify-invitation", json={"invitation_token": expiring}).status_code == 404
    assert client.post("/api/v1/auth/register", json=registration(expiring)).status_code == 404

    created = world.admin.post("/api/v1/admin/invitations", {})
    token = created.json()["invitation_url"].split("#t=")[1]
    assert (
        world.admin.post("/api/v1/admin/invitations/revoke", {"invitation_id": created.json()["invitation_id"]}).status_code
        == 200
    )
    assert client.post("/api/v1/auth/register", json=registration(token)).status_code == 404
    listing = {i["invitation_id"]: i["status"] for i in world.admin.get("/api/v1/admin/invitations").json()}
    assert listing[created.json()["invitation_id"]] == "revoked"


def test_address_must_be_on_the_approved_list(world, client):
    approve(world)
    token = invite(world)
    r = client.post("/api/v1/auth/register", json=registration(token, number="9999"))
    assert r.status_code == 422 and r.json()["error"]["code"] == "address_not_recognized"
    # The failed attempt did not consume the invitation.
    assert client.post("/api/v1/auth/register", json=registration(token)).status_code == 201


def test_wrong_address_guesses_lock_the_invitation(world, client):
    approve(world)
    token = invite(world)
    client.app.state.limiter.reset()
    codes = []
    for n in range(8):
        client.app.state.limiter.reset()  # isolate the per-invitation limit from the per-client limit
        codes.append(client.post("/api/v1/auth/register", json=registration(token, number=str(500 + n))).status_code)
    assert codes[:5] == [422] * 5 and codes[-1] == 404  # after too many misses it reads as invalid
    client.app.state.limiter.reset()
    assert client.post("/api/v1/auth/register", json=registration(token)).status_code == 404


def test_duplicate_household_registration_needs_a_household_invitation(world, client):
    household_id = approve(world)
    assert client.post("/api/v1/auth/register", json=registration(invite(world))).status_code == 201
    second = client.post("/api/v1/auth/register", json=registration(invite(world), email="partner@example.org"))
    assert second.status_code == 409 and second.json()["error"]["code"] == "household_already_registered"
    bound = invite(world, household_id=household_id)
    assert client.post("/api/v1/auth/register", json=registration(bound, email="partner@example.org")).status_code == 201
    headers = sign_in(client, "partner@example.org")
    assert client.get("/api/v1/households/me", headers=headers).json()["member_count"] == 2
    # A household-bound invitation works only for that household.
    approve(world, number="81")
    other_bound = invite(world, household_id=household_id)
    r = client.post("/api/v1/auth/register", json=registration(other_bound, email="x@example.org", number="81"))
    assert r.status_code == 422
    # One e-mail address cannot hold two memberships.
    r = client.post("/api/v1/auth/register", json=registration(invite(world), number="81"))
    assert r.status_code == 409


def test_privacy_acknowledgement_is_required(world, client):
    approve(world)
    body = registration(invite(world))
    body["privacy_acknowledged"] = False
    assert client.post("/api/v1/auth/register", json=body).status_code == 422


def test_join_from_inside_the_app(world, client):
    approve(world)
    token = invite(world)
    headers = sign_in(client, "walkup@example.org")
    assert client.get("/api/v1/households/me", headers=headers).json()["error"]["code"] == "not_a_member"
    joined = client.post(
        "/api/v1/households/join",
        headers=headers,
        json={
            "invitation_token": token,
            "house_number": "77",
            "street_name": "Willow Way",
            "privacy_acknowledged": True,
            "isp": "att",
        },
    )
    assert joined.status_code == 200, joined.text
    assert joined.json()["isp"] == "att" and joined.json()["membership_status"] == "active"
    assert client.get("/api/v1/households/me", headers=headers).status_code == 200


def test_signing_in_without_an_invitation_gives_nothing(client, world):
    headers = sign_in(client, "stranger@example.org")
    for path in (
        "/api/v1/households/me",
        "/api/v1/neighborhoods/me",
        "/api/v1/incidents",
        "/api/v1/connectivity/status",
        "/api/v1/devices",
        "/api/v1/admin/households",
    ):
        r = client.get(path, headers=headers)
        assert r.status_code == 403 and r.json()["error"]["code"] == "not_a_member", path


def test_refresh_tokens_rotate(client):
    client.post("/auth/v1/otp", json={"email": "rotate@example.org"})
    code = client.app.state.dev_auth.last_code["rotate@example.org"]
    assert (
        client.post(
            "/auth/v1/verify", json={"email": "rotate@example.org", "token": "000000" if code != "000000" else "111111"}
        ).status_code
        == 403
    )
    session = client.post("/auth/v1/verify", json={"email": "rotate@example.org", "token": code}).json()
    assert (
        client.post("/auth/v1/verify", json={"email": "rotate@example.org", "token": code}).status_code == 403
    )  # code is single-use
    first = client.post("/auth/v1/token?grant_type=refresh_token", json={"refresh_token": session["refresh_token"]})
    assert first.status_code == 200 and first.json()["refresh_token"] != session["refresh_token"]
    assert (
        client.post("/auth/v1/token?grant_type=refresh_token", json={"refresh_token": session["refresh_token"]}).status_code
        == 400
    )


def test_operator_creates_a_neighborhood_and_first_admin_signs_in(db, client):
    neighborhood_id = create_neighborhood(db, "Oak Ridge", "Organizer@Example.Org", "12", "Oak Ridge Road")
    headers = sign_in(client, "organizer@example.org")
    me = client.get("/api/v1/households/me", headers=headers).json()
    assert me["role"] == "admin" and me["neighborhood_id"] == str(neighborhood_id)
    assert client.get("/api/v1/admin/neighborhood/summary", headers=headers).json()["name"] == "Oak Ridge"
    # Disabling the neighborhood locks everyone out of neighborhood data.
    assert set_neighborhood_status(db, neighborhood_id, "disabled")
    assert client.get("/api/v1/neighborhoods/me", headers=headers).status_code == 403


def test_leaving_removes_devices_and_telemetry(world, owner_conn):
    resident = world.actor(4)
    assert resident.submit([resident.heartbeat(), resident.wifi_lost()]).status_code == 200
    assert resident.post("/api/v1/households/leave").status_code == 200
    h = resident.household
    assert owner_conn.execute("SELECT count(*) AS n FROM nw.devices WHERE user_id = %s", [h.user_id]).fetchone()["n"] == 0
    assert (
        owner_conn.execute(
            "SELECT count(*) AS n FROM nw.connectivity_events WHERE household_id = %s", [h.household_id]
        ).fetchone()["n"]
        == 0
    )
    household = owner_conn.execute("SELECT membership_status FROM nw.households WHERE id = %s", [h.household_id]).fetchone()
    assert household["membership_status"] == "approved"  # address stays approved for a future invitation
    assert resident.get("/api/v1/households/me").json()["error"]["code"] == "not_a_member"
    assert resident.submit([resident.heartbeat()]).status_code == 403


def test_account_deletion_erases_identity(world, owner_conn):
    resident = world.actor(6)
    resident.submit([resident.heartbeat()])
    assert resident.client.delete("/api/v1/account", headers=resident.headers).status_code == 200
    user = owner_conn.execute(
        "SELECT email, auth_subject, status FROM nw.users WHERE id = %s", [resident.household.user_id]
    ).fetchone()
    assert user == {"email": None, "auth_subject": None, "status": "deleted"}
    assert resident.get("/api/v1/households/me").status_code == 403
    # The operator is told to remove the sign-in record at the identity provider (no admin key configured here).
    pending = owner_conn.execute("SELECT detail FROM nw.security_events WHERE kind = 'identity_deletion_pending'").fetchone()
    assert pending["detail"]["auth_subject"] == DevAuthState.subject_for(resident.household.email)


def test_device_limit_and_revocation(world):
    resident = world.actor(2)
    body = {"platform": "android", "os_version": "15", "app_version": "0.1.0", "capabilities": {}}
    codes = [resident.post("/api/v1/devices/register", {"device_id": str(uuid.uuid4()), **body}).status_code for _ in range(5)]
    assert codes == [200, 200, 200, 200, 409]  # the seeded device plus four more reaches the limit of five
    assert resident.post("/api/v1/devices/revoke", {"device_id": str(resident.household.device_id)}).status_code == 200
    r = resident.submit([resident.heartbeat()])
    assert r.status_code == 403 and r.json()["error"]["code"] == "device_revoked"
    r = resident.post("/api/v1/devices/register", {"device_id": str(resident.household.device_id), **body})
    assert r.status_code == 403 and r.json()["error"]["code"] == "device_revoked"


def test_expired_access_token_is_rejected(world, client):
    h = world.hood.households[1]
    from datetime import UTC, datetime

    old = token_for(h.auth_subject, h.email, now=datetime.now(UTC) - timedelta(hours=2), ttl=3600)
    r = client.get("/api/v1/households/me", headers={"Authorization": f"Bearer {old}"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"


def test_join_page_carries_no_secrets_and_a_strict_policy(client):
    page = client.get("/join")
    assert page.status_code == 200 and "Neighborhood Watchdog" in page.text
    csp = page.headers["content-security-policy"]
    assert "default-src 'none'" in csp and "unsafe-inline" not in csp
    assert page.headers["referrer-policy"] == "no-referrer"
    assert client.get("/privacy").status_code == 200
