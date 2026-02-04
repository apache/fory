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

#include "fory/util/flat_int_map.h"
#include <gtest/gtest.h>
#include <random>
#include <unordered_set>

namespace fory {
namespace util {

class FlatIntMapTest : public ::testing::Test {
protected:
  int dummy_values_[100];
};

// ============================================================================
// Tests for U64PtrMap (uint64_t keys, pointer values)
// ============================================================================

TEST_F(FlatIntMapTest, U64PtrMap_BasicInsertAndFind) {
  U64PtrMap<int> map(16);

  map.put(1, &dummy_values_[0]);
  map.put(2, &dummy_values_[1]);
  map.put(3, &dummy_values_[2]);

  EXPECT_EQ(map.size(), 3);

  auto *entry1 = map.find(1);
  ASSERT_NE(entry1, nullptr);
  EXPECT_EQ(entry1->key, 1);
  EXPECT_EQ(entry1->value, &dummy_values_[0]);

  auto *entry2 = map.find(2);
  ASSERT_NE(entry2, nullptr);
  EXPECT_EQ(entry2->value, &dummy_values_[1]);

  auto *entry3 = map.find(3);
  ASSERT_NE(entry3, nullptr);
  EXPECT_EQ(entry3->value, &dummy_values_[2]);
}

TEST_F(FlatIntMapTest, U64PtrMap_FindNonExistent) {
  U64PtrMap<int> map(16);

  map.put(1, &dummy_values_[0]);

  EXPECT_EQ(map.find(2), nullptr);
  EXPECT_EQ(map.find(100), nullptr);
  EXPECT_EQ(map.find(UINT64_MAX), nullptr); // Max value is reserved as empty
}

TEST_F(FlatIntMapTest, U64PtrMap_ZeroKey) {
  U64PtrMap<int> map(16);

  map.put(0, &dummy_values_[0]); // 0 is now a valid key
  EXPECT_EQ(map.size(), 1);

  auto *entry = map.find(0);
  ASSERT_NE(entry, nullptr);
  EXPECT_EQ(entry->value, &dummy_values_[0]);
}

TEST_F(FlatIntMapTest, U64PtrMap_UpdateExistingKey) {
  U64PtrMap<int> map(16);

  map.put(1, &dummy_values_[0]);
  EXPECT_EQ(map.size(), 1);

  map.put(1, &dummy_values_[1]);
  EXPECT_EQ(map.size(), 1); // Size should not increase

  auto *entry = map.find(1);
  ASSERT_NE(entry, nullptr);
  EXPECT_EQ(entry->value, &dummy_values_[1]);
}

TEST_F(FlatIntMapTest, U64PtrMap_Contains) {
  U64PtrMap<int> map(16);

  map.put(42, &dummy_values_[0]);

  EXPECT_TRUE(map.contains(42));
  EXPECT_FALSE(map.contains(43));
  EXPECT_FALSE(map.contains(UINT64_MAX)); // Max is reserved
}

TEST_F(FlatIntMapTest, U64PtrMap_EmptyMap) {
  U64PtrMap<int> map(16);

  EXPECT_TRUE(map.empty());
  EXPECT_EQ(map.size(), 0);
  EXPECT_EQ(map.find(1), nullptr);

  map.put(1, &dummy_values_[0]);
  EXPECT_FALSE(map.empty());
}

TEST_F(FlatIntMapTest, U64PtrMap_PowerOf2Capacity) {
  U64PtrMap<int> map1(10);
  EXPECT_EQ(map1.capacity(), 16); // Rounded up to 16

  U64PtrMap<int> map2(17);
  EXPECT_EQ(map2.capacity(), 32); // Rounded up to 32

  U64PtrMap<int> map3(64);
  EXPECT_EQ(map3.capacity(), 64); // Already power of 2
}

TEST_F(FlatIntMapTest, U64PtrMap_ManyInsertions) {
  U64PtrMap<int> map(256);

  // Insert many entries
  for (int i = 1; i <= 100; ++i) {
    map.put(static_cast<uint64_t>(i), &dummy_values_[i % 100]);
  }

  EXPECT_EQ(map.size(), 100);

  // Verify all entries
  for (int i = 1; i <= 100; ++i) {
    auto *entry = map.find(static_cast<uint64_t>(i));
    ASSERT_NE(entry, nullptr) << "Key " << i << " not found";
    EXPECT_EQ(entry->value, &dummy_values_[i % 100]);
  }
}

TEST_F(FlatIntMapTest, U64PtrMap_CollisionHandling) {
  // Use a small capacity to force collisions
  U64PtrMap<int> map(8);

  // Insert several entries that might collide
  for (int i = 1; i <= 5; ++i) {
    map.put(static_cast<uint64_t>(i), &dummy_values_[i]);
  }

  EXPECT_EQ(map.size(), 5);

  // All entries should still be findable
  for (int i = 1; i <= 5; ++i) {
    auto *entry = map.find(static_cast<uint64_t>(i));
    ASSERT_NE(entry, nullptr);
    EXPECT_EQ(entry->value, &dummy_values_[i]);
  }
}

TEST_F(FlatIntMapTest, U64PtrMap_LargeKeys) {
  U64PtrMap<int> map(16);

  uint64_t key1 = 0xFFFFFFFFFFFFFFFEULL; // Max-1 (max is reserved)
  uint64_t key2 = 0x123456789ABCDEF0ULL;
  uint64_t key3 = 0x1ULL;

  map.put(key1, &dummy_values_[0]);
  map.put(key2, &dummy_values_[1]);
  map.put(key3, &dummy_values_[2]);

  EXPECT_EQ(map.size(), 3);

  EXPECT_NE(map.find(key1), nullptr);
  EXPECT_NE(map.find(key2), nullptr);
  EXPECT_NE(map.find(key3), nullptr);
}

TEST_F(FlatIntMapTest, U64PtrMap_CopyConstructor) {
  U64PtrMap<int> map1(16);
  map1.put(1, &dummy_values_[0]);
  map1.put(2, &dummy_values_[1]);

  U64PtrMap<int> map2(map1);

  EXPECT_EQ(map2.size(), 2);
  EXPECT_NE(map2.find(1), nullptr);
  EXPECT_NE(map2.find(2), nullptr);

  // Modify original - copy should be independent
  map1.put(3, &dummy_values_[2]);
  EXPECT_EQ(map1.size(), 3);
  EXPECT_EQ(map2.size(), 2);
  EXPECT_EQ(map2.find(3), nullptr);
}

TEST_F(FlatIntMapTest, U64PtrMap_MoveConstructor) {
  U64PtrMap<int> map1(16);
  map1.put(1, &dummy_values_[0]);
  map1.put(2, &dummy_values_[1]);

  U64PtrMap<int> map2(std::move(map1));

  EXPECT_EQ(map2.size(), 2);
  EXPECT_NE(map2.find(1), nullptr);
  EXPECT_NE(map2.find(2), nullptr);
}

TEST_F(FlatIntMapTest, U64PtrMap_Iterator) {
  U64PtrMap<int> map(16);
  map.put(1, &dummy_values_[0]);
  map.put(2, &dummy_values_[1]);
  map.put(3, &dummy_values_[2]);

  std::unordered_set<uint64_t> found_keys;
  for (auto it = map.begin(); it != map.end(); ++it) {
    found_keys.insert(it->key);
  }

  EXPECT_EQ(found_keys.size(), 3);
  EXPECT_TRUE(found_keys.count(1));
  EXPECT_TRUE(found_keys.count(2));
  EXPECT_TRUE(found_keys.count(3));
}

TEST_F(FlatIntMapTest, U64PtrMap_RangeBasedFor) {
  U64PtrMap<int> map(16);
  map.put(10, &dummy_values_[0]);
  map.put(20, &dummy_values_[1]);

  int count = 0;
  for (const auto &entry : map) {
    EXPECT_TRUE(entry.first == 10 || entry.first == 20);
    ++count;
  }
  EXPECT_EQ(count, 2);
}

TEST_F(FlatIntMapTest, U64PtrMap_ConstFind) {
  U64PtrMap<int> map(16);
  map.put(1, &dummy_values_[0]);

  const U64PtrMap<int> &const_map = map;

  const auto *entry = const_map.find(1);
  ASSERT_NE(entry, nullptr);
  EXPECT_EQ(entry->key, 1);
}

// Performance-oriented test: verify lookup works well with type-index-like keys
TEST_F(FlatIntMapTest, U64PtrMap_TypeIndexLikeKeys) {
  U64PtrMap<void> map(128);

  // Simulate type index values (typically hash-based, well-distributed)
  std::vector<uint64_t> keys;
  for (int i = 0; i < 50; ++i) {
    // Simulate fnv1a-like hashing
    uint64_t key = 0xcbf29ce484222325ULL;
    key ^= static_cast<uint64_t>(i);
    key *= 0x100000001b3ULL;
    keys.push_back(key);
    map.put(key, reinterpret_cast<void *>(static_cast<uintptr_t>(i + 1)));
  }

  EXPECT_EQ(map.size(), 50);

  // Verify all lookups work
  for (size_t i = 0; i < keys.size(); ++i) {
    auto *entry = map.find(keys[i]);
    ASSERT_NE(entry, nullptr) << "Key at index " << i << " not found";
    EXPECT_EQ(entry->value,
              reinterpret_cast<void *>(static_cast<uintptr_t>(i + 1)));
  }
}

// ============================================================================
// Tests for U32PtrMap (uint32_t keys, pointer values)
// ============================================================================

TEST_F(FlatIntMapTest, U32PtrMap_BasicInsertAndFind) {
  U32PtrMap<int> map(16);

  map.put(1, &dummy_values_[0]);
  map.put(2, &dummy_values_[1]);
  map.put(3, &dummy_values_[2]);

  EXPECT_EQ(map.size(), 3);

  auto *entry1 = map.find(1);
  ASSERT_NE(entry1, nullptr);
  EXPECT_EQ(entry1->key, 1u);
  EXPECT_EQ(entry1->value, &dummy_values_[0]);

  auto *entry2 = map.find(2);
  ASSERT_NE(entry2, nullptr);
  EXPECT_EQ(entry2->value, &dummy_values_[1]);

  auto *entry3 = map.find(3);
  ASSERT_NE(entry3, nullptr);
  EXPECT_EQ(entry3->value, &dummy_values_[2]);
}

TEST_F(FlatIntMapTest, U32PtrMap_FindNonExistent) {
  U32PtrMap<int> map(16);

  map.put(1, &dummy_values_[0]);

  EXPECT_EQ(map.find(2), nullptr);
  EXPECT_EQ(map.find(100), nullptr);
  EXPECT_EQ(map.find(UINT32_MAX), nullptr); // Max value is reserved as empty
}

TEST_F(FlatIntMapTest, U32PtrMap_ZeroKey) {
  U32PtrMap<int> map(16);

  map.put(0, &dummy_values_[0]); // 0 is now a valid key
  EXPECT_EQ(map.size(), 1);

  auto *entry = map.find(0);
  ASSERT_NE(entry, nullptr);
  EXPECT_EQ(entry->value, &dummy_values_[0]);
}

TEST_F(FlatIntMapTest, U32PtrMap_UpdateExistingKey) {
  U32PtrMap<int> map(16);

  map.put(1, &dummy_values_[0]);
  EXPECT_EQ(map.size(), 1);

  map.put(1, &dummy_values_[1]);
  EXPECT_EQ(map.size(), 1); // Size should not increase

  auto *entry = map.find(1);
  ASSERT_NE(entry, nullptr);
  EXPECT_EQ(entry->value, &dummy_values_[1]);
}

TEST_F(FlatIntMapTest, U32PtrMap_Contains) {
  U32PtrMap<int> map(16);

  map.put(42, &dummy_values_[0]);

  EXPECT_TRUE(map.contains(42));
  EXPECT_FALSE(map.contains(43));
  EXPECT_FALSE(map.contains(UINT32_MAX)); // Max is reserved
}

TEST_F(FlatIntMapTest, U32PtrMap_EmptyMap) {
  U32PtrMap<int> map(16);

  EXPECT_TRUE(map.empty());
  EXPECT_EQ(map.size(), 0);
  EXPECT_EQ(map.find(1), nullptr);

  map.put(1, &dummy_values_[0]);
  EXPECT_FALSE(map.empty());
}

TEST_F(FlatIntMapTest, U32PtrMap_ManyInsertions) {
  U32PtrMap<int> map(256);

  // Insert many entries
  for (uint32_t i = 1; i <= 100; ++i) {
    map.put(i, &dummy_values_[i % 100]);
  }

  EXPECT_EQ(map.size(), 100);

  // Verify all entries
  for (uint32_t i = 1; i <= 100; ++i) {
    auto *entry = map.find(i);
    ASSERT_NE(entry, nullptr) << "Key " << i << " not found";
    EXPECT_EQ(entry->value, &dummy_values_[i % 100]);
  }
}

TEST_F(FlatIntMapTest, U32PtrMap_CollisionHandling) {
  // Use a small capacity to force collisions
  U32PtrMap<int> map(8);

  // Insert several entries that might collide
  for (uint32_t i = 1; i <= 5; ++i) {
    map.put(i, &dummy_values_[i]);
  }

  EXPECT_EQ(map.size(), 5);

  // All entries should still be findable
  for (uint32_t i = 1; i <= 5; ++i) {
    auto *entry = map.find(i);
    ASSERT_NE(entry, nullptr);
    EXPECT_EQ(entry->value, &dummy_values_[i]);
  }
}

TEST_F(FlatIntMapTest, U32PtrMap_LargeKeys) {
  U32PtrMap<int> map(16);

  uint32_t key1 = 0xFFFFFFFEu; // Max-1 (max is reserved)
  uint32_t key2 = 0x12345678u;
  uint32_t key3 = 0x1u;

  map.put(key1, &dummy_values_[0]);
  map.put(key2, &dummy_values_[1]);
  map.put(key3, &dummy_values_[2]);

  EXPECT_EQ(map.size(), 3);

  EXPECT_NE(map.find(key1), nullptr);
  EXPECT_NE(map.find(key2), nullptr);
  EXPECT_NE(map.find(key3), nullptr);
}

TEST_F(FlatIntMapTest, U32PtrMap_CopyConstructor) {
  U32PtrMap<int> map1(16);
  map1.put(1, &dummy_values_[0]);
  map1.put(2, &dummy_values_[1]);

  U32PtrMap<int> map2(map1);

  EXPECT_EQ(map2.size(), 2);
  EXPECT_NE(map2.find(1), nullptr);
  EXPECT_NE(map2.find(2), nullptr);

  // Modify original - copy should be independent
  map1.put(3, &dummy_values_[2]);
  EXPECT_EQ(map1.size(), 3);
  EXPECT_EQ(map2.size(), 2);
  EXPECT_EQ(map2.find(3), nullptr);
}

TEST_F(FlatIntMapTest, U32PtrMap_MoveConstructor) {
  U32PtrMap<int> map1(16);
  map1.put(1, &dummy_values_[0]);
  map1.put(2, &dummy_values_[1]);

  U32PtrMap<int> map2(std::move(map1));

  EXPECT_EQ(map2.size(), 2);
  EXPECT_NE(map2.find(1), nullptr);
  EXPECT_NE(map2.find(2), nullptr);
}

TEST_F(FlatIntMapTest, U32PtrMap_Iterator) {
  U32PtrMap<int> map(16);
  map.put(1, &dummy_values_[0]);
  map.put(2, &dummy_values_[1]);
  map.put(3, &dummy_values_[2]);

  std::unordered_set<uint32_t> found_keys;
  for (auto it = map.begin(); it != map.end(); ++it) {
    found_keys.insert(it->key);
  }

  EXPECT_EQ(found_keys.size(), 3);
  EXPECT_TRUE(found_keys.count(1));
  EXPECT_TRUE(found_keys.count(2));
  EXPECT_TRUE(found_keys.count(3));
}

TEST_F(FlatIntMapTest, U32PtrMap_RangeBasedFor) {
  U32PtrMap<int> map(16);
  map.put(10, &dummy_values_[0]);
  map.put(20, &dummy_values_[1]);

  int count = 0;
  for (const auto &entry : map) {
    EXPECT_TRUE(entry.first == 10 || entry.first == 20);
    ++count;
  }
  EXPECT_EQ(count, 2);
}

TEST_F(FlatIntMapTest, U32PtrMap_ConstFind) {
  U32PtrMap<int> map(16);
  map.put(1, &dummy_values_[0]);

  const U32PtrMap<int> &const_map = map;

  const auto *entry = const_map.find(1);
  ASSERT_NE(entry, nullptr);
  EXPECT_EQ(entry->key, 1u);
}

// Test for type_id lookups (simulates internal type_info_by_id_ usage)
TEST_F(FlatIntMapTest, U32PtrMap_TypeIdLookups) {
  U32PtrMap<void> map(256);

  // Simulate internal type ID values used in TypeResolver
  std::vector<uint32_t> type_ids;
  for (uint32_t i = 1; i <= 50; ++i) {
    uint32_t type_id = i;
    type_ids.push_back(type_id);
    map.put(type_id, reinterpret_cast<void *>(static_cast<uintptr_t>(i)));
  }

  EXPECT_EQ(map.size(), 50);

  // Verify all lookups work
  for (size_t i = 0; i < type_ids.size(); ++i) {
    auto *entry = map.find(type_ids[i]);
    ASSERT_NE(entry, nullptr) << "Type ID at index " << i << " not found";
    EXPECT_EQ(entry->value,
              reinterpret_cast<void *>(static_cast<uintptr_t>(i + 1)));
  }
}

// ============================================================================
// Tests for auto-grow functionality
// ============================================================================

TEST_F(FlatIntMapTest, AutoGrow_U64PtrMap) {
  // Start with small capacity, default load factor 0.5
  U64PtrMap<int> map(8);
  EXPECT_EQ(map.capacity(), 8);

  // Insert more than capacity * load_factor (8 * 0.5 = 4)
  for (int i = 1; i <= 10; ++i) {
    map.put(static_cast<uint64_t>(i), &dummy_values_[i % 100]);
  }

  EXPECT_EQ(map.size(), 10);
  EXPECT_GT(map.capacity(), 8); // Should have grown

  // Verify all entries still accessible after grow
  for (int i = 1; i <= 10; ++i) {
    auto *entry = map.find(static_cast<uint64_t>(i));
    ASSERT_NE(entry, nullptr) << "Key " << i << " not found after grow";
    EXPECT_EQ(entry->value, &dummy_values_[i % 100]);
  }
}

TEST_F(FlatIntMapTest, AutoGrow_U32PtrMap) {
  U32PtrMap<int> map(8);
  EXPECT_EQ(map.capacity(), 8);

  for (uint32_t i = 1; i <= 10; ++i) {
    map.put(i, &dummy_values_[i % 100]);
  }

  EXPECT_EQ(map.size(), 10);
  EXPECT_GT(map.capacity(), 8);

  for (uint32_t i = 1; i <= 10; ++i) {
    auto *entry = map.find(i);
    ASSERT_NE(entry, nullptr);
    EXPECT_EQ(entry->value, &dummy_values_[i % 100]);
  }
}

TEST_F(FlatIntMapTest, CustomLoadFactor) {
  // Use higher load factor (0.75) - more memory efficient but slower lookup
  U64PtrMap<int> map(16, 0.75f);
  EXPECT_EQ(map.capacity(), 16);

  // Can insert up to 16 * 0.75 = 12 before grow
  for (int i = 1; i <= 12; ++i) {
    map.put(static_cast<uint64_t>(i), &dummy_values_[i % 100]);
  }
  EXPECT_EQ(map.capacity(), 16); // Should not have grown yet

  // One more should trigger grow
  map.put(13, &dummy_values_[13]);
  EXPECT_GT(map.capacity(), 16);
}

TEST_F(FlatIntMapTest, GetOrDefault_PtrMap) {
  U64PtrMap<int> map(16);
  map.put(1, &dummy_values_[0]);
  map.put(2, &dummy_values_[1]);

  // Test get_or_default() returns value directly
  int *val1 = map.get_or_default(1, nullptr);
  EXPECT_EQ(val1, &dummy_values_[0]);

  int *val2 = map.get_or_default(2, nullptr);
  EXPECT_EQ(val2, &dummy_values_[1]);

  // Non-existent key returns default
  int *val3 = map.get_or_default(999, nullptr);
  EXPECT_EQ(val3, nullptr);

  // Can use non-null default
  int *val4 = map.get_or_default(999, &dummy_values_[50]);
  EXPECT_EQ(val4, &dummy_values_[50]);
}

// ============================================================================
// Tests for U64Map (uint64_t keys, scalar values)
// ============================================================================

TEST_F(FlatIntMapTest, U64Map_ScalarValues) {
  U64Map<int> map(8);

  // Insert many entries to trigger multiple grows
  for (int i = 1; i <= 1000; ++i) {
    map.put(static_cast<uint64_t>(i), i * 10);
  }

  EXPECT_EQ(map.size(), 1000);

  // Verify all entries using get_or_default
  for (int i = 1; i <= 1000; ++i) {
    int val = map.get_or_default(static_cast<uint64_t>(i), -1);
    EXPECT_EQ(val, i * 10) << "Key " << i << " has wrong value";
  }

  // Non-existent key returns default
  EXPECT_EQ(map.get_or_default(9999, -1), -1);
}

TEST_F(FlatIntMapTest, U64Map_GetOrDefault) {
  U64Map<int> map(16);
  map.put(1, 100);
  map.put(2, 200);

  EXPECT_EQ(map.get_or_default(1, -1), 100);
  EXPECT_EQ(map.get_or_default(2, -1), 200);
  EXPECT_EQ(map.get_or_default(3, -1), -1);
  EXPECT_EQ(map.get_or_default(3, 999), 999);
}

TEST_F(FlatIntMapTest, U32Map_ScalarValues) {
  U32Map<int> map(16);

  for (uint32_t i = 1; i <= 100; ++i) {
    map.put(i, static_cast<int>(i * 2));
  }

  EXPECT_EQ(map.size(), 100);

  for (uint32_t i = 1; i <= 100; ++i) {
    int val = map.get_or_default(i, -1);
    EXPECT_EQ(val, static_cast<int>(i * 2));
  }
}

} // namespace util
} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
