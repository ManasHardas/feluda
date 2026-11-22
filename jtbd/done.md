# Done

- 2026-11-21: **Phase 1, repository and architecture.** Structure, decision records, API contract, local development environment, CI workflow files. Commits `cd56cae`, `4e217f8`, `6728184`, `5f4f637`. (CI files exist but have never run: see todo 6.)
- 2026-11-21: **Phase 2, backend foundation.** Migrations, sign-in verification, membership, invitations, device registration, event ingestion, tested against PostgreSQL. Commit `4e217f8`.
- 2026-11-21: **Phase 4, iOS app.** Builds for the simulator; 97 unit tests pass. Not run on a device. Commit `6728184`.
- 2026-11-21: **Phase 5, detection engine.** Scenarios A to J and 49 engine unit tests pass. Commits `4e217f8`, `6728184`.
- 2026-11-21: **Phase 6, notifications.** FCM and APNs senders, planning, retries, tracking, tested against fake transports. No real push sent. Commit `6728184`.
- 2026-11-21: **Phase 7, security hardening.** Authorization, row-level-security and abuse tests (42), threat model, self-review. Commits `6728184`, `5f4f637`.
- 2026-11-21: **Phase 8 and 9, documentation and configuration only.** Deployment files, distribution workflows and instructions exist. Nothing deployed or distributed: the remaining work is todo 10 to 12.
- 2026-11-22: **Mac restarted**; network works again.
- 2026-11-22: **Phase 3, Android app.** `assembleDebug testDebugUnitTest lintDebug assembleRelease` pass, 90 unit tests. One test fake fixed (it consumed request bodies before the assertions). Not run on a device. Commit: see `git log` for "android: build verified".
- 2026-11-22: **Backend tests the normal way.** `make backend-test` 144 passed; `make backend-test-docker` 143 passed, 1 skipped.
- 2026-11-22: **Backend dependency audit and SBOM.** Advisories found in PyJWT and Starlette pins; upgraded; audit clean; SBOM generated.
- 2026-11-22: **Live contract checks.** Each app's real networking code completes the resident journey against a running local backend (`make live-ios`, `make live-android`); no contract mismatches found. Added a native Swift package so the iOS core tests run without a simulator (`make ios-core-test`).
