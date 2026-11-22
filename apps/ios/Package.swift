// swift-tools-version:5.9
// Lets the app's core logic (no UIKit) and its unit tests build and run natively on a Mac:
//     swift test
// This is in addition to the Xcode project, which remains the way the app itself is built.
// Useful in CI without a simulator and when the simulator test runner is unavailable.
import PackageDescription

let package = Package(
    name: "NeighborhoodWatchdogCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    targets: [
        .target(name: "NeighborhoodWatchdog", path: "NeighborhoodWatchdog/Core"),
        .testTarget(name: "NeighborhoodWatchdogTests", dependencies: ["NeighborhoodWatchdog"], path: "NeighborhoodWatchdogTests",
                    exclude: ["MiscTests.swift"]),  // needs app-level types (AppConfig, UI helpers)
    ]
)
