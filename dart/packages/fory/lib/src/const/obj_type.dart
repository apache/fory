/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// TODO: not all types are supported, same for foryJava, such as Uint8 and Uint16. The Type enum exists, and the serializer is designed, but they are not used.
/// TODO: foryJava manages writeRef for these types more precisely, such as control for time types and strings, not just basic types. Here we only control basic types.
library;

enum ObjType {
  /// Unknown/polymorphic type marker. For example, a field is a parent class/non-specific class,
  /// which cannot be analyzed during static code generation.
  UNKNOWN(0, false),

  // x
  /// Boolean as 1 bit, LSB bit-packed ordering
  BOOL(1, true), // 1 // This string means that when the Dart type is bool, ObjType.BOOL is used by default.

  // x
  /// Signed 8-bit little-endian integer
  INT8(2, true), // 2

  // x
  /// Signed 16-bit little-endian integer
  INT16(3, true), // 3

  // x
  /// Signed 32-bit little-endian integer
  INT32(4, true), // 4

  // x
  /// var_int32: a 32-bit signed integer which uses fory var_int32 encoding.
  VAR_INT32(5, true), // 5

  // x
  /// Signed 64-bit little-endian integer
  INT64(6, true), // 6

  // x
  /// var_int64: a 64-bit signed integer which uses fory var_int64 encoding.
  VAR_INT64(7, true), // 7

  // x
  /// sli_int64: a 64-bit signed integer which uses fory SLI encoding.
  SLI_INT64(8, true), // 8

  /// float16: a 16-bit floating point number.
  FLOAT16(9, true), // 9

  // x
  /// float32: a 32-bit floating point number.
  FLOAT32(10, true), // 10

  // x
  /// float64: a 64-bit floating point number including NaN and Infinity.
  FLOAT64(11, true), // 11

  // x
  /// string: a text string encoded using Latin1/UTF16/UTF-8 encoding.
  STRING(12, true), // 12

  // x
  /// enum: a data type consisting of a set of named values.
  ENUM(13, true), // 13

  /// named_enum: an enum whose value will be serialized as the registered name.
  NAMED_ENUM(14, true), // 14

  /// A dynamic(final) type serialized by Fory Struct serializer. i.e. it doesn't have subclasses.
  /// Suppose we're deserializing {@code List<SomeClass>}, we can save dynamic serializer dispatch
  /// since `SomeClass` is dynamic(final).
  STRUCT(15, false), // 15

  /// A dynamic(final) type serialized by Fory compatible Struct serializer.
  COMPATIBLE_STRUCT(16, false), // 16

  // x
  /// A `struct` whose type mapping will be encoded as a name.
  NAMED_STRUCT(17, false), // 17

  /// A `compatible_struct` whose type mapping will be encoded as a name.
  NAMED_COMPATIBLE_STRUCT(18, false), // 18

  /// A type which will be serialized by a customized serializer.
  EXT(19, false), // 19

  /// An `ext` type whose type mapping will be encoded as a name.
  NAMED_EXT(20, false), // 20

  // x
  /// A sequence of objects.
  LIST(21, false), // 21

  // x
  /// An unordered set of unique elements.
  SET(22, false), // 22

  // x
  /// A map of key-value pairs. Mutable types such as `list/map/set/array/tensor/arrow` are not
  /// allowed as key of map.
  MAP(23, false), // 23

  /// An absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
  DURATION(24, true), // 24

  // TODO: here time
  // x
  /// A point in time, independent of any calendar/timezone, as a count of nanoseconds. The count is
  /// relative to an epoch at UTC midnight on January 1, 1970.
  TIMESTAMP(25, true), // 25

  // TODO: here time
  /// A naive date without timezone. The count is days relative to an epoch at UTC midnight on Jan 1,
  /// 1970.
  DATE(26, true), // 26

  /// Exact decimal value represented as an integer value in two's complement.
  DECIMAL(27, true), // 27

  // x
  /// A variable-length array of bytes.
  BINARY(28, true), // 28

  /// x
  /// A multidimensional array where every sub-array can have different sizes but all have the same
  /// type. Only numeric components allowed. Other arrays will be taken as List. The implementation
  /// should support interoperability between array and list.
  ARRAY(29, false), // 29

  /// One dimensional bool array.
  BOOL_ARRAY(30, true), // 30

  /// One dimensional int8 array.
  INT8_ARRAY(31, true), // 31

  /// One dimensional int16 array.
  INT16_ARRAY(32, true), // 32

  /// One dimensional int32 array.
  INT32_ARRAY(33, true), // 33

  /// One dimensional int64 array.
  INT64_ARRAY(34, true), // 34

  /// One dimensional half_float_16 array.
  FLOAT16_ARRAY(35, true), // 35

  /// One dimensional float32 array.
  FLOAT32_ARRAY(36, true), // 36

  /// One dimensional float64 array.
  FLOAT64_ARRAY(37, true), // 37

  /// An (arrow record batch) object.
  ARROW_RECORD_BATCH(38, false), // 38

  /// An (arrow table) object.
  ARROW_TABLE(39, false), // 39

  /// Unsigned 8-bit integer
  UINT8(40, true), // 40

  /// Unsigned 16-bit little-endian integer
  UINT16(41, true), // 41

  /// Unsigned 32-bit little-endian integer
  UINT32(42, true), // 42

  /// var_uint32: a 32-bit unsigned integer which uses fory var_uint32 encoding.
  VAR_UINT32(43, true), // 43

  /// Unsigned 64-bit little-endian integer
  UINT64(44, true), // 44

  /// var_uint64: a 64-bit unsigned integer which uses fory var_uint64 encoding.
  VAR_UINT64(45, true), // 45

  /// tagged_uint64: a 64-bit unsigned integer which uses fory tagged var_uint64 encoding.
  TAGGED_UINT64(46, true); // 46

  final int id;
  final bool independent;

  const ObjType(this.id, this.independent);

  static ObjType? fromId(int id) {
    // The current implementation is linear, so it's simpler here. If the id and ordinal become irregular in the future, this won't work.
    if (id >= 1 && id <= 46) return ObjType.values[id];
    return null;
  }

  // Helper methods
  bool isStructType() {
    return this == STRUCT
        || this == COMPATIBLE_STRUCT
        || this == NAMED_STRUCT
        || this == NAMED_COMPATIBLE_STRUCT;
  }

  bool isTimeType() {
    return this == TIMESTAMP
        || this == DATE
        || this == DURATION;
  }
}
