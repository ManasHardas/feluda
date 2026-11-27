# Neighborhood Watchdog for iOS

Native SwiftUI app (Swift 6 language mode, iOS 17.0+, no third-party dependencies). It watches the
connection state of the phone, reports changes to the Neighborhood Watchdog backend and shows
household and neighborhood status. It is an anomaly detector: it never states the cause of an
interruption.

There is no sign-in: no e-mail address, no one-time code, no account and no token refresh
(`ai/docs/decisions/0014-no-sign-in-device-enrolment.md`). A phone is enrolled once with an
invitation link and then holds one device secret in the Keychain.

The behaviour contract is `shared/documentation/CLIENT_PROTOCOL.md`; request and response shapes
come from `shared/api-contracts/openapi.json`, which is generated from the backend.

Status of this app: IMPLEMENTED and AUTOMATED_TESTED (unit tests on the iOS simulator, and the
networking code against a local backend with `make live-ios`). Everything that needs a real iPhone
or an Apple developer team is MANUAL_TEST_REQUIRED or EXTERNAL_CREDENTIAL_REQUIRED; see
`TESTING.md` and "Not verified" below.

## Layout

| Path | Contents |
| --- | --- |
| `project.yml` | XcodeGen spec, the source of truth for the project |
| `NeighborhoodWatchdog.xcodeproj` | Generated project (regenerate with `xcodegen generate`) |
| `Config/` | `Base`, `Debug`, `Release` xcconfig, `Local.xcconfig.example` |
| `NeighborhoodWatchdog/Core/` | Pure logic, Foundation and CryptoKit only: models, JSON, state machine, gap detection, queue, backoff, reporter, fingerprint, API client (enrolment and the device secret), monitoring coordinator |
| `NeighborhoodWatchdog/Platform/` | Keychain, `NWPathMonitor` service, system info |
| `NeighborhoodWatchdog/App/` | App entry, app delegate (push, background refresh), configuration, composition root, app model |
| `NeighborhoodWatchdog/UI/` | SwiftUI screens and their view models |
| `NeighborhoodWatchdog/Info.plist`, `Info-Debug.plist` | Release and Debug Info.plist (see ATS below) |
| `NeighborhoodWatchdog/PrivacyInfo.xcprivacy` | Privacy manifest |
| `NeighborhoodWatchdogTests/` | XCTest unit tests |
| `scripts/make-debug-plist.sh` | Regenerates `Info-Debug.plist` from `Info.plist` |

## Build and test

```sh
cd apps/ios
xcodegen generate            # only after changing project.yml or adding files

# Debug build for the simulator, no signing
xcodebuild -project NeighborhoodWatchdog.xcodeproj -scheme NeighborhoodWatchdog \
  -destination 'generic/platform=iOS Simulator' -configuration Debug \
  -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO build

# Unit tests (pick a simulator from `xcrun simctl list devices available`)
xcodebuild test -project NeighborhoodWatchdog.xcodeproj -scheme NeighborhoodWatchdog \
  -destination 'platform=iOS Simulator,name=iPhone 17' -configuration Debug \
  -derivedDataPath build/DerivedData

# Release build for the simulator (compile check)
xcodebuild -project NeighborhoodWatchdog.xcodeproj -scheme NeighborhoodWatchdog \
  -destination 'generic/platform=iOS Simulator' -configuration Release \
  -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO build
```

`build/` is git-ignored.

From the repository root: `make ios-test` (simulator), `make ios-core-test` (the `Core` logic as a
Swift package on the Mac, no simulator) and `make live-ios` (runs `LiveBackendTests` against a
development backend on `http://localhost:8000`: health, verify invitation, enrol two phones, the
authenticated calls, event upload with duplicates, status, incidents, leave, and the 401 after
leaving). Without `WATCHDOG_LIVE_API` in the environment that test is skipped.

## Joining (no sign-in)

1. **Introduction.** What the app watches and sends, and what it cannot do. "Continue" only moves
   on; nothing is monitored or sent yet.
2. **Join.** The invitation reaches the app in one of two ways:
   - the web join page opens `feluda://join#t=<token>` (the `feluda` URL scheme is registered in
     `Info.plist` under `CFBundleURLTypes` and handled with `onOpenURL` in `RootView`);
   - the resident pastes the link (`https://…/join#t=<token>`) or the bare token into the field
     that the join screen shows when the app was opened without a link.

   The app checks the invitation (`POST /api/v1/auth/verify-invitation`) and shows the name of the
   neighborhood. Then it asks for exactly two things: the **street** (required) and the **house
   number** (optional; left out of the request when empty). Under them is the short privacy
   summary with a link to the full text. Tapping "Join" is the acknowledgement
   (`privacy_acknowledged: true`) and calls `POST /api/v1/enroll` with a random installation id,
   the iOS and app version and the capability flags.
3. The response contains the **device secret**. It is stored only in the Keychain
   (`KeychainDeviceTokenStore`, readable after first unlock, this device only) and sent as
   `Authorization: Bearer <secret>` on every later request. It never expires and is never
   refreshed.
4. Notification permission and the home-network step follow, as before.

If the server answers **401 `unauthorized`** to any request (the phone was removed or revoked),
the app removes the secret, the queue, the home-network fingerprint and the installation id, and
shows the join screen. A new invitation link is needed. Enrolling again with the same link after a
lost answer is safe: the server issues a fresh secret without using the link a second time.

### App version and "Update required"

Every request to the backend carries `X-App-Version: ios/<CFBundleShortVersionString> (<CFBundleVersion>)`,
for example `ios/0.1.0 (1)` (set in one place, `APIClient`; the reachability probe sends it only
to the service's own health endpoint). The server decides which versions it still accepts
(`MIN_APP_VERSION_IOS`, `LATEST_APP_VERSION_IOS`, `IOS_INSTALL_URL` on the backend).

- **426 `update_required`** to any request: the app sets its update gate, stops sending (event
  uploads, device and push-token updates; nothing is retried) and shows only a full-screen
  "Update required" screen with an "Update" button. Recorded events stay in the queue. The refusal
  is remembered for this version, so a background relaunch does not try again; installing the
  update lifts it. The device secret is kept.
- **`GET /api/v1/app/compatibility?platform=ios&version=<x.y.z>`** (no credential) runs at launch
  and when the app comes to the foreground, at most once an hour. `supported: false` shows the same
  screen; `supported: true` lifts it and sends what waited. `update_available: true` shows a small
  notice "A new version is available" that can be closed. A failed check changes nothing.
- "Update" opens the server's `update_url` (https, `itms-beta:` or `itms-apps:` only), otherwise
  the TestFlight app (`itms-beta://`), otherwise `https://testflight.apple.com/`.

**Leave neighborhood** (Settings) calls `POST /api/v1/households/leave`: the server deletes this
phone and everything it reported, and the app empties itself. There is no account, so there is no
"delete account".

The household label and internet provider are not asked when joining. They can be set later under
Settings > Household membership (`PATCH /api/v1/households/me`).

## Configuration

Build settings in `Config/*.xcconfig` are injected into Info.plist and read by `AppConfig`. No
secrets belong there. There is no auth service to configure.

| Setting | Meaning | Debug default | Release default |
| --- | --- | --- | --- |
| `API_BASE_URL` | Backend origin | `http://localhost:8000` | `https://api.example.org` (replace) |
| `APNS_ENVIRONMENT` | Sent with the push token | `sandbox` | `production` |
| `SOURCE_REPO_URL`, `PRIVACY_URL`, `SECURITY_REVIEW_URL`, `SECURITY_CONTACT` | Transparency screen | empty | empty |
| `PROBE_FALLBACK_URL` | Second reachability target | Apple's success page | same |
| `PRODUCT_BUNDLE_IDENTIFIER` | Bundle id | `app.feluda` | same |
| `DEVELOPMENT_TEAM` | Apple team id | empty | empty |

Empty transparency values are shown in the app as "Not configured in this build".

Override any of them in `Config/Local.xcconfig` (git-ignored; copy `Local.xcconfig.example`).

**URLs in xcconfig:** `//` starts a comment, so `https://host` would be cut to `https:`. Write
`https:/$()/host`. `AppConfig` refuses a cut-off or non-HTTPS URL and the app shows a configuration
error instead of starting.

**App Transport Security:** Release uses `Info.plist`, which has no ATS exceptions, and `AppConfig`
rejects any non-HTTPS service URL. Debug uses `Info-Debug.plist`, identical except for one
exception that allows plain HTTP to `localhost` only. After editing `Info.plist`, run
`scripts/make-debug-plist.sh`.

## Signing, provisioning and capabilities

The project builds and tests on the simulator with no team. To run on an iPhone or to archive:

1. Set `DEVELOPMENT_TEAM` in `Config/Local.xcconfig` (automatic signing is on).
2. Register the bundle id in the Apple developer account with these capabilities:
   - **Push Notifications** (entitlement `aps-environment`; the file says `development`, Xcode
     switches it to `production` when exporting for TestFlight or the App Store).
   - **Background Modes**: Background fetch and Remote notifications. These are the only two modes
     in `UIBackgroundModes`. The BGTask identifier is `<bundle id>.refresh`.
   - **Time Sensitive Notifications** (entitlement
     `com.apple.developer.usernotifications.time-sensitive`). The app does not raise the
     interruption level itself; this only lets a server payload with
     `"interruption-level": "time-sensitive"` be honoured. Remove the entitlement if the backend
     will not send that.
3. The backend needs an APNs key for the same team and bundle id.

Not requested: the **Critical Alerts** entitlement. It needs Apple's individual approval and is not
used. Also not requested: location, Local Network, Bluetooth, camera, microphone, contacts.

TestFlight: archive with the Release configuration, upload, and note that the first build for
external testers goes through Apple's beta review, which has no fixed duration. TestFlight is not
App Store publication.

## How monitoring works

- `PathMonitorService` runs three `NWPathMonitor`s: default path, Wi-Fi only, cellular only.
- A satisfied path does not prove the internet is reachable. `ReachabilityProbe` sends an HTTPS
  request to `/api/v1/monitoring/health` and, if that does not answer, to `PROBE_FALLBACK_URL`. It
  does this twice: once over any path and once with cellular access switched off. The second result
  is "internet works over Wi-Fi" and decides between `HEALTHY` and `WIFI_CONNECTED_NO_INTERNET`.
- `ConnectivityStateMachine` debounces changes for 5 seconds (30 seconds at most) and stamps each
  event with the time of the first callback. A change that reverts inside the debounce produces no
  event.
- `MonitoringCoordinator` turns state changes into events, writes them to the file-backed queue and
  asks `EventReporter` to upload. While the app runs it probes every 60 seconds and sends a
  `HEARTBEAT` every 5 minutes.
- Probes are rate-limited: never more than one probe start per 5 seconds however fast path callbacks
  arrive (extra requests are merged into one deferred probe), and the periodic probe doubles its
  interval after each probe that reached nothing, up to 15 minutes. One probe is at most four
  requests.
- Under XCTest the host app starts nothing: no services, monitors, probes, timers or uploads
  (`AppModel.isRunningTests`). All tests use fake transports and never touch the network.

### Background behaviour (platform limitation)

iOS suspends this app shortly after it leaves the foreground. While suspended it observes nothing.
The app does not use location, audio, VoIP or any other mode to stay alive. What it does:

- writes an "alive" time every 10 seconds while it runs;
- on entering the background, asks for the short standard extension (`beginBackgroundTask`) to
  upload what is queued;
- registers one `BGAppRefreshTask`. iOS decides if and when it runs; it may be hours apart, and it
  does not run at all after a force-quit, with Background App Refresh off, or usually in Low Power
  Mode. Each run takes one observation, sends a `HEARTBEAT` and flushes the queue;
- treats a silent remote notification the same way;
- on every resume or launch, compares the alive time with now. More than 2 minutes means
  `MONITORING_GAP_DETECTED` (`app_suspended`, `process_terminated` after a cold launch,
  `device_reboot` when the device booted inside the gap), followed by the difference between the
  state stored before the gap and the state now, with confidence `RECONCILED` and
  `occurred_at` = resume time.

The main screen states that monitoring is not continuous and shows the last successful observation
and the known gaps.

### Reporting and cellular (platform limitation)

Uploads use a `URLSession` with `allowsCellularAccess`, `allowsExpensiveNetworkAccess`,
`allowsConstrainedNetworkAccess` and `waitsForConnectivity` all true and a 45-second resource
timeout. What iOS does with that:

- When Wi-Fi is down, requests go over cellular if cellular data is on and allowed for this app.
- When Wi-Fi is associated but has no internet, iOS may or may not move traffic to cellular
  (depends on Wi-Fi Assist and its own judgement). The app cannot force it.
- An app cannot choose the interface of a `URLSession` request, and `URLSession` does not say which
  interface a finished request used. `reported_via` is therefore the interface type of the default
  path at send time (`unknown` when there is none), not a proof of routing.
  `can_route_over_cellular` is registered as `false`.

Investigated alternative: `NWConnection` with `requiredInterfaceType = .cellular` does pin a
connection to cellular. Using it for uploads would mean writing an HTTP/1.1 client with TLS, the
bearer header, response parsing and timeouts by hand on top of a raw connection, and it could not be
tested without a device with a SIM. `URLSession` was kept. A cellular-pinned `NWConnection` upload
path is a possible later improvement and is not implemented.

Queue: file-backed (`Application Support/Watchdog/event-queue.json`), atomic writes, file protection
"until first user authentication", excluded from backups. Cap 2,000 events or 7 days; over the count
cap heartbeats are dropped first, then lifecycle events, connectivity events last. Backoff starts at
5 s, doubles, caps at 15 minutes, with plus or minus 20 % jitter; `Retry-After` and
`retry_after_seconds` are honoured; a connectivity callback retries early except during a
server-requested rate-limit wait.

### Values iOS cannot provide

Always null: `last_wifi_rssi_dbm`, `wifi_radio_enabled`, `airplane_mode`,
`local_gateway_reachable`, `vpn_active`. `band_evidence` is always `BAND_UNKNOWN`.
`user_initiated` is true only for the monitoring switch (iOS does not tell an app that the user
switched Wi-Fi off). `external_power_lost_recently` follows the protocol rule but is evaluated when
the event is built, about 5 seconds after it occurred, so a charger unplugged later than that is not
seen. Battery state is not delivered while the app is suspended.

### Home-network fingerprint

Built from `NWPath.gateways` of the Wi-Fi path, salted SHA-256, stored in the Keychain only. Each
gateway address is hashed separately; an IPv6 router address decides when both sides have one,
otherwise the IPv4 gateway is compared. iOS does not always provide gateways (it does not in the
simulator); then the match is null. An IPv4-only match is weak because addresses such as
192.168.1.1 are common.

## Known limitations

- Monitoring is not continuous (above). A loss that starts and ends while the app is suspended is
  invisible; a loss that is still present on resume is reported with the resume time.
- `MONITORING_PERMISSION_REQUIRED` is never produced: path monitoring needs no permission on iOS.
  `PERMISSION_CHANGED` is sent when notification permission or Background App Refresh changes.
- The device secret lives in the Keychain; flags live in UserDefaults. A new install clears leftover
  Keychain items on first launch, so reinstalling the app means asking for a new invitation link.
- If the app is launched in the background before the first unlock after a restart, the Keychain
  cannot be read. The app then decides nothing and removes nothing until it is opened.
- The `feluda://` scheme is a custom URL scheme, not a universal link: any app could register the
  same scheme. The token it carries is only useful together with the server's checks (expiry, use
  count, revocation).
- No UI tests. Screens other than the first onboarding screen were not exercised on a simulator.
- English only.

## Not verified

- No real iPhone was used. Every item in `TESTING.md` is MANUAL_TEST_REQUIRED.
- The screens were never run against a server. Joining, status, incidents, preferences and uploads
  are covered by unit tests with a fake transport and, for the networking code only, by
  `make live-ios` against a local development backend.
- Opening `feluda://join#t=<token>` from the web join page was not tried on a simulator or a phone
  (MANUAL_TEST_REQUIRED, see `TESTING.md`). Only the parsing of the link is unit-tested.
- Push registration and delivery need a team, a provisioning profile and backend APNs credentials:
  EXTERNAL_CREDENTIAL_REQUIRED.
- `BGAppRefreshTask` does not fire on its own in the simulator.
