// swift-tools-version:5.9
import PackageDescription

let package = Package(
  name: "ForyGrpcInterop",
  platforms: [.macOS(.v13)],
  dependencies: [
    .package(url: "https://github.com/grpc/grpc-swift.git", exact: "1.24.2"),
    .package(path: "../../../../swift"),
  ],
  targets: [
    .target(
      name: "ForyGrpcGenerated",
      dependencies: [
        .product(name: "GRPC", package: "grpc-swift"),
        .product(name: "Fory", package: "swift"),
      ],
      path: "Sources/Generated"
    ),
    // Package-less schemas, one module each. Both emit a bare `ForyModule`, so
    // together they cover generated helpers whose textual paths are identical
    // across modules.
    .target(
      name: "ForyGrpcDefaultPackageOne",
      dependencies: [
        .product(name: "GRPC", package: "grpc-swift"),
        .product(name: "Fory", package: "swift"),
      ],
      path: "Sources/GeneratedDefaultPackageOne"
    ),
    .target(
      name: "ForyGrpcDefaultPackageTwo",
      dependencies: [
        .product(name: "GRPC", package: "grpc-swift"),
        .product(name: "Fory", package: "swift"),
      ],
      path: "Sources/GeneratedDefaultPackageTwo"
    ),
    .executableTarget(
      name: "interop",
      dependencies: [
        "ForyGrpcGenerated",
        .product(name: "GRPC", package: "grpc-swift"),
        .product(name: "Fory", package: "swift"),
      ],
      path: "Sources/Interop"
    ),
    .testTarget(
      name: "ForyGrpcTests",
      dependencies: [
        "ForyGrpcGenerated",
        "ForyGrpcDefaultPackageOne",
        "ForyGrpcDefaultPackageTwo",
        .product(name: "GRPC", package: "grpc-swift"),
        .product(name: "Fory", package: "swift"),
      ],
      path: "Tests/ForyGrpcTests"
    ),
  ]
)
