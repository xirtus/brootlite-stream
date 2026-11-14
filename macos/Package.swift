// swift-tools-version: 6.0
import Foundation
import PackageDescription

let packageDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let coreReleaseDir = packageDir
    .deletingLastPathComponent()
    .appendingPathComponent("core/target/release")
    .path

let package = Package(
    name: "BrootLiteMac",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "BrootLiteMac", targets: ["BrootLiteMac"])
    ],
    targets: [
        .target(
            name: "CBrootLiteBridge",
            path: "Sources/CBrootLiteBridge",
            publicHeadersPath: "include",
            cSettings: [
                .define("GL_SILENCE_DEPRECATION", to: "1")
            ]
        ),
        .executableTarget(
            name: "BrootLiteMac",
            dependencies: ["CBrootLiteBridge"],
            path: "Sources/BrootLiteMac",
            swiftSettings: [
                .swiftLanguageMode(.v6)
            ],
            linkerSettings: [
                .unsafeFlags(["-Xlinker", "-w", "\(coreReleaseDir)/libbrootlite_core.a"]),
                .linkedFramework("AppKit"),
                .linkedFramework("SwiftUI"),
                .linkedFramework("OpenGL"),
                .linkedFramework("QuartzCore"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("AVKit"),
                .linkedFramework("CoreImage"),
                .linkedFramework("CoreText"),
                .linkedFramework("Security"),
                .linkedFramework("SystemConfiguration"),
                .linkedFramework("CoreFoundation"),
                .linkedLibrary("iconv")
            ]
        )
    ]
)
