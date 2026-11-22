# 0006 Correlation recomputes a bounded window from stored events

**Decision.** Each run for a neighborhood loads the events from just before the oldest unprocessed
event (or oldest open incident), rebuilds household failure episodes, clusters them and upserts
incidents by matching on time and shared households. A per-neighborhood advisory lock serialises
runs. Runs happen right after each accepted upload and from a periodic tick.

**Why.** Phones upload late and out of order. Recomputing from occurrence times makes late arrival
a non-issue and makes runs idempotent: the same events always give the same incidents. Incremental
state machines are faster but much harder to get right with reordering.

**Consequences.** An incident can change classification as evidence arrives (for example late
power-loss reports). The alert level shown is the current assessment; `peak_alert_level` records
the highest level reached and drives notifications, which are never retracted, only followed by a
resolution message. Closed incidents are not reopened by late evidence.

**Cost.** Work per run grows with events in the window; trivial at pilot size (hundreds of rows).

**Revisit when.** A neighborhood produces more than a few thousand events per hour.
