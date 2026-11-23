# API

Base path `/api/v1`. The machine-readable contract is `shared/api-contracts/openapi.json`,
generated from the backend (`make openapi`) and checked in CI (`make openapi-check`). In
development the interactive documentation is at `http://localhost:8000/docs`; it is switched off in
staging and production.

## Conventions

- JSON in and out. Timestamps are ISO 8601 with a time zone; the server stores and returns UTC.
- There is no sign-in (decision 0014). A phone is enrolled once with an invitation link and
  receives a device secret. Every later call sends `Authorization: Bearer <device secret>`. The
  secret does not expire and there is nothing to refresh. The server stores only its SHA-256 hash.
- Administrator calls send `Authorization: Bearer <ADMIN_API_TOKEN>`, the deployment's single
  operator secret. A device secret is never accepted there, and the operator secret is never
  accepted on resident routes.
- Requests with unknown fields are rejected with 422. Identifiers of households and neighborhoods
  are never accepted from phones; the server derives them from the enrolment record of the calling
  phone.
- Errors: `{"error": {"code": "...", "message": "...", "request_id": "..."}}`. Every response has
  an `X-Request-ID` header.

| Status | Code | Meaning |
| --- | --- | --- |
| 401 | `unauthorized` | No credential, or one the server does not know. For a phone this means it is not enrolled: it never joined, it left, it was removed, or its household was removed. It needs an invitation link |
| 403 | `household_suspended` | The household was suspended by the organizer. The phone can still read `GET /households/me`, remove a phone of its household and leave |
| 403 | `forbidden` | Not allowed (for example the neighborhood is switched off) |
| 403 | `device_revoked` | Event upload for a device record that is no longer active |
| 404 | `not_found`, `device_not_registered`, `invitation_invalid` | Does not exist, or is not yours to see. Unknown, used-up, expired and revoked invitations all answer `invitation_invalid` |
| 409 | `household_already_registered` | The address is already taking part and this link does not belong to it |
| 409 | `device_conflict`, `household_exists`, `invalid_state` | Conflict |
| 422 | `validation_error` | Invalid input |
| 429 | `rate_limited` | Too many requests; see `Retry-After` |

## Endpoints

### Public (no credential)

| Method and path | Purpose |
| --- | --- |
| `GET /health` | Liveness of the process |
| `GET /api/v1/monitoring/health` | API and database health; 503 when the database is unreachable |
| `POST /api/v1/auth/verify-invitation` | Is this invitation usable? Returns the neighborhood name and the expiry. The path keeps its old `auth` prefix; no authentication is involved |
| `POST /api/v1/enroll` | Enrol this phone with an invitation: street (required), house number (optional), privacy acknowledgement, a random install id, platform, versions and capability flags. Returns the device secret once, the household and the device. No name, e-mail address, phone number or password is accepted |
| `GET /join`, `GET /privacy` | Invitation page (opens the app with `feluda://join#t=<token>`) and privacy summary |

Verification is limited to 20 calls and enrolment to 10 calls per 10 minutes per client address.

Enrolling again with the same install id and the same link (for example because the first answer
was lost) returns a fresh secret and does not count as another use of the link.

### Resident (device secret)

| Method and path | Purpose |
| --- | --- |
| `GET` / `PATCH /households/me` | Own household; change label or provider |
| `POST /households/leave` | Leave: deletes this phone, its secret and everything it reported |
| `GET /neighborhoods/me` | Neighborhood overview, colour and explanation |
| `POST /devices/register` | Update what this phone reports about itself (OS and app version, capabilities). Never creates a phone |
| `GET /devices` | The phones of the caller's household |
| `POST /devices/revoke` | Remove this phone or another phone of the same household |
| `POST /notifications/register` | Register a push token for this phone |
| `GET` / `PUT /notifications/preferences` | Opt-outs and quiet hours |
| `POST /connectivity/events` | Upload up to 50 events |
| `GET /connectivity/status` | Household monitoring status, colour and explanation |
| `GET /incidents?status=open|all&limit=` | Visible incidents |
| `GET /incidents/{incident_id}` | One incident with score breakdown, evidence and recommended actions |

### Administrator (operator secret)

`Authorization: Bearer <ADMIN_API_TOKEN>`. When the deployment has more than one neighborhood, also
send `X-Neighborhood-Id: <uuid>`; with exactly one it is not needed. If `ADMIN_API_TOKEN` is empty
these routes answer 401 for everyone. There is no administrator role among residents and no
multi-factor step: whoever holds the secret administers every neighborhood of the deployment.

| Method and path | Purpose |
| --- | --- |
| `POST` / `GET /admin/households` | Add a household ahead of time (house number, street, optional provider and coordinates); list households. Adding one is optional: enrolment creates households by itself |
| `POST /admin/households/suspend`, `/reinstate`, `/remove` | Membership control |
| `POST` / `GET /admin/invitations`, `POST /admin/invitations/revoke` | Invitations. `max_uses` (1 to 20, default 4) is how many phones a link can enrol; `expires_in_hours` (default 72, at most 720); optional `household_id` binds the link to one existing household. The link is returned once, at creation |
| `POST /admin/devices/revoke` | Remove a lost or compromised phone |
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

## Example: enrolling a phone

```http
POST /api/v1/enroll
Content-Type: application/json

{
  "invitation_token": "<token from the link>",
  "street_name": "Example Road",
  "house_number": null,
  "privacy_acknowledged": true,
  "device_id": "5a791633-08fa-58b4-851a-7c3f0ca1bae6",
  "platform": "android",
  "os_version": "15",
  "app_version": "0.1.0",
  "capabilities": {"can_measure_rssi": true, "background_monitoring": "callback"}
}
```

The 201 response carries `device_token` (the device secret, shown only here), `household` and
`device`. Exact field types are in the contract.

## Example: uploading events

```http
POST /api/v1/connectivity/events
Authorization: Bearer <device secret>
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

`device_id` must be the calling phone's own id. `result` is `accepted`, `duplicate` (already
stored; safe to drop from the queue) or `rejected` with `reason` `rate_limited` (keep and retry
later), `timestamp_in_future` or `too_old` (drop).

## Versioning

Breaking changes get a new prefix (`/api/v2`). Adding optional fields or enum values to responses
is not breaking; clients must ignore fields they do not know and treat unknown enum values as
unknown.

The removal of sign-in on 2026-11-23 was a breaking change made inside `/api/v1` (removed:
`POST /auth/register`, `POST /households/join`, `DELETE /account`; added: `POST /enroll`). That was
acceptable only because nothing was deployed and no app build had been distributed.
