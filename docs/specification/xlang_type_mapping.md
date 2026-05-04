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

## User Type IDs

When registering user types (struct/ext/enum/union), the internal type ID is written as the 8-bit
kind, and the user type ID is written separately as an unsigned varint32. There is no bit
shift/packing, and `user_type_id` can be in the range `0~0xFFFFFFFE`.

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

## Type Mapping

The first column names the Fory schema expression or canonical wire tag. Scalar
encoding rows such as `fixed int32` and `tagged int64` are not FDL type names;
FDL spells them as an encoding modifier plus a semantic integer type.

| Fory schema / wire tag             | Fory Type ID | Java                                      | Python                                    | Javascript                            | C++                                                 | Golang                                         | Rust                              |
| ---------------------------------- | ------------ | ----------------------------------------- | ----------------------------------------- | ------------------------------------- | --------------------------------------------------- | ---------------------------------------------- | --------------------------------- |
| bool                               | 1            | bool/Boolean                              | bool                                      | Boolean                               | bool                                                | bool                                           | bool                              |
| int8                               | 2            | byte/Byte                                 | int/pyfory.Int8                           | Type.int8()                           | int8_t                                              | int8                                           | i8                                |
| int16                              | 3            | short/Short                               | int/pyfory.Int16                          | Type.int16()                          | int16_t                                             | int16                                          | i16                               |
| fixed int32                        | 4            | int/Integer                               | int/pyfory.FixedInt32                     | `Type.int32({ encoding: "fixed" })`   | int32_t                                             | int32                                          | i32                               |
| int32                              | 5            | int/Integer                               | int/pyfory.Int32                          | Type.int32()                          | int32_t                                             | int32                                          | i32                               |
| fixed int64                        | 6            | long/Long                                 | int/pyfory.FixedInt64                     | `Type.int64({ encoding: "fixed" })`   | int64_t                                             | int64                                          | i64                               |
| int64                              | 7            | long/Long                                 | int/pyfory.Int64                          | Type.int64()                          | int64_t                                             | int64                                          | i64                               |
| tagged int64                       | 8            | long/Long                                 | int/pyfory.TaggedInt64                    | `Type.int64({ encoding: "tagged" })`  | int64_t                                             | int64                                          | i64                               |
| uint8                              | 9            | short/Short                               | int/pyfory.UInt8                          | Type.uint8()                          | uint8_t                                             | uint8                                          | u8                                |
| uint16                             | 10           | int/Integer                               | int/pyfory.UInt16                         | Type.uint16()                         | uint16_t                                            | uint16                                         | u16                               |
| fixed uint32                       | 11           | long/Long                                 | int/pyfory.FixedUInt32                    | `Type.uint32({ encoding: "fixed" })`  | uint32_t                                            | uint32                                         | u32                               |
| uint32                             | 12           | long/Long                                 | int/pyfory.UInt32                         | Type.uint32()                         | uint32_t                                            | uint32                                         | u32                               |
| fixed uint64                       | 13           | long/Long                                 | int/pyfory.FixedUInt64                    | `Type.uint64({ encoding: "fixed" })`  | uint64_t                                            | uint64                                         | u64                               |
| uint64                             | 14           | long/Long                                 | int/pyfory.UInt64                         | Type.uint64()                         | uint64_t                                            | uint64                                         | u64                               |
| tagged uint64                      | 15           | long/Long                                 | int/pyfory.TaggedUInt64                   | `Type.uint64({ encoding: "tagged" })` | uint64_t                                            | uint64                                         | u64                               |
| float8                             | 16           | /                                         | /                                         | /                                     | /                                                   | /                                              | /                                 |
| float16                            | 17           | Float16                                   | native float / pyfory.Float16 annotation  | `number`                              | `fory::float16_t`                                   | `float16.Float16`                              | `Float16`                         |
| bfloat16                           | 18           | BFloat16                                  | native float / pyfory.BFloat16 annotation | `BFloat16` / `number`                 | `fory::bfloat16_t`                                  | `bfloat16.BFloat16`                            | `BFloat16`                        |
| float32                            | 19           | float/Float                               | float/pyfory.Float32                      | Type.float32()                        | float                                               | float32                                        | f32                               |
| float64                            | 20           | double/Double                             | float/pyfory.Float64                      | Type.float64()                        | double                                              | float64                                        | f64                               |
| string                             | 21           | String                                    | str                                       | String                                | string                                              | string                                         | String/str                        |
| list                               | 22           | List/Collection                           | list/tuple                                | array                                 | vector                                              | slice                                          | Vec                               |
| set                                | 23           | Set                                       | set                                       | /                                     | set                                                 | fory.Set                                       | Set                               |
| map                                | 24           | Map                                       | dict                                      | Map                                   | unordered_map                                       | map                                            | HashMap                           |
| enum                               | 25           | Enum subclasses                           | enum subclasses                           | /                                     | enum                                                | /                                              | enum                              |
| named_enum                         | 26           | Enum subclasses                           | enum subclasses                           | /                                     | enum                                                | /                                              | enum                              |
| struct                             | 27           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            |
| compatible_struct                  | 28           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            |
| named_struct                       | 29           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            |
| named_compatible_struct            | 30           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            |
| ext                                | 31           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            |
| named_ext                          | 32           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            |
| union                              | 33           | Union                                     | typing.Union                              | /                                     | `std::variant<Ts...>`                               | /                                              | tagged union enum                 |
| none                               | 36           | null                                      | None                                      | null                                  | `std::monostate`                                    | nil                                            | `()`                              |
| duration                           | 37           | Duration                                  | timedelta                                 | Number                                | duration                                            | Duration                                       | Duration                          |
| timestamp                          | 38           | Instant                                   | datetime                                  | Number                                | std::chrono::nanoseconds                            | Time                                           | DateTime                          |
| date                               | 39           | LocalDate                                 | datetime.date                             | Date                                  | fory::serialization::Date                           | fory.Date                                      | chrono::NaiveDate                 |
| decimal                            | 40           | BigDecimal                                | Decimal                                   | Decimal                               | /                                                   | fory.Decimal                                   | fory::Decimal                     |
| binary                             | 41           | byte[]                                    | bytes                                     | /                                     | `uint8_t[n]/vector<T>`                              | `[n]uint8/[]T`                                 | `Vec<u8>`                         |
| `array<bool>` (bool_array)         | 43           | bool[]                                    | BoolArray / ndarray(np.bool\_)            | Type.boolArray()                      | `bool[n]`                                           | `[n]bool/[]T`                                  | `Vec<bool>`                       |
| `array<int8>` (int8_array)         | 44           | `@Int8Type byte[]`                        | Int8Array / ndarray(int8)                 | Type.int8Array()                      | `int8_t[n]/vector<T>`                               | `[n]int8/[]T`                                  | `Vec<i8>`                         |
| `array<int16>` (int16_array)       | 45           | short[]                                   | Int16Array / ndarray(int16)               | Type.int16Array()                     | `int16_t[n]/vector<T>`                              | `[n]int16/[]T`                                 | `Vec<i16>`                        |
| `array<int32>` (int32_array)       | 46           | int[]                                     | Int32Array / ndarray(int32)               | Type.int32Array()                     | `int32_t[n]/vector<T>`                              | `[n]int32/[]T`                                 | `Vec<i32>`                        |
| `array<int64>` (int64_array)       | 47           | long[]                                    | Int64Array / ndarray(int64)               | Type.int64Array()                     | `int64_t[n]/vector<T>`                              | `[n]int64/[]T`                                 | `Vec<i64>`                        |
| `array<uint8>` (uint8_array)       | 48           | `@UInt8Type byte[]`                       | UInt8Array / ndarray(uint8)               | Type.uint8Array()                     | `uint8_t[n]/vector<T>`                              | `[n]uint8/[]T`                                 | `Vec<u8>`                         |
| `array<uint16>` (uint16_array)     | 49           | `@UInt16Type short[]`                     | UInt16Array / ndarray(uint16)             | Type.uint16Array()                    | `uint16_t[n]/vector<T>`                             | `[n]uint16/[]T`                                | `Vec<u16>`                        |
| `array<uint32>` (uint32_array)     | 50           | `@UInt32Type int[]`                       | UInt32Array / ndarray(uint32)             | Type.uint32Array()                    | `uint32_t[n]/vector<T>`                             | `[n]uint32/[]T`                                | `Vec<u32>`                        |
| `array<uint64>` (uint64_array)     | 51           | `@UInt64Type long[]`                      | UInt64Array / ndarray(uint64)             | Type.uint64Array()                    | `uint64_t[n]/vector<T>`                             | `[n]uint64/[]T`                                | `Vec<u64>`                        |
| `array<float8>` (float8_array)     | 52           | /                                         | /                                         | /                                     | /                                                   | /                                              | /                                 |
| `array<float16>` (float16_array)   | 53           | `Float16Array` / `@Float16Type short[]`   | Float16Array / ndarray(float16)           | Type.float16Array()                   | `fory::float16_t[n]/std::vector<fory::float16_t>`   | `[N]float16.Float16` / `[]float16.Float16`     | `Vec<Float16>` / `[Float16; N]`   |
| `array<bfloat16>` (bfloat16_array) | 54           | `BFloat16Array` / `@BFloat16Type short[]` | BFloat16Array / ndarray(bfloat16)         | Type.bfloat16Array()                  | `fory::bfloat16_t[n]/std::vector<fory::bfloat16_t>` | `[N]bfloat16.BFloat16` / `[]bfloat16.BFloat16` | `Vec<BFloat16>` / `[BFloat16; N]` |
| `array<float32>` (float32_array)   | 55           | float[]                                   | Float32Array / ndarray(float32)           | Type.float32Array()                   | `float[n]/vector<T>`                                | `[n]float32/[]T`                               | `Vec<f32>`                        |
| `array<float64>` (float64_array)   | 56           | double[]                                  | Float64Array / ndarray(float64)           | Type.float64Array()                   | `double[n]/vector<T>`                               | `[n]float64/[]T`                               | `Vec<f64>`                        |

Notes:

- Python `pyfory.Float16` and `pyfory.BFloat16` are reserved annotation markers; scalar values deserialize as native Python `float`.
- Python `BoolArray`, `Int8Array`, `Int16Array`, `Int32Array`, `Int64Array`, `UInt8Array`, `UInt16Array`, `UInt32Array`, `UInt64Array`, `Float16Array`, `BFloat16Array`, `Float32Array`, and `Float64Array` are public dense-array wrappers with list-like sequence behavior.
- Java plain `byte[]` maps to `binary`. Numeric byte arrays use type-use annotations:
  `@Int8Type byte[]` for `array<int8>` and `@UInt8Type byte[]` for `array<uint8>`.
- Dart uses `BoolList` for `array<bool>`, typed-data lists for integer/float32/float64 arrays, and
  `Float16List` / `BFloat16List` for `array<float16>` / `array<bfloat16>`. Plain Dart `List<bool>`
  maps to `list<bool>` unless a field uses `@ArrayField(element: BoolType())` or
  `@ForyField(type: ArrayType(element: BoolType()))` with a `BoolList` carrier.
- `Float16[]` and `BFloat16[]` remain object arrays in xlang mode and serialize with the `list` wire type.
- `ARRAY (42)` is reserved for a future dedicated multi-dimensional array encoding and is not part
  of the current xlang type-mapping surface.
- Current xlang uses `*_ARRAY` for one-dimensional primitive arrays and nested `list` for
  multi-dimensional arrays.

## Type info

Due to differences between type systems of languages, those types can't be mapped one-to-one between languages.

If one host-language type corresponds to multiple Fory scalar encodings, for
example Java `long` can represent fixed, varint, or tagged `int64`, the user
must provide encoding metadata when the default is not the intended schema.

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
    private @Int32Type int f1;
    private List<@Int32Type Integer> f2;
  }
  ```

- Python:

  ```python
  class Foo:
      f1: pyfory.Int32
      f2: List[pyfory.Int32]
  ```
