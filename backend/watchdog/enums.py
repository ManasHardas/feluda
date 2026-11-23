"""Enumerations shared by the API contract, the database and the detection engine.

These values are part of the public API contract (shared/api-contracts/openapi.json).
Changing or removing a value is a breaking change for the mobile apps.
"""

from enum import IntEnum, StrEnum


class ConnectivityState(StrEnum):
    HEALTHY = "HEALTHY"
    WIFI_DISCONNECTED = "WIFI_DISCONNECTED"
    WIFI_CONNECTED_NO_INTERNET = "WIFI_CONNECTED_NO_INTERNET"
    INTERNET_UNAVAILABLE = "INTERNET_UNAVAILABLE"
    CELLULAR_AVAILABLE = "CELLULAR_AVAILABLE"
    CELLULAR_UNAVAILABLE = "CELLULAR_UNAVAILABLE"
    NETWORK_TRANSITION = "NETWORK_TRANSITION"
    MONITORING_PAUSED = "MONITORING_PAUSED"
    MONITORING_PERMISSION_REQUIRED = "MONITORING_PERMISSION_REQUIRED"
    UNKNOWN = "UNKNOWN"


class EventType(StrEnum):
    WIFI_LOST = "WIFI_LOST"
    WIFI_RESTORED = "WIFI_RESTORED"
    DEFAULT_NETWORK_CHANGED = "DEFAULT_NETWORK_CHANGED"
    CELLULAR_AVAILABLE = "CELLULAR_AVAILABLE"
    CELLULAR_UNAVAILABLE = "CELLULAR_UNAVAILABLE"
    INTERNET_REACHABILITY_CHANGED = "INTERNET_REACHABILITY_CHANGED"
    SIGNAL_STRENGTH_CHANGED = "SIGNAL_STRENGTH_CHANGED"
    WIFI_ASSOCIATION_CHANGED = "WIFI_ASSOCIATION_CHANGED"
    MONITORING_STARTED = "MONITORING_STARTED"
    MONITORING_RESUMED = "MONITORING_RESUMED"
    MONITORING_STOPPED = "MONITORING_STOPPED"
    MONITORING_DISABLED_BY_USER = "MONITORING_DISABLED_BY_USER"
    MONITORING_GAP_DETECTED = "MONITORING_GAP_DETECTED"
    PERMISSION_CHANGED = "PERMISSION_CHANGED"
    HEARTBEAT = "HEARTBEAT"


class Platform(StrEnum):
    ANDROID = "android"
    IOS = "ios"


class Transport(StrEnum):
    WIFI = "wifi"
    CELLULAR = "cellular"
    ETHERNET = "ethernet"
    VPN = "vpn"
    OTHER = "other"
    NONE = "none"
    UNKNOWN = "unknown"


class BandEvidence(StrEnum):
    BAND_2_4_OBSERVED = "BAND_2_4_OBSERVED"
    BAND_5_OBSERVED = "BAND_5_OBSERVED"
    BAND_6_OBSERVED = "BAND_6_OBSERVED"
    BAND_UNKNOWN = "BAND_UNKNOWN"
    MULTI_BAND_FAILURE_NOT_VERIFIED = "MULTI_BAND_FAILURE_NOT_VERIFIED"


class ObservationConfidence(StrEnum):
    """How the device learned about the event.

    OBSERVED: seen live through an operating-system callback; the timestamp is the callback time.
    RECONCILED: derived after a monitoring gap by comparing state before and after; the true time
    of the change is unknown and lies somewhere inside the gap.
    """

    OBSERVED = "OBSERVED"
    RECONCILED = "RECONCILED"


class GapReason(StrEnum):
    APP_SUSPENDED = "app_suspended"
    PROCESS_TERMINATED = "process_terminated"
    DEVICE_REBOOT = "device_reboot"
    PERMISSION_REVOKED = "permission_revoked"
    BACKGROUND_RESTRICTED = "background_restricted"
    UNKNOWN = "unknown"


class BackgroundMonitoring(StrEnum):
    """What the platform lets this app do while it is not in the foreground."""

    CALLBACK = "callback"  # network callbacks delivered while the process is alive (Android)
    OPPORTUNISTIC = "opportunistic"  # only when the OS chooses to wake the app (iOS)
    FOREGROUND_ONLY = "foreground_only"


class Isp(StrEnum):
    ATT = "att"
    XFINITY = "xfinity"
    SPECTRUM = "spectrum"
    VERIZON = "verizon"
    TMOBILE_HOME = "tmobile_home"
    GOOGLE_FIBER = "google_fiber"
    OTHER = "other"
    UNKNOWN = "unknown"


class HouseholdStatus(StrEnum):
    APPROVED = "approved"  # address pre-registered by an administrator, nobody joined yet
    ACTIVE = "active"
    SUSPENDED = "suspended"
    REMOVED = "removed"


class MemberRole(StrEnum):
    RESIDENT = "resident"
    ADMIN = "admin"


class MemberStatus(StrEnum):
    ACTIVE = "active"
    REMOVED = "removed"


class InvitationStatus(StrEnum):
    ACTIVE = "active"
    USED = "used"
    REVOKED = "revoked"


class DeviceStatus(StrEnum):
    ACTIVE = "active"
    REVOKED = "revoked"


class Classification(StrEnum):
    NORMAL = "NORMAL"
    HOUSEHOLD_CONNECTIVITY_ISSUE = "HOUSEHOLD_CONNECTIVITY_ISSUE"
    POSSIBLE_ISP_OUTAGE = "POSSIBLE_ISP_OUTAGE"
    POSSIBLE_ROUTER_OR_POWER_FAILURE = "POSSIBLE_ROUTER_OR_POWER_FAILURE"
    POSSIBLE_WIRELESS_INTERFERENCE = "POSSIBLE_WIRELESS_INTERFERENCE"
    CORRELATED_NEIGHBORHOOD_DISRUPTION = "CORRELATED_NEIGHBORHOOD_DISRUPTION"
    HIGH_PRIORITY_SUSPICIOUS_DISRUPTION = "HIGH_PRIORITY_SUSPICIOUS_DISRUPTION"


class AlertLevel(IntEnum):
    NORMAL = 0
    HOUSEHOLD_WARNING = 1
    NEIGHBORHOOD_ANOMALY = 2
    HIGH_PRIORITY = 3


class StatusColor(StrEnum):
    GREEN = "GREEN"
    YELLOW = "YELLOW"
    ORANGE = "ORANGE"
    RED = "RED"
    GREY = "GREY"  # monitoring inactive or unknown: never shown as healthy


class IncidentScope(StrEnum):
    HOUSEHOLD = "household"
    NEIGHBORHOOD = "neighborhood"


class IncidentStatus(StrEnum):
    OPEN = "open"
    RESOLVED = "resolved"  # enough affected households reported recovery
    EXPIRED = "expired"  # no recovery reports arrived; closed by timeout, outcome unknown
    MERGED = "merged"  # a household-scope incident that became part of a neighborhood incident


class FailureMode(StrEnum):
    WIFI_LOST = "wifi_lost"  # Wi-Fi association lost
    NO_INTERNET = "no_internet"  # Wi-Fi still associated, internet unreachable
    ALL_CONNECTIVITY_LOST = "all_connectivity_lost"  # neither Wi-Fi nor cellular


class MonitoringStatus(StrEnum):
    CONFIRMED_ACTIVE = "CONFIRMED_ACTIVE"
    KNOWN_GAP = "KNOWN_GAP"
    PAUSED = "PAUSED"
    NO_RECENT_TELEMETRY = "NO_RECENT_TELEMETRY"
    FAILURE_REPORTED = "FAILURE_REPORTED"
    NEVER_REPORTED = "NEVER_REPORTED"


class PushProvider(StrEnum):
    FCM = "fcm"
    APNS = "apns"


class ApnsEnvironment(StrEnum):
    SANDBOX = "sandbox"
    PRODUCTION = "production"


class DeliveryStatus(StrEnum):
    PENDING = "pending"
    SENT = "sent"  # accepted by the push provider; arrival on the phone is not guaranteed
    FAILED = "failed"
    EXPIRED = "expired"
    SKIPPED_QUIET_HOURS = "skipped_quiet_hours"
    SKIPPED_PREFERENCE = "skipped_preference"
    SKIPPED_NOT_CONFIGURED = "skipped_not_configured"


class NotificationKind(StrEnum):
    ALERT = "alert"
    ESCALATION = "escalation"
    RESOLUTION = "resolution"


class EventResult(StrEnum):
    ACCEPTED = "accepted"
    DUPLICATE = "duplicate"
    REJECTED = "rejected"
