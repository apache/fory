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
#include "fory/meta/preprocessor.h"
#include "fory/type/type.h"
#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <utility>

namespace fory {

namespace serialization {
template <typename T> class SharedWeak;
} // namespace serialization

// ============================================================================
// Field Option Tags
// ============================================================================

/// Tag to mark a shared_ptr/SharedWeak/unique_ptr field as nullable.
/// Only valid for std::shared_ptr, SharedWeak, and std::unique_ptr types.
/// For nullable primitives/strings, use std::optional<T> instead.
struct nullable {};

/// Tag to explicitly mark a pointer field as non-nullable.
/// Useful for future pointer types (e.g., weak_ptr) that might be nullable by
/// default. For shared_ptr/SharedWeak/unique_ptr, non-nullable is already the
/// default.
struct not_null {};

/// Tag to enable reference tracking for shared_ptr/SharedWeak fields.
/// Only valid for std::shared_ptr or SharedWeak types (requires shared
/// ownership for ref tracking).
struct ref {};

/// Template tag to control dynamic type dispatch for smart pointer fields.
/// - `dynamic<true>`: Force type info to be written (enable runtime subtype
/// support)
/// - `dynamic<false>`: Skip type info (use declared type directly)
///
/// By default, Fory auto-detects polymorphism via `std::is_polymorphic<T>`.
/// Use this tag to override the default behavior.
///
/// Example:
///   fory::field<std::shared_ptr<Base>, 0, fory::dynamic<false>> ptr;
template <bool V> struct dynamic : std::bool_constant<V> {};

namespace detail {

// ============================================================================
// Type Traits for Smart Pointers and Optional
// ============================================================================

template <typename T>
using FieldInfo = decltype(::fory::meta::ForyFieldInfo(std::declval<T>()));

inline constexpr size_t kInvalidFieldIndex = static_cast<size_t>(-1);

template <typename T> constexpr size_t FieldIndex(std::string_view name) {
  constexpr auto names = FieldInfo<T>::Names;
  for (size_t i = 0; i < names.size(); ++i) {
    if (names[i] == name) {
      return i;
    }
  }
  return kInvalidFieldIndex;
}

template <typename T, size_t Index, typename Enable = void> struct FieldTypeAt;

template <typename T, size_t Index>
struct FieldTypeAt<T, Index, std::enable_if_t<Index != kInvalidFieldIndex>> {
  using PtrsType = typename FieldInfo<T>::PtrsType;
  using PtrT = std::tuple_element_t<Index, PtrsType>;
  using type = ::fory::meta::RemoveMemberPointerCVRefT<PtrT>;
};

template <typename T, size_t Index>
struct FieldTypeAt<T, Index, std::enable_if_t<Index == kInvalidFieldIndex>> {
  static_assert(Index != kInvalidFieldIndex,
                "Unknown field name in FORY_FIELD_TAGS");
};

template <typename T> struct is_shared_ptr : std::false_type {};

template <typename T>
struct is_shared_ptr<std::shared_ptr<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_shared_ptr_v = is_shared_ptr<T>::value;

template <typename T> struct is_shared_weak : std::false_type {};

template <typename T>
struct is_shared_weak<serialization::SharedWeak<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_shared_weak_v = is_shared_weak<T>::value;

template <typename T> struct is_unique_ptr : std::false_type {};

template <typename T, typename D>
struct is_unique_ptr<std::unique_ptr<T, D>> : std::true_type {};

template <typename T>
inline constexpr bool is_unique_ptr_v = is_unique_ptr<T>::value;

template <typename T> struct is_optional : std::false_type {};

template <typename T> struct is_optional<std::optional<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_optional_v = is_optional<T>::value;

/// Helper to check if type is shared_ptr/SharedWeak or unique_ptr
template <typename T>
inline constexpr bool is_smart_ptr_v =
    is_shared_ptr_v<T> || is_shared_weak_v<T> || is_unique_ptr_v<T>;

// ============================================================================
// Option Tag Detection
// ============================================================================

/// Check if a specific tag type is present in the Options pack
template <typename Tag, typename... Options>
inline constexpr bool has_option_v = (std::is_same_v<Tag, Options> || ...);

/// Check if a type is a dynamic<V> tag
template <typename T> struct is_dynamic_tag : std::false_type {};
template <bool V> struct is_dynamic_tag<dynamic<V>> : std::true_type {};
template <typename T>
inline constexpr bool is_dynamic_tag_v = is_dynamic_tag<T>::value;

/// Check if any dynamic<V> tag is present in Options pack
template <typename... Options>
inline constexpr bool has_dynamic_option_v = (is_dynamic_tag_v<Options> || ...);

/// Extract the dynamic value from Options pack (default = -1 for AUTO)
/// Returns: 1 for dynamic<true>, 0 for dynamic<false>, -1 for AUTO (not
/// specified)
template <typename... Options> struct get_dynamic_value {
  static constexpr int value = -1; // AUTO
};
template <bool V, typename... Rest>
struct get_dynamic_value<dynamic<V>, Rest...> {
  static constexpr int value = V ? 1 : 0;
};
template <typename First, typename... Rest>
struct get_dynamic_value<First, Rest...> {
  static constexpr int value = get_dynamic_value<Rest...>::value;
};
template <typename... Options>
inline constexpr int get_dynamic_value_v = get_dynamic_value<Options...>::value;

// ============================================================================
// Field Tag Entry for FORY_FIELD_TAGS Macro
// ============================================================================

/// Compile-time field tag metadata entry
/// Dynamic: -1 = AUTO (use std::is_polymorphic), 0 = FALSE (not dynamic), 1 =
/// TRUE (dynamic)
template <int16_t Id, bool Nullable, bool Ref, int Dynamic = -1>
struct FieldTagEntry {
  static constexpr int16_t id = Id;
  static constexpr bool is_nullable = Nullable;
  static constexpr bool track_ref = Ref;
  static constexpr int dynamic_value = Dynamic;
};

struct FieldTagEntryWithName {
  const char *name;
  int16_t id;
  bool is_nullable;
  bool track_ref;
  int dynamic_value;
};

template <typename Entry>
constexpr FieldTagEntryWithName make_field_tag_entry(const char *name) {
  return FieldTagEntryWithName{name, Entry::id, Entry::is_nullable,
                               Entry::track_ref, Entry::dynamic_value};
}

/// Default: no field tags defined for type T (legacy specialization path)
template <typename T> struct ForyFieldTagsImpl {
  static constexpr bool has_tags = false;
};

template <typename T>
using AdlFieldTagsDescriptor =
    decltype(ForyFieldTags(std::declval<meta::Identity<T>>()));

template <typename T, typename = void>
struct HasAdlFieldTags : std::false_type {};

template <typename T>
struct HasAdlFieldTags<T, std::void_t<AdlFieldTagsDescriptor<T>>>
    : std::true_type {};

template <typename T, typename Enable = void> struct FieldTagsInfo {
  static constexpr bool has_tags = false;
  static constexpr size_t field_count = 0;
  static inline constexpr auto entries = std::tuple<>{};
  using Entries = std::decay_t<decltype(entries)>;
  static constexpr bool use_index = true;
};

template <typename T>
struct FieldTagsInfo<T, std::enable_if_t<HasAdlFieldTags<T>::value>> {
  using Descriptor = AdlFieldTagsDescriptor<T>;
  static constexpr bool has_tags = Descriptor::has_tags;
  static inline constexpr auto entries = Descriptor::entries;
  using Entries = std::decay_t<decltype(entries)>;
  static constexpr size_t field_count = std::tuple_size_v<Entries>;
  static constexpr bool use_index = false;
};

template <typename T>
struct FieldTagsInfo<T, std::enable_if_t<!HasAdlFieldTags<T>::value &&
                                         ForyFieldTagsImpl<T>::has_tags>> {
  static constexpr bool has_tags = true;
  static constexpr size_t field_count = ForyFieldTagsImpl<T>::field_count;
  using Entries = typename ForyFieldTagsImpl<T>::Entries;
  static inline constexpr auto entries = Entries{};
  static constexpr bool use_index = true;
};

template <typename T>
inline constexpr bool has_field_tags_v = FieldTagsInfo<T>::has_tags;

} // namespace detail

// ============================================================================
// Field Encoding Types for Unsigned Integers
// ============================================================================

/// Encoding strategies for integer fields
enum class Encoding {
  Default = 0, // Use type's default encoding
  Varint = 1,  // Variable-length encoding (smaller values use fewer bytes)
  Fixed = 2,   // Fixed-size encoding (always uses full type width)
  Tagged = 3   // Tagged encoding (uses tag byte + value)
};

// ============================================================================
// FieldMeta - Compile-time Field Configuration with Builder Pattern
// ============================================================================

/// Compile-time field metadata with fluent builder API.
/// Supports both:
///   - Simple: F(0) - just field ID
///   - Full:   F(0).nullable().varint().compress(false).dynamic(false)
struct FieldMeta {
  int16_t id_ = -1;
  bool nullable_ = false;
  bool ref_ = false;
  int dynamic_ = -1; // -1 = AUTO, 0 = FALSE, 1 = TRUE
  Encoding encoding_ = Encoding::Default;
  bool compress_ = true;
  int16_t type_id_override_ = -1; // -1 = unset

  // Builder methods - each returns a modified copy
  constexpr FieldMeta id(int16_t v) const {
    auto c = *this;
    c.id_ = v;
    return c;
  }
  constexpr FieldMeta nullable(bool v = true) const {
    auto c = *this;
    c.nullable_ = v;
    return c;
  }
  constexpr FieldMeta ref(bool v = true) const {
    auto c = *this;
    c.ref_ = v;
    return c;
  }
  /// Set dynamic type dispatch: true = write type info, false = skip type info
  constexpr FieldMeta dynamic(bool v) const {
    auto c = *this;
    c.dynamic_ = v ? 1 : 0;
    return c;
  }
  constexpr FieldMeta encoding(Encoding v) const {
    auto c = *this;
    c.encoding_ = v;
    return c;
  }
  constexpr FieldMeta compress(bool v) const {
    auto c = *this;
    c.compress_ = v;
    return c;
  }
  constexpr FieldMeta type_id(TypeId v) const {
    auto c = *this;
    c.type_id_override_ = static_cast<int16_t>(v);
    return c;
  }
  constexpr FieldMeta int8_array() const { return type_id(TypeId::INT8_ARRAY); }
  constexpr FieldMeta uint8_array() const {
    return type_id(TypeId::UINT8_ARRAY);
  }

  // Convenience shortcuts for common encodings
  constexpr FieldMeta varint() const { return encoding(Encoding::Varint); }
  constexpr FieldMeta fixed() const { return encoding(Encoding::Fixed); }
  constexpr FieldMeta tagged() const { return encoding(Encoding::Tagged); }
};

/// Short factory functions for FieldMeta - use F() as a builder or F(id) for
/// tag
constexpr FieldMeta F() { return FieldMeta{}; }
constexpr FieldMeta F(int16_t id) { return FieldMeta{}.id(id); }

namespace detail {

// ============================================================================
// Config Normalization - Handle both integer IDs and FieldMeta
// ============================================================================

/// Normalize configuration: convert integer to FieldMeta, pass FieldMeta
/// through
template <typename T> constexpr auto normalize_config(T &&v) {
  if constexpr (std::is_integral_v<std::decay_t<T>>) {
    // Old syntax: just an integer ID
    return FieldMeta{}.id(static_cast<int16_t>(v));
  } else if constexpr (std::is_same_v<std::decay_t<T>, FieldMeta>) {
    // New syntax: already a FieldMeta
    return v;
  } else {
    static_assert(
        std::is_integral_v<std::decay_t<T>> ||
            std::is_same_v<std::decay_t<T>, FieldMeta>,
        "Field config must be an integer ID or FieldMeta (use F(id)...)");
    return FieldMeta{};
  }
}

/// Apply tag to FieldMeta
constexpr FieldMeta apply_tag(FieldMeta m, nullable) { return m.nullable(); }
constexpr FieldMeta apply_tag(FieldMeta m, not_null) {
  return m.nullable(false);
}
constexpr FieldMeta apply_tag(FieldMeta m, ref) { return m.ref(); }
template <bool V> constexpr FieldMeta apply_tag(FieldMeta m, dynamic<V>) {
  return m.dynamic(V);
}

/// Fold multiple tags onto a base config
template <typename... Tags>
constexpr FieldMeta apply_tags(FieldMeta base, Tags... tags) {
  ((base = apply_tag(base, tags)), ...);
  return base;
}

// ============================================================================
// FieldEntry - Stores Field Configuration Metadata
// ============================================================================

/// Field entry that stores name and configuration metadata
struct FieldEntry {
  const char *name; // Field name for debugging
  FieldMeta meta;   // Field configuration

  constexpr FieldEntry(const char *n, FieldMeta m) : name(n), meta(m) {}
};

/// Create a FieldEntry
constexpr auto make_field_entry(const char *name, FieldMeta meta) {
  return FieldEntry{name, meta};
}

/// Default: no field config defined for type T
template <typename T> struct ForyFieldConfigImpl {
  static constexpr bool has_config = false;
};

template <typename T>
using AdlFieldConfigDescriptor =
    decltype(ForyFieldConfig(std::declval<meta::Identity<T>>()));

template <typename T, typename = void>
struct HasAdlFieldConfig : std::false_type {};

template <typename T>
struct HasAdlFieldConfig<T, std::void_t<AdlFieldConfigDescriptor<T>>>
    : std::true_type {};

template <typename T, typename Enable = void> struct FieldConfigInfo {
  static constexpr bool has_config = false;
  static constexpr size_t field_count = 0;
  static inline constexpr auto entries = std::tuple<>{};
};

template <typename T>
struct FieldConfigInfo<T, std::enable_if_t<HasAdlFieldConfig<T>::value>> {
  using Descriptor = AdlFieldConfigDescriptor<T>;
  static constexpr bool has_config = Descriptor::has_config;
  static constexpr size_t field_count = Descriptor::field_count;
  static inline constexpr auto entries = Descriptor::entries;
};

template <typename T>
struct FieldConfigInfo<T,
                       std::enable_if_t<!HasAdlFieldConfig<T>::value &&
                                        ForyFieldConfigImpl<T>::has_config>> {
  static constexpr bool has_config = true;
  static constexpr size_t field_count = ForyFieldConfigImpl<T>::field_count;
  static inline constexpr auto entries = ForyFieldConfigImpl<T>::entries;
};

template <typename T>
inline constexpr bool has_field_config_v = FieldConfigInfo<T>::has_config;

/// Helper to get field encoding from FieldConfigInfo
template <typename T, size_t Index, typename = void>
struct GetFieldConfigEntry {
  static constexpr Encoding encoding = Encoding::Default;
  static constexpr int16_t id = -1;
  static constexpr bool nullable = false;
  static constexpr bool ref = false;
  static constexpr int dynamic_value = -1; // AUTO
  static constexpr bool compress = true;
  static constexpr int16_t type_id_override = -1;
  static constexpr bool has_entry = false;
};

template <typename T, size_t Index>
struct GetFieldConfigEntry<T, Index,
                           std::enable_if_t<FieldConfigInfo<T>::has_config>> {
private:
  static constexpr std::string_view field_name = FieldInfo<T>::Names[Index];

  template <size_t I = 0> static constexpr FieldEntry find_entry() {
    if constexpr (I >=
                  std::tuple_size_v<
                      std::decay_t<decltype(FieldConfigInfo<T>::entries)>>) {
      return FieldEntry{"", FieldMeta{}};
    } else {
      constexpr auto entry = std::get<I>(FieldConfigInfo<T>::entries);
      if (std::string_view{entry.name} == field_name) {
        return entry;
      }
      return find_entry<I + 1>();
    }
  }

public:
  static constexpr FieldEntry entry = find_entry<>();
  static constexpr Encoding encoding = entry.meta.encoding_;
  static constexpr int16_t id = entry.meta.id_;
  static constexpr bool nullable = entry.meta.nullable_;
  static constexpr bool ref = entry.meta.ref_;
  static constexpr int dynamic_value = entry.meta.dynamic_;
  static constexpr bool compress = entry.meta.compress_;
  static constexpr int16_t type_id_override = entry.meta.type_id_override_;
  static constexpr bool has_entry = entry.name[0] != '\0';
};

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
///   - SharedWeak<T>: Can use nullable and/or ref
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
                "fory::nullable is only valid for shared_ptr/SharedWeak/"
                "unique_ptr. "
                "Use std::optional<T> for nullable primitives/strings.");

  // Validate: not_null only for smart pointers (for now)
  static_assert(!detail::has_option_v<not_null, Options...> ||
                    detail::is_smart_ptr_v<T>,
                "fory::not_null is only valid for pointer types.");

  // Validate: ref only for shared_ptr/SharedWeak
  static_assert(!detail::has_option_v<ref, Options...> ||
                    detail::is_shared_ptr_v<T> || detail::is_shared_weak_v<T>,
                "fory::ref is only valid for shared_ptr/SharedWeak "
                "(reference tracking requires shared ownership).");

  // Validate: dynamic<V> only for smart pointers
  static_assert(!detail::has_dynamic_option_v<Options...> ||
                    detail::is_smart_ptr_v<T>,
                "fory::dynamic<V> is only valid for shared_ptr/SharedWeak/"
                "unique_ptr.");

  // Validate: no options for optional (inherently nullable)
  static_assert(!detail::is_optional_v<T> || sizeof...(Options) == 0,
                "std::optional<T> is inherently nullable. No options allowed.");

  // Validate: no options for non-smart-pointer types
  static_assert(detail::is_smart_ptr_v<T> || detail::is_optional_v<T> ||
                    sizeof...(Options) == 0,
                "Options are only valid for shared_ptr/SharedWeak/unique_ptr "
                "fields. "
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
  /// - It's std::shared_ptr or SharedWeak (default)
  /// - Or explicitly marked with fory::ref
  static constexpr bool track_ref = detail::is_shared_ptr_v<T> ||
                                    detail::is_shared_weak_v<T> ||
                                    detail::has_option_v<ref, Options...>;

  /// Dynamic type dispatch control:
  /// - -1 (AUTO): Use std::is_polymorphic<T> to decide
  /// - 0 (FALSE): Skip type info, use declared type directly
  /// - 1 (TRUE): Write type info, enable runtime subtype support
  static constexpr int dynamic_value = detail::get_dynamic_value_v<Options...>;

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
  static constexpr bool value =
      detail::is_shared_ptr_v<T> || detail::is_shared_weak_v<T>;
};

template <typename T, int16_t Id, typename... Options>
struct field_track_ref<field<T, Id, Options...>> {
  static constexpr bool value = field<T, Id, Options...>::track_ref;
};

template <typename T>
inline constexpr bool field_track_ref_v = field_track_ref<T>::value;

/// Get dynamic_value from field type (-1 = AUTO, 0 = FALSE, 1 = TRUE)
template <typename T> struct field_dynamic_value {
  static constexpr int value = -1; // AUTO
};

template <typename T, int16_t Id, typename... Options>
struct field_dynamic_value<field<T, Id, Options...>> {
  static constexpr int value = field<T, Id, Options...>::dynamic_value;
};

template <typename T>
inline constexpr int field_dynamic_value_v = field_dynamic_value<T>::value;

// ============================================================================
// FORY_FIELD_TAGS Macro Support
// ============================================================================

namespace detail {

// Helper to parse field tag entry from macro arguments
// Supports: (field, id), (field, id, nullable), (field, id, ref),
//           (field, id, nullable, ref), (field, id, dynamic<false>), etc.
template <typename FieldType, int16_t Id, typename... Options>
struct ParseFieldTagEntry {
  static constexpr bool is_nullable =
      is_optional_v<FieldType> ||
      (is_smart_ptr_v<FieldType> && has_option_v<nullable, Options...>);

  static constexpr bool track_ref = is_shared_ptr_v<FieldType> ||
                                    is_shared_weak_v<FieldType> ||
                                    has_option_v<ref, Options...>;

  static constexpr int dynamic_value = get_dynamic_value_v<Options...>;

  // Compile-time validation
  static_assert(!has_option_v<nullable, Options...> ||
                    is_smart_ptr_v<FieldType>,
                "fory::nullable is only valid for shared_ptr/SharedWeak/"
                "unique_ptr");

  static_assert(!has_option_v<ref, Options...> || is_shared_ptr_v<FieldType> ||
                    is_shared_weak_v<FieldType>,
                "fory::ref is only valid for shared_ptr/SharedWeak");

  static_assert(!has_dynamic_option_v<Options...> || is_smart_ptr_v<FieldType>,
                "fory::dynamic<V> is only valid for shared_ptr/SharedWeak/"
                "unique_ptr");

  using type = FieldTagEntry<Id, is_nullable, track_ref, dynamic_value>;
};

/// Get field tag entry by index from FieldTagsInfo
template <typename T, size_t Index, typename = void> struct GetFieldTagEntry {
  static constexpr int16_t id = -1;
  static constexpr bool is_nullable = false;
  static constexpr bool track_ref = false;
  static constexpr int dynamic_value = -1; // AUTO
  static constexpr bool has_entry = false;
};

template <typename T, size_t Index>
struct GetFieldTagEntry<
    T, Index,
    std::enable_if_t<FieldTagsInfo<T>::has_tags &&
                     (Index < FieldTagsInfo<T>::field_count) &&
                     FieldTagsInfo<T>::use_index>> {
  using Entry = std::tuple_element_t<Index, typename FieldTagsInfo<T>::Entries>;
  static constexpr int16_t id = Entry::id;
  static constexpr bool is_nullable = Entry::is_nullable;
  static constexpr bool track_ref = Entry::track_ref;
  static constexpr int dynamic_value = Entry::dynamic_value;
  static constexpr bool has_entry = true;
};

template <typename T, size_t Index>
struct GetFieldTagEntry<T, Index,
                        std::enable_if_t<FieldTagsInfo<T>::has_tags &&
                                         !FieldTagsInfo<T>::use_index>> {
private:
  static constexpr std::string_view field_name = FieldInfo<T>::Names[Index];

  template <size_t I = 0> static constexpr FieldTagEntryWithName find_entry() {
    if constexpr (I >= std::tuple_size_v<typename FieldTagsInfo<T>::Entries>) {
      return FieldTagEntryWithName{"", -1, false, false, -1};
    } else {
      constexpr auto entry = std::get<I>(FieldTagsInfo<T>::entries);
      if (std::string_view{entry.name} == field_name) {
        return entry;
      }
      return find_entry<I + 1>();
    }
  }

  static constexpr FieldTagEntryWithName entry = find_entry<>();

public:
  static constexpr int16_t id = entry.id;
  static constexpr bool is_nullable = entry.is_nullable;
  static constexpr bool track_ref = entry.track_ref;
  static constexpr int dynamic_value = entry.dynamic_value;
  static constexpr bool has_entry = entry.name[0] != '\0';
};

} // namespace detail

} // namespace fory

// ============================================================================
// FORY_FIELD_TAGS Macro Implementation
// ============================================================================

// Helper macros to extract parts from (field, id, ...) tuples
#define FORY_FT_FIELD(tuple) FORY_FT_FIELD_IMPL tuple
#define FORY_FT_FIELD_IMPL(field, ...) field

// Stringify field name
#define FORY_FT_STRINGIFY(x) FORY_FT_STRINGIFY_I(x)
#define FORY_FT_STRINGIFY_I(x) #x

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

#define FORY_FT_FIELD_INDEX(Type, tuple)                                       \
  ::fory::detail::FieldIndex<Type>(                                            \
      std::string_view{FORY_FT_STRINGIFY(FORY_FT_FIELD(tuple))})

#define FORY_FT_FIELD_TYPE(Type, tuple)                                        \
  typename ::fory::detail::FieldTypeAt<Type,                                   \
                                       FORY_FT_FIELD_INDEX(Type, tuple)>::type

#define FORY_FT_MAKE_ENTRY_2(Type, tuple)                                      \
  ::fory::detail::make_field_tag_entry<                                        \
      typename ::fory::detail::ParseFieldTagEntry<                             \
          FORY_FT_FIELD_TYPE(Type, tuple), FORY_FT_ID(tuple)>::type>(          \
      FORY_FT_STRINGIFY(FORY_FT_FIELD(tuple)))

#define FORY_FT_MAKE_ENTRY_3(Type, tuple)                                      \
  ::fory::detail::make_field_tag_entry<                                        \
      typename ::fory::detail::ParseFieldTagEntry<                             \
          FORY_FT_FIELD_TYPE(Type, tuple), FORY_FT_ID(tuple),                  \
          ::fory::FORY_FT_GET_OPT1(tuple)>::type>(                             \
      FORY_FT_STRINGIFY(FORY_FT_FIELD(tuple)))

#define FORY_FT_MAKE_ENTRY_4(Type, tuple)                                      \
  ::fory::detail::make_field_tag_entry<                                        \
      typename ::fory::detail::ParseFieldTagEntry<                             \
          FORY_FT_FIELD_TYPE(Type, tuple), FORY_FT_ID(tuple),                  \
          ::fory::FORY_FT_GET_OPT1(tuple),                                     \
          ::fory::FORY_FT_GET_OPT2(tuple)>::type>(                             \
      FORY_FT_STRINGIFY(FORY_FT_FIELD(tuple)))

#define FORY_FT_MAKE_ENTRY_5(Type, tuple)                                      \
  ::fory::detail::make_field_tag_entry<                                        \
      typename ::fory::detail::ParseFieldTagEntry<                             \
          FORY_FT_FIELD_TYPE(Type, tuple), FORY_FT_ID(tuple),                  \
          ::fory::FORY_FT_GET_OPT1(tuple), ::fory::FORY_FT_GET_OPT2(tuple),    \
          ::fory::FORY_FT_GET_OPT3(tuple)>::type>(                             \
      FORY_FT_STRINGIFY(FORY_FT_FIELD(tuple)))

// Main macro: FORY_FIELD_TAGS(Type, (field1, id1), (field2, id2, nullable),...)
#define FORY_FT_DESCRIPTOR_NAME(line)                                          \
  FORY_PP_CONCAT(ForyFieldTagsDescriptor_, line)
#define FORY_FIELD_TAGS(Type, ...)                                             \
  FORY_FIELD_TAGS_IMPL(__LINE__, Type, __VA_ARGS__)
#define FORY_FIELD_TAGS_IMPL(line, Type, ...)                                  \
  struct FORY_FT_DESCRIPTOR_NAME(line) {                                       \
    static constexpr bool has_tags = true;                                     \
    static inline constexpr auto entries =                                     \
        std::make_tuple(FORY_FT_ENTRIES(Type, __VA_ARGS__));                   \
    using Entries = std::decay_t<decltype(entries)>;                           \
    static constexpr size_t field_count = std::tuple_size_v<Entries>;          \
  };                                                                           \
  constexpr auto ForyFieldTags(::fory::meta::Identity<Type>) {                 \
    return FORY_FT_DESCRIPTOR_NAME(line){};                                    \
  }                                                                            \
  static_assert(true)

// Helper to generate entries tuple content using indirect expansion pattern
// This ensures FORY_PP_NARG is fully expanded before concatenation
#define FORY_FT_ENTRIES(Type, ...)                                             \
  FORY_FT_ENTRIES_I(Type, FORY_PP_NARG(__VA_ARGS__), __VA_ARGS__)
#define FORY_FT_ENTRIES_I(Type, N, ...) FORY_FT_ENTRIES_II(Type, N, __VA_ARGS__)
#define FORY_FT_ENTRIES_II(Type, N, ...) FORY_FT_ENTRIES_##N(Type, __VA_ARGS__)

// Generate entries for 1-64 fields
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
#define FORY_FT_ENTRIES_17(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17)                       \
  FORY_FT_ENTRIES_16(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16),                                      \
      FORY_FT_MAKE_ENTRY(T, _17)
#define FORY_FT_ENTRIES_18(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18)                  \
  FORY_FT_ENTRIES_17(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17),                                 \
      FORY_FT_MAKE_ENTRY(T, _18)
#define FORY_FT_ENTRIES_19(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19)             \
  FORY_FT_ENTRIES_18(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18),                            \
      FORY_FT_MAKE_ENTRY(T, _19)
#define FORY_FT_ENTRIES_20(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20)        \
  FORY_FT_ENTRIES_19(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19),                       \
      FORY_FT_MAKE_ENTRY(T, _20)
#define FORY_FT_ENTRIES_21(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21)   \
  FORY_FT_ENTRIES_20(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20),                  \
      FORY_FT_MAKE_ENTRY(T, _21)
#define FORY_FT_ENTRIES_22(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22)                                                \
  FORY_FT_ENTRIES_21(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21),             \
      FORY_FT_MAKE_ENTRY(T, _22)
#define FORY_FT_ENTRIES_23(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23)                                           \
  FORY_FT_ENTRIES_22(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22),        \
      FORY_FT_MAKE_ENTRY(T, _23)
#define FORY_FT_ENTRIES_24(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24)                                      \
  FORY_FT_ENTRIES_23(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23),   \
      FORY_FT_MAKE_ENTRY(T, _24)
#define FORY_FT_ENTRIES_25(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25)                                 \
  FORY_FT_ENTRIES_24(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24),                                                     \
      FORY_FT_MAKE_ENTRY(T, _25)
#define FORY_FT_ENTRIES_26(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26)                            \
  FORY_FT_ENTRIES_25(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25),                                                \
      FORY_FT_MAKE_ENTRY(T, _26)
#define FORY_FT_ENTRIES_27(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27)                       \
  FORY_FT_ENTRIES_26(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26),                                           \
      FORY_FT_MAKE_ENTRY(T, _27)
#define FORY_FT_ENTRIES_28(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28)                  \
  FORY_FT_ENTRIES_27(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27),                                      \
      FORY_FT_MAKE_ENTRY(T, _28)
#define FORY_FT_ENTRIES_29(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29)             \
  FORY_FT_ENTRIES_28(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28),                                 \
      FORY_FT_MAKE_ENTRY(T, _29)
#define FORY_FT_ENTRIES_30(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30)        \
  FORY_FT_ENTRIES_29(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29),                            \
      FORY_FT_MAKE_ENTRY(T, _30)
#define FORY_FT_ENTRIES_31(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31)   \
  FORY_FT_ENTRIES_30(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30),                       \
      FORY_FT_MAKE_ENTRY(T, _31)
#define FORY_FT_ENTRIES_32(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32)                                                \
  FORY_FT_ENTRIES_31(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31),                  \
      FORY_FT_MAKE_ENTRY(T, _32)
#define FORY_FT_ENTRIES_33(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33)                                           \
  FORY_FT_ENTRIES_32(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32),             \
      FORY_FT_MAKE_ENTRY(T, _33)
#define FORY_FT_ENTRIES_34(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34)                                      \
  FORY_FT_ENTRIES_33(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33),        \
      FORY_FT_MAKE_ENTRY(T, _34)
#define FORY_FT_ENTRIES_35(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35)                                 \
  FORY_FT_ENTRIES_34(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34),   \
      FORY_FT_MAKE_ENTRY(T, _35)
#define FORY_FT_ENTRIES_36(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36)                            \
  FORY_FT_ENTRIES_35(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35),                                                     \
      FORY_FT_MAKE_ENTRY(T, _36)
#define FORY_FT_ENTRIES_37(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37)                       \
  FORY_FT_ENTRIES_36(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36),                                                \
      FORY_FT_MAKE_ENTRY(T, _37)
#define FORY_FT_ENTRIES_38(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38)                  \
  FORY_FT_ENTRIES_37(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37),                                           \
      FORY_FT_MAKE_ENTRY(T, _38)
#define FORY_FT_ENTRIES_39(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39)             \
  FORY_FT_ENTRIES_38(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38),                                      \
      FORY_FT_MAKE_ENTRY(T, _39)
#define FORY_FT_ENTRIES_40(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40)        \
  FORY_FT_ENTRIES_39(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39),                                 \
      FORY_FT_MAKE_ENTRY(T, _40)
#define FORY_FT_ENTRIES_41(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41)   \
  FORY_FT_ENTRIES_40(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40),                            \
      FORY_FT_MAKE_ENTRY(T, _41)
#define FORY_FT_ENTRIES_42(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42)                     \
  FORY_FT_ENTRIES_41(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41),                       \
      FORY_FT_MAKE_ENTRY(T, _42)
#define FORY_FT_ENTRIES_43(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43)                \
  FORY_FT_ENTRIES_42(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42),                  \
      FORY_FT_MAKE_ENTRY(T, _43)
#define FORY_FT_ENTRIES_44(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44)           \
  FORY_FT_ENTRIES_43(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43),             \
      FORY_FT_MAKE_ENTRY(T, _44)
#define FORY_FT_ENTRIES_45(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45)      \
  FORY_FT_ENTRIES_44(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44),        \
      FORY_FT_MAKE_ENTRY(T, _45)
#define FORY_FT_ENTRIES_46(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46) \
  FORY_FT_ENTRIES_45(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45),   \
      FORY_FT_MAKE_ENTRY(T, _46)
#define FORY_FT_ENTRIES_47(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47)                       \
  FORY_FT_ENTRIES_46(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46),                                                     \
      FORY_FT_MAKE_ENTRY(T, _47)
#define FORY_FT_ENTRIES_48(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47, _48)                  \
  FORY_FT_ENTRIES_47(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47),                                                \
      FORY_FT_MAKE_ENTRY(T, _48)
#define FORY_FT_ENTRIES_49(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47, _48, _49)             \
  FORY_FT_ENTRIES_48(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48),                                           \
      FORY_FT_MAKE_ENTRY(T, _49)
#define FORY_FT_ENTRIES_50(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47, _48, _49, _50)        \
  FORY_FT_ENTRIES_49(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49),                                      \
      FORY_FT_MAKE_ENTRY(T, _50)
#define FORY_FT_ENTRIES_51(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47, _48, _49, _50, _51)   \
  FORY_FT_ENTRIES_50(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50),                                 \
      FORY_FT_MAKE_ENTRY(T, _51)
#define FORY_FT_ENTRIES_52(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52)                                              \
  FORY_FT_ENTRIES_51(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51),                            \
      FORY_FT_MAKE_ENTRY(T, _52)
#define FORY_FT_ENTRIES_53(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53)                                         \
  FORY_FT_ENTRIES_52(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52),                       \
      FORY_FT_MAKE_ENTRY(T, _53)
#define FORY_FT_ENTRIES_54(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54)                                    \
  FORY_FT_ENTRIES_53(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53),                  \
      FORY_FT_MAKE_ENTRY(T, _54)
#define FORY_FT_ENTRIES_55(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55)                               \
  FORY_FT_ENTRIES_54(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54),             \
      FORY_FT_MAKE_ENTRY(T, _55)
#define FORY_FT_ENTRIES_56(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56)                          \
  FORY_FT_ENTRIES_55(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55),        \
      FORY_FT_MAKE_ENTRY(T, _56)
#define FORY_FT_ENTRIES_57(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57)                     \
  FORY_FT_ENTRIES_56(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56),   \
      FORY_FT_MAKE_ENTRY(T, _57)
#define FORY_FT_ENTRIES_58(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58)                \
  FORY_FT_ENTRIES_57(                                                          \
      T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15,     \
      _16, _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29,    \
      _30, _31, _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43,    \
      _44, _45, _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57),   \
      FORY_FT_MAKE_ENTRY(T, _58)
#define FORY_FT_ENTRIES_59(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59)           \
  FORY_FT_ENTRIES_58(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58),                                                \
      FORY_FT_MAKE_ENTRY(T, _59)
#define FORY_FT_ENTRIES_60(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60)      \
  FORY_FT_ENTRIES_59(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59),                                           \
      FORY_FT_MAKE_ENTRY(T, _60)
#define FORY_FT_ENTRIES_61(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60, _61) \
  FORY_FT_ENTRIES_60(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59, _60),                                      \
      FORY_FT_MAKE_ENTRY(T, _61)
#define FORY_FT_ENTRIES_62(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60, _61, \
    _62)                                                                       \
  FORY_FT_ENTRIES_61(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59, _60, _61),                                 \
      FORY_FT_MAKE_ENTRY(T, _62)
#define FORY_FT_ENTRIES_63(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60, _61, \
    _62, _63)                                                                  \
  FORY_FT_ENTRIES_62(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59, _60, _61, _62),                            \
      FORY_FT_MAKE_ENTRY(T, _63)
#define FORY_FT_ENTRIES_64(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60, _61, \
    _62, _63, _64)                                                             \
  FORY_FT_ENTRIES_63(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59, _60, _61, _62, _63),                       \
      FORY_FT_MAKE_ENTRY(T, _64)

// ============================================================================
// FORY_FIELD_CONFIG Macro - New Syntax with Member Pointer Verification
// ============================================================================
//
// Usage:
//   FORY_FIELD_CONFIG(MyStruct, MyStruct,
//       (field1, F(0)),                        // Simple: just ID
//       (field2, F(1).nullable()),             // With nullable
//       (field3, F(2).varint()),               // With encoding
//       (field4, F(3).nullable().ref()),       // Multiple options
//       (field5, 4)                            // Backward compatible: integer
//       ID
//   );
//
// This macro:
// 1. Verifies field names exist at compile time via member pointers
// 2. Supports both integer IDs (old) and F(id).xxx() builder (new)
// 3. Stores configuration in a constexpr tuple for efficient access

// Helper to stringify field name
#define FORY_FC_STRINGIFY(x) FORY_FC_STRINGIFY_I(x)
#define FORY_FC_STRINGIFY_I(x) #x

// Extract field name (first element of tuple)
#define FORY_FC_NAME(tuple) FORY_FC_NAME_IMPL tuple
#define FORY_FC_NAME_IMPL(name, ...) name

// Extract config (second element of tuple)
#define FORY_FC_CONFIG(tuple) FORY_FC_CONFIG_IMPL tuple
#define FORY_FC_CONFIG_IMPL(name, config, ...) config

// Create a FieldEntry with member pointer verification
#define FORY_FC_MAKE_ENTRY(Type, tuple)                                        \
  ::fory::detail::make_field_entry(                                            \
      FORY_FC_STRINGIFY(FORY_FC_NAME(tuple)),                                  \
      ::fory::detail::normalize_config(FORY_FC_CONFIG(tuple)))

// Generate entries using indirect expansion
#define FORY_FC_ENTRIES(Type, ...)                                             \
  FORY_FC_ENTRIES_I(Type, FORY_PP_NARG(__VA_ARGS__), __VA_ARGS__)
#define FORY_FC_ENTRIES_I(Type, N, ...) FORY_FC_ENTRIES_II(Type, N, __VA_ARGS__)
#define FORY_FC_ENTRIES_II(Type, N, ...) FORY_FC_ENTRIES_##N(Type, __VA_ARGS__)

// Generate entries for 1-64 fields
#define FORY_FC_ENTRIES_1(T, _1) FORY_FC_MAKE_ENTRY(T, _1)
#define FORY_FC_ENTRIES_2(T, _1, _2)                                           \
  FORY_FC_MAKE_ENTRY(T, _1), FORY_FC_MAKE_ENTRY(T, _2)
#define FORY_FC_ENTRIES_3(T, _1, _2, _3)                                       \
  FORY_FC_ENTRIES_2(T, _1, _2), FORY_FC_MAKE_ENTRY(T, _3)
#define FORY_FC_ENTRIES_4(T, _1, _2, _3, _4)                                   \
  FORY_FC_ENTRIES_3(T, _1, _2, _3), FORY_FC_MAKE_ENTRY(T, _4)
#define FORY_FC_ENTRIES_5(T, _1, _2, _3, _4, _5)                               \
  FORY_FC_ENTRIES_4(T, _1, _2, _3, _4), FORY_FC_MAKE_ENTRY(T, _5)
#define FORY_FC_ENTRIES_6(T, _1, _2, _3, _4, _5, _6)                           \
  FORY_FC_ENTRIES_5(T, _1, _2, _3, _4, _5), FORY_FC_MAKE_ENTRY(T, _6)
#define FORY_FC_ENTRIES_7(T, _1, _2, _3, _4, _5, _6, _7)                       \
  FORY_FC_ENTRIES_6(T, _1, _2, _3, _4, _5, _6), FORY_FC_MAKE_ENTRY(T, _7)
#define FORY_FC_ENTRIES_8(T, _1, _2, _3, _4, _5, _6, _7, _8)                   \
  FORY_FC_ENTRIES_7(T, _1, _2, _3, _4, _5, _6, _7), FORY_FC_MAKE_ENTRY(T, _8)
#define FORY_FC_ENTRIES_9(T, _1, _2, _3, _4, _5, _6, _7, _8, _9)               \
  FORY_FC_ENTRIES_8(T, _1, _2, _3, _4, _5, _6, _7, _8),                        \
      FORY_FC_MAKE_ENTRY(T, _9)
#define FORY_FC_ENTRIES_10(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10)         \
  FORY_FC_ENTRIES_9(T, _1, _2, _3, _4, _5, _6, _7, _8, _9),                    \
      FORY_FC_MAKE_ENTRY(T, _10)
#define FORY_FC_ENTRIES_11(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11)    \
  FORY_FC_ENTRIES_10(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10),              \
      FORY_FC_MAKE_ENTRY(T, _11)
#define FORY_FC_ENTRIES_12(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12)                                                \
  FORY_FC_ENTRIES_11(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11),         \
      FORY_FC_MAKE_ENTRY(T, _12)
#define FORY_FC_ENTRIES_13(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13)                                           \
  FORY_FC_ENTRIES_12(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12),    \
      FORY_FC_MAKE_ENTRY(T, _13)
#define FORY_FC_ENTRIES_14(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14)                                      \
  FORY_FC_ENTRIES_13(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13),                                                     \
      FORY_FC_MAKE_ENTRY(T, _14)
#define FORY_FC_ENTRIES_15(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15)                                 \
  FORY_FC_ENTRIES_14(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14),                                                \
      FORY_FC_MAKE_ENTRY(T, _15)
#define FORY_FC_ENTRIES_16(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16)                            \
  FORY_FC_ENTRIES_15(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15),                                           \
      FORY_FC_MAKE_ENTRY(T, _16)
#define FORY_FC_ENTRIES_17(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17)                       \
  FORY_FC_ENTRIES_16(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16),                                      \
      FORY_FC_MAKE_ENTRY(T, _17)
#define FORY_FC_ENTRIES_18(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18)                  \
  FORY_FC_ENTRIES_17(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17),                                 \
      FORY_FC_MAKE_ENTRY(T, _18)
#define FORY_FC_ENTRIES_19(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19)             \
  FORY_FC_ENTRIES_18(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18),                            \
      FORY_FC_MAKE_ENTRY(T, _19)
#define FORY_FC_ENTRIES_20(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20)        \
  FORY_FC_ENTRIES_19(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19),                       \
      FORY_FC_MAKE_ENTRY(T, _20)
#define FORY_FC_ENTRIES_21(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21)   \
  FORY_FC_ENTRIES_20(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20),                  \
      FORY_FC_MAKE_ENTRY(T, _21)
#define FORY_FC_ENTRIES_22(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22)                                                \
  FORY_FC_ENTRIES_21(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21),             \
      FORY_FC_MAKE_ENTRY(T, _22)
#define FORY_FC_ENTRIES_23(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23)                                           \
  FORY_FC_ENTRIES_22(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22),        \
      FORY_FC_MAKE_ENTRY(T, _23)
#define FORY_FC_ENTRIES_24(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24)                                      \
  FORY_FC_ENTRIES_23(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23),   \
      FORY_FC_MAKE_ENTRY(T, _24)
#define FORY_FC_ENTRIES_25(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25)                                 \
  FORY_FC_ENTRIES_24(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24),                                                     \
      FORY_FC_MAKE_ENTRY(T, _25)
#define FORY_FC_ENTRIES_26(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26)                            \
  FORY_FC_ENTRIES_25(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25),                                                \
      FORY_FC_MAKE_ENTRY(T, _26)
#define FORY_FC_ENTRIES_27(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27)                       \
  FORY_FC_ENTRIES_26(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26),                                           \
      FORY_FC_MAKE_ENTRY(T, _27)
#define FORY_FC_ENTRIES_28(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28)                  \
  FORY_FC_ENTRIES_27(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27),                                      \
      FORY_FC_MAKE_ENTRY(T, _28)
#define FORY_FC_ENTRIES_29(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29)             \
  FORY_FC_ENTRIES_28(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28),                                 \
      FORY_FC_MAKE_ENTRY(T, _29)
#define FORY_FC_ENTRIES_30(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30)        \
  FORY_FC_ENTRIES_29(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29),                            \
      FORY_FC_MAKE_ENTRY(T, _30)
#define FORY_FC_ENTRIES_31(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31)   \
  FORY_FC_ENTRIES_30(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30),                       \
      FORY_FC_MAKE_ENTRY(T, _31)
#define FORY_FC_ENTRIES_32(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32)                                                \
  FORY_FC_ENTRIES_31(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31),                  \
      FORY_FC_MAKE_ENTRY(T, _32)
#define FORY_FC_ENTRIES_33(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33)                                           \
  FORY_FC_ENTRIES_32(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32),             \
      FORY_FC_MAKE_ENTRY(T, _33)
#define FORY_FC_ENTRIES_34(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34)                                      \
  FORY_FC_ENTRIES_33(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33),        \
      FORY_FC_MAKE_ENTRY(T, _34)
#define FORY_FC_ENTRIES_35(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35)                                 \
  FORY_FC_ENTRIES_34(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34),   \
      FORY_FC_MAKE_ENTRY(T, _35)
#define FORY_FC_ENTRIES_36(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36)                            \
  FORY_FC_ENTRIES_35(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35),                                                     \
      FORY_FC_MAKE_ENTRY(T, _36)
#define FORY_FC_ENTRIES_37(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37)                       \
  FORY_FC_ENTRIES_36(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36),                                                \
      FORY_FC_MAKE_ENTRY(T, _37)
#define FORY_FC_ENTRIES_38(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38)                  \
  FORY_FC_ENTRIES_37(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37),                                           \
      FORY_FC_MAKE_ENTRY(T, _38)
#define FORY_FC_ENTRIES_39(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39)             \
  FORY_FC_ENTRIES_38(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38),                                      \
      FORY_FC_MAKE_ENTRY(T, _39)
#define FORY_FC_ENTRIES_40(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40)        \
  FORY_FC_ENTRIES_39(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39),                                 \
      FORY_FC_MAKE_ENTRY(T, _40)
#define FORY_FC_ENTRIES_41(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41)   \
  FORY_FC_ENTRIES_40(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40),                            \
      FORY_FC_MAKE_ENTRY(T, _41)
#define FORY_FC_ENTRIES_42(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42)                     \
  FORY_FC_ENTRIES_41(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41),                       \
      FORY_FC_MAKE_ENTRY(T, _42)
#define FORY_FC_ENTRIES_43(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43)                \
  FORY_FC_ENTRIES_42(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42),                  \
      FORY_FC_MAKE_ENTRY(T, _43)
#define FORY_FC_ENTRIES_44(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44)           \
  FORY_FC_ENTRIES_43(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43),             \
      FORY_FC_MAKE_ENTRY(T, _44)
#define FORY_FC_ENTRIES_45(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45)      \
  FORY_FC_ENTRIES_44(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44),        \
      FORY_FC_MAKE_ENTRY(T, _45)
#define FORY_FC_ENTRIES_46(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46) \
  FORY_FC_ENTRIES_45(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45),   \
      FORY_FC_MAKE_ENTRY(T, _46)
#define FORY_FC_ENTRIES_47(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47)                       \
  FORY_FC_ENTRIES_46(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46),                                                     \
      FORY_FC_MAKE_ENTRY(T, _47)
#define FORY_FC_ENTRIES_48(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47, _48)                  \
  FORY_FC_ENTRIES_47(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47),                                                \
      FORY_FC_MAKE_ENTRY(T, _48)
#define FORY_FC_ENTRIES_49(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47, _48, _49)             \
  FORY_FC_ENTRIES_48(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48),                                           \
      FORY_FC_MAKE_ENTRY(T, _49)
#define FORY_FC_ENTRIES_50(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47, _48, _49, _50)        \
  FORY_FC_ENTRIES_49(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49),                                      \
      FORY_FC_MAKE_ENTRY(T, _50)
#define FORY_FC_ENTRIES_51(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11,    \
                           _12, _13, _14, _15, _16, _17, _18, _19, _20, _21,   \
                           _22, _23, _24, _25, _26, _27, _28, _29, _30, _31,   \
                           _32, _33, _34, _35, _36, _37, _38, _39, _40, _41,   \
                           _42, _43, _44, _45, _46, _47, _48, _49, _50, _51)   \
  FORY_FC_ENTRIES_50(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50),                                 \
      FORY_FC_MAKE_ENTRY(T, _51)
#define FORY_FC_ENTRIES_52(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52)                                              \
  FORY_FC_ENTRIES_51(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51),                            \
      FORY_FC_MAKE_ENTRY(T, _52)
#define FORY_FC_ENTRIES_53(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53)                                         \
  FORY_FC_ENTRIES_52(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52),                       \
      FORY_FC_MAKE_ENTRY(T, _53)
#define FORY_FC_ENTRIES_54(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54)                                    \
  FORY_FC_ENTRIES_53(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53),                  \
      FORY_FC_MAKE_ENTRY(T, _54)
#define FORY_FC_ENTRIES_55(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55)                               \
  FORY_FC_ENTRIES_54(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54),             \
      FORY_FC_MAKE_ENTRY(T, _55)
#define FORY_FC_ENTRIES_56(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56)                          \
  FORY_FC_ENTRIES_55(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55),        \
      FORY_FC_MAKE_ENTRY(T, _56)
#define FORY_FC_ENTRIES_57(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57)                     \
  FORY_FC_ENTRIES_56(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56),   \
      FORY_FC_MAKE_ENTRY(T, _57)
#define FORY_FC_ENTRIES_58(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58)                \
  FORY_FC_ENTRIES_57(                                                          \
      T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15,     \
      _16, _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29,    \
      _30, _31, _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43,    \
      _44, _45, _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57),   \
      FORY_FC_MAKE_ENTRY(T, _58)
#define FORY_FC_ENTRIES_59(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59)           \
  FORY_FC_ENTRIES_58(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58),                                                \
      FORY_FC_MAKE_ENTRY(T, _59)
#define FORY_FC_ENTRIES_60(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60)      \
  FORY_FC_ENTRIES_59(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59),                                           \
      FORY_FC_MAKE_ENTRY(T, _60)
#define FORY_FC_ENTRIES_61(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60, _61) \
  FORY_FC_ENTRIES_60(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59, _60),                                      \
      FORY_FC_MAKE_ENTRY(T, _61)
#define FORY_FC_ENTRIES_62(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60, _61, \
    _62)                                                                       \
  FORY_FC_ENTRIES_61(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59, _60, _61),                                 \
      FORY_FC_MAKE_ENTRY(T, _62)
#define FORY_FC_ENTRIES_63(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60, _61, \
    _62, _63)                                                                  \
  FORY_FC_ENTRIES_62(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59, _60, _61, _62),                            \
      FORY_FC_MAKE_ENTRY(T, _63)
#define FORY_FC_ENTRIES_64(                                                    \
    T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16,  \
    _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, \
    _32, _33, _34, _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45, _46, \
    _47, _48, _49, _50, _51, _52, _53, _54, _55, _56, _57, _58, _59, _60, _61, \
    _62, _63, _64)                                                             \
  FORY_FC_ENTRIES_63(T, _1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12,     \
                     _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23,    \
                     _24, _25, _26, _27, _28, _29, _30, _31, _32, _33, _34,    \
                     _35, _36, _37, _38, _39, _40, _41, _42, _43, _44, _45,    \
                     _46, _47, _48, _49, _50, _51, _52, _53, _54, _55, _56,    \
                     _57, _58, _59, _60, _61, _62, _63),                       \
      FORY_FC_MAKE_ENTRY(T, _64)

// Main FORY_FIELD_CONFIG macro
// Creates a constexpr tuple of FieldEntry objects with member pointer
// verification. Alias is a token-safe name without '::'.
#define FORY_FC_DESCRIPTOR_NAME(Alias)                                         \
  FORY_PP_CONCAT(ForyFieldConfigDescriptor_, Alias)
#define FORY_FIELD_CONFIG(Type, Alias, ...)                                    \
  struct FORY_FC_DESCRIPTOR_NAME(Alias) {                                      \
    static constexpr bool has_config = true;                                   \
    static inline constexpr auto entries =                                     \
        std::make_tuple(FORY_FC_ENTRIES(Type, __VA_ARGS__));                   \
    static constexpr size_t field_count =                                      \
        std::tuple_size_v<std::decay_t<decltype(entries)>>;                    \
  };                                                                           \
  constexpr auto ForyFieldConfig(::fory::meta::Identity<Type>) {               \
    return FORY_FC_DESCRIPTOR_NAME(Alias){};                                   \
  }                                                                            \
  static_assert(true)
