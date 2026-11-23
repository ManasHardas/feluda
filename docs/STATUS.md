# Status

Last updated 2026-11-22 (after the machine restart). This page is the honest scoreboard:
what exists, what was actually run, and what was not.

| Word | Meaning |
| --- | --- |
| IMPLEMENTED | The code exists. Nothing more is claimed |
| AUTOMATED_TESTED | Automated tests exist and were seen to pass on the build machine |
| MANUAL_TEST_REQUIRED | Needs a person and a physical phone or a real account; not done |
| EXTERNAL_CREDENTIAL_REQUIRED | Needs an account, key or certificate the build did not have; not done |
| EXTERNAL_REVIEW_REQUIRED | Needs a third party (Apple, an independent reviewer); not done |
| NOT_IMPLEMENTED | Does not exist |

## What was actually run

| What | Result | When |
| --- | --- | --- |
| Backend: lint and format check | clean | 2026-11-21 |
| Backend: all tests against PostgreSQL 16 (unit, integration, security, scenarios A to J, notifications, admin), `make backend-test` | **144 passed, 0 failed**, again after upgrading dependencies | 2026-11-22 |
| Backend: migrations on an empty database, re-run, pending check | applied; no-op; nothing pending | 2026-11-21 |
| Backend: seed script, operator command | 24 households seeded; listed | 2026-11-21 |
| Backend: real server start, health, registration page, scenario C replayed over HTTP, local sign-in code issued | worked; incident classified and explained as expected | 2026-11-21 |
| API contract check (`openapi.json` matches the code) | up to date | 2026-11-21 |
| iOS: Debug build for the simulator | BUILD SUCCEEDED (run twice, by the app's builder and again independently) | 2026-11-21 |
| iOS: Release build for the simulator | BUILD SUCCEEDED (reported by the app's builder) | 2026-11-21 |
| iOS: unit tests on an iPhone 17 simulator (iOS 26.3) | 97 passed, 0 failed (reported by the app's builder) | 2026-11-21 |
| iOS: app launched once in the simulator | first onboarding screen rendered; nothing else exercised | 2026-11-21 |
| Android: `assembleDebug testDebugUnitTest lintDebug assembleRelease` | BUILD SUCCESSFUL; **90 unit tests passed, 0 failed**; lint passed; unsigned release APK built with R8 | 2026-11-22 |
| Backend tests inside containers (`make backend-test-docker`), which also builds the image's build stage | 143 passed, 1 skipped (the repository secret scan needs git) | 2026-11-22 |
| Backend dependency audit (pip-audit) | First run found advisories in PyJWT 2.10.1 and Starlette 0.41.3; dependencies upgraded; **no known vulnerabilities** afterwards | 2026-11-22 |
| Backend SBOM (`make sbom`, CycloneDX) | generated | 2026-11-22 |
| **Live contract check, iOS** (`make live-ios`): the app's real API and sign-in code against a running local backend: sign-in by code, not-a-member, join, device and push-token registration, preferences, event upload and duplicate upload, status, neighborhood, incident list and detail, token refresh with rotation, device revoke, leave | passed, no contract mismatch | 2026-11-22 |
| **Live contract check, Android** (`make live-android`): the same journey through the app's real OkHttp/Retrofit/token stack | passed, no contract mismatch | 2026-11-22 |
| iOS core tests run natively on the Mac (`make ios-core-test`, a Swift package over the same sources) | 87 executed, 0 failures (1 skipped: the live test) | 2026-11-22 |
| iOS tests on the simulator, after the macOS update to 27.2.1 with Xcode still 26.3 | could not run ("test runner hung before establishing connection") | 2026-11-22 |
| iOS tests on the simulator after updating Xcode to 27.2 (`make ios-test`) | 98 run, 1 skipped (live test), 0 failures | 2026-11-22 |
| GitHub Actions workflows | **never ran** (the repository is not on GitHub) | |
| Anything on a physical phone | **not done** | |
| Any deployment | **not done** | |

The production container image target (`runtime`) has not been built on its own yet; CI does that.

## Definition of done, item by item

| # | Requirement | Status | Evidence or gap |
| --- | --- | --- | --- |
| 1 | Android application compiles | AUTOMATED_TESTED | Debug APK, unit tests, lint and the R8 release build all pass. Never installed on a phone or emulator |
| 2 | iOS application compiles | AUTOMATED_TESTED | Debug and Release simulator builds succeed; a signed device build needs an Apple team |
| 3 | Backend starts locally | AUTOMATED_TESTED | Started and exercised over HTTP |
| 4 | Database migrations execute successfully | AUTOMATED_TESTED | `test_migrations_are_complete_and_immutable`, and run by hand |
| 5 | User registration works | AUTOMATED_TESTED | `test_full_onboarding_journey` (web flow and in-app join). Real e-mail delivery of codes: EXTERNAL_CREDENTIAL_REQUIRED |
| 6 | Invitation verification works | AUTOMATED_TESTED | Single use, expiry, revocation, lock, uniform errors |
| 7 | Household membership is enforced server-side | AUTOMATED_TESTED | API tests and direct row-level-security tests |
| 8 | Device registration works | AUTOMATED_TESTED | Register, re-register, limit, revoke |
| 9 | Connectivity events can be recorded | AUTOMATED_TESTED | Ingestion, idempotency, limits |
| 10 | Events reported independently of home Wi-Fi when supported | IMPLEMENTED; MANUAL_TEST_REQUIRED | Android binds uploads to the cellular network (compiled and unit-tested with fakes; never run on a phone). iOS cannot force an interface; the system decides. Needs real phones |
| 11 | Failed event uploads are queued | AUTOMATED_TESTED on both platforms | Unit tests with fake transports |
| 12 | Neighborhood correlation works | AUTOMATED_TESTED | Scenarios A to J and 49 engine unit tests |
| 13 | Incident classification is explainable | AUTOMATED_TESTED | Score breakdown, supporting and missing evidence stored and asserted |
| 14 | Push notification integrations are implemented | IMPLEMENTED; AUTOMATED_TESTED against fake transports; EXTERNAL_CREDENTIAL_REQUIRED | FCM and APNs request shape, retries, tracking tested. **No real push has ever been sent** |
| 15 | Security tests pass | AUTOMATED_TESTED | 42 tests |
| 16 | Simulation tests pass | AUTOMATED_TESTED | 11 tests |
| 17 | Privacy documentation is complete | IMPLEMENTED | `docs/PRIVACY.md`, `/privacy` page, in-app text. Not reviewed by a lawyer |
| 18 | Deployment documentation is complete | IMPLEMENTED | `docs/DEPLOYMENT.md`. The steps have never been followed against real accounts |
| 19 | CI pipelines run successfully | **NOT DONE.** IMPLEMENTED only | Nine workflows written; none has ever run. Expect fixes on first run |
| 20 | Private beta distribution documented and ready for account configuration | IMPLEMENTED; EXTERNAL_CREDENTIAL_REQUIRED; EXTERNAL_REVIEW_REQUIRED (Apple) | `docs/APP_DISTRIBUTION.md`, two manual workflows |

## Feature status

### Backend

| Feature | Status |
| --- | --- |
| Invitations, registration, membership, leaving, account deletion | AUTOMATED_TESTED |
| Sign-in token verification (shared secret) | AUTOMATED_TESTED |
| Sign-in token verification through JWKS (asymmetric keys) | IMPLEMENTED; not tested (needs a real issuer) |
| Supabase Auth itself (code e-mails, rotation settings) | EXTERNAL_CREDENTIAL_REQUIRED |
| Deleting the identity record at the provider on account deletion | IMPLEMENTED; not tested; falls back to an operator task |
| Row-level security and restricted roles | AUTOMATED_TESTED |
| Event ingestion, rate limits, replay protection | AUTOMATED_TESTED |
| Detection engine, correlation, incident lifecycle | AUTOMATED_TESTED with simulated data only; **never validated against a real disruption** |
| Notifications: planning, preferences, quiet hours, retries, expiry, resolution | AUTOMATED_TESTED with fake senders |
| SMS | NOT_IMPLEMENTED (optional in the brief, off by default; not built) |
| Administrator API and command-line tool | API: AUTOMATED_TESTED. Tool: IMPLEMENTED, never run against a server |
| Administrator multi-factor enrolment | NOT_IMPLEMENTED (enforcement exists) |
| Retention, audit log, security events | AUTOMATED_TESTED |
| Registration web page | IMPLEMENTED; served and checked for headers; **not opened in a browser or from WhatsApp** |
| Structured logs, request ids, health endpoints, counters | AUTOMATED_TESTED |
| Error tracking (Sentry) | IMPLEMENTED as an option; not tested |

### Android

| Feature | Status |
| --- | --- |
| Core logic: state machine, gaps, queue, backoff, fingerprint, contract JSON, auth networking | AUTOMATED_TESTED (90 tests) |
| Screens, monitoring service, cellular-bound upload, push handling | IMPLEMENTED; compiled; never run on a device or emulator. No UI tests |
| Lint, release build with R8 | Pass. The minified build has never been launched, so a missing keep rule could still show up at run time |
| Everything on a phone (16 scenarios in `apps/android/TESTING.md`) | MANUAL_TEST_REQUIRED |
| Firebase configuration and push | EXTERNAL_CREDENTIAL_REQUIRED |

### iOS

| Feature | Status |
| --- | --- |
| Core logic: state machine, gaps, queue, backoff, fingerprint, API and auth clients | AUTOMATED_TESTED (97 tests) |
| Screens | IMPLEMENTED; compiled; only the first onboarding screen was ever seen running. No UI tests |
| Background refresh, push, Keychain on a device | MANUAL_TEST_REQUIRED (the simulator cannot show them) |
| Forcing uploads over cellular | Not possible on iOS; documented |
| App icon | NOT_IMPLEMENTED |
| Signing, provisioning, TestFlight | EXTERNAL_CREDENTIAL_REQUIRED, EXTERNAL_REVIEW_REQUIRED |
| Everything on an iPhone (11 scenarios in `apps/ios/TESTING.md`) | MANUAL_TEST_REQUIRED |

### End to end

| Feature | Status |
| --- | --- |
| Each app's networking code talking to the backend | AUTOMATED_TESTED (`make live-ios`, `make live-android`), on the Mac, not on a device |
| Each app running as an app (screens, monitoring, background work) against the backend | **Never done.** Needs an emulator or simulator session, or phones |
| Staging environment | NOT DONE (EXTERNAL_CREDENTIAL_REQUIRED). The brief asked for a functioning staging environment; it does not exist |
| GitHub repository, branch protection | Private repository `ManasHardas/feluda`; CI ran green on pull request 1 (2026-11-22); public or not is the owner's decision; branch protection NOT DONE |
| Signed release tags, SBOM, provenance | Workflow IMPLEMENTED; never run |
| Reproducible build demonstrated | NOT DONE |
| Independent security review | EXTERNAL_REVIEW_REQUIRED |
