# 0001 Monorepo with a modular-monolith backend

**Decision.** One repository: `apps/android`, `apps/ios`, `apps/admin`, `backend`, `shared`,
`infrastructure`, `docs`. The backend is one deployable Python package (`backend/watchdog`) with
sub-packages `api`, `services`, `correlation`, `notifications`, `authentication`.

**Why.** A pilot of tens of households has no load that justifies separate services, and one
process is far easier to reason about for security review. The three parts stay independently
buildable: each has its own build tool, CI workflow and README, and they share only the API
contract in `shared/`.

**Deviation from the suggested layout.** The brief lists `backend/api`, `backend/services`, and so
on directly under `backend/`. Python needs one importable top-level package, so they live under
`backend/watchdog/`. `backend/migrations`, `backend/tests` and `backend/Dockerfile` are where the
brief puts them.

**Rejected.** Separate correlation and notification services (more moving parts, no benefit at
this size). Supabase Edge Functions for business logic (ties core logic to one vendor and makes it
hard to test offline).

**Revisit when.** Correlation runs take longer than a few seconds, or more than one team works on
the backend.
