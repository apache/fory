---
title: Basic Serialization
sidebar_position: 1
id: basic_serialization
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

This page covers object graph serialization and core API usage in Swift.

## Object Graph Serialization

Use `@ForyStruct`, `@ForyEnum`, or `@ForyUnion`, register types, then serialize and deserialize.

```swift
import Foundation
import Fory

@ForyStruct
struct Address: Equatable {
    var street: String = ""
    var zip: Int32 = 0
}

@ForyStruct
struct Person: Equatable {
    var id: Int64 = 0
    var name: String = ""
    var nickname: String? = nil
    var tags: Set<String> = []
    var scores: [Int32] = []
    var addresses: [Address] = []
    var metadata: [Int8: Int32?] = [:]
}

let fory = Fory()
try fory.register(Address.self, id: 100)
try fory.register(Person.self, id: 101)

let person = Person(
    id: 42,
    name: "Alice",
    nickname: nil,
    tags: ["swift", "xlang"],
    scores: [10, 20, 30],
    addresses: [Address(street: "Main", zip: 94107)],
    metadata: [1: 100, 2: nil]
)

let data = try fory.serialize(person)
let decoded: Person = try fory.deserialize(data)
assert(decoded == person)
```

## Working with Existing Buffers

Append serialized bytes to an existing `Data` and deserialize from `ByteBuffer`.

```swift
var output = Data()
try fory.serialize(person, to: &output)

let inputBuffer = ByteBuffer(data: output)
let fromBuffer: Person = try fory.deserialize(from: inputBuffer)
assert(fromBuffer == person)
```

## Selecting a Serializer

A type that implements `Serializer` with `Target == Self` selects itself:

```swift
let data = try fory.serialize(person)
let decoded: Person = try fory.deserialize(data)
```

This implicit selection composes through generated fields and ordinary
optionals, arrays, sets, and dictionaries. It also applies when an application
intentionally gives an external type one retroactive self-target conformance.

When a separate serializer targets the value, select it with `with`:

```swift
try fory.register(UserSerializer.self, id: 200)

let data = try fory.serialize(
    externalUser,
    with: UserSerializer.self
)
let decoded = try fory.deserialize(
    data,
    with: UserSerializer.self
)
```

The same selection works with existing buffers:

```swift
var output = Data()
try fory.serialize(
    externalUser,
    with: UserSerializer.self,
    to: &output
)

let input = ByteBuffer(data: output)
let decoded = try fory.deserialize(
    from: input,
    with: UserSerializer.self
)
```

See [External-Type Serialization](external-types.md) for structural
serializers and recursive carrier roots. See
[Manual Serializers](manual-serializers.md) for serializers implemented
directly by a type, retroactive conformances, and separate manual serializers.

## Built-in Supported Types

### Primitive and scalar

- `Bool`
- `Int8`, `Int16`, `Int32`, `Int64`, `Int`
- `UInt8`, `UInt16`, `UInt32`, `UInt64`, `UInt`
- `Float`, `Double`
- `String`
- `Data`

### Date and time

- `Date`
- `LocalDate`
- `Duration`

Use `Date` for timestamp values and `LocalDate` for day-only dates. `LocalDate`
supports epoch-day and `Date` conversions through `fromEpochDay(_:)`,
`toEpochDay()`, `init(utcDate:)`, and `toUTCDate()`.

### Collections

- Optionals and arrays whose values directly implement `Serializer`
- Sets whose elements directly implement `Serializer` and are `Hashable`
- Dictionaries whose keys and values directly implement `Serializer`, with
  `Hashable` keys

Children that use a separate serializer compose with:

- `OptionalSerializer<S>`
- `ArraySerializer<S>`
- `SetSerializer<S>`
- `DictionarySerializer<KS, VS>`

### Dynamic

- `Any` and `AnyObject`
- `AnyHashable`
- Arbitrary application protocol values
- Supported heterogeneous arrays and dictionaries

`Any` and `AnyObject` roots use direct root APIs. Arbitrary application
protocol roots and dynamic values nested in carriers use explicit `with:`
selection.
See [Polymorphism and Dynamic Types](polymorphism.md).
