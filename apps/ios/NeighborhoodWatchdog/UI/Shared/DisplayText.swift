import SwiftUI

// Wording rules for everything a resident reads:
// - This app detects connectivity anomalies. It cannot identify their cause and never says that
//   jamming was confirmed.
// - Allowed terms: connectivity interruption, wireless connectivity anomaly, correlated wireless
//   disruption, suspected RF interference, elevated neighborhood alert.
// - Grey means "not known". It is never presented as safe.

extension StatusColor {
    var color: Color {
        switch self {
        case .green: return .green
        case .yellow: return .yellow
        case .orange: return .orange
        case .red: return .red
        case .grey: return .gray
        }
    }

    /// The meaning of the colour, always shown next to it.
    var meaning: String {
        switch self {
        case .green: return "No current connectivity anomaly"
        case .yellow: return "Household connectivity interruption"
        case .orange: return "Multiple nearby connectivity disruptions"
        case .red: return "High-priority suspicious neighborhood disruption"
        case .grey: return "Monitoring inactive or status unknown"
        }
    }

    /// A shape as well as a colour, so the status does not depend on colour vision.
    var symbolName: String {
        switch self {
        case .green: return "checkmark.circle.fill"
        case .yellow: return "exclamationmark.circle.fill"
        case .orange: return "exclamationmark.triangle.fill"
        case .red: return "exclamationmark.octagon.fill"
        case .grey: return "questionmark.circle.fill"
        }
    }
}

extension ConnectivityState {
    var displayName: String {
        switch self {
        case .healthy: return "Wi-Fi connected, internet reachable"
        case .wifiDisconnected: return "Wi-Fi not connected"
        case .wifiConnectedNoInternet: return "Wi-Fi connected, internet not reachable"
        case .internetUnavailable: return "No connection at all"
        case .cellularAvailable: return "Cellular only (no Wi-Fi seen yet)"
        case .cellularUnavailable: return "No Wi-Fi seen yet and no cellular"
        case .networkTransition: return "Network is changing"
        case .monitoringPaused: return "Monitoring is switched off"
        case .monitoringPermissionRequired: return "Monitoring needs a permission"
        case .unknown: return "Not known yet"
        }
    }
}

extension MonitoringStatus {
    var displayName: String {
        switch self {
        case .confirmedActive: return "Monitoring confirmed active"
        case .knownGap: return "Known monitoring gap"
        case .paused: return "Monitoring switched off"
        case .noRecentTelemetry: return "No recent reports"
        case .failureReported: return "Connectivity interruption reported"
        case .neverReported: return "No reports received yet"
        }
    }
}

extension Classification {
    var displayName: String {
        switch self {
        case .normal: return "No anomaly"
        case .householdConnectivityIssue: return "Household connectivity interruption"
        case .possibleIspOutage: return "Possible internet provider outage"
        case .possibleRouterOrPowerFailure: return "Possible router or power failure"
        case .possibleWirelessInterference: return "Wireless connectivity anomaly (suspected RF interference)"
        case .correlatedNeighborhoodDisruption: return "Correlated wireless disruption"
        case .highPrioritySuspiciousDisruption: return "Elevated neighborhood alert"
        }
    }
}

extension AlertLevel {
    var displayName: String {
        switch self {
        case .normal: return "Normal"
        case .householdWarning: return "Household warning"
        case .neighborhoodAnomaly: return "Neighborhood anomaly"
        case .highPriority: return "High priority"
        }
    }
}

extension IncidentStatus {
    var displayName: String {
        switch self {
        case .open: return "Ongoing"
        case .resolved: return "Resolved: affected households reported recovery"
        case .expired: return "Closed without recovery reports (outcome unknown)"
        }
    }
}

extension IncidentScope {
    var displayName: String {
        switch self {
        case .household: return "Your household"
        case .neighborhood: return "Neighborhood"
        }
    }
}

extension Isp {
    var displayName: String {
        switch self {
        case .att: return "AT&T"
        case .xfinity: return "Xfinity"
        case .spectrum: return "Spectrum"
        case .verizon: return "Verizon"
        case .tmobileHome: return "T-Mobile Home Internet"
        case .googleFiber: return "Google Fiber"
        case .other: return "Other"
        case .unknown: return "Not known"
        }
    }

    static func displayName(forKey key: String) -> String {
        Isp(rawValue: key)?.displayName ?? key
    }
}

extension FailureMode {
    var displayName: String {
        switch self {
        case .wifiLost: return "Wi-Fi connection lost"
        case .noInternet: return "Wi-Fi connected, internet not reachable"
        case .allConnectivityLost: return "Wi-Fi and cellular both lost"
        }
    }
}

extension GapReason {
    var displayName: String {
        switch self {
        case .appSuspended: return "iOS paused the app in the background"
        case .processTerminated: return "The app was not running"
        case .deviceReboot: return "The phone was restarted"
        case .permissionRevoked: return "A permission was removed"
        case .backgroundRestricted: return "Background activity was restricted"
        case .unknown: return "Reason not known"
        }
    }
}

extension EventType {
    var displayName: String {
        switch self {
        case .wifiLost: return "Wi-Fi lost"
        case .wifiRestored: return "Wi-Fi restored"
        case .defaultNetworkChanged: return "Default network changed"
        case .cellularAvailable: return "Cellular available"
        case .cellularUnavailable: return "Cellular unavailable"
        case .internetReachabilityChanged: return "Internet reachability changed"
        case .signalStrengthChanged: return "Signal strength changed"
        case .wifiAssociationChanged: return "Wi-Fi network changed"
        case .monitoringStarted: return "Monitoring started"
        case .monitoringResumed: return "Monitoring resumed"
        case .monitoringStopped: return "Monitoring stopped"
        case .monitoringDisabledByUser: return "Monitoring switched off by you"
        case .monitoringGapDetected: return "Monitoring gap"
        case .permissionChanged: return "Permission changed"
        case .heartbeat: return "Still monitoring"
        }
    }
}

extension Transport {
    var displayName: String {
        switch self {
        case .wifi: return "Wi-Fi"
        case .cellular: return "Cellular"
        case .ethernet: return "Wired"
        case .vpn: return "VPN"
        case .other: return "Other"
        case .none: return "None"
        case .unknown: return "Not known"
        }
    }
}

enum DisplayFormat {
    static func dateTime(_ date: Date?) -> String {
        guard let date else { return "Never" }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    static func duration(_ seconds: TimeInterval) -> String {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .abbreviated
        formatter.allowedUnits = seconds >= 3600 ? [.day, .hour, .minute] : [.minute, .second]
        formatter.maximumUnitCount = 2
        return formatter.string(from: max(0, seconds)) ?? "\(Int(seconds)) s"
    }

    static func yesNoUnknown(_ value: Bool?, yes: String, no: String, unknown: String = "Not known") -> String {
        switch value {
        case .some(true): return yes
        case .some(false): return no
        case .none: return unknown
        }
    }
}
