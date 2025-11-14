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

#include "fory/util/time_util.h"
#include <chrono>
#include <cstdio>
#include <ctime>
#include <string>

namespace fory {

using std::chrono::system_clock;

std::string FormatTimePoint(system_clock::time_point tp) {
  time_t raw_time = system_clock::to_time_t(tp);
  // unnecessary to release tm, it's created by localtime and every thread will
  // have one.
  tm *timeinfo = std::localtime(&raw_time);

  char datetime_str[64] = {};
  size_t written_size = std::strftime(datetime_str, sizeof(datetime_str),
                                      "%Y-%m-%d %H:%M:%S,", timeinfo);
  std::chrono::milliseconds ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(
          tp.time_since_epoch());
  const int ms_value = static_cast<int>(ms.count() % 1000);
  std::snprintf(datetime_str + written_size,
                sizeof(datetime_str) - written_size, "%03d", ms_value);
  return datetime_str;
}

} // namespace fory
