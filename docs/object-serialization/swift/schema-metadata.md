---
title: Schema Metadata
sidebar_position: 7
id: schema-metadata
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

- `@ForyStruct` on struct/class models and external structural serializers
- `@ForyEnum` on C-style enum models and external enum serializers
- `@ForyUnion` and `@ForyCase` on associated-value enum models and external union serializers
- `@ForyField(encoding: ...)` on numeric fields
- `@ForyField(with: ...)` for exact serializer selection
- `@ListField`, `@ArrayField`, `@SetField`, and `@MapField` for collection field metadata

## External Targets

Use `target:` when the serializer declaration and serialized value type are
different:

```swift
@ForyStruct(target: ThirdParty.User.self)
struct UserSerializer {
    var name: String
    var age: UInt32
}
```

Equivalent target arguments are available on `@ForyEnum` and `@ForyUnion`.
See [External-Type Serialization](external-types.md) for target access and
construction requirements.

## `@ForyField(with:)`

An unannotated field implicitly selects its declared type when that type
implements `Serializer` with `Target == Self`, including an intentional
retroactive external conformance. Use `with` to select a separate serializer
for one exact field node:

```swift
@ForyStruct
struct Account {
    @ForyField(with: UserSerializer.self)
    var owner: ThirdParty.User
}
```

The serializer target must exactly match the declared field type. Optional and
whole-carrier nodes select their carrier serializer explicitly:

```swift
@ForyField(with: OptionalSerializer<UserSerializer>.self)
var owner: ThirdParty.User?

@ForyField(with: ArraySerializer<UserSerializer>.self)
var users: [ThirdParty.User]
```

`with` may be combined with `id`, but not with `encoding`, `type`, or another
type selection at the same node.

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

    @ListField(element: .with(UserSerializer.self))
    var users: [ThirdParty.User] = []

    @MapField(
        key: .with(KeySerializer.self),
        value: .list(element: .with(UserSerializer.self))
    )
    var usersByKey: [ThirdParty.Key: [ThirdParty.User]] = [:]
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

External payloads select a serializer with `.with(...)`:

```swift
@ForyCase(id: 2, payload: .with(UserSerializer.self))
case user(ThirdParty.User)
```

Every `@ForyUnion` must declare `@ForyUnknownCase case unknown(UnknownCase)` and
at least one non-`unknown` case. The unknown case is only the Fory-owned
forward-compatibility carrier and cannot be the default value source. It is
omitted from the schema case table because the marker only selects the carrier
and does not add a schema entry. Schema cases use non-negative IDs.

A known union case has zero or one associated value. Use a struct payload when
one alternative contains multiple logical fields.

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

An external class serializer uses a class declaration. Its target must expose
an accessible zero-argument initializer and writable matching fields so Fory
can preserve shared and circular references.

## Dynamic Any Fields in Macro Types

Fory model macros support dynamic fields and nested containers:

- `Any`, `AnyObject`, and arbitrary `any Protocol` existentials
- `AnyHashable`
- `[Any]`
- `[String: Any]`
- `[Int32: Any]`
- `[AnyHashable: Any]`

Other dictionary key types work when the key is `Hashable` and implements
`Serializer` with `Target == Self`. For an external key using a separate
serializer, select it with `@MapField(key: .with(KeySerializer.self))`.
