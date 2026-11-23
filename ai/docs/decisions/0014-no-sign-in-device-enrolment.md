# 0014 No sign-in: a phone is enrolled by an invitation link

**Decision (owner, 2026-11-23).** Nobody signs in. There are no accounts, e-mail addresses,
passwords or one-time codes, and the service never sends anything except push notifications.
This replaces decision 0004.

- **Joining.** The organizer creates an invitation link (`/join#t=<token>`) and sends it privately
  to one home. Opening it in the app enrols that phone. The resident types the street (mandatory)
  and, if they wish, a house number (optional). Nothing else is asked.
- **Entry gate.** The owner chose a private link per home over one shared neighborhood code or
  open entry. A link enrols a limited number of phones (4 by default), expires, and can be revoked.
- **Identity of a phone.** Enrolment returns a random 256-bit secret, stored on the phone in the
  platform keystore and on the server only as a SHA-256 hash (`nw.device_credentials`). The phone
  sends it as a bearer token. It does not expire; it ends when the phone leaves, is removed by its
  household or the organizer, or the app is reinstalled.
- **Households.** One link is one household. The first phone that uses a link creates it from the
  street and optional house number typed, and the link is then bound to it; later phones with that
  link join it whatever they type, so a link can never fake several homes. A different link cannot
  attach a phone to a numbered address that is already taking part; the organizer issues a link for
  that household. When the last phone leaves, a household residents created is deleted.
- **Administration.** One operator secret (`ADMIN_API_TOKEN`) for `/api/v1/admin`, used by
  `apps/admin/nwadmin.py`. Residents have no administrator role.
- **Leaving.** Deletes the phone, its secret and everything it reported. There is no account left
  to delete.

**Why.** The owner wants zero friction and no personal data: a neighbor should only install, tap a
link and name their street. Removing e-mail also removes the need for an identity provider, an
SMTP sender and their abuse surface. Row-level security is unchanged: each enrolled phone has its
own row in `nw.users` and every request still runs under that identity.

**Costs accepted.**
- A stolen phone secret works until the phone is removed; there is no second factor. The secret
  never leaves the keystore and is revocable.
- Losing or reinstalling the app means asking for a new link.
- The operator secret is a single shared credential with no per-person audit trail or
  multi-factor step. Acceptable for one organizer; revisit before there are several.
- Without coordinates (nobody is asked for a location), correlation works on the neighborhood as a
  whole and the distance check applies only to households the organizer added with coordinates.
- Supabase is now used only as a PostgreSQL host; Supabase Auth is unused.
