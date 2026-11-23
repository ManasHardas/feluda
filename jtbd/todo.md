# To do, in priority order

Last updated 2026-11-23. Source brief: `neighborhood_watchdog_master_build_prompt_v2.md`. What has and has not been verified: `docs/STATUS.md`. Summary for whoever picks this up: `docs/HANDOFF.md`.

## Verification still owed (agent can do these)
4. **Run each app as an app against a local backend** (Android emulator, iOS simulator): tap through sign-in, join, an event upload and an incident screen. The networking layer is verified (`make live-ios`, `make live-android`); the screens and the monitoring service have never run against a server. Needs an Android emulator image installed; the iOS simulator works again. Done when: both complete the flow on screen.

## Needs the owner (cannot be done by the agent)
6. **Publishing, remaining part.** The repository is private at `ManasHardas/feluda`; pull request 1 is merged. Still open: the owner says whether to make it public (dependency review and CodeQL result upload only work when public), and branch protection is set as in `CONTRIBUTING.md`. Done when: both are settled.
8. **Security contact in deployments.** `SECURITY.md` has the address; the `SECURITY_CONTACT` setting of the backend and the app builds is set when staging is created (item 10). Done when: the registration page and both apps show it.
9. **Confirm decision 0010** (a lone phone losing Wi-Fi is shown in the app but not pushed). 0012 (paid always-on host) was confirmed 2026-11-22. Done when: 0010 is confirmed or changed.
10. **Accounts and credentials:** a host (Fly.io, chosen by the owner 2026-11-22) and the Firebase service-account key. Supabase (database only), the Firebase project and the Apple keys are in place. Values go in `.env.staging` and `secrets/` (both ignored by git). Done when: staging answers the smoke test and one real push reaches each platform.
11. **Physical-device tests:** `apps/android/TESTING.md` (17 scenarios) and `apps/ios/TESTING.md` (12 scenarios). Done when: each scenario has a recorded result.
12. **TestFlight first-build review** by Apple. Done when: an external tester can install.
13. **Independent security review** before going beyond a small pilot (`docs/SECURITY_REVIEW.md`). Done when: a summary with scope, date, findings and remediation is published.
14. **Legal check of the privacy text** for where the pilot runs. Done when: the operator is named in the notice and the owner is satisfied.

## Owner change of direction, 2026-11-23: no sign-in
0. **Tap through the new join flow on screen** (iOS simulator and Android emulator) against the local backend: open a `feluda://` link, type a street, join, see the main screen; and the 401 path back to the join screen. Sign-in itself is removed and verified by tests (see done.md). Done when: both apps were seen completing it.

## Staging set-up in progress (Supabase project `feluda`)
10d. **Still missing for staging** (owner): Fly.io login (`fly auth login`); Firebase service-account key file in `secrets/firebase-service-account.json` (needed for Android push). Done when: both are in place.
10a. **Backend database login and operator secret.** Create `nw_app_login` with `infrastructure/scripts/create_app_login.sql` and generate `ADMIN_API_TOKEN`; store both only in the host's secret store. Waiting for the host account. Done when: the deployed backend connects and `nwadmin.py` can create an invitation.
10c. **Supabase advisor warning:** `nw.set_updated_at` and `nw.current_user_id` have no fixed `search_path`. Low risk (neither is SECURITY DEFINER, both call built-ins only); fixing `current_user_id` may stop PostgreSQL inlining it in row-level-security policies. Done when: fixed in a new migration with the policy timing checked, or accepted in a decision record.

## Not built yet
14a. **Review the Dependabot pull requests 2 to 12.** Several are major-version jumps (Retrofit 3, Kotlin 2.4, Python 3.15 base image, GitHub Actions majors). Merge one at a time after its CI passes, with the apps rebuilt. Done when: each is merged or closed with a reason.
15. **Per-person administrator access.** Administration uses one shared operator secret (decision 0014): no per-person audit trail, no second factor. Done when: there is more than one organizer and each has their own revocable credential, or the owner accepts the shared secret for good.
15a. **Street suggestions when joining.** The street is free text, so two spellings that normalization does not unify count as different streets. Done when: the join screen offers the streets already in the neighborhood, or the owner decides it is not needed.
15b. **Decide the app's display name and custom link scheme.** The link scheme is `feluda://`; the app is still called "Neighborhood Watchdog" on screen. Done when: the owner confirms or renames.
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
