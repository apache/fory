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
#include <map>
#include <optional>
#include <string>
#include <utility>
#include <variant>
#include <vector>

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
  FORY_STRUCT(Configured, (id_, fory::F(1).varint()));
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
  FORY_STRUCT(OptionalHolder, (name_, fory::F(1)));
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

class NestedChoice final {
public:
  using Values = std::map<uint32_t, std::vector<uint64_t>>;

  NestedChoice() = default;

  static NestedChoice values(Values value) {
    return NestedChoice(std::move(value));
  }

  uint32_t fory_case_id() const noexcept { return 1; }

  template <class Visitor> decltype(auto) visit(Visitor &&vis) const {
    return std::forward<Visitor>(vis)(values_);
  }

  bool operator==(const NestedChoice &other) const {
    return values_ == other.values_;
  }

private:
  Values values_;

  explicit NestedChoice(Values value) : values_(std::move(value)) {}
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
  FORY_STRUCT(Partial, (id_, fory::F(5)), (name_, fory::F(6)),
              (count_, fory::F(7).varint()));
};

class EnumContainer final {
public:
  enum class Kind { Alpha, Beta };
};

FORY_ENUM(EnumContainer::Kind, Alpha, Beta);

FORY_UNION(Choice, (text, std::string, fory::F(1)),
           (number, int32_t, fory::F(2).varint()));
FORY_UNION(NestedChoice,
           (values, NestedChoice::Values,
            fory::F(1).map(fory::T::uint32().fixed(),
                           fory::T::list(fory::T::uint64().tagged()))));

} // namespace macro_test

namespace fory {
namespace serialization {
namespace test {

TEST(NamespaceMacros, FieldConfigInNamespace) {
  static_assert(::fory::detail::has_field_config_v<macro_test::Configured>);
  static_assert(
      ::fory::detail::GetFieldConfigEntry<macro_test::Configured, 0>::id == 1);
  static_assert(::fory::detail::GetFieldConfigEntry<macro_test::Configured,
                                                    0>::encoding ==
                ::fory::Encoding::Varint);

  static_assert(
      ::fory::detail::GetFieldConfigEntry<macro_test::OptionalHolder, 0>::id ==
      1);
  static_assert(::fory::serialization::detail::CompileTimeFieldHelpers<
                macro_test::OptionalHolder>::field_nullable<0>());

  static_assert(
      ::fory::detail::GetFieldConfigEntry<macro_test::Partial, 0>::id == 5);
  static_assert(
      ::fory::detail::GetFieldConfigEntry<macro_test::Partial, 1>::id == 6);
  static_assert(::fory::serialization::detail::CompileTimeFieldHelpers<
                macro_test::Partial>::field_nullable<1>());
  static_assert(
      ::fory::detail::GetFieldConfigEntry<macro_test::Partial, 2>::id == 7);
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

TEST(NamespaceMacros, NestedUnionPayloadSpec) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  ASSERT_TRUE(fory.register_union<macro_test::NestedChoice>(1002).ok());

  macro_test::NestedChoice::Values values = {{4000000000u, {7u, 1000000000u}},
                                             {3u, {42u}}};
  auto bytes = fory.serialize(macro_test::NestedChoice::values(values));
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();

  auto decoded =
      fory.deserialize<macro_test::NestedChoice>(bytes->data(), bytes->size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(macro_test::NestedChoice::values(values), decoded.value());
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
