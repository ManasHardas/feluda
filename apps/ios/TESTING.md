# Testing the iOS app

## Automated (AUTOMATED_TESTED)

`xcodebuild test` runs the XCTest suite in `NeighborhoodWatchdogTests/` on an iOS simulator. It
covers the state machine, debounce, gap detection and reconciliation, the monitoring coordinator
with fake path and probe, the queue, backoff, reporter, fingerprint, JSON contract, enrolment and
the device secret (stored on enrolment, sent as the bearer token, removed on a 401), the API
client, invitation-link parsing, configuration and wording rules. These tests use fakes for the
network and the clock. They do not prove behaviour on a real phone.

`make ios-core-test` (repository root) runs the same tests for `Core/` natively on the Mac.

`make live-ios` runs `LiveBackendTests` against a development backend on `http://localhost:8000`
(`make live-backend` in another terminal, after `make seed`). The script creates an invitation for
two phones and passes `WATCHDOG_LIVE_API`, `WATCHDOG_LIVE_INVITATION`, `WATCHDOG_LIVE_HOUSE_NUMBER`
and `WATCHDOG_LIVE_STREET`. There is no e-mail and no code. The test checks health, verifies the
invitation, enrols one phone with only the street and one with street and house number, repeats
the enrolment (fresh secret, link not used again), makes the authenticated calls, updates the
device, registers a push token, reads and writes preferences, uploads events twice (accepted, then
duplicate), reads status, neighborhood and incidents, leaves, and confirms the 401 afterwards. It
exercises the networking code only, not the screens.

## Real iPhone tests

**Every test below is MANUAL_TEST_REQUIRED. None has been performed.** No physical device was
available when this app was written. Do not mark any of them passed without doing it.

Setup for all tests: an iPhone with a SIM and mobile data, iOS 17 or later, a signed build
(see README), a reachable backend, an invitation link from the organizer
(`apps/admin/nwadmin.py`), a completed onboarding including the home-network step (test 0), and
notifications allowed. There is no sign-in. Use Settings > Transparency > "See the events on this phone" to read what the
app queued and sent, and the backend's record to compare. Keep the phone unplugged unless the test
says otherwise. Note the wall-clock time of each action.

| # | Test | Status |
| --- | --- | --- |
| 0 | Joining with an invitation link (setup for the others) | MANUAL_TEST_REQUIRED |
| 1 | Wi-Fi loss | MANUAL_TEST_REQUIRED |
| 2 | Cellular transition | MANUAL_TEST_REQUIRED |
| 3 | Wi-Fi recovery | MANUAL_TEST_REQUIRED |
| 4 | Backgrounding | MANUAL_TEST_REQUIRED |
| 5 | Device locking | MANUAL_TEST_REQUIRED |
| 6 | App termination | MANUAL_TEST_REQUIRED |
| 7 | Airplane mode | MANUAL_TEST_REQUIRED |
| 8 | Cellular loss | MANUAL_TEST_REQUIRED |
| 9 | Permission changes | MANUAL_TEST_REQUIRED |
| 10 | Push delivery | MANUAL_TEST_REQUIRED |
| 11 | Background suspension and resume | MANUAL_TEST_REQUIRED |

### 0. Joining with an invitation link — MANUAL_TEST_REQUIRED
1. Fresh install. Open the invitation link in Safari and tap the button that opens the app. Expect
   iOS to ask whether to open the app, then the introduction, then (after "Continue") the join
   screen already showing the name of the neighborhood, with no field for the link.
2. Expect exactly two fields: street, and house number marked optional. No e-mail, no code, no
   label, no provider, no checkbox. "Join" stays disabled while the street is empty.
3. Enter only the street and tap "Join". Expect the notification step next. Under Settings >
   Household membership expect the street and "House number: Not given".
4. Second phone, same link: open the app first (no link), paste the link into the field, tap
   "Continue", give the same street and no house number, join. Expect "Phones: 2" under Settings >
   Household membership on both. On another install, also try pasting only the code after `#t=`,
   and joining with a house number.
5. Paste a made-up or expired link: expect the message that the invitation is not valid and no
   address fields.
6. Use a link whose uses are spent: expect the same message after "Continue".
7. Enter the house number of a home that already takes part through another link: expect the
   server's message and that the phone is not enrolled.
8. Remove the first phone from the second (Settings > Device registration, swipe "Remove"). Open
   the app on the first phone: expect the join screen with a note that the phone is no longer
   enrolled, an empty event list, and no upload attempts.
9. Settings > Leave neighborhood on the second phone: expect the introduction again, and that the
   backend no longer lists the phone or its events. Expect no "Sign out" and no "Delete account"
   anywhere in the app.
10. Delete and reinstall the app, open it: expect the introduction, not the main screen (the
    Keychain is cleared on the first launch of a new install).

### 1. Wi-Fi loss — MANUAL_TEST_REQUIRED
1. App in the foreground on home Wi-Fi; main screen shows Wi-Fi connected, internet reachable.
2. Unplug the router's power (not the phone's Wi-Fi switch).
3. Expect within about 10 seconds: state "Wi-Fi not connected"; a `WIFI_LOST` event whose
   `occurred_at` is the unplug time (not 5 seconds later), `home_network_match: true`,
   `last_wifi_rssi_dbm: null`, `band_evidence: BAND_UNKNOWN`; usually also `DEFAULT_NETWORK_CHANGED`.
4. Expect the event to reach the backend over mobile data, with `reported_via: cellular`.
5. Repeat with the router's internet cable pulled but its power on: expect
   `INTERNET_REACHABILITY_CHANGED` and state "Wi-Fi connected, internet not reachable" within about
   70 seconds (the periodic probe), and record whether the upload left over cellular or waited.
6. Repeat by switching Wi-Fi off in Control Center and in Settings; record what the app reports.

### 2. Cellular transition — MANUAL_TEST_REQUIRED
1. On home Wi-Fi, walk out of range with the app open.
2. Expect `WIFI_LOST` and `DEFAULT_NETWORK_CHANGED` with `default_transport: cellular`.
3. Stand at the edge of range so Wi-Fi drops and returns within 2 to 3 seconds: expect no event.

### 3. Wi-Fi recovery — MANUAL_TEST_REQUIRED
1. After test 1, power the router again with the app open.
2. Expect `WIFI_RESTORED` only once the internet is reachable over Wi-Fi, state back to healthy,
   `home_network_match: true`.
3. Connect to a different Wi-Fi instead (for example a hotspot from another phone): expect
   `home_network_match: false` and record which event type is sent.

### 4. Backgrounding — MANUAL_TEST_REQUIRED
1. With events queued (create them in airplane mode, then restore the network), press Home.
2. Expect the queue to be uploaded within the short background window.
3. Return after 1 minute: expect no gap event. Return after 5 minutes: expect
   `MONITORING_GAP_DETECTED` with `gap_reason: app_suspended`, then `MONITORING_RESUMED`.

### 5. Device locking — MANUAL_TEST_REQUIRED
1. App in the foreground, lock the phone for 10 minutes, unplug the router at minute 3.
2. Unlock. Expect a gap event, then `WIFI_LOST` with `confidence: RECONCILED` and `occurred_at`
   equal to the unlock time (not minute 3), then `MONITORING_RESUMED`.
3. Restart the phone, do not unlock for 20 minutes, then unlock and open the app. Record the
   behaviour (settings and Keychain are unavailable before the first unlock).

### 6. App termination — MANUAL_TEST_REQUIRED
1. Swipe the app away in the app switcher. Wait 10 minutes. Open it.
2. Expect a gap with `gap_reason: process_terminated` and no background activity in between.
3. Restart the phone, open the app after more than 2 minutes: expect `gap_reason: device_reboot`.

### 7. Airplane mode — MANUAL_TEST_REQUIRED
1. App open, switch airplane mode on (make sure Wi-Fi goes off with it).
2. Expect state "No connection at all"; `WIFI_LOST`, `CELLULAR_UNAVAILABLE` and
   `DEFAULT_NETWORK_CHANGED` queued, `airplane_mode: null`.
3. Wait 10 minutes, switch airplane mode off. Expect the queued events to be uploaded with their
   original `occurred_at`, a later `sent_at`, and no duplicates on the backend.

### 8. Cellular loss — MANUAL_TEST_REQUIRED
1. On Wi-Fi, switch Mobile Data off in Settings: expect `CELLULAR_UNAVAILABLE`, state stays healthy.
2. Now unplug the router: expect "No connection at all" and events kept in the queue.
3. Switch Mobile Data back on: expect upload. Also test Settings > Mobile Data with this app's
   switch off, and Low Data Mode on.

### 9. Permission changes — MANUAL_TEST_REQUIRED
1. Switch notifications off for the app in iPhone Settings, return to the app: expect
   `PERMISSION_CHANGED` and the main screen showing notifications off.
2. Switch Background App Refresh off for the app: expect `PERMISSION_CHANGED` and the warning.
3. Decline notifications during a fresh onboarding (after joining): expect the app to continue and
   say so.

### 10. Push delivery — MANUAL_TEST_REQUIRED (also EXTERNAL_CREDENTIAL_REQUIRED)
1. Confirm the backend received an APNs token (hex) with `apns_environment: sandbox` for a
   development build, `production` for TestFlight.
2. Trigger an alert from the backend with the app in the foreground, in the background, and
   force-quit. Expect a banner each time; tapping opens that incident.
3. Send a payload with `"interruption-level": "time-sensitive"` during a Focus and record the result.
4. Reinstall the app and confirm a new token is registered.

### 11. Background suspension and resume — MANUAL_TEST_REQUIRED
1. Leave the app in the background for several hours on a normal day with Background App Refresh on.
2. Record how often a `HEARTBEAT` arrived at the backend (background refresh runs). Expect irregular
   intervals; none is also a valid result.
3. Open the app: expect the gaps listed under "Known monitoring gaps" to match the time without
   heartbeats.
4. Repeat with Low Power Mode on.
5. In Xcode, pause in the debugger and run
   `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"app.feluda.refresh"]`
   to force one refresh and confirm a `HEARTBEAT` is uploaded.
