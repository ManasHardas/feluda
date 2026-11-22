"""Administrator API. Every route requires an active member with the admin role (and multi-factor
sign-in when ADMIN_REQUIRE_MFA is set). The neighborhood is always the administrator's own."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Query, Request

from ..authentication.dependencies import Member, get_admin
from ..schemas import (
    AdminAuditLogOut,
    AdminConfigurationOut,
    AdminCreateHouseholdRequest,
    AdminCreateInvitationRequest,
    AdminDeliveryFailureOut,
    AdminHouseholdActionRequest,
    AdminHouseholdOut,
    AdminInvitationCreatedOut,
    AdminInvitationOut,
    AdminNeighborhoodSummaryOut,
    AdminRevokeDeviceRequest,
    AdminRevokeInvitationRequest,
    AdminUpdateConfigurationRequest,
    ErrorResponse,
    IncidentListOut,
    OkResponse,
)
from ..services import admin as service
from .deps import limit_by_key, now

router = APIRouter(
    prefix="/api/v1/admin", tags=["admin"], responses={code: {"model": ErrorResponse} for code in (401, 403, 404, 409, 422, 429)}
)


def _admin(request: Request, admin: Member = Depends(get_admin)) -> Member:
    limit_by_key(request, "admin", str(admin.user_id), 120, 60)
    return admin


@router.post("/households", response_model=AdminHouseholdOut, status_code=201)
def create_household(request: Request, body: AdminCreateHouseholdRequest, admin: Member = Depends(_admin)):
    return service.create_household(request.app.state.db, admin, body)


@router.get("/households", response_model=list[AdminHouseholdOut])
def list_households(request: Request, admin: Member = Depends(_admin)):
    return service.list_households(request.app.state.db, admin)


@router.post("/households/suspend", response_model=OkResponse)
def suspend_household(request: Request, body: AdminHouseholdActionRequest, admin: Member = Depends(_admin)):
    service.suspend_household(request.app.state.db, admin, body.household_id, body.reason, now(request))
    return OkResponse()


@router.post("/households/reinstate", response_model=OkResponse)
def reinstate_household(request: Request, body: AdminHouseholdActionRequest, admin: Member = Depends(_admin)):
    service.reinstate_household(request.app.state.db, admin, body.household_id, body.reason, now(request))
    return OkResponse()


@router.post("/households/remove", response_model=OkResponse)
def remove_household(request: Request, body: AdminHouseholdActionRequest, admin: Member = Depends(_admin)):
    service.remove_household(request.app.state.db, admin, body.household_id, body.reason, now(request))
    return OkResponse()


@router.post("/invitations", response_model=AdminInvitationCreatedOut, status_code=201)
def create_invitation(request: Request, body: AdminCreateInvitationRequest, admin: Member = Depends(_admin)):
    return service.create_invitation(request.app.state.db, request.app.state.settings, admin, body, now(request))


@router.get("/invitations", response_model=list[AdminInvitationOut])
def list_invitations(request: Request, admin: Member = Depends(_admin)):
    return service.list_invitations(request.app.state.db, admin, now(request))


@router.post("/invitations/revoke", response_model=OkResponse)
def revoke_invitation(request: Request, body: AdminRevokeInvitationRequest, admin: Member = Depends(_admin)):
    service.revoke_invitation(request.app.state.db, admin, body.invitation_id)
    return OkResponse()


@router.post("/devices/revoke", response_model=OkResponse)
def revoke_device(request: Request, body: AdminRevokeDeviceRequest, admin: Member = Depends(_admin)):
    service.revoke_device(request.app.state.db, admin, body.device_id, body.reason, now(request))
    return OkResponse()


@router.get("/neighborhood/summary", response_model=AdminNeighborhoodSummaryOut)
def neighborhood_summary(request: Request, admin: Member = Depends(_admin)):
    return service.summary(request.app.state.db, admin, now(request))


@router.get("/incidents", response_model=IncidentListOut)
def incident_history(request: Request, admin: Member = Depends(_admin), limit: int = Query(100, ge=1, le=500)):
    return IncidentListOut(incidents=service.incident_history(request.app.state.db, admin, limit))


@router.get("/incidents/export", response_model=list[dict[str, Any]])
def export_incidents(request: Request, admin: Member = Depends(_admin)):
    return service.export_incidents(request.app.state.db, admin, now(request))


@router.get("/configuration", response_model=AdminConfigurationOut)
def get_configuration(request: Request, admin: Member = Depends(_admin)):
    return service.get_configuration(request.app.state.db, admin)


@router.put("/configuration", response_model=AdminConfigurationOut)
def update_configuration(request: Request, body: AdminUpdateConfigurationRequest, admin: Member = Depends(_admin)):
    return service.update_configuration(request.app.state.db, admin, body.values)


@router.get("/notifications/failures", response_model=list[AdminDeliveryFailureOut])
def notification_failures(request: Request, admin: Member = Depends(_admin), limit: int = Query(100, ge=1, le=500)):
    return service.delivery_failures(request.app.state.db, admin, limit)


@router.get("/audit-logs", response_model=list[AdminAuditLogOut])
def audit_logs(request: Request, admin: Member = Depends(_admin), limit: int = Query(100, ge=1, le=500)):
    return service.audit_logs(request.app.state.db, admin, limit)
