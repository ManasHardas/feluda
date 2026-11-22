# To do, in priority order

Last updated 2026-11-21. Source brief: `neighborhood_watchdog_master_build_prompt_v2.md`.

## Build phases (in progress, 2026-11-21)
1. **Repository and architecture.** Done when: structure, decisions, API contract, local dev environment and CI files exist and the first tests pass.
2. **Backend foundation.** Done when: migrations run on a local PostgreSQL, invitation, registration, device registration and event ingestion work and are tested.
3. **Android app.** Done when: `./gradlew assembleDebug testDebugUnitTest` passes locally.
4. **iOS app.** Done when: `xcodebuild build test` passes on a simulator locally.
5. **Detection engine.** Done when: scenarios A to J have passing automated tests.
6. **Notifications.** Done when: FCM and APNs senders are implemented and tested against fake transports, with delivery tracking.
7. **Security hardening.** Done when: authorization, row-level-security and abuse tests pass and the threat model is written.
8. **Deployment.** Done when: deployment files and instructions exist for staging and production.
9. **Private beta distribution.** Done when: release workflows and instructions exist for Firebase App Distribution and TestFlight.

## Blocked right now (2026-11-21, late evening)
- **This Mac cannot open new TCP connections.** About 32,500 sockets are stuck in TIME_WAIT / LAST_ACK and hold every local port (`netstat -an -p tcp | grep -c TIME_WAIT`). The machine has been up 65 days. A restart should clear it. Until then: backend database tests, Docker pulls, Gradle downloads and pushes cannot run. Done when: `curl https://example.com` works again and `make backend-test` shows all tests passing (last full green run before the outage: 143 passed; code changed slightly since, see docs/STATUS.md).

## Needs the owner (cannot be done by the agent)
(filled in at hand-off)
