# Privacy

Being a member of a private neighborhood group does not remove the need for individual consent and
data minimization. This document says what the system handles, why, who can see it, how long it is
kept, and how it is deleted. A short version for residents is served at `/privacy` and shown in the
apps before monitoring can be switched on.

Nobody signs in (decision 0014). The service has no accounts and never asks for a name, an e-mail
address, a phone number or a password. A phone joins with a private invitation link; the resident
types the street and, only if they wish, a house number.

This is a description of the software, not legal advice. Whoever operates a deployment is the data
controller for it and should check what local law requires (for example a privacy notice naming the
operator).

## What is collected and why

| Data | Why | Where stored |
| --- | --- | --- |
| Street name (required, typed by the resident, normalized) | To say which street a household is on | `households` |
| House number (optional, typed by the resident, normalized) | To make the household that address, so that phones joining with another link cannot attach themselves to it | `households` |
| Household coordinates (never asked of residents; present only if the organizer adds the household by hand with them) | Distance between households for correlation | `households`; readable only by the backend |
| Device secret | To recognise the phone on later requests, in place of an account | On the phone, in the platform keystore. On the server only as a SHA-256 hash, in `device_credentials` |
| Which invitation link enrolled a phone or created a household | To group the phones of one home, and to let a retried enrolment succeed without using the link twice | `devices`, `households` |
| Optional household label and internet provider | Label is for the resident's own screen; provider helps tell an outage from something unusual | `households` |
| Connectivity events: type, state, time, platform, OS and app version, and the diagnostics listed in `shared/documentation/CLIENT_PROTOCOL.md` | The purpose of the service | `connectivity_events` |
| Device registration: random install id, platform, versions, capability flags | To attribute events and remove phones | `devices`, with one row per phone in `users` that holds nothing but an id and a status |
| Push token | To deliver alerts | `notification_tokens` |
| Notification preferences | Quiet hours and opt-outs | `notification_preferences` |

## What is never collected

Names, e-mail addresses, phone numbers, passwords, browsing history, DNS lookups, URLs, messages, device contents, Wi-Fi names, Wi-Fi or router
passwords, continuous or one-off GPS location, hardware identifiers, advertising identifiers,
contacts, camera footage, microphone audio, and credentials for cameras, alarms, routers or internet
providers. The apps request no location, camera, microphone or contacts permission. The API rejects
any field it does not know, so a modified client cannot send extra data into storage
(`test_malformed_events_are_rejected`, `test_nothing_personal_is_stored_or_accepted`).

A street, and more so a street with a house number, is still information about a home. It is
stored next to the home's connectivity history, which is why the rest of this document applies even
though nobody is named.

The home-network fingerprint is computed and kept on the phone; only "matches / does not match /
unknown" is sent (decision 0008).

## Who can see what

| Who | Can see |
| --- | --- |
| A resident's phone | Own household's street, house number if one was given, label, phones (platform and versions), events and status; neighborhood incidents as totals and approximate extent |
| Other residents | Never another household's address, events, devices or identity. They see counts such as "6 households affected" |
| The organizer (whoever holds the operator secret) | Every household's street, house number if given, provider, membership status, number of phones and whether it has coordinates; invitations with their use counts; incident history without the households involved; delivery failures; the audit log. Not push tokens, device secrets, device details or anyone's event history |
| The correlation component | Coordinates, provider and status of households, and events. Not streets, house numbers, labels, device secrets or tokens (enforced by database grants) |
| Operator of the deployment | Technically everything in the database and host. Access should be limited to the fewest people and is not logged by the application itself |
| Supabase, the hosting provider, Google (FCM), Apple (APNs) | What their service needs: Supabase stores the database and nothing else (its sign-in service is not used); FCM and APNs receive the push token and the alert text (which contains no address) |

Incident records shown to residents carry an extent rounded up to 50 metres, never coordinates.

## Consent and control

- The invitation page states what is and is not collected. The app shows the privacy summary and
  requires an explicit acknowledgement before it enrols the phone; the server refuses an enrolment
  without it (`test_privacy_acknowledgement_is_required`).
- Each app explains monitoring before it can be enabled and requires explicit consent; nothing is
  monitored before that.
- Monitoring can be switched off in the app at any time. The backend then shows the device as
  paused, never as healthy.
- The Transparency screen shows the queued and recently sent events, the permissions granted, the
  last successful report and the source code link.

## Retention

| Data | Kept for | Setting |
| --- | --- | --- |
| Heartbeat events | 3 days | `retention_heartbeat_days` |
| Other connectivity events and device sessions | 30 days | `retention_raw_events_days` |
| Incident summaries and candidates | 90 days after they start | `retention_incidents_days` |
| Administrative audit log and security events | 90 days | `retention_audit_days` |
| Notification delivery records | 30 days | `retention_notification_deliveries_days` |
| Used, revoked and expired invitations | 90 days | `retention_audit_days` |
| A phone's enrolment record and the hash of its secret | Until the phone leaves or its household is removed. A phone that was removed by its household or the organizer keeps its record, without the secret, until then | |
| Household record (street, optional house number, provider) | Until its last phone leaves; see below | |
| Database backups | Supabase free plan: none. Paid plans: per Supabase's schedule. Own `pg_dump` copies: operator's choice, suggested 7 days | |

Deletion runs every 6 hours in the backend (`services/retention.py`), and on demand with
`python -m watchdog.ops purge`.

## Leaving and deletion

| Action | Effect |
| --- | --- |
| Leave (in the app) | The phone's record, its secret, the events it reported, its sessions, push token and preferences are deleted. If it was the last phone of the household, all of the household's events are deleted, and so is the household record with the street and house number residents typed. (An address the organizer added in advance keeps only what the organizer entered, with label and provider cleared.) There is no account, so there is nothing further to delete and no separate "delete account" action |
| A phone is removed by another phone of its household, or by the organizer | Its secret and push token are deleted and its session closed; it can no longer connect. Its record and the events it reported stay until retention removes the events or the household is removed |
| The organizer removes a household | All its phones and events deleted; label cleared; outstanding links for it revoked. The household record stays, marked as removed |
| The app is deleted from the phone without leaving first | The server is not told. The phone's record stays, and its events until retention removes them. Ask the organizer to remove the phone or the household |

What remains after the last phone of a home leaves: incident summaries (counts, times,
classification, score) until their 90-day retention ends, without any link to the household; audit
entries that mention internal ids; and copies inside backups until those expire. Two cases keep a
household record: an address the organizer added in advance (only what the organizer entered), and
a household the organizer removed (kept, with its address, so the same address cannot simply join
again). The operator can delete either row in the database on request.

## Security of the data

See `docs/SECURITY.md`. In short: HTTPS only, row-level security in the database, credentials in
platform secure storage, no secrets in the apps, and logs without addresses or secrets. A phone is
recognised only by its device secret: there is no second factor, so a household should remove a
lost phone (or ask the organizer to).
