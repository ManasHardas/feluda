# To do, in priority order

Last updated 2026-11-21 (end of the first build session). Source brief: `neighborhood_watchdog_master_build_prompt_v2.md`. What has and has not been verified: `docs/STATUS.md`. Summary for whoever picks this up: `docs/HANDOFF.md`.

## Blocked on the machine
1. **Restart the Mac.** It cannot open new TCP connections: about 32,500 sockets are stuck in TIME_WAIT / LAST_ACK and hold every local port (uptime was 65 days). Done when: `curl https://example.com` returns 200.

## Verification still owed (agent can do these once the network works)
2. **Android: finish the build.** Only `compileDebugKotlin` has run. Run `./gradlew --no-daemon assembleDebug testDebugUnitTest`, then `lintDebug` and `assembleRelease`, and fix what they report (test sources have never been compiled; R8 keep rules are untested). Done when: all four tasks pass and `docs/STATUS.md` says so.
3. **Backend tests the normal way.** They passed (144) through a temporary relay. Done when: `make backend-test` and `make backend-test-docker` pass.
4. **Run each app against a local backend** (Android emulator, iOS simulator) through sign-in, join, device registration, an event upload and an incident screen. Neither app has ever talked to the backend. Done when: both complete the flow and any contract mismatches are fixed.
5. **Dependency audit and SBOM.** `make sbom` and the pip-audit step have never run. Done when: results are recorded in `docs/RELEASE_VERIFICATION.md`.

## Needs the owner (cannot be done by the agent)
6. **Publishing.** Decide which GitHub account, public or private for now, and say go. Nothing has been pushed anywhere. Done when: the repository exists, CI has run once, branch protection is set as in `CONTRIBUTING.md`.
7. **License.** None chosen; the README says all rights reserved until one is. Done when: a LICENSE file is added.
8. **Security contact.** `SECURITY.md` has a placeholder. Done when: an address is set there and in the `SECURITY_CONTACT` settings.
9. **Confirm the judgement calls** in `ai/docs/decisions/`, first 0010 (a lone phone losing Wi-Fi is shown but not pushed) and 0012 (a few dollars a month for an always-on host instead of a free one that sleeps). Done when: each is confirmed or changed.
10. **Accounts and credentials:** Supabase (staging and production projects), a host (Fly.io by default), Firebase project, Apple Developer Program ($99 per year), an SMTP sender for sign-in codes. Steps: `docs/DEPLOYMENT.md`, `infrastructure/supabase/README.md`, `docs/APP_DISTRIBUTION.md`. Done when: staging answers the smoke test and one real push reaches each platform.
11. **Physical-device tests:** `apps/android/TESTING.md` (16 scenarios) and `apps/ios/TESTING.md` (11 scenarios). Done when: each scenario has a recorded result.
12. **TestFlight first-build review** by Apple. Done when: an external tester can install.
13. **Independent security review** before going beyond a small pilot (`docs/SECURITY_REVIEW.md`). Done when: a summary with scope, date, findings and remediation is published.
14. **Legal check of the privacy text** for where the pilot runs. Done when: the operator is named in the notice and the owner is satisfied.

## Not built yet
15. **Administrator multi-factor enrolment.** The backend enforces it when `ADMIN_REQUIRE_MFA=true`, but there is no way to enrol. Done when: an administrator can add a second factor and sign in with it.
16. **Android SBOM task** (CycloneDX Gradle plugin). Done when: `release.yml` attaches `sbom-android.cdx.json`.
17. **iOS app icon.** Done when: the asset catalog has one.
18. **Pin CI actions by commit hash and base images by digest; Gradle dependency verification metadata.** Done when: `docs/RELEASE_VERIFICATION.md` "Known gaps" is empty.
19. **Reproducible-build check for Android** on two machines. Done when: the comparison in `docs/RELEASE_VERIFICATION.md` has been carried out and the result written down.
20. **Scheduled database backups** (the Supabase free plan has none). Done when: a dump runs on a schedule and one restore has been tested.
21. **UI tests** for both apps (only logic is tested). Done when: the main flows have automated UI coverage.
22. **Crash reporting decision** (none is included; adding one sends device data to a third party). Done when: decided and documented.
23. **SMS alerts** (optional in the brief, off by default, not built). Done when: the owner asks for it.

## After the first field test
24. **Revisit score weights, thresholds and the household push rule** with real data (`docs/DETECTION_PROTOCOL.md`). Done when: changes, if any, are recorded as a decision.
