# Decisions

One file per decision: what was decided, why, what was rejected, and what would make us revisit it.
All of these were made by the build agent on 2026-11-21 under the brief's instruction to choose the
simplest reliable approach and document it. None has been reviewed by the owner yet.

| # | Decision |
| --- | --- |
| 0001 | Monorepo with a modular-monolith backend |
| 0002 | Plain SQL with psycopg and a small migration runner; no ORM |
| 0003 | Great-circle distance in the application instead of PostGIS |
| 0004 | Superseded by 0014. (Was: sign-in through Supabase Auth; the backend only verifies tokens) |
| 0005 | Three database roles and row-level security as a second line of defence |
| 0006 | Correlation recomputes a bounded window from stored events |
| 0007 | Deterministic, additive suspicion score; high priority needs persistence |
| 0008 | Home-network fingerprint kept on the phone; no location permission |
| 0009 | No permanent foreground service on Android; gaps are reported |
| 0010 | Household-level pushes only with evidence the phone was at home |
| 0011 | PostgreSQL as the queue; no Redis, Kafka or Kubernetes |
| 0012 | Fly.io as the documented host; one small always-on machine |
| 0013 | Invitation token in the URL fragment |
| 0014 | No sign-in: a phone is enrolled by an invitation link (owner's decision, 2026-11-23) |
