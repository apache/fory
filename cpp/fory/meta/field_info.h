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

template <typename T> struct Identity {
  using Type = T;
};

namespace details {

template <typename T>
using MemberStructInfo =
    decltype(T::fory_struct_info(std::declval<Identity<T>>()));

template <typename T, typename = void>
struct HasMemberStructInfo : std::false_type {};

template <typename T>
struct HasMemberStructInfo<T, std::void_t<MemberStructInfo<T>>>
    : std::true_type {};

template <typename T>
using AdlStructInfo = decltype(fory_struct_info(std::declval<Identity<T>>()));

template <typename T, typename = void>
struct HasAdlStructInfo : std::false_type {};

template <typename T>
struct HasAdlStructInfo<T, std::void_t<AdlStructInfo<T>>> : std::true_type {};

template <typename T> struct TupleWrapper {
  T value;
};

template <typename T> constexpr TupleWrapper<T> wrap_tuple(const T &value) {
  return {value};
}

template <typename T> constexpr const T &unwrap_tuple(const T &value) {
  return value;
}

template <typename T>
constexpr const T &unwrap_tuple(const TupleWrapper<T> &value) {
  return value.value;
}

// it must be able to be executed in compile-time
template <typename FieldInfo, size_t... I>
constexpr bool is_valid_field_info_impl(std::index_sequence<I...>) {
  constexpr auto ptrs = FieldInfo::ptrs();
  return IsUnique<std::get<I>(ptrs)...>::value;
}

} // namespace details

template <typename FieldInfo> constexpr bool is_valid_field_info() {
  return details::is_valid_field_info_impl<FieldInfo>(
      std::make_index_sequence<FieldInfo::Size>{});
}

template <typename T>
struct HasForyStructInfo
    : std::bool_constant<details::HasMemberStructInfo<T>::value ||
                         details::HasAdlStructInfo<T>::value> {};

// decltype(fory_field_info<T>(v)) records field meta information for type T
// it includes:
// - number of fields: typed size_t
// - field names: typed `std::string_view`
// - field member points: typed `decltype(a) T::*` for any member `T::a`
template <typename T>
constexpr auto fory_field_info([[maybe_unused]] const T &value) noexcept {
  if constexpr (details::HasMemberStructInfo<T>::value) {
    using FieldInfo = decltype(T::fory_struct_info(Identity<T>{}));
    static_assert(is_valid_field_info<FieldInfo>(),
                  "duplicated fields in FORY_STRUCT arguments are detected");
    return T::fory_struct_info(Identity<T>{});
  } else if constexpr (details::HasAdlStructInfo<T>::value) {
    using FieldInfo = decltype(fory_struct_info(Identity<T>{}));
    static_assert(is_valid_field_info<FieldInfo>(),
                  "duplicated fields in FORY_STRUCT arguments are detected");
    return fory_struct_info(Identity<T>{});
  } else {
    static_assert(AlwaysFalse<T>,
                  "FORY_STRUCT for type T is expected but not defined");
  }
}

constexpr std::array<std::string_view, 0> concat_arrays() { return {}; }

template <size_t N>
constexpr std::array<std::string_view, N>
concat_arrays(const std::array<std::string_view, N> &value) {
  return value;
}

template <size_t N, size_t M>
constexpr std::array<std::string_view, N + M>
concat_arrays(const std::array<std::string_view, N> &left,
              const std::array<std::string_view, M> &right) {
  std::array<std::string_view, N + M> out{};
  for (size_t i = 0; i < N; ++i) {
    out[i] = left[i];
  }
  for (size_t i = 0; i < M; ++i) {
    out[N + i] = right[i];
  }
  return out;
}

template <size_t N, size_t M, typename... Rest>
constexpr auto concat_arrays(const std::array<std::string_view, N> &left,
                             const std::array<std::string_view, M> &right,
                             const Rest &...rest) {
  return concat_arrays(concat_arrays(left, right), rest...);
}

constexpr std::tuple<> concat_tuples() { return {}; }

template <typename Tuple> constexpr Tuple concat_tuples(const Tuple &tuple) {
  return tuple;
}

template <typename Tuple1, typename Tuple2, size_t... I, size_t... J>
constexpr auto concat_two_tuples_impl(const Tuple1 &left, const Tuple2 &right,
                                      std::index_sequence<I...>,
                                      std::index_sequence<J...>) {
  return std::tuple{std::get<I>(left)..., std::get<J>(right)...};
}

template <typename Tuple1, typename Tuple2>
constexpr auto concat_tuples(const Tuple1 &left, const Tuple2 &right) {
  return concat_two_tuples_impl(
      left, right, std::make_index_sequence<std::tuple_size_v<Tuple1>>{},
      std::make_index_sequence<std::tuple_size_v<Tuple2>>{});
}

template <typename Tuple1, typename Tuple2, typename... Rest>
constexpr auto concat_tuples(const Tuple1 &left, const Tuple2 &right,
                             const Rest &...rest) {
  return concat_tuples(concat_tuples(left, right), rest...);
}

template <typename Tuple, size_t... I>
constexpr auto concat_arrays_from_tuple_impl(const Tuple &tuple,
                                             std::index_sequence<I...>) {
  return concat_arrays(std::get<I>(tuple)...);
}

template <typename Tuple>
constexpr auto concat_arrays_from_tuple(const Tuple &tuple) {
  return concat_arrays_from_tuple_impl(
      tuple, std::make_index_sequence<std::tuple_size_v<Tuple>>{});
}

template <typename Tuple, size_t... I>
constexpr auto concat_tuples_from_tuple_impl(const Tuple &tuple,
                                             std::index_sequence<I...>) {
  return concat_tuples(details::unwrap_tuple(std::get<I>(tuple))...);
}

template <typename Tuple>
constexpr auto concat_tuples_from_tuple(const Tuple &tuple) {
  return concat_tuples_from_tuple_impl(
      tuple, std::make_index_sequence<std::tuple_size_v<Tuple>>{});
}

} // namespace meta

} // namespace fory

#define FORY_BASE(type) (FORY_BASE_TAG, type)

#define FORY_PP_IS_BASE_TAG(x)                                                 \
  FORY_PP_CHECK(FORY_PP_CONCAT(FORY_PP_IS_BASE_TAG_PROBE_, x))
#define FORY_PP_IS_BASE_TAG_PROBE_FORY_BASE_TAG FORY_PP_PROBE()

#define FORY_PP_IS_BASE(arg) FORY_PP_IS_BASE_IMPL(FORY_PP_IS_PAREN(arg), arg)
#define FORY_PP_IS_BASE_IMPL(is_paren, arg)                                    \
  FORY_PP_CONCAT(FORY_PP_IS_BASE_IMPL_, is_paren)(arg)
#define FORY_PP_IS_BASE_IMPL_0(arg) 0
#define FORY_PP_IS_BASE_IMPL_1(arg)                                            \
  FORY_PP_IS_BASE_TAG(FORY_PP_TUPLE_FIRST(arg))

#define FORY_BASE_TYPE(arg) FORY_PP_TUPLE_SECOND(arg)

#define FORY_FIELD_INFO_NAMES_FIELD(field) #field,
#define FORY_FIELD_INFO_NAMES_FUNC(arg)                                        \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (FORY_PP_EMPTY(), FORY_FIELD_INFO_NAMES_FIELD(arg))

#define FORY_FIELD_INFO_PTRS_FIELD(type, field) &type::field,
#define FORY_FIELD_INFO_PTRS_FUNC(type, arg)                                   \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (FORY_PP_EMPTY(), FORY_FIELD_INFO_PTRS_FIELD(type, arg))

#define FORY_BASE_NAMES_ARG(arg)                                               \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (FORY_BASE_NAMES_ARG_IMPL(arg), FORY_PP_EMPTY())
#define FORY_BASE_NAMES_ARG_IMPL(arg)                                          \
  decltype(::fory::meta::fory_field_info(                                      \
      std::declval<FORY_BASE_TYPE(arg)>()))::Names,

#define FORY_BASE_PTRS_ARG(arg)                                                \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (FORY_BASE_PTRS_ARG_IMPL(arg), FORY_PP_EMPTY())
#define FORY_BASE_PTRS_ARG_IMPL(arg)                                           \
  fory::meta::details::wrap_tuple(decltype(::fory::meta::fory_field_info(      \
      std::declval<FORY_BASE_TYPE(arg)>()))::ptrs()),

#define FORY_BASE_SIZE_ADD(arg)                                                \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (+decltype(::fory::meta::fory_field_info(                                    \
       std::declval<FORY_BASE_TYPE(arg)>()))::Size,                            \
   FORY_PP_EMPTY())

#define FORY_FIELD_SIZE_ADD(arg)                                               \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))(FORY_PP_EMPTY(), +1)

// NOTE: FORY_STRUCT can be used inside the class/struct definition or at
// namespace scope. The macro defines constexpr functions which are detected
// via member lookup (in-class) or ADL (namespace scope).
// MSVC (VS 2022 17.11, 19.41) fixes in-class pointer-to-member constexpr
// issues; keep evaluation inside `ptrs` function instead of field for older
// toolsets.
#define FORY_STRUCT_FIELDS(type, unique_id, ...)                               \
  static_assert(std::is_class_v<type>, "it must be a class type");             \
  struct FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id) {                 \
    static inline constexpr size_t BaseSize =                                  \
        0 FORY_PP_FOREACH(FORY_BASE_SIZE_ADD, __VA_ARGS__);                    \
    static inline constexpr size_t FieldSize =                                 \
        0 FORY_PP_FOREACH(FORY_FIELD_SIZE_ADD, __VA_ARGS__);                   \
    static inline constexpr size_t Size = BaseSize + FieldSize;                \
    static inline constexpr std::string_view Name = #type;                     \
    static inline constexpr auto BaseNames =                                   \
        fory::meta::concat_arrays_from_tuple(                                  \
            std::tuple{FORY_PP_FOREACH(FORY_BASE_NAMES_ARG, __VA_ARGS__)});    \
    static inline constexpr std::array<std::string_view, FieldSize>            \
        FieldNames = {                                                         \
            FORY_PP_FOREACH(FORY_FIELD_INFO_NAMES_FUNC, __VA_ARGS__)};         \
    static inline constexpr auto Names =                                       \
        fory::meta::concat_arrays(BaseNames, FieldNames);                      \
    using BasePtrsType = decltype(fory::meta::concat_tuples_from_tuple(        \
        std::tuple{FORY_PP_FOREACH(FORY_BASE_PTRS_ARG, __VA_ARGS__)}));        \
    static constexpr BasePtrsType base_ptrs() {                                \
      return fory::meta::concat_tuples_from_tuple(                             \
          std::tuple{FORY_PP_FOREACH(FORY_BASE_PTRS_ARG, __VA_ARGS__)});       \
    }                                                                          \
    using FieldPtrsType = decltype(std::tuple{                                 \
        FORY_PP_FOREACH_1(FORY_FIELD_INFO_PTRS_FUNC, type, __VA_ARGS__)});     \
    static constexpr FieldPtrsType FieldPtrs() {                               \
      return std::tuple{                                                       \
          FORY_PP_FOREACH_1(FORY_FIELD_INFO_PTRS_FUNC, type, __VA_ARGS__)};    \
    }                                                                          \
    using PtrsType = decltype(fory::meta::concat_tuples(                       \
        std::declval<BasePtrsType>(), std::declval<FieldPtrsType>()));         \
    static constexpr PtrsType ptrs() {                                         \
      return fory::meta::concat_tuples(base_ptrs(), FieldPtrs());              \
    }                                                                          \
    static const PtrsType &ptrs_ref() {                                        \
      static const PtrsType value = ptrs();                                    \
      return value;                                                            \
    }                                                                          \
  };                                                                           \
  static_assert(FORY_PP_CONCAT(ForyFieldInfoDescriptor_,                       \
                               unique_id)::Name.data() != nullptr,             \
                "ForyFieldInfoDescriptor name must be available");             \
  static_assert(                                                               \
      FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id)::Names.size() ==     \
          FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id)::Size,           \
      "ForyFieldInfoDescriptor names size mismatch");                          \
  [[maybe_unused]] inline static constexpr auto fory_struct_info(              \
      const ::fory::meta::Identity<type> &) noexcept {                         \
    return FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id){};              \
  }                                                                            \
  [[maybe_unused]] inline static constexpr std::true_type fory_struct_marker(  \
      const ::fory::meta::Identity<type> &) noexcept {                         \
    return {};                                                                 \
  }

#define FORY_STRUCT_DETAIL_EMPTY(type, unique_id)                              \
  static_assert(std::is_class_v<type>, "it must be a class type");             \
  struct FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id) {                 \
    static inline constexpr size_t Size = 0;                                   \
    static inline constexpr std::string_view Name = #type;                     \
    static inline constexpr std::array<std::string_view, Size> Names = {};     \
    using PtrsType = decltype(std::tuple{});                                   \
    static constexpr PtrsType ptrs() { return {}; }                            \
    static const PtrsType &ptrs_ref() {                                        \
      static const PtrsType value = ptrs();                                    \
      return value;                                                            \
    }                                                                          \
  };                                                                           \
  static_assert(FORY_PP_CONCAT(ForyFieldInfoDescriptor_,                       \
                               unique_id)::Name.data() != nullptr,             \
                "ForyFieldInfoDescriptor name must be available");             \
  static_assert(                                                               \
      FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id)::Names.size() ==     \
          FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id)::Size,           \
      "ForyFieldInfoDescriptor names size mismatch");                          \
  [[maybe_unused]] inline static constexpr auto fory_struct_info(              \
      const ::fory::meta::Identity<type> &) noexcept {                         \
    return FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id){};              \
  }                                                                            \
  [[maybe_unused]] inline static constexpr std::true_type fory_struct_marker(  \
      const ::fory::meta::Identity<type> &) noexcept {                         \
    return {};                                                                 \
  }

#define FORY_STRUCT_WITH_FIELDS(type, unique_id, ...)                          \
  FORY_STRUCT_FIELDS(type, unique_id, __VA_ARGS__)

#define FORY_STRUCT_EMPTY(type, unique_id)                                     \
  FORY_STRUCT_DETAIL_EMPTY(type, unique_id)

#define FORY_STRUCT_1(type, unique_id, ...) FORY_STRUCT_EMPTY(type, unique_id)
#define FORY_STRUCT_0(type, unique_id, ...)                                    \
  FORY_STRUCT_WITH_FIELDS(type, unique_id, __VA_ARGS__)

#define FORY_STRUCT_IMPL(type, unique_id, ...)                                 \
  FORY_PP_CONCAT(FORY_STRUCT_, FORY_PP_IS_EMPTY(__VA_ARGS__))                  \
  (type, unique_id, __VA_ARGS__)

#define FORY_STRUCT(type, ...) FORY_STRUCT_IMPL(type, __LINE__, __VA_ARGS__)

#define FORY_STRUCT_EVOLVING(type, value)                                      \
  template <>                                                                  \
  struct fory::meta::StructEvolving<type> : std::bool_constant<value> {}
