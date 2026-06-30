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
#include <array>
#include <cstdint>
#include <deque>
#include <forward_list>
#include <list>
#include <map>
#include <memory>
#include <set>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace fory {
namespace serialization {
namespace {

constexpr int64_t kDefaultGraphMemoryBytes = 128LL * 1024LL * 1024LL;

struct BudgetItem {
  int32_t id = 0;
  std::string name;

  bool operator==(const BudgetItem &other) const {
    return id == other.id && name == other.name;
  }

  FORY_STRUCT(BudgetItem, id, name);
};

struct BudgetEmpty {
  bool operator==(const BudgetEmpty &) const { return true; }

  FORY_STRUCT(BudgetEmpty);
};

struct BudgetSiblings {
  std::vector<BudgetItem> left;
  std::vector<BudgetItem> right;

  bool operator==(const BudgetSiblings &other) const {
    return left == other.left && right == other.right;
  }

  FORY_STRUCT(BudgetSiblings, left, right);
};

struct BudgetFixedArrayOwner {
  std::array<int32_t, 4> prefix{};
  std::vector<BudgetItem> items;

  bool operator==(const BudgetFixedArrayOwner &other) const {
    return prefix == other.prefix && items == other.items;
  }

  FORY_STRUCT(BudgetFixedArrayOwner, prefix, items);
};

template <typename Fn> auto with_fory(int64_t max_graph_memory_bytes, Fn &&fn) {
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(false)
                  .track_ref(false)
                  .max_graph_memory_bytes(max_graph_memory_bytes)
                  .build();
  fory.register_struct<BudgetItem>(1);
  fory.register_struct<BudgetSiblings>(2);
  fory.register_struct<BudgetFixedArrayOwner>(3);
  fory.register_struct<BudgetEmpty>(4);
  return std::forward<Fn>(fn)(fory);
}

template <typename T> std::vector<uint8_t> serialize_value(const T &value) {
  auto bytes = with_fory(kDefaultGraphMemoryBytes,
                         [&](Fory &fory) { return fory.serialize(value); });
  EXPECT_TRUE(bytes.ok()) << bytes.error().to_string();
  return std::move(bytes).value();
}

size_t nested_empty_budget(size_t count) {
  using Outer = std::vector<std::vector<std::string>>;
  using Inner = std::vector<std::string>;
  return sizeof(Outer) + count * sizeof(Inner);
}

template <typename T>
void expect_budget_boundary(const T &value, size_t required) {
  ASSERT_GT(required, 0u);
  auto bytes = serialize_value(value);

  if (required > 1) {
    auto small_result =
        with_fory(static_cast<int64_t>(required - 1),
                  [&](Fory &fory) { return fory.deserialize<T>(bytes); });
    ASSERT_FALSE(small_result.ok());
    EXPECT_EQ(small_result.error().code(), ErrorCode::InvalidData);
  }

  auto exact_result =
      with_fory(static_cast<int64_t>(required),
                [&](Fory &fory) { return fory.deserialize<T>(bytes); });
  ASSERT_TRUE(exact_result.ok()) << exact_result.error().to_string();
  EXPECT_EQ(exact_result.value(), value);
}

TEST(GraphMemoryBudgetTest, FixedDefaultBudgetAndDisable) {
  Config config;
  ReadContext context(config, std::make_unique<TypeResolver>());

  ASSERT_TRUE(context.init_graph_budget());
  ASSERT_TRUE(context.reserve_graph_memory(
      static_cast<size_t>(kDefaultGraphMemoryBytes)));
  ASSERT_FALSE(context.reserve_graph_memory(1));
  EXPECT_EQ(context.take_error().code(), ErrorCode::InvalidData);

  Config disabled_config;
  disabled_config.max_graph_memory_bytes = 0;
  ReadContext disabled(disabled_config, std::make_unique<TypeResolver>());
  ASSERT_TRUE(disabled.init_graph_budget());
  ASSERT_TRUE(disabled.reserve_graph_memory(std::numeric_limits<size_t>::max()));
}

TEST(GraphMemoryBudgetTest, RootKindsShareConfiguredBudget) {
  constexpr size_t count = 3;
  std::vector<std::vector<std::string>> value(count);
  auto bytes = serialize_value(value);
  const size_t required = nested_empty_budget(count);

  auto byte_result = with_fory(static_cast<int64_t>(required - 1),
                               [&](Fory &fory) {
                                 return fory.deserialize<
                                     std::vector<std::vector<std::string>>>(
                                     bytes);
                               });
  ASSERT_FALSE(byte_result.ok());
  EXPECT_EQ(byte_result.error().code(), ErrorCode::InvalidData);

  std::string input(reinterpret_cast<const char *>(bytes.data()), bytes.size());
  std::istringstream source(input);
  StdInputStream stream(source, 8);
  auto stream_result = with_fory(static_cast<int64_t>(required - 1),
                                 [&](Fory &fory) {
                                   return fory.deserialize<
                                       std::vector<std::vector<std::string>>>(
                                       stream);
                                 });
  ASSERT_FALSE(stream_result.ok());
  EXPECT_EQ(stream_result.error().code(), ErrorCode::InvalidData);

  std::istringstream exact_source(input);
  StdInputStream exact_stream(exact_source, 8);
  auto exact_result = with_fory(static_cast<int64_t>(required), [&](Fory &fory) {
    return fory.deserialize<std::vector<std::vector<std::string>>>(
        exact_stream);
  });
  ASSERT_TRUE(exact_result.ok()) << exact_result.error().to_string();
  EXPECT_EQ(exact_result.value(), value);
}

TEST(GraphMemoryBudgetTest, ExplicitOverride) {
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

TEST(GraphMemoryBudgetTest, SmartPointerStructOwners) {
  auto shared_value = std::make_shared<BudgetItem>();
  shared_value->id = 7;
  shared_value->name = "shared";
  auto shared_bytes = serialize_value(shared_value);
  constexpr size_t shared_required =
      sizeof(std::shared_ptr<BudgetItem>) + sizeof(BudgetItem);

  auto shared_small =
      with_fory(static_cast<int64_t>(shared_required - 1), [&](Fory &fory) {
        return fory.deserialize<std::shared_ptr<BudgetItem>>(shared_bytes);
      });
  ASSERT_FALSE(shared_small.ok());
  EXPECT_EQ(shared_small.error().code(), ErrorCode::InvalidData);

  auto shared_exact =
      with_fory(static_cast<int64_t>(shared_required), [&](Fory &fory) {
        return fory.deserialize<std::shared_ptr<BudgetItem>>(shared_bytes);
      });
  ASSERT_TRUE(shared_exact.ok()) << shared_exact.error().to_string();
  ASSERT_NE(shared_exact.value(), nullptr);
  EXPECT_EQ(*shared_exact.value(), *shared_value);

  auto unique_value = std::make_unique<BudgetItem>();
  unique_value->id = 9;
  unique_value->name = "unique";
  auto unique_bytes = serialize_value(unique_value);

  constexpr size_t unique_required =
      sizeof(std::unique_ptr<BudgetItem>) + sizeof(BudgetItem);
  auto unique_small =
      with_fory(static_cast<int64_t>(unique_required - 1), [&](Fory &fory) {
        return fory.deserialize<std::unique_ptr<BudgetItem>>(unique_bytes);
      });
  ASSERT_FALSE(unique_small.ok());
  EXPECT_EQ(unique_small.error().code(), ErrorCode::InvalidData);

  auto unique_exact =
      with_fory(static_cast<int64_t>(unique_required), [&](Fory &fory) {
        return fory.deserialize<std::unique_ptr<BudgetItem>>(unique_bytes);
      });
  ASSERT_TRUE(unique_exact.ok()) << unique_exact.error().to_string();
  ASSERT_NE(unique_exact.value(), nullptr);
  EXPECT_EQ(*unique_exact.value(), *unique_value);
}

TEST(GraphMemoryBudgetTest, SmartPointerVectorOwner) {
  auto value = std::make_shared<std::vector<BudgetItem>>(3);
  auto bytes = serialize_value(value);
  const size_t required = sizeof(std::shared_ptr<std::vector<BudgetItem>>) +
                          sizeof(std::vector<BudgetItem>) +
                          value->size() * sizeof(BudgetItem);

  auto small_result =
      with_fory(static_cast<int64_t>(required - 1), [&](Fory &fory) {
        return fory.deserialize<std::shared_ptr<std::vector<BudgetItem>>>(
            bytes);
      });
  ASSERT_FALSE(small_result.ok());
  EXPECT_EQ(small_result.error().code(), ErrorCode::InvalidData);

  auto exact_result =
      with_fory(static_cast<int64_t>(required), [&](Fory &fory) {
        return fory.deserialize<std::shared_ptr<std::vector<BudgetItem>>>(
            bytes);
      });
  ASSERT_TRUE(exact_result.ok()) << exact_result.error().to_string();
  ASSERT_NE(exact_result.value(), nullptr);
  EXPECT_EQ(*exact_result.value(), *value);
}

TEST(GraphMemoryBudgetTest, EmptyStructRootChargesOwner) {
  BudgetEmpty value;
  expect_budget_boundary(value, sizeof(BudgetEmpty));
}

TEST(GraphMemoryBudgetTest, NestedEmptyContainersUseParentStorage) {
  std::vector<std::vector<std::string>> value(1);
  auto bytes = serialize_value(value);
  const size_t required = sizeof(std::vector<std::vector<std::string>>) +
                          sizeof(std::vector<std::string>);

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

TEST(GraphMemoryBudgetTest, SiblingCumulativeBudget) {
  BudgetSiblings value;
  value.left.resize(16);
  value.right.resize(16);
  auto bytes = serialize_value(value);
  const size_t root_owner = sizeof(BudgetSiblings);
  const size_t one_vector = value.left.size() * sizeof(BudgetItem);

  auto small_result =
      with_fory(static_cast<int64_t>(root_owner + one_vector), [&](Fory &fory) {
        return fory.deserialize<BudgetSiblings>(bytes);
      });
  ASSERT_FALSE(small_result.ok());
  EXPECT_EQ(small_result.error().code(), ErrorCode::InvalidData);

  auto enough_result = with_fory(
      static_cast<int64_t>(root_owner + one_vector * 2),
      [&](Fory &fory) { return fory.deserialize<BudgetSiblings>(bytes); });
  ASSERT_TRUE(enough_result.ok()) << enough_result.error().to_string();
  EXPECT_EQ(enough_result.value(), value);
}

TEST(GraphMemoryBudgetTest, MapBudget) {
  std::map<std::string, int32_t> value{{"a", 1}, {"b", 2}, {"c", 3}};
  const size_t entry_bytes = sizeof(std::string) + sizeof(int32_t);
  const size_t required =
      sizeof(std::map<std::string, int32_t>) + value.size() * entry_bytes;

  expect_budget_boundary(value, required);
}

TEST(GraphMemoryBudgetTest, CollectionLowerBounds) {
  std::deque<BudgetItem> deque_value(4);
  expect_budget_boundary(deque_value,
                         sizeof(std::deque<BudgetItem>) +
                             deque_value.size() * sizeof(BudgetItem));

  std::list<BudgetItem> list_value(4);
  expect_budget_boundary(list_value,
                         sizeof(std::list<BudgetItem>) +
                             list_value.size() * sizeof(BudgetItem));

  std::forward_list<BudgetItem> forward_value(4);
  expect_budget_boundary(forward_value, sizeof(std::forward_list<BudgetItem>) +
                                            size_t{4} * sizeof(BudgetItem));
}

TEST(GraphMemoryBudgetTest, VectorBoolChargesPackedStorage) {
  std::vector<bool> value(33);
  value[0] = true;
  value[32] = true;
  expect_budget_boundary(value, size_t{5});
}

TEST(GraphMemoryBudgetTest, OrderedSetAndMapLowerBounds) {
  std::set<int32_t> set_value{1, 2, 3, 4};
  expect_budget_boundary(set_value, sizeof(std::set<int32_t>) +
                                        set_value.size() * sizeof(int32_t));

  std::map<std::string, int32_t> map_value{{"a", 1}, {"b", 2}};
  expect_budget_boundary(map_value,
                         sizeof(std::map<std::string, int32_t>) +
                             map_value.size() *
                                 (sizeof(std::string) + sizeof(int32_t)));
}

TEST(GraphMemoryBudgetTest, UnorderedContainersLowerBounds) {
  std::unordered_set<int32_t> set_value{1, 2, 3, 4};
  expect_budget_boundary(set_value, sizeof(std::unordered_set<int32_t>) +
                                        set_value.size() * sizeof(int32_t));

  std::unordered_map<std::string, int32_t> map_value{{"a", 1}, {"b", 2}};
  expect_budget_boundary(map_value,
                         sizeof(std::unordered_map<std::string, int32_t>) +
                             map_value.size() *
                                 (sizeof(std::string) + sizeof(int32_t)));
}

TEST(GraphMemoryBudgetTest, ArrayHasNoStandaloneReservation) {
  std::array<int32_t, 4> value{{1, 2, 3, 4}};
  auto bytes = serialize_value(value);
  auto result = with_fory(1, [&](Fory &fory) {
    return fory.deserialize<std::array<int32_t, 4>>(bytes);
  });
  ASSERT_TRUE(result.ok()) << result.error().to_string();
  EXPECT_EQ(result.value(), value);
}

TEST(GraphMemoryBudgetTest, FixedInlineOwnerChargesNestedVector) {
  BudgetFixedArrayOwner value;
  value.prefix = {{1, 2, 3, 4}};
  value.items.resize(3);
  const size_t required =
      sizeof(BudgetFixedArrayOwner) + value.items.size() * sizeof(BudgetItem);

  expect_budget_boundary(value, required);
}

TEST(GraphMemoryBudgetTest, DensePathsSkipped) {
  {
    std::string value = "graph-budget-string";
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

TEST(GraphMemoryBudgetTest, ByteCheckStillRejectsLargeLength) {
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
