# Detection protocol

How connectivity reports become classifications. The code is `backend/watchdog/correlation/engine.py`
(pure functions) and `service.py` (storage). Parameters and their defaults are in
`backend/watchdog/config.py`; an administrator can change them per neighborhood within fixed bounds.

**These are initial experimental parameters for a pilot, not validated detection thresholds.**
**No output of this system is proof of deliberate interference or of criminal activity.**

## 1. From events to household episodes

A failure **onset** is one of:

| Event | Failure mode |
| --- | --- |
| `WIFI_LOST`, cellular available or unknown | `wifi_lost` |
| `WIFI_LOST` with state `INTERNET_UNAVAILABLE` or cellular unavailable | `all_connectivity_lost` |
| `INTERNET_REACHABILITY_CHANGED` with state `WIFI_CONNECTED_NO_INTERNET` | `no_internet` |

An event is **not** an onset when: the user caused it (`user_initiated`, Wi-Fi switched off,
airplane mode); the phone was on a Wi-Fi network that is not the home network
(`home_network_match = false`); it was reconstructed after a monitoring gap (`RECONCILED`: its real
time is unknown); or abuse controls excluded it (more than 6 onsets per device per hour).

An **episode** runs from a household's first onset until any of its devices reports `HEALTHY` on
the home network. Events with the same timestamp are ordered by the device's sequence number. If a
different phone of the same household, which did not itself fail, is healthy at home within 90
seconds of the onset, the onset is discarded: one phone had a problem (or left), not the home.

## 2. Clustering

Onsets are grouped when each is within 60 seconds of the previous one, within 300 seconds of the
first, and within 500 metres of the first household. A household counts once per cluster. A
household without a registered location is treated as inside the radius and the incident says so.
Clusters in different places at the same time become separate incidents.

Correlation uses the time the event **happened** on the phone, not the time it arrived. A report
that arrives more than 120 seconds late is counted as delayed and the incident says how many were.

## 3. Who counts as "active"

The denominator is households with telemetry in the last 45 minutes, plus the affected ones.

| Status | Meaning |
| --- | --- |
| `CONFIRMED_ACTIVE` | A device reported recently and says it is monitoring |
| `FAILURE_REPORTED` | A device recently reported a failure state |
| `KNOWN_GAP` | The last monitoring session ended and nothing has resumed |
| `PAUSED` | The resident switched monitoring off, or the device was revoked |
| `NO_RECENT_TELEMETRY` | Nothing recent and no known reason |
| `NEVER_REPORTED` | Registered but never reported |

**A household that is not reporting is never counted as affected and never shown as safe.**

## 4. A cluster of several households

A cluster must have at least 3 households and at least 10 % of active households. Smaller clusters
are recorded as candidates and each household is assessed on its own (section 5).

| Check, in order | Classification | Level |
| --- | --- | --- |
| Most homes kept Wi-Fi and lost only internet | `POSSIBLE_ISP_OUTAGE` | 1 |
| At least 2 phones were charging and half or more of those lost power at that moment | `POSSIBLE_ROUTER_OR_POWER_FAILURE` | 1 |
| Otherwise score it (below). Score 40 or more | `CORRELATED_NEIGHBORHOOD_DISRUPTION` | 2 |
| Score 75 or more **and** 4 or more households **and** half or more still down after 120 s | `HIGH_PRIORITY_SUSPICIOUS_DISRUPTION` | 3 |
| Score below 40 | not promoted; households assessed individually | |

### The score

| Indicator | Max | How it is earned |
| --- | --- | --- |
| Affected households | 20 | proportional, full at 6 |
| Share of active households | 10 | proportional, full at 50 % |
| Time tightness | 10 | full when simultaneous, zero at a 60 s spread |
| Provider diversity | 15 | two or more known providers among the affected |
| Cellular stayed available | 15 | share of reporting devices with cellular up |
| Wi-Fi association lost | 10 | share of homes that lost Wi-Fi itself, not just internet |
| Strong signal before the loss | 5 | share of devices that report signal strength and had -70 dBm or better (Android only) |
| Persistence | 15 | share of homes still without Wi-Fi after 120 s |
| Penalty: power evidence | up to -30 | share of charging phones that lost power |
| Penalty: recurring time of day | -10 | 2 or more similar incidents within 30 minutes of this time of day in the last 14 days |

The result is clamped to 0 to 100. Every incident stores the breakdown with one sentence per
indicator, a list of supporting evidence and a list of missing evidence. The apps always show the
explanation next to the number.

Provider diversity raises suspicion only inside this wireless-loss path. If homes on two providers
all keep Wi-Fi and lose internet, that is a wider upstream problem, not interference.

### False-positive scenarios and how they are handled

| Situation | What the system does |
| --- | --- |
| Provider-wide outage | Wi-Fi stays associated: classified as a possible provider outage |
| Neighborhood power cut | Charging phones lose power: classified as power or infrastructure failure. With no charging phone it cannot tell, and says so under missing evidence |
| Scheduled router firmware update | Usually brief (no persistence, so never level 3); recurring nightly updates trigger the time-of-day penalty |
| Widespread DNS failure | Looks like "no internet" across providers: upstream problem |
| Mobile carrier outage | Lowers the cellular indicator; reports arrive late and are marked delayed |
| Phone OS update or reboot | Reported as a monitoring gap; reconstructed changes are not onsets |
| Several residents rebooting routers | Needs 3 households within 60 seconds and a score of 40; brief, so never level 3 |
| A phone leaving the house | Not the home network, or weak fading signal, or another phone at home is healthy: no onset or household-only record |

## 5. One household alone

| Evidence | Classification |
| --- | --- |
| Wi-Fi associated, internet unreachable, router answers or is unchecked | `POSSIBLE_ISP_OUTAGE` |
| Wi-Fi associated, router does not answer | `POSSIBLE_ROUTER_OR_POWER_FAILURE` |
| Wi-Fi lost and the phone stopped charging at that moment | `POSSIBLE_ROUTER_OR_POWER_FAILURE` |
| Wi-Fi lost, cellular up, and a strong signal just before, or the third loss in 30 minutes | `POSSIBLE_WIRELESS_INTERFERENCE` |
| Wi-Fi lost with a weak, fading signal on one phone, first time | nothing recorded (the phone most likely left) |
| Anything else | `HOUSEHOLD_CONNECTIVITY_ISSUE` |

All are level 1 and visible only to that household. One device can never raise the level: the
minimum for a neighborhood incident is bounded at 2 households in configuration and is 3 by default.

## 6. Incident lifecycle

- **Open**: re-assessed on every upload and every tick. The classification can change as evidence
  arrives. `peak_alert_level` keeps the highest level reached.
- **Resolved**: 60 % of affected households (all, for a household incident) reported recovery.
- **Expired**: 60 minutes without new evidence. Shown as "closed without recovery reports; outcome
  unknown". Many iPhones never report recovery, so expiry is common and is not a claim of safety.
- **Merged**: a household incident absorbed into a neighborhood incident.

## 7. Alert levels and who is told

| Level | Meaning | Recipients |
| --- | --- | --- |
| 0 | Nothing unusual reported | nobody |
| 1 | Household warning, or a likely outage or power failure affecting several homes | the affected households only; a plain single-phone Wi-Fi loss is shown in the app but not pushed (decision 0010) |
| 2 | Correlated wireless disruption | every participating household |
| 3 | High-priority suspicious disruption | every participating household, marked time-sensitive |

The service never contacts the police or anyone else on a resident's behalf.

## 8. Wi-Fi bands

A phone is associated with one access point on one band. The apps send `band_evidence`:
`BAND_2_4_OBSERVED`, `BAND_5_OBSERVED` or `BAND_6_OBSERVED` when Android exposes the frequency of the
network that was lost, `BAND_UNKNOWN` otherwise (always on iOS), and
`MULTI_BAND_FAILURE_NOT_VERIFIED` to state explicitly that other bands were not checked. The backend
stores this as evidence and does not use it in the score. Nothing in the product claims that both
bands failed, and nothing claims spectrum analysis.

## 9. Words the product uses

Connectivity interruption, wireless connectivity anomaly, correlated wireless disruption, suspected
RF interference, elevated neighborhood alert. The only place the word "jamming" appears in resident-
facing text is the sentence "This does not confirm deliberate jamming." Tests enforce this for
notification text and incident explanations.
