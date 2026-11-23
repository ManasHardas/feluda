import Foundation

/// Active internet reachability check.
///
/// A satisfied network path only says that an interface has a route. It does not prove that any
/// internet destination can be reached (a router without an uplink still gives a satisfied Wi-Fi
/// path). So the app sends a real HTTPS request: first to the service's health endpoint and, if
/// that does not answer, to a second independent target. Reachable means either one answered.
///
/// Two transports are used:
/// - `anyPath`: the system may use any interface (it may quietly use cellular when Wi-Fi is bad);
/// - `withoutCellular`: cellular access is switched off for the request, so an answer means the
///   internet works over Wi-Fi (or another non-cellular interface).
struct ReachabilityProbe: ReachabilityProbing {
    let healthURL: URL
    let fallbackURL: URL?
    let anyPath: HTTPTransport
    let withoutCellular: HTTPTransport

    func probe(testWithoutCellular: Bool) async -> ProbeResult {
        async let general = reachable(over: anyPath)
        if testWithoutCellular {
            async let restricted = reachable(over: withoutCellular)
            return await ProbeResult(anyPath: general, withoutCellular: restricted)
        }
        return await ProbeResult(anyPath: general, withoutCellular: nil)
    }

    private func reachable(over transport: HTTPTransport) async -> Bool {
        if await answers(healthURL, over: transport) { return true }
        if let fallbackURL, await answers(fallbackURL, over: transport) { return true }
        return false
    }

    /// Any HTTP response counts as "answered": the request went out and a server replied.
    /// With HTTPS a captive portal cannot fake that reply.
    private func answers(_ url: URL, over transport: HTTPTransport) async -> Bool {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        do {
            _ = try await transport.send(request)
            return true
        } catch {
            return false
        }
    }
}
