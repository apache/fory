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
- Xlang serialization is the portable wire format shared by Java, Python, C++,
  Go, Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin. Keep type
  IDs, names, schemas, and compatibility settings aligned across every peer.

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

| Fory schema / wire tag             | Fory Type ID | Java                                      | Python                                    | JavaScript/TypeScript                 | C++                                                 | Go                                             | Rust                              | C#                                 | Swift                    | Dart                        | Scala                           | Kotlin                 |
| ---------------------------------- | ------------ | ----------------------------------------- | ----------------------------------------- | ------------------------------------- | --------------------------------------------------- | ---------------------------------------------- | --------------------------------- | ---------------------------------- | ------------------------ | --------------------------- | ------------------------------- | ---------------------- |
| bool                               | 1            | bool/Boolean                              | bool                                      | Boolean                               | bool                                                | bool                                           | bool                              | bool                               | Bool                     | bool                        | Boolean                         | Boolean                |
| int8                               | 2            | byte/Byte                                 | int/pyfory.Int8                           | Type.int8()                           | int8_t                                              | int8                                           | i8                                | sbyte                              | Int8                     | int + `Int8Type`            | Byte                            | Byte                   |
| int16                              | 3            | short/Short                               | int/pyfory.Int16                          | Type.int16()                          | int16_t                                             | int16                                          | i16                               | short                              | Int16                    | int + `Int16Type`           | Short                           | Short                  |
| fixed int32                        | 4            | int/Integer                               | int/pyfory.FixedInt32                     | `Type.int32({ encoding: "fixed" })`   | int32_t                                             | int32                                          | i32                               | int + `S.Fixed<S.Int32>`           | Int32 + `.fixed`         | int + fixed metadata        | Int + fixed metadata            | `@Fixed Int`           |
| int32                              | 5            | int/Integer                               | int/pyfory.Int32                          | Type.int32()                          | int32_t                                             | int32                                          | i32                               | int                                | Int32                    | int + `Int32Type`           | Int                             | Int                    |
| fixed int64                        | 6            | long/Long                                 | int/pyfory.FixedInt64                     | `Type.int64({ encoding: "fixed" })`   | int64_t                                             | int64                                          | i64                               | long + `S.Fixed<S.Int64>`          | Int64 + `.fixed`         | Int64 + fixed metadata      | Long + fixed metadata           | `@Fixed Long`          |
| int64                              | 7            | long/Long                                 | int/pyfory.Int64                          | Type.int64()                          | int64_t                                             | int64                                          | i64                               | long                               | Int64                    | int / Int64                 | Long                            | Long                   |
| tagged int64                       | 8            | long/Long                                 | int/pyfory.TaggedInt64                    | `Type.int64({ encoding: "tagged" })`  | int64_t                                             | int64                                          | i64                               | long + `S.Tagged<S.Int64>`         | Int64 + `.tagged`        | Int64 + tagged metadata     | Long + tagged metadata          | `@Tagged Long`         |
| uint8                              | 9            | short/Short                               | int/pyfory.UInt8                          | Type.uint8()                          | uint8_t                                             | uint8                                          | u8                                | byte                               | UInt8                    | int + `Uint8Type`           | Int + unsigned metadata         | UByte                  |
| uint16                             | 10           | int/Integer                               | int/pyfory.UInt16                         | Type.uint16()                         | uint16_t                                            | uint16                                         | u16                               | ushort                             | UInt16                   | int + `Uint16Type`          | Int + unsigned metadata         | UShort                 |
| fixed uint32                       | 11           | long/Long                                 | int/pyfory.FixedUInt32                    | `Type.uint32({ encoding: "fixed" })`  | uint32_t                                            | uint32                                         | u32                               | uint + `S.Fixed<S.UInt32>`         | UInt32 + `.fixed`        | int + fixed uint32 metadata | Long + fixed unsigned metadata  | `@Fixed UInt`          |
| uint32                             | 12           | long/Long                                 | int/pyfory.UInt32                         | Type.uint32()                         | uint32_t                                            | uint32                                         | u32                               | uint                               | UInt32                   | int + `Uint32Type`          | Long + unsigned metadata        | UInt                   |
| fixed uint64                       | 13           | long/Long                                 | int/pyfory.FixedUInt64                    | `Type.uint64({ encoding: "fixed" })`  | uint64_t                                            | uint64                                         | u64                               | ulong + `S.Fixed<S.UInt64>`        | UInt64 + `.fixed`        | Uint64 + fixed metadata     | Long + fixed unsigned metadata  | `@Fixed ULong`         |
| uint64                             | 14           | long/Long                                 | int/pyfory.UInt64                         | Type.uint64()                         | uint64_t                                            | uint64                                         | u64                               | ulong                              | UInt64                   | Uint64                      | Long + unsigned metadata        | ULong                  |
| tagged uint64                      | 15           | long/Long                                 | int/pyfory.TaggedUInt64                   | `Type.uint64({ encoding: "tagged" })` | uint64_t                                            | uint64                                         | u64                               | ulong + `S.Tagged<S.UInt64>`       | UInt64 + `.tagged`       | Uint64 + tagged metadata    | Long + tagged unsigned metadata | `@Tagged ULong`        |
| float8                             | 16           | /                                         | /                                         | /                                     | /                                                   | /                                              | /                                 | /                                  | /                        | /                           | /                               | /                      |
| float16                            | 17           | Float16                                   | native float / pyfory.Float16 annotation  | `number`                              | `fory::float16_t`                                   | `float16.Float16`                              | `Float16`                         | Half                               | Float16                  | double + `Float16Type`      | Float16                         | Float16                |
| bfloat16                           | 18           | BFloat16                                  | native float / pyfory.BFloat16 annotation | `number`                              | `fory::bfloat16_t`                                  | `bfloat16.BFloat16`                            | `BFloat16`                        | BFloat16                           | BFloat16                 | double + `Bfloat16Type`     | BFloat16                        | BFloat16               |
| float32                            | 19           | float/Float                               | float/pyfory.Float32                      | Type.float32()                        | float                                               | float32                                        | f32                               | float                              | Float                    | Float32                     | Float                           | Float                  |
| float64                            | 20           | double/Double                             | float/pyfory.Float64                      | Type.float64()                        | double                                              | float64                                        | f64                               | double                             | Double                   | double                      | Double                          | Double                 |
| string                             | 21           | String                                    | str                                       | String                                | string                                              | string                                         | String/str                        | string                             | String                   | String                      | String                          | String                 |
| list                               | 22           | List/Collection                           | list/tuple                                | array                                 | vector                                              | slice                                          | Vec                               | `List<T>`                          | `[T]`                    | `List<T>`                   | `List[T]`                       | `List<T>`              |
| set                                | 23           | Set                                       | set                                       | /                                     | set                                                 | fory.Set                                       | Set                               | `HashSet<T>`                       | `Set<T>`                 | `Set<T>`                    | `Set[T]`                        | `Set<T>`               |
| map                                | 24           | Map                                       | dict                                      | Map                                   | unordered_map                                       | map                                            | HashMap                           | `Dictionary<K,V>`                  | `[K: V]`                 | `Map<K, V>`                 | `Map[K, V]`                     | `Map<K, V>`            |
| enum                               | 25           | Enum subclasses                           | enum subclasses                           | /                                     | enum                                                | /                                              | enum                              | `[ForyEnum]` enum                  | enum                     | enum                        | Scala 3 enum                    | enum class             |
| named_enum                         | 26           | Enum subclasses                           | enum subclasses                           | /                                     | enum                                                | /                                              | enum                              | `[ForyEnum]` enum                  | enum                     | enum                        | Scala 3 enum                    | enum class             |
| struct                             | 27           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            | `[ForyStruct]` class/struct        | @ForyStruct struct/class | @ForyStruct class           | case class/class                | data class/class       |
| compatible_struct                  | 28           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            | `[ForyStruct]` class/struct        | @ForyStruct struct/class | @ForyStruct class           | case class/class                | data class/class       |
| named_struct                       | 29           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            | `[ForyStruct]` class/struct        | @ForyStruct struct/class | @ForyStruct class           | case class/class                | data class/class       |
| named_compatible_struct            | 30           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            | `[ForyStruct]` class/struct        | @ForyStruct struct/class | @ForyStruct class           | case class/class                | data class/class       |
| ext                                | 31           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            | `[ForyStruct]` class/struct        | @ForyStruct struct/class | @ForyStruct class           | case class/class                | data class/class       |
| named_ext                          | 32           | pojo/record                               | data class                                | object                                | struct/class                                        | struct                                         | struct                            | `[ForyStruct]` class/struct        | @ForyStruct struct/class | @ForyStruct class           | case class/class                | data class/class       |
| union                              | 33           | Union                                     | typing.Union                              | /                                     | `std::variant<Ts...>`                               | /                                              | tagged union enum                 | `[ForyUnion]` ADT record           | tagged enum              | @ForyUnion class            | ADT enum                        | sealed class           |
| none                               | 36           | null                                      | None                                      | null                                  | `std::monostate`                                    | nil                                            | `()`                              | null                               | nil                      | null                        | null                            | null                   |
| duration                           | 37           | Duration                                  | timedelta                                 | Number                                | fory::Duration                                      | Duration                                       | Duration                          | TimeSpan                           | Duration                 | Duration                    | java.time.Duration              | kotlin.time.Duration   |
| timestamp                          | 38           | Instant                                   | datetime                                  | Number                                | fory::Timestamp                                     | Time                                           | Timestamp                         | DateTime/DateTimeOffset            | Date                     | Timestamp                   | java.time.Instant               | java.time.Instant      |
| date                               | 39           | LocalDate                                 | datetime.date                             | Date                                  | fory::Date                                          | fory.Date                                      | Date                              | DateOnly                           | LocalDate                | LocalDate                   | java.time.LocalDate             | java.time.LocalDate    |
| decimal                            | 40           | BigDecimal                                | Decimal                                   | Decimal                               | fory::serialization::Decimal                        | fory.Decimal                                   | fory::Decimal                     | decimal                            | Decimal                  | Decimal                     | java.math.BigDecimal            | java.math.BigDecimal   |
| binary                             | 41           | byte[]                                    | bytes                                     | /                                     | `uint8_t[n]/vector<T>`                              | `[n]uint8/[]T`                                 | `Vec<u8>`                         | byte[]                             | Data                     | Uint8List                   | Array[Byte]                     | ByteArray              |
| `array<bool>` (bool_array)         | 43           | bool[]                                    | BoolArray / ndarray(np.bool\_)            | BoolArray / Type.boolArray()          | `bool[n]`                                           | `[n]bool/[]T`                                  | `Vec<bool>`                       | bool[]                             | [Bool] + @ArrayField     | BoolList                    | Array[Boolean]                  | BooleanArray           |
| `array<int8>` (int8_array)         | 44           | `@Int8Type byte[]`                        | Int8Array / ndarray(int8)                 | Type.int8Array()                      | `int8_t[n]/vector<T>`                               | `[n]int8/[]T`                                  | `Vec<i8>`                         | sbyte[]                            | [Int8] + @ArrayField     | Int8List                    | Array[Byte] + metadata          | ByteArray + @ArrayType |
| `array<int16>` (int16_array)       | 45           | short[]                                   | Int16Array / ndarray(int16)               | Type.int16Array()                     | `int16_t[n]/vector<T>`                              | `[n]int16/[]T`                                 | `Vec<i16>`                        | short[]                            | [Int16] + @ArrayField    | Int16List                   | Array[Short]                    | ShortArray             |
| `array<int32>` (int32_array)       | 46           | int[]                                     | Int32Array / ndarray(int32)               | Type.int32Array()                     | `int32_t[n]/vector<T>`                              | `[n]int32/[]T`                                 | `Vec<i32>`                        | int[]                              | [Int32] + @ArrayField    | Int32List                   | Array[Int]                      | IntArray               |
| `array<int64>` (int64_array)       | 47           | long[]                                    | Int64Array / ndarray(int64)               | Type.int64Array()                     | `int64_t[n]/vector<T>`                              | `[n]int64/[]T`                                 | `Vec<i64>`                        | long[]                             | [Int64] + @ArrayField    | Int64List                   | Array[Long]                     | LongArray              |
| `array<uint8>` (uint8_array)       | 48           | `@UInt8Type byte[]`                       | UInt8Array / ndarray(uint8)               | Type.uint8Array()                     | `uint8_t[n]/vector<T>`                              | `[n]uint8/[]T`                                 | `Vec<u8>`                         | byte[]                             | [UInt8] + @ArrayField    | Uint8List                   | Array[Byte] + metadata          | UByteArray             |
| `array<uint16>` (uint16_array)     | 49           | `@UInt16Type short[]`                     | UInt16Array / ndarray(uint16)             | Type.uint16Array()                    | `uint16_t[n]/vector<T>`                             | `[n]uint16/[]T`                                | `Vec<u16>`                        | ushort[]                           | [UInt16] + @ArrayField   | Uint16List                  | Array[Short] + metadata         | UShortArray            |
| `array<uint32>` (uint32_array)     | 50           | `@UInt32Type int[]`                       | UInt32Array / ndarray(uint32)             | Type.uint32Array()                    | `uint32_t[n]/vector<T>`                             | `[n]uint32/[]T`                                | `Vec<u32>`                        | uint[]                             | [UInt32] + @ArrayField   | Uint32List                  | Array[Int] + metadata           | UIntArray              |
| `array<uint64>` (uint64_array)     | 51           | `@UInt64Type long[]`                      | UInt64Array / ndarray(uint64)             | Type.uint64Array()                    | `uint64_t[n]/vector<T>`                             | `[n]uint64/[]T`                                | `Vec<u64>`                        | ulong[]                            | [UInt64] + @ArrayField   | Uint64List                  | Array[Long] + metadata          | ULongArray             |
| `array<float8>` (float8_array)     | 52           | /                                         | /                                         | /                                     | /                                                   | /                                              | /                                 | /                                  | /                        | /                           | /                               | /                      |
| `array<float16>` (float16_array)   | 53           | `Float16Array` / `@Float16Type short[]`   | Float16Array / ndarray(float16)           | Float16Array / Type.float16Array()    | `fory::float16_t[n]/std::vector<fory::float16_t>`   | `[N]float16.Float16` / `[]float16.Float16`     | `Vec<Float16>` / `[Float16; N]`   | Half[] / `S.Array<S.Float16>`      | [Float16] + @ArrayField  | Float16List                 | Array[Short] + metadata         | Float16Array           |
| `array<bfloat16>` (bfloat16_array) | 54           | `BFloat16Array` / `@BFloat16Type short[]` | BFloat16Array / ndarray(bfloat16)         | BFloat16Array / Type.bfloat16Array()  | `fory::bfloat16_t[n]/std::vector<fory::bfloat16_t>` | `[N]bfloat16.BFloat16` / `[]bfloat16.BFloat16` | `Vec<BFloat16>` / `[BFloat16; N]` | BFloat16[] / `S.Array<S.BFloat16>` | [BFloat16] + @ArrayField | Bfloat16List                | Array[Short] + metadata         | BFloat16Array          |
| `array<float32>` (float32_array)   | 55           | float[]                                   | Float32Array / ndarray(float32)           | Type.float32Array()                   | `float[n]/vector<T>`                                | `[n]float32/[]T`                               | `Vec<f32>`                        | float[]                            | [Float] + @ArrayField    | Float32List                 | Array[Float]                    | FloatArray             |
| `array<float64>` (float64_array)   | 56           | double[]                                  | Float64Array / ndarray(float64)           | Type.float64Array()                   | `double[n]/vector<T>`                               | `[n]float64/[]T`                               | `Vec<f64>`                        | double[]                           | [Double] + @ArrayField   | Float64List                 | Array[Double]                   | DoubleArray            |

Notes:

- Python `pyfory.Float16` and `pyfory.BFloat16` are reserved annotation markers; scalar values deserialize as native Python `float`.
- Python `BoolArray`, `Int8Array`, `Int16Array`, `Int32Array`, `Int64Array`, `UInt8Array`, `UInt16Array`, `UInt32Array`, `UInt64Array`, `Float16Array`, `BFloat16Array`, `Float32Array`, and `Float64Array` are public dense-array wrappers with list-like sequence behavior.
- JavaScript `BoolArray`, fallback `Float16Array`, and `BFloat16Array` are public dense-array wrappers backed by `Uint8Array` or `Uint16Array`. Scalar `float16` and `bfloat16` values use `number`. A JavaScript environment with native `Float16Array` may return that native carrier for `array<float16>`.
- Java plain `byte[]` maps to `binary`. Numeric byte arrays use type-use annotations:
  `@Int8Type byte[]` for `array<int8>` and `@UInt8Type byte[]` for `array<uint8>`.
- Dart uses `double` plus `Float16Type` or `Bfloat16Type` metadata for scalar
  `float16` and `bfloat16`, `BoolList` for `array<bool>`, typed-data lists for integer/float32/float64 arrays, and
  `Float16List` / `Bfloat16List` for `array<float16>` / `array<bfloat16>`. Plain Dart `List<bool>`
  maps to `list<bool>` unless a field uses `@ArrayField(element: BoolType())` or
  `@ForyField(type: ArrayType(element: BoolType()))` with a `BoolList` carrier.
- `Float16[]` and `BFloat16[]` remain object arrays in xlang mode and serialize with the `list` wire type.
- `ARRAY (42)` is reserved for a future dedicated multi-dimensional array encoding and is not part
  of the current xlang type-mapping surface.
- Current xlang uses `*_ARRAY` for one-dimensional primitive arrays and nested `list` for
  multi-dimensional arrays.
- C++ xlang `date`, `timestamp`, and `duration` map to `fory::Date`, `fory::Timestamp`, and
  `fory::Duration` for generated schemas and dynamic `std::any` values. `std::chrono` temporal
  types are explicit C++ serialization and deserialization targets only.
- Kotlin KSP xlang maps `UByte`, `UShort`, `UInt`, and `ULong` to `uint8`,
  `uint16`, `uint32`, and `uint64`. Kotlin primitive and unsigned array
  carriers map to dense arrays. `ByteArray` maps to `binary` by default and to
  `array<int8>` when its type use is marked with Fory `ArrayType`.
  `array<float16>` and `array<bfloat16>` use Java core `Float16Array` and
  `BFloat16Array`.
- Kotlin xlang `duration` uses `kotlin.time.Duration`. Infinite values are not
  representable by the xlang duration payload and must raise a serialization
  error.
- `list<T>` and `array<T>` remain distinct schema kinds. In schema-compatible struct/class field
  matching only, a direct top-level `list<T>` field may be read as a direct top-level `array<T>`
  field, and a direct top-level `array<T>` field may be read as a direct top-level `list<T>` field,
  when `T` is one of the dense bool/numeric array domains. Integer list element encodings in the
  same signedness and width domain match the corresponding dense array element domain. The rule does
  not apply inside nested collection, map, array, union, or generic positions. A peer `list<T?>`
  element schema is still a compatible schema match for a local `array<T>` field; if the actual
  payload contains a null element, the dense-array reader raises a compatible-read error instead of
  coercing the value. Reference-tracked list-element framing is separate from nullable element
  schema and may be rejected during compatible field classification when the local matched field is
  `array<T>` and the runtime cannot materialize it without generic/reference paths.
- `binary` and `array<uint8>` remain distinct schema kinds. In schema-compatible struct/class field
  matching only, a direct top-level `binary` field may be read as a direct top-level `array<uint8>`
  field and the reverse may be read as the same byte sequence. This rule does not apply inside
  nested collection, map, array, union, or generic positions, and it does not include `array<int8>`.
- The table above remains the canonical xlang schema mapping. Compatible readers may apply the
  scalar field adaptation rules defined by `xlang_serialization_spec.md` during schema-compatible
  struct/class field matching. Those rules do not change TypeDef metadata, dynamic root type
  mapping, same-schema mode, or nested collection/map/array/union/generic positions.

### Scala IDL Mapping

The Scala schema IDL target emits Scala 3 source only. The `fory-scala` artifact remains cross-built
for Scala 2.13 and Scala 3.

| Fory schema kind                      | Scala generated carrier                                                                  |
| ------------------------------------- | ---------------------------------------------------------------------------------------- |
| `optional T`                          | `Option[T]`                                                                              |
| `bool`                                | `Boolean`                                                                                |
| `int8`, `int16`, `int32`, `int64`     | `Byte`, `Short`, `Int`, `Long`                                                           |
| `uint8`, `uint16`, `uint32`, `uint64` | `Int`, `Int`, `Long`, `Long` plus unsigned Fory type metadata                            |
| `float16`, `bfloat16`                 | JVM `Float16` and `BFloat16` carriers                                                    |
| `float32`, `float64`                  | `Float`, `Double`                                                                        |
| `string`                              | `String`                                                                                 |
| `binary`                              | `Array[Byte]`                                                                            |
| `list<T>`, `set<T>`, `map<K, V>`      | `List[T]`, `Set[T]`, `Map[K, V]`                                                         |
| `array<bool>`                         | `Array[Boolean]`                                                                         |
| `array<int8>`, `array<uint8>`         | `Array[Byte]` with signed/unsigned descriptor metadata                                   |
| `array<int16>`, `array<uint16>`       | `Array[Short]` with signed/unsigned descriptor metadata                                  |
| `array<int32>`, `array<uint32>`       | `Array[Int]` with signed/unsigned descriptor metadata                                    |
| `array<int64>`, `array<uint64>`       | `Array[Long]` with signed/unsigned descriptor metadata                                   |
| `array<float16>`, `array<bfloat16>`   | `Array[Short]` with reduced-precision descriptor metadata                                |
| `array<float32>`, `array<float64>`    | `Array[Float]`, `Array[Double]`                                                          |
| `date`, `timestamp`, `duration`       | `java.time.LocalDate`, `java.time.Instant`, `java.time.Duration`                         |
| `decimal`                             | `java.math.BigDecimal`                                                                   |
| `message`                             | Scala 3 `case class` by default; normal class only for message/union construction cycles |
| `enum`                                | Scala 3 `enum` with stable Fory enum IDs on case-level `@ForyEnumId` annotations         |
| `union`                               | Scala 3 ADT `enum derives ForySerializer`                                                |
| `any`                                 | `AnyRef`                                                                                 |

Generated Scala descriptor metadata is produced by Scala 3 macro derivation
from Scala compile-time types, including nested generics, `Option`, arrays,
scalar encoding annotations, nullability, and `@Ref`. Java reflection is not the
source of truth for generated Scala TypeDef metadata. Scala `@Ref` metadata is
represented by the shared `org.apache.fory.annotation.Ref` annotation; `@Ref`
is the JVM owner for reference tracking metadata.

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
