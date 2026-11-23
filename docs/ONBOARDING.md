# Onboarding a neighborhood

For the neighborhood organizer. Residents need only an invitation link. Nobody signs in: there are
no accounts, e-mail addresses, passwords or codes (decision 0014).

Status: the backend side of everything below is covered by automated tests. The two apps are being
updated for this flow and it has not been run end to end on a phone, an emulator or a simulator yet
(`docs/STATUS.md`).

## Before inviting anyone

1. The operator creates the neighborhood (`docs/DEPLOYMENT.md`, step 5) and gives you the
   backend address and the operator secret. Keep the secret like a password: it is the only thing
   that protects administration, it is shared by everyone who administers the deployment, and
   there is no second factor. Set it up once:

   ```bash
   export NWADMIN_API=https://api.example.org
   read -rs NWADMIN_TOKEN && export NWADMIN_TOKEN     # paste the secret; it is not shown or saved in history
   python apps/admin/nwadmin.py summary
   ```

2. Decide who may join. A WhatsApp group is a convenient way to *reach* people, but being in the
   group is not authorization. Whoever holds a live link can join, so send each link privately to
   one home, never to the group.
3. Tell residents plainly what this is: an anomaly detector that depends on phones being at home
   and on, with gaps, and not a replacement for an alarm system.

## Invite

```bash
python apps/admin/nwadmin.py invite                      # one link for one home, up to 4 phones, 72 hours
python apps/admin/nwadmin.py invite --phones 2 --hours 24
python apps/admin/nwadmin.py invite --household <id>     # more phones for a home that already takes part
```

The link is shown once. One link is for one home: all the phones of that home use the same link.
It stops working when it has enrolled its number of phones, when it expires, or when you revoke it.

Give a link no more uses than the home has phones. A link is the whole entry gate: nothing checks
that the person using it lives where they say. One link can only ever make one household, so one
person cannot pose as several homes without several links from you. Look at `nwadmin.py households`
after people join and remove anything you do not recognise.

Message you can adapt:

> You're invited to our private Neighborhood Watchdog pilot. It watches for unusual Wi-Fi
> disruptions across our street and alerts everyone taking part. Install the app, then open this
> link on your phone (it is for your home only and expires in 3 days): <link>
> It asks only for your street. No name, e-mail or account. It never collects browsing, passwords,
> location or camera footage. Please don't forward the link.

## What the resident does

1. Opens the link. The page explains the product and what is collected, checks the invitation, and
   offers the install link for their phone (Android: Firebase App Distribution; iPhone: TestFlight.
   See `docs/APP_DISTRIBUTION.md` for what testers see).
2. Installs the app, returns to the page and taps "Open in the app". If that does nothing, they
   open the app and paste the invitation link there.
3. In the app: reads the short introduction, gives explicit consent, and types the street. A house
   number is optional. Nothing else is asked.
4. Allows notifications, and confirms once that the phone is on the home Wi-Fi.
5. Monitoring starts. The main screen shows a colour with an explanation.

Every further phone of the same home repeats this with the same link.

## How phones become households

- **One link is one household.** The first phone that uses a link creates the household from the
  street and optional house number it typed. Every later phone using the same link joins that
  household, whatever it types.
- A phone of that home that uses a *different* link becomes a second household (or, if the first
  phone gave a house number and this one types the same address, it is refused with "This address
  is already taking part"). So tell each home to use one link for all its phones, or issue
  `invite --household <id>` for the rest.

## Optional: coordinates

Residents are not asked for a location. Without coordinates the 500-metre check is skipped and all
homes in the neighborhood are treated as near each other, which is fine for a few streets
(`docs/DETECTION_PROTOCOL.md`). If you want the distance check, add the home yourself before it
joins, with coordinates taken from a map by hand:

```bash
python apps/admin/nwadmin.py household-add --number 14 --street "Oak Ridge Road" --isp xfinity --lat 33.0501 --lon -97.0502
python apps/admin/nwadmin.py invite --household <id from the line above>
```

Addresses are not sent to any geocoding service. There is no command to add coordinates to a
household that already exists.

## Afterwards

| Task | Command |
| --- | --- |
| See who joined | `nwadmin.py households` |
| Add phones to a household | `nwadmin.py invite --household <id>` |
| Withdraw an invitation | `nwadmin.py invitations`, then `nwadmin.py invite-revoke <id>` |
| Suspend or reinstate a household | `nwadmin.py suspend <id>` / `nwadmin.py reinstate <id>` |
| Remove a household and its data | `nwadmin.py remove <id>` |
| Remove one lost phone | `nwadmin.py device-revoke <device id>` (or another phone of that home does it in Settings) |
| Check overall status | `nwadmin.py summary` |
| Review incidents | `nwadmin.py incidents` |

These are recorded in the audit log (`nwadmin.py audit`). The log shows what was done, not who did
it: everyone with the operator secret looks the same.

## Support answers

- **"My invitation says it cannot be used."** It expired, has already enrolled all its phones, or
  was withdrawn. Issue a new one (`invite --household <id>` if the home already takes part).
- **"It says this address is already taking part."** A phone enrolled that house number and street
  with a different link. If it is the same home, send `invite --household <id>`. If nobody in that
  home did it, someone else claimed the address: remove that household and invite the home again.
- **"I have a new phone" or "I reinstalled the app."** The old enrolment cannot be recovered. Send
  `invite --household <id> --phones 1`, and remove the old phone.
- **"I lost my phone."** Another phone of the home removes it in Settings, or you run
  `device-revoke`. Until then the lost phone can still read the household's status and report.
- **"The app is grey."** Monitoring is off, or the phone has not reported recently. On iPhone this
  is normal when the app has not been opened for a while. Grey means unknown, not safe.
