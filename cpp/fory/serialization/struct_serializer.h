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

#include "fory/meta/enum_info.h"
#include "fory/meta/field.h"
#include "fory/meta/field_info.h"
#include "fory/meta/preprocessor.h"
#include "fory/meta/type_traits.h"
#include "fory/serialization/serializer.h"
#include "fory/serialization/serializer_traits.h"
#include "fory/serialization/skip.h"
#include "fory/serialization/type_resolver.h"
#include "fory/util/string_util.h"
#include <algorithm>
#include <array>
#include <memory>
#include <numeric>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>

#ifdef FORY_DEBUG
#include <iostream>
#endif

namespace fory {
namespace serialization {

/// Field type markers for collection fields in compatible/evolution mode.
/// These match Java's FieldResolver.FieldTypes values.
constexpr int8_t FIELD_TYPE_OBJECT = 0;
constexpr int8_t FIELD_TYPE_COLLECTION_ELEMENT_FINAL = 1;
constexpr int8_t FIELD_TYPE_MAP_KEY_FINAL = 2;
constexpr int8_t FIELD_TYPE_MAP_VALUE_FINAL = 3;
constexpr int8_t FIELD_TYPE_MAP_KV_FINAL = 4;

/// Serialization metadata for a type.
///
/// This template is populated automatically when `FORY_STRUCT` is used to
/// register a type. The registration macro defines an ADL-visible marker
/// function which this trait detects in order to enable serialization. The
/// field count is derived from the generated `ForyFieldInfo` metadata.
template <typename T, typename Enable> struct SerializationMeta {
  static constexpr bool is_serializable = false;
  static constexpr size_t field_count = 0;
};
template <typename T>
struct SerializationMeta<
    T, std::void_t<decltype(ForyStructMarker(std::declval<const T &>()))>> {
  static constexpr bool is_serializable = true;
  static constexpr size_t field_count =
      decltype(ForyFieldInfo(std::declval<const T &>()))::Size;
};

/// Main serialization registration macro.
///
/// This macro must be placed in the same namespace as the type for ADL
/// (Argument-Dependent Lookup).
///
/// It builds upon FORY_FIELD_INFO to add serialization-specific metadata:
/// - Marks the type as serializable
/// - Provides compile-time metadata access
///
/// Example:
/// ```cpp
/// namespace myapp {
///   struct Person {
///     std::string name;
///     int32_t age;
///   };
///   FORY_STRUCT(Person, name, age);
/// }
/// ```
///
/// After expansion, the type can be serialized using Fory:
/// ```cpp
/// fory::serialization::Fory fory;
/// myapp::Person person{"Alice", 30};
/// auto bytes = fory.serialize(person);
/// ```
/// Main struct registration macro.
/// TypeIndex uses the fallback (type_fallback_hash based on PRETTY_FUNCTION)
/// which provides unique type identification without namespace issues.
#define FORY_STRUCT(Type, ...)                                                 \
  FORY_FIELD_INFO(Type, __VA_ARGS__)                                           \
  inline constexpr std::true_type ForyStructMarker(const Type &) noexcept {    \
    return {};                                                                 \
  }

namespace detail {

/// Helper to check if a TypeId represents a primitive type.
/// Per xlang spec, primitive types are: bool, int8-64, var_int32/64,
/// sli_int64, float16/32/64. For native mode (xlang=false), also includes
/// unsigned types: u8-64. All other types (string, list, set, map, struct,
/// enum, etc.) are non-primitive and require ref flags.
inline constexpr bool is_primitive_type_id(TypeId type_id) {
  return type_id == TypeId::BOOL || type_id == TypeId::INT8 ||
         type_id == TypeId::INT16 || type_id == TypeId::INT32 ||
         type_id == TypeId::VAR_INT32 || type_id == TypeId::INT64 ||
         type_id == TypeId::VAR_INT64 || type_id == TypeId::SLI_INT64 ||
         type_id == TypeId::FLOAT16 || type_id == TypeId::FLOAT32 ||
         type_id == TypeId::FLOAT64 ||
         // Unsigned types for native mode (xlang=false)
         type_id == TypeId::U8 || type_id == TypeId::U16 ||
         type_id == TypeId::U32 || type_id == TypeId::U64;
}

/// Write a primitive value to buffer at given offset WITHOUT updating
/// writer_index. Returns the number of bytes written. Caller must ensure buffer
/// has sufficient capacity.
template <typename T>
FORY_ALWAYS_INLINE uint32_t put_primitive_at(T value, Buffer &buffer,
                                             uint32_t offset) {
  if constexpr (std::is_same_v<T, int32_t> || std::is_same_v<T, int>) {
    // varint32 with zigzag encoding
    int32_t val = static_cast<int32_t>(value);
    uint32_t zigzag =
        (static_cast<uint32_t>(val) << 1) ^ static_cast<uint32_t>(val >> 31);
    return buffer.PutVarUint32(offset, zigzag);
  } else if constexpr (std::is_same_v<T, uint32_t> ||
                       std::is_same_v<T, unsigned int>) {
    buffer.UnsafePut<uint32_t>(offset, static_cast<uint32_t>(value));
    return 4;
  } else if constexpr (std::is_same_v<T, int64_t> ||
                       std::is_same_v<T, long long>) {
    // varint64 with zigzag encoding
    int64_t val = static_cast<int64_t>(value);
    uint64_t zigzag =
        (static_cast<uint64_t>(val) << 1) ^ static_cast<uint64_t>(val >> 63);
    return buffer.PutVarUint64(offset, zigzag);
  } else if constexpr (std::is_same_v<T, uint64_t> ||
                       std::is_same_v<T, unsigned long long>) {
    buffer.UnsafePut<uint64_t>(offset, static_cast<uint64_t>(value));
    return 8;
  } else if constexpr (std::is_same_v<T, bool>) {
    buffer.UnsafePutByte(offset, static_cast<uint8_t>(value ? 1 : 0));
    return 1;
  } else if constexpr (std::is_same_v<T, int8_t> ||
                       std::is_same_v<T, uint8_t>) {
    buffer.UnsafePutByte(offset, static_cast<uint8_t>(value));
    return 1;
  } else if constexpr (std::is_same_v<T, int16_t> ||
                       std::is_same_v<T, uint16_t>) {
    buffer.UnsafePut<T>(offset, value);
    return 2;
  } else if constexpr (std::is_same_v<T, float>) {
    buffer.UnsafePut<float>(offset, value);
    return 4;
  } else if constexpr (std::is_same_v<T, double>) {
    buffer.UnsafePut<double>(offset, value);
    return 8;
  } else {
    static_assert(sizeof(T) == 0, "Unsupported primitive type");
    return 0;
  }
}

/// Write a fixed-size primitive at absolute offset. Does NOT return bytes
/// written (caller uses compile-time size). Caller ensures buffer capacity.
template <typename T>
FORY_ALWAYS_INLINE void put_fixed_primitive_at(T value, Buffer &buffer,
                                               uint32_t offset) {
  if constexpr (std::is_same_v<T, bool>) {
    buffer.UnsafePutByte(offset, static_cast<uint8_t>(value ? 1 : 0));
  } else if constexpr (std::is_same_v<T, int8_t> ||
                       std::is_same_v<T, uint8_t>) {
    buffer.UnsafePutByte(offset, static_cast<uint8_t>(value));
  } else if constexpr (std::is_same_v<T, int16_t> ||
                       std::is_same_v<T, uint16_t>) {
    buffer.UnsafePut<T>(offset, value);
  } else if constexpr (std::is_same_v<T, uint32_t> ||
                       std::is_same_v<T, unsigned int>) {
    buffer.UnsafePut<uint32_t>(offset, static_cast<uint32_t>(value));
  } else if constexpr (std::is_same_v<T, uint64_t> ||
                       std::is_same_v<T, unsigned long long>) {
    buffer.UnsafePut<uint64_t>(offset, static_cast<uint64_t>(value));
  } else if constexpr (std::is_same_v<T, float>) {
    buffer.UnsafePut<float>(offset, value);
  } else if constexpr (std::is_same_v<T, double>) {
    buffer.UnsafePut<double>(offset, value);
  } else {
    static_assert(sizeof(T) == 0, "Unsupported fixed-size primitive type");
  }
}

/// Write a varint primitive at offset. Returns bytes written.
/// Caller ensures buffer capacity.
template <typename T>
FORY_ALWAYS_INLINE uint32_t put_varint_at(T value, Buffer &buffer,
                                          uint32_t offset) {
  if constexpr (std::is_same_v<T, int32_t> || std::is_same_v<T, int>) {
    // varint32 with zigzag encoding
    int32_t val = static_cast<int32_t>(value);
    uint32_t zigzag =
        (static_cast<uint32_t>(val) << 1) ^ static_cast<uint32_t>(val >> 31);
    return buffer.PutVarUint32(offset, zigzag);
  } else if constexpr (std::is_same_v<T, int64_t> ||
                       std::is_same_v<T, long long>) {
    // varint64 with zigzag encoding
    int64_t val = static_cast<int64_t>(value);
    uint64_t zigzag =
        (static_cast<uint64_t>(val) << 1) ^ static_cast<uint64_t>(val >> 63);
    return buffer.PutVarUint64(offset, zigzag);
  } else {
    static_assert(sizeof(T) == 0, "Unsupported varint type");
    return 0;
  }
}

template <size_t... Indices, typename Func>
void for_each_index(std::index_sequence<Indices...>, Func &&func) {
  (func(std::integral_constant<size_t, Indices>{}), ...);
}

template <typename T, typename Func, size_t... Indices>
void dispatch_field_index_impl(size_t target_index, Func &&func,
                               std::index_sequence<Indices...>, bool &handled) {
  handled = ((target_index == Indices
                  ? (func(std::integral_constant<size_t, Indices>{}), true)
                  : false) ||
             ...);
}

template <typename T, typename Func>
void dispatch_field_index(size_t target_index, Func &&func, bool &handled) {
  constexpr size_t field_count =
      decltype(ForyFieldInfo(std::declval<const T &>()))::Size;
  dispatch_field_index_impl<T>(target_index, std::forward<Func>(func),
                               std::make_index_sequence<field_count>{},
                               handled);
}

// ------------------------------------------------------------------
// Compile-time helpers to compute sorted field indices / names and
// create small jump-table wrappers to unroll read/write per-field calls.
// The goal is to mimic the Rust-derived serializer behaviour where the
// sorted field order is known at compile-time and the read path for
// compatible mode uses a fast switch/jump table.
// ------------------------------------------------------------------

template <typename T> struct CompileTimeFieldHelpers {
  using FieldDescriptor = decltype(ForyFieldInfo(std::declval<const T &>()));
  static constexpr size_t FieldCount = FieldDescriptor::Size;
  static inline constexpr auto Names = FieldDescriptor::Names;
  static inline constexpr auto Ptrs = FieldDescriptor::Ptrs;
  using FieldPtrs = decltype(Ptrs);

  template <size_t Index> static constexpr uint32_t field_type_id() {
    if constexpr (FieldCount == 0) {
      return 0;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;
      // Unwrap fory::field<> to get the actual type for serialization
      using FieldType = unwrap_field_t<RawFieldType>;
      return static_cast<uint32_t>(Serializer<FieldType>::type_id);
    }
  }

  /// Returns true if the field at Index is nullable for fingerprint
  /// computation. This checks:
  /// 1. If the field is a fory::field<>, use its is_nullable metadata
  /// 2. Else if FORY_FIELD_TAGS is defined, use that metadata
  /// 3. Otherwise, use xlang defaults: only std::optional is nullable
  ///    (For xlang: nullable=false by default, except for Optional types)
  template <size_t Index> static constexpr bool field_nullable() {
    if constexpr (FieldCount == 0) {
      return false;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;

      // If it's a fory::field<> wrapper, use its metadata
      if constexpr (is_fory_field_v<RawFieldType>) {
        return RawFieldType::is_nullable;
      }
      // Else if FORY_FIELD_TAGS is defined, use that metadata
      else if constexpr (::fory::detail::has_field_tags_v<T>) {
        return ::fory::detail::GetFieldTagEntry<T, Index>::is_nullable;
      }
      // For non-wrapped types, use xlang defaults:
      // Only std::optional is nullable (field_is_nullable_v returns true for
      // optional). For xlang consistency, shared_ptr/unique_ptr are NOT
      // nullable by default - users must explicitly mark them as nullable.
      else {
        return field_is_nullable_v<RawFieldType>;
      }
    }
  }

  /// Returns the tag ID for the field at Index.
  /// Returns -1 if no tag ID is defined.
  template <size_t Index> static constexpr int16_t field_tag_id() {
    if constexpr (FieldCount == 0) {
      return -1;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;

      // If it's a fory::field<> wrapper, use its tag_id
      if constexpr (is_fory_field_v<RawFieldType>) {
        return RawFieldType::tag_id;
      }
      // Else if FORY_FIELD_TAGS is defined, use that metadata
      else if constexpr (::fory::detail::has_field_tags_v<T>) {
        return ::fory::detail::GetFieldTagEntry<T, Index>::id;
      }
      // No tag ID defined
      else {
        return -1;
      }
    }
  }

  /// Returns true if reference tracking is enabled for the field at Index.
  /// Only valid for std::shared_ptr fields with fory::ref tag.
  template <size_t Index> static constexpr bool field_track_ref() {
    if constexpr (FieldCount == 0) {
      return false;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;

      // If it's a fory::field<> wrapper, use its track_ref metadata
      if constexpr (is_fory_field_v<RawFieldType>) {
        return RawFieldType::track_ref;
      }
      // Else if FORY_FIELD_TAGS is defined, use that metadata
      else if constexpr (::fory::detail::has_field_tags_v<T>) {
        return ::fory::detail::GetFieldTagEntry<T, Index>::track_ref;
      }
      // Default: no reference tracking
      else {
        return false;
      }
    }
  }

  /// Returns true if the field at Index is marked as monomorphic.
  /// Use this for shared_ptr/unique_ptr fields with polymorphic inner types
  /// when you know the actual runtime type will always be exactly T.
  template <size_t Index> static constexpr bool field_monomorphic() {
    if constexpr (FieldCount == 0) {
      return false;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;

      // If it's a fory::field<> wrapper, use its is_monomorphic metadata
      if constexpr (is_fory_field_v<RawFieldType>) {
        return RawFieldType::is_monomorphic;
      }
      // Else if FORY_FIELD_TAGS is defined, use that metadata
      else if constexpr (::fory::detail::has_field_tags_v<T>) {
        return ::fory::detail::GetFieldTagEntry<T, Index>::is_monomorphic;
      }
      // Default: not monomorphic (polymorphic types use dynamic dispatch)
      else {
        return false;
      }
    }
  }

  /// Get the underlying field type (unwraps fory::field<> if present)
  template <size_t Index> struct UnwrappedFieldTypeHelper {
    using PtrT = std::tuple_element_t<Index, FieldPtrs>;
    using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;
    using type = unwrap_field_t<RawFieldType>;
  };
  template <size_t Index>
  using UnwrappedFieldType = typename UnwrappedFieldTypeHelper<Index>::type;

  /// Legacy compatibility: returns true if field requires ref metadata
  /// in the wire format (i.e., is optional/nullable)
  template <size_t Index> static constexpr bool field_requires_ref_metadata() {
    if constexpr (FieldCount == 0) {
      return false;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;
      using FieldType = unwrap_field_t<RawFieldType>;
      // Check the unwrapped type
      return requires_ref_metadata_v<FieldType>;
    }
  }

  /// Check if field at Index uses fixed-size encoding based on C++ type
  /// Fixed types: bool, int8, uint8, int16, uint16, uint32, uint64, float,
  /// double Note: TypeId::INT32/INT64 can be either signed (varint) or unsigned
  /// (fixed)
  template <size_t Index> static constexpr bool field_is_fixed_primitive() {
    if constexpr (FieldCount == 0) {
      return false;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;
      using FieldType = unwrap_field_t<RawFieldType>;
      return std::is_same_v<FieldType, bool> ||
             std::is_same_v<FieldType, int8_t> ||
             std::is_same_v<FieldType, uint8_t> ||
             std::is_same_v<FieldType, int16_t> ||
             std::is_same_v<FieldType, uint16_t> ||
             std::is_same_v<FieldType, uint32_t> ||
             std::is_same_v<FieldType, unsigned int> ||
             std::is_same_v<FieldType, uint64_t> ||
             std::is_same_v<FieldType, unsigned long long> ||
             std::is_same_v<FieldType, float> ||
             std::is_same_v<FieldType, double>;
    }
  }

  /// Check if field at Index uses varint encoding based on C++ type
  /// Varint types: int32, int, int64, long long (signed integers use zigzag)
  template <size_t Index> static constexpr bool field_is_varint_primitive() {
    if constexpr (FieldCount == 0) {
      return false;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;
      using FieldType = unwrap_field_t<RawFieldType>;
      return std::is_same_v<FieldType, int32_t> ||
             std::is_same_v<FieldType, int> ||
             std::is_same_v<FieldType, int64_t> ||
             std::is_same_v<FieldType, long long>;
    }
  }

  /// Get fixed size in bytes for a field based on its C++ type
  template <size_t Index> static constexpr size_t field_fixed_size_bytes() {
    if constexpr (FieldCount == 0) {
      return 0;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;
      using FieldType = unwrap_field_t<RawFieldType>;
      if constexpr (std::is_same_v<FieldType, bool> ||
                    std::is_same_v<FieldType, int8_t> ||
                    std::is_same_v<FieldType, uint8_t>) {
        return 1;
      } else if constexpr (std::is_same_v<FieldType, int16_t> ||
                           std::is_same_v<FieldType, uint16_t>) {
        return 2;
      } else if constexpr (std::is_same_v<FieldType, uint32_t> ||
                           std::is_same_v<FieldType, unsigned int> ||
                           std::is_same_v<FieldType, float>) {
        return 4;
      } else if constexpr (std::is_same_v<FieldType, uint64_t> ||
                           std::is_same_v<FieldType, unsigned long long> ||
                           std::is_same_v<FieldType, double>) {
        return 8;
      } else {
        return 0; // Not a fixed-size primitive
      }
    }
  }

  /// Get max varint size in bytes for a field based on its C++ type
  template <size_t Index> static constexpr size_t field_max_varint_bytes() {
    if constexpr (FieldCount == 0) {
      return 0;
    } else {
      using PtrT = std::tuple_element_t<Index, FieldPtrs>;
      using RawFieldType = meta::RemoveMemberPointerCVRefT<PtrT>;
      using FieldType = unwrap_field_t<RawFieldType>;
      if constexpr (std::is_same_v<FieldType, int32_t> ||
                    std::is_same_v<FieldType, int>) {
        return 5; // int32 varint max
      } else if constexpr (std::is_same_v<FieldType, int64_t> ||
                           std::is_same_v<FieldType, long long>) {
        return 10; // int64 varint max
      } else {
        return 0; // Not a varint primitive
      }
    }
  }

  /// Create arrays of field encoding info at compile time
  template <size_t... Indices>
  static constexpr std::array<bool, FieldCount>
  make_field_is_fixed_array(std::index_sequence<Indices...>) {
    if constexpr (FieldCount == 0) {
      return {};
    } else {
      return {field_is_fixed_primitive<Indices>()...};
    }
  }

  template <size_t... Indices>
  static constexpr std::array<bool, FieldCount>
  make_field_is_varint_array(std::index_sequence<Indices...>) {
    if constexpr (FieldCount == 0) {
      return {};
    } else {
      return {field_is_varint_primitive<Indices>()...};
    }
  }

  template <size_t... Indices>
  static constexpr std::array<size_t, FieldCount>
  make_field_fixed_size_array(std::index_sequence<Indices...>) {
    if constexpr (FieldCount == 0) {
      return {};
    } else {
      return {field_fixed_size_bytes<Indices>()...};
    }
  }

  template <size_t... Indices>
  static constexpr std::array<size_t, FieldCount>
  make_field_max_varint_array(std::index_sequence<Indices...>) {
    if constexpr (FieldCount == 0) {
      return {};
    } else {
      return {field_max_varint_bytes<Indices>()...};
    }
  }

  /// Arrays storing encoding info for each field (indexed by original field
  /// index)
  static inline constexpr std::array<bool, FieldCount> field_is_fixed =
      make_field_is_fixed_array(std::make_index_sequence<FieldCount>{});
  static inline constexpr std::array<bool, FieldCount> field_is_varint =
      make_field_is_varint_array(std::make_index_sequence<FieldCount>{});
  static inline constexpr std::array<size_t, FieldCount> field_fixed_sizes =
      make_field_fixed_size_array(std::make_index_sequence<FieldCount>{});
  static inline constexpr std::array<size_t, FieldCount> field_max_varints =
      make_field_max_varint_array(std::make_index_sequence<FieldCount>{});

  template <size_t... Indices>
  static constexpr std::array<uint32_t, FieldCount>
  make_type_ids(std::index_sequence<Indices...>) {
    if constexpr (FieldCount == 0) {
      return {};
    } else {
      return {field_type_id<Indices>()...};
    }
  }

  template <size_t... Indices>
  static constexpr std::array<bool, FieldCount>
  make_nullable_flags(std::index_sequence<Indices...>) {
    if constexpr (FieldCount == 0) {
      return {};
    } else {
      return {field_nullable<Indices>()...};
    }
  }

  template <size_t... Indices>
  static constexpr std::array<bool, FieldCount>
  make_requires_ref_metadata_flags(std::index_sequence<Indices...>) {
    if constexpr (FieldCount == 0) {
      return {};
    } else {
      return {field_requires_ref_metadata<Indices>()...};
    }
  }

  static inline constexpr std::array<uint32_t, FieldCount> type_ids =
      make_type_ids(std::make_index_sequence<FieldCount>{});

  static inline constexpr std::array<bool, FieldCount> nullable_flags =
      make_nullable_flags(std::make_index_sequence<FieldCount>{});

  /// Flags for fields that require ref metadata encoding (smart pointers,
  /// optional)
  static inline constexpr std::array<bool, FieldCount>
      requires_ref_metadata_flags = make_requires_ref_metadata_flags(
          std::make_index_sequence<FieldCount>{});

  static inline constexpr std::array<size_t, FieldCount> snake_case_lengths =
      []() constexpr {
        std::array<size_t, FieldCount> lengths{};
        if constexpr (FieldCount > 0) {
          for (size_t i = 0; i < FieldCount; ++i) {
            lengths[i] = ::fory::snake_case_length(Names[i]);
          }
        }
        return lengths;
      }();

  static constexpr size_t compute_max_snake_length() {
    size_t max_length = 0;
    if constexpr (FieldCount > 0) {
      for (size_t length : snake_case_lengths) {
        if (length > max_length) {
          max_length = length;
        }
      }
    }
    return max_length;
  }

  static inline constexpr size_t max_snake_case_length =
      compute_max_snake_length();

  static inline constexpr std::array<
      std::array<char, max_snake_case_length + 1>, FieldCount>
      snake_case_storage = []() constexpr {
        std::array<std::array<char, max_snake_case_length + 1>, FieldCount>
            storage{};
        if constexpr (FieldCount > 0) {
          for (size_t i = 0; i < FieldCount; ++i) {
            const auto [buffer, length] =
                ::fory::to_snake_case<max_snake_case_length>(Names[i]);
            (void)length;
            storage[i] = buffer;
          }
        }
        return storage;
      }();

  static inline constexpr std::array<std::string_view, FieldCount>
      snake_case_names = []() constexpr {
        std::array<std::string_view, FieldCount> names{};
        if constexpr (FieldCount > 0) {
          for (size_t i = 0; i < FieldCount; ++i) {
            names[i] = std::string_view(snake_case_storage[i].data(),
                                        snake_case_lengths[i]);
          }
        }
        return names;
      }();

  static constexpr bool is_primitive_type_id(uint32_t tid) {
    return tid >= static_cast<uint32_t>(TypeId::BOOL) &&
           tid <= static_cast<uint32_t>(TypeId::FLOAT64);
  }

  static constexpr int32_t primitive_type_size(uint32_t tid) {
    switch (static_cast<TypeId>(tid)) {
    case TypeId::BOOL:
    case TypeId::INT8:
      return 1;
    case TypeId::INT16:
    case TypeId::FLOAT16:
      return 2;
    case TypeId::INT32:
    case TypeId::VAR_INT32:
    case TypeId::FLOAT32:
      return 4;
    case TypeId::INT64:
    case TypeId::VAR_INT64:
    case TypeId::FLOAT64:
      return 8;
    default:
      return 0;
    }
  }

  static constexpr bool is_compress_id(uint32_t tid) {
    return tid == static_cast<uint32_t>(TypeId::INT32) ||
           tid == static_cast<uint32_t>(TypeId::INT64) ||
           tid == static_cast<uint32_t>(TypeId::VAR_INT32) ||
           tid == static_cast<uint32_t>(TypeId::VAR_INT64);
  }

  /// Check if a type ID is an internal (built-in, final) type for group 2.
  /// Internal types are STRING, DURATION, TIMESTAMP, LOCAL_DATE, DECIMAL,
  /// BINARY. Java xlang DescriptorGrouper excludes enums from finals (line 897
  /// in XtypeResolver). Excludes: ENUM (13-14), STRUCT (15-18), EXT (19-20),
  /// LIST (21), SET (22), MAP (23)
  static constexpr bool is_internal_type_id(uint32_t tid) {
    return tid == static_cast<uint32_t>(TypeId::STRING) ||
           (tid >= static_cast<uint32_t>(TypeId::DURATION) &&
            tid <= static_cast<uint32_t>(TypeId::BINARY));
  }

  static constexpr int group_rank(size_t index) {
    if constexpr (FieldCount == 0) {
      return 6;
    } else {
      uint32_t tid = type_ids[index];
      bool nullable = nullable_flags[index];
      if (is_primitive_type_id(tid)) {
        return nullable ? 1 : 0;
      }
      // Check LIST/SET/MAP BEFORE is_internal_type_id since they fall
      // within the internal type range (STRING=12 to DECIMAL=27) but
      // need their own groups for proper field ordering.
      if (tid == static_cast<uint32_t>(TypeId::LIST))
        return 3;
      if (tid == static_cast<uint32_t>(TypeId::SET))
        return 4;
      if (tid == static_cast<uint32_t>(TypeId::MAP))
        return 5;
      if (is_internal_type_id(tid))
        return 2;
      return 6;
    }
  }

  static constexpr bool field_compare(size_t a, size_t b) {
    if constexpr (FieldCount == 0) {
      return false;
    } else {
      int ga = group_rank(a);
      int gb = group_rank(b);
      if (ga != gb)
        return ga < gb;

      uint32_t a_tid = type_ids[a];
      uint32_t b_tid = type_ids[b];
      bool a_null = nullable_flags[a];
      bool b_null = nullable_flags[b];

      if (ga == 0 || ga == 1) {
        bool compress_a = is_compress_id(a_tid);
        bool compress_b = is_compress_id(b_tid);
        int32_t sa = primitive_type_size(a_tid);
        int32_t sb = primitive_type_size(b_tid);
        if (a_null != b_null)
          return !a_null;
        if (compress_a != compress_b)
          return !compress_a;
        if (sa != sb)
          return sa > sb;
        if (a_tid != b_tid)
          return a_tid > b_tid; // type_id descending to match Java
        return snake_case_names[a] < snake_case_names[b];
      }

      if (ga == 2) {
        // Internal types (STRING, etc.): sort by type_id ascending, then name
        if (a_tid != b_tid)
          return a_tid < b_tid;
        return snake_case_names[a] < snake_case_names[b];
      }

      return snake_case_names[a] < snake_case_names[b];
    }
  }

  static constexpr std::array<size_t, FieldCount> compute_sorted_indices() {
    std::array<size_t, FieldCount> indices{};
    for (size_t i = 0; i < FieldCount; ++i) {
      indices[i] = i;
    }
    for (size_t i = 0; i < FieldCount; ++i) {
      size_t best = i;
      for (size_t j = i + 1; j < FieldCount; ++j) {
        if (field_compare(indices[j], indices[best])) {
          best = j;
        }
      }
      if (best != i) {
        size_t tmp = indices[i];
        indices[i] = indices[best];
        indices[best] = tmp;
      }
    }
    return indices;
  }

  static inline constexpr std::array<size_t, FieldCount> sorted_indices =
      compute_sorted_indices();

  static inline constexpr std::array<std::string_view, FieldCount>
      sorted_field_names = []() constexpr {
        std::array<std::string_view, FieldCount> arr{};
        for (size_t i = 0; i < FieldCount; ++i) {
          arr[i] = snake_case_names[sorted_indices[i]];
        }
        return arr;
      }();

  /// Check if ALL fields are primitives and non-nullable (can use fast path)
  /// Also excludes fields that require ref metadata (smart pointers, optional)
  /// since their type_id may be the element type but they need special
  /// handling.
  static constexpr bool compute_all_primitives_non_nullable() {
    if constexpr (FieldCount == 0) {
      return true;
    } else {
      for (size_t i = 0; i < FieldCount; ++i) {
        if (!is_primitive_type_id(type_ids[i]) || nullable_flags[i] ||
            requires_ref_metadata_flags[i]) {
          return false;
        }
      }
      return true;
    }
  }

  static inline constexpr bool all_primitives_non_nullable =
      compute_all_primitives_non_nullable();

  /// Compute max serialized size for all primitive fields (for buffer
  /// pre-reservation)
  static constexpr size_t compute_max_primitive_size() {
    if constexpr (FieldCount == 0) {
      return 0;
    } else {
      size_t total = 0;
      for (size_t i = 0; i < FieldCount; ++i) {
        // Varint max: 5 bytes for int32, 10 bytes for int64
        // Fixed: 1/2/4/8 bytes
        uint32_t tid = type_ids[i];
        switch (static_cast<TypeId>(tid)) {
        case TypeId::BOOL:
        case TypeId::INT8:
          total += 1;
          break;
        case TypeId::INT16:
        case TypeId::FLOAT16:
          total += 2;
          break;
        case TypeId::INT32:
        case TypeId::VAR_INT32:
          total += 8; // varint max, but bulk write may write up to 8 bytes
          break;
        case TypeId::FLOAT32:
          total += 4;
          break;
        case TypeId::INT64:
        case TypeId::VAR_INT64:
        case TypeId::SLI_INT64:
          total += 10; // varint max
          break;
        case TypeId::FLOAT64:
          total += 8;
          break;
        default:
          total += 10; // safe default
          break;
        }
      }
      return total;
    }
  }

  static inline constexpr size_t max_primitive_serialized_size =
      compute_max_primitive_size();

  /// Count leading non-nullable primitive fields in sorted order.
  /// Since fields are sorted with non-nullable primitives first (group 0),
  /// we can fast-write these fields and slow-write the rest.
  /// Excludes fields that require ref metadata (smart pointers, optional).
  static constexpr size_t compute_primitive_field_count() {
    if constexpr (FieldCount == 0) {
      return 0;
    } else {
      size_t count = 0;
      for (size_t i = 0; i < FieldCount; ++i) {
        size_t original_idx = sorted_indices[i];
        if (is_primitive_type_id(type_ids[original_idx]) &&
            !nullable_flags[original_idx] &&
            !requires_ref_metadata_flags[original_idx]) {
          ++count;
        } else {
          break; // Non-nullable primitives are always first in sorted order
        }
      }
      return count;
    }
  }

  static inline constexpr size_t primitive_field_count =
      compute_primitive_field_count();

  /// Check if a type_id represents a fixed-size primitive (not varint)
  /// Includes bool, int8, int16, float16, float32, float64
  /// Note: INT32/INT64 use varint encoding per basic_serializer.h write/read
  static constexpr bool is_fixed_size_primitive(uint32_t tid) {
    switch (static_cast<TypeId>(tid)) {
    case TypeId::BOOL:
    case TypeId::INT8:
    case TypeId::INT16:
    case TypeId::FLOAT16:
    case TypeId::FLOAT32:
    case TypeId::FLOAT64:
      return true;
    default:
      return false;
    }
  }

  /// Check if a type_id represents a varint primitive (int32/int64 types)
  /// Per basic_serializer.h, INT32/INT64 use zigzag varint encoding
  /// VAR_INT32/VAR_INT64/SLI_INT64 also use varint encoding
  static constexpr bool is_varint_primitive(uint32_t tid) {
    switch (static_cast<TypeId>(tid)) {
    case TypeId::INT32:     // int32_t uses zigzag varint per basic_serializer.h
    case TypeId::INT64:     // int64_t uses zigzag varint per basic_serializer.h
    case TypeId::VAR_INT32: // explicit varint type
    case TypeId::VAR_INT64: // explicit varint type
    case TypeId::SLI_INT64: // alternative int64 encoding
      return true;
    default:
      return false;
    }
  }

  /// Get the max varint size in bytes for a type_id (0 if not varint)
  static constexpr size_t max_varint_bytes(uint32_t tid) {
    switch (static_cast<TypeId>(tid)) {
    case TypeId::INT32:     // int32_t uses zigzag varint
    case TypeId::VAR_INT32: // explicit varint
      return 5;             // int32 varint max
    case TypeId::INT64:     // int64_t uses zigzag varint
    case TypeId::VAR_INT64: // explicit varint
    case TypeId::SLI_INT64:
      return 10; // int64 varint max
    default:
      return 0;
    }
  }

  /// Get the fixed size in bytes for a type_id (0 if not fixed-size)
  /// Note: INT32/INT64 use varint encoding per basic_serializer.h, not fixed
  static constexpr size_t fixed_size_bytes(uint32_t tid) {
    switch (static_cast<TypeId>(tid)) {
    case TypeId::BOOL:
    case TypeId::INT8:
      return 1;
    case TypeId::INT16:
    case TypeId::FLOAT16:
      return 2;
    case TypeId::FLOAT32:
      return 4;
    case TypeId::FLOAT64:
      return 8;
    default:
      return 0;
    }
  }

  /// Compute total bytes for leading fixed-size primitive fields only
  /// (stops at first varint or non-primitive field)
  /// Uses type-based arrays to correctly distinguish signed (varint) vs
  /// unsigned (fixed)
  static constexpr size_t compute_leading_fixed_size_bytes() {
    if constexpr (FieldCount == 0) {
      return 0;
    } else {
      size_t total = 0;
      for (size_t i = 0; i < FieldCount; ++i) {
        size_t original_idx = sorted_indices[i];
        if (nullable_flags[original_idx]) {
          break; // Stop at nullable
        }
        if (!field_is_fixed[original_idx]) {
          break; // Stop at first non-fixed (varint or non-primitive)
        }
        total += field_fixed_sizes[original_idx];
      }
      return total;
    }
  }

  /// Count leading fixed-size primitive fields (stops at first varint or
  /// non-primitive)
  static constexpr size_t compute_leading_fixed_count() {
    if constexpr (FieldCount == 0) {
      return 0;
    } else {
      size_t count = 0;
      for (size_t i = 0; i < FieldCount; ++i) {
        size_t original_idx = sorted_indices[i];
        if (nullable_flags[original_idx]) {
          break;
        }
        if (!field_is_fixed[original_idx]) {
          break; // Varint or non-primitive encountered
        }
        ++count;
      }
      return count;
    }
  }

  static inline constexpr size_t leading_fixed_size_bytes =
      compute_leading_fixed_size_bytes();
  static inline constexpr size_t leading_fixed_count =
      compute_leading_fixed_count();

  /// Count consecutive varint primitives (int32, int64) after leading fixed
  /// fields
  static constexpr size_t compute_varint_count() {
    if constexpr (FieldCount == 0) {
      return 0;
    } else {
      size_t count = 0;
      for (size_t i = leading_fixed_count; i < FieldCount; ++i) {
        size_t original_idx = sorted_indices[i];
        if (nullable_flags[original_idx]) {
          break; // Stop at nullable
        }
        if (!field_is_varint[original_idx]) {
          break; // Stop at non-varint (e.g., float, double, non-primitive)
        }
        ++count;
      }
      return count;
    }
  }

  /// Compute max bytes needed for all varint fields
  static constexpr size_t compute_max_varint_bytes() {
    if constexpr (FieldCount == 0) {
      return 0;
    } else {
      size_t total = 0;
      for (size_t i = leading_fixed_count;
           i < leading_fixed_count + compute_varint_count(); ++i) {
        size_t original_idx = sorted_indices[i];
        total += field_max_varints[original_idx];
      }
      return total;
    }
  }

  static inline constexpr size_t varint_count = compute_varint_count();
  static inline constexpr size_t max_varint_size = compute_max_varint_bytes();

  /// Compute max serialized size for leading primitive fields only.
  /// Used for hybrid fast/slow path buffer pre-reservation.
  static constexpr size_t compute_max_leading_primitive_size() {
    if constexpr (FieldCount == 0 || primitive_field_count == 0) {
      return 0;
    } else {
      size_t total = 0;
      for (size_t i = 0; i < primitive_field_count; ++i) {
        size_t original_idx = sorted_indices[i];
        uint32_t tid = type_ids[original_idx];
        switch (static_cast<TypeId>(tid)) {
        case TypeId::BOOL:
        case TypeId::INT8:
          total += 1;
          break;
        case TypeId::INT16:
        case TypeId::FLOAT16:
          total += 2;
          break;
        case TypeId::INT32:
        case TypeId::VAR_INT32:
          total += 5; // varint max
          break;
        case TypeId::FLOAT32:
          total += 4;
          break;
        case TypeId::INT64:
        case TypeId::VAR_INT64:
        case TypeId::SLI_INT64:
          total += 10; // varint max
          break;
        case TypeId::FLOAT64:
          total += 8;
          break;
        default:
          break;
        }
      }
      return total;
    }
  }

  static inline constexpr size_t max_leading_primitive_size =
      compute_max_leading_primitive_size();
};

/// Compute the write offset of field at sorted index I within leading fixed
/// fields. This is the sum of sizes of all fields before index I.
/// Uses type-based field_fixed_sizes for correct encoding detection.
template <typename T, size_t I>
constexpr size_t compute_fixed_field_write_offset() {
  using Helpers = CompileTimeFieldHelpers<T>;
  size_t offset = 0;
  for (size_t i = 0; i < I; ++i) {
    size_t original_idx = Helpers::sorted_indices[i];
    offset += Helpers::field_fixed_sizes[original_idx];
  }
  return offset;
}

/// Helper to write a single fixed-size primitive field at compile-time offset.
/// No lambda overhead - direct function call that will be inlined.
template <typename T, size_t SortedIdx>
FORY_ALWAYS_INLINE void write_single_fixed_field(const T &obj, Buffer &buffer,
                                                 uint32_t base_offset) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t original_index = Helpers::sorted_indices[SortedIdx];
  constexpr size_t field_offset =
      compute_fixed_field_write_offset<T, SortedIdx>();
  const auto field_info = ForyFieldInfo(obj);
  const auto field_ptr = std::get<original_index>(decltype(field_info)::Ptrs);
  using RawFieldType =
      typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
  using FieldType = unwrap_field_t<RawFieldType>;
  // Get the actual value (unwrap fory::field<> if needed)
  const FieldType &field_value = [&]() -> const FieldType & {
    if constexpr (is_fory_field_v<RawFieldType>) {
      return (obj.*field_ptr).value;
    } else {
      return obj.*field_ptr;
    }
  }();
  put_fixed_primitive_at<FieldType>(field_value, buffer,
                                    base_offset + field_offset);
}

/// Fast write leading fixed-size primitive fields using compile-time offsets.
/// Caller must ensure buffer has sufficient capacity.
/// Optimized: uses compile-time offsets and updates writer_index once at end.
template <typename T, size_t... Indices>
FORY_ALWAYS_INLINE void
write_fixed_primitive_fields(const T &obj, Buffer &buffer,
                             std::index_sequence<Indices...>) {
  using Helpers = CompileTimeFieldHelpers<T>;
  const uint32_t base_offset = buffer.writer_index();

  // Write each field using helper function - no lambda overhead
  (write_single_fixed_field<T, Indices>(obj, buffer, base_offset), ...);

  // Update writer_index once with total fixed bytes (compile-time constant)
  buffer.WriterIndex(base_offset + Helpers::leading_fixed_size_bytes);
}

/// Helper to write a single varint primitive field.
/// No lambda overhead - direct function call that will be inlined.
template <typename T, size_t SortedPos>
FORY_ALWAYS_INLINE void write_single_varint_field(const T &obj, Buffer &buffer,
                                                  uint32_t &offset) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t original_index = Helpers::sorted_indices[SortedPos];
  const auto field_info = ForyFieldInfo(obj);
  const auto field_ptr = std::get<original_index>(decltype(field_info)::Ptrs);
  using RawFieldType =
      typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
  using FieldType = unwrap_field_t<RawFieldType>;
  // Get the actual value (unwrap fory::field<> if needed)
  const FieldType &field_value = [&]() -> const FieldType & {
    if constexpr (is_fory_field_v<RawFieldType>) {
      return (obj.*field_ptr).value;
    } else {
      return obj.*field_ptr;
    }
  }();
  offset += put_varint_at<FieldType>(field_value, buffer, offset);
}

/// Fast write consecutive varint primitive fields (int32, int64).
/// Caller must ensure buffer has sufficient capacity.
/// Optimized: tracks offset locally and updates writer_index once at the end.
template <typename T, size_t FixedCount, size_t... Indices>
FORY_ALWAYS_INLINE void
write_varint_primitive_fields(const T &obj, Buffer &buffer, uint32_t &offset,
                              std::index_sequence<Indices...>) {
  // Write each varint field using helper function - no lambda overhead
  // Indices are 0, 1, 2, ... so actual sorted position is FixedCount + Indices
  (write_single_varint_field<T, FixedCount + Indices>(obj, buffer, offset),
   ...);
}

/// Helper to write a single remaining primitive field.
/// No lambda overhead - direct function call that will be inlined.
template <typename T, size_t SortedPos>
FORY_ALWAYS_INLINE void
write_single_remaining_field(const T &obj, Buffer &buffer, uint32_t &offset) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t original_index = Helpers::sorted_indices[SortedPos];
  const auto field_info = ForyFieldInfo(obj);
  const auto field_ptr = std::get<original_index>(decltype(field_info)::Ptrs);
  using RawFieldType =
      typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
  using FieldType = unwrap_field_t<RawFieldType>;
  // Get the actual value (unwrap fory::field<> if needed)
  const FieldType &field_value = [&]() -> const FieldType & {
    if constexpr (is_fory_field_v<RawFieldType>) {
      return (obj.*field_ptr).value;
    } else {
      return obj.*field_ptr;
    }
  }();
  offset += put_primitive_at<FieldType>(field_value, buffer, offset);
}

/// Write remaining primitive fields after fixed and varint phases.
/// StartPos is the first sorted index to process.
template <typename T, size_t StartPos, size_t... Indices>
FORY_ALWAYS_INLINE void
write_remaining_primitive_fields(const T &obj, Buffer &buffer, uint32_t &offset,
                                 std::index_sequence<Indices...>) {
  // Write each remaining field using helper function - no lambda overhead
  (write_single_remaining_field<T, StartPos + Indices>(obj, buffer, offset),
   ...);
}

/// Fast path writer for primitive-only, non-nullable structs.
/// Writes all fields directly without Result wrapping.
/// Optimized: three-phase approach with single writer_index update at the end.
/// Phase 1: Fixed-size primitives (compile-time offsets)
/// Phase 2: Varint primitives (local offset tracking)
/// Phase 3: Remaining primitives (if any)
template <typename T, size_t... Indices>
FORY_ALWAYS_INLINE void
write_primitive_fields_fast(const T &obj, Buffer &buffer,
                            std::index_sequence<Indices...>) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t fixed_count = Helpers::leading_fixed_count;
  constexpr size_t fixed_bytes = Helpers::leading_fixed_size_bytes;
  constexpr size_t varint_count = Helpers::varint_count;
  constexpr size_t total_count = sizeof...(Indices);

  // Phase 1: Write leading fixed-size primitives if any
  if constexpr (fixed_count > 0 && fixed_bytes > 0) {
    write_fixed_primitive_fields<T>(obj, buffer,
                                    std::make_index_sequence<fixed_count>{});
  }

  // Phase 2: Write consecutive varint primitives if any
  if constexpr (varint_count > 0) {
    uint32_t offset = buffer.writer_index();
    write_varint_primitive_fields<T, fixed_count>(
        obj, buffer, offset, std::make_index_sequence<varint_count>{});
    buffer.WriterIndex(offset);
  }

  // Phase 3: Write remaining primitives (if any) using dedicated helper
  constexpr size_t fast_count = fixed_count + varint_count;
  if constexpr (fast_count < total_count) {
    uint32_t offset = buffer.writer_index();
    write_remaining_primitive_fields<T, fast_count>(
        obj, buffer, offset,
        std::make_index_sequence<total_count - fast_count>{});
    buffer.WriterIndex(offset);
  }
}

template <typename T, size_t Index, typename FieldPtrs>
void write_single_field(const T &obj, WriteContext &ctx,
                        const FieldPtrs &field_ptrs);

template <size_t Index, typename T>
void read_single_field_by_index(T &obj, ReadContext &ctx);

/// Helper to write a single field
template <typename T, size_t Index, typename FieldPtrs>
void write_single_field(const T &obj, WriteContext &ctx,
                        const FieldPtrs &field_ptrs, bool has_generics) {
  using Helpers = CompileTimeFieldHelpers<T>;
  const auto field_ptr = std::get<Index>(field_ptrs);
  using RawFieldType =
      typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
  // Unwrap fory::field<> to get the actual type for serialization
  using FieldType = unwrap_field_t<RawFieldType>;

  // Get the actual value (unwrap fory::field<> if needed)
  const auto &raw_field_ref = obj.*field_ptr;
  const FieldType &field_value = [&]() -> const FieldType & {
    if constexpr (is_fory_field_v<RawFieldType>) {
      return raw_field_ref.value;
    } else {
      return raw_field_ref;
    }
  }();

  constexpr TypeId field_type_id = Serializer<FieldType>::type_id;
  constexpr bool is_primitive_field = is_primitive_type_id(field_type_id);

  // Get field metadata from fory::field<> or FORY_FIELD_TAGS or defaults
  constexpr bool is_nullable = Helpers::template field_nullable<Index>();
  constexpr bool track_ref = Helpers::template field_track_ref<Index>();
  // For backwards compatibility, also check requires_ref_metadata_v
  constexpr bool field_requires_ref = requires_ref_metadata_v<FieldType>;

  // Per Rust implementation: primitives are written directly without ref/type
  if constexpr (is_primitive_field && !field_requires_ref) {
    Serializer<FieldType>::write_data(field_value, ctx);
    return;
  }

  // Per xlang protocol: collections follow the same nullable logic as other
  // fields. RefMode is determined by nullable/track_ref flags.
  // write_type is false for collections (type is known from struct schema).
  // has_generics is true to enable generic element type handling.
  constexpr bool is_collection_field = field_type_id == TypeId::LIST ||
                                       field_type_id == TypeId::SET ||
                                       field_type_id == TypeId::MAP;
  if constexpr (is_collection_field) {
    // Compute RefMode from field metadata
    constexpr RefMode coll_ref_mode =
        make_ref_mode(is_nullable || field_requires_ref, track_ref);
    Serializer<FieldType>::write(field_value, ctx, coll_ref_mode, false, true);
    return;
  }

  // For other types, determine RefMode and write_type per Rust logic
  // RefMode: based on nullable and track_ref flags
  // Per xlang protocol: non-nullable fields skip ref flag entirely
  constexpr RefMode field_ref_mode =
      make_ref_mode(is_nullable || field_requires_ref, track_ref);

  // write_type: determined by field_need_write_type_info logic
  // Enums: false (per Rust util.rs:58-59)
  // Structs/EXT: true ONLY in compatible mode (per C++ read logic)
  // Others: false
  constexpr bool is_struct = is_struct_type(field_type_id);
  constexpr bool is_ext = is_ext_type(field_type_id);
  constexpr bool is_polymorphic = field_type_id == TypeId::UNKNOWN;

  // Check if field is marked as monomorphic (skip dynamic type dispatch)
  constexpr bool is_monomorphic = Helpers::template field_monomorphic<Index>();

  // Per C++ read logic: struct fields need type info only in compatible mode
  // Polymorphic types always need type info, UNLESS marked as monomorphic
  bool write_type = (is_polymorphic && !is_monomorphic) ||
                    ((is_struct || is_ext) && ctx.is_compatible());

  Serializer<FieldType>::write(field_value, ctx, field_ref_mode, write_type);
}

/// Helper to write a single field at compile-time sorted position
template <typename T, size_t SortedPosition>
void write_field_at_sorted_position(const T &obj, WriteContext &ctx,
                                    bool has_generics) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t original_index = Helpers::sorted_indices[SortedPosition];
  const auto field_info = ForyFieldInfo(obj);
  const auto field_ptrs = decltype(field_info)::Ptrs;
  write_single_field<T, original_index>(obj, ctx, field_ptrs, has_generics);
}

/// Helper to write remaining (non-primitive) fields starting from offset.
/// Used in hybrid fast/slow path when some leading fields are primitives.
template <typename T, size_t Offset, size_t... Is>
FORY_ALWAYS_INLINE void write_remaining_fields(const T &obj, WriteContext &ctx,
                                               bool has_generics,
                                               std::index_sequence<Is...>) {
  constexpr size_t remaining = sizeof...(Is);
  constexpr size_t max_bytes_per_field = 10;
  ctx.buffer().Grow(static_cast<uint32_t>(remaining * max_bytes_per_field));

  (write_field_at_sorted_position<T, Offset + Is>(obj, ctx, has_generics), ...);
}

/// Write struct fields recursively using index sequence (sorted order)
/// Optimized with hybrid fast/slow path: primitive fields use direct buffer
/// writes, non-primitive fields use full serialization with error handling.
template <typename T, size_t... Indices>
void write_struct_fields_impl(const T &obj, WriteContext &ctx,
                              std::index_sequence<Indices...>,
                              bool has_generics) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t prim_count = Helpers::primitive_field_count;
  constexpr size_t total_count = sizeof...(Indices);

  if constexpr (prim_count == total_count) {
    // FAST PATH: ALL fields are non-nullable primitives
    // Use direct buffer writes without per-field Grow()
    constexpr size_t max_size = Helpers::max_primitive_serialized_size;
    ctx.buffer().Grow(static_cast<uint32_t>(max_size));
    write_primitive_fields_fast<T>(obj, ctx.buffer(),
                                   std::make_index_sequence<prim_count>{});
  } else if constexpr (prim_count > 0) {
    // HYBRID PATH: Some leading primitives + remaining non-primitives
    // Part 1: Fast-write primitive fields (sorted indices 0 to prim_count-1)
    constexpr size_t max_prim_size = Helpers::max_leading_primitive_size;
    ctx.buffer().Grow(static_cast<uint32_t>(max_prim_size));
    write_primitive_fields_fast<T>(obj, ctx.buffer(),
                                   std::make_index_sequence<prim_count>{});

    // Part 2: Slow-write remaining fields with error checking
    write_remaining_fields<T, prim_count>(
        obj, ctx, has_generics,
        std::make_index_sequence<total_count - prim_count>{});
  } else {
    // SLOW PATH: No leading primitives - all fields need full serialization
    constexpr size_t max_bytes_per_field = 10;
    ctx.buffer().Grow(static_cast<uint32_t>(total_count * max_bytes_per_field));

    (write_field_at_sorted_position<T, Indices>(obj, ctx, has_generics), ...);
  }
}

/// Type trait to check if a type is a raw primitive (not a wrapper like
/// optional, shared_ptr, etc.)
template <typename T> struct is_raw_primitive : std::false_type {};
template <> struct is_raw_primitive<bool> : std::true_type {};
template <> struct is_raw_primitive<int8_t> : std::true_type {};
template <> struct is_raw_primitive<uint8_t> : std::true_type {};
template <> struct is_raw_primitive<int16_t> : std::true_type {};
template <> struct is_raw_primitive<uint16_t> : std::true_type {};
template <> struct is_raw_primitive<int32_t> : std::true_type {};
template <> struct is_raw_primitive<uint32_t> : std::true_type {};
template <> struct is_raw_primitive<int64_t> : std::true_type {};
template <> struct is_raw_primitive<uint64_t> : std::true_type {};
template <> struct is_raw_primitive<float> : std::true_type {};
template <> struct is_raw_primitive<double> : std::true_type {};
template <typename T>
inline constexpr bool is_raw_primitive_v = is_raw_primitive<T>::value;

/// Helper to read a primitive field directly using Error* pattern.
/// This bypasses Serializer<FieldType>::read for better performance.
/// Returns the read value; sets error on failure.
/// NOTE: Only use for raw primitive types, not wrappers!
template <typename FieldType>
FORY_ALWAYS_INLINE FieldType read_primitive_field_direct(ReadContext &ctx,
                                                         Error &error) {
  static_assert(is_raw_primitive_v<FieldType>,
                "read_primitive_field_direct only supports raw primitives");

  // Use the actual C++ type, not TypeId, because signed/unsigned types
  // have different encoding (signed use varint, unsigned use fixed bytes).
  if constexpr (std::is_same_v<FieldType, bool>) {
    uint8_t v = ctx.read_uint8(error);
    return v != 0;
  } else if constexpr (std::is_same_v<FieldType, int8_t>) {
    return ctx.read_int8(error);
  } else if constexpr (std::is_same_v<FieldType, uint8_t>) {
    return ctx.read_uint8(error);
  } else if constexpr (std::is_same_v<FieldType, int16_t>) {
    // int16_t uses fixed 2-byte encoding
    return ctx.read_int16(error);
  } else if constexpr (std::is_same_v<FieldType, uint16_t>) {
    // uint16_t uses fixed 2-byte encoding
    int16_t v = ctx.read_int16(error);
    return static_cast<uint16_t>(v);
  } else if constexpr (std::is_same_v<FieldType, int32_t>) {
    // int32_t uses varint encoding
    return ctx.read_varint32(error);
  } else if constexpr (std::is_same_v<FieldType, uint32_t>) {
    // uint32_t uses fixed 4-byte encoding (not varint!)
    return static_cast<uint32_t>(ctx.read_int32(error));
  } else if constexpr (std::is_same_v<FieldType, int64_t>) {
    // int64_t uses varint encoding
    return ctx.read_varint64(error);
  } else if constexpr (std::is_same_v<FieldType, uint64_t>) {
    // uint64_t uses fixed 8-byte encoding (not varint!)
    return static_cast<uint64_t>(ctx.read_int64(error));
  } else if constexpr (std::is_same_v<FieldType, float>) {
    return ctx.read_float(error);
  } else if constexpr (std::is_same_v<FieldType, double>) {
    return ctx.read_double(error);
  } else {
    // Fallback for other types - should not be reached for primitives
    static_assert(sizeof(FieldType) == 0,
                  "Unexpected type in read_primitive_field_direct");
    return FieldType{};
  }
}

/// Helper to read a single field by index
template <size_t Index, typename T>
void read_single_field_by_index(T &obj, ReadContext &ctx) {
  using Helpers = CompileTimeFieldHelpers<T>;
  const auto field_info = ForyFieldInfo(obj);
  const auto field_ptrs = decltype(field_info)::Ptrs;
  const auto field_ptr = std::get<Index>(field_ptrs);
  using RawFieldType =
      typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
  // Unwrap fory::field<> to get the actual type for deserialization
  using FieldType = unwrap_field_t<RawFieldType>;

  // In non-compatible mode, no type info for fields except for polymorphic
  // types (type_id == UNKNOWN), which always need type info. In compatible
  // mode, nested structs carry TypeMeta in the stream so that
  // `Serializer<T>::read` can dispatch to `read_compatible` with the correct
  // remote schema.
  constexpr bool field_requires_ref = requires_ref_metadata_v<FieldType>;
  constexpr TypeId field_type_id = Serializer<FieldType>::type_id;
  // Check if field is a struct type - use type_id to handle shared_ptr<Struct>
  constexpr bool is_struct_field = is_struct_type(field_type_id);
  constexpr bool is_ext_field = is_ext_type(field_type_id);
  constexpr bool is_polymorphic_field = field_type_id == TypeId::UNKNOWN;

  // Check if field is marked as monomorphic (skip dynamic type dispatch)
  constexpr bool is_monomorphic = Helpers::template field_monomorphic<Index>();

  // Polymorphic types need type info, UNLESS marked as monomorphic
  bool read_type = is_polymorphic_field && !is_monomorphic;

  // Get field metadata from fory::field<> or FORY_FIELD_TAGS or defaults
  constexpr bool is_nullable = Helpers::template field_nullable<Index>();
  constexpr bool track_ref = Helpers::template field_track_ref<Index>();

  // In compatible mode, nested struct fields always carry type metadata
  // (xtypeId + meta index). We must read this metadata so that
  // `Serializer<T>::read` can dispatch to `read_compatible` with the correct
  // remote TypeMeta instead of treating the bytes as part of the first field
  // value.
  if (!is_polymorphic_field && (is_struct_field || is_ext_field) &&
      ctx.is_compatible()) {
    read_type = true;
  }

  // Per xlang spec, all non-primitive fields have ref flags.
  // Primitive types: bool, int8-64, var_int32/64, sli_int64, float16/32/64
  // Non-primitives include: string, list, set, map, struct, enum, etc.
  constexpr bool is_primitive_field = is_primitive_type_id(field_type_id);

  // Compute RefMode based on field metadata
  // RefMode: based on nullable and track_ref flags
  // Per xlang protocol: non-nullable fields skip ref flag entirely
  constexpr RefMode field_ref_mode =
      make_ref_mode(is_nullable || field_requires_ref, track_ref);

#ifdef FORY_DEBUG
  const auto debug_names = decltype(field_info)::Names;
  std::cerr << "[xlang][field] T=" << typeid(T).name() << ", index=" << Index
            << ", name=" << debug_names[Index]
            << ", field_requires_ref=" << field_requires_ref
            << ", is_nullable=" << is_nullable
            << ", ref_mode=" << static_cast<int>(field_ref_mode)
            << ", read_type=" << read_type
            << ", reader_index=" << ctx.buffer().reader_index() << std::endl;
#endif

  // OPTIMIZATION: For raw primitive fields (not wrappers like optional,
  // shared_ptr) that don't need ref metadata, bypass Serializer<T>::read
  // and use direct buffer reads with Error&.
  constexpr bool is_raw_prim = is_raw_primitive_v<FieldType>;
  if constexpr (is_raw_prim && is_primitive_field && !field_requires_ref) {
    // Assign to field (handle fory::field<> wrapper if needed)
    if constexpr (is_fory_field_v<RawFieldType>) {
      (obj.*field_ptr).value =
          read_primitive_field_direct<FieldType>(ctx, ctx.error());
    } else {
      obj.*field_ptr = read_primitive_field_direct<FieldType>(ctx, ctx.error());
    }
  } else {
    // Assign to field (handle fory::field<> wrapper if needed)
    FieldType result =
        Serializer<FieldType>::read(ctx, field_ref_mode, read_type);
    if constexpr (is_fory_field_v<RawFieldType>) {
      (obj.*field_ptr).value = std::move(result);
    } else {
      obj.*field_ptr = std::move(result);
    }
  }
}

/// Helper to read a single field by index in compatible mode using
/// remote field metadata to decide reference flag presence.
template <size_t Index, typename T>
void read_single_field_by_index_compatible(T &obj, ReadContext &ctx,
                                           RefMode remote_ref_mode) {
  using Helpers = CompileTimeFieldHelpers<T>;
  const auto field_info = ForyFieldInfo(obj);
  const auto field_ptrs = decltype(field_info)::Ptrs;
  const auto field_ptr = std::get<Index>(field_ptrs);
  using RawFieldType =
      typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
  // Unwrap fory::field<> to get the actual type for deserialization
  using FieldType = unwrap_field_t<RawFieldType>;

  constexpr TypeId field_type_id = Serializer<FieldType>::type_id;
  // Check if field is a struct type - use type_id to handle shared_ptr<Struct>
  constexpr bool is_struct_field = is_struct_type(field_type_id);
  constexpr bool is_ext_field = is_ext_type(field_type_id);
  constexpr bool is_polymorphic_field = field_type_id == TypeId::UNKNOWN;
  constexpr bool is_primitive_field = is_primitive_type_id(field_type_id);

  // Check if field is marked as monomorphic (skip dynamic type dispatch)
  constexpr bool is_monomorphic = Helpers::template field_monomorphic<Index>();

  // Polymorphic types need type info, UNLESS marked as monomorphic
  bool read_type = is_polymorphic_field && !is_monomorphic;

  // In compatible mode, nested struct fields always carry type metadata
  // (xtypeId + meta index). We must read this metadata so that
  // `Serializer<T>::read` can dispatch to `read_compatible` with the correct
  // remote TypeMeta instead of treating the bytes as part of the first field
  // value.
  if (!is_polymorphic_field && (is_struct_field || is_ext_field) &&
      ctx.is_compatible()) {
    read_type = true;
  }

  // In compatible mode, trust the remote field metadata (remote_ref_mode)
  // to tell us whether a ref/null flag was written before the value payload.

  // OPTIMIZATION: For raw primitive fields (not wrappers) with no ref flag,
  // bypass Serializer<T>::read and use direct buffer reads with Error&.
  constexpr bool is_raw_prim = is_raw_primitive_v<FieldType>;
  if constexpr (is_raw_prim && is_primitive_field) {
    if (remote_ref_mode == RefMode::None) {
      // Assign to field (handle fory::field<> wrapper if needed)
      if constexpr (is_fory_field_v<RawFieldType>) {
        (obj.*field_ptr).value =
            read_primitive_field_direct<FieldType>(ctx, ctx.error());
      } else {
        obj.*field_ptr =
            read_primitive_field_direct<FieldType>(ctx, ctx.error());
      }
      return;
    }
  }

  // Assign to field (handle fory::field<> wrapper if needed)
  FieldType result =
      Serializer<FieldType>::read(ctx, remote_ref_mode, read_type);
  if constexpr (is_fory_field_v<RawFieldType>) {
    (obj.*field_ptr).value = std::move(result);
  } else {
    obj.*field_ptr = std::move(result);
  }
}

/// Helper to dispatch field reading by field_id in compatible mode.
/// Uses fold expression with short-circuit to avoid lambda overhead.
/// Sets handled=true if field was matched.
template <typename T, size_t... Indices>
FORY_ALWAYS_INLINE void
dispatch_compatible_field_read_impl(T &obj, ReadContext &ctx, int16_t field_id,
                                    RefMode remote_ref_mode, bool &handled,
                                    std::index_sequence<Indices...>) {
  using Helpers = CompileTimeFieldHelpers<T>;

  // Short-circuit fold: stops at first match
  // Each element evaluates to bool; || short-circuits on first true
  ((static_cast<int16_t>(Indices) == field_id
        ? (handled = true,
           read_single_field_by_index_compatible<
               Helpers::sorted_indices[Indices]>(obj, ctx, remote_ref_mode),
           true)
        : false) ||
   ...);
}

/// Helper to read a single field at compile-time sorted position
template <typename T, size_t SortedPosition>
void read_field_at_sorted_position(T &obj, ReadContext &ctx) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t original_index = Helpers::sorted_indices[SortedPosition];
  read_single_field_by_index<original_index>(obj, ctx);
}

/// Get the fixed size of a primitive type at compile time
template <typename T> constexpr size_t fixed_primitive_size() {
  if constexpr (std::is_same_v<T, bool> || std::is_same_v<T, int8_t> ||
                std::is_same_v<T, uint8_t>) {
    return 1;
  } else if constexpr (std::is_same_v<T, int16_t> ||
                       std::is_same_v<T, uint16_t>) {
    return 2;
  } else if constexpr (std::is_same_v<T, uint32_t> ||
                       std::is_same_v<T, float>) {
    return 4;
  } else if constexpr (std::is_same_v<T, uint64_t> ||
                       std::is_same_v<T, double>) {
    return 8;
  } else {
    return 0; // Not a fixed-size primitive
  }
}

/// Compute the offset of field at sorted index I within the leading fixed
/// fields This is the sum of sizes of all fields before index I
/// Uses type-based field_fixed_sizes for correct encoding detection
template <typename T, size_t I> constexpr size_t compute_fixed_field_offset() {
  using Helpers = CompileTimeFieldHelpers<T>;
  size_t offset = 0;
  for (size_t i = 0; i < I; ++i) {
    size_t original_idx = Helpers::sorted_indices[i];
    offset += Helpers::field_fixed_sizes[original_idx];
  }
  return offset;
}

/// Read a fixed-size primitive value at a given absolute offset using
/// UnsafeGet. Does NOT update any offset - purely reads at the specified
/// position. Caller must ensure buffer bounds are pre-checked.
template <typename T>
FORY_ALWAYS_INLINE T read_fixed_primitive_at(Buffer &buffer, uint32_t offset) {
  if constexpr (std::is_same_v<T, bool>) {
    return buffer.UnsafeGet<uint8_t>(offset) != 0;
  } else if constexpr (std::is_same_v<T, int8_t>) {
    return static_cast<int8_t>(buffer.UnsafeGet<uint8_t>(offset));
  } else if constexpr (std::is_same_v<T, uint8_t>) {
    return buffer.UnsafeGet<uint8_t>(offset);
  } else if constexpr (std::is_same_v<T, int16_t>) {
    return buffer.UnsafeGet<int16_t>(offset);
  } else if constexpr (std::is_same_v<T, uint16_t>) {
    return buffer.UnsafeGet<uint16_t>(offset);
  } else if constexpr (std::is_same_v<T, int32_t> || std::is_same_v<T, int>) {
    // Handle both int32_t and int (different types on some platforms)
    return static_cast<T>(buffer.UnsafeGet<int32_t>(offset));
  } else if constexpr (std::is_same_v<T, uint32_t> ||
                       std::is_same_v<T, unsigned int>) {
    // Handle both uint32_t and unsigned int (different types on some platforms)
    return static_cast<T>(buffer.UnsafeGet<uint32_t>(offset));
  } else if constexpr (std::is_same_v<T, float>) {
    return buffer.UnsafeGet<float>(offset);
  } else if constexpr (std::is_same_v<T, uint64_t> ||
                       std::is_same_v<T, unsigned long long>) {
    // Handle both uint64_t and unsigned long long (different types on some
    // platforms)
    return static_cast<T>(buffer.UnsafeGet<uint64_t>(offset));
  } else if constexpr (std::is_same_v<T, int64_t> ||
                       std::is_same_v<T, long long>) {
    // Handle both int64_t and long long (different types on some platforms)
    // Note: int64_t/long long uses varint, but if classified as fixed by
    // TypeId, we read as fixed 8 bytes
    return static_cast<T>(buffer.UnsafeGet<int64_t>(offset));
  } else if constexpr (std::is_same_v<T, double>) {
    return buffer.UnsafeGet<double>(offset);
  } else {
    static_assert(sizeof(T) == 0, "Unsupported fixed-size primitive type");
    return T{};
  }
}

/// Helper to read a single fixed-size primitive field at compile-time offset.
/// No lambda overhead - direct function call that will be inlined.
template <typename T, size_t SortedIdx>
FORY_ALWAYS_INLINE void read_single_fixed_field(T &obj, Buffer &buffer,
                                                uint32_t base_offset) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t original_index = Helpers::sorted_indices[SortedIdx];
  constexpr size_t field_offset = compute_fixed_field_offset<T, SortedIdx>();
  const auto field_info = ForyFieldInfo(obj);
  const auto field_ptr = std::get<original_index>(decltype(field_info)::Ptrs);
  using RawFieldType =
      typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
  using FieldType = unwrap_field_t<RawFieldType>;
  FieldType result =
      read_fixed_primitive_at<FieldType>(buffer, base_offset + field_offset);
  // Assign to field (handle fory::field<> wrapper if needed)
  if constexpr (is_fory_field_v<RawFieldType>) {
    (obj.*field_ptr).value = result;
  } else {
    obj.*field_ptr = result;
  }
}

/// Fast read leading fixed-size primitive fields using UnsafeGet.
/// Caller must ensure buffer bounds are pre-checked.
/// Optimized: uses compile-time offsets and updates reader_index once at end.
template <typename T, size_t... Indices>
FORY_ALWAYS_INLINE void
read_fixed_primitive_fields(T &obj, Buffer &buffer,
                            std::index_sequence<Indices...>) {
  using Helpers = CompileTimeFieldHelpers<T>;
  const uint32_t base_offset = buffer.reader_index();

  // Read each field using helper function - no lambda overhead
  (read_single_fixed_field<T, Indices>(obj, buffer, base_offset), ...);

  // Update reader_index once with total fixed bytes (compile-time constant)
  buffer.ReaderIndex(base_offset + Helpers::leading_fixed_size_bytes);
}

/// Read a single varint field at a given offset.
/// Does NOT update reader_index - caller must track offset and update once.
/// Caller must ensure buffer has enough bytes (pre-checked).
template <typename T>
FORY_ALWAYS_INLINE T read_varint_at(Buffer &buffer, uint32_t &offset) {
  uint32_t bytes_read;
  if constexpr (std::is_same_v<T, int32_t> || std::is_same_v<T, int>) {
    // Handle both int32_t and int (different types on some platforms)
    uint32_t raw = buffer.GetVarUint32(offset, &bytes_read);
    offset += bytes_read;
    // Zigzag decode
    return static_cast<T>((raw >> 1) ^ (~(raw & 1) + 1));
  } else if constexpr (std::is_same_v<T, int64_t> ||
                       std::is_same_v<T, long long>) {
    // Handle both int64_t and long long (different types on some platforms)
    uint64_t raw = buffer.GetVarUint64(offset, &bytes_read);
    offset += bytes_read;
    // Zigzag decode
    return static_cast<T>((raw >> 1) ^ (~(raw & 1) + 1));
  } else {
    static_assert(sizeof(T) == 0, "Unsupported varint type");
    return T{};
  }
}

/// Helper to read a single varint primitive field.
/// No lambda overhead - direct function call that will be inlined.
template <typename T, size_t SortedPos>
FORY_ALWAYS_INLINE void read_single_varint_field(T &obj, Buffer &buffer,
                                                 uint32_t &offset) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t original_index = Helpers::sorted_indices[SortedPos];
  const auto field_info = ForyFieldInfo(obj);
  const auto field_ptr = std::get<original_index>(decltype(field_info)::Ptrs);
  using RawFieldType =
      typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
  using FieldType = unwrap_field_t<RawFieldType>;
  FieldType result = read_varint_at<FieldType>(buffer, offset);
  // Assign to field (handle fory::field<> wrapper if needed)
  if constexpr (is_fory_field_v<RawFieldType>) {
    (obj.*field_ptr).value = result;
  } else {
    obj.*field_ptr = result;
  }
}

/// Fast read consecutive varint primitive fields (int32, int64).
/// Caller must ensure buffer bounds are pre-checked for max varint bytes.
/// Optimized: tracks offset locally and updates reader_index once at the end.
/// StartIdx is the sorted index to start reading from.
template <typename T, size_t StartIdx, size_t... Is>
FORY_ALWAYS_INLINE void
read_varint_primitive_fields(T &obj, Buffer &buffer, uint32_t &offset,
                             std::index_sequence<Is...>) {
  // Read each varint field using helper function - no lambda overhead
  // Is are 0, 1, 2, ... so actual sorted position is StartIdx + Is
  (read_single_varint_field<T, StartIdx + Is>(obj, buffer, offset), ...);
}

/// Helper to read remaining fields starting from Offset
template <typename T, size_t Offset, size_t Total, size_t... Is>
void read_remaining_fields_impl(T &obj, ReadContext &ctx,
                                std::index_sequence<Is...>) {
  (read_field_at_sorted_position<T, Offset + Is>(obj, ctx), ...);
}

template <typename T, size_t Offset, size_t Total>
void read_remaining_fields(T &obj, ReadContext &ctx) {
  read_remaining_fields_impl<T, Offset, Total>(
      obj, ctx, std::make_index_sequence<Total - Offset>{});
}

/// Read struct fields recursively using index sequence (sorted order - matches
/// write order)
/// Optimized: when compatible=false, use fast paths for:
/// 1. Leading fixed-size primitives (bool, int8, int16, float, double)
/// 2. Consecutive varint primitives (int32, int64) after fixed fields
/// Both paths pre-check bounds and update reader_index once at the end.
template <typename T, size_t... Indices>
void read_struct_fields_impl(T &obj, ReadContext &ctx,
                             std::index_sequence<Indices...>) {
  using Helpers = CompileTimeFieldHelpers<T>;
  constexpr size_t fixed_count = Helpers::leading_fixed_count;
  constexpr size_t fixed_bytes = Helpers::leading_fixed_size_bytes;
  constexpr size_t varint_count = Helpers::varint_count;
  constexpr size_t total_count = sizeof...(Indices);

  // FAST PATH: When compatible=false, use optimized batch reading
  if (!ctx.is_compatible()) {
    Buffer &buffer = ctx.buffer();

    // Phase 1: Read leading fixed-size primitives if any
    if constexpr (fixed_count > 0 && fixed_bytes > 0) {
      // Pre-check bounds for all fixed-size fields at once
      if (FORY_PREDICT_FALSE(buffer.reader_index() + fixed_bytes >
                             buffer.size())) {
        ctx.set_error(Error::buffer_out_of_bound(buffer.reader_index(),
                                                 fixed_bytes, buffer.size()));
        return;
      }
      // Fast read fixed-size primitives
      read_fixed_primitive_fields<T>(obj, buffer,
                                     std::make_index_sequence<fixed_count>{});
    }

    // Phase 2: Read consecutive varint primitives (int32, int64) if any
    // Note: varint bounds checking is done per-byte during reading since
    // varint lengths are variable (actual size << max possible size)
    if constexpr (varint_count > 0) {
      // Track offset locally for batch varint reading
      uint32_t offset = buffer.reader_index();
      // Fast read varint primitives (bounds checking happens in
      // GetVarUint32/64)
      read_varint_primitive_fields<T, fixed_count>(
          obj, buffer, offset, std::make_index_sequence<varint_count>{});
      // Update reader_index once after all varints
      buffer.ReaderIndex(offset);
    }

    // Phase 3: Read remaining fields (if any) with normal path
    constexpr size_t fast_count = fixed_count + varint_count;
    if constexpr (fast_count < total_count) {
      read_remaining_fields<T, fast_count, total_count>(obj, ctx);
    }
    return;
  }

  // NORMAL PATH: compatible mode - all fields need full serialization
  (read_field_at_sorted_position<T, Indices>(obj, ctx), ...);
}

/// Read struct fields with schema evolution (compatible mode)
/// Reads fields in remote schema order, dispatching by field_id to local fields
template <typename T, size_t... Indices>
void read_struct_fields_compatible(T &obj, ReadContext &ctx,
                                   const TypeMeta *remote_type_meta,
                                   std::index_sequence<Indices...>) {
  const auto &remote_fields = remote_type_meta->get_field_infos();

  // Iterate through remote fields in their serialization order
  for (size_t remote_idx = 0; remote_idx < remote_fields.size(); ++remote_idx) {
    const auto &remote_field = remote_fields[remote_idx];
    int16_t field_id = remote_field.field_id;

    // Use the precomputed ref_mode from remote field metadata.
    // This is computed from nullable and ref_tracking flags in the remote
    // field's header during FieldInfo::from_bytes.
    RefMode remote_ref_mode = remote_field.field_type.ref_mode;

    if (field_id == -1) {
      // Field unknown locally — skip its value
      skip_field_value(ctx, remote_field.field_type, remote_ref_mode);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return;
      }
      continue;
    }

    // Dispatch to the correct local field by field_id
    // Uses fold expression with short-circuit - no lambda overhead
    bool handled = false;
    dispatch_compatible_field_read_impl<T>(obj, ctx, field_id, remote_ref_mode,
                                           handled,
                                           std::index_sequence<Indices...>{});

    if (!handled) {
      // Shouldn't happen if TypeMeta::assign_field_ids worked correctly
      skip_field_value(ctx, remote_field.field_type, remote_ref_mode);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return;
      }
      continue;
    }

    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
  }
}

} // namespace detail

/// Serializer for types registered with FORY_STRUCT
template <typename T>
struct Serializer<T, std::enable_if_t<is_fory_serializable_v<T>>> {
  static constexpr TypeId type_id = TypeId::STRUCT;

  /// Write type info only (type_id and meta index if applicable).
  /// This is used by collection serializers to write element type info.
  /// Matches Rust's struct_::write_type_info.
  static void write_type_info(WriteContext &ctx) {
    auto type_info_res = ctx.type_resolver().template get_type_info<T>();
    if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
      ctx.set_error(std::move(type_info_res).error());
      return;
    }
    const TypeInfo *type_info = type_info_res.value();
    ctx.write_varuint32(type_info->type_id);

    // In compatible mode, always write meta index (matches Rust behavior)
    if (ctx.is_compatible() && type_info->type_meta) {
      // Use TypeInfo* overload to avoid type_index creation
      size_t meta_index = ctx.push_meta(type_info);
      ctx.write_varuint32(static_cast<uint32_t>(meta_index));
    }
  }

  /// Read and validate type info.
  /// This consumes the type_id and meta index from the buffer.
  static void read_type_info(ReadContext &ctx) {
    const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      ctx.set_error(Error::type_mismatch(type_info->type_id,
                                         static_cast<uint32_t>(type_id)));
    }
  }

  static void write(const T &obj, WriteContext &ctx, RefMode ref_mode,
                    bool write_type, bool has_generics = false) {
    // Handle ref flag based on mode
    if (ref_mode == RefMode::Tracking && ctx.track_ref()) {
      // In Tracking mode, write REF_VALUE_FLAG (0) and reserve a ref_id slot
      // to keep ref IDs in sync with Java (which tracks all objects)
      ctx.write_int8(REF_VALUE_FLAG);
      ctx.ref_writer().reserve_ref_id();
    } else if (ref_mode != RefMode::None) {
      ctx.write_int8(NOT_NULL_VALUE_FLAG);
    }

    if (write_type) {
      // Direct lookup using compile-time type_index<T>() - O(1) hash lookup
      auto type_info_res = ctx.type_resolver().template get_type_info<T>();
      if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      const TypeInfo *type_info = type_info_res.value();
      uint32_t tid = type_info->type_id;

      // Fast path: check if this is a simple STRUCT type (no meta needed)
      uint32_t type_id_low = tid & 0xff;
      if (type_id_low == static_cast<uint32_t>(TypeId::STRUCT)) {
        // Simple STRUCT - just write the type_id directly
        ctx.write_struct_type_id_direct(tid);
      } else {
        // Complex type (NAMED_STRUCT, COMPATIBLE_STRUCT, etc.) - use TypeInfo*
        ctx.write_struct_type_info(type_info);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return;
        }
      }
    }
    write_data_generic(obj, ctx, has_generics);
  }

  static void write_data(const T &obj, WriteContext &ctx) {
    if (ctx.check_struct_version()) {
      auto type_info_res = ctx.type_resolver().template get_type_info<T>();
      if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      const TypeInfo *type_info = type_info_res.value();
      if (!type_info->type_meta) {
        ctx.set_error(
            Error::type_error("Type metadata not initialized for struct"));
        return;
      }
      int32_t local_version =
          TypeMeta::compute_struct_version(*type_info->type_meta);
      ctx.buffer().WriteInt32(local_version);
    }

    using FieldDescriptor = decltype(ForyFieldInfo(std::declval<const T &>()));
    constexpr size_t field_count = FieldDescriptor::Size;
    detail::write_struct_fields_impl(
        obj, ctx, std::make_index_sequence<field_count>{}, false);
  }

  static void write_data_generic(const T &obj, WriteContext &ctx,
                                 bool has_generics) {
    if (ctx.check_struct_version()) {
      auto type_info_res = ctx.type_resolver().template get_type_info<T>();
      if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      const TypeInfo *type_info = type_info_res.value();
      if (!type_info->type_meta) {
        ctx.set_error(
            Error::type_error("Type metadata not initialized for struct"));
        return;
      }
      int32_t local_version =
          TypeMeta::compute_struct_version(*type_info->type_meta);
      ctx.buffer().WriteInt32(local_version);
    }

    using FieldDescriptor = decltype(ForyFieldInfo(std::declval<const T &>()));
    constexpr size_t field_count = FieldDescriptor::Size;
    detail::write_struct_fields_impl(
        obj, ctx, std::make_index_sequence<field_count>{}, has_generics);
  }

  static T read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    // Handle reference metadata
    int8_t ref_flag;
    if (ref_mode != RefMode::None) {
      ref_flag = ctx.read_int8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return T{};
      }
#ifdef FORY_DEBUG
      std::cerr << "[xlang][struct] T=" << typeid(T).name()
                << ", read_ref_flag=" << static_cast<int>(ref_flag)
                << ", reader_index=" << ctx.buffer().reader_index()
                << std::endl;
#endif
    } else {
      ref_flag = static_cast<int8_t>(RefFlag::NotNullValue);
    }

    constexpr int8_t not_null_value_flag =
        static_cast<int8_t>(RefFlag::NotNullValue);
    constexpr int8_t ref_value_flag = static_cast<int8_t>(RefFlag::RefValue);
    constexpr int8_t null_flag = static_cast<int8_t>(RefFlag::Null);

    if (ref_flag == not_null_value_flag || ref_flag == ref_value_flag) {
      // When ref_flag is RefValue (0), Java assigned a ref_id to this object.
      // We must reserve a matching ref_id slot so that nested refs line up.
      // Structs can't actually be referenced (only shared_ptrs can), but we
      // need the ref_id numbering to stay in sync with Java.
      if (ctx.track_ref() && ref_flag == ref_value_flag) {
        ctx.ref_reader().reserve_ref_id();
      }
      // In compatible mode: use meta sharing (matches Rust behavior)
      if (ctx.is_compatible()) {
        // In compatible mode: always use remote TypeMeta for schema evolution
        if (read_type) {
          // Read type_id
          uint32_t remote_type_id = ctx.read_varuint32(ctx.error());
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            return T{};
          }

          // Check LOCAL type to decide if we should read meta_index (matches
          // Rust logic)
          auto local_type_info_res =
              ctx.type_resolver().template get_type_info<T>();
          if (!local_type_info_res.ok()) {
            ctx.set_error(std::move(local_type_info_res).error());
            return T{};
          }
          const TypeInfo *local_type_info = local_type_info_res.value();
          uint32_t local_type_id = local_type_info->type_id;
          uint8_t local_type_id_low = local_type_id & 0xff;

          if (local_type_id_low ==
                  static_cast<uint8_t>(TypeId::COMPATIBLE_STRUCT) ||
              local_type_id_low ==
                  static_cast<uint8_t>(TypeId::NAMED_COMPATIBLE_STRUCT)) {
            // Use meta sharing: read varint index and get TypeInfo from
            // meta_reader
            uint32_t meta_index = ctx.read_varuint32(ctx.error());
            if (FORY_PREDICT_FALSE(ctx.has_error())) {
              return T{};
            }
            auto remote_type_info_res = ctx.get_type_info_by_index(meta_index);
            if (!remote_type_info_res.ok()) {
              ctx.set_error(std::move(remote_type_info_res).error());
              return T{};
            }

            return read_compatible(ctx, remote_type_info_res.value());
          } else {
            // Local type is not compatible struct - verify type match and read
            // data
            if (remote_type_id != local_type_id) {
              ctx.set_error(
                  Error::type_mismatch(remote_type_id, local_type_id));
              return T{};
            }
            return read_data(ctx);
          }
        } else {
          // read_type=false in compatible mode: same version, use sorted order
          // (fast path)
          return read_data(ctx);
        }
      } else {
        // Non-compatible mode: read type info if requested, then read data.
        //
        // For xlang, we delegate type-info parsing to ReadContext so that
        // named structs/ext/enums consume their namespace/type-name
        // metadata exactly as Java/Rust do. This keeps the reader
        // position aligned with the subsequent class-version hash and
        // payload, and also validates that the concrete type id matches
        // the expected static type.
        if (read_type) {
          // Direct lookup using compile-time type_index<T>() - O(1) hash lookup
          auto type_info_res = ctx.type_resolver().template get_type_info<T>();
          if (!type_info_res.ok()) {
            ctx.set_error(std::move(type_info_res).error());
            return T{};
          }
          const TypeInfo *type_info = type_info_res.value();
          uint32_t expected_type_id = type_info->type_id;

          // FAST PATH: For simple numeric type IDs (not named types), we can
          // just read the varint and compare directly without hash lookup.
          // Named types have type_id_low in ranges that require metadata
          // parsing.
          uint8_t expected_type_id_low = expected_type_id & 0xff;
          if (expected_type_id_low !=
                  static_cast<uint8_t>(TypeId::NAMED_ENUM) &&
              expected_type_id_low != static_cast<uint8_t>(TypeId::NAMED_EXT) &&
              expected_type_id_low !=
                  static_cast<uint8_t>(TypeId::NAMED_STRUCT)) {
            // Simple type ID - just read and compare varint directly
            uint32_t remote_type_id = ctx.read_varuint32(ctx.error());
            if (FORY_PREDICT_FALSE(ctx.has_error())) {
              return T{};
            }
            if (remote_type_id != expected_type_id) {
              ctx.set_error(
                  Error::type_mismatch(remote_type_id, expected_type_id));
              return T{};
            }
          } else {
            // Named type - need to parse full type info
            const TypeInfo *remote_info = ctx.read_any_typeinfo(ctx.error());
            if (FORY_PREDICT_FALSE(ctx.has_error())) {
              return T{};
            }
            uint32_t remote_type_id = remote_info ? remote_info->type_id : 0u;
            if (remote_type_id != expected_type_id) {
              ctx.set_error(
                  Error::type_mismatch(remote_type_id, expected_type_id));
              return T{};
            }
          }
        }
        return read_data(ctx);
      }
    } else if (ref_flag == null_flag) {
      // Null value
      if constexpr (std::is_default_constructible_v<T>) {
        return T{};
      } else {
        ctx.set_error(Error::invalid_data(
            "Null value encountered for non-default-constructible struct"));
        return T{};
      }
    } else {
      ctx.set_error(Error::invalid_ref("Unknown ref flag, value: " +
                                       std::to_string(ref_flag)));
      return T{};
    }
  }

  static T read_compatible(ReadContext &ctx, const TypeInfo *remote_type_info) {
    // Read and verify struct version if enabled (matches write_data behavior)
    if (ctx.check_struct_version()) {
      int32_t read_version = ctx.buffer().ReadInt32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return T{};
      }
      auto local_type_info_res =
          ctx.type_resolver().template get_type_info<T>();
      if (!local_type_info_res.ok()) {
        ctx.set_error(std::move(local_type_info_res).error());
        return T{};
      }
      const TypeInfo *local_type_info = local_type_info_res.value();
      if (!local_type_info->type_meta) {
        ctx.set_error(Error::type_error(
            "Type metadata not initialized for requested struct"));
        return T{};
      }
      int32_t local_version =
          TypeMeta::compute_struct_version(*local_type_info->type_meta);
      auto version_res = TypeMeta::check_struct_version(
          read_version, local_version, local_type_info->type_name);
      if (!version_res.ok()) {
        ctx.set_error(std::move(version_res).error());
        return T{};
      }
    }

    T obj{};
    using FieldDescriptor = decltype(ForyFieldInfo(std::declval<const T &>()));
    constexpr size_t field_count = FieldDescriptor::Size;

    // remote_type_info is from the stream, with field_ids already assigned
    if (!remote_type_info || !remote_type_info->type_meta) {
      ctx.set_error(Error::type_error("Remote type metadata not available"));
      return T{};
    }

    // Use remote TypeMeta for schema evolution - field IDs already assigned
    detail::read_struct_fields_compatible(
        obj, ctx, remote_type_info->type_meta.get(),
        std::make_index_sequence<field_count>{});
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return T{};
    }

    return obj;
  }

  static T read_data(ReadContext &ctx) {
    if (ctx.check_struct_version()) {
      int32_t read_version = ctx.buffer().ReadInt32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return T{};
      }
      auto local_type_info_res =
          ctx.type_resolver().template get_type_info<T>();
      if (!local_type_info_res.ok()) {
        ctx.set_error(std::move(local_type_info_res).error());
        return T{};
      }
      const TypeInfo *local_type_info = local_type_info_res.value();
      if (!local_type_info->type_meta) {
        ctx.set_error(Error::type_error(
            "Type metadata not initialized for requested struct"));
        return T{};
      }
      int32_t local_version =
          TypeMeta::compute_struct_version(*local_type_info->type_meta);
      auto version_res = TypeMeta::check_struct_version(
          read_version, local_version, local_type_info->type_name);
      if (!version_res.ok()) {
        ctx.set_error(std::move(version_res).error());
        return T{};
      }
    }

    T obj{};
    using FieldDescriptor = decltype(ForyFieldInfo(std::declval<const T &>()));
    constexpr size_t field_count = FieldDescriptor::Size;
    detail::read_struct_fields_impl(obj, ctx,
                                    std::make_index_sequence<field_count>{});
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return T{};
    }

    return obj;
  }

  // Optimized read when type info already known (for polymorphic collections)
  // This method is critical for the optimization described in xlang spec
  // section 5.4.4 When deserializing List<Base> where all elements are same
  // concrete type, we read type info once and pass it to all element
  // deserializers
  static T read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                               const TypeInfo &type_info) {
    // Note: When called from polymorphic shared_ptr, the shared_ptr has already
    // consumed the ref flag, so we should not read it again here. The read_ref
    // parameter is just for protocol compatibility but should not cause us to
    // read another ref flag.

    // In compatible mode with type info provided, use schema evolution path
    if (ctx.is_compatible() && type_info.type_meta) {
      return read_compatible(ctx, &type_info);
    }

    // Otherwise use normal read path
    return read_data(ctx);
  }
};

} // namespace serialization
} // namespace fory
