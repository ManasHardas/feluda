# Testing

## What is automated

| Suite | Where | Needs | Count at hand-off |
| --- | --- | --- | --- |
| Backend unit tests (engine, scoring, normalization, tokens, configuration, limiter) | `backend/tests/test_unit_engine.py` | nothing | 49 |
| Backend integration (onboarding, devices, leaving, deletion) | `backend/tests/test_onboarding.py`, `test_smoke.py` | PostgreSQL | 18 |
| Backend security (authorization, isolation, row-level security, replay, malformed input, rate limits, secrets, logs) | `backend/tests/test_security.py` | PostgreSQL | 42 |
| Scenario simulation A to J | `backend/tests/test_scenarios.py` | PostgreSQL | 11 |
| Notifications (routing, duplicates, retries, expiry, preferences, FCM and APNs request shape) | `backend/tests/test_notifications.py` | PostgreSQL | 14 |
| Administration, configuration, retention, migrations, schema | `backend/tests/test_admin_and_ops.py` | PostgreSQL | 10 |
| Android unit tests | `apps/android/app/src/test` | JDK 17, network for the first dependency download | 9 test files written; **never compiled or run** |
| iOS unit tests | `apps/ios/NeighborhoodWatchdogTests` | Xcode, a simulator | 97 |

Run them:

```bash
make backend-test          # starts PostgreSQL in Docker, runs all backend tests
make backend-test-docker   # the same, entirely inside containers
make backend-unit          # only the tests that need no database
make android-test
make ios-test
make test                  # everything, plus lint and the API contract check
```

Database tests use a real PostgreSQL because row-level security cannot be tested otherwise. Each
session creates a fresh database, connects the application through a non-owner login role, and
drops the database afterwards. In CI (`REQUIRE_DATABASE=1`) a missing database is an error, never a
silent skip. Locally without Docker the database tests are skipped and say so.

Current results and what has not been verified are in `docs/STATUS.md`.

## Scenario simulator

`backend/watchdog/simulation/scenarios.py` defines scenarios A to J as timed uploads from the seeded
neighborhood. The tests play them through the HTTP API with an injected clock.

| | Scenario | Expected and asserted |
| --- | --- | --- |
| A | One household loses Wi-Fi | Household incident, level 1; invisible to others; no neighborhood incident |
| B | Five Xfinity households lose internet, Wi-Fi stays associated | `POSSIBLE_ISP_OUTAGE`, level 1, no interference score |
| C | Three AT&T and three Xfinity households lose Wi-Fi, cellular up | `CORRELATED_NEIGHBORHOOD_DISRUPTION`, level 2, everyone alerted once; becomes level 3 only after it persists |
| D | Ten households, power cut | `POSSIBLE_ROUTER_OR_POWER_FAILURE`; persistence does not turn it into a wireless alert |
| E | One device sends 100 false events | 60 accepted, 40 rate-limited, 54 excluded from correlation; no neighborhood incident |
| F | Events arrive 10 minutes late | Correlated by occurrence time; incident starts when it happened; 5 delayed reports noted |
| G | Resident disables monitoring | `PAUSED`, grey, never green, however long ago |
| H | No cellular: event queued on the phone | Accepted later with the original time and a 900-second delay; recorded as already resolved |
| I | iOS suspends the app | Session closed as `app_suspended`; reconstructed loss is stored but not used as an onset |
| J | Two households, 30 minutes apart | Two household incidents; no neighborhood incident |

H and I are also covered on the phone side by the apps' unit tests (queueing without connectivity,
gap detection and reconciliation).

Try them by hand: `make docker-up migrate seed run`, then in another terminal
`make simulate SCENARIO=C`.

## What needs a person and real hardware

Marked MANUAL_TEST_REQUIRED in `docs/STATUS.md`. **None of this has been done.** No physical phone
was available during the build.

- Android: `apps/android/TESTING.md` (Wi-Fi disconnection and reconnection, Wi-Fi without internet,
  cellular available and unavailable, airplane mode, reboot, process termination, background
  restrictions, battery saver, permission revocation, network transitions, delayed uploads).
- iOS: `apps/ios/TESTING.md` (Wi-Fi loss, cellular transition, recovery, backgrounding, locking,
  termination, airplane mode, cellular loss, permission changes, push delivery, suspension and
  resume). Use real iPhones; the simulator has no cellular radio and does not run background refresh.
- End to end: a real push arriving on each platform; the registration page opened from WhatsApp on
  both platforms; uploads really leaving over cellular when home Wi-Fi is off; battery use over a day.

Suggested first field test: two households, three phones, one evening. Switch a router off for
three minutes with one phone on charge and one not, and compare what each phone reported with what
the backend concluded.

## Adding tests

- A new detection rule needs a unit test in `test_unit_engine.py` and, if it changes an outcome, a
  scenario.
- A new endpoint needs an authorization test (no token, wrong role, other neighborhood).
- A new table needs row-level security and a line in the role tests.
- Never assert on wording that claims confirmation; `test_wording_never_asserts_jamming_and_never_promises_delivery`
  and `test_every_neighborhood_explanation_denies_confirmation_and_never_says_jamming` guard the vocabulary.
