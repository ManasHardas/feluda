"""Resident-facing API, version 1."""

from __future__ import annotations

from typing import Literal
from uuid import UUID

from fastapi import APIRouter, BackgroundTasks, Depends, Query, Request, Response

from .. import __version__, metrics
from ..app_versions import is_below, parse_version
from ..authentication.dependencies import Member, Operator, get_member, get_member_any, get_operator
from ..enums import Platform
from ..errors import ApiError
from ..schemas import (
    AppCompatibilityOut,
    ConnectivityStatusOut,
    DeviceOut,
    EnrollRequest,
    EnrollResponse,
    ErrorResponse,
    HealthOut,
    HouseholdOut,
    IncidentDetailOut,
    IncidentListOut,
    NeighborhoodOut,
    NotificationPreferences,
    OkResponse,
    RegisterDeviceRequest,
    RegisterPushTokenRequest,
    RevokeDeviceRequest,
    SubmitEventsRequest,
    SubmitEventsResponse,
    UpdateHouseholdRequest,
    VerifyInvitationRequest,
    VerifyInvitationResponse,
)
from ..services import devices, events, incidents, membership, status
from .deps import limit_by_client, limit_by_key, now

ERRORS = {code: {"model": ErrorResponse} for code in (401, 403, 404, 409, 422, 426, 429)}
router = APIRouter(prefix="/api/v1", responses=ERRORS)


def _privacy_ack(acknowledged: bool) -> None:
    if not acknowledged:
        raise ApiError(422, "validation_error", "The privacy summary must be acknowledged before joining.")


# ---------------------------------------------------------------- health


@router.get("/monitoring/health", response_model=HealthOut, tags=["monitoring"], responses={503: {"model": HealthOut}})
def health(request: Request, response: Response) -> HealthOut:
    database_ok = request.app.state.db.ping()
    if not database_ok:
        response.status_code = 503
    return HealthOut(
        status="ok" if database_ok else "degraded",
        api="ok",
        database="ok" if database_ok else "unavailable",
        version=__version__,
        server_time=now(request),
    )


@router.get("/app/compatibility", response_model=AppCompatibilityOut, tags=["app"])
def app_compatibility(
    request: Request,
    platform: Platform,
    version: str = Query(min_length=5, max_length=14, pattern=r"^\d{1,4}\.\d{1,4}\.\d{1,4}$"),
) -> AppCompatibilityOut:
    """Whether this app version is still accepted and whether a newer one exists. No credential needed."""
    settings = request.app.state.settings
    ios = platform == Platform.IOS
    minimum = settings.min_app_version_ios if ios else settings.min_app_version_android
    latest = settings.latest_app_version_ios if ios else settings.latest_app_version_android
    url = settings.ios_install_url if ios else settings.android_install_url
    mine = parse_version(version)
    return AppCompatibilityOut(
        platform=platform,
        version=version,
        supported=not is_below(mine, minimum),
        update_available=bool(latest) and is_below(mine, latest),
        minimum_version=minimum or None,
        latest_version=latest or None,
        update_url=url or None,
    )


@router.get("/monitoring/metrics", tags=["monitoring"], include_in_schema=False)
def metrics_snapshot(operator: Operator = Depends(get_operator)) -> dict[str, int]:
    return metrics.snapshot()


# ---------------------------------------------------------------- invitations and enrolment (no credential yet)


@router.post("/auth/verify-invitation", response_model=VerifyInvitationResponse, tags=["onboarding"])
def verify_invitation(request: Request, body: VerifyInvitationRequest) -> VerifyInvitationResponse:
    limit_by_client(request, "verify-invitation", 20, 600)
    return membership.verify_invitation(request.app.state.db, body.invitation_token, now(request))


@router.post("/enroll", response_model=EnrollResponse, status_code=201, tags=["onboarding"])
def enroll(request: Request, body: EnrollRequest) -> EnrollResponse:
    """Enrol this phone with an invitation link. No account, e-mail address or password is involved."""
    limit_by_client(request, "enroll", 10, 600)
    _privacy_ack(body.privacy_acknowledged)
    return membership.enroll(request.app.state.db, body, now(request))


# ---------------------------------------------------------------- household


@router.get("/households/me", response_model=HouseholdOut, tags=["household"])
def my_household(request: Request, member: Member = Depends(get_member_any)) -> HouseholdOut:
    return membership.household_me(request.app.state.db, member.user_id)


@router.patch("/households/me", response_model=HouseholdOut, tags=["household"])
def update_my_household(request: Request, body: UpdateHouseholdRequest, member: Member = Depends(get_member)) -> HouseholdOut:
    membership.update_household(request.app.state.db, member, body.display_label, body.isp.value if body.isp else None)
    return membership.household_me(request.app.state.db, member.user_id)


@router.post("/households/leave", response_model=OkResponse, tags=["household"])
def leave_neighborhood(request: Request, member: Member = Depends(get_member_any)) -> OkResponse:
    membership.leave(request.app.state.db, member, now(request))
    return OkResponse()


@router.get("/neighborhoods/me", response_model=NeighborhoodOut, tags=["neighborhood"])
def my_neighborhood(request: Request, member: Member = Depends(get_member)) -> NeighborhoodOut:
    return status.neighborhood_overview(request.app.state.db, member, now(request))


# ---------------------------------------------------------------- devices


@router.post("/devices/register", response_model=DeviceOut, tags=["devices"])
def register_device(request: Request, body: RegisterDeviceRequest, member: Member = Depends(get_member)) -> DeviceOut:
    limit_by_key(request, "device-register", str(member.device_id), 20, 3600)
    return devices.register(request.app.state.db, member, body)


@router.get("/devices", response_model=list[DeviceOut], tags=["devices"])
def list_devices(request: Request, member: Member = Depends(get_member)) -> list[DeviceOut]:
    return devices.list_devices(request.app.state.db, member)


@router.post("/devices/revoke", response_model=OkResponse, tags=["devices"])
def revoke_device(request: Request, body: RevokeDeviceRequest, member: Member = Depends(get_member_any)) -> OkResponse:
    devices.revoke(request.app.state.db, member, body.device_id, now(request))
    return OkResponse()


# ---------------------------------------------------------------- notifications


@router.post("/notifications/register", response_model=OkResponse, tags=["notifications"])
def register_push_token(request: Request, body: RegisterPushTokenRequest, member: Member = Depends(get_member)) -> OkResponse:
    if body.device_id != member.device_id:
        raise ApiError(404, "device_not_registered", "This device is not registered.")
    db = request.app.state.db
    with db.api(member.user_id) as conn:
        device = conn.execute("SELECT id, platform, status FROM nw.devices WHERE id = %s", [body.device_id]).fetchone()
    if device is None or device["status"] != "active":
        raise ApiError(404, "device_not_registered", "This device is not registered.")
    expected = "fcm" if device["platform"] == "android" else "apns"
    if body.provider != expected:
        raise ApiError(422, "validation_error", f"Devices on {device['platform']} register {expected} tokens.")
    with db.service() as conn:
        # A token identifies one app install. If it shows up again (reinstall, new sign-in) it moves here.
        conn.execute(
            "DELETE FROM nw.notification_tokens WHERE (provider = %s AND token = %s) OR device_id = %s",
            [body.provider.value, body.token, body.device_id],
        )
        conn.execute(
            "INSERT INTO nw.notification_tokens (user_id, device_id, provider, token, apns_environment)"
            " VALUES (%s, %s, %s, %s, %s)",
            [
                member.user_id,
                body.device_id,
                body.provider.value,
                body.token,
                (body.apns_environment.value if body.apns_environment else "production") if expected == "apns" else None,
            ],
        )
    return OkResponse()


@router.get("/notifications/preferences", response_model=NotificationPreferences, tags=["notifications"])
def get_preferences(request: Request, member: Member = Depends(get_member)) -> NotificationPreferences:
    with request.app.state.db.api(member.user_id) as conn:
        row = conn.execute("SELECT * FROM nw.notification_preferences WHERE user_id = %s", [member.user_id]).fetchone()
    if row is None:
        return NotificationPreferences()
    return NotificationPreferences(**{k: row[k] for k in NotificationPreferences.model_fields})


@router.put("/notifications/preferences", response_model=NotificationPreferences, tags=["notifications"])
def put_preferences(
    request: Request, body: NotificationPreferences, member: Member = Depends(get_member)
) -> NotificationPreferences:
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

    try:
        ZoneInfo(body.timezone)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise ApiError(422, "validation_error", "Unknown time zone.") from exc
    with request.app.state.db.api(member.user_id) as conn:
        conn.execute(
            "INSERT INTO nw.notification_preferences (user_id, household_alerts, neighborhood_alerts, quiet_hours_enabled,"
            " quiet_hours_start, quiet_hours_end, timezone) VALUES (%s, %s, %s, %s, %s, %s, %s)"
            " ON CONFLICT (user_id) DO UPDATE SET household_alerts = EXCLUDED.household_alerts,"
            " neighborhood_alerts = EXCLUDED.neighborhood_alerts, quiet_hours_enabled = EXCLUDED.quiet_hours_enabled,"
            " quiet_hours_start = EXCLUDED.quiet_hours_start, quiet_hours_end = EXCLUDED.quiet_hours_end,"
            " timezone = EXCLUDED.timezone",
            [
                member.user_id,
                body.household_alerts,
                body.neighborhood_alerts,
                body.quiet_hours_enabled,
                body.quiet_hours_start,
                body.quiet_hours_end,
                body.timezone,
            ],
        )
    return body


# ---------------------------------------------------------------- connectivity


@router.post("/connectivity/events", response_model=SubmitEventsResponse, tags=["connectivity"])
def submit_events(
    request: Request, body: SubmitEventsRequest, background: BackgroundTasks, member: Member = Depends(get_member)
) -> SubmitEventsResponse:
    if body.device_id != member.device_id:
        raise ApiError(404, "device_not_registered", "This device is not registered. Enrol it first.")
    limit_by_key(request, "event-requests", str(body.device_id), 60, 60)
    response, stored = events.ingest(request.app.state.db, member, body, now(request))
    if stored:
        pipeline = request.app.state.pipeline
        # Correlate right away (event-driven); push delivery happens after the response is sent.
        pipeline.process_neighborhood(member.neighborhood_id)
        background.add_task(pipeline.deliver)
    return response


@router.get("/connectivity/status", response_model=ConnectivityStatusOut, tags=["connectivity"])
def connectivity_status(request: Request, member: Member = Depends(get_member)) -> ConnectivityStatusOut:
    return status.connectivity_status(request.app.state.db, member, now(request))


# ---------------------------------------------------------------- incidents


@router.get("/incidents", response_model=IncidentListOut, tags=["incidents"])
def list_incidents(
    request: Request,
    member: Member = Depends(get_member),
    status_filter: Literal["open", "all"] = Query("open", alias="status"),
    limit: int = Query(50, ge=1, le=200),
) -> IncidentListOut:
    return IncidentListOut(incidents=incidents.list_incidents(request.app.state.db, member, status_filter, limit))


@router.get("/incidents/{incident_id}", response_model=IncidentDetailOut, tags=["incidents"])
def get_incident(request: Request, incident_id: UUID, member: Member = Depends(get_member)) -> IncidentDetailOut:
    return incidents.get_incident(request.app.state.db, member, incident_id)
