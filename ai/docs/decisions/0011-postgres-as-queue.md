# 0011 PostgreSQL as the queue; no Redis, Kafka or Kubernetes

**Decision.** Unprocessed events are rows with `correlated_at IS NULL`; pending notifications are
rows with `status = 'pending'` taken with `FOR UPDATE SKIP LOCKED`; cross-process rate limits are
fixed-window counters in a table; advisory locks give mutual exclusion.

**Why.** The brief asks for no extra infrastructure without measured need. Everything stays
transactional with the data it concerns and there is one thing to back up.

**Limit.** The in-process sliding-window limiter (unauthenticated endpoints, request floods) is per
process. With more than one API instance the effective limit multiplies by the instance count; the
limits that protect the detection engine are in the database and are exact.

**Revisit when.** More than one API instance is needed or event volume exceeds what one small
database handles comfortably.
