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

| Fory Type               | Fory Type ID | Java            | Python                   | Javascript          | C++                            | Golang           | Rust              |
| ----------------------- | ------------ | --------------- | ------------------------ | ------------------- | ------------------------------ | ---------------- | ----------------- |
| bool                    | 1            | bool/Boolean    | bool                     | Boolean             | bool                           | bool             | bool              |
| int8                    | 2            | byte/Byte       | int/pyfory.int8          | Type.int8()         | int8_t                         | int8             | i8                |
| int16                   | 3            | short/Short     | int/pyfory.int16         | Type.int16()        | int16_t                        | int16            | i16               |
| int32                   | 4            | int/Integer     | int/pyfory.fixed_int32   | Type.int32()        | int32_t                        | int32            | i32               |
| varint32                | 5            | int/Integer     | int/pyfory.int32         | Type.varint32()     | int32_t                        | int32            | i32               |
| int64                   | 6            | long/Long       | int/pyfory.fixed_int64   | Type.int64()        | int64_t                        | int64            | i64               |
| varint64                | 7            | long/Long       | int/pyfory.int64         | Type.varint64()     | int64_t                        | int64            | i64               |
| tagged_int64            | 8            | long/Long       | int/pyfory.tagged_int64  | Type.tagged_int64() | int64_t                        | int64            | i64               |
| uint8                   | 9            | short/Short     | int/pyfory.uint8         | Type.uint8()        | uint8_t                        | uint8            | u8                |
| uint16                  | 10           | int/Integer     | int/pyfory.uint16        | Type.uint16()       | uint16_t                       | uint16           | u16               |
| uint32                  | 11           | long/Long       | int/pyfory.fixed_uint32  | Type.uint32()       | uint32_t                       | uint32           | u32               |
| var_uint32              | 12           | long/Long       | int/pyfory.uint32        | Type.varUInt32()    | uint32_t                       | uint32           | u32               |
| uint64                  | 13           | long/Long       | int/pyfory.fixed_uint64  | Type.uint64()       | uint64_t                       | uint64           | u64               |
| var_uint64              | 14           | long/Long       | int/pyfory.uint64        | Type.varUInt64()    | uint64_t                       | uint64           | u64               |
| tagged_uint64           | 15           | long/Long       | int/pyfory.tagged_uint64 | Type.taggedUInt64() | uint64_t                       | uint64           | u64               |
| float16                 | 16           | float/Float     | float/pyfory.float16     | Type.float16()      | fory::float16_t                | fory.float16     | fory::f16         |
| float32                 | 17           | float/Float     | float/pyfory.float32     | Type.float32()      | float                          | float32          | f32               |
| float64                 | 18           | double/Double   | float/pyfory.float64     | Type.float64()      | double                         | float64          | f64               |
| string                  | 19           | String          | str                      | String              | string                         | string           | String/str        |
| list                    | 20           | List/Collection | list/tuple               | array               | vector                         | slice            | Vec               |
| set                     | 21           | Set             | set                      | /                   | set                            | fory.Set         | Set               |
| map                     | 22           | Map             | dict                     | Map                 | unordered_map                  | map              | HashMap           |
| enum                    | 23           | Enum subclasses | enum subclasses          | /                   | enum                           | /                | enum              |
| named_enum              | 24           | Enum subclasses | enum subclasses          | /                   | enum                           | /                | enum              |
| struct                  | 25           | pojo/record     | data class               | object              | struct/class                   | struct           | struct            |
| compatible_struct       | 26           | pojo/record     | data class               | object              | struct/class                   | struct           | struct            |
| named_struct            | 27           | pojo/record     | data class               | object              | struct/class                   | struct           | struct            |
| named_compatible_struct | 28           | pojo/record     | data class               | object              | struct/class                   | struct           | struct            |
| ext                     | 29           | pojo/record     | data class               | object              | struct/class                   | struct           | struct            |
| named_ext               | 30           | pojo/record     | data class               | object              | struct/class                   | struct           | struct            |
| union                   | 31           | Union           | typing.Union             | /                   | `std::variant<Ts...>`          | /                | tagged union enum |
| none                    | 32           | null            | None                     | null                | `std::monostate`               | nil              | `()`              |
| duration                | 33           | Duration        | timedelta                | Number              | duration                       | Duration         | Duration          |
| timestamp               | 34           | Instant         | datetime                 | Number              | std::chrono::nanoseconds       | Time             | DateTime          |
| date                    | 35           | Date            | datetime                 | Number              | fory::serialization::Date      | Time             | DateTime          |
| decimal                 | 36           | BigDecimal      | Decimal                  | bigint              | /                              | /                | /                 |
| binary                  | 37           | byte[]          | bytes                    | /                   | `uint8_t[n]/vector<T>`         | `[n]uint8/[]T`   | `Vec<uint8_t>`    |
| array                   | 38           | array           | np.ndarray               | /                   | /                              | array/slice      | Vec               |
| bool_array              | 39           | bool[]          | ndarray(np.bool\_)       | /                   | `bool[n]`                      | `[n]bool/[]T`    | `Vec<bool>`       |
| int8_array              | 40           | byte[]          | ndarray(int8)            | /                   | `int8_t[n]/vector<T>`          | `[n]int8/[]T`    | `Vec<i8>`         |
| int16_array             | 41           | short[]         | ndarray(int16)           | /                   | `int16_t[n]/vector<T>`         | `[n]int16/[]T`   | `Vec<i16>`        |
| int32_array             | 42           | int[]           | ndarray(int32)           | /                   | `int32_t[n]/vector<T>`         | `[n]int32/[]T`   | `Vec<i32>`        |
| int64_array             | 43           | long[]          | ndarray(int64)           | /                   | `int64_t[n]/vector<T>`         | `[n]int64/[]T`   | `Vec<i64>`        |
| uint8_array             | 44           | short[]         | ndarray(uint8)           | /                   | `uint8_t[n]/vector<T>`         | `[n]uint8/[]T`   | `Vec<u8>`         |
| uint16_array            | 45           | int[]           | ndarray(uint16)          | /                   | `uint16_t[n]/vector<T>`        | `[n]uint16/[]T`  | `Vec<u16>`        |
| uint32_array            | 46           | long[]          | ndarray(uint32)          | /                   | `uint32_t[n]/vector<T>`        | `[n]uint32/[]T`  | `Vec<u32>`        |
| uint64_array            | 47           | long[]          | ndarray(uint64)          | /                   | `uint64_t[n]/vector<T>`        | `[n]uint64/[]T`  | `Vec<u64>`        |
| float16_array           | 48           | float[]         | ndarray(float16)         | /                   | `fory::float16_t[n]/vector<T>` | `[n]float16/[]T` | `Vec<fory::f16>`  |
| float32_array           | 49           | float[]         | ndarray(float32)         | /                   | `float[n]/vector<T>`           | `[n]float32/[]T` | `Vec<f32>`        |
| float64_array           | 50           | double[]        | ndarray(float64)         | /                   | `double[n]/vector<T>`          | `[n]float64/[]T` | `Vec<f64>`        |

## Type info(not implemented currently)

Due to differences between type systems of languages, those types can't be mapped one-to-one between languages.

If the user notices that one type on a language corresponds to multiple types in Fory type systems, for example, `long`
in java has type `int64/varint64/h64`, it means the language lacks some types, and the user must provide extra type
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
      f1: pyfory.varint32
      f2: List[pyfory.varint32]
  ```
