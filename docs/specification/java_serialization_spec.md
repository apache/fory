---
title: Java Serialization Format
sidebar_position: 1
id: fory_java_serialization_spec
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

## Spec overview

Apache Fory™ Java Serialization is an automatic object serialization framework that supports reference and polymorphism. Apache Fory™
will
convert an object from/to fory java serialization binary format. Apache Fory™ has two core concepts for java serialization:

- **Apache Fory™ Java Binary format**
- **Framework to convert object to/from Apache Fory™ Java Binary format**

The serialization format is a dynamic binary format. The dynamics and reference/polymorphism support make Apache Fory™ flexible,
much more easy to use, but
also introduce more complexities compared to static serialization frameworks. So the format will be more complex.

Here is the overall format:

```
| fory header | object ref meta | object class meta | object value data |
```

The data are serialized using little endian byte order overall. If bytes swap is costly for some object,
Fory will write the byte order for that object into the data instead of converting it to little endian.

## Fory header

Fory header consists starts one byte:

```
|     4 bits    | 1 bit | 1 bit | 1 bit  | 1 bit |          optional 4 bytes          |
+---------------+-------+-------+--------+-------+------------------------------------+
| reserved bits |  oob  | xlang | endian | null  | unsigned int for meta start offset |
```

- null flag: 1 when object is null, 0 otherwise. If an object is null, other bits won't be set.
- endian flag: 1 when data is encoded by little endian, 0 for big endian.
- xlang flag: 1 when serialization uses xlang format, 0 when serialization uses Fory java format.
- oob flag: 1 when passed `BufferCallback` is not null, 0 otherwise.

If meta share mode is enabled, an uncompressed unsigned int is appended to indicate the start offset of metadata.

## Reference Meta

Reference tracking handles whether the object is null, and whether to track reference for the object by writing
corresponding flags and maintaining internal state.

Reference flags:

| Flag                | Byte Value | Description                                                                                                                                             |
| ------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| NULL FLAG           | `-3`       | This flag indicates the object is a null value. We don't use another byte to indicate REF, so that we can save one byte.                                |
| REF FLAG            | `-2`       | This flag indicates the object is already serialized previously, and fory will write a ref id with unsigned varint format instead of serialize it again |
| NOT_NULL VALUE FLAG | `-1`       | This flag indicates the object is a non-null value and fory doesn't track ref for this type of object.                                                  |
| REF VALUE FLAG      | `0`        | This flag indicates the object is referencable and the first time to serialize.                                                                         |

When reference tracking is disabled globally or for specific types, or for certain types within a particular
context(e.g., a field of a class), only the `NULL` and `NOT_NULL VALUE` flags will be used for reference meta.

## Class Meta

Fory supports to register class by an optional id, the registration can be used for security check and class
identification.
If a class is registered, it will have a user-provided or an auto-growing unsigned int i.e. `class_id`.

Depending on whether meta share mode and registration is enabled for current class, Fory will write class meta
differently.

### Schema consistent

If schema consistent mode is enabled globally or enabled for current class, class meta will be written as follows:

- If class is registered, it will be written as a fory unsigned varint: `class_id << 1`.
- If class is not registered:
  - If class is not an array, fory will write one byte `0bxxxxxxx1` first, then write class name.
    - The first little bit is `1`, which is different from first bit `0` of
      encoded class id. Fory can use this information to determine whether to read class by class id for
      deserialization.
  - If class is not registered and class is an array, fory will write one byte `dimensions << 1 | 1` first, then write
    component
    class subsequently. This can reduce array class name cost if component class is or will be serialized.
  - Class will be written as two enumerated fory unsigned by default: `package name` and `class name`. If meta share
    mode is
    enabled,
    class will be written as an unsigned varint which points to index in `MetaContext`.

### Schema evolution

If schema evolution mode is enabled globally or enabled for current class, class meta will be written as follows:

- If meta share mode is not enabled, class meta will be written as schema consistent mode. Additionally, field meta such
  as field type
  and name will be written with the field value using a key-value like layout.
- If meta share mode is enabled, class meta will be written as a meta-share encoded binary if class hasn't been written
  before, otherwise an unsigned varint id which references to previous written class meta will be written.

## Meta share

> This mode will forbid streaming writing since it needs to look back for update the start offset after the whole object
> graph
> writing and meta collecting is finished. Only in this way we can ensure deserialization failure doesn't lost shared
> meta.
> Meta streamline will be supported in the future for enclosed meta sharing which doesn't cross multiple serializations
> of different objects.

For Schema consistent mode, class will be encoded as an enumerated string by full class name. Here we mainly describe
the meta layout for schema evolution mode:

```
|  8 bytes global meta header   |  1~2 bytes  |   variable bytes   |  variable bytes   | variable bytes |
+-------------------------------+-------------|--------------------+-------------------+----------------+
| 50 bits hash + 14 bits header | type header | current class meta | parent class meta |      ...       |
```

Class meta are encoded from parent class to leaf class, only class with serializable fields will be encoded.

### Global meta header

Meta header is a 64 bits number value encoded in little endian order.

- lower 12 bits are used to encode meta size. If meta size `>= 0b1111_1111_1111`, then write
  `meta_ size - 0b1111_1111_1111` next.
- 13rd bit is used to indicate whether to write fields meta. When this class is schema-consistent or use registered
  serializer, fields meta will be skipped. Class Meta will be used for share namespace + type name only.
- 14rd bit is used to indicate whether meta is compressed.
- Other 50 bits is used to store the unique hash of `flags + all layers class meta`.

### Type header

- Lowest 4 digits `0b0000~0b1110` are used to record num classes. `0b1111` is preserved to indicate that Fory need to
  read more bytes for length using Fory unsigned int encoding. If current class doesn't has parent class, or parent
  class doesn't have fields to serialize, or we're in a context which serialize fields of current class
  only(`ObjectStreamSerializer#SlotInfo` is an example), num classes will be 1.
- Other 4 bits are preserved to future extensions.
- If num classes are greater than or equal to `0b1111`, write `num_classes - 0b1111` as varuint next.

### Single layer class meta

```
|      unsigned varint       |      meta string      |     meta string     |  field info: variable bytes   | variable bytes  | ... |
+----------------------------+-----------------------+---------------------+-------------------------------+-----------------+-----+
| num fields + register flag | header + package name | header + class name | header + type id + field name | next field info | ... |
```

- num fields: encode `num fields << 1 | register flag(1 when class registered)` as unsigned varint.
  - If class is registered, then an unsigned varint class id will be written next, package and class name will be
    omitted.
  - If current class is schema consistent, then num field will be `0` to flag it.
  - If current class isn't schema consistent, then num field will be the number of compatible fields. For example,
    users
    can use tag id to mark some field as compatible field in schema consistent context. In such cases, schema
    consistent
    fields will be serialized first, then compatible fields will be serialized next. At deserialization, Fory will use
    fields info of those fields which aren't annotated by tag id for deserializing schema consistent fields, then use
    fields info in meta for deserializing compatible fields.
- Package name encoding(omitted when class is registered):
  - encoding algorithm: `UTF8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL`
  - Header: `6 bits size | 2 bits encoding flags`. The `6 bits size: 0~63` will be used to indicate size `0~63`,
    the value `63` the size need more byte to read, the encoding will encode `size - 63` as a varint next.
- Class name encoding(omitted when class is registered):
  - encoding algorithm: `UTF8/LOWER_UPPER_DIGIT_SPECIAL/FIRST_TO_LOWER_SPECIAL/ALL_TO_LOWER_SPECIAL`
  - header: `6 bits size | 2 bits encoding flags`. The `6 bits size: 0~63` will be used to indicate size `0~63`,
    the value `63` the size need more byte to read, the encoding will encode `size - 63` as a varint next.
- Field info:
  - header(8
    bits): `3 bits size + 2 bits field name encoding + polymorphism flag + nullability flag + ref tracking flag`.
    Users can use annotation to provide those info.
    - 2 bits field name encoding:
      - encoding: `UTF8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL/TAG_ID`
      - If tag id is used, i.e. field name is written by an unsigned varint tag id. 2 bits encoding will be `11`.
    - size of field name:
      - The `3 bits size: 0~7` will be used to indicate length `1~7`, the value `6` the size read more bytes,
        the encoding will encode `size - 7` as a varint next.
      - If encoding is `TAG_ID`, then num_bytes of field name will be used to store tag id.
    - ref tracking: when set to 1, ref tracking will be enabled for this field.
    - nullability: when set to 1, this field can be null.
    - polymorphism: when set to 1, the actual type of field will be the declared field type even the type if
      not `final`.
  - type id:
    - For registered type-consistent classes, it will be the registered class id.
    - Otherwise it will be encoded as `OBJECT_ID` if it isn't `final` and `FINAL_OBJECT_ID` if it's `final`. The
      meta for such types is written separately instead of inlining here is to reduce meta space cost if object of
      this type is serialized in current object graph multiple times, and the field value may be null too.
  - Field name: If type id is set, type id will be used instead. Otherwise meta string encoding length and data will
    be written instead.

Field order are left as implementation details, which is not exposed to specification, the deserialization need to
resort fields based on Fory field comparator. In this way, fory can compute statistics for field names or types and
using a more compact encoding.

### Other layers class meta

Same encoding algorithm as the previous layer except:

- header + package name:
  - Header:
    - If package name has been written before: `varint index + sharing flag(set)` will be written
    - If package name hasn't been written before:
      - If meta string encoding is `LOWER_SPECIAL` and the length of encoded string `<=` 64, then header will be
        `6 bits size + encoding flag(set) + sharing flag(unset)`.
      - Otherwise, header will
        be `3 bits unset + 3 bits encoding flags + encoding flag(unset) + sharing flag(unset)`

## Meta String

Meta string is mainly used to encode meta strings such as class name and field names.

### Encoding Algorithms

String binary encoding algorithm:

| Algorithm                 | Pattern       | Description                                                                                                                                                                                                                                                                             |
| ------------------------- | ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| LOWER_SPECIAL             | `a-z._$\|`    | every char is written using 5 bits, `a-z`: `0b00000~0b11001`, `._$\|`: `0b11010~0b11101`, prepend one bit at the start to indicate whether strip last char since last byte may have 7 redundant bits(1 indicates strip last char)                                                       |
| LOWER_UPPER_DIGIT_SPECIAL | `a-zA-Z0~9._` | every char is written using 6 bits, `a-z`: `0b00000~0b11001`, `A-Z`: `0b11010~0b110011`, `0~9`: `0b110100~0b111101`, `._`: `0b111110~0b111111`, prepend one bit at the start to indicate whether strip last char since last byte may have 7 redundant bits(1 indicates strip last char) |
| UTF-8                     | any chars     | UTF-8 encoding                                                                                                                                                                                                                                                                          |

Encoding flags:

| Encoding Flag             | Pattern                                                       | Encoding Algorithm                                                                                                                                          |
| ------------------------- | ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| LOWER_SPECIAL             | every char is in `a-z._$\|`                                   | `LOWER_SPECIAL`                                                                                                                                             |
| FIRST_TO_LOWER_SPECIAL    | every char is in `a-z[c1,c2]` except first char is upper case | replace first upper case char to lower case, then use `LOWER_SPECIAL`                                                                                       |
| ALL_TO_LOWER_SPECIAL      | every char is in `a-zA-Z[c1,c2]`                              | replace every upper case char by `\|` + `lower case`, then use `LOWER_SPECIAL`, use this encoding if it's smaller than Encoding `LOWER_UPPER_DIGIT_SPECIAL` |
| LOWER_UPPER_DIGIT_SPECIAL | every char is in `a-zA-Z[c1,c2]`                              | use `LOWER_UPPER_DIGIT_SPECIAL` encoding if it's smaller than Encoding `FIRST_TO_LOWER_SPECIAL`                                                             |
| UTF8                      | any utf-8 char                                                | use `UTF-8` encoding                                                                                                                                        |
| Compression               | any utf-8 char                                                | lossless compression                                                                                                                                        |

Notes:

- For package name encoding, `c1,c2` should be `._`; For field/type name encoding, `c1,c2` should be `_$`;
- Depending on cases, one can choose encoding `flags + data` jointly, uses 3 bits of first byte for flags and other
  bytes
  for data.

### Shared meta string

The shared meta string format consists of header and encoded string binary. Header of encoded string binary will be
inlined
in shared meta header.

Header is written using little endian order, Fory can read this flag first to determine how to deserialize the data.

#### Write by data

If string hasn't been written before, the data will be written as follows:

```
| unsigned varint: string binary size + 1 bit: not written before | 56 bits: unique hash | 3 bits encoding flags + string binary |
```

If string binary size is less than `16` bytes, the hash will be omitted to save spaces. Unique hash can be omitted too
if caller pass a flag to disable it. In such cases, the format will be:

```
| unsigned varint: string binary size + 1 bit: not written before  | 3 bits encoding flags + string binary |
```

#### Write by ref

If string has been written before, the data will be written as follows:

```
| unsigned varint: written string id + 1 bit: written before |
```

## Value Format

### Basic types

#### Bool

- size: 1 byte
- format: 0 for `false`, 1 for `true`

#### Byte

- size: 1 byte
- format: write as pure byte.

#### Short

- size: 2 byte
- byte order: little endian order

#### Char

- size: 2 byte
- byte order: little endian order

#### Unsigned int

- size: 1~5 byte
- Format: The most significant bit (MSB) in every byte indicates whether to have the next byte. If first bit is set
  i.e. `b & 0x80 == 0x80`, then
  the next byte should be read until the first bit of the next byte is unset.

#### Signed int

- size: 1~5 byte
- Format: First convert the number into positive unsigned int by `(v << 1) ^ (v >> 31)` ZigZag algorithm, then encoding
  it as an unsigned int.

#### Unsigned long

- size: 1~9 byte
- Fory PVL(Progressive Variable-length Long) Encoding:
  - positive long format: first bit in every byte indicates whether to have the next byte. If first bit is set
    i.e. `b & 0x80 == 0x80`, then the next byte should be read until the first bit is unset.

#### Signed long

- size: 1~9 byte
- Fory SLI(Small long as int) Encoding:
  - If long is in [-1073741824, 1073741823], encode as 4 bytes int: `| little-endian: ((int) value) << 1 |`
  - Otherwise write as 9 bytes: `| 0b1 | little-endian 8 bytes long |`
- Fory PVL(Progressive Variable-length Long) Encoding:
  - First convert the number into positive unsigned long by `(v << 1) ^ (v >> 63)` ZigZag algorithm to reduce cost of
    small negative numbers, then encoding it as an unsigned long.

#### Float

- size: 4 byte
- format: convert float to 4 bytes int by `Float.floatToRawIntBits`, then write as binary by little endian order.

#### Double

- size: 8 byte
- format: convert double to 8 bytes int by `Double.doubleToRawLongBits`, then write as binary by little endian order.

### String

Format:

```
| header: size << 2 | 2 bits encoding flags | binary data |
```

- `size + encoding` will be concat as a long and encoded as an unsigned var long. The little 2 bits is used for
  encoding:
  0 for `latin`, 1 for `utf-16`, 2 for `utf-8`.
- encoded string binary data based on encoding: `latin/utf-16/utf-8`.

Which encoding to choose:

- For JDK8: fory detect `latin` at runtime, if string is `latin` string, then use `latin` encoding, otherwise
  use `utf-16`.
- For JDK9+: fory use `coder` in `String` object for encoding, `latin`/`utf-16` will be used for encoding.
- If the string is encoded by `utf-8`, then fory will use `utf-8` to decode the data. But currently fory doesn't enable
  utf-8 encoding by default for java. Cross-language string serialization of fory uses `utf-8` by default.

### Collection

> All collection serializers must extend `CollectionLikeSerializer`.

Format:

```
length(unsigned varint) | collection header | elements header | elements data
```

#### Collection header

- For `ArrayList/LinkedArrayList/HashSet/LinkedHashSet`, this will be empty.
- For `TreeSet`, this will be `Comparator`
- For subclass of `ArrayList`, this may be extra object field info.

#### Elements header

In most cases, all collection elements are same type and not null, elements header will encode those homogeneous
information to avoid the cost of writing it for every element. Specifically, there are four kinds of information
which will be encoded by elements header, each use one bit:

- If track elements ref, use the first bit `0b1` of the header to flag it.
- If the collection has null, use the second bit `0b10` of the header to flag it. If ref tracking is enabled for this
  element type, this flag is invalid.
- If the collection element types are the declared type, use the 3rd bit `0b100` of the header to flag it.
- If the collection element types are same, use the 4th bit `0b1000` header to flag it.

By default, all bits are unset, which means all elements won't track ref, all elements are same type, not null and
the actual element is the declared type in the custom class field.

The implementation can generate different deserialization code based read header, and look up the generated code from a
linear map/list.

#### Elements data

Based on the elements header, the serialization of elements data may skip `ref flag`/`null flag`/`element class info`.

`CollectionSerializer#write/read` can be taken as an example.

### Array

#### Primitive array

Primitive array are taken as a binary buffer, serialization will just write the length of array size as an unsigned int,
then copy the whole buffer into the stream.

Such serialization won't compress the array. If users want to compress primitive array, users need to register custom
serializers for such types.

#### Object array

Object array is serialized using the collection format. Object component type will be taken as collection element
generic
type.

### Map

> All Map serializers must extend `MapLikeSerializer`.

Format:

```
| length(unsigned varint) | map header | key value pairs data |
```

#### Map header

- For `HashMap/LinkedHashMap`, this will be empty.
- For `TreeMap`, this will be `Comparator`
- For other `Map`, this may be extra object field info.

#### Map Key-Value data

Map iteration is too expensive, Fory won't compute the header like for collection before since it introduce
[considerable overhead](https://github.com/apache/fory/issues/925).
Users can use `MapFieldInfo` annotation to provide header in advance. Otherwise Fory will use first key-value pair to
predict header optimistically, and update the chunk header if the prediction failed at some pair.

Fory will serialize map chunk by chunk, every chunk has 127 pairs at most.

```
|    1 byte      |     1 byte     | variable bytes  |
+----------------+----------------+-----------------+
|    KV header   | chunk size: N  |   N*2 objects   |
```

KV header:

- If track key ref, use the first bit `0b1` of the header to flag it.
- If the key has null, use the second bit `0b10` of the header to flag it. If ref tracking is enabled for this
  key type, this flag is invalid.
- If the actual key type of map is the declared key type, use the 3rd bit `0b100` of the header to flag it.
- If track value ref, use the 4th bit `0b1000` of the header to flag it.
- If the value has null, use the 5th bit `0b10000` of the header to flag it. If ref tracking is enabled for this
  value type, this flag is invalid.
- If the value type of map is the declared value type, use the 6rd bit `0b100000` of the header to flag it.
- If key or value is null, that key and value will be written as a separate chunk, and chunk size writing will be
  skipped too.

If streaming write is enabled, which means Fory can't update written `chunk size`. In such cases, map key-value data
format will be:

```
|    1 byte      | variable bytes  |
+----------------+-----------------+
|    KV header   |   N*2 objects   |
```

`KV header` will be a header marked by `MapFieldInfo` in java. The implementation can generate different deserialization
code based read header, and look up the generated code from a linear map/list.

### Enum

Enums are serialized as an unsigned var int. If the order of enum values change, the deserialized enum value may not be
the value users expect. In such cases, users must register enum serializer by make it write enum value as an enumerated
string with unique hash disabled.

### Object

Object means object of `pojo/struct/bean/record` type.
Object will be serialized by writing its fields data in fory order.

Depending on schema compatibility, objects will have different formats.

#### Field order

Field will be ordered as following, every group of fields will have its own order:

- primitive fields: larger size type first, smaller later, variable size type last.
- boxed primitive fields: same order as primitive fields
- final fields: same type together, then sorted by field name lexicographically.
- collection fields: same order as final fields
- map fields: same order as final fields
- other fields: same order as final fields

#### Schema consistent

Object fields will be serialized one by one using following format:

```
Primitive field value:
|   var bytes    |
+----------------+
|   value data   |
+----------------+
Boxed field value:
| one byte  |   var bytes   |
+-----------+---------------+
| null flag |  field value  |
+-----------+---------------+
field value of final type with ref tracking:
| var bytes | var objects |
+-----------+-------------+
| ref meta  | value data  |
+-----------+-------------+
field value of final type without ref tracking:
| one byte  | var objects |
+-----------+-------------+
| null flag | field value |
+-----------+-------------+
field value of non-final type with ref tracking:
| one byte  | var bytes | var objects |
+-----------+-------------+-------------+
| ref meta  | class meta  | value data  |
+-----------+-------------+-------------+
field value of non-final type without ref tracking:
| one byte  | var bytes | var objects |
+-----------+------------+------------+
| null flag | class meta | value data |
+-----------+------------+------------+
```

#### Schema evolution

Schema evolution have similar format as schema consistent mode for object except:

- For this object type itself, `schema consistent` mode will write class by id/name, but `schema evolution` mode will
  write class field names, types and other meta too, see [Class meta](#class-meta).
- Class meta of `final custom type` needs to be written too, because peers may not have this class defined.

### Class

Class will be serialized using class meta format.

## Implementation guidelines

- Try to merge multiple bytes into an int/long write before writing to reduce memory IO and bound check cost.
- Read multiple bytes as an int/long, then split into multiple bytes to reduce memory IO and bound check cost.
- Try to use one varint/long to write flags and length together to save one byte cost and reduce memory io.
- Condition branches are less expensive compared to memory IO cost unless there are too many branches.
