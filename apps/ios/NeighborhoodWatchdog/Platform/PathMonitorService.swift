import Foundation
import Network

/// Observes network paths with three `NWPathMonitor`s: the default path, a Wi-Fi-only path and a
/// cellular-only path. It reports what iOS says about interfaces; it does not test whether the
/// internet is reachable (see `ReachabilityProbe` for that).
final class PathMonitorService: PathObserving, @unchecked Sendable {
    private let queue = DispatchQueue(label: "org.neighborhoodwatchdog.path-monitor")
    private let lock = NSLock()
    private var snapshot = PathSnapshot()
    private var handler: (@Sendable (PathSnapshot) -> Void)?
    private var monitors: [NWPathMonitor] = []

    func start(onChange: @escaping @Sendable (PathSnapshot) -> Void) {
        stop()
        let general = NWPathMonitor()
        let wifi = NWPathMonitor(requiredInterfaceType: .wifi)
        let cellular = NWPathMonitor(requiredInterfaceType: .cellular)

        general.pathUpdateHandler = { [weak self] path in
            let transport = Self.transport(of: path)
            self?.update {
                $0.defaultTransport = transport
                $0.otherPathUsable = path.status == .satisfied && ![.wifi, .cellular, .none, .unknown].contains(transport)
            }
        }
        wifi.pathUpdateHandler = { [weak self] path in
            let available = path.status == .satisfied
            let gateways = available ? path.gateways.compactMap(Self.address(of:)) : []
            self?.update {
                $0.wifiAvailable = available
                $0.wifiGateways = gateways
            }
        }
        cellular.pathUpdateHandler = { [weak self] path in
            let available = path.status == .satisfied
            self?.update { $0.cellularAvailable = available }
        }

        lock.lock()
        snapshot = PathSnapshot()
        handler = onChange
        monitors = [general, wifi, cellular]
        lock.unlock()
        for monitor in [general, wifi, cellular] { monitor.start(queue: queue) }
    }

    func stop() {
        lock.lock()
        let old = monitors
        monitors = []
        handler = nil
        snapshot = PathSnapshot()
        lock.unlock()
        for monitor in old { monitor.cancel() }
    }

    func current() -> PathSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return snapshot
    }

    private func update(_ change: (inout PathSnapshot) -> Void) {
        lock.lock()
        var next = snapshot
        change(&next)
        let changed = next != snapshot
        snapshot = next
        let handler = self.handler
        lock.unlock()
        if changed { handler?(next) }
    }

    /// Interface type of a path. `.none` when the path is not satisfied; `.unknown` when the
    /// path is satisfied but lists no interface.
    static func transport(of path: NWPath) -> Transport {
        guard path.status == .satisfied else { return .none }
        guard let type = path.availableInterfaces.first?.type else { return .unknown }
        switch type {
        case .wifi: return .wifi
        case .cellular: return .cellular
        case .wiredEthernet: return .ethernet
        case .loopback, .other: return .other
        @unknown default: return .unknown
        }
    }

    private static func address(of endpoint: NWEndpoint) -> String? {
        guard case let .hostPort(host, _) = endpoint else { return nil }
        switch host {
        case let .ipv4(address): return address.debugDescription
        case let .ipv6(address): return address.debugDescription
        case let .name(name, _): return name
        @unknown default: return nil
        }
    }
}

/// A single always-on monitor of the default path. It is used only to label uploads with the
/// interface type in use at send time (`reported_via`) and to retry waiting uploads when a network
/// comes back. It records nothing.
final class UploadPathObserver: @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "org.neighborhoodwatchdog.upload-path")
    private let lock = NSLock()
    private var transport: Transport?
    private var onUsable: (@Sendable () -> Void)?

    func start(onUsable: @escaping @Sendable () -> Void) {
        lock.lock()
        self.onUsable = onUsable
        lock.unlock()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let value = PathMonitorService.transport(of: path)
            self.lock.lock()
            let previous = self.transport
            self.transport = value
            let callback = self.onUsable
            self.lock.unlock()
            if value != .none, previous != value { callback?() }
        }
        monitor.start(queue: queue)
    }

    /// The interface type the system is using now. "unknown" when there is no usable path or the
    /// type cannot be told. This describes the default path; iOS does not tell an app which
    /// interface an individual URLSession request really used.
    func currentTransport() -> Transport {
        lock.lock()
        defer { lock.unlock() }
        guard let transport, transport != .none else { return .unknown }
        return transport
    }
}
