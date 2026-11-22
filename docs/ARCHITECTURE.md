# Architecture

Neighborhood Watchdog is a private, invitation-only network that notices unusual, possibly
coordinated Wi-Fi disruptions across a neighborhood. Phones report connectivity changes; the
backend correlates reports from nearby households and alerts residents.

**It is a wireless anomaly detector, not a jammer detector.** A phone app cannot measure radio
interference. Every classification is an inference from connectivity reports and is labelled so.

## Components

```text
 Android app (Kotlin, Compose)        iOS app (Swift, SwiftUI)
   NetworkCallback + WorkManager        NWPathMonitor + BGAppRefresh
   durable event queue (Room)           durable event queue (file)
        |  HTTPS (Bearer access token)        |
        +----------------+--------------------+
                         v
              Backend API (FastAPI, one process)
   +-----------------------------------------------------------+
   | authentication   verify access tokens, resolve membership |
   | api              /api/v1 routes, request validation        |
   | services         membership, devices, ingestion, status,   |
   |                  incidents, administration, retention      |
   | correlation      pure engine + storage of incidents        |
   | notifications    planner, dispatcher, FCM and APNs senders |
   | pipeline         glue + periodic maintenance tick          |
   +-----------------------------------------------------------+
        |                         |                    |
        v                         v                    v
  PostgreSQL (schema nw)    Supabase Auth        FCM / APNs
  row-level security        (e-mail codes)       (push delivery)
```

Sign-in happens between the apps and the identity provider. The backend never sees codes or
refresh tokens; it only verifies access tokens.

## Repository layout

| Path | Contents |
| --- | --- |
| `apps/android` | Android app. Own Gradle build. |
| `apps/ios` | iOS app. Own Xcode project (generated from `project.yml`). |
| `apps/admin` | Small command-line client for the administrator API. |
| `backend` | Python package `watchdog`, SQL migrations, tests, Dockerfile. |
| `shared/api-contracts/openapi.json` | The API contract, generated from the backend. |
| `shared/documentation/CLIENT_PROTOCOL.md` | Behaviour both apps must implement. |
| `infrastructure` | Hosting config, scripts, Supabase and monitoring notes. |
| `docs` | This documentation. |
| `ai/docs/decisions` | Decision records with reasons and rejected options. |
| `jtbd` | Open and finished work. |

Each of Android, iOS and backend builds, tests and ships on its own; they share only the contract.

## How an event becomes an alert

1. The phone's monitor sees a change (for example Wi-Fi lost), waits about 5 seconds so a roam does
   not count, writes an event to its durable queue, and uploads it (over cellular when it can).
2. `POST /api/v1/connectivity/events`: the backend checks the device belongs to the caller, drops
   duplicates by event id, validates timestamps, applies per-device and per-household limits, and
   stores the event with both its occurrence time and its receipt time.
3. Correlation runs at once for that neighborhood: events become household failure episodes,
   episodes close in time (60 s) and space (500 m) become clusters, clusters are classified
   (`docs/DETECTION_PROTOCOL.md`), incidents are created or updated.
4. The notification planner creates one delivery row per device that should hear about it; the
   dispatcher sends through FCM or APNs after the HTTP response, with retries and tracking.
5. A tick every 20 seconds re-evaluates open incidents (escalation on persistence, resolution,
   expiry), retries notifications, and runs retention every 6 hours.

There is no message broker: PostgreSQL is the queue (decision 0011).

## Data model

Schema `nw` (see `backend/migrations/0001_initial_schema.sql`): `users`, `neighborhoods`,
`households`, `household_members`, `invitations`, `devices`, `device_sessions`,
`connectivity_events`, `incident_candidates`, `incidents`, `incident_households`,
`notification_tokens`, `notification_preferences`, `notification_deliveries`, `admin_audit_logs`,
`security_events`, `system_configuration`, `rate_limit_counters`, `schema_migrations`.

All primary keys are UUIDs, all timestamps are `timestamptz` in UTC, every table has `created_at`
and `updated_at`, and row-level security is enabled on every table. Telemetry tables hold only
UUIDs; addresses and e-mail addresses live in `households` and `users` and are unreadable to the
correlation role.

## Platform capabilities (they are not the same)

| Capability | Android | iOS |
| --- | --- | --- |
| Live network callbacks | While the process is alive | While the app is running; suspended in the background |
| Periodic check | WorkManager, about every 15 minutes | Only when iOS grants a background refresh |
| Signal strength before a loss | Yes (no location permission needed) | No |
| Wi-Fi band | When the OS exposes the frequency | No |
| Router reachable on the local network | TCP probe to the gateway | Not measured |
| Wi-Fi switched off / airplane mode known | Yes | No |
| Force an upload over cellular | Yes (`requestNetwork`, socket bound to the cellular network) | No; the system chooses the path |
| Monitoring gaps | Reported | Reported; expected to be frequent |

The backend records each device's capability flags and treats a missing measurement as unknown,
never as a value.

## Technology choices

Kotlin, Jetpack Compose, Hilt, Retrofit/OkHttp, kotlinx.serialization, WorkManager, Room, Android
Keystore, FCM. Swift, SwiftUI, URLSession, Network framework, Keychain, APNs, BackgroundTasks, no
third-party iOS dependencies. Python 3.12, FastAPI, psycopg 3, PyJWT, httpx, PostgreSQL 16 (13 or
later works). Supabase for managed PostgreSQL and sign-in. Docker for local development and
deployment. GitHub Actions for CI. Reasons and alternatives are in `ai/docs/decisions/`.

## Known limitations

- Phones cannot observe radio energy; only its effect on their own connection.
- A phone is on one Wi-Fi band at a time; "both bands failed" is never claimed.
- iOS monitoring has gaps by design of the operating system.
- A household whose only phone is away from home is not monitored at all.
- Detection thresholds are untested against real disruptions (see decision 0007).
- One API process per environment is assumed (decision 0011).
