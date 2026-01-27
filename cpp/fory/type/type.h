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
  VARINT32 = 5,
  // a 64-bit signed integer.
  INT64 = 6,
  // a 64-bit signed integer which uses fory PVL encoding.
  VARINT64 = 7,
  // a 64-bit signed integer which uses fory hybrid encoding.
  TAGGED_INT64 = 8,
  // an 8-bit unsigned integer.
  UINT8 = 9,
  // a 16-bit unsigned integer.
  UINT16 = 10,
  // a 32-bit unsigned integer.
  UINT32 = 11,
  // a 32-bit unsigned integer which uses fory var_uint32 encoding.
  VAR_UINT32 = 12,
  // a 64-bit unsigned integer.
  UINT64 = 13,
  // a 64-bit unsigned integer which uses fory var_uint64 encoding.
  VAR_UINT64 = 14,
  // a 64-bit unsigned integer which uses fory hybrid encoding.
  TAGGED_UINT64 = 15,
  // a 16-bit floating point number.
  FLOAT16 = 16,
  // a 32-bit floating point number.
  FLOAT32 = 17,
  // a 64-bit floating point number including NaN and Infinity.
  FLOAT64 = 18,
  // a text string encoded using Latin1/UTF16/UTF-8 encoding.
  STRING = 19,
  // a sequence of objects.
  LIST = 20,
  // an unordered set of unique elements.
  SET = 21,
  // a map of key-value pairs.
  MAP = 22,
  // a data type consisting of a set of named values.
  ENUM = 23,
  // an enum whose value will be serialized as the registered name.
  NAMED_ENUM = 24,
  // a morphic(final) type serialized by Fory Struct serializer.
  STRUCT = 25,
  // a morphic(final) type serialized by Fory compatible Struct serializer.
  COMPATIBLE_STRUCT = 26,
  // a `struct` whose type mapping will be encoded as a name.
  NAMED_STRUCT = 27,
  // a `compatible_struct` whose type mapping will be encoded as a name.
  NAMED_COMPATIBLE_STRUCT = 28,
  // a type which will be serialized by a customized serializer.
  EXT = 29,
  // an `ext` type whose type mapping will be encoded as a name.
  NAMED_EXT = 30,
  // a union value whose schema identity is not embedded.
  UNION = 31,
  // a union value with embedded numeric union type ID.
  TYPED_UNION = 32,
  // a union value with embedded union type name/TypeDef.
  NAMED_UNION = 33,
  // a null value with no data.
  NONE = 34,
  // an absolute length of time, independent of any calendar/timezone,
  // as a count of nanoseconds.
  DURATION = 35,
  // a point in time, independent of any calendar/timezone, as a count
  // of nanoseconds.
  TIMESTAMP = 36,
  // a naive date without timezone. The count is days relative to an
  // epoch at UTC midnight on Jan 1, 1970.
  DATE = 37,
  // exact decimal value represented as an integer value in two's
  // complement.
  DECIMAL = 38,
  // a variable-length array of bytes.
  BINARY = 39,
  // a multidimensional array with varying sub-array sizes but same type.
  ARRAY = 40,
  // one-dimensional boolean array.
  BOOL_ARRAY = 41,
  // one-dimensional int8 array.
  INT8_ARRAY = 42,
  // one-dimensional int16 array.
  INT16_ARRAY = 43,
  // one-dimensional int32 array.
  INT32_ARRAY = 44,
  // one-dimensional int64 array.
  INT64_ARRAY = 45,
  // one-dimensional uint8 array.
  UINT8_ARRAY = 46,
  // one-dimensional uint16 array.
  UINT16_ARRAY = 47,
  // one-dimensional uint32 array.
  UINT32_ARRAY = 48,
  // one-dimensional uint64 array.
  UINT64_ARRAY = 49,
  // one-dimensional float16 array.
  FLOAT16_ARRAY = 50,
  // one-dimensional float32 array.
  FLOAT32_ARRAY = 51,
  // one-dimensional float64 array.
  FLOAT64_ARRAY = 52,
  // C++ specific types (not part of xlang spec)
  // 8-bits character.
  CHAR = 64,
  // 16-bits character
  CHAR16 = 65,
  // 32-bits character
  CHAR32 = 66,
  // Bound value for range checks (types with id >= BOUND are not internal
  // types).
  BOUND = 67
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
  case TypeId::TYPED_UNION:
  case TypeId::NAMED_UNION:
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
  case TypeId::NAMED_UNION:
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
  case TypeId::NAMED_UNION:
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
         tid_low != static_cast<uint32_t>(TypeId::TYPED_UNION) &&
         tid_low != static_cast<uint32_t>(TypeId::NAMED_UNION) &&
         tid_low != static_cast<uint32_t>(TypeId::UNKNOWN);
}
} // namespace fory
