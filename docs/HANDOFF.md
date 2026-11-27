# Engineering hand-off

Written 2026-11-21 at the end of the first build session. Read `docs/STATUS.md` next to this: it
says exactly what was run and what was not.

## State on 2026-11-23: sign-in removed

The owner decided that nobody signs in (decision 0014). A phone joins with a private invitation
link, the resident types the street and optionally a house number, and the phone then holds a
device secret. Administration uses one operator secret (`ADMIN_API_TOKEN`) with
`apps/admin/nwadmin.py`. Supabase is only the database host; there is no identity provider and no
mail sender.

- Backend: 143 tests pass locally against PostgreSQL 16; lint clean; API contract up to date.
- Apps: iOS 100 simulator tests and Android 87 unit tests pass; each app's networking code completes
  the enrolment journey against a running backend (`make live-ios`, `make live-android`). The join
  screens have not been tapped through on screen.
- Staging database: migrations 0001 and 0002 applied and verified identical to a local database. 0002 drops columns the
  old backend needs, so it cannot be rolled back by image (`docs/DEPLOYMENT.md`, "Migrations").
- Still never done: either app running as an app against the backend, anything on a phone, any
  deployment, any real push, an independent security review.
- What the change costs in security, stated in `docs/SECURITY.md` ("What this design gives up")
  and `docs/THREAT_MODEL.md`: no second factor, a device secret that works until the phone is
  removed, one shared operator secret without a per-person audit trail, and no coordinates unless
  the organizer adds them. `docs/SECURITY_REVIEW.md` finding 13 (one link can create several
  households) is open and needs the owner's attention before inviting anyone.

Sections 1 to 20 below were written on 2026-11-21 and 2026-11-22 and have been corrected where
sign-in was mentioned; test counts in them are marked with their date.

## State on 2026-11-22 (before sign-in was removed)

After the restart everything that was blocked was run: backend 144 tests pass (also inside
containers), Android builds with 90 unit tests passing plus lint and the release build, the
dependency audit is clean after an upgrade. Both apps' networking code also passes a full
resident journey against a live local backend (`make live-ios`, `make live-android`). Still never
done: either app running as an app against the backend, anything on a phone, any deployment.
CI ran green on GitHub on 2026-11-22 (pull request 1). The iOS simulator tests pass again on Xcode 27.2 (`make ios-test`); `make ios-core-test`
runs the core tests natively without a simulator.

## 1. What was built

A monorepo with: a FastAPI backend on PostgreSQL (enrolment of phones by invitation link with no
sign-in, device registration, event ingestion with abuse limits, a rules-based correlation engine with an
explainable score, FCM and APNs senders with delivery tracking, administrator API, retention,
audit); a native Android app and a native iOS app (monitoring, durable queue, gap reporting,
onboarding with consent, status, incidents, settings, transparency); an invitation web page; an
administrator command-line tool; CI, release and distribution workflows; deployment configuration;
and the documentation set. On 2026-11-23, after sign-in was removed: backend 143, iOS 100 and Android 87
tests pass. Nothing is
deployed and nothing has run on a phone.

## 2. Repository structure

`README.md` has the table. In short: `apps/{android,ios,admin}`, `backend/{watchdog,migrations,tests}`,
`shared/{api-contracts,documentation}`, `infrastructure/{deployment,scripts,supabase,monitoring}`,
`docs/`, `ai/docs/decisions/`, `jtbd/`, `.github/workflows/`.

## 3. Architecture decisions

Fourteen records in `ai/docs/decisions/`. The ones the owner should look at first because they
depart from, or narrow, the brief:

- **0014** (the owner's own decision): no sign-in; phones are enrolled by invitation link. It
  replaces 0004 and lists the costs accepted.

- **0010**: a single phone losing Wi-Fi is recorded but not pushed unless evidence shows the phone
  was at home (otherwise every departure from the house would notify).
- **0003**: distance is computed in the application, not with PostGIS.
- **0002**: plain SQL migrations with a small runner, no ORM, forward-only.
- **0009**: no permanent foreground service on Android; gaps are reported instead.
- **0012**: Fly.io with one always-on machine (a few dollars a month) rather than a free host that
  sleeps; a zero-cost alternative is described.
- **0008**: the home network is recognised by a fingerprint kept on the phone.

## 4. Technology choices

As the brief specified (Kotlin/Compose/Hilt/Retrofit/WorkManager; Swift/SwiftUI/URLSession/
NWPathMonitor; Python/FastAPI/PostgreSQL; Supabase, now for the database only; GitHub Actions;
Docker). Additions: psycopg 3, httpx, uv, PyJWT (only for the APNs provider token); Room on Android; XcodeGen for the iOS project file. Versions are pinned
(`backend/uv.lock`, `apps/android/gradle/libs.versions.toml`).

## 5. Database schema

`backend/migrations/0001_initial_schema.sql`: 18 tables in schema `nw`, UUID keys, UTC timestamps,
row-level security on every table, three restricted roles. `0002_no_sign_in.sql` adds
`device_credentials` (hashes of device secrets), use counts on invitations, an optional house
number, and drops the e-mail and identity columns from `users`. Summary in `docs/ARCHITECTURE.md`,
security model in `docs/SECURITY.md`.

## 6. API documentation

`docs/API.md`; contract `shared/api-contracts/openapi.json` (28 paths); client behaviour
`shared/documentation/CLIENT_PROTOCOL.md`. Interactive docs at `/docs` in development.

## 7. Security architecture

`docs/SECURITY.md`, `docs/THREAT_MODEL.md`, `docs/MASVS_CHECKLIST.md`, `docs/SECURITY_REVIEW.md`
(a self-review with 15 findings; not an independent audit).

## 8. Privacy architecture

`docs/PRIVACY.md`. No names, e-mail addresses or accounts; no location, no Wi-Fi names, no browsing
data; other residents see totals only; 30-day raw retention; leaving deletes the phone and what it
reported (tested in the backend).

## 9. Android build instructions

`apps/android/README.md`. JDK 17 and Android SDK 35. `make android-build`, `make android-test`,
`make android-release`. Configuration and signing come from Gradle properties or environment
variables (names in the README); no `google-services.json`.

## 10. iOS build instructions

`apps/ios/README.md`. `make ios-build`, `make ios-test`. Regenerate the project with
`make ios-generate` after editing `project.yml`. Device builds need a development team in
`Config/Local.xcconfig`.

## 11. Backend deployment instructions

`docs/DEPLOYMENT.md` and `infrastructure/supabase/README.md`. Not carried out, apart from a staging
database with migration 0001.

## 12. Firebase setup

`docs/APP_DISTRIBUTION.md`, Android section: project, four public configuration values for the
app, a service account for the backend (`FCM_SERVICE_ACCOUNT_JSON`), another for distribution.

## 13. Apple Developer and TestFlight setup

`docs/APP_DISTRIBUTION.md`, iOS section: enrolment, bundle id and capabilities, APNs key, App Store
Connect record and API key, test information, first-build review.

## 14. GitHub Actions

| Workflow | Trigger | Purpose |
| --- | --- | --- |
| `backend.yml` | pull request, push to main | lint, unit tests, API contract, integration and security tests with PostgreSQL, migration validation, image build |
| `android.yml` | changes under `apps/android` | build, unit tests, lint, unsigned release build |
| `ios.yml` | changes under `apps/ios` | simulator build and tests on a macOS runner |
| `security.yml` | every change, weekly | dependency audit, secret scan, dependency review, CodeQL |
| `preview.yml` | pull request into main touching an app | both apps against staging, to the owner's phones only |
| `release.yml` | push to `release` | production migrations and deploy, apps to residents, tag, GitHub release |
| `release-guard.yml` | pull request into `release` | only `main`, new `VERSION`, `CHANGELOG.md` section |
| `build-ios.yml`, `build-android.yml`, `release-artifacts.yml` | called by the above | signed builds and uploads; SBOMs and provenance |
| `testflight-refresh.yml` | every second month | re-uploads the released iOS build before TestFlight's 90-day expiry |
| `deploy-staging.yml` | push to main touching the backend | staging migrations, deploy, smoke test |
| `uptime.yml` | every 15 minutes | health probe once a URL is configured |

The pull-request workflows ran green on pull request 1 (2026-11-22, before sign-in was removed).
The release, distribution, deploy and uptime workflows have never run. The app builds no longer
receive `AUTH_BASE_URL` or `AUTH_PUBLIC_KEY`. Repository settings to apply are listed in
`CONTRIBUTING.md`.

## 15. Environment variables

`.env.example` (backend, with comments) and the table in `docs/DEPLOYMENT.md`. App build settings
in each app's README. GitHub secrets and variables in `docs/APP_DISTRIBUTION.md`.

## 16. Automated test results

`docs/STATUS.md`, "What was actually run". 2026-11-23: backend 143, iOS 100 (1 skipped), Android 87
(1 skipped) passed, and both live journeys passed.

## 17. Outstanding manual tasks

In `jtbd/todo.md`, in order. The ones only the owner can do:

2. Say whether the repository should be public (it is published privately under `ManasHardas`;
   license MIT and the security contact are set).
3. Create accounts: Supabase (two projects, database only), Fly.io or another host, Firebase. The
   owner already has the Apple Developer Program. No mail sender is needed any more.
4. Test on real phones (both test plans).
5. Confirm the judgement calls in the decision records, 0010 first.
6. Arrange an independent security review before going beyond a small pilot.

## 18. Known limitations

- A phone app cannot detect radio interference; it sees only its own connection. If Wi-Fi and
  cellular are both blocked, nothing is reported or received until connectivity returns.
- iOS monitors only while the app runs or when the system wakes it. Expect long gaps.
- Android without a foreground service also has gaps; they are reported.
- An iPhone leaving home looks like a Wi-Fi loss at home unless another phone is still there.
- Detection thresholds are untested against real events. Three colluding households can raise a
  false neighborhood alert.
- Nobody signs in. A phone is recognised by a device secret that works until the phone is removed,
  and administration by one shared operator secret: no second factor and no per-person audit trail
  (decision 0014).
- One invitation link can create several households (`docs/SECURITY_REVIEW.md`, finding 13).
- Residents are not asked for a location, so the 500-metre check applies only to households the
  organizer adds with coordinates.
- One backend instance is assumed.
- No real push notification has ever been sent. The apps' networking code talked to a local backend
  under the old flow only; neither app has run as an app against it.
- More in `docs/ARCHITECTURE.md`, `docs/THREAT_MODEL.md`, and each app's README.

## 19. Estimated monthly infrastructure cost

About $2 to $4 a month for up to roughly 100 households (one small always-on machine; database
and push on free tiers; there is no sign-in service to pay for), about $30 a month at 500
households (paid database plan). Plus
$99 a year for Apple. Staging doubles the machine cost. Assumptions, free-tier limits and the
points where they break are in `docs/DEPLOYMENT.md`. Prices must be re-checked; they were not
verified online during the build.

## 20. Recommended next steps

1. Finish the apps' update for enrolment by link, re-run their tests and the live contract checks,
   then run both apps against a local backend (emulator and simulator) through the whole
   onboarding flow. That is the largest unverified area.
2. Stand up staging and send one real push to each platform.
3. A two-household field test (switch a router off; see `docs/TESTING.md`).
4. Publish the repository and let CI run; fix the workflows.
5. Decide whether one link may create more than one household (`docs/SECURITY_REVIEW.md`, finding
   13); add the Android SBOM task; pin CI actions by hash.
6. Collect a few weeks of pilot data, then revisit the score weights and the household push rule.

## Exact commands

| Task | Command |
| --- | --- |
| Run locally | `make setup && make docker-up && make migrate && make seed && make run` |
| Run tests | `make test` (or `make backend-test`, `make android-test`, `make ios-test`) |
| Build Android | `make android-build` (debug), `make android-release` |
| Build iOS | `make ios-build` |
| Start backend | `make run` (development); production runs the container: `uvicorn watchdog.asgi:app --host 0.0.0.0 --port 8000` |
| Apply database migrations | `make migrate` (local); `DATABASE_URL=<owner url> uv run python -m watchdog.migrate` in `backend/` (any environment) |
| Seed sample households | `make seed` (`make seed ARGS=--reset` to recreate) |
| Replay a scenario | `make simulate SCENARIO=C` |
| Deploy staging | `MIGRATION_DATABASE_URL=<owner url> infrastructure/scripts/deploy.sh staging` |
| Generate beta artifacts | Open a pull request: "Preview build" sends both apps to the owner's phones (`docs/RELEASE_PROCESS.md`). By hand: Android `cd apps/android && ./gradlew --no-daemon assembleRelease` with the signing variables set; iOS Xcode → Product → Archive |
| Release to residents | Merge `main` into `release` after the owner says "merge" (`docs/RELEASE_PROCESS.md`); tags are created by the workflow |

## Changes made to this Mac during the build

- Homebrew: `openjdk@17`, `xcodegen`, and the cask `android-commandlinetools`.
- Android SDK packages under `/opt/homebrew/share/android-commandlinetools`: `platform-tools`,
  `platforms;android-35`, `build-tools;35.0.0`, and `build-tools;34.0.0` (pulled in automatically
  by the first Gradle run).
- `~/.gradle/` (Gradle 8.11.1 distribution and dependency caches).
- Docker: container `neighborhood-watchdog-db-1` (PostgreSQL on 127.0.0.1:54329) and volume
  `neighborhood-watchdog_db-data`, holding the simulated neighborhood. Still running;
  `make docker-down` stops it, `docker compose down -v` also deletes the data.
- uv downloaded Python 3.12 for the backend's virtual environment (`backend/.venv`).
- iOS simulator "iPhone 17": was booted for tests and shut down again.

To remove the toolchains: `brew uninstall openjdk@17 xcodegen && brew uninstall --cask android-commandlinetools`,
and delete `~/.gradle`.
