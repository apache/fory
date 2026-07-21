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
    .testTarget(
      name: "ForyGrpcTests",
      dependencies: [
        "ForyGrpcGenerated",
        .product(name: "GRPC", package: "grpc-swift"),
        .product(name: "Fory", package: "swift"),
      ],
      path: "Tests/ForyGrpcTests"
    ),
  ]
)
