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
#include <map>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace fory {
namespace serialization {
namespace {

constexpr size_t kKnownBudgetSlack = 64 * 1024;

struct BudgetItem {
  int32_t id = 0;
  std::string name;

  bool operator==(const BudgetItem &other) const {
    return id == other.id && name == other.name;
  }

  FORY_STRUCT(BudgetItem, id, name);
};

struct BudgetSiblings {
  std::vector<BudgetItem> left;
  std::vector<BudgetItem> right;

  bool operator==(const BudgetSiblings &other) const {
    return left == other.left && right == other.right;
  }

  FORY_STRUCT(BudgetSiblings, left, right);
};

template <typename Fn>
auto with_fory(int64_t max_container_memory_bytes, Fn &&fn) {
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(false)
                  .track_ref(false)
                  .max_container_memory_bytes(max_container_memory_bytes)
                  .build();
  fory.register_struct<BudgetItem>(1);
  fory.register_struct<BudgetSiblings>(2);
  return std::forward<Fn>(fn)(fory);
}

template <typename T> std::vector<uint8_t> serialize_value(const T &value) {
  auto bytes = with_fory(-1, [&](Fory &fory) { return fory.serialize(value); });
  EXPECT_TRUE(bytes.ok()) << bytes.error().to_string();
  return std::move(bytes).value();
}

size_t nested_empty_budget(size_t count) {
  using Inner = std::vector<std::string>;
  using Outer = std::vector<Inner>;
  return sizeof(Outer) + count * sizeof(Inner) + count * sizeof(Inner);
}

TEST(ContainerMemoryBudgetTest, KnownLengthAutoBudget) {
  constexpr size_t count = 3000;
  std::vector<std::vector<std::string>> value(count);
  auto bytes = serialize_value(value);
  const size_t auto_limit = bytes.size() * 8 + kKnownBudgetSlack;
  const size_t required = nested_empty_budget(count);
  ASSERT_GT(required, auto_limit);

  auto default_result = with_fory(-1, [&](Fory &fory) {
    return fory.deserialize<std::vector<std::vector<std::string>>>(bytes);
  });
  ASSERT_FALSE(default_result.ok());
  EXPECT_EQ(default_result.error().code(), ErrorCode::InvalidData);

  auto explicit_auto_result =
      with_fory(static_cast<int64_t>(auto_limit), [&](Fory &fory) {
        return fory.deserialize<std::vector<std::vector<std::string>>>(bytes);
      });
  ASSERT_FALSE(explicit_auto_result.ok());
  EXPECT_EQ(explicit_auto_result.error().code(), ErrorCode::InvalidData);

  auto explicit_result =
      with_fory(static_cast<int64_t>(required), [&](Fory &fory) {
        return fory.deserialize<std::vector<std::vector<std::string>>>(bytes);
      });
  ASSERT_TRUE(explicit_result.ok()) << explicit_result.error().to_string();
  EXPECT_EQ(explicit_result.value(), value);
}

TEST(ContainerMemoryBudgetTest, StreamAutoBudget) {
  constexpr size_t count = 10000;
  std::vector<std::vector<std::string>> value(count);
  auto bytes = serialize_value(value);
  const size_t known_limit = bytes.size() * 8 + kKnownBudgetSlack;
  ASSERT_GT(nested_empty_budget(count), known_limit);

  auto known_result = with_fory(-1, [&](Fory &fory) {
    return fory.deserialize<std::vector<std::vector<std::string>>>(bytes);
  });
  ASSERT_FALSE(known_result.ok());
  EXPECT_EQ(known_result.error().code(), ErrorCode::InvalidData);

  std::string input(reinterpret_cast<const char *>(bytes.data()), bytes.size());
  std::istringstream source(input);
  StdInputStream stream(source, 8);
  auto stream_result = with_fory(-1, [&](Fory &fory) {
    return fory.deserialize<std::vector<std::vector<std::string>>>(stream);
  });
  ASSERT_TRUE(stream_result.ok()) << stream_result.error().to_string();
  EXPECT_EQ(stream_result.value(), value);
}

TEST(ContainerMemoryBudgetTest, ExplicitOverride) {
  std::vector<BudgetItem> value(8);
  auto bytes = serialize_value(value);
  const size_t required =
      sizeof(std::vector<BudgetItem>) + value.size() * sizeof(BudgetItem);

  auto small_result =
      with_fory(static_cast<int64_t>(required - 1), [&](Fory &fory) {
        return fory.deserialize<std::vector<BudgetItem>>(bytes);
      });
  ASSERT_FALSE(small_result.ok());
  EXPECT_EQ(small_result.error().code(), ErrorCode::InvalidData);

  auto exact_result =
      with_fory(static_cast<int64_t>(required), [&](Fory &fory) {
        return fory.deserialize<std::vector<BudgetItem>>(bytes);
      });
  ASSERT_TRUE(exact_result.ok()) << exact_result.error().to_string();
  EXPECT_EQ(exact_result.value(), value);
}

TEST(ContainerMemoryBudgetTest, EmptyContainersChargeFixedCost) {
  std::vector<std::vector<std::string>> value(1);
  auto bytes = serialize_value(value);
  const size_t required = nested_empty_budget(1);

  auto small_result =
      with_fory(static_cast<int64_t>(required - 1), [&](Fory &fory) {
        return fory.deserialize<std::vector<std::vector<std::string>>>(bytes);
      });
  ASSERT_FALSE(small_result.ok());
  EXPECT_EQ(small_result.error().code(), ErrorCode::InvalidData);

  auto exact_result =
      with_fory(static_cast<int64_t>(required), [&](Fory &fory) {
        return fory.deserialize<std::vector<std::vector<std::string>>>(bytes);
      });
  ASSERT_TRUE(exact_result.ok()) << exact_result.error().to_string();
  EXPECT_EQ(exact_result.value(), value);
}

TEST(ContainerMemoryBudgetTest, SiblingCumulativeBudget) {
  BudgetSiblings value;
  value.left.resize(16);
  value.right.resize(16);
  auto bytes = serialize_value(value);
  const size_t one_vector =
      sizeof(std::vector<BudgetItem>) + value.left.size() * sizeof(BudgetItem);

  auto small_result =
      with_fory(static_cast<int64_t>(one_vector), [&](Fory &fory) {
        return fory.deserialize<BudgetSiblings>(bytes);
      });
  ASSERT_FALSE(small_result.ok());
  EXPECT_EQ(small_result.error().code(), ErrorCode::InvalidData);

  auto enough_result =
      with_fory(static_cast<int64_t>(one_vector * 2), [&](Fory &fory) {
        return fory.deserialize<BudgetSiblings>(bytes);
      });
  ASSERT_TRUE(enough_result.ok()) << enough_result.error().to_string();
  EXPECT_EQ(enough_result.value(), value);
}

TEST(ContainerMemoryBudgetTest, MapBudget) {
  std::map<std::string, int32_t> value{{"a", 1}, {"b", 2}, {"c", 3}};
  auto bytes = serialize_value(value);
  const size_t entry_bytes =
      sizeof(std::string) + sizeof(int32_t) + 16 + sizeof(void *) * 3;
  const size_t required = sizeof(value) + value.size() * entry_bytes;

  auto small_result =
      with_fory(static_cast<int64_t>(required - 1), [&](Fory &fory) {
        return fory.deserialize<std::map<std::string, int32_t>>(bytes);
      });
  ASSERT_FALSE(small_result.ok());
  EXPECT_EQ(small_result.error().code(), ErrorCode::InvalidData);

  auto exact_result =
      with_fory(static_cast<int64_t>(required), [&](Fory &fory) {
        return fory.deserialize<std::map<std::string, int32_t>>(bytes);
      });
  ASSERT_TRUE(exact_result.ok()) << exact_result.error().to_string();
  EXPECT_EQ(exact_result.value(), value);
}

TEST(ContainerMemoryBudgetTest, DensePathsSkipped) {
  {
    std::string value = "container-budget-string";
    auto bytes = serialize_value(value);
    auto result = with_fory(
        1, [&](Fory &fory) { return fory.deserialize<std::string>(bytes); });
    ASSERT_TRUE(result.ok()) << result.error().to_string();
    EXPECT_EQ(result.value(), value);
  }
  {
    std::vector<uint8_t> value(256, 7);
    auto bytes = serialize_value(value);
    auto result = with_fory(1, [&](Fory &fory) {
      return fory.deserialize<std::vector<uint8_t>>(bytes);
    });
    ASSERT_TRUE(result.ok()) << result.error().to_string();
    EXPECT_EQ(result.value(), value);
  }
  {
    std::vector<int32_t> value(256, 42);
    auto bytes = serialize_value(value);
    auto result = with_fory(1, [&](Fory &fory) {
      return fory.deserialize<std::vector<int32_t>>(bytes);
    });
    ASSERT_TRUE(result.ok()) << result.error().to_string();
    EXPECT_EQ(result.value(), value);
  }
}

TEST(ContainerMemoryBudgetTest, ByteCheckStillRejectsLargeLength) {
  Config config;
  auto resolver = std::make_unique<TypeResolver>();
  ReadContext ctx(config, std::move(resolver));
  std::vector<uint8_t> bytes{64};
  Buffer buffer(bytes.data(), static_cast<uint32_t>(bytes.size()), false);
  ctx.attach(buffer);

  auto result = Serializer<std::vector<std::string>>::read_data(ctx);
  EXPECT_TRUE(result.empty());
  ASSERT_TRUE(ctx.has_error());
  EXPECT_EQ(ctx.error().code(), ErrorCode::BufferOutOfBound);
}

} // namespace
} // namespace serialization
} // namespace fory
