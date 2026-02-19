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
            targets: ["Fory"]
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
            name: "ForyMacro",
            dependencies: [
                .product(name: "SwiftCompilerPlugin", package: "swift-syntax"),
                .product(name: "SwiftSyntax", package: "swift-syntax"),
                .product(name: "SwiftSyntaxBuilder", package: "swift-syntax"),
                .product(name: "SwiftSyntaxMacros", package: "swift-syntax"),
            ],
            path: "Sources/ForyMacro"
        ),
        .target(
            name: "Fory",
            dependencies: ["ForyMacro"],
            path: "Sources/Fory"
        ),
        .executableTarget(
            name: "ForySwiftXlangPeer",
            dependencies: ["Fory"],
            path: "Tests/ForyXlangPeer"
        ),
        .testTarget(
            name: "ForyTests",
            dependencies: ["Fory"],
            path: "Tests/ForyTests"
        ),
    ]
)
