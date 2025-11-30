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

#include "fory/util/result.h"
#include "gtest/gtest.h"

namespace fory {

// ===== Test Result<T, Error> =====

class ResultTest : public ::testing::Test {};

TEST_F(ResultTest, BasicValue) {
  Result<int, Error> res(42);
  ASSERT_TRUE(res.ok());
  ASSERT_TRUE(res.has_value());
  ASSERT_EQ(res.value(), 42);
  ASSERT_EQ(*res, 42);
}

TEST_F(ResultTest, BasicError) {
  Result<int, Error> res = Unexpected(Error::invalid("invalid value"));
  ASSERT_FALSE(res.ok());
  ASSERT_FALSE(res.has_value());
  ASSERT_EQ(res.error().code(), ErrorCode::Invalid);
}

TEST_F(ResultTest, ValueOr) {
  Result<int, Error> ok_res(10);
  ASSERT_EQ(ok_res.value_or(5), 10);

  Result<int, Error> err_res = Unexpected(Error::invalid("error"));
  ASSERT_EQ(err_res.value_or(5), 5);
}

TEST_F(ResultTest, CopyValue) {
  Result<std::string, Error> res1(std::string("hello"));
  Result<std::string, Error> res2 = res1;

  ASSERT_TRUE(res2.ok());
  ASSERT_EQ(res2.value(), "hello");
  ASSERT_EQ(res1.value(), "hello"); // Original still valid
}

TEST_F(ResultTest, MoveValue) {
  Result<std::string, Error> res1(std::string("hello"));
  Result<std::string, Error> res2 = std::move(res1);

  ASSERT_TRUE(res2.ok());
  ASSERT_EQ(res2.value(), "hello");
}

TEST_F(ResultTest, CopyError) {
  Result<int, Error> res1 = Unexpected(Error::key_error("not found"));
  Result<int, Error> res2 = res1;

  ASSERT_FALSE(res2.ok());
  ASSERT_EQ(res2.error().code(), ErrorCode::KeyError);
  ASSERT_EQ(res2.error().message(), "not found");
}

TEST_F(ResultTest, MoveError) {
  Result<int, Error> res1 = Unexpected(Error::key_error("not found"));
  Result<int, Error> res2 = std::move(res1);

  ASSERT_FALSE(res2.ok());
  ASSERT_EQ(res2.error().code(), ErrorCode::KeyError);
}

TEST_F(ResultTest, PointerAccess) {
  Result<std::string, Error> res(std::string("test"));
  ASSERT_EQ(res->size(), 4);
  ASSERT_EQ(res->length(), 4);
}

TEST_F(ResultTest, ComplexType) {
  struct Data {
    int x;
    std::string name;
  };

  Result<Data, Error> res(Data{42, "test"});
  ASSERT_TRUE(res.ok());
  ASSERT_EQ(res->x, 42);
  ASSERT_EQ(res->name, "test");
}

TEST_F(ResultTest, ErrorFactoryMethods) {
  Result<int, Error> oom = Unexpected(Error::out_of_memory("no memory"));
  ASSERT_EQ(oom.error().code(), ErrorCode::OutOfMemory);

  Result<int, Error> oob = Unexpected(Error::out_of_bound("index too large"));
  ASSERT_EQ(oob.error().code(), ErrorCode::OutOfBound);

  Result<int, Error> type_err = Unexpected(Error::type_error("wrong type"));
  ASSERT_EQ(type_err.error().code(), ErrorCode::TypeError);

  Result<int, Error> io_err = Unexpected(Error::io_error("read failed"));
  ASSERT_EQ(io_err.error().code(), ErrorCode::IOError);

  Result<int, Error> encode_err =
      Unexpected(Error::encode_error("encode failed"));
  ASSERT_EQ(encode_err.error().code(), ErrorCode::EncodeError);

  Result<int, Error> invalid_data = Unexpected(Error::invalid_data("bad data"));
  ASSERT_EQ(invalid_data.error().code(), ErrorCode::InvalidData);

  Result<int, Error> type_mismatch = Unexpected(Error::type_mismatch(1, 2));
  ASSERT_EQ(type_mismatch.error().code(), ErrorCode::TypeMismatch);

  Result<int, Error> buf_oob =
      Unexpected(Error::buffer_out_of_bound(10, 20, 25));
  ASSERT_EQ(buf_oob.error().code(), ErrorCode::BufferOutOfBound);
}

TEST_F(ResultTest, UnexpectedHelper) {
  // Test that Unexpected can be used to return errors
  auto make_error = []() -> Result<int, Error> {
    return Unexpected(Error::invalid("test"));
  };

  auto res = make_error();
  ASSERT_FALSE(res.ok());
  ASSERT_EQ(res.error().code(), ErrorCode::Invalid);
}

TEST_F(ResultTest, Assignment) {
  // Value to value
  Result<int, Error> r1(10);
  Result<int, Error> r2(20);
  r1 = r2;
  ASSERT_TRUE(r1.ok());
  ASSERT_EQ(r1.value(), 20);

  // Error to error
  Result<int, Error> r3 = Unexpected(Error::invalid("err1"));
  Result<int, Error> r4 = Unexpected(Error::invalid("err2"));
  r3 = r4;
  ASSERT_FALSE(r3.ok());
  ASSERT_EQ(r3.error().message(), "err2");

  // Value to error
  Result<int, Error> r5(10);
  Result<int, Error> r6 = Unexpected(Error::invalid("err"));
  r5 = r6;
  ASSERT_FALSE(r5.ok());
  ASSERT_EQ(r5.error().code(), ErrorCode::Invalid);

  // Error to value
  Result<int, Error> r7 = Unexpected(Error::invalid("err"));
  Result<int, Error> r8(42);
  r7 = r8;
  ASSERT_TRUE(r7.ok());
  ASSERT_EQ(r7.value(), 42);
}

// ===== Test Result<T&, E> =====

TEST_F(ResultTest, ReferenceBasic) {
  int x = 42;
  Result<int &, Error> res(x);
  ASSERT_TRUE(res.ok());
  ASSERT_EQ(res.value(), 42);
  ASSERT_EQ(&res.value(), &x);

  // Modify through reference
  res.value() = 100;
  ASSERT_EQ(x, 100);
}

TEST_F(ResultTest, ReferenceError) {
  Result<int &, Error> res = Unexpected(Error::invalid("not found"));
  ASSERT_FALSE(res.ok());
  ASSERT_EQ(res.error().code(), ErrorCode::Invalid);
}

TEST_F(ResultTest, ReferenceValueOr) {
  int x = 10, fallback = 99;
  Result<int &, Error> ok_res(x);
  ASSERT_EQ(&ok_res.value_or(fallback), &x);

  Result<int &, Error> err_res = Unexpected(Error::invalid("error"));
  ASSERT_EQ(&err_res.value_or(fallback), &fallback);
}

TEST_F(ResultTest, ReferenceCopyReseats) {
  int x = 1, y = 2;
  Result<int &, Error> r1(x);
  Result<int &, Error> r2(y);
  r1 = r2;
  ASSERT_EQ(&r1.value(), &y);
}

TEST_F(ResultTest, ReferenceConstRef) {
  const int x = 42;
  Result<const int &, Error> res(x);
  ASSERT_TRUE(res.ok());
  ASSERT_EQ(res.value(), 42);
  ASSERT_EQ(&res.value(), &x);
}

TEST_F(ResultTest, ReferenceArrowOperator) {
  std::string s = "hello";
  Result<std::string &, Error> res(s);
  ASSERT_EQ(res->size(), 5);
  res->append(" world");
  ASSERT_EQ(s, "hello world");
}

// ===== Test macros =====

Result<void, Error> helper_ok() { return Result<void, Error>(); }

Result<void, Error> helper_error() {
  return Unexpected(Error::invalid("test error"));
}

Result<int, Error> compute_ok() { return 42; }

Result<int, Error> compute_error() {
  return Unexpected(Error::invalid("computation failed"));
}

TEST_F(ResultTest, ReturnNotOkMacro) {
  auto test_fn = []() -> Result<void, Error> {
    FORY_RETURN_NOT_OK(helper_ok());
    return Result<void, Error>();
  };

  auto result = test_fn();
  ASSERT_TRUE(result.ok());

  auto test_fn_error = []() -> Result<void, Error> {
    FORY_RETURN_NOT_OK(helper_error());
    return Result<void, Error>(); // Should not reach here
  };

  auto result_error = test_fn_error();
  ASSERT_FALSE(result_error.ok());
}

TEST_F(ResultTest, AssignOrReturnMacro) {
  auto test_fn = []() -> Result<int, Error> {
    int value;
    FORY_ASSIGN_OR_RETURN(value, compute_ok());
    EXPECT_EQ(value, 42);
    return value + 1;
  };

  auto result = test_fn();
  ASSERT_TRUE(result.ok());
  ASSERT_EQ(result.value(), 43);

  auto test_fn_error = []() -> Result<int, Error> {
    int value;
    FORY_ASSIGN_OR_RETURN(value, compute_error());
    return value; // Should not reach here
  };

  auto result_error = test_fn_error();
  ASSERT_FALSE(result_error.ok());
  ASSERT_EQ(result_error.error().code(), ErrorCode::Invalid);
}

} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
