# Physical-device test instructions (Android)

Status of every scenario in this file: **MANUAL_TEST_REQUIRED**. None has been performed. The JVM unit
tests cover the decision logic only; they cannot show how a real phone, radio or Android version
behaves. Do not mark a scenario as passed unless a person ran it on a phone and recorded the result.

## Setup

1. A phone with Android 8.0 or newer, a SIM with mobile data, and a home Wi-Fi network you control
   (you need to be able to unplug the router and to unplug only its internet uplink).
2. Backend reachable from the phone over HTTPS (or, for a debug build on an emulator only,
   `http://10.0.2.2:8000`). Build with `API_BASE_URL` set. There is no sign-in: you need an invitation
   link from the organizer (`apps/admin/nwadmin.py`), one per home, good for a limited number of phones.
3. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Complete onboarding: read the introduction, join with the invitation link (street required,
   house number optional; see scenario 0), allow or decline notifications, confirm the home Wi-Fi.
5. Keep a second view open for evidence:
   - in the app: **Transparency**, sections "Events waiting to be sent" and "Last 50 events sent"
     (tap an event to see the JSON);
   - on the server: the event rows for this device;
   - `adb logcat -s WatchdogMonitor WM-WorkerWrapper` for errors.
6. Record for each run: phone model, Android version, date, result, and the JSON of the events.

Timing to expect: a change is classified about 5 seconds after the first callback. Uploads follow
within seconds when a network is available.

## 0. Joining with an invitation link — MANUAL_TEST_REQUIRED

- Link from the web page: open the invitation link (`https://<host>/join#t=<token>`) in the phone's
  browser and use the page's button that opens the app (`feluda://join#t=<token>`). Do it once with
  the app not running and once with the app already open on the join screen. Expect: the join
  screen shows "Invitation for: <neighborhood>" without anything being pasted.
  `adb shell am start -a android.intent.action.VIEW -d 'feluda://join#t=<token>'` does the same.
- Pasted link: open the app from the launcher. Expect a single field for the invitation link. Paste
  the whole link, then repeat on a fresh install with only the token. A malformed, expired, used-up
  or withdrawn link shows an error and does not continue.
- The form asks only for the street and the house number. Expect: no e-mail, code, password, label,
  provider or checkbox anywhere in onboarding. "Join" with an empty street shows an error and sends
  nothing. Join once with a house number and, on a second phone with the same link, without one.
- A short privacy summary and a link to the full text are shown above "Join".
- An address that already takes part through another link: the server's explanation is shown.
- After joining: Settings shows the street (and house number if given) and the number of phones.
- Removed phone: have the organizer remove this phone (or remove it from another phone of the
  household in Settings). Expect: at the next request the app returns to the join screen and says
  the phone is no longer part of the network; the queue viewer is empty after joining again with a
  new link.
- Reinstall: after reinstalling, the app starts at the introduction and needs a new link.

## 1. Wi-Fi disconnection — MANUAL_TEST_REQUIRED

Steps: app in the foreground, phone on home Wi-Fi, mobile data on. Unplug the router's power.

Expect:
- Main screen "This phone" shows "Network is changing", then "Wi-Fi is disconnected".
- One `WIFI_LOST` event: `occurred_at` within a second or two of the unplug (not 5 s later),
  `connectivity_state = WIFI_DISCONNECTED`, `wifi_associated = false`, `home_network_match = true`,
  `cellular_available = true`, `last_wifi_rssi_dbm` set, `band_evidence` matching the band the phone
  was on, `user_initiated` absent, `wifi_radio_enabled = true`.
- Also `CELLULAR_AVAILABLE` (first time) and `DEFAULT_NETWORK_CHANGED`.
- In "Last 50 events sent" the `WIFI_LOST` entry says "via cellular".

Repeat with the phone charging from a charger on the same power strip as the router and switch the
strip off: `external_power_lost_recently = true`. Repeat on battery: the field is absent.

Variant: switch Wi-Fi off in quick settings instead. Expect `WIFI_LOST` with `user_initiated = true`
and `wifi_radio_enabled = false`.

## 2. Wi-Fi reconnection — MANUAL_TEST_REQUIRED

Steps: after scenario 1, plug the router back in and wait for the phone to rejoin.

Expect: `WIFI_RESTORED` with `connectivity_state = HEALTHY`, `internet_reachable = true`,
`home_network_match = true`; `DEFAULT_NETWORK_CHANGED`. If the phone associates before the router has
internet, expect `WIFI_ASSOCIATION_CHANGED` with `WIFI_CONNECTED_NO_INTERNET` first and
`WIFI_RESTORED` when the internet is back. No `WIFI_RESTORED` without internet.

## 3. Wi-Fi connected without internet — MANUAL_TEST_REQUIRED

Steps: phone on home Wi-Fi. Unplug only the router's WAN / modem cable so Wi-Fi stays up.

Expect: within about a minute (Android's own validation plus the app's probe)
`INTERNET_REACHABILITY_CHANGED` with `connectivity_state = WIFI_CONNECTED_NO_INTERNET`,
`wifi_associated = true`, `internet_reachable = false`, `local_gateway_reachable = true`. The upload
goes over cellular ("via cellular"). Plug the cable back: a second `INTERNET_REACHABILITY_CHANGED`
with `HEALTHY`.

Also check a captive-portal network (hotel or cafe Wi-Fi before signing in): state should be
`WIFI_CONNECTED_NO_INTERNET`, `home_network_match = false`.

## 4. Cellular available while Wi-Fi fails — MANUAL_TEST_REQUIRED

Covered by scenarios 1 and 3. Additionally confirm on the server that `reported_via = cellular` and
that `sent_at - occurred_at` is small (seconds). On a phone with "Mobile data always active" enabled
in developer options, `cellular_available` should already be `true` in events while on Wi-Fi.

## 5. Cellular unavailable — MANUAL_TEST_REQUIRED

Steps: switch mobile data off (or remove the SIM). Then unplug the router.

Expect: state "No connection"; `WIFI_LOST` with `connectivity_state = INTERNET_UNAVAILABLE` and
`cellular_available = false` stays in "Events waiting to be sent" with a growing attempt count.
Restore the router: the queued event is sent, with its original `occurred_at` and a later `sent_at`.

With Wi-Fi off and mobile data on, toggle mobile data off and on: expect `CELLULAR_UNAVAILABLE` then
`CELLULAR_AVAILABLE`.

## 6. Airplane mode — MANUAL_TEST_REQUIRED

Steps: on home Wi-Fi with mobile data on, switch airplane mode on. Wait 30 seconds. Switch it off.

Expect: `WIFI_LOST` with `airplane_mode = true`, `user_initiated = true`,
`connectivity_state = INTERNET_UNAVAILABLE`; nothing is sent while in airplane mode; after switching
it off the queued events are sent and `WIFI_RESTORED` follows. Server side, the classification must
not treat a user-initiated loss as an anomaly.

## 7. Device reboot — MANUAL_TEST_REQUIRED

Steps: note the time, power the phone off, wait 5 minutes, power it on, unlock it, do not open the app.
Wait up to 15 minutes.

Expect: `MONITORING_GAP_DETECTED` with `gap_reason = device_reboot`, `gap_started_at` close to the
power-off time and `gap_ended_at` close to the time monitoring resumed, then `MONITORING_RESUMED`,
then heartbeats. If the Wi-Fi state differed before and after, one event with
`confidence = RECONCILED`. Note: before the first unlock after boot (Direct Boot), the app does not
run; the gap ends after unlock. Some manufacturers block auto-start after boot; record whether
monitoring resumed without opening the app.

## 8. App process termination — MANUAL_TEST_REQUIRED

Steps (a): `adb shell am kill app.feluda` with the app in the background (or
swipe it away from recents). Wait for the next heartbeat (up to 15 minutes) or open the app after
3 minutes.

Expect: `MONITORING_GAP_DETECTED` with `gap_reason = process_terminated` (duration over 2 minutes),
then `MONITORING_RESUMED`.

Steps (b): Settings, Apps, Force stop. Android cancels all jobs of a force-stopped app, so nothing
happens until the app is opened by hand. Expect the gap to be reported then. Record this as an
operating-system limit.

## 9. Background restrictions — MANUAL_TEST_REQUIRED

Steps: Settings, Apps, Neighborhood Watchdog, Battery, choose "Restricted". Leave the app in the
background for 30 minutes, unplug the router during that time, then open the app.

Expect: the main screen warns that Android is restricting the app; a gap with
`gap_reason = background_restricted` or `app_suspended`; the Wi-Fi loss appears as a `RECONCILED`
event with `occurred_at` = the time the app resumed (not the unplug time). Repeat with
"Unrestricted" and compare how long the gaps are.

Also run unrestricted with the screen off for an hour (Doze): record how often heartbeats arrive.

## 10. Battery saver — MANUAL_TEST_REQUIRED

Steps: switch battery saver on. Leave the app in the background for an hour with the screen off.

Expect: events carry `battery_saver = true`; heartbeats are less frequent; the missing time shows up
as gaps; the main screen says battery saver is on. No crash, no lost events.

## 11. Permission revocation — MANUAL_TEST_REQUIRED

Steps: Settings, Apps, Neighborhood Watchdog, Notifications, switch off. Open the app.

Expect: main screen shows "Notification permission: Not granted"; one `PERMISSION_CHANGED` event;
monitoring continues. Switch notifications back on: a second `PERMISSION_CHANGED`.

On Android 13+ also deny the permission in onboarding on a fresh install: onboarding must still
complete and monitoring must start.

## 12. Network transitions — MANUAL_TEST_REQUIRED

Steps:
- Roam: with two access points of the same network (mesh), walk between them. Expect no `WIFI_LOST`.
- Band switch: move until the phone changes from 5 GHz to 2.4 GHz. Expect no `WIFI_LOST`; later
  events show the new `band_evidence`.
- Leave home: walk out of Wi-Fi range. Expect `WIFI_LOST` with `home_network_match = true`.
- Join another Wi-Fi (a friend's house): expect `WIFI_RESTORED` / association events with
  `home_network_match = false`. If that network uses the same router address as home and has no
  IPv6, it may wrongly match; record it.
- VPN: switch a VPN on and off at home. Expect `DEFAULT_NETWORK_CHANGED` with `vpn_active` set, no
  `WIFI_LOST`.
- Flapping: switch Wi-Fi off and on several times within 20 seconds. Expect at most a small number
  of events, no duplicates, state settles within about 25 seconds.

## 13. Delayed event uploads — MANUAL_TEST_REQUIRED

Steps: airplane mode on at home. Wait 20 minutes (heartbeats accumulate). Airplane mode off.

Expect: all queued events are sent oldest first; each has its original `occurred_at` and the same
`event_id` it had in the queue viewer; `sent_at` is the upload time; the server reports no duplicates
when the same test is repeated with the backend stopped during the first attempt (stop the backend,
cause an event, start the backend: the event arrives exactly once).

Rate limit: have the backend answer `rate_limited` with `retry_after_seconds = 60`; confirm the app
waits at least 60 seconds before the next attempt and keeps the event.

## 14. Monitoring toggle, leave — MANUAL_TEST_REQUIRED

- Settings, switch monitoring off: one `MONITORING_DISABLED_BY_USER` with
  `connectivity_state = MONITORING_PAUSED`; no further events; the main screen never shows the phone
  as working normally while paused. Switch on: `MONITORING_RESUMED` with `user_initiated = true`, no gap event.
- Leave neighborhood (asks for confirmation and says that what the phone reported is deleted):
  monitoring stops; the app returns to the introduction; on the server the phone, its events and
  its push token are gone; joining again needs an invitation link that still has a free place.
  There is no sign-out and no "delete account": there is no account.

## 15. Push notifications — MANUAL_TEST_REQUIRED, EXTERNAL_CREDENTIAL_REQUIRED

Needs a Firebase project and the four `FIREBASE_*` build values. Send a level 1 and a level 3 alert
from the backend: the first uses the "Household warnings" channel, the second "Neighborhood alerts";
tapping opens the incident screen. Without the Firebase values, confirm that onboarding, Settings
and Transparency all say "Push notifications are not configured in this build".

## 16. Privacy checks — MANUAL_TEST_REQUIRED

- Settings, Apps, Neighborhood Watchdog, Permissions: no location permission is listed.
- `adb shell dumpsys package app.feluda | grep permission`: compare with the
  list on the Transparency screen.
- `adb backup` / device-to-device transfer: app data is not included.
- Inspect a few event JSONs: no SSID, BSSID, IP address or gateway address appears.
