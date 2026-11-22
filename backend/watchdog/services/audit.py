"""Audit and security-event records. Identifiers and counts only: no tokens, addresses or raw IPs."""

from __future__ import annotations

import hashlib
from typing import Any
from uuid import UUID

from psycopg.types.json import Jsonb

from ..db import Conn


def audit(conn: Conn, *, action: str, neighborhood_id: UUID | None, actor_user_id: UUID | None = None,
          actor_type: str = "admin", target_type: str | None = None, target_id: UUID | None = None,
          metadata: dict[str, Any] | None = None) -> None:
    conn.execute(
        "INSERT INTO nw.admin_audit_logs (neighborhood_id, actor_user_id, actor_type, action, target_type, target_id, metadata)"
        " VALUES (%s, %s, %s, %s, %s, %s, %s)",
        [neighborhood_id, actor_user_id, actor_type, action, target_type, target_id, Jsonb(metadata or {})],
    )


def security_event(conn: Conn, *, kind: str, neighborhood_id: UUID | None = None, user_id: UUID | None = None,
                   device_id: UUID | None = None, household_id: UUID | None = None,
                   detail: dict[str, Any] | None = None) -> None:
    conn.execute(
        "INSERT INTO nw.security_events (kind, neighborhood_id, user_id, device_id, household_id, detail)"
        " VALUES (%s, %s, %s, %s, %s, %s)",
        [kind, neighborhood_id, user_id, device_id, household_id, Jsonb(detail or {})],
    )


def hash_ip(ip: str, salt: str) -> str:
    return hashlib.sha256(f"{salt}:{ip}".encode()).hexdigest()[:16]
