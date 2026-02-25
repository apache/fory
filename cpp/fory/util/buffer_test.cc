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
#include <limits>
#include <utility>
#include <vector>

#include "gtest/gtest.h"

#include "fory/util/buffer.h"

namespace fory {

TEST(Buffer, to_string) {
  std::shared_ptr<Buffer> buffer;
  allocate_buffer(16, &buffer);
  for (int i = 0; i < 16; ++i) {
    buffer->unsafe_put_byte<int8_t>(i, static_cast<int8_t>('a' + i));
  }
  EXPECT_EQ(buffer->to_string(), "abcdefghijklmnop");

  float f = 1.11;
  buffer->unsafe_put<float>(0, f);
  EXPECT_EQ(buffer->get<float>(0), f);
}

void check_var_uint32(int32_t start_offset, std::shared_ptr<Buffer> buffer,
                      int32_t value, uint32_t bytes_written) {
  uint32_t actual_bytes_written = buffer->put_var_uint32(start_offset, value);
  EXPECT_EQ(actual_bytes_written, bytes_written);
  uint32_t read_bytes_length;
  int32_t var_int = buffer->get_var_uint32(start_offset, &read_bytes_length);
  EXPECT_EQ(value, var_int);
  EXPECT_EQ(read_bytes_length, bytes_written);
}

TEST(Buffer, TestVarUint) {
  std::shared_ptr<Buffer> buffer;
  allocate_buffer(64, &buffer);
  for (int i = 0; i < 32; ++i) {
    check_var_uint32(i, buffer, 1, 1);
    check_var_uint32(i, buffer, 1 << 6, 1);
    check_var_uint32(i, buffer, 1 << 7, 2);
    check_var_uint32(i, buffer, 1 << 13, 2);
    check_var_uint32(i, buffer, 1 << 14, 3);
    check_var_uint32(i, buffer, 1 << 20, 3);
    check_var_uint32(i, buffer, 1 << 21, 4);
    check_var_uint32(i, buffer, 1 << 27, 4);
    check_var_uint32(i, buffer, 1 << 28, 5);
    check_var_uint32(i, buffer, 1 << 30, 5);
  }
}

void check_var_uint64(int32_t start_offset, std::shared_ptr<Buffer> buffer,
                      uint64_t value, uint32_t bytes_written) {
  uint32_t actual_bytes_written = buffer->put_var_uint64(start_offset, value);
  EXPECT_EQ(actual_bytes_written, bytes_written);
  uint32_t read_bytes_length;
  uint64_t var_int = buffer->get_var_uint64(start_offset, &read_bytes_length);
  EXPECT_EQ(value, var_int);
  EXPECT_EQ(read_bytes_length, bytes_written);
}

TEST(Buffer, TestVarUint64) {
  std::shared_ptr<Buffer> buffer;
  allocate_buffer(256, &buffer);
  const std::vector<std::pair<uint64_t, uint32_t>> cases = {
      {0, 1},
      {1, 1},
      {127, 1},
      {128, 2},
      {16383, 2},
      {16384, 3},
      {2097151, 3},
      {2097152, 4},
      {268435455, 4},
      {268435456, 5},
      {34359738367ULL, 5},
      {34359738368ULL, 6},
      {4398046511103ULL, 6},
      {4398046511104ULL, 7},
      {562949953421311ULL, 7},
      {562949953421312ULL, 8},
      {72057594037927935ULL, 8},
      {72057594037927936ULL, 9},
      {std::numeric_limits<uint64_t>::max(), 9},
  };
  for (int i = 0; i < 32; ++i) {
    for (const auto &entry : cases) {
      check_var_uint64(i, buffer, entry.first, entry.second);
    }
  }
}

TEST(Buffer, TestGetBytesAsInt64) {
  std::shared_ptr<Buffer> buffer;
  allocate_buffer(64, &buffer);
  buffer->unsafe_put<int32_t>(0, 100);
  int64_t result = -1;
  EXPECT_TRUE(buffer->get_bytes_as_int64(0, 0, &result).ok());
  EXPECT_EQ(result, 0);
  EXPECT_TRUE(buffer->get_bytes_as_int64(0, 1, &result).ok());
  EXPECT_EQ(result, 100);
}

TEST(Buffer, TestGetBytesAsInt64OutOfBound) {
  std::shared_ptr<Buffer> buffer;
  allocate_buffer(8, &buffer);
  int64_t result = -1;
  auto oob = buffer->get_bytes_as_int64(7, 2, &result);
  EXPECT_FALSE(oob.ok());
  auto invalid = buffer->get_bytes_as_int64(0, 9, &result);
  EXPECT_FALSE(invalid.ok());
}

TEST(Buffer, TestGetVarUint32Truncated) {
  std::vector<uint8_t> bytes = {0x80};
  Buffer buffer(bytes);
  uint32_t read_bytes = 123;
  uint32_t value = buffer.get_var_uint32(0, &read_bytes);
  EXPECT_EQ(value, 0U);
  EXPECT_EQ(read_bytes, 0U);

  Error error;
  uint32_t decoded = buffer.read_var_uint32(error);
  EXPECT_EQ(decoded, 0U);
  EXPECT_FALSE(error.ok());
  EXPECT_EQ(buffer.reader_index(), 0U);
}

TEST(Buffer, TestGetVarUint64Truncated) {
  std::vector<uint8_t> bytes(8, 0x80);
  Buffer buffer(bytes);
  uint32_t read_bytes = 123;
  uint64_t value = buffer.get_var_uint64(0, &read_bytes);
  EXPECT_EQ(value, 0ULL);
  EXPECT_EQ(read_bytes, 0U);

  Error error;
  uint64_t decoded = buffer.read_var_uint64(error);
  EXPECT_EQ(decoded, 0ULL);
  EXPECT_FALSE(error.ok());
  EXPECT_EQ(buffer.reader_index(), 0U);
}

TEST(Buffer, TestReadVarUint36SmallTruncated) {
  std::vector<uint8_t> bytes = {0x80, 0x80, 0x80, 0x80};
  Buffer buffer(bytes);

  Error error;
  uint64_t decoded = buffer.read_var_uint36_small(error);
  EXPECT_EQ(decoded, 0ULL);
  EXPECT_FALSE(error.ok());
  EXPECT_EQ(buffer.reader_index(), 0U);
}
} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
