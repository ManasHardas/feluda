# 0010 Household-level pushes only with evidence the phone was at home

**Decision.** Every single-household interruption is recorded and shown in that household's app
(yellow). A push is sent only when the evidence shows the phone was at home when connectivity
failed: Wi-Fi still associated but no internet, charging power lost at the same moment, a strong
signal just before the loss, or repeated losses. The push waits 180 seconds for a quick recovery
and is limited to one per household per hour. A plain "one phone lost Wi-Fi" with no such evidence
is not pushed.

**Why.** The brief says level 1 notifies that household. Taken literally, every resident with an
iPhone would get a push each time they leave the house, and would switch notifications off within
a day, which would also silence the neighborhood alerts that matter.

**Deviation from the brief.** This narrows "notify only that household" to "notify that household
when it is worth it". Thresholds are configuration (`household_alert_delay_s`,
`household_alert_cooldown_min`). The owner should confirm this choice.
