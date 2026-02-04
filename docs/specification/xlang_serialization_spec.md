---
title: Xlang Serialization Format
sidebar_position: 0
id: xlang_serialization_spec
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

## Cross-language Serialization Specification

Apache Fory™ xlang serialization enables automatic cross-language object serialization with support for shared references, circular references, and polymorphism. Unlike traditional serialization frameworks that require IDL definitions and schema compilation, Fory serializes objects directly without any intermediate steps.

Key characteristics:

- **Automatic**: No IDL definition, no schema compilation, no manual object-to-protocol conversion
- **Cross-language**: Same binary format works seamlessly across Java, Python, C++, Rust, Go, JavaScript, and more
- **Reference-aware**: Handles shared references and circular references without duplication or infinite recursion
- **Polymorphic**: Supports object polymorphism with runtime type resolution

This specification defines the Fory xlang binary format. The format is dynamic rather than static, which enables flexibility and ease of use at the cost of additional complexity in the wire format.

## Type Systems

### Data Types

- bool: a boolean value (true or false).
- int8: a 8-bit signed integer.
- int16: a 16-bit signed integer.
- int32: a 32-bit signed integer.
- varint32: a 32-bit signed integer which use fory variable-length encoding.
- int64: a 64-bit signed integer.
- varint64: a 64-bit signed integer which use fory PVL encoding.
- tagged_int64: a 64-bit signed integer which use fory Hybrid encoding.
- uint8: an 8-bit unsigned integer.
- uint16: a 16-bit unsigned integer.
- uint32: a 32-bit unsigned integer.
- var_uint32: a 32-bit unsigned integer which use fory variable-length encoding.
- uint64: a 64-bit unsigned integer.
- var_uint64: a 64-bit unsigned integer which use fory PVL encoding.
- tagged_uint64: a 64-bit unsigned integer which use fory Hybrid encoding.
- float8: an 8-bit floating point number.
- float16: a 16-bit floating point number.
- bfloat16: a 16-bit brain floating point number.
- float32: a 32-bit floating point number.
- float64: a 64-bit floating point number including NaN and Infinity.
- string: a text string encoded using Latin1/UTF16/UTF-8 encoding.
- enum: a data type consisting of a set of named values. Rust enum with non-predefined field values are not supported as
  an enum.
- named_enum: an enum whose value will be serialized as the registered name.
- struct: a dynamic(final) type serialized by Fory Struct serializer. i.e. it doesn't have subclasses. Suppose we're
  deserializing `List<SomeClass>`, we can save dynamic serializer dispatch since `SomeClass` is dynamic(final).
- compatible_struct: a dynamic(final) type serialized by Fory compatible Struct serializer.
- named_struct: a `struct` whose type mapping will be encoded as a name.
- named_compatible_struct: a `compatible_struct` whose type mapping will be encoded as a name.
- ext: a type which will be serialized by a customized serializer.
- named_ext: an `ext` type whose type mapping will be encoded as a name.
- list: a sequence of objects.
- set: an unordered set of unique elements.
- map: a map of key-value pairs. Mutable types such as `list/map/set/array` are not allowed as key of map.
- duration: an absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
- timestamp: a point in time, independent of any calendar/timezone, encoded as seconds (int64) and nanoseconds
  (uint32) since the epoch at UTC midnight on January 1, 1970.
- date: a naive date without timezone. The count is days relative to an epoch at UTC midnight on Jan 1, 1970.
- decimal: exact decimal value represented as an integer value in two's complement.
- binary: an variable-length array of bytes.
- array: only allow 1d numeric components. Other arrays will be taken as List. The implementation should support the
  interoperability between array and list.
  - bool_array: one dimensional bool array.
  - int8_array: one dimensional int8 array.
  - int16_array: one dimensional int16 array.
  - int32_array: one dimensional int32 array.
  - int64_array: one dimensional int64 array.
  - float8_array: one dimensional float8 array.
  - float16_array: one dimensional half_float_16 array.
  - bfloat16_array: one dimensional bfloat16 array.
  - float32_array: one dimensional float32 array.
  - float64_array: one dimensional float64 array.
- union: a tagged union type that can hold one of several alternative types. The active alternative is identified by an index.
- typed_union: a union value with registered numeric union type ID.
- named_union: a union value with embedded union type name or shared TypeDef.
- none: represents an empty/unit value with no data (e.g., for empty union alternatives).

Note:

- Unsigned integer types use the same byte sizes as their signed counterparts; the difference is in value interpretation. See [Type mapping](xlang_type_mapping.md) for language-specific type mappings.

### Polymorphisms

For polymorphism, if one non-final class is registered, and only one subclass is registered, then we can take all
elements in List/Map have same type, thus reduce runtime check cost.

Collection/Array polymorphism are not fully supported, since some languages such as golang have only one collection
type. If users want to get exactly the type he passed, he must pass that type when deserializing or annotate that type
to the field of struct.

### Type disambiguation

Due to differences between type systems of languages, those types can't be mapped one-to-one between languages. When
deserializing, Fory use the target data structure type and the data type in the data jointly to determine how to
deserialize and populate the target data structure. For example:

```java
class Foo {
  int[] intArray;
  Object[] objects;
  List<Object> objectList;
}

class Foo2 {
  int[] intArray;
  List<Object> objects;
  List<Object> objectList;
}
```

`intArray` has an `int32_array` type. But both `objects` and `objectList` fields in the serialize data have `list` data
type. When deserializing, the implementation will create an `Object` array for `objects`, but create a `ArrayList`
for `objectList` to populate its elements. And the serialized data of `Foo` can be deserialized into `Foo2` too.

Users can also provide meta hints for fields of a type, or the type whole. Here is an example in java which use
annotation to provide such information.

```java
@ForyObject(fieldsNullable = false, trackingRef = false)
class Foo {
  @ForyField(trackingRef = false)
  int[] intArray;
  @ForyField(polymorphic = true)
  Object object;
  @ForyField(tagId = 1, nullable = true)
  List<Object> objectList;
}
```

Such information can be provided in other languages too:

- cpp: use macro and template.
- golang: use struct tag.
- python: use typehint.
- rust: use macro.

### Type ID

All internal data types use an 8-bit internal ID (`0~255`, with `0~56` defined here). Users can
register types by numeric ID (`0~0xFFFFFFFE` in current implementations). User IDs are encoded
separately from the internal type ID; there is no bit shifting/packing.

Named types (`NAMED_*`) do not embed a user ID; their names are carried in metadata instead.

#### Internal Type ID Table

| Type ID | Name                    | Description                                         |
| ------- | ----------------------- | --------------------------------------------------- |
| 0       | UNKNOWN                 | Unknown type, used for dynamic typing               |
| 1       | BOOL                    | Boolean value                                       |
| 2       | INT8                    | 8-bit signed integer                                |
| 3       | INT16                   | 16-bit signed integer                               |
| 4       | INT32                   | 32-bit signed integer                               |
| 5       | VARINT32                | Variable-length encoded 32-bit signed integer       |
| 6       | INT64                   | 64-bit signed integer                               |
| 7       | VARINT64                | Variable-length encoded 64-bit signed integer       |
| 8       | TAGGED_INT64            | Hybrid encoded 64-bit signed integer                |
| 9       | UINT8                   | 8-bit unsigned integer                              |
| 10      | UINT16                  | 16-bit unsigned integer                             |
| 11      | UINT32                  | 32-bit unsigned integer                             |
| 12      | VAR_UINT32              | Variable-length encoded 32-bit unsigned integer     |
| 13      | UINT64                  | 64-bit unsigned integer                             |
| 14      | VAR_UINT64              | Variable-length encoded 64-bit unsigned integer     |
| 15      | TAGGED_UINT64           | Hybrid encoded 64-bit unsigned integer              |
| 16      | FLOAT8                  | 8-bit floating point (float8)                       |
| 17      | FLOAT16                 | 16-bit floating point (half precision)              |
| 18      | BFLOAT16                | 16-bit brain floating point                         |
| 19      | FLOAT32                 | 32-bit floating point (single precision)            |
| 20      | FLOAT64                 | 64-bit floating point (double precision)            |
| 21      | STRING                  | UTF-8/UTF-16/Latin1 encoded string                  |
| 22      | LIST                    | Ordered collection (List, Array, Vector)            |
| 23      | SET                     | Unordered collection of unique elements             |
| 24      | MAP                     | Key-value mapping                                   |
| 25      | ENUM                    | Enum registered by numeric ID                       |
| 26      | NAMED_ENUM              | Enum registered by namespace + type name            |
| 27      | STRUCT                  | Struct registered by numeric ID (schema consistent) |
| 28      | COMPATIBLE_STRUCT       | Struct with schema evolution support (by ID)        |
| 29      | NAMED_STRUCT            | Struct registered by namespace + type name          |
| 30      | NAMED_COMPATIBLE_STRUCT | Struct with schema evolution (by name)              |
| 31      | EXT                     | Extension type registered by numeric ID             |
| 32      | NAMED_EXT               | Extension type registered by namespace + type name  |
| 33      | UNION                   | Union value, schema identity not embedded           |
| 34      | TYPED_UNION             | Union value with registered numeric type ID         |
| 35      | NAMED_UNION             | Union value with embedded type name/TypeDef         |
| 36      | NONE                    | Empty/unit type (no data)                           |
| 37      | DURATION                | Time duration (seconds + nanoseconds)               |
| 38      | TIMESTAMP               | Point in time (seconds + nanoseconds since epoch)   |
| 39      | DATE                    | Date without timezone (days since epoch)            |
| 40      | DECIMAL                 | Arbitrary precision decimal                         |
| 41      | BINARY                  | Raw binary data                                     |
| 42      | ARRAY                   | Generic array type                                  |
| 43      | BOOL_ARRAY              | 1D boolean array                                    |
| 44      | INT8_ARRAY              | 1D int8 array                                       |
| 45      | INT16_ARRAY             | 1D int16 array                                      |
| 46      | INT32_ARRAY             | 1D int32 array                                      |
| 47      | INT64_ARRAY             | 1D int64 array                                      |
| 48      | UINT8_ARRAY             | 1D uint8 array                                      |
| 49      | UINT16_ARRAY            | 1D uint16 array                                     |
| 50      | UINT32_ARRAY            | 1D uint32 array                                     |
| 51      | UINT64_ARRAY            | 1D uint64 array                                     |
| 52      | FLOAT8_ARRAY            | 1D float8 array                                     |
| 53      | FLOAT16_ARRAY           | 1D float16 array                                    |
| 54      | BFLOAT16_ARRAY          | 1D bfloat16 array                                   |
| 55      | FLOAT32_ARRAY           | 1D float32 array                                    |
| 56      | FLOAT64_ARRAY           | 1D float64 array                                    |

#### Type ID Encoding for User Types

When registering user types (struct/ext/enum/union), the internal type ID is written as the 8-bit
kind. The user type ID is written separately as an unsigned varint32 (small7); there is no bit
shift or packing.

**Examples:**

| User ID | Type              | Internal ID | Encoded User ID | Decimal |
| ------- | ----------------- | ----------- | --------------- | ------- |
| 0       | STRUCT            | 27          | 0               | 0       |
| 0       | ENUM              | 25          | 0               | 0       |
| 1       | STRUCT            | 27          | 1               | 1       |
| 1       | COMPATIBLE_STRUCT | 28          | 1               | 1       |
| 2       | NAMED_STRUCT      | 29          | 2               | 2       |

When reading type IDs:

- Read internal type ID from the type ID field.
- If the internal type is a user-registered kind, read `user_type_id` as varuint32.

### Type mapping

See [Type mapping](xlang_type_mapping.md)

## Spec overview

Here is the overall format:

```
| fory header | object ref meta | object type meta | object value data |
```

The data are serialized using little endian byte order for all types.

## Fory header

Fory header format for xlang serialization:

```
|        1 byte bitmap           |
+--------------------------------+
|            flags               |
```

Detailed byte layout:

```
Byte 0:   Bitmap flags
          - Bit 0: null flag (0x01)
          - Bit 1: xlang flag (0x02)
          - Bit 2: oob flag (0x04)
          - Bits 3-7: reserved
```

- **null flag** (bit 0): 1 when object is null, 0 otherwise. If an object is null, only this flag is set.
- **xlang flag** (bit 1): 1 when serialization uses Fory xlang format, 0 when serialization uses Fory language-native format.
- **oob flag** (bit 2): 1 when out-of-band serialization is enabled (BufferCallback is not null), 0 otherwise.

All data is encoded in little-endian format.

## Reference Meta

Reference tracking handles whether the object is null, and whether to track reference for the object by writing
corresponding flags and maintaining internal state.

### Reference Flags

| Flag                | Byte Value (int8) | Hex    | Description                                                                                              |
| ------------------- | ----------------- | ------ | -------------------------------------------------------------------------------------------------------- |
| NULL FLAG           | `-3`              | `0xFD` | Object is null. No further bytes are written for this object.                                            |
| REF FLAG            | `-2`              | `0xFE` | Object was already serialized. Followed by unsigned varint32 reference ID.                               |
| NOT_NULL VALUE FLAG | `-1`              | `0xFF` | Object is non-null but reference tracking is disabled for this type. Object data follows immediately.    |
| REF VALUE FLAG      | `0`               | `0x00` | Object is referencable and this is its first occurrence. Object data follows. Assigns next reference ID. |

### Reference Tracking Algorithm

**Writing:**

```
function write_ref_or_null(buffer, obj):
    if obj is null:
        buffer.write_int8(NULL_FLAG)      // -3
        return true  // done, no more data to write

    if reference_tracking_enabled:
        ref_id = lookup_written_objects(obj)
        if ref_id exists:
            buffer.write_int8(REF_FLAG)   // -2
            buffer.write_varuint32(ref_id)
            return true  // done, reference written
        else:
            buffer.write_int8(REF_VALUE_FLAG)  // 0
            add_to_written_objects(obj, next_ref_id++)
            return false  // continue to serialize object data
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)  // -1
        return false  // continue to serialize object data
```

**Reading:**

```
function read_ref_or_null(buffer):
    flag = buffer.read_int8()
    switch flag:
        case NULL_FLAG (-3):
            return (null, true)  // null object, done
        case REF_FLAG (-2):
            ref_id = buffer.read_varuint32()
            obj = get_from_read_objects(ref_id)
            return (obj, true)  // referenced object, done
        case NOT_NULL_VALUE_FLAG (-1):
            return (null, false)  // non-null, continue reading
        case REF_VALUE_FLAG (0):
            reserve_ref_slot()  // will be filled after reading
            return (null, false)  // non-null, continue reading
```

### Reference ID Assignment

- Reference IDs are assigned sequentially starting from `0`
- The ID is assigned when `REF_VALUE_FLAG` is written (first occurrence)
- Objects are stored in a list/map indexed by their reference ID
- For reading, a placeholder slot is reserved before deserializing the object, then filled after

### When Reference Tracking is Disabled

When reference tracking is disabled globally or for specific types, only the `NULL` and `NOT_NULL VALUE` flags
will be used for reference meta. This reduces overhead for types that are known not to have references.

### Language-Specific Considerations

**Languages with nullable and reference types by default (Java, Python, JavaScript):**

In xlang mode, for cross-language compatibility:

- All fields are treated as **not-null** by default
- Reference tracking is **disabled** by default
- Users can explicitly mark fields as nullable or enable reference tracking via annotations
- `Optional` types (e.g., `java.util.Optional`, `typing.Optional`) are treated as nullable

**Annotation examples:**

```java
// Java: use @ForyField annotation
public class MyClass {
    @ForyField(nullable = true, ref = true)
    private Object refField;

    @ForyField(nullable = false)
    private String requiredField;
}
```

```python
# Python: use typing with fory field descriptors
from pyfory import Fory, ForyField

class MyClass:
    ref_field: ForyField(SomeType, nullable=True, ref=True)
    required_field: ForyField(str, nullable=False)
```

**Languages with non-nullable types by default:**

| Language | Null Representation       | Reference Tracking Support              |
| -------- | ------------------------- | --------------------------------------- |
| Rust     | `Option::None`            | Via `Rc<T>`, `Arc<T>`, `Weak<T>`        |
| C++      | `std::nullopt`, `nullptr` | Via `std::shared_ptr<T>`, `weak_ptr<T>` |
| Go       | `nil` interface/pointer   | Via pointer/interface types             |

**Important:** For languages like Rust that don't have implicit reference semantics, reference tracking must use
explicit smart pointers (`Rc`, `Arc`).

## Type Meta

Every non-primitive value begins with a type ID that identifies its concrete type. The type ID is
followed by optional type-specific metadata.

### Type ID encoding

- The type ID is written as an unsigned varint32 (small7).
- Internal types use their internal type ID directly (low 8 bits).
- User-registered types write the internal type ID, then write `user_type_id` as varuint32.
  - `user_type_id` is a numeric ID (0~0xFFFFFFFE in current implementations).
  - `internal_type_id` is one of `ENUM`, `STRUCT`, `COMPATIBLE_STRUCT`, `EXT`, or `TYPED_UNION`.
- Named types do not embed a user ID. They use `NAMED_*` internal type IDs and carry a namespace
  and type name (or shared TypeDef) instead.

### Type meta payload

After the type ID:

- **ENUM / STRUCT / EXT / TYPED_UNION**: no extra bytes beyond the `user_type_id` (registration by ID required on both sides).
- **COMPATIBLE_STRUCT**:
  - If meta share is enabled, write a shared TypeDef entry (see below).
  - If meta share is disabled, no extra bytes.
- **NAMED_ENUM / NAMED_STRUCT / NAMED_COMPATIBLE_STRUCT / NAMED_EXT / NAMED_UNION**:
  - If meta share is disabled, write `namespace` and `type_name` as meta strings.
  - If meta share is enabled, write a shared TypeDef entry (see below).
- **UNION**: no extra bytes at this layer.
- **LIST / SET / MAP / ARRAY / primitives**: no extra bytes at this layer.

Unregistered types are serialized as named types:

- Enums -> `NAMED_ENUM`
- Struct-like classes -> `NAMED_STRUCT` (or `NAMED_COMPATIBLE_STRUCT` when meta share is enabled)
- Custom extension types -> `NAMED_EXT`
- Unions -> `NAMED_UNION`

The namespace is the package/module name and the type name is the simple class name.

### Shared Type Meta (streaming)

When meta share is enabled, TypeDef metadata is written inline the first time a type is
encountered, and subsequent occurrences only reference it.

Encoding:

- `marker = (index << 1) | flag`
- `flag = 0`: new type definition follows
- `flag = 1`: reference to a previously written type definition
- `index` is the sequential index assigned to this type (starting from 0).

Write algorithm:

1. Look up the class in the per-stream meta context map.
2. If found, write `(index << 1) | 1`.
3. If not found:
   - assign `index = next_id`
   - write `(index << 1)`
   - write the encoded TypeDef bytes immediately after

Read algorithm:

1. Read `marker` as varuint32.
2. `flag = marker & 1`, `index = marker >>> 1`.
3. If `flag == 1`, use the cached TypeDef at `index`.
4. If `flag == 0`, read a TypeDef, cache it at `index`, and use it.

TypeDef bytes include the 8-byte global header and optional size extension.

### TypeDef (schema evolution metadata)

TypeDef describes a struct-like type (or a named enum/ext) for schema evolution and name
resolution. It is encoded as:

```
|    8-byte global header   | [optional size varuint] | TypeDef body |
```

#### Global header

The 8-byte header is a little-endian uint64:

- Low 8 bits: meta size (number of bytes in the TypeDef body).
  - If meta size >= 0xFF, the low 8 bits are set to 0xFF and an extra
    `varuint32(meta_size - 0xFF)` follows immediately after the header.
- Bit 8: `HAS_FIELDS_META` (1 = fields metadata present).
- Bit 9: `COMPRESS_META` (1 = body is compressed; decompress before parsing).
- Bits 10-13: reserved for future extension (must be zero).
- High 50 bits: hash of the TypeDef body.

#### TypeDef body

TypeDef body has a single layer (fields are flattened in class hierarchy order):

```
| meta header (1 byte) | type spec | field info ... |
```

Meta header byte:

- Bits 0-4: `num_fields` (0-30).
  - If `num_fields == 31`, read an extra `varuint32` and add it.
- Bit 5: `REGISTER_BY_NAME` (1 = namespace + type name, 0 = numeric type ID).
- Bits 6-7: reserved.

Type spec:

- If `REGISTER_BY_NAME` is set:
  - `namespace` meta string
  - `type_name` meta string
- Otherwise:
  - `type_id` as `varuint32` (small7)

Field info list:

Each field is encoded as:

```
| field header (1 byte) | field type info | [field name bytes] |
```

Field header layout:

- Bits 6-7: field name encoding (`UTF8`, `ALL_TO_LOWER_SPECIAL`,
  `LOWER_UPPER_DIGIT_SPECIAL`, or `TAG_ID`)
- Bits 2-5: size
  - For name encoding: `size = (name_bytes_length - 1)`
  - For tag ID: `size = tag_id`
  - If `size == 0b1111`, read `varuint32(size - 15)` and add it
- Bit 1: nullable flag
- Bit 0: reference tracking flag

Field type info:

- The top-level field type is written as `varuint32(type_id)` (small7) without flags.
- For `LIST` / `SET`, an element type follows, encoded as
  `(nested_type_id << 2) | (nullable << 1) | tracking_ref`.
- For `MAP`, key type and value type follow, both encoded the same way.
- One-dimensional primitive arrays use `*_ARRAY` type IDs; other arrays are encoded as `LIST`.

Field names:

- If `TAG_ID` encoding is used, no name bytes are written.
- Otherwise, write the encoded field name bytes as a meta string.
- For xlang, field names are converted to `snake_case` before encoding for
  cross-language compatibility.

Field order:

Field order is implementation-defined. Decoders must match fields by name or tag ID rather than
position. Fory uses a stable grouping and sorting order to produce deterministic TypeDefs.

## Meta String

Meta string is a compressed encoding for metadata strings such as field names, type names, and namespaces.
This compression significantly reduces the size of type metadata in serialized data.

### Encoding Type IDs

| ID  | Name                      | Bits/Char | Character Set                        |
| --- | ------------------------- | --------- | ------------------------------------ |
| 0   | UTF8                      | 8         | Any UTF-8 character                  |
| 1   | LOWER_SPECIAL             | 5         | `a-z . _ $ \|`                       |
| 2   | LOWER_UPPER_DIGIT_SPECIAL | 6         | `a-z A-Z 0-9 . _`                    |
| 3   | FIRST_TO_LOWER_SPECIAL    | 5         | First char uppercase, rest `a-z . _` |
| 4   | ALL_TO_LOWER_SPECIAL      | 5         | `a-z A-Z . _` (uppercase escaped)    |

### Character Mapping Tables

#### LOWER_SPECIAL (5 bits per character)

| Character | Code (binary) | Code (decimal) |
| --------- | ------------- | -------------- |
| a-z       | 00000-11001   | 0-25           |
| .         | 11010         | 26             |
| \_        | 11011         | 27             |
| $         | 11100         | 28             |
| \|        | 11101         | 29             |

**Note:** The `|` character is used as an escape sequence in ALL_TO_LOWER_SPECIAL encoding.

#### LOWER_UPPER_DIGIT_SPECIAL (6 bits per character)

| Character | Code (binary) | Code (decimal) |
| --------- | ------------- | -------------- |
| a-z       | 000000-011001 | 0-25           |
| A-Z       | 011010-110011 | 26-51          |
| 0-9       | 110100-111101 | 52-61          |
| .         | 111110        | 62             |
| \_        | 111111        | 63             |

### Encoding Algorithms

#### LOWER_SPECIAL Encoding

For strings containing only `a-z`, `.`, `_`, `$`, `|`:

```
function encode_lower_special(str):
    bits = []
    for char in str:
        bits.append(lookup_lower_special[char])  // 5 bits each

    // Pad to byte boundary
    total_bits = len(str) * 5
    padding_bits = (8 - (total_bits % 8)) % 8

    // First bit indicates if last char should be stripped (due to padding)
    strip_last = (padding_bits >= 5)
    if strip_last:
        prepend bit 1
    else:
        prepend bit 0

    return pack_bits_to_bytes(bits)
```

#### FIRST_TO_LOWER_SPECIAL Encoding

For strings like `MyFieldName` where only the first character is uppercase:

```
function encode_first_to_lower_special(str):
    // Convert first char to lowercase
    modified = str[0].lower() + str[1:]
    // Then use LOWER_SPECIAL encoding
    return encode_lower_special(modified)
```

#### ALL_TO_LOWER_SPECIAL Encoding

For strings with multiple uppercase characters like `MyTypeName`:

```
function encode_all_to_lower_special(str):
    result = ""
    for char in str:
        if char.is_upper():
            result += "|" + char.lower()  // Escape uppercase with |
        else:
            result += char
    return encode_lower_special(result)
```

Example: `MyType` → `|my|type` → encoded with LOWER_SPECIAL

### Encoding Selection Algorithm

```
function choose_encoding(str):
    if all chars in str are in [a-z . _ $ |]:
        return LOWER_SPECIAL

    if first char is uppercase AND rest are in [a-z . _]:
        return FIRST_TO_LOWER_SPECIAL

    if all chars are in [a-z A-Z . _]:
        lower_special_size = encode_all_to_lower_special(str).size
        luds_size = encode_lower_upper_digit_special(str).size
        if lower_special_size <= luds_size:
            return ALL_TO_LOWER_SPECIAL
        else:
            return LOWER_UPPER_DIGIT_SPECIAL

    if all chars are in [a-z A-Z 0-9 . _]:
        return LOWER_UPPER_DIGIT_SPECIAL

    return UTF8
```

### Meta String Header Format

Meta strings are written with a header that includes the encoding type:

```
| 3 bits encoding | 5+ bits length | encoded bytes |
```

Or for larger strings:

```
| varuint: (length << 3) | encoding | encoded bytes |
```

### Special Character Sets by Context

Different contexts use different special characters:

| Context    | Special Chars | Notes                              |
| ---------- | ------------- | ---------------------------------- |
| Field Name | . \_ $ \|     | $ for inner classes, \| for escape |
| Namespace  | . \_          | Package/module separators          |
| Type Name  | $ \_          | $ for inner classes in Java        |

### Deduplication

Meta strings are deduplicated within a serialization session:

```
First occurrence:  | (length << 1) | [hash if large] | encoding | bytes |
Reference:         | ((id + 1) << 1) | 1 |
```

- Bit 0 of the header indicates: 0 = new string, 1 = reference to previous
- Large strings (> 16 bytes) include 64-bit hash for content-based deduplication
- Small strings use exact byte comparison

## Value Format

### Basic types

#### bool

- size: 1 byte
- format: 0 for `false`, 1 for `true`

#### int8

- size: 1 byte
- format: write as pure byte.

#### int16

- size: 2 byte
- byte order: raw bytes of little endian order

#### unsigned int32

- size: 4 byte
- byte order: raw bytes of little endian order

#### unsigned varint32

- size: 1~5 bytes
- Format: The most significant bit (MSB) in every byte indicates whether to have the next byte. If the continuation
  bit is set (i.e. `b & 0x80 == 0x80`), then the next byte should be read until a byte with unset continuation bit.

**Encoding Algorithm:**

```
function write_varuint32(value):
    while value >= 0x80:
        buffer.write_byte((value & 0x7F) | 0x80)  // 7 bits of data + continuation bit
        value = value >> 7
    buffer.write_byte(value)  // final byte without continuation bit
```

**Decoding Algorithm:**

```
function read_varuint32():
    result = 0
    shift = 0
    while true:
        byte = buffer.read_byte()
        result = result | ((byte & 0x7F) << shift)
        if (byte & 0x80) == 0:
            break
        shift = shift + 7
    return result
```

**Byte sizes by value range:**

| Value Range            | Bytes |
| ---------------------- | ----- |
| 0 ~ 127                | 1     |
| 128 ~ 16383            | 2     |
| 16384 ~ 2097151        | 3     |
| 2097152 ~ 268435455    | 4     |
| 268435456 ~ 4294967295 | 5     |

#### signed int32

- size: 4 bytes
- byte order: raw bytes of little endian order

#### signed varint32

- size: 1~5 bytes
- Format: First convert the number into positive unsigned int using ZigZag encoding, then encode as unsigned varint.

**ZigZag Encoding:**

```
// Encode: convert signed to unsigned
zigzag_value = (value << 1) ^ (value >> 31)

// Decode: convert unsigned back to signed
original = (zigzag_value >> 1) ^ (-(zigzag_value & 1))
// Or equivalently:
original = (zigzag_value >> 1) ^ (~(zigzag_value & 1) + 1)
```

ZigZag encoding maps signed integers to unsigned integers so that small absolute values (positive or negative)
have small encoded values:

| Original | ZigZag Encoded |
| -------- | -------------- |
| 0        | 0              |
| -1       | 1              |
| 1        | 2              |
| -2       | 3              |
| 2        | 4              |
| ...      | ...            |

#### unsigned int64

- size: 8 bytes
- byte order: raw bytes of little endian order

#### unsigned varint64

- size: 1~9 bytes

Uses PVL (Progressive Variable-Length) encoding:

```
function write_varuint64(value):
    while value >= 0x80:
        buffer.write_byte((value & 0x7F) | 0x80)
        value = value >> 7
    buffer.write_byte(value)
```

| Value Range   | Bytes |
| ------------- | ----- |
| 0 ~ 127       | 1     |
| 128 ~ 16383   | 2     |
| ...           | ...   |
| 2^56 ~ 2^63-1 | 9     |

#### unsigned hybrid int64 (TAGGED_UINT64)

- size: 4 or 9 bytes

Optimized for unsigned values that fit in 31 bits (common case for IDs, sizes, counts, etc.):

```
if value in [0, 2147483647]:  // fits in 31 bits (2^31 - 1), full unsigned range
    write 4 bytes: ((int32) value) << 1  // bit 0 is 0, indicating 4-byte encoding
else:
    write 1 byte:  0x01                  // bit 0 is 1, indicating 9-byte encoding
    write 8 bytes: value as little-endian uint64
```

Reading:

```
first_int32 = read_int32_le()
if (first_int32 & 1) == 0:
    return (uint64)(first_int32 >> 1)  // 4-byte encoding, unsigned
else:
    return read_uint64_le()            // read remaining 8 bytes
```

Note: TAGGED_UINT64 uses the full 31 bits for positive values [0, 2^31-1], compared to TAGGED_INT64 which splits the range for signed values [-2^30, 2^30-1].

#### VarUint36Small

A specialized encoding used for string headers that combines size (up to 36 bits) with encoding flags:

```
// Write: encodes (size << 2) | encoding_flags
function write_varuint36_small(value):
    if value < 0x80:
        buffer.write_byte(value)
    else:
        // Standard varint encoding for values >= 128
        write_varuint64(value)
```

This encoding is optimized for the common case where string length fits in 7 bits (strings < 32 characters).

#### signed int64

- size: 8 bytes
- byte order: raw bytes of little endian order

#### signed varint64

- size: 1~9 bytes

Uses ZigZag encoding first, then PVL varint:

```
// Encode
zigzag_value = (value << 1) ^ (value >> 63)
write_varuint64(zigzag_value)

// Decode
zigzag_value = read_varuint64()
value = (zigzag_value >> 1) ^ (-(zigzag_value & 1))
```

#### signed hybrid int64 (TAGGED_INT64)

- size: 4 or 9 bytes

Optimized for small signed values:

```
if value in [-1073741824, 1073741823]:  // fits in 30 bits + sign ([-2^30, 2^30-1])
    write 4 bytes: ((int32) value) << 1  // bit 0 is 0, indicating 4-byte encoding
else:
    write 1 byte:  0x01                  // bit 0 is 1, indicating 9-byte encoding
    write 8 bytes: value as little-endian int64
```

Reading:

```
first_int32 = read_int32_le()
if (first_int32 & 1) == 0:
    return (int64)(first_int32 >> 1)  // 4-byte encoding, sign-extended
else:
    return read_int64_le()            // read remaining 8 bytes
```

Note: TAGGED_INT64 uses 30 bits + sign for values [-2^30, 2^30-1], while TAGGED_UINT64 uses full 31 bits for unsigned values [0, 2^31-1].

#### float8

- size: 1 byte
- format:
  - float8 has 4 kinds: float8 kind enum: float8_e4m3fn, float8_e4m3fnuz, float8_e5m2, float8_e5m2fnuz
  - when serialize as field, write raw 8 bits as one byte directly
  - when serialize as an object: write type kind as a byte, then write value byte

#### float16

- size: 2 bytes
- format: encode the specified floating-point value according to the IEEE 754 standard binary16 format, preserving NaN values, then write as binary by little endian order.

#### bfloat16

- size: 2 bytes
- format: encode the specified floating-point value according to the IEEE 754 standard bfloat16 format, preserving NaN values, then write as binary by little endian order.

#### float32

- size: 4 byte
- format: encode the specified floating-point value according to the IEEE 754 floating-point "single format" bit layout,
  preserving Not-a-Number (NaN) values, then write as binary by little endian order.

#### float64

- size: 8 byte
- format: encode the specified floating-point value according to the IEEE 754 floating-point "double format" bit layout,
  preserving Not-a-Number (NaN) values. then write as binary by little endian order.

### string

Format:

```
| varuint36_small: (size << 2) | encoding | binary data |
```

#### String Header

The header is encoded using `varuint36_small` format, which combines the byte length and encoding type:

```
header = (byte_length << 2) | encoding_type
```

| Encoding Type | Value | Description                             |
| ------------- | ----- | --------------------------------------- |
| LATIN1        | 0     | ISO-8859-1 single-byte encoding         |
| UTF16         | 1     | UTF-16 encoding (2 bytes per code unit) |
| UTF8          | 2     | UTF-8 variable-length encoding          |
| Reserved      | 3     | Reserved for future use                 |

#### Encoding Algorithm

**Writing:**

```
function write_string(str):
    bytes = encode_to_bytes(str, chosen_encoding)
    header = (bytes.length << 2) | encoding_type
    buffer.write_varuint36_small(header)
    buffer.write_bytes(bytes)
```

**Reading:**

```
function read_string():
    header = buffer.read_varuint36_small()
    encoding = header & 0x03
    byte_length = header >> 2
    bytes = buffer.read_bytes(byte_length)
    return decode_bytes(bytes, encoding)
```

#### Encoding Selection by Language

**Writing:**

| Language     | Encoding Strategy                                        |
| ------------ | -------------------------------------------------------- |
| Java (JDK8)  | Detect at runtime: LATIN1 if all chars < 256, else UTF16 |
| Java (JDK9+) | Use String's internal coder: LATIN1 or UTF16             |
| Python       | Can write LATIN1, UTF16, or UTF8 based on string content |
| C++          | UTF8 (`std::string`) or UTF16 (`std::u16string`)         |
| Rust         | UTF8 (`String`)                                          |
| Go           | UTF8 (`string`)                                          |
| JavaScript   | UTF8                                                     |

**Reading:** All languages support decoding all three encodings (LATIN1, UTF16, UTF8).

**Recommendation:** Select encoding based on maximum performance - use the encoding that matches the language's native string representation to avoid conversion overhead.

#### Empty String

Empty strings are encoded with header `0` (length 0, any encoding) followed by no data bytes.

### duration

Duration is an absolute length of time, independent of any calendar/timezone, as a count of seconds and nanoseconds.

Format:

```
| signed varint64: seconds | signed int32: nanoseconds |
```

- `seconds`: Number of seconds in the duration, encoded as a signed varint64. Can be positive or negative.
- `nanoseconds`: Nanosecond adjustment to the duration, encoded as a signed int32. Value range is [0, 999,999,999] for positive durations, and [-999,999,999, 0] for negative durations.

Notes:

- The duration is stored as two separate fields to maintain precision and avoid overflow issues.
- Seconds are encoded using varint64 for compact representation of common duration values.
- Nanoseconds are stored as a fixed int32 since the range is limited.
- The sign of the duration is determined by the seconds field. When seconds is 0, the sign is determined by nanoseconds.

### collection/list

Format:

```
| varuint32: length | 1 byte elements header | [optional type info] | elements data |
```

#### Elements Header

The elements header is a single byte that encodes metadata about the collection elements to optimize serialization:

```
| bit 7-4 (reserved) |    bit 3    |      bit 2       |   bit 1  |   bit 0   |
+--------------------+-------------+------------------+----------+-----------+
|      reserved      | is_same_type| is_decl_elem_type| has_null | track_ref |
```

| Bit | Name              | Value | Meaning when SET (1)                    | Meaning when UNSET (0)                  |
| --- | ----------------- | ----- | --------------------------------------- | --------------------------------------- |
| 0   | track_ref         | 0x01  | Track references for elements           | Don't track element references          |
| 1   | has_null          | 0x02  | Collection may contain null elements    | No null elements (skip null checks)     |
| 2   | is_decl_elem_type | 0x04  | Elements are the declared generic type  | Element types differ from declared type |
| 3   | is_same_type      | 0x08  | All elements have the same runtime type | Elements have different runtime types   |

**Common header values:**

| Header | Hex | Meaning                                                        |
| ------ | --- | -------------------------------------------------------------- |
| 0x0C   | 12  | Declared type + same type, non-null, no ref tracking (optimal) |
| 0x0D   | 13  | Declared type + same type, non-null, with ref tracking         |
| 0x0E   | 14  | Declared type + same type, may have nulls, no ref tracking     |
| 0x08   | 8   | Same type but not declared type (type info written once)       |
| 0x00   | 0   | Different types, non-null, no ref tracking (type per element)  |

#### Type Info After Header

When `is_decl_elem_type` (bit 2) is NOT set, the element type info is written once after the header if `is_same_type` (bit 3) is set:

```
| header (0x08) | type_id (varuint32) | elements... |
```

When both `is_decl_elem_type` and `is_same_type` are NOT set, type info is written per element.

#### Element Serialization Based on Header

The header determines how each element is serialized:

#### elements data

Based on the elements header, the serialization of elements data may skip `ref flag`/`null flag`/`element type info`.

```python
fory = ...
buffer = ...
elems = ...
if element_type_is_same:
    if not is_declared_type:
        fory.write_type(buffer, elem_type)
    elem_serializer = get_serializer(...)
    if track_ref:
        for elem in elems:
            if not ref_resolver.write_ref_or_null(buffer, elem):
                elem_serializer.write(buffer, elem)
    elif has_null:
        for elem in elems:
            if elem is None:
                buffer.write_byte(null_flag)
            else:
                buffer.write_byte(not_null_flag)
                elem_serializer.write(buffer, elem)
    else:
        for elem in elems:
            elem_serializer.write(buffer, elem)
else:
    if track_ref:
        for elem in elems:
            fory.write_ref(buffer, elem)
    elif has_null:
        for elem in elems:
            fory.write_nullable(buffer, elem)
    else:
        for elem in elems:
            fory.write_value(buffer, elem)
```

[`CollectionSerializer#writeElements`](https://github.com/apache/fory/blob/20a1a78b17a75a123a6f5b7094c06ff77defc0fe/java/fory-core/src/main/java/org/apache/fory/serializer/collection/CollectionLikeSerializer.java#L302)
can be taken as an example.

### array

#### primitive array

Primitive array are taken as a binary buffer, serialization will just write the length of array size as an unsigned int,
then copy the whole buffer into the stream.

Such serialization won't compress the array. If users want to compress primitive array, users need to register custom
serializers for such types or mark it as list type.

Float array specifics:

- float16/bfloat16 array: write `varuint` length, then raw bytes in little endian order.
- float8 array: write element type kind as a byte, then `varuint` length, then raw bytes in little endian order.

#### Multi-dimensional arrays

Xlang does not define a dedicated tensor encoding. Multi-dimensional arrays are serialized as
nested lists, while one-dimensional primitive arrays use the `*_ARRAY` type IDs.

#### object array

Object array is serialized using the list format. Object component type will be taken as list element
generic type.

### map

Map uses a chunk-based format to handle heterogeneous key-value pairs efficiently:

```
| varuint32: total_size | chunk_1 | chunk_2 | ... | chunk_n |
```

#### Map Chunk Format

Each chunk contains up to 255 key-value pairs with the same metadata characteristics:

```
|    1 byte    |     1 byte     |        variable bytes        |
+--------------+----------------+------------------------------+
|  KV header   |  chunk size N  |  N key-value pairs (N*2 obj) |
```

#### KV Header Bits

The KV header is a single byte encoding metadata for both keys and values:

```
|  bit 7-6   |     bit 5     |     bit 4    |     bit 3     |     bit 2     |     bit 1    |     bit 0     |
+------------+---------------+--------------+---------------+---------------+--------------+---------------+
|  reserved  | val_decl_type | val_has_null | val_track_ref | key_decl_type | key_has_null | key_track_ref |
```

| Bit | Name          | Value | Meaning when SET (1)                     |
| --- | ------------- | ----- | ---------------------------------------- |
| 0   | key_track_ref | 0x01  | Track references for keys                |
| 1   | key_has_null  | 0x02  | Keys may be null (rare, usually invalid) |
| 2   | key_decl_type | 0x04  | Key is the declared generic type         |
| 3   | val_track_ref | 0x08  | Track references for values              |
| 4   | val_has_null  | 0x10  | Values may be null                       |
| 5   | val_decl_type | 0x20  | Value is the declared generic type       |

**Common KV header values:**

| Header | Hex | Meaning                                                             |
| ------ | --- | ------------------------------------------------------------------- |
| 0x24   | 36  | Key + value are declared types, non-null, no ref tracking (optimal) |
| 0x2C   | 44  | Key + value declared types, value tracks refs                       |
| 0x34   | 52  | Key + value declared types, value may be null                       |
| 0x00   | 0   | Key + value not declared types, non-null, no ref tracking           |

#### Chunk Size

- Maximum chunk size: 255 pairs (fits in 1 byte)
- When key or value is null, that entry is serialized as a separate chunk with implicit size 1 (chunk size byte is skipped)
- Reader tracks accumulated count against total map size to know when to stop reading chunks

#### Why Chunk-Based Format?

Map iteration is expensive. Computing a single header for all pairs would require two passes. The chunk-based
approach allows:

1. **Optimistic prediction**: Use first key-value pair to predict header
2. **Adaptive chunking**: Start new chunk if prediction fails for a pair
3. **Efficient reading**: Most maps fit in single chunk (< 255 pairs)
4. **Memory efficiency**: Minimal overhead for common homogeneous maps

#### Why serialize chunk by chunk?

When fory will use first key-value pair to predict header optimistically, it can't know how many pairs have same
meta(tracking kef ref, key has null and so on). If we don't write chunk by chunk with max chunk size, we must write at
least `X` bytes to take up a place for later to update the number which has same elements, `X` is the num_bytes for
encoding varint encoding of map size.

And most map size are smaller than 255, if all pairs have same data, the chunk will be 1. This is common in golang/rust,
which object are not reference by default.

Also, if only one or two keys have different meta, we can make it into a different chunk, so that most pairs can share
meta.

The implementation can accumulate read count with map size to decide whether to read more chunks.

### enum

Enums are serialized as an unsigned var int. If the order of enum values change, the deserialized enum value may not be
the value users expect. In such cases, users must register enum serializer by make it write enum value as an enumerated
string with unique hash disabled.

### timestamp

Timestamp represents a point in time independent of any calendar/timezone. It is encoded as:

- `seconds` (int64): seconds since Unix epoch (1970-01-01T00:00:00Z)
- `nanos` (uint32): nanosecond adjustment within the second

On write, implementations must normalize negative timestamps so that `nanos` is always in `[0, 1_000_000_000)`.
This is a fixed-size 12-byte payload (8 bytes seconds + 4 bytes nanos).

### date

Date represents a date without timezone. It is encoded as an `int32` count of days since the Unix epoch
(1970-01-01). This is a fixed-size 4-byte payload.

### decimal

Not supported for now.

### struct

Struct means object of `class/pojo/struct/bean/record` type. Struct values are serialized by writing
fields in Fory order. The type meta before the value is written according to the rules in
[Type Meta](#type-meta).

#### Field order

Field order must be deterministic and identical across languages. This section defines the
language-neutral ordering algorithm; implementations must follow the rules here rather than any
language-specific helper classes.

##### Step 1: Field identifier

For every field, compute a stable identifier used for ordering:

- If a tag ID is configured (e.g., `@ForyField(id=...)`), use the tag ID as a decimal string.
- Otherwise, use the field name converted to `snake_case`.

Tag IDs must be unique within a type; duplicate tag IDs are invalid.

##### Step 2: Group assignment

Assign each field to exactly one group in the following order:

1. **Primitive (non-nullable)**: primitive or boxed numeric/boolean types with `nullable=false`.
2. **Primitive (nullable)**: primitive or boxed numeric/boolean types with `nullable=true`.
3. **Built-in (non-container)**: internal type IDs that are not user-defined and not UNKNOWN,
   excluding collections and maps (for example: STRING, TIME types, UNION/TYPED_UNION/NAMED_UNION,
   primitive arrays).
4. **Collection**: list/set/object-array fields. Non-primitive arrays are treated as LIST for
   ordering purposes.
5. **Map**: map fields.
6. **Other**: user-defined enum/struct/ext and UNKNOWN types.

##### Step 3: Intra-group ordering

Within each group, apply the following sort keys in order until a difference is found:

**Primitive groups (1 and 2):**

1. **Compression category**: fixed-size numeric and boolean types first, then compressed numeric
   types (`VARINT32`, `VAR_UINT32`, `VARINT64`, `VAR_UINT64`, `TAGGED_INT64`, `TAGGED_UINT64`).
2. **Primitive size** (descending): 8-byte > 4-byte > 2-byte > 1-byte.
3. **Internal type ID** (descending) as a tie-breaker for equal sizes.
4. **Field identifier** (lexicographic ascending).

**Built-in / Collection / Map groups (3-5):**

1. **Internal type ID** (ascending).
2. **Field identifier** (lexicographic ascending).

**Other group (6):**

1. **Field identifier** (lexicographic ascending).

If two fields still compare equal after the rules above, preserve a deterministic order by
comparing declaring class name and then the original field name. This tie-breaker should be
reachable only in invalid schemas (e.g., duplicate tag IDs).

##### Notes

- The ordering above is used for serialization order and TypeDef field lists. Schema hashes use
  the field identifier ordering described in the schema hash section.
- Collection/map normalization is required so peers with different concrete types (e.g.,
  `List` vs `Collection`) still agree on ordering.
- The compressed numeric rule is critical for cross-language consistency: compressed integer
  fields are always placed after all fixed-width integer fields.

#### Schema consistent (meta share disabled)

Object value layout:

```
| [optional 4-byte schema hash] | field values |
```

The schema hash is written only when class-version checking is enabled. It is the low 32 bits of a
MurmurHash3 x64_128 of the struct fingerprint string:

- For each field, build `<field_id_or_name>,<type_id>,<ref>,<nullable>;`.
- Field identifier is the tag ID if present, otherwise the snake_case field name.
- Sort by field identifier lexicographically before concatenation.

Field values are serialized in Fory order. Primitive fields are written as raw values (nullable
primitives include a null flag). Non-primitive fields write ref/null flags as needed and then the
value; polymorphic fields include type meta.

#### Compatible mode (meta share enabled)

The field value layout is the same as schema-consistent mode, but the type meta for
`COMPATIBLE_STRUCT` and `NAMED_COMPATIBLE_STRUCT` uses shared TypeDef entries. Deserializers use
TypeDef to map fields by name or tag ID and to honor nullable/ref flags from metadata; unknown fields
are skipped.

### Union

Union values are encoded using three union type IDs so the union schema identity lives in type meta (like
`STRUCT/ENUM/EXT`) and is easy to carry inside `Any`.

#### IDL syntax

```fdl
union Contact [id=0] {
  string email = 1;
  int32  phone = 2;
}
```

Rules:

- Each union alternative MUST have a stable tag number (`= 1`, `= 2`, ...).
- Tag numbers MUST be unique within the union and MUST NOT be reused.

#### Type IDs and type meta

| Type ID | Name        | Meaning                                              |
| ------: | ----------- | ---------------------------------------------------- |
|      33 | UNION       | Union value, schema identity not embedded            |
|      34 | TYPED_UNION | Union value with registered numeric type ID          |
|      35 | NAMED_UNION | Union value with embedded type name / shared TypeDef |

Type meta encoding:

- `UNION (33)`: no additional type meta payload.
- `TYPED_UNION (34)`: write `user_type_id` as varuint32 after the type ID.
- `NAMED_UNION (35)`: followed by named type meta (namespace + type name, or shared TypeDef marker/body).

#### Union value payload

A union payload is:

```
| case_id (varuint32) | case_value (Any-style value) |
```

`case_id` is the union alternative tag number.

`case_value` MUST be encoded as a full xlang value:

```
| field_ref_meta | field_value_type_meta | field_value_bytes |
```

This is required even for primitives so unknown alternatives can be skipped safely.

#### Wire layouts

**UNION (schema known from context)**

```
| ... outer ref meta ... | type_id=UNION(33) | case_id | case_value |
```

**TYPED_UNION (schema identified by numeric id)**

```
| ... outer ref meta ... | type_id=TYPED_UNION(34) | user_type_id | case_id | case_value |
```

user_type_id: varuint32 numeric registration ID for the union schema.

**NAMED_UNION (schema embedded by name/typedef)**

```
| ... outer ref meta ... | type_id=NAMED_UNION(35) | name_or_typedef | case_id | case_value |
```

#### Decoding rules

1. Read outer ref meta and `type_id`.
2. If `TYPED_UNION`, read `user_type_id` and resolve the union schema by ID.
3. If `NAMED_UNION`, read named type meta and resolve the union schema.
4. Read `case_id`.
5. Read `case_value` as Any-style value (ref meta + type meta + value).

If `case_id` is unknown, the decoder MUST still consume the case value using `field_value_type_meta` and
standard `skipValue(type_id)`.

#### When to use each type ID

- Use `UNION` when the union schema is known from context.
- Use `TYPED_UNION` for dynamic containers when numeric registration is available.
- Use `NAMED_UNION` when name-based resolution is preferred or required.

#### Compatibility notes

- `case_id` is a stable identifier; added alternatives are forward compatible and unknown cases can be skipped.

### Type

Type will be serialized using type meta format.

## Common Pitfalls

1. **Byte Order**: Always use little-endian for multi-byte values
2. **Varint Sign Extension**: Ensure proper handling of signed vs unsigned varints
3. **Reference ID Ordering**: IDs must be assigned in serialization order
4. **Field Order Consistency**: Must match exactly across languages in schema-consistent mode; in compatible mode, match by TypeDef field names or tag IDs
5. **String Encoding**: Use best encoding for current language
6. **Null Handling**: Different languages represent null differently
7. **Empty Collections**: Still write length (0) and header byte
8. **Schema Hash Calculation**: Must use the same fingerprint and MurmurHash3 algorithm across languages when enabled

## Language Implementation Guidelines

See [Xlang Implementation Guide](xlang_implementation_guide.md) documentation.
