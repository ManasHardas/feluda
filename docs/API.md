# API

Base path `/api/v1`. The machine-readable contract is `shared/api-contracts/openapi.json`,
generated from the backend (`make openapi`) and checked in CI (`make openapi-check`). In
development the interactive documentation is at `http://localhost:8000/docs`; it is switched off in
staging and production.

## Conventions

- JSON in and out. Timestamps are ISO 8601 with a time zone; the server stores and returns UTC.
- Authenticated calls send `Authorization: Bearer <access token>` from the identity provider.
- Requests with unknown fields are rejected with 422. Identifiers of households and neighborhoods
  are never accepted from clients; the server derives them from the caller's membership.
- Errors: `{"error": {"code": "...", "message": "...", "request_id": "..."}}`. Every response has
  an `X-Request-ID` header.

| Status | Code | Meaning |
| --- | --- | --- |
| 401 | `unauthorized` | Missing, invalid or expired access token |
| 403 | `not_a_member` | Signed in, but this identity has no membership (needs an invitation) |
| 403 | `household_suspended` | Membership suspended by the organizer |
| 403 | `forbidden` | Not allowed (for example not an administrator) |
| 403 | `device_revoked` | The device registration was revoked |
| 404 | `not_found`, `device_not_registered`, `invitation_invalid` | Does not exist, or is not yours to see |
| 409 | `household_already_registered`, `device_conflict`, `device_limit`, `household_exists`, `invalid_state` | Conflict |
| 422 | `validation_error`, `address_not_recognized` | Invalid input |
| 429 | `rate_limited` | Too many requests; see `Retry-After` |

## Endpoints

### Public

| Method and path | Purpose |
| --- | --- |
| `GET /health` | Liveness of the process |
| `GET /api/v1/monitoring/health` | API and database health; 503 when the database is unreachable |
| `POST /api/v1/auth/verify-invitation` | Is this invitation usable? Returns the neighborhood name |
| `POST /api/v1/auth/register` | Redeem an invitation: address, e-mail, optional label and provider |
| `GET /join`, `GET /privacy` | Registration page and privacy summary |

### Resident (signed in)

| Method and path | Purpose |
| --- | --- |
| `POST /households/join` | Redeem an invitation from inside the app |
| `GET` / `PATCH /households/me` | Own household; change label or provider |
| `POST /households/leave` | Leave the neighborhood |
| `DELETE /account` | Delete the account |
| `GET /neighborhoods/me` | Neighborhood overview, colour and explanation |
| `POST /devices/register`, `GET /devices`, `POST /devices/revoke` | Device registration |
| `POST /notifications/register` | Register a push token for a device |
| `GET` / `PUT /notifications/preferences` | Opt-outs and quiet hours |
| `POST /connectivity/events` | Upload up to 50 events |
| `GET /connectivity/status` | Household monitoring status, colour and explanation |
| `GET /incidents?status=open|all&limit=` | Visible incidents |
| `GET /incidents/{incident_id}` | One incident with score breakdown, evidence and recommended actions |

### Administrator (admin role; multi-factor when `ADMIN_REQUIRE_MFA=true`)

| Method and path | Purpose |
| --- | --- |
| `POST` / `GET /admin/households` | Approve an address; list households |
| `POST /admin/households/suspend`, `/reinstate`, `/remove` | Membership control |
| `POST` / `GET /admin/invitations`, `POST /admin/invitations/revoke` | Invitations. The link is returned once, at creation |
| `POST /admin/devices/revoke` | Revoke a compromised device |
| `GET /admin/neighborhood/summary` | Aggregate status |
| `GET /admin/incidents`, `GET /admin/incidents/export` | Incident history; anonymized export |
| `GET` / `PUT /admin/configuration` | Detection thresholds (bounded) |
| `GET /admin/notifications/failures` | Deliveries that failed, expired or could not be sent |
| `GET /admin/audit-logs` | Administrative actions |
| `GET /monitoring/metrics` | Counters |

Creating a neighborhood and switching one off are operator commands, not API calls
(`python -m watchdog.ops`, see `docs/OPERATIONS.md`).

### Operator

`POST /internal/tick` with `Authorization: Bearer <INTERNAL_TICK_TOKEN>` runs one maintenance pass.
It answers 404 unless the token is configured and matches. Only needed on hosts that put the
process to sleep.

## Example: uploading events

```http
POST /api/v1/connectivity/events
Authorization: Bearer eyJ...
Content-Type: application/json

{
  "device_id": "5a791633-08fa-58b4-851a-7c3f0ca1bae6",
  "reported_via": "cellular",
  "sent_at": "2026-03-10T20:00:07Z",
  "events": [{
    "event_id": "0b0f3c1e-7d43-4f0e-9a55-2f1f6a9d6c11",
    "sequence": 412,
    "event_type": "WIFI_LOST",
    "connectivity_state": "WIFI_DISCONNECTED",
    "occurred_at": "2026-03-10T20:00:00Z",
    "os_version": "15",
    "app_version": "0.1.0",
    "confidence": "OBSERVED",
    "diagnostics": {
      "wifi_associated": false, "home_network_match": true, "cellular_available": true,
      "default_transport": "cellular", "last_wifi_rssi_dbm": -52,
      "band_evidence": "MULTI_BAND_FAILURE_NOT_VERIFIED"
    }
  }]
}
```

```json
{
  "server_time": "2026-03-10T20:00:07.412Z",
  "outcomes": [{"event_id": "0b0f3c1e-7d43-4f0e-9a55-2f1f6a9d6c11", "result": "accepted", "reason": null}],
  "retry_after_seconds": null
}
```

`result` is `accepted`, `duplicate` (already stored; safe to drop from the queue) or `rejected`
with `reason` `rate_limited` (keep and retry later), `timestamp_in_future` or `too_old` (drop).

## Versioning

Breaking changes get a new prefix (`/api/v2`). Adding optional fields or enum values to responses
is not breaking; clients must ignore fields they do not know and treat unknown enum values as
unknown.
