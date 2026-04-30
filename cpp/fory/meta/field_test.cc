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

#include "gtest/gtest.h"

#include "fory/meta/field.h"
#include "fory/meta/field_info.h"
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace fory::test {

namespace T = fory::T;

TEST(FieldTraits, CarrierTraits) {
  static_assert(!detail::is_shared_ptr_v<int>);
  static_assert(detail::is_shared_ptr_v<std::shared_ptr<int>>);
  static_assert(!detail::is_unique_ptr_v<int>);
  static_assert(detail::is_unique_ptr_v<std::unique_ptr<int>>);
  static_assert(!detail::is_optional_v<int>);
  static_assert(detail::is_optional_v<std::optional<int>>);
  static_assert(!detail::is_smart_ptr_v<std::optional<int>>);
  static_assert(detail::is_smart_ptr_v<std::shared_ptr<int>>);
  static_assert(detail::is_smart_ptr_v<std::unique_ptr<int>>);
}

TEST(FieldBuilder, ScalarEncodingSpec) {
  constexpr auto fixed = fory::F(7).fixed();
  static_assert(fixed.has_id_);
  static_assert(fixed.id_ == 7);
  static_assert(fixed.encoding_ == Encoding::Fixed);
  static_assert(fixed.spec_.encoding_[0] == Encoding::Fixed);

  constexpr auto tagged = T::uint64().tagged();
  static_assert(tagged.scalar_[0] == FieldScalarKind::UInt64);
  static_assert(tagged.encoding_[0] == Encoding::Tagged);
}

TEST(FieldBuilder, NestedSpecComposition) {
  constexpr auto spec = fory::F().map(T::varint(), T::list(T::tagged()));
  static_assert(!spec.has_id_);
  static_assert(spec.spec_.kind_[0] == FieldNodeKind::Map);
  static_assert(spec.spec_.kind_[1] == FieldNodeKind::Scalar);
  static_assert(spec.spec_.encoding_[1] == Encoding::Varint);
  static_assert(spec.spec_.kind_[2] == FieldNodeKind::List);
  static_assert(spec.spec_.encoding_[3] == Encoding::Tagged);
}

TEST(FieldBuilder, ChainMapPartialOverrides) {
  constexpr auto key_only = fory::F().map().key(T::varint());
  static_assert(key_only.spec_.kind_[0] == FieldNodeKind::Map);
  static_assert(key_only.spec_.child0_[0] == 1);
  static_assert(key_only.spec_.child1_[0] == -1);
  static_assert(key_only.spec_.encoding_[1] == Encoding::Varint);

  constexpr auto value_only = fory::F().map().value(T::list(T::tagged()));
  static_assert(value_only.spec_.child0_[0] == -1);
  static_assert(value_only.spec_.child1_[0] == 1);
  static_assert(value_only.spec_.kind_[1] == FieldNodeKind::List);
  static_assert(value_only.spec_.encoding_[2] == Encoding::Tagged);
}

struct NameModeStruct {
  uint32_t id;
  std::optional<std::vector<uint64_t>> values;

  FORY_STRUCT(NameModeStruct, id,
              (values, fory::F().inner(T::list(T::tagged()))));
};

struct IdModeStruct {
  uint32_t id;
  std::map<uint32_t, std::vector<int64_t>> values;

  FORY_STRUCT(IdModeStruct, (id, fory::F(0).varint()),
              (values, fory::F(1).map(T::varint(), T::list(T::tagged()))));
};

struct NamespaceConfigStruct {
  uint32_t id;
  uint64_t timestamp;
};

FORY_STRUCT(NamespaceConfigStruct, (id, fory::F(3).varint()),
            (timestamp, fory::F(4).tagged()));

TEST(FieldConfig, NameModeEntries) {
  static_assert(detail::has_field_config_v<NameModeStruct>);
  static_assert(!detail::GetFieldConfigEntry<NameModeStruct, 0>::has_id);
  static_assert(!detail::GetFieldConfigEntry<NameModeStruct, 1>::has_id);
  static_assert(detail::GetFieldConfigEntry<NameModeStruct, 1>::spec.kind_[0] ==
                FieldNodeKind::Inner);
}

TEST(FieldConfig, IdModeEntries) {
  static_assert(detail::has_field_config_v<IdModeStruct>);
  static_assert(detail::GetFieldConfigEntry<IdModeStruct, 0>::has_id);
  static_assert(detail::GetFieldConfigEntry<IdModeStruct, 0>::id == 0);
  static_assert(detail::GetFieldConfigEntry<IdModeStruct, 0>::encoding ==
                Encoding::Varint);
  static_assert(detail::GetFieldConfigEntry<IdModeStruct, 1>::has_id);
  static_assert(detail::GetFieldConfigEntry<IdModeStruct, 1>::id == 1);
  static_assert(detail::GetFieldConfigEntry<IdModeStruct, 1>::spec.kind_[0] ==
                FieldNodeKind::Map);
}

TEST(FieldConfig, NamespaceScopeEntries) {
  static_assert(detail::has_field_config_v<NamespaceConfigStruct>);
  static_assert(
      detail::GetFieldConfigEntry<NamespaceConfigStruct, 0>::has_entry);
  static_assert(detail::GetFieldConfigEntry<NamespaceConfigStruct, 0>::has_id);
  static_assert(detail::GetFieldConfigEntry<NamespaceConfigStruct, 0>::id == 3);
  static_assert(
      detail::GetFieldConfigEntry<NamespaceConfigStruct, 0>::encoding ==
      Encoding::Varint);
  static_assert(detail::GetFieldConfigEntry<NamespaceConfigStruct, 1>::has_id);
  static_assert(detail::GetFieldConfigEntry<NamespaceConfigStruct, 1>::id == 4);
  static_assert(detail::GetFieldConfigEntry<NamespaceConfigStruct, 1>::spec
                    .encoding_[0] == Encoding::Tagged);
}

} // namespace fory::test
