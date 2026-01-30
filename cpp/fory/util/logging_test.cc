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

#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "fory/util/logging.h"
#include "gtest/gtest.h"

namespace fory {

TEST(PrintLogTest, BasicLog) {
  FORY_LOG(FORY_INFO) << "test info";
  ASSERT_DEATH(FORY_LOG(FORY_FATAL) << "test fatal",
               "\\[.*\\] FATAL cpp/fory/util/logging_test.cc:.*: test fatal");
}

TEST(PrintLogTest, TestCheckOp) {
  int i = 1;
  FORY_CHECK_EQ(i, 1);
  ASSERT_DEATH(FORY_CHECK_EQ(i, 2), "1 vs 2");

  FORY_CHECK_NE(i, 0);
  ASSERT_DEATH(FORY_CHECK_NE(i, 1), "1 vs 1");

  FORY_CHECK_LE(i, 1);
  ASSERT_DEATH(FORY_CHECK_LE(i, 0), "1 vs 0");

  FORY_CHECK_LT(i, 2);
  ASSERT_DEATH(FORY_CHECK_LT(i, 1), "1 vs 1");

  FORY_CHECK_GE(i, 1);
  ASSERT_DEATH(FORY_CHECK_GE(i, 2), "1 vs 2");

  FORY_CHECK_GT(i, 0);
  ASSERT_DEATH(FORY_CHECK_GT(i, 1), "1 vs 1");

  int j = 0;
  FORY_CHECK_NE(i, j);
  ASSERT_DEATH(FORY_CHECK_EQ(i, j), "1 vs 0");
}

std::string test_function_level0() {
  std::string call_trace = get_call_trace();
  return call_trace;
}

std::string test_function_level1() { return test_function_level0(); }

std::string test_function_level2() { return test_function_level1(); }

#ifndef _WIN32
TEST(PrintLogTest, CallstackTraceTest) {
  auto ret = test_function_level2();
  FORY_LOG(FORY_INFO) << "stack trace:\n" << ret;
  // work for linux
  // EXPECT_TRUE(ret.find("test_function_level0") != std::string::npos);
  // work for mac
  // EXPECT_TRUE(ret.find("get_call_trace") != std::string::npos);
  EXPECT_TRUE(ret.find("fory") != std::string::npos);
  EXPECT_TRUE(ret.find("PrintLogTest") != std::string::npos);
}
#endif

} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
