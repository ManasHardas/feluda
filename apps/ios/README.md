# Neighborhood Watchdog for iOS

Native SwiftUI app (Swift 6 language mode, iOS 17.0+, no third-party dependencies). It watches the
connection state of the phone, reports changes to the Neighborhood Watchdog backend and shows
household and neighborhood status. It is an anomaly detector: it never states the cause of an
interruption.

The behaviour contract is `shared/documentation/CLIENT_PROTOCOL.md`; field names and enums come
from `backend/watchdog/schemas.py` and `enums.py`.

Status of this app: IMPLEMENTED and AUTOMATED_TESTED (unit tests on the iOS simulator). Everything
that needs a real iPhone, an Apple developer team or a running backend is MANUAL_TEST_REQUIRED or
EXTERNAL_CREDENTIAL_REQUIRED; see `TESTING.md` and "Not verified" below.

## Layout

| Path | Contents |
| --- | --- |
| `project.yml` | XcodeGen spec, the source of truth for the project |
| `NeighborhoodWatchdog.xcodeproj` | Generated project (regenerate with `xcodegen generate`) |
| `Config/` | `Base`, `Debug`, `Release` xcconfig, `Local.xcconfig.example` |
| `NeighborhoodWatchdog/Core/` | Pure logic, Foundation and CryptoKit only: models, JSON, state machine, gap detection, queue, backoff, reporter, fingerprint, API client, auth, monitoring coordinator |
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

## Configuration

Build settings in `Config/*.xcconfig` are injected into Info.plist and read by `AppConfig`. No
secrets belong there; the auth key is the publishable key.

| Setting | Meaning | Debug default | Release default |
| --- | --- | --- | --- |
| `API_BASE_URL` | Backend origin | `http://localhost:8000` | `https://api.example.org` (replace) |
| `AUTH_BASE_URL` | Auth origin plus `/auth/v1` | `http://localhost:8000/auth/v1` | `https://auth.example.org/auth/v1` (replace) |
| `AUTH_PUBLIC_KEY` | Publishable key, sent as `apikey` | local placeholder | empty (set it) |
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
connection to cellular. Using it for uploads would mean writing an HTTP/1.1 client with TLS, auth
headers, response parsing and timeouts by hand on top of a raw connection, and it could not be
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
- If the app is launched before the first unlock after a restart, its settings cannot be read and it
  behaves as not set up until the next launch.
- Sign-in state lives in the Keychain; flags live in UserDefaults. A new install clears leftover
  Keychain items on first launch.
- No UI tests. Screens other than the first onboarding screen were not exercised on a simulator.
- English only.

## Not verified

- No real iPhone was used. Every item in `TESTING.md` is MANUAL_TEST_REQUIRED.
- The app was not run against the backend (it was not available). Sign-in, joining, status,
  incidents, preferences and uploads are covered only by unit tests with a fake transport.
- Push registration and delivery need a team, a provisioning profile and backend APNs credentials:
  EXTERNAL_CREDENTIAL_REQUIRED.
- `BGAppRefreshTask` does not fire on its own in the simulator.
