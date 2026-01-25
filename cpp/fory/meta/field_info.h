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
    decltype(T::ForyStructInfo(std::declval<Identity<T>>()));

template <typename T, typename = void>
struct HasMemberStructInfo : std::false_type {};

template <typename T>
struct HasMemberStructInfo<T, std::void_t<MemberStructInfo<T>>>
    : std::true_type {};

template <typename T>
using AdlStructInfo = decltype(ForyStructInfo(std::declval<Identity<T>>()));

template <typename T, typename = void>
struct HasAdlStructInfo : std::false_type {};

template <typename T>
struct HasAdlStructInfo<T, std::void_t<AdlStructInfo<T>>> : std::true_type {};

template <typename T> struct TupleWrapper {
  T value;
};

template <typename T> constexpr TupleWrapper<T> WrapTuple(const T &value) {
  return {value};
}

template <typename T> constexpr const T &UnwrapTuple(const T &value) {
  return value;
}

template <typename T>
constexpr const T &UnwrapTuple(const TupleWrapper<T> &value) {
  return value.value;
}

// it must be able to be executed in compile-time
template <typename FieldInfo, size_t... I>
constexpr bool IsValidFieldInfoImpl(std::index_sequence<I...>) {
  constexpr auto ptrs = FieldInfo::Ptrs();
  return IsUnique<std::get<I>(ptrs)...>::value;
}

} // namespace details

template <typename FieldInfo> constexpr bool IsValidFieldInfo() {
  return details::IsValidFieldInfoImpl<FieldInfo>(
      std::make_index_sequence<FieldInfo::Size>{});
}

template <typename T>
struct HasForyStructInfo
    : std::bool_constant<details::HasMemberStructInfo<T>::value ||
                         details::HasAdlStructInfo<T>::value> {};

// decltype(ForyFieldInfo<T>(v)) records field meta information for type T
// it includes:
// - number of fields: typed size_t
// - field names: typed `std::string_view`
// - field member points: typed `decltype(a) T::*` for any member `T::a`
template <typename T>
constexpr auto ForyFieldInfo([[maybe_unused]] const T &value) noexcept {
  if constexpr (details::HasMemberStructInfo<T>::value) {
    using FieldInfo = decltype(T::ForyStructInfo(Identity<T>{}));
    static_assert(IsValidFieldInfo<FieldInfo>(),
                  "duplicated fields in FORY_STRUCT arguments are detected");
    return T::ForyStructInfo(Identity<T>{});
  } else if constexpr (details::HasAdlStructInfo<T>::value) {
    using FieldInfo = decltype(ForyStructInfo(Identity<T>{}));
    static_assert(IsValidFieldInfo<FieldInfo>(),
                  "duplicated fields in FORY_STRUCT arguments are detected");
    return ForyStructInfo(Identity<T>{});
  } else {
    static_assert(AlwaysFalse<T>,
                  "FORY_STRUCT for type T is expected but not defined");
  }
}

constexpr std::array<std::string_view, 0> ConcatArrays() { return {}; }

template <size_t N>
constexpr std::array<std::string_view, N>
ConcatArrays(const std::array<std::string_view, N> &value) {
  return value;
}

template <size_t N, size_t M>
constexpr std::array<std::string_view, N + M>
ConcatArrays(const std::array<std::string_view, N> &left,
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
constexpr auto ConcatArrays(const std::array<std::string_view, N> &left,
                            const std::array<std::string_view, M> &right,
                            const Rest &...rest) {
  return ConcatArrays(ConcatArrays(left, right), rest...);
}

constexpr std::tuple<> ConcatTuples() { return {}; }

template <typename Tuple> constexpr Tuple ConcatTuples(const Tuple &tuple) {
  return tuple;
}

template <typename Tuple1, typename Tuple2, size_t... I, size_t... J>
constexpr auto ConcatTwoTuplesImpl(const Tuple1 &left, const Tuple2 &right,
                                   std::index_sequence<I...>,
                                   std::index_sequence<J...>) {
  return std::tuple{std::get<I>(left)..., std::get<J>(right)...};
}

template <typename Tuple1, typename Tuple2>
constexpr auto ConcatTuples(const Tuple1 &left, const Tuple2 &right) {
  return ConcatTwoTuplesImpl(
      left, right, std::make_index_sequence<std::tuple_size_v<Tuple1>>{},
      std::make_index_sequence<std::tuple_size_v<Tuple2>>{});
}

template <typename Tuple1, typename Tuple2, typename... Rest>
constexpr auto ConcatTuples(const Tuple1 &left, const Tuple2 &right,
                            const Rest &...rest) {
  return ConcatTuples(ConcatTuples(left, right), rest...);
}

template <typename Tuple, size_t... I>
constexpr auto ConcatArraysFromTupleImpl(const Tuple &tuple,
                                         std::index_sequence<I...>) {
  return ConcatArrays(std::get<I>(tuple)...);
}

template <typename Tuple>
constexpr auto ConcatArraysFromTuple(const Tuple &tuple) {
  return ConcatArraysFromTupleImpl(
      tuple, std::make_index_sequence<std::tuple_size_v<Tuple>>{});
}

template <typename Tuple, size_t... I>
constexpr auto ConcatTuplesFromTupleImpl(const Tuple &tuple,
                                         std::index_sequence<I...>) {
  return ConcatTuples(details::UnwrapTuple(std::get<I>(tuple))...);
}

template <typename Tuple>
constexpr auto ConcatTuplesFromTuple(const Tuple &tuple) {
  return ConcatTuplesFromTupleImpl(
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
  decltype(::fory::meta::ForyFieldInfo(                                        \
      std::declval<FORY_BASE_TYPE(arg)>()))::Names,

#define FORY_BASE_PTRS_ARG(arg)                                                \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (FORY_BASE_PTRS_ARG_IMPL(arg), FORY_PP_EMPTY())
#define FORY_BASE_PTRS_ARG_IMPL(arg)                                           \
  fory::meta::details::WrapTuple(decltype(::fory::meta::ForyFieldInfo(         \
      std::declval<FORY_BASE_TYPE(arg)>()))::Ptrs()),

#define FORY_BASE_SIZE_ADD(arg)                                                \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (+decltype(::fory::meta::ForyFieldInfo(                                      \
       std::declval<FORY_BASE_TYPE(arg)>()))::Size,                            \
   FORY_PP_EMPTY())

#define FORY_FIELD_SIZE_ADD(arg)                                               \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))(FORY_PP_EMPTY(), +1)

// NOTE: FORY_STRUCT can be used inside the class/struct definition or at
// namespace scope. The macro defines constexpr functions which are detected
// via member lookup (in-class) or ADL (namespace scope).
// MSVC (VS 2022 17.11, 19.41) fixes in-class pointer-to-member constexpr
// issues; keep evaluation inside `Ptrs` function instead of field for older
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
        fory::meta::ConcatArraysFromTuple(                                     \
            std::tuple{FORY_PP_FOREACH(FORY_BASE_NAMES_ARG, __VA_ARGS__)});    \
    static inline constexpr std::array<std::string_view, FieldSize>            \
        FieldNames = {                                                         \
            FORY_PP_FOREACH(FORY_FIELD_INFO_NAMES_FUNC, __VA_ARGS__)};         \
    static inline constexpr auto Names =                                       \
        fory::meta::ConcatArrays(BaseNames, FieldNames);                       \
    using BasePtrsType = decltype(fory::meta::ConcatTuplesFromTuple(           \
        std::tuple{FORY_PP_FOREACH(FORY_BASE_PTRS_ARG, __VA_ARGS__)}));        \
    static constexpr BasePtrsType BasePtrs() {                                 \
      return fory::meta::ConcatTuplesFromTuple(                                \
          std::tuple{FORY_PP_FOREACH(FORY_BASE_PTRS_ARG, __VA_ARGS__)});       \
    }                                                                          \
    using FieldPtrsType = decltype(std::tuple{                                 \
        FORY_PP_FOREACH_1(FORY_FIELD_INFO_PTRS_FUNC, type, __VA_ARGS__)});     \
    static constexpr FieldPtrsType FieldPtrs() {                               \
      return std::tuple{                                                       \
          FORY_PP_FOREACH_1(FORY_FIELD_INFO_PTRS_FUNC, type, __VA_ARGS__)};    \
    }                                                                          \
    using PtrsType = decltype(fory::meta::ConcatTuples(                        \
        std::declval<BasePtrsType>(), std::declval<FieldPtrsType>()));         \
    static constexpr PtrsType Ptrs() {                                         \
      return fory::meta::ConcatTuples(BasePtrs(), FieldPtrs());                \
    }                                                                          \
    static const PtrsType &PtrsRef() {                                         \
      static const PtrsType value = Ptrs();                                    \
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
  [[maybe_unused]] inline static constexpr auto ForyStructInfo(                \
      const ::fory::meta::Identity<type> &) noexcept {                         \
    return FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id){};              \
  }                                                                            \
  [[maybe_unused]] inline static constexpr std::true_type ForyStructMarker(    \
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
    static constexpr PtrsType Ptrs() { return {}; }                            \
    static const PtrsType &PtrsRef() {                                         \
      static const PtrsType value = Ptrs();                                    \
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
  [[maybe_unused]] inline static constexpr auto ForyStructInfo(                \
      const ::fory::meta::Identity<type> &) noexcept {                         \
    return FORY_PP_CONCAT(ForyFieldInfoDescriptor_, unique_id){};              \
  }                                                                            \
  [[maybe_unused]] inline static constexpr std::true_type ForyStructMarker(    \
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
