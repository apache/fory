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
#include "fory/row/writer.h"
#include <memory>
#include <optional>
#include <string_view>
#include <type_traits>
#include <utility>

namespace fory {
namespace row {

namespace encoder {

namespace details {

template <typename> struct ForySchemaBasicType;

template <> struct ForySchemaBasicType<bool> {
  static inline DataTypePtr value() { return boolean(); }
};

template <> struct ForySchemaBasicType<int8_t> {
  static inline DataTypePtr value() { return int8(); }
};

template <> struct ForySchemaBasicType<int16_t> {
  static inline DataTypePtr value() { return int16(); }
};

template <> struct ForySchemaBasicType<int32_t> {
  static inline DataTypePtr value() { return int32(); }
};

template <> struct ForySchemaBasicType<int64_t> {
  static inline DataTypePtr value() { return int64(); }
};

template <> struct ForySchemaBasicType<float> {
  static inline DataTypePtr value() { return float32(); }
};

template <> struct ForySchemaBasicType<double> {
  static inline DataTypePtr value() { return float64(); }
};

inline std::string string_view_to_string(std::string_view s) {
  return {s.begin(), s.end()};
}

template <typename T>
inline constexpr bool IsString =
    meta::IsOneOf<T, std::string, std::string_view>::value;

template <typename T> inline constexpr bool IsMap = meta::IsPairIterable<T>;

template <typename T>
inline constexpr bool IsArray =
    meta::IsIterable<T> && !IsString<T> && !IsMap<T>;

template <typename> inline constexpr bool IsOptional = false;

template <typename T> inline constexpr bool IsOptional<std::optional<T>> = true;

template <typename T>
inline constexpr bool IsClassButNotBuiltin =
    std::is_class_v<T> &&
    !(IsString<T> || IsArray<T> || IsOptional<T> || IsMap<T>);

inline decltype(auto) get_child_type(RowWriter &writer, int index) {
  return writer.schema()->field(index)->type();
}

inline decltype(auto) get_child_type(ArrayWriter &writer, int index) {
  return writer.type()->field(0)->type();
}

// Helper to check if ForySchemaBasicType is defined for T
template <typename T, typename = void>
struct HasForySchemaBasicType : std::false_type {};

template <typename T>
struct HasForySchemaBasicType<
    T, std::void_t<decltype(ForySchemaBasicType<T>::value())>>
    : std::true_type {};

} // namespace details

using meta::fory_field_info;

// Type alias for field vector
using FieldVector = std::vector<FieldPtr>;

struct EmptyWriteVisitor {
  template <typename, typename T> void visit(T &&) {}
};

template <typename C> struct DefaultWriteVisitor {
  C &cont;

  DefaultWriteVisitor(C &cont) : cont(cont) {}

  template <typename, typename T> void visit(std::unique_ptr<T> writer) {
    cont.push_back(std::move(writer));
  }
};

// RowEncodeTrait<T> defines how to serialize `T` to the row format
// it includes:
// - Type(): construct fory format type of type `T`
// - Schema(): construct schema of type `T` (only for class types)
// - write(auto&& visitor, const T& value, ...):
//     encode `T` via the provided writer
template <typename T, typename Enable = void> struct RowEncodeTrait {
  static_assert(meta::AlwaysFalse<T>,
                "type T is currently not supported for encoding");
};

template <typename T>
struct RowEncodeTrait<T, std::enable_if_t<details::HasForySchemaBasicType<
                             std::remove_cv_t<T>>::value>> {

  static auto Type() {
    return details::ForySchemaBasicType<std::remove_cv_t<T>>::value();
  }

  template <typename V, typename W,
            std::enable_if_t<meta::IsOneOf<W, RowWriter, ArrayWriter>::value,
                             int> = 0>
  static void write(V &&, const T &value, W &writer, int index) {
    writer.write(index, value);
  }
};

template <typename T>
struct RowEncodeTrait<
    T, std::enable_if_t<details::IsString<std::remove_cv_t<T>>>> {
  static auto Type() { return utf8(); }

  template <typename V, typename W,
            std::enable_if_t<meta::IsOneOf<W, RowWriter, ArrayWriter>::value,
                             int> = 0>
  static void write(V &&, const T &value, W &writer, int index) {
    writer.write_string(index, value);
  }
};

template <typename T>
struct RowEncodeTrait<
    T, std::enable_if_t<details::IsOptional<std::remove_cv_t<T>>>> {
  static auto Type() { return RowEncodeTrait<typename T::value_type>::Type(); }

  template <typename V, typename W,
            std::enable_if_t<meta::IsOneOf<W, RowWriter, ArrayWriter>::value,
                             int> = 0>
  static void write(V &&visitor, const T &value, W &writer, int index) {
    if (value) {
      RowEncodeTrait<typename T::value_type>::write(std::forward<V>(visitor),
                                                    *value, writer, index);
    } else {
      writer.set_null_at(index);
    }
  }
};

template <typename T>
struct RowEncodeTrait<
    T, std::enable_if_t<details::IsClassButNotBuiltin<std::remove_cv_t<T>>>> {
private:
  using FieldInfo = decltype(fory_field_info(std::declval<T>()));

  template <size_t I> static FieldPtr get_field() {
    using FieldType = meta::RemoveMemberPointerCVRefT<
        std::tuple_element_t<I, decltype(FieldInfo::ptrs())>>;
    return field(details::string_view_to_string(FieldInfo::Names[I]),
                 RowEncodeTrait<FieldType>::Type());
  }

  template <size_t... I>
  static encoder::FieldVector field_vector_impl(std::index_sequence<I...>) {
    return {get_field<I>()...};
  }

  template <size_t I, typename V>
  static void write_field(V &&visitor, const T &value, RowWriter &writer) {
    using FieldType = meta::RemoveMemberPointerCVRefT<
        std::tuple_element_t<I, decltype(FieldInfo::ptrs())>>;
    RowEncodeTrait<FieldType>::write(std::forward<V>(visitor),
                                     value.*std::get<I>(FieldInfo::ptrs_ref()),
                                     writer, I);
  }

  template <typename V, size_t... I>
  static void write_impl(V &&visitor, const T &value, RowWriter &writer,
                         std::index_sequence<I...>) {
    (write_field<I>(std::forward<V>(visitor), value, writer), ...);
  }

public:
  static encoder::FieldVector FieldVector() {
    return field_vector_impl(std::make_index_sequence<FieldInfo::Size>());
  }

  static auto Type() { return struct_(FieldVector()); }

  static auto Schema() { return schema(FieldVector()); }

  template <typename V>
  static void write(V &&visitor, const T &value, RowWriter &writer) {
    write_impl(std::forward<V>(visitor), value, writer,
               std::make_index_sequence<FieldInfo::Size>());
  }

  template <typename V, typename W,
            std::enable_if_t<meta::IsOneOf<W, RowWriter, ArrayWriter>::value,
                             int> = 0>
  static void write(V &&visitor, const T &value, W &writer, int index) {
    auto offset = writer.cursor();

    auto child_type = std::dynamic_pointer_cast<StructType>(
        details::get_child_type(writer, index));
    auto inner_writer =
        std::make_unique<RowWriter>(schema(child_type->fields()), &writer);

    inner_writer->reset();
    RowEncodeTrait<T>::write(std::forward<V>(visitor), value,
                             *inner_writer.get());

    writer.set_offset_and_size(index, offset, writer.cursor() - offset);

    std::forward<V>(visitor).template visit<std::remove_cv_t<T>>(
        std::move(inner_writer));
  }
};

template <typename T>
struct RowEncodeTrait<T,
                      std::enable_if_t<details::IsArray<std::remove_cv_t<T>>>> {
  static auto Type() {
    return list(RowEncodeTrait<meta::GetValueType<T>>::Type());
  }

  template <typename V>
  static void write(V &&visitor, const T &value, ArrayWriter &writer) {
    int index = 0;
    for (const auto &v : value) {
      RowEncodeTrait<meta::GetValueType<T>>::write(std::forward<V>(visitor), v,
                                                   writer, index);
      ++index;
    }
  }

  template <typename V, typename W,
            std::enable_if_t<meta::IsOneOf<W, RowWriter, ArrayWriter>::value,
                             int> = 0>
  static void write(V &&visitor, const T &value, W &writer, int index) {
    auto offset = writer.cursor();

    auto inner_writer = std::make_unique<ArrayWriter>(
        std::dynamic_pointer_cast<ListType>(
            details::get_child_type(writer, index)),
        &writer);

    inner_writer->reset(value.size());
    RowEncodeTrait<T>::write(std::forward<V>(visitor), value,
                             *inner_writer.get());

    writer.set_offset_and_size(index, offset, writer.cursor() - offset);

    std::forward<V>(visitor).template visit<std::remove_cv_t<T>>(
        std::move(inner_writer));
  }
};

template <typename T>
struct RowEncodeTrait<T,
                      std::enable_if_t<details::IsMap<std::remove_cv_t<T>>>> {
  static auto Type() {
    return map(RowEncodeTrait<typename T::value_type::first_type>::Type(),
               RowEncodeTrait<typename T::value_type::second_type>::Type());
  }

  template <typename V>
  static void write_key(V &&visitor, const T &value, ArrayWriter &writer) {
    int index = 0;
    for (const auto &v : value) {
      RowEncodeTrait<typename T::value_type::first_type>::write(
          std::forward<V>(visitor), v.first, writer, index);
      ++index;
    }
  }

  template <typename V>
  static void write_value(V &&visitor, const T &value, ArrayWriter &writer) {
    int index = 0;
    for (const auto &v : value) {
      RowEncodeTrait<typename T::value_type::second_type>::write(
          std::forward<V>(visitor), v.second, writer, index);
      ++index;
    }
  }

  template <typename V, typename W,
            std::enable_if_t<meta::IsOneOf<W, RowWriter, ArrayWriter>::value,
                             int> = 0>
  static void write(V &&visitor, const T &value, W &writer, int index) {
    auto offset = writer.cursor();
    writer.write_directly(-1);

    auto map_type = std::dynamic_pointer_cast<MapType>(
        details::get_child_type(writer, index));

    auto key_writer =
        std::make_unique<ArrayWriter>(list(map_type->key_type()), &writer);

    key_writer->reset(value.size());
    RowEncodeTrait<T>::write_key(std::forward<V>(visitor), value,
                                 *key_writer.get());

    writer.write_directly(offset, key_writer->size());

    auto value_writer =
        std::make_unique<ArrayWriter>(list(map_type->item_type()), &writer);

    value_writer->reset(value.size());
    RowEncodeTrait<T>::write_value(std::forward<V>(visitor), value,
                                   *value_writer.get());

    writer.set_offset_and_size(index, offset, writer.cursor() - offset);

    std::forward<V>(visitor).template visit<std::remove_cv_t<T>>(
        std::move(key_writer));
    std::forward<V>(visitor).template visit<std::remove_cv_t<T>>(
        std::move(value_writer));
  }
};

} // namespace encoder

} // namespace row
} // namespace fory
