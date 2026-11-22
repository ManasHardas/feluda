# Client protocol: what the Android and iOS apps must do

This is the behaviour contract for both mobile apps. Field-level types are in
`backend/watchdog/schemas.py` and `backend/watchdog/enums.py` (exported to
`shared/api-contracts/openapi.json`). Where this document and the schema disagree, the schema wins
and this document must be fixed.

## 1. Configuration (build-time, no secrets)

| Key | Meaning |
| --- | --- |
| `API_BASE_URL` | Backend origin, e.g. `https://api.example.org`. All API paths start with `/api/v1`. |
| `AUTH_BASE_URL` | Supabase Auth (GoTrue) origin plus `/auth/v1`, e.g. `https://xyz.supabase.co/auth/v1`. In local development the backend emulates it at `http://<host>:8000/auth/v1`. |
| `AUTH_PUBLIC_KEY` | Supabase publishable (anon) key, sent as the `apikey` header to the auth endpoints. Public by design. Never the service-role key. |
| `SOURCE_REPO_URL`, `PRIVACY_URL`, `SECURITY_CONTACT` | Shown in the Transparency screen. |

Debug builds may point at `http://10.0.2.2:8000` (Android emulator) or `http://localhost:8000`
(iOS simulator). Release builds must use HTTPS only.

## 2. Authentication (email one-time code)

The apps talk to the auth service directly; the backend only verifies the resulting access token.

1. `POST {AUTH_BASE_URL}/otp` body `{"email": "...", "create_user": true}` with header `apikey`. Response 200 `{}`.
2. `POST {AUTH_BASE_URL}/verify` body `{"type": "email", "email": "...", "token": "123456"}`.
   Response 200 `{"access_token", "refresh_token", "expires_in", "token_type": "bearer", "user": {"id", "email"}}`.
3. `POST {AUTH_BASE_URL}/token?grant_type=refresh_token` body `{"refresh_token": "..."}`. Same response shape.
   Refresh tokens rotate: store the new one, discard the old one.
4. `POST {AUTH_BASE_URL}/logout` with `Authorization: Bearer <access_token>`.

Rules:
- Store access and refresh tokens only in the Keychain (iOS) or in a Keystore-encrypted store (Android).
- Refresh when the access token has less than 60 seconds left, and once on a `401` from the API, then retry once.
- Serialise refreshes: never run two refresh calls at once (rotation would invalidate one).
- Every API request sends `Authorization: Bearer <access_token>`.
- An API `403` with code `not_a_member` means the email is not registered through an invitation: show the "enter invitation" screen.

## 3. Onboarding flow

1. Welcome and education screens (what is monitored, what is transmitted, what is not collected, why
   cellular is used, why background monitoring is limited, how correlation works, how to turn it
   off, how to leave). Explicit consent checkbox and button. Monitoring never starts before consent.
2. Sign in with email code (section 2).
3. `GET /api/v1/households/me`.
   - 200: member. Continue.
   - 403 `not_a_member`: ask for the invitation link or code, house number, street name, optional
     label and provider, then `POST /api/v1/households/join`. (Residents who registered on the web
     page already are members and skip this.)
4. `POST /api/v1/devices/register` with a random install UUID (generated once, stored locally; never
   a hardware identifier) and the capability flags.
5. Ask for notification permission, obtain the push token, `POST /api/v1/notifications/register`.
6. Home-network step: ask the resident to confirm "this phone is on my home Wi-Fi now" and store the
   home-network fingerprint locally (section 5). Skippable; if skipped, `home_network_match` is null.
7. Start monitoring; emit `MONITORING_STARTED`.

Invitation links look like `https://<host>/join#t=<token>`. The token is in the URL fragment so it is
not sent to servers by link previews. The app accepts either the full link or the bare token.

## 4. Connectivity states and events

State machine output is exactly one `ConnectivityState`:

| Condition | State |
| --- | --- |
| Monitoring off by user | `MONITORING_PAUSED` |
| A needed permission is missing | `MONITORING_PERMISSION_REQUIRED` |
| Wi-Fi up and internet reachable | `HEALTHY` |
| Wi-Fi associated, internet not reachable over it | `WIFI_CONNECTED_NO_INTERNET` |
| No Wi-Fi, cellular usable | `WIFI_DISCONNECTED` |
| No Wi-Fi, no cellular, no other path | `INTERNET_UNAVAILABLE` |
| A change happened less than about 5 seconds ago and has not settled | `NETWORK_TRANSITION` |
| Cannot tell | `UNKNOWN` |

`CELLULAR_AVAILABLE` / `CELLULAR_UNAVAILABLE` exist in the enum for cellular-only observations (a
device that never had Wi-Fi in this session); they are also event types.

Debounce: wait about 5 seconds after a callback before classifying a loss, so a roam or a band
switch does not produce a `WIFI_LOST`. The event's `occurred_at` is the time of the first callback,
not the time the debounce finished.

Events to emit (one per real change, never synthetic):

| Event type | When |
| --- | --- |
| `WIFI_LOST` | Wi-Fi association lost and not back after the debounce. |
| `WIFI_RESTORED` | Wi-Fi back with internet after a loss. |
| `INTERNET_REACHABILITY_CHANGED` | Wi-Fi associated but internet reachability flipped (either way). |
| `DEFAULT_NETWORK_CHANGED` | The default route moved to another transport. |
| `CELLULAR_AVAILABLE` / `CELLULAR_UNAVAILABLE` | Cellular path appeared or disappeared. |
| `SIGNAL_STRENGTH_CHANGED` | Android only; at most one per 10 minutes and only for a change of 15 dB or more. |
| `WIFI_ASSOCIATION_CHANGED` | The associated network changed (home to not-home or back), where detectable. |
| `MONITORING_STARTED` / `MONITORING_RESUMED` / `MONITORING_STOPPED` | Monitor lifecycle. |
| `MONITORING_DISABLED_BY_USER` | Resident switched monitoring off. State is `MONITORING_PAUSED`. |
| `MONITORING_GAP_DETECTED` | On resume, when the monitor was not running for more than 2 minutes. Fill `gap_started_at` (last persisted "alive" time), `gap_ended_at`, `gap_reason`. |
| `PERMISSION_CHANGED` | A needed permission was granted or revoked. |
| `HEARTBEAT` | Periodic "still monitoring, state is X". Android: WorkManager every 15 minutes. iOS: whenever the app runs (foreground timer every 5 minutes, background refresh when iOS grants it). |

Reconciliation after a gap: compare the state persisted before the gap with the state now. If it
changed, emit the matching event with `confidence = RECONCILED` and `occurred_at` = resume time, and
always emit `MONITORING_GAP_DETECTED` first. Never back-date a reconciled event.

Diagnostics rules:
- A value that was not measured is `null`. Never send a default as if it were a measurement.
- `internet_reachable`: result of an HTTPS request to `GET {API_BASE_URL}/api/v1/monitoring/health`
  and, if that fails, a second independent HTTPS probe target; true if either answers.
- `local_gateway_reachable`: Android: TCP connect attempt to the gateway address from
  `LinkProperties` (ports 53, 80, 443; connection refused also counts as reachable). Not ICMP.
  iOS: null unless the Local Network permission was granted; do not ask for it only for this.
- `last_wifi_rssi_dbm`: Android only, last value seen before the loss. iOS: null.
- `band_evidence`: Android from the Wi-Fi frequency when the OS exposes it, else `BAND_UNKNOWN`.
  iOS: always `BAND_UNKNOWN`. Never `MULTI_BAND_FAILURE_NOT_VERIFIED` unless both bands were actually
  tried; in practice the apps send it only to say "a loss happened and other bands were not checked".
- `external_power_lost_recently`: true only if the phone was charging and the charger disconnected
  within 15 seconds either side of the event. False if it was charging and stayed charging. Null if
  it was not charging.
- `wifi_radio_enabled`, `airplane_mode`: Android only. iOS: null.
- `user_initiated`: true when the app knows the user caused it (monitoring toggle, Wi-Fi switched off).

## 5. Home-network fingerprint (stays on the device)

Purpose: a phone leaving the house loses Wi-Fi too. Only loss of the *home* network matters.

- Android: from `LinkProperties` of the Wi-Fi network: default gateway addresses (IPv4 and the
  IPv6 link-local router address) plus the interface's IPv4 prefix. SHA-256 with a random salt
  stored locally. No location permission is used, so SSID and BSSID are not read.
- iOS: from `NWPath.gateways` on the Wi-Fi interface (IPv4 and IPv6 endpoints). Same hashing.
- Compare on each Wi-Fi association. `home_network_match` = true / false, or null when no
  fingerprint was stored or the gateways were not available.
- The fingerprint and the salt never leave the device and are deleted on sign-out.

## 6. Reporting

`POST /api/v1/connectivity/events` with up to 50 events per request.

- Every event is written to a durable local queue (database) before any network attempt.
- Upload order: oldest first. Remove an event from the queue only when its outcome is `accepted`,
  `duplicate`, or `rejected` with a reason other than `rate_limited`.
- On network failure, HTTP 5xx or `rate_limited`: keep the events and retry with exponential backoff
  (start 5 s, factor 2, cap 15 minutes, with jitter). Honour `retry_after_seconds`.
- `event_id` is generated once when the event is created and never changes across retries.
- `occurred_at` never changes. `sent_at` is the time of this upload attempt.
- Queue cap: 2,000 events or 7 days; drop the oldest `HEARTBEAT` events first, never failure events
  inside the cap.
- When Wi-Fi is lost or has no internet, try the cellular network explicitly:
  - Android: `ConnectivityManager.requestNetwork` with `TRANSPORT_CELLULAR` + `NET_CAPABILITY_INTERNET`,
    then bind the HTTP client to that `Network` (`network.socketFactory`). Set `reported_via = cellular`
    only when the request really went through that network.
  - iOS: the system chooses the path. Use a `URLSession` with `allowsCellularAccess = true`,
    `allowsExpensiveNetworkAccess = true`, `allowsConstrainedNetworkAccess = true`,
    `waitsForConnectivity = true`. `reported_via` is the interface type of the current `NWPath`
    at send time; say `unknown` if it cannot be told. Never claim guaranteed cellular routing.
- If no network works, keep the events; a retry is triggered by the next connectivity callback.

## 7. Status the app shows

- `GET /api/v1/connectivity/status` gives the household status, colour and explanation text.
- `GET /api/v1/neighborhoods/me` gives the neighborhood colour and explanation.
- `GET /api/v1/incidents` and `/incidents/{id}` give incident lists and details.
- Colours: GREEN no current anomaly; YELLOW household connectivity interruption; ORANGE multiple
  nearby disruptions; RED high-priority suspicious neighborhood disruption; GREY monitoring inactive
  or unknown. GREY is never presented as safe. Always show the explanation text next to the colour.
- If the app cannot reach the backend, the main screen says so and shows the local device state only.
- Never show "confirmed jamming" or similar. Use: connectivity interruption, wireless connectivity
  anomaly, correlated wireless disruption, suspected RF interference, elevated neighborhood alert.

## 8. Push notifications

Payload `data` keys (strings): `incident_id`, `alert_level` ("1".."3"), `kind`
(`alert` | `escalation` | `resolution`), `classification`. Title and body come from the server.
Tapping opens the incident screen. On a new token from the OS, call `/notifications/register` again.

## 9. Settings, transparency, leaving

- Monitoring toggle: off emits `MONITORING_DISABLED_BY_USER` and stops the monitor.
- Notification preferences: `GET` / `PUT /api/v1/notifications/preferences`.
- Leave neighborhood: `POST /api/v1/households/leave`. Delete account: `DELETE /api/v1/account`.
  After either: stop monitoring, clear tokens, queue, fingerprint and install id.
- Transparency screen: source repository link, app version and build, privacy policy, list of data
  transmitted, permissions currently granted, last successful report time, monitoring status and
  platform limits, security review summary link, security contact, and a viewer of the local event
  queue and the last 50 sent events (so residents can inspect what the app reports).

## 10. Errors

Error body: `{"error": {"code": "...", "message": "...", "request_id": "..."}}`.
Codes used: `unauthorized` (401), `not_a_member` (403), `forbidden` (403), `household_suspended` (403),
`device_revoked` (403), `device_not_registered` (404), `invitation_invalid` (404),
`address_not_recognized` (422), `household_already_registered` (409), `validation_error` (422),
`rate_limited` (429, with `Retry-After`), `not_found` (404).
