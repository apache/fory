// swift-tools-version:5.9
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

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
