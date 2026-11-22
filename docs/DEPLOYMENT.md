# Deployment

**Status: nothing is deployed.** No Supabase project, hosting account, Firebase project or Apple
account was available to the build. Everything below is prepared configuration and exact steps,
marked EXTERNAL_CREDENTIAL_REQUIRED in `docs/STATUS.md`.

## Environments

| | Development | Staging | Production |
| --- | --- | --- | --- |
| Database | Docker PostgreSQL on localhost | Supabase project `watchdog-staging` | Supabase project `watchdog-production` |
| Sign-in | Local emulator (`DEV_AUTH_ENABLED`) | Staging Supabase Auth | Production Supabase Auth |
| Backend | `make run` | Fly.io app `neighborhood-watchdog-staging` | Fly.io app `neighborhood-watchdog-production` |
| Push | Not configured (deliveries recorded as such) | FCM project and APNs sandbox | FCM and APNs production |
| Data | Simulated seed | Simulated seed or volunteers | Real residents |

Credentials are never shared between environments. The backend refuses to start in staging or
production with the sign-in emulator on, without token verification settings, with a short JWT
secret, with the default IP salt, or with a non-HTTPS public URL.

## Local development

```bash
make setup          # backend dependencies (needs uv)
make docker-up      # PostgreSQL on localhost:54329
make migrate        # apply migrations
make seed           # simulated neighborhood with 24 households
make run            # API on http://localhost:8000 (docs at /docs)
make simulate SCENARIO=C
make backend-test   # all backend tests
```

Sign in locally as `resident01@example.org` (administrator) to `resident24@example.org`; the
one-time code is printed in the backend log.

## First deployment of an environment

1. **Supabase**: follow `infrastructure/supabase/README.md` (project, migrations, login role,
   sign-in settings).
2. **Fly.io**: install `flyctl`, `fly auth login`, then
   `fly apps create neighborhood-watchdog-staging` (choose your own unique name and put it, and the
   resulting URL, into `infrastructure/deployment/fly.staging.toml`).
3. **Secrets** (never in files):

   ```bash
   fly secrets set --app neighborhood-watchdog-staging \
     DATABASE_URL='postgresql://nw_app_login:<password>@<pooler-host>:5432/postgres?sslmode=require' \
     AUTH_JWKS_URL='https://<ref>.supabase.co/auth/v1/.well-known/jwks.json' \
     AUTH_JWT_ISSUER='https://<ref>.supabase.co/auth/v1' \
     IP_HASH_SALT="$(openssl rand -hex 24)" \
     ANDROID_INSTALL_URL='<Firebase App Distribution invite link>' \
     IOS_INSTALL_URL='<TestFlight link>' \
     SOURCE_REPO_URL='https://github.com/<owner>/<repo>' \
     SECURITY_CONTACT='<address>' \
     FCM_PROJECT_ID='<id>' FCM_SERVICE_ACCOUNT_JSON="$(cat service-account.json)" \
     APNS_KEY_ID='<id>' APNS_TEAM_ID='<id>' APNS_BUNDLE_ID='<bundle id>' APNS_PRIVATE_KEY="$(cat AuthKey_XXXX.p8)"
   ```

4. **Deploy**: `MIGRATION_DATABASE_URL='<owner url>' infrastructure/scripts/deploy.sh staging`
   (migrations, then the image, then a smoke test). Or run the "Deploy staging" workflow after
   adding `FLY_API_TOKEN` and `MIGRATION_DATABASE_URL` as secrets of a GitHub environment named
   `staging`, and `PUBLIC_BASE_URL` as a variable.
5. **Create the neighborhood and first administrator** (run with the owner connection):

   ```bash
   cd backend && DATABASE_URL='<owner url>' uv run python -m watchdog.ops create-neighborhood \
     --name "Oak Ridge" --admin-email organizer@example.org --house-number 12 --street "Oak Ridge Road" \
     --latitude 33.05 --longitude -97.05
   ```

6. Follow `docs/ONBOARDING.md` to approve addresses and invite residents.

Production is the same with `production` in place of `staging`. The deploy script asks for typed
confirmation, and the GitHub environment `production` should require a reviewer. Nothing deploys
automatically on merge.

## Environment variables

The full list with comments is `.env.example`.

| Variable | Required in staging/production | Secret |
| --- | --- | --- |
| `ENVIRONMENT`, `PUBLIC_BASE_URL` | yes | no |
| `DATABASE_URL` | yes | yes |
| `AUTH_JWKS_URL` or `AUTH_JWT_SECRET`; `AUTH_JWT_ISSUER`; `AUTH_JWT_AUDIENCE` | yes | the secret variant is |
| `IP_HASH_SALT` | yes | yes |
| `ADMIN_REQUIRE_MFA` | recommended `true` in production | no |
| `AUTH_ADMIN_URL`, `AUTH_SERVICE_ROLE_KEY` | optional | the key is |
| `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_JSON` | for Android push | the JSON is |
| `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_BUNDLE_ID`, `APNS_PRIVATE_KEY` | for iOS push | the key is |
| `ANDROID_INSTALL_URL`, `IOS_INSTALL_URL`, `SOURCE_REPO_URL`, `SUPPORT_URL`, `SECURITY_CONTACT` | shown to residents | no |
| `BACKGROUND_WORKER_ENABLED`, `TICK_INTERVAL_SECONDS`, `INTERNAL_TICK_TOKEN` | optional | the token is |
| `TRUST_PROXY_HEADERS` | `true` behind Fly's proxy | no |
| `SENTRY_DSN`, `LOG_LEVEL`, `CORS_ALLOWED_ORIGINS`, `DATABASE_POOL_MAX` | optional | DSN is low-sensitivity |

## Migrations

- `backend/migrations/NNNN_name.sql`, applied in order by `python -m watchdog.migrate`, each in a
  transaction, recorded with a checksum. A changed file that was already applied is refused, and
  CI rejects pull requests that edit a migration already on `main`.
- Never change a production schema by hand.
- Write migrations so the previous backend version still works with the new schema (add first,
  remove in a later release). That is what makes rollback by image possible.

## Rollback

| Problem | Action |
| --- | --- |
| Bad backend release | `fly releases --app <app>` then `fly deploy --image <previous image>`; the schema is still compatible by the rule above |
| Bad migration | Write and apply a new forward migration that undoes it. If data was damaged, restore from backup (below) |
| Bad app build | Stop distributing it (Firebase App Distribution / TestFlight) and publish the previous build with a higher build number |

## Backups and restore

- Supabase free plan: no automatic backups. Paid plans: daily backups per Supabase.
- Own backups: `OWNER_DATABASE_URL=... infrastructure/scripts/backup.sh /secure/location` writes a
  compressed dump of schema `nw` and prints its SHA-256. Store it encrypted; keep about 7.
- Restore into an empty database: `pg_restore --clean --if-exists --no-owner -d <target url> <file>`,
  then `python -m watchdog.migrate --check`, then run `create_app_login.sql` again.
- Test a restore into the staging project before relying on it. This has not been done yet.

## Health and monitoring

See `infrastructure/monitoring/README.md`. Fly checks `/api/v1/monitoring/health` every 30 seconds;
the "Uptime check" workflow probes it from outside every 15 minutes once `PUBLIC_BASE_URL` is set.

## Cost estimate

Assumptions: 2 phones per household; an Android phone stores about 96 heartbeats a day kept 3 days
and about 10 other events a day kept 30 days; about 1 KB per stored event including indexes. Prices
are list prices as the author knew them and **must be checked** on the providers' pricing pages
before deciding; free tiers change and are not guaranteed.

| Households | Database size | Backend | Database plan | Push | Monthly total |
| --- | --- | --- | --- | --- | --- |
| 10 | about 12 MB | 1 tiny always-on machine, about $2 to $4 | Supabase free | free | about $2 to $4 |
| 50 | about 60 MB | same | Supabase free | free | about $2 to $4 |
| 100 | about 120 MB | same | Supabase free | free | about $2 to $4 |
| 500 | about 600 MB | 512 MB machine, about $4 to $6 | Supabase Pro, about $25 (the free 500 MB limit is exceeded) | free | about $30 |

Add per environment: staging doubles the backend cost. Fixed costs outside the table: Apple
Developer Program, $99 per year, required for TestFlight; a domain name if wanted, about $12 per
year; Firebase App Distribution, FCM and APNs have no charge.

Where free tiers stop being enough:

- **Supabase free database (500 MB)**: reached at roughly 400 households with the default
  retention. Lowering `retention_heartbeat_days` to 1 pushes that to roughly 800.
- **Supabase free plan has no backups** and pauses projects after about a week without activity
  (an always-on backend keeps it active). For real residents' data, paid backups or the backup
  script on a schedule are needed from day one.
- **Sign-in e-mail**: Supabase's built-in sender allows only a few messages per hour. Onboarding
  more than a handful of residents a day needs your own SMTP provider (several have free tiers of
  about 100 messages a day).
- **GitHub Actions**: free for public repositories; macOS minutes are charged at a multiple on
  private ones.
- **A zero-cost backend** is possible on a scale-to-zero host with the external tick (decision
  0012) at the price of cold starts on the first report after idle.
