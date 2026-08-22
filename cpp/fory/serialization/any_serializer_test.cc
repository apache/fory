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

#include "fory/serialization/any_serializer.h"
#include "fory/serialization/fory.h"
#include "gtest/gtest.h"

#include <any>
#include <stdexcept>
#include <string>

namespace fory {
namespace serialization {
namespace test {

struct AnyInnerStruct {
  int32_t id;
  std::string name;

  bool operator==(const AnyInnerStruct &other) const {
    return id == other.id && name == other.name;
  }

  FORY_STRUCT(AnyInnerStruct, id, name);
};

inline bool any_equals(const std::any &left, const std::any &right) {
  if (left.type() != right.type()) {
    return false;
  }
  if (left.type() == typeid(std::string)) {
    return std::any_cast<const std::string &>(left) ==
           std::any_cast<const std::string &>(right);
  }
  if (left.type() == typeid(AnyInnerStruct)) {
    return std::any_cast<const AnyInnerStruct &>(left) ==
           std::any_cast<const AnyInnerStruct &>(right);
  }
  return false;
}

struct AnyHolderStruct {
  std::any first;
  std::any second;

  bool operator==(const AnyHolderStruct &other) const {
    return any_equals(first, other.first) && any_equals(second, other.second);
  }

  FORY_STRUCT(AnyHolderStruct, first, second);
};

struct RecursiveAny {
  int32_t value;
  std::any next;

  FORY_STRUCT(RecursiveAny, value, next);
};

std::any throw_any(ReadContext &) {
  throw std::runtime_error("nested read failed");
}

std::any read_int_any(ReadContext &) { return int32_t{7}; }

TEST(AnySerializerTest, RoundTripStructFields) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();

  ASSERT_TRUE(fory.register_struct<AnyInnerStruct>(1).ok());
  ASSERT_TRUE(fory.register_struct<AnyHolderStruct>(2).ok());

  ASSERT_TRUE(register_any_type<std::string>(fory.type_resolver()).ok());
  ASSERT_TRUE(register_any_type<AnyInnerStruct>(fory.type_resolver()).ok());

  AnyHolderStruct original;
  original.first = std::string("hello any");
  original.second = AnyInnerStruct{42, "nested"};

  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(serialize_result).value();
  auto deserialize_result =
      fory.deserialize<AnyHolderStruct>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();

  AnyHolderStruct deserialized = std::move(deserialize_result).value();
  EXPECT_EQ(original, deserialized);
}

TEST(AnySerializerTest, RecursiveDepth) {
  auto fory =
      Fory::builder().xlang(true).track_ref(false).max_dyn_depth(2).build();
  ASSERT_TRUE(fory.register_struct<RecursiveAny>(3).ok());
  ASSERT_TRUE(register_any_type<RecursiveAny>(fory.type_resolver()).ok());
  ASSERT_TRUE(register_any_type<int32_t>(fory.type_resolver()).ok());

  RecursiveAny level3{3, int32_t{4}};
  RecursiveAny level2{2, level3};
  RecursiveAny level1{1, level2};

  auto deep_bytes = fory.serialize(level1);
  ASSERT_TRUE(deep_bytes.ok()) << deep_bytes.error().to_string();
  auto deep_result = fory.deserialize<RecursiveAny>(deep_bytes.value());
  ASSERT_FALSE(deep_result.ok());
  EXPECT_EQ(deep_result.error().code(), ErrorCode::DepthExceed);

  RecursiveAny shallow{1, int32_t{2}};
  auto shallow_bytes = fory.serialize(shallow);
  ASSERT_TRUE(shallow_bytes.ok()) << shallow_bytes.error().to_string();
  auto shallow_result = fory.deserialize<RecursiveAny>(shallow_bytes.value());
  ASSERT_TRUE(shallow_result.ok()) << shallow_result.error().to_string();
  EXPECT_EQ(std::any_cast<int32_t>(shallow_result.value().next), 2);
}

TEST(AnySerializerTest, ExceptionDepthCleanup) {
  Config config;
  ReadContext ctx(config, std::make_unique<TypeResolver>());
  Buffer buffer;
  ctx.attach(buffer);

  TypeInfo type_info;
  type_info.harness.any_read_fn = &throw_any;
  EXPECT_THROW(
      Serializer<std::any>::read_with_type_info(ctx, RefMode::None, type_info),
      std::runtime_error);
  EXPECT_EQ(ctx.current_dyn_depth(), 1U);

  ctx.detach();
  ctx.reset();
  EXPECT_EQ(ctx.current_dyn_depth(), 0U);

  Buffer next_buffer;
  ctx.attach(next_buffer);
  type_info.harness.any_read_fn = &read_int_any;
  auto value =
      Serializer<std::any>::read_with_type_info(ctx, RefMode::None, type_info);
  ASSERT_FALSE(ctx.has_error()) << ctx.error().to_string();
  EXPECT_EQ(std::any_cast<int32_t>(value), 7);
  EXPECT_EQ(ctx.current_dyn_depth(), 0U);
}

} // namespace test
} // namespace serialization
} // namespace fory
