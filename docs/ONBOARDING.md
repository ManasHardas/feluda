# Onboarding a neighborhood

For the neighborhood organizer (administrator). Residents need only the invitation link.

## Before inviting anyone

1. The operator creates the neighborhood and your administrator account
   (`docs/DEPLOYMENT.md`, step 5). You sign in to the app with that e-mail address.
2. Decide who may join. A WhatsApp group is a convenient way to *reach* people, but being in the
   group is not authorization. Only addresses you approve can register.
3. Tell residents plainly what this is: an anomaly detector that depends on phones being at home
   and on, with gaps, and not a replacement for an alarm system.

## Approve addresses

With the command-line tool (`apps/admin/README.md`):

```bash
python apps/admin/nwadmin.py --api https://api.example.org login
python apps/admin/nwadmin.py household-add --number 14 --street "Oak Ridge Road" --isp xfinity --lat 33.0501 --lon -97.0502
python apps/admin/nwadmin.py households
```

Coordinates are optional but needed for the 500-metre check; without them a household is treated as
nearby. Take them from a map by hand. Addresses are not sent to any geocoding service.

## Invite

```bash
python apps/admin/nwadmin.py invite                      # any approved address without a resident yet
python apps/admin/nwadmin.py invite --household <id>     # one specific household (needed for a second resident)
```

The link is shown once. Send it to that resident privately. It expires after 72 hours by default
and works once. Prefer household-bound invitations: a leaked general invitation could be used by
someone who knows an approved address that has not registered yet.

Message you can adapt:

> You're invited to our private Neighborhood Watchdog pilot. It watches for unusual Wi-Fi
> disruptions across our street and alerts everyone taking part. Open this link on your phone to
> register (it works once and expires in 3 days): <link>
> It never collects browsing, passwords, location or camera footage. Please don't forward the link.

## What the resident does

1. Opens the link. The page explains the product and what is collected, checks the invitation, and
   asks for house number, street, e-mail address and, optionally, a label and internet provider.
2. Installs the app from the link shown after registering (Android: Firebase App Distribution;
   iPhone: TestFlight). See `docs/APP_DISTRIBUTION.md` for what testers see.
3. Opens the app, reads the short introduction, gives explicit consent, and signs in with the same
   e-mail address and the 6-digit code sent to it.
4. Allows notifications, and confirms once that the phone is on the home Wi-Fi.
5. Monitoring starts. The main screen shows a colour with an explanation.

Residents who install the app first can paste the invitation link into the app instead of using
the web page.

## Afterwards

| Task | Command |
| --- | --- |
| See who joined | `nwadmin.py households` |
| Add a second resident to a household | `nwadmin.py invite --household <id>` |
| Withdraw an unused invitation | `nwadmin.py invitations`, then `nwadmin.py invite-revoke <id>` |
| Suspend or reinstate a household | `nwadmin.py suspend <id>` / `nwadmin.py reinstate <id>` |
| Remove a household and its data | `nwadmin.py remove <id>` |
| Check overall status | `nwadmin.py summary` |
| Review incidents | `nwadmin.py incidents` |

Every one of these is recorded in the audit log (`nwadmin.py audit`).

## Support answers

- **"I never got the code."** Check spam. Codes expire after an hour. The sender is the sign-in
  service configured for the deployment.
- **"My invitation says it cannot be used."** It expired, was used, or was locked after several
  wrong addresses. Issue a new one.
- **"It says my address is not recognized."** The house number and street must match the approved
  list. `nwadmin.py households` shows the normalized form (`14`, `oak ridge rd`).
- **"The app is grey."** Monitoring is off, or the phone has not reported recently. On iPhone this
  is normal when the app has not been opened for a while. Grey means unknown, not safe.
