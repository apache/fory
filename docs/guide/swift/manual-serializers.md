---
title: Manual Serializers
sidebar_position: 10
id: manual_serializers
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

Use a manual serializer when a target needs a custom encoding or cannot meet
the direct-access requirements of an
[external structural serializer](external-types.md).

A manual serializer is not limited to external types:

- A target that itself conforms to `Serializer` with `Target == Self` selects
  that implementation implicitly everywhere.
- A separate serializer whose `Target` is another type must be selected
  explicitly everywhere it is needed.

The same selection rules apply to roots, generated fields, optionals, arrays,
sets, and dictionaries. Register a separate serializer and select it explicitly
where it is used.

## When to Use a Manual Serializer

- The target has private or immutable state.
- The target must enforce construction invariants.
- The target needs a specialized compact encoding.
- The target needs custom validation or construction logic.
- An external enum is not exhaustively switchable.
- An external union cannot represent `UnknownCase`.

## User-Owned Targets

When you own the target, implement `Serializer` on the target itself and set
`Target` to the same type:

```swift
import Fory

struct AccountID: Serializer, Equatable {
    typealias Target = AccountID

    let rawValue: UInt64

    static var staticTypeId: TypeId {
        .ext
    }

    static func writeData(
        _ value: AccountID,
        _ context: WriteContext
    ) throws {
        try UInt64.writeData(value.rawValue, context)
    }

    static func readData(
        _ context: ReadContext
    ) throws -> AccountID {
        AccountID(rawValue: try UInt64.readData(context))
    }
}
```

Register and use the type through the ordinary root APIs:

```swift
let fory = Fory()
try fory.register(AccountID.self, id: 300)

let input = AccountID(rawValue: 42)
let data = try fory.serialize(input)
let output: AccountID = try fory.deserialize(data)

assert(input == output)
```

No `with:` argument is needed because `AccountID.Target == AccountID`.

## One Global Serializer for an External Type

Swift permits an application to make an external type implement `Serializer`
through a retroactive conformance:

```swift
import Foundation
import Fory

extension UUID: @retroactive Serializer {
    public typealias Target = UUID

    public static var staticTypeId: TypeId {
        .ext
    }

    public static func defaultValue(
        _ context: ReadContext
    ) throws -> UUID {
        _ = context
        return UUID(
            uuidString: "00000000-0000-0000-0000-000000000000"
        )!
    }

    public static func writeData(
        _ value: UUID,
        _ context: WriteContext
    ) throws {
        try String.writeData(value.uuidString, context)
    }

    public static func readData(
        _ context: ReadContext
    ) throws -> UUID {
        let raw = try String.readData(context)
        guard let uuid = UUID(uuidString: raw) else {
            throw ForyError.invalidData("invalid UUID string: \(raw)")
        }
        return uuid
    }
}
```

Register the external type itself:

```swift
try fory.register(UUID.self, id: 300)

let input = UUID()
let data = try fory.serialize(input)
let output: UUID = try fory.deserialize(data)
```

Because `UUID.Target == UUID`, unannotated generated fields and ordinary
carriers also select this implementation:

```swift
@ForyStruct
struct Request {
    var requestID: UUID
}

let input = [UUID(), UUID()]
let data = try fory.serialize(input)
let output: [UUID] = try fory.deserialize(data)
```

A retroactive conformance applies to the entire process. Swift allows only one
`Serializer` conformance for a given type; `@retroactive` acknowledges the
compiler warning but does not make competing conformances safe. Use this form
only when the application intentionally chooses the single global
implementation. Public libraries should generally provide a separate
serializer instead.

## Separate Serializers

Use a separate serializer when a public library must not claim a process-global
conformance or when an application needs multiple or alternative
implementations. The target may be external or user-owned:

```swift
import Foundation
import Fory

public enum UUIDStringSerializer: Serializer {
    public typealias Target = UUID

    public static var staticTypeId: TypeId {
        .ext
    }

    public static func defaultValue(
        _ context: ReadContext
    ) throws -> UUID {
        _ = context
        return UUID(
            uuidString: "00000000-0000-0000-0000-000000000000"
        )!
    }

    public static func writeData(
        _ value: UUID,
        _ context: WriteContext
    ) throws {
        try String.writeData(value.uuidString, context)
    }

    public static func readData(
        _ context: ReadContext
    ) throws -> UUID {
        let raw = try String.readData(context)
        guard let uuid = UUID(uuidString: raw) else {
            throw ForyError.invalidData("invalid UUID string: \(raw)")
        }
        return uuid
    }
}
```

Register the separate serializer and select it explicitly at the root:

```swift
let fory = Fory()
try fory.register(UUIDStringSerializer.self, id: 300)

let input = UUID()
let data = try fory.serialize(input, with: UUIDStringSerializer.self)
let output = try fory.deserialize(data, with: UUIDStringSerializer.self)

assert(input == output)
```

Another declaration, such as `UUIDBytesSerializer`, may target the same type
with a different body. Fory cannot choose between separate serializers
automatically. Select the desired serializer with `with:` at roots and with the
matching field annotation. Register only one implementation for the target on
a given `Fory` instance.

The direct `Any` and `AnyObject` root conveniences remain dynamic operations.
A registered serializer may be used for a concrete value passed as `Any`, but
typed roots and fields still require `with:` for a separate serializer.

## Fields and Carriers

A field whose type directly implements `Serializer` with `Target == Self`
needs no selector:

```swift
@ForyStruct
struct Request {
    var accountID: AccountID
}
```

A separate serializer must be selected explicitly:

```swift
@ForyStruct
struct ExternalRequest {
    @ForyField(with: UUIDStringSerializer.self)
    var requestID: UUID
}
```

Ordinary carriers containing types that directly implement `Serializer` need
no selector. This includes intentional retroactive conformances:

```swift
let accountIDs = [
    AccountID(rawValue: 1),
    AccountID(rawValue: 2),
]
let data = try fory.serialize(accountIDs)
let output: [AccountID] = try fory.deserialize(data)
```

For an element that uses a separate serializer, name it in the carrier
annotation:

```swift
@ListField(element: .with(UUIDStringSerializer.self))
var requestIDs: [UUID]
```

At a root, use the matching carrier serializer:

```swift
let data = try fory.serialize(
    requestIDs,
    with: ArraySerializer<UUIDStringSerializer>.self
)
```

## Manual Serializer Rules

A manual serializer must return `.ext` from `staticTypeId`. The
`.structType`, `.enumType`, and `.typedUnion` values are reserved for
`@ForyStruct`, `@ForyEnum`, and `@ForyUnion`.

`writeData` and `readData` process only the target body. Do not call a root
`serialize` or `deserialize` method from either operation.

## Defaults

Implement `defaultValue(_:)` only when the target has a valid value for a null
or missing field.

## Input Validation

Reject invalid input with an appropriate `ForyError`.

## Manual Class Serializers

For a cyclic class, override the complete-value `read` operation and use Fory's
reference APIs so repeated references resolve to the same object.
