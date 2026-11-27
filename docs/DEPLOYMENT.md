# Deployment

**Status.** Staging is deployed (Fly.io `feluda-staging`, Supabase project `feluda`, both migrations
applied; 2026-11-23). Production is deployed (2026-11-27): Fly.io `feluda-production` (one machine,
`iad`), Supabase project `feluda-production` (free plan, `us-east-1`, both migrations applied), smoke
test passed at https://feluda-production.fly.dev. `https://api.feluda.app` works once its DNS records
point at Fly (`jtbd/todo.md`). It runs `main` as of 2026-11-27 until the first release replaces it.

Production's database has its own migration login `nw_migrator` (owner of schema `nw`, created with a
locally computed password hash), so the Supabase `postgres` password is not needed anywhere. Its
pooler host is `aws-2-us-east-1.pooler.supabase.com` (staging: `aws-1`); the other hosts answer
"tenant not found". How changes reach each
environment: `docs/RELEASE_PROCESS.md`.

## Environments

| | Development | Staging | Production |
| --- | --- | --- | --- |
| Database | Docker PostgreSQL on localhost | Supabase project `feluda` | Supabase project `feluda-production` |
| Operator secret (`ADMIN_API_TOKEN`) | Fixed placeholder from the `Makefile` | Its own random value | Its own random value |
| Backend | `make run` | Fly.io app `feluda-staging` (https://feluda-staging.fly.dev, region `iad`), follows `main` | Fly.io app `feluda-production` at https://api.feluda.app (region `iad`), follows `release` |
| Push | Not configured (deliveries recorded as such) | FCM project and APNs sandbox | FCM and APNs production |
| Data | Simulated seed | Simulated seed or volunteers | Real residents |

There is no sign-in in any environment: phones are enrolled by invitation link (decision 0014).
Credentials are never shared between environments. The backend refuses to start in staging or
production with an `ADMIN_API_TOKEN` shorter than 32 characters, with the default IP salt, with a
short `INTERNAL_TICK_TOKEN`, or with a non-HTTPS public URL. An empty `ADMIN_API_TOKEN` is allowed
and switches the administrator API off, which also means no invitation can be created. The
development placeholder for `ADMIN_API_TOKEN` is published in this repository and is long enough
to pass that check: never use it outside your own machine.

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

There is nothing to sign in to. `make seed` creates 24 simulated households, each with one
simulated phone, and `make simulate` reports as those phones. To administer the local backend, or
to create an invitation link for an app running in an emulator or simulator:

```bash
export NWADMIN_API=http://localhost:8000
export NWADMIN_TOKEN=local-development-admin-token-not-for-production   # the Makefile's local placeholder
python apps/admin/nwadmin.py households
python apps/admin/nwadmin.py invite
```

## First deployment of an environment

1. **Supabase** (database only): follow `infrastructure/supabase/README.md` (project, migrations,
   login role).
2. **Fly.io**: install `flyctl`, `fly auth login`, then
   `fly apps create feluda-staging` (done for staging; for another name, put it and the resulting URL
   into `infrastructure/deployment/fly.staging.toml`). Use region `iad`, next to Supabase `us-east-1`.
3. **Secrets** (never in files). Through Supabase's pooler the user name is `nw_app_login.<project-ref>`;
   check which pooler host your project uses (staging: `aws-1-us-east-1`; `aws-0` answers "tenant not
   found"). On staging the login role was created with a password hash computed locally
   (`ALTER ROLE nw_app_login PASSWORD 'SCRAM-SHA-256$...'`), so the plain password exists only in
   Fly's secret store. Prefer `fly secrets import` from stdin over arguments, which land in shell history:

   ```bash
   fly secrets set --app feluda-staging \
     DATABASE_URL='postgresql://nw_app_login.<project-ref>:<password>@aws-1-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require' \
     ADMIN_API_TOKEN='<operator secret>' \
     IP_HASH_SALT="$(openssl rand -hex 24)" \
     ANDROID_INSTALL_URL='<Firebase App Distribution invite link>' \
     IOS_INSTALL_URL='<TestFlight link>' \
     SOURCE_REPO_URL='https://github.com/<owner>/<repo>' \
     SECURITY_CONTACT='<address>' \
     FCM_PROJECT_ID='<id>' FCM_SERVICE_ACCOUNT_JSON="$(cat service-account.json)" \
     APNS_KEY_ID='<id>' APNS_TEAM_ID='<id>' APNS_BUNDLE_ID='<bundle id>' APNS_PRIVATE_KEY="$(cat AuthKey_XXXX.p8)"
   ```

   Generate the operator secret with `openssl rand -hex 32` and keep it in a password manager: the
   organizer needs it for `apps/admin/nwadmin.py`, and it is the only protection of the
   administrator API.

4. **Deploy**: `MIGRATION_DATABASE_URL='<owner url>' infrastructure/scripts/deploy.sh staging`
   (migrations, then the image, then a smoke test). In normal operation GitHub Actions deploys:
   staging on every backend change merged into `main` ("Deploy staging"), production on every
   release ("Release"), with `FLY_API_TOKEN` and `MIGRATION_DATABASE_URL` as secrets of the GitHub
   environments `staging` and `production` (`docs/RELEASE_PROCESS.md`).
5. **Create the neighborhood** (run with the owner connection). It starts empty; there is no
   administrator account to create:

   ```bash
   cd backend && DATABASE_URL='<owner url>' uv run python -m watchdog.ops create-neighborhood --name "Oak Ridge"
   ```

6. Follow `docs/ONBOARDING.md` to create invitation links and send them to residents.

Production is the same with `production` in place of `staging`. The deploy script asks for typed
confirmation, and the GitHub environment `production` should require a reviewer. Nothing deploys
automatically on merge.

## Environment variables

The full list with comments is `.env.example`.

| Variable | Required in staging/production | Secret |
| --- | --- | --- |
| `ENVIRONMENT`, `PUBLIC_BASE_URL` | yes | no |
| `DATABASE_URL` | yes | yes |
| `ADMIN_API_TOKEN` | yes, at least 32 characters (empty disables the administrator API) | yes |
| `IP_HASH_SALT` | yes | yes |
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
- **Exception: `0002_no_sign_in.sql` breaks this rule.** It drops the e-mail and identity columns
  in the same release that stops using them, so a backend from before it does not work against a
  database that has it, and rollback by image across it is not possible. This was accepted because
  nothing was deployed. A phone or household created under the old flow has no device secret after
  0002 and has to be enrolled again.

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
- **GitHub Actions**: free for public repositories; macOS minutes are charged at a multiple on
  private ones.
- **A zero-cost backend** is possible on a scale-to-zero host with the external tick (decision
  0012) at the price of cold starts on the first report after idle.
