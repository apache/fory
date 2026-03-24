---
title: Field Configuration
sidebar_position: 5
id: field_configuration
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

This page covers macro-level field configuration in Swift.

## Available Macro Attributes

- `@ForyObject` on struct/class/enum
- `@ForyField(encoding: ...)` on numeric fields

## `@ForyField(encoding:)`

Use `@ForyField` to override integer encoding strategy.

```swift
@ForyObject
struct Metrics: Equatable {
    @ForyField(encoding: .fixed)
    var u32Fixed: UInt32 = 0

    @ForyField(encoding: .tagged)
    var u64Tagged: UInt64 = 0
}
```

### Supported combinations

| Swift type | Supported encoding values      | Default encoding |
| ---------- | ------------------------------ | ---------------- |
| `Int32`    | `.varint`, `.fixed`            | `.varint`        |
| `UInt32`   | `.varint`, `.fixed`            | `.varint`        |
| `Int64`    | `.varint`, `.fixed`, `.tagged` | `.varint`        |
| `UInt64`   | `.varint`, `.fixed`, `.tagged` | `.varint`        |
| `Int`      | `.varint`, `.fixed`, `.tagged` | `.varint`        |
| `UInt`     | `.varint`, `.fixed`, `.tagged` | `.varint`        |

Compile-time validation rejects unsupported combinations (for example, `Int32` with `.tagged`).

## `@ForyObject` Requirements

### Struct and class fields

- Stored properties must declare explicit types
- Computed properties are ignored
- Static/class properties are ignored

### Class requirement

Classes annotated with `@ForyObject` must provide a `required init()` for default construction.

```swift
@ForyObject
final class Node {
    var value: Int32 = 0
    var next: Node? = nil

    required init() {}
}
```

## Dynamic Any Fields in Macro Types

`@ForyObject` supports dynamic fields and nested containers:

- `Any`, `AnyObject`, `any Serializer`
- `AnyHashable`
- `[Any]`
- `[String: Any]`
- `[Int32: Any]`
- `[AnyHashable: Any]`

Current limitations:

- `Dictionary<K, Any>` is only supported when `K` is `String`, `Int32`, or `AnyHashable`
