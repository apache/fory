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
#include <cstdint>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <utility>

namespace fory {

/// Encoding strategies for integer field nodes.
enum class Encoding { Default = 0, Varint = 1, Fixed = 2, Tagged = 3 };

enum class FieldNodeKind {
  Default = 0,
  Scalar = 1,
  List = 2,
  Set = 3,
  Map = 4,
  Inner = 5,
  Array = 6
};

enum class FieldScalarKind {
  Inferred = 0,
  Bool,
  Int8,
  Int16,
  Int32,
  Int64,
  UInt8,
  UInt16,
  UInt32,
  UInt64,
  Float16,
  BFloat16,
  Float32,
  Float64,
  String
};

struct FieldNodeSpec {
  static constexpr uint8_t kMaxNodes = 16;

  FieldNodeKind kind_[kMaxNodes]{};
  Encoding encoding_[kMaxNodes]{};
  FieldScalarKind scalar_[kMaxNodes]{};
  int8_t child0_[kMaxNodes]{};
  int8_t child1_[kMaxNodes]{};
  uint8_t size_ = 1;

  constexpr FieldNodeSpec() {
    for (uint8_t i = 0; i < kMaxNodes; ++i) {
      kind_[i] = FieldNodeKind::Default;
      encoding_[i] = Encoding::Default;
      scalar_[i] = FieldScalarKind::Inferred;
      child0_[i] = -1;
      child1_[i] = -1;
    }
  }

  static constexpr FieldNodeSpec
  scalar(FieldScalarKind scalar_kind = FieldScalarKind::Inferred) {
    FieldNodeSpec spec;
    spec.kind_[0] = FieldNodeKind::Scalar;
    spec.scalar_[0] = scalar_kind;
    return spec;
  }

  constexpr int8_t append_tree(const FieldNodeSpec &child,
                               int8_t child_index = 0) {
    const int8_t dest = static_cast<int8_t>(size_++);
    kind_[dest] = child.kind_[child_index];
    encoding_[dest] = child.encoding_[child_index];
    scalar_[dest] = child.scalar_[child_index];
    if (child.child0_[child_index] >= 0) {
      child0_[dest] = append_tree(child, child.child0_[child_index]);
    }
    if (child.child1_[child_index] >= 0) {
      child1_[dest] = append_tree(child, child.child1_[child_index]);
    }
    return dest;
  }

  constexpr FieldNodeSpec with_encoding(Encoding encoding) const {
    auto copy = *this;
    copy.encoding_[0] = encoding;
    if (copy.kind_[0] == FieldNodeKind::Default) {
      copy.kind_[0] = FieldNodeKind::Scalar;
    }
    return copy;
  }

  constexpr FieldNodeSpec fixed() const {
    return with_encoding(Encoding::Fixed);
  }
  constexpr FieldNodeSpec varint() const {
    return with_encoding(Encoding::Varint);
  }
  constexpr FieldNodeSpec tagged() const {
    return with_encoding(Encoding::Tagged);
  }

  constexpr FieldNodeSpec list(FieldNodeSpec elem) const {
    auto copy = *this;
    copy.size_ = 1;
    copy.kind_[0] = FieldNodeKind::List;
    copy.child0_[0] = copy.append_tree(elem);
    copy.child1_[0] = -1;
    copy.encoding_[0] = Encoding::Default;
    return copy;
  }

  constexpr FieldNodeSpec array(FieldNodeSpec elem) const {
    auto copy = *this;
    copy.size_ = 1;
    copy.kind_[0] = FieldNodeKind::Array;
    copy.child0_[0] = copy.append_tree(elem);
    copy.child1_[0] = -1;
    copy.encoding_[0] = Encoding::Default;
    return copy;
  }

  constexpr FieldNodeSpec set(FieldNodeSpec elem) const {
    auto copy = *this;
    copy.size_ = 1;
    copy.kind_[0] = FieldNodeKind::Set;
    copy.child0_[0] = copy.append_tree(elem);
    copy.child1_[0] = -1;
    copy.encoding_[0] = Encoding::Default;
    return copy;
  }

  constexpr FieldNodeSpec map() const {
    auto copy = *this;
    copy.size_ = 1;
    copy.kind_[0] = FieldNodeKind::Map;
    copy.child0_[0] = -1;
    copy.child1_[0] = -1;
    copy.encoding_[0] = Encoding::Default;
    return copy;
  }

  constexpr FieldNodeSpec map(FieldNodeSpec key, FieldNodeSpec value) const {
    return map().key(key).value(value);
  }

  constexpr FieldNodeSpec key(FieldNodeSpec key_spec) const {
    auto copy = *this;
    copy.kind_[0] = FieldNodeKind::Map;
    copy.child0_[0] = copy.append_tree(key_spec);
    return copy;
  }

  constexpr FieldNodeSpec value(FieldNodeSpec value_spec) const {
    auto copy = *this;
    copy.kind_[0] = FieldNodeKind::Map;
    copy.child1_[0] = copy.append_tree(value_spec);
    return copy;
  }

  constexpr FieldNodeSpec inner(FieldNodeSpec child) const {
    auto copy = *this;
    copy.size_ = 1;
    copy.kind_[0] = FieldNodeKind::Inner;
    copy.child0_[0] = copy.append_tree(child);
    copy.child1_[0] = -1;
    copy.encoding_[0] = Encoding::Default;
    return copy;
  }
};

struct FieldMeta {
  int16_t id_ = -1;
  bool has_id_ = false;
  bool nullable_ = false;
  bool ref_ = false;
  int dynamic_ = -1;
  FieldNodeSpec spec_{};
  Encoding encoding_ = Encoding::Default;
  bool compress_ = true;
  int16_t type_id_override_ = -1;

  constexpr FieldMeta id(int16_t v) const {
    auto copy = *this;
    copy.id_ = v;
    copy.has_id_ = true;
    return copy;
  }
  constexpr FieldMeta nullable(bool v = true) const {
    auto copy = *this;
    copy.nullable_ = v;
    return copy;
  }
  constexpr FieldMeta ref(bool v = true) const {
    auto copy = *this;
    copy.ref_ = v;
    return copy;
  }
  constexpr FieldMeta dynamic(bool v) const {
    auto copy = *this;
    copy.dynamic_ = v ? 1 : 0;
    return copy;
  }
  constexpr FieldMeta with_spec(FieldNodeSpec spec) const {
    auto copy = *this;
    copy.spec_ = spec;
    copy.encoding_ = spec.encoding_[0];
    return copy;
  }
  constexpr FieldMeta fixed() const { return with_spec(spec_.fixed()); }
  constexpr FieldMeta varint() const { return with_spec(spec_.varint()); }
  constexpr FieldMeta tagged() const { return with_spec(spec_.tagged()); }
  constexpr FieldMeta list(FieldNodeSpec elem) const {
    return with_spec(spec_.list(elem));
  }
  constexpr FieldMeta array(FieldNodeSpec elem) const {
    return with_spec(spec_.array(elem));
  }
  constexpr FieldMeta set(FieldNodeSpec elem) const {
    return with_spec(spec_.set(elem));
  }
  constexpr FieldMeta map() const { return with_spec(spec_.map()); }
  constexpr FieldMeta map(FieldNodeSpec key, FieldNodeSpec value) const {
    return with_spec(spec_.map(key, value));
  }
  constexpr FieldMeta key(FieldNodeSpec key_spec) const {
    return with_spec(spec_.key(key_spec));
  }
  constexpr FieldMeta value(FieldNodeSpec value_spec) const {
    return with_spec(spec_.value(value_spec));
  }
  constexpr FieldMeta inner(FieldNodeSpec child) const {
    return with_spec(spec_.inner(child));
  }
};

constexpr FieldMeta F() { return FieldMeta{}; }
constexpr FieldMeta F(int16_t id) { return FieldMeta{}.id(id); }

namespace T {

constexpr FieldNodeSpec
scalar(FieldScalarKind kind = FieldScalarKind::Inferred) {
  return FieldNodeSpec::scalar(kind);
}
constexpr FieldNodeSpec fixed() { return scalar().fixed(); }
constexpr FieldNodeSpec varint() { return scalar().varint(); }
constexpr FieldNodeSpec tagged() { return scalar().tagged(); }
constexpr FieldNodeSpec list(FieldNodeSpec elem) {
  return FieldNodeSpec{}.list(elem);
}
constexpr FieldNodeSpec array(FieldNodeSpec elem) {
  return FieldNodeSpec{}.array(elem);
}
constexpr FieldNodeSpec set(FieldNodeSpec elem) {
  return FieldNodeSpec{}.set(elem);
}
constexpr FieldNodeSpec map() { return FieldNodeSpec{}.map(); }
constexpr FieldNodeSpec map(FieldNodeSpec key, FieldNodeSpec value) {
  return FieldNodeSpec{}.map(key, value);
}
constexpr FieldNodeSpec inner(FieldNodeSpec child) {
  return FieldNodeSpec{}.inner(child);
}

constexpr FieldNodeSpec boolean() { return scalar(FieldScalarKind::Bool); }
constexpr FieldNodeSpec int8() { return scalar(FieldScalarKind::Int8); }
constexpr FieldNodeSpec int16() { return scalar(FieldScalarKind::Int16); }
constexpr FieldNodeSpec int32() { return scalar(FieldScalarKind::Int32); }
constexpr FieldNodeSpec int64() { return scalar(FieldScalarKind::Int64); }
constexpr FieldNodeSpec uint8() { return scalar(FieldScalarKind::UInt8); }
constexpr FieldNodeSpec uint16() { return scalar(FieldScalarKind::UInt16); }
constexpr FieldNodeSpec uint32() { return scalar(FieldScalarKind::UInt32); }
constexpr FieldNodeSpec uint64() { return scalar(FieldScalarKind::UInt64); }
constexpr FieldNodeSpec float16() { return scalar(FieldScalarKind::Float16); }
constexpr FieldNodeSpec bfloat16() { return scalar(FieldScalarKind::BFloat16); }
constexpr FieldNodeSpec float32() { return scalar(FieldScalarKind::Float32); }
constexpr FieldNodeSpec float64() { return scalar(FieldScalarKind::Float64); }
constexpr FieldNodeSpec string() { return scalar(FieldScalarKind::String); }

} // namespace T

namespace detail {

struct FieldEntry {
  const char *name;
  FieldMeta meta;

  constexpr FieldEntry(const char *n, FieldMeta m) : name(n), meta(m) {}
};

constexpr auto make_field_entry(const char *name, FieldMeta meta) {
  return FieldEntry{name, meta};
}

} // namespace detail

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

#define FORY_PP_IS_CONFIG(arg)                                                 \
  FORY_PP_IS_CONFIG_IMPL(FORY_PP_IS_PAREN(arg), arg)
#define FORY_PP_IS_CONFIG_IMPL(is_paren, arg)                                  \
  FORY_PP_CONCAT(FORY_PP_IS_CONFIG_IMPL_, is_paren)(arg)
#define FORY_PP_IS_CONFIG_IMPL_0(arg) 0
#define FORY_PP_IS_CONFIG_IMPL_1(arg) FORY_PP_NOT(FORY_PP_IS_BASE(arg))

#define FORY_FIELD_ARG_NAME(arg)                                               \
  FORY_PP_IF(FORY_PP_IS_CONFIG(arg))                                           \
  (FORY_PP_TUPLE_FIRST(arg), arg)

#define FORY_FIELD_ARG_META(arg)                                               \
  FORY_PP_IF(FORY_PP_IS_CONFIG(arg))                                           \
  (FORY_PP_TUPLE_SECOND(arg), ::fory::F())

#define FORY_FIELD_INFO_NAMES_FIELD(field) #field,
#define FORY_FIELD_INFO_NAMES_ARG(arg) FORY_FIELD_INFO_NAMES_FIELD(arg)
#define FORY_FIELD_INFO_NAMES_FUNC(arg)                                        \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (FORY_PP_EMPTY(), FORY_FIELD_INFO_NAMES_ARG(FORY_FIELD_ARG_NAME(arg)))

#define FORY_FIELD_INFO_PTRS_FIELD(type, field) &type::field,
#define FORY_FIELD_INFO_PTRS_ARG(type, field)                                  \
  FORY_FIELD_INFO_PTRS_FIELD(type, field)
#define FORY_FIELD_INFO_PTRS_FUNC(type, arg)                                   \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (FORY_PP_EMPTY(), FORY_FIELD_INFO_PTRS_ARG(type, FORY_FIELD_ARG_NAME(arg)))

#define FORY_FIELD_INFO_CONFIG_ENTRY(arg)                                      \
  ::fory::detail::make_field_entry(                                            \
      FORY_PP_STRINGIFY(FORY_FIELD_ARG_NAME(arg)), FORY_FIELD_ARG_META(arg)),
#define FORY_FIELD_INFO_CONFIG_FUNC(arg)                                       \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (FORY_PP_EMPTY(), FORY_FIELD_INFO_CONFIG_ENTRY(arg))

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

#define FORY_BASE_CONFIG_ARG(arg)                                              \
  FORY_PP_IF(FORY_PP_IS_BASE(arg))                                             \
  (FORY_BASE_CONFIG_ARG_IMPL(arg), FORY_PP_EMPTY())
#define FORY_BASE_CONFIG_ARG_IMPL(arg)                                         \
  fory::meta::details::wrap_tuple(decltype(::fory::meta::fory_field_info(      \
      std::declval<FORY_BASE_TYPE(arg)>()))::entries),

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
    static inline constexpr auto BaseConfigEntries =                           \
        fory::meta::concat_tuples_from_tuple(                                  \
            std::tuple{FORY_PP_FOREACH(FORY_BASE_CONFIG_ARG, __VA_ARGS__)});   \
    static inline constexpr auto FieldConfigEntries =                          \
        std::tuple{FORY_PP_FOREACH(FORY_FIELD_INFO_CONFIG_FUNC, __VA_ARGS__)}; \
    static constexpr bool has_config = true;                                   \
    static inline constexpr auto entries =                                     \
        fory::meta::concat_tuples(BaseConfigEntries, FieldConfigEntries);      \
    using FieldConfigEntriesType = std::decay_t<decltype(entries)>;            \
    [[maybe_unused]] static constexpr size_t field_count =                     \
        std::tuple_size_v<FieldConfigEntriesType>;                             \
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
  [[maybe_unused]] inline static constexpr auto fory_field_config(             \
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
    static constexpr bool has_config = false;                                  \
    static inline constexpr auto entries = std::tuple{};                       \
    [[maybe_unused]] static constexpr size_t field_count = 0;                  \
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
  [[maybe_unused]] inline static constexpr auto fory_field_config(             \
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
