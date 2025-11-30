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

#include "fory/meta/field_info.h"
#include "fory/meta/type_traits.h"
#include "fory/serialization/config.h"
#include "fory/serialization/serializer.h"
#include "fory/serialization/serializer_traits.h"
#include "fory/serialization/type_info.h"
#include "fory/type/type.h"
#include "fory/util/buffer.h"
#include "fory/util/error.h"
#include "fory/util/logging.h"
#include "fory/util/result.h"

namespace fory {
namespace serialization {

// Forward declarations
class Fory;
class TypeResolver;
class WriteContext;
class ReadContext;

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

/// Represents type information including type ID, nullability, and generics
class FieldType {
public:
  uint32_t type_id;
  bool nullable;
  std::vector<FieldType> generics;

  FieldType() : type_id(0), nullable(false) {}

  FieldType(uint32_t tid, bool null, std::vector<FieldType> gens = {})
      : type_id(tid), nullable(null), generics(std::move(gens)) {}

  /// Write field type to buffer
  /// @param buffer Target buffer
  /// @param write_flag Whether to write nullability flag (for nested types)
  /// @param nullable_val Nullability to write if write_flag is true
  Result<void, Error> write_to(Buffer &buffer, bool write_flag,
                               bool nullable_val) const;

  /// Read field type from buffer
  /// @param buffer Source buffer
  /// @param read_flag Whether to read nullability flag (for nested types)
  /// @param nullable_val Nullability if read_flag is false
  static Result<FieldType, Error> read_from(Buffer &buffer, bool read_flag,
                                            bool nullable_val);

  bool operator==(const FieldType &other) const {
    return type_id == other.type_id && nullable == other.nullable &&
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
  int16_t field_id;       // Assigned during deserialization (-1 = skip)
  std::string field_name; // Field name
  FieldType field_type;   // Field type information

  FieldInfo() : field_id(-1) {}

  FieldInfo(std::string name, FieldType type)
      : field_id(-1), field_name(std::move(name)), field_type(std::move(type)) {
  }

  /// Write field info to buffer (for serialization)
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
  int64_t hash;                       // Type hash for fast comparison
  uint32_t type_id;                   // Type ID (for non-named registration)
  std::string namespace_str;          // Namespace (for named registration)
  std::string type_name;              // Type name (for named registration)
  bool register_by_name;              // Whether registered by name
  std::vector<FieldInfo> field_infos; // Field information

  TypeMeta() : hash(0), type_id(0), register_by_name(false) {}

  /// Create TypeMeta from field information
  static TypeMeta from_fields(uint32_t tid, const std::string &ns,
                              const std::string &name, bool by_name,
                              std::vector<FieldInfo> fields);

  /// Write type meta to buffer (for serialization)
  Result<std::vector<uint8_t>, Error> to_bytes() const;

  /// Read type meta from buffer (for deserialization)
  /// @param buffer Source buffer
  /// @param local_type_info Local type information (for field ID assignment)
  static Result<std::shared_ptr<TypeMeta>, Error>
  from_bytes(Buffer &buffer, const TypeMeta *local_type_info);

  /// Read type meta from buffer with pre-read header
  /// @param buffer Source buffer (positioned after header)
  /// @param header Pre-read 8-byte header
  static Result<std::shared_ptr<TypeMeta>, Error>
  from_bytes_with_header(Buffer &buffer, int64_t header);

  /// Skip type meta in buffer without parsing
  static Result<void, Error> skip_bytes(Buffer &buffer, int64_t header);

  /// Check struct version consistency
  static Result<void, Error> check_struct_version(int32_t read_version,
                                                  int32_t local_version,
                                                  const std::string &type_name);

  /// Get sorted field infos (sorted according to xlang spec)
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

  /// Compute struct version hash from field metadata.
  ///
  /// This mirrors Rust's
  /// `compute_struct_version_hash` in
  /// rust/fory-derive/src/object/util.rs:
  ///
  ///   - Build a fingerprint string using
  ///     `snake_case(field_name),effective_type_id,nullable;`
  ///     for each field in the sorted order defined by the
  ///     xlang spec (primitive, nullable primitive, internal,
  ///     list, set, map, other).
  ///   - Hash the fingerprint with MurmurHash3_x64_128 using
  ///     seed 47, then take the low 32 bits as signed i32.
  ///
  /// Java's ObjectSerializer.computeStructHash uses the same
  /// algorithm, so this function provides the cross-language
  /// struct version ID used by class version checking.
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
  static FieldType build(bool nullable) {
    FieldType inner = FieldTypeBuilder<Inner>::build(true);
    inner.nullable = true;
    return inner;
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_shared_ptr_v<decay_t<T>>>> {
  using Inner = typename decay_t<T>::element_type;
  static FieldType build(bool nullable) {
    FieldType inner = FieldTypeBuilder<Inner>::build(true);
    inner.nullable = true;
    return inner;
  }
};

template <typename T>
struct FieldTypeBuilder<T, std::enable_if_t<is_unique_ptr_v<decay_t<T>>>> {
  using Inner = typename decay_t<T>::element_type;
  static FieldType build(bool nullable) {
    FieldType inner = FieldTypeBuilder<Inner>::build(true);
    inner.nullable = true;
    return inner;
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

template <typename T>
struct FieldTypeBuilder<
    T, std::enable_if_t<
           !is_optional_v<decay_t<T>> && !is_shared_ptr_v<decay_t<T>> &&
           !is_unique_ptr_v<decay_t<T>> && !is_vector_v<decay_t<T>> &&
           !is_set_like_v<decay_t<T>> && !is_map_like_v<decay_t<T>> &&
           !is_string_view_v<decay_t<T>> &&
           has_serializer_type_id_v<decay_t<T>>>> {
  using Decayed = decay_t<T>;
  static FieldType build(bool nullable) {
    return FieldType(to_type_id(Serializer<Decayed>::type_id), nullable);
  }
};

template <typename T, size_t Index> struct FieldInfoBuilder {
  static FieldInfo build() {
    const auto meta = ForyFieldInfo(T{});
    const auto field_names = decltype(meta)::Names;
    const auto field_ptrs = decltype(meta)::Ptrs;

    std::string field_name(field_names[Index]);
    const auto field_ptr = std::get<Index>(field_ptrs);
    using RawFieldType =
        typename meta::RemoveMemberPointerCVRefT<decltype(field_ptr)>;
    using ActualFieldType =
        std::remove_cv_t<std::remove_reference_t<RawFieldType>>;

    FieldType field_type = FieldTypeBuilder<ActualFieldType>::build(false);
    return FieldInfo(std::move(field_name), std::move(field_type));
  }
};

template <typename T, size_t... Indices>
std::vector<FieldInfo> build_field_infos(std::index_sequence<Indices...>) {
  std::vector<FieldInfo> fields;
  fields.reserve(sizeof...(Indices));
  (fields.push_back(FieldInfoBuilder<T, Indices>::build()), ...);
  return fields;
}

} // namespace detail

// ============================================================================
// Helper function to encode meta strings at registration time
// ============================================================================

/// Encode a meta string for namespace or type_name using the appropriate
/// encoder. This is called during registration to pre-compute the encoded form.
Result<std::shared_ptr<CachedMetaString>, Error>
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
  template <typename T>
  const absl::flat_hash_map<std::string, size_t> &field_name_to_index();

  template <typename T>
  Result<std::shared_ptr<TypeInfo>, Error> get_struct_type_info();

  uint32_t get_type_id(const TypeInfo &info) const;

  template <typename T> Result<uint32_t, Error> get_type_id();

  /// Get type info by type ID (for non-namespaced types)
  std::shared_ptr<TypeInfo> get_type_info_by_id(uint32_t type_id) const;

  /// Get type info by namespace and type name (for namespaced types)
  std::shared_ptr<TypeInfo>
  get_type_info_by_name(const std::string &ns,
                        const std::string &type_name) const;

  /// Read type information dynamically from ReadContext based on type ID.
  ///
  /// This method handles reading type info for various type categories:
  /// - COMPATIBLE_STRUCT/NAMED_COMPATIBLE_STRUCT: reads meta index
  /// - NAMED_ENUM/NAMED_STRUCT/NAMED_EXT: reads namespace and type name (if not
  /// sharing meta)
  /// - Other types: looks up by type ID
  ///
  /// @return TypeInfo pointer if found, error otherwise
  Result<std::shared_ptr<TypeInfo>, Error> read_any_typeinfo(ReadContext &ctx);

  /// Read type info from stream with explicit local TypeMeta for field_id
  /// assignment
  Result<std::shared_ptr<TypeInfo>, Error>
  read_any_typeinfo(ReadContext &ctx, const TypeMeta *local_type_meta);

  /// Get TypeInfo by type_index (used for looking up registered types)
  /// @return const pointer to TypeInfo if found, error otherwise
  Result<const TypeInfo *, Error>
  get_type_info(const std::type_index &type_index) const;

  /// Get TypeInfo by compile-time type ID (fast path for template types)
  /// Works for enums, structs, and any registered type.
  /// @return shared_ptr to TypeInfo if found, nullptr otherwise
  template <typename T> std::shared_ptr<TypeInfo> get_type_info() const;

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
  Result<std::shared_ptr<TypeResolver>, Error> build_final_type_resolver();

  /// Clones the TypeResolver for use in a new context.
  ///
  /// This method creates a shallow clone of the TypeResolver. The clone shares
  /// the same TypeInfo objects as the original but is a separate instance.
  ///
  /// @return A new TypeResolver instance
  std::shared_ptr<TypeResolver> clone() const;

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
  static Result<std::shared_ptr<TypeInfo>, Error>
  build_struct_type_info(uint32_t type_id, std::string ns,
                         std::string type_name, bool register_by_name);

  template <typename T>
  static Result<std::shared_ptr<TypeInfo>, Error>
  build_enum_type_info(uint32_t type_id, std::string ns, std::string type_name,
                       bool register_by_name);

  template <typename T>
  static Result<std::shared_ptr<TypeInfo>, Error>
  build_ext_type_info(uint32_t type_id, std::string ns, std::string type_name,
                      bool register_by_name);

  template <typename T> static Harness make_struct_harness();

  template <typename T> static Harness make_serializer_harness();

  template <typename T>
  static Result<void, Error>
  harness_write_adapter(const void *value, WriteContext &ctx,
                        bool write_ref_info, bool write_type_info,
                        bool has_generics);

  template <typename T>
  static Result<void *, Error> harness_read_adapter(ReadContext &ctx,
                                                    bool read_ref_info,
                                                    bool read_type_info);

  template <typename T>
  static Result<void, Error> harness_write_data_adapter(const void *value,
                                                        WriteContext &ctx,
                                                        bool has_generics);

  template <typename T>
  static Result<void *, Error> harness_read_data_adapter(ReadContext &ctx);

  template <typename T>
  static Result<std::vector<FieldInfo>, Error>
  harness_struct_sorted_fields(TypeResolver &resolver);

  template <typename T>
  static Result<std::vector<FieldInfo>, Error>
  harness_empty_sorted_fields(TypeResolver &resolver);

  static std::string make_name_key(const std::string &ns,
                                   const std::string &name);

  Result<void, Error> register_type_internal(uint64_t ctid,
                                             std::shared_ptr<TypeInfo> info);

  // For runtime polymorphic lookups (smart pointers with dynamic types)
  Result<void, Error>
  register_type_internal_runtime(const std::type_index &type_index,
                                 std::shared_ptr<TypeInfo> info);

  void check_registration_thread();

  void register_builtin_types();

  bool compatible_;
  bool xlang_;
  bool check_struct_version_;
  bool track_ref_;

  std::thread::id registration_thread_id_;
  bool finalized_;

  // Primary map using compile-time type ID (uint64_t) - fast for template
  // lookups
  absl::flat_hash_map<uint64_t, std::shared_ptr<TypeInfo>> type_info_by_ctid_;
  absl::flat_hash_map<uint32_t, std::shared_ptr<TypeInfo>> type_info_by_id_;
  absl::flat_hash_map<std::string, std::shared_ptr<TypeInfo>>
      type_info_by_name_;
  absl::flat_hash_map<uint64_t, std::shared_ptr<TypeInfo>> partial_type_infos_;

  // For runtime polymorphic lookups (smart pointers) - uses std::type_index
  absl::flat_hash_map<std::type_index, std::shared_ptr<TypeInfo>>
      type_info_by_runtime_type_;
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

template <typename T> const TypeMeta &TypeResolver::struct_meta() {
  constexpr uint64_t ctid = type_index<T>();
  auto it = type_info_by_ctid_.find(ctid);
  FORY_CHECK(it != type_info_by_ctid_.end()) << "Type not registered";
  FORY_CHECK(it->second && it->second->type_meta)
      << "Type metadata not initialized for requested struct";
  return *it->second->type_meta;
}

template <typename T> TypeMeta TypeResolver::clone_struct_meta() {
  constexpr uint64_t ctid = type_index<T>();
  auto it = type_info_by_ctid_.find(ctid);
  FORY_CHECK(it != type_info_by_ctid_.end()) << "Type not registered";
  FORY_CHECK(it->second && it->second->type_meta)
      << "Type metadata not initialized for requested struct";
  return *it->second->type_meta;
}

template <typename T>
const std::vector<size_t> &TypeResolver::sorted_indices() {
  constexpr uint64_t ctid = type_index<T>();
  auto it = type_info_by_ctid_.find(ctid);
  FORY_CHECK(it != type_info_by_ctid_.end()) << "Type not registered";
  return it->second->sorted_indices;
}

template <typename T>
const absl::flat_hash_map<std::string, size_t> &
TypeResolver::field_name_to_index() {
  constexpr uint64_t ctid = type_index<T>();
  auto it = type_info_by_ctid_.find(ctid);
  FORY_CHECK(it != type_info_by_ctid_.end()) << "Type not registered";
  return it->second->name_to_index;
}

template <typename T>
Result<std::shared_ptr<TypeInfo>, Error> TypeResolver::get_struct_type_info() {
  static_assert(is_fory_serializable_v<T>,
                "get_struct_type_info requires FORY_STRUCT types");
  // Use compile-time type ID (uint64_t key) for fast lookup
  constexpr uint64_t ctid = type_index<T>();
  auto it = type_info_by_ctid_.find(ctid);
  if (FORY_PREDICT_TRUE(it != type_info_by_ctid_.end())) {
    return it->second;
  }
  return Unexpected(Error::type_error(
      "Type not registered. All struct/enum/ext types must be explicitly "
      "registered before serialization."));
}

template <typename T>
std::shared_ptr<TypeInfo> TypeResolver::get_type_info() const {
  // Use compile-time type ID (uint64_t key) for fast lookup
  constexpr uint64_t ctid = type_index<T>();
  auto it = type_info_by_ctid_.find(ctid);
  if (FORY_PREDICT_TRUE(it != type_info_by_ctid_.end())) {
    return it->second;
  }
  return nullptr;
}

inline uint32_t TypeResolver::get_type_id(const TypeInfo &info) const {
  // In the xlang spec the numeric type id used on the wire for
  // structs is the "actual" type id computed by the resolver
  // (see Rust's `struct_::actual_type_id`). That value is already
  // stored in `info.type_id` for both id-based and name-based
  // registrations. Unlike the previous implementation we must not
  // apply another layer of shifting/tagging here, otherwise the
  // local type id will no longer match the id written by Java/Rust
  // and struct reads will fail with `TypeMismatch`.
  //
  // Rust equivalent:
  //   let type_id = T::fory_get_type_id(type_resolver)?;
  //   context.writer.write_varuint32(type_id);
  // and on read:
  //   ensure!(local_type_id == remote_type_id, Error::type_mismatch(...));
  //
  // So we simply return the stored actual type id.
  (void)this; // suppress unused warning in some builds
  return info.type_id;
}

template <typename T> Result<uint32_t, Error> TypeResolver::get_type_id() {
  FORY_TRY(info, get_struct_type_info<T>());
  if (!info->type_meta) {
    return Unexpected(Error::type_error(
        "Type metadata not initialized for requested struct"));
  }
  return get_type_id(*info);
}

template <typename T>
Result<void, Error> TypeResolver::register_by_id(uint32_t type_id) {
  check_registration_thread();
  if (type_id == 0) {
    return Unexpected(
        Error::invalid("type_id must be non-zero for register_by_id"));
  }

  constexpr uint64_t ctid = type_index<T>();

  if constexpr (is_fory_serializable_v<T>) {
    // Encode type_id: shift left by 8 bits and add type category in low byte
    uint32_t actual_type_id =
        compatible_
            ? (type_id << 8) + static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT)
            : (type_id << 8) + static_cast<uint32_t>(TypeId::STRUCT);

    FORY_TRY(info, build_struct_type_info<T>(actual_type_id, "", "", false));
    if (!info->harness.valid()) {
      return Unexpected(
          Error::invalid("Harness for registered type is incomplete"));
    }

    partial_type_infos_[ctid] = info;
    // Also register for runtime polymorphic lookups (smart pointers)
    type_info_by_runtime_type_[std::type_index(typeid(T))] = info;
    return register_type_internal(ctid, std::move(info));
  } else if constexpr (std::is_enum_v<T>) {
    uint32_t actual_type_id =
        (type_id << 8) + static_cast<uint32_t>(TypeId::ENUM);

    FORY_TRY(info, build_enum_type_info<T>(actual_type_id, "", "", false));
    if (!info->harness.valid()) {
      return Unexpected(
          Error::invalid("Harness for registered enum type is incomplete"));
    }

    partial_type_infos_[ctid] = info;
    type_info_by_runtime_type_[std::type_index(typeid(T))] = info;
    return register_type_internal(ctid, std::move(info));
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
        compatible_ ? static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT)
                    : static_cast<uint32_t>(TypeId::NAMED_STRUCT);

    FORY_TRY(info,
             build_struct_type_info<T>(actual_type_id, ns, type_name, true));
    if (!info->harness.valid()) {
      return Unexpected(
          Error::invalid("Harness for registered type is incomplete"));
    }

    partial_type_infos_[ctid] = info;
    type_info_by_runtime_type_[std::type_index(typeid(T))] = info;
    return register_type_internal(ctid, std::move(info));
  } else if constexpr (std::is_enum_v<T>) {
    uint32_t actual_type_id = static_cast<uint32_t>(TypeId::NAMED_ENUM);
    FORY_TRY(info,
             build_enum_type_info<T>(actual_type_id, ns, type_name, true));
    if (!info->harness.valid()) {
      return Unexpected(
          Error::invalid("Harness for registered enum type is incomplete"));
    }

    partial_type_infos_[ctid] = info;
    type_info_by_runtime_type_[std::type_index(typeid(T))] = info;
    return register_type_internal(ctid, std::move(info));
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
  if (type_id == 0) {
    return Unexpected(
        Error::invalid("type_id must be non-zero for register_ext_type_by_id"));
  }

  constexpr uint64_t ctid = type_index<T>();

  // Encode type_id: shift left by 8 bits and add type category in low byte
  uint32_t actual_type_id = (type_id << 8) + static_cast<uint32_t>(TypeId::EXT);

  FORY_TRY(info, build_ext_type_info<T>(actual_type_id, "", "", false));

  partial_type_infos_[ctid] = info;
  type_info_by_runtime_type_[std::type_index(typeid(T))] = info;
  return register_type_internal(ctid, std::move(info));
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
  FORY_TRY(info, build_ext_type_info<T>(actual_type_id, ns, type_name, true));

  partial_type_infos_[ctid] = info;
  type_info_by_runtime_type_[std::type_index(typeid(T))] = info;
  return register_type_internal(ctid, std::move(info));
}

template <typename T>
Result<std::shared_ptr<TypeInfo>, Error>
TypeResolver::build_struct_type_info(uint32_t type_id, std::string ns,
                                     std::string type_name,
                                     bool register_by_name) {
  static_assert(is_fory_serializable_v<T>,
                "build_struct_type_info requires FORY_STRUCT types");

  if (type_id == 0) {
    type_id = static_cast<uint32_t>(TypeId::STRUCT);
  }

  auto entry = std::make_shared<TypeInfo>();
  entry->type_id = type_id;
  entry->namespace_name = std::move(ns);
  entry->register_by_name = register_by_name;
  entry->is_external = false;

  const auto meta_desc = ForyFieldInfo(T{});
  constexpr size_t field_count = decltype(meta_desc)::Size;
  const auto field_names = decltype(meta_desc)::Names;

  std::string resolved_name = type_name;
  if (resolved_name.empty()) {
    resolved_name = std::string(decltype(meta_desc)::Name);
  }
  if (register_by_name && resolved_name.empty()) {
    return Unexpected(Error::invalid(
        "Resolved type name must be non-empty when register_by_name is true"));
  }
  entry->type_name = std::move(resolved_name);

  entry->name_to_index.reserve(field_count);
  for (size_t i = 0; i < field_count; ++i) {
    entry->name_to_index.emplace(std::string(field_names[i]), i);
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

  TypeMeta meta =
      TypeMeta::from_fields(type_id, entry->namespace_name, entry->type_name,
                            register_by_name, std::move(sorted_fields));

  FORY_TRY(type_def, meta.to_bytes());
  entry->type_def = std::move(type_def);

  Buffer buffer(entry->type_def.data(),
                static_cast<uint32_t>(entry->type_def.size()), false);
  buffer.WriterIndex(static_cast<uint32_t>(entry->type_def.size()));
  FORY_TRY(parsed_meta, TypeMeta::from_bytes(buffer, nullptr));
  entry->type_meta = std::move(parsed_meta);
  entry->harness = make_struct_harness<T>();

  // Pre-encode namespace and type_name for efficient writing
  FORY_TRY(enc_ns, encode_meta_string(entry->namespace_name, true));
  entry->encoded_namespace = std::move(enc_ns);
  FORY_TRY(enc_tn, encode_meta_string(entry->type_name, false));
  entry->encoded_type_name = std::move(enc_tn);

  return entry;
}

template <typename T>
Result<std::shared_ptr<TypeInfo>, Error>
TypeResolver::build_enum_type_info(uint32_t type_id, std::string ns,
                                   std::string type_name,
                                   bool register_by_name) {
  static_assert(std::is_enum_v<T>, "build_enum_type_info requires enum types");

  if (register_by_name && type_name.empty()) {
    return Unexpected(Error::invalid(
        "type_name must be non-empty when register_by_name is true"));
  }

  auto entry = std::make_shared<TypeInfo>();
  entry->type_id = type_id;
  entry->namespace_name = std::move(ns);
  entry->register_by_name = register_by_name;
  entry->is_external = false;

  // When a user explicitly provides a type_name via registration, Java stores
  // and writes that exact name. The ENUM_PREFIX "2" is only added when Java
  // auto-generates the type name from the class itself (via encodePkgAndClass).
  // Since C++ users always explicitly provide the type name, we should NOT
  // add the prefix.
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
Result<std::shared_ptr<TypeInfo>, Error>
TypeResolver::build_ext_type_info(uint32_t type_id, std::string ns,
                                  std::string type_name,
                                  bool register_by_name) {
  auto entry = std::make_shared<TypeInfo>();
  entry->type_id = type_id;
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
  TypeMeta meta =
      TypeMeta::from_fields(type_id, entry->namespace_name, entry->type_name,
                            register_by_name, std::vector<FieldInfo>{});
  FORY_TRY(type_def, meta.to_bytes());
  entry->type_def = std::move(type_def);

  return entry;
}

template <typename T> Harness TypeResolver::make_struct_harness() {
  return Harness(&TypeResolver::harness_write_adapter<T>,
                 &TypeResolver::harness_read_adapter<T>,
                 &TypeResolver::harness_write_data_adapter<T>,
                 &TypeResolver::harness_read_data_adapter<T>,
                 &TypeResolver::harness_struct_sorted_fields<T>);
}

template <typename T> Harness TypeResolver::make_serializer_harness() {
  return Harness(&TypeResolver::harness_write_adapter<T>,
                 &TypeResolver::harness_read_adapter<T>,
                 &TypeResolver::harness_write_data_adapter<T>,
                 &TypeResolver::harness_read_data_adapter<T>,
                 &TypeResolver::harness_empty_sorted_fields<T>);
}

template <typename T>
Result<void, Error>
TypeResolver::harness_write_adapter(const void *value, WriteContext &ctx,
                                    bool write_ref_info, bool write_type_info,
                                    bool has_generics) {
  (void)has_generics;
  const T *ptr = static_cast<const T *>(value);
  return Serializer<T>::write(*ptr, ctx, write_ref_info, write_type_info);
}

template <typename T>
Result<void *, Error> TypeResolver::harness_read_adapter(ReadContext &ctx,
                                                         bool read_ref_info,
                                                         bool read_type_info) {
  FORY_TRY(value, Serializer<T>::read(ctx, read_ref_info, read_type_info));
  T *ptr = new T(std::move(value));
  return ptr;
}

template <typename T>
Result<void, Error>
TypeResolver::harness_write_data_adapter(const void *value, WriteContext &ctx,
                                         bool has_generics) {
  const T *ptr = static_cast<const T *>(value);
  return Serializer<T>::write_data_generic(*ptr, ctx, has_generics);
}

template <typename T>
Result<void *, Error>
TypeResolver::harness_read_data_adapter(ReadContext &ctx) {
  FORY_TRY(value, Serializer<T>::read_data(ctx));
  T *ptr = new T(std::move(value));
  return ptr;
}

template <typename T>
Result<std::vector<FieldInfo>, Error>
TypeResolver::harness_struct_sorted_fields(TypeResolver &) {
  static_assert(is_fory_serializable_v<T>,
                "harness_struct_sorted_fields requires FORY_STRUCT types");
  const auto meta_desc = ForyFieldInfo(T{});
  constexpr size_t field_count = decltype(meta_desc)::Size;
  auto fields =
      detail::build_field_infos<T>(std::make_index_sequence<field_count>{});
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

inline Result<void, Error>
TypeResolver::register_type_internal(uint64_t ctid,
                                     std::shared_ptr<TypeInfo> info) {
  if (!info || !info->harness.valid()) {
    return Unexpected(
        Error::invalid("TypeInfo or harness is invalid during registration"));
  }

  type_info_by_ctid_[ctid] = info;

  if (info->type_id != 0 && !info->register_by_name) {
    auto it = type_info_by_id_.find(info->type_id);
    if (it != type_info_by_id_.end() && it->second.get() != info.get()) {
      return Unexpected(Error::invalid("Type id already registered: " +
                                       std::to_string(info->type_id)));
    }
    type_info_by_id_[info->type_id] = info;
  }

  if (info->register_by_name) {
    auto key = make_name_key(info->namespace_name, info->type_name);
    auto it = type_info_by_name_.find(key);
    if (it != type_info_by_name_.end() && it->second.get() != info.get()) {
      return Unexpected(Error::invalid(
          "Type already registered for namespace '" + info->namespace_name +
          "' and name '" + info->type_name + "'"));
    }
    type_info_by_name_[key] = info;
  }

  return Result<void, Error>();
}

inline Result<void, Error>
TypeResolver::register_type_internal_runtime(const std::type_index &type_index,
                                             std::shared_ptr<TypeInfo> info) {
  // For runtime polymorphic lookups (smart pointers)
  type_info_by_runtime_type_[type_index] = info;
  return Result<void, Error>();
}

inline std::shared_ptr<TypeInfo>
TypeResolver::get_type_info_by_id(uint32_t type_id) const {
  auto it = type_info_by_id_.find(type_id);
  if (it != type_info_by_id_.end()) {
    return it->second;
  }
  return nullptr;
}

inline std::shared_ptr<TypeInfo>
TypeResolver::get_type_info_by_name(const std::string &ns,
                                    const std::string &type_name) const {
  auto key = make_name_key(ns, type_name);
  auto it = type_info_by_name_.find(key);
  if (it != type_info_by_name_.end()) {
    return it->second;
  }
  return nullptr;
}

// ============================================================================
// WriteContext template implementations (defined here because they need
// TypeResolver to be complete)
// ============================================================================

template <typename E> Result<void, Error> WriteContext::write_enum_typeinfo() {
  auto type_info = type_resolver_->get_type_info<E>();
  if (type_info) {
    return write_enum_typeinfo(type_info.get());
  }
  // Enum not registered, write plain ENUM type id
  buffer_.WriteVarUint32(static_cast<uint32_t>(TypeId::ENUM));
  return Result<void, Error>();
}

} // namespace serialization
} // namespace fory
