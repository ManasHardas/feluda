# 0007 Deterministic, additive suspicion score; high priority needs persistence

**Decision.** A multi-household cluster is first routed by signature:

1. Most homes kept Wi-Fi and lost only internet: possible provider or upstream outage (level 1).
2. Several charging phones lost external power at the same moment: possible power or router
   failure (level 1).
3. Otherwise (Wi-Fi association lost): an additive 0 to 100 score over eight indicators and two
   penalties, each with a stored one-line explanation. Level 2 at 40 or more. Level 3 needs 75 or
   more, at least four households, and at least half of them still without Wi-Fi after 120 seconds.

Weights and thresholds are configuration with bounds (`backend/watchdog/config.py`), changeable per
neighborhood by an administrator. No machine learning.

**Why.** Every number shown to a resident must be explainable, and the model must be testable
case by case. Persistence as a gate keeps a brief simultaneous blip (a mesh firmware restart, for
example) from becoming the loudest alert.

**Honest limits.** The weights are judgement calls for a pilot, not validated thresholds. A phone
cannot tell a jammer from a router losing power except through the charging signal, and iPhones
give no signal-strength evidence. The explanation text always ends by saying that deliberate
interference has not been confirmed.

**Revisit when.** Pilot data shows which indicators actually separate real disruptions from noise.
