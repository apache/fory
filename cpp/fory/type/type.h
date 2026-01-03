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

#pragma once

#include <cstdint> // For fixed-width integer types

namespace fory {
enum class TypeId : int32_t {
  // Unknown/polymorphic type marker.
  UNKNOWN = 0,
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
  // an union type that can hold different types of values.
  UNION = 38,
  // a null value with no data.
  NONE = 39,
  // Unsigned integer types (native mode only, not supported in xlang mode)
  // an 8-bit unsigned integer.
  U8 = 64,
  // a 16-bit unsigned integer.
  U16 = 65,
  // a 32-bit unsigned integer.
  U32 = 66,
  // a 64-bit unsigned integer.
  U64 = 67,
  // 8-bits character.
  CHAR = 68,
  // 16-bits character
  CHAR16 = 69,
  // 32-bits character
  CHAR32 = 70,
  // Unsigned integer array types (native mode only)
  // one-dimensional uint16 array.
  U16_ARRAY = 73,
  // one-dimensional uint32 array.
  U32_ARRAY = 74,
  // one-dimensional uint64 array.
  U64_ARRAY = 75,
  // Bound value for range checks (types with id >= BOUND are not internal
  // types).
  BOUND = 78
};

inline bool IsUserType(int32_t type_id) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::ENUM:
  case TypeId::NAMED_ENUM:
  case TypeId::STRUCT:
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
  case TypeId::EXT:
  case TypeId::NAMED_EXT:
    return true;
  default:
    return false;
  }
}

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

/// Check if type_id represents a struct type.
/// Struct types include STRUCT, COMPATIBLE_STRUCT, NAMED_STRUCT, and
/// NAMED_COMPATIBLE_STRUCT.
inline constexpr bool is_struct_type(TypeId type_id) {
  return type_id == TypeId::STRUCT || type_id == TypeId::COMPATIBLE_STRUCT ||
         type_id == TypeId::NAMED_STRUCT ||
         type_id == TypeId::NAMED_COMPATIBLE_STRUCT;
}

/// Check if type_id represents an ext type.
/// Ext types include EXT and NAMED_EXT.
inline constexpr bool is_ext_type(TypeId type_id) {
  return type_id == TypeId::EXT || type_id == TypeId::NAMED_EXT;
}

/// Check if type_id represents an internal (built-in) type.
/// Internal types are all types except user-defined types (ENUM, STRUCT, EXT).
/// UNKNOWN is excluded because it's a marker for polymorphic types, not a
/// concrete type.
/// Keep as constexpr for compile time evaluation or constant folding.
inline constexpr bool is_internal_type(uint32_t type_id) {
  if (type_id == 0 || type_id >= static_cast<uint32_t>(TypeId::BOUND)) {
    return false;
  }
  // Internal types are all types that are NOT user types or UNKNOWN
  uint32_t tid_low = type_id & 0xff;
  return tid_low != static_cast<uint32_t>(TypeId::ENUM) &&
         tid_low != static_cast<uint32_t>(TypeId::NAMED_ENUM) &&
         tid_low != static_cast<uint32_t>(TypeId::STRUCT) &&
         tid_low != static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT) &&
         tid_low != static_cast<uint32_t>(TypeId::NAMED_STRUCT) &&
         tid_low != static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT) &&
         tid_low != static_cast<uint32_t>(TypeId::EXT) &&
         tid_low != static_cast<uint32_t>(TypeId::NAMED_EXT) &&
         tid_low != static_cast<uint32_t>(TypeId::UNKNOWN);
}
} // namespace fory
