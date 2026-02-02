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

#include "fory/util/error.h"
#include "fory/util/result.h"
#include "gtest/gtest.h"

namespace fory {

// ===== Test Result<void, Error> =====

class ErrorTest : public ::testing::Test {};

TEST_F(ErrorTest, BasicOK) {
  Result<void, Error> ok;
  ASSERT_TRUE(ok.ok());
  ASSERT_TRUE(ok.has_value());
  ASSERT_TRUE(static_cast<bool>(ok));
}

TEST_F(ErrorTest, BasicError) {
  Result<void, Error> err = Unexpected(Error::invalid("test error"));
  ASSERT_FALSE(err.ok());
  ASSERT_FALSE(err.has_value());
  ASSERT_EQ(err.error().code(), ErrorCode::Invalid);
  ASSERT_EQ(err.error().message(), "test error");
}

TEST_F(ErrorTest, ErrorTypes) {
  auto oom = Unexpected(Error::out_of_memory("no memory"));
  Result<void, Error> s1 = oom;
  ASSERT_EQ(s1.error().code(), ErrorCode::OutOfMemory);

  auto oob = Unexpected(Error::out_of_bound("index too large"));
  Result<void, Error> s2 = oob;
  ASSERT_EQ(s2.error().code(), ErrorCode::OutOfBound);

  auto type_err = Unexpected(Error::type_error("wrong type"));
  Result<void, Error> s3 = type_err;
  ASSERT_EQ(s3.error().code(), ErrorCode::TypeError);

  auto io_err = Unexpected(Error::io_error("read failed"));
  Result<void, Error> s4 = io_err;
  ASSERT_EQ(s4.error().code(), ErrorCode::IOError);
}

TEST_F(ErrorTest, CopyAndMove) {
  Result<void, Error> err1 = Unexpected(Error::key_error("key not found"));
  Result<void, Error> err2 = err1; // copy
  ASSERT_FALSE(err2.ok());
  ASSERT_EQ(err2.error().code(), ErrorCode::KeyError);

  Result<void, Error> err3 = std::move(err1); // Move
  ASSERT_FALSE(err3.ok());
  ASSERT_EQ(err3.error().code(), ErrorCode::KeyError);
}

TEST_F(ErrorTest, StringConversions) {
  Error err = Error::invalid("test");
  ASSERT_EQ(err.code_as_string(), "Invalid");

  ErrorCode code = Error::string_to_code("Invalid");
  ASSERT_EQ(code, ErrorCode::Invalid);

  code = Error::string_to_code("foobar");
  ASSERT_EQ(code, ErrorCode::UnknownError);
}

TEST_F(ErrorTest, BasicErrorCreation) {
  Error err1 = Error::invalid("test error");
  ASSERT_EQ(err1.code(), ErrorCode::Invalid);
  ASSERT_EQ(err1.message(), "test error");

  // Test copy constructor
  Error err2 = err1;
  ASSERT_EQ(err2.code(), ErrorCode::Invalid);
  ASSERT_EQ(err2.message(), "test error");

  // Test move constructor
  Error err3 = std::move(err1);
  ASSERT_EQ(err3.code(), ErrorCode::Invalid);
  ASSERT_EQ(err3.message(), "test error");
}

TEST_F(ErrorTest, ResultWithError) {
  Result<int, Error> result1 = Unexpected(Error::type_error("wrong type"));
  ASSERT_FALSE(result1.ok());
  ASSERT_EQ(result1.error().code(), ErrorCode::TypeError);

  // Test copy
  Result<int, Error> result2 = result1;
  ASSERT_FALSE(result2.ok());
  ASSERT_EQ(result2.error().code(), ErrorCode::TypeError);

  // Test move
  Result<int, Error> result3 = std::move(result1);
  ASSERT_FALSE(result3.ok());
  ASSERT_EQ(result3.error().code(), ErrorCode::TypeError);
}

TEST_F(ErrorTest, ErrorFactories) {
  auto err1 = Error::type_mismatch(1, 2);
  ASSERT_EQ(err1.code(), ErrorCode::TypeMismatch);

  auto err2 = Error::buffer_out_of_bound(10, 20, 25);
  ASSERT_EQ(err2.code(), ErrorCode::BufferOutOfBound);

  auto err3 = Error::encode_error("encode failed");
  ASSERT_EQ(err3.code(), ErrorCode::EncodeError);

  auto err4 = Error::invalid_data("bad data");
  ASSERT_EQ(err4.code(), ErrorCode::InvalidData);
}

TEST_F(ErrorTest, ErrorSize) {
  // Error contains bool ok_ + unique_ptr<ErrorState> state_
  // This gives us: 1 byte bool + 7 bytes padding + 8 bytes unique_ptr = 16
  // bytes on 64-bit systems.
  ASSERT_EQ(sizeof(Error), sizeof(void *) + sizeof(void *));
}

} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
