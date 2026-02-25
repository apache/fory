// swift-tools-version: 6.0
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
    name: "fory-swift-benchmark",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .executable(
            name: "swift-benchmark",
            targets: ["SwiftBenchmark"]
        ),
    ],
    dependencies: [
        .package(name: "fory-swift", path: "./fory-swift-dep"),
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.35.0"),
        .package(url: "https://github.com/Flight-School/MessagePack.git", from: "1.2.4"),
    ],
    targets: [
        .target(
            name: "SwiftBenchmarkProto",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ],
            path: "Sources/SwiftBenchmarkProto",
            exclude: ["bench.proto"]
        ),
        .executableTarget(
            name: "SwiftBenchmark",
            dependencies: [
                .product(name: "Fory", package: "fory-swift"),
                "SwiftBenchmarkProto",
                .product(name: "MessagePack", package: "MessagePack"),
            ],
            path: "Sources/SwiftBenchmark"
        ),
    ]
)
