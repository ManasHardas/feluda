# Supabase setup (managed PostgreSQL and sign-in)

Supabase is used for two things only: a managed PostgreSQL database and e-mail one-time-code
sign-in (Supabase Auth). The backend uses plain PostgreSQL features, so any PostgreSQL 13+ and any
identity provider that issues standard JWTs can replace it (see `ai/docs/decisions/`).

Status: EXTERNAL_CREDENTIAL_REQUIRED. Nothing below has been done; it needs a Supabase account.

## One project per environment

Create two projects, `watchdog-staging` and `watchdog-production`. Never share keys between them.
The free plan allows two active projects and pauses a project after about a week without activity;
check the current limits at https://supabase.com/pricing before relying on it for production.

## Database

1. Project settings → Database → copy the **direct connection** string (the `postgres` owner role).
   This is `MIGRATION_DATABASE_URL`. It is used only to run migrations.
2. Apply migrations from your machine or the deploy workflow:
   `DATABASE_URL="$MIGRATION_DATABASE_URL" uv run python -m watchdog.migrate` (in `backend/`).
3. Create the restricted login role for the backend:
   `psql "$MIGRATION_DATABASE_URL" -v app_password="$(openssl rand -hex 24)" -f infrastructure/scripts/create_app_login.sql`
4. The backend's `DATABASE_URL` uses role `nw_app_login` with that password, through the
   **session pooler** or **transaction pooler** host shown in the dashboard (both work: the backend
   does not use server-side prepared statements).
5. Do not add the `nw` schema to "Exposed schemas" (API settings). The tables must stay unreachable
   through Supabase's REST API. Row-level security is enabled on every table with no policy for the
   `anon` and `authenticated` roles, so they would see nothing even if the schema were exposed.

## Sign-in (Supabase Auth)

1. Authentication → Providers → Email: enable. Turn **Confirm email** on.
2. Authentication → Email templates → Magic Link: change the body so it shows the code:
   `Your Neighborhood Watchdog sign-in code is {{ .Token }}`. The apps ask for this 6-digit code.
3. Authentication → Sessions: access token lifetime 3600 seconds or less; refresh-token rotation on
   (the default), reuse interval 10 seconds.
4. Authentication → Rate limits: keep the defaults or lower them. Configure your own SMTP sender
   (the built-in one allows only a few e-mails per hour and is meant for testing).
5. For administrators in production: Authentication → Multi-factor → enable TOTP, and set
   `ADMIN_REQUIRE_MFA=true` on the backend. (Enrolling a second factor needs a small web or app
   flow that is not built yet; see `jtbd/todo.md`.)

## Values the backend needs

| Backend variable | Where it comes from |
| --- | --- |
| `AUTH_JWKS_URL` | `https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json` (projects with asymmetric signing keys) |
| `AUTH_JWT_SECRET` | Project settings → API → JWT secret (only for projects still on the legacy shared secret) |
| `AUTH_JWT_ISSUER` | `https://<project-ref>.supabase.co/auth/v1` |
| `AUTH_ADMIN_URL`, `AUTH_SERVICE_ROLE_KEY` | Optional; lets account deletion remove the sign-in record too. Server-side secret. |

## Values the apps need (public by design)

| App setting | Value |
| --- | --- |
| `AUTH_BASE_URL` | `https://<project-ref>.supabase.co/auth/v1` |
| `AUTH_PUBLIC_KEY` | The **publishable / anon** key. Never the service-role or secret key. |
