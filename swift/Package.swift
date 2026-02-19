// swift-tools-version: 6.0
import CompilerPluginSupport
import PackageDescription

let package = Package(
    name: "fory-swift",
    platforms: [
        .macOS(.v13),
        .iOS(.v16),
    ],
    products: [
        .library(
            name: "Fory",
            targets: ["ForyCore"]
        ),
        .library(
            name: "ForyCore",
            targets: ["ForyCore"]
        ),
        .executable(
            name: "ForySwiftXlangPeer",
            targets: ["ForySwiftXlangPeer"]
        ),
    ],
    dependencies: [
        .package(url: "https://github.com/swiftlang/swift-syntax.git", from: "600.0.0")
    ],
    targets: [
        .macro(
            name: "ForyMacros",
            dependencies: [
                .product(name: "SwiftCompilerPlugin", package: "swift-syntax"),
                .product(name: "SwiftSyntax", package: "swift-syntax"),
                .product(name: "SwiftSyntaxBuilder", package: "swift-syntax"),
                .product(name: "SwiftSyntaxMacros", package: "swift-syntax"),
            ],
            path: "Sources/ForyMacros"
        ),
        .target(
            name: "ForyCore",
            dependencies: ["ForyMacros"],
            path: "Sources/ForyCore"
        ),
        .executableTarget(
            name: "ForySwiftXlangPeer",
            dependencies: ["ForyCore"],
            path: "Sources/ForyXlangTests"
        ),
        .testTarget(
            name: "ForyTests",
            dependencies: ["ForyCore"],
            path: "Tests/ForyTests"
        ),
    ]
)
