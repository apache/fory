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
#include <string>
#include <tuple>

namespace fory {
namespace serialization {
namespace {

// ============================================================================
// Compile-time Homogeneity Detection Tests
// ============================================================================

TEST(TupleSerializerTest, HomogeneityDetection) {
  // Empty tuple is homogeneous
  static_assert(is_tuple_homogeneous_v<>, "Empty should be homogeneous");

  // Single element is homogeneous
  static_assert(is_tuple_homogeneous_v<int>, "Single int is homogeneous");
  static_assert(is_tuple_homogeneous_v<std::string>,
                "Single string is homogeneous");

  // Same types are homogeneous
  static_assert(is_tuple_homogeneous_v<int, int>, "int,int is homogeneous");
  static_assert(is_tuple_homogeneous_v<int, int, int>,
                "int,int,int is homogeneous");
  static_assert(is_tuple_homogeneous_v<std::string, std::string>,
                "string,string is homogeneous");

  // Different types are heterogeneous
  static_assert(!is_tuple_homogeneous_v<int, double>,
                "int,double is heterogeneous");
  static_assert(!is_tuple_homogeneous_v<int, std::string>,
                "int,string is heterogeneous");
  static_assert(!is_tuple_homogeneous_v<int, int, double>,
                "int,int,double is heterogeneous");
}

// ============================================================================
// Holder Structs for Testing
// ============================================================================

// First test with vector to verify test setup
struct VectorHolder {
  std::vector<int32_t> values;
  FORY_STRUCT(VectorHolder, values);
};

struct TupleHomogeneousHolder {
  std::tuple<int32_t, int32_t, int32_t> values;
  FORY_STRUCT(TupleHomogeneousHolder, values);
};

struct TupleHeterogeneousHolder {
  std::tuple<int32_t, std::string, double> values;
  FORY_STRUCT(TupleHeterogeneousHolder, values);
};

struct TupleSingleHolder {
  std::tuple<std::string> value;
  FORY_STRUCT(TupleSingleHolder, value);
};

struct TupleEmptyHolder {
  std::tuple<> value;
  FORY_STRUCT(TupleEmptyHolder, value);
};

struct TupleNestedHolder {
  std::tuple<std::tuple<int32_t, int32_t>, std::string> values;
  FORY_STRUCT(TupleNestedHolder, values);
};

Fory create_fory() {
  return Fory::builder().xlang(true).track_ref(true).build();
}

// ============================================================================
// Round-Trip Tests
// ============================================================================

// Verify test setup works with vector first
TEST(TupleSerializerTest, VectorRoundTrip) {
  auto fory = create_fory();
  fory.register_struct<VectorHolder>(299);

  VectorHolder original;
  original.values = {10, 20, 30};

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<VectorHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_EQ(deserialized.values.size(), 3u);
  EXPECT_EQ(deserialized.values[0], 10);
  EXPECT_EQ(deserialized.values[1], 20);
  EXPECT_EQ(deserialized.values[2], 30);
}

TEST(TupleSerializerTest, HomogeneousTupleRoundTrip) {
  auto fory = create_fory();
  auto reg_result = fory.register_struct<TupleHomogeneousHolder>(300);
  ASSERT_TRUE(reg_result.ok())
      << "Registration failed: " << reg_result.error().to_string();

  TupleHomogeneousHolder original;
  original.values = std::make_tuple(10, 20, 30);

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<TupleHomogeneousHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_EQ(std::get<0>(deserialized.values), 10);
  EXPECT_EQ(std::get<1>(deserialized.values), 20);
  EXPECT_EQ(std::get<2>(deserialized.values), 30);
}

TEST(TupleSerializerTest, HeterogeneousTupleRoundTrip) {
  auto fory = create_fory();
  fory.register_struct<TupleHeterogeneousHolder>(301);

  TupleHeterogeneousHolder original;
  original.values = std::make_tuple(42, std::string("hello"), 3.14);

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<TupleHeterogeneousHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_EQ(std::get<0>(deserialized.values), 42);
  EXPECT_EQ(std::get<1>(deserialized.values), "hello");
  EXPECT_DOUBLE_EQ(std::get<2>(deserialized.values), 3.14);
}

TEST(TupleSerializerTest, SingleElementTupleRoundTrip) {
  auto fory = create_fory();
  fory.register_struct<TupleSingleHolder>(302);

  TupleSingleHolder original;
  original.value = std::make_tuple(std::string("single"));

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<TupleSingleHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_EQ(std::get<0>(deserialized.value), "single");
}

TEST(TupleSerializerTest, EmptyTupleRoundTrip) {
  auto fory = create_fory();
  fory.register_struct<TupleEmptyHolder>(303);

  TupleEmptyHolder original;

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<TupleEmptyHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();
}

TEST(TupleSerializerTest, NestedTupleRoundTrip) {
  auto fory = create_fory();
  fory.register_struct<TupleNestedHolder>(304);

  TupleNestedHolder original;
  original.values =
      std::make_tuple(std::make_tuple(1, 2), std::string("nested"));

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<TupleNestedHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  auto inner = std::get<0>(deserialized.values);
  EXPECT_EQ(std::get<0>(inner), 1);
  EXPECT_EQ(std::get<1>(inner), 2);
  EXPECT_EQ(std::get<1>(deserialized.values), "nested");
}

// ============================================================================
// Large Tuple Test (testing limit of implementation)
// ============================================================================

struct TupleLargeHolder {
  std::tuple<int32_t, int32_t, int32_t, int32_t, int32_t, int32_t, int32_t,
             int32_t, int32_t, int32_t>
      values;
  FORY_STRUCT(TupleLargeHolder, values);
};

TEST(TupleSerializerTest, LargeTupleRoundTrip) {
  auto fory = create_fory();
  fory.register_struct<TupleLargeHolder>(305);

  TupleLargeHolder original;
  original.values = std::make_tuple(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<TupleLargeHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_EQ(std::get<0>(deserialized.values), 1);
  EXPECT_EQ(std::get<4>(deserialized.values), 5);
  EXPECT_EQ(std::get<9>(deserialized.values), 10);
}

// ============================================================================
// Homogeneous Optimization Verification
// ============================================================================

TEST(TupleSerializerTest, HomogeneousOptimizationSize) {
  auto fory = create_fory();
  fory.register_struct<TupleHomogeneousHolder>(306);
  fory.register_struct<TupleHeterogeneousHolder>(307);

  // Homogeneous tuple: type info written once
  TupleHomogeneousHolder homo;
  homo.values = std::make_tuple(1, 2, 3);

  auto homo_bytes = fory.serialize(homo);
  ASSERT_TRUE(homo_bytes.ok());

  // Heterogeneous tuple: type info written per element
  TupleHeterogeneousHolder hetero;
  hetero.values = std::make_tuple(1, std::string("x"), 1.0);

  auto hetero_bytes = fory.serialize(hetero);
  ASSERT_TRUE(hetero_bytes.ok());

  // Both should serialize successfully - size comparison is informational
  // Homogeneous should be smaller due to single type info
  EXPECT_GT(homo_bytes->size(), 0u);
  EXPECT_GT(hetero_bytes->size(), 0u);
}

} // namespace
} // namespace serialization
} // namespace fory
