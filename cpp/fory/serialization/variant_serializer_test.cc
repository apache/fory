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

#include "fory/serialization/fory.h"
#include "gtest/gtest.h"
#include <string>
#include <variant>
#include <vector>

using namespace fory::serialization;

// Structs for schema evolution testing
namespace {
using VariantType = std::variant<int, std::string>;

// Old schema: struct with two variant fields
struct OldStruct {
  int id;
  VariantType field1;
  VariantType field2;
  FORY_STRUCT(OldStruct, id, field1, field2);
};

// New schema: struct with only one variant field
struct NewStruct {
  int id;
  VariantType field1;
  FORY_STRUCT(NewStruct, id, field1);
};
} // namespace

// Helper to create a Fory instance
Fory create_fory() {
  return Fory::builder().xlang(false).track_ref(true).build();
}

Fory create_xlang_fory() {
  return Fory::builder().xlang(true).track_ref(true).build();
}

// Test basic variant serialization with primitive types
TEST(VariantSerializerTest, BasicVariant) {
  Fory fory = create_fory();

  // Test with int active
  {
    std::variant<int, std::string, double> v1 = 42;
    auto result1 = fory.serialize(v1);
    ASSERT_TRUE(result1.has_value());

    auto read_result1 =
        fory.deserialize<std::variant<int, std::string, double>>(
            result1.value());
    ASSERT_TRUE(read_result1.has_value()) << read_result1.error().message();
    ASSERT_EQ(read_result1.value().index(), 0);
    ASSERT_EQ(std::get<0>(read_result1.value()), 42);
  }

  // Test with string active
  {
    std::variant<int, std::string, double> v2 = std::string("hello");
    auto result2 = fory.serialize(v2);
    ASSERT_TRUE(result2.has_value());

    auto read_result2 =
        fory.deserialize<std::variant<int, std::string, double>>(
            result2.value());
    ASSERT_TRUE(read_result2.has_value());
    ASSERT_EQ(read_result2.value().index(), 1);
    ASSERT_EQ(std::get<1>(read_result2.value()), "hello");
  }

  // Test with double active
  {
    std::variant<int, std::string, double> v3 = 3.14;
    auto result3 = fory.serialize(v3);
    ASSERT_TRUE(result3.has_value());

    auto read_result3 =
        fory.deserialize<std::variant<int, std::string, double>>(
            result3.value());
    ASSERT_TRUE(read_result3.has_value());
    ASSERT_EQ(read_result3.value().index(), 2);
    ASSERT_DOUBLE_EQ(std::get<2>(read_result3.value()), 3.14);
  }
}

// Test variant with complex types
TEST(VariantSerializerTest, ComplexVariant) {
  Fory fory = create_fory();

  std::variant<std::vector<int>, std::string> v1 =
      std::vector<int>{1, 2, 3, 4, 5};
  auto result = fory.serialize(v1);
  ASSERT_TRUE(result.has_value());

  auto read_result =
      fory.deserialize<std::variant<std::vector<int>, std::string>>(
          result.value());
  ASSERT_TRUE(read_result.has_value());
  ASSERT_EQ(read_result.value().index(), 0);

  auto vec = std::get<0>(read_result.value());
  ASSERT_EQ(vec.size(), 5);
  ASSERT_EQ(vec[0], 1);
  ASSERT_EQ(vec[4], 5);
}

// Test variant in xlang mode
TEST(VariantSerializerTest, XlangMode) {
  Fory fory = create_xlang_fory();

  std::variant<int, std::string, bool> v = std::string("xlang");
  auto result = fory.serialize(v);
  ASSERT_TRUE(result.has_value());

  auto read_result =
      fory.deserialize<std::variant<int, std::string, bool>>(result.value());
  ASSERT_TRUE(read_result.has_value());
  ASSERT_EQ(read_result.value().index(), 1);
  ASSERT_EQ(std::get<1>(read_result.value()), "xlang");
}

// Test variant with monostate (empty variant)
TEST(VariantSerializerTest, MonostateVariant) {
  Fory fory = create_fory();

  std::variant<std::monostate, int, std::string> v1;
  auto result = fory.serialize(v1);
  ASSERT_TRUE(result.has_value());

  auto read_result =
      fory.deserialize<std::variant<std::monostate, int, std::string>>(
          result.value());
  ASSERT_TRUE(read_result.has_value());
  ASSERT_EQ(read_result.value().index(), 0);
  ASSERT_TRUE(std::holds_alternative<std::monostate>(read_result.value()));
}

// Test nested variants
TEST(VariantSerializerTest, NestedVariant) {
  Fory fory = create_fory();

  using InnerVariant = std::variant<int, std::string>;
  using OuterVariant = std::variant<InnerVariant, double>;

  OuterVariant v = InnerVariant{std::string("nested")};
  auto result = fory.serialize(v);
  ASSERT_TRUE(result.has_value());

  auto read_result = fory.deserialize<OuterVariant>(result.value());
  ASSERT_TRUE(read_result.has_value());
  ASSERT_EQ(read_result.value().index(), 0);

  auto inner = std::get<0>(read_result.value());
  ASSERT_EQ(inner.index(), 1);
  ASSERT_EQ(std::get<1>(inner), "nested");
}

// Test that variant can be skipped in compatible mode for schema evolution
TEST(VariantSerializerTest, SkipInCompatibleMode) {
  constexpr uint32_t type_id = 1;

  // Create first Fory instance and register OldStruct
  auto fory1 = Fory::builder().compatible(true).xlang(true).build();
  fory1.register_struct<OldStruct>(type_id);

  // Serialize with old schema (two variants)
  OldStruct old_data{42, std::string("hello"), 123};
  auto result = fory1.serialize(old_data);
  ASSERT_TRUE(result.has_value()) << result.error().message();

  // Create second Fory instance and register NewStruct with same type_id
  auto fory2 = Fory::builder().compatible(true).xlang(true).build();
  fory2.register_struct<NewStruct>(type_id);

  // Deserialize with new schema (one variant) - should skip field2
  auto read_result = fory2.deserialize<NewStruct>(result.value());
  ASSERT_TRUE(read_result.has_value()) << read_result.error().message();
  ASSERT_EQ(read_result.value().id, 42);
  ASSERT_EQ(read_result.value().field1.index(), 1);
  ASSERT_EQ(std::get<1>(read_result.value().field1), "hello");
}
