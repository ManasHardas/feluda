# 0012 Fly.io as the documented host; one small always-on machine

**Decision.** The backend container runs on one always-on `shared-cpu-1x` 256 MB machine per
environment on Fly.io (`infrastructure/deployment/fly.*.toml`), with Supabase for the database and
sign-in.

**Why.** Alerts are time-critical, so a host that puts the process to sleep (and takes tens of
seconds to wake) is a poor fit, and the in-process worker needs a live process for escalation and
retries. A tiny always-on machine costs a few dollars a month; free tiers that sleep are cheaper
but worse at the one thing the product is for. The image is a plain container, so any container
host works.

**Zero-cost alternative (documented, not configured).** A scale-to-zero host plus a scheduler that
calls `POST /internal/tick` every minute with `INTERNAL_TICK_TOKEN`, with
`BACKGROUND_WORKER_ENABLED=false`. Uploads still trigger correlation immediately; the first upload
after idle pays the cold start.

**Not done.** No account exists and nothing is deployed. Prices and free-tier limits change;
check them before committing (`docs/DEPLOYMENT.md` has the estimate and its assumptions).

**Owner confirmation (2026-11-22).** The owner accepted paying a few dollars a month for an always-on
machine. Fly.io stays the recommended host; the account does not exist yet.
