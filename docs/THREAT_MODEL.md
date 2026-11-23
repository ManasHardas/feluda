# Threat model

Scope: the two apps, the backend, the database and the onboarding web page, for a pilot of one or a
few neighborhoods. Method: assets, actors, then threats by STRIDE category with the control in
place and the risk that remains. Reviewed by the build agent on 2026-11-21; not reviewed by an
independent party.

## Assets

1. Which address belongs to which household, and that a household takes part.
2. E-mail addresses.
3. Connectivity history of a household (it reveals when people are home and when the house has no
   Wi-Fi, which is useful to a burglar).
4. The integrity of alerts: residents must be able to trust that an alert reflects real reports,
   and that silence is not manufactured.
5. Availability of ingestion and alerting during an incident.
6. Signing keys and service credentials.

## Actors

| Actor | Capability |
| --- | --- |
| Outsider on the internet | Can reach public endpoints; has no invitation |
| Burglar near the neighborhood | May disrupt Wi-Fi or power; may hold a stolen phone or a leaked invitation |
| Malicious or compromised resident | Valid account and device in one neighborhood; can modify their app |
| Colluding residents | Several valid accounts in one neighborhood |
| Malicious administrator | Admin role in one neighborhood |
| Curious operator or provider employee | Database or host access |
| Supply-chain attacker | Tampered dependency or build |

## Threats

### Spoofing

| Threat | Control | Residual risk |
| --- | --- | --- |
| Join without being invited | Random single-use hashed tokens; address must be on the approved list; rate limits; lock after 5 wrong addresses | Someone who obtains a live invitation link and knows a not-yet-registered approved address can register as that household. Mitigation: organizers bind invitations to a household and send them privately; they can remove a household |
| Use someone else's account | Token signature, audience and expiry verified; identity bound to the account on first sign-in only | Control of the resident's mailbox equals control of the account |
| Report as another device or household | Device must belong to the caller; database insert policy re-checks | None known |
| Fake push notifications | Only the backend holds FCM and APNs credentials | Compromise of those credentials |

### Tampering

| Threat | Control | Residual risk |
| --- | --- | --- |
| Fabricate a neighborhood alert from one account | Distinct-household minimum; per-device onset allowance; one device per event batch | None from a single household |
| Colluding residents fabricate an alert | Onset allowance, audit trail, administrator can suspend | **Accepted for the pilot:** 3 colluding households can cause a level-2 alert, and with sustained false reports a level-3 alert |
| Suppress a real alert by reporting "healthy" | A healthy report only cancels the reporter's own household | A resident can hide their own household's outage only |
| Replay or reorder events | Idempotent event ids; timestamp window; sequence ordering | Back-dating within the 7-day window is possible for a modified client, limited to its own household |
| Change detection thresholds | Administrator only, bounded, audited | A malicious administrator can make detection insensitive within the bounds |
| Tamper with the app | Server-side authorization does not trust the client | A modified client can send false readings for its own household |

### Repudiation

Administrative actions are written to an audit log that residents and administrators cannot edit.
Limits: the operator can alter the database; audit entries are kept 90 days.

### Information disclosure

| Threat | Control | Residual risk |
| --- | --- | --- |
| Resident reads another household's address or events | API scoping, row-level security, incident rows per own household only, aggregate-only incident views | In a very small neighborhood, "3 households affected within 100 m" may be guessable to a neighbor who knows who takes part |
| Enumerate invitations or addresses | Uniform errors, rate limits, invitation lock | Slow guessing of addresses by someone holding a valid invitation: 5 per hour |
| Token in logs, previews or referrers | URL fragment, no-referrer policy, log redaction, tests | The invitation is visible to anyone who can read the chat it was sent in |
| Burglar learns a house has no Wi-Fi right now | Only the household itself sees its own incidents; neighborhood alerts carry no location | A burglar who is an invited resident sees neighborhood-level alerts like everyone else |
| Push text on a lock screen | Alerts carry no address or household identity | The existence of an alert is visible |
| Database or backup leak | Minimal data; no passwords stored; provider encryption at rest | Addresses, e-mail addresses and 30 days of connectivity history would be exposed |
| Operator misuse | Minimization, short retention | Not technically prevented |

### Denial of service

| Threat | Control | Residual risk |
| --- | --- | --- |
| Flood the API | Per-client and per-device limits; small request sizes | A distributed flood needs the hosting provider's protections; none are configured |
| The disruption itself blocks reporting | Uploads try cellular; events are queued and sent later with their original time | **Inherent:** if Wi-Fi and cellular are both unavailable, nothing is reported until connectivity returns, and an alert cannot reach phones without connectivity. A broadband jammer defeats this product in real time |
| Backend or database down | Health checks, uptime probe, queued uploads on phones | A single small instance and a free-tier database are not highly available |
| Push provider outage | Retries with backoff, delivery tracking, in-app status | Alerts are late or missing |
| Exhaust an invitation's attempts | The lock lasts an hour; the organizer can issue a new one | Nuisance only |

### Elevation of privilege

| Threat | Control | Residual risk |
| --- | --- | --- |
| Resident becomes administrator | Role is server-side; no write path from residents; tests | None known |
| Administrator reaches another neighborhood | Scope from membership; tests | None known |
| SQL injection | Parameterised queries only; static analysis in CI | None known |
| Development sign-in emulator left on | Refused by the settings loader outside development and test; test | None known |
| Service-role key in an app | Not needed by the apps; repository scan test | Human error when configuring builds |

## What this system cannot protect against

- A burglar who cuts power and jams cellular: phones cannot report until later.
- A household with no phone at home: nothing observes its network.
- iOS suspension: an iPhone in a pocket may report minutes late or not until opened.
- A false sense of security: the apps and documents say plainly that no report does not mean no
  problem, and that the product does not replace an alarm system.

## Review triggers

Revisit this document before moving from the pilot to wider use, when adding any new data type or
permission, when adding a second API instance, and after any incident.
