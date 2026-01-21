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
#include "fory/meta/type_traits.h"
#include <array>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <utility>

namespace fory {

namespace meta {

// decltype(ForyFieldInfo<T>(v)) records field meta information for type T
// it includes:
// - number of fields: typed size_t
// - field names: typed `std::string_view`
// - field member points: typed `decltype(a) T::*` for any member `T::a`
template <typename T> constexpr auto ForyFieldInfo(const T &) noexcept {
  static_assert(AlwaysFalse<T>,
                "FORY_FIELD_INFO for type T is expected but not defined");
}

namespace details {

// it must be able to be executed in compile-time
template <typename FieldInfo, size_t... I>
constexpr bool IsValidFieldInfoImpl(std::index_sequence<I...>) {
  return IsUnique<std::get<I>(FieldInfo::Ptrs)...>::value;
}

} // namespace details

template <typename FieldInfo> constexpr bool IsValidFieldInfo() {
  return details::IsValidFieldInfoImpl<FieldInfo>(
      std::make_index_sequence<FieldInfo::Size>{});
}

} // namespace meta

} // namespace fory

#define FORY_FIELD_INFO_NAMES_FUNC(field) #field,
#define FORY_FIELD_INFO_PTRS_FUNC(type, field) &type::field,

// here we define function overloads in the current namespace rather than
// template specialization of classes since specialization of template in
// different namespace is hard
// NOTE: for performing ADL (argument-dependent lookup),
// `FORY_FIELD_INFO(T, ...)` must be defined in the same namespace as `T`
#define FORY_FIELD_INFO(type, ...)                                             \
  static_assert(std::is_class_v<type>, "it must be a class type");             \
  template <typename> struct ForyFieldInfoImpl;                                \
  template <> struct ForyFieldInfoImpl<type> {                                 \
    static inline constexpr size_t Size = FORY_PP_NARG(__VA_ARGS__);           \
    static inline constexpr std::string_view Name = #type;                     \
    static inline constexpr std::array<std::string_view, Size> Names = {       \
        FORY_PP_FOREACH(FORY_FIELD_INFO_NAMES_FUNC, __VA_ARGS__)};             \
    static inline constexpr auto Ptrs = std::tuple{                            \
        FORY_PP_FOREACH_1(FORY_FIELD_INFO_PTRS_FUNC, type, __VA_ARGS__)};      \
  };                                                                           \
  static_assert(                                                               \
      fory::meta::IsValidFieldInfo<ForyFieldInfoImpl<type>>(),                 \
      "duplicated fields in FORY_FIELD_INFO arguments are detected");          \
  static_assert(ForyFieldInfoImpl<type>::Name.data() != nullptr,               \
                "ForyFieldInfoImpl name must be available");                   \
  static_assert(ForyFieldInfoImpl<type>::Names.size() ==                       \
                    ForyFieldInfoImpl<type>::Size,                             \
                "ForyFieldInfoImpl names size mismatch");                      \
  inline constexpr auto ForyFieldInfo(const type &) noexcept {                 \
    return ForyFieldInfoImpl<type>{};                                          \
  }                                                                            \
  static_assert(                                                               \
      static_cast<ForyFieldInfoImpl<type> (*)(const type &) noexcept>(         \
          &ForyFieldInfo) != nullptr,                                          \
      "ForyFieldInfo must be declared");
