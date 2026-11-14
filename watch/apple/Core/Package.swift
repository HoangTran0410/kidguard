// swift-tools-version:5.9
import PackageDescription

// UI-free core of the Apple Watch app: models, JSON, rules edits, relay client, pairing.
// Builds and tests on macOS (`swift test`), and is linked into the watchOS app by project.yml.
let package = Package(
    name: "KidGuardCore",
    platforms: [.macOS(.v13), .iOS(.v16), .watchOS(.v10)],
    products: [.library(name: "KidGuardCore", targets: ["KidGuardCore"])],
    targets: [
        .target(name: "KidGuardCore"),
        .testTarget(name: "KidGuardCoreTests", dependencies: ["KidGuardCore"]),
    ],
    swiftLanguageVersions: [.v5]
)
