# Engineering hand-off

Written 2026-11-21 at the end of the first build session. Read `docs/STATUS.md` next to this: it
says exactly what was run and what was not.

## State on 2026-11-22

After the restart everything that was blocked was run: backend 144 tests pass (also inside
containers), Android builds with 90 unit tests passing plus lint and the release build, the
dependency audit is clean after an upgrade. Both apps' networking code also passes a full
resident journey against a live local backend (`make live-ios`, `make live-android`). Still never
done: either app running as an app against the backend, anything on a phone, any deployment, any
CI run. Since the macOS update to 27.2.1, Xcode 26.3 can no longer run tests on the simulator
(update Xcode); the iOS core tests run natively with `make ios-core-test` meanwhile.

## 1. What was built

A monorepo with: a FastAPI backend on PostgreSQL (invitation-based membership, device
registration, event ingestion with abuse limits, a rules-based correlation engine with an
explainable score, FCM and APNs senders with delivery tracking, administrator API, retention,
audit); a native Android app and a native iOS app (monitoring, durable queue, gap reporting,
onboarding with consent, status, incidents, settings, transparency); a registration web page; an
administrator command-line tool; CI, release and distribution workflows; deployment configuration;
and the documentation set. 144 backend tests, 97 iOS tests and 90 Android tests pass. Nothing is deployed and nothing has run on a phone.

## 2. Repository structure

`README.md` has the table. In short: `apps/{android,ios,admin}`, `backend/{watchdog,migrations,tests}`,
`shared/{api-contracts,documentation}`, `infrastructure/{deployment,scripts,supabase,monitoring}`,
`docs/`, `ai/docs/decisions/`, `jtbd/`, `.github/workflows/`.

## 3. Architecture decisions

Thirteen records in `ai/docs/decisions/`. The ones the owner should look at first because they
depart from, or narrow, the brief:

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
NWPathMonitor; Python/FastAPI/PostgreSQL; Supabase; GitHub Actions; Docker). Additions: psycopg 3,
PyJWT, httpx, uv; Room on Android; XcodeGen for the iOS project file. Versions are pinned
(`backend/uv.lock`, `apps/android/gradle/libs.versions.toml`).

## 5. Database schema

`backend/migrations/0001_initial_schema.sql`: 18 tables in schema `nw`, UUID keys, UTC timestamps,
row-level security on every table, three restricted roles. Summary in `docs/ARCHITECTURE.md`,
security model in `docs/SECURITY.md`.

## 6. API documentation

`docs/API.md`; contract `shared/api-contracts/openapi.json` (30 paths); client behaviour
`shared/documentation/CLIENT_PROTOCOL.md`. Interactive docs at `/docs` in development.

## 7. Security architecture

`docs/SECURITY.md`, `docs/THREAT_MODEL.md`, `docs/MASVS_CHECKLIST.md`, `docs/SECURITY_REVIEW.md`
(a self-review with 11 findings; not an independent audit).

## 8. Privacy architecture

`docs/PRIVACY.md`. No location, no Wi-Fi names, no browsing data; other residents see totals only;
30-day raw retention; leave and delete are implemented and tested.

## 9. Android build instructions

`apps/android/README.md`. JDK 17 and Android SDK 35. `make android-build`, `make android-test`,
`make android-release`. Configuration and signing come from Gradle properties or environment
variables (names in the README); no `google-services.json`.

## 10. iOS build instructions

`apps/ios/README.md`. `make ios-build`, `make ios-test`. Regenerate the project with
`make ios-generate` after editing `project.yml`. Device builds need a development team in
`Config/Local.xcconfig`.

## 11. Backend deployment instructions

`docs/DEPLOYMENT.md` and `infrastructure/supabase/README.md`. Not carried out.

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
| `release.yml` | version tag | artifacts, SBOMs, checksums, provenance, draft release |
| `distribute-android.yml`, `distribute-ios.yml` | manual | Firebase App Distribution, TestFlight |
| `deploy-staging.yml` | manual | migrations, deploy, smoke test (production behind an approval) |
| `uptime.yml` | every 15 minutes | health probe once a URL is configured |

None has run yet. Repository settings to apply are listed in `CONTRIBUTING.md`.

## 15. Environment variables

`.env.example` (backend, with comments) and the table in `docs/DEPLOYMENT.md`. App build settings
in each app's README. GitHub secrets and variables in `docs/APP_DISTRIBUTION.md`.

## 16. Automated test results

`docs/STATUS.md`, "What was actually run". Backend 144 passed; iOS 97 passed; Android 90 passed.

## 17. Outstanding manual tasks

In `jtbd/todo.md`, in order. The ones only the owner can do:

2. Decide whether and where to publish the repository (which GitHub account, public or private for
   now), choose a license, set the security contact, replace `@OWNER` in CODEOWNERS.
3. Create accounts: Supabase (two projects), Fly.io or another host, Firebase, Apple Developer
   Program ($99 per year).
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
- Administrators sign in with a single factor until an enrolment flow exists.
- One backend instance is assumed.
- No real push notification has ever been sent, and neither app has ever talked to the backend.
- More in `docs/ARCHITECTURE.md`, `docs/THREAT_MODEL.md`, and each app's README.

## 19. Estimated monthly infrastructure cost

About $2 to $4 a month for up to roughly 100 households (one small always-on machine; database,
sign-in and push on free tiers), about $30 a month at 500 households (paid database plan). Plus
$99 a year for Apple. Staging doubles the machine cost. Assumptions, free-tier limits and the
points where they break are in `docs/DEPLOYMENT.md`. Prices must be re-checked; they were not
verified online during the build.

## 20. Recommended next steps

1. Run both apps against a local backend (emulator and simulator) through
   the whole onboarding flow. That is the largest unverified area.
2. Stand up staging and send one real push to each platform.
3. A two-household field test (switch a router off; see `docs/TESTING.md`).
4. Publish the repository and let CI run; fix the workflows.
5. Build administrator multi-factor enrolment; add the Android SBOM task; pin CI actions by hash.
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
| Generate beta artifacts | Android: `cd apps/android && ./gradlew --no-daemon assembleRelease` with the signing variables set. iOS: run the "Distribute iOS (TestFlight)" workflow, or Xcode → Product → Archive |
| Tagged release artifacts | `git tag -s v0.1.0 && git push origin v0.1.0` (runs `release.yml`) |

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
