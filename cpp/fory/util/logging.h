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

#include <cstring>
#include <iosfwd>
#include <iostream>
#include <string>

#include "fory/util/macros.h"

namespace fory {

/// This function returns the current call stack information.
std::string get_call_trace();

// Simple logging implementation to avoid introduce glog dependency.

enum class ForyLogLevel {
  FORY_DEBUG = -1,
  FORY_INFO = 0,
  FORY_WARNING = 1,
  FORY_ERROR = 2,
  FORY_FATAL = 3
};

#define FORY_LOG_INTERNAL(level)                                               \
  ::fory::ForyLog(__FILE__, __LINE__, ::fory::ForyLogLevel::level)

#define FORY_LOG_ENABLED(level)                                                \
  ::fory::ForyLog::is_level_enabled(::fory::ForyLogLevel::level)

#define FORY_LOG(level)                                                        \
  if (FORY_LOG_ENABLED(level))                                                 \
  FORY_LOG_INTERNAL(level)

#define FORY_CHECK(condition)                                                  \
  if (!(condition))                                                            \
  FORY_LOG_INTERNAL(FORY_FATAL) << " Check failed: " #condition " "

#define FORY_CHECK_OP(left, op, right)                                         \
  do {                                                                         \
    const auto &_left_ = left;                                                 \
    const auto &_right_ = right;                                               \
    FORY_CHECK((_left_ op _right_)) << " " << _left_ << " vs " << _right_;     \
  } while (0)

#define FORY_CHECK_EQ(left, right) FORY_CHECK_OP(left, ==, right)
#define FORY_CHECK_NE(left, right) FORY_CHECK_OP(left, !=, right)
#define FORY_CHECK_LE(left, right) FORY_CHECK_OP(left, <=, right)
#define FORY_CHECK_LT(left, right) FORY_CHECK_OP(left, <, right)
#define FORY_CHECK_GE(left, right) FORY_CHECK_OP(left, >=, right)
#define FORY_CHECK_GT(left, right) FORY_CHECK_OP(left, >, right)

class ForyLog {
public:
  ForyLog(const char *file_name, int line_number, ForyLogLevel severity);

  virtual ~ForyLog();

  template <typename T> ForyLog &operator<<(const T &t) {
    stream() << t;
    return *this;
  };

  /// Return whether or not the log level is enabled in current setting.
  ///
  /// \param log_level The input log level to test.
  /// \return True if input log level is not lower than the threshold.
  static bool is_level_enabled(ForyLogLevel log_level);

  static ForyLogLevel get_log_level();

protected:
  virtual std::ostream &stream() { return std::cerr; };

private:
  /// log level.
  ForyLogLevel severity_;
};

} // namespace fory
