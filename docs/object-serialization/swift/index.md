---
title: Swift Object Serialization
sidebar_position: 0
id: index
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Apache Fory Swift provides high-performance object graph serialization with strong type safety, macro-based code generation, schema evolution, and cross-language compatibility.

## Why Fory Swift?

- Fast binary serialization for Swift value and reference types
- `@ForyStruct`, `@ForyEnum`, and `@ForyUnion` macros for zero-boilerplate model serialization
- Xlang protocol compatibility with Java, Rust, Go, Python, and more
- Compatible mode for schema evolution across versions
- External structural, custom, and recursively composed carrier serializers
- Built-in support for dynamic values and arbitrary application protocol existentials
- Reference tracking for shared/circular graphs, including weak references on classes

## Install

Add Fory Swift from the Apache Fory GitHub repository:

```swift
dependencies: [
    .package(url: "https://github.com/apache/fory.git", exact: "$version")
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "Fory", package: "fory")
        ]
    )
]
```

## Guide Contents

- [Configuration](configuration.md)
- [Basic Serialization](basic-serialization.md)
- [Schema Metadata](schema-metadata.md)
- [Type Registration](type-registration.md)
- [External-Type Serialization](external-types.md)
- [Custom Serializers](custom-serializers.md)
- [Shared and Circular References](references.md)
- [Polymorphism and Dynamic Types](polymorphism.md)
- [Schema Evolution](schema-evolution.md)
- [gRPC Support](../../grpc/swift.md)
- [Troubleshooting](troubleshooting.md)

## Quick Example

```swift
import Fory

@ForyStruct
struct User: Equatable {
    var name: String = ""
    var age: Int32 = 0
}

let fory = Fory()
try fory.register(User.self, id: 1)

let input = User(name: "alice", age: 30)
let data = try fory.serialize(input)
let output: User = try fory.deserialize(data)

assert(input == output)
```

Before decoding bytes from outside the application trust boundary, read
[Swift Security](security.md).
