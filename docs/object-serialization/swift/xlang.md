---
title: Xlang Serialization
sidebar_position: 1
id: xlang
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

Fory Swift can exchange payloads with other Fory implementations using the xlang protocol.

## Recommended Xlang Configuration

```swift
let fory = Fory()
```

## Register Types with Shared Identity

### ID-based registration

```swift
@ForyStruct
struct Order {
    var id: Int64 = 0
    var amount: Double = 0
}

let fory = Fory()
try fory.register(Order.self, id: 100)
```

### Name-based registration

```swift
try fory.register(Order.self, name: "com.example.Order")
```

## Xlang Rules

- Keep type registration mapping consistent across languages
- Keep compatible mode enabled when independently evolving schemas. Swift enables it by default.
- Register all user-defined concrete targets used by dynamic fields and
  application protocol values
- Use an external structural serializer, a separate custom serializer, or one
  intentional retroactive self-target conformance for a type owned by another
  module

## Lists and Dense Arrays

Swift `Array<T>` fields map to Fory `list<T>` unless field metadata explicitly
requests dense `array<T>`. Use `array<T>` only for one-dimensional bool or
numeric data.

| Fory schema       | Swift field metadata sketch                              |
| ----------------- | -------------------------------------------------------- |
| `list<int32>`     | `@ListField(element: .int32()) var ids: [Int32]`         |
| `array<bool>`     | `@ArrayField(element: .bool) var flags: [Bool]`          |
| `array<int8>`     | `@ArrayField(element: .int8) var values: [Int8]`         |
| `array<int16>`    | `@ArrayField(element: .int16) var values: [Int16]`       |
| `array<int32>`    | `@ArrayField(element: .int32()) var values: [Int32]`     |
| `array<int64>`    | `@ArrayField(element: .int64()) var values: [Int64]`     |
| `array<uint8>`    | `@ArrayField(element: .uint8) var values: [UInt8]`       |
| `array<uint16>`   | `@ArrayField(element: .uint16) var values: [UInt16]`     |
| `array<uint32>`   | `@ArrayField(element: .uint32()) var values: [UInt32]`   |
| `array<uint64>`   | `@ArrayField(element: .uint64()) var values: [UInt64]`   |
| `array<float16>`  | `@ArrayField(element: .float16) var values: [Float16]`   |
| `array<bfloat16>` | `@ArrayField(element: .bfloat16) var values: [BFloat16]` |
| `array<float32>`  | `@ArrayField(element: .float32) var values: [Float]`     |
| `array<float64>`  | `@ArrayField(element: .float64) var values: [Double]`    |

An array that uses a separate element serializer still uses normal list
encoding. Use `@ArrayField` only for supported dense bool or numeric arrays.

## External Targets

External structural serializers produce the same xlang STRUCT, ENUM, or UNION
schema and value bytes as an equivalent ordinary Swift model:

```swift
@ForyStruct(target: ThirdParty.Order.self)
struct OrderSerializer {
    var id: Int64
    var amount: Double
}

try fory.register(OrderSerializer.self, id: 100)
```

Use `.with(...)` in field metadata and `with:` at a root. See
[External-Type Serialization](external-types.md).

That explicit selection is required because the structural serializer is a
separate declaration. An external type with one intentional retroactive
`Target == Self` conformance instead uses ordinary roots, fields, and carriers.

Swift has no native serialization mode. A known `@ForyUnion` case has zero or
one associated value. Use a struct payload for a union alternative with
multiple logical fields.

## Swift IDL Workflow

Generate Swift models directly from Fory IDL/Proto/FBS inputs:

```bash
foryc schema.fdl --swift_out ./Sources/Generated
```

Generated Swift code includes:

- `@ForyStruct`, `@ForyEnum`, `@ForyUnion`, and field/case metadata
- Tagged union enums (associated-value enum cases)
- `ForyModule.install(_:)` helpers with transitive import installation
- `toBytes` / `fromBytes` helpers on generated types

Install the generated module before xlang serialization:

```swift
let fory = Fory(ref: true)
try Addressbook.ForyModule.install(fory)

let payload = try fory.serialize(book)
let decoded: Addressbook.AddressBook = try fory.deserialize(payload)
```

### Run Swift IDL Integration Tests

```bash
cd integration_tests/idl_tests
./run_swift_tests.sh
```

This runs Swift roundtrip matrix tests and Java peer roundtrip checks (`IDL_PEER_LANG=swift`).

## Debugging Xlang Tests

Enable debug output when running xlang tests:

```bash
ENABLE_FORY_DEBUG_OUTPUT=1 FORY_SWIFT_JAVA_CI=1 mvn -T16 test -Dtest=org.apache.fory.xlang.SwiftXlangTest
```

## First round trip

```swift
import Fory

@ForyStruct
struct Person: Equatable {
    var name: String = ""
    var age: Int32 = 0
}

let fory = Fory()
fory.register(Person.self, id: 1)

let person = Person(name: "chaokunyang", age: 28)
let data = try fory.serialize(person)
let result: Person = try fory.deserialize(data)

print("\(result.name) \(result.age)")
```

For more cross-language rules and examples, see:

- [Cross-Language Serialization Guide](../xlang/index.md)
- [Java Guide](../java/index.md)
- [Python Guide](../python/index.md)
- [Dart Guide](../dart/index.md)
- [Go Guide](../go/index.md)
- [Rust Guide](../rust/index.md)
- [C++ Guide](../cpp/index.md)
- [C# Guide](../csharp/index.md)
- [Swift Guide](../swift/index.md)
