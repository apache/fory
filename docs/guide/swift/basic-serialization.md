---
title: Basic Serialization
sidebar_position: 2
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

Use `@ForyObject` on structs/classes/enums, register types, then serialize and deserialize.

```swift
import Foundation
import Fory

@ForyObject
struct Address: Equatable {
    var street: String = ""
    var zip: Int32 = 0
}

@ForyObject
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
fory.register(Address.self, id: 100)
fory.register(Person.self, id: 101)

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
- `ForyDate`
- `ForyTimestamp`

### Collections

- `[T]` where `T: Serializer`
- `Set<T>` where `T: Serializer & Hashable`
- `[K: V]` where `K: Serializer & Hashable`, `V: Serializer`
- Optional variants (`T?`)

### Dynamic

- `Any`
- `AnyObject`
- `any Serializer`
- `AnyHashable`
- `[Any]`
- `[String: Any]`
- `[Int32: Any]`
- `[AnyHashable: Any]`
