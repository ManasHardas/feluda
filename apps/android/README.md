# Neighborhood Watchdog: Android app

Native Android client for the private, invitation-only neighborhood connectivity anomaly network.
It watches this phone's own network connection, reports connectivity events to the backend, and
shows household and neighborhood status. It detects unusual patterns; it does not and cannot
identify their cause.

The behaviour contract is `shared/documentation/CLIENT_PROTOCOL.md`; field names and enum values
come from `backend/watchdog/schemas.py` and `enums.py`.

Status words used below: IMPLEMENTED, AUTOMATED_TESTED (JVM unit tests), MANUAL_TEST_REQUIRED
(needs a physical phone; see `TESTING.md`), EXTERNAL_CREDENTIAL_REQUIRED.
**Nothing in this app has been run on a phone or an emulator yet.**

### Verification status (2026-11-21)

- Seen to succeed: `./gradlew --no-daemon :app:compileDebugKotlin` (main sources, including KSP for
  Hilt and Room, and the generated Java).
- Not yet run, because the build machine lost all outbound and loopback TCP connectivity before the
  remaining dependencies could be downloaded: packaging of the debug APK, the unit tests
  (`testDebugUnitTest`; the test sources have not even been compiled), `lintDebug`, and
  `assembleRelease` (R8). Treat the unit tests as written but NOT yet AUTOMATED_TESTED until the
  commands under "Build" have been run and seen to pass.
- No SBOM task exists in this build (no CycloneDX plugin, no `cyclonedxBom` task).

## Build

Requirements: JDK 17, Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk     # or put sdk.dir=... in local.properties (not committed)

./gradlew --no-daemon assembleDebug testDebugUnitTest   # debug APK + unit tests
./gradlew --no-daemon lintDebug                         # Android lint
./gradlew --no-daemon assembleRelease                   # minified release APK (unsigned without signing settings)
./gradlew --stop                                        # make sure no daemon is left running
```

Outputs: `app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/release/app-release-unsigned.apk` (or `app-release.apk` when signed).

Pinned toolchain: Gradle 8.11.1 (wrapper, with distribution checksum), Android Gradle Plugin 8.7.3,
Kotlin 2.0.21, KSP 2.0.21-1.0.28. Every library version is pinned in `gradle/libs.versions.toml`;
there are no dynamic versions. To turn on Gradle dependency verification, generate the metadata once
on a trusted machine and commit it:

```sh
./gradlew --no-daemon --write-verification-metadata sha256 assembleDebug assembleRelease testDebugUnitTest lintDebug
```

Kotlin is compiled inside the Gradle process (`kotlin.compiler.execution.strategy=in-process`) and
the Gradle daemon is off by default (`org.gradle.daemon=false`), so a build leaves nothing running.

## Configuration keys

None of these is a secret. Each is read from a Gradle property (`-PKEY=value` or
`~/.gradle/gradle.properties`) and, if absent, from an environment variable of the same name.

| Key | Meaning | Default |
| --- | --- | --- |
| `API_BASE_URL` | Backend origin. Paths start with `/api/v1`. | debug `http://10.0.2.2:8000`; release: must be set |
| `AUTH_BASE_URL` | Auth (GoTrue) origin plus `/auth/v1`. | debug `http://10.0.2.2:8000/auth/v1`; release: must be set |
| `AUTH_PUBLIC_KEY` | Publishable (anon) key sent as the `apikey` header. Never the service-role key. | blank |
| `SOURCE_REPO_URL`, `PRIVACY_URL`, `SECURITY_CONTACT`, `SECURITY_REVIEW_URL` | Shown on the Transparency screen. | blank ("Not configured in this build") |
| `INTERNET_PROBE_URL` | Second, independent HTTPS reachability target. | `https://connectivitycheck.gstatic.com/generate_204` |
| `FIREBASE_PROJECT_ID`, `FIREBASE_APPLICATION_ID`, `FIREBASE_API_KEY`, `FIREBASE_SENDER_ID` | Firebase Cloud Messaging. | blank (push off) |
| `APPLICATION_ID` | Android application id. | `org.neighborhoodwatchdog.android` |
| `VERSION_NAME`, `VERSION_CODE` | App version and build number. | `0.1.0`, `1` |
| `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Release signing. | absent (unsigned release) |

Rules enforced by the build:

- Release: `API_BASE_URL` and `AUTH_BASE_URL` must start with `https://` or the build fails. If they
  are not set, the release build still succeeds but points at `https://api-not-configured.invalid`,
  which cannot work; set them for a real release.
- Release disallows cleartext traffic (`res/xml/network_security_config.xml`). Debug allows cleartext
  only for `10.0.2.2`, `localhost` and `127.0.0.1` (`src/debug/res/xml/network_security_config.xml`).
- The second probe target defaults to the address Android itself already contacts for its own
  connectivity check, so no additional party learns anything new. Set `INTERNET_PROBE_URL` to your
  own endpoint to change it.

### Firebase without `google-services.json`  (EXTERNAL_CREDENTIAL_REQUIRED)

There is no `google-services.json` and the google-services Gradle plugin is not applied. Firebase is
initialised at startup with `FirebaseOptions.Builder()` from the four `FIREBASE_*` values
(`push/Push.kt`). These values are the public client identifiers from the Firebase console
(Project settings, "Your apps"). If any is blank, Firebase is not initialised and the app shows
"Push notifications are not configured in this build" in onboarding, Settings and Transparency.
Push delivery has not been tested (no Firebase project, no device).

### Release signing

The keystore is never in the repository. Provide the four `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` values through the environment
or `~/.gradle/gradle.properties` (CI: secrets). Without them `assembleRelease` produces an unsigned
APK. Keep the keystore and its passwords in a secrets manager with a backup: losing the key means
residents must uninstall and reinstall. `*.jks`, `*.keystore` and `keystore.properties` are ignored
by git here.

## Package layout

```
org.neighborhoodwatchdog.android
  config/        AppConfig (BuildConfig values)
  domain/        pure Kotlin, no Android classes, unit tested on the JVM
    model/         contract enums, event models, JSON configuration
    monitoring/    ConnectivityStateMachine, NetworkSnapshot, GapDetector, Reconciler, DiagnosticsBuilder
    reporting/     EventUploader, BackoffPolicy, QueuePolicy, EventFactory, queue/transport interfaces
    home/          HomeNetworkFingerprint, InvitationToken
    auth/          TokenManager (refresh under a mutex, rotation)
  data/
    api/           Retrofit interfaces, request/response models, error parsing
    auth/          KeystoreCipher (AES-GCM, AndroidKeyStore), encrypted token store, OkHttp interceptor + authenticator
    local/         Room database (event queue, last 50 sent events), LocalState, FingerprintStore
    repository/    WatchdogRepository, DeviceRepository
  monitoring/    Android side: ConnectivityMonitor (NetworkCallbacks), ProbeRunner, NetworkEventTransport
                 (cellular-bound upload), MonitoringController, WorkManager workers, BootReceiver
  push/          Firebase init, FCM service, notification channels
  session/       onboarding stage, sign-out / leave / delete
  ui/            Compose screens and view models (onboarding, main, incidents, settings, transparency)
  di/            Hilt module
```

## Background strategy (decision)

**What the app does**

1. `ConnectivityManager.NetworkCallback`s are registered in the application process (not in an
   activity): the default network, every Wi-Fi network and every cellular network. While Android keeps
   the process alive and running, changes are seen live.
2. A WorkManager periodic job runs every 15 minutes (the shortest period WorkManager allows). It
   starts the process if needed, registers the callbacks again if they are not registered, measures
   again, writes a `HEARTBEAT` event and flushes the queue.
3. A "last alive" mark (wall clock, time since boot, boot counter) is persisted while the monitor
   runs. Whenever the monitor runs again after more than 2 minutes without having executed any code,
   it emits `MONITORING_GAP_DETECTED` with `gap_started_at`, `gap_ended_at` and a reason:
   `device_reboot` (boot receiver ran, boot counter changed, or time-since-boot went backwards),
   `permission_revoked`, `app_suspended` (same process, it was frozen or the phone slept),
   `background_restricted` (new process and Android reports the app as background-restricted),
   otherwise `process_terminated`.
4. After a gap the state before and after are compared; a difference is reported as one event with
   `confidence = RECONCILED` and `occurred_at` = the resume time. Nothing is back-dated. Changes
   delivered in the first 10 seconds after a suspension are also marked `RECONCILED`, because Android
   may have held those callbacks back for an unknown time.
5. A `BOOT_COMPLETED` receiver records that a reboot happened and starts the monitor again.
6. Every event is written to the Room queue first. An upload is then attempted in the process and,
   as a backup that survives process death, by a WorkManager job with a "network connected"
   constraint, expedited where the OS quota allows.

**What the app does not do, and why**

There is no permanent foreground service.

- Policy: from Android 14 a foreground service needs a declared type, and none of the allowed types
  fits "watch the network forever". Using `dataSync`, `connectedDevice` or `specialUse` for this
  would be a misuse that Google Play rejects, and Android 15 limits `dataSync` to 6 hours a day.
- Battery and trust: a permanent service with a permanent notification costs battery and is the kind
  of behaviour residents and reviewers rightly distrust in a monitoring app.

**The consequence, stated plainly**

When the app is not in the foreground, Android may freeze the process within seconds to minutes
(Android 11+ cached-app freezer), stop it, or defer its jobs (Doze, battery saver, app standby
buckets, manufacturer battery managers). During that time the app does not see network changes. A
Wi-Fi loss that happens while the process is frozen is noticed at the next heartbeat (up to about
15 minutes later, longer in Doze) and reported as a gap followed by a reconciled event, without the
true time of the loss. Expect most background time on modern phones to be reported as
`app_suspended` gaps. The app reports this honestly instead of claiming continuous monitoring, the
main screen and Transparency screen say so, and the backend must treat a household in a gap as
"unknown", not as "healthy".

## How connectivity is classified

`domain/monitoring/ConnectivityStateMachine.kt` is pure Kotlin. Inputs are `NetworkSnapshot`s and a
clock. A change is classified only after the picture has been quiet for 5 seconds (20 seconds at
most if the network keeps flapping), so a roam or band switch produces no `WIFI_LOST`. Events carry
the time of the first callback of the change.

- Internet over Wi-Fi: the app's own HTTPS probe when there is a result, otherwise the OS
  `NET_CAPABILITY_VALIDATED` flag.
- Probe: `GET {API_BASE_URL}/api/v1/monitoring/health`, then `INTERNET_PROBE_URL`; true if either
  answers. Bound to the Wi-Fi network when Wi-Fi is associated.
- Gateway: TCP connect to the default-route gateway from `LinkProperties` on ports 53, 80, 443. A
  refused connection counts as reachable. No ICMP, no assumed address. An IPv6 link-local gateway
  without an interface scope cannot be probed; with only such gateways the value is null.
- Probes are rate-limited: one at a time, at least 10 seconds apart, every 5 minutes while healthy,
  and with backoff (30 s doubling to 5 minutes) while Wi-Fi has no internet.
- Cellular: "available" means a cellular data network is up. While Wi-Fi works, most phones keep
  mobile data down, so `cellular_available` is null (not measured) rather than false.
- A value that was not measured is null. `band_evidence` comes from the Wi-Fi frequency or is
  `BAND_UNKNOWN`. The app never sends `MULTI_BAND_FAILURE_NOT_VERIFIED`.

## Reporting

- Queue: Room table `queued_events`; removed only on `accepted`, `duplicate`, or `rejected` for a
  reason other than `rate_limited`. `event_id` and `occurred_at` never change; `sent_at` is per attempt.
- Backoff: 5 s, factor 2, cap 15 minutes, ±20 % jitter; `retry_after_seconds` / `Retry-After` honoured.
  A connectivity callback may shorten a network-failure wait, but never below 5 seconds and never a
  rate-limit wait.
- Cap: 7 days or 2,000 events; heartbeats are dropped first, then lifecycle and other low-value
  events; failure events only if the queue holds more than 2,000 of them.
- Cellular: when the default network is not validated Wi-Fi or Ethernet, the app calls
  `requestNetwork(TRANSPORT_CELLULAR + NET_CAPABILITY_INTERNET)`, binds an OkHttp client to that
  `Network` (socket factory and DNS, separate connection pool), sends, and releases the request.
  `reported_via` is `cellular` only for that bound request. Otherwise it is the transport of the
  default network at send time.

## Permissions

| Permission | Why | Runtime prompt |
| --- | --- | --- |
| `INTERNET` | Send reports, load status. | no |
| `ACCESS_NETWORK_STATE` | Network callbacks. | no |
| `ACCESS_WIFI_STATE` | Wi-Fi switched on/off, signal strength, band. | no |
| `CHANGE_NETWORK_STATE` | `requestNetwork` for the cellular path when Wi-Fi fails. | no |
| `POST_NOTIFICATIONS` | Alerts. Optional. | Android 13+, with a rationale, during onboarding |
| `RECEIVE_BOOT_COMPLETED` | Restart monitoring after reboot and record the reboot gap. | no |

Added by libraries in the merged manifest (listed with their reason on the Transparency screen):
`WAKE_LOCK` and `FOREGROUND_SERVICE` (WorkManager; the foreground service is used only on Android 11
and older, for seconds, while an expedited upload runs), `com.google.android.c2dm.permission.RECEIVE`
(Firebase Cloud Messaging), and an app-private `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX).

Not requested: any location permission, `NEARBY_WIFI_DEVICES`, `READ_PHONE_STATE`. The app does not
read SSID or BSSID and does not scan for Wi-Fi networks.

Backups: `android:allowBackup="false"` and data-extraction rules that exclude everything.

## Minimum version: Android 8.0 (API 26)

API 26 has `registerDefaultNetworkCallback` and `registerNetworkCallback` with a `Handler`,
`requestNetwork` with a timeout, notification channels and adaptive icons, and covers about 97 % or
more of active devices. Supporting older versions would need separate code paths for all of these.

## Android-version-specific behaviour

| Android | Effect on this app |
| --- | --- |
| 8-9 (API 26-28) | RSSI and frequency from `WifiManager.getConnectionInfo()`. SSID is not read. |
| 9+ (API 28) | The app can see that the user restricted it in the background (`isBackgroundRestricted`). |
| 10+ (API 29) | Wi-Fi SSID/BSSID need location permission; the app does not ask and does not need them. Signal strength also comes from `NetworkCapabilities`. Wi-Fi scans are throttled (4 per 2 minutes in the foreground, about 1 per 30 minutes in the background), which is why the app does not scan and cannot observe a second band. |
| 11+ (API 30) | Cached processes may be frozen: callbacks are not delivered until the app runs again. Reported as gaps. |
| 12+ (API 31) | `WifiInfo` arrives through `NetworkCapabilities.getTransportInfo()`; without `FLAG_INCLUDE_LOCATION_INFO` (not set) it is redacted but still carries RSSI and frequency. Expedited WorkManager jobs replace the short foreground service. Data-extraction rules apply. |
| 13+ (API 33) | `POST_NOTIFICATIONS` is a runtime permission. |
| 14+ (API 34) | Foreground services need a type; none fits, so none is used. |
| 15 (API 35) | Edge-to-edge is enforced; target SDK is 35. |
| All | Doze and app standby defer the 15-minute job (maintenance windows can be an hour or more apart in deep Doze). Battery saver delays background work further. |

## Known limitations

- Not run on any device or emulator. Everything in `TESTING.md` is MANUAL_TEST_REQUIRED.
- Background monitoring is intermittent by design (see above).
- `external_power_lost_recently` covers unplug events from 15 seconds before the change until the
  event is written (about 5 seconds after), not the full 15 seconds after: waiting longer would delay
  the report. Unplug events are only seen while the process is alive.
- The home-network fingerprint is weak on networks without an IPv6 link-local router address: two
  networks with the same IPv4 gateway and prefix (for example `192.168.1.1/24`) then match each other.
- A token refresh is sent over the default network, not the bound cellular network. If Wi-Fi is
  associated without internet and Android has not switched the default network, a report that
  needs a refresh first waits for the next retry.
- No permission that monitoring needs can be revoked by the user on Android (they are install-time
  permissions), so `MONITORING_PERMISSION_REQUIRED` is implemented but not expected in practice.
  `PERMISSION_CHANGED` is emitted when the notification permission changes.
- RSSI is read when Android delivers a capabilities change, at each probe and at each heartbeat; the
  app does not poll, so `SIGNAL_STRENGTH_CHANGED` can lag.
- Invitation links are pasted into the app; the app does not register itself as a handler for the
  `https://<host>/join` link because the host is deployment-specific.
- Firebase App Distribution / Play internal testing are not set up here (no credentials).

## Tests

`./gradlew --no-daemon testDebugUnitTest` runs JVM tests only. They use fakes and a `MockWebServer`
on localhost and never contact a real server.

| Test class | Covers |
| --- | --- |
| `ConnectivityStateMachineTest` | Wi-Fi loss, reconnection, Wi-Fi without internet, cellular available / unavailable, airplane mode, debounce and transitions, signal strength rule, paused is never HEALTHY |
| `GapDetectorTest` | reboot, process termination, suspension, clock changes, reconciliation |
| `EventUploaderTest` | durable queue, backoff, delayed upload keeps `occurred_at`, duplicates, rate limits, batch size, queue cap |
| `HomeNetworkFingerprintTest` | hashing and matching, invitation parsing |
| `DiagnosticsTest` | null-when-unmeasured, band mapping, power rule |
| `ContractJsonTest` | field names and enum spellings against the contract, round trip |
| `TokenManagerTest`, `AuthNetworkingTest` | refresh serialisation and rotation, single retry on 401, sign-in calls |
