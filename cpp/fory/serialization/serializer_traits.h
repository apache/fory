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
#include "fory/meta/type_traits.h"
#include <map>
#include <optional>
#include <set>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
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

/// Detect std::optional
template <typename T> struct is_optional : std::false_type {};

template <typename T> struct is_optional<std::optional<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_optional_v = is_optional<T>::value;

// ============================================================================
// Fory Struct Detection
// ============================================================================

/// Check if type has FORY_FIELD_INFO defined via ADL
/// This trait only evaluates to true if ForyFieldInfo is available AND doesn't
/// trigger static_assert
template <typename T, typename = void>
struct has_fory_field_info : std::false_type {};

template <typename T>
struct has_fory_field_info<
    T, std::void_t<decltype(SerializationMeta<T, void>::is_serializable)>>
    : std::bool_constant<SerializationMeta<T, void>::is_serializable> {};

template <typename T>
inline constexpr bool has_fory_field_info_v = has_fory_field_info<T>::value;

/// Check if type is serializable (has both FORY_FIELD_INFO and
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
// Reference Metadata Requirements
// ============================================================================

/// Determine if a type requires reference metadata (null/ref flags) even when
/// nested inside another structure.
template <typename T> struct requires_ref_metadata : std::false_type {};

template <typename T>
struct requires_ref_metadata<std::optional<T>> : std::true_type {};

template <typename T>
struct requires_ref_metadata<std::shared_ptr<T>> : std::true_type {};

template <typename T>
struct requires_ref_metadata<std::unique_ptr<T>> : std::true_type {};

template <typename T>
inline constexpr bool requires_ref_metadata_v = requires_ref_metadata<T>::value;

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

} // namespace serialization
} // namespace fory
