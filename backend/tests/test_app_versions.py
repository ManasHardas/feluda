"""Minimum and latest app versions: the server refuses outdated apps with 426 and says where to update."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from tests.conftest import make_settings
from watchdog.app_versions import is_below, parse_header, parse_version
from watchdog.enums import Platform
from watchdog.main import create_app


def test_parsing():
    assert parse_version("1.2.3") == (1, 2, 3)
    assert parse_version("1.2") is None and parse_version("v1.2.3") is None
    assert parse_header("ios/0.10.0") == (Platform.IOS, (0, 10, 0))
    assert parse_header("android/1.0.2 (17)") == (Platform.ANDROID, (1, 0, 2))
    assert parse_header("") is None and parse_header("windows/1.0.0") is None and parse_header("ios/1.0") is None
    assert is_below((0, 9, 9), "0.10.0") and not is_below((0, 10, 0), "0.10.0") and not is_below((0, 1, 0), "")


def test_settings_refuse_a_malformed_minimum():
    with pytest.raises(ValueError):
        make_settings(min_app_version_ios="1.0")


@pytest.fixture
def versioned(db, clock, senders):
    settings = make_settings(min_app_version_ios="1.0.0", latest_app_version_ios="1.2.0", latest_app_version_android="0.3.0")
    with TestClient(create_app(settings, clock=clock, senders=senders, database=db)) as client:
        yield client


def test_compatibility_endpoint(versioned):
    old = versioned.get("/api/v1/app/compatibility", params={"platform": "ios", "version": "0.9.0"}).json()
    assert old == {
        "platform": "ios",
        "version": "0.9.0",
        "supported": False,
        "update_available": True,
        "minimum_version": "1.0.0",
        "latest_version": "1.2.0",
        "update_url": "https://testflight.example.test/join",
    }
    current = versioned.get("/api/v1/app/compatibility", params={"platform": "ios", "version": "1.2.0"}).json()
    assert current["supported"] and not current["update_available"]
    android = versioned.get("/api/v1/app/compatibility", params={"platform": "android", "version": "0.1.0"}).json()
    assert android["supported"] and android["update_available"] and android["minimum_version"] is None
    assert versioned.get("/api/v1/app/compatibility", params={"platform": "ios", "version": "1.0"}).status_code == 422


def test_outdated_app_is_refused_everywhere_except_exempt_paths(versioned):
    old = {"X-App-Version": "ios/0.9.0 (5)"}
    refused = versioned.get("/api/v1/households/me", headers=old)
    assert refused.status_code == 426 and refused.json()["error"]["code"] == "update_required"
    assert refused.headers["x-request-id"]
    assert versioned.post("/api/v1/enroll", json={}, headers=old).status_code == 426
    assert versioned.get("/api/v1/monitoring/health", headers=old).status_code == 200
    assert versioned.get("/join", headers=old).status_code == 200
    # Supported, other platform, missing or unreadable header: normal handling (here 401, no credential).
    for headers in ({"X-App-Version": "ios/1.0.0"}, {"X-App-Version": "android/0.0.1"}, {}, {"X-App-Version": "garbage"}):
        assert versioned.get("/api/v1/households/me", headers=headers).status_code == 401


def test_no_minimum_configured_accepts_every_version(client):
    assert client.get("/api/v1/households/me", headers={"X-App-Version": "ios/0.0.1"}).status_code == 401
    body = client.get("/api/v1/app/compatibility", params={"platform": "android", "version": "0.0.1"}).json()
    assert body["supported"] and not body["update_available"] and body["latest_version"] is None
