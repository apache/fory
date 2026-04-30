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
#include "fory/type/type.h"
#include <cstdint>
#include <memory>
#include <optional>
#include <string_view>
#include <tuple>
#include <type_traits>

namespace fory {

namespace serialization {
template <typename T> class SharedWeak;
} // namespace serialization

namespace detail {

template <typename T>
using FieldInfo = decltype(::fory::meta::fory_field_info(std::declval<T>()));

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

template <typename T>
inline constexpr bool is_smart_ptr_v =
    is_shared_ptr_v<T> || is_shared_weak_v<T> || is_unique_ptr_v<T>;

template <typename T>
using MemberFieldConfigDescriptor =
    decltype(T::fory_field_config(std::declval<meta::Identity<T>>()));

template <typename T>
using AdlFieldConfigDescriptor =
    decltype(fory_field_config(std::declval<meta::Identity<T>>()));

template <typename T, typename = void>
struct HasMemberFieldConfig : std::false_type {};

template <typename T>
struct HasMemberFieldConfig<T, std::void_t<MemberFieldConfigDescriptor<T>>>
    : std::true_type {};

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
struct FieldConfigInfo<T, std::enable_if_t<HasMemberFieldConfig<T>::value>> {
  using Descriptor = MemberFieldConfigDescriptor<T>;
  static constexpr bool has_config = Descriptor::has_config;
  static constexpr size_t field_count = Descriptor::field_count;
  static inline constexpr auto entries = Descriptor::entries;
};

template <typename T>
struct FieldConfigInfo<T, std::enable_if_t<!HasMemberFieldConfig<T>::value &&
                                           HasAdlFieldConfig<T>::value>> {
  using Descriptor = AdlFieldConfigDescriptor<T>;
  static constexpr bool has_config = Descriptor::has_config;
  static constexpr size_t field_count = Descriptor::field_count;
  static inline constexpr auto entries = Descriptor::entries;
};

template <typename T>
inline constexpr bool has_field_config_v = FieldConfigInfo<T>::has_config;

template <typename T, size_t Index, typename = void>
struct GetFieldConfigEntry {
  static constexpr Encoding encoding = Encoding::Default;
  static constexpr int16_t id = -1;
  static constexpr bool nullable = false;
  static constexpr bool ref = false;
  static constexpr int dynamic_value = -1;
  static constexpr bool compress = true;
  static constexpr int16_t type_id_override = -1;
  static constexpr bool has_id = false;
  static constexpr FieldNodeSpec spec = FieldNodeSpec{};
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
  static constexpr bool has_id = entry.meta.has_id_;
  static constexpr FieldNodeSpec spec = entry.meta.spec_;
  static constexpr bool has_entry = entry.name[0] != '\0';
};

} // namespace detail

template <typename T> struct is_fory_field : std::false_type {};
template <typename T>
inline constexpr bool is_fory_field_v = is_fory_field<T>::value;

template <typename T> struct unwrap_field {
  using type = T;
};
template <typename T> using unwrap_field_t = typename unwrap_field<T>::type;

template <typename T> struct field_tag_id {
  static constexpr int16_t value = -1;
};
template <typename T>
inline constexpr int16_t field_tag_id_v = field_tag_id<T>::value;

template <typename T> struct field_is_nullable {
  static constexpr bool value = detail::is_optional_v<T>;
};
template <typename T>
inline constexpr bool field_is_nullable_v = field_is_nullable<T>::value;

template <typename T> struct field_track_ref {
  static constexpr bool value =
      detail::is_shared_ptr_v<T> || detail::is_shared_weak_v<T>;
};
template <typename T>
inline constexpr bool field_track_ref_v = field_track_ref<T>::value;

template <typename T> struct field_dynamic_value {
  static constexpr int value = -1;
};
template <typename T>
inline constexpr int field_dynamic_value_v = field_dynamic_value<T>::value;

} // namespace fory
