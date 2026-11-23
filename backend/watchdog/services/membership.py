"""Invitations, enrolment of a phone, household membership and leaving.

There are no accounts. An invitation link enrols a phone; the phone then holds a random secret.
The only things a resident supplies are the street name and, optionally, a house number.
"""

from __future__ import annotations

import logging
from datetime import datetime
from uuid import UUID

from psycopg.types.json import Jsonb

from ..authentication.dependencies import Member
from ..db import Conn, Database
from ..errors import ApiError, invitation_invalid, unauthorized
from ..invitation_tokens import anonymous_reference, hash_token, new_token
from ..normalization import normalize_house_number, normalize_street_name
from ..schemas import DeviceOut, EnrollRequest, EnrollResponse, HouseholdOut, VerifyInvitationResponse
from .audit import audit, security_event
from .devices import device_out

log = logging.getLogger(__name__)


def _street_not_usable() -> ApiError:
    return ApiError(422, "validation_error", "Enter the street name using letters or numbers.")


def _household_taken() -> ApiError:
    return ApiError(
        409,
        "household_already_registered",
        "This address is already taking part. To add another phone to it, use the same invitation link "
        "that was used for the first phone, or ask your neighborhood organizer for a link for this household.",
    )


def _usable_invitation(conn: Conn, token: str, now: datetime, lock: bool, allow_used: bool = False):
    query = (
        "SELECT i.id, i.neighborhood_id, i.household_id, i.status, i.expires_at, i.max_uses, i.use_count,"
        " n.name, n.status AS n_status"
        " FROM nw.invitations i JOIN nw.neighborhoods n ON n.id = i.neighborhood_id WHERE i.token_hash = %s"
    )
    if lock:
        query += " FOR UPDATE OF i"
    row = conn.execute(query, [hash_token(token)]).fetchone()
    statuses = ("active", "used") if allow_used else ("active",)
    if row is None or row["status"] not in statuses or row["expires_at"] <= now or row["n_status"] != "active":
        raise invitation_invalid()
    return row


def verify_invitation(db: Database, token: str, now: datetime) -> VerifyInvitationResponse:
    with db.service() as conn:
        row = _usable_invitation(conn, token, now, lock=False)
    return VerifyInvitationResponse(neighborhood_name=row["name"], expires_at=row["expires_at"])


def _household_for(conn: Conn, invitation, number: str | None, street: str) -> UUID:
    """Find or create the household this enrolment belongs to. Never trusts an identifier from the client.

    One link is one home. The first phone that uses a link decides the household (by the street and
    optional house number typed); the link is then bound to it, and every later phone using the same
    link joins that household whatever it types. So a link can never create more than one household,
    and several phones from one link cannot look like several homes to the correlation engine.
    """
    neighborhood_id = invitation["neighborhood_id"]
    if invitation["household_id"] is not None:
        # A link bound to one household (by the organizer, or by its first use): the phone joins it.
        household = conn.execute(
            "SELECT id, membership_status FROM nw.households WHERE id = %s FOR UPDATE", [invitation["household_id"]]
        ).fetchone()
        if household is None or household["membership_status"] in ("suspended", "removed"):
            raise invitation_invalid()
        return household["id"]

    household = None
    if number is not None:
        household = conn.execute(
            "SELECT id, membership_status FROM nw.households WHERE neighborhood_id = %s"
            " AND normalized_house_number = %s AND normalized_street_name = %s FOR UPDATE",
            [neighborhood_id, number, street],
        ).fetchone()
    if household is None:
        household_id = conn.execute(
            "INSERT INTO nw.households (neighborhood_id, anonymous_reference, normalized_house_number,"
            " normalized_street_name, membership_status, invitation_id) VALUES (%s, %s, %s, %s, 'active', %s) RETURNING id",
            [neighborhood_id, anonymous_reference(), number, street, invitation["id"]],
        ).fetchone()["id"]
    else:
        if household["membership_status"] in ("suspended", "removed"):
            raise _household_taken()
        phones = conn.execute(
            "SELECT count(*) AS n FROM nw.household_members WHERE household_id = %s AND status = 'active'", [household["id"]]
        ).fetchone()["n"]
        if phones:
            # Another link must not be able to attach a phone to a home that is already taking part:
            # that phone would see the household's status and alerts.
            raise _household_taken()
        household_id = household["id"]  # an address the organizer added in advance
    conn.execute("UPDATE nw.invitations SET household_id = %s WHERE id = %s", [household_id, invitation["id"]])
    return household_id


def _issue_credential(conn: Conn, device_id: UUID) -> str:
    secret = new_token()
    conn.execute(
        "INSERT INTO nw.device_credentials (device_id, secret_hash) VALUES (%s, %s)"
        " ON CONFLICT (device_id) DO UPDATE SET secret_hash = EXCLUDED.secret_hash",
        [device_id, hash_token(secret)],
    )
    return secret


def _enroll(conn: Conn, body: EnrollRequest, now: datetime) -> tuple[str, UUID, DeviceOut]:
    invitation = _usable_invitation(conn, body.invitation_token, now, lock=True, allow_used=True)
    capabilities = Jsonb(body.capabilities.model_dump(mode="json"))

    existing = conn.execute("SELECT * FROM nw.devices WHERE id = %s FOR UPDATE", [body.device_id]).fetchone()
    if existing is not None:
        # The same phone enrolling again with the same link (for example the first answer was lost on
        # the way back): give it a fresh secret without using the link up a second time.
        if (
            existing["invitation_id"] != invitation["id"]
            or existing["status"] != "active"
            or existing["platform"] != body.platform.value
        ):
            raise ApiError(409, "device_conflict", "This phone cannot be enrolled with this link. Reinstall the app.")
        row = conn.execute(
            "UPDATE nw.devices SET os_version = %s, app_version = %s, capabilities = %s WHERE id = %s RETURNING *",
            [body.os_version, body.app_version, capabilities, body.device_id],
        ).fetchone()
        return _issue_credential(conn, row["id"]), row["user_id"], device_out(row)

    if invitation["status"] != "active" or invitation["use_count"] >= invitation["max_uses"]:
        raise invitation_invalid()
    try:
        street = normalize_street_name(body.street_name)
    except ValueError as exc:
        raise _street_not_usable() from exc
    number = None
    if body.house_number is not None:
        try:
            number = normalize_house_number(body.house_number)
        except ValueError:
            number = None  # nothing usable was typed: treat it as not given

    household_id = _household_for(conn, invitation, number, street)
    conn.execute("UPDATE nw.households SET membership_status = 'active' WHERE id = %s", [household_id])
    user_id = conn.execute("INSERT INTO nw.users (status) VALUES ('active') RETURNING id").fetchone()["id"]
    conn.execute(
        "INSERT INTO nw.household_members (household_id, user_id, role, status) VALUES (%s, %s, 'resident', 'active')",
        [household_id, user_id],
    )
    row = conn.execute(
        "INSERT INTO nw.devices (id, user_id, household_id, platform, os_version, app_version, capabilities, invitation_id)"
        " VALUES (%s, %s, %s, %s, %s, %s, %s, %s) RETURNING *",
        [
            body.device_id,
            user_id,
            household_id,
            body.platform.value,
            body.os_version,
            body.app_version,
            capabilities,
            invitation["id"],
        ],
    ).fetchone()
    used = invitation["use_count"] + 1
    conn.execute(
        "UPDATE nw.invitations SET use_count = %s, used_at = %s, used_by_user_id = %s,"
        " status = CASE WHEN %s >= max_uses THEN 'used' ELSE status END WHERE id = %s",
        [used, now, user_id, used, invitation["id"]],
    )
    audit(
        conn,
        action="invitation_used",
        neighborhood_id=invitation["neighborhood_id"],
        actor_user_id=user_id,
        actor_type="system",
        target_type="invitation",
        target_id=invitation["id"],
    )
    return _issue_credential(conn, row["id"]), user_id, device_out(row)


def enroll(db: Database, body: EnrollRequest, now: datetime) -> EnrollResponse:
    try:
        with db.service() as conn:
            secret, user_id, device = _enroll(conn, body, now)
    except ApiError as error:
        if error.code in ("household_already_registered", "device_conflict"):
            with db.service() as conn:  # separate transaction: the failed attempt was rolled back
                invitation = conn.execute(
                    "SELECT id, neighborhood_id FROM nw.invitations WHERE token_hash = %s", [hash_token(body.invitation_token)]
                ).fetchone()
                if invitation:
                    security_event(
                        conn,
                        kind="enrolment_refused",
                        neighborhood_id=invitation["neighborhood_id"],
                        detail={"reason": error.code, "invitation_id": str(invitation["id"])},
                    )
        raise
    return EnrollResponse(device_token=secret, household=household_me(db, user_id), device=device)


def household_me(db: Database, user_id: UUID) -> HouseholdOut:
    with db.api(user_id) as conn:
        row = conn.execute(
            "SELECT h.id, h.neighborhood_id, h.anonymous_reference, h.display_label, h.normalized_house_number,"
            " h.normalized_street_name, h.isp, h.membership_status"
            " FROM nw.household_members hm JOIN nw.households h ON h.id = hm.household_id"
            " WHERE hm.user_id = %s AND hm.status = 'active'",
            [user_id],
        ).fetchone()
        if row is None:
            raise unauthorized("This phone is not enrolled. Use an invitation link to join.")
    with db.service() as conn:
        devices = conn.execute(
            "SELECT count(*) AS n FROM nw.devices WHERE household_id = %s AND status = 'active'", [row["id"]]
        ).fetchone()["n"]
    return HouseholdOut(
        household_id=row["id"],
        neighborhood_id=row["neighborhood_id"],
        anonymous_reference=row["anonymous_reference"],
        display_label=row["display_label"],
        house_number=row["normalized_house_number"],
        street_name=row["normalized_street_name"],
        isp=row["isp"],
        membership_status=row["membership_status"],
        device_count=devices,
    )


def update_household(db: Database, member: Member, display_label: str | None, isp: str | None) -> None:
    with db.api(member.user_id) as conn:
        conn.execute(
            "UPDATE nw.households SET display_label = COALESCE(%s, display_label), isp = COALESCE(%s, isp) WHERE id = %s",
            [display_label, isp, member.household_id],
        )


def leave(db: Database, member: Member, now: datetime) -> None:
    """Remove this phone and everything it reported. There is no account, so nothing else remains."""
    with db.service() as conn:
        # Deleting the phone's row cascades to its device, secret, raw events, sessions, push token,
        # notification preferences and membership.
        conn.execute("DELETE FROM nw.users WHERE id = %s", [member.user_id])
        remaining = conn.execute(
            "SELECT count(*) AS n FROM nw.household_members WHERE household_id = %s AND status = 'active'", [member.household_id]
        ).fetchone()["n"]
        if remaining == 0:
            conn.execute("DELETE FROM nw.connectivity_events WHERE household_id = %s", [member.household_id])
            # Last phone gone. A household that residents created by joining is deleted with the street and
            # house number they typed. One the organizer added in advance (invitation_id is NULL) keeps only
            # what the organizer entered; a household the organizer removed stays as a block on re-entry.
            deleted = conn.execute(
                "DELETE FROM nw.households WHERE id = %s AND invitation_id IS NOT NULL AND membership_status <> 'removed'"
                " RETURNING id",
                [member.household_id],
            ).fetchone()
            if deleted is None:
                conn.execute(
                    "UPDATE nw.households SET membership_status = CASE WHEN membership_status = 'active' THEN 'approved'"
                    " ELSE membership_status END, display_label = NULL, isp = 'unknown' WHERE id = %s",
                    [member.household_id],
                )
        audit(
            conn,
            action="member_left",
            neighborhood_id=member.neighborhood_id,
            actor_type="system",
            target_type="household",
            target_id=member.household_id,
        )
