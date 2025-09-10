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

#include <cstdint> // For fixed-width integer types

namespace fory {
enum class TypeId : int32_t {
  // a boolean value (true or false).
  BOOL = 1,
  // an 8-bit signed integer.
  INT8 = 2,
  // a 16-bit signed integer.
  INT16 = 3,
  // a 32-bit signed integer.
  INT32 = 4,
  // a 32-bit signed integer which uses fory var_int32 encoding.
  VAR_INT32 = 5,
  // a 64-bit signed integer.
  INT64 = 6,
  // a 64-bit signed integer which uses fory PVL encoding.
  VAR_INT64 = 7,
  // a 64-bit signed integer which uses fory SLI encoding.
  SLI_INT64 = 8,
  // a 16-bit floating point number.
  FLOAT16 = 9,
  // a 32-bit floating point number.
  FLOAT32 = 10,
  // a 64-bit floating point number including NaN and Infinity.
  FLOAT64 = 11,
  // a text string encoded using Latin1/UTF16/UTF-8 encoding.
  STRING = 12,
  // a data type consisting of a set of named values.
  ENUM = 13,
  // an enum whose value will be serialized as the registered name.
  NAMED_ENUM = 14,
  // a morphic(final) type serialized by Fory Struct serializer.
  STRUCT = 15,
  // a morphic(final) type serialized by Fory compatible Struct serializer.
  COMPATIBLE_STRUCT = 16,
  // a `struct` whose type mapping will be encoded as a name.
  NAMED_STRUCT = 17,
  // a `compatible_struct` whose type mapping will be encoded as a name.
  NAMED_COMPATIBLE_STRUCT = 18,
  // a type which will be serialized by a customized serializer.
  EXT = 19,
  // an `ext` type whose type mapping will be encoded as a name.
  NAMED_EXT = 20,
  // a sequence of objects.
  LIST = 21,
  // an unordered set of unique elements.
  SET = 22,
  // a map of key-value pairs.
  MAP = 23,
  // an absolute length of time, independent of any calendar/timezone,
  // as a count of nanoseconds.
  DURATION = 24,
  // a point in time, independent of any calendar/timezone, as a count
  // of nanoseconds.
  TIMESTAMP = 25,
  // a naive date without timezone. The count is days relative to an
  // epoch at UTC midnight on Jan 1, 1970.
  LOCAL_DATE = 26,
  // exact decimal value represented as an integer value in two's
  // complement.
  DECIMAL = 27,
  // a variable-length array of bytes.
  BINARY = 28,
  // a multidimensional array with varying sub-array sizes but same type.
  ARRAY = 29,
  // one-dimensional boolean array.
  BOOL_ARRAY = 30,
  // one-dimensional int8 array.
  INT8_ARRAY = 31,
  // one-dimensional int16 array.
  INT16_ARRAY = 32,
  // one-dimensional int32 array.
  INT32_ARRAY = 33,
  // one-dimensional int64 array.
  INT64_ARRAY = 34,
  // one-dimensional float16 array.
  FLOAT16_ARRAY = 35,
  // one-dimensional float32 array.
  FLOAT32_ARRAY = 36,
  // one-dimensional float64 array.
  FLOAT64_ARRAY = 37,
  // an arrow record batch object.
  ARROW_RECORD_BATCH = 38,
  // an arrow table object.
  ARROW_TABLE = 39,
  // Bound value, typically used as a sentinel value.
  BOUND = 64
};

inline bool IsNamespacedType(int32_t type_id) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::NAMED_ENUM:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
  case TypeId::NAMED_EXT:
    return true;
  default:
    return false;
  }
}

inline bool IsTypeShareMeta(int32_t type_id) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::NAMED_ENUM:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_EXT:
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
    return true;
  default:
    return false;
  }
}
} // namespace fory
