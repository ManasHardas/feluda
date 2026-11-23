# Security architecture

Baseline: OWASP MASVS for the apps (`docs/MASVS_CHECKLIST.md`), and the controls below for the
backend. To report a vulnerability see `SECURITY.md` in the repository root. This document
describes controls that exist in the code and the tests that check them. It makes no claim that the
system is free of vulnerabilities, and no independent audit has been performed
(`docs/SECURITY_REVIEW.md`).

## Identity and access

| Control | Where | Checked by |
| --- | --- | --- |
| Sign-in by e-mail one-time code at the identity provider; short-lived access tokens; rotating refresh tokens | Supabase Auth; apps | `test_refresh_tokens_rotate` (local emulator); provider settings in `infrastructure/supabase/README.md` |
| Access token verified on every protected request: signature, expiry, audience, issuer when configured; `alg=none` and unexpected algorithms refused | `authentication/tokens.py` | `test_tokens_with_wrong_signature_audience_or_algorithm_are_rejected`, `test_expired_access_token_is_rejected` |
| Every non-public route depends on token verification | `api/*.py` | `test_every_route_except_the_public_ones_declares_authentication` |
| Accounts exist only through invitations; an unknown identity gets `not_a_member` everywhere | `authentication/dependencies.py` | `test_signing_in_without_an_invitation_gives_nothing` |
| Household and neighborhood always come from the server-side membership, never from the request; unknown fields are rejected | `schemas.py` (`extra="forbid"`) | `test_client_supplied_household_or_neighborhood_ids_are_refused` |
| Administrator endpoints need the admin role, optionally multi-factor sign-in (`ADMIN_REQUIRE_MFA`) | `get_admin` | `test_residents_cannot_use_admin_endpoints`, `test_admin_endpoints_can_require_multi_factor_sign_in` |
| Administrators act only inside their own neighborhood | `services/admin.py` | `test_admin_of_one_neighborhood_cannot_touch_another` |

## Database

Three restricted roles and row-level security on every table (decision 0005). The login role has
no privileges of its own.

| Rule from the brief | Enforced by | Checked by |
| --- | --- | --- |
| Users read their own account and household | policies `own_user`, `own_household_read` | `test_rls_resident_sees_only_own_rows` |
| Users submit events only for their own active devices | policy `own_events_insert` | `test_rls_resident_cannot_write_outside_own_household` |
| Users cannot change membership | no grant, no policy | same test |
| Users cannot read other households' telemetry | policy `own_events_read` | `test_rls_resident_sees_only_own_rows` |
| Only administrators invite, suspend, remove | API role check; `nw_api` has no access to `invitations` | `test_residents_cannot_use_admin_endpoints` |
| Correlation reads only what it needs | column grants for `nw_correlation` | `test_correlation_role_cannot_read_personal_data` |
| Nothing is reachable through Supabase's REST API | schema `nw` not exposed; RLS on every table; nothing granted to PUBLIC | `test_every_table_has_row_level_security_enabled` |

Service-role keys and database passwords exist only in the backend's environment. The apps contain
the identity provider's public key and URLs and nothing else.

## Invitations

256-bit random tokens from the operating system's generator; only the SHA-256 hash is stored;
single use; configurable expiry (default 72 hours, maximum 30 days); revocable; bound to one
neighborhood and optionally one household; carried in the URL fragment (decision 0013). Unknown,
used, expired and revoked tokens all return the same 404. Verification is limited to 20 attempts
per 10 minutes per client, registration to 10, and five wrong addresses per hour lock an
invitation. Creation, use and revocation are audited without the token.
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
| Stolen or lost phone | Resident or administrator revokes the device; its tokens and sessions are removed |

Accepted residual risk: three or more colluding households in one neighborhood can produce a false
level-2 alert. It is audited, visible to the administrator, and limited by the hourly onset
allowance; see `docs/THREAT_MODEL.md`.

## Transport, storage and keys

- HTTPS everywhere. Release builds of both apps refuse cleartext. HSTS is sent in staging and
  production. No certificate pinning (see the MASVS checklist for the reasoning).
- Encryption at rest is the database provider's (Supabase: AES-256 on disk). Backups made with
  `infrastructure/scripts/backup.sh` must be stored encrypted by whoever runs it.
- App credentials: Android Keystore-wrapped AES-GCM; iOS Keychain, this-device-only.
- No custom cryptography. Libraries: PyJWT with `cryptography`, Python `secrets` and `hashlib`,
  CryptoKit, Android Keystore.

### Key rotation

| Secret | How to rotate |
| --- | --- |
| Database password (`nw_app_login`) | `ALTER ROLE ... PASSWORD`, update `DATABASE_URL` secret, restart |
| Identity provider signing key | Rotate in Supabase; with JWKS the backend picks up the new key within an hour (cache lifetime). With the legacy shared secret, update `AUTH_JWT_SECRET` and restart; residents sign in again |
| FCM service account | Create a new key in Google Cloud, update `FCM_SERVICE_ACCOUNT_JSON`, delete the old key |
| APNs key | Create a new key in the Apple Developer account, update `APNS_KEY_ID` and `APNS_PRIVATE_KEY`, revoke the old key |
| Android signing key | Cannot be rotated casually: testers would need to reinstall. Keep an offline backup. See `docs/APP_DISTRIBUTION.md` |
| `IP_HASH_SALT`, `INTERNAL_TICK_TOKEN` | Replace and restart |

Full procedure and breach handling: `docs/OPERATIONS.md`.

## Logging and audit

Structured logs carry a request id, the route template, status and duration. They never contain
addresses, e-mail addresses, tokens or client IPs (`test_logs_never_contain_tokens_addresses_or_emails`).
Administrative actions go to `admin_audit_logs`; abuse signals to `security_events`; both are kept
90 days. Client addresses used for rate limiting are salted hashes held in memory only.

## Web pages

`/join` and `/privacy` send a Content-Security-Policy that allows only the page's own nonce-tagged
script and style, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, and no third-party
resources. API documentation pages are disabled in staging and production.

## Supply chain

Pinned dependency versions with lockfiles; pinned toolchains; weekly dependency audit, secret
scanning over the full history, dependency review on pull requests and CodeQL
(`.github/workflows/security.yml`); SBOMs, checksums and build provenance on release
(`docs/RELEASE_VERIFICATION.md`). A test scans the repository for committed keys and tokens.
