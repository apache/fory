---
title: Xlang Type Mapping
sidebar_position: 7
id: xlang_type_mapping
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

Note:

- For type definition, see [Type Systems in Spec](xlang_serialization_spec.md#type-systems)
- `int16_t[n]/vector<T>` indicates `int16_t[n]/vector<int16_t>`
- The cross-language serialization is not stable, do not use it in your production environment.

## Type Mapping

| Fory Type               | Fory Type ID | Java            | Python                            | Javascript      | C++                            | Golang           | Rust             |
| ----------------------- | ------------ | --------------- | --------------------------------- | --------------- | ------------------------------ | ---------------- | ---------------- |
| bool                    | 1            | bool/Boolean    | bool                              | Boolean         | bool                           | bool             | bool             |
| int8                    | 2            | byte/Byte       | int/pyfory.Int8                   | Type.int8()     | int8_t                         | int8             | i8               |
| int16                   | 3            | short/Short     | int/pyfory.Int16                  | Type.int16()    | int16_t                        | int16            | i6               |
| int32                   | 4            | int/Integer     | int/pyfory.Int32                  | Type.int32()    | int32_t                        | int32            | i32              |
| var_int32               | 5            | int/Integer     | int/pyfory.VarInt32               | Type.varint32() | fory::varint32_t               | fory.varint32    | fory::varint32   |
| int64                   | 6            | long/Long       | int/pyfory.Int64                  | Type.int64()    | int64_t                        | int64            | i64              |
| var_int64               | 7            | long/Long       | int/pyfory.VarInt64               | Type.varint64() | fory::varint64_t               | fory.varint64    | fory::varint64   |
| sli_int64               | 8            | long/Long       | int/pyfory.SliInt64               | Type.sliint64() | fory::sliint64_t               | fory.sliint64    | fory::sliint64   |
| float16                 | 9            | float/Float     | float/pyfory.Float16              | Type.float16()  | fory::float16_t                | fory.float16     | fory::f16        |
| float32                 | 10           | float/Float     | float/pyfory.Float32              | Type.float32()  | float                          | float32          | f32              |
| float64                 | 11           | double/Double   | float/pyfory.Float64              | Type.float64()  | double                         | float64          | f64              |
| string                  | 12           | String          | str                               | String          | string                         | string           | String/str       |
| enum                    | 13           | Enum subclasses | enum subclasses                   | /               | enum                           | /                | enum             |
| named_enum              | 14           | Enum subclasses | enum subclasses                   | /               | enum                           | /                | enum             |
| struct                  | 15           | pojo/record     | data class / type with type hints | object          | struct/class                   | struct           | struct           |
| compatible_struct       | 16           | pojo/record     | data class / type with type hints | object          | struct/class                   | struct           | struct           |
| named_struct            | 17           | pojo/record     | data class / type with type hints | object          | struct/class                   | struct           | struct           |
| named_compatible_struct | 18           | pojo/record     | data class / type with type hints | object          | struct/class                   | struct           | struct           |
| ext                     | 19           | pojo/record     | data class / type with type hints | object          | struct/class                   | struct           | struct           |
| named_ext               | 20           | pojo/record     | data class / type with type hints | object          | struct/class                   | struct           | struct           |
| list                    | 21           | List/Collection | list/tuple                        | array           | vector                         | slice            | Vec              |
| set                     | 22           | Set             | set                               | /               | set                            | fory.Set         | Set              |
| map                     | 23           | Map             | dict                              | Map             | unordered_map                  | map              | HashMap          |
| duration                | 24           | Duration        | timedelta                         | Number          | duration                       | Duration         | Duration         |
| timestamp               | 25           | Instant         | datetime                          | Number          | std::chrono::nanoseconds       | Time             | DateTime         |
| local_date              | 26           | Date            | datetime                          | Number          | std::chrono::nanoseconds       | Time             | DateTime         |
| decimal                 | 27           | BigDecimal      | Decimal                           | bigint          | /                              | /                | /                |
| binary                  | 28           | byte[]          | bytes                             | /               | `uint8_t[n]/vector<T>`         | `[n]uint8/[]T`   | `Vec<uint8_t>`   |
| array                   | 29           | array           | np.ndarray                        | /               | /                              | array/slice      | Vec              |
| bool_array              | 30           | bool[]          | ndarray(np.bool\_)                | /               | `bool[n]`                      | `[n]bool/[]T`    | `Vec<bool>`      |
| int8_array              | 31           | byte[]          | ndarray(int8)                     | /               | `int8_t[n]/vector<T>`          | `[n]int8/[]T`    | `Vec<i18>`       |
| int16_array             | 32           | short[]         | ndarray(int16)                    | /               | `int16_t[n]/vector<T>`         | `[n]int16/[]T`   | `Vec<i16>`       |
| int32_array             | 33           | int[]           | ndarray(int32)                    | /               | `int32_t[n]/vector<T>`         | `[n]int32/[]T`   | `Vec<i32>`       |
| int64_array             | 34           | long[]          | ndarray(int64)                    | /               | `int64_t[n]/vector<T>`         | `[n]int64/[]T`   | `Vec<i64>`       |
| float16_array           | 35           | float[]         | ndarray(float16)                  | /               | `fory::float16_t[n]/vector<T>` | `[n]float16/[]T` | `Vec<fory::f16>` |
| float32_array           | 36           | float[]         | ndarray(float32)                  | /               | `float[n]/vector<T>`           | `[n]float32/[]T` | `Vec<f32>`       |
| float64_array           | 37           | double[]        | ndarray(float64)                  | /               | `double[n]/vector<T>`          | `[n]float64/[]T` | `Vec<f64>`       |
| arrow record batch      | 38           | /               | /                                 | /               | /                              | /                | /                |
| arrow table             | 39           | /               | /                                 | /               | /                              | /                | /                |

## Type info(not implemented currently)

Due to differences between type systems of languages, those types can't be mapped one-to-one between languages.

If the user notices that one type on a language corresponds to multiple types in Fory type systems, for example, `long`
in java has type `int64/varint64/sliint64`, it means the language lacks some types, and the user must provide extra type
info when using Fory.

## Type annotation

If the type is a field of another class, users can provide meta hints for fields of a type, or for the whole type.
Such information can be provided in other languages too:

- java: use annotation.
- cpp: use macro and template.
- golang: use struct tag.
- python: use typehint.
- rust: use macro.

Here is en example:

- Java:

  ```java
  class Foo {
    @Int32Type(varint = true)
    int f1;
    List<@Int32Type(varint = true) Integer> f2;
  }
  ```

- Python:

  ```python
  class Foo:
      f1: Int32Type(varint=True)
      f2: List[Int32Type(varint=True)]
  ```

## Type wrapper

If the type is not a field of a class, the user must wrap this type with a Fory type to pass the extra type info.

For example, suppose Fory Java provide a `VarInt64` type, when a user invoke `fory.serialize(long_value)`, he need to
invoke like `fory.serialize(new VarInt64(long_value))`.
