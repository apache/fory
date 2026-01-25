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

#include "fory/util/pool.h"

#include <atomic>
#include <gtest/gtest.h>
#include <stdexcept>
#include <thread>
#include <vector>

namespace fory {
namespace util {
namespace {

TEST(PoolTest, AcquireUsesFactory) {
  size_t created = 0;
  Pool<size_t> pool([&created] {
    ++created;
    return std::make_unique<size_t>(created);
  });

  size_t *first_ptr = nullptr;
  {
    auto first = pool.acquire();
    ASSERT_TRUE(first);
    first_ptr = first.get();
    EXPECT_EQ(*first, 1U);
  }

  {
    auto second = pool.acquire();
    ASSERT_TRUE(second);
    EXPECT_EQ(second.get(), first_ptr);
    EXPECT_EQ(*second, 1U);
    EXPECT_EQ(created, 1U);
  }
}

TEST(PoolTest, BorrowReturnsValue) {
  Pool<int> pool([] { return std::make_unique<int>(3); });
  const int result = pool.borrow([](int &value) {
    ++value;
    return value;
  });
  EXPECT_EQ(result, 4);

  auto reused = pool.acquire();
  ASSERT_TRUE(reused);
  EXPECT_EQ(*reused, 4);
}

TEST(PoolTest, BorrowRestoresOnException) {
  Pool<int> pool([] { return std::make_unique<int>(5); });
  EXPECT_THROW(pool.borrow([](int &value) {
    value = 7;
    throw std::runtime_error("boom");
  }),
               std::runtime_error);

  auto reused = pool.acquire();
  ASSERT_TRUE(reused);
  EXPECT_EQ(*reused, 7);
}

TEST(PoolTest, ReleasesBackToPool) {
  Pool<int> pool([] { return std::make_unique<int>(0); });
  int *first_ptr = nullptr;
  {
    auto first = pool.acquire();
    first_ptr = first.get();
    *first = 7;
  }

  auto second = pool.acquire();
  EXPECT_EQ(second.get(), first_ptr);
  EXPECT_EQ(*second, 7);
}

TEST(PoolTest, ConcurrentBorrow) {
  std::atomic<size_t> created{0};
  Pool<size_t> pool([&created] {
    created.fetch_add(1, std::memory_order_relaxed);
    return std::make_unique<size_t>(0);
  });

  constexpr size_t kThreads = 8;
  constexpr size_t kIterations = 256;
  std::vector<std::thread> threads;
  threads.reserve(kThreads);

  for (size_t t = 0; t < kThreads; ++t) {
    threads.emplace_back([&pool, iterations = kIterations]() {
      for (size_t i = 0; i < iterations; ++i) {
        auto value = pool.acquire();
        (*value)++;
      }
    });
  }

  for (auto &thread : threads) {
    thread.join();
  }

  // Only a bounded number of items should be created even under contention.
  EXPECT_LE(created.load(std::memory_order_relaxed), kThreads);
}

} // namespace
} // namespace util
} // namespace fory
