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

#include <atomic>
#include <thread>

namespace fory {
namespace util {

// Spin-based mutex tuned for extremely short critical sections. It avoids the
// heavier std::mutex and relies on cooperative yielding when contention occurs.
class SpinLock {
public:
  SpinLock() noexcept = default;

  void lock() noexcept {
    while (flag_.test_and_set(std::memory_order_acquire)) {
      std::this_thread::yield();
    }
  }

  bool try_lock() noexcept {
    return !flag_.test_and_set(std::memory_order_acquire);
  }

  void unlock() noexcept { flag_.clear(std::memory_order_release); }

private:
  std::atomic_flag flag_ = ATOMIC_FLAG_INIT;
};

// Helper that acquires a SpinLock on construction and releases it on scope
// exit, ensuring exception-safe ownership semantics.
class SpinLockGuard {
public:
  explicit SpinLockGuard(SpinLock &lock) noexcept : lock_(lock) {
    lock_.lock();
  }

  ~SpinLockGuard() { lock_.unlock(); }

  SpinLockGuard(const SpinLockGuard &) = delete;
  SpinLockGuard &operator=(const SpinLockGuard &) = delete;

private:
  SpinLock &lock_;
};

} // namespace util
} // namespace fory
