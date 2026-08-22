---
title: Go Standard Row Format
sidebar_position: 7
id: go
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

Apache Fory™ Go implements the Standard Row Format used by Java, C++, Python, and Rust in the
`github.com/apache/fory/go/fory/row` package. It provides a reflection-based struct encoder,
zero-copy readers for random field access, and the cross-language schema encoding.

## Overview

Use Row Format when readers need selected fields or collection elements rather than a fully
reconstructed value. Readers are views over the encoded bytes: reading one field costs a bitmap
test and a slot lookup, and other fields are never touched.

Row Format is a trusted in-memory format. Decode only bytes produced by a Fory row writer for the
same schema, from a source you trust. Malformed bytes surface as errors from the encoder's decode
methods, but the format does not defend against hostile input the way object serialization does.

## When to Use Row Format

- Analytics workloads with selective field access
- Large datasets where only a subset of fields is needed
- Memory-mapped or shared data read by several languages
- High-throughput pipelines that exchange Standard Row bytes with Java, C++, Python, or Rust

## Basic Usage

```go
package main

import (
    "fmt"

    "github.com/apache/fory/go/fory/row"
)

type UserProfile struct {
    Id          int64
    Username    string
    Email       *string
    Scores      []int32
    Preferences map[string]string
    IsActive    bool
}

func main() {
    encoder, err := row.NewEncoder[UserProfile]()
    if err != nil {
        panic(err)
    }

    email := "alice@example.com"
    profile := UserProfile{
        Id:          12345,
        Username:    "alice",
        Email:       &email,
        Scores:      []int32{95, 87, 92, 88},
        Preferences: map[string]string{"theme": "dark", "language": "en"},
        IsActive:    true,
    }

    rowBytes, err := encoder.ToRow(&profile)
    if err != nil {
        panic(err)
    }

    // Random access without decoding the whole struct.
    schema := encoder.Schema()
    r := row.NewRow(schema, rowBytes)
    fmt.Println(r.String(schema.FieldIndex("username"))) // alice
    fmt.Println(r.Array(schema.FieldIndex("scores")).Int32(1)) // 87
    fmt.Println(r.IsNullAt(schema.FieldIndex("email")))        // false

    // Full decode when the whole value is needed.
    decoded, err := encoder.FromRow(rowBytes)
    if err != nil {
        panic(err)
    }
    fmt.Println(decoded.Preferences["theme"]) // dark
}
```

`NewEncoder[T]` infers the schema from the struct type once and compiles the conversion for it.
Creating an encoder is comparatively expensive; create one per struct type and reuse it.

## Rows and Framed Messages

`ToRow` and `FromRow` work with bare row bytes, which is what the other languages' `toRow` and
`BinaryRow.pointTo` exchange. `Encode` and `Decode` add the framing used by the Java and Python
row encoders: an 8-byte little-endian schema hash followed by the row.

```go
framed, err := encoder.Encode(&profile)   // hash + row
decoded, err := encoder.Decode(framed)    // verifies the hash, then decodes
```

The hash is a type-shape fingerprint: it folds the recursive field type ids and nothing else, so
`Decode` rejects a writer whose field types differ but cannot detect renamed fields, changed
nullability, or reordered fields of the same type. Share the schema bytes (see
[Schema Exchange](#schema-exchange)) when peers must agree on more than the type shape.

`Decode` and `FromRow` return errors for truncated or inconsistent bytes; they never panic. Decoded
values never alias the input, so the input buffer can be reused immediately.

## Zero-Copy Reading

`row.NewRow(schema, bytes)` creates a view over a row. `Struct`, `Array`, and `Map` return views
over the nested bytes, and `Binary` returns a sub-slice of the input. These views stay valid only
while the underlying bytes are alive and unmodified. `String` copies, because a Go string must not
observe later changes to the buffer.

```go
r := row.NewRow(schema, rowBytes)
tags := r.Array(schema.FieldIndex("tags"))
for i := 0; i < tags.NumElements(); i++ {
    if !tags.IsNullAt(i) {
        fmt.Println(tags.String(i))
    }
}
attrs := r.Map(schema.FieldIndex("attrs"))
fmt.Println(attrs.Keys().String(0), attrs.Values().Int32(0))
```

Fixed-width getters return the zero value for null fields; use `IsNullAt` to distinguish null from
zero. Variable-width getters return `nil` (or `""`) for null. Out-of-range indexes panic, as does
malformed data read through a raw view; use the encoder's decode methods when an error is needed.

## Nullability

A Go pointer field is a nullable field: `nil` writes the null bit and decodes back to `nil`.
Slices, maps, and `[]byte` are nullable as well and distinguish `nil` from empty.

Strings, nested value structs, `fory.Date`, `time.Time`, and `time.Duration` are also nullable in
the schema because their Java carriers are objects, but the Go value cannot hold `nil`. Decoding a
null into one of these fields is an error. Use a pointer carrier (`*string`, `*time.Time`, `*Inner`)
when nulls must round-trip, for example when reading rows written by Java with `null` values.

Map values are always nullable and map keys never are, matching the other languages. A `[]*int32`
element or `map[string]*int32` value carries a null element; `[]int32` and `map[string]int32` do
not.

## Field Order and Names

Fields are sorted by their lowerCamel name and named by its snake_case form (`UserName` becomes
`user_name`), matching Java's schema inference so both languages derive the same schema from
equivalent struct definitions. Unexported fields are skipped. The `fory` struct tag uses the same
grammar as object serialization: `fory:"-"`, `fory:"ignore"`, and `fory:"ignore=true"` skip a
field; other keys are accepted and ignored by Row Format.

Changing a field name or type changes the schema. Coordinate such changes across all producers and
consumers.

## Supported Types

| Go type                                       | Standard Row Format encoding     | Nullable |
| --------------------------------------------- | -------------------------------- | -------- |
| `bool`, `int8`, `int16`, `int32`, `int64`     | Fixed-width scalar               | No       |
| `int`                                         | Fixed-width int64                | No       |
| `float32`, `float64`                          | Fixed-width IEEE 754 scalar      | No       |
| `fory.Date`                                   | Fixed-width date32 in epoch days | Yes      |
| `time.Time`                                   | Fixed-width epoch microseconds   | Yes      |
| `time.Duration`                               | Fixed-width microseconds         | Yes      |
| `string`                                      | Variable-width UTF-8             | Yes      |
| `[]byte`                                      | Standard array of int8           | Yes      |
| `[]T` for supported element types             | Standard array                   | Yes      |
| `map[K]V`                                     | Standard map                     | Yes      |
| Nested struct                                 | Nested Standard Row              | Yes      |
| `*T` for any supported non-slice, non-map `T` | Same encoding as `T`             | Yes      |

`[]byte` matches Java's `byte[]`, which Java also infers as a list of int8. The row format's
binary type is available only to hand-built schemas through `RowWriter.WriteBytes` and
`Row.Binary`.

Strings must be valid UTF-8. Timestamps must fit in an int64 number of microseconds. Map keys must
be scalars, strings, or value structs whose exported, non-ignored fields consist of such types, so
that the encoded key determines Go equality; `time.Time` and pointers are not valid keys.

Unsupported: unsigned integers, fixed-size arrays, nested pointers, pointers to slices or maps,
interfaces, channels, functions, recursive types, `float16`, and `decimal`.

## Schema Exchange

`SchemaToBytes` and `SchemaFromBytes` implement the cross-language schema encoding shared with
Java's `SchemaEncoder` and Python's `Schema.to_bytes`/`from_bytes`. `ComputeSchemaHash` computes
the same type-shape hash used by `Encode`.

```go
schemaBytes, err := row.SchemaToBytes(encoder.Schema())
schema, err := row.SchemaFromBytes(schemaBytes)
fmt.Println(schema.Equal(encoder.Schema())) // true
```

A Java bean and a Go struct with equivalent fields produce identical schema bytes when their
nullability matches: use pointer fields for Java boxed types (`Integer`, `String` in lists) and
value fields for Java primitives.

## Writing Rows by Hand

`RowWriter`, `ArrayWriter`, and `MapWriter` write rows for a schema you construct yourself, without
a Go struct. They share one `fory.ByteBuffer`; a nested value is written at the buffer's current
position and then attached to its parent slot with `SetOffsetAndSize`.

```go
schema := row.NewSchema([]row.Field{
    row.NewField("id", row.Int64Type{}, false),
    row.NewField("tags", row.List(row.StringType{}), true),
})
w := row.NewRowWriter(schema)
w.Reset()
w.WriteInt64(0, 7)

tags := row.NewArrayWriter(row.List(row.StringType{}).Elem, w.Buffer())
start := w.Buffer().WriterIndex()
if err := tags.Reset(2); err != nil {
    panic(err)
}
if err := tags.WriteString(0, "go"); err != nil {
    panic(err)
}
tags.SetNullAt(1)
if err := w.SetOffsetAndSize(1, start, w.Buffer().WriterIndex()-start); err != nil {
    panic(err)
}
rowBytes := w.ToBytes() // valid until the buffer is written to again
```

Call `Reset` before each row; to reuse a writer for a new top-level row, set the buffer's writer
index back to zero first.

## Thread Safety

An `Encoder` owns a reusable write buffer and is not safe for concurrent use; create one encoder
per goroutine or guard it with a mutex. Writers share the same rule. `Row`, `ArrayData`, and
`MapData` views only read, so one view can be shared by concurrent readers as long as the
underlying bytes are not modified.

## Related Topics

- [Basic Serialization](../object-serialization/go/basic-serialization.md) - Object graph serialization
- [Standard Row Format](index.md#standard-row) - Shared layout for Java, Python, C++, Rust, and Go
- [Row Format Specification](../specification/row_format_spec.md) - Protocol details
