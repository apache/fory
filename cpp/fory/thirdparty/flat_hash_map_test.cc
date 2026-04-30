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

#include "fory/thirdparty/flat_hash_map.h"

#include <cstdint>
#include <string>
#include <typeindex>

#include "gtest/gtest.h"

namespace fory {

TEST(FlatHashMapTest, RequiredApiCoverage) {
  flat_hash_map<uint64_t, std::string> map;
  EXPECT_TRUE(map.empty());

  auto inserted = map.emplace(1, "one");
  EXPECT_TRUE(inserted.second);
  EXPECT_EQ(inserted.first->second, "one");
  EXPECT_FALSE(map.emplace(1, "duplicate").second);
  EXPECT_EQ(map[1], "one");

  map[2] = "two";
  EXPECT_EQ(map.size(), 2);
  EXPECT_NE(map.find(1), nullptr);
  EXPECT_EQ(map.find(42), nullptr);

  size_t old_bucket_count = map.bucket_count();
  map.reserve(256);
  EXPECT_GE(map.bucket_count(), old_bucket_count);
  map.rehash(512);
  EXPECT_GE(map.bucket_count(), 512);

  size_t visited = 0;
  for (const auto &[key, value] : map) {
    EXPECT_TRUE((key == 1 && value == "one") || (key == 2 && value == "two"));
    ++visited;
  }
  EXPECT_EQ(visited, 2);

  flat_hash_map<uint64_t, std::string> copied = map;
  EXPECT_EQ(copied[1], "one");
  EXPECT_EQ(copied[2], "two");

  flat_hash_map<uint64_t, std::string> moved = std::move(copied);
  EXPECT_EQ(moved[1], "one");
  EXPECT_EQ(moved[2], "two");

  moved.clear();
  EXPECT_TRUE(moved.empty());
  moved[3] = "three";
  EXPECT_EQ(moved[3], "three");
}

TEST(FlatHashMapTest, SupportsRequiredKeyShapes) {
  flat_hash_map<uint64_t, int> u64_map;
  u64_map[123] = 1;
  EXPECT_EQ(u64_map.find(123)->second, 1);

  int target = 0;
  flat_hash_map<uintptr_t, int> pointer_map;
  pointer_map[reinterpret_cast<uintptr_t>(&target)] = 2;
  EXPECT_EQ(pointer_map.find(reinterpret_cast<uintptr_t>(&target))->second, 2);

  flat_hash_map<std::string, int> string_map;
  string_map["abc"] = 3;
  EXPECT_EQ(string_map.find("abc")->second, 3);

  flat_hash_map<std::type_index, int> type_map;
  type_map[std::type_index(typeid(int))] = 4;
  EXPECT_EQ(type_map.find(std::type_index(typeid(int)))->second, 4);

  flat_hash_map<std::pair<int64_t, int64_t>, int> pair_map;
  pair_map[std::make_pair<int64_t, int64_t>(7, 9)] = 5;
  EXPECT_EQ(pair_map.find(std::make_pair<int64_t, int64_t>(7, 9))->second, 5);
}

TEST(FlatHashMapTest, HandlesGroupProbingAndDeletedSlots) {
  flat_hash_map<uint64_t, uint64_t> map;
  for (uint64_t i = 0; i < 4096; ++i) {
    map.emplace(i, i * 3);
  }
  EXPECT_EQ(map.size(), 4096);

  for (uint64_t i = 0; i < 4096; i += 2) {
    EXPECT_EQ(map.erase(i), 1);
  }
  EXPECT_EQ(map.size(), 2048);

  for (uint64_t i = 1; i < 4096; i += 2) {
    auto *entry = map.find(i);
    ASSERT_NE(entry, nullptr);
    EXPECT_EQ(entry->second, i * 3);
  }

  for (uint64_t i = 4096; i < 6144; ++i) {
    map.emplace(i, i * 5);
  }
  EXPECT_EQ(map.size(), 4096);

  for (uint64_t i = 4096; i < 6144; ++i) {
    auto *entry = map.find(i);
    ASSERT_NE(entry, nullptr);
    EXPECT_EQ(entry->second, i * 5);
  }
}

} // namespace fory
