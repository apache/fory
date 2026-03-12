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

#include <algorithm>
#include <any>
#include <array>
#include <cstdint>
#include <deque>
#include <functional>
#include <list>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <thread>
#include <tuple>
#include <type_traits>
#include <typeindex>
#include <typeinfo>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "absl/container/flat_hash_map.h"

#include "fory/meta/field.h"
#include "fory/meta/field_info.h"
#include "fory/meta/type_traits.h"
#include "fory/serialization/config.h"
#include "fory/serialization/serializer.h"
#include "fory/serialization/serializer_traits.h"
#include "fory/serialization/type_info.h"
#include "fory/type/type.h"
#include "fory/util/buffer.h"
#include "fory/util/error.h"
#include "fory/util/flat_int_map.h"
#include "fory/util/logging.h"
#include "fory/util/result.h"
#include "fory/util/string_util.h"

namespace fory {
namespace serialization {

using meta::fory_field_info;

// Forward declarations
class Fory;
class TypeResolver;
class WriteContext;
class ReadContext;

template <typename T>
Result<const TypeInfo *, Error>
get_type_info_with_resolver(TypeResolver &resolver);

// ============================================================================
// TypeIndex primary template (fallback)
// Specializations for primitives and containers are in serializer_traits.h
// ============================================================================

/// Helper function to get type-specific hash using PRETTY_FUNCTION.
/// Must be a function because __PRETTY_FUNCTION__ is only valid inside
/// functions.
template <typename T> constexpr uint64_t type_fallback_hash() {
  return fnv1a_64(FORY_PRETTY_FUNCTION);
}

/// Primary template - defaults to using PRETTY_FUNCTION fallback
/// Specialized for: primitives, containers, FORY_STRUCT, FORY_ENUM
/// Note: No default argument here since it's already declared in type_index.h
template <typename T, typename Enable> struct TypeIndex {
  // Fallback: combine Serializer<T>::type_id with type-specific hash
  // This handles any unspecialized types (enums, structs, etc.)
  static constexpr uint64_t value = fnv1a_64_combine(
      static_cast<uint64_t>(Serializer<T>::type_id), type_fallback_hash<T>());
};

// ============================================================================
// FieldType - Represents type information for a field
// ============================================================================

/// Represents type information including type ID, nullability, ref tracking,
/// ref_mode (precomputed), and generics
class FieldType {
public:
  uint32_t type_id;
  // Stored as unsigned; 0xffffffff means "unset".
  uint32_t user_type_id;
  bool nullable;
  bool track_ref;
  RefMode ref_mode; // Precomputed from nullable and track_ref
  std::vector<FieldType> generics;

  FieldType()
      : type_id(0), user_type_id(kInvalidUserTypeId), nullable(false),
        track_ref(false), ref_mode(RefMode::None) {}

  FieldType(uint32_t tid, bool null, bool ref_track = false,
            std::vector<FieldType> gens = {},
            uint32_t user_tid = kInvalidUserTypeId)
      : type_id(tid), user_type_id(user_tid), nullable(null),
        track_ref(ref_track), ref_mode(make_ref_mode(null, ref_track)),
        generics(std::move(gens)) {}

  /// write field type to buffer
  /// @param buffer Target buffer
  /// @param write_flag Whether to write nullability flag (for nested types)
  /// @param nullable_val Nullability to write if write_flag is true
  Result<void, Error> write_to(Buffer &buffer, bool write_flag,
                               bool nullable_val) const;

  /// Read field type from buffer
  /// @param buffer Source buffer
  /// @param read_flag Whether to read nullability flag (for nested types)
  /// @param nullable_val Nullability if read_flag is false
  /// @param ref_tracking_val Ref tracking if read_flag is false
  static Result<FieldType, Error> read_from(Buffer &buffer, bool read_flag,
                                            bool nullable_val,
                                            bool ref_tracking_val = false);

  bool operator==(const FieldType &other) const {
    return type_id == other.type_id && nullable == other.nullable &&
           user_type_id == other.user_type_id && track_ref == other.track_ref &&
           generics == other.generics;
  }

  bool operator!=(const FieldType &other) const { return !(*this == other); }
};

// ============================================================================
// FieldInfo - Field metadata (name, type, id)
// ============================================================================

/// Field information including name, type, and assigned field ID
class FieldInfo {
public:
  int16_t field_id;       // Tag ID if configured; -1 means no ID
  std::string field_name; // Field name
  FieldType field_type;   // Field type information

  FieldInfo() : field_id(-1) {}

  FieldInfo(std::string name, FieldType type)
      : field_id(-1), field_name(std::move(name)), field_type(std::move(type)) {
  }

  /// write field info to buffer (for serialization)
  Result<std::vector<uint8_t>, Error> to_bytes() const;

  /// Read field info from buffer (for deserialization)
  static Result<FieldInfo, Error> from_bytes(Buffer &buffer);

  bool operator==(const FieldInfo &other) const {
    return field_name == other.field_name && field_type == other.field_type;
  }
};

// ============================================================================
// TypeMeta - Complete type metadata (for schema evolution)
// ============================================================================

constexpr size_t MAX_PARSED_NUM_TYPE_DEFS = 8192;

/// Type metadata containing all field information
/// Used for schema evolution to compare remote and local type schemas
class TypeMeta {
public:
  int64_t hash;     // Type hash for fast comparison
  uint32_t type_id; // Type ID (for non-named registration)
  // Stored as unsigned; 0xffffffff means "unset".
  uint32_t user_type_id;     // User type ID (for non-named registration)
  std::string namespace_str; // Namespace (for named registration)
  std::string type_name;     // Type name (for named registration)
  bool register_by_name;     // Whether registered by name
  std::vector<FieldInfo> field_infos; // Field information

  TypeMeta()
      : hash(0), type_id(0), user_type_id(kInvalidUserTypeId),
        register_by_name(false) {}

  /// Create TypeMeta from field information
  static TypeMeta from_fields(uint32_t tid, const std::string &ns,
                              const std::string &name, bool by_name,
                              uint32_t user_type_id,
                              std::vector<FieldInfo> fields);

  /// write type meta to buffer (for serialization)
  Result<std::vector<uint8_t>, Error> to_bytes() const;

  /// Read type meta from buffer (for deserialization)
  /// @param buffer Source buffer
  /// @param local_type_info Local type information (for field ID assignment)
  static Result<std::unique_ptr<TypeMeta>, Error>
  from_bytes(Buffer &buffer, const TypeMeta *local_type_info);

  /// Read type meta from buffer with pre-read header
  /// @param buffer Source buffer (positioned after header)
  /// @param header Pre-read 8-byte header
  static Result<std::unique_ptr<TypeMeta>, Error>
  from_bytes_with_header(Buffer &buffer, int64_t header);

  /// skip type meta in buffer without parsing
  static Result<void, Error> skip_bytes(Buffer &buffer, int64_t header);

  /// Check struct version consistency
  static Result<void, Error> check_struct_version(int32_t read_version,
                                                  int32_t local_version,
                                                  const std::string &type_name);

  /// get sorted field infos (sorted according to xlang spec)
  static std::vector<FieldInfo> sort_field_infos(std::vector<FieldInfo> fields);

  /// Assign field IDs by comparing with local type
  /// This is the key function for schema evolution!
  static void assign_field_ids(const TypeMeta *local_type,
                               std::vector<FieldInfo> &remote_fields);

  const std::vector<FieldInfo> &get_field_infos() const { return field_infos; }
  int64_t get_hash() const { return hash; }
  uint32_t get_type_id() const { return type_id; }
  const std::string &get_type_name() const { return type_name; }
  const std::string &get_namespace() const { return namespace_str; }

  /// Computes the fingerprint string for a struct type used in schema
  /// versioning.
  ///
  /// Fingerprint Format:
  ///   Each field contributes: `<field_id_or_name>,<type_id>,<ref>,<nullable>;`
  ///   Fields are sorted lexicographically by field identifier (tag ID string
  ///   if present, otherwise snake_case field name).
  ///
  /// Field Components:
  ///   - field_id_or_name: tag ID as string if configured, otherwise snake_case
  ///   field name
  ///   - type_id: Fory TypeId as decimal string (e.g., "4" for INT32)
  ///   - ref: "1" if reference tracking enabled, "0" otherwise (always "0" in
  ///   C++)
  ///   - nullable: "1" if null flag is written, "0" otherwise
  ///
  /// Example fingerprints:
  ///   - With tag IDs: "0,4,0,0;1,12,0,1;"
  ///   - With field names: "age,4,0,0;name,12,0,1;"
  ///
  /// This format is consistent across Go, Java, Rust, and C++ implementations.
  static std::string
  compute_struct_fingerprint(const std::vector<FieldInfo> &field_infos);

  /// Compute struct version hash from field metadata.
  ///
  /// Uses compute_struct_fingerprint to build the fingerprint string,
  /// then hashes it with MurmurHash3_x64_128 using seed 47, and takes
  /// the low 32 bits as signed i32.
  ///
  /// This provides the cross-language struct version ID used by class
  /// version checking, consistent with Go, Java, and Rust implementations.
  static int32_t compute_struct_version(const TypeMeta &meta);

private:
  /// Compute hash from type meta bytes
  static int64_t compute_hash(const std::vector<uint8_t> &meta_bytes);
};

// ============================================================================
// Helper utilities for building field metadata
// ============================================================================

namespace detail {

inline uint32_t to_type_id(TypeId id) { return static_cast<uint32_t>(id); }

template <typename T> struct is_shared_ptr : std::false_type {};
template <typename T>
struct is_shared_ptr<std::shared_ptr<T>> : std::true_type {};
template <typename T>
inline constexpr bool is_shared_ptr_v = is_shared_ptr<T>::value;

template <typename T> struct is_unique_ptr : std::false_type {};
template <typename T, typename D>
struct is_unique_ptr<std::unique_ptr<T, D>> : std::true_type {};
template <typename T>
inline constexpr bool is_unique_ptr_v = is_unique_ptr<T>::value;

template <typename T, typename Enable = void> struct FieldTypeBuilder;

template <typename T>
using decay_t = std::remove_cv_t<std::remove_reference_t<T>>;

template <typename T> struct is_string_view : std::false_type {};
template <typename CharT, typename Traits>
struct is_string_view<std::basic_string_view<CharT, Traits>> : std::true_type {
};
template <typename T>
inline constexpr bool is_string_view_v = is_string_view<T>::value;

template <typename T, typename = void>
struct has_serializer_type_id : std::false_type {};

template <typename T>
struct has_serializer_type_id<
    T, std::void_t<decltype(Serializer<decay_t<T>>::type_id)>>
    : std::true_type {};

template <typename T>
inline constexpr bool has_serializer_type_id_v =
    has_serializer_type_id<T>::value;

template <typename T> FieldType build_field_type(bool nullable = false) {
  return FieldTypeBuilder<T>::build(nullable);
}

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_optional_v<decay_t<T>>>> {
  using Inner = typename decay_t<T>::value_type;
  static FieldType build(bool) {
    FieldType inner = FieldTypeBuilder<Inner>::build(true);
    inner.nullable = true;
    return inner;
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_shared_ptr_v<decay_t<T>>>> {
  using Inner = typename decay_t<T>::element_type;
  static FieldType build(bool) {
    if constexpr (std::is_polymorphic_v<Inner>) {
      FieldType ft(to_type_id(TypeId::UNKNOWN), true);
      return ft;
    } else {
      FieldType inner = FieldTypeBuilder<Inner>::build(true);
      inner.nullable = true;
      return inner;
    }
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_unique_ptr_v<decay_t<T>>>> {
  using Inner = typename decay_t<T>::element_type;
  static FieldType build(bool) {
    if constexpr (std::is_polymorphic_v<Inner>) {
      FieldType ft(to_type_id(TypeId::UNKNOWN), true);
      return ft;
    } else {
      FieldType inner = FieldTypeBuilder<Inner>::build(true);
      inner.nullable = true;
      return inner;
    }
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_vector_v<decay_t<T>>>> {
  using Vec = decay_t<T>;
  using Element = element_type_t<Vec>;
  static FieldType build(bool nullable) {
    constexpr TypeId vec_type_id = Serializer<Vec>::type_id;
    if constexpr (vec_type_id == TypeId::LIST) {
      FieldType elem = FieldTypeBuilder<Element>::build(false);
      FieldType ft(to_type_id(vec_type_id), nullable);
      ft.generics.push_back(std::move(elem));
      return ft;
    } else {
      return FieldType(to_type_id(vec_type_id), nullable);
    }
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_list_v<decay_t<T>>>> {
  using List = decay_t<T>;
  using Element = typename List::value_type;
  static FieldType build(bool nullable) {
    FieldType elem = FieldTypeBuilder<Element>::build(false);
    FieldType ft(to_type_id(TypeId::LIST), nullable);
    ft.generics.push_back(std::move(elem));
    return ft;
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_deque_v<decay_t<T>>>> {
  using Deque = decay_t<T>;
  using Element = typename Deque::value_type;
  static FieldType build(bool nullable) {
    FieldType elem = FieldTypeBuilder<Element>::build(false);
    FieldType ft(to_type_id(TypeId::LIST), nullable);
    ft.generics.push_back(std::move(elem));
    return ft;
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_forward_list_v<decay_t<T>>>> {
  using FList = decay_t<T>;
  using Element = typename FList::value_type;
  static FieldType build(bool nullable) {
    FieldType elem = FieldTypeBuilder<Element>::build(false);
    FieldType ft(to_type_id(TypeId::LIST), nullable);
    ft.generics.push_back(std::move(elem));
    return ft;
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_set_like_v<decay_t<T>>>> {
  using Set = decay_t<T>;
  using Element = element_type_t<Set>;
  static FieldType build(bool nullable) {
    FieldType elem = FieldTypeBuilder<Element>::build(false);
    FieldType ft(to_type_id(Serializer<Set>::type_id), nullable);
    ft.generics.push_back(std::move(elem));
    return ft;
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_map_like_v<decay_t<T>>>> {
  using Map = decay_t<T>;
  using Key = key_type_t<Map>;
  using Value = mapped_type_t<Map>;
  static FieldType build(bool nullable) {
    FieldType key_ft = FieldTypeBuilder<Key>::build(false);
    FieldType value_ft = FieldTypeBuilder<Value>::build(false);
    FieldType ft(to_type_id(Serializer<Map>::type_id), nullable);
    ft.generics.push_back(std::move(key_ft));
    ft.generics.push_back(std::move(value_ft));
    return ft;
  }
};

template <typename CharT, typename Traits>
struct FieldTypeBuilder<std::basic_string_view<CharT, Traits>, void> {
  static FieldType build(bool nullable) {
    using StringT = std::basic_string<CharT, Traits>;
    return FieldTypeBuilder<StringT>::build(nullable);
  }
};

// Tuple FieldTypeBuilder - builds FieldType with all element types as generics
template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_tuple_v<decay_t<T>>>> {
  using Tuple = decay_t<T>;

  template <size_t... Is>
  static void add_element_types(FieldType &ft, std::index_sequence<Is...>) {
    (ft.generics.push_back(
         FieldTypeBuilder<std::tuple_element_t<Is, Tuple>>::build(false)),
     ...);
  }

  static FieldType build(bool nullable) {
    constexpr size_t tuple_size = std::tuple_size_v<Tuple>;
    FieldType ft(to_type_id(Serializer<Tuple>::type_id), nullable);
    if constexpr (tuple_size > 0) {
      add_element_types(ft, std::make_index_sequence<tuple_size>{});
    } else {
      // Empty tuple: use UNKNOWN as stub element type for schema encoding
      ft.generics.push_back(
          FieldType(static_cast<uint32_t>(TypeId::UNKNOWN), false));
    }
    return ft;
  }
};

// Specialization for enum types - xlang mode treats enums as non-nullable by
// default
template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<std::is_enum_v<decay_t<T>>>> {
  using Decayed = decay_t<T>;
  static FieldType build(bool nullable) {
    // In xlang mode, enum fields are non-nullable by default (like primitives).
    // This matches Java's ObjectSerializer.compute_struct_fingerprint behavior.
    return FieldType(to_type_id(Serializer<Decayed>::type_id), nullable);
  }
};

template <typename T>
struct FieldTypeBuilder<
    T, std::enable_if_t<
           !is_optional_v<decay_t<T>> && !is_shared_ptr_v<decay_t<T>> &&
           !is_unique_ptr_v<decay_t<T>> && !is_vector_v<decay_t<T>> &&
           !is_list_v<decay_t<T>> && !is_deque_v<decay_t<T>> &&
           !is_forward_list_v<decay_t<T>> && !is_set_like_v<decay_t<T>> &&
           !is_map_like_v<decay_t<T>> && !is_string_view_v<decay_t<T>> &&
           !is_tuple_v<decay_t<T>> && !std::is_enum_v<decay_t<T>> &&
           has_serializer_type_id_v<decay_t<T>>>> {
  using Decayed = decay_t<T>;
  static FieldType build(bool nullable) {
    return FieldType(to_type_id(Serializer<Decayed>::type_id), nullable);
  }
};

inline bool field_type_needs_user_type_id(uint32_t) { return false; }

template <typename T>
Result<FieldType, Error> build_field_type_with_resolver(TypeResolver &resolver,
                                                        bool nullable);

template <typename Tuple, size_t Index>
Result<void, Error> add_tuple_element_types(TypeResolver &resolver,
                                            FieldType &ft) {
  if constexpr (Index < std::tuple_size_v<Tuple>) {
    using Elem = std::tuple_element_t<Index, Tuple>;
    FORY_TRY(elem_ft, build_field_type_with_resolver<Elem>(resolver, false));
    ft.generics.push_back(std::move(elem_ft));
    return add_tuple_element_types<Tuple, Index + 1>(resolver, ft);
  } else {
    return Result<void, Error>();
  }
}

template <typename T>
Result<FieldType, Error> build_field_type_with_resolver(TypeResolver &resolver,
                                                        bool nullable) {
  using Decayed = decay_t<T>;
  if constexpr (is_optional_v<Decayed>) {
    using Inner = typename Decayed::value_type;
    FORY_TRY(inner, build_field_type_with_resolver<Inner>(resolver, true));
    inner.nullable = true;
    return inner;
  } else if constexpr (is_shared_ptr_v<Decayed>) {
    using Inner = typename Decayed::element_type;
    if constexpr (std::is_polymorphic_v<Inner>) {
      return FieldType(to_type_id(TypeId::UNKNOWN), true);
    } else {
      FORY_TRY(inner, build_field_type_with_resolver<Inner>(resolver, true));
      inner.nullable = true;
      return inner;
    }
  } else if constexpr (::fory::detail::is_shared_weak_v<Decayed>) {
    using Inner = nullable_element_t<Decayed>;
    if constexpr (std::is_polymorphic_v<Inner>) {
      return FieldType(to_type_id(TypeId::UNKNOWN), true);
    } else {
      FORY_TRY(inner, build_field_type_with_resolver<Inner>(resolver, true));
      inner.nullable = true;
      return inner;
    }
  } else if constexpr (is_unique_ptr_v<Decayed>) {
    using Inner = typename Decayed::element_type;
    if constexpr (std::is_polymorphic_v<Inner>) {
      return FieldType(to_type_id(TypeId::UNKNOWN), true);
    } else {
      FORY_TRY(inner, build_field_type_with_resolver<Inner>(resolver, true));
      inner.nullable = true;
      return inner;
    }
  } else if constexpr (is_vector_v<Decayed>) {
    using Vec = Decayed;
    using Element = element_type_t<Vec>;
    constexpr TypeId vec_type_id = Serializer<Vec>::type_id;
    if constexpr (vec_type_id == TypeId::LIST) {
      FORY_TRY(elem, build_field_type_with_resolver<Element>(resolver, false));
      FieldType ft(to_type_id(vec_type_id), nullable);
      ft.generics.push_back(std::move(elem));
      return ft;
    } else {
      return FieldType(to_type_id(vec_type_id), nullable);
    }
  } else if constexpr (is_list_v<Decayed>) {
    using Element = typename Decayed::value_type;
    FORY_TRY(elem, build_field_type_with_resolver<Element>(resolver, false));
    FieldType ft(to_type_id(TypeId::LIST), nullable);
    ft.generics.push_back(std::move(elem));
    return ft;
  } else if constexpr (is_deque_v<Decayed>) {
    using Element = typename Decayed::value_type;
    FORY_TRY(elem, build_field_type_with_resolver<Element>(resolver, false));
    FieldType ft(to_type_id(TypeId::LIST), nullable);
    ft.generics.push_back(std::move(elem));
    return ft;
  } else if constexpr (is_forward_list_v<Decayed>) {
    using Element = typename Decayed::value_type;
    FORY_TRY(elem, build_field_type_with_resolver<Element>(resolver, false));
    FieldType ft(to_type_id(TypeId::LIST), nullable);
    ft.generics.push_back(std::move(elem));
    return ft;
  } else if constexpr (is_set_like_v<Decayed>) {
    using Set = Decayed;
    using Element = element_type_t<Set>;
    FORY_TRY(elem, build_field_type_with_resolver<Element>(resolver, false));
    FieldType ft(to_type_id(Serializer<Set>::type_id), nullable);
    ft.generics.push_back(std::move(elem));
    return ft;
  } else if constexpr (is_map_like_v<Decayed>) {
    using Map = Decayed;
    using Key = key_type_t<Map>;
    using Value = mapped_type_t<Map>;
    FORY_TRY(key_ft, build_field_type_with_resolver<Key>(resolver, false));
    FORY_TRY(val_ft, build_field_type_with_resolver<Value>(resolver, false));
    FieldType ft(to_type_id(Serializer<Map>::type_id), nullable);
    ft.generics.push_back(std::move(key_ft));
    ft.generics.push_back(std::move(val_ft));
    return ft;
  } else if constexpr (is_string_view_v<Decayed>) {
    using StringT = std::basic_string<typename Decayed::value_type,
                                      typename Decayed::traits_type>;
    return build_field_type_with_resolver<StringT>(resolver, nullable);
  } else if constexpr (is_tuple_v<Decayed>) {
    constexpr size_t tuple_size = std::tuple_size_v<Decayed>;
    FieldType ft(to_type_id(Serializer<Decayed>::type_id), nullable);
    if constexpr (tuple_size > 0) {
      FORY_RETURN_IF_ERROR((add_tuple_element_types<Decayed, 0>(resolver, ft)));
    } else {
      ft.generics.push_back(
          FieldType(static_cast<uint32_t>(TypeId::UNKNOWN), false));
    }
    return ft;
  } else {
    FieldType ft = FieldTypeBuilder<Decayed>::build(nullable);
    if (is_user_type(static_cast<TypeId>(ft.type_id))) {
      if constexpr (std::is_enum_v<Decayed>) {
        auto info_result = get_type_info_with_resolver<Decayed>(resolver);
        if (info_result.ok()) {
          uint32_t resolved_type_id = info_result.value()->type_id;
          if (resolved_type_id == static_cast<uint32_t>(TypeId::NAMED_ENUM)) {
            resolved_type_id = static_cast<uint32_t>(TypeId::ENUM);
          }
          ft.type_id = resolved_type_id;
        }
      } else {
        FORY_TRY(info, get_type_info_with_resolver<Decayed>(resolver));
        uint32_t resolved_type_id = info->type_id;
        switch (static_cast<TypeId>(resolved_type_id)) {
        case TypeId::NAMED_ENUM:
          resolved_type_id = static_cast<uint32_t>(TypeId::ENUM);
          break;
        case TypeId::TYPED_UNION:
        case TypeId::NAMED_UNION:
          resolved_type_id = static_cast<uint32_t>(TypeId::UNION);
          break;
        default:
          break;
        }
        ft.type_id = resolved_type_id;
      }
    }
    return ft;
  }
}

// Helper template functions to compute is_nullable and track_ref at compile
// time. These replace constexpr lambdas which have issues on MSVC.
template <typename ActualFieldType, typename T, size_t Index,
          typename UnwrappedFieldType>
constexpr bool compute_is_nullable() {
  if constexpr (is_fory_field_v<ActualFieldType>) {
    return ActualFieldType::is_nullable;
  } else if constexpr (::fory::detail::has_field_tags_v<T>) {
    return ::fory::detail::GetFieldTagEntry<T, Index>::is_nullable;
  } else if constexpr (::fory::detail::has_field_config_v<T> &&
                       ::fory::detail::GetFieldConfigEntry<T,
                                                           Index>::has_entry &&
                       ::fory::detail::GetFieldConfigEntry<T,
                                                           Index>::nullable) {
    return true;
  } else {
    // Default: nullable if std::optional or smart pointers.
    return is_optional_v<UnwrappedFieldType> ||
           is_shared_ptr_v<UnwrappedFieldType> ||
           is_unique_ptr_v<UnwrappedFieldType>;
  }
}

template <typename ActualFieldType, typename T, size_t Index>
constexpr bool compute_track_ref() {
  if constexpr (is_fory_field_v<ActualFieldType>) {
    return ActualFieldType::track_ref;
  } else if constexpr (::fory::detail::has_field_tags_v<T>) {
    return ::fory::detail::GetFieldTagEntry<T, Index>::track_ref;
  } else if constexpr (::fory::detail::has_field_config_v<T> &&
                       ::fory::detail::GetFieldConfigEntry<T,
                                                           Index>::has_entry &&
                       ::fory::detail::GetFieldConfigEntry<T, Index>::ref) {
    return true;
  } else {
    using UnwrappedFieldType = fory::unwrap_field_t<ActualFieldType>;
    return ::fory::detail::is_shared_ptr_v<UnwrappedFieldType> ||
           ::fory::detail::is_shared_weak_v<UnwrappedFieldType>;
  }
}

template <typename ActualFieldType, typename T, size_t Index>
constexpr int16_t compute_field_id() {
  if constexpr (::fory::detail::has_field_config_v<T>) {
    constexpr int16_t config_id =
        ::fory::detail::GetFieldConfigEntry<T, Index>::id;
    if constexpr (config_id >= 0) {
      return config_id;
    }
  }
  if constexpr (is_fory_field_v<ActualFieldType>) {
    return field_tag_id_v<ActualFieldType>;
  }
  if constexpr (::fory::detail::has_field_tags_v<T>) {
    constexpr int16_t tag_id = ::fory::detail::GetFieldTagEntry<T, Index>::id;
    if constexpr (tag_id >= 0) {
      return tag_id;
    }
  }
  return -1;
}

// Helper to check if a type is unsigned integer
template <typename T> struct is_unsigned_integer : std::false_type {};
template <> struct is_unsigned_integer<uint8_t> : std::true_type {};
template <> struct is_unsigned_integer<uint16_t> : std::true_type {};
template <> struct is_unsigned_integer<uint32_t> : std::true_type {};
template <> struct is_unsigned_integer<uint64_t> : std::true_type {};
template <typename T>
inline constexpr bool is_unsigned_integer_v = is_unsigned_integer<T>::value;

// Helper to get inner type of optional, or the type itself
template <typename T, typename Enable = void> struct unwrap_optional_inner {
  using type = T;
};
template <typename T>
struct unwrap_optional_inner<T, std::enable_if_t<is_optional_v<decay_t<T>>>> {
  using type = typename decay_t<T>::value_type;
};
template <typename T>
using unwrap_optional_inner_t = typename unwrap_optional_inner<T>::type;

// Helper to compute the correct type_id for unsigned types based on encoding
template <typename FieldT, typename StructT, size_t Index>
constexpr uint32_t compute_unsigned_type_id() {
  // For unsigned types, check if FORY_FIELD_CONFIG specifies an encoding
  if constexpr (::fory::detail::has_field_config_v<StructT>) {
    constexpr auto enc =
        ::fory::detail::GetFieldConfigEntry<StructT, Index>::encoding;
    // Handle inner type for std::optional
    using InnerType = unwrap_optional_inner_t<FieldT>;
    if constexpr (std::is_same_v<InnerType, uint8_t>) {
      return static_cast<uint32_t>(TypeId::UINT8);
    } else if constexpr (std::is_same_v<InnerType, uint16_t>) {
      return static_cast<uint32_t>(TypeId::UINT16);
    } else if constexpr (std::is_same_v<InnerType, uint32_t>) {
      if constexpr (enc == Encoding::Varint) {
        return static_cast<uint32_t>(TypeId::VAR_UINT32);
      } else {
        return static_cast<uint32_t>(TypeId::UINT32);
      }
    } else if constexpr (std::is_same_v<InnerType, uint64_t>) {
      if constexpr (enc == Encoding::Varint) {
        return static_cast<uint32_t>(TypeId::VAR_UINT64);
      } else if constexpr (enc == Encoding::Tagged) {
        return static_cast<uint32_t>(TypeId::TAGGED_UINT64);
      } else {
        return static_cast<uint32_t>(TypeId::UINT64);
      }
    }
  }
  // Not an unsigned type with field config, use default
  return 0;
}

// Helper to compute the correct type_id for signed types based on encoding
template <typename FieldT, typename StructT, size_t Index>
constexpr uint32_t compute_signed_type_id() {
  if constexpr (::fory::detail::has_field_config_v<StructT>) {
    constexpr auto enc =
        ::fory::detail::GetFieldConfigEntry<StructT, Index>::encoding;
    using InnerType = unwrap_optional_inner_t<FieldT>;
    if constexpr (std::is_same_v<InnerType, int32_t> ||
                  std::is_same_v<InnerType, int>) {
      if constexpr (enc == Encoding::Fixed) {
        return static_cast<uint32_t>(TypeId::INT32);
      }
    } else if constexpr (std::is_same_v<InnerType, int64_t> ||
                         std::is_same_v<InnerType, long long>) {
      if constexpr (enc == Encoding::Fixed) {
        return static_cast<uint32_t>(TypeId::INT64);
      } else if constexpr (enc == Encoding::Tagged) {
        return static_cast<uint32_t>(TypeId::TAGGED_INT64);
      }
    }
  }
  return 0;
}

template <typename T, size_t Index> struct FieldInfoBuilder {
  static FieldInfo build() {
    using MetaDesc = decltype(fory_field_info(std::declval<T>()));
    const auto field_names = MetaDesc::Names;
    const auto &field_ptrs = MetaDesc::ptrs_ref();

    // Convert camel_case field name to snake_case for cross-language
    // compatibility
    std::string_view original_name = field_names[Index];
    constexpr size_t max_snake_len = 128; // Reasonable max for field names
    auto [snake_buffer, snake_len] =
        ::fory::to_snake_case<max_snake_len>(original_name);
    std::string field_name(snake_buffer.data(), snake_len);

    const auto field_ptr = std::get<Index>(field_ptrs);
    using RawFieldType =
        typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
    using ActualFieldType =
        std::remove_cv_t<std::remove_reference_t<RawFieldType>>;
    // unwrap fory::field<> to get the underlying type for FieldTypeBuilder
    using UnwrappedFieldType = fory::unwrap_field_t<ActualFieldType>;

    // get nullable and track_ref from field tags (FORY_FIELD_TAGS or
    // fory::field<>)
    constexpr bool is_nullable =
        compute_is_nullable<ActualFieldType, T, Index, UnwrappedFieldType>();
    constexpr bool track_ref = compute_track_ref<ActualFieldType, T, Index>();
    constexpr int16_t field_id = compute_field_id<ActualFieldType, T, Index>();

    FieldType field_type = FieldTypeBuilder<UnwrappedFieldType>::build(false);

    // Override type_id for unsigned types based on encoding from
    // FORY_FIELD_CONFIG
    using InnerType = unwrap_optional_inner_t<UnwrappedFieldType>;
    constexpr uint32_t unsigned_tid =
        compute_unsigned_type_id<UnwrappedFieldType, T, Index>();
    if constexpr (unsigned_tid != 0 && is_unsigned_integer_v<InnerType>) {
      field_type.type_id = unsigned_tid;
    }

    // Override type_id for signed types based on encoding from
    // FORY_FIELD_CONFIG
    constexpr uint32_t signed_tid =
        compute_signed_type_id<UnwrappedFieldType, T, Index>();
    if constexpr (signed_tid != 0) {
      field_type.type_id = signed_tid;
    }

    if constexpr (::fory::detail::has_field_config_v<T>) {
      constexpr int16_t override_id =
          ::fory::detail::GetFieldConfigEntry<T, Index>::type_id_override;
      if constexpr (override_id >= 0) {
        field_type.type_id = static_cast<uint32_t>(override_id);
      }
    }

    // Override nullable and track_ref from field-level metadata
    field_type.nullable = is_nullable;
    field_type.track_ref = track_ref;
    field_type.ref_mode = make_ref_mode(is_nullable, track_ref);
#ifdef FORY_DEBUG
    // DEBUG: Print field info for debugging fingerprint mismatch
    std::cerr << "[xlang][debug] FieldInfoBuilder T=" << typeid(T).name()
              << " Index=" << Index << " field=" << field_name
              << " type_id=" << field_type.type_id << " has_tags="
              << ::fory::detail::has_field_tags_v<T> << " is_nullable="
              << is_nullable << " track_ref=" << track_ref << std::endl;
#endif
    FieldInfo info(std::move(field_name), std::move(field_type));
    info.field_id = field_id;
    return info;
  }

  static Result<FieldInfo, Error> build_with_resolver(TypeResolver &resolver) {
    using MetaDesc = decltype(fory_field_info(std::declval<T>()));
    const auto field_names = MetaDesc::Names;
    const auto &field_ptrs = MetaDesc::ptrs_ref();

    // Convert camel_case field name to snake_case for cross-language
    // compatibility
    std::string_view original_name = field_names[Index];
    constexpr size_t max_snake_len = 128; // Reasonable max for field names
    auto [snake_buffer, snake_len] =
        ::fory::to_snake_case<max_snake_len>(original_name);
    std::string field_name(snake_buffer.data(), snake_len);

    const auto field_ptr = std::get<Index>(field_ptrs);
    using RawFieldType =
        typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
    using ActualFieldType =
        std::remove_cv_t<std::remove_reference_t<RawFieldType>>;
    // unwrap fory::field<> to get the underlying type for FieldTypeBuilder
    using UnwrappedFieldType = fory::unwrap_field_t<ActualFieldType>;

    // get nullable and track_ref from field tags (FORY_FIELD_TAGS or
    // fory::field<>)
    constexpr bool is_nullable =
        compute_is_nullable<ActualFieldType, T, Index, UnwrappedFieldType>();
    constexpr bool track_ref = compute_track_ref<ActualFieldType, T, Index>();
    constexpr int16_t field_id = compute_field_id<ActualFieldType, T, Index>();

    FORY_TRY(field_type, build_field_type_with_resolver<UnwrappedFieldType>(
                             resolver, false));

    // Override type_id for unsigned types based on encoding from
    // FORY_FIELD_CONFIG
    using InnerType = unwrap_optional_inner_t<UnwrappedFieldType>;
    constexpr uint32_t unsigned_tid =
        compute_unsigned_type_id<UnwrappedFieldType, T, Index>();
    if constexpr (unsigned_tid != 0 && is_unsigned_integer_v<InnerType>) {
      field_type.type_id = unsigned_tid;
    }

    // Override type_id for signed types based on encoding from
    // FORY_FIELD_CONFIG
    constexpr uint32_t signed_tid =
        compute_signed_type_id<UnwrappedFieldType, T, Index>();
    if constexpr (signed_tid != 0) {
      field_type.type_id = signed_tid;
    }

    if constexpr (::fory::detail::has_field_config_v<T>) {
      constexpr int16_t override_id =
          ::fory::detail::GetFieldConfigEntry<T, Index>::type_id_override;
      if constexpr (override_id >= 0) {
        field_type.type_id = static_cast<uint32_t>(override_id);
      }
    }

    // Override nullable and track_ref from field-level metadata
    field_type.nullable = is_nullable;
    field_type.track_ref = track_ref;
    field_type.ref_mode = make_ref_mode(is_nullable, track_ref);
#ifdef FORY_DEBUG
    // DEBUG: Print field info for debugging fingerprint mismatch
    std::cerr << "[xlang][debug] FieldInfoBuilder T=" << typeid(T).name()
              << " Index=" << Index << " field=" << field_name
              << " type_id=" << field_type.type_id << " has_tags="
              << ::fory::detail::has_field_tags_v<T> << " is_nullable="
              << is_nullable << " track_ref=" << track_ref << std::endl;
#endif
    FieldInfo info(std::move(field_name), std::move(field_type));
    info.field_id = field_id;
    return info;
  }
};

template <typename T, size_t... Indices>
std::vector<FieldInfo> build_field_infos(std::index_sequence<Indices...>) {
  std::vector<FieldInfo> fields;
  fields.reserve(sizeof...(Indices));
  (fields.push_back(FieldInfoBuilder<T, Indices>::build()), ...);
  return fields;
}

template <typename T>
Result<void, Error> append_field_infos(TypeResolver &, std::vector<FieldInfo> &,
                                       std::index_sequence<>) {
  return Result<void, Error>();
}

template <typename T, size_t Index, size_t... Rest>
Result<void, Error> append_field_infos(TypeResolver &resolver,
                                       std::vector<FieldInfo> &fields,
                                       std::index_sequence<Index, Rest...>) {
  FORY_TRY(info, (FieldInfoBuilder<T, Index>::build_with_resolver(resolver)));
  fields.push_back(std::move(info));
  return append_field_infos<T>(resolver, fields,
                               std::index_sequence<Rest...>{});
}

template <typename T, size_t... Indices>
Result<std::vector<FieldInfo>, Error>
build_field_infos_with_resolver(TypeResolver &resolver,
                                std::index_sequence<Indices...>) {
  std::vector<FieldInfo> fields;
  fields.reserve(sizeof...(Indices));
  auto append_fields = [&](auto index_sequence) -> Result<void, Error> {
    return append_field_infos<T>(resolver, fields, index_sequence);
  };
  FORY_RETURN_IF_ERROR(append_fields(std::index_sequence<Indices...>{}));
  return fields;
}

} // namespace detail

// ============================================================================
// Helper function to encode meta strings at registration time
// ============================================================================

/// encode a meta string for namespace or type_name using the appropriate
/// encoder. This is called during registration to pre-compute the encoded form.
Result<std::unique_ptr<CachedMetaString>, Error>
encode_meta_string(const std::string &value, bool is_namespace);

// ============================================================================
// TypeResolver - central registry for type metadata and configuration
// ============================================================================
// Note: Harness and TypeInfo are defined in type_info.h (included via
// context.h)

class TypeResolver {
public:
  TypeResolver();
  TypeResolver(const TypeResolver &) = delete;
  TypeResolver &operator=(const TypeResolver &) = delete;

  void apply_config(const Config &config);

  bool compatible() const { return compatible_; }

  template <typename T> const TypeMeta &struct_meta();
  template <typename T> TypeMeta clone_struct_meta();
  template <typename T> const std::vector<size_t> &sorted_indices();

  /// get type info by type ID (for internal types)
  /// @return const pointer to TypeInfo if found, error otherwise
  Result<const TypeInfo *, Error> get_type_info_by_id(uint32_t type_id) const;

  /// get type info by type ID and user type ID (for user-registered types)
  /// @return const pointer to TypeInfo if found, error otherwise
  Result<const TypeInfo *, Error>
  get_user_type_info_by_id(uint32_t type_id, uint32_t user_type_id) const;

  /// get type info by namespace and type name (for namespaced types)
  /// @return const pointer to TypeInfo if found, error otherwise
  Result<const TypeInfo *, Error>
  get_type_info_by_name(const std::string &ns,
                        const std::string &type_name) const;

  /// get TypeInfo by type_index (used for looking up registered types)
  /// @return const pointer to TypeInfo if found, error otherwise
  Result<const TypeInfo *, Error>
  get_type_info(const std::type_index &type_index) const;

  /// get TypeInfo by compile-time type ID (fast path for template types)
  /// Works for enums, structs, and any registered type.
  /// @return const pointer to TypeInfo if found, error otherwise
  template <typename T> Result<const TypeInfo *, Error> get_type_info() const;

  template <typename T> Result<void, Error> register_any_type();

  /// Builds the final TypeResolver by completing all partial type infos
  /// created during registration.
  ///
  /// This method processes all types that were registered. During registration,
  /// types are stored in `partial_type_infos` without their complete
  /// type metadata to avoid circular dependencies. This method:
  ///
  /// 1. Iterates through all partial type infos
  /// 2. Calls their `sorted_field_infos` function to get complete field
  /// information
  /// 3. Builds complete TypeMeta and serializes it to bytes
  /// 4. Returns a new TypeResolver with all type infos fully initialized
  ///
  /// @return A new TypeResolver with all type infos fully initialized and ready
  /// for use.
  Result<std::unique_ptr<TypeResolver>, Error> build_final_type_resolver();

  /// Deep clones the TypeResolver for use in a new context.
  ///
  /// This method creates a deep clone of the TypeResolver. All TypeInfo
  /// objects are cloned into new instances owned by the new TypeResolver.
  /// This ensures thread safety when cloning for use in different contexts.
  ///
  /// @return A new TypeResolver instance with cloned TypeInfo objects
  std::unique_ptr<TypeResolver> clone() const;

private:
  friend class BaseFory;
  friend class Fory;

  template <typename T> Result<void, Error> register_by_id(uint32_t type_id);

  template <typename T>
  Result<void, Error> register_by_name(const std::string &ns,
                                       const std::string &type_name);

  template <typename T>
  Result<void, Error> register_ext_type_by_id(uint32_t type_id);

  template <typename T>
  Result<void, Error> register_ext_type_by_name(const std::string &ns,
                                                const std::string &type_name);

  template <typename T>
  Result<void, Error> register_union_by_id(uint32_t type_id);

  template <typename T>
  Result<void, Error> register_union_by_name(const std::string &ns,
                                             const std::string &type_name);

  template <typename T>
  static Result<std::unique_ptr<TypeInfo>, Error>
  build_struct_type_info(uint32_t type_id, uint32_t user_type_id,
                         std::string ns, std::string type_name,
                         bool register_by_name);

  template <typename T>
  static Result<std::unique_ptr<TypeInfo>, Error>
  build_enum_type_info(uint32_t type_id, uint32_t user_type_id, std::string ns,
                       std::string type_name, bool register_by_name);

  template <typename T>
  static Result<std::unique_ptr<TypeInfo>, Error>
  build_ext_type_info(uint32_t type_id, uint32_t user_type_id, std::string ns,
                      std::string type_name, bool register_by_name);

  template <typename T>
  static Result<std::unique_ptr<TypeInfo>, Error>
  build_union_type_info(uint32_t type_id, uint32_t user_type_id, std::string ns,
                        std::string type_name, bool register_by_name);

  template <typename T> static Harness make_struct_harness();

  template <typename T> static Harness make_struct_harness_impl(std::true_type);

  template <typename T>
  static Harness make_struct_harness_impl(std::false_type);

  template <typename T> static Harness make_serializer_harness();

  template <typename T>
  static void harness_write_adapter(const void *value, WriteContext &ctx,
                                    RefMode ref_mode, bool write_type_info,
                                    bool has_generics);

  template <typename T>
  static void *harness_read_adapter(ReadContext &ctx, RefMode ref_mode,
                                    bool read_type_info);

  template <typename T>
  static void *harness_read_adapter_abstract(ReadContext &ctx, RefMode ref_mode,
                                             bool read_type_info);

  template <typename T>
  static void harness_write_data_adapter(const void *value, WriteContext &ctx,
                                         bool has_generics);

  template <typename T>
  static void *harness_read_data_adapter(ReadContext &ctx);

  template <typename T>
  static void *harness_read_data_adapter_abstract(ReadContext &ctx);

  template <typename T>
  static Result<std::vector<FieldInfo>, Error>
  harness_struct_sorted_fields(TypeResolver &resolver);

  template <typename T>
  static Result<std::vector<FieldInfo>, Error>
  harness_empty_sorted_fields(TypeResolver &resolver);

  template <typename T>
  static void *harness_read_compatible_adapter(ReadContext &ctx,
                                               const TypeInfo *ti);

  template <typename T>
  static void *harness_read_compatible_adapter_abstract(ReadContext &ctx,
                                                        const TypeInfo *ti);

  static std::string make_name_key(const std::string &ns,
                                   const std::string &name);
  static uint64_t make_user_type_key(uint32_t type_id, uint32_t user_type_id);

  /// Register a TypeInfo, taking ownership and storing in primary storage.
  /// Returns pointer to the stored TypeInfo (owned by TypeResolver).
  Result<TypeInfo *, Error>
  register_type_internal(uint64_t ctid, std::unique_ptr<TypeInfo> info);

  // For runtime polymorphic lookups (smart pointers with dynamic types)
  void register_type_internal_runtime(const std::type_index &type_index,
                                      TypeInfo *info);

  void check_registration_thread();

  void register_builtin_types();

  bool compatible_;
  bool xlang_;
  bool check_struct_version_;
  bool track_ref_;

  std::thread::id registration_thread_id_;
  bool finalized_;

  // Primary storage - owns all TypeInfo objects
  std::vector<std::unique_ptr<TypeInfo>> type_infos_;

  // Lookup maps - store raw pointers (borrowed from primary storage)
  // Using compile-time type ID (uint64_t) - fast for template lookups
  // FlatIntMap is optimized for integer keys with minimal overhead
  util::U64PtrMap<TypeInfo> type_info_by_ctid_{256};
  util::U32PtrMap<TypeInfo> type_info_by_id_{256};
  util::U64PtrMap<TypeInfo> user_type_info_by_id_{256};
  absl::flat_hash_map<std::string, TypeInfo *> type_info_by_name_;
  util::U64PtrMap<TypeInfo> partial_type_infos_{256};

  // For runtime polymorphic lookups (smart pointers) - uses std::type_index
  absl::flat_hash_map<std::type_index, TypeInfo *> type_info_by_runtime_type_;
};

// Alias for backward compatibility (already defined above as top-level)
// using TypeInfo = TypeInfo;

// ============================================================================
// Inline implementations
// ============================================================================

inline TypeResolver::TypeResolver()
    : compatible_(false), xlang_(false), check_struct_version_(true),
      track_ref_(true), registration_thread_id_(std::this_thread::get_id()),
      finalized_(false) {
  register_builtin_types();
}

inline void TypeResolver::apply_config(const Config &config) {
  compatible_ = config.compatible;
  xlang_ = config.xlang;
  check_struct_version_ = config.check_struct_version;
  track_ref_ = config.track_ref;
}

inline void TypeResolver::check_registration_thread() {
  FORY_CHECK(std::this_thread::get_id() == registration_thread_id_)
      << "TypeResolver registration methods must be called from the same "
         "thread that created the TypeResolver";
  FORY_CHECK(!finalized_)
      << "TypeResolver has been finalized, cannot register more types";
}

namespace detail {

template <typename T>
inline void any_write_adapter(const std::any &value, WriteContext &ctx) {
  const T *ptr = std::any_cast<T>(&value);
  if (ptr != nullptr) {
    Serializer<T>::write_data(*ptr, ctx);
    return;
  }
  const auto *shared_ptr = std::any_cast<std::shared_ptr<T>>(&value);
  if (shared_ptr != nullptr) {
    if (FORY_PREDICT_FALSE(!(*shared_ptr))) {
      ctx.set_error(Error::invalid("std::any stored shared_ptr is null"));
      return;
    }
    Serializer<T>::write_data(**shared_ptr, ctx);
    return;
  }
  ctx.set_error(Error::type_error("std::any stored value type mismatch"));
}

template <typename T> inline std::any any_read_adapter(ReadContext &ctx) {
  T value = Serializer<T>::read_data(ctx);
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return std::any();
  }
  if constexpr (std::is_copy_constructible<T>::value) {
    return std::any(std::move(value));
  }
  return std::any(std::make_shared<T>(std::move(value)));
}

template <typename T>
inline std::any any_read_adapter_abstract(ReadContext &ctx) {
  ctx.set_error(Error::type_error("Cannot deserialize abstract type"));
  return std::any();
}

} // namespace detail

template <typename T> const TypeMeta &TypeResolver::struct_meta() {
  constexpr uint64_t ctid = type_index<T>();
  TypeInfo *info = type_info_by_ctid_.get_or_default(ctid, nullptr);
  FORY_CHECK(info != nullptr) << "Type not registered";
  FORY_CHECK(info->type_meta)
      << "Type metadata not initialized for requested struct";
  return *info->type_meta;
}

template <typename T> TypeMeta TypeResolver::clone_struct_meta() {
  constexpr uint64_t ctid = type_index<T>();
  TypeInfo *info = type_info_by_ctid_.get_or_default(ctid, nullptr);
  FORY_CHECK(info != nullptr) << "Type not registered";
  FORY_CHECK(info->type_meta)
      << "Type metadata not initialized for requested struct";
  return *info->type_meta;
}

template <typename T>
const std::vector<size_t> &TypeResolver::sorted_indices() {
  constexpr uint64_t ctid = type_index<T>();
  TypeInfo *info = type_info_by_ctid_.get_or_default(ctid, nullptr);
  FORY_CHECK(info != nullptr) << "Type not registered";
  return info->sorted_indices;
}

template <typename T>
Result<const TypeInfo *, Error> TypeResolver::get_type_info() const {
  // Use compile-time type ID (uint64_t key) for fast lookup
  constexpr uint64_t ctid = type_index<T>();
  TypeInfo *info = type_info_by_ctid_.get_or_default(ctid, nullptr);
  if (FORY_PREDICT_TRUE(info != nullptr)) {
    return info;
  }
  return Unexpected(Error::type_error("Type not registered"));
}

template <typename T>
Result<const TypeInfo *, Error>
get_type_info_with_resolver(TypeResolver &resolver) {
  return resolver.get_type_info<T>();
}

template <typename T> Result<void, Error> TypeResolver::register_any_type() {
  check_registration_thread();
  constexpr uint32_t static_type_id =
      static_cast<uint32_t>(Serializer<T>::type_id);
  TypeInfo *type_info = nullptr;
  if (is_internal_type(static_type_id)) {
    type_info = type_info_by_id_.get_or_default(static_type_id, nullptr);
    if (FORY_PREDICT_FALSE(type_info == nullptr)) {
      return Unexpected(Error::type_error("TypeInfo not found for type_id: " +
                                          std::to_string(static_type_id)));
    }
  } else {
    constexpr uint64_t ctid = type_index<T>();
    type_info = type_info_by_ctid_.get_or_default(ctid, nullptr);
    if (FORY_PREDICT_FALSE(type_info == nullptr)) {
      return Unexpected(Error::type_error("Type not registered"));
    }
  }

  type_info->harness.any_write_fn = &detail::any_write_adapter<T>;
  type_info->harness.any_read_fn = &detail::any_read_adapter<T>;
  register_type_internal_runtime(std::type_index(typeid(T)), type_info);
  return Result<void, Error>();
}

template <typename T>
Result<void, Error> TypeResolver::register_by_id(uint32_t type_id) {
  check_registration_thread();
  if (type_id == kInvalidUserTypeId) {
    return Unexpected(Error::invalid(
        "type_id must be in range [0, 0xfffffffe] for register_by_id"));
  }

  constexpr uint64_t ctid = type_index<T>();

  if constexpr (is_fory_serializable_v<T>) {
    uint32_t actual_type_id =
        compatible_ && meta::StructEvolving<T>::value
            ? static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT)
            : static_cast<uint32_t>(TypeId::STRUCT);
    uint32_t user_type_id = type_id;

    FORY_TRY(info, build_struct_type_info<T>(actual_type_id, user_type_id, "",
                                             "", false));
    if (!info->harness.valid()) {
      return Unexpected(
          Error::invalid("Harness for registered type is incomplete"));
    }

    // Register and get back the stored pointer
    FORY_TRY(stored_ptr, register_type_internal(ctid, std::move(info)));
    // Also register for runtime polymorphic lookups and partial type infos
    partial_type_infos_.put(ctid, stored_ptr);
    register_type_internal_runtime(std::type_index(typeid(T)), stored_ptr);
    return Result<void, Error>();
  } else if constexpr (std::is_enum_v<T>) {
    uint32_t actual_type_id = static_cast<uint32_t>(TypeId::ENUM);
    uint32_t user_type_id = type_id;

    FORY_TRY(info, build_enum_type_info<T>(actual_type_id, user_type_id, "", "",
                                           false));
    if (!info->harness.valid()) {
      return Unexpected(
          Error::invalid("Harness for registered enum type is incomplete"));
    }

    FORY_TRY(stored_ptr, register_type_internal(ctid, std::move(info)));
    partial_type_infos_.put(ctid, stored_ptr);
    register_type_internal_runtime(std::type_index(typeid(T)), stored_ptr);
    return Result<void, Error>();
  } else {
    static_assert(is_fory_serializable_v<T>,
                  "register_by_id requires a type declared with FORY_STRUCT "
                  "or an enum type");
    return Result<void, Error>();
  }
}

template <typename T>
Result<void, Error>
TypeResolver::register_by_name(const std::string &ns,
                               const std::string &type_name) {
  check_registration_thread();
  if (type_name.empty()) {
    return Unexpected(
        Error::invalid("type_name must be non-empty for register_by_name"));
  }

  constexpr uint64_t ctid = type_index<T>();

  if constexpr (is_fory_serializable_v<T>) {
    uint32_t actual_type_id =
        compatible_ && meta::StructEvolving<T>::value
            ? static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT)
            : static_cast<uint32_t>(TypeId::NAMED_STRUCT);

    FORY_TRY(info, build_struct_type_info<T>(actual_type_id, kInvalidUserTypeId,
                                             ns, type_name, true));
    if (!info->harness.valid()) {
      return Unexpected(
          Error::invalid("Harness for registered type is incomplete"));
    }

    FORY_TRY(stored_ptr, register_type_internal(ctid, std::move(info)));
    partial_type_infos_.put(ctid, stored_ptr);
    register_type_internal_runtime(std::type_index(typeid(T)), stored_ptr);
    return Result<void, Error>();
  } else if constexpr (std::is_enum_v<T>) {
    uint32_t actual_type_id = static_cast<uint32_t>(TypeId::NAMED_ENUM);
    FORY_TRY(info, build_enum_type_info<T>(actual_type_id, kInvalidUserTypeId,
                                           ns, type_name, true));
    if (!info->harness.valid()) {
      return Unexpected(
          Error::invalid("Harness for registered enum type is incomplete"));
    }

    FORY_TRY(stored_ptr, register_type_internal(ctid, std::move(info)));
    partial_type_infos_.put(ctid, stored_ptr);
    register_type_internal_runtime(std::type_index(typeid(T)), stored_ptr);
    return Result<void, Error>();
  } else {
    static_assert(is_fory_serializable_v<T>,
                  "register_by_name requires a type declared with FORY_STRUCT "
                  "or an enum type");
    return Result<void, Error>();
  }
}

template <typename T>
Result<void, Error> TypeResolver::register_ext_type_by_id(uint32_t type_id) {
  check_registration_thread();
  if (type_id == kInvalidUserTypeId) {
    return Unexpected(Error::invalid("type_id must be in range [0, 0xfffffffe] "
                                     "for register_ext_type_by_id"));
  }

  constexpr uint64_t ctid = type_index<T>();

  uint32_t actual_type_id = static_cast<uint32_t>(TypeId::EXT);
  uint32_t user_type_id = type_id;

  FORY_TRY(info,
           build_ext_type_info<T>(actual_type_id, user_type_id, "", "", false));

  FORY_TRY(stored_ptr, register_type_internal(ctid, std::move(info)));
  partial_type_infos_.put(ctid, stored_ptr);
  register_type_internal_runtime(std::type_index(typeid(T)), stored_ptr);
  return Result<void, Error>();
}

template <typename T>
Result<void, Error>
TypeResolver::register_ext_type_by_name(const std::string &ns,
                                        const std::string &type_name) {
  check_registration_thread();
  if (type_name.empty()) {
    return Unexpected(Error::invalid(
        "type_name must be non-empty for register_ext_type_by_name"));
  }

  constexpr uint64_t ctid = type_index<T>();

  uint32_t actual_type_id = static_cast<uint32_t>(TypeId::NAMED_EXT);
  FORY_TRY(info, build_ext_type_info<T>(actual_type_id, kInvalidUserTypeId, ns,
                                        type_name, true));

  FORY_TRY(stored_ptr, register_type_internal(ctid, std::move(info)));
  partial_type_infos_.put(ctid, stored_ptr);
  register_type_internal_runtime(std::type_index(typeid(T)), stored_ptr);
  return Result<void, Error>();
}

template <typename T>
Result<void, Error> TypeResolver::register_union_by_id(uint32_t type_id) {
  check_registration_thread();
  if (type_id == kInvalidUserTypeId) {
    return Unexpected(Error::invalid(
        "type_id must be in range [0, 0xfffffffe] for register_union_by_id"));
  }

  constexpr uint64_t ctid = type_index<T>();
  uint32_t actual_type_id = static_cast<uint32_t>(TypeId::TYPED_UNION);
  uint32_t user_type_id = type_id;

  FORY_TRY(info, build_union_type_info<T>(actual_type_id, user_type_id, "", "",
                                          false));

  FORY_TRY(stored_ptr, register_type_internal(ctid, std::move(info)));
  partial_type_infos_.put(ctid, stored_ptr);
  register_type_internal_runtime(std::type_index(typeid(T)), stored_ptr);
  return Result<void, Error>();
}

template <typename T>
Result<void, Error>
TypeResolver::register_union_by_name(const std::string &ns,
                                     const std::string &type_name) {
  check_registration_thread();
  if (type_name.empty()) {
    return Unexpected(Error::invalid(
        "type_name must be non-empty for register_union_by_name"));
  }

  constexpr uint64_t ctid = type_index<T>();

  uint32_t actual_type_id = static_cast<uint32_t>(TypeId::NAMED_UNION);
  FORY_TRY(info, build_union_type_info<T>(actual_type_id, kInvalidUserTypeId,
                                          ns, type_name, true));

  FORY_TRY(stored_ptr, register_type_internal(ctid, std::move(info)));
  partial_type_infos_.put(ctid, stored_ptr);
  register_type_internal_runtime(std::type_index(typeid(T)), stored_ptr);
  return Result<void, Error>();
}

template <typename T>
Result<std::unique_ptr<TypeInfo>, Error>
TypeResolver::build_struct_type_info(uint32_t type_id, uint32_t user_type_id,
                                     std::string ns, std::string type_name,
                                     bool register_by_name) {
  static_assert(is_fory_serializable_v<T>,
                "build_struct_type_info requires FORY_STRUCT types");

  if (type_id == 0) {
    type_id = static_cast<uint32_t>(TypeId::STRUCT);
  }

  auto entry = std::make_unique<TypeInfo>();
  entry->type_id = type_id;
  entry->user_type_id = user_type_id;
  entry->namespace_name = std::move(ns);
  entry->register_by_name = register_by_name;
  entry->is_external = false;

  using MetaDesc = decltype(fory_field_info(std::declval<T>()));
  constexpr size_t field_count = MetaDesc::Size;
  const auto field_names = MetaDesc::Names;

  std::string resolved_name = type_name;
  if (resolved_name.empty()) {
    resolved_name = std::string(MetaDesc::Name);
  }
  if (register_by_name && resolved_name.empty()) {
    return Unexpected(Error::invalid(
        "Resolved type name must be non-empty when register_by_name is true"));
  }
  entry->type_name = std::move(resolved_name);

  entry->name_to_index.reserve(field_count);
  for (size_t i = 0; i < field_count; ++i) {
    // Convert camel_case field name to snake_case for cross-language
    // compatibility
    constexpr size_t max_snake_len = 128;
    auto [snake_buffer, snake_len] =
        ::fory::to_snake_case<max_snake_len>(field_names[i]);
    entry->name_to_index.emplace(std::string(snake_buffer.data(), snake_len),
                                 i);
  }

  auto field_infos =
      detail::build_field_infos<T>(std::make_index_sequence<field_count>{});
  auto sorted_fields = TypeMeta::sort_field_infos(std::move(field_infos));

  entry->sorted_indices.clear();
  entry->sorted_indices.reserve(field_count);
  for (const auto &sorted_field : sorted_fields) {
    auto it = entry->name_to_index.find(sorted_field.field_name);
    FORY_CHECK(it != entry->name_to_index.end())
        << "Sorted field name '" << sorted_field.field_name
        << "' not found in original struct definition";
    entry->sorted_indices.push_back(it->second);
  }

  entry->harness = make_struct_harness<T>();

  // Pre-encode namespace and type_name for efficient writing
  FORY_TRY(enc_ns, encode_meta_string(entry->namespace_name, true));
  entry->encoded_namespace = std::move(enc_ns);
  FORY_TRY(enc_tn, encode_meta_string(entry->type_name, false));
  entry->encoded_type_name = std::move(enc_tn);

  return entry;
}

template <typename T>
Result<std::unique_ptr<TypeInfo>, Error>
TypeResolver::build_enum_type_info(uint32_t type_id, uint32_t user_type_id,
                                   std::string ns, std::string type_name,
                                   bool register_by_name) {
  static_assert(std::is_enum_v<T>, "build_enum_type_info requires enum types");

  if (register_by_name && type_name.empty()) {
    return Unexpected(Error::invalid(
        "type_name must be non-empty when register_by_name is true"));
  }

  auto entry = std::make_unique<TypeInfo>();
  entry->type_id = type_id;
  entry->user_type_id = user_type_id;
  entry->namespace_name = std::move(ns);
  entry->register_by_name = register_by_name;
  entry->is_external = false;

  // When a user explicitly provides a type_name via registration, Java stores
  // and writes that exact name. The ENUM_PREFIX "2" is only added when Java
  // auto-generates the type name from the class itself (via
  // encode_pkg_and_class). Since C++ users always explicitly provide the type
  // name, we should NOT add the prefix.
  if (!type_name.empty()) {
    entry->type_name = std::move(type_name);
  } else {
    entry->type_name = std::string(typeid(T).name());
  }

  entry->harness = make_serializer_harness<T>();
  if (!entry->harness.valid()) {
    return Unexpected(Error::invalid("Harness for enum type is incomplete"));
  }

  // Pre-encode namespace and type_name for efficient writing
  FORY_TRY(enc_ns, encode_meta_string(entry->namespace_name, true));
  entry->encoded_namespace = std::move(enc_ns);
  FORY_TRY(enc_tn, encode_meta_string(entry->type_name, false));
  entry->encoded_type_name = std::move(enc_tn);

  return entry;
}

template <typename T>
Result<std::unique_ptr<TypeInfo>, Error>
TypeResolver::build_ext_type_info(uint32_t type_id, uint32_t user_type_id,
                                  std::string ns, std::string type_name,
                                  bool register_by_name) {
  auto entry = std::make_unique<TypeInfo>();
  entry->type_id = type_id;
  entry->user_type_id = user_type_id;
  entry->namespace_name = ns;
  entry->type_name = type_name;
  entry->register_by_name = register_by_name;
  entry->is_external = true;
  entry->harness = make_serializer_harness<T>();

  if (!entry->harness.valid()) {
    return Unexpected(
        Error::invalid("Harness for external type is incomplete"));
  }

  // Pre-encode namespace and type_name for efficient writing
  FORY_TRY(enc_ns, encode_meta_string(entry->namespace_name, true));
  entry->encoded_namespace = std::move(enc_ns);
  FORY_TRY(enc_tn, encode_meta_string(entry->type_name, false));
  entry->encoded_type_name = std::move(enc_tn);

  // Create TypeMeta for extension type (with empty fields) and generate
  // type_def. Extension types need type_def for meta sharing in compatible
  // mode.
  TypeMeta meta = TypeMeta::from_fields(
      type_id, entry->namespace_name, entry->type_name, register_by_name,
      entry->user_type_id, std::vector<FieldInfo>{});
  FORY_TRY(type_def, meta.to_bytes());
  entry->type_def = std::move(type_def);

  return entry;
}

template <typename T>
Result<std::unique_ptr<TypeInfo>, Error>
TypeResolver::build_union_type_info(uint32_t type_id, uint32_t user_type_id,
                                    std::string ns, std::string type_name,
                                    bool register_by_name) {
  auto entry = std::make_unique<TypeInfo>();
  entry->type_id = type_id;
  entry->user_type_id = user_type_id;
  entry->namespace_name = std::move(ns);
  if (!type_name.empty()) {
    entry->type_name = std::move(type_name);
  } else {
    entry->type_name = std::string(typeid(T).name());
  }
  entry->register_by_name = register_by_name;
  entry->harness = make_serializer_harness<T>();

  if (!entry->harness.valid()) {
    return Unexpected(Error::invalid("Harness for union type is incomplete"));
  }

  FORY_TRY(enc_ns, encode_meta_string(entry->namespace_name, true));
  entry->encoded_namespace = std::move(enc_ns);
  FORY_TRY(enc_tn, encode_meta_string(entry->type_name, false));
  entry->encoded_type_name = std::move(enc_tn);

  return entry;
}

template <typename T> Harness TypeResolver::make_struct_harness() {
  constexpr bool needs_abstract_harness =
      std::is_abstract_v<T> || !std::is_default_constructible_v<T>;
  return make_struct_harness_impl<T>(
      std::bool_constant<needs_abstract_harness>{});
}

template <typename T>
Harness TypeResolver::make_struct_harness_impl(std::true_type) {
  Harness harness(&TypeResolver::harness_write_adapter<T>,
                  &TypeResolver::harness_read_adapter_abstract<T>,
                  &TypeResolver::harness_write_data_adapter<T>,
                  &TypeResolver::harness_read_data_adapter_abstract<T>,
                  &TypeResolver::harness_struct_sorted_fields<T>,
                  &TypeResolver::harness_read_compatible_adapter_abstract<T>);
  harness.any_write_fn = &detail::any_write_adapter<T>;
  harness.any_read_fn = &detail::any_read_adapter_abstract<T>;
  return harness;
}

template <typename T>
Harness TypeResolver::make_struct_harness_impl(std::false_type) {
  Harness harness(&TypeResolver::harness_write_adapter<T>,
                  &TypeResolver::harness_read_adapter<T>,
                  &TypeResolver::harness_write_data_adapter<T>,
                  &TypeResolver::harness_read_data_adapter<T>,
                  &TypeResolver::harness_struct_sorted_fields<T>,
                  &TypeResolver::harness_read_compatible_adapter<T>);
  harness.any_write_fn = &detail::any_write_adapter<T>;
  harness.any_read_fn = &detail::any_read_adapter<T>;
  return harness;
}

template <typename T> Harness TypeResolver::make_serializer_harness() {
  Harness harness(&TypeResolver::harness_write_adapter<T>,
                  &TypeResolver::harness_read_adapter<T>,
                  &TypeResolver::harness_write_data_adapter<T>,
                  &TypeResolver::harness_read_data_adapter<T>,
                  &TypeResolver::harness_empty_sorted_fields<T>);
  harness.any_write_fn = &detail::any_write_adapter<T>;
  harness.any_read_fn = &detail::any_read_adapter<T>;
  return harness;
}

template <typename T>
void TypeResolver::harness_write_adapter(const void *value, WriteContext &ctx,
                                         RefMode ref_mode, bool write_type_info,
                                         bool has_generics) {
  (void)has_generics;
  const T *ptr = static_cast<const T *>(value);
  Serializer<T>::write(*ptr, ctx, ref_mode, write_type_info);
}

template <typename T>
void *TypeResolver::harness_read_adapter(ReadContext &ctx, RefMode ref_mode,
                                         bool read_type_info) {
  T value = Serializer<T>::read(ctx, ref_mode, read_type_info);
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return nullptr;
  }
  return new T(std::move(value));
}

template <typename T>
void *TypeResolver::harness_read_adapter_abstract(ReadContext &ctx, RefMode,
                                                  bool) {
  ctx.set_error(Error::type_error("Cannot deserialize abstract type"));
  return nullptr;
}

template <typename T>
void TypeResolver::harness_write_data_adapter(const void *value,
                                              WriteContext &ctx,
                                              bool has_generics) {
  const T *ptr = static_cast<const T *>(value);
  Serializer<T>::write_data_generic(*ptr, ctx, has_generics);
}

template <typename T>
void *TypeResolver::harness_read_data_adapter(ReadContext &ctx) {
  T value = Serializer<T>::read_data(ctx);
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return nullptr;
  }
  return new T(std::move(value));
}

template <typename T>
void *TypeResolver::harness_read_data_adapter_abstract(ReadContext &ctx) {
  ctx.set_error(Error::type_error("Cannot deserialize abstract type"));
  return nullptr;
}

template <typename T>
void *TypeResolver::harness_read_compatible_adapter(ReadContext &ctx,
                                                    const TypeInfo *ti) {
  T value = Serializer<T>::read_compatible(ctx, ti);
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return nullptr;
  }
  return new T(std::move(value));
}

template <typename T>
void *TypeResolver::harness_read_compatible_adapter_abstract(ReadContext &ctx,
                                                             const TypeInfo *) {
  ctx.set_error(Error::type_error("Cannot deserialize abstract type"));
  return nullptr;
}

template <typename T>
Result<std::vector<FieldInfo>, Error>
TypeResolver::harness_struct_sorted_fields(TypeResolver &resolver) {
  static_assert(is_fory_serializable_v<T>,
                "harness_struct_sorted_fields requires FORY_STRUCT types");
  using MetaDesc = decltype(fory_field_info(std::declval<T>()));
  constexpr size_t field_count = MetaDesc::Size;
  FORY_TRY(fields, detail::build_field_infos_with_resolver<T>(
                       resolver, std::make_index_sequence<field_count>{}));
  auto sorted = TypeMeta::sort_field_infos(std::move(fields));
  return sorted;
}

template <typename T>
Result<std::vector<FieldInfo>, Error>
TypeResolver::harness_empty_sorted_fields(TypeResolver &) {
  return std::vector<FieldInfo>{};
}

inline std::string TypeResolver::make_name_key(const std::string &ns,
                                               const std::string &name) {
  std::string key;
  key.reserve(ns.size() + 1 + name.size());
  key.append(ns);
  key.push_back('\0');
  key.append(name);
  return key;
}

inline uint64_t TypeResolver::make_user_type_key(uint32_t type_id,
                                                 uint32_t user_type_id) {
  return (static_cast<uint64_t>(type_id) << 32) |
         static_cast<uint64_t>(user_type_id);
}

inline Result<TypeInfo *, Error>
TypeResolver::register_type_internal(uint64_t ctid,
                                     std::unique_ptr<TypeInfo> info) {
  if (!info || !info->harness.valid()) {
    return Unexpected(
        Error::invalid("TypeInfo or harness is invalid during registration"));
  }

  // Validate all uniqueness constraints before mutating resolver state so
  // failed registration leaves no partial entries behind.
  TypeInfo *raw_ptr = info.get();
  const bool is_internal = ::fory::is_internal_type(raw_ptr->type_id);
  const bool has_user_type_key =
      !raw_ptr->register_by_name && raw_ptr->user_type_id != kInvalidUserTypeId;
  uint64_t user_type_key = 0;
  std::string name_key;

  if (is_internal) {
    TypeInfo *existing =
        type_info_by_id_.get_or_default(raw_ptr->type_id, nullptr);
    if (existing != nullptr) {
      return Unexpected(Error::invalid("Type id already registered: " +
                                       std::to_string(raw_ptr->type_id)));
    }
  } else if (has_user_type_key) {
    user_type_key = make_user_type_key(raw_ptr->type_id, raw_ptr->user_type_id);
    TypeInfo *existing =
        user_type_info_by_id_.get_or_default(user_type_key, nullptr);
    if (existing != nullptr) {
      return Unexpected(Error::invalid(
          "Type id already registered: " + std::to_string(raw_ptr->type_id) +
          "/" + std::to_string(raw_ptr->user_type_id)));
    }
  }

  if (raw_ptr->register_by_name) {
    name_key = make_name_key(raw_ptr->namespace_name, raw_ptr->type_name);
    auto it = type_info_by_name_.find(name_key);
    if (it != type_info_by_name_.end()) {
      return Unexpected(Error::invalid(
          "Type already registered for namespace '" + raw_ptr->namespace_name +
          "' and name '" + raw_ptr->type_name + "'"));
    }
  }

  // Commit all state after validation passes.
  type_infos_.push_back(std::move(info));
  TypeInfo *stored_ptr = type_infos_.back().get();
  type_info_by_ctid_.put(ctid, stored_ptr);

  if (is_internal) {
    type_info_by_id_.put(stored_ptr->type_id, stored_ptr);
  } else if (has_user_type_key) {
    user_type_info_by_id_.put(user_type_key, stored_ptr);
  }

  if (stored_ptr->register_by_name) {
    type_info_by_name_[name_key] = stored_ptr;
  }

  return stored_ptr;
}

inline void
TypeResolver::register_type_internal_runtime(const std::type_index &type_index,
                                             TypeInfo *info) {
  // For runtime polymorphic lookups (smart pointers)
  type_info_by_runtime_type_[type_index] = info;
}

inline Result<const TypeInfo *, Error>
TypeResolver::get_type_info_by_id(uint32_t type_id) const {
  TypeInfo *info = type_info_by_id_.get_or_default(type_id, nullptr);
  if (info != nullptr) {
    return info;
  }
  return Unexpected(Error::type_error("TypeInfo not found for type_id: " +
                                      std::to_string(type_id)));
}

inline Result<const TypeInfo *, Error>
TypeResolver::get_user_type_info_by_id(uint32_t type_id,
                                       uint32_t user_type_id) const {
  uint64_t key = make_user_type_key(type_id, user_type_id);
  TypeInfo *info = user_type_info_by_id_.get_or_default(key, nullptr);
  if (info != nullptr) {
    return info;
  }
  return Unexpected(Error::type_error(
      "TypeInfo not found for type_id: " + std::to_string(type_id) +
      ", user_type_id: " + std::to_string(user_type_id)));
}

inline Result<const TypeInfo *, Error>
TypeResolver::get_type_info_by_name(const std::string &ns,
                                    const std::string &type_name) const {
  auto key = make_name_key(ns, type_name);
  auto it = type_info_by_name_.find(key);
  if (it != type_info_by_name_.end()) {
    return it->second;
  }
  return Unexpected(Error::type_error("TypeInfo not found for type: " + ns +
                                      "." + type_name));
}

// ============================================================================
// WriteContext template implementations (defined here because they need
// TypeResolver to be complete)
// ============================================================================

template <typename E> Result<void, Error> WriteContext::write_enum_type_info() {
  FORY_TRY(type_info, type_resolver_->get_type_info<E>());
  return write_enum_type_info(type_info);
}

} // namespace serialization
} // namespace fory
