"""FastAPI dependencies that turn a request's credential into an authorised caller.

There is no sign-in. A phone is enrolled once with an invitation link and receives a random secret
(its device credential), which it sends as a bearer token. Authorisation is decided here and in
PostgreSQL row-level security, never from identifiers the client sends: the household and
neighborhood of a request always come from the enrolment record of the calling phone.

Administration uses one separate secret (ADMIN_API_TOKEN) held by the operator.
"""

from __future__ import annotations

import secrets
from dataclasses import dataclass
from uuid import UUID

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ..errors import forbidden, household_suspended, not_found, unauthorized
from ..invitation_tokens import hash_token

_bearer = HTTPBearer(auto_error=False, description="Device credential issued at enrolment")
NEIGHBORHOOD_HEADER = "X-Neighborhood-Id"


@dataclass(frozen=True)
class Member:
    """An enrolled phone. `user_id` is the row-level-security identity of that one phone."""

    user_id: UUID
    device_id: UUID
    household_id: UUID
    neighborhood_id: UUID
    household_status: str
    neighborhood_status: str

    @property
    def is_active(self) -> bool:
        return self.household_status == "active" and self.neighborhood_status == "active"


@dataclass(frozen=True)
class Operator:
    """The administrator, acting on one neighborhood. Not a resident: has no household and no user id."""

    neighborhood_id: UUID
    user_id: None = None
    household_id: None = None


def _credential(credentials: HTTPAuthorizationCredentials | None) -> str:
    if credentials is None or credentials.scheme.lower() != "bearer" or not credentials.credentials:
        raise unauthorized()
    return credentials.credentials


def get_member_any(request: Request, credentials: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> Member:
    """An enrolled phone whose household may be suspended (enough to read its own enrolment or leave)."""
    secret = _credential(credentials)
    with request.app.state.db.service() as conn:
        row = conn.execute(
            "SELECT d.id AS device_id, d.user_id, d.household_id, h.neighborhood_id, h.membership_status,"
            " n.status AS neighborhood_status"
            " FROM nw.device_credentials c JOIN nw.devices d ON d.id = c.device_id"
            " JOIN nw.household_members hm ON hm.user_id = d.user_id AND hm.household_id = d.household_id"
            " AND hm.status = 'active'"
            " JOIN nw.households h ON h.id = d.household_id JOIN nw.neighborhoods n ON n.id = h.neighborhood_id"
            " WHERE c.secret_hash = %s AND d.status = 'active'",
            [hash_token(secret)],
        ).fetchone()
    if row is None or row["membership_status"] == "removed":
        # One answer for unknown, revoked and removed phones.
        raise unauthorized("This phone is not enrolled. Use an invitation link to join.")
    return Member(
        user_id=row["user_id"],
        device_id=row["device_id"],
        household_id=row["household_id"],
        neighborhood_id=row["neighborhood_id"],
        household_status=row["membership_status"],
        neighborhood_status=row["neighborhood_status"],
    )


def get_member(member: Member = Depends(get_member_any)) -> Member:
    if member.household_status == "suspended":
        raise household_suspended()
    if not member.is_active:
        raise forbidden("This neighborhood is not active.")
    return member


def get_operator(request: Request, credentials: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> Operator:
    """The administrator. The neighborhood is named in a header, or is the only one that exists."""
    expected = request.app.state.settings.admin_api_token
    supplied = _credential(credentials)
    if not expected or not secrets.compare_digest(supplied.encode(), expected.encode()):
        raise unauthorized()
    requested = request.headers.get(NEIGHBORHOOD_HEADER, "").strip()
    with request.app.state.db.service() as conn:
        if requested:
            try:
                wanted = UUID(requested)
            except ValueError as exc:
                raise not_found("Neighborhood not found.") from exc
            row = conn.execute("SELECT id FROM nw.neighborhoods WHERE id = %s", [wanted]).fetchone()
            if row is None:
                raise not_found("Neighborhood not found.")
            return Operator(neighborhood_id=row["id"])
        rows = conn.execute("SELECT id FROM nw.neighborhoods ORDER BY created_at LIMIT 2").fetchall()
    if len(rows) != 1:
        raise not_found(f"Name the neighborhood with the {NEIGHBORHOOD_HEADER} header.")
    return Operator(neighborhood_id=rows[0]["id"])
