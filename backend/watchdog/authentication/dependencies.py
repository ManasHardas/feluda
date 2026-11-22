"""FastAPI dependencies that turn a bearer token into an authorised member.

Authorisation is decided here and in PostgreSQL row-level security, never from identifiers the
client sends. The household and neighborhood of a request always come from the membership record of
the signed-in user.
"""

from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ..errors import forbidden, household_suspended, not_a_member, unauthorized
from .tokens import Principal

_bearer = HTTPBearer(auto_error=False, description="Access token from the identity provider")


@dataclass(frozen=True)
class Member:
    user_id: UUID
    household_id: UUID
    neighborhood_id: UUID
    role: str
    household_status: str
    neighborhood_status: str
    assurance_level: str

    @property
    def is_active(self) -> bool:
        return self.household_status == "active" and self.neighborhood_status == "active"


def get_principal(request: Request, credentials: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> Principal:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise unauthorized()
    return request.app.state.verifier.verify(credentials.credentials)


def resolve_user_id(request: Request, principal: Principal) -> UUID | None:
    """Find the account for this identity; bind it on first sign-in.

    An account row is created only by invitation-based registration, keyed by e-mail address. The
    first time that e-mail address signs in (which proves control of the mailbox through the
    one-time code) the identity provider's subject is attached to the row. Identities that never
    registered get no account.
    """
    db = request.app.state.db
    with db.service() as conn:
        row = conn.execute(
            "SELECT id FROM nw.users WHERE auth_subject = %s AND status <> 'deleted'", [principal.subject]
        ).fetchone()
        if row:
            return row["id"]
        if principal.email is None:
            return None
        row = conn.execute(
            "UPDATE nw.users SET auth_subject = %s, status = 'active'"
            " WHERE email = %s AND auth_subject IS NULL AND status = 'pending' RETURNING id",
            [principal.subject, principal.email],
        ).fetchone()
        return row["id"] if row else None


def get_member_any(request: Request, principal: Principal = Depends(get_principal)) -> Member:
    """A member whose household may be suspended (enough to read own membership, leave, delete)."""
    user_id = resolve_user_id(request, principal)
    if user_id is None:
        raise not_a_member()
    with request.app.state.db.service() as conn:
        row = conn.execute(
            "SELECT hm.household_id, hm.role, h.neighborhood_id, h.membership_status, n.status AS neighborhood_status"
            " FROM nw.household_members hm JOIN nw.households h ON h.id = hm.household_id"
            " JOIN nw.neighborhoods n ON n.id = h.neighborhood_id"
            " WHERE hm.user_id = %s AND hm.status = 'active'",
            [user_id],
        ).fetchone()
    if row is None or row["membership_status"] == "removed":
        raise not_a_member()
    return Member(
        user_id=user_id,
        household_id=row["household_id"],
        neighborhood_id=row["neighborhood_id"],
        role=row["role"],
        household_status=row["membership_status"],
        neighborhood_status=row["neighborhood_status"],
        assurance_level=principal.assurance_level,
    )


def get_member(member: Member = Depends(get_member_any)) -> Member:
    if member.household_status == "suspended":
        raise household_suspended()
    if not member.is_active:
        raise forbidden("This neighborhood is not active.")
    return member


def get_admin(request: Request, member: Member = Depends(get_member)) -> Member:
    if member.role != "admin":
        raise forbidden("Administrator access is required.")
    if request.app.state.settings.admin_require_mfa and member.assurance_level != "aal2":
        raise forbidden("Administrator access requires multi-factor sign-in.")
    return member
