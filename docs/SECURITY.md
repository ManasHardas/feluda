# Security architecture

Baseline: OWASP MASVS for the apps (`docs/MASVS_CHECKLIST.md`), and the controls below for the
backend. To report a vulnerability see `SECURITY.md` in the repository root. This document
describes controls that exist in the code and the tests that check them. It makes no claim that the
system is free of vulnerabilities, and no independent audit has been performed
(`docs/SECURITY_REVIEW.md`).

## Identity and access

| Control | Where | Checked by |
| --- | --- | --- |
| Nobody signs in (decision 0014). A phone is enrolled once with an invitation link and receives a random 256-bit device secret, which it sends as a bearer token. The server stores only the SHA-256 hash (`nw.device_credentials`) | `services/membership.py`, `authentication/dependencies.py` | `test_full_enrolment_journey_asks_only_for_the_street`, `test_invitation_use_limit_and_storage_as_a_hash` |
| Every non-public route requires a credential; an unknown, removed or revoked phone gets the same 401 | `api/*.py`, `get_member_any` | `test_every_protected_endpoint_requires_a_token`, `test_every_route_except_the_public_ones_declares_authentication`, `test_without_an_invitation_there_is_no_access` |
| Household and neighborhood always come from the enrolment record of the calling phone, never from the request; unknown fields are rejected | `schemas.py` (`extra="forbid"`) | `test_client_supplied_household_or_neighborhood_ids_are_refused` |
| A phone can be removed by itself or by another phone of its own household, never by another household | `services/devices.py` | `test_a_phone_can_be_removed_by_its_household_only` |
| Administrator endpoints need the operator secret `ADMIN_API_TOKEN` (compared in constant time; at least 32 characters in staging and production). An empty value switches the administrator API off. A device secret is not accepted there, and the operator secret is not accepted on resident routes | `get_operator`, `settings.py` | `test_residents_cannot_use_admin_endpoints`, `test_device_secrets_and_the_operator_secret_are_not_interchangeable`, `test_administration_is_disabled_without_an_operator_secret`, `test_production_settings_refuse_unsafe_configuration` |
| Each administrator call acts on one neighborhood: the only one, or the one named in `X-Neighborhood-Id`. Objects of another neighborhood answer 404 | `get_operator`, `services/admin.py` | `test_admin_of_one_neighborhood_cannot_touch_another` |

### What this design gives up

Stated plainly, because these are weaker than the e-mail sign-in they replaced (accepted by the
owner in decision 0014):

- **No second factor and no expiry for phones.** Whoever holds a phone's device secret is that
  phone until it is removed. The secret is meant to stay in the platform keystore and can be
  revoked by the household or the organizer, but it is not rotated and nothing notices its theft.
- **One shared operator secret.** It administers every neighborhood on the deployment. There is no
  per-person administrator identity, so the audit log records what was done but not who did it,
  and there is no multi-factor step. The neighborhood header is a selector, not a permission
  boundary. Acceptable for one organizer; revisit before there are several.
- **The invitation link is the whole entry gate.** There is no approved-address list to match
  against and no verified mailbox. Anyone who obtains a live link can enrol phones with it (see
  "Invitations" and `docs/THREAT_MODEL.md`).
- **A lost or reinstalled app means asking for a new link.** There is no recovery path.

What became smaller: no e-mail addresses, names or passwords are stored anywhere, there is no
identity provider or mail sender to configure or to be abused, and there are no token-verification
keys to manage.

## Database

Three restricted roles and row-level security on every table (decision 0005). The login role has
no privileges of its own.

| Rule from the brief | Enforced by | Checked by |
| --- | --- | --- |
| A phone reads its own enrolment record and household | policies `own_user`, `own_household_read` | `test_rls_resident_sees_only_own_rows` |
| A phone submits events only for itself, while active | policy `own_events_insert` | `test_rls_resident_cannot_write_outside_own_household` |
| Phones cannot change membership | no grant, no policy | same test |
| Phones cannot read other households' telemetry | policy `own_events_read` | `test_rls_resident_sees_only_own_rows` |
| Only the operator invites, suspends, removes | Operator secret; `nw_api` has no access to `invitations` or `device_credentials` | `test_residents_cannot_use_admin_endpoints` |
| Correlation reads only what it needs | column grants for `nw_correlation` | `test_correlation_role_cannot_read_personal_data` |
| Nothing is reachable through Supabase's REST API | schema `nw` not exposed; RLS on every table; nothing granted to PUBLIC | `test_every_table_has_row_level_security_enabled` |

Row-level security works as before the change: each enrolled phone has its own row in `nw.users`
and every resident request runs under that identity. The operator secret, database passwords and
push credentials exist only in the backend's environment. The apps contain the API address and
public links, and nothing secret until a phone is enrolled; then the phone holds its own device
secret.

## Invitations

256-bit random tokens from the operating system's generator; only the SHA-256 hash is stored; a
link enrols a limited number of phones (`max_uses`, default 4, at most 20); configurable expiry
(default 72 hours, maximum 30 days); revocable; bound to one neighborhood and optionally one
household; carried in the URL fragment (decision 0013). Unknown, used-up, expired and revoked
tokens all return the same 404. Verification is limited to 20 attempts and enrolment to 10 per
10 minutes per client address (held in memory, per process). Creation, use and revocation are
audited without the token; an enrolment refused because the address or the phone's id is already
taken is recorded as an `enrolment_refused` security event.

Households: with a house number the household is that address; without one, the phones enrolled
with the same link on the same street form one household. A different link cannot attach a phone to
a home that already has an enrolled phone (409), so a stranger's link cannot be used to read that
home's status and alerts.

Limits of this gate: there is no check that the street or house number typed is real or belongs to
the person. A link holder can claim an address nobody has enrolled yet, and by giving a different
house number for each phone one link can create up to `max_uses` separate households. The earlier
lock after five wrong addresses no longer exists, because there is no address list to be wrong
against. See `docs/THREAT_MODEL.md` for what follows from this.
Tests: `test_onboarding.py`, `test_invitation_guessing_is_rate_limited_and_indistinguishable`.

## Abuse and false reports

| Threat | Control |
| --- | --- |
| Flood of events from one device | 60 per minute, 600 per hour per device; 1,500 per hour per household; 60 requests per minute per device. Database-backed, so they hold across restarts. |
| Fabricated outages from one device | Onsets beyond 6 per device per hour are stored but excluded from correlation and logged as `suspicious_reporting` |
| One device raising a neighborhood alert | Impossible by construction: incidents count distinct households, minimum 3 (never configurable below 2) |
| A resident inventing an incident for others | A device can report only for its own household; residents cannot write incidents |
| Replay and duplicates | Event UUID is the primary key; re-submission is a no-op; another household cannot overwrite an id |
| Forged timestamps | More than 120 s in the future or older than 7 days is rejected |
| Malformed input | Strict typed schemas; unknown fields rejected; values are not echoed in errors |
| Stolen or lost phone | Another phone of the household, or the organizer, removes it: its device secret and push token are deleted and it gets 401 from then on. Until someone does that, the secret keeps working |

Accepted residual risk: three or more colluding households in one neighborhood can produce a false
level-2 alert, and so can one person who enrols three or more invented households (see
"Invitations"). Enrolments are audited and visible to the organizer, and reports are limited by
the hourly onset allowance; see `docs/THREAT_MODEL.md`.

## Transport, storage and keys

- HTTPS everywhere. Release builds of both apps refuse cleartext. HSTS is sent in staging and
  production. No certificate pinning (see the MASVS checklist for the reasoning).
- Encryption at rest is the database provider's (Supabase: AES-256 on disk). Backups made with
  `infrastructure/scripts/backup.sh` must be stored encrypted by whoever runs it.
- The device secret on the phone: Android Keystore-wrapped AES-GCM; iOS Keychain, this-device-only
  (the apps are being updated for this; not verified on a device). On the server: SHA-256 hash only.
- No custom cryptography. Libraries: Python `secrets` and `hashlib`, PyJWT with `cryptography`
  (only to sign the provider token APNs requires), CryptoKit, Android Keystore.

### Key rotation

| Secret | How to rotate |
| --- | --- |
| Database password (`nw_app_login`) | `ALTER ROLE ... PASSWORD`, update `DATABASE_URL` secret, restart |
| Operator secret (`ADMIN_API_TOKEN`) | Generate a new one (`openssl rand -hex 32`), update the secret, restart, update `NWADMIN_TOKEN` wherever the admin tool is used. The old value stops working at the restart |
| A phone's device secret | Not rotated. Remove the phone (`nwadmin.py device-revoke`, or from another phone of the household) and enrol it again with a new link |
| FCM service account | Create a new key in Google Cloud, update `FCM_SERVICE_ACCOUNT_JSON`, delete the old key |
| APNs key | Create a new key in the Apple Developer account, update `APNS_KEY_ID` and `APNS_PRIVATE_KEY`, revoke the old key |
| Android signing key | Cannot be rotated casually: testers would need to reinstall. Keep an offline backup. See `docs/APP_DISTRIBUTION.md` |
| `IP_HASH_SALT`, `INTERNAL_TICK_TOKEN` | Replace and restart |

Full procedure and breach handling: `docs/OPERATIONS.md`.

## Logging and audit

Structured logs carry a request id, the route template, status and duration. They never contain
addresses, invitation tokens, device secrets, the operator secret or client IPs
(`test_logs_never_contain_secrets_or_addresses`).
Administrative actions go to `admin_audit_logs`; abuse signals to `security_events`; both are kept
90 days. Audit entries for administrator actions name the action and its target, not a person:
there is only one operator secret. Client addresses used for rate limiting are salted hashes held in memory only.

## Web pages

`/join` and `/privacy` send a Content-Security-Policy that allows only the page's own nonce-tagged
script and style, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, and no third-party
resources. API documentation pages are disabled in staging and production.

## Supply chain

Pinned dependency versions with lockfiles; pinned toolchains; weekly dependency audit, secret
scanning over the full history, dependency review on pull requests and CodeQL
(`.github/workflows/security.yml`); SBOMs, checksums and build provenance on release
(`docs/RELEASE_VERIFICATION.md`). A test scans the repository for committed keys and tokens.
