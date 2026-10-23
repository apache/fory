---
title: Row Format
sidebar_position: 2
id: row_format_spec
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

## Overview

Apache Fory Row Format is a cache-friendly, random-access binary format designed for high-performance data processing. Unlike traditional serialization formats that require full deserialization, the row format enables:

- **Random Field Access**: Read individual fields without deserializing the entire row
- **Zero-Copy Operations**: Direct memory access without data transformation
- **Cache-Friendly Layout**: Optimized memory layout for CPU cache efficiency
- **Cross-Language Support**: Consistent binary format across Java, C++, Python, Rust, and Go

Fory provides two row format variants:

| Format          | Languages                   | Use Case                       |
| --------------- | --------------------------- | ------------------------------ |
| Standard Format | Java, C++, Python, Rust, Go | Cross-language compatibility   |
| Compact Format  | Java only                   | Space efficiency, smaller rows |

## Format Comparison

| Feature              | Standard Format               | Compact Format                      |
| -------------------- | ----------------------------- | ----------------------------------- |
| Field Slot Size      | Fixed 8 bytes                 | Natural width (1, 2, 4, or 8 bytes) |
| Null Bitmap Size     | 8-byte aligned                | Byte-aligned, can borrow padding    |
| Null Bitmap Position | Before field slots            | After field slots (at end)          |
| Fixed-Size Structs   | Variable region (offset+size) | Inline in fixed region              |
| Field Ordering       | Schema-defined order          | Sorted by alignment                 |
| All Non-Nullable     | Bitmap still present          | Bitmap skipped entirely             |
| Alignment            | Strict 8-byte                 | Relaxed (2, 4, or 8-byte)           |

---

## Standard Row Format

The standard format prioritizes cross-language compatibility and simplicity with uniform 8-byte field slots.

### Design Principles

1. **8-Byte Alignment**: All major structures are aligned to 8-byte boundaries for optimal memory access
2. **Fixed-Width Field Slots**: Every field uses an 8-byte slot for uniform offset calculation
3. **Null Bitmap**: Compact null tracking using bit vectors
4. **Relative Offsets**: Variable-length data uses relative offsets for sub-buffer navigation

### Row Binary Layout

A row stores structured data with the following layout:

```
+----------------+------------------+------------------+-----+------------------+------------------+
|  Null Bitmap   |  Field 0 Slot    |  Field 1 Slot    | ... |  Field N-1 Slot  |  Variable Data   |
+----------------+------------------+------------------+-----+------------------+------------------+
|  B bytes       |     8 bytes      |     8 bytes      |     |     8 bytes      |  Variable size   |
```

#### Null Bitmap

The null bitmap tracks which fields contain null values:

- **Size**: `((num_fields + 63) / 64) * 8` bytes (rounded up to nearest 8-byte word)
- **Encoding**: Each bit corresponds to a field index
  - Bit value `1` = field is null
  - Bit value `0` = field is not null
- **Bit Order**: Bit 0 of the first byte corresponds to field 0

**Example**: For 10 fields, bitmap size = `((10 + 63) / 64) * 8 = 8` bytes

#### Field Slots

Each field occupies a fixed 8-byte slot regardless of its actual data type:

- **Slot Offset**: `bitmap_size + field_index * 8`
- **Total Fixed Region**: `bitmap_size + num_fields * 8` bytes

**Field Slot Contents by Type**:

| Type Category  | Slot Contents                       |
| -------------- | ----------------------------------- |
| Fixed-width    | Value stored directly (zero-padded) |
| Variable-width | Offset + Size encoding (see below)  |

#### Variable-Width Data Encoding

Variable-length fields (strings, arrays, maps, nested structs) store an offset-size pair in their slot. The pair is interpreted as one little-endian 64-bit value:

```
+---------------------------+---------------------------+
|    Relative Offset        |         Size              |
|       (32 bits)           |       (32 bits)           |
+---------------------------+---------------------------+
|<-------------- 64-bit field slot value -------------->|
```

- **Relative Offset** (upper 32 bits): Offset from the row's base address
- **Size** (lower 32 bits): Size of the variable-width data in bytes
- **Physical byte order**: Bytes 0-3 contain size and bytes 4-7 contain the relative offset

**Encoding**:

```
offset_and_size = (relative_offset << 32) | size
```

**Decoding**:

```
relative_offset = (offset_and_size >> 32) & 0xFFFFFFFF
size = offset_and_size & 0xFFFFFFFF
```

#### Variable Data Region

Variable-length data is stored after the fixed region:

- Data is written sequentially as fields are set
- Each variable-length value is padded to 8-byte alignment
- Padding bytes are zeroed for deterministic output

### Array Binary Layout

Arrays store homogeneous sequences of elements:

```
+------------------+------------------+------------------+
|  Element Count   |   Null Bitmap    |   Element Data   |
+------------------+------------------+------------------+
|     8 bytes      |     B bytes      |   Variable size  |
```

#### Array Header

| Field         | Size                            | Description                 |
| ------------- | ------------------------------- | --------------------------- |
| Element Count | 8 bytes                         | Number of elements (uint64) |
| Null Bitmap   | `((count + 63) / 64) * 8` bytes | Per-element null flags      |

**Header Size**: `8 + ((num_elements + 63) / 64) * 8` bytes

#### Array Element Data

Elements are stored contiguously after the header:

- **Fixed-width elements**: Stored with their natural width (1, 2, 4, or 8 bytes)
- **Variable-width elements**: Stored as 8-byte offset+size pairs

**Element Offset**: `header_size + element_index * element_size`

**Data Region Size**: Rounded up to nearest 8-byte boundary

#### Array Element Sizes

| Element Type     | Element Size          |
| ---------------- | --------------------- |
| bool             | 1 byte                |
| int8             | 1 byte                |
| int16            | 2 bytes               |
| int32            | 4 bytes               |
| int64            | 8 bytes               |
| float32          | 4 bytes               |
| float64          | 8 bytes               |
| string/binary    | 8 bytes (offset+size) |
| array/map/struct | 8 bytes (offset+size) |

### Map Binary Layout

Maps store key-value pairs as two separate arrays:

```
+------------------+------------------+------------------+
|  Keys Array Size |   Keys Array     |   Values Array   |
+------------------+------------------+------------------+
|     8 bytes      |   Variable size  |   Variable size  |
```

#### Map Structure

| Field           | Size     | Description                       |
| --------------- | -------- | --------------------------------- |
| Keys Array Size | 8 bytes  | Total size of keys array in bytes |
| Keys Array      | Variable | Full array structure for keys     |
| Values Array    | Variable | Full array structure for values   |

**Keys Array Offset**: `base_offset + 8`
**Values Array Offset**: `base_offset + 8 + keys_array_size`

Both keys and values arrays follow the standard array binary layout.
The key and value arrays must contain the same number of elements.

### Nested Struct Layout

Nested structs are stored as complete row structures within the variable data region:

1. Parent field slot contains offset+size pointing to nested row
2. Nested row has its own null bitmap and field slots
3. Supports arbitrary nesting depth

```
Parent Row:
+----------------+------------------+------------------+
|  Null Bitmap   |  ... Slots ...   |  Nested Row Data |
+----------------+------------------+------------------+
                        |                    ^
                        |  offset+size       |
                        +------------------->+

Nested Row:
+----------------+------------------+------------------+
|  Null Bitmap   |  Field Slots     |  Variable Data   |
+----------------+------------------+------------------+
```

---

## Compact Row Format (Java Only)

The compact format provides space-efficient encoding with additional optimizations. It is currently implemented in Java only.

> **Note**: The compact format is still under development and may not be stable yet.

### Design Principles

1. **Natural Width Storage**: Fixed-size fields use their natural byte width instead of 8 bytes
2. **Alignment-Based Field Sorting**: Fields are sorted by alignment requirements to minimize padding
3. **Conditional Null Bitmap**: Null bitmap is omitted when all fields are non-nullable
4. **Inline Fixed-Size Structs**: Nested structs with all fixed-size fields are stored inline

### Compact Row Binary Layout

```
+------------------+------------------+-----+------------------+----------------+------------------+
|  Field 0 Value   |  Field 1 Value   | ... |  Field N-1 Value | Null Bitmap    |  Variable Data   |
+------------------+------------------+-----+------------------+----------------+------------------+
|  W0 bytes        |  W1 bytes        |     |  WN-1 bytes      | B bytes (opt)  |  Variable size   |
```

#### Key Differences from Standard Format

1. **Field Slot Sizes**: Each field uses its natural width (Wi = type width or 8 for variable)
2. **Null Bitmap Position**: Placed after field slots, can borrow alignment padding
3. **Field Order**: Fields are sorted by slot width (see [Field Sorting Algorithm](#field-sorting-algorithm))
4. **Conditional Bitmap**: Skipped entirely if all fields are non-nullable

#### Null Bitmap (Compact)

- **Size**: `(num_fields + 7) / 8` bytes, one bit per field (byte-aligned, not 8-byte aligned)
- **Skipped**: When all fields are primitive/non-nullable
- **Position**: After all fixed-size field slots, can use alignment padding space

#### Field Sorting Algorithm

Fields are sorted to reduce padding. The sort key of a field is its slot width `w`: its fixed
width (see [Fixed-Width Calculation](#fixed-width-calculation)), or 8 for a variable-width field,
whose slot holds an offset and a size.

1. Group 0 holds the fields whose `w` is 2, 4, or a multiple of 8. Group 1 holds every other
   width, such as 1, 3, 6, or 12. Group 0 comes first.
2. Within a group, a larger `w` comes first.
3. The sort is stable: fields with the same group and `w` keep their order in the schema.

A struct sorts its own fields by the same rule. For example, `bool`, `int16`, `int64`, `string`,
and a 12-byte inline struct `{int64, int32}` sort as `int64` and `string` (in schema order),
`int16`, the 12-byte struct, then `bool`.

#### Fixed-Size Struct Inlining

Nested structs with all fixed-size fields are stored inline in the parent row:

**Standard Format** (nested struct with 2 int32 fields):

```
Parent slot: [offset (4 bytes) | size (4 bytes)]  → Points to nested row (8+ bytes elsewhere)
```

**Compact Format** (same nested struct):

```
Parent slot: [int32 field 0 | int32 field 1]  → 8 bytes total, inline
```

This eliminates the offset+size indirection for fixed-size nested structures.

#### Fixed-Width Calculation

A field's fixed width is determined recursively:

- **Primitive types**: Natural byte width: 1 for `bool` and `int8`; 2 for `int16` and `float16`;
  4 for `int32`, `float32`, and `date32`; 8 for `int64`, `float64`, `timestamp`, and `duration`
- **Fixed-size binary**: Its byte width
- **Struct types**: The struct's null bitmap bytes plus the sum of all child fixed widths (if all
  children are fixed-width). The bitmap bytes are 0 when every child is non-nullable, and
  `(num_children + 7) / 8` otherwise
- **Variable types** (string, variable-size binary, decimal, list, map): Returns -1 (uses 8-byte
  offset+size slot)

```
fixed_width(field) =
  if primitive or fixed-size binary: type_width
  if struct and all_children_fixed: header_bytes + sum(fixed_width(child) for each child)
  else: -1 (variable, uses 8-byte slot)

header_bytes = 0 if no child is nullable, else (num_children + 7) / 8
```

### Compact Array Binary Layout

```
+------------------+------------------+------------------+
|  Element Count   |   Null Bitmap    |   Element Data   |
+------------------+------------------+------------------+
|     4 bytes      | B bytes (opt)    |   Variable size  |
```

#### Compact Array Header

| Field         | Size                          | Description                |
| ------------- | ----------------------------- | -------------------------- |
| Element Count | 4 bytes                       | Number of elements (int32) |
| Null Bitmap   | `(count + 7) / 8` bytes (opt) | Per-element null flags     |

**Header Size Calculation**:

```
header_size = 4 + (element_nullable ? (num_elements + 7) / 8 : 0)

// Round to 8-byte boundary only if element width is 8-byte aligned
if (fixed_width % 8 == 0):
    header_size = round_to_8_bytes(header_size)
```

#### Key Differences from Standard Array

1. **Element Count**: 4 bytes instead of 8 bytes
2. **Null Bitmap**: Byte-aligned, skipped if elements are non-nullable
3. **Fixed-Size Structs**: Inline storage for fixed-width struct elements

---

## Schema Evolution

Schema evolution lets a codec read payloads written by older versions of the same struct. It is
opt-in and currently implemented in Java only. It does not change the existing wire contract
above: a payload written without it is unchanged. When it is enabled, the row hash word holds the
strict hash below instead of the default schema hash, and every top-level array and map payload
starts with an 8-byte hash prefix. An array or map nested in a row, an array, or a map carries no
prefix. Producer and consumer must agree on whether it is enabled. The protocol below
is defined over language-neutral inputs so another runtime can implement it and interoperate with
Java payloads.

See the [Java row format guide](../row-format/java.md#schema-evolution) for usage, annotations,
and limitations.

### Version Model

- Versions are per-struct integers starting at 1. Version numbers never appear on the wire;
  payloads are identified by a strict schema hash, so peers need identical declared histories,
  not synchronized version counters.
- A schema history is a set of field entries `(wire name, type, nullability, since, until)`.
  A live field has an unbounded `until`; a removed field declares the version it disappeared in.
  How the entries are declared is language-specific (Java uses annotations and a removed-fields
  interface); the protocol object is the entry tuple.
- A layout is materialized at version 1 and at every `since` and `until` version, even when no
  field is active at that version (a version-1 payload of a struct whose fields all arrive later
  has an empty layout). Layouts that come out identical collapse into one entry.
- Reading a payload that predates a field yields the reader type's absent value: an empty
  optional for an optional type, null for another nullable field, and the type's zero value (`0`,
  `0.0`, `false`, empty for languages without null) otherwise. A default that the reader's type
  declares for the field (a field initializer, for example) does not apply. Fields removed in the
  reader's schema are discarded.
- A removed entry and a live entry with the same wire name and the same declared type in the
  implementation language are the same field: the reader reads the removed entry's value into the
  live field in every layout that has it. A removed entry with a different declared type is
  invalid, and building the history fails, when the two entries have the same row type and
  nullability after the format puts every struct in wire order (in the compact format, two structs
  whose children differ only in order match), or when either reaches a versioned struct: an older
  layout could then be identical to a newer one, and the reader would decode old values as the new
  type. A removed entry with a different declared type is otherwise discarded, because its row
  type or nullability differs.
  Layouts that come out identical are one entry, so a reader reads a field that any of the
  collapsed versions reads.
- When a struct contains other versioned structs (directly, or through list elements, map keys, or
  map values, to any depth), each distinct nested struct type is one version dimension and the
  reader enumerates the cross-product of versions. A single payload carries one version per struct
  type.

### Wire Names and Canonical Field Order

- A field's wire name is its declared member name converted to lower `snake_case` by the field-name
  rule of the xlang object-graph format (a `camelCase` name converts; a name already in
  `snake_case` is used as-is). That rule does not yet define the conversion of acronyms, a leading
  capital letter, or non-ASCII capital letters, and the runtimes differ on them. Only ordinary
  lowerCamelCase names, with no consecutive capital letters and only ASCII capital letters, are
  portable across runtimes today. A runtime can restrict its member names further: the Java
  runtime requires lowerCamelCase member names.
- The canonical order of the fields of a versioned layout is ascending by wire name, compared by
  Unicode code point (equivalently, byte-wise over the UTF-8 encodings). Duplicate wire names
  within one version are invalid.
- The compact format then sorts the fields of every struct, at every depth (including structs in
  list elements and in map keys and values), with a stable sort over the canonical order. The
  sort key is the field's slot width `w`: the [fixed width](#fixed-width-calculation) of a
  fixed-width field, and 8 for a variable-width field. A field whose `w` is 2, 4, or a multiple
  of 8 sorts before a field with any other `w`. Within each of those two groups, a larger `w`
  sorts first, and fields with equal `w` keep their canonical order. For example, a `string` and
  an `int64` keep their canonical order, and a 12-byte inline struct `{int64, int32}` sorts after
  an `int16` and before a `bool`.
- A custom codec's encoded shape (its struct children, their names, and their order) is fixed by
  the codec's own definition, not by name sorting, and must be reproduced identically in every
  runtime that registers an equivalent codec. The codec's order is the canonical order of its
  struct.

### Strict Schema Hash

The strict hash distinguishes layouts that differ in row format, field name, or nullability,
unlike the default schema hash, which mixes only type IDs. All inputs are language-neutral.

- State: an unsigned 64-bit accumulator `h`, seeded with the FNV-1a offset basis
  `0xcbf29ce484222325`. Each input value `v` (one unsigned 64-bit integer) mixes as
  `h = (h XOR v) * 0x100000001b3 mod 2^64` (the FNV-1a prime). A negative input (a negative
  decimal scale) mixes as its 64-bit two's-complement value.
- The first value mixed after the seed is the format tag: `0` for the standard format and `1` for
  the compact format. The two formats write a layout in the same field order when the compact sort
  moves no field (for example, when every field is 8 bytes wide), so without the tag a reader of
  one format would accept a payload of the other and decode wrong values.
- The hash covers fields in the order they are on the wire, at every depth: the canonical order
  for the standard format, and the compact sort order above for the compact format,
  including for structs in list elements and map entries. The hash of a layout mixes the format
  tag, then folds its top-level fields in that order, each as a named field.
- A named field mixes: each byte of the wire name's UTF-8 encoding, then one `0` terminator,
  then the field's shape.
- A field's shape mixes, in order:
  1. The Fory type ID (table below).
  2. Shape parameters the ID does not carry: for `binary`, the byte width (`0` for
     variable-width); for `decimal`, the precision then the scale.
  3. Nullability: `1` if nullable, `0` otherwise.
  4. Children: a `list` mixes its element field's shape and a `map` mixes its key field's shape
     then its value field's shape — child positions are fixed by the parent type, so no names are
     mixed. At every depth, a map's key field mixes nullability `0` and its value field mixes
     nullability `1`. A `struct` mixes its child count, then each child as a named field; the count
     delimits the struct's extent so nesting structure stays unambiguous.
- Type IDs are the row format's subset of the Fory cross-language type table: `bool` 1, `int8` 2,
  `int16` 3, `int32` 4, `int64` 6, `float16` 17, `float32` 19, `float64` 20, `string` 21,
  `list` 22, `map` 24, `struct` 27, `duration` 37, `timestamp` 38, `date32` 39, `decimal` 40,
  `binary` 41.
- The hash is 64 bits, so two distinct layouts in one history hash to the same value with odds of
  about 2^-64. An implementation should reject a history with such a collision when it builds the
  codec, but it need not detect every collision.
- Known-answer vectors for the strict hash in the standard format, except where a row names the
  compact format (`n` is nullable, `nn` is not nullable):

  | Layout                                                   | Strict hash          |
  | -------------------------------------------------------- | -------------------- |
  | `id: int32 nn`                                           | `0x506a3242a9f93ae2` |
  | `id: int32 nn`, compact format                           | `0xb7db30d6e6593b2f` |
  | `name: string n`                                         | `0x4bd0d984e7109ef6` |
  | `größe: int64 nn`                                        | `0xe5fcaf6a66d32a0e` |
  | `amount: decimal(10, 2) nn`                              | `0x2ef05b2b90e987a9` |
  | `amount: decimal(38, -3) nn`                             | `0x7e49dad4d1337a8a` |
  | `blob: binary n, digest: binary(4) nn`                   | `0xca28ad1d8bf595e7` |
  | `tags: list<string n> n`                                 | `0x55f88fd672e92ea9` |
  | `counts: map<string nn, int32 n> n`                      | `0x570b6dadf84868c8` |
  | `point: struct<x: int32 nn, y: int32 nn> n, z: int64 nn` | `0x793051ee66a15234` |
  | `combine(0x506a3242a9f93ae2, 0x4bd0d984e7109ef6)`        | `0x11ce9d3600e26f89` |

  In the compact format, the layout with the fields `code: string n`, `flag: bool nn`,
  `id: int64 nn`, `stats: struct<count: int32 nn, total: int64 nn> n`, and `version: int16 nn`
  hashes in the order `code`, `id`, `version`, `stats` (children `total`, `count`), `flag`. Its
  strict hash is `0xa6ca2310859be6e6`.

### Framing and Dispatch

- A row payload's existing leading 8-byte hash word (little-endian) holds the strict hash of the
  writer's current layout when evolution is enabled, and the default schema hash otherwise.
- Array and map payloads carry no hash word without evolution. With evolution, every top-level
  array and map payload starts with an 8-byte prefix (little-endian). An array or map nested in a
  row, an array, or a map carries no prefix. So the body of every payload written with evolution
  enabled starts with an 8-byte schema identifier: the strict hash of its layout for a
  row or an array, and the combined key and value hash for a map.
- When the payload size is written before the payload (a little-endian int32), the size comes
  first and the hash word or prefix follows it. The size counts the 8 bytes of the hash and the
  body, but not its own 4 bytes.
- An array's prefix is the strict hash of a layout with one field: the empty name `""`, the element
  type with each reachable struct at the payload's version, and the element's nullability.
- A map's prefix is one combined value,
  `combine(keyHash, valueHash) = mix(mix(basis, keyHash), valueHash)` with the same basis and mix
  step as above. `keyHash` and `valueHash` are each the strict hash of a one-field layout built as
  for an array element: the key with nullability `0`, and the value with nullability `1`. A
  position that reaches no struct still hashes its type. The combination is order-sensitive, so a
  map key and value evolve independently while the payload carries one hash. Nested versioned
  structs need no prefix of their own: their layouts substitute into the enclosing payload's
  hash.
- The reader dispatches on the hash: the current layout's hash selects the current codec, a known
  historical hash selects that layout's projection, and an unknown hash is an error.
- Known-answer vectors for the prefix (`n` is nullable, `nn` is not nullable):

  | Payload                                              | Prefix               |
  | ---------------------------------------------------- | -------------------- |
  | map, key `string nn`, value `int32 n`                | `0xa8efad587d8a6b3b` |
  | array of `struct<narrow: int8 nn, wide: int64 nn> n` | `0x66c342b5ad6942cb` |
  | the same array, compact format                       | `0x905b89131d905a88` |

- Whether evolution is enabled is agreed out of band. Array and map payloads written without
  evolution have no header, so an evolution-off consumer cannot detect the prefix of an
  evolution-enabled payload, and the result of that read is undefined; see the Java guide for the
  rollout procedure.

---

## Common Specifications

The following specifications apply to both standard and compact formats.

### Type Encoding

#### Primitive Types

| Type    | Width   | Encoding                        |
| ------- | ------- | ------------------------------- |
| bool    | 1 byte  | `0x00` (false) or `0x01` (true) |
| int8    | 1 byte  | Two's complement                |
| int16   | 2 bytes | Two's complement, little-endian |
| int32   | 4 bytes | Two's complement, little-endian |
| int64   | 8 bytes | Two's complement, little-endian |
| float32 | 4 bytes | IEEE 754 single precision       |
| float64 | 8 bytes | IEEE 754 double precision       |

#### Temporal Types

| Type      | Width   | Encoding                              |
| --------- | ------- | ------------------------------------- |
| timestamp | 8 bytes | Microseconds since Unix epoch (int64) |
| date32    | 4 bytes | Days since Unix epoch (int32)         |
| duration  | 8 bytes | Duration in microseconds (int64)      |

#### String and Binary

- **Encoding**: UTF-8 for strings, raw bytes for binary
- **Storage**: Offset+size pair in field slot, data in variable region
- **Padding**: Data padded to 8-byte alignment (standard) or natural alignment (compact)

### Null Handling

#### Row Null Handling

- Null fields have their corresponding bit set to 1 in the null bitmap
- Field slot contents are undefined for null fields (standard) or zeroed (compact)
- Reading a null field returns a null/empty value indicator

#### Array Null Handling

- Null elements have their corresponding bit set to 1 in the array's null bitmap
- Element data is undefined for null elements
- Compact format: Bitmap skipped if elements are non-nullable

#### Variable-Width Null Semantics

When reading variable-width data from a null field:

- Returns size of -1 or equivalent null indicator
- No data access is performed

### Alignment and Padding

#### Standard Format Alignment

1. **Null Bitmap**: Size rounded to 8-byte boundary
2. **Field Slots**: Always 8 bytes each
3. **Variable Data**: Each value padded to 8-byte boundary
4. **Array Data**: Total data region padded to 8-byte boundary

#### Compact Format Alignment

1. **Field Slots**: Natural width (1, 2, 4, or 8 bytes)
2. **Null Bitmap**: Byte-aligned, placed after fields
3. **Variable Data**: Padded to 8-byte boundary only when needed
4. **Header**: May use relaxed alignment for smaller overhead

#### Padding Bytes

- All padding bytes must be set to zero
- Ensures deterministic serialization output
- Prevents information leakage from uninitialized memory

## Size Calculations

### Standard Row Size

```
row_size = bitmap_size + num_fields * 8 + variable_data_size

where:
  bitmap_size = ((num_fields + 63) / 64) * 8
  variable_data_size = sum of (padded_size for each variable field)
  padded_size = ((size + 7) / 8) * 8
```

### Compact Row Size

```
row_size = fixed_region_size + bitmap_size + variable_data_size

where:
  fixed_region_size = sum of (fixed_width(field) or 8 for each field)
  bitmap_size = all_non_nullable ? 0 : (num_nullable_fields + 7) / 8
  // May be rounded to 8-byte boundary if has variable fields
```

### Standard Array Size

```
array_size = header_size + fixed_data_size + variable_data_size

where:
  header_size = 8 + ((num_elements + 63) / 64) * 8
  element_slot_size = natural width for fixed-width elements, otherwise 8
  fixed_data_size = ((num_elements * element_slot_size + 7) / 8) * 8
  variable_data_size = sum of (padded_size for each non-null variable-width element)
  padded_size = ((size + 7) / 8) * 8
```

### Compact Array Size

```
array_size = header_size + data_size

where:
  header_size = 4 + (element_nullable ? (num_elements + 7) / 8 : 0)
  // header_size rounded to 8 if element_width % 8 == 0
  data_size = num_elements * element_width
```

### Map Size

```
map_size = 8 + keys_array_size + values_array_size
```

## Summary Tables

### Layout Summary

| Component        | Standard Format                        | Compact Format                        |
| ---------------- | -------------------------------------- | ------------------------------------- |
| Row Header       | `((N + 63) / 64) * 8` bytes            | 0 or `(N + 7) / 8` bytes (at end)     |
| Row Field Slots  | `N * 8` bytes                          | `sum(field_widths)` bytes             |
| Array Header     | `8 + ((E + 63) / 64) * 8` bytes        | `4 + (E + 7) / 8` bytes (if nullable) |
| Array Elements   | 8-aligned slots plus variable bodies   | `E * element_width`                   |
| Map Header       | 8 bytes                                | 8 bytes                               |
| Offset+Size Pair | 8-byte `u64`: `(offset << 32) \| size` | 8 bytes (same)                        |

Where N = number of fields, E = number of elements

### Type Width Summary

| Category      | Storage Width | Standard Slot | Compact Slot |
| ------------- | ------------- | ------------- | ------------ |
| bool          | 1 byte        | 8 bytes       | 1 byte       |
| int8          | 1 byte        | 8 bytes       | 1 byte       |
| int16         | 2 bytes       | 8 bytes       | 2 bytes      |
| int32         | 4 bytes       | 8 bytes       | 4 bytes      |
| int64         | 8 bytes       | 8 bytes       | 8 bytes      |
| float32       | 4 bytes       | 8 bytes       | 4 bytes      |
| float64       | 8 bytes       | 8 bytes       | 8 bytes      |
| string/binary | Variable      | 8 bytes       | 8 bytes      |
| array         | Variable      | 8 bytes       | 8 bytes      |
| map           | Variable      | 8 bytes       | 8 bytes      |
| struct        | Variable      | 8 bytes       | inline or 8  |

## Implementation Notes

### Endianness

- All multi-byte integers are stored in **little-endian** format
- Floating-point values use IEEE 754 bit representations stored in little-endian byte order

### Memory Safety

- Writers must zero padding bytes to prevent information leakage
- Readers must validate offsets and sizes before accessing data
- Row format accepts only trusted input; its offset and size checks are correctness and
  defense-in-depth measures, not an untrusted-input security boundary

### Performance Considerations

**Standard Format**:

- Fixed 8-byte slots enable O(1) field access with simple arithmetic
- 8-byte alignment optimizes CPU cache line usage
- Best for cross-language interoperability

**Compact Format**:

- Smaller row sizes reduce memory bandwidth
- Field sorting minimizes padding waste
- Inline structs eliminate pointer chasing
- Relaxed alignment may have slight CPU overhead on some architectures

### When to Use Each Format

| Scenario                                   | Recommended Format |
| ------------------------------------------ | ------------------ |
| Cross-language data exchange               | Standard           |
| Java-only, memory-constrained              | Compact            |
| Many small primitive fields                | Compact            |
| Many nested fixed-size structs             | Compact            |
| Maximum read performance                   | Standard           |
| Interoperability with Java/C++/Python/Rust | Standard           |
