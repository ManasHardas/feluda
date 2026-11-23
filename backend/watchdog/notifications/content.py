"""Notification wording. Inference is never presented as fact; the word "jamming" is never asserted."""

from __future__ import annotations

from ..enums import Classification, NotificationKind

CHECK_DEVICES = (
    "Please check your alarm and camera connectivity through their respective applications and verify that your "
    "security system's backup protection is operating."
)


def compose(kind: str, classification: str, alert_level: int, affected: int, scope: str) -> tuple[str, str]:
    if kind == NotificationKind.RESOLUTION:
        if scope == "household":
            return ("Home connectivity restored", "Your household's devices report that Wi-Fi connectivity is back.")
        return (
            "Neighborhood connectivity restored",
            "Most affected households report that connectivity is back. The cause of the earlier disruption has not "
            "been determined.",
        )
    if alert_level >= 3:
        return (
            "HIGH-PRIORITY NEIGHBORHOOD WIRELESS ALERT",
            f"{affected} participating households report a continuing, simultaneous Wi-Fi disruption with several "
            "independent indicators of possible wireless interference. This does not confirm deliberate jamming. "
            + CHECK_DEVICES,
        )
    if alert_level == 2:
        return (
            "NEIGHBORHOOD WIRELESS ALERT",
            "Multiple participating households have reported simultaneous Wi-Fi disruptions. Possible wireless "
            "interference has been detected. This does not confirm deliberate jamming. " + CHECK_DEVICES,
        )
    if classification == Classification.POSSIBLE_ISP_OUTAGE:
        if scope == "neighborhood":
            return (
                "Possible internet outage nearby",
                f"{affected} households, including yours, lost internet access while Wi-Fi stayed connected. This looks "
                "like a provider or upstream problem. Internet-dependent cameras and alarms may be offline.",
            )
        return (
            "Home internet appears to be down",
            "Your home Wi-Fi is up but the internet behind it is not reachable. Internet-dependent cameras and alarms "
            "may be offline.",
        )
    if classification == Classification.POSSIBLE_ROUTER_OR_POWER_FAILURE:
        if scope == "neighborhood":
            return (
                "Possible power or infrastructure failure nearby",
                f"{affected} households, including yours, lost Wi-Fi at the same time and phones stopped charging. "
                "Security devices without battery backup may be offline.",
            )
        return (
            "Possible router or power failure at home",
            "Your home Wi-Fi went away together with signs of a power or router failure. Security devices without "
            "battery backup may be offline.",
        )
    if classification == Classification.POSSIBLE_WIRELESS_INTERFERENCE:
        return (
            "Wireless connectivity anomaly at home",
            "Your home Wi-Fi dropped in an unusual way while cellular service stayed available. Wireless interference "
            "is possible but not confirmed. " + CHECK_DEVICES,
        )
    return (
        "Home Wi-Fi connectivity interruption",
        "A phone in your household lost its home Wi-Fi connection. If you are away, check that your cameras and alarm "
        "are still online.",
    )
