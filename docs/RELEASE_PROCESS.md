# How a change reaches residents

Decision 0015. Two branches: `main` (reviewed work) and `release` (what residents run). Nothing
reaches a resident's phone until the owner has read the change, tried it on his own phones and
said "merge".

```
pull request ──► preview on the owner's phones ──► owner: "merge" ──► main ──► release ──► residents
   (tests)        (staging server)                                      (Release workflow)
```

## 1. Pull request

Every change is a pull request into `main`. Its description says in plain words what changes for
residents, with screenshots for anything on screen. All test suites run on every pull request
(backend, iOS, Android, security); a pull request with a failing check cannot be merged. If the
change is meant for residents, the pull request also raises `VERSION` and adds a `CHANGELOG.md`
section for the new version: that text is what residents read in TestFlight and App Tester.

Version numbers: raise the last number for fixes (1.0.0 → 1.0.1), the middle one for new features
(1.0.1 → 1.1.0), the first one for changes that need everyone to update (1.1.0 → 2.0.0).

## 2. Preview on your phones

When a pull request changes an app, the "Preview build" workflow builds both apps from it and
sends them only to you. Previews talk to the **staging** server, never to production, and carry
the word PREVIEW in their notes. When it finishes, it comments on the pull request with the build
number (about 20 minutes after the last push).

**One-time setup, iPhone:** install **TestFlight** from the App Store. In App Store Connect, open
Feluda → TestFlight → group **Owner** and add yourself (once). Accept the e-mail invitation on the
iPhone.

**One-time setup, Android:** in the Firebase console, App Distribution → Testers & Groups → group
**owner**, add your Google account (once). Open the invitation e-mail on the Android phone; it
installs **App Tester**. Allow installs from App Tester when Android asks.

**Each preview:**

- iPhone: open TestFlight → Feluda. The newest build is at the top (the number in the pull request
  comment); tap **Install** or **Update**. "Previous builds" lets you go back.
- Android: open App Tester → Feluda → the release with the build number from the comment →
  **Download**, then **Install**.

A preview replaces the app on your phone, including the server it talks to. To return to the
released version: iPhone, TestFlight → Feluda → Previous builds → the released build. Android,
App Tester → the release marked with the released version.

A preview is enrolled against staging; use a staging invitation link
(`NWADMIN_API=https://feluda-staging.fly.dev python3 apps/admin/nwadmin.py invite`).

## 3. "merge"

When you say "merge", the agent:

1. merges the pull request into `main` (staging is redeployed automatically if the backend changed),
2. opens a pull request from `main` into `release`; the "Release guard" check confirms it comes from
   `main`, that `VERSION` was not released before and that `CHANGELOG.md` describes it,
3. merges it when all checks pass.

Changes that are not for residents (documentation, tests, CI) are merged into `main` only, without
a release.

## 4. The release (automatic)

The merge into `release` runs the "Release" workflow:

1. production database migrations, backend deploy to `https://api.feluda.app`, smoke test,
2. both apps built from `release` against production,
3. iPhone: uploaded and added to the TestFlight group **Neighbors**. The first build of a new
   version waits for Apple's beta review (usually under a day); residents then get it through
   TestFlight, automatically where automatic updates are on,
4. Android: sent to the Firebase group **neighbors**; residents get an e-mail and update in App Tester,
5. older apps are told a new version exists (they show "A new version is available"),
6. a tag `vX.Y.Z` and a GitHub release with the APK, checksums, SBOMs and provenance.

Every second month "TestFlight refresh" uploads the released code again with a new build number,
because TestFlight builds stop opening after 90 days.

## Retiring an old version

Set `MIN_APP_VERSION_IOS` / `MIN_APP_VERSION_ANDROID` on production
(`fly secrets set --app feluda-production MIN_APP_VERSION_ANDROID=1.2.0`). Phones with an older
version stop sending and show "Update required" with the install link. Do it only after the newer
version has reached residents, and only when the backend can no longer serve the older one.

## Where the keys are

GitHub repository secrets (used only by these workflows): App Store Connect API key, Apple
distribution certificate and App Store profile, Android signing key, Firebase service account.
GitHub environments `staging` and `production`: Fly deploy token and database migration URL.
The originals are in the owner's git-ignored `secrets/` folder and `.env.staging`; **keep a backup
of both outside this computer**. Losing the Android key means residents must uninstall and
reinstall to get any update.
