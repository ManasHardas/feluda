# Threat model

Scope: the two apps, the backend, the database and the invitation web page, for a pilot of one or a
few neighborhoods. Method: assets, actors, then threats by STRIDE category with the control in
place and the risk that remains. Reviewed by the build agent on 2026-11-21 and revised on
2026-11-23 for the removal of sign-in (decision 0014); not reviewed by an independent party.

There is no sign-in. A phone is enrolled with a private invitation link and is then recognised by a
device secret; administration uses one operator secret. Several risks below exist because of that
choice and were accepted by the owner. They are marked **Accepted (0014)**.

## Assets

1. Which street, and where given which house number, belongs to which household, and that a
   household takes part.
2. Connectivity history of a household (it reveals when people are home and when the house has no
   Wi-Fi, which is useful to a burglar).
3. The integrity of alerts: residents must be able to trust that an alert reflects real reports,
   and that silence is not manufactured.
4. Availability of ingestion and alerting during an incident.
5. Device secrets, live invitation links and the operator secret.
6. Signing keys and service credentials.

No names, e-mail addresses, phone numbers or passwords are held, so they are not assets here.

## Actors

| Actor | Capability |
| --- | --- |
| Outsider on the internet | Can reach public endpoints; has no invitation |
| Holder of a leaked invitation link | Can enrol phones until the link is used up, expires or is revoked |
| Burglar near the neighborhood | May disrupt Wi-Fi or power; may hold a stolen phone or a leaked invitation |
| Malicious or compromised resident | An enrolled phone in one neighborhood; can modify their app or call the API directly |
| Colluding residents | Several enrolled households in one neighborhood |
| Whoever holds the operator secret | Full administration of every neighborhood on the deployment |
| Curious operator or provider employee | Database or host access |
| Supply-chain attacker | Tampered dependency or build |

## Threats

### Spoofing

| Threat | Control | Residual risk |
| --- | --- | --- |
| Join without being invited | Random hashed invitation tokens with a use limit (default 4 phones), expiry and revocation; uniform errors; rate limits on verification and enrolment | None known without a link |
| Join with a leaked or forwarded link | The link is sent privately to one home; it stops working when used up, expired or revoked; the organizer sees every household and its phone count and can remove either | **Accepted (0014):** the link is the whole gate. Whoever has a live link can enrol up to its remaining uses, name any street, and then sees neighborhood-level alerts and counts as a participating household. Nothing checks that they live there |
| Attach a phone to someone else's household | A home that already has an enrolled phone can only be joined with the link that created it or one the organizer issued for it; a household without a house number can only be joined with its own link | A link holder can claim an address that nobody has enrolled yet, including one the organizer added in advance. The real resident then gets a conflict and must ask the organizer, who removes the false household |
| Act as an enrolled phone | 256-bit random device secret, stored hashed on the server and in the platform keystore on the phone; removable by the household or the organizer | **Accepted (0014):** no second factor, no expiry, no rotation. A secret copied from a phone (malware, a rooted or jailbroken device, an unlocked phone in someone's hands) works until that phone is removed, and nothing detects the copy |
| Act as the administrator | One operator secret, at least 32 characters outside development, compared in constant time; the administrator API is off when it is empty | **Accepted (0014):** a single shared credential with no second factor. If it leaks, the holder controls every neighborhood until it is rotated |
| Report as another device or household | A phone can upload only under its own id; the database insert policy re-checks | None known |
| Fake push notifications | Only the backend holds FCM and APNs credentials | Compromise of those credentials |

### Tampering

| Threat | Control | Residual risk |
| --- | --- | --- |
| Fabricate a neighborhood alert from one household | Distinct-household minimum; per-device onset allowance; one device per event batch | None from a single household |
| One person creates several households | A link has a use limit; enrolments are audited; the organizer sees the household list | **Mitigated:** one link is one household (the first enrolment binds the link; later phones join it whatever they type), so faking several households needs several links from the organizer. Review `nwadmin.py households` after inviting and remove what does not belong |
| Colluding residents fabricate an alert | Onset allowance, audit trail, the organizer can suspend | **Accepted for the pilot:** 3 colluding households can cause a level-2 alert, and with sustained false reports a level-3 alert |
| Suppress a real alert by reporting "healthy" | A healthy report only cancels the reporter's own household | A resident can hide their own household's outage only |
| Replay or reorder events | Idempotent event ids; timestamp window; sequence ordering | Back-dating within the 7-day window is possible for a modified client, limited to its own household |
| Change detection thresholds | Operator secret only, bounded, audited | Whoever holds the operator secret can make detection insensitive within the bounds |
| Tamper with the app | Server-side authorization does not trust the client | A modified client can send false readings for its own household |

### Repudiation

Administrative actions are written to an audit log that residents cannot read or edit.
Limits: the log records the action and its target but not a person, because every administrator
uses the same operator secret (**Accepted (0014)**); the operator can alter the database; audit
entries are kept 90 days.

### Information disclosure

| Threat | Control | Residual risk |
| --- | --- | --- |
| Resident reads another household's address or events | API scoping, row-level security, incident rows per own household only, aggregate-only incident views | In a very small neighborhood, "3 households affected" may be guessable to a neighbor who knows who takes part |
| Enumerate invitations | 256-bit tokens, uniform errors, rate limits | None practical |
| Learn whether an address takes part | Enrolment needs a live link | A link holder who enrols with a house number learns from the conflict answer that this address already takes part. Costs one attempt each, limited to 10 per 10 minutes per client address |
| Invitation token in logs, previews or referrers | URL fragment, no-referrer policy, log redaction, tests | The invitation is visible to anyone who can read the chat it was sent in |
| Invitation token captured on the phone | The page hands the token to the app through the `feluda://` link | On Android another installed app can register the same link scheme and receive the token instead. Not verified on devices; pasting the link into the app avoids it |
| Burglar learns a house has no Wi-Fi right now | Only the household itself sees its own incidents; neighborhood alerts carry no location | A burglar who holds an invitation sees neighborhood-level alerts like everyone else |
| Push text on a lock screen | Alerts carry no address or household identity | The existence of an alert is visible |
| Database or backup leak | Minimal data; no names, e-mail addresses or passwords stored; device secrets and invitation tokens stored only as hashes of 256-bit random values; provider encryption at rest | Streets, house numbers where given, and 30 days of connectivity history would be exposed. Push tokens too |
| Operator misuse | Minimization, short retention | Not technically prevented |

### Denial of service

| Threat | Control | Residual risk |
| --- | --- | --- |
| Flood the API | Per-client and per-device limits; small request sizes | A distributed flood needs the hosting provider's protections; none are configured |
| The disruption itself blocks reporting | Uploads try cellular; events are queued and sent later with their original time | **Inherent:** if Wi-Fi and cellular are both unavailable, nothing is reported until connectivity returns, and an alert cannot reach phones without connectivity. A broadband jammer defeats this product in real time |
| Backend or database down | Health checks, uptime probe, queued uploads on phones | A single small instance and a free-tier database are not highly available |
| Push provider outage | Retries with backoff, delivery tracking, in-app status | Alerts are late or missing |
| Use up a link's enrolments, or claim addresses first | The organizer can revoke the link, remove the false households and issue a new one | Nuisance, and work for the organizer |
| A resident loses the app or the phone | A new link from the organizer | No self-service recovery: the phone is not monitored until the organizer acts |

### Elevation of privilege

| Threat | Control | Residual risk |
| --- | --- | --- |
| Resident becomes administrator | There is no administrator role among residents; a device secret is refused on administrator routes; tests | None known |
| Administrator reaches another neighborhood | Each call acts on one named neighborhood | Not a boundary any more: the one operator secret is valid for every neighborhood on the deployment. Run separate deployments if neighborhoods must not share an administrator |
| SQL injection | Parameterised queries only; static analysis in CI | None known |
| Administrator API left open | An empty `ADMIN_API_TOKEN` disables it; short values are refused outside development; tests | A deployment that reuses the development value published in this repository. The 32-character rule does not catch that |
| Operator secret in an app or in the repository | Not needed by the apps; repository scan test | Human error when configuring builds |

## What this system cannot protect against

- A burglar who cuts power and jams cellular: phones cannot report until later.
- A household with no phone at home: nothing observes its network.
- iOS suspension: an iPhone in a pocket may report minutes late or not until opened.
- A false sense of security: the apps and documents say plainly that no report does not mean no
  problem, and that the product does not replace an alarm system.

## Review triggers

Revisit this document before moving from the pilot to wider use, when adding any new data type or
permission, when adding a second API instance, when a second person needs to administer a
deployment, and after any incident.
