---
title: Schema Metadata
sidebar_position: 4
id: schema_metadata
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

This page covers macro-level schema metadata in Swift.

## Available Macro Attributes

- `@ForyStruct` on struct/class models
- `@ForyEnum` on C-style enum models
- `@ForyUnion` and `@ForyCase` on associated-value enum models
- `@ForyField(encoding: ...)` on numeric fields
- `@ListField`, `@ArrayField`, `@SetField`, and `@MapField` for collection field metadata

## `@ForyField(encoding:)`

Use `@ForyField` to override integer encoding strategy.

```swift
@ForyStruct
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

## Nested Collection Field Metadata

Use `@ListField`, `@ArrayField`, `@SetField`, and `@MapField` when a collection field
needs type-specific wire metadata, such as fixed or tagged integer encoding inside a
container. Use `@ArrayField` for dense non-null bool, integer, and floating-point arrays.

```swift
@ForyStruct
struct NestedMetrics: Equatable {
    @ListField(element: .encoding(.fixed))
    var values: [Int32?] = []

    @ArrayField(element: .int32())
    var denseValues: [Int32] = []

    @SetField(element: .encoding(.fixed))
    var ids: Set<UInt32?> = []

    @MapField(key: .encoding(.fixed), value: .encoding(.tagged))
    var byId: [Int32: UInt64] = [:]

    @MapField(value: .list(element: .encoding(.fixed)))
    var groups: [String: [Int32?]] = [:]
}
```

Non-null `List` elements with fixed-width signed or unsigned integer metadata are
classified and encoded as the matching Fory primitive packed-array type. `Set`
fields stay classified as Fory sets, including fixed-width integer sets.

When the Swift property type is an alias or otherwise needs a full hint, use
`@ForyField(type:)`:

```swift
typealias MetricsMap = [String: [Int32?]]

@ForyStruct
struct AliasMetrics: Equatable {
    @ForyField(type: .map(
        key: .string,
        value: .list(.int32(nullable: true, encoding: .fixed))
    ))
    var metrics: MetricsMap = [:]
}
```

Union payloads use the same DSL through `@ForyCase(payload:)`:

```swift
@ForyUnion
enum Event {
    @ForyUnknownCase
    case unknown(UnknownCase)

    @ForyCase(id: 0)
    case created(String)

    @ForyCase(id: 1, payload: .uint64(encoding: .fixed))
    case deleted(UInt64)
}
```

Every `@ForyUnion` must declare `@ForyUnknownCase case unknown(UnknownCase)` and
at least one non-`unknown` case. The unknown case is only the Fory-owned
forward-compatibility carrier and cannot be the default value source. It is
omitted from the schema case table because the marker only selects the carrier
and does not add a schema entry. Schema cases use non-negative IDs.

## Model Macro Requirements

### Struct and class fields

- Stored properties must declare explicit types
- Computed properties are ignored
- Static/class properties are ignored

### Class requirement

Classes annotated with `@ForyStruct` must provide a `required init()` for default construction.

```swift
@ForyStruct
final class Node {
    var value: Int32 = 0
    var next: Node? = nil

    required init() {}
}
```

## Dynamic Any Fields in Macro Types

Fory model macros support dynamic fields and nested containers:

- `Any`, `AnyObject`, `any Serializer`
- `AnyHashable`
- `[Any]`
- `[String: Any]`
- `[Int32: Any]`
- `[AnyHashable: Any]`

Current limitations:

- `Dictionary<K, Any>` is only supported when `K` is `String`, `Int32`, or `AnyHashable`
