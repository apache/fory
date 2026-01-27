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

#include "gtest/gtest.h"

#include <cstdint>
#include <iostream>
#include <string_view>
#include <type_traits>

namespace fory {

enum class PlainEnum : int32_t { A = -2, B = 7 };

enum class RegisteredEnum : int32_t { FIRST = -3, SECOND = 0, THIRD = 42 };

enum LegacyEnum : int32_t {
  LEGACY_FIRST = 5,
  LEGACY_SECOND = 15,
  LEGACY_THIRD = 25
};

FORY_ENUM(::fory::RegisteredEnum, FIRST, SECOND, THIRD);
FORY_ENUM(::fory::LegacyEnum, LEGACY_FIRST, LEGACY_SECOND, LEGACY_THIRD);

} // namespace fory

namespace fory {
namespace test {

using ::fory::LegacyEnum;
using ::fory::PlainEnum;
using ::fory::RegisteredEnum;

TEST(EnumInfoTest, DefaultMetadataFallback) {
  using Info = meta::EnumInfo<PlainEnum>;
  static_assert(!Info::defined, "PlainEnum should not be registered");
  EXPECT_FALSE(Info::defined);
  EXPECT_EQ(0u, Info::size);
  EXPECT_FALSE(Info::contains(PlainEnum::A));
  EXPECT_FALSE(Info::contains(static_cast<PlainEnum>(123)));

  using Metadata = meta::EnumMetadata<PlainEnum>;
  std::underlying_type_t<PlainEnum> ordinal = 0;
  EXPECT_TRUE(Metadata::to_ordinal(PlainEnum::B, &ordinal));
  EXPECT_EQ(static_cast<std::underlying_type_t<PlainEnum>>(PlainEnum::B),
            ordinal);

  PlainEnum roundtrip = PlainEnum::A;
  EXPECT_TRUE(Metadata::from_ordinal(ordinal, &roundtrip));
  EXPECT_EQ(PlainEnum::B, roundtrip);

  ordinal = static_cast<std::underlying_type_t<PlainEnum>>(PlainEnum::A);
  EXPECT_TRUE(Metadata::from_ordinal(ordinal, &roundtrip));
  EXPECT_EQ(PlainEnum::A, roundtrip);
}

TEST(EnumInfoTest, RegisteredEnumProvidesMetadata) {
  using Info = meta::EnumInfo<RegisteredEnum>;
  static_assert(Info::defined, "RegisteredEnum should be registered");
  EXPECT_TRUE(Info::defined);
  EXPECT_EQ(3u, Info::size);

  EXPECT_TRUE(Info::contains(RegisteredEnum::FIRST));
  EXPECT_TRUE(Info::contains(RegisteredEnum::SECOND));
  EXPECT_TRUE(Info::contains(RegisteredEnum::THIRD));
  EXPECT_FALSE(Info::contains(static_cast<RegisteredEnum>(-1000)));

  EXPECT_EQ(RegisteredEnum::FIRST, Info::values[0]);
  EXPECT_EQ(RegisteredEnum::SECOND, Info::values[1]);
  EXPECT_EQ(RegisteredEnum::THIRD, Info::values[2]);

  EXPECT_EQ(std::string_view("::fory::RegisteredEnum::FIRST"), Info::names[0]);
  EXPECT_EQ(std::string_view("::fory::RegisteredEnum::SECOND"), Info::names[1]);
  EXPECT_EQ(std::string_view("::fory::RegisteredEnum::THIRD"), Info::names[2]);

  EXPECT_EQ(0u, Info::ordinal(RegisteredEnum::FIRST));
  EXPECT_EQ(1u, Info::ordinal(RegisteredEnum::SECOND));
  EXPECT_EQ(2u, Info::ordinal(RegisteredEnum::THIRD));
  EXPECT_EQ(Info::size, Info::ordinal(static_cast<RegisteredEnum>(INT32_MIN)));

  EXPECT_EQ(RegisteredEnum::FIRST, Info::value_at(0));
  EXPECT_EQ(RegisteredEnum::SECOND, Info::value_at(1));
  EXPECT_EQ(RegisteredEnum::THIRD, Info::value_at(2));

  EXPECT_EQ(std::string_view("::fory::RegisteredEnum::FIRST"),
            Info::name(RegisteredEnum::FIRST));
  EXPECT_EQ(std::string_view("::fory::RegisteredEnum::SECOND"),
            Info::name(RegisteredEnum::SECOND));
  EXPECT_EQ(std::string_view("::fory::RegisteredEnum::THIRD"),
            Info::name(RegisteredEnum::THIRD));
  EXPECT_TRUE(Info::name(static_cast<RegisteredEnum>(INT32_MAX)).empty());

  using Metadata = meta::EnumMetadata<RegisteredEnum>;
  std::underlying_type_t<RegisteredEnum> ordinal = 0;
  EXPECT_TRUE(Metadata::to_ordinal(RegisteredEnum::THIRD, &ordinal));
  EXPECT_EQ(2, ordinal);

  RegisteredEnum roundtrip = RegisteredEnum::FIRST;
  EXPECT_TRUE(Metadata::from_ordinal(ordinal, &roundtrip));
  EXPECT_EQ(RegisteredEnum::THIRD, roundtrip);

  ordinal = 5;
  EXPECT_FALSE(Metadata::from_ordinal(ordinal, &roundtrip));
  ordinal = -1;
  EXPECT_FALSE(Metadata::from_ordinal(ordinal, &roundtrip));
}

TEST(EnumInfoTest, LegacyEnumProvidesMetadata) {
  using Info = meta::EnumInfo<LegacyEnum>;
  static_assert(Info::defined, "LegacyEnum should be registered");
  EXPECT_TRUE(Info::defined);
  EXPECT_EQ(3u, Info::size);

  EXPECT_TRUE(Info::contains(LegacyEnum::LEGACY_FIRST));
  EXPECT_TRUE(Info::contains(LegacyEnum::LEGACY_SECOND));
  EXPECT_TRUE(Info::contains(LegacyEnum::LEGACY_THIRD));
  EXPECT_FALSE(Info::contains(static_cast<LegacyEnum>(-1000)));

  EXPECT_EQ(LegacyEnum::LEGACY_FIRST, Info::values[0]);
  EXPECT_EQ(LegacyEnum::LEGACY_SECOND, Info::values[1]);
  EXPECT_EQ(LegacyEnum::LEGACY_THIRD, Info::values[2]);

  EXPECT_EQ(std::string_view("::fory::LegacyEnum::LEGACY_FIRST"),
            Info::names[0]);
  EXPECT_EQ(std::string_view("::fory::LegacyEnum::LEGACY_SECOND"),
            Info::names[1]);
  EXPECT_EQ(std::string_view("::fory::LegacyEnum::LEGACY_THIRD"),
            Info::names[2]);

  EXPECT_EQ(0u, Info::ordinal(LegacyEnum::LEGACY_FIRST));
  EXPECT_EQ(1u, Info::ordinal(LegacyEnum::LEGACY_SECOND));
  EXPECT_EQ(2u, Info::ordinal(LegacyEnum::LEGACY_THIRD));
  EXPECT_EQ(Info::size, Info::ordinal(static_cast<LegacyEnum>(INT32_MIN)));

  EXPECT_EQ(LegacyEnum::LEGACY_FIRST, Info::value_at(0));
  EXPECT_EQ(LegacyEnum::LEGACY_SECOND, Info::value_at(1));
  EXPECT_EQ(LegacyEnum::LEGACY_THIRD, Info::value_at(2));

  EXPECT_EQ(std::string_view("::fory::LegacyEnum::LEGACY_FIRST"),
            Info::name(LegacyEnum::LEGACY_FIRST));
  EXPECT_EQ(std::string_view("::fory::LegacyEnum::LEGACY_SECOND"),
            Info::name(LegacyEnum::LEGACY_SECOND));
  EXPECT_EQ(std::string_view("::fory::LegacyEnum::LEGACY_THIRD"),
            Info::name(LegacyEnum::LEGACY_THIRD));
  EXPECT_TRUE(Info::name(static_cast<LegacyEnum>(INT32_MAX)).empty());

  using Metadata = meta::EnumMetadata<LegacyEnum>;
  std::underlying_type_t<LegacyEnum> ordinal = 0;
  EXPECT_TRUE(Metadata::to_ordinal(LegacyEnum::LEGACY_THIRD, &ordinal));
  EXPECT_EQ(2, ordinal);

  LegacyEnum roundtrip = LegacyEnum::LEGACY_FIRST;
  EXPECT_TRUE(Metadata::from_ordinal(ordinal, &roundtrip));
  EXPECT_EQ(LegacyEnum::LEGACY_THIRD, roundtrip);

  ordinal = 5;
  EXPECT_FALSE(Metadata::from_ordinal(ordinal, &roundtrip));
  ordinal = -1;
  EXPECT_FALSE(Metadata::from_ordinal(ordinal, &roundtrip));
}

} // namespace test
} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  std::cout << "Starting EnumInfo tests" << std::endl;
  return RUN_ALL_TESTS();
}
