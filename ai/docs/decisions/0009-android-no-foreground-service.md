# 0009 No permanent foreground service on Android; gaps are reported

**Decision.** Network callbacks are registered while the app process is alive. WorkManager runs a
15-minute periodic job that re-registers callbacks, sends a heartbeat, flushes the queue and
detects gaps; an expedited job uploads new events. A boot receiver reschedules the work. There is
no always-on foreground service.

**Why.** Google Play policy requires a declared, user-visible purpose for each foreground-service
type and none fits passive connectivity monitoring cleanly; a permanent notification and the
battery cost would also push residents to uninstall. The brief says not to create a foreground
service just to stay alive.

**Consequence.** When Android stops the process, monitoring pauses until the next WorkManager run.
The app records the gap and reports it (`MONITORING_GAP_DETECTED`), the status screen shows it, and
the backend counts the household as "no recent telemetry" rather than healthy.

**Revisit when.** Pilot data shows gaps are too long on common phones. An opt-in foreground service
with an honest notification would be the next step, after a policy check.
