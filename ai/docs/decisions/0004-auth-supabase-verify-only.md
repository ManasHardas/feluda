# 0004 Sign-in through Supabase Auth; the backend only verifies tokens

**Superseded on 2026-11-23 by 0014 (no sign-in). Kept for history; none of this is in the code any more.**

**Decision.** The apps call Supabase Auth's REST endpoints directly for e-mail one-time codes,
access tokens and rotating refresh tokens. The backend verifies the access token (JWKS, or the
legacy shared secret) and maps its subject to an account. Accounts are created only by redeeming an
invitation, keyed by e-mail address; the first sign-in with that verified address binds the
identity to the account.

**Why.** Sign-in, code delivery, token rotation and abuse limits are solved problems that should
not be re-implemented. Verify-only keeps the backend portable: any issuer of standard JWTs works.
The Supabase service-role key is never needed by the apps and is optional for the backend.

**Local development.** `DEV_AUTH_ENABLED=true` mounts an in-memory emulator of the three endpoints
at `/auth/v1` and prints codes to the log. The settings loader refuses to start with it in staging
or production, and a test checks that.

**Risk accepted.** Anyone can create a Supabase identity for any address they control; without an
invitation it maps to no account and every endpoint answers `not_a_member`. An identity that
presents an e-mail claim equal to an already-bound account does not get that account (binding
happens once, by subject).

**Open.** Administrator multi-factor enrolment needs a UI that is not built. `ADMIN_REQUIRE_MFA`
is enforced by the backend when set.
