"""Invitations, registration, household membership, leaving and account deletion."""

from __future__ import annotations

import logging
from datetime import datetime
from uuid import UUID

import httpx

from .. import ratelimit
from ..authentication.dependencies import Member
from ..authentication.tokens import Principal
from ..db import Conn, Database
from ..errors import ApiError, forbidden, invitation_invalid, not_a_member
from ..invitation_tokens import hash_token
from ..normalization import normalize_house_number, normalize_street_name
from ..schemas import HouseholdOut, VerifyInvitationResponse
from ..settings import Settings
from .audit import audit, security_event

log = logging.getLogger(__name__)

INVITATION_ATTEMPTS_PER_HOUR = 10


def _address_not_recognized() -> ApiError:
    return ApiError(422, "address_not_recognized",
                    "This address is not on the list approved for this invitation. Check the house number and "
                    "street name, or contact your neighborhood organizer.")


def _already_registered() -> ApiError:
    return ApiError(409, "household_already_registered",
                    "This household or e-mail address is already registered. Ask your neighborhood organizer for "
                    "an invitation issued for your household.")


def _usable_invitation(conn: Conn, token: str, now: datetime, lock: bool):
    row = conn.execute(
        "SELECT i.id, i.neighborhood_id, i.household_id, i.role, i.status, i.expires_at, n.name, n.status AS n_status"
        " FROM nw.invitations i JOIN nw.neighborhoods n ON n.id = i.neighborhood_id WHERE i.token_hash = %s"
        + (" FOR UPDATE OF i" if lock else ""),
        [hash_token(token)],
    ).fetchone()
    if row is None or row["status"] != "active" or row["expires_at"] <= now or row["n_status"] != "active":
        raise invitation_invalid()
    return row


def verify_invitation(db: Database, token: str, now: datetime) -> VerifyInvitationResponse:
    with db.service() as conn:
        row = _usable_invitation(conn, token, now, lock=False)
    return VerifyInvitationResponse(
        neighborhood_name=row["name"], expires_at=row["expires_at"], household_bound=row["household_id"] is not None
    )


def _redeem(conn: Conn, *, token: str, now: datetime, house_number: str, street_name: str, display_label: str | None,
            isp: str | None, email: str | None, auth_subject: str | None) -> UUID:
    """Consume an invitation and attach a user to the matching approved household. Returns the user id."""
    invitation = _usable_invitation(conn, token, now, lock=True)
    neighborhood_id = invitation["neighborhood_id"]

    granted, _ = ratelimit.consume(conn, f"invite-attempt:{invitation['id']}", INVITATION_ATTEMPTS_PER_HOUR, 3600, now)
    if not granted:
        raise invitation_invalid()

    try:
        number, street = normalize_house_number(house_number), normalize_street_name(street_name)
    except ValueError as exc:
        raise _address_not_recognized() from exc
    household = conn.execute(
        "SELECT id, membership_status, isp FROM nw.households WHERE neighborhood_id = %s"
        " AND normalized_house_number = %s AND normalized_street_name = %s FOR UPDATE",
        [neighborhood_id, number, street],
    ).fetchone()
    if (
        household is None
        or household["membership_status"] in ("suspended", "removed")
        or (invitation["household_id"] is not None and invitation["household_id"] != household["id"])
    ):
        raise _address_not_recognized()

    members = conn.execute(
        "SELECT count(*) AS n FROM nw.household_members WHERE household_id = %s AND status = 'active'", [household["id"]]
    ).fetchone()["n"]
    if members and invitation["household_id"] is None:
        # A second resident needs an invitation the organizer issued for that household.
        raise _already_registered()

    user = None
    if auth_subject:
        user = conn.execute("SELECT id FROM nw.users WHERE auth_subject = %s AND status <> 'deleted'", [auth_subject]).fetchone()
    if user is None and email:
        user = conn.execute("SELECT id FROM nw.users WHERE email = %s AND status <> 'deleted' FOR UPDATE", [email]).fetchone()
    if user is None:
        user = conn.execute(
            "INSERT INTO nw.users (email, auth_subject, status) VALUES (%s, %s, %s) RETURNING id",
            [email, auth_subject, "active" if auth_subject else "pending"],
        ).fetchone()
    else:
        active = conn.execute(
            "SELECT 1 FROM nw.household_members WHERE user_id = %s AND status = 'active'", [user["id"]]
        ).fetchone()
        if active:
            raise _already_registered()
        if auth_subject:
            conn.execute(
                "UPDATE nw.users SET auth_subject = COALESCE(auth_subject, %s), status = 'active' WHERE id = %s",
                [auth_subject, user["id"]],
            )
    user_id = user["id"]

    conn.execute(
        "INSERT INTO nw.household_members (household_id, user_id, role, status) VALUES (%s, %s, %s, 'active')"
        " ON CONFLICT (household_id, user_id) DO UPDATE SET status = 'active', role = EXCLUDED.role",
        [household["id"], user_id, invitation["role"]],
    )
    conn.execute(
        "UPDATE nw.households SET membership_status = 'active', display_label = COALESCE(%s, display_label),"
        " isp = CASE WHEN %s::text IS NOT NULL AND %s <> 'unknown' THEN %s ELSE isp END WHERE id = %s",
        [display_label, isp, isp, isp, household["id"]],
    )
    conn.execute(
        "UPDATE nw.invitations SET status = 'used', used_at = %s, used_by_user_id = %s WHERE id = %s",
        [now, user_id, invitation["id"]],
    )
    audit(conn, action="invitation_used", neighborhood_id=neighborhood_id, actor_user_id=user_id, actor_type="system",
          target_type="invitation", target_id=invitation["id"])
    return user_id


def _redeem_logged(db: Database, **kwargs) -> UUID:
    try:
        with db.service() as conn:
            return _redeem(conn, **kwargs)
    except ApiError as error:
        if error.code in ("address_not_recognized", "household_already_registered"):
            with db.service() as conn:  # separate transaction: the failed attempt was rolled back
                invitation = conn.execute(
                    "SELECT id, neighborhood_id FROM nw.invitations WHERE token_hash = %s", [hash_token(kwargs["token"])]
                ).fetchone()
                if invitation:
                    # Count the failed attempt against the invitation so addresses cannot be guessed at speed.
                    ratelimit.consume(conn, f"invite-attempt:{invitation['id']}", INVITATION_ATTEMPTS_PER_HOUR, 3600,
                                      kwargs["now"])
                    security_event(conn, kind="invitation_redeem_failed", neighborhood_id=invitation["neighborhood_id"],
                                   detail={"reason": error.code, "invitation_id": str(invitation["id"])})
        raise


def register(db: Database, *, token: str, email: str, house_number: str, street_name: str,
             display_label: str | None, isp: str | None, now: datetime) -> None:
    _redeem_logged(db, token=token, now=now, house_number=house_number, street_name=street_name,
                   display_label=display_label, isp=isp, email=email.strip().lower(), auth_subject=None)


def join(db: Database, principal: Principal, *, token: str, house_number: str, street_name: str,
         display_label: str | None, isp: str | None, now: datetime) -> UUID:
    if principal.email is None:
        raise forbidden("The sign-in does not include a verified e-mail address.")
    return _redeem_logged(db, token=token, now=now, house_number=house_number, street_name=street_name,
                          display_label=display_label, isp=isp, email=principal.email, auth_subject=principal.subject)


def household_me(db: Database, user_id: UUID) -> HouseholdOut:
    with db.api(user_id) as conn:
        row = conn.execute(
            "SELECT h.id, h.neighborhood_id, h.anonymous_reference, h.display_label, h.normalized_house_number,"
            " h.normalized_street_name, h.isp, h.membership_status, hm.role"
            " FROM nw.household_members hm JOIN nw.households h ON h.id = hm.household_id"
            " WHERE hm.user_id = %s AND hm.status = 'active'",
            [user_id],
        ).fetchone()
        if row is None:
            raise not_a_member()
        devices = conn.execute("SELECT count(*) AS n FROM nw.devices WHERE status = 'active'").fetchone()["n"]
    with db.service() as conn:
        members = conn.execute(
            "SELECT count(*) AS n FROM nw.household_members WHERE household_id = %s AND status = 'active'", [row["id"]]
        ).fetchone()["n"]
    return HouseholdOut(
        household_id=row["id"], neighborhood_id=row["neighborhood_id"], anonymous_reference=row["anonymous_reference"],
        display_label=row["display_label"], house_number=row["normalized_house_number"],
        street_name=row["normalized_street_name"], isp=row["isp"], membership_status=row["membership_status"],
        role=row["role"], member_count=members, device_count=devices,
    )


def update_household(db: Database, member: Member, display_label: str | None, isp: str | None) -> None:
    with db.api(member.user_id) as conn:
        conn.execute(
            "UPDATE nw.households SET display_label = COALESCE(%s, display_label), isp = COALESCE(%s, isp) WHERE id = %s",
            [display_label, isp, member.household_id],
        )


def _detach(conn: Conn, member: Member, now: datetime, delete_account: bool) -> str | None:
    """Remove a user's membership, devices and telemetry. Returns the identity subject when the account is deleted."""
    conn.execute(
        "UPDATE nw.household_members SET status = 'removed' WHERE user_id = %s AND status = 'active'", [member.user_id]
    )
    # Deleting the devices cascades to their raw events, sessions and push tokens.
    conn.execute("DELETE FROM nw.devices WHERE user_id = %s", [member.user_id])
    conn.execute("DELETE FROM nw.notification_preferences WHERE user_id = %s", [member.user_id])
    remaining = conn.execute(
        "SELECT count(*) AS n FROM nw.household_members WHERE household_id = %s AND status = 'active'", [member.household_id]
    ).fetchone()["n"]
    if remaining == 0:
        # Last resident gone: the address stays on the approved list, everything personal is cleared.
        conn.execute(
            "UPDATE nw.households SET membership_status = CASE WHEN membership_status = 'active' THEN 'approved'"
            " ELSE membership_status END, display_label = NULL WHERE id = %s",
            [member.household_id],
        )
        conn.execute("DELETE FROM nw.connectivity_events WHERE household_id = %s", [member.household_id])
    subject = None
    if delete_account:
        row = conn.execute(
            "UPDATE nw.users u SET email = NULL, auth_subject = NULL, status = 'deleted' FROM nw.users old"
            " WHERE u.id = %s AND old.id = u.id RETURNING old.auth_subject",
            [member.user_id],
        ).fetchone()
        subject = row["auth_subject"] if row else None
    audit(conn, action="account_deleted" if delete_account else "member_left", neighborhood_id=member.neighborhood_id,
          actor_user_id=member.user_id, actor_type="system", target_type="household", target_id=member.household_id)
    return subject


def leave(db: Database, member: Member, now: datetime) -> None:
    with db.service() as conn:
        _detach(conn, member, now, delete_account=False)


def delete_account(db: Database, settings: Settings, member: Member, now: datetime) -> None:
    with db.service() as conn:
        subject = _detach(conn, member, now, delete_account=True)
    deleted_at_provider = False
    if subject and settings.auth_admin_url and settings.auth_service_role_key:
        try:
            response = httpx.delete(
                f"{settings.auth_admin_url.rstrip('/')}/admin/users/{subject}",
                headers={"Authorization": f"Bearer {settings.auth_service_role_key}", "apikey": settings.auth_service_role_key},
                timeout=10,
            )
            deleted_at_provider = response.status_code in (200, 204, 404)
        except httpx.HTTPError:
            deleted_at_provider = False
    if subject and not deleted_at_provider:
        # The operator must remove the sign-in record at the identity provider by hand (see docs/OPERATIONS.md).
        with db.service() as conn:
            security_event(conn, kind="identity_deletion_pending", neighborhood_id=member.neighborhood_id,
                           detail={"auth_subject": subject})
        log.warning("identity record not deleted at provider; manual action needed", extra={"user_id": str(member.user_id)})
