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

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <functional>

namespace fory {

/// Duration: absolute length of time as nanoseconds
class Duration {
public:
  Duration() : ns_(0) {}

  explicit Duration(std::chrono::nanoseconds ns) : ns_(ns) {}

  std::chrono::nanoseconds to_chrono() const { return ns_; }

  int64_t count() const { return ns_.count(); }

  bool operator==(const Duration &other) const { return ns_ == other.ns_; }

  bool operator!=(const Duration &other) const { return !(*this == other); }

  bool operator<(const Duration &other) const { return ns_ < other.ns_; }

private:
  std::chrono::nanoseconds ns_;
};

/// Timestamp: point in time as nanoseconds since Unix epoch (Jan 1, 1970 UTC)
class Timestamp {
public:
  using ChronoType = std::chrono::time_point<std::chrono::system_clock,
                                             std::chrono::nanoseconds>;
  Timestamp() : tp_() {}

  explicit Timestamp(ChronoType tp) : tp_(tp) {}

  explicit Timestamp(std::chrono::nanoseconds ns) : tp_(ns) {}

  ChronoType to_chrono() const { return tp_; }

  std::chrono::nanoseconds time_since_epoch() const {
    return tp_.time_since_epoch();
  }

  bool operator==(const Timestamp &other) const { return tp_ == other.tp_; }

  bool operator!=(const Timestamp &other) const { return !(*this == other); }

  bool operator<(const Timestamp &other) const { return tp_ < other.tp_; }

private:
  ChronoType tp_;
};

/// Date: naive date without timezone as days since Unix epoch
class Date {
public:
  Date() : days_since_epoch_(0) {}
  explicit Date(int32_t days) : days_since_epoch_(days) {}

  int32_t days_since_epoch() const { return days_since_epoch_; }

  bool operator==(const Date &other) const {
    return days_since_epoch_ == other.days_since_epoch_;
  }

  bool operator!=(const Date &other) const { return !(*this == other); }

  bool operator<(const Date &other) const {
    return days_since_epoch_ < other.days_since_epoch_;
  }

private:
  int32_t days_since_epoch_; // Days since Jan 1, 1970 UTC
};

namespace serialization {

// Keep legacy serialization names available with the carrier types
using Duration = ::fory::Duration;
using Timestamp = ::fory::Timestamp;
using Date = ::fory::Date;

} // namespace serialization
} // namespace fory

// ============================================================================
// std::hash specializations for Fory temporal carrier types
// so they can be used as key types in unordered_map
//
// Hash inputs use the canonical numeric representation:
//   Date      -> days since epoch (int32_t)
//   Duration  -> total nanoseconds (int64_t)
//   Timestamp -> epoch nanoseconds (int64_t)
// ============================================================================

namespace std {

template <> struct hash<fory::Date> {
  size_t operator()(const fory::Date &d) const noexcept {
    return hash<int32_t>{}(d.days_since_epoch());
  }
};

template <> struct hash<fory::Duration> {
  size_t operator()(const fory::Duration &d) const noexcept {
    return hash<int64_t>{}(d.count());
  }
};

template <> struct hash<fory::Timestamp> {
  size_t operator()(const fory::Timestamp &t) const noexcept {
    return hash<int64_t>{}(t.time_since_epoch().count());
  }
};

} // namespace std
