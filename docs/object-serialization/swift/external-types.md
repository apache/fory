---
title: External-Type Serialization
sidebar_position: 10
id: external-types
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

External-type serialization lets an application serialize a type owned by
another Swift module without modifying that type.

Use an external structural serializer when the target exposes a schema that
Fory can access and construct directly. Use a
[custom serializer](custom-serializers.md) when the target needs a custom
encoding or has private construction invariants.

An external structural serializer is a separate declaration. Register it, then
select it explicitly at roots, fields, and carrier children.

For one application-owned global implementation, Swift also permits an
external type to conform retroactively to `Serializer` with `Target == Self`.
That form uses ordinary implicit selection everywhere and has process-global
conformance risks. See [Custom Serializers](custom-serializers.md).

## External Structs

Declare a local serializer with the same serialized fields and select the
external target:

```swift
import Fory
import ThirdParty

@ForyStruct(target: ThirdParty.User.self)
struct UserSerializer {
    var name: String
    var age: UInt32
}
```

Register and use the serializer:

```swift
let fory = Fory()
try fory.register(UserSerializer.self, id: 100)

let user = ThirdParty.User(name: "Alice", age: 32)
let bytes = try fory.serialize(user, with: UserSerializer.self)
let decoded = try fory.deserialize(bytes, with: UserSerializer.self)
```

The target struct must expose readable properties and an accessible initializer
whose labels and value types match the serializer declaration. A public
struct's synthesized memberwise initializer is not automatically public.

## External Classes

Use a class serializer declaration for a class target:

```swift
@ForyStruct(target: ThirdParty.Node.self)
final class NodeSerializer {
    var value: Int32 = 0

    @ForyField(with: OptionalSerializer<NodeSerializer>.self)
    var next: ThirdParty.Node? = nil
}
```

The target class must have an accessible zero-argument initializer and writable
serialized properties. These requirements allow Fory to preserve shared and
circular references.

Swift budgets only fields listed in this declaration. Add
`@ForyField(ignore: true)` fields for substantial omitted storage; they count
toward the graph budget but are not serialized.

Use a custom serializer if the class is immutable, requires constructor
arguments, or cannot be safely observed before all fields are assigned.

## External Enums

Use `@ForyEnum(target:)` for an enum with no associated values:

```swift
@ForyEnum(target: ThirdParty.Status.self)
enum StatusSerializer {
    case active
    case disabled
}
```

An enum from another module must be exhaustively switchable. A resilient
non-frozen public enum requires a custom serializer.

## External Unions

Use `@ForyUnion(target:)` for xlang union values:

```swift
@ForyUnion(target: ThirdParty.Command<UnknownCase>.self)
enum CommandSerializer {
    @ForyUnknownCase
    case unknown(UnknownCase)

    @ForyCase(id: 0)
    case rename(String)

    @ForyCase(id: 1, payload: .with(UserSerializer.self))
    case replace(ThirdParty.User)
}
```

The target must expose matching cases and a lossless
`unknown(UnknownCase)` case. A dependency-free third-party module can declare a
generic unknown payload, such as `Command<UnknownPayload>`, and the serializer
can target its `Command<UnknownCase>` specialization. Use a custom serializer
when a third-party union has a different unknown-case representation or cannot
preserve unknown payloads.

A known Swift union case has zero or one associated value. Use an explicit
struct payload when an alternative has multiple logical fields.

## Selecting a Serializer for a Field

Select one exact field node with `@ForyField(with:)`:

```swift
@ForyStruct
struct Account {
    @ForyField(with: UserSerializer.self)
    var owner: ThirdParty.User
}
```

For an optional field, select the optional carrier:

```swift
@ForyField(with: OptionalSerializer<UserSerializer>.self)
var owner: ThirdParty.User?
```

The selected serializer's `Target` must exactly match the declared field type.

## Selecting Serializers Inside Carriers

Use `.with(...)` inside collection field annotations:

```swift
@ForyStruct
struct Directory {
    @ListField(element: .with(UserSerializer.self))
    var users: [ThirdParty.User]

    @SetField(element: .with(KeySerializer.self))
    var keys: Set<ThirdParty.Key>

    @MapField(
        key: .with(KeySerializer.self),
        value: .with(UserSerializer.self)
    )
    var usersByKey: [ThirdParty.Key: ThirdParty.User]

    @MapField(
        value: .list(element: .with(UserSerializer.self))
    )
    var groups: [String: [ThirdParty.User]]
}
```

Whole-carrier selection is equivalent:

```swift
@ForyField(with: ArraySerializer<UserSerializer>.self)
var users: [ThirdParty.User]
```

At one field node, `with` cannot be combined with an encoding or another type
selection. It may be combined with `id`.

## Root Carrier Composition

Swift provides carrier serializers for its supported generic carriers:

| Carrier serializer             | Target                   |
| ------------------------------ | ------------------------ |
| `OptionalSerializer<S>`        | `S.Target?`              |
| `ArraySerializer<S>`           | `[S.Target]`             |
| `SetSerializer<S>`             | `Set<S.Target>`          |
| `DictionarySerializer<KS, VS>` | `[KS.Target: VS.Target]` |

Carrier serializers compose recursively:

```swift
typealias DirectorySerializer = DictionarySerializer<
    String,
    ArraySerializer<OptionalSerializer<UserSerializer>>
>

let bytes = try fory.serialize(
    directory,
    with: DirectorySerializer.self
)

let decoded = try fory.deserialize(
    bytes,
    with: DirectorySerializer.self
)
```

`SetSerializer` requires a hashable target element.
`DictionarySerializer` requires a hashable target key.

Carrier serializers use the same optional, array, set, and dictionary
encodings as ordinary Swift values.

## Buffer APIs

The explicit serializer selection is available for all typed root forms:

```swift
var output = Data()
try fory.serialize(user, with: UserSerializer.self, to: &output)

let input = ByteBuffer(data: output)
let decoded = try fory.deserialize(
    from: input,
    with: UserSerializer.self
)
```

The `with` label always selects a serializer.

## Dynamic Values

Registered external targets work through dynamic `Any`, `AnyObject`, and
application protocol values:

```swift
protocol Animal {
    var name: String { get }
}

let animal: any Animal = cat
let bytes = try fory.serialize(
    animal,
    with: DynamicSerializer<any Animal>.self
)
let decoded = try fory.deserialize(
    bytes,
    with: DynamicSerializer<any Animal>.self
)
```

Register each concrete target through its ordinary, external structural, or
custom serializer.

For a root carrier containing protocol values, compose
`DynamicSerializer` explicitly:

```swift
typealias AnimalArraySerializer =
    ArraySerializer<DynamicSerializer<any Animal>>

let bytes = try fory.serialize(
    animals,
    with: AnimalArraySerializer.self
)
```

## Type Aliases

Serializer aliases and root carrier aliases are supported.

When a field type alias hides a collection shape, supply the full recursive
field hint:

```swift
typealias Users = [ThirdParty.User]

@ForyField(type: .list(element: .with(UserSerializer.self)))
var users: Users
```
