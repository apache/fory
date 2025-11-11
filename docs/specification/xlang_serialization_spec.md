---
title: Xlang Serialization Format
sidebar_position: 0
id: fory_xlang_serialization_spec
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

> Format Version History:
>
> - Version 0.1 - serialization spec formalized

Apache Fory™ xlang serialization is an automatic object serialization framework that supports reference and polymorphism.
Apache Fory™ will convert an object from/to fory xlang serialization binary format.
Apache Fory™ has two core concepts for xlang serialization:

- **Apache Fory™ xlang binary format**
- **Framework implemented in different languages to convert object to/from Apache Fory™ xlang binary format**

The serialization format is a dynamic binary format. The dynamics and reference/polymorphism support make Apache Fory™ flexible,
much more easy to use, but
also introduce more complexities compared to static serialization frameworks. So the format will be more complex.

## Type Systems

### Data Types

- bool: a boolean value (true or false).
- int8: a 8-bit signed integer.
- int16: a 16-bit signed integer.
- int32: a 32-bit signed integer.
- var_int32: a 32-bit signed integer which use fory var_int32 encoding.
- int64: a 64-bit signed integer.
- var_int64: a 64-bit signed integer which use fory PVL encoding.
- sli_int64: a 64-bit signed integer which use fory SLI encoding.
- float16: a 16-bit floating point number.
- float32: a 32-bit floating point number.
- float64: a 64-bit floating point number including NaN and Infinity.
- string: a text string encoded using Latin1/UTF16/UTF-8 encoding.
- enum: a data type consisting of a set of named values. Rust enum with non-predefined field values are not supported as
  an enum.
- named_enum: an enum whose value will be serialized as the registered name.
- struct: a morphic(final) type serialized by Fory Struct serializer. i.e. it doesn't have subclasses. Suppose we're
  deserializing `List<SomeClass>`, we can save dynamic serializer dispatch since `SomeClass` is morphic(final).
- compatible_struct: a morphic(final) type serialized by Fory compatible Struct serializer.
- named_struct: a `struct` whose type mapping will be encoded as a name.
- named_compatible_struct: a `compatible_struct` whose type mapping will be encoded as a name.
- ext: a type which will be serialized by a customized serializer.
- named_ext: an `ext` type whose type mapping will be encoded as a name.
- list: a sequence of objects.
- set: an unordered set of unique elements.
- map: a map of key-value pairs. Mutable types such as `list/map/set/array/tensor/arrow` are not allowed as key of map.
- duration: an absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
- timestamp: a point in time, independent of any calendar/timezone, as a count of nanoseconds. The count is relative
  to an epoch at UTC midnight on January 1, 1970.
- local_date: a naive date without timezone. The count is days relative to an epoch at UTC midnight on Jan 1, 1970.
- decimal: exact decimal value represented as an integer value in two's complement.
- binary: an variable-length array of bytes.
- array: only allow 1d numeric components. Other arrays will be taken as List. The implementation should support the
  interoperability between array and list.
  - bool_array: one dimensional int16 array.
  - int8_array: one dimensional int8 array.
  - int16_array: one dimensional int16 array.
  - int32_array: one dimensional int32 array.
  - int64_array: one dimensional int64 array.
  - float16_array: one dimensional half_float_16 array.
  - float32_array: one dimensional float32 array.
  - float64_array: one dimensional float64 array.
- tensor: multidimensional array which every sub-array have same size and type.
- arrow record batch: an arrow [record batch](https://arrow.apache.org/docs/cpp/tables.html#record-batches) object.
- arrow table: an arrow [table](https://arrow.apache.org/docs/cpp/tables.html#tables) object.

Note:

- Unsigned int/long are not added here, since not every language support those types.

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

All internal data types are expressed using an ID in range `0~64`. Users can use `0~4096` for representing their
types.

### Type mapping

See [Type mapping](xlang_type_mapping.md)

## Spec overview

Here is the overall format:

```
| fory header | object ref meta | object type meta | object value data |
```

The data are serialized using little endian byte order overall. If bytes swap is costly for some object,
Fory will write the byte order for that object into the data instead of converting it to little endian.

## Fory header

Fory header consists starts one byte:

```
|    2 bytes   |     4 bits    | 1 bit | 1 bit | 1 bit  | 1 bit |   1 byte   |          optional 4 bytes          |
+--------------+---------------+-------+-------+--------+-------+------------+------------------------------------+
| magic number | reserved bits |  oob  | xlang | endian | null  |  language  | unsigned int for meta start offset |
```

- magic number: used to identify fory serialization protocol, current version use `0x62d4`.
- null flag: 1 when object is null, 0 otherwise. If an object is null, other bits won't be set.
- endian flag: 1 when data is encoded by little endian, 0 for big endian.
- xlang flag: 1 when serialization uses xlang format, 0 when serialization uses Fory java format.
- oob flag: 1 when passed `BufferCallback` is not null, 0 otherwise.
- language: the language when serializing objects, such as JAVA, PYTHON, GO, etc. Fory can use this flag to determine whether spend more time on serialization to make the deserialization faster for dynamic languages.

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
context(e.g., a field of a type), only the `NULL` and `NOT_NULL VALUE` flags will be used for reference meta.

For languages which doesn't support reference such as rust, reference tracking must be disabled for correct
deserialization by fory rust implementation.

For languages whose object values are not null by default:

- In rust, Fory takes `Option:None` as a null value
- In c++, Fory takes `std::nullopt` as a null value
- In golang, Fory takes `null interface/pointer` as a null value

If one want to deserialize in languages like `Java/Python/JavaScript`, he should mark the type with all fields
not-null by default, or using schema-evolution mode to carry the not-null fields info in the data.

## Type Meta

For every type to be serialized, it have a type id to indicate its type.

- basic types: the type id
- enum:
  - `Type.ENUM` + registered id
  - `Type.NAMED_ENUM` + registered namespace+typename
- list: `Type.List`
- set: `Type.SET`
- map: `Type.MAP`
- ext:
  - `Type.EXT` + registered id
  - `Type.NAMED_EXT` + registered namespace+typename
- struct:
  - `Type.STRUCT` + struct meta
  - `Type.NAMED_STRUCT` + struct meta

Every type must be registered with an ID or name first. The registration can be used for security check and type
identification.

Struct is a special type, depending whether schema compatibility is enabled, Fory will write struct meta
differently.

### Struct Schema consistent

- If schema consistent mode is enabled globally when creating fory, type meta will be written as a fory unsigned varint
  of `type_id`. Schema evolution related meta will be ignored.
- If schema evolution mode is enabled globally when creating fory, and current class is configured to use schema
  consistent mode like `struct` vs `table` in flatbuffers:
  - Type meta will be add to `captured_type_defs`: `captured_type_defs[type def stub] = map size` ahead when
    registering type.
  - Get index of the meta in `captured_type_defs`, write that index as `| unsigned varint: index |`.

### Struct Schema evolution

If schema evolution mode is enabled globally when creating fory, and enabled for current type, type meta will be written
using one of the following mode. Which mode to use is configured when creating fory.

- Normal mode(meta share not enabled):
  - If type meta hasn't been written before, add `type def`
    to `captured_type_defs`: `captured_type_defs[type def] = map size`.
  - Get index of the meta in `captured_type_defs`, write that index as `| unsigned varint: index |`.
  - After finished the serialization of the object graph, fory will start to write `captured_type_defs`:
    - Firstly, set current to `meta start offset` of fory header
    - Then write `captured_type_defs` one by one:

      ```python
      buffer.write_var_uint32(len(writting_type_defs) - len(schema_consistent_type_def_stubs))
      for type_meta in writting_type_defs:
          if not type_meta.is_stub():
              type_meta.write_type_def(buffer)
      writing_type_defs = copy(schema_consistent_type_def_stubs)
      ```

- Meta share mode: the writing steps are same as the normal mode, but `captured_type_defs` will be shared across
  multiple serializations of different objects. For example, suppose we have a batch to serialize:

  ```python
  captured_type_defs = {}
  stream = ...
  # add `Type1` to `captured_type_defs` and write `Type1`
  fory.serialize(stream, [Type1()])
  # add `Type2` to `captured_type_defs` and write `Type2`, `Type1` is written before.
  fory.serialize(stream, [Type1(), Type2()])
  # `Type1` and `Type2` are written before, no need to write meta.
  fory.serialize(stream, [Type1(), Type2()])
  ```

- Streaming mode(streaming mode doesn't support meta share):
  - If type meta hasn't been written before, the data will be written as:

    ```
    | unsigned varint: 0b11111111 | type def |
    ```

  - If type meta has been written before, the data will be written as:

    ```
    | unsigned varint: written index << 1 |
    ```

    `written index` is the id in `captured_type_defs`.

  - With this mode, `meta start offset` can be omitted.

> The normal mode and meta share mode will forbid streaming writing since it needs to look back for update the start
> offset after the whole object graph writing and meta collecting is finished. Only in this way we can ensure
> deserialization failure in meta share mode doesn't lost shared meta.

#### Type Def

Here we mainly describe the meta layout for schema evolution mode:

```
|    8 bytes header    |   variable bytes   |  variable bytes   |
+----------------------+--------------------+-------------------+
| global binary header |    meta header     |    fields meta    |
```

For languages which support inheritance, if parent class and subclass has fields with same name, using field in
subclass.

##### Global binary header

`50 bits hash + 1bit compress flag + write fields meta + 12 bits meta size`. Right is the lower bits.

- lower 12 bits are used to encode meta size. If meta size `>= 0b1111_1111_1111`, then write
  `meta_ size - 0b1111_1111_1111` next.
- 13rd bit is used to indicate whether to write fields meta. When this class is schema-consistent or use registered
  serializer, fields meta will be skipped. Class Meta will be used for share namespace + type name only.
- 14rd bit is used to indicate whether meta is compressed.
- Other 50 bits is used to store the unique hash of `flags + all layers class meta`.

##### Meta header

Meta header is a 8 bits number value.

- Lowest 5 digits `0b00000~0b11110` are used to record num fields. `0b11111` is preserved to indicate that Fory need to
  read more bytes for length using Fory unsigned int encoding. Note that num_fields is the number of compatible fields.
  Users can use tag id to mark some fields as compatible fields in schema consistent context. In such cases, schema
  consistent fields will be serialized first, then compatible fields will be serialized next. At deserialization,
  Fory will use fields info of those fields which aren't annotated by tag id for deserializing schema consistent
  fields, then use fields info in meta for deserializing compatible fields.
- The 6th bit: 0 for registered by id, 1 for registered by name.
- Remaining 2 bits are reserved for future extension.

##### Fields meta

Format:

```
|   field info: variable bytes    | variable bytes  | ... |
+---------------------------------+-----------------+-----+
| header + type info + field name | next field info | ... |
```

###### Field Header

Field Header is 8 bits, annotation can be used to provide more specific info. If annotation not exists, fory will infer
those info automatically.

The format for field header is:

```
2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag
```

Detailed spec:

- 2 bits field name encoding:
  - encoding: `UTF8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL/TAG_ID`
  - If tag id is used, field name will be written by an unsigned varint tag id, and 2 bits encoding will be `11`.
- size of field name:
  - The `4 bits size: 0~14` will be used to indicate length `1~15`, the value `15` indicates to read more bytes,
    the encoding will encode `size - 15` as a varint next.
  - If encoding is `TAG_ID`, then num_bytes of field name will be used to store tag id.
- ref tracking: when set to 1, ref tracking will be enabled for this field.
- nullability: when set to 1, this field can be null.

###### Field Type Info

Field type info is written as unsigned int8. Detailed id spec is:

- For struct registered by id, it will be `Type.STRUCT`.
- For struct registered by name, it will be `Type.NAMED_STRUCT`.
- For enum registered by id, it will be `Type.ENUM`.
- For enum registered by name, it will be `Type.NAMED_ENUM`.
- For ext type registered by id, it will be `Type.EXT`.
- For ext type registered by name, it will be `Type.NAMED_EXT`.
- For list/set type, it will be written as `Type.LIST/SET`, then write element type recursively.
- For 1D primitive array type, it will be written as `Type.XXX_ARRAY`.
- For multi-dimensional primitive array type with same size on each dim, it will be written as `Type.TENSOR`.
- For other array type, it will be written as `Type.LIST`, then write element type recursively.
- For map type, it will be written as `Type.MAP`, then write key and value type recursively.
- For other types supported by fory directly, it will be fory type id for that type.
- For other types not determined at compile time, write `Type.UNKNOWN` instead. For such types, actual type
  will be written when serializing such field values.

Polymorphism spec:

- `struct/named_struct/ext/named_ext` are taken as polymorphic, the meta for those types are written separately
  instead of inlining here to reduce meta space cost if object of this type is serialized in current object graph
  multiple times, and the field value may be null too.
- `enum` is taken as morphic, if deserialization doesn't have this field, or the type is not enum, enum value
  will be skipped.
- `list/map/set` are taken as morphic, when serializing values of those type, the concrete types won't be written
  again.
- Other types that fory supported are taken as morphic too.

List/Set/Map nested type spec:

- `list`: `| list type id | nested type id << 2 + nullability flag + ref tracking flag | ... multi-layer type info |`
- `set`: `| set type id | nested type id << 2 + nullability flag + ref tracking flag | ... multi-layer type info |`
- `map`: `| set type id | key type info | value type info |`
  - Key type format: `| nested type id << 2 + nullability flag + ref tracking flag | ... multi-layer type info |`
  - Value type format: `| nested type id << 2 + nullability flag + ref tracking flag | ... multi-layer type info |`

###### Field Name

If tag id is set, tag id will be used instead. Otherwise meta string of field name will be written instead.

###### Field order

Field order are left as implementation details, which is not exposed to specification, the deserialization need to
resort fields based on Fory fields sort algorithms. In this way, fory can compute statistics for field names or types and
using a more compact encoding.

## Extended Type Meta with Inheritance support

If one want to support inheritance for struct, one can implement following spec.

### Schema consistent

Fields are serialized from parent type to leaf type. Fields are sorted using fory struct fields sort algorithms.

### Schema Evolution

Meta layout for schema evolution mode:

```
|    8 bytes header    | variable bytes | variable bytes |   variable bytes   |   variable bytes   |
+----------------------+----------------+----------------+--------------------+--------------------+
| global binary header |  meta header   |  fields meta   | parent meta header | parent fields meta |
```

#### Meta header

Meta header is a 64 bits number value encoded in little endian order.

- Lowest 4 digits `0b0000~0b1110` are used to record num classes. `0b1111` is preserved to indicate that Fory need to
  read more bytes for length using Fory unsigned int encoding. If current type doesn't has parent type, or parent
  type doesn't have fields to serialize, or we're in a context which serialize fields of current type
  only, num classes will be 1.
- The 5th bit is used to indicate whether this type needs schema evolution.
- Other 56 bits are used to store the unique hash of `flags + all layers type meta`.

#### Single layer type meta

```
| unsigned varint | var uint |  field info: variable bytes   | variable bytes  | ... |
+-----------------+----------+-------------------------------+-----------------+-----+
|   num_fields    | type id  | header + type id + field name | next field info | ... |
```

#### Other layers type meta

Same encoding algorithm as the previous layer.

## Meta String

Meta string is mainly used to encode meta strings such as field names.

### Encoding Algorithms

String binary encoding algorithm:

| Algorithm                 | Pattern       | Description                                                                                                                                                                                                                                                                             |
| ------------------------- | ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| LOWER_SPECIAL             | `a-z._$\|`    | every char is written using 5 bits, `a-z`: `0b00000~0b11001`, `._$\|`: `0b11010~0b11101`, prepend one bit at the start to indicate whether strip last char since last byte may have 7 redundant bits(1 indicates strip last char)                                                       |
| LOWER_UPPER_DIGIT_SPECIAL | `a-zA-Z0~9._` | every char is written using 6 bits, `a-z`: `0b00000~0b11001`, `A-Z`: `0b11010~0b110011`, `0~9`: `0b110100~0b111101`, `._`: `0b111110~0b111111`, prepend one bit at the start to indicate whether strip last char since last byte may have 7 redundant bits(1 indicates strip last char) |
| UTF-8                     | any chars     | UTF-8 encoding                                                                                                                                                                                                                                                                          |

Encoding flags:

| Encoding Flag             | Pattern                                                  | Encoding Algorithm                                                                                                                                          |
| ------------------------- | -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| LOWER_SPECIAL             | every char is in `a-z._\|`                               | `LOWER_SPECIAL`                                                                                                                                             |
| FIRST_TO_LOWER_SPECIAL    | every char is in `a-z._` except first char is upper case | replace first upper case char to lower case, then use `LOWER_SPECIAL`                                                                                       |
| ALL_TO_LOWER_SPECIAL      | every char is in `a-zA-Z._`                              | replace every upper case char by `\|` + `lower case`, then use `LOWER_SPECIAL`, use this encoding if it's smaller than Encoding `LOWER_UPPER_DIGIT_SPECIAL` |
| LOWER_UPPER_DIGIT_SPECIAL | every char is in `a-zA-Z._`                              | use `LOWER_UPPER_DIGIT_SPECIAL` encoding if it's smaller than Encoding `FIRST_TO_LOWER_SPECIAL`                                                             |
| UTF8                      | any utf-8 char                                           | use `UTF-8` encoding                                                                                                                                        |
| Compression               | any utf-8 char                                           | lossless compression                                                                                                                                        |

Notes:

- Depending on cases, one can choose encoding `flags + data` jointly, uses 3 bits of first byte for flags and other
  bytes
  for data.

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

- size: 1~5 byte
- Format: The most significant bit (MSB) in every byte indicates whether to have the next byte. If first bit is set
  i.e. `b & 0x80 == 0x80`, then
  the next byte should be read until the first bit of the next byte is unset.

#### signed int32

- size: 4 byte
- byte order: raw bytes of little endian order

#### signed varint32

- size: 1~5 byte
- Format: First convert the number into positive unsigned int by `(v << 1) ^ (v >> 31)` ZigZag algorithm, then encode
  it as an unsigned varint.

#### unsigned int64

- size: 8 byte
- byte order: raw bytes of little endian order

#### unsigned varint64

- size: 1~9 byte
- Fory SLI(Small long as int) Encoding:
  - If long is in `[0, 2147483647]`, encode as 4 bytes int: `| little-endian: ((int) value) << 1 |`
  - Otherwise write as 9 bytes: `| 0b1 | little-endian 8 bytes long |`
- Fory PVL(Progressive Variable-length Long) Encoding:
  - positive long format: first bit in every byte indicates whether to have the next byte. If first bit is set
    i.e. `b & 0x80 == 0x80`, then the next byte should be read until the first bit is unset.

#### signed int64

- size: 8 byte
- byte order: raw bytes of little endian order

#### signed varint64

- size: 1~9 byte
- Fory SLI(Small long as int) Encoding:
  - If long is in `[-1073741824, 1073741823]`, encode as 4 bytes int: `| little-endian: ((int) value) << 1 |`
  - Otherwise write as 9 bytes: `| 0b1 | little-endian 8 bytes long |`
- Fory PVL(Progressive Variable-length Long) Encoding:
  - First convert the number into positive unsigned long by `(v << 1) ^ (v >> 63)` ZigZag algorithm to reduce cost of
    small negative numbers, then encoding it as an unsigned long.

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
| unsigned varint64: size << 2 `bitor` 2 bits encoding flags | binary data |
```

- `size + encoding` will be concat as a long and encoded as an unsigned varint64. The little 2 bits is used for
  encoding:
  0 for `latin1(ISO-8859-1)`, 1 for `utf-16`, 2 for `utf-8`.
- encoded string binary data based on encoding: `latin/utf-16/utf-8`.

Which encoding to choose:

- For JDK8: fory detect `latin` at runtime, if string is `latin` string, then use `latin` encoding, otherwise
  use `utf-16`.
- For JDK9+: fory use `coder` in `String` object for encoding, `latin`/`utf-16` will be used for encoding.
- If the string is encoded by `utf-8`, then fory will use `utf-8` to decode the data. Cross-language string
  serialization of fory uses `utf-8` by default.

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
| unsigned varint64: length | 1 byte elements header | elements data |
```

#### elements header

In most cases, all elements are same type and not null, elements header will encode those homogeneous
information to avoid the cost of writing it for every element. Specifically, there are four kinds of information
which will be encoded by elements header, each use one bit:

- If track elements ref, use the first bit `0b1` of the header to flag it.
- If the elements have null, use the second bit `0b10` of the header to flag it. If ref tracking is enabled for this
  element type, this flag is invalid.
- If the element types are the declared type, use the 3rd bit `0b100` of the header to flag it.
- If the element types are smae, use the 4th bit `0b1000` header to flag it.

By default, all bits are unset, which means all elements won't track ref, all elements are same type, not null and
the actual element is the declared type in the custom type field.

The implementation can generate different deserialization code based read header, and look up the generated code from
a linear map/list.

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

#### Tensor

Tensor is a special primitive multi-dimensional array which all dimensions have same size and type. The serialization
format is:

```
| num_dims(unsigned varint) | shape[0](unsigned varint) | shape[...] | shape[N] | element type | data |
```

The data is continuous to reduce copy and may zero-copy in some cases.

#### object array

Object array is serialized using the list format. Object component type will be taken as list element
generic type.

### map

Map uses a chunk by chunk based Format:

```
| length(unsigned varint) | key value chunk data | ... | key value chunk data |
```

#### map key-value chunk data

Map iteration is too expensive, Fory won't compute the header like for list since it introduce
[considerable overhead](https://github.com/apache/fory/issues/925).
Users can use `MapFieldInfo` annotation to provide the header in advance. Otherwise Fory will use first key-value pair
to predict header optimistically, and update the chunk header if the prediction failed at some pair.

Fory will serialize the map chunk by chunk, every chunk has 255 pairs at most.

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

`KV header` will be a header marked by `MapFieldInfo` in java. For languages such as golang, this can be computed in
advance for non-interface types most times. The implementation can generate different deserialization code based read
header, and look up the generated code from a linear map/list.

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

### decimal

Not supported for now.

### struct

Struct means object of `class/pojo/struct/bean/record` type.
Struct will be serialized by writing its fields data in fory order.

Depending on schema compatibility, structs will have different formats.

#### field order

Field will be ordered as following, every group of fields will have its own order:

- primitive fields:
  - larger size type first, smaller later, variable size type last.
  - when same size, sort by type id
  - when same size and type id, sort by snake case field name
  - types: bool/int8/int16/int32/varint32/int64/varint64/sliint64/float16/float32/float64
- nullable primitive fields: same order as primitive fields
- other internal type fields: sort by type id then snake case field name
- list fields: sort by snake case field name
- set fields: sort by snake case field name
- map fields: sort by snake case field name
- other fields: sort by snake case field name

If two fields have same type, then sort by snake_case styled field name.

#### schema consistent

Object will be written as:

```
|    4 byte     |  variable bytes  |
+---------------+------------------+
|   type hash   |   field values   |
```

Type hash is used to check the type schema consistency across languages. Type hash will be the first 32 bits of 56 bits
value of the type meta.

Object fields will be serialized one by one using following format:

```
not null primitive field value:
|   var bytes    |
+----------------+
|   value data   |
+----------------+
nullable primitive field value:
| one byte  |   var bytes   |
+-----------+---------------+
| null flag |  field value  |
+-----------+---------------+
other interal types supported by fory
| var bytes | var objects |
+-----------+-------------+
| null flag | value data  |
+-----------+-------------+
list field type:
| one byte  | var objects |
+-----------+-------------+
| ref meta  | value data  |
set field type:
| one byte  | var objects |
+-----------+-------------+
| ref meta  | value data  |
map field type:
| one byte  | var objects |
+-----------+-------------+
| ref meta  | value data  |
+-----------+-------------+-------------+
other types such as enum/struct/ext
| one byte  | var bytes | var objects |
+-----------+------------+------------+
| ref  flag | type meta | value data |
+-----------+------------+------------+
```

Type hash algorithm:

- Sort fields by fields sort algorithm
- Start with string `""`
- Iterate every field, append string by:
  - `snow_case(field_name),`. For camelcase name, convert it to snow_case first.
  - `$type_id,`, for other fields, use type id `TypeId::UNKNOWN` instead.
  - `$nullable;`, `1` if nullable, `0` otherwise.
- Then convert string to utf8 bytes
- Compute murmurhash3_x64_128, and use first 32 bits

#### Schema evolution

Schema evolution have similar format as schema consistent mode for object except:

- For the object type, `schema consistent` mode will write type by id only, but `schema evolution` mode will
  write type consisting of field names, types and other meta too, see [Type meta](#type-meta).
- Type meta of `final custom type` needs to be written too, because peers may not have this type defined.

### Type

Type will be serialized using type meta format.

## Implementation guidelines

### How to reduce memory read/write code

- Try to merge multiple bytes into an int/long write before writing to reduce memory IO and bound check cost.
- Read multiple bytes as an int/long, then split into multiple bytes to reduce memory IO and bound check cost.
- Try to use one varint/long to write flags and length together to save one byte cost and reduce memory io.
- Condition branches are less expensive compared to memory IO cost unless there are too many branches.

### Fast deserialization for static languages without runtime codegen support

For type evolution, the serializer will encode the type meta into the serialized data. The deserializer will compare
this meta with class meta in the current process, and use the diff to determine how to deserialize the data.

For java/javascript/python, we can use the diff to generate serializer code at runtime and load it as class/function for
deserialization. In this way, the type evolution will be as fast as type consist mode.

For C++/Rust, we can't generate the serializer code at runtime. So we need to generate the code at compile-time using
meta programming. But at that time, we don't know the type schema in other processes, so we can't generate the
serializer code for such inconsistent types. We may need to generate the code which has a loop and compare field name
one by one to decide whether to deserialize and assign the field or skip the field value.

One fast way is that we can optimize the string comparison into `jump` instructions:

- Assume the current type has `n` fields, and the peer type has `n1` fields.
- Generate an auto growing `field id` from `0` for every sorted field in the current type at the compile time.
- Compare the received type meta with current type, generate same id if the field name is same, otherwise generate an
  auto growing id starting from `n`, cache this meta at runtime.
- Iterate the fields of received type meta, use a `switch` to compare the `field id` to deserialize data
  and `assign/skip` field value. **Continuous** field id will be optimized into `jump` in `switch` block, so it will
  very fast.

Here is an example, suppose process A has a class `Foo` with version 1 defined as `Foo1`, process B has a class `Foo`
with version 2 defined as `Foo2`:

```c++
// class Foo with version 1
class Foo1 {
  int32_t v1; // id 0
  std::string v2; // id 1
};
// class Foo with version 2
class Foo2 {
  // id 0, but will have id 2 in process A
  bool v0;
  // id 1, but will have id 0 in process A
  int32_t v1;
  // id 2, but will have id 3 in process A
  int64_t long_value;
  // id 3, but will have id 1 in process A
  std::string v2;
  // id 4, but will have id 4 in process A
  std::vector<std::string> list;
};
```

When process A received serialized `Foo2` from process B, here is how it deserialize the data:

```c++
Foo1 foo1 = ...;
const std::vector<fory::FieldInfo> &field_infos = type_meta.field_infos;
for (const auto &field_info : field_infos) {
  switch (field_info.field_id) {
    case 0:
      foo1.v1 = buffer.read_varint32();
      break;
    case 1:
      foo1.v2 = fory.read_string();
      break;
    default:
      fory.skip_data(field_info);
  }
}
```
