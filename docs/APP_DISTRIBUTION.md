# Private beta distribution

The pilot needs no public store listing. Android goes out through Firebase App Distribution,
iOS through TestFlight external testing.

How builds get here is described in `docs/RELEASE_PROCESS.md` (decision 0015): previews go to the
owner's groups ("Owner" in TestFlight, `owner` in Firebase), releases to the residents' groups
("Neighbors", `neighbors`), all from GitHub Actions. This page is the one-time setup.

**Status.** Done by hand on 2026-11-23: one build of each app (0.1.0, build 1, against staging)
reached the owner's groups. The preview and release workflows have not run yet. TestFlight external
testing needs Apple's review of the first build of each version (EXTERNAL_REVIEW_REQUIRED).

## Android: Firebase App Distribution

Reference: https://firebase.google.com/docs/app-distribution

### One-time setup

1. Create a Firebase project (console.firebase.google.com). Add an Android app with the package
   name `app.feluda` (or your own; then set the `APPLICATION_ID` Gradle
   property to match).
2. Project settings → General: note the **Project ID**, **App ID**, **Web API key** and
   **Sender ID**. The app is configured from these four values at build time; no
   `google-services.json` is committed (`apps/android/README.md`).
3. App Distribution → Testers and groups: the groups `owner` (previews) and `neighbors` (residents) exist.
4. Cloud Messaging is enabled by default. For the backend, create a service account key with the
   role "Firebase Cloud Messaging API Admin" and store the JSON as the backend secret
   `FCM_SERVICE_ACCOUNT_JSON`. For the distribution workflow, create a service account with
   "Firebase App Distribution Admin" and store its JSON as the GitHub secret
   `FIREBASE_SERVICE_ACCOUNT_JSON`.
5. Create the release signing key, once:

   ```bash
   keytool -genkeypair -v -keystore release.jks -alias watchdog -keyalg RSA -keysize 4096 -validity 9125
   base64 -i release.jks | pbcopy     # paste as the GitHub secret ANDROID_KEYSTORE_BASE64
   ```

   **Keep `release.jks` and its passwords in two safe offline places.** If the key is lost, testers
   must uninstall and reinstall, losing local state. Never commit it. If the app later goes to
   Google Play, Play App Signing re-signs with Google's key and this one becomes the upload key.

### GitHub settings

Repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` (key alias `feluda`, key
password the same), `FIREBASE_SERVICE_ACCOUNT_JSON`; variables `FIREBASE_PROJECT_ID`,
`FIREBASE_ANDROID_APP_ID`, `FIREBASE_API_KEY`, `FIREBASE_SENDER_ID`, `SECURITY_CONTACT`. Builds are
made by `.github/workflows/build-android.yml` and uploaded with `infrastructure/scripts/firebase_distribute.py`.

### Inviting testers

Create an **invite link** for the group (App Distribution → Invite links) and set it as the
backend's `ANDROID_INSTALL_URL`, so the invitation page offers it. Adding a resident's Google
account to the group by hand also works, but it means collecting an e-mail address the service
itself never asks for. Either way Firebase asks the tester to sign in with a Google account to
download: that is Google's requirement for App Distribution, outside this service, and worth
telling residents in advance. Testers accept the invitation, install the Firebase App Tester app or
download the APK directly, and may have to allow installs from that source once.

### Alternative

Google Play Console internal testing (up to 100 testers, needs a $25 developer account and a Play
listing): https://support.google.com/googleplay/android-developer/answer/9845334 . A production
Play release is not needed for the pilot.

## iOS: TestFlight external testing

Reference: https://developer.apple.com/testflight/ and
https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers

### One-time setup

1. Enrol in the Apple Developer Program ($99 per year). An individual enrolment shows the owner's
   legal name as the seller.
2. Certificates, Identifiers & Profiles → Identifiers: register the bundle id
   `app.feluda` (or your own) with the capabilities **Push Notifications** and
   **Time Sensitive Notifications**. Background modes (fetch, remote notifications) are in the app's
   Info.plist.
3. Keys: create an **APNs key** (.p8). Give the backend `APNS_KEY_ID`, `APNS_TEAM_ID`,
   `APNS_BUNDLE_ID` and the key contents as `APNS_PRIVATE_KEY`.
4. App Store Connect → Apps → New App with that bundle id. This creates the record TestFlight
   needs; it is **not** a public App Store listing and nothing is published by doing this.
5. App Store Connect → Users and Access → Integrations → App Store Connect API: create a key with
   the App Manager role for the upload workflow.
6. Fill in TestFlight → Test Information: beta app description, feedback e-mail (yours, as the
   publisher; Apple requires it), privacy policy URL (`https://<your host>/privacy`), and
   instructions for Apple's reviewer. The app has no sign-in, so say so in the review notes and
   give the reviewer a way in: create a demo neighborhood and put an invitation link for it in the
   notes, with the longest expiry (`nwadmin.py invite --hours 720 --phones 4`; 30 days is the
   maximum, so renew it if the review takes longer), and tell them any street name will do.
7. Answer the export-compliance question: the app uses only standard HTTPS encryption (exempt).
8. App Privacy: declare what `docs/PRIVACY.md` lists (identifiers: device id; diagnostics/other
   data: connectivity events; address data: street and, if given, house number). No contact
   information is collected. Declare the data as linked to the user, which is the cautious answer:
   there is no account, but a street is stored next to the phone's id. None is used for tracking. Keep it consistent with
   `apps/ios/NeighborhoodWatchdog/PrivacyInfo.xcprivacy`.

### GitHub settings

Repository secrets `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY`, `IOS_DISTRIBUTION_P12_BASE64`,
`IOS_DISTRIBUTION_P12_PASSWORD`, `IOS_APP_STORE_PROFILE_BASE64` (profile "Feluda App Store");
variable `APPLE_TEAM_ID`. Builds are made by `.github/workflows/build-ios.yml` with manual signing
and handed to testers with `infrastructure/scripts/testflight.py`.

After processing (minutes to an hour), in TestFlight:

1. The external group "Neighbors" exists (public link `https://testflight.apple.com/join/Mx16mTh8`);
   the release workflow adds each release build to it.
2. The **first** build added to an external group goes to Beta App Review. Later builds of the same
   version usually do not need a full review. Do not promise residents a date.
3. Enable a **public link** for the group (limit the tester count) and set it as the backend's
   `IOS_INSTALL_URL`. A public link can be forwarded; the app is useless without a neighborhood
   invitation. Inviting testers one by one by e-mail is tighter, but it means collecting e-mail
   addresses the service itself never asks for.

External testing allows up to 10,000 testers; a build expires 90 days after upload, which is why
"TestFlight refresh" re-uploads the released code every second month. Residents
install Apple's TestFlight app, open the invitation, and tap Install.

## Release notes template

`docs/RELEASE_NOTES_TEMPLATE.md`.

## Verifying what was distributed

`docs/RELEASE_VERIFICATION.md` explains how a resident or reviewer can relate an installed build to
the public source, and where that is not fully possible.
