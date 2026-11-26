---
title: Swift Standard Row Format
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

Apache Fory™ Swift implements the Standard Row Format used by Java, C++, Python, Rust, and Go in
the `Fory` module. It provides a `@ForyRow` macro that generates a struct encoder, read-only views
for random field access, and the cross-language schema encoding. Add the `Fory` package as described
in the [Swift guide](../object-serialization/swift/index.md).

## Overview

Use Row Format when readers need selected fields or collection elements rather than a fully
reconstructed value. Readers are views over the encoded bytes: reading one field costs a bitmap
test and a slot lookup, and other fields are never touched.

Row Format is a trusted in-memory format. Decode only bytes produced by a Fory row writer for the
same schema, from a source you trust. Malformed bytes surface as thrown errors, but the format does
not defend against hostile input the way object serialization does.

## When to Use Row Format

- Analytics workloads with selective field access
- Large datasets where only a subset of fields is needed
- Memory-mapped or shared data read by several languages
- High-throughput pipelines that exchange Standard Row bytes with Java, C++, Python, Rust, or Go

## Basic Usage

```swift
import Fory

@ForyRow
struct UserProfile {
    var id: Int64
    var username: String
    var email: String?
    var scores: [Int32]
    var preferences: [String: String]
    var isActive: Bool
}

let encoder = RowEncoder<UserProfile>()
let profile = UserProfile(
    id: 12345,
    username: "alice",
    email: "alice@example.com",
    scores: [95, 87, 92, 88],
    preferences: ["theme": "dark", "language": "en"],
    isActive: true
)

let rowBytes = try encoder.toRow(profile)

// Random access without decoding the whole struct.
let schema = encoder.schema
let row = try Row(schema: schema, bytes: Array(rowBytes))
print(try row.string(at: schema.fieldIndex(of: "username")!) ?? "")           // alice
print(try row.array(at: schema.fieldIndex(of: "scores")!)?.int32(at: 1) ?? 0) // 87
print(row.isNull(at: schema.fieldIndex(of: "email")!))                        // false

// Full decode when the whole value is needed.
let decoded = try encoder.fromRow(rowBytes)
print(decoded.preferences["theme"] ?? "") // dark
```

`@ForyRow` builds the schema from the struct's stored properties at compile time.
`RowEncoder<T>` reuses one write buffer; create one encoder per struct type and reuse it.

## Rows and Framed Messages

`toRow` and `fromRow` work with bare row bytes, which is what the other languages' `toRow` and
`BinaryRow.pointTo` exchange. `encode` and `decode` add the framing used by the Java and Python
row encoders: an 8-byte little-endian schema hash followed by the row.

```swift
let framed = try encoder.encode(profile)   // hash + row
let fromFrame = try encoder.decode(framed) // verifies the hash, then decodes
```

The hash is a type-shape fingerprint: it covers the field types and nothing else, so `decode`
rejects a writer whose field types differ but cannot detect renamed fields, changed nullability,
or reordered fields of the same type. Share the schema bytes (see
[Schema Exchange](#schema-exchange)) when peers must agree on more than the type shape.

`decode` and `fromRow` throw `ForyError` for truncated or inconsistent bytes. Decoded values never
refer to the input, so the input can be reused immediately.

## Zero-Copy Reading

`Row(schema:bytes:)` creates a view over a row. `row(at:)`, `array(at:)`, and `map(at:)` return
views over the nested bytes without copying them. `string(at:)` and `binary(at:)` copy the value
out. A view holds its own reference to the byte array, so changing your array afterwards does not
change what the view reads.

```swift
let scores = try row.array(at: schema.fieldIndex(of: "scores")!)!
for index in 0..<scores.elementCount {
    if let score = scores.int32(at: index) {
        print(score)
    }
}
let preferences = try row.map(at: schema.fieldIndex(of: "preferences")!)!
for index in 0..<preferences.count {
    print(try preferences.keys.string(at: index) ?? "", try preferences.values.string(at: index) ?? "")
}
```

Every getter returns `nil` for a null field or element. Getters for strings, binary values, and
nested values also throw when the stored offset or size is out of range. An index outside the
row's fields or the array's elements stops the program, as an array subscript does, and so does
calling `row(at:)`, `array(at:)`, or `map(at:)` on a field of another type. Use the encoder's
decode methods when an error is needed instead.

## Nullability

An optional property is a nullable field: `nil` sets the null bit and decodes back to `nil`.

`String`, `Date`, `LocalDate`, `Duration`, `Data`, arrays, sets, dictionaries, enums, and nested
`@ForyRow` structs are nullable in the schema even when the property is not optional, so that the
schema matches the one Java infers for the same shape. Decoding a null into a non-optional property
of one of these types throws `ForyError.invalidData`. Declare the property optional, such as
`String?` or `Inner?`, when nulls must round-trip, for example when reading rows written by Java
with `null` values.

`Bool`, integers, `Float`, and `Double` are not nullable unless the property is optional. To match
a Java bean, use `Int32` for a Java `int` field and `Int32?` for a Java `Integer` field.

Map values are always nullable and map keys never are, matching the other languages. `[Int32?]`
and `[String: Int32?]` can hold null elements or values; `[Int32]` and `[String: Int32]` cannot.

## Field Order and Names

Fields are sorted by property name and named by its snake_case form: each capital letter becomes
an underscore followed by the lowercase letter, so `isActive` becomes `is_active` and `userID`
becomes `user_i_d`. This matches Java's schema inference, so both languages derive the same schema
from equivalent definitions.

Every stored property is a field. Computed, `static`, and `lazy` properties are skipped. Changing
a property name or type changes the schema; coordinate such changes across all producers and
consumers.

## Struct Requirements

`@ForyRow` applies to non-generic structs. Each stored property needs an explicit type annotation.
Properties may be `private`.

Decoding calls the struct's memberwise initializer. Declare any custom initializer in an
extension, because an initializer inside the struct body removes the memberwise one. A `let`
property with a default value cannot be set by that initializer, so `@ForyRow` reports it as an
error; use `var` instead.

The macro reports these cases as compile-time errors on the property: unsigned integer types,
nested optionals such as `Int??`, implicitly unwrapped optionals, tuples, property wrappers and
other attributes, a property whose type is the struct itself, optional map keys, and `Date` or
`Duration` map keys.

## Supported Types

| Swift type                                | Standard Row Format encoding     | Nullable |
| ----------------------------------------- | -------------------------------- | -------- |
| `Bool`, `Int8`, `Int16`, `Int32`, `Int64` | Fixed-width scalar               | No       |
| `Int`                                     | Fixed-width int64                | No       |
| `Float`, `Double`                         | Fixed-width IEEE 754 scalar      | No       |
| `LocalDate`                               | Fixed-width date32 in epoch days | Yes      |
| `Date`                                    | Fixed-width epoch microseconds   | Yes      |
| `Duration`                                | Fixed-width microseconds         | Yes      |
| `String`                                  | Variable-width UTF-8             | Yes      |
| `Data`                                    | Standard array of int8           | Yes      |
| `[T]` for supported element types         | Standard array                   | Yes      |
| `Set<T>`                                  | Standard array                   | Yes      |
| `[K: V]`                                  | Standard map                     | Yes      |
| `enum E: String` with `RowCodable`        | Variable-width UTF-8 raw value   | Yes      |
| `@ForyRow` struct                         | Nested Standard Row              | Yes      |
| `T?` for any supported `T`                | Same encoding as `T`             | Yes      |

`Data` matches Java's `byte[]`, which Java also infers as a list of int8, and encodes the same as
`[Int8]`. The row format's binary type is available only to hand-built schemas through
`RowWriter.writeBytes` and `Row.binary(at:)`.

`Date` is rounded to the nearest microsecond. Dates between roughly the years 1729 and 2273 come
back exactly to the microsecond; further away, a `Date` cannot hold every microsecond. `Duration`
is truncated toward zero to whole microseconds. Encoding throws when a `Date` or `Duration` does
not fit in a 64-bit number of microseconds, or a `LocalDate` does not fit in 32-bit epoch days.
Decoding an `Int` throws if the stored value does not fit the platform's `Int`.

A `Set` is stored as a list, so element order is not kept. An enum stores its raw value; opt in
with an empty `extension MyEnum: RowCodable {}`. Decoding a raw value that names no case throws.

Dictionaries and sets have no fixed order, so the bytes of a row that holds one can differ between
runs of a program, while the decoded values are always equal. Set the environment variable
`SWIFT_DETERMINISTIC_HASHING=1` when tests need the same bytes on every run.

Map keys must be `Hashable` and are never null. `Date` and `Duration` keys are rejected because
they are rounded when written, so two different keys could decode as one.

Unsupported: unsigned integers, tuples, nested optionals, classes, generic structs, recursive
types, `float16`, and `decimal`.

## Schema Exchange

`toBytes()` and `init(bytes:)` implement the cross-language schema encoding shared with Java's
`SchemaEncoder`, Python's `Schema.to_bytes`/`from_bytes`, and Go's
`SchemaToBytes`/`SchemaFromBytes`. `hash` computes the same type-shape hash used by `encode`.

```swift
let schemaBytes = try encoder.schema.toBytes()
let received = try RowSchema(bytes: schemaBytes)
print(received == encoder.schema)              // true
print(received.hash == encoder.schemaHash)     // true
```

A Java bean and a Swift struct with equivalent fields produce identical schema bytes when their
nullability matches: use optional properties for Java boxed types (`Integer`, `String` in lists)
and non-optional properties for Java primitives.

## Writing Rows by Hand

`RowWriter`, `RowArrayWriter`, and `RowMapWriter` write rows for a schema you construct yourself,
without a Swift struct. They share one `ByteBuffer`; a nested value is written at the end of the
buffer and then attached to its parent slot with `setOffsetAndSize`.

```swift
let item = RowField(name: "item", dataType: .string, nullable: true)
let schema = RowSchema([
    RowField(name: "id", dataType: .int64, nullable: false),
    RowField(name: "tags", dataType: .list(item), nullable: true)
])
var writer = RowWriter(schema: schema)
writer.reset()
writer.writeInt64(7, at: 0)

var tags = RowArrayWriter(element: item, buffer: writer.buffer)
try tags.reset(count: 2)
try tags.writeString("swift", at: 0)
tags.setNullAt(1)
try writer.setOffsetAndSize(at: 1, absoluteStart: tags.base, size: tags.size)

let rowBytes = writer.toBytes()
```

For a map, call `reset()` on a `RowMapWriter`, write the keys with one `RowArrayWriter`, call
`finishKeys()`, write the values with a second `RowArrayWriter`, then attach the map to its parent
slot with `setOffsetAndSize`, passing the map writer's `base` and `size`. Each `reset()` starts a
new row at the end of the buffer; call `writer.buffer.clear()` first to reuse the buffer for a new
top-level row.

## Thread Safety

A `RowEncoder` owns a reusable write buffer and is not safe for concurrent use; create one encoder
per thread or task, or guard it with a lock. Writers follow the same rule. `Row`, `RowArray`, and
`RowMap` views only read.

## Related Topics

- [Basic Serialization](../object-serialization/swift/basic-serialization.md) - Object graph serialization
- [Standard Row Format](index.md#standard-row) - Shared layout for Java, Python, C++, Rust, Go, and Swift
- [Row Format Specification](../specification/row_format_spec.md) - Protocol details
