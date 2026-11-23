import pytest

pytestmark = pytest.mark.db


def test_health_and_openapi(client):
    r = client.get("/api/v1/monitoring/health")
    assert r.status_code == 200 and r.json()["database"] == "ok"
    assert client.get("/health").json() == {"status": "ok"}
    spec = client.get("/openapi.json").json()
    assert "/api/v1/connectivity/events" in spec["paths"]


def test_seeded_resident_can_read_household(world):
    actor = world.actor(3)
    r = actor.get("/api/v1/households/me")
    assert r.status_code == 200, r.text
    assert r.json()["membership_status"] == "active"
    r = actor.submit([actor.heartbeat()], reported_via="wifi")
    assert r.status_code == 200, r.text
    assert r.json()["outcomes"][0]["result"] == "accepted"
    status = actor.get("/api/v1/connectivity/status").json()
    assert status["status_color"] == "GREEN"
    assert status["household_monitoring_status"] == "CONFIRMED_ACTIVE"
