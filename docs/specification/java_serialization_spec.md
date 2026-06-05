---
title: Java Serialization Format
sidebar_position: 1
id: java_serialization_spec
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

## Scope

This document specifies the Apache Fory Java native binary format: the format
used by Java when `withXlang(false)` is configured. The format is optimized for
Java object graphs, Java collection implementations, Java primitive arrays,
Java class registration, Java serialization hooks, and optional schema
evolution.

Java native mode and xlang mode share low-level building blocks such as
little-endian numeric payloads, variable-length integer encodings, reference
flags, meta string encodings, and TypeDef/ClassDef concepts. They are different
wire formats. In Java native mode, only the scalar type IDs from `BOOL` through
`STRING` are shared with xlang. Collection, map, struct, array, enum, and
native Java implementation type IDs are Java native IDs unless this document
explicitly says otherwise.

See [Xlang Serialization Format](xlang_serialization_spec.md) for the
cross-language format.

## Stream Layout

A Java native stream contains one header byte followed by one or more root
objects. Each root object is encoded as a normal object slot:

```text
| header | root_0 | root_1 | ... |

root:
| reference flag | [type metadata] | [value payload] |
```

All multi-byte fixed-width values are little endian. A big-endian Java implementation
must still write and read little-endian payloads.

The stream is stateful. Type metadata, class definitions, and object references
are assigned indexes as they are first encountered and may be referenced later
in the same stream.

## Header

The header is a single byte:

```text
| bits 7..2 reserved | bit 1 out-of-band | bit 0 xlang |
```

- `xlang` must be `0` for Java native mode.
- `out-of-band` is `1` when a `BufferCallback` is configured.
- Reserved bits must be `0`.

Java native mode does not write a language ID after the header.

## Reference Slots

Objects, nullable fields, and reference-tracked fields use the standard Fory
reference slot. The first byte is signed:

| Flag                  | Byte | Payload that follows                                             |
| --------------------- | ---- | ---------------------------------------------------------------- |
| `NULL_FLAG`           | `-3` | No payload. The slot value is `null`.                            |
| `REF_FLAG`            | `-2` | `varuint32` reference ID of an earlier object.                   |
| `NOT_NULL_VALUE_FLAG` | `-1` | Value payload. No reference ID is assigned for this occurrence.  |
| `REF_VALUE_FLAG`      | `0`  | Value payload. Assign the next reference ID before reading data. |

When reference tracking is disabled for a slot, writers use only `NULL_FLAG`
and `NOT_NULL_VALUE_FLAG`.

Primitive field fast paths do not wrap non-null primitive values in a reference
slot. Boxed primitives and other nullable values use the slot selected by field
metadata.

## Type Metadata

Dynamic object slots write type metadata before the value payload. Type metadata
identifies the serializer and, when needed, carries class names or ClassDef
metadata.

```text
| varuint32 type_id | [type-specific metadata] |
```

Registered Java classes, Java native built-ins, and Fory internal serializers
use numeric type IDs. Unregistered classes or classes registered by name carry
name metadata. Schema-evolution classes may carry a ClassDef.

### Native Type ID Ranges

| Range    | Meaning                                                            |
| -------- | ------------------------------------------------------------------ |
| `0`      | `UNKNOWN`, used in metadata for dynamic or object-typed positions. |
| `1..21`  | Shared scalar IDs from `BOOL` through `STRING`.                    |
| `22..63` | Reserved in Java native mode for the xlang internal ID range.      |
| `64..68` | Reserved for future Java native internal IDs.                      |
| `69..98` | Java native built-ins listed below.                                |
| `99+`    | User and Fory class IDs assigned by the Java `ClassResolver`.      |

The shared scalar IDs are:

| ID  | Name            | Java value domain                       |
| --- | --------------- | --------------------------------------- |
| 1   | `BOOL`          | boolean values in xlang metadata        |
| 2   | `INT8`          | signed 8-bit integer metadata           |
| 3   | `INT16`         | signed 16-bit integer metadata          |
| 4   | `INT32`         | fixed-width signed 32-bit metadata      |
| 5   | `VARINT32`      | variable-width signed 32-bit metadata   |
| 6   | `INT64`         | fixed-width signed 64-bit metadata      |
| 7   | `VARINT64`      | variable-width signed 64-bit metadata   |
| 8   | `TAGGED_INT64`  | tagged signed 64-bit metadata           |
| 9   | `UINT8`         | unsigned 8-bit metadata                 |
| 10  | `UINT16`        | unsigned 16-bit metadata                |
| 11  | `UINT32`        | fixed-width unsigned 32-bit metadata    |
| 12  | `VAR_UINT32`    | variable-width unsigned 32-bit metadata |
| 13  | `UINT64`        | fixed-width unsigned 64-bit metadata    |
| 14  | `VAR_UINT64`    | variable-width unsigned 64-bit metadata |
| 15  | `TAGGED_UINT64` | tagged unsigned 64-bit metadata         |
| 16  | `FLOAT8`        | reserved 8-bit float metadata           |
| 17  | `FLOAT16`       | half precision float metadata           |
| 18  | `BFLOAT16`      | bfloat16 metadata                       |
| 19  | `FLOAT32`       | 32-bit floating point metadata          |
| 20  | `FLOAT64`       | 64-bit floating point metadata          |
| 21  | `STRING`        | Java `String`                           |

Java native built-ins start at ID `69`:

| ID  | Name                         | Java type or serializer owner           |
| --- | ---------------------------- | --------------------------------------- |
| 69  | `VOID_ID`                    | `java.lang.Void`                        |
| 70  | `CHAR_ID`                    | `java.lang.Character`                   |
| 71  | `PRIMITIVE_VOID_ID`          | `void`                                  |
| 72  | `PRIMITIVE_BOOL_ID`          | `boolean`                               |
| 73  | `PRIMITIVE_INT8_ID`          | `byte`                                  |
| 74  | `PRIMITIVE_CHAR_ID`          | `char`                                  |
| 75  | `PRIMITIVE_INT16_ID`         | `short`                                 |
| 76  | `PRIMITIVE_INT32_ID`         | `int`                                   |
| 77  | `PRIMITIVE_FLOAT32_ID`       | `float`                                 |
| 78  | `PRIMITIVE_INT64_ID`         | `long`                                  |
| 79  | `PRIMITIVE_FLOAT64_ID`       | `double`                                |
| 80  | `PRIMITIVE_BOOLEAN_ARRAY_ID` | `boolean[]`                             |
| 81  | `PRIMITIVE_BYTE_ARRAY_ID`    | `byte[]`                                |
| 82  | `PRIMITIVE_CHAR_ARRAY_ID`    | `char[]`                                |
| 83  | `PRIMITIVE_SHORT_ARRAY_ID`   | `short[]`                               |
| 84  | `PRIMITIVE_INT_ARRAY_ID`     | `int[]`                                 |
| 85  | `PRIMITIVE_FLOAT_ARRAY_ID`   | `float[]`                               |
| 86  | `PRIMITIVE_LONG_ARRAY_ID`    | `long[]`                                |
| 87  | `PRIMITIVE_DOUBLE_ARRAY_ID`  | `double[]`                              |
| 88  | `STRING_ARRAY_ID`            | `String[]`                              |
| 89  | `OBJECT_ARRAY_ID`            | `Object[]` and object array serializers |
| 90  | `ARRAYLIST_ID`               | `java.util.ArrayList`                   |
| 91  | `HASHMAP_ID`                 | `java.util.HashMap`                     |
| 92  | `HASHSET_ID`                 | `java.util.HashSet`                     |
| 93  | `CLASS_ID`                   | `java.lang.Class`                       |
| 94  | `EMPTY_OBJECT_ID`            | Empty-object serializer                 |
| 95  | `LAMBDA_STUB_ID`             | Lambda replacement type ID              |
| 96  | `JDK_PROXY_STUB_ID`          | JDK proxy replacement type ID           |
| 97  | `REPLACE_STUB_ID`            | `writeReplace`/`readResolve` type ID    |
| 98  | `NONEXISTENT_META_SHARED_ID` | Unknown-class marker type ID            |

### Registered, Named, and Unregistered Classes

Java native mode supports three class identity forms:

- ID registration: the type ID is the registered numeric class ID.
- Name registration: the type metadata carries namespace and type name strings.
- Unregistered class: the type metadata carries the package name as namespace
  and the simple Java class name as type name.

Class registration is the fastest and most compact form. Name-based forms are
used when stable names are required or class registration is disabled.

### Meta Sharing

When meta sharing is enabled, class metadata is written once and referenced by a
stream-local index:

```text
| varuint32 marker | [class definition bytes if new] |

marker = (index << 1) | flag
flag = 0: new definition, class definition bytes follow
flag = 1: reference to an earlier definition
```

Indexes are assigned in first-use order.

## Schema Modes

Java native mode has two object schema modes.

### Same-Schema Mode

Same-schema mode is used when compatible mode is disabled. The writer and
reader must have matching fields and field order. No per-object ClassDef is
required for ordinary registered classes. Field values are written directly in
protocol order.

### Compatible Mode

Compatible mode writes ClassDef metadata for struct-like classes. Readers match
local fields against remote ClassDef fields by identifier, read matching fields,
and skip unknown fields using the remote field type metadata. Compatible mode is
the Java native schema-evolution path.

In compatible mode, a matched field may read between direct top-level scalar
ClassDef schemas when the remote value can be represented by the local scalar
schema without changing the logical value. This is a read adaptation only:
writers keep emitting their local canonical field schema and payload, and
ClassDef metadata, same-schema mode, dynamic value serialization, and
unknown-field skipping continue to treat the original field schemas as distinct.

The rule applies only to the immediate schema of a matched field. It does not
apply to dynamic root values, map keys, map values, collection elements, array
elements, enum values, temporal values, binary values, structs, or nested
generic/container positions.

The scalar domains are Java boolean/boxed boolean, `String`, Java primitive and
boxed numeric scalar fields, Fory scalar annotations whose ClassDef metadata
identifies a narrower numeric wire domain, and `BigDecimal` as the exact decimal
numeric scalar. Java native-only metadata outside the type IDs shared with xlang
must still use the Java ClassDef metadata to identify the scalar domain.
Compatible scalar conversion applies only when both the remote and local
top-level ClassDef field metadata have `trackingRef = false`; if either matched
field has `trackingRef = true`, scalar type changes are schema/type incompatible
during compatible layout construction. Same scalar ClassDef field types with
matching top-level `trackingRef` and null/optional framing are exact same-schema
direct reads, not compatible scalar conversion. Same scalar ClassDef field types
with different top-level `trackingRef` framing are schema/type incompatible
because the wire framing differs. Same scalar ClassDef field types with
different top-level null/optional framing may still use the nullable/optional
composition rule below when both fields have `trackingRef = false`.

Compatible scalar conversion follows the xlang scalar conversion contract:

- `String` to boolean accepts exactly `"0"`, `"1"`, `"false"`, and `"true"`.
  Boolean to `String` produces `"false"` or `"true"`.
- numeric to boolean accepts only exact zero and one. Boolean to numeric
  produces exact zero or one in the local numeric domain.
- numeric to numeric succeeds only when the local numeric domain represents the
  same mathematical value, including range checks, signedness checks, exact
  integer/floating round-trip checks, floating signed-zero preservation, and
  rejection of `NaN` across different floating type IDs.
- `BigDecimal` participates as an exact numeric scalar. Converted decimal values
  use canonical scale: zero and non-zero integers use scale `0`; finite
  fractional values use the smallest non-negative scale that preserves the
  mathematical value and leaves an unscaled value not divisible by `10`.
  Compatible conversion rejects numeric strings longer than `320` bytes before
  arbitrary precision parsing. It also rejects converted decimal values whose
  canonical exponent or scale work exceeds the `256` digit bound before
  constructing large powers of ten or formatting plain decimal text. Same-type
  `BigDecimal` reads preserve the ordinary decimal payload.
- `String` to numeric accepts only the finite compatible numeric literal grammar
  from the xlang serialization spec and then applies the same lossless
  target-domain checks. `NaN`, infinities, whitespace, leading plus signs,
  Unicode decimal digits, underscores, grouping separators, non-decimal radices,
  and type suffixes fail.
- numeric to `String` emits canonical finite numeric text: integers use plain
  decimal text, floating values use exact plain decimal text with a decimal point
  and signed-zero preservation, and `BigDecimal` values use exact plain decimal
  text without exponent notation or insignificant trailing fractional zeros.

Nullable fields, boxed carriers, and primitive defaults compose with scalar
conversion when the matched top-level field schemas have `trackingRef = false`.
Readers first consume the remote null/optional framing described by the remote
ClassDef field metadata. Present values are converted and then assigned or
wrapped into the local carrier. Null or absent remote values use the same
compatible-mode missing/null behavior already defined for the local field.
Reference-tracked scalar conversion is not supported.

Schema pairs outside the scalar conversion matrix remain schema/type
compatibility errors while building the compatible layout. Once a matched field
is accepted as a scalar conversion action, invalid payload values are
deserialization data errors and must be reported as
`org.apache.fory.exception.DeserializationException`, not as schema misses or
registration errors.

## Field Order

Java native object serializers use the same deterministic field-order
categories as the current xlang protocol:

1. Primitive non-nullable numeric and boolean scalar fields.
2. Primitive nullable numeric and boolean scalar fields, including boxed Java
   primitive wrappers.
3. Non-primitive fields.

Primitive groups keep the primitive comparator:

1. Fixed-width primitive encodings before compressed or variable-width
   primitive encodings.
2. Larger primitive width before smaller primitive width.
3. Internal primitive type ID ascending.
4. Field identifier.

Non-primitive fields sort directly by field identifier. Non-primitive type ID,
serializer kind, collection kind, map kind, and Java implementation class do not
participate in field order.

Field identifiers are selected as follows:

- If a field has an explicit non-negative `@ForyField(id = ...)`, that numeric
  ID is the field identifier.
- Otherwise, the Java field name converted to snake_case is the field
  identifier.
- Negative annotation values are not valid field IDs. The annotation default
  value `-1` means no explicit ID and is ignored for identifier selection.

Identifier comparison is:

1. If both fields have explicit IDs, compare IDs numerically.
2. If only one field has an explicit ID, the ID-based field sorts before the
   name-based field.
3. If neither field has an explicit ID, compare snake_case names
   lexicographically.
4. If identifiers are equal, use deterministic tie-breakers such as declaring
   class and original field name. Untagged fields with the same snake_case
   identifier in the same class are invalid. A child field that hides an
   inherited field with the same Java field name keeps only the nearest field in
   xlang TypeDef metadata because the inherited field has no distinct untagged
   identifier.

Generated serializers may keep separate internal descriptor groups for
primitive, collection, map, built-in, and user-defined serializers so they can
emit specialized fast paths. Those internal groups are an implementation detail
and must not change wire field order.

## ClassDef Encoding

Compatible mode and meta sharing encode Java class definitions as TypeDef
records. A TypeDef has an 8-byte header followed by class metadata bytes:

```text
| 8-byte header | [varuint32 extra_size] | class metadata bytes |
```

Header bits:

```text
| 52-bit hash | 3 reserved bits | 1 compress bit | 8 size bits |
```

- `size`: the lower 8 bits. If the value is `0xff`, read `extra_size` as
  `varuint32` and add it to `0xff`.
- `compress`: set when class metadata bytes are compressed by the configured
  meta compressor.
- `reserved`: must be zero.
- `hash`: 52 bits derived from MurmurHash3 x64_128 seed 47 over
  `class_metadata_bytes || header_low12_le`. `header_low12_le` is the low 12
  header bits encoded as two little-endian bytes with the upper four bits of the
  second byte clear. Take lane 0 of the MurmurHash3 result, left-shift it by 12
  with signed 64-bit wraparound, apply signed absolute value, and mask with
  `0xfffffffffffff000`.

### Class Metadata Body

```text
| root_kind_and_layer_count | class_layer_0 | class_layer_1 | ... |

class_layer:
| varuint32 class_header | [registered type IDs or names] | field_info... |
```

`root_kind_and_layer_count` stores the root TypeDef kind in the high four bits
and `(num_layers - 1)` in the low four bits. If the low four bits are `0b1111`,
read an extra `varuint32` and add it to `15`.

Root kind codes:

| Code  | Kind                                         |
| ----- | -------------------------------------------- |
| 0     | `STRUCT`                                     |
| 1     | `COMPATIBLE_STRUCT`                          |
| 2     | `NAMED_STRUCT`                               |
| 3     | `NAMED_COMPATIBLE_STRUCT`                    |
| 4     | `ENUM`                                       |
| 5     | `NAMED_ENUM`                                 |
| 6     | `EXT`                                        |
| 7     | `NAMED_EXT`                                  |
| 8     | `TYPED_UNION`                                |
| 9     | `NAMED_UNION`                                |
| 10-14 | Reserved                                     |
| 15    | Extended-kind escape, rejected until defined |

`class_header = (num_fields << 1) | registered_flag`.

- If `registered_flag == 1`, write the class type ID as one byte. For
  user-registered `ENUM`, `STRUCT`, `COMPATIBLE_STRUCT`, `EXT`, and
  `TYPED_UNION`, write the user type ID as `varuint32`.
- If `registered_flag == 0`, write namespace and type name as meta strings.

Class layers are encoded from parent to leaf. Field lists inside each layer use
the field order defined above.

### Field Info

Each field is encoded as:

```text
| field_header | [extended_name_or_id_size] | [field name bytes] | field_type |
```

`field_header` bits:

| Bits | Meaning                                          |
| ---- | ------------------------------------------------ |
| 0    | `trackingRef`                                    |
| 1    | `nullable`                                       |
| 2..3 | field name encoding                              |
| 4..6 | encoded name length minus one, or compact tag ID |
| 7    | reserved, must be zero                           |

Field name encodings:

| Code | Encoding                             |
| ---- | ------------------------------------ |
| 0    | UTF-8                                |
| 1    | all-to-lower special encoding        |
| 2    | lower/upper/digit special encoding   |
| 3    | tag ID; field name bytes are omitted |

For name encodings, bits `4..6` store `encoded_length - 1` when it is less than
`7`. If the value is `7`, read an extra `varuint32` and add it to `7`.

For tag ID encoding, bits `4..6` store the numeric field ID when it is less than
`7`. If the value is `7`, read an extra `varuint32` and add it to `7`. Field IDs
must be non-negative. Duplicate field IDs in one TypeDef are invalid.

### Field Type

Field types describe how compatible readers read or skip the field payload.
Top-level field types write only the type tag. Nested field types store
`nullable` and `trackingRef` in the low bits:

```text
nested_field_type_header = (type_tag << 2) | (nullable << 1) | trackingRef
```

Type tags:

| Tag | Field type                  | Payload                          |
| --- | --------------------------- | -------------------------------- |
| 0   | Object/dynamic              | none                             |
| 1   | Map                         | key field type, value field type |
| 2   | Collection/List/Set         | element field type               |
| 3   | Java array                  | dimensions, component field type |
| 4   | Enum                        | none                             |
| 5+  | Registered or built-in type | `tag - 5` is the type ID         |

## Meta Strings

Namespaces, type names, and field names use the meta string encodings defined
by the xlang specification. A meta string header stores the byte length and
encoding kind; extended lengths are written as `varuint32`.

Package and namespace names use UTF-8, all-to-lower special encoding, or
lower/upper/digit special encoding. Type names use UTF-8,
lower/upper/digit special encoding, first-to-lower special encoding, or
all-to-lower special encoding. Field names use the field-info encoding table
above.

## Primitive Values

Primitive values are written without type metadata when the field serializer is
known statically:

| Java type | Payload                                                                     |
| --------- | --------------------------------------------------------------------------- |
| `boolean` | one byte: `0` or `1`                                                        |
| `byte`    | one signed byte                                                             |
| `char`    | two-byte UTF-16 code unit, little endian                                    |
| `short`   | two-byte signed integer, little endian                                      |
| `int`     | fixed int32 little endian, or ZigZag varint32 when configured               |
| `long`    | fixed int64 little endian, ZigZag varint64, or tagged int64 when configured |
| `float`   | IEEE 754 binary32, little endian                                            |
| `double`  | IEEE 754 binary64, little endian                                            |

Boxed primitives use the same value payload after the selected null/reference
slot.

## String Values

Java strings are encoded as:

```text
| varuint36_small7 header | bytes |

header = (num_bytes << 2) | coder
```

`coder` values:

| Value | Encoding             |
| ----- | -------------------- |
| 0     | Latin-1              |
| 1     | UTF-16 little endian |
| 2     | UTF-8                |

`num_bytes` is the byte length of the encoded payload.

## Enum Values

Enum value payload depends on configuration:

- Ordinal mode writes the enum ordinal as `varuint32`.
- `@ForyEnumId` mode writes the configured non-negative enum tag as
  `varuint32`.
- Name mode writes the enum constant name as a meta string.

`@ForyEnumId` may be declared on enum constants, on one integer field, or on one
zero-argument integer getter, according to the Java API contract. Duplicate or
negative enum tags are invalid.

## Arrays

### Primitive Arrays

Primitive arrays write a length prefix and contiguous little-endian element
payload:

```text
| varuint32 byte_length | raw element bytes |
```

Compressed `int[]` and `long[]` arrays use element count followed by compressed
elements:

```text
int[] compressed:
| varuint32 length | varint32... |

long[] compressed:
| varuint32 length | varint64 or tagged_int64... |
```

`byte[]` is the binary serializer and writes `varuint32 length` followed by raw
bytes.

### Object Arrays

Object arrays write the array length and an element type mode:

```text
| varuint32_small7 (length << 1 | monomorphic_flag) |
| [shared element class metadata] |
| element slots... |
```

- If `monomorphic_flag == 1`, all non-null elements use the same element
  serializer. The shared element class metadata is written once.
- If `monomorphic_flag == 0`, each non-null element writes its own type
  metadata.

Each nullable or reference-tracked element is still represented by a reference
slot before its element payload.

## Collections

Java collection serializers write collection size, element flags, optional
shared element type metadata, and element payloads:

```text
| varuint32_small7 size | elements_header | [element type metadata] | elements... |
```

`elements_header` bits:

| Bit | Meaning                               |
| --- | ------------------------------------- |
| 0   | Element reference tracking is enabled |
| 1   | At least one element may be null      |
| 2   | Declared element type is used         |
| 3   | All non-null elements share one type  |

When all non-null elements share a type and the declared element type is not
used, the shared element type metadata is written once before element payloads.
Otherwise each non-null element writes its own type metadata. Null and reference
flags follow the reference-slot rules.

### Collection Subclasses

Specialized serializers for supported JDK collection subclasses write
subclass-owned field layers before the element payload:

```text
| varuint32_small7 size |
| [comparator reference for sorted/priority collections] |
| varuint32_small7 num_class_layers |
| class_layer_fields... |
| elements_header | [element type metadata] | elements... |
```

`num_class_layers` is the exact number of subclass field layers encoded in the
payload. Readers must reject a payload whose layer count does not match the
local serializer because the value payload does not carry enough layer identity
to skip a mismatched subclass layout.

## Maps

Maps write entry count followed by one or more chunks. Each chunk groups entries
with compatible key and value metadata:

```text
| varuint32_small7 size | chunk... |
```

Non-null chunks:

```text
| header | uint8 chunk_size | [key type metadata] | [value type metadata] | entries... |
```

`chunk_size` is in `1..255`.

`header` bits:

| Bit | Meaning                             |
| --- | ----------------------------------- |
| 0   | Key reference tracking is enabled   |
| 1   | Chunk may contain null keys         |
| 2   | Declared key type is used           |
| 3   | Value reference tracking is enabled |
| 4   | Chunk may contain null values       |
| 5   | Declared value type is used         |

Null key or null value entries are encoded as single-entry special chunks
without a `chunk_size` byte:

- null key and non-null value: special null-key header, then value payload.
- non-null key and null value: special null-value header, then key payload.
- null key and null value: `KV_NULL` header only.

`EnumMap` writes its entry count, key enum class metadata, and then its normal
map entry payload:

```text
| varuint32_small7 size | key enum class metadata | chunk... |
```

The key enum class metadata is present even when `size` is zero so an empty
`EnumMap` can be reconstructed with the original key type.

### Map Subclasses

Specialized serializers for supported JDK map subclasses write subclass-owned
field layers before entry chunks:

```text
| varuint32_small7 size |
| [comparator reference for sorted maps] |
| varuint32_small7 num_class_layers |
| class_layer_fields... |
| chunk... |
```

Readers must reject mismatched `num_class_layers` for the same reason as
collection subclasses.

## JDK Wrappers and Views

Java native mode has serializers for selected JDK wrappers and views:

- Unmodifiable and synchronized collection/map wrappers keep the wrapper type
  metadata and write the wrapped source collection or map as a normal object
  payload.
- Recognized sublist views keep the sublist type metadata and write one
  serializer-owned mode byte. Mode `0` writes visible elements as a collection
  payload. Mode `1` writes view offset, size, and source list reference.
- `Collections.newSetFromMap` writes the backing map payload.
- Immutable JDK collection serializers keep list, set, or map payload
  semantics and materialize an equivalent immutable or unmodifiable container
  on read.

Android and JVM implementations may choose different concrete public backing
types for wrapper payloads, but the serializer-owned payload modes above define
the wire shape.

## Struct and Object Payloads

Struct-like object payloads contain field values in protocol field order. The
selected serializer owns the exact field fast path:

```text
| field_0 payload | field_1 payload | ... |
```

For each field, field metadata decides whether the field writes a primitive
payload directly, a nullable slot, a reference-tracked slot, type metadata, or a
specialized collection/map/array payload.

Compatible-mode readers use the remote ClassDef field list to map fields by
identifier. Unknown fields are skipped using their remote field type metadata.

Generated serializers may split large generated methods and hoist serializers,
field offsets, collection metadata, or map metadata. Those generated-code
decisions must preserve the same object payload order.

## Throwable Payloads

`Throwable` serializers preserve standard Java throwable state and
subclass-owned fields:

```text
| stack_trace_ref | cause_ref | message_ref |
| varuint32 suppressed_count | suppressed_ref... |
| varuint32 extra_field_count | extra_field_name/value... |
| varuint32_small7 num_class_layers |
| class_layer_fields... |
```

`extra_field_count` is reserved for serializer-owned extension fields and is
currently written as zero. `num_class_layers` must match the local throwable
serializer layout on read.

## Replacement and Java Serialization Hooks

Java native mode supports serializer-owned handling for Java object replacement
and Java serialization hooks:

- `writeReplace`/`readResolve` values use replacement metadata and payloads
  owned by the replacement serializer.
- JDK proxy and lambda replacements use their registered native type IDs.
- Types that require Java Object Serialization compatibility may be delegated to
  serializers that reproduce the required Java semantics inside a Fory object
  slot.

These serializers still obey the stream header, reference slot, and type
metadata rules in this document.

## Unknown Classes

When meta sharing is enabled and a reader does not have a local class for a
remote ClassDef, Java may materialize an unknown-class value using
`NONEXISTENT_META_SHARED_ID`. The value stores enough field data to preserve and
copy the unknown value according to the unknown-class serializer. It does not
make the unknown Java class available to user code.

## Out-of-Band Buffers

When the header out-of-band bit is set, serializers may write references to
external buffers instead of writing all bytes inline. The callback defines the
external buffer transport. The main stream remains a valid Fory stream
containing references to those buffers at serializer-owned payload positions.
