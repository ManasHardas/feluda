# Status

Last updated 2026-11-23. This page is the honest scoreboard: what exists, what was actually run,
and what was not.

**Sign-in was removed on 2026-11-23 (decision 0014).** Phones are now enrolled with an invitation
link; there are no accounts, e-mail addresses or codes. Backend and both apps are changed and their automated tests pass,
including each app's real networking code enrolling against a running backend. **The new join
screens have not been tapped through on screen yet**, and nothing has run on a physical phone.

| Word | Meaning |
| --- | --- |
| IMPLEMENTED | The code exists. Nothing more is claimed |
| AUTOMATED_TESTED | Automated tests exist and were seen to pass on the build machine |
| MANUAL_TEST_REQUIRED | Needs a person and a physical phone or a real account; not done |
| EXTERNAL_CREDENTIAL_REQUIRED | Needs an account, key or certificate the build did not have; not done |
| EXTERNAL_REVIEW_REQUIRED | Needs a third party (Apple, an independent reviewer); not done |
| NOT_IMPLEMENTED | Does not exist |

"In progress" below means the work has started and is neither finished nor verified.

## What was actually run

### Since sign-in was removed

| What | Result | When |
| --- | --- | --- |
| Backend: all tests against PostgreSQL 16 (unit, enrolment, security, scenarios A to J, notifications, admin), `make backend-test` | **143 passed, 0 failed** | 2026-11-23 |
| Backend: lint and format check | clean | 2026-11-23 |
| API contract check (`openapi.json` matches the code) | up to date (28 paths) | 2026-11-23 |
| Android app with the new enrolment flow: `assembleDebug testDebugUnitTest lintDebug assembleRelease` | **87 passed, 1 skipped (the live test); lint 0 errors; R8 release build succeeds** | 2026-11-23 |
| Android networking code against a running local backend (`make live-android`): enrol, retry, second phone joins the same household, third refused, events, status, incidents, leave, 401 after leaving | **passed** | 2026-11-23 |
| iOS app with the new enrolment flow: simulator tests (`make ios-test`, Xcode 27.2) | **100 run, 1 skipped (the live test), 0 failures** | 2026-11-23 |
| iOS core tests natively (`make ios-core-test`) | **89 run, 1 skipped, 0 failures** | 2026-11-23 |
| iOS networking code against a running local backend (`make live-ios`): same journey as Android | **passed** | 2026-11-23 |
| Live contract checks (`make live-ios`, `make live-android`) against the new backend | **passed** | 2026-11-23 |
| Backend tests inside containers (`make backend-test-docker`) | not re-run since the change | |
| Backend started as a real server and exercised by hand; invitation page | not repeated since the change | |
| Administrator tool (`apps/admin/nwadmin.py`) with the operator secret, against staging | `summary` worked | 2026-11-23 |
| Migration 0002 on the staging database | applied and compared with a local database | 2026-11-23 |
| GitHub Actions on the no-sign-in change (pull request 13) | all checks passed | 2026-11-23 |
| **Staging backend deployed** on Fly.io (`feluda-staging`, Virginia) against the Supabase database | smoke test passed; an invite, join, status and leave with a throwaway test phone worked end to end and left no data | 2026-11-23 |
| Push credentials on the staging server | the Firebase key obtained a Google access token; the APNs key signs a provider token. **No push has been sent to a phone** | 2026-11-23 |
| Anything on a physical phone | **not done** | |
| Any deployment | **not done** | |
| Any real push notification | **never sent** | |

### Before sign-in was removed (kept as a record; the app rows no longer describe the current code)

| What | Result | When |
| --- | --- | --- |
| Backend: all tests against PostgreSQL 16 | 144 passed, 0 failed | 2026-11-22 |
| Backend: migrations on an empty database, re-run, pending check | applied; no-op; nothing pending | 2026-11-21 |
| Backend: seed script, operator command | 24 households seeded; listed | 2026-11-21 |
| Backend: real server start, health, registration page, scenario C replayed over HTTP | worked; incident classified and explained as expected | 2026-11-21 |
| Backend tests inside containers (`make backend-test-docker`), which also builds the image's build stage | 143 passed, 1 skipped (the repository secret scan needs git) | 2026-11-22 |
| Backend dependency audit (pip-audit) | First run found advisories in PyJWT 2.10.1 and Starlette 0.41.3; dependencies upgraded; **no known vulnerabilities** afterwards | 2026-11-22 |
| Backend SBOM (`make sbom`, CycloneDX) | generated | 2026-11-22 |
| Staging database (Supabase): migration 0001 | applied and compared with a local database | 2026-11-22 |
| iOS: Debug and Release builds for the simulator | BUILD SUCCEEDED | 2026-11-21 |
| iOS: app launched once in the simulator | first onboarding screen rendered; nothing else exercised | 2026-11-21 |
| iOS tests on the simulator, after the macOS update to 27.2.1 with Xcode still 26.3 | could not run ("test runner hung before establishing connection") | 2026-11-22 |
| iOS tests on the simulator after updating Xcode to 27.2 (`make ios-test`) | 98 run, 1 skipped (live test), 0 failures | 2026-11-22 |
| iOS core tests run natively on the Mac (`make ios-core-test`) | 87 executed, 0 failures (1 skipped: the live test) | 2026-11-22 |
| Android: `assembleDebug testDebugUnitTest lintDebug assembleRelease` | BUILD SUCCESSFUL; unit tests passed, 0 failed (90 recorded for this run; 91 at the last run before the change); lint passed; unsigned release APK built with R8 | 2026-11-22 |
| Live contract checks, iOS and Android, old flow (sign-in by code, join, upload, status, incidents, revoke, leave) | passed, no contract mismatch | 2026-11-22 |
| GitHub Actions on pull request 1 (merged) | green | 2026-11-22 |

The production container image target (`runtime`) has not been built on its own on the build
machine; CI does that.

## Definition of done, item by item

| # | Requirement | Status | Evidence or gap |
| --- | --- | --- | --- |
| 1 | Android application compiles | AUTOMATED_TESTED | Debug and release builds, unit tests and lint pass with enrolment by link (2026-11-23). Never installed on a phone or emulator |
| 2 | iOS application compiles | AUTOMATED_TESTED | Simulator build and tests pass with enrolment by link (2026-11-23). No signed device build yet |
| 3 | Backend starts locally | AUTOMATED_TESTED | `test_smoke.py`; started and exercised by hand before the change |
| 4 | Database migrations execute successfully | AUTOMATED_TESTED | `test_migrations_are_complete_and_immutable` covers 0001 and 0002; both applied to staging |
| 5 | User registration works | Backend: AUTOMATED_TESTED. Apps: AUTOMATED_TESTED (live journey); screens not run | Registration is now enrolment by invitation link with only a street asked: `test_full_enrolment_journey_asks_only_for_the_street`, `test_nothing_personal_is_stored_or_accepted`. No e-mail is sent, so nothing depends on a mail sender any more |
| 6 | Invitation verification works | AUTOMATED_TESTED | Use limit, expiry, revocation, uniform errors, retry without using the link twice |
| 7 | Household membership is enforced server-side | AUTOMATED_TESTED | API tests and direct row-level-security tests; a foreign link cannot attach a phone to an enrolled home |
| 8 | Device registration works | AUTOMATED_TESTED | Enrol, update, remove by the own household only, operator revoke |
| 9 | Connectivity events can be recorded | AUTOMATED_TESTED | Ingestion, idempotency, limits |
| 10 | Events reported independently of home Wi-Fi when supported | IMPLEMENTED; MANUAL_TEST_REQUIRED | Android binds uploads to the cellular network (unit-tested with fakes; never run on a phone). iOS cannot force an interface; the system decides. Needs real phones |
| 11 | Failed event uploads are queued | AUTOMATED_TESTED on both platforms before the change | Unit tests with fake transports; not re-run since |
| 12 | Neighborhood correlation works | AUTOMATED_TESTED | Scenarios A to J and 49 engine unit tests |
| 13 | Incident classification is explainable | AUTOMATED_TESTED | Score breakdown, supporting and missing evidence stored and asserted |
| 14 | Push notification integrations are implemented | IMPLEMENTED; AUTOMATED_TESTED against fake transports; EXTERNAL_CREDENTIAL_REQUIRED | FCM and APNs request shape, retries, tracking tested. **No real push has ever been sent** |
| 15 | Security tests pass | AUTOMATED_TESTED | 41 tests |
| 16 | Simulation tests pass | AUTOMATED_TESTED | 11 tests |
| 17 | Privacy documentation is complete | IMPLEMENTED | `docs/PRIVACY.md`, `/privacy` page, in-app text. Not reviewed by a lawyer |
| 18 | Deployment documentation is complete | IMPLEMENTED | `docs/DEPLOYMENT.md`. The steps have never been followed end to end |
| 19 | CI pipelines run successfully | Partly | The pull-request workflows ran green on pull request 1 (2026-11-22), before this change. The release, distribution, deploy and uptime workflows have never run |
| 20 | Private beta distribution documented and ready for account configuration | IMPLEMENTED; EXTERNAL_CREDENTIAL_REQUIRED; EXTERNAL_REVIEW_REQUIRED (Apple) | `docs/APP_DISTRIBUTION.md`, two manual workflows |

## Feature status

### Backend

| Feature | Status |
| --- | --- |
| Invitations, enrolment by link, households, removing a phone, leaving | AUTOMATED_TESTED |
| Device secret for phones and operator secret for administration (no sign-in) | AUTOMATED_TESTED |
| Per-person administrator credentials, second factor, expiring or rotating device secrets | NOT_IMPLEMENTED, by decision 0014 |
| Staging database (Supabase) | Migrations 0001 and 0002 applied. The backend connects as the restricted login `nw_app_login` through the session pooler |
| Row-level security and restricted roles | AUTOMATED_TESTED |
| Event ingestion, rate limits, replay protection | AUTOMATED_TESTED |
| Detection engine, correlation, incident lifecycle | AUTOMATED_TESTED with simulated data only; **never validated against a real disruption**. Residents are not asked for a location, so the distance check applies only to households the organizer adds with coordinates |
| Notifications: planning, preferences, quiet hours, retries, expiry, resolution | AUTOMATED_TESTED with fake senders |
| SMS | NOT_IMPLEMENTED (optional in the brief, off by default; not built) |
| Administrator API and command-line tool | API: AUTOMATED_TESTED. Tool: IMPLEMENTED, no automated tests, never run against a deployment |
| Retention, audit log, security events | AUTOMATED_TESTED |
| Invitation web page | IMPLEMENTED; headers and absence of secrets tested; **not opened in a browser or from WhatsApp, and its hand-off to the app (`feluda://join`) has never been tried** |
| Structured logs, request ids, health endpoints, counters | AUTOMATED_TESTED |
| Error tracking (Sentry) | IMPLEMENTED as an option; not tested |

### Android

| Feature | Status |
| --- | --- |
| Enrolment by invitation link, device secret in the keystore, no sign-in screens | AUTOMATED_TESTED (unit tests and the live journey); screens never run |
| Core logic: state machine, gaps, queue, backoff, fingerprint, contract JSON | AUTOMATED_TESTED (87 tests, 2026-11-23) |
| Screens, monitoring service, cellular-bound upload, push handling | IMPLEMENTED; never run on a device or emulator. No UI tests |
| Lint, release build with R8 | Passed 2026-11-23. The minified build has never been launched, so a missing keep rule could still show up at run time |
| Everything on a phone (scenarios in `apps/android/TESTING.md`) | MANUAL_TEST_REQUIRED |
| Firebase configuration and push | EXTERNAL_CREDENTIAL_REQUIRED |

### iOS

| Feature | Status |
| --- | --- |
| Enrolment by invitation link, device secret in the Keychain, no sign-in screens | AUTOMATED_TESTED (unit tests and the live journey); only the paste screen and the "open in app" prompt were seen in a simulator |
| Core logic: state machine, gaps, queue, backoff, fingerprint, API client | AUTOMATED_TESTED (100 tests, 2026-11-23) |
| Screens | IMPLEMENTED; only the first onboarding screen was ever seen running. No UI tests |
| Background refresh, push, Keychain on a device | MANUAL_TEST_REQUIRED (the simulator cannot show them) |
| Forcing uploads over cellular | Not possible on iOS; documented |
| App icon | NOT_IMPLEMENTED |
| Signing, provisioning, TestFlight | EXTERNAL_CREDENTIAL_REQUIRED, EXTERNAL_REVIEW_REQUIRED |
| Everything on an iPhone (scenarios in `apps/ios/TESTING.md`) | MANUAL_TEST_REQUIRED |

### End to end

| Feature | Status |
| --- | --- |
| Each app's networking code talking to the backend | Passed for the old flow on 2026-11-22 (`make live-ios`, `make live-android`). Not re-run for enrolment by link |
| Each app running as an app (screens, monitoring, background work) against the backend | **Never done.** Needs an emulator or simulator session, or phones |
| Staging environment | DEPLOYED 2026-11-23: https://feluda-staging.fly.dev, one always-on machine. The apps are not yet built against it or distributed. No production environment |
| GitHub repository, branch protection | Private repository `ManasHardas/feluda`; public or not is the owner's decision; branch protection NOT DONE |
| Signed release tags, SBOM, provenance | Workflow IMPLEMENTED; never run |
| Reproducible build demonstrated | NOT DONE |
| Independent security review | EXTERNAL_REVIEW_REQUIRED. Not done; `docs/SECURITY_REVIEW.md` is a self-review |
