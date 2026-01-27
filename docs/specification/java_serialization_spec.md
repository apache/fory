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

## Spec overview

Apache Fory Java serialization is a dynamic binary format for Java object graphs. It supports
shared references, circular references, polymorphism, and optional schema evolution. The format is
stream friendly: shared type metadata is written inline when needed and there is no meta start
offset.

The Java native format is an extension of the xlang wire format and reuses the same core framing
and encodings; see `docs/specification/xlang_serialization_spec.md` for the shared baseline.

Overall layout:

```
| fory header | object ref meta | object type meta | object value data |
```

All data is encoded in little endian byte order. When running on a big endian platform, array
serializers swap byte order on write/read so the on-wire layout remains little endian.

## Fory header

Java native serialization writes a one byte bitmap header. The header layout mirrors the xlang
bitmap and uses the same flag bits.

```
|     5 bits    | 1 bit | 1 bit | 1 bit |
+--------------+-------+-------+-------+
| reserved     |  oob  | xlang | null  |
```

- null flag: 1 when object is null, 0 otherwise. If object is null, other bits are not set.
- xlang flag: 1 when serialization uses xlang format, 0 when serialization uses Java native format.
- oob flag: 1 when `BufferCallback` is not null, 0 otherwise.

If xlang flag is set, a one byte language ID is written after the bitmap. In Java native mode (xlang
flag unset), no language byte is written.

## Reference meta

Reference tracking uses the same flags as the xlang specification.

| Flag                | Byte Value | Description                                                                                              |
| ------------------- | ---------- | -------------------------------------------------------------------------------------------------------- |
| NULL FLAG           | `-3`       | Object is null. No further bytes are written for this object.                                            |
| REF FLAG            | `-2`       | Object was already serialized. Followed by unsigned varint32 reference ID.                               |
| NOT_NULL VALUE FLAG | `-1`       | Object is non-null but reference tracking is disabled for this type. Object data follows immediately.    |
| REF VALUE FLAG      | `0`        | Object is referencable and this is its first occurrence. Object data follows. Assigns next reference ID. |

When reference tracking is disabled globally or for a specific field/type, only `NULL FLAG` and
`NOT_NULL VALUE FLAG` are used.

## Type system and type IDs

Java native serialization uses the unified type ID layout shared with xlang:

```
full_type_id = (user_type_id << 8) | internal_type_id
```

- `internal_type_id` is the low 8 bits describing the kind (enum/struct/ext, named variants, or a
  built-in type).
- `user_type_id` is the numeric registration ID (0-based) for user-defined enum/struct/ext types.
- Named types use `NAMED_*` internal IDs and carry names in metadata rather than embedding a user
  ID.

### Shared internal type IDs (0-32)

Java native mode shares the xlang internal IDs for basic types and user-defined enum/struct/ext
tags. These IDs are stable across languages.

| Type ID | Name                    |
| ------- | ----------------------- |
| 0       | UNKNOWN                 |
| 1       | BOOL                    |
| 2       | INT8                    |
| 3       | INT16                   |
| 4       | INT32                   |
| 5       | VARINT32                |
| 6       | INT64                   |
| 7       | VARINT64                |
| 8       | TAGGED_INT64            |
| 9       | UINT8                   |
| 10      | UINT16                  |
| 11      | UINT32                  |
| 12      | VAR_UINT32              |
| 13      | UINT64                  |
| 14      | VAR_UINT64              |
| 15      | TAGGED_UINT64           |
| 16      | FLOAT16                 |
| 17      | FLOAT32                 |
| 18      | FLOAT64                 |
| 19      | STRING                  |
| 20      | LIST                    |
| 21      | SET                     |
| 22      | MAP                     |
| 23      | ENUM                    |
| 24      | NAMED_ENUM              |
| 25      | STRUCT                  |
| 26      | COMPATIBLE_STRUCT       |
| 27      | NAMED_STRUCT            |
| 28      | NAMED_COMPATIBLE_STRUCT |
| 29      | EXT                     |
| 30      | NAMED_EXT               |
| 31      | UNION                   |
| 32      | NONE                    |

### Java native built-in type IDs

Java native serialization assigns Java-specific built-ins starting at `Types.NONE + 1`.
Type IDs greater than 32 are not shared with xlang; they are only valid in Java native mode.

| Type ID | Name                       | Description                    |
| ------- | -------------------------- | ------------------------------ |
| 33      | VOID_ID                    | java.lang.Void                 |
| 34      | CHAR_ID                    | java.lang.Character            |
| 35      | PRIMITIVE_VOID_ID          | void                           |
| 36      | PRIMITIVE_BOOL_ID          | boolean                        |
| 37      | PRIMITIVE_INT8_ID          | byte                           |
| 38      | PRIMITIVE_CHAR_ID          | char                           |
| 39      | PRIMITIVE_INT16_ID         | short                          |
| 40      | PRIMITIVE_INT32_ID         | int                            |
| 41      | PRIMITIVE_FLOAT32_ID       | float                          |
| 42      | PRIMITIVE_INT64_ID         | long                           |
| 43      | PRIMITIVE_FLOAT64_ID       | double                         |
| 44      | PRIMITIVE_BOOLEAN_ARRAY_ID | boolean[]                      |
| 45      | PRIMITIVE_BYTE_ARRAY_ID    | byte[]                         |
| 46      | PRIMITIVE_CHAR_ARRAY_ID    | char[]                         |
| 47      | PRIMITIVE_SHORT_ARRAY_ID   | short[]                        |
| 48      | PRIMITIVE_INT_ARRAY_ID     | int[]                          |
| 49      | PRIMITIVE_FLOAT_ARRAY_ID   | float[]                        |
| 50      | PRIMITIVE_LONG_ARRAY_ID    | long[]                         |
| 51      | PRIMITIVE_DOUBLE_ARRAY_ID  | double[]                       |
| 52      | STRING_ARRAY_ID            | String[]                       |
| 53      | OBJECT_ARRAY_ID            | Object[]                       |
| 54      | ARRAYLIST_ID               | java.util.ArrayList            |
| 55      | HASHMAP_ID                 | java.util.HashMap              |
| 56      | HASHSET_ID                 | java.util.HashSet              |
| 57      | CLASS_ID                   | java.lang.Class                |
| 58      | EMPTY_OBJECT_ID            | empty object stub              |
| 59      | LAMBDA_STUB_ID             | lambda stub                    |
| 60      | JDK_PROXY_STUB_ID          | JDK proxy stub                 |
| 61      | REPLACE_STUB_ID            | writeReplace/readResolve stub  |
| 62      | NONEXISTENT_META_SHARED_ID | meta-shared unknown class stub |

### Registration and named types

User-defined enum/struct/ext types can be registered by numeric ID or by name.

- Numeric registration: `full_type_id = (user_id << 8) | internal_type_id`.
- Name registration: type meta uses namespace and type name (see below).
- Unregistered types are encoded as named types using namespace = package name and type name =
  simple class name.

Named type selection rules for unregistered types:

- enum -> NAMED_ENUM
- struct-like serializers -> NAMED_STRUCT (or NAMED_COMPATIBLE_STRUCT in compatible mode)
- all other custom serializers -> NAMED_EXT

## Type meta encoding

Every value is written with a type ID followed by optional type metadata:

1. Write `type_id` using varuint32 small7 encoding.
2. For `NAMED_ENUM`, `NAMED_STRUCT`, `NAMED_EXT`, `NAMED_COMPATIBLE_STRUCT`:
   - If meta share is enabled: write shared class meta (streaming format).
   - Otherwise: write namespace and type name as meta strings.
3. For `COMPATIBLE_STRUCT`:
   - If meta share is enabled: write shared class meta (streaming format).
   - Otherwise: no extra meta (type ID is sufficient).
4. All other types: no extra meta.

### Shared class meta (streaming)

When meta share is enabled, Java uses the streaming shared meta protocol and writes TypeDef
bytes inline on first use.

```
| varuint32: index_marker | [class def bytes if new] |

index_marker = (index << 1) | flag
flag = 1 -> reference
flag = 0 -> new type
```

- If `flag == 1`, this is a reference to a previously written type. No class def bytes follow.
- If `flag == 0`, this is a new type definition and class def bytes are written inline.

The index is assigned sequentially in the order types are first encountered.

## Schema modes

Java native serialization supports two schema modes:

- Schema consistent (compatible mode disabled): fields are serialized in a fixed order and no
  ClassDef is required. Type meta uses `STRUCT` or `NAMED_STRUCT` for user-defined classes.
- Schema evolution (compatible mode enabled): fields are serialized with schema evolution metadata
  (ClassDef). Type meta uses `COMPATIBLE_STRUCT` or `NAMED_COMPATIBLE_STRUCT`.

## ClassDef format (compatible mode)

ClassDef is the schema evolution metadata encoded for compatible structs. It is written inline
when shared meta is enabled, or referenced by index when already seen.

### Binary layout

```
| 8 bytes header | [varuint32 extra size] | class meta bytes |
```

Header layout (lower bits on the right):

```
| 50-bit hash | 4 bits reserved | 1 bit compress | 1 bit has_fields_meta | 8-bit size |
```

- size: lower 8 bits. If size equals the mask (0xFF), write extra size as varuint32 and add it.
- compress: set when payload is compressed.
- has_fields_meta: set when field metadata is present.
- reserved: bits 10-13 are reserved for future use and must be zero.
- hash: 50-bit hash of the payload and flags.

### Class meta bytes

Class meta encodes a linearized class hierarchy (from parent to leaf) and field metadata:

```
| num_classes | class_layer_0 | class_layer_1 | ... |

class_layer:
| num_fields << 1 | registered_flag | [type_id if registered] |
| namespace | type_name | field_infos |
```

- `num_classes` stores `(num_layers - 1)` in a single byte.
  - If it equals `0b1111`, read an extra varuint32 small7 and add it.
  - The actual number of layers is `num_classes + 1`.
- `registered_flag` is 1 if the class is registered by numeric ID.
- If registered by ID, the class type ID follows (varuint32 small7).
- If registered by name or unregistered, namespace and type name are written as meta strings.

### Field info

Each field uses a compact header followed by its name bytes (omitted when TAG_ID is used) and its
type info:

```
| field_header | [field_name_bytes] | field_type |
```

`field_header` bits:

- bit 0: trackingRef
- bit 1: nullable
- bits 2-3: field name encoding
- bits 4-6: name length (len-1), or tag ID when TAG_ID is used; value 7 indicates extended length
- bit 7: reserved (0)

Field name encoding:

- 0: UTF8
- 1: ALL_TO_LOWER_SPECIAL
- 2: LOWER_UPPER_DIGIT_SPECIAL
- 3: TAG_ID (field name omitted, tag ID stored in size field)

If length is extended (size==7), an extra varuint32 small7 storing `(len-1) - 7` follows.

### Field type encoding

Field types are encoded with a type tag and optional nested type info. For nested types, the header
includes nullable/trackingRef flags in the low bits.
Top-level field types use the tag only (no flags).

Type tags:

| Tag | Field type                                |
| --- | ----------------------------------------- |
| 0   | Object (ObjectFieldType)                  |
| 1   | Map (MapFieldType)                        |
| 2   | Collection/List/Set (CollectionFieldType) |
| 3   | Array (ArrayFieldType)                    |
| 4   | Enum (EnumFieldType)                      |
| 5+  | Registered type (RegisteredFieldType)     |

Encoding rules:

- ObjectFieldType: write tag 0.
- MapFieldType: write tag 1, then key type, then value type.
- CollectionFieldType: write tag 2, then element type.
- ArrayFieldType: write tag 3, then dimensions, then component type.
- EnumFieldType: write tag 4.
- RegisteredFieldType: write tag `5 + type_id`.

For nested types, nullable/trackingRef flags are stored in the low bits of the header as
`(type_tag << 2) | (nullable << 1) | tracking_ref`.

## Meta string encoding

Namespace, type names, and field names use the same meta string encodings as the xlang spec.

### Package and type names

Header format:

```
| 6 bits size | 2 bits encoding |
```

- size is the byte length of the encoded name.
- if size == 63, write extra length `(size - 63)` as varuint32 small7.

Encodings:

- Package name: UTF8, ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL
- Type name: UTF8, LOWER_UPPER_DIGIT_SPECIAL, FIRST_TO_LOWER_SPECIAL, ALL_TO_LOWER_SPECIAL

### Field names

Field name encoding is described in the ClassDef field header section. When using TAG_ID, the
field name bytes are omitted and the tag ID is stored in the size field.

### Encoding algorithms

See the xlang specification for encoding algorithms and tables:
`docs/specification/xlang_serialization_spec.md#meta-string`.

## Value encodings

This section describes the byte layouts for common built-in serializers used in Java native
serialization. Custom serializers (EXT) may define additional formats but must still follow the
reference and type meta rules described above.

### Primitives

- boolean: 1 byte (0x00 or 0x01).
- byte: 1 byte.
- short: 2 bytes little endian.
- char: 2 bytes little endian (UTF-16 code unit).
- int:
  - fixed: 4 bytes little endian.
  - varint: signed varint32 (ZigZag) when `compressInt` is enabled.
- long:
  - fixed: 8 bytes little endian.
  - varint: signed varint64 (ZigZag) when `longEncoding=VARINT`.
  - tagged: tagged int64 when `longEncoding=TAGGED`.
- float: IEEE 754 float32, little endian.
- double: IEEE 754 float64, little endian.

Varint encodings follow the xlang spec:
`docs/specification/xlang_serialization_spec.md#unsigned-varint32`.

### String

Strings are encoded as:

```
| varuint36_small: (num_bytes << 2) | coder | string bytes |
```

- coder: 2-bit value
  - 0: LATIN1
  - 1: UTF16
  - 2: UTF8
- num_bytes: byte length of the encoded string payload.

UTF16 is encoded as little endian 2-byte code units.

### Enum

- If `serializeEnumByName` is enabled: write enum name as a meta string.
- Otherwise: write enum ordinal as varuint32 small7.

### Binary (byte[])

Primitive byte arrays are encoded as:

```
| varuint32: num_bytes | raw bytes |
```

### Primitive arrays

Primitive arrays use `writePrimitiveArrayWithSize` unless compression is enabled:

```
| varuint32: byte_length | raw bytes |
```

- `compressIntArray`: int[] encoded as `| varuint32: length | varint32... |`.
- `compressLongArray`: long[] encoded as `| varuint32: length | varint64/tagged... |`.

### Object arrays

Object arrays encode length and a monomorphic flag:

```
| varuint32_small7: (length << 1) | mono_flag |
```

- If `mono_flag == 1`, all elements share a known component serializer. Each element uses ref
  flags and the component serializer writes the value.
- If `mono_flag == 0`, each element uses ref flags and writes its own class info and data.

### Collections (List/Set)

Collections encode length and a one-byte elements header:

```
| varuint32_small7: length | elements_header | [elem_class_info] | elements... |
```

`elements_header` bits (see `CollectionFlags`):

- bit 0: TRACKING_REF
- bit 1: HAS_NULL
- bit 2: IS_DECL_ELEMENT_TYPE
- bit 3: IS_SAME_TYPE

If `IS_SAME_TYPE` is set and `IS_DECL_ELEMENT_TYPE` is not set, the element class info is written
once before the elements. Element values then follow with either ref flags (if TRACKING_REF) or
per-element null flags (if HAS_NULL).

If `IS_SAME_TYPE` is not set, each element is written with its own class info and data (and
optionally ref flags).

### Maps

Maps encode entry count and then a sequence of chunks. Each chunk groups entries that share key
and value types.

```
| varuint32_small7: size | chunk_1 | chunk_2 | ... |

chunk (non-null entries):
| header | chunk_size | [key_class_info] | [value_class_info] | entries... |
```

`header` bits (see `MapFlags`):

- bit 0: TRACKING_KEY_REF
- bit 1: KEY_HAS_NULL
- bit 2: KEY_DECL_TYPE
- bit 3: TRACKING_VALUE_REF
- bit 4: VALUE_HAS_NULL
- bit 5: VALUE_DECL_TYPE

If `KEY_DECL_TYPE` or `VALUE_DECL_TYPE` is unset, the corresponding class info is written once at
the start of the chunk. `chunk_size` is a single byte (1..255) and `MAX_CHUNK_SIZE` is 255.

#### Null key/value entries

Entries with null key or null value are encoded as special single-entry chunks without a
`chunk_size` byte:

- null key, non-null value: `NULL_KEY_VALUE_DECL_TYPE*` flags, then value payload
- null value, non-null key: `NULL_VALUE_KEY_DECL_TYPE*` flags, then key payload
- null key and null value: `KV_NULL` header only

These chunks always represent exactly one entry.

### Objects and structs

Object values are encoded as:

```
| ref meta | type meta | field data |
```

Field data is written by the serializer selected by the class info. For standard object
serialization:

- Fields are sorted deterministically using `DescriptorGrouper` order:
  primitives, boxed primitives, built-ins, collections, maps, then other fields, with names sorted
  within each category.
- For compatible mode, `MetaSharedSerializer` uses ClassDef field metadata to read and skip
  unknown fields.
- For each field, the serializer uses field metadata (nullable, trackingRef, polymorphic) to decide
  whether to write ref flags and/or type meta before the field value.

### Extensions (EXT)

Extension types are encoded by their registered serializer. Type meta is still written before the
value as described above. The serializer is responsible for the value layout.

## Out-of-band buffers

When a `BufferCallback` is provided, the oob flag is set in the header and serializers may emit
buffer references instead of inline bytes (for example, large primitive arrays). The out-of-band
buffer protocol is specific to the callback implementation; the main stream only contains
references to those buffers.
