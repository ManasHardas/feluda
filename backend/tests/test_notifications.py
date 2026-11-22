"""Notification routing, duplicate suppression, retries, expiry, preferences and provider payloads."""

from __future__ import annotations

import json
from datetime import timedelta

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa

from watchdog.notifications.content import compose
from watchdog.notifications.providers import ApnsSender, FcmSender, PushMessage, SendResult

from .conftest import World

pytestmark = pytest.mark.db


def correlated_disruption(world: World):
    members = world.hood.by_isp("att")[:3] + world.hood.by_isp("xfinity")[:3]
    for household in members:
        actor = world.actor(household)
        assert actor.submit([actor.wifi_lost()]).status_code == 200
    return members


def deliver(world: World):
    return world.client.app.state.pipeline.deliver(world.clock.now())


def by_status(world: World) -> dict[str, int]:
    out: dict[str, int] = {}
    for d in world.deliveries():
        out[d["status"]] = out.get(d["status"], 0) + 1
    return out


def test_level_two_alert_reaches_every_participating_device_once(world, senders):
    world.add_push_tokens()
    correlated_disruption(world)
    deliver(world)
    assert by_status(world) == {"sent": 24}
    assert len(senders["fcm"].sent) == 12 and len(senders["apns"].sent) == 12
    token, message = senders["fcm"].sent[0]
    assert message.title == "NEIGHBORHOOD WIRELESS ALERT" and message.alert_level == 2
    assert message.data["kind"] == "alert" and message.data["classification"] == "CORRELATED_NEIGHBORHOOD_DISRUPTION"
    # Running everything again changes nothing: no duplicates.
    world.tick()
    world.tick(10)
    deliver(world)
    assert len(world.deliveries()) == 24 and len(senders["fcm"].sent) + len(senders["apns"].sent) == 24


def test_household_warning_goes_only_to_that_household_and_only_with_at_home_evidence(world, senders):
    world.add_push_tokens()
    ios_resident = world.actor(next(h for h in world.hood.households if h.platform == "ios"))
    ios_resident.submit([ios_resident.wifi_lost()])  # no signal data: could be a phone leaving the house
    world.tick(400)
    deliver(world)
    assert world.deliveries() == []  # shown in the app, not pushed
    assert len(world.incidents("household")) == 1

    android = world.actor(next(h for h in world.hood.households if h.platform == "android" and h.index > 1))
    android.submit([android.wifi_lost(last_wifi_rssi_dbm=-48)])  # strong signal just before the loss
    world.tick(60)
    assert world.deliveries() == []  # not yet: give a blip time to recover
    world.tick(200)
    deliver(world)
    [delivery] = world.deliveries()
    assert delivery["user_id"] == android.household.user_id and delivery["alert_level"] == 1 and delivery["status"] == "sent"
    assert "Wireless interference is possible but not confirmed" in delivery["body"]


def test_quick_recovery_sends_nothing(world, senders):
    world.add_push_tokens()
    android = world.actor(2)
    android.submit([android.wifi_lost(last_wifi_rssi_dbm=-48)])
    world.clock.advance(40)
    android.submit([android.wifi_restored()])
    world.tick(600)
    deliver(world)
    assert world.deliveries() == [] and world.incidents("household")[0]["status"] == "resolved"


def test_resolution_notification_follows_recovery(world, senders):
    world.add_push_tokens()
    members = correlated_disruption(world)
    deliver(world)
    world.clock.advance(90)
    for household in members[:4]:  # 4 of 6 recover: above the 60 % needed
        actor = world.actor(household)
        actor.submit([actor.wifi_restored()])
    [incident] = world.incidents("neighborhood")
    assert incident["status"] == "resolved" and incident["resolved_at"] is not None
    deliver(world)
    resolutions = [d for d in world.deliveries() if d["kind"] == "resolution"]
    assert len(resolutions) == 24 and all(d["status"] == "sent" for d in resolutions)
    assert "cause of the earlier disruption has not been determined" in resolutions[0]["body"]
    world.tick(30)
    assert len([d for d in world.deliveries() if d["kind"] == "resolution"]) == 24  # sent once


def test_unresolved_incident_expires_without_claiming_recovery(world, senders):
    world.add_push_tokens()
    correlated_disruption(world)
    deliver(world)
    world.tick(61 * 60)
    [incident] = world.incidents("neighborhood")
    assert incident["status"] == "expired"
    assert [d for d in world.deliveries() if d["kind"] == "resolution"] == []
    viewer = world.actor(20)
    listed = viewer.get("/api/v1/incidents?status=all").json()["incidents"][0]
    assert "outcome unknown" in listed["summary"] and listed["status_color"] == "GREY"


def test_retry_with_backoff_then_success(world, senders):
    world.add_push_tokens()
    senders["fcm"].results = [SendResult(ok=False, error="fcm 503 UNAVAILABLE", retryable=True)]
    correlated_disruption(world)
    deliver(world)
    assert by_status(world) == {"sent": 23, "pending": 1}
    pending = next(d for d in world.deliveries() if d["status"] == "pending")
    assert pending["attempts"] == 1 and pending["last_error"] == "fcm 503 UNAVAILABLE"
    assert pending["next_attempt_at"] == world.clock.now() + timedelta(seconds=30)
    deliver(world)  # too early: nothing happens
    assert by_status(world)["pending"] == 1
    world.clock.advance(31)
    deliver(world)
    assert by_status(world) == {"sent": 24}
    assert next(d for d in world.deliveries() if d["id"] == pending["id"])["attempts"] == 2


def test_permanent_failures_are_tracked_and_never_reported_as_sent(world, senders):
    world.add_push_tokens()
    senders["apns"].results = [
        SendResult(ok=False, error="apns 410 Unregistered", invalid_token=True),
        SendResult(ok=False, error="apns 403 BadCertificate", retryable=False),
    ]
    correlated_disruption(world)
    deliver(world)
    assert by_status(world) == {"sent": 22, "failed": 2}
    with world.db.service() as conn:
        assert conn.execute("SELECT count(*) AS n FROM nw.notification_tokens WHERE status = 'invalid'").fetchone()["n"] == 1
    failures = world.admin.get("/api/v1/admin/notifications/failures").json()
    assert sorted(f["last_error"] for f in failures) == ["apns 403 BadCertificate", "apns 410 Unregistered"]
    assert world.admin.get("/api/v1/admin/neighborhood/summary").json()["notification_failures_last_7_days"] == 2


def test_retries_stop_at_the_attempt_limit_and_at_expiry(world, senders):
    world.add_push_tokens()
    senders["fcm"].results = [SendResult(ok=False, error="fcm 500", retryable=True)] * 50
    correlated_disruption(world)
    for _ in range(12):
        deliver(world)
        world.clock.advance(300)
    statuses = by_status(world)
    assert statuses["sent"] == 12 and statuses.get("pending", 0) == 0
    assert statuses.get("failed", 0) + statuses.get("expired", 0) == 12
    assert max(d["attempts"] for d in world.deliveries()) <= 5


def test_unconfigured_provider_is_recorded_not_faked(world, senders):
    world.add_push_tokens()
    senders["apns"].configured = False
    correlated_disruption(world)
    deliver(world)
    assert by_status(world) == {"sent": 12, "skipped_not_configured": 12}
    assert senders["apns"].sent == []
    skipped = next(d for d in world.deliveries() if d["status"] == "skipped_not_configured")
    assert "not configured" in skipped["last_error"] and skipped["provider_message_id"] is None


def test_preferences_and_quiet_hours(world, senders):
    world.add_push_tokens()
    opted_out, quiet = world.actor(10), world.actor(12)
    r = opted_out.client.put(
        "/api/v1/notifications/preferences",
        headers=opted_out.headers,
        json={"household_alerts": True, "neighborhood_alerts": False},
    )
    assert r.status_code == 200
    r = quiet.client.put(
        "/api/v1/notifications/preferences",
        headers=quiet.headers,
        json={
            "quiet_hours_enabled": True,
            "quiet_hours_start": "13:00:00",
            "quiet_hours_end": "07:00:00",
            "timezone": "America/Chicago",
        },
    )
    assert r.status_code == 200 and quiet.get("/api/v1/notifications/preferences").json()["timezone"] == "America/Chicago"
    assert (
        quiet.client.put(
            "/api/v1/notifications/preferences", headers=quiet.headers, json={"timezone": "Mars/Olympus"}
        ).status_code
        == 422
    )

    # Level 1 at 15:00 Chicago time (20:00 UTC) falls inside the quiet hours of that resident.
    quiet.submit([quiet.wifi_lost(last_wifi_rssi_dbm=-45)])
    world.tick(200)
    deliver(world)
    [warning] = world.deliveries()
    assert warning["status"] == "skipped_quiet_hours" and warning["user_id"] == quiet.household.user_id

    # A level-2 neighborhood alert is critical: quiet hours do not apply, the opt-out does.
    correlated_disruption(world)
    deliver(world)
    alerts = {d["user_id"]: d["status"] for d in world.deliveries() if d["alert_level"] == 2}
    assert alerts[quiet.household.user_id] == "sent"
    assert alerts[opted_out.household.user_id] == "skipped_preference"


def test_isp_outage_notifies_only_affected_households(world, senders):
    world.add_push_tokens()
    affected = world.hood.by_isp("xfinity")[:5]
    for household in affected:
        actor = world.actor(household)
        actor.submit(
            [
                actor.event(
                    "INTERNET_REACHABILITY_CHANGED",
                    "WIFI_CONNECTED_NO_INTERNET",
                    wifi_associated=True,
                    home_network_match=True,
                    internet_reachable=False,
                    cellular_available=True,
                )
            ]
        )
    deliver(world)
    recipients = {d["user_id"] for d in world.deliveries()}
    assert recipients == {h.user_id for h in affected}
    assert all(d["title"] == "Possible internet outage nearby" and d["alert_level"] == 1 for d in world.deliveries())


def test_wording_never_asserts_jamming_and_never_promises_delivery():
    for kind in ("alert", "escalation", "resolution"):
        for classification in (
            "HOUSEHOLD_CONNECTIVITY_ISSUE",
            "POSSIBLE_ISP_OUTAGE",
            "POSSIBLE_ROUTER_OR_POWER_FAILURE",
            "POSSIBLE_WIRELESS_INTERFERENCE",
            "CORRELATED_NEIGHBORHOOD_DISRUPTION",
            "HIGH_PRIORITY_SUSPICIOUS_DISRUPTION",
        ):
            for level in (1, 2, 3):
                for scope in ("household", "neighborhood"):
                    title, body = compose(kind, classification, level, 5, scope)
                    text = f"{title} {body}".lower()
                    if "jamming" in text:
                        assert "does not confirm deliberate jamming" in text
                    for banned in ("confirmed jamming", "jammer detected", "you are safe", "guaranteed", "police have been"):
                        assert banned not in text


# ------------------------------------------------------------------ provider payloads (fake HTTP transport)

MESSAGE = PushMessage(
    title="NEIGHBORHOOD WIRELESS ALERT",
    body="Body",
    data={"incident_id": "i-1", "alert_level": "2", "kind": "alert", "classification": "CORRELATED_NEIGHBORHOOD_DISRUPTION"},
    alert_level=2,
    expires_at_epoch=4102444800,
    collapse_key="i-1",
)


def test_fcm_request_shape_and_error_mapping():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()
    account = json.dumps(
        {
            "client_email": "svc@example-project.iam.gserviceaccount.com",
            "private_key": pem,
            "token_uri": "https://oauth2.example.test/token",
        }
    )
    seen: list[httpx.Request] = []
    replies = [
        httpx.Response(200, json={"name": "projects/p/messages/123"}),
        httpx.Response(404, json={"error": {"status": "UNREGISTERED"}}),
        httpx.Response(503, json={"error": {"status": "UNAVAILABLE"}}),
    ]

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.host == "oauth2.example.test":
            assertion = dict(p.split("=") for p in request.content.decode().split("&"))["assertion"]
            claims = jwt.decode(assertion, key.public_key(), algorithms=["RS256"], audience="https://oauth2.example.test/token")
            assert claims["scope"] == "https://www.googleapis.com/auth/firebase.messaging"
            return httpx.Response(200, json={"access_token": "ya29.test", "expires_in": 3600})
        return replies.pop(0)

    sender = FcmSender("example-project", account, client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert sender.configured
    ok = sender.send("device-token", MESSAGE)
    assert ok == SendResult(ok=True, message_id="projects/p/messages/123")
    request = seen[1]
    assert str(request.url) == "https://fcm.googleapis.com/v1/projects/example-project/messages:send"
    assert request.headers["authorization"] == "Bearer ya29.test"
    body = json.loads(request.content)["message"]
    assert body["token"] == "device-token" and body["android"]["priority"] == "HIGH"
    assert body["android"]["notification"]["channel_id"] == "neighborhood_alerts" and body["data"]["incident_id"] == "i-1"
    gone = sender.send("device-token", MESSAGE)
    assert not gone.ok and gone.invalid_token and not gone.retryable
    busy = sender.send("device-token", MESSAGE)
    assert not busy.ok and busy.retryable and not busy.invalid_token
    assert len([r for r in seen if r.url.host == "oauth2.example.test"]) == 1  # access token is cached
    assert not FcmSender("", "").configured


def test_apns_request_shape_and_error_mapping():
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()
    seen: list[httpx.Request] = []
    replies = [
        httpx.Response(200, headers={"apns-id": "ABC-123"}),
        httpx.Response(410, json={"reason": "Unregistered"}),
        httpx.Response(429, json={"reason": "TooManyRequests"}),
    ]

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return replies.pop(0)

    sender = ApnsSender(
        "KEYID12345",
        "TEAMID1234",
        pem,
        "org.neighborhoodwatchdog.ios",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    ok = sender.send("abcdef0123", MESSAGE, "sandbox")
    assert ok == SendResult(ok=True, message_id="ABC-123")
    request = seen[0]
    assert str(request.url) == "https://api.sandbox.push.apple.com/3/device/abcdef0123"
    assert request.headers["apns-topic"] == "org.neighborhoodwatchdog.ios" and request.headers["apns-push-type"] == "alert"
    assert request.headers["apns-priority"] == "10" and request.headers["apns-expiration"] == "4102444800"
    provider_token = request.headers["authorization"].split()[1]
    assert jwt.get_unverified_header(provider_token) == {"alg": "ES256", "kid": "KEYID12345", "typ": "JWT"}
    assert jwt.decode(provider_token, key.public_key(), algorithms=["ES256"])["iss"] == "TEAMID1234"
    payload = json.loads(request.content)
    assert payload["aps"]["alert"]["title"] == "NEIGHBORHOOD WIRELESS ALERT"
    assert payload["aps"]["interruption-level"] == "time-sensitive" and payload["incident_id"] == "i-1"
    gone = sender.send("abcdef0123", MESSAGE, "production")
    assert str(seen[1].url).startswith("https://api.push.apple.com/") and gone.invalid_token and not gone.retryable
    busy = sender.send("abcdef0123", MESSAGE)
    assert busy.retryable and not busy.invalid_token
    assert not ApnsSender("", "", "", "").configured
