# Monitoring

What exists (IMPLEMENTED in code; needs a deployed environment to be useful):

| Signal | Where | How to watch it |
| --- | --- | --- |
| Liveness | `GET /health` | Host health check (Fly.io check in `infrastructure/deployment/fly.*.toml`) |
| API and database health | `GET /api/v1/monitoring/health` (503 when the database is unreachable) | `.github/workflows/uptime.yml` probes it every 15 minutes once the `PUBLIC_BASE_URL` repository variable is set |
| Structured logs | JSON on stdout, one line per request with `request_id`, route template, status, duration | Host log viewer (`fly logs`), or ship to any log service |
| Counters | `GET /api/v1/monitoring/metrics` (operator secret): events accepted, duplicates, rate-limited, onsets excluded, correlation runs, incidents created/escalated/resolved/expired, notifications by status, tick errors, HTTP status classes | Poll from a dashboard, or read ad hoc |
| Notification failures | `GET /api/v1/admin/notifications/failures`, and `notification_failures_last_7_days` in the admin summary | Review weekly during the pilot |
| Abuse signals | Table `nw.security_events` (kinds: `event_rate_limited`, `suspicious_reporting`, `enrolment_refused`) | SQL in `docs/OPERATIONS.md` |
| Errors | Optional Sentry: install the `errors` extra and set `SENTRY_DSN`. Request bodies and personal data are not sent. | Sentry project alerts |
| App crashes | Android: Play/Firebase vitals are not enabled by default. iOS: TestFlight crash reports arrive in Xcode Organizer without extra code. | See `docs/OPERATIONS.md` |

Suggested alerts for a pilot (set up in whatever tool you use; none are configured yet):

1. Health check failing for 5 minutes.
2. `tick_errors` increasing.
3. More than 10 % of notification deliveries `failed` or `skipped_not_configured` in a day.
4. A burst of `enrolment_refused` security events (someone trying a link against addresses that
   already take part).

Logs never contain addresses, invitation tokens, device secrets, the operator secret or client IP
addresses (enforced by a test:
`backend/tests/test_security.py::test_logs_never_contain_secrets_or_addresses`).
