"""Integration tests: invitation link, enrolling a phone without any sign-in, leaving."""

from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from watchdog.invitation_tokens import hash_token
from watchdog.ops import create_neighborhood, set_neighborhood_status

from .conftest import Admin, enroll_body

pytestmark = pytest.mark.db


def bearer(response) -> dict[str, str]:
    assert response.status_code == 201, response.text
    return {"Authorization": f"Bearer {response.json()['device_token']}"}


def enroll(client, token, street="Willow Way", house_number=None, **overrides):
    return client.post("/api/v1/enroll", json=enroll_body(token, street, house_number, **overrides))


def test_full_enrolment_journey_asks_only_for_the_street(world, client):
    r = world.admin.post("/api/v1/admin/invitations", {})
    assert r.status_code == 201, r.text
    url = r.json()["invitation_url"]
    assert "/join#t=" in url  # token in the fragment, never in the path or query
    token = url.split("#t=")[1]

    verified = client.post("/api/v1/auth/verify-invitation", json={"invitation_token": token})
    assert verified.status_code == 200 and verified.json()["neighborhood_name"] == "Maple Hollow (simulated)"

    device_id = str(uuid.uuid4())
    body = enroll_body(
        token,
        "willow WAY ",
        device_id=device_id,
        platform="ios",
        os_version="18.2",
        capabilities={"background_monitoring": "opportunistic", "can_identify_home_network": True},
    )
    assert set(body) - {"device_id", "platform", "os_version", "app_version", "capabilities"} == {
        "invitation_token",
        "street_name",
        "privacy_acknowledged",
    }  # the resident types a street and nothing else
    enrolled = client.post("/api/v1/enroll", json=body)
    headers = bearer(enrolled)
    household = enrolled.json()["household"]
    assert household["street_name"] == "willow way" and household["house_number"] is None
    assert household["membership_status"] == "active" and household["device_count"] == 1
    assert enrolled.json()["device"]["device_id"] == device_id

    me = client.get("/api/v1/households/me", headers=headers)
    assert me.status_code == 200 and me.json() == household

    # The phone can update what it reports about itself, and only itself.
    update = {"device_id": device_id, "platform": "ios", "os_version": "18.3", "app_version": "0.1.1", "capabilities": {}}
    again = client.post("/api/v1/devices/register", headers=headers, json=update)
    assert again.status_code == 200 and again.json()["os_version"] == "18.3"
    other = client.post("/api/v1/devices/register", headers=headers, json={**update, "device_id": str(uuid.uuid4())})
    assert other.status_code == 409

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


def test_nothing_personal_is_stored_or_accepted(world, client, owner_conn):
    token = world.admin.invite()
    # An e-mail address (or any other extra field) is refused, not silently stored.
    assert client.post("/api/v1/enroll", json={**enroll_body(token), "email": "someone@example.org"}).status_code == 422
    columns = {
        r["column_name"]
        for r in owner_conn.execute("SELECT column_name FROM information_schema.columns WHERE table_schema = 'nw'")
    }
    assert not columns & {"email", "auth_subject", "password", "phone", "name_of_person", "full_name"}
    # There is no sign-in endpoint of any kind.
    paths = set(client.app.openapi()["paths"])
    assert "/api/v1/enroll" in paths
    assert not [p for p in paths if any(word in p for word in ("otp", "login", "signin", "password", "account"))]


def test_street_is_mandatory_and_house_number_is_optional(world, client):
    token = world.admin.invite()
    missing = enroll_body(token)
    del missing["street_name"]
    assert client.post("/api/v1/enroll", json=missing).status_code == 422
    assert enroll(client, token, street="   ").status_code == 422
    assert enroll(client, token, street="!!!").status_code == 422

    without = enroll(client, token, street="Willow Way")  # the refused attempts did not use up the link
    assert without.status_code == 201 and without.json()["household"]["house_number"] is None
    blank = enroll(client, world.admin.invite(), street="Birch Row", house_number="  ")
    assert blank.status_code == 201 and blank.json()["household"]["house_number"] is None
    numbered = enroll(client, world.admin.invite(), street="Willow Way", house_number=" 12-B ")
    assert numbered.status_code == 201 and numbered.json()["household"]["house_number"] == "12b"


def test_one_link_is_one_household_whatever_is_typed(world, client, owner_conn):
    family = world.admin.invite(max_uses=4)
    first = enroll(client, family, street="Willow Way")
    second = enroll(client, family, street="Willow way")
    # Typing other addresses with the same link cannot create more households (and so cannot fake a
    # multi-household disruption from one link).
    third = enroll(client, family, street="Elm Street", house_number="1")
    fourth = enroll(client, family, street="Oak Lane", house_number="2")
    ids = {r.json()["household"]["household_id"] for r in (first, second, third, fourth)}
    assert len(ids) == 1 and fourth.json()["household"]["device_count"] == 4
    assert owner_conn.execute("SELECT count(*) AS n FROM nw.households WHERE invitation_id IS NOT NULL").fetchone()["n"] == 1
    # Another home on the same street, enrolled with its own link, is a different household.
    neighbour = enroll(client, world.admin.invite(), street="Willow Way")
    assert neighbour.json()["household"]["household_id"] not in ids


def test_another_link_cannot_attach_a_phone_to_an_enrolled_home(world, client, owner_conn):
    enroll(client, world.admin.invite(), street="Cedar Row", house_number="9")
    intruder = enroll(client, world.admin.invite(), street="cedar row", house_number="9")
    assert intruder.status_code == 409 and intruder.json()["error"]["code"] == "household_already_registered"
    assert (
        owner_conn.execute("SELECT count(*) AS n FROM nw.security_events WHERE kind = 'enrolment_refused'").fetchone()["n"] == 1
    )
    # The same goes for the seeded homes, which are already taking part.
    seeded = world.hood.households[0]
    r = enroll(client, world.admin.invite(), street=seeded.street_name, house_number=seeded.house_number)
    assert r.status_code == 409
    # The organizer can issue a link for that household.
    bound = world.admin.invite(household_id=str(seeded.household_id))
    joined = enroll(client, bound, street="anything typed here")
    assert joined.status_code == 201 and joined.json()["household"]["household_id"] == str(seeded.household_id)


def test_invitation_use_limit_and_storage_as_a_hash(world, client, owner_conn):
    token = world.admin.invite(max_uses=2)
    stored = owner_conn.execute("SELECT token_hash FROM nw.invitations ORDER BY created_at DESC LIMIT 1").fetchone()
    assert stored["token_hash"] == hash_token(token) and token not in stored["token_hash"]
    assert enroll(client, token).status_code == 201
    assert enroll(client, token).status_code == 201
    third = enroll(client, token)
    assert third.status_code == 404 and third.json()["error"]["code"] == "invitation_invalid"
    assert client.post("/api/v1/auth/verify-invitation", json={"invitation_token": token}).status_code == 404
    listed = world.admin.get("/api/v1/admin/invitations").json()[0]
    assert listed["status"] == "used" and listed["use_count"] == 2 and listed["max_uses"] == 2
    # Device secrets are stored only as hashes too.
    for row in owner_conn.execute("SELECT secret_hash FROM nw.device_credentials"):
        assert len(row["secret_hash"]) == 64


def test_retrying_enrolment_does_not_use_the_link_twice(world, client, owner_conn):
    token = world.admin.invite(max_uses=1)
    body = enroll_body(token, "Willow Way")
    first = client.post("/api/v1/enroll", json=body)
    retry = client.post("/api/v1/enroll", json=body)  # the first answer never reached the phone
    assert first.status_code == 201 and retry.status_code == 201
    assert retry.json()["device_token"] != first.json()["device_token"]
    assert client.get("/api/v1/households/me", headers=bearer(retry)).status_code == 200
    assert client.get("/api/v1/households/me", headers=bearer(first)).status_code == 401  # the old secret is replaced
    assert owner_conn.execute("SELECT use_count FROM nw.invitations").fetchone()["use_count"] == 1
    # A different phone cannot ride on the used link, and a known device id cannot be claimed with another link.
    assert enroll(client, token).status_code == 404
    stolen = client.post("/api/v1/enroll", json={**body, "invitation_token": world.admin.invite()})
    assert stolen.status_code == 409 and stolen.json()["error"]["code"] == "device_conflict"


def test_invitation_expiry_and_revocation(world, client):
    token = world.admin.invite(expires_in_hours=1)
    world.clock.advance(3601)
    assert enroll(client, token).status_code == 404
    token = world.admin.invite()
    invitation_id = world.admin.get("/api/v1/admin/invitations").json()[0]["invitation_id"]
    assert world.admin.post("/api/v1/admin/invitations/revoke", {"invitation_id": invitation_id}).status_code == 200
    assert enroll(client, token).status_code == 404
    assert enroll(client, "x" * 43).status_code == 404  # unknown tokens get the same answer


def test_privacy_acknowledgement_is_required(world, client):
    r = client.post("/api/v1/enroll", json=enroll_body(world.admin.invite(), privacy_acknowledged=False))
    assert r.status_code == 422


def test_without_an_invitation_there_is_no_access(client, world):
    assert client.get("/api/v1/households/me").status_code == 401
    for secret in ("", "guess", "x" * 43, world.hood.households[0].device_secret[:-1]):
        r = client.get("/api/v1/households/me", headers={"Authorization": f"Bearer {secret}"})
        assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"


def test_operator_creates_a_neighborhood_and_invites_the_first_phone(db, client):
    neighborhood_id = create_neighborhood(db, "Oak Ridge")
    admin = Admin(client, neighborhood_id)
    assert admin.get("/api/v1/admin/neighborhood/summary").json()["name"] == "Oak Ridge"
    headers = bearer(enroll(client, admin.invite(), street="Oak Ridge Road"))
    me = client.get("/api/v1/households/me", headers=headers).json()
    assert me["neighborhood_id"] == str(neighborhood_id)
    # Disabling the neighborhood locks everyone out of neighborhood data.
    assert set_neighborhood_status(db, neighborhood_id, "disabled")
    assert client.get("/api/v1/neighborhoods/me", headers=headers).status_code == 403
    assert enroll(client, admin.invite()).status_code == 404


def test_leaving_removes_the_phone_and_everything_it_reported(world, owner_conn):
    resident = world.actor(4)
    assert resident.submit([resident.heartbeat(), resident.wifi_lost()]).status_code == 200
    assert resident.post("/api/v1/households/leave").status_code == 200
    h = resident.household
    for table, column, value in (
        ("users", "id", h.user_id),
        ("devices", "id", h.device_id),
        ("device_credentials", "device_id", h.device_id),
        ("household_members", "user_id", h.user_id),
        ("connectivity_events", "household_id", h.household_id),
    ):
        count = owner_conn.execute(f"SELECT count(*) AS n FROM nw.{table} WHERE {column} = %s", [value]).fetchone()["n"]
        assert count == 0, table
    household = owner_conn.execute("SELECT membership_status FROM nw.households WHERE id = %s", [h.household_id]).fetchone()
    assert household["membership_status"] == "approved"  # the bare address stays so the home can join again
    assert resident.get("/api/v1/households/me").status_code == 401
    assert resident.submit([resident.heartbeat()]).status_code == 401


def test_when_the_last_phone_leaves_the_typed_address_is_deleted(world, client, owner_conn):
    family = world.admin.invite(max_uses=2)
    first, second = enroll(client, family, street="Hidden Lane", house_number="7"), enroll(client, family)
    household_id = first.json()["household"]["household_id"]
    assert client.post("/api/v1/households/leave", headers=bearer(first)).status_code == 200
    assert owner_conn.execute("SELECT count(*) AS n FROM nw.households WHERE id = %s", [household_id]).fetchone()["n"] == 1
    assert client.post("/api/v1/households/leave", headers=bearer(second)).status_code == 200
    assert owner_conn.execute("SELECT count(*) AS n FROM nw.households WHERE id = %s", [household_id]).fetchone()["n"] == 0
    assert (
        owner_conn.execute("SELECT count(*) AS n FROM nw.households WHERE normalized_street_name = 'hidden ln'").fetchone()["n"]
        == 0
    )


def test_a_phone_can_be_removed_by_its_household_only(world, client):
    family = world.admin.invite(max_uses=2)
    first, second = enroll(client, family), enroll(client, family)
    mine, theirs = bearer(first), bearer(second)
    listed = client.get("/api/v1/devices", headers=mine).json()
    assert {d["device_id"] for d in listed} == {first.json()["device"]["device_id"], second.json()["device"]["device_id"]}
    outsider = world.actor(2)
    r = outsider.post("/api/v1/devices/revoke", {"device_id": second.json()["device"]["device_id"]})
    assert r.status_code == 404
    r = client.post("/api/v1/devices/revoke", headers=mine, json={"device_id": second.json()["device"]["device_id"]})
    assert r.status_code == 200
    assert client.get("/api/v1/households/me", headers=theirs).status_code == 401
    assert client.get("/api/v1/households/me", headers=mine).status_code == 200


def test_join_page_carries_no_secrets_and_a_strict_policy(client):
    page = client.get("/join")
    assert page.status_code == 200 and "Neighborhood Watchdog" in page.text
    assert 'type="email"' not in page.text and "<form" not in page.text
    csp = page.headers["content-security-policy"]
    assert "default-src 'none'" in csp and "unsafe-inline" not in csp
    assert page.headers["referrer-policy"] == "no-referrer"
    assert client.get("/privacy").status_code == 200
    home = client.get("/")
    assert home.status_code == 200 and "invitation link" in home.text and "{{" not in home.text


def test_clock_moves_do_not_invalidate_a_phone(world):
    resident = world.actor(1)
    world.clock.advance(timedelta(days=200).total_seconds())
    assert resident.get("/api/v1/households/me").status_code == 200  # a device secret does not expire
