import Darwin
import Foundation

enum SystemInfo {
    /// "17.4.1"
    static var osVersion: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return v.patchVersion == 0 ? "\(v.majorVersion).\(v.minorVersion)" : "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
    }

    static var marketingVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    static var buildNumber: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
    }

    /// "1.0.0 (1)". Matches the API's version-string pattern.
    static var appVersion: String { "\(marketingVersion) (\(buildNumber))" }

    /// Wall-clock time the device booted (kern.boottime). Used only to tell a device restart from
    /// an app suspension when measuring a monitoring gap; it is never transmitted.
    static func bootTime() -> Date? {
        var value = timeval()
        var size = MemoryLayout<timeval>.stride
        var mib: [Int32] = [CTL_KERN, KERN_BOOTTIME]
        guard sysctl(&mib, 2, &value, &size, nil, 0) == 0, value.tv_sec > 0 else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(value.tv_sec) + TimeInterval(value.tv_usec) / 1_000_000)
    }

    /// Wall-clock seconds since boot (includes time the device was asleep).
    static func uptime(now: Date = Date()) -> TimeInterval? {
        guard let boot = bootTime() else { return nil }
        let value = now.timeIntervalSince(boot)
        return value >= 0 ? value : nil
    }
}
