# Operations runbook

For whoever runs a deployment. Commands marked **owner** need the database owner connection string
(`OWNER_URL` below); keep it out of shell history (`read -rs OWNER_URL`).

Operator commands: `cd backend && DATABASE_URL="$OWNER_URL" uv run python -m watchdog.ops <command>`.
They are deliberately not reachable through the API, and each writes an audit record.

## Routine

| When | What |
| --- | --- |
| Weekly during the pilot | `nwadmin.py summary`, `nwadmin.py failures`, and the security-event query below |
| Weekly | Check the "Security" workflow results and open dependency updates |
| Monthly | Run a backup and a test restore into staging |
| After each release | Compare artifact checksums (`docs/RELEASE_VERIFICATION.md`) |

Administrator commands (`nwadmin.py`) need `NWADMIN_API` and `NWADMIN_TOKEN` (the deployment's
`ADMIN_API_TOKEN`); see `apps/admin/README.md`. There is no sign-in for residents or for the
organizer (decision 0014).

## Create a neighborhood

```bash
python -m watchdog.ops create-neighborhood --name "Oak Ridge"
```

It starts empty. Phones join through invitation links (`docs/ONBOARDING.md`). With more than one
neighborhood on a deployment, every `nwadmin.py` call must name one (`--neighborhood <uuid>` or
`NWADMIN_NEIGHBORHOOD`).

## Disable a neighborhood

```bash
python -m watchdog.ops list-neighborhoods
python -m watchdog.ops set-neighborhood-status --neighborhood-id <uuid> --status disabled
```

Effect, immediately: phones cannot read neighborhood data or upload events (403), no incidents are
created, no notifications are planned. Data is kept. `--status active` reverses it.

## Revoke a compromised device

- The household: on another phone of the same home, Settings → the list of the home's phones →
  remove. A phone can also remove itself. (The apps are being updated for this screen; the call
  behind it is `POST /api/v1/devices/revoke`.)
- The organizer: `nwadmin.py device-revoke <device id> --reason "lost phone"`. The id comes from the
  security events query below or from the Settings screen of a phone in that home.
- Effect: the phone's device secret and push token are deleted and its monitoring session is
  closed. Every request from it answers 401 from then on, and the app shows the invitation screen.
  Its record and the events it already reported stay until retention removes the events or the
  household is removed.
- A device secret does not expire and there is no second factor. Until the phone is removed, a
  lost or stolen phone (or a copy of its secret) keeps full access to that household's view. There
  is nothing else to sign out of.
- To let the home use the phone again, or a replacement: `nwadmin.py invite --household <id> --phones 1`.

## Revoke an invitation

`nwadmin.py invitations`, then `nwadmin.py invite-revoke <invitation id>`. A revoked invitation
answers exactly like an unknown one. Phones it already enrolled stay enrolled; remove them or their
household separately if the link went to the wrong person.

## Disable notifications

| Scope | How |
| --- | --- |
| Everything, at once | Remove the push credentials and restart: `fly secrets unset FCM_SERVICE_ACCOUNT_JSON APNS_PRIVATE_KEY`. Deliveries are then recorded as `skipped_not_configured`; nothing is sent |
| One neighborhood | Disable the neighborhood (above) |
| Make alerts rarer in one neighborhood | `nwadmin.py config-set correlated_score_threshold=60 min_affected_households=4` |
| One resident | They change preferences in the app |

## Investigate a suspected false positive

1. `nwadmin.py incidents` to find it; the resident app's incident screen (or
   `GET /api/v1/incidents/{id}` from an enrolled phone) shows the score breakdown, supporting and missing
   evidence.
2. Look at the raw pattern (**owner**):

   ```sql
   SELECT e.occurred_at, e.received_at, round(e.report_delay_s) AS delay_s, h.anonymous_reference, h.isp,
          e.platform, e.event_type, e.connectivity_state, e.confidence, e.excluded_reason, e.diagnostics
   FROM nw.connectivity_events e JOIN nw.households h ON h.id = e.household_id
   WHERE e.neighborhood_id = '<neighborhood id>'
     AND e.occurred_at BETWEEN '<start>'::timestamptz - interval '10 minutes' AND '<start>'::timestamptz + interval '30 minutes'
   ORDER BY e.occurred_at, e.sequence;
   ```

3. Ask: were phones at home (`home_network_match`)? Did charging phones lose power? Same provider?
   Did Wi-Fi stay associated? Was it brief? Is there a pattern at this time of day
   (`SELECT started_at FROM nw.incidents WHERE neighborhood_id = ... ORDER BY started_at`)?
4. Clusters that did not become incidents are in `nw.incident_candidates` with the reason.
5. Adjust thresholds if a pattern is clear (`nwadmin.py config`, `config-set`), and write down what
   was changed and why in `ai/docs/decisions/`. Replay a scenario locally to see the effect:
   `make simulate SCENARIO=C`.

Security events (**owner**):

```sql
SELECT created_at, kind, device_id, household_id, detail FROM nw.security_events
WHERE created_at > now() - interval '7 days' ORDER BY created_at DESC;
```

`suspicious_reporting` on one device repeatedly means a faulty or dishonest reporter: talk to the
household, or revoke the device. `enrolment_refused` means a link was used with an address that
already takes part, or with a phone id that is already enrolled: usually a second phone of a home
that was given the wrong link, occasionally someone probing.

## Restore a database backup

1. Stop the backend (`fly scale count 0 --app <app>`) so nothing writes during the restore.
2. `pg_restore --clean --if-exists --no-owner -d "$OWNER_URL" <dump file>` (check the file's
   SHA-256 first against the value printed when it was made).
3. `python -m watchdog.migrate --check` must report nothing pending; if the dump is older than the
   code, run `python -m watchdog.migrate`.
4. Re-run `infrastructure/scripts/create_app_login.sql` if the login role's grants were lost.
5. Start the backend (`fly scale count 1`), run `infrastructure/scripts/smoke_test.sh <url>`.
6. Events residents' phones queued meanwhile upload on their own. Incidents and notifications
   between the backup and the restore are lost; say so to the organizer.

## Rotate credentials

Table of secrets and steps: `docs/SECURITY.md`, "Key rotation". Order for a routine rotation: create
the new credential, set it in the secret store, restart, verify with the smoke test and one test
notification, then revoke the old credential. For a suspected leak, revoke first.

## Rotate the operator secret

Do this when someone who knew it should no longer administer, and at once if it may have leaked.
It is one shared secret with no second factor and no per-person audit trail.

```bash
openssl rand -hex 32                                   # put the output in a password manager
read -rs NEW_TOKEN                                     # paste it; keeps it out of shell history
fly secrets set --app <app> ADMIN_API_TOKEN="$NEW_TOKEN"   # restarts the app
```

The old value stops working at the restart. Give the new one to the organizer through a private
channel and update `NWADMIN_TOKEN` wherever the admin tool is used.
Afterwards check `nwadmin.py audit` and `nwadmin.py invitations` for anything you did not do.

## Suspected data breach

1. **Contain.** Rotate the database password, the operator secret and every credential that may be exposed. If the
   backend itself is suspect, stop it (`fly scale count 0`). Disable affected neighborhoods.
2. **Preserve.** Export host logs for the period, and copy `nw.admin_audit_logs` and
   `nw.security_events` before retention removes them.
3. **Assess.** What could have been read: streets, house numbers where given, up to 30 days of
   connectivity history, push tokens. Device secrets and invitation tokens are stored only as
   hashes of long random values, so a copy of the database does not let someone act as a phone.
   There are no names, e-mail addresses, passwords, payment data, location tracks or camera data in
   the system. Which neighborhoods, which period.
4. **Invalidate.** Revoke outstanding invitations. If device secrets themselves could have been
   taken (from phones, or from the backend's memory or traffic rather than from the database),
   there is no bulk reset: remove the affected phones or households and invite them again.
5. **Notify.** Tell the organizer and affected residents plainly what happened, what was exposed
   and what they should do. Check whether a legal notification duty applies where you are; this
   document is not legal advice.
6. **Fix and record.** Patch the cause, add a test, write up the incident and update
   `docs/THREAT_MODEL.md`.

## Deletion requests

A resident who leaves in the app deletes their phone's record and everything it reported; there is
no account and nothing at a third party to clean up. If someone deleted the app without leaving,
or asks for the household's street and house number to be erased as well, remove the household
(`nwadmin.py remove <id>`), which deletes its phones and events, and then delete the row (**owner**):
`DELETE FROM nw.households WHERE id = '<household id>';`
The foreign keys cascade to what still refers to the household. This statement has not been tried
against a real deployment; try it on staging first.

## App crash reporting

Neither app ships a third-party crash SDK. iOS: crashes from TestFlight testers who share
diagnostics appear in Xcode → Organizer → Crashes. Android: testers can send feedback through
Firebase App Distribution; adding Crashlytics is a decision for the owner because it sends device
data to Google (`jtbd/todo.md`).

## Background worker

The API process runs a maintenance pass every 20 seconds (escalation, expiry, notification retries;
retention every 6 hours). `tick_errors` in the metrics should stay at zero. If the host cannot keep
a process alive, set `BACKGROUND_WORKER_ENABLED=false`, set `INTERNAL_TICK_TOKEN`, and have a
scheduler call `POST /internal/tick` every minute, or run `python -m watchdog.worker` somewhere.
