# To do, in priority order

Last updated 2026-11-22. Source brief: `neighborhood_watchdog_master_build_prompt_v2.md`. What has and has not been verified: `docs/STATUS.md`. Summary for whoever picks this up: `docs/HANDOFF.md`.

## Verification still owed (agent can do these)
4. **Run each app as an app against a local backend** (Android emulator, iOS simulator): tap through sign-in, join, an event upload and an incident screen. The networking layer is verified (`make live-ios`, `make live-android`); the screens and the monitoring service have never run against a server. Needs an Android emulator image installed; the iOS simulator works again. Done when: both complete the flow on screen.

## Needs the owner (cannot be done by the agent)
6. **Publishing, remaining part.** The repository is private at `ManasHardas/feluda`, pull request 1 is open and CI is green. Still open: the owner says whether to make it public (dependency review and CodeQL result upload only work when public), the pull request is merged, and branch protection is set as in `CONTRIBUTING.md`. Done when: all three are settled.
8. **Security contact in deployments.** `SECURITY.md` has the address; the `SECURITY_CONTACT` setting of the backend and the app builds is set when staging is created (item 10). Done when: the registration page and both apps show it.
9. **Confirm decision 0010** (a lone phone losing Wi-Fi is shown in the app but not pushed). 0012 (paid always-on host) was confirmed 2026-11-22. Done when: 0010 is confirmed or changed.
10. **Accounts and credentials:** Supabase (staging and production projects), a host (Fly.io recommended), Firebase project, an SMTP sender for sign-in codes. The owner already has the Apple Developer Program. The owner fills in `.env.staging` (ignored by git; a blank template is written there) and puts key files in `secrets/`. Steps: `docs/DEPLOYMENT.md`, `infrastructure/supabase/README.md`, `docs/APP_DISTRIBUTION.md`. Done when: staging answers the smoke test and one real push reaches each platform.
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
