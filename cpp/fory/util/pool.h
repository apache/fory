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

#pragma once

#include "fory/util/lock.h"

#include <cassert>
#include <functional>
#include <memory>
#include <type_traits>
#include <utility>
#include <vector>

namespace fory {
namespace util {

// Thread-safe object pool that hands out scoped unique_ptr handles with a
// custom deleter so instances automatically return to the pool.
template <typename T> class Pool {
public:
  using Factory = std::function<std::unique_ptr<T>()>;

  explicit Pool(Factory factory) : factory_(std::move(factory)) {
    assert(factory_);
  }

  Pool(const Pool &) = delete;
  Pool &operator=(const Pool &) = delete;
  Pool(Pool &&) = delete;
  Pool &operator=(Pool &&) = delete;

  struct Releaser {
    Pool *pool;
    void operator()(T *ptr) const noexcept { pool->release(ptr); }
  };

  using Ptr = std::unique_ptr<T, Releaser>;

  // Fetch an instance from the pool, constructing via the factory if needed.
  Ptr acquire() { return Ptr(acquire_raw().release(), Releaser{this}); }

  template <typename Handler>
  auto borrow(Handler &&handler)
      -> decltype(std::forward<Handler>(handler)(std::declval<T &>())) {
    // Provide a convenient scoped access pattern while guaranteeing the object
    // is returned even if the handler throws.
    Ptr item = acquire();
    using ResultType =
        decltype(std::forward<Handler>(handler)(std::declval<T &>()));
    if constexpr (std::is_void_v<ResultType>) {
      std::forward<Handler>(handler)(*item);
      return;
    } else {
      ResultType result = std::forward<Handler>(handler)(*item);
      return result;
    }
  }

private:
  std::unique_ptr<T> acquire_raw() {
    std::unique_ptr<T> item;
    {
      SpinLockGuard guard(lock_);
      if (!items_.empty()) {
        item = std::move(items_.back());
        items_.pop_back();
      }
    }
    if (!item) {
      item = factory_();
    }
    return item;
  }

  void release(T *ptr) noexcept {
    if (ptr == nullptr) {
      return;
    }
    std::unique_ptr<T> item(ptr);
    SpinLockGuard guard(lock_);
    items_.push_back(std::move(item));
  }

  SpinLock lock_;
  std::vector<std::unique_ptr<T>> items_;
  Factory factory_;
};

} // namespace util
} // namespace fory
