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
#include <memory>
#include <optional>
#include <vector>

namespace fory {
namespace serialization {

struct OptionalIntHolder {
  std::optional<int32_t> value;
};
FORY_STRUCT(OptionalIntHolder, value);

struct OptionalSharedHolder {
  std::optional<std::shared_ptr<int32_t>> value;
};
FORY_STRUCT(OptionalSharedHolder, value);

struct SharedPair {
  std::shared_ptr<int32_t> first;
  std::shared_ptr<int32_t> second;
};
FORY_STRUCT(SharedPair, first, second);

struct UniqueHolder {
  std::unique_ptr<int32_t> value;
};
FORY_STRUCT(UniqueHolder, value);

namespace {

Fory create_serializer(bool track_ref) {
  return Fory::builder().track_ref(track_ref).build();
}

TEST(SmartPtrSerializerTest, OptionalIntRoundTrip) {
  OptionalIntHolder original;
  original.value = 42;

  auto fory = create_serializer(true);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<OptionalIntHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  const auto &deserialized = deserialize_result.value();
  ASSERT_TRUE(deserialized.value.has_value());
  EXPECT_EQ(*deserialized.value, 42);
}

TEST(SmartPtrSerializerTest, OptionalIntNullRoundTrip) {
  OptionalIntHolder original;
  original.value.reset();

  auto fory = create_serializer(true);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<OptionalIntHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  const auto &deserialized = deserialize_result.value();
  EXPECT_FALSE(deserialized.value.has_value());
}

TEST(SmartPtrSerializerTest, OptionalSharedPtrRoundTrip) {
  OptionalSharedHolder original;
  original.value = std::make_shared<int32_t>(42);

  auto fory = create_serializer(true);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<OptionalSharedHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  const auto &deserialized = deserialize_result.value();
  ASSERT_TRUE(deserialized.value.has_value());
  ASSERT_TRUE(deserialized.value.value());
  EXPECT_EQ(*deserialized.value.value(), 42);
}

TEST(SmartPtrSerializerTest, SharedPtrReferenceTracking) {
  auto shared = std::make_shared<int32_t>(1337);
  SharedPair original{shared, shared};

  auto fory = create_serializer(true);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result =
      fory.deserialize<SharedPair>(bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.first);
  ASSERT_TRUE(deserialized.second);
  EXPECT_EQ(*deserialized.first, 1337);
  EXPECT_EQ(*deserialized.second, 1337);
  EXPECT_EQ(deserialized.first, deserialized.second)
      << "Reference tracking should preserve shared_ptr aliasing";
}

TEST(SmartPtrSerializerTest, UniquePtrRoundTrip) {
  UniqueHolder original;
  original.value = std::make_unique<int32_t>(2025);

  auto fory = create_serializer(true);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<UniqueHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.value);
  EXPECT_EQ(*deserialized.value, 2025);
}

TEST(SmartPtrSerializerTest, UniquePtrNullRoundTrip) {
  UniqueHolder original;
  original.value.reset();

  auto fory = create_serializer(true);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<UniqueHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_EQ(deserialized.value, nullptr);
}

// ============================================================================
// Polymorphic type tests
// ============================================================================

struct Base {
  virtual ~Base() = default;
  virtual std::string get_type() const = 0;
  int32_t base_value = 0;
};
FORY_STRUCT(Base, base_value);

struct Derived1 : Base {
  std::string get_type() const override { return "Derived1"; }
  std::string derived1_data;
};
FORY_STRUCT(Derived1, base_value, derived1_data);

struct Derived2 : Base {
  std::string get_type() const override { return "Derived2"; }
  int32_t derived2_data = 0;
};
FORY_STRUCT(Derived2, base_value, derived2_data);

struct PolymorphicSharedHolder {
  std::shared_ptr<Base> ptr;
};
FORY_STRUCT(PolymorphicSharedHolder, ptr);

struct PolymorphicUniqueHolder {
  std::unique_ptr<Base> ptr;
};
FORY_STRUCT(PolymorphicUniqueHolder, ptr);

TEST(SmartPtrSerializerTest, PolymorphicSharedPtrDerived1) {
  auto fory = create_serializer(true);
  auto register_result =
      fory.type_resolver().template register_by_name<Derived1>("test",
                                                               "Derived1");
  ASSERT_TRUE(register_result.ok())
      << "Failed to register Derived1: " << register_result.error().to_string();

  PolymorphicSharedHolder original;
  original.ptr = std::make_shared<Derived1>();
  original.ptr->base_value = 42;
  static_cast<Derived1 *>(original.ptr.get())->derived1_data = "hello";

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<PolymorphicSharedHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->get_type(), "Derived1");
  EXPECT_EQ(deserialized.ptr->base_value, 42);
  EXPECT_EQ(static_cast<Derived1 *>(deserialized.ptr.get())->derived1_data,
            "hello");
}

TEST(SmartPtrSerializerTest, PolymorphicSharedPtrDerived2) {
  auto fory = create_serializer(true);
  auto register_result =
      fory.type_resolver().template register_by_name<Derived2>("test",
                                                               "Derived2");
  ASSERT_TRUE(register_result.ok())
      << "Failed to register Derived2: " << register_result.error().to_string();

  PolymorphicSharedHolder original;
  original.ptr = std::make_shared<Derived2>();
  original.ptr->base_value = 99;
  static_cast<Derived2 *>(original.ptr.get())->derived2_data = 1234;

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<PolymorphicSharedHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->get_type(), "Derived2");
  EXPECT_EQ(deserialized.ptr->base_value, 99);
  EXPECT_EQ(static_cast<Derived2 *>(deserialized.ptr.get())->derived2_data,
            1234);
}

TEST(SmartPtrSerializerTest, PolymorphicUniquePtrDerived1) {
  auto fory = create_serializer(true);
  auto register_result =
      fory.type_resolver().template register_by_name<Derived1>("test",
                                                               "Derived1");
  ASSERT_TRUE(register_result.ok())
      << "Failed to register Derived1: " << register_result.error().to_string();

  PolymorphicUniqueHolder original;
  original.ptr = std::make_unique<Derived1>();
  original.ptr->base_value = 42;
  static_cast<Derived1 *>(original.ptr.get())->derived1_data = "world";

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<PolymorphicUniqueHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->get_type(), "Derived1");
  EXPECT_EQ(deserialized.ptr->base_value, 42);
  EXPECT_EQ(static_cast<Derived1 *>(deserialized.ptr.get())->derived1_data,
            "world");
}

TEST(SmartPtrSerializerTest, PolymorphicUniquePtrDerived2) {
  auto fory = create_serializer(true);
  auto register_result =
      fory.type_resolver().template register_by_name<Derived2>("test",
                                                               "Derived2");
  ASSERT_TRUE(register_result.ok())
      << "Failed to register Derived2: " << register_result.error().to_string();

  PolymorphicUniqueHolder original;
  original.ptr = std::make_unique<Derived2>();
  original.ptr->base_value = 77;
  static_cast<Derived2 *>(original.ptr.get())->derived2_data = 5678;

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<PolymorphicUniqueHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->get_type(), "Derived2");
  EXPECT_EQ(deserialized.ptr->base_value, 77);
  EXPECT_EQ(static_cast<Derived2 *>(deserialized.ptr.get())->derived2_data,
            5678);
}

} // namespace
} // namespace serialization
} // namespace fory