"""Test fixtures.

Database tests run against a real PostgreSQL (row-level security cannot be tested otherwise). A
fresh database is created per test session and dropped afterwards. The application connects with a
non-owner login role, as it should in production.

    TEST_DATABASE_URL  administrative connection used to create the test database
                       (default: the docker-compose database on localhost:54329)
"""

from __future__ import annotations

import os
import secrets
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

import jwt
import psycopg
import pytest
from fastapi.testclient import TestClient
from psycopg import sql
from psycopg.conninfo import conninfo_to_dict, make_conninfo

from watchdog import metrics, migrate
from watchdog.clock import FixedClock
from watchdog.db import Database
from watchdog.main import create_app
from watchdog.notifications.providers import PushMessage, SendResult
from watchdog.seed import SeededHousehold, SeededNeighborhood, seed_neighborhood
from watchdog.settings import Settings

ADMIN_URL = os.environ.get("TEST_DATABASE_URL", "postgresql://watchdog:watchdog_local_dev_only@localhost:54329/postgres")
JWT_SECRET = "test-secret-test-secret-test-secret-123456"
START = datetime(2026, 3, 10, 20, 0, 0, tzinfo=UTC)


def _database_available() -> bool:
    try:
        with psycopg.connect(ADMIN_URL, connect_timeout=3):
            return True
    except psycopg.Error:
        return False


DB_AVAILABLE = _database_available()


def pytest_collection_modifyitems(config, items):
    if DB_AVAILABLE:
        return
    if os.environ.get("CI") or os.environ.get("REQUIRE_DATABASE"):
        raise pytest.UsageError("PostgreSQL is required for the test suite but is not reachable (TEST_DATABASE_URL).")
    skip = pytest.mark.skip(reason="PostgreSQL not reachable; start it with `make docker-up`")
    for item in items:
        if "db" in item.keywords:
            item.add_marker(skip)


@pytest.fixture(scope="session")
def database_urls():
    suffix = secrets.token_hex(4)
    name = f"nwtest_{suffix}"
    login = f"nw_test_login_{suffix}"  # unique per session, so a crashed earlier session cannot block this one
    password = secrets.token_hex(12)
    base = conninfo_to_dict(ADMIN_URL)
    with psycopg.connect(ADMIN_URL, autocommit=True) as conn:
        conn.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(name)))
    owner_url = make_conninfo(**{**base, "dbname": name})
    migrate.run(owner_url)
    with psycopg.connect(owner_url, autocommit=True) as conn:
        # The application's login role: no privileges of its own, may only switch to the three restricted roles.
        role = sql.Identifier(login)
        conn.execute(sql.SQL("CREATE ROLE {} LOGIN NOINHERIT PASSWORD {}").format(role, sql.Literal(password)))
        conn.execute(sql.SQL("GRANT nw_api, nw_correlation, nw_service TO {}").format(role))
        conn.execute(sql.SQL("GRANT CONNECT ON DATABASE {} TO {}").format(sql.Identifier(name), role))
    app_url = make_conninfo(**{**base, "dbname": name, "user": login, "password": password})
    yield {"owner": owner_url, "app": app_url}
    with psycopg.connect(ADMIN_URL, autocommit=True) as conn:
        conn.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))
        conn.execute(sql.SQL("DROP ROLE IF EXISTS {}").format(sql.Identifier(login)))


@pytest.fixture(scope="session")
def _database(database_urls):
    db = Database(database_urls["app"], max_size=4)
    db.open()
    yield db
    db.close()


@pytest.fixture(scope="session")
def owner_conn(database_urls):
    """One long-lived owner connection for the whole session (avoids churning through local ports)."""
    with psycopg.connect(database_urls["owner"], autocommit=True, row_factory=psycopg.rows.dict_row) as conn:
        yield conn


@pytest.fixture
def db(_database, owner_conn):
    tables = [
        r["tablename"]
        for r in owner_conn.execute(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'nw' AND tablename <> 'schema_migrations'"
        )
    ]
    owner_conn.execute(sql.SQL("TRUNCATE {} CASCADE").format(sql.SQL(", ").join(sql.Identifier("nw", t) for t in tables)))
    metrics.reset()
    return _database


@pytest.fixture
def clock():
    return FixedClock(START)


class FakeSender:
    """Stands in for FCM/APNs. Records what would be sent; the result is scripted by the test."""

    def __init__(self) -> None:
        self.configured = True
        self.sent: list[tuple[str, PushMessage]] = []
        self.results: list[SendResult] = []

    def send(self, token: str, message: PushMessage, apns_environment: str | None = None) -> SendResult:
        self.sent.append((token, message))
        return self.results.pop(0) if self.results else SendResult(ok=True, message_id=f"fake-{len(self.sent)}")


@pytest.fixture
def senders():
    return {"fcm": FakeSender(), "apns": FakeSender()}


def make_settings(**overrides: Any) -> Settings:
    values = {
        "environment": "test",
        "auth_jwt_secret": JWT_SECRET,
        "dev_auth_enabled": True,
        "background_worker_enabled": False,
        "public_base_url": "https://watchdog.example.test",
        "android_install_url": "https://appdistribution.example.test/android",
        "ios_install_url": "https://testflight.example.test/join",
        "ip_hash_salt": "test-salt",
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


@pytest.fixture
def settings():
    return make_settings()


@pytest.fixture
def app(db, clock, senders, settings):
    return create_app(settings, clock=clock, senders=senders, database=db)


@pytest.fixture
def client(app):
    with TestClient(app, raise_server_exceptions=True) as test_client:
        yield test_client


def token_for(
    subject: str,
    email: str | None,
    *,
    now: datetime = START,
    ttl: int = 3600,
    secret: str = JWT_SECRET,
    aal: str = "aal1",
    audience: str = "authenticated",
) -> str:
    claims = {"sub": subject, "aud": audience, "aal": aal, "iat": int(now.timestamp()), "exp": int(now.timestamp()) + ttl}
    if email:
        claims["email"] = email
    return jwt.encode(claims, secret, algorithm="HS256")


@dataclass
class Actor:
    """One seeded resident with helpers to call the API as that person."""

    client: TestClient
    clock: FixedClock
    household: SeededHousehold
    _seq: int = field(default=100)

    @property
    def headers(self) -> dict[str, str]:
        # Tokens are minted at real time so they stay valid however far the test clock moves.
        return {"Authorization": f"Bearer {token_for(self.household.auth_subject, self.household.email, now=datetime.now(UTC))}"}

    def get(self, path: str, **kwargs):
        return self.client.get(path, headers=self.headers, **kwargs)

    def post(self, path: str, json: Any = None):
        return self.client.post(path, headers=self.headers, json=json)

    def submit(self, events: list[dict[str, Any]], reported_via: str = "cellular", device_id=None):
        return self.post(
            "/api/v1/connectivity/events",
            {
                "device_id": str(device_id or self.household.device_id),
                "reported_via": reported_via,
                "sent_at": self.clock.now().isoformat(),
                "events": events,
            },
        )

    def event(self, event_type: str, state: str, *, at: datetime | None = None, **diagnostics: Any) -> dict[str, Any]:
        self._seq += 1
        confidence = diagnostics.pop("confidence", "OBSERVED")
        return {
            "event_id": str(uuid.uuid4()),
            "sequence": self._seq,
            "event_type": event_type,
            "connectivity_state": state,
            "occurred_at": (at or self.clock.now()).isoformat(),
            "os_version": "15",
            "app_version": "0.1.0",
            "confidence": confidence,
            "diagnostics": {k: (v.isoformat() if isinstance(v, datetime) else v) for k, v in diagnostics.items()},
        }

    def heartbeat(self, at: datetime | None = None):
        return self.event(
            "HEARTBEAT",
            "HEALTHY",
            at=at,
            wifi_associated=True,
            home_network_match=True,
            internet_reachable=True,
            cellular_available=True,
        )

    def wifi_lost(self, at: datetime | None = None, **extra: Any):
        diagnostics = {"wifi_associated": False, "home_network_match": True, "cellular_available": True}
        diagnostics.update(extra)
        state = diagnostics.pop("state", "WIFI_DISCONNECTED")
        return self.event("WIFI_LOST", state, at=at, **diagnostics)

    def wifi_restored(self, at: datetime | None = None):
        return self.event(
            "WIFI_RESTORED",
            "HEALTHY",
            at=at,
            wifi_associated=True,
            home_network_match=True,
            internet_reachable=True,
            cellular_available=True,
        )


@dataclass
class World:
    db: Database
    client: TestClient
    clock: FixedClock
    hood: SeededNeighborhood
    _actors: dict = field(default_factory=dict)

    def actor(self, household: SeededHousehold | int) -> Actor:
        if isinstance(household, int):
            household = self.hood.households[household]
        # One Actor per household, so its per-device sequence counter keeps increasing like a real phone's.
        if household.device_id not in self._actors:
            self._actors[household.device_id] = Actor(self.client, self.clock, household)
        return self._actors[household.device_id]

    @property
    def admin(self) -> Actor:
        return self.actor(self.hood.admin)

    def all_heartbeat(self) -> None:
        for household in self.hood.households:
            actor = self.actor(household)
            assert actor.submit([actor.heartbeat()], reported_via="wifi").status_code == 200

    def tick(self, seconds: float = 0) -> None:
        if seconds:
            self.clock.advance(seconds)
        self.client.app.state.pipeline.tick(self.clock.now())

    def incidents(self, scope: str | None = None) -> list[dict[str, Any]]:
        with self.db.service() as conn:
            rows = conn.execute(
                "SELECT * FROM nw.incidents WHERE neighborhood_id = %s AND status <> 'merged' ORDER BY started_at",
                [self.hood.neighborhood_id],
            ).fetchall()
        return [r for r in rows if scope is None or r["scope"] == scope]

    def add_push_tokens(self) -> None:
        with self.db.service() as conn:
            for h in self.hood.households:
                conn.execute(
                    "INSERT INTO nw.notification_tokens (user_id, device_id, provider, token, apns_environment)"
                    " VALUES (%s, %s, %s, %s, %s)",
                    [
                        h.user_id,
                        h.device_id,
                        "fcm" if h.platform == "android" else "apns",
                        f"token-{h.index:02d}-{'x' * 20}",
                        None if h.platform == "android" else "sandbox",
                    ],
                )

    def deliveries(self) -> list[dict[str, Any]]:
        with self.db.service() as conn:
            return conn.execute("SELECT * FROM nw.notification_deliveries ORDER BY created_at").fetchall()


@pytest.fixture
def world(db, client, clock):
    return World(db, client, clock, seed_neighborhood(db))


@pytest.fixture
def second_hood(db):
    return seed_neighborhood(db, name="Birch Park (simulated)", count=6)


def later(seconds: float) -> datetime:
    return START + timedelta(seconds=seconds)
