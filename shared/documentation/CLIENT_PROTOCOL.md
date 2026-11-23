# Client protocol: what the Android and iOS apps must do

This is the behaviour contract for both mobile apps. Field-level types are in
`backend/watchdog/schemas.py` and `backend/watchdog/enums.py` (exported to
`shared/api-contracts/openapi.json`). Where this document and the schema disagree, the schema wins
and this document must be fixed.

Revised 2026-11-23 for the removal of sign-in (decision 0014). The backend implements what is
described here and its tests pass. The two apps are being updated to it and have not been
re-verified against this version yet (`docs/STATUS.md`).

## 1. Configuration (build-time, no secrets)

| Key | Meaning |
| --- | --- |
| `API_BASE_URL` | Backend origin, e.g. `https://api.example.org`. All API paths start with `/api/v1`. |
| `SOURCE_REPO_URL`, `PRIVACY_URL`, `SECURITY_CONTACT` | Shown in the Transparency screen. |

Debug builds may point at `http://10.0.2.2:8000` (Android emulator) or `http://localhost:8000`
(iOS simulator). Release builds must use HTTPS only.

The apps register the URL scheme `feluda` so that the invitation page can open them
(`feluda://join#t=<token>`). There are no sign-in settings: no identity provider is involved.

## 2. Identity of a phone (no sign-in)

Nobody signs in. There are no accounts, e-mail addresses, passwords, one-time codes, access tokens
or refresh tokens.

1. Enrolment (`POST /api/v1/enroll`, section 3) returns `device_token`: a random secret for this
   phone. It is returned once.
2. Store it only in the Keychain (iOS, this device only) or in a Keystore-encrypted store
   (Android). Never log it, never put it in a backup, never show it.
3. Every API request after enrolment sends `Authorization: Bearer <device_token>`.
4. The secret does not expire and is never refreshed. There is no logout call.

Rules:
- A `401` from an authenticated call means this phone is not enrolled any more (it was removed by
  its household or the organizer, or the household was removed). Do not retry with the same
  secret. Stop monitoring, delete the secret, and show the "enter invitation" screen. Generate a
  new install id before enrolling again: the id of a removed phone is refused.
- A `403` with code `household_suspended` means the organizer suspended the household. Keep the
  secret, stop uploading, show the message. `GET /api/v1/households/me`, removing a phone and
  leaving still work.
- The phone never sends a household or neighborhood id; the server takes both from the enrolment.

## 3. Onboarding flow

1. Welcome and education screens (what is monitored, what is transmitted, what is not collected, why
   cellular is used, why background monitoring is limited, how correlation works, how to turn it
   off, how to leave). Explicit consent checkbox and button. Monitoring never starts before consent.
2. Obtain the invitation: the app was opened by `feluda://join#t=<token>`, or the resident pastes
   the invitation link (`https://<host>/join#t=<token>`) or the bare token. Optionally call
   `POST /api/v1/auth/verify-invitation` with `{"invitation_token": "..."}` to show the
   neighborhood name before asking anything. (The path keeps an old name; it needs no credential.)
3. Ask for the street name (required) and the house number (optional, may be left empty). Ask for
   nothing else: no name, no e-mail address, no phone number.
4. `POST /api/v1/enroll` with `invitation_token`, `street_name`, `house_number` (or null),
   `privacy_acknowledged: true` (only after the resident saw the privacy summary and agreed), a
   random install UUID as `device_id` (generated once, stored locally; never a hardware
   identifier), `platform`, `os_version`, `app_version` and the capability flags. The 201 response
   has `device_token`, `household` and `device`. Store the secret (section 2).
   - If the answer is lost (timeout, crash), send the same request again with the same `device_id`
     and the same invitation. The server returns a fresh secret and does not count a second use.
   - `404 invitation_invalid`: the link is unknown, expired, used up or withdrawn. Say so and ask
     for a new link from the organizer.
   - `409 household_already_registered`: that address already takes part and this link is not the
     one for it. Show the server's message (use the home's own link, or ask the organizer).
   - `409 device_conflict`: this install id is already enrolled another way. Show the message.
   - `422 validation_error`: the street was empty or unusable, or the privacy summary was not
     acknowledged.
5. Ask for notification permission, obtain the push token, `POST /api/v1/notifications/register`.
6. Home-network step: ask the resident to confirm "this phone is on my home Wi-Fi now" and store the
   home-network fingerprint locally (section 5). Skippable; if skipped, `home_network_match` is null.
7. Start monitoring; emit `MONITORING_STARTED`.

An optional household label and the internet provider are not part of enrolment. The resident can
set them afterwards (`PATCH /api/v1/households/me`).

`POST /api/v1/devices/register` no longer creates anything. Call it with the phone's own
`device_id` when the OS version, the app version or the capability flags change.

Invitation links look like `https://<host>/join#t=<token>`. The token is in the URL fragment so it is
not sent to servers by link previews. The app accepts the `feluda://join#t=<token>` form, the full
web link or the bare token. One link enrols a limited number of phones (4 by default) into one
household: the first phone's street and house number define it, and later phones with the same link
join it whatever they type. Every phone of a home should use the same link.

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
- The fingerprint and the salt never leave the device and are deleted when the phone leaves or is
  found to be no longer enrolled.

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
- Leave: `POST /api/v1/households/leave`. The server deletes this phone, its secret and everything
  it reported. There is no account, so there is no separate "delete account" action. Afterwards:
  stop monitoring, clear the device secret, queue, fingerprint and install id.
- Phones of this home: `GET /api/v1/devices` lists them (platform and versions);
  `POST /api/v1/devices/revoke` removes one. A phone may remove itself or another phone of its own
  household, for example a lost one.
- Transparency screen: source repository link, app version and build, privacy policy, list of data
  transmitted, permissions currently granted, last successful report time, monitoring status and
  platform limits, security review summary link, security contact, and a viewer of the local event
  queue and the last 50 sent events (so residents can inspect what the app reports).

## 10. Errors

Error body: `{"error": {"code": "...", "message": "...", "request_id": "..."}}`.
Codes used: `unauthorized` (401: not enrolled), `forbidden` (403), `household_suspended` (403),
`device_revoked` (403), `device_not_registered` (404), `invitation_invalid` (404),
`household_already_registered` (409), `device_conflict` (409), `validation_error` (422),
`rate_limited` (429, with `Retry-After`), `not_found` (404).
`not_a_member` and `address_not_recognized` no longer exist.

## 11. Clarifications agreed while building the apps

- `wifi_validated`: Android uses the operating system's validated flag. iOS has no such flag and
  uses the app's own HTTPS probe with cellular access disallowed. `internet_reachable` is the
  any-path probe on both.
- `MONITORING_PERMISSION_REQUIRED` is never produced on iOS (path monitoring needs no permission).
  `PERMISSION_CHANGED` is sent for notification-permission and Background App Refresh changes.
- Cellular-only states (`CELLULAR_AVAILABLE` / `CELLULAR_UNAVAILABLE` as a state) apply only to an
  install that has never seen Wi-Fi since monitoring was set up. Otherwise no Wi-Fi with cellular
  is `WIFI_DISCONNECTED`.
- No Wi-Fi and no cellular but another usable path (wired, for example): `WIFI_DISCONNECTED`.
- Wi-Fi associated again without confirmed internet, or a first association with no earlier loss:
  `WIFI_ASSOCIATION_CHANGED`. `WIFI_RESTORED` is sent only once internet over Wi-Fi is confirmed
  after a loss.
- A cold launch after a state change emits `MONITORING_GAP_DETECTED` even when the gap is shorter
  than 2 minutes, so a `RECONCILED` event always follows a gap event.
- `external_power_lost_recently` is evaluated when the event is built (about 5 seconds after it
  occurred).
- Queue cap drop order: heartbeats, then lifecycle events, then the oldest connectivity events.
- A `422` on a batch is retried one event at a time; the refused event is dropped and logged
  locally. A `404 device_not_registered` means the `device_id` in the request is not the id this
  phone enrolled with; registration cannot repair that any more, so the events stay queued and the
  app reports the error locally.
- There is no sign-out. The nearest thing is leaving (section 9).
- Push payload custom keys: FCM puts them in `data`; APNs puts them at the top level of the
  payload next to `aps`. Apps should read both places.
- `reported_via` is the transport the app believes the upload used. It is not proof of routing.
- The second probe target is configurable (`PROBE_FALLBACK_URL`); the default is a plain HTTPS
  request to a large public site and is disclosed on the Transparency screen.
- UUIDs may be sent in upper or lower case.
- `band_evidence` on a `WIFI_LOST`: either the band last observed (or `BAND_UNKNOWN`), or
  `MULTI_BAND_FAILURE_NOT_VERIFIED`. Both are accepted; the backend stores the value as evidence
  and does not score it. Android sends the band last observed.
- Android: `cellular_available` is null while on Wi-Fi unless a cellular network is actually up
  (Android usually keeps mobile data down on Wi-Fi). `CELLULAR_UNAVAILABLE` is emitted only after
  cellular was seen available.
- Android: `external_power_lost_recently` covers 15 seconds before to about 5 seconds after the
  event, so the report is not held back.
- First-ever Wi-Fi association: Android sends `WIFI_RESTORED`, iOS sends
  `WIFI_ASSOCIATION_CHANGED`. The backend treats both as a healthy report with no preceding loss.
- Android reports any stretch of 2 minutes or more in which its process ran no code as
  `MONITORING_GAP_DETECTED` (mostly `app_suspended`), and sends callbacks that arrive within 10
  seconds after such a stretch as `RECONCILED`. Gaps are therefore frequent on both platforms; the
  backend treats a gap as unknown, never as healthy.
- Leaving removes the phone on the server, so no `MONITORING_STOPPED` event is sent for it.
- Rejection reason for a rate limit is exactly `rate_limited`.
