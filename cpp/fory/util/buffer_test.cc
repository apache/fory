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
#include "fory/util/stream.h"

namespace fory {

class OneByteStreamBuf : public std::streambuf {
public:
  explicit OneByteStreamBuf(std::vector<uint8_t> data)
      : data_(std::move(data)), pos_(0) {}

protected:
  std::streamsize xsgetn(char *s, std::streamsize count) override {
    if (pos_ >= data_.size() || count <= 0) {
      return 0;
    }
    s[0] = static_cast<char>(data_[pos_]);
    ++pos_;
    return 1;
  }

  int_type underflow() override {
    if (pos_ >= data_.size()) {
      return traits_type::eof();
    }
    current_ = static_cast<char>(data_[pos_]);
    setg(&current_, &current_, &current_ + 1);
    return traits_type::to_int_type(current_);
  }

private:
  std::vector<uint8_t> data_;
  size_t pos_;
  char current_ = 0;
};

class OneByteIStream : public std::istream {
public:
  explicit OneByteIStream(std::vector<uint8_t> data)
      : std::istream(nullptr), buf_(std::move(data)) {
    rdbuf(&buf_);
  }

private:
  OneByteStreamBuf buf_;
};

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

TEST(Buffer, StreamReadFromOneByteSource) {
  std::vector<uint8_t> raw;
  raw.reserve(64);
  Buffer writer(raw);
  writer.write_uint32(0x01020304U);
  writer.write_int64(-1234567890LL);
  writer.write_var_uint32(300);
  writer.write_var_int64(-4567890123LL);
  writer.write_tagged_uint64(0x123456789ULL);
  writer.write_var_uint36_small(0x1FFFFULL);

  raw.resize(writer.writer_index());
  OneByteIStream one_byte_stream(raw);
  ForyInputStream stream(one_byte_stream, 8);
  Buffer reader(stream);
  Error error;

  EXPECT_EQ(reader.read_uint32(error), 0x01020304U);
  ASSERT_TRUE(error.ok()) << error.to_string();
  EXPECT_EQ(reader.read_int64(error), -1234567890LL);
  ASSERT_TRUE(error.ok()) << error.to_string();
  EXPECT_EQ(reader.read_var_uint32(error), 300U);
  ASSERT_TRUE(error.ok()) << error.to_string();
  EXPECT_EQ(reader.read_var_int64(error), -4567890123LL);
  ASSERT_TRUE(error.ok()) << error.to_string();
  EXPECT_EQ(reader.read_tagged_uint64(error), 0x123456789ULL);
  ASSERT_TRUE(error.ok()) << error.to_string();
  EXPECT_EQ(reader.read_var_uint36_small(error), 0x1FFFFULL);
  ASSERT_TRUE(error.ok()) << error.to_string();
}

TEST(Buffer, StreamGetAndReaderIndexFromOneByteSource) {
  std::vector<uint8_t> raw{0x11, 0x22, 0x33, 0x44, 0x55};
  OneByteIStream one_byte_stream(raw);
  ForyInputStream stream(one_byte_stream, 2);
  Buffer reader(stream);
  Error error;
  ASSERT_TRUE(reader.ensure_readable(4, error)) << error.to_string();

  EXPECT_EQ(reader.get<uint32_t>(0), 0x44332211U);
  reader.reader_index(4);
  EXPECT_EQ(reader.read_uint8(error), 0x55);
  ASSERT_TRUE(error.ok()) << error.to_string();
}

TEST(Buffer, StreamReadBytesAndSkipAdvanceReaderIndex) {
  std::vector<uint8_t> raw{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
  OneByteIStream one_byte_stream(raw);
  ForyInputStream stream(one_byte_stream, 2);
  Buffer reader(stream);
  Error error;
  uint8_t out[5] = {0};

  reader.read_bytes(out, 5, error);
  ASSERT_TRUE(error.ok()) << error.to_string();
  EXPECT_EQ(reader.reader_index(), 5U);
  EXPECT_EQ(out[0], 0U);
  EXPECT_EQ(out[4], 4U);

  reader.skip(3, error);
  ASSERT_TRUE(error.ok()) << error.to_string();
  EXPECT_EQ(reader.reader_index(), 8U);
  EXPECT_EQ(reader.read_uint8(error), 8U);
  ASSERT_TRUE(error.ok()) << error.to_string();
}

TEST(Buffer, StreamSkipAndUnread) {
  std::vector<uint8_t> raw{0x01, 0x02, 0x03, 0x04, 0x05};
  OneByteIStream one_byte_stream(raw);
  ForyInputStream stream(one_byte_stream, 2);
  auto fill_result = stream.fill_buffer(4);
  ASSERT_TRUE(fill_result.ok()) << fill_result.error().to_string();

  Buffer &view = stream.get_buffer();
  EXPECT_EQ(view.size(), 4U);
  EXPECT_EQ(view.reader_index(), 0U);

  auto skip_result = stream.skip(3);
  ASSERT_TRUE(skip_result.ok()) << skip_result.error().to_string();
  EXPECT_EQ(view.reader_index(), 3U);

  auto unread_result = stream.unread(2);
  ASSERT_TRUE(unread_result.ok()) << unread_result.error().to_string();
  EXPECT_EQ(view.reader_index(), 1U);

  skip_result = stream.skip(1);
  ASSERT_TRUE(skip_result.ok()) << skip_result.error().to_string();
  EXPECT_EQ(view.reader_index(), 2U);
}

TEST(Buffer, StreamReadErrorWhenInsufficientData) {
  std::vector<uint8_t> raw{0x01, 0x02, 0x03};
  OneByteIStream one_byte_stream(raw);
  ForyInputStream stream(one_byte_stream, 2);
  Buffer reader(stream);
  Error error;
  EXPECT_EQ(reader.read_uint32(error), 0U);
  EXPECT_FALSE(error.ok());
  EXPECT_EQ(error.code(), ErrorCode::BufferOutOfBound);
}
} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
