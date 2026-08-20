---
title: Swift Setup
sidebar_position: 8
id: swift
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

Fory Swift provides xlang Object Serialization and compiler-generated models.
It is distributed through Swift Package Manager, uses Swift tools 6.0, and
targets macOS 13 or later and iOS 16 or later.

## Verify the Toolchain

```bash
swift --version
```

## Object Serialization

Create an executable package:

```bash
swift package init --type executable --name ForyExample
```

Add the released package and depend on its `Fory` library in the generated `Package.swift`:

```swift title="Package.swift"
dependencies: [
    .package(url: "https://github.com/apache/fory.git", exact: "1.6.1")
],
targets: [
    .executableTarget(
        name: "ForyExample",
        dependencies: [.product(name: "Fory", package: "fory")]
    )
]
```

Replace `Sources/main.swift` with:

```swift title="Sources/main.swift"
import Fory

@ForyStruct
struct User: Equatable {
    var id: Int64 = 0
    var name: String = ""
}

let fory = Fory()
try fory.register(User.self, id: 1)

let input = User(id: 1, name: "Alice")
let bytes = try fory.serialize(input)
let decoded: User = try fory.deserialize(bytes)
assert(input == decoded)
```

```bash
swift run
```

Swift uses xlang mode. Continue with
[Swift Object Serialization](../object-serialization/swift/index.md),
[xlang types](../object-serialization/swift/basic-serialization.md#cross-language-interoperability),
[configuration](../object-serialization/swift/configuration.md), and
[schema evolution](../object-serialization/swift/schema-evolution.md).

## Other Capabilities

- **Fory IDL and Compiler** generates Swift models and registration helpers. See [Compiler Getting Started](../compiler/getting-started.md) and the [Swift generated-code guide](../compiler/generated-code/swift.md).
