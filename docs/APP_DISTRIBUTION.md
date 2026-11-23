# Private beta distribution

The pilot needs no public store listing. Android goes out through Firebase App Distribution,
iOS through TestFlight external testing.

**Status: EXTERNAL_CREDENTIAL_REQUIRED.** Workflows and steps are prepared; no build has been
distributed, no Firebase project or Apple account exists for this project yet.
TestFlight external testing additionally needs Apple's review of the first build
(EXTERNAL_REVIEW_REQUIRED); how long that takes is up to Apple.

## Android: Firebase App Distribution

Reference: https://firebase.google.com/docs/app-distribution

### One-time setup

1. Create a Firebase project (console.firebase.google.com). Add an Android app with the package
   name `org.neighborhoodwatchdog.android` (or your own; then set the `APPLICATION_ID` Gradle
   property to match).
2. Project settings → General: note the **Project ID**, **App ID**, **Web API key** and
   **Sender ID**. The app is configured from these four values at build time; no
   `google-services.json` is committed (`apps/android/README.md`).
3. App Distribution → Testers and groups: create the group `neighborhood-pilot`.
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

### GitHub settings (environment `beta`)

| Kind | Name |
| --- | --- |
| Secrets | `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, `FIREBASE_SERVICE_ACCOUNT_JSON` |
| Variables | `API_BASE_URL`, `AUTH_BASE_URL`, `AUTH_PUBLIC_KEY`, `PRIVACY_URL`, `SECURITY_CONTACT`, `FIREBASE_PROJECT_ID`, `FIREBASE_ANDROID_APP_ID`, `FIREBASE_API_KEY`, `FIREBASE_SENDER_ID` |

### Each release

Run the workflow **Distribute Android (Firebase App Distribution)** with the tester group and
release notes. It runs the unit tests, builds a signed release APK, prints its SHA-256 and uploads
it. By hand instead:

```bash
cd apps/android
ANDROID_KEYSTORE_PATH=/path/release.jks ANDROID_KEYSTORE_PASSWORD=... ANDROID_KEY_ALIAS=watchdog ANDROID_KEY_PASSWORD=... \
API_BASE_URL=https://api.example.org AUTH_BASE_URL=https://<ref>.supabase.co/auth/v1 AUTH_PUBLIC_KEY=<publishable key> \
./gradlew --no-daemon assembleRelease
firebase appdistribution:distribute app/build/outputs/apk/release/app-release.apk \
  --app <FIREBASE_ANDROID_APP_ID> --groups neighborhood-pilot --release-notes "..."
```

### Inviting testers

Add a resident's Google account e-mail to the group, or create an **invite link** for the group
(App Distribution → Invite links) and set it as the backend's `ANDROID_INSTALL_URL`, so the
registration page offers it. Testers accept the invitation, install the Firebase App Tester app or
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
   `com.manashardas.feluda` (or your own) with the capabilities **Push Notifications** and
   **Time Sensitive Notifications**. Background modes (fetch, remote notifications) are in the app's
   Info.plist.
3. Keys: create an **APNs key** (.p8). Give the backend `APNS_KEY_ID`, `APNS_TEAM_ID`,
   `APNS_BUNDLE_ID` and the key contents as `APNS_PRIVATE_KEY`.
4. App Store Connect → Apps → New App with that bundle id. This creates the record TestFlight
   needs; it is **not** a public App Store listing and nothing is published by doing this.
5. App Store Connect → Users and Access → Integrations → App Store Connect API: create a key with
   the App Manager role for the upload workflow.
6. Fill in TestFlight → Test Information: beta app description, feedback e-mail, privacy policy URL
   (`https://<your host>/privacy`), and sign-in instructions for Apple's reviewer. The reviewer
   needs a way in: create a demo neighborhood with one approved address and give them an invitation
   link and a mailbox they can read, or review notes that explain the one-time-code sign-in.
7. Answer the export-compliance question: the app uses only standard HTTPS encryption (exempt).
8. App Privacy: declare what `docs/PRIVACY.md` lists (contact info: e-mail address; identifiers:
   device id; diagnostics/other data: connectivity events; coarse address data: house number and
   street), all linked to the user, none used for tracking. Keep it consistent with
   `apps/ios/NeighborhoodWatchdog/PrivacyInfo.xcprivacy`.

### GitHub settings (environment `beta`)

| Kind | Name |
| --- | --- |
| Secrets | `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY` |
| Variables | `APPLE_TEAM_ID`, `IOS_BUNDLE_ID`, `API_BASE_URL`, `AUTH_BASE_URL`, `AUTH_PUBLIC_KEY` |

### Each release

Run the workflow **Distribute iOS (TestFlight)** with a build number higher than the last. It
archives with automatic signing through the API key and uploads to App Store Connect. By hand:
open `apps/ios/NeighborhoodWatchdog.xcodeproj` in Xcode, set the team, Product → Archive,
Distribute App → TestFlight & App Store → Upload. The workflow has never been run; expect to adjust
signing settings on the first attempt.

After processing (minutes to an hour), in TestFlight:

1. Create an external group, for example "Neighborhood pilot", and add the build.
2. The **first** build added to an external group goes to Beta App Review. Later builds of the same
   version usually do not need a full review. Do not promise residents a date.
3. Invite testers by e-mail, or enable a **public link** for the group (limit the tester count) and
   set it as the backend's `IOS_INSTALL_URL`. A public link can be forwarded; the app is useless
   without a neighborhood invitation, but prefer e-mail invitations if that matters to you.

External testing allows up to 10,000 testers; a build expires 90 days after upload. Residents
install Apple's TestFlight app, open the invitation, and tap Install.

## Release notes template

`docs/RELEASE_NOTES_TEMPLATE.md`.

## Verifying what was distributed

`docs/RELEASE_VERIFICATION.md` explains how a resident or reviewer can relate an installed build to
the public source, and where that is not fully possible.
