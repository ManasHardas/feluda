# 0002 Plain SQL with psycopg and a small migration runner; no ORM

**Decision.** Queries are parameterised SQL through psycopg 3. Migrations are numbered `.sql`
files applied by `python -m watchdog.migrate` (forward-only, each in a transaction, checksum
recorded, edited files refused).

**Why.** Row-level security, column-level grants and `SET LOCAL ROLE` are the heart of the
security design, and they are clearest in SQL. An ORM would add a second definition of the schema
that can drift from the migrations. The runner is about 60 lines and the same files can be applied
with `psql` or the Supabase CLI.

**Rejected.** SQLAlchemy plus Alembic (standard, but more surface and a duplicate schema
definition). Supabase migrations as the only path (vendor-specific).

**Cost.** No automatic down-migrations. Rollback is: deploy the previous image (migrations must be
backward compatible for one release) or restore from backup. See `docs/DEPLOYMENT.md`.

**Revisit when.** The schema grows past a few dozen tables or several people write migrations.
