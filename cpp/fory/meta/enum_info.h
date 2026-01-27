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
#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string_view>
#include <type_traits>

namespace fory {
namespace meta {

namespace detail {

template <typename Enum>
using AdlEnumInfoDescriptor =
    decltype(ForyEnumInfo(std::declval<Identity<Enum>>()));

template <typename Enum, typename = void>
struct HasAdlEnumInfo : std::false_type {};

template <typename Enum>
struct HasAdlEnumInfo<Enum, std::void_t<AdlEnumInfoDescriptor<Enum>>>
    : std::true_type {};

} // namespace detail

/// Compile-time metadata for enums registered with FORY_ENUM.
/// Default implementation assumes no metadata is available.
template <typename Enum, typename Enable = void> struct EnumInfo {
  using EnumType = Enum;
  static constexpr bool defined = false;
  static constexpr std::size_t size = 0;

  static inline constexpr std::array<EnumType, 0> values = {};
  static inline constexpr std::array<std::string_view, 0> names = {};

  static constexpr bool contains(EnumType) { return false; }

  static constexpr std::size_t ordinal(EnumType) { return size; }

  static constexpr EnumType value_at(std::size_t) { return EnumType{}; }

  static constexpr std::string_view name(EnumType) {
    return std::string_view();
  }
};

template <typename Enum>
struct EnumInfo<Enum, std::enable_if_t<detail::HasAdlEnumInfo<Enum>::value>> {
  using Descriptor = detail::AdlEnumInfoDescriptor<Enum>;
  using EnumType = Enum;
  static constexpr bool defined = Descriptor::defined;
  static constexpr std::size_t size = Descriptor::size;

  static inline constexpr std::array<EnumType, size> values =
      Descriptor::values;
  static inline constexpr std::array<std::string_view, size> names =
      Descriptor::names;

  static constexpr bool contains(EnumType value) {
    return Descriptor::contains(value);
  }

  static constexpr std::size_t ordinal(EnumType value) {
    return Descriptor::ordinal(value);
  }

  static constexpr EnumType value_at(std::size_t index) {
    return Descriptor::value_at(index);
  }

  static constexpr std::string_view name(EnumType value) {
    return Descriptor::name(value);
  }
};

/// Metadata helpers that map enums to contiguous ordinals when metadata is
/// available, falling back to naive casts otherwise. All functions return
/// false on failure to support lightweight error handling that callers can
/// adapt to their context.
template <typename Enum, typename Enable = void> struct EnumMetadata {
  using OrdinalType = std::underlying_type_t<Enum>;

  static inline bool to_ordinal(Enum value, OrdinalType *out) {
    *out = static_cast<OrdinalType>(value);
    return true;
  }

  static inline bool from_ordinal(OrdinalType ordinal, Enum *out) {
    *out = static_cast<Enum>(ordinal);
    return true;
  }
};

template <typename Enum>
struct EnumMetadata<Enum, std::enable_if_t<EnumInfo<Enum>::defined>> {
  using OrdinalType = std::underlying_type_t<Enum>;

  static inline bool to_ordinal(Enum value, OrdinalType *out) {
    const std::size_t ordinal = EnumInfo<Enum>::ordinal(value);
    if (ordinal == EnumInfo<Enum>::size) {
      return false;
    }
    if (ordinal >
        static_cast<std::size_t>(std::numeric_limits<OrdinalType>::max())) {
      return false;
    }
    *out = static_cast<OrdinalType>(ordinal);
    return true;
  }

  static inline bool from_ordinal(OrdinalType ordinal, Enum *out) {
    if constexpr (std::is_signed_v<OrdinalType>) {
      if (ordinal < 0) {
        return false;
      }
    }
    using Unsigned = std::make_unsigned_t<OrdinalType>;
    const auto index = static_cast<std::size_t>(static_cast<Unsigned>(ordinal));
    if (index >= EnumInfo<Enum>::size) {
      return false;
    }
    *out = EnumInfo<Enum>::value_at(index);
    return true;
  }
};

} // namespace meta
} // namespace fory

// ============================================================================
// Enum Registration Macros
// ============================================================================

#define FORY_INTERNAL_ENUM_VALUE_ENTRY(EnumType, value) EnumType::value,

#define FORY_INTERNAL_ENUM_NAME_ENTRY(EnumType, value)                         \
  std::string_view(FORY_PP_STRINGIFY(EnumType::value)),

#define FORY_ENUM_DESCRIPTOR_NAME(line)                                        \
  FORY_PP_CONCAT(ForyEnumInfoDescriptor_, line)

#define FORY_INTERNAL_ENUM_DEFINE(EnumType, ...)                               \
  FORY_INTERNAL_ENUM_DEFINE_IMPL(__LINE__, EnumType, __VA_ARGS__)

#define FORY_INTERNAL_ENUM_DEFINE_IMPL(line, EnumType, ...)                    \
  struct FORY_ENUM_DESCRIPTOR_NAME(line) {                                     \
    using Enum = EnumType;                                                     \
    static constexpr bool defined = true;                                      \
    static constexpr std::size_t size = FORY_PP_NARG(__VA_ARGS__);             \
    static inline constexpr std::array<Enum, size> values = {                  \
        FORY_PP_FOREACH_1(FORY_INTERNAL_ENUM_VALUE_ENTRY, EnumType,            \
                          __VA_ARGS__)};                                       \
    static inline constexpr std::array<std::string_view, size> names = {       \
        FORY_PP_FOREACH_1(FORY_INTERNAL_ENUM_NAME_ENTRY, EnumType,             \
                          __VA_ARGS__)};                                       \
                                                                               \
    static constexpr bool contains(Enum value) {                               \
      for (std::size_t i = 0; i < size; ++i) {                                 \
        if (values[i] == value) {                                              \
          return true;                                                         \
        }                                                                      \
      }                                                                        \
      return false;                                                            \
    }                                                                          \
                                                                               \
    static constexpr std::size_t ordinal(Enum value) {                         \
      for (std::size_t i = 0; i < size; ++i) {                                 \
        if (values[i] == value) {                                              \
          return i;                                                            \
        }                                                                      \
      }                                                                        \
      return size;                                                             \
    }                                                                          \
                                                                               \
    static constexpr Enum value_at(std::size_t index) {                        \
      return values[index];                                                    \
    }                                                                          \
                                                                               \
    static constexpr std::string_view name(Enum value) {                       \
      for (std::size_t i = 0; i < size; ++i) {                                 \
        if (values[i] == value) {                                              \
          return names[i];                                                     \
        }                                                                      \
      }                                                                        \
      return std::string_view();                                               \
    }                                                                          \
  };                                                                           \
  constexpr auto ForyEnumInfo(::fory::meta::Identity<EnumType>) {              \
    return FORY_ENUM_DESCRIPTOR_NAME(line){};                                  \
  }                                                                            \
  static_assert(true)

/// Register an enum's enumerators to enable compile-time metadata queries.
/// TypeIndex uses the fallback (type_fallback_hash based on PRETTY_FUNCTION)
/// which provides unique type identification without namespace issues.
///
/// Usage examples:
/// ```cpp
/// enum class Color { RED = 10, YELLOW = 20, WHITE = 30 };
/// FORY_ENUM(Color, RED, YELLOW, WHITE);
/// ```
#define FORY_ENUM(EnumType, ...)                                               \
  FORY_INTERNAL_ENUM_DEFINE(EnumType, __VA_ARGS__)
