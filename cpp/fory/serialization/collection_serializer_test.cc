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
#include <cstdint>
#include <deque>
#include <forward_list>
#include <list>
#include <memory>
#include <optional>
#include <string>
#include <tuple>
#include <variant>
#include <vector>

namespace fory {
namespace serialization {

// ============================================================================
// Polymorphic Base and Derived Types for Testing
// ============================================================================

struct Animal {
  virtual ~Animal() = default;
  virtual std::string speak() const = 0;
  int32_t age = 0;
  FORY_STRUCT(Animal, age);
};

struct Dog : Animal {
  std::string speak() const override { return "Woof"; }
  std::string name;
  FORY_STRUCT(Dog, FORY_BASE(Animal), name);
};

struct Cat : Animal {
  std::string speak() const override { return "Meow"; }
  int32_t lives = 9;
  FORY_STRUCT(Cat, FORY_BASE(Animal), lives);
};

// Holder structs for testing collections as struct fields
struct VectorPolymorphicHolder {
  std::vector<std::shared_ptr<Animal>> animals;
  FORY_STRUCT(VectorPolymorphicHolder, animals);
};

struct VectorHomogeneousHolder {
  std::vector<std::shared_ptr<Dog>> dogs;
  FORY_STRUCT(VectorHomogeneousHolder, dogs);
};

struct RemoteCollectionV1 {
  int32_t value{};
  int32_t removed{};
  FORY_STRUCT(RemoteCollectionV1, value, removed);
};

struct RemoteCollectionV2 {
  int32_t value{};
  FORY_STRUCT(RemoteCollectionV2, value);
};

struct EmptyProgressStruct {
  FORY_STRUCT(EmptyProgressStruct);
};

struct NestedEmptyProgressStruct {
  EmptyProgressStruct value;
  FORY_STRUCT(NestedEmptyProgressStruct, value);
};

struct PositiveProgressStruct {
  int32_t value{};
  FORY_STRUCT(PositiveProgressStruct, value);
};

static_assert(!read_data_always_advances_v<EmptyProgressStruct>);
static_assert(!read_data_always_advances_v<NestedEmptyProgressStruct>);
static_assert(read_data_always_advances_v<PositiveProgressStruct>);

namespace {

Fory create_fory() {
  return Fory::builder().xlang(true).compatible(false).track_ref(true).build();
}

void register_types(Fory &fory) {
  fory.register_struct<VectorPolymorphicHolder>(100);
  fory.register_struct<VectorHomogeneousHolder>(101);
  fory.register_struct<Animal>("test", "Animal");
  fory.register_struct<Dog>("test", "Dog");
  fory.register_struct<Cat>("test", "Cat");
}

// ============================================================================
// Polymorphic Vector Tests
// ============================================================================

TEST(CollectionSerializerTest, RejectsMissingConcreteType) {
  Config config;
  ReadContext ctx(config, std::make_unique<TypeResolver>());
  Buffer buffer;
  buffer.write_uint8(COLL_IS_SAME_TYPE | COLL_DECL_ELEMENT_TYPE);
  ctx.attach(buffer);

  using Values = std::vector<std::shared_ptr<Animal>>;
  auto result =
      read_collection_data_slow<std::shared_ptr<Animal>, Values>(ctx, 1);

  EXPECT_TRUE(result.empty());
  ASSERT_TRUE(ctx.has_error());
  EXPECT_EQ(ctx.error().code(), ErrorCode::InvalidData);
  EXPECT_NE(ctx.error().message().find("concrete type information"),
            std::string::npos);
}

TEST(CollectionSerializerTest, SameTypeUsesRemoteReadBinding) {
  auto writer =
      Fory::builder().xlang(true).compatible(true).track_ref(false).build();
  auto reader =
      Fory::builder().xlang(true).compatible(true).track_ref(false).build();
  auto owner_reader =
      Fory::builder().xlang(true).compatible(true).track_ref(false).build();
  ASSERT_TRUE(
      writer.register_struct<RemoteCollectionV1>("remote", "Asymmetric").ok());
  ASSERT_TRUE(
      reader.register_struct<RemoteCollectionV2>("remote", "Asymmetric").ok());
  ASSERT_TRUE(
      owner_reader.register_struct<RemoteCollectionV2>("remote", "Asymmetric")
          .ok());
  using WriterRoot = std::tuple<std::vector<RemoteCollectionV1>, int32_t>;
  using ReaderRoot = std::tuple<std::vector<RemoteCollectionV2>, int32_t>;
  WriterRoot original{std::vector<RemoteCollectionV1>{{7, 70}, {9, 90}}, 11};
  auto bytes = writer.serialize(original);
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();

  auto values = reader.deserialize<ReaderRoot>(*bytes);
  ASSERT_TRUE(values.ok()) << values.error().to_string();
  ASSERT_EQ(std::get<0>(*values).size(), 2U);
  EXPECT_EQ(std::get<0>(*values)[0].value, 7);
  EXPECT_EQ(std::get<0>(*values)[1].value, 9);
  EXPECT_EQ(std::get<1>(*values), 11);

  using OwnerRoot =
      std::tuple<std::vector<std::shared_ptr<RemoteCollectionV2>>, int32_t>;
  auto owners = owner_reader.deserialize<OwnerRoot>(*bytes);
  ASSERT_TRUE(owners.ok()) << owners.error().to_string();
  const auto &owner_values = std::get<0>(*owners);
  ASSERT_EQ(owner_values.size(), 2U);
  ASSERT_TRUE(owner_values[0]);
  ASSERT_TRUE(owner_values[1]);
  EXPECT_EQ(owner_values[0]->value, 7);
  EXPECT_EQ(owner_values[1]->value, 9);
  EXPECT_EQ(std::get<1>(*owners), 11);
}

TEST(CollectionSerializerTest, NestedValuePreservesReferenceIds) {
  auto writer = create_fory();
  auto reader = create_fory();
  using Inner = std::vector<std::shared_ptr<int32_t>>;
  using WriterValues = std::vector<std::shared_ptr<Inner>>;
  using ReaderValues = std::vector<Inner>;
  auto shared = std::make_shared<int32_t>(17);
  WriterValues original{std::make_shared<Inner>(Inner{shared, shared})};
  auto bytes = writer.serialize(original);
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();

  auto decoded = reader.deserialize<ReaderValues>(*bytes);
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  ASSERT_EQ(decoded->size(), 1U);
  ASSERT_EQ((*decoded)[0].size(), 2U);
  ASSERT_TRUE((*decoded)[0][0]);
  EXPECT_EQ(*(*decoded)[0][0], 17);
  EXPECT_EQ((*decoded)[0][0], (*decoded)[0][1]);
}

TEST(CollectionSerializerTest, VectorPolymorphicHeterogeneousElements) {
  auto fory = create_fory();
  register_types(fory);

  // Create vector with different derived types
  VectorPolymorphicHolder original;
  auto dog = std::make_shared<Dog>();
  dog->age = 3;
  dog->name = "Buddy";
  auto cat = std::make_shared<Cat>();
  cat->age = 5;
  cat->lives = 9;

  original.animals.push_back(dog);
  original.animals.push_back(cat);

  // Serialize
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  // Deserialize
  auto deserialize_result = fory.deserialize<VectorPolymorphicHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.animals.size(), 2u);

  // Verify first element is Dog
  auto *d = dynamic_cast<Dog *>(deserialized.animals[0].get());
  ASSERT_NE(d, nullptr) << "First element should be Dog";
  EXPECT_EQ(d->age, 3);
  EXPECT_EQ(d->name, "Buddy");
  EXPECT_EQ(d->speak(), "Woof");

  // Verify second element is Cat
  auto *c = dynamic_cast<Cat *>(deserialized.animals[1].get());
  ASSERT_NE(c, nullptr) << "Second element should be Cat";
  EXPECT_EQ(c->age, 5);
  EXPECT_EQ(c->lives, 9);
  EXPECT_EQ(c->speak(), "Meow");
}

TEST(CollectionSerializerTest, VectorPolymorphicHomogeneousElements) {
  auto fory = create_fory();
  register_types(fory);

  // Create vector with same derived type
  VectorPolymorphicHolder original;
  auto dog1 = std::make_shared<Dog>();
  dog1->age = 2;
  dog1->name = "Max";
  auto dog2 = std::make_shared<Dog>();
  dog2->age = 4;
  dog2->name = "Rex";

  original.animals.push_back(dog1);
  original.animals.push_back(dog2);

  // Serialize
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  // Deserialize
  auto deserialize_result = fory.deserialize<VectorPolymorphicHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.animals.size(), 2u);

  // Both should be Dog
  auto *d1 = dynamic_cast<Dog *>(deserialized.animals[0].get());
  auto *d2 = dynamic_cast<Dog *>(deserialized.animals[1].get());
  ASSERT_NE(d1, nullptr);
  ASSERT_NE(d2, nullptr);
  EXPECT_EQ(d1->name, "Max");
  EXPECT_EQ(d2->name, "Rex");
}

TEST(CollectionSerializerTest, VectorPolymorphicWithNulls) {
  auto fory = create_fory();
  register_types(fory);

  VectorPolymorphicHolder original;
  auto dog = std::make_shared<Dog>();
  dog->age = 1;
  dog->name = "Spot";

  original.animals.push_back(dog);
  original.animals.push_back(nullptr); // null element
  auto cat = std::make_shared<Cat>();
  cat->age = 2;
  cat->lives = 7;
  original.animals.push_back(cat);

  // Serialize
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  // Deserialize
  auto deserialize_result = fory.deserialize<VectorPolymorphicHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.animals.size(), 3u);
  EXPECT_NE(deserialized.animals[0], nullptr);
  EXPECT_EQ(deserialized.animals[1], nullptr);
  EXPECT_NE(deserialized.animals[2], nullptr);
}

TEST(CollectionSerializerTest, VectorNonPolymorphicSharedPtr) {
  auto fory = create_fory();
  register_types(fory);

  VectorHomogeneousHolder original;
  auto dog1 = std::make_shared<Dog>();
  dog1->age = 5;
  dog1->name = "Fido";
  auto dog2 = std::make_shared<Dog>();
  dog2->age = 3;
  dog2->name = "Bruno";

  original.dogs.push_back(dog1);
  original.dogs.push_back(dog2);

  // Serialize
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  // Deserialize
  auto deserialize_result = fory.deserialize<VectorHomogeneousHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.dogs.size(), 2u);
  EXPECT_EQ(deserialized.dogs[0]->name, "Fido");
  EXPECT_EQ(deserialized.dogs[1]->name, "Bruno");
}

TEST(CollectionSerializerTest, VectorSharedPtrReferenceTracking) {
  auto fory = create_fory();
  register_types(fory);

  // Test that shared_ptr reference tracking works
  VectorHomogeneousHolder original;
  auto shared_dog = std::make_shared<Dog>();
  shared_dog->age = 7;
  shared_dog->name = "Shared";

  original.dogs.push_back(shared_dog);
  original.dogs.push_back(shared_dog); // Same pointer twice

  // Serialize
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  // Deserialize
  auto deserialize_result = fory.deserialize<VectorHomogeneousHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.dogs.size(), 2u);
  // Both should point to the same object due to reference tracking
  EXPECT_EQ(deserialized.dogs[0], deserialized.dogs[1])
      << "Reference tracking should preserve shared_ptr aliasing";
}

TEST(CollectionSerializerTest, VectorEmpty) {
  auto fory = create_fory();
  register_types(fory);

  VectorPolymorphicHolder original;
  // Empty vector

  // Serialize
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  // Deserialize
  auto deserialize_result = fory.deserialize<VectorPolymorphicHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_TRUE(deserialized.animals.empty());
}

// ============================================================================
// Non-Polymorphic Collection Tests (Regression)
// ============================================================================

struct VectorStringHolder {
  std::vector<std::string> strings;
  FORY_STRUCT(VectorStringHolder, strings);
};

struct VectorIntHolder {
  std::vector<int32_t> numbers;
  FORY_STRUCT(VectorIntHolder, numbers);
};

TEST(CollectionSerializerTest, VectorStringRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<VectorStringHolder>(200);

  VectorStringHolder original;
  original.strings = {"hello", "world", "fory"};

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<VectorStringHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.strings.size(), 3u);
  EXPECT_EQ(deserialized.strings[0], "hello");
  EXPECT_EQ(deserialized.strings[1], "world");
  EXPECT_EQ(deserialized.strings[2], "fory");
}

TEST(CollectionSerializerTest, VectorIntRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<VectorIntHolder>(201);

  VectorIntHolder original;
  original.numbers = {1, 2, 3, 42, 100};

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<VectorIntHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.numbers.size(), 5u);
  EXPECT_EQ(deserialized.numbers[0], 1);
  EXPECT_EQ(deserialized.numbers[4], 100);
}

// ============================================================================
// Optional/Nullable Element Tests
// ============================================================================

struct VectorOptionalHolder {
  std::vector<std::optional<std::string>> values;
  FORY_STRUCT(VectorOptionalHolder, values);
};

TEST(CollectionSerializerTest, VectorOptionalWithNulls) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<VectorOptionalHolder>(202);

  VectorOptionalHolder original;
  original.values.push_back("first");
  original.values.push_back(std::nullopt);
  original.values.push_back("third");

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<VectorOptionalHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.values.size(), 3u);
  EXPECT_TRUE(deserialized.values[0].has_value());
  EXPECT_EQ(*deserialized.values[0], "first");
  EXPECT_FALSE(deserialized.values[1].has_value());
  EXPECT_TRUE(deserialized.values[2].has_value());
  EXPECT_EQ(*deserialized.values[2], "third");
}

// ============================================================================
// std::list Tests
// ============================================================================

struct ListStringHolder {
  std::list<std::string> strings;
  FORY_STRUCT(ListStringHolder, strings);
};

struct ListIntHolder {
  std::list<int32_t> numbers;
  FORY_STRUCT(ListIntHolder, numbers);
};

TEST(CollectionSerializerTest, ListStringRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<ListStringHolder>(300);

  ListStringHolder original;
  original.strings = {"hello", "world", "fory", "list"};

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<ListStringHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.strings.size(), 4u);
  auto it = deserialized.strings.begin();
  EXPECT_EQ(*it++, "hello");
  EXPECT_EQ(*it++, "world");
  EXPECT_EQ(*it++, "fory");
  EXPECT_EQ(*it++, "list");
}

TEST(CollectionSerializerTest, ListIntRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<ListIntHolder>(301);

  ListIntHolder original;
  original.numbers = {1, 2, 3, 42, 100};

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<ListIntHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.numbers.size(), 5u);
  auto it = deserialized.numbers.begin();
  EXPECT_EQ(*it++, 1);
  EXPECT_EQ(*it++, 2);
  EXPECT_EQ(*it++, 3);
  EXPECT_EQ(*it++, 42);
  EXPECT_EQ(*it++, 100);
}

TEST(CollectionSerializerTest, ListEmptyRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<ListStringHolder>(302);

  ListStringHolder original;
  // Empty list

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<ListStringHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_TRUE(deserialized.strings.empty());
}

// ============================================================================
// std::deque Tests
// ============================================================================

struct DequeStringHolder {
  std::deque<std::string> strings;
  FORY_STRUCT(DequeStringHolder, strings);
};

struct DequeIntHolder {
  std::deque<int32_t> numbers;
  FORY_STRUCT(DequeIntHolder, numbers);
};

TEST(CollectionSerializerTest, DequeStringRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<DequeStringHolder>(400);

  DequeStringHolder original;
  original.strings = {"hello", "world", "fory", "deque"};

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<DequeStringHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.strings.size(), 4u);
  EXPECT_EQ(deserialized.strings[0], "hello");
  EXPECT_EQ(deserialized.strings[1], "world");
  EXPECT_EQ(deserialized.strings[2], "fory");
  EXPECT_EQ(deserialized.strings[3], "deque");
}

TEST(CollectionSerializerTest, DequeIntRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<DequeIntHolder>(401);

  DequeIntHolder original;
  original.numbers = {10, 20, 30, 40, 50};

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<DequeIntHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.numbers.size(), 5u);
  EXPECT_EQ(deserialized.numbers[0], 10);
  EXPECT_EQ(deserialized.numbers[1], 20);
  EXPECT_EQ(deserialized.numbers[2], 30);
  EXPECT_EQ(deserialized.numbers[3], 40);
  EXPECT_EQ(deserialized.numbers[4], 50);
}

TEST(CollectionSerializerTest, DequeEmptyRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<DequeStringHolder>(402);

  DequeStringHolder original;
  // Empty deque

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<DequeStringHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_TRUE(deserialized.strings.empty());
}

// ============================================================================
// std::forward_list Tests
// ============================================================================

struct ForwardListStringHolder {
  std::forward_list<std::string> strings;
  FORY_STRUCT(ForwardListStringHolder, strings);
};

struct ForwardListIntHolder {
  std::forward_list<int32_t> numbers;
  FORY_STRUCT(ForwardListIntHolder, numbers);
};

TEST(CollectionSerializerTest, ForwardListStringRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<ForwardListStringHolder>(500);

  ForwardListStringHolder original;
  original.strings = {"hello", "world", "fory", "forward_list"};

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<ForwardListStringHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  // Convert to vector for easier comparison
  std::vector<std::string> result(deserialized.strings.begin(),
                                  deserialized.strings.end());
  ASSERT_EQ(result.size(), 4u);
  EXPECT_EQ(result[0], "hello");
  EXPECT_EQ(result[1], "world");
  EXPECT_EQ(result[2], "fory");
  EXPECT_EQ(result[3], "forward_list");
}

TEST(CollectionSerializerTest, ForwardListIntRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<ForwardListIntHolder>(501);

  ForwardListIntHolder original;
  original.numbers = {100, 200, 300, 400, 500};

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<ForwardListIntHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  // Convert to vector for easier comparison
  std::vector<int32_t> result(deserialized.numbers.begin(),
                              deserialized.numbers.end());
  ASSERT_EQ(result.size(), 5u);
  EXPECT_EQ(result[0], 100);
  EXPECT_EQ(result[1], 200);
  EXPECT_EQ(result[2], 300);
  EXPECT_EQ(result[3], 400);
  EXPECT_EQ(result[4], 500);
}

TEST(CollectionSerializerTest, ForwardListEmptyRoundTrip) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  fory.register_struct<ForwardListStringHolder>(502);

  ForwardListStringHolder original;
  // Empty forward_list

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<ForwardListStringHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_TRUE(deserialized.strings.empty());
}

TEST(CollectionSerializerTest, UnbackedItemBudget) {
  Config config;
  EXPECT_EQ(config.max_unbacked_container_items, 8192);
  EXPECT_DEATH((void)Fory::builder().max_unbacked_container_items(-1),
               "max_unbacked_container_items");

  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(false)
                  .track_ref(false)
                  .max_unbacked_container_items(4)
                  .build();

  auto excessive = fory.serialize(std::vector<std::monostate>(5));
  ASSERT_TRUE(excessive.ok()) << excessive.error().to_string();
  auto rejected = fory.deserialize<std::vector<std::monostate>>(*excessive);
  EXPECT_FALSE(rejected.ok());

  auto allowed = fory.serialize(std::vector<std::monostate>(4));
  ASSERT_TRUE(allowed.ok()) << allowed.error().to_string();
  auto decoded = fory.deserialize<std::vector<std::monostate>>(*allowed);
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(decoded->size(), 4u);

  auto positive_fory = Fory::builder()
                           .xlang(true)
                           .compatible(false)
                           .track_ref(false)
                           .max_unbacked_container_items(0)
                           .build();
  ASSERT_TRUE(positive_fory.register_struct<PositiveProgressStruct>(902).ok());
  std::vector<PositiveProgressStruct> positive{{1}, {2}, {3}};
  auto positive_bytes = positive_fory.serialize(positive);
  ASSERT_TRUE(positive_bytes.ok()) << positive_bytes.error().to_string();
  auto positive_decoded =
      positive_fory.deserialize<std::vector<PositiveProgressStruct>>(
          *positive_bytes);
  ASSERT_TRUE(positive_decoded.ok()) << positive_decoded.error().to_string();
  EXPECT_EQ(positive_decoded->size(), positive.size());
}

} // namespace
} // namespace serialization
} // namespace fory
