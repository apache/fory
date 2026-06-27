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
- **Cross-Language Support**: Consistent binary format across Java, C++, and Python

Fory provides two row format variants:

| Format          | Languages         | Use Case                       |
| --------------- | ----------------- | ------------------------------ |
| Standard Format | Java, C++, Python | Cross-language compatibility   |
| Compact Format  | Java only         | Space efficiency, smaller rows |

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

Variable-length fields (strings, arrays, maps, nested structs) store an offset-size pair in their slot:

```
+---------------------------+---------------------------+
|    Relative Offset        |         Size              |
|       (32 bits)           |       (32 bits)           |
+---------------------------+---------------------------+
|<-------------- 64-bit field slot value -------------->|
```

- **Relative Offset** (upper 32 bits): Offset from the row's base address
- **Size** (lower 32 bits): Size of the variable-width data in bytes

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
3. **Field Order**: Fields are sorted by alignment (8-byte → 4-byte → 2-byte → 1-byte → variable)
4. **Conditional Bitmap**: Skipped entirely if all fields are non-nullable

#### Null Bitmap (Compact)

- **Size**: `(num_nullable_fields + 7) / 8` bytes (byte-aligned, not 8-byte aligned)
- **Skipped**: When all fields are primitive/non-nullable
- **Position**: After all fixed-size field slots, can use alignment padding space

#### Field Sorting Algorithm

Fields are sorted to minimize padding and optimize alignment:

```
Priority order (highest to lowest):
1. Fields with 8-byte alignment (int64, float64, variable-width)
2. Fields with 4-byte alignment (int32, float32)
3. Fields with 2-byte alignment (int16)
4. Fields with 1-byte alignment (int8, bool)
```

Within each alignment group, larger fields come first.

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

- **Primitive types**: Natural byte width (1, 2, 4, or 8)
- **Struct types**: Sum of all child fixed widths (if all children are fixed-width)
- **Variable types** (string, array, map): Returns -1 (uses 8-byte offset+size slot)

```
fixed_width(field) =
  if primitive: type_width
  if struct and all_children_fixed: header_bytes + sum(fixed_width(child) for each child)
  else: -1 (variable, uses 8-byte slot)
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

## Schema Evolution (Java Only)

Schema evolution lets a codec read payloads written by older versions of the same bean. It is implemented in Java only and does not change the cross-language wire contract above; producer and consumer must agree on whether it is enabled.

The Java encoder frames a row payload with a leading 8-byte schema-hash word. When evolution is enabled, that word holds a stricter hash that also distinguishes field names and nullability; otherwise it holds the format's default schema hash. Array and map payloads carry no hash word otherwise, so under evolution they gain an 8-byte strict-hash prefix. A map's prefix is a single hash that identifies the key and value layouts together, so a map key and value evolve independently while the payload still carries one hash.

See the [Java row format guide](../guide/java/row-format.md#schema-evolution) for usage, annotations, and limitations.

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
array_size = header_size + data_size

where:
  header_size = 8 + ((num_elements + 63) / 64) * 8
  data_size = ((num_elements * element_size + 7) / 8) * 8
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

| Component        | Standard Format                 | Compact Format                        |
| ---------------- | ------------------------------- | ------------------------------------- |
| Row Header       | `((N + 63) / 64) * 8` bytes     | 0 or `(N + 7) / 8` bytes (at end)     |
| Row Field Slots  | `N * 8` bytes                   | `sum(field_widths)` bytes             |
| Array Header     | `8 + ((E + 63) / 64) * 8` bytes | `4 + (E + 7) / 8` bytes (if nullable) |
| Array Elements   | `E * element_size` (8-aligned)  | `E * element_width`                   |
| Map Header       | 8 bytes                         | 8 bytes                               |
| Offset+Size Pair | 8 bytes (32-bit offset + size)  | 8 bytes (same)                        |

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
- Floating-point values use native IEEE 754 representation

### Memory Safety

- Writers must zero padding bytes to prevent information leakage
- Readers must validate offsets and sizes before accessing data
- Buffer bounds checking is required for untrusted input

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

| Scenario                         | Recommended Format |
| -------------------------------- | ------------------ |
| Cross-language data exchange     | Standard           |
| Java-only, memory-constrained    | Compact            |
| Many small primitive fields      | Compact            |
| Many nested fixed-size structs   | Compact            |
| Maximum read performance         | Standard           |
| Interoperability with C++/Python | Standard           |
