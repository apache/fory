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
#include "gtest/gtest.h"

namespace fory {

TEST(TimeTest, TestFormatTimePoint) {
  std::tm tm{};             // zero initialise
  tm.tm_year = 2022 - 1900; // 2020
  tm.tm_mon = 7 - 1;        // February
  tm.tm_mday = 2;           // 15th
  tm.tm_hour = 10;
  tm.tm_min = 20;
  tm.tm_sec = 20;
  tm.tm_isdst = 0; // Not daylight saving
  std::time_t t = std::mktime(&tm);
  auto tp = std::chrono::system_clock::from_time_t(t);
  EXPECT_EQ(format_time_point(tp), "2022-07-02 10:20:20,000");
  format_time_point(std::chrono::system_clock::now());
}

} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
