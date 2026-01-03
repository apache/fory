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

#include "fory/meta/preprocessor.h"
#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <utility>

namespace fory {

// ============================================================================
// Field Option Tags
// ============================================================================

/// Tag to mark a shared_ptr/unique_ptr field as nullable.
/// Only valid for std::shared_ptr and std::unique_ptr types.
/// For nullable primitives/strings, use std::optional<T> instead.
struct nullable {};

/// Tag to explicitly mark a pointer field as non-nullable.
/// Useful for future pointer types (e.g., weak_ptr) that might be nullable by
/// default. For shared_ptr/unique_ptr, non-nullable is already the default.
struct not_null {};

/// Tag to enable reference tracking for shared_ptr fields.
/// Only valid for std::shared_ptr types (requires shared ownership for ref
/// tracking).
struct ref {};

/// Tag to mark a polymorphic shared_ptr/unique_ptr field as monomorphic.
/// Use this when the field type has virtual methods but you know the actual
/// runtime type will always be exactly T (not a derived type).
/// This avoids dynamic type dispatch overhead during serialization.
struct monomorphic {};

namespace detail {

// ============================================================================
// Type Traits for Smart Pointers and Optional
// ============================================================================

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

template <typename T> struct is_optional : std::false_type {};

template <typename T> struct is_optional<std::optional<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_optional_v = is_optional<T>::value;

/// Helper to check if type is shared_ptr or unique_ptr
template <typename T>
inline constexpr bool is_smart_ptr_v = is_shared_ptr_v<T> || is_unique_ptr_v<T>;

// ============================================================================
// Option Tag Detection
// ============================================================================

/// Check if a specific tag type is present in the Options pack
template <typename Tag, typename... Options>
inline constexpr bool has_option_v = (std::is_same_v<Tag, Options> || ...);

// ============================================================================
// Field Tag Entry for FORY_FIELD_TAGS Macro
// ============================================================================

/// Compile-time field tag metadata entry
template <int16_t Id, bool Nullable, bool Ref, bool Monomorphic = false>
struct FieldTagEntry {
  static constexpr int16_t id = Id;
  static constexpr bool is_nullable = Nullable;
  static constexpr bool track_ref = Ref;
  static constexpr bool is_monomorphic = Monomorphic;
};

/// Default: no field tags defined for type T
template <typename T> struct ForyFieldTagsImpl {
  static constexpr bool has_tags = false;
};

template <typename T>
inline constexpr bool has_field_tags_v = ForyFieldTagsImpl<T>::has_tags;

} // namespace detail

// ============================================================================
// fory::field<T, Id, Options...> Template
// ============================================================================

/// Field wrapper template that provides compile-time field metadata.
///
/// Usage:
///   struct Person {
///     fory::field<std::string, 0> name;                    // non-nullable
///     fory::field<int32_t, 1> age;                         // non-nullable
///     fory::field<std::optional<std::string>, 2> nickname; // inherently
///     nullable fory::field<std::shared_ptr<Person>, 3> parent;        //
///     non-nullable fory::field<std::shared_ptr<Person>, 4, fory::nullable>
///     guardian; fory::field<std::shared_ptr<Node>, 5, fory::ref> node;
///     fory::field<std::shared_ptr<Node>, 6, fory::nullable, fory::ref> link;
///   };
///
/// Template Parameters:
///   T       - The underlying field type
///   Id      - The field tag ID (int16_t) for compact serialization
///   Options - Optional tags: fory::nullable, fory::ref
///
/// Type Rules:
///   - Primitives/strings: No options allowed (use std::optional for nullable)
///   - std::optional<T>: Inherently nullable, no options needed
///   - std::shared_ptr<T>: Can use nullable and/or ref
///   - std::unique_ptr<T>: Can use nullable only (no ref - exclusive
///   ownership)
template <typename T, int16_t Id, typename... Options> class field {
  // Validate: nullable and not_null are mutually exclusive
  static_assert(!(detail::has_option_v<nullable, Options...> &&
                  detail::has_option_v<not_null, Options...>),
                "fory::nullable and fory::not_null are mutually exclusive.");

  // Validate: nullable only for smart pointers
  static_assert(!detail::has_option_v<nullable, Options...> ||
                    detail::is_smart_ptr_v<T>,
                "fory::nullable is only valid for shared_ptr/unique_ptr. "
                "Use std::optional<T> for nullable primitives/strings.");

  // Validate: not_null only for smart pointers (for now)
  static_assert(!detail::has_option_v<not_null, Options...> ||
                    detail::is_smart_ptr_v<T>,
                "fory::not_null is only valid for pointer types.");

  // Validate: ref only for shared_ptr
  static_assert(!detail::has_option_v<ref, Options...> ||
                    detail::is_shared_ptr_v<T>,
                "fory::ref is only valid for shared_ptr "
                "(reference tracking requires shared ownership).");

  // Validate: monomorphic only for smart pointers
  static_assert(!detail::has_option_v<monomorphic, Options...> ||
                    detail::is_smart_ptr_v<T>,
                "fory::monomorphic is only valid for shared_ptr/unique_ptr.");

  // Validate: no options for optional (inherently nullable)
  static_assert(!detail::is_optional_v<T> || sizeof...(Options) == 0,
                "std::optional<T> is inherently nullable. No options allowed.");

  // Validate: no options for non-smart-pointer types
  static_assert(detail::is_smart_ptr_v<T> || detail::is_optional_v<T> ||
                    sizeof...(Options) == 0,
                "Options are only valid for shared_ptr/unique_ptr fields. "
                "Use std::optional<T> for nullable primitives/strings.");

public:
  using value_type = T;
  static constexpr int16_t tag_id = Id;

  /// Field is nullable if:
  /// - It's std::optional (inherently nullable), OR
  /// - It's a smart pointer with fory::nullable option
  static constexpr bool is_nullable =
      detail::is_optional_v<T> ||
      (detail::is_smart_ptr_v<T> && detail::has_option_v<nullable, Options...>);

  /// Reference tracking is enabled if:
  /// - It's std::shared_ptr with fory::ref option
  static constexpr bool track_ref =
      detail::is_shared_ptr_v<T> && detail::has_option_v<ref, Options...>;

  /// Monomorphic serialization is enabled if:
  /// - It's std::shared_ptr or std::unique_ptr with fory::monomorphic option
  /// - When true, the serializer will not use dynamic type dispatch
  static constexpr bool is_monomorphic =
      detail::is_smart_ptr_v<T> &&
      detail::has_option_v<monomorphic, Options...>;

  T value{};

  // Default constructor
  field() = default;

  // Value constructors
  field(const T &v) : value(v) {}
  field(T &&v) : value(std::move(v)) {}

  // Copy and move constructors
  field(const field &) = default;
  field(field &&) = default;

  // Copy and move assignment
  field &operator=(const field &) = default;
  field &operator=(field &&) = default;

  // Value assignment
  field &operator=(const T &v) {
    value = v;
    return *this;
  }
  field &operator=(T &&v) {
    value = std::move(v);
    return *this;
  }

  // Implicit conversions to underlying type
  operator T &() { return value; }
  operator const T &() const { return value; }

  // Pointer-like access for smart pointers
  T *operator->() { return &value; }
  const T *operator->() const { return &value; }

  // Dereference operators
  T &operator*() { return value; }
  const T &operator*() const { return value; }

  // Get underlying value
  T &get() { return value; }
  const T &get() const { return value; }
};

// ============================================================================
// Type Traits for fory::field Detection
// ============================================================================

/// Check if a type is a fory::field wrapper
template <typename T> struct is_fory_field : std::false_type {};

template <typename T, int16_t Id, typename... Options>
struct is_fory_field<field<T, Id, Options...>> : std::true_type {};

template <typename T>
inline constexpr bool is_fory_field_v = is_fory_field<T>::value;

/// Unwrap fory::field to get the underlying type
template <typename T> struct unwrap_field {
  using type = T;
};

template <typename T, int16_t Id, typename... Options>
struct unwrap_field<field<T, Id, Options...>> {
  using type = T;
};

template <typename T> using unwrap_field_t = typename unwrap_field<T>::type;

/// Get tag ID from field type (returns -1 if not a fory::field)
template <typename T> struct field_tag_id {
  static constexpr int16_t value = -1;
};

template <typename T, int16_t Id, typename... Options>
struct field_tag_id<field<T, Id, Options...>> {
  static constexpr int16_t value = Id;
};

template <typename T>
inline constexpr int16_t field_tag_id_v = field_tag_id<T>::value;

/// Determines whether a field is nullable and requires a RefFlag byte.
///
/// This mirrors Rust's `field_need_write_ref_into(type_id, nullable)` in
/// rust/fory-core/src/serializer/util.rs and determines whether the writer
/// emits a `RefFlag` byte before the field's value payload.
///
/// Per the xlang protocol:
/// - Non-nullable types (nullable=false) skip the ref flag entirely
/// - Nullable types (nullable=true) write a ref flag to indicate null vs
///   non-null
///
/// For non-field types, std::optional is considered nullable.
/// For fory::field types, uses the explicit nullable option if provided.
template <typename T> struct field_is_nullable {
  static constexpr bool value = detail::is_optional_v<T>;
};

template <typename T, int16_t Id, typename... Options>
struct field_is_nullable<field<T, Id, Options...>> {
  static constexpr bool value = field<T, Id, Options...>::is_nullable;
};

template <typename T>
inline constexpr bool field_is_nullable_v = field_is_nullable<T>::value;

/// Get track_ref from field type
template <typename T> struct field_track_ref {
  static constexpr bool value = false;
};

template <typename T, int16_t Id, typename... Options>
struct field_track_ref<field<T, Id, Options...>> {
  static constexpr bool value = field<T, Id, Options...>::track_ref;
};

template <typename T>
inline constexpr bool field_track_ref_v = field_track_ref<T>::value;

/// Get is_monomorphic from field type
template <typename T> struct field_is_monomorphic {
  static constexpr bool value = false;
};

template <typename T, int16_t Id, typename... Options>
struct field_is_monomorphic<field<T, Id, Options...>> {
  static constexpr bool value = field<T, Id, Options...>::is_monomorphic;
};

template <typename T>
inline constexpr bool field_is_monomorphic_v = field_is_monomorphic<T>::value;

// ============================================================================
// FORY_FIELD_TAGS Macro Support
// ============================================================================

namespace detail {

// Helper to parse field tag entry from macro arguments
// Supports: (field, id), (field, id, nullable), (field, id, ref),
//           (field, id, nullable, ref), (field, id, monomorphic), etc.
template <typename FieldType, int16_t Id, typename... Options>
struct ParseFieldTagEntry {
  static constexpr bool is_nullable =
      is_optional_v<FieldType> ||
      (is_smart_ptr_v<FieldType> && has_option_v<nullable, Options...>);

  static constexpr bool track_ref =
      is_shared_ptr_v<FieldType> && has_option_v<ref, Options...>;

  static constexpr bool is_monomorphic =
      is_smart_ptr_v<FieldType> && has_option_v<monomorphic, Options...>;

  // Compile-time validation
  static_assert(!has_option_v<nullable, Options...> ||
                    is_smart_ptr_v<FieldType>,
                "fory::nullable is only valid for shared_ptr/unique_ptr");

  static_assert(!has_option_v<ref, Options...> || is_shared_ptr_v<FieldType>,
                "fory::ref is only valid for shared_ptr");

  static_assert(!has_option_v<monomorphic, Options...> ||
                    is_smart_ptr_v<FieldType>,
                "fory::monomorphic is only valid for shared_ptr/unique_ptr");

  using type = FieldTagEntry<Id, is_nullable, track_ref, is_monomorphic>;
};

/// Get field tag entry by index from ForyFieldTagsImpl
template <typename T, size_t Index, typename = void> struct GetFieldTagEntry {
  static constexpr int16_t id = -1;
  static constexpr bool is_nullable = false;
  static constexpr bool track_ref = false;
  static constexpr bool is_monomorphic = false;
};

template <typename T, size_t Index>
struct GetFieldTagEntry<
    T, Index,
    std::enable_if_t<ForyFieldTagsImpl<T>::has_tags &&
                     (Index < ForyFieldTagsImpl<T>::field_count)>> {
  using Entry =
      std::tuple_element_t<Index, typename ForyFieldTagsImpl<T>::Entries>;
  static constexpr int16_t id = Entry::id;
  static constexpr bool is_nullable = Entry::is_nullable;
  static constexpr bool track_ref = Entry::track_ref;
  static constexpr bool is_monomorphic = Entry::is_monomorphic;
};

} // namespace detail

} // namespace fory

// ============================================================================
// FORY_FIELD_TAGS Macro Implementation
// ============================================================================

// Helper macros to extract parts from (field, id, ...) tuples
#define FORY_FT_FIELD(tuple) FORY_FT_FIELD_IMPL tuple
#define FORY_FT_FIELD_IMPL(field, ...) field

#define FORY_FT_ID(tuple) FORY_FT_ID_IMPL tuple
#define FORY_FT_ID_IMPL(field, id, ...) id

// Get options from tuple
#define FORY_FT_GET_OPT1(tuple) FORY_FT_GET_OPT1_IMPL tuple
#define FORY_FT_GET_OPT1_IMPL(f, i, o1, ...) o1
#define FORY_FT_GET_OPT2(tuple) FORY_FT_GET_OPT2_IMPL tuple
#define FORY_FT_GET_OPT2_IMPL(f, i, o1, o2, ...) o2
#define FORY_FT_GET_OPT3(tuple) FORY_FT_GET_OPT3_IMPL tuple
#define FORY_FT_GET_OPT3_IMPL(f, i, o1, o2, o3, ...) o3

// Detect number of elements in tuple: 2, 3, 4, or 5
#define FORY_FT_TUPLE_SIZE(tuple) FORY_FT_TUPLE_SIZE_IMPL tuple
#define FORY_FT_TUPLE_SIZE_IMPL(...)                                           \
  FORY_FT_TUPLE_SIZE_SELECT(__VA_ARGS__, 5, 4, 3, 2, 1, 0)
#define FORY_FT_TUPLE_SIZE_SELECT(_1, _2, _3, _4, _5, N, ...) N

// Create FieldTagEntry based on tuple size using indirect call pattern
// This pattern ensures the concatenated macro name is properly rescanned
#define FORY_FT_MAKE_ENTRY(Type, tuple)                                        \
  FORY_FT_MAKE_ENTRY_I(Type, tuple, FORY_FT_TUPLE_SIZE(tuple))
#define FORY_FT_MAKE_ENTRY_I(Type, tuple, size)                                \
  FORY_FT_MAKE_ENTRY_II(Type, tuple, size)
#define FORY_FT_MAKE_ENTRY_II(Type, tuple, size)                               \
  FORY_FT_MAKE_ENTRY_##size(Type, tuple)

#define FORY_FT_MAKE_ENTRY_2(Type, tuple)                                      \
  typename ::fory::detail::ParseFieldTagEntry<                                 \
      decltype(std::declval<Type>().FORY_FT_FIELD(tuple)),                     \
      FORY_FT_ID(tuple)>::type

#define FORY_FT_MAKE_ENTRY_3(Type, tuple)                                      \
  typename ::fory::detail::ParseFieldTagEntry<                                 \
      decltype(std::declval<Type>().FORY_FT_FIELD(tuple)), FORY_FT_ID(tuple),  \
      ::fory::FORY_FT_GET_OPT1(tuple)>::type

#define FORY_FT_MAKE_ENTRY_4(Type, tuple)                                      \
  typename ::fory::detail::ParseFieldTagEntry<                                 \
      decltype(std::declval<Type>().FORY_FT_FIELD(tuple)), FORY_FT_ID(tuple),  \
      ::fory::FORY_FT_GET_OPT1(tuple), ::fory::FORY_FT_GET_OPT2(tuple)>::type

#define FORY_FT_MAKE_ENTRY_5(Type, tuple)                                      \
  typename ::fory::detail::ParseFieldTagEntry<                                 \
      decltype(std::declval<Type>().FORY_FT_FIELD(tuple)), FORY_FT_ID(tuple),  \
      ::fory::FORY_FT_GET_OPT1(tuple), ::fory::FORY_FT_GET_OPT2(tuple),        \
      ::fory::FORY_FT_GET_OPT3(tuple)>::type

// Main macro: FORY_FIELD_TAGS(Type, (field1, id1), (field2, id2, nullable),...)
// Note: Uses fory::detail:: instead of ::fory::detail:: for GCC compatibility
#define FORY_FIELD_TAGS(Type, ...)                                             \
  template <> struct fory::detail::ForyFieldTagsImpl<Type> {                   \
    static constexpr bool has_tags = true;                                     \
    static constexpr size_t field_count = FORY_PP_NARG(__VA_ARGS__);           \
    using Entries = std::tuple<FORY_FT_ENTRIES(Type, __VA_ARGS__)>;            \
  }

// Helper to generate entries tuple content using indirect expansion pattern
// This ensures FORY_PP_NARG is fully expanded before concatenation
#define FORY_FT_ENTRIES(Type, ...)                                             \
  FORY_FT_ENTRIES_I(Type, FORY_PP_NARG(__VA_ARGS__), __VA_ARGS__)
#define FORY_FT_ENTRIES_I(Type, N, ...) FORY_FT_ENTRIES_II(Type, N, __VA_ARGS__)
#define FORY_FT_ENTRIES_II(Type, N, ...) FORY_FT_ENTRIES_##N(Type, __VA_ARGS__)

// Generate entries for 1-32 fields
#define FORY_FT_ENTRIES_1(T, _1) FORY_FT_MAKE_ENTRY(T, _1)
#define FORY_FT_ENTRIES_2(T, _1, _2)                                           \
  FORY_FT_MAKE_ENTRY(T, _1), FORY_FT_MAKE_ENTRY(T, _2)
#define FORY_FT_ENTRIES_3(T, _1, _2, _3)                                       \
  FORY_FT_ENTRIES_2(T, _1, _2), FORY_FT_MAKE_ENTRY(T, _3)
#define FORY_FT_ENTRIES_4(T, _1, _2, _3, _4)                                   \
  FORY_FT_ENTRIES_3(T, _1, _2, _3), FORY_FT_MAKE_ENTRY(T, _4)
#define FORY_FT_ENTRIES_5(T, _1, _2, _3, _4, _5)                               \
  FORY_FT_ENTRIES_4(T, _1, _2, _3, _4), FORY_FT_MAKE_ENTRY(T, _5)
#define FORY_FT_ENTRIES_6(T, _1, _2, _3, _4, _5, _6)                           \
  FORY_FT_ENTRIES_5(T, _1, _2, _3, _4, _5), FORY_FT_MAKE_ENTRY(T, _6)
#define FORY_FT_ENTRIES_7(T, _1, _2, _3, _4, _5, _6, _7)                       \
  FORY_FT_ENTRIES_6(T, _1, _2, _3, _4, _5, _6), FORY_FT_MAKE_ENTRY(T, _7)
#define FORY_FT_ENTRIES_8(T, _1, _2, _3, _4, _5, _6, _7, _8)                   \
  FORY_FT_ENTRIES_7(T, _1, _2, _3, _4, _5, _6, _7), FORY_FT_MAKE_ENTRY(T, _8)
#define FORY_FT_ENTRIES_9(T, _1, _2, _3, _4, _5, _6, _7, _8, _9)               \
  FORY_FT_ENTRIES_8(T, _1, _2, _3, _4, _5, _6, _7, _8),                        \
      FORY_FT_MAKE_ENTRY(T, _9)
#define FORY_FT_ENTRIES_10(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10)         \
  FORY_FT_ENTRIES_9(T, _1, _2, _3, _4, _5, _6, _7, _8, _9),                    \
      FORY_FT_MAKE_ENTRY(T, _10)
#define FORY_FT_ENTRIES_11(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11)    \
  FORY_FT_ENTRIES_10(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10),              \
      FORY_FT_MAKE_ENTRY(T, _11)
#define FORY_FT_ENTRIES_12(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12)                                                \
  FORY_FT_ENTRIES_11(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11),         \
      FORY_FT_MAKE_ENTRY(T, _12)
#define FORY_FT_ENTRIES_13(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13)                                           \
  FORY_FT_ENTRIES_12(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12),    \
      FORY_FT_MAKE_ENTRY(T, _13)
#define FORY_FT_ENTRIES_14(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14)                                      \
  FORY_FT_ENTRIES_13(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13),                                                     \
      FORY_FT_MAKE_ENTRY(T, _14)
#define FORY_FT_ENTRIES_15(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15)                                 \
  FORY_FT_ENTRIES_14(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14),                                                \
      FORY_FT_MAKE_ENTRY(T, _15)
#define FORY_FT_ENTRIES_16(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16)                            \
  FORY_FT_ENTRIES_15(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15),                                           \
      FORY_FT_MAKE_ENTRY(T, _16)
