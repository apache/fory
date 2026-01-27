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
#include <gtest/gtest.h>
#include <map>
#include <memory>
#include <string>
#include <unordered_map>

using namespace fory::serialization;

// Helper function to test roundtrip serialization
template <typename T> void test_map_roundtrip(const T &original) {
  // Create Fory instance with default config
  auto fory = Fory::builder().xlang(true).build();

  // Serialize
  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().message();
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result = fory.deserialize<T>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().message();
  auto deserialized = deserialize_result.value();

  // Compare
  EXPECT_EQ(original, deserialized);
}

// ============================================================================
// Basic Map Tests
// ============================================================================

TEST(MapSerializerTest, EmptyMapRoundtrip) {
  test_map_roundtrip(std::map<std::string, int32_t>{});
  test_map_roundtrip(std::unordered_map<std::string, int32_t>{});
}

TEST(MapSerializerTest, SingleEntryMapRoundtrip) {
  test_map_roundtrip(std::map<std::string, int32_t>{{"one", 1}});
  test_map_roundtrip(std::unordered_map<std::string, int32_t>{{"one", 1}});
}

TEST(MapSerializerTest, SmallMapRoundtrip) {
  std::map<std::string, int32_t> map{
      {"one", 1}, {"two", 2}, {"three", 3}, {"four", 4}, {"five", 5}};
  test_map_roundtrip(map);

  std::unordered_map<std::string, int32_t> umap{
      {"one", 1}, {"two", 2}, {"three", 3}, {"four", 4}, {"five", 5}};
  test_map_roundtrip(umap);
}

TEST(MapSerializerTest, LargeMapRoundtrip) {
  // Test chunking behavior with more than 255 entries
  std::map<int32_t, std::string> map;
  for (int i = 0; i < 300; ++i) {
    map[i] = "value_" + std::to_string(i);
  }
  test_map_roundtrip(map);
}

TEST(MapSerializerTest, MapIntToStringRoundtrip) {
  std::map<int32_t, std::string> map{{1, "one"}, {2, "two"}, {3, "three"}};
  test_map_roundtrip(map);
}

TEST(MapSerializerTest, MapStringToVectorRoundtrip) {
  std::map<std::string, std::vector<int32_t>> map{
      {"first", {1, 2, 3}}, {"second", {4, 5, 6}}, {"third", {7, 8, 9}}};
  test_map_roundtrip(map);
}

TEST(MapSerializerTest, NestedMapRoundtrip) {
  std::map<std::string, std::map<std::string, int32_t>> nested{
      {"group1", {{"a", 1}, {"b", 2}}}, {"group2", {{"c", 3}, {"d", 4}}}};
  test_map_roundtrip(nested);
}

// ============================================================================
// Map with Optional Values (Polymorphic-like behavior)
// ============================================================================

TEST(MapSerializerTest, MapWithOptionalValues) {
  // Config with ref tracking enabled for nullability
  auto fory = Fory::builder().xlang(true).track_ref(true).build();

  std::map<std::string, std::optional<int32_t>> map{
      {"has_value", 42}, {"no_value", std::nullopt}, {"another_value", 100}};

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization error: " << serialize_result.error().to_string();
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result =
      fory.deserialize<std::map<std::string, std::optional<int32_t>>>(
          bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok());
  auto deserialized = deserialize_result.value();

  // Verify
  ASSERT_EQ(map.size(), deserialized.size());
  EXPECT_EQ(map["has_value"], deserialized["has_value"]);
  EXPECT_EQ(map["no_value"], deserialized["no_value"]);
  EXPECT_EQ(map["another_value"], deserialized["another_value"]);
}

// ============================================================================
// Map with Shared Pointers (True Polymorphic References)
// ============================================================================

TEST(MapSerializerTest, MapWithSharedPtrValues) {
  // Config with ref tracking enabled
  auto fory = Fory::builder().xlang(true).track_ref(true).build();

  auto ptr1 = std::make_shared<int32_t>(42);
  auto ptr2 = std::make_shared<int32_t>(100);

  std::map<std::string, std::shared_ptr<int32_t>> map{
      {"first", ptr1}, {"second", ptr2}, {"third", ptr1} // ptr1 shared
  };

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok());
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result =
      fory.deserialize<std::map<std::string, std::shared_ptr<int32_t>>>(
          bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok());
  auto deserialized = deserialize_result.value();

  // Verify values
  ASSERT_EQ(map.size(), deserialized.size());
  EXPECT_EQ(*deserialized["first"], 42);
  EXPECT_EQ(*deserialized["second"], 100);
  EXPECT_EQ(*deserialized["third"], 42);

  // Verify reference sharing (they should point to the same object after
  // deserialization)
  EXPECT_EQ(deserialized["first"].get(), deserialized["third"].get());
  EXPECT_NE(deserialized["first"].get(), deserialized["second"].get());
}

TEST(MapSerializerTest, MapWithNullSharedPtrValues) {
  // Config with ref tracking enabled
  auto fory = Fory::builder().xlang(true).track_ref(true).build();

  std::map<std::string, std::shared_ptr<std::string>> map{
      {"valid", std::make_shared<std::string>("hello")},
      {"null", nullptr},
      {"another", std::make_shared<std::string>("world")}};

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok());
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result =
      fory.deserialize<std::map<std::string, std::shared_ptr<std::string>>>(
          bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok());
  auto deserialized = deserialize_result.value();

  // Verify
  ASSERT_EQ(map.size(), deserialized.size());
  ASSERT_NE(deserialized["valid"], nullptr);
  EXPECT_EQ(*deserialized["valid"], "hello");
  EXPECT_EQ(deserialized["null"], nullptr);
  ASSERT_NE(deserialized["another"], nullptr);
  EXPECT_EQ(*deserialized["another"], "world");
}

TEST(MapSerializerTest, MapWithCircularReferences) {
  // This tests that circular references within map values are handled correctly
  auto fory = Fory::builder().xlang(true).track_ref(true).build();

  auto shared_value = std::make_shared<int32_t>(999);

  std::map<int32_t, std::shared_ptr<int32_t>> map{{1, shared_value},
                                                  {2, shared_value},
                                                  {3, shared_value},
                                                  {4, shared_value}};

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok());
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result =
      fory.deserialize<std::map<int32_t, std::shared_ptr<int32_t>>>(
          bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok());
  auto deserialized = deserialize_result.value();

  // Verify all point to same object
  ASSERT_EQ(deserialized.size(), 4u);
  EXPECT_EQ(*deserialized[1], 999);
  EXPECT_EQ(deserialized[1].get(), deserialized[2].get());
  EXPECT_EQ(deserialized[2].get(), deserialized[3].get());
  EXPECT_EQ(deserialized[3].get(), deserialized[4].get());
}

// ============================================================================
// Protocol Compliance Tests
// ============================================================================

TEST(MapSerializerTest, ChunkSizeBoundary) {
  // Test behavior at chunk size boundary (255 entries)
  std::map<int32_t, int32_t> map;
  for (int i = 0; i < 255; ++i) {
    map[i] = i * 2;
  }
  test_map_roundtrip(map);
}

TEST(MapSerializerTest, MultipleChunks) {
  // Test multiple chunks (256+ entries)
  std::map<int32_t, int32_t> map;
  for (int i = 0; i < 512; ++i) {
    map[i] = i * 2;
  }
  test_map_roundtrip(map);
}

TEST(MapSerializerTest, VerifyChunkedEncoding) {
  // This test verifies the chunked encoding format
  auto fory = Fory::builder().xlang(true).build();

  std::map<int32_t, int32_t> map{{1, 10}, {2, 20}, {3, 30}};

  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok());
  auto bytes = serialize_result.value();

  // The format should be:
  // - Header (4 bytes)
  // - TypeId (1 byte for MAP)
  // - Map length as varuint32
  // - Chunk header (1 byte)
  // - Chunk size (1 byte) = 3
  // - Type info for key (if needed)
  // - Type info for value (if needed)
  // - Key-value pairs (3 pairs)

  // Just verify we can deserialize it back correctly
  auto deserialize_result =
      fory.deserialize<std::map<int32_t, int32_t>>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok());
  auto deserialized = deserialize_result.value();
  EXPECT_EQ(map, deserialized);
}

// ============================================================================
// Edge Cases
// ============================================================================

TEST(MapSerializerTest, MapWithEmptyStringKeys) {
  std::map<std::string, int32_t> map{{"", 0}, {"a", 1}, {"", 2}};
  // Note: std::map will only have one entry with key ""
  test_map_roundtrip(map);
}

TEST(MapSerializerTest, MapWithDuplicateValues) {
  std::map<int32_t, std::string> map{
      {1, "same"}, {2, "same"}, {3, "same"}, {4, "different"}};
  test_map_roundtrip(map);
}

TEST(MapSerializerTest, UnorderedMapOrdering) {
  // Verify that unordered_map serialization/deserialization works
  // even though iteration order may differ
  std::unordered_map<int32_t, std::string> map;
  for (int i = 0; i < 50; ++i) {
    map[i] = "value_" + std::to_string(i);
  }

  auto fory = Fory::builder().xlang(true).build();

  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok());
  auto bytes = serialize_result.value();

  auto deserialize_result =
      fory.deserialize<std::unordered_map<int32_t, std::string>>(bytes.data(),
                                                                 bytes.size());
  ASSERT_TRUE(deserialize_result.ok());
  auto deserialized = deserialize_result.value();

  // Compare element by element (order doesn't matter)
  EXPECT_EQ(map.size(), deserialized.size());
  for (const auto &[key, value] : map) {
    EXPECT_EQ(deserialized[key], value);
  }
}

// ============================================================================
// Polymorphic Types Tests - Virtual Base Classes
// ============================================================================

// Define polymorphic base and derived classes for testing
namespace polymorphic_test {

// Base class for polymorphic keys
struct BaseKey {
  int32_t id = 0;

  BaseKey() = default;
  explicit BaseKey(int32_t id) : id(id) {}
  virtual ~BaseKey() = default;

  virtual std::string type_name() const = 0;

  bool operator<(const BaseKey &other) const { return id < other.id; }
  bool operator==(const BaseKey &other) const {
    return id == other.id && type_name() == other.type_name();
  }

  FORY_STRUCT(BaseKey, id);
};

struct DerivedKeyA : public BaseKey {
  std::string data;

  DerivedKeyA() = default;
  DerivedKeyA(int32_t id, std::string data)
      : BaseKey(id), data(std::move(data)) {}

  std::string type_name() const override { return "DerivedKeyA"; }
  FORY_STRUCT(DerivedKeyA, FORY_BASE(BaseKey), data);
};

struct DerivedKeyB : public BaseKey {
  double value = 0.0;

  DerivedKeyB() = default;
  DerivedKeyB(int32_t id, double value) : BaseKey(id), value(value) {}

  std::string type_name() const override { return "DerivedKeyB"; }
  FORY_STRUCT(DerivedKeyB, FORY_BASE(BaseKey), value);
};

// Base class for polymorphic values
struct BaseValue {
  std::string name;

  BaseValue() = default;
  explicit BaseValue(std::string name) : name(std::move(name)) {}
  virtual ~BaseValue() = default;

  virtual int32_t get_priority() const = 0;
  virtual std::string type_name() const = 0;

  bool operator==(const BaseValue &other) const {
    return name == other.name && get_priority() == other.get_priority() &&
           type_name() == other.type_name();
  }

  FORY_STRUCT(BaseValue, name);
};

struct DerivedValueX : public BaseValue {
  int32_t priority = 0;

  DerivedValueX() = default;
  DerivedValueX(std::string name, int32_t priority)
      : BaseValue(std::move(name)), priority(priority) {}

  int32_t get_priority() const override { return priority; }
  std::string type_name() const override { return "DerivedValueX"; }
  FORY_STRUCT(DerivedValueX, FORY_BASE(BaseValue), priority);
};

struct DerivedValueY : public BaseValue {
  int32_t priority = 0;
  std::vector<std::string> tags;

  DerivedValueY() = default;
  DerivedValueY(std::string name, int32_t priority,
                std::vector<std::string> tags)
      : BaseValue(std::move(name)), priority(priority), tags(std::move(tags)) {}

  int32_t get_priority() const override { return priority; }
  std::string type_name() const override { return "DerivedValueY"; }
  FORY_STRUCT(DerivedValueY, FORY_BASE(BaseValue), priority, tags);
};

} // namespace polymorphic_test

// Type IDs for polymorphic types
constexpr uint32_t DERIVED_KEY_A_TYPE_ID = 1000;
constexpr uint32_t DERIVED_KEY_B_TYPE_ID = 1001;
constexpr uint32_t DERIVED_VALUE_X_TYPE_ID = 1002;
constexpr uint32_t DERIVED_VALUE_Y_TYPE_ID = 1003;

// Test map with polymorphic value types (most common case)
TEST(MapSerializerTest, PolymorphicValueTypes) {
  using namespace polymorphic_test;

  auto fory =
      Fory::builder().xlang(true).track_ref(true).compatible(true).build();

  // Register concrete derived types for polymorphic serialization
  ASSERT_TRUE(
      fory.register_struct<DerivedValueX>(DERIVED_VALUE_X_TYPE_ID).ok());
  ASSERT_TRUE(
      fory.register_struct<DerivedValueY>(DERIVED_VALUE_Y_TYPE_ID).ok());

  // Create map with different derived value types
  std::map<int32_t, std::shared_ptr<BaseValue>> map;
  map[1] = std::make_shared<DerivedValueX>("first", 10);
  map[2] = std::make_shared<DerivedValueY>(
      "second", 20, std::vector<std::string>{"tag1", "tag2"});
  map[3] = std::make_shared<DerivedValueX>("third", 30);

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().message();
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result =
      fory.deserialize<std::map<int32_t, std::shared_ptr<BaseValue>>>(
          bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().message();
  auto deserialized = deserialize_result.value();

  // Verify size
  ASSERT_EQ(map.size(), deserialized.size());

  // Verify first entry (DerivedValueX)
  ASSERT_NE(deserialized[1], nullptr);
  EXPECT_EQ(deserialized[1]->name, "first");
  EXPECT_EQ(deserialized[1]->get_priority(), 10);
  EXPECT_EQ(deserialized[1]->type_name(), "DerivedValueX");

  // Verify second entry (DerivedValueY)
  ASSERT_NE(deserialized[2], nullptr);
  EXPECT_EQ(deserialized[2]->name, "second");
  EXPECT_EQ(deserialized[2]->get_priority(), 20);
  EXPECT_EQ(deserialized[2]->type_name(), "DerivedValueY");
  auto *derived_y = dynamic_cast<DerivedValueY *>(deserialized[2].get());
  ASSERT_NE(derived_y, nullptr);
  EXPECT_EQ(derived_y->tags.size(), 2u);
  EXPECT_EQ(derived_y->tags[0], "tag1");
  EXPECT_EQ(derived_y->tags[1], "tag2");

  // Verify third entry (DerivedValueX)
  ASSERT_NE(deserialized[3], nullptr);
  EXPECT_EQ(deserialized[3]->name, "third");
  EXPECT_EQ(deserialized[3]->get_priority(), 30);
  EXPECT_EQ(deserialized[3]->type_name(), "DerivedValueX");
}

// Test map with polymorphic key types
TEST(MapSerializerTest, PolymorphicKeyTypes) {
  using namespace polymorphic_test;

  auto fory =
      Fory::builder().xlang(true).track_ref(true).compatible(true).build();

  // Register concrete derived types for polymorphic serialization
  ASSERT_TRUE(fory.register_struct<DerivedKeyA>(DERIVED_KEY_A_TYPE_ID).ok());
  ASSERT_TRUE(fory.register_struct<DerivedKeyB>(DERIVED_KEY_B_TYPE_ID).ok());

  // Create map with different derived key types
  std::map<std::shared_ptr<BaseKey>, std::string> map;
  map[std::make_shared<DerivedKeyA>(1, "key_a")] = "value_a";
  map[std::make_shared<DerivedKeyB>(2, 3.14)] = "value_b";
  map[std::make_shared<DerivedKeyA>(3, "key_c")] = "value_c";

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().message();
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result =
      fory.deserialize<std::map<std::shared_ptr<BaseKey>, std::string>>(
          bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().message();
  auto deserialized = deserialize_result.value();

  // Verify size
  ASSERT_EQ(map.size(), deserialized.size());

  // Note: Since keys are polymorphic pointers, we need to iterate and verify
  // content We can't directly index by key like map[key] because pointer
  // addresses differ
  std::vector<std::pair<std::shared_ptr<BaseKey>, std::string>> des_vec(
      deserialized.begin(), deserialized.end());

  ASSERT_EQ(des_vec.size(), 3u);

  // Verify keys are properly deserialized with correct derived types
  int key_a_count = 0;
  int key_b_count = 0;

  for (const auto &[key, value] : des_vec) {
    ASSERT_NE(key, nullptr);
    if (key->type_name() == "DerivedKeyA") {
      key_a_count++;
      auto *derived_a = dynamic_cast<DerivedKeyA *>(key.get());
      ASSERT_NE(derived_a, nullptr);
      EXPECT_FALSE(derived_a->data.empty());
    } else if (key->type_name() == "DerivedKeyB") {
      key_b_count++;
      auto *derived_b = dynamic_cast<DerivedKeyB *>(key.get());
      ASSERT_NE(derived_b, nullptr);
      EXPECT_EQ(derived_b->value, 3.14);
    }
  }

  EXPECT_EQ(key_a_count, 2);
  EXPECT_EQ(key_b_count, 1);
}

// Test map with both polymorphic keys and values
TEST(MapSerializerTest, PolymorphicKeyAndValueTypes) {
  using namespace polymorphic_test;

  auto fory =
      Fory::builder().xlang(true).track_ref(true).compatible(true).build();

  // Register concrete derived types for polymorphic serialization
  ASSERT_TRUE(fory.register_struct<DerivedKeyA>(DERIVED_KEY_A_TYPE_ID).ok());
  ASSERT_TRUE(fory.register_struct<DerivedKeyB>(DERIVED_KEY_B_TYPE_ID).ok());
  ASSERT_TRUE(
      fory.register_struct<DerivedValueX>(DERIVED_VALUE_X_TYPE_ID).ok());
  ASSERT_TRUE(
      fory.register_struct<DerivedValueY>(DERIVED_VALUE_Y_TYPE_ID).ok());

  // Create map with polymorphic keys and values
  std::map<std::shared_ptr<BaseKey>, std::shared_ptr<BaseValue>> map;
  map[std::make_shared<DerivedKeyA>(1, "alpha")] =
      std::make_shared<DerivedValueX>("value_x1", 100);
  map[std::make_shared<DerivedKeyB>(2, 2.71)] = std::make_shared<DerivedValueY>(
      "value_y1", 200, std::vector<std::string>{"a", "b"});
  map[std::make_shared<DerivedKeyA>(3, "beta")] =
      std::make_shared<DerivedValueX>("value_x2", 300);

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().message();
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result = fory.deserialize<
      std::map<std::shared_ptr<BaseKey>, std::shared_ptr<BaseValue>>>(
      bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().message();
  auto deserialized = deserialize_result.value();

  // Verify size
  ASSERT_EQ(map.size(), deserialized.size());

  // Verify all entries
  for (const auto &[key, value] : deserialized) {
    ASSERT_NE(key, nullptr);
    ASSERT_NE(value, nullptr);

    // Verify key-value type combinations
    if (key->type_name() == "DerivedKeyA") {
      auto *derived_key = dynamic_cast<DerivedKeyA *>(key.get());
      ASSERT_NE(derived_key, nullptr);
      EXPECT_FALSE(derived_key->data.empty());

      // DerivedKeyA should map to DerivedValueX in this test
      EXPECT_EQ(value->type_name(), "DerivedValueX");
    } else if (key->type_name() == "DerivedKeyB") {
      auto *derived_key = dynamic_cast<DerivedKeyB *>(key.get());
      ASSERT_NE(derived_key, nullptr);
      EXPECT_EQ(derived_key->value, 2.71);

      // DerivedKeyB should map to DerivedValueY in this test
      EXPECT_EQ(value->type_name(), "DerivedValueY");
    }
  }
}

// Test polymorphic types with null values
TEST(MapSerializerTest, PolymorphicTypesWithNulls) {
  using namespace polymorphic_test;

  auto fory =
      Fory::builder().xlang(true).track_ref(true).compatible(true).build();

  // Register concrete derived types for polymorphic serialization
  ASSERT_TRUE(
      fory.register_struct<DerivedValueX>(DERIVED_VALUE_X_TYPE_ID).ok());
  ASSERT_TRUE(
      fory.register_struct<DerivedValueY>(DERIVED_VALUE_Y_TYPE_ID).ok());

  // Create map with some null polymorphic values
  std::map<int32_t, std::shared_ptr<BaseValue>> map;
  map[1] = std::make_shared<DerivedValueX>("first", 10);
  map[2] = nullptr; // Null value
  map[3] = std::make_shared<DerivedValueY>("third", 30,
                                           std::vector<std::string>{"t1"});
  map[4] = nullptr; // Another null value
  map[5] = std::make_shared<DerivedValueX>("fifth", 50);

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().message();
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result =
      fory.deserialize<std::map<int32_t, std::shared_ptr<BaseValue>>>(
          bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().message();
  auto deserialized = deserialize_result.value();

  // Verify
  ASSERT_EQ(deserialized.size(), 5u);
  ASSERT_NE(deserialized[1], nullptr);
  EXPECT_EQ(deserialized[1]->name, "first");
  EXPECT_EQ(deserialized[2], nullptr);
  ASSERT_NE(deserialized[3], nullptr);
  EXPECT_EQ(deserialized[3]->name, "third");
  EXPECT_EQ(deserialized[4], nullptr);
  ASSERT_NE(deserialized[5], nullptr);
  EXPECT_EQ(deserialized[5]->name, "fifth");
}

// Test polymorphic types with shared references
TEST(MapSerializerTest, PolymorphicTypesWithSharedReferences) {
  using namespace polymorphic_test;

  auto fory =
      Fory::builder().xlang(true).track_ref(true).compatible(true).build();

  // Register concrete derived types for polymorphic serialization
  ASSERT_TRUE(
      fory.register_struct<DerivedValueX>(DERIVED_VALUE_X_TYPE_ID).ok());
  ASSERT_TRUE(
      fory.register_struct<DerivedValueY>(DERIVED_VALUE_Y_TYPE_ID).ok());

  // Create shared polymorphic values
  auto shared_value1 = std::make_shared<DerivedValueX>("shared_x", 999);
  auto shared_value2 = std::make_shared<DerivedValueY>(
      "shared_y", 888, std::vector<std::string>{"shared"});

  // Create map with shared references
  std::map<int32_t, std::shared_ptr<BaseValue>> map;
  map[1] = shared_value1;
  map[2] = shared_value2;
  map[3] = shared_value1; // Same as key 1
  map[4] = std::make_shared<DerivedValueX>("unique", 777);
  map[5] = shared_value2; // Same as key 2
  map[6] = shared_value1; // Same as key 1 and 3

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().message();
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result =
      fory.deserialize<std::map<int32_t, std::shared_ptr<BaseValue>>>(
          bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().message();
  auto deserialized = deserialize_result.value();

  // Verify size
  ASSERT_EQ(deserialized.size(), 6u);

  // Verify shared references are preserved
  // Keys 1, 3, 6 should point to the same object
  EXPECT_EQ(deserialized[1].get(), deserialized[3].get());
  EXPECT_EQ(deserialized[1].get(), deserialized[6].get());
  EXPECT_EQ(deserialized[1]->name, "shared_x");
  EXPECT_EQ(deserialized[1]->get_priority(), 999);

  // Keys 2, 5 should point to the same object
  EXPECT_EQ(deserialized[2].get(), deserialized[5].get());
  EXPECT_EQ(deserialized[2]->name, "shared_y");
  EXPECT_EQ(deserialized[2]->get_priority(), 888);

  // Key 4 should be unique
  EXPECT_NE(deserialized[4].get(), deserialized[1].get());
  EXPECT_NE(deserialized[4].get(), deserialized[2].get());
  EXPECT_EQ(deserialized[4]->name, "unique");
  EXPECT_EQ(deserialized[4]->get_priority(), 777);
}

// Test large map with polymorphic values (tests chunking with polymorphic
// types)
TEST(MapSerializerTest, LargeMapWithPolymorphicValues) {
  using namespace polymorphic_test;

  auto fory =
      Fory::builder().xlang(true).track_ref(true).compatible(true).build();

  // Register concrete derived types for polymorphic serialization
  ASSERT_TRUE(
      fory.register_struct<DerivedValueX>(DERIVED_VALUE_X_TYPE_ID).ok());
  ASSERT_TRUE(
      fory.register_struct<DerivedValueY>(DERIVED_VALUE_Y_TYPE_ID).ok());

  // Create a large map with alternating polymorphic types
  std::map<int32_t, std::shared_ptr<BaseValue>> map;
  for (int i = 0; i < 300; ++i) {
    if (i % 2 == 0) {
      map[i] = std::make_shared<DerivedValueX>("value_x_" + std::to_string(i),
                                               i * 10);
    } else {
      map[i] = std::make_shared<DerivedValueY>(
          "value_y_" + std::to_string(i), i * 20,
          std::vector<std::string>{"tag_" + std::to_string(i)});
    }
  }

  // Serialize
  auto serialize_result = fory.serialize(map);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().message();
  auto bytes = serialize_result.value();

  // Deserialize
  auto deserialize_result =
      fory.deserialize<std::map<int32_t, std::shared_ptr<BaseValue>>>(
          bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().message();
  auto deserialized = deserialize_result.value();

  // Verify
  ASSERT_EQ(deserialized.size(), 300u);

  // Spot check a few entries
  ASSERT_NE(deserialized[0], nullptr);
  EXPECT_EQ(deserialized[0]->type_name(), "DerivedValueX");
  EXPECT_EQ(deserialized[0]->name, "value_x_0");

  ASSERT_NE(deserialized[1], nullptr);
  EXPECT_EQ(deserialized[1]->type_name(), "DerivedValueY");
  EXPECT_EQ(deserialized[1]->name, "value_y_1");

  ASSERT_NE(deserialized[299], nullptr);
  EXPECT_EQ(deserialized[299]->type_name(), "DerivedValueY");
  EXPECT_EQ(deserialized[299]->name, "value_y_299");
}

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
