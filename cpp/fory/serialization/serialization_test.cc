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
#include "fory/serialization/ref_resolver.h"
#include "gtest/gtest.h"
#include <cstdint>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "fory/type/type.h"

// ============================================================================
// Test Struct Definitions (must be at global scope for FORY_STRUCT)
// ============================================================================

struct SimpleStruct {
  int32_t x;
  int32_t y;

  bool operator==(const SimpleStruct &other) const {
    return x == other.x && y == other.y;
  }
};

FORY_STRUCT(SimpleStruct, x, y);

struct ComplexStruct {
  std::string name;
  int32_t age;
  std::vector<std::string> hobbies;

  bool operator==(const ComplexStruct &other) const {
    return name == other.name && age == other.age && hobbies == other.hobbies;
  }
};

FORY_STRUCT(ComplexStruct, name, age, hobbies);

struct NestedStruct {
  SimpleStruct point;
  std::string label;

  bool operator==(const NestedStruct &other) const {
    return point == other.point && label == other.label;
  }
};

FORY_STRUCT(NestedStruct, point, label);

enum class Color { RED, GREEN, BLUE };
enum class LegacyStatus : int32_t { NEG = -3, ZERO = 0, LARGE = 42 };
FORY_ENUM(LegacyStatus, NEG, ZERO, LARGE);

enum OldStatus : int32_t { OLD_NEG = -7, OLD_ZERO = 0, OLD_POS = 13 };
FORY_ENUM(::OldStatus, OLD_NEG, OLD_ZERO, OLD_POS);

namespace fory {
namespace serialization {
namespace test {

// ============================================================================
// Test Helpers
// ============================================================================

template <typename T>
void test_roundtrip(const T &original, bool should_equal = true) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();

  // Serialize
  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(serialize_result).value();
  ASSERT_GT(bytes.size(), 0) << "Serialized bytes should not be empty";

  // Deserialize
  auto deserialize_result = fory.deserialize<T>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();

  T deserialized = std::move(deserialize_result).value();

  // Compare
  if (should_equal) {
    EXPECT_EQ(original, deserialized);
  }
}

// ============================================================================
// Primitive Type Tests
// ============================================================================

TEST(SerializationTest, BoolRoundtrip) {
  test_roundtrip(true);
  test_roundtrip(false);
}

TEST(SerializationTest, Int8Roundtrip) {
  test_roundtrip<int8_t>(0);
  test_roundtrip<int8_t>(127);
  test_roundtrip<int8_t>(-128);
  test_roundtrip<int8_t>(42);
}

TEST(SerializationTest, Int16Roundtrip) {
  test_roundtrip<int16_t>(0);
  test_roundtrip<int16_t>(32767);
  test_roundtrip<int16_t>(-32768);
  test_roundtrip<int16_t>(1234);
}

TEST(SerializationTest, Int32Roundtrip) {
  test_roundtrip<int32_t>(0);
  test_roundtrip<int32_t>(2147483647);
  test_roundtrip<int32_t>(-2147483648);
  test_roundtrip<int32_t>(123456);
}

TEST(SerializationTest, Int64Roundtrip) {
  test_roundtrip<int64_t>(0);
  test_roundtrip<int64_t>(9223372036854775807LL);
  test_roundtrip<int64_t>(-9223372036854775807LL - 1);
  test_roundtrip<int64_t>(123456789012345LL);
}

TEST(SerializationTest, FloatRoundtrip) {
  test_roundtrip<float>(0.0f);
  test_roundtrip<float>(3.14159f);
  test_roundtrip<float>(-2.71828f);
  test_roundtrip<float>(1.23456e10f);
}

TEST(SerializationTest, DoubleRoundtrip) {
  test_roundtrip<double>(0.0);
  test_roundtrip<double>(3.141592653589793);
  test_roundtrip<double>(-2.718281828459045);
  test_roundtrip<double>(1.23456789012345e100);
}

TEST(SerializationTest, StringRoundtrip) {
  test_roundtrip(std::string(""));
  test_roundtrip(std::string("Hello, World!"));
  test_roundtrip(std::string("The quick brown fox jumps over the lazy dog"));
  test_roundtrip(std::string("UTF-8: 你好世界"));
}

// ============================================================================
// Enum Tests
// ============================================================================

TEST(SerializationTest, EnumRoundtrip) {
  test_roundtrip(Color::RED);
  test_roundtrip(Color::GREEN);
  test_roundtrip(Color::BLUE);
}

TEST(SerializationTest, OldEnumRoundtrip) {
  test_roundtrip(OldStatus::OLD_NEG);
  test_roundtrip(OldStatus::OLD_ZERO);
  test_roundtrip(OldStatus::OLD_POS);
}

TEST(SerializationTest, EnumSerializesOrdinalValue) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();

  auto bytes_result = fory.serialize(LegacyStatus::LARGE);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  ASSERT_GE(bytes.size(), 4 + 1 + 1 + sizeof(int32_t));
  size_t offset = 4;
  EXPECT_EQ(bytes[offset], static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  EXPECT_EQ(bytes[offset + 1], static_cast<uint8_t>(TypeId::ENUM));

  int32_t serialized_value = 0;
  std::memcpy(&serialized_value, bytes.data() + offset + 2, sizeof(int32_t));
  EXPECT_EQ(serialized_value, 2);
}

TEST(SerializationTest, OldEnumSerializesOrdinalValue) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();

  auto bytes_result = fory.serialize(OldStatus::OLD_POS);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  ASSERT_GE(bytes.size(), 4 + 1 + 1 + sizeof(int32_t));
  size_t offset = 4;
  EXPECT_EQ(bytes[offset], static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  EXPECT_EQ(bytes[offset + 1], static_cast<uint8_t>(TypeId::ENUM));

  int32_t serialized_value = 0;
  std::memcpy(&serialized_value, bytes.data() + offset + 2, sizeof(int32_t));
  EXPECT_EQ(serialized_value, 2);
}

TEST(SerializationTest, EnumOrdinalMappingHandlesNonZeroStart) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();

  auto bytes_result = fory.serialize(LegacyStatus::NEG);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  ASSERT_GE(bytes.size(), 4 + 1 + 1 + sizeof(int32_t));
  size_t offset = 4;
  EXPECT_EQ(bytes[offset], static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  EXPECT_EQ(bytes[offset + 1], static_cast<uint8_t>(TypeId::ENUM));
  int32_t serialized_value = 0;
  std::memcpy(&serialized_value, bytes.data() + offset + 2, sizeof(int32_t));
  EXPECT_EQ(serialized_value, 0);

  auto roundtrip = fory.deserialize<LegacyStatus>(bytes.data(), bytes.size());
  ASSERT_TRUE(roundtrip.ok())
      << "Deserialization failed: " << roundtrip.error().to_string();
  EXPECT_EQ(roundtrip.value(), LegacyStatus::NEG);
}

TEST(SerializationTest, EnumOrdinalMappingRejectsInvalidOrdinal) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();

  auto bytes_result = fory.serialize(LegacyStatus::NEG);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  size_t offset = 4;
  int32_t invalid_ordinal = 99;
  std::memcpy(bytes.data() + offset + 2, &invalid_ordinal, sizeof(int32_t));

  auto decode = fory.deserialize<LegacyStatus>(bytes.data(), bytes.size());
  EXPECT_FALSE(decode.ok());
}

TEST(SerializationTest, OldEnumOrdinalMappingHandlesNonZeroStart) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();

  auto bytes_result = fory.serialize(OldStatus::OLD_NEG);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  ASSERT_GE(bytes.size(), 4 + 1 + 1 + sizeof(int32_t));
  size_t offset = 4;
  EXPECT_EQ(bytes[offset], static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  EXPECT_EQ(bytes[offset + 1], static_cast<uint8_t>(TypeId::ENUM));
  int32_t serialized_value = 0;
  std::memcpy(&serialized_value, bytes.data() + offset + 2, sizeof(int32_t));
  EXPECT_EQ(serialized_value, 0);

  auto roundtrip = fory.deserialize<OldStatus>(bytes.data(), bytes.size());
  ASSERT_TRUE(roundtrip.ok())
      << "Deserialization failed: " << roundtrip.error().to_string();
  EXPECT_EQ(roundtrip.value(), OldStatus::OLD_NEG);
}

// ============================================================================
// Container Type Tests
// ============================================================================

TEST(SerializationTest, VectorIntRoundtrip) {
  test_roundtrip(std::vector<int32_t>{});
  test_roundtrip(std::vector<int32_t>{1});
  test_roundtrip(std::vector<int32_t>{1, 2, 3, 4, 5});
  test_roundtrip(std::vector<int32_t>{-10, 0, 10, 20, 30});
}

TEST(SerializationTest, VectorStringRoundtrip) {
  test_roundtrip(std::vector<std::string>{});
  test_roundtrip(std::vector<std::string>{"hello"});
  test_roundtrip(std::vector<std::string>{"foo", "bar", "baz"});
}

TEST(SerializationTest, MapStringIntRoundtrip) {
  test_roundtrip(std::map<std::string, int32_t>{});
  test_roundtrip(std::map<std::string, int32_t>{{"one", 1}});
  test_roundtrip(
      std::map<std::string, int32_t>{{"one", 1}, {"two", 2}, {"three", 3}});
}

TEST(SerializationTest, NestedVectorRoundtrip) {
  test_roundtrip(std::vector<std::vector<int32_t>>{});
  test_roundtrip(std::vector<std::vector<int32_t>>{{1, 2}, {3, 4}, {5}});
}

// ============================================================================
// Struct Type Tests (using structs defined above)
// ============================================================================

TEST(SerializationTest, SimpleStructRoundtrip) {
  ::SimpleStruct s1{42, 100};
  test_roundtrip(s1);

  ::SimpleStruct s2{0, 0};
  test_roundtrip(s2);

  ::SimpleStruct s3{-10, -20};
  test_roundtrip(s3);
}

TEST(SerializationTest, ComplexStructRoundtrip) {
  ::ComplexStruct c1{"Alice", 30, {"reading", "coding", "gaming"}};
  test_roundtrip(c1);

  ::ComplexStruct c2{"Bob", 25, {}};
  test_roundtrip(c2);
}

TEST(SerializationTest, NestedStructRoundtrip) {
  ::NestedStruct n1{{10, 20}, "origin"};
  test_roundtrip(n1);

  ::NestedStruct n2{{-5, 15}, "point A"};
  test_roundtrip(n2);
}

// ============================================================================
// Error Handling Tests
// ============================================================================

TEST(SerializationTest, DeserializeInvalidData) {
  auto fory = Fory::builder().build();

  uint8_t invalid_data[] = {0xFF, 0xFF, 0xFF};
  auto result = fory.deserialize<int32_t>(invalid_data, 3);
  EXPECT_FALSE(result.ok());
}

TEST(SerializationTest, DeserializeNullPointer) {
  auto fory = Fory::builder().build();
  auto result = fory.deserialize<int32_t>(nullptr, 0);
  EXPECT_FALSE(result.ok());
}

TEST(SerializationTest, DeserializeZeroSize) {
  auto fory = Fory::builder().build();
  uint8_t data[] = {0x01};
  auto result = fory.deserialize<int32_t>(data, 0);
  EXPECT_FALSE(result.ok());
}

// ============================================================================
// Configuration Tests
// ============================================================================

TEST(SerializationTest, ConfigurationBuilder) {
  auto fory1 = Fory::builder()
                   .compatible(true)
                   .xlang(false)
                   .check_struct_version(true)
                   .max_depth(128)
                   .track_ref(false)
                   .build();

  EXPECT_TRUE(fory1.config().compatible);
  EXPECT_FALSE(fory1.config().xlang);
  EXPECT_TRUE(fory1.config().check_struct_version);
  EXPECT_EQ(fory1.config().max_depth, 128);
  EXPECT_FALSE(fory1.config().track_ref);
}

} // namespace test
} // namespace serialization
} // namespace fory
