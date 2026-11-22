# Privacy

Being a member of a private neighborhood group does not remove the need for individual consent and
data minimization. This document says what the system handles, why, who can see it, how long it is
kept, and how it is deleted. A short version for residents is served at `/privacy` and shown in the
apps before monitoring can be switched on.

This is a description of the software, not legal advice. Whoever operates a deployment is the data
controller for it and should check what local law requires (for example a privacy notice naming the
operator).

## What is collected and why

| Data | Why | Where stored |
| --- | --- | --- |
| House number and street name (normalized) | To match a resident to an address the organizer approved, and to relate nearby households | `households` |
| Household coordinates (entered by the organizer, optional) | Distance between households for correlation | `households`; readable only by the backend |
| E-mail address | Sign-in by one-time code and account recovery | `users`, and at the identity provider |
| Optional household label and internet provider | Label is for the resident's own screen; provider helps tell an outage from something unusual | `households` |
| Connectivity events: type, state, time, platform, OS and app version, and the diagnostics listed in `shared/documentation/CLIENT_PROTOCOL.md` | The purpose of the service | `connectivity_events` |
| Device registration: random install id, platform, versions, capability flags | To attribute events and revoke devices | `devices` |
| Push token | To deliver alerts | `notification_tokens` |
| Notification preferences | Quiet hours and opt-outs | `notification_preferences` |

## What is never collected

Browsing history, DNS lookups, URLs, messages, device contents, Wi-Fi names, Wi-Fi or router
passwords, continuous or one-off GPS location, hardware identifiers, advertising identifiers,
contacts, camera footage, microphone audio, and credentials for cameras, alarms, routers or internet
providers. The apps request no location, camera, microphone or contacts permission. The API rejects
any field it does not know, so a modified client cannot send extra data into storage
(`test_malformed_events_are_rejected`).

The home-network fingerprint is computed and kept on the phone; only "matches / does not match /
unknown" is sent (decision 0008).

## Who can see what

| Who | Can see |
| --- | --- |
| A resident | Own household's address, label, devices, events and status; neighborhood incidents as totals and approximate extent |
| Other residents | Never another household's address, events, devices or identity. They see counts such as "6 households affected" |
| Neighborhood administrator | The approved address list with membership status and counts of members and devices; incident history without the households involved; delivery failures; the audit log. Not e-mail addresses, push tokens, device details or anyone's event history |
| The correlation component | Coordinates, provider and status of households, and events. Not addresses, labels, e-mail addresses or tokens (enforced by database grants) |
| Operator of the deployment | Technically everything in the database and host. Access should be limited to the fewest people and is not logged by the application itself |
| Supabase, the hosting provider, Google (FCM), Apple (APNs) | What their service needs: Supabase stores the database and e-mail addresses; FCM and APNs receive the push token and the alert text (which contains no address) |

Incident records shown to residents carry an extent rounded up to 50 metres, never coordinates.

## Consent and control

- The registration page states what is collected and requires an explicit acknowledgement.
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
| Account data | Until the account is deleted | |
| Database backups | Supabase free plan: none. Paid plans: per Supabase's schedule. Own `pg_dump` copies: operator's choice, suggested 7 days | |

Deletion runs every 6 hours in the backend (`services/retention.py`), and on demand with
`python -m watchdog.ops purge`.

## Leaving and deletion

| Action | Effect |
| --- | --- |
| Leave neighborhood (in the app) | Membership ended; the person's devices, their events, sessions, push tokens and preferences deleted. If they were the last resident of the household, all of the household's events are deleted and its label cleared; the address returns to "approved" |
| Delete account (in the app) | Everything above, and the account's e-mail address and identity link are erased. If `AUTH_ADMIN_URL` and `AUTH_SERVICE_ROLE_KEY` are set, the sign-in record at the identity provider is deleted too; otherwise the operator is told to do it by hand (`python -m watchdog.ops pending-identity-deletions`) |
| Administrator removes a household | Members removed; devices and events deleted; label cleared |

What remains after deletion: incident summaries (counts, times, classification, score) until their
90-day retention ends, including a row linking the household's internal id to incidents it was part
of; audit entries that mention the internal ids; the address on the organizer's approved list unless
the organizer removes it; and copies inside backups until those expire.

## Security of the data

See `docs/SECURITY.md`. In short: HTTPS only, row-level security in the database, credentials in
platform secure storage, no secrets in the apps, and logs without personal data.
