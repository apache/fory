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

#include "fory/meta/field_info.h"
#include "fory/meta/type_index.h"
#include "fory/meta/type_traits.h"
#include "fory/type/type.h"
#include <array>
#include <deque>
#include <forward_list>
#include <list>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <tuple>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <variant>
#include <vector>

namespace fory {
namespace serialization {

// Forward declarations for trait detection
template <typename T, typename Enable = void> struct Serializer;
template <typename T, typename Enable = void> struct SerializationMeta;

// ============================================================================
// Container Type Detection
// ============================================================================

/// Detect map-like containers (has key_type and mapped_type)
template <typename T, typename = void> struct is_map_like : std::false_type {};

template <typename T>
struct is_map_like<T,
                   std::void_t<typename T::key_type, typename T::mapped_type>>
    : std::true_type {};

template <typename T>
inline constexpr bool is_map_like_v = is_map_like<T>::value;

/// Detect set-like containers (has key_type but not mapped_type)
template <typename T, typename = void> struct is_set_like : std::false_type {};

template <typename T>
struct is_set_like<
    T, std::void_t<typename T::key_type, std::enable_if_t<!is_map_like_v<T>>>>
    : std::true_type {};

template <typename T>
inline constexpr bool is_set_like_v = is_set_like<T>::value;

/// Detect std::vector
template <typename T> struct is_vector : std::false_type {};

template <typename T, typename Alloc>
struct is_vector<std::vector<T, Alloc>> : std::true_type {};

template <typename T> inline constexpr bool is_vector_v = is_vector<T>::value;

/// Detect std::list
template <typename T> struct is_list : std::false_type {};

template <typename T, typename Alloc>
struct is_list<std::list<T, Alloc>> : std::true_type {};

template <typename T> inline constexpr bool is_list_v = is_list<T>::value;

/// Detect std::deque
template <typename T> struct is_deque : std::false_type {};

template <typename T, typename Alloc>
struct is_deque<std::deque<T, Alloc>> : std::true_type {};

template <typename T> inline constexpr bool is_deque_v = is_deque<T>::value;

/// Detect std::forward_list
template <typename T> struct is_forward_list : std::false_type {};

template <typename T, typename Alloc>
struct is_forward_list<std::forward_list<T, Alloc>> : std::true_type {};

template <typename T>
inline constexpr bool is_forward_list_v = is_forward_list<T>::value;

/// Detect std::optional
template <typename T> struct is_optional : std::false_type {};

template <typename T> struct is_optional<std::optional<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_optional_v = is_optional<T>::value;

/// Detect std::tuple
template <typename T> struct is_tuple : std::false_type {};

template <typename... Ts>
struct is_tuple<std::tuple<Ts...>> : std::true_type {};

template <typename T> inline constexpr bool is_tuple_v = is_tuple<T>::value;

/// Detect std::variant
template <typename T> struct is_variant : std::false_type {};

template <typename... Ts>
struct is_variant<std::variant<Ts...>> : std::true_type {};

template <typename T> inline constexpr bool is_variant_v = is_variant<T>::value;

/// Detect std::weak_ptr
template <typename T> struct is_weak_ptr : std::false_type {};

template <typename T> struct is_weak_ptr<std::weak_ptr<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_weak_ptr_v = is_weak_ptr<T>::value;

// ============================================================================
// Nullable Type Detection and Helpers
// ============================================================================

/// Detect types that can hold null values (optional, shared_ptr, unique_ptr,
/// weak_ptr)
template <typename T> struct is_nullable : std::false_type {};

template <typename T> struct is_nullable<std::optional<T>> : std::true_type {};

template <typename T>
struct is_nullable<std::shared_ptr<T>> : std::true_type {};

template <typename T, typename D>
struct is_nullable<std::unique_ptr<T, D>> : std::true_type {};

template <typename T> struct is_nullable<std::weak_ptr<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_nullable_v = is_nullable<T>::value;

/// Check if a nullable value is null
template <typename T> inline bool is_null_value(const T &value) {
  if constexpr (is_optional_v<T>) {
    return !value.has_value();
  } else if constexpr (is_weak_ptr_v<T>) {
    return value.expired();
  } else {
    // shared_ptr, unique_ptr - check against nullptr
    return value == nullptr;
  }
}

/// Get the inner/element type of a nullable type
template <typename T> struct nullable_element_type {
  using type = T;
};

template <typename T> struct nullable_element_type<std::optional<T>> {
  using type = T;
};

template <typename T> struct nullable_element_type<std::shared_ptr<T>> {
  using type = T;
};

template <typename T, typename D>
struct nullable_element_type<std::unique_ptr<T, D>> {
  using type = T;
};

template <typename T> struct nullable_element_type<std::weak_ptr<T>> {
  using type = T;
};

template <typename T>
using nullable_element_t = typename nullable_element_type<T>::type;

/// Dereference a nullable value to get the inner value
/// Note: Caller must ensure the value is not null before calling this
template <typename T>
inline const nullable_element_t<T> &deref_nullable(const T &value) {
  if constexpr (is_optional_v<T>) {
    return *value;
  } else if constexpr (is_weak_ptr_v<T>) {
    // For weak_ptr, we need to lock it first
    // This is a special case - caller should handle weak_ptr separately
    static_assert(!is_weak_ptr_v<T>,
                  "weak_ptr should be handled separately via lock()");
    return *value.lock();
  } else {
    // shared_ptr, unique_ptr
    return *value;
  }
}

// ============================================================================
// Fory Struct Detection
// ============================================================================

/// Check if type has FORY_STRUCT defined via member lookup or ADL.
/// This trait only evaluates to true if ForyFieldInfo is available AND doesn't
/// trigger static_assert.
template <typename T, typename = void>
struct has_fory_field_info : std::false_type {};

template <typename T>
struct has_fory_field_info<
    T, std::void_t<decltype(SerializationMeta<T, void>::is_serializable)>>
    : std::bool_constant<SerializationMeta<T, void>::is_serializable> {};

template <typename T>
inline constexpr bool has_fory_field_info_v = has_fory_field_info<T>::value;

/// Check if type is serializable (has both FORY_STRUCT and
/// SerializationMeta)
template <typename T, typename = void>
struct is_fory_serializable : std::false_type {};

template <typename T>
struct is_fory_serializable<
    T, std::enable_if_t<has_fory_field_info_v<T> &&
                        std::is_class_v<SerializationMeta<T, void>>>>
    : std::true_type {};

template <typename T>
inline constexpr bool is_fory_serializable_v = is_fory_serializable<T>::value;

// ============================================================================
// Generic Type Detection
// ============================================================================

/// Check if a type is a "generic" type (container with type parameters)
/// Generic types benefit from has_generics optimization
template <typename T> struct is_generic_type : std::false_type {};

template <typename T, typename Alloc>
struct is_generic_type<std::vector<T, Alloc>> : std::true_type {};

template <typename K, typename V, typename... Args>
struct is_generic_type<std::map<K, V, Args...>> : std::true_type {};

template <typename K, typename V, typename... Args>
struct is_generic_type<std::unordered_map<K, V, Args...>> : std::true_type {};

template <typename T, typename... Args>
struct is_generic_type<std::set<T, Args...>> : std::true_type {};

template <typename T, typename... Args>
struct is_generic_type<std::unordered_set<T, Args...>> : std::true_type {};

template <typename T, typename Alloc>
struct is_generic_type<std::list<T, Alloc>> : std::true_type {};

template <typename T, typename Alloc>
struct is_generic_type<std::deque<T, Alloc>> : std::true_type {};

template <typename T, typename Alloc>
struct is_generic_type<std::forward_list<T, Alloc>> : std::true_type {};

template <typename... Ts>
struct is_generic_type<std::tuple<Ts...>> : std::true_type {};

template <typename T>
inline constexpr bool is_generic_type_v = is_generic_type<T>::value;

// ============================================================================
// Polymorphic Type Detection
// ============================================================================

/// Check if a type supports polymorphism (has virtual functions)
/// This detects C++ polymorphic types that have virtual functions and can be
/// used with RTTI to determine the concrete type at runtime.
///
/// For smart pointers (shared_ptr, unique_ptr), they are polymorphic if their
/// element type is polymorphic, matching Rust's Rc<dyn Trait> behavior.
template <typename T> struct is_polymorphic : std::is_polymorphic<T> {};

// Smart pointers are polymorphic if their element type is polymorphic
// This matches Rust's Rc<dyn Trait> / Arc<dyn Trait> behavior
template <typename T>
struct is_polymorphic<std::shared_ptr<T>> : std::is_polymorphic<T> {};

template <typename T>
struct is_polymorphic<std::unique_ptr<T>> : std::is_polymorphic<T> {};

template <typename T>
inline constexpr bool is_polymorphic_v = is_polymorphic<T>::value;

// Forward declaration for type resolver (defined in type_resolver.h)
class TypeResolver;

// ============================================================================
// Concrete Type ID Retrieval
// ============================================================================

// Helper to detect std::shared_ptr
template <typename T> struct is_std_shared_ptr : std::false_type {};
template <typename T>
struct is_std_shared_ptr<std::shared_ptr<T>> : std::true_type {};
template <typename T>
inline constexpr bool is_std_shared_ptr_v = is_std_shared_ptr<T>::value;

// Helper to detect std::unique_ptr
template <typename T> struct is_std_unique_ptr : std::false_type {};
template <typename T>
struct is_std_unique_ptr<std::unique_ptr<T>> : std::true_type {};
template <typename T>
inline constexpr bool is_std_unique_ptr_v = is_std_unique_ptr<T>::value;

/// Get the concrete type_index for a value
/// For non-polymorphic types, this is just typeid(T)
/// For polymorphic types, this returns the runtime type using RTTI
/// For smart pointers, dereferences to get the actual derived type
template <typename T>
inline std::type_index get_concrete_type_id(const T &value) {
  if constexpr (is_std_shared_ptr_v<T> || is_std_unique_ptr_v<T>) {
    // For shared_ptr/unique_ptr, dereference to get the concrete derived type
    // This matches what smart_ptr_serializers.h does
    if (value) {
      return std::type_index(typeid(*value));
    } else {
      // For null pointers, return the static element type
      using element_type = typename T::element_type;
      return std::type_index(typeid(element_type));
    }
  } else if constexpr (is_polymorphic_v<T>) {
    // For polymorphic types, get runtime type using RTTI
    // typeid(value) performs dynamic type lookup for polymorphic types
    return std::type_index(typeid(value));
  } else {
    // For non-polymorphic types, use static type
    return std::type_index(typeid(T));
  }
}

// Note: get_type_id_dyn is declared here but implemented after TypeResolver is
// fully defined See the implementation in context.h or a separate
// implementation file

// ============================================================================
// Shared Reference Detection
// ============================================================================

/// Check if a type is a shared reference (Rc/Arc in Rust, shared_ptr in C++)
template <typename T> struct is_shared_ref : std::false_type {};

template <typename T>
struct is_shared_ref<std::shared_ptr<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_shared_ref_v = is_shared_ref<T>::value;

// ============================================================================
// Element Type Extraction
// ============================================================================

/// Get element type for containers (reuse meta::GetValueType)
template <typename T> using element_type_t = typename meta::GetValueType<T>;

/// Get key type for map-like containers
template <typename T, typename = void> struct key_type_impl {};

template <typename T>
struct key_type_impl<T, std::void_t<typename T::key_type>> {
  using type = typename T::key_type;
};

template <typename T> using key_type_t = typename key_type_impl<T>::type;

/// Get mapped type for map-like containers
template <typename T, typename = void> struct mapped_type_impl {};

template <typename T>
struct mapped_type_impl<T, std::void_t<typename T::mapped_type>> {
  using type = typename T::mapped_type;
};

template <typename T> using mapped_type_t = typename mapped_type_impl<T>::type;

// ============================================================================
// TypeIndex - Compile-time type index for fast hash map lookups
// Infrastructure (TypeIndex forward decl, fnv1a_64) comes from type_index.h
// ============================================================================

// Forward declaration of type_index function (needed for recursive container
// types)
template <typename T> constexpr uint64_t type_index();

// ============================================================================
// Primitive type specializations - use TypeId directly (guaranteed unique)
// ============================================================================

template <> struct TypeIndex<bool> {
  static constexpr uint64_t value = static_cast<uint64_t>(TypeId::BOOL);
};

template <> struct TypeIndex<int8_t> {
  static constexpr uint64_t value = static_cast<uint64_t>(TypeId::INT8);
};

template <> struct TypeIndex<int16_t> {
  static constexpr uint64_t value = static_cast<uint64_t>(TypeId::INT16);
};

template <> struct TypeIndex<int32_t> {
  static constexpr uint64_t value = static_cast<uint64_t>(TypeId::VARINT32);
};

template <> struct TypeIndex<int64_t> {
  static constexpr uint64_t value = static_cast<uint64_t>(TypeId::VARINT64);
};

// Note: Unsigned types (uint8_t, uint16_t, uint32_t, uint64_t) use the fallback
// template since xlang serialization doesn't have separate unsigned type IDs.
// They get unique hashes via type_fallback_hash<T>() which uses
// PRETTY_FUNCTION.

template <> struct TypeIndex<float> {
  static constexpr uint64_t value = static_cast<uint64_t>(TypeId::FLOAT32);
};

template <> struct TypeIndex<double> {
  static constexpr uint64_t value = static_cast<uint64_t>(TypeId::FLOAT64);
};

template <> struct TypeIndex<std::string> {
  static constexpr uint64_t value = static_cast<uint64_t>(TypeId::STRING);
};

// ============================================================================
// Container type specializations - combine container name + element type_index
// This ensures nested containers with FORY_STRUCT elements are unique
// ============================================================================

// vector<T>
template <typename T> struct TypeIndex<std::vector<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::vector"), type_index<T>());
};

// list<T>
template <typename T> struct TypeIndex<std::list<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::list"), type_index<T>());
};

// deque<T>
template <typename T> struct TypeIndex<std::deque<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::deque"), type_index<T>());
};

// forward_list<T>
template <typename T> struct TypeIndex<std::forward_list<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::forward_list"), type_index<T>());
};

// set<T>
template <typename T> struct TypeIndex<std::set<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::set"), type_index<T>());
};

// unordered_set<T>
template <typename T> struct TypeIndex<std::unordered_set<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::unordered_set"), type_index<T>());
};

// map<K, V>
template <typename K, typename V> struct TypeIndex<std::map<K, V>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::map"), type_index<K>(), type_index<V>());
};

// unordered_map<K, V>
template <typename K, typename V> struct TypeIndex<std::unordered_map<K, V>> {
  static constexpr uint64_t value = fnv1a_64_combine(
      fnv1a_64("std::unordered_map"), type_index<K>(), type_index<V>());
};

// pair<T1, T2>
template <typename T1, typename T2> struct TypeIndex<std::pair<T1, T2>> {
  static constexpr uint64_t value = fnv1a_64_combine(
      fnv1a_64("std::pair"), type_index<T1>(), type_index<T2>());
};

// array<T, N>
template <typename T, size_t N> struct TypeIndex<std::array<T, N>> {
  static constexpr uint64_t value = fnv1a_64_combine(
      fnv1a_64("std::array"), type_index<T>(), static_cast<uint64_t>(N));
};

// optional<T>
template <typename T> struct TypeIndex<std::optional<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::optional"), type_index<T>());
};

// shared_ptr<T>
template <typename T> struct TypeIndex<std::shared_ptr<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::shared_ptr"), type_index<T>());
};

// unique_ptr<T>
template <typename T> struct TypeIndex<std::unique_ptr<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::unique_ptr"), type_index<T>());
};

// tuple<Ts...>
template <typename... Ts> struct TypeIndex<std::tuple<Ts...>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::tuple"), (type_index<Ts>() ^ ...));
};

// variant<Ts...>
template <typename... Ts> struct TypeIndex<std::variant<Ts...>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("std::variant"), (type_index<Ts>() ^ ...));
};

// ============================================================================
// type_index<T>() function - main entry point
// ============================================================================

/// Get compile-time type index for a type.
/// This is much faster than std::type_index for hash map lookups.
/// Returns a unique uint64_t for each type.
template <typename T> constexpr uint64_t type_index() {
  return TypeIndex<T>::value;
}

/// Macro for user-defined types to define their TypeIndex
/// Usage: FORY_DEFINE_TYPE_INDEX(MyType) after type definition
#define FORY_DEFINE_TYPE_INDEX(Type)                                           \
  template <> struct fory::serialization::TypeIndex<Type> {                    \
    static constexpr uint64_t value = fnv1a_64(FORY_FILE_LINE);                \
  }

} // namespace serialization
} // namespace fory
