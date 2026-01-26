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

#include "fory/meta/enum_info.h"
#include "fory/serialization/fory.h"
#include "fory/serialization/struct_serializer.h"
#include "fory/serialization/union_serializer.h"

#include "gtest/gtest.h"

#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <variant>

namespace macro_test {

enum class LocalEnum { One, Two };

FORY_ENUM(LocalEnum, One, Two);

class Configured final {
public:
  Configured() = default;
  explicit Configured(int32_t id) : id_(id) {}

  bool operator==(const Configured &other) const { return id_ == other.id_; }

private:
  int32_t id_ = 0;

public:
  FORY_STRUCT(Configured, id_);
};

class OptionalHolder final {
public:
  OptionalHolder() = default;
  explicit OptionalHolder(std::optional<std::string> name)
      : name_(std::move(name)) {}

  bool operator==(const OptionalHolder &other) const {
    return name_ == other.name_;
  }

private:
  std::optional<std::string> name_;

public:
  FORY_STRUCT(OptionalHolder, name_);
};

class Choice final {
public:
  Choice() = default;

  static Choice text(std::string value) {
    return Choice(std::in_place_type<std::string>, std::move(value));
  }

  static Choice number(int32_t value) {
    return Choice(std::in_place_type<int32_t>, value);
  }

  uint32_t fory_case_id() const noexcept {
    if (std::holds_alternative<std::string>(value_)) {
      return 1;
    }
    return 2;
  }

  template <class Visitor> decltype(auto) visit(Visitor &&vis) const {
    if (std::holds_alternative<std::string>(value_)) {
      return std::forward<Visitor>(vis)(std::get<std::string>(value_));
    }
    return std::forward<Visitor>(vis)(std::get<int32_t>(value_));
  }

  bool operator==(const Choice &other) const { return value_ == other.value_; }

private:
  std::variant<std::string, int32_t> value_;

  template <class T, class... Args>
  explicit Choice(std::in_place_type_t<T> tag, Args &&...args)
      : value_(tag, std::forward<Args>(args)...) {}
};

class Partial final {
public:
  Partial() = default;
  Partial(int32_t id, std::optional<std::string> name, int64_t count)
      : id_(id), name_(std::move(name)), count_(count) {}

private:
  int32_t id_ = 0;
  std::optional<std::string> name_;
  int64_t count_ = 0;

public:
  FORY_STRUCT(Partial, id_, name_, count_);
};

class EnumContainer final {
public:
  enum class Kind { Alpha, Beta };
};

FORY_ENUM(EnumContainer::Kind, Alpha, Beta);

FORY_FIELD_CONFIG(Configured, Configured, (id_, fory::F().id(1).varint()));
FORY_FIELD_TAGS(OptionalHolder, (name_, 1));
FORY_FIELD_CONFIG(Partial, Partial, (count_, fory::F().id(7).varint()));
FORY_FIELD_TAGS(Partial, (id_, 5));

FORY_UNION(Choice, (std::string, text, fory::F(1)),
           (int32_t, number, fory::F(2).varint()));

} // namespace macro_test

namespace fory {
namespace serialization {
namespace test {

TEST(NamespaceMacros, FieldConfigAndTagsInNamespace) {
  static_assert(::fory::detail::has_field_config_v<macro_test::Configured>);
  static_assert(
      ::fory::detail::GetFieldConfigEntry<macro_test::Configured, 0>::id == 1);
  static_assert(::fory::detail::GetFieldConfigEntry<macro_test::Configured,
                                                    0>::encoding ==
                ::fory::Encoding::Varint);

  static_assert(::fory::detail::has_field_tags_v<macro_test::OptionalHolder>);
  static_assert(::fory::detail::GetFieldTagEntry<macro_test::OptionalHolder,
                                                 0>::is_nullable);

  static_assert(::fory::detail::GetFieldTagEntry<macro_test::Partial, 0>::id ==
                5);
  static_assert(
      !::fory::detail::GetFieldTagEntry<macro_test::Partial, 1>::has_entry);
  static_assert(::fory::serialization::detail::CompileTimeFieldHelpers<
                macro_test::Partial>::field_nullable<1>());
  static_assert(
      ::fory::detail::GetFieldConfigEntry<macro_test::Partial, 2>::id == 7);
  static_assert(
      ::fory::detail::GetFieldConfigEntry<macro_test::Partial, 0>::id == -1);
}

TEST(NamespaceMacros, UnionInNamespace) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  ASSERT_TRUE(fory.register_union<macro_test::Choice>(1001).ok());

  auto bytes = fory.serialize(macro_test::Choice::text("hello"));
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();

  auto decoded =
      fory.deserialize<macro_test::Choice>(bytes->data(), bytes->size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(macro_test::Choice::text("hello"), decoded.value());
}

TEST(NamespaceMacros, EnumInAndOutOfClass) {
  static_assert(::fory::meta::EnumInfo<macro_test::LocalEnum>::defined);
  static_assert(::fory::meta::EnumInfo<macro_test::LocalEnum>::size == 2);
  static_assert(::fory::meta::EnumInfo<macro_test::LocalEnum>::name(
                    macro_test::LocalEnum::One) == "LocalEnum::One");

  static_assert(
      ::fory::meta::EnumInfo<macro_test::EnumContainer::Kind>::defined);
  static_assert(::fory::meta::EnumInfo<macro_test::EnumContainer::Kind>::size ==
                2);
  static_assert(::fory::meta::EnumInfo<macro_test::EnumContainer::Kind>::name(
                    macro_test::EnumContainer::Kind::Alpha) ==
                "EnumContainer::Kind::Alpha");
}

} // namespace test
} // namespace serialization
} // namespace fory
