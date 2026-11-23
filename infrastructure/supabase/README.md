# Supabase setup (managed PostgreSQL only)

Supabase is used for one thing: a managed PostgreSQL database. There is no sign-in (decision 0014),
so Supabase Auth, its e-mail templates, its keys and an SMTP sender are not used and need no
configuration. The backend uses plain PostgreSQL features, so any PostgreSQL 13+ can replace it
(see `ai/docs/decisions/`).

Status: EXTERNAL_CREDENTIAL_REQUIRED for anything not listed here. A staging project exists and
migration `0001_initial_schema.sql` is applied to it. Migration `0002_no_sign_in.sql` is **not**
applied there yet. No production project exists. No backend is deployed against either.

## One project per environment

Create two projects, `watchdog-staging` and `watchdog-production`. Never share keys between them.
The free plan allows two active projects and pauses a project after about a week without activity;
check the current limits at https://supabase.com/pricing before relying on it for production.

## Database

1. Project settings → Database → copy the **direct connection** string (the `postgres` owner role).
   This is `MIGRATION_DATABASE_URL`. It is used only to run migrations.
2. Apply migrations from your machine or the deploy workflow:
   `DATABASE_URL="$MIGRATION_DATABASE_URL" uv run python -m watchdog.migrate` (in `backend/`).
   Migration 0002 removes the e-mail and identity columns and is not compatible with a backend
   from before it (`docs/DEPLOYMENT.md`, "Migrations").
3. Create the restricted login role for the backend:
   `psql "$MIGRATION_DATABASE_URL" -v app_password="$(openssl rand -hex 24)" -f infrastructure/scripts/create_app_login.sql`
4. The backend's `DATABASE_URL` uses role `nw_app_login` with that password, through the
   **session pooler** or **transaction pooler** host shown in the dashboard (both work: the backend
   does not use server-side prepared statements).
5. Do not add the `nw` schema to "Exposed schemas" (API settings). The tables must stay unreachable
   through Supabase's REST API. Row-level security is enabled on every table with no policy for the
   `anon` and `authenticated` roles, so they would see nothing even if the schema were exposed.

## Parts of Supabase that are not used

- **Authentication.** Nothing in the backend or the apps calls it. Leave it unconfigured, and if
  the dashboard offers it, switch off new sign-ups so the unused service accepts nobody.
- **The project's API keys** (publishable / anon, service-role, signing secret). None is needed by
  the backend or by the apps. Do not copy them anywhere.

## Values the backend needs

| Backend variable | Where it comes from |
| --- | --- |
| `DATABASE_URL` | Step 4 above. A secret |

Everything else the backend needs (`ADMIN_API_TOKEN`, `IP_HASH_SALT`, push credentials) does not
come from Supabase; see `docs/DEPLOYMENT.md`.

## Values the apps need

None from Supabase. The apps are configured with the backend's address only
(`shared/documentation/CLIENT_PROTOCOL.md`, section 1).
