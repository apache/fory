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
#include <sstream>
#include <string>
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

  const std::vector<std::streamsize> &read_sizes() const { return read_sizes_; }

protected:
  std::streamsize xsgetn(char *s, std::streamsize count) override {
    read_sizes_.push_back(count);
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
  std::vector<std::streamsize> read_sizes_;
};

class OneByteIStream : public std::istream {
public:
  explicit OneByteIStream(std::vector<uint8_t> data)
      : std::istream(nullptr), buf_(std::move(data)) {
    rdbuf(&buf_);
  }

  const std::vector<std::streamsize> &read_sizes() const {
    return buf_.read_sizes();
  }

private:
  OneByteStreamBuf buf_;
};

class CountingOutputStream final : public OutputStream {
public:
  Result<void, Error> write_to_stream(const uint8_t *src,
                                      uint32_t length) override {
    write_calls_++;
    if (length == 0) {
      return Result<void, Error>();
    }
    data_.insert(data_.end(), src, src + length);
    return Result<void, Error>();
  }

  Result<void, Error> flush_stream() override {
    flush_calls_++;
    return Result<void, Error>();
  }

  const std::vector<uint8_t> &data() const { return data_; }
  uint32_t write_calls() const { return write_calls_; }
  uint32_t flush_calls() const { return flush_calls_; }

private:
  std::vector<uint8_t> data_;
  uint32_t write_calls_ = 0;
  uint32_t flush_calls_ = 0;
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
  uint32_t actual_bytes_written =
      buffer->put_var_uint32_unchecked(start_offset, value);
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
  uint32_t actual_bytes_written =
      buffer->put_var_uint64_unchecked(start_offset, value);
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

TEST(Buffer, VarUint36SmallUsesVarUint64) {
  constexpr uint64_t kFirstSixByteValue = uint64_t{1} << 35;
  constexpr uint64_t kMaxValue = (uint64_t{1} << 36) - 1;
  const std::vector<uint64_t> values = {0x7f, 0x80, kFirstSixByteValue,
                                        kMaxValue};

  for (uint64_t value : values) {
    std::shared_ptr<Buffer> expected;
    std::shared_ptr<Buffer> actual;
    ASSERT_TRUE(allocate_buffer(32, &expected));
    ASSERT_TRUE(allocate_buffer(32, &actual));
    expected->write_var_uint64(value);
    actual->write_var_uint36_small(value);

    ASSERT_EQ(actual->writer_index(), expected->writer_index());
    EXPECT_EQ(
        std::memcmp(actual->data(), expected->data(), actual->writer_index()),
        0);

    Error error;
    EXPECT_EQ(actual->read_var_uint36_small(error), value);
    ASSERT_TRUE(error.ok()) << error.to_string();
    EXPECT_EQ(actual->reader_index(), actual->writer_index());

    std::vector<uint8_t> exact(actual->data(),
                               actual->data() + actual->writer_index());
    Buffer exact_buffer(exact);
    EXPECT_EQ(exact_buffer.read_var_uint36_small(error), value);
    ASSERT_TRUE(error.ok()) << error.to_string();
    EXPECT_EQ(exact_buffer.reader_index(), exact.size());
  }
}

TEST(Buffer, WriterRangeRejectsOverflow) {
  std::shared_ptr<Buffer> buffer;
  ASSERT_TRUE(allocate_buffer(8, &buffer));

  buffer->writer_index(8);
  EXPECT_DEATH(buffer->writer_index(9), "");

  buffer->writer_index(1);
  EXPECT_DEATH(
      buffer->increase_writer_index(std::numeric_limits<uint32_t>::max()), "");
  EXPECT_DEATH(buffer->grow(std::numeric_limits<uint32_t>::max()), "");

  buffer->writer_index(8);
  buffer->grow(1);
  buffer->increase_writer_index(1);
  EXPECT_EQ(buffer->writer_index(), 9U);
}

TEST(Buffer, OffsetRangeRejectsOverflow) {
  std::shared_ptr<Buffer> buffer;
  ASSERT_TRUE(allocate_buffer(8, &buffer));
  uint32_t bytes_read = 0;

  EXPECT_DEATH(
      (void)buffer->get<uint64_t>(std::numeric_limits<uint32_t>::max()), "");
  EXPECT_DEATH(buffer->get_int24(std::numeric_limits<uint32_t>::max()), "");
  EXPECT_DEATH(buffer->put_int24(std::numeric_limits<uint32_t>::max(), 1), "");

  EXPECT_DEATH(buffer->get_tagged_uint64(std::numeric_limits<uint32_t>::max(),
                                         &bytes_read),
               "");
  buffer->unsafe_put<uint32_t>(0, 1);
  EXPECT_DEATH(buffer->get_tagged_int64(0, &bytes_read), "");
  const uint8_t byte = 1;
  EXPECT_DEATH(
      buffer->copy_from(std::numeric_limits<uint32_t>::max(), &byte, 0, 1), "");
}

TEST(Buffer, RepresentedCopyChecksExtents) {
  std::shared_ptr<Buffer> source;
  std::shared_ptr<Buffer> destination;
  ASSERT_TRUE(allocate_buffer(4, &source));
  ASSERT_TRUE(allocate_buffer(2, &destination));

  EXPECT_DEATH(source->copy(3, 2, destination), "");
  EXPECT_DEATH(source->copy(0, 4, destination), "");

  Buffer destination_ref(destination->data(), destination->size(), false);
  EXPECT_DEATH(source->copy(3, 2, destination_ref), "");
  EXPECT_DEATH(source->copy(0, 4, destination_ref), "");

  source->unsafe_put<uint16_t>(0, 0x0201);
  source->copy(0, 2, destination);
  EXPECT_EQ(destination->get<uint16_t>(0), 0x0201);
}

TEST(Buffer, RepresentedCopySupportsOverlap) {
  std::vector<uint8_t> bytes = {1, 2, 3, 4, 5};
  Buffer source(bytes);
  auto destination = std::make_shared<Buffer>(bytes.data() + 1, 4, false);
  source.copy(0, 4, destination);
  EXPECT_EQ(bytes, (std::vector<uint8_t>{1, 1, 2, 3, 4}));

  bytes = {1, 2, 3, 4, 5};
  Buffer source_ref(bytes);
  Buffer destination_ref(bytes.data() + 1, 4, false);
  source_ref.copy(0, 4, destination_ref);
  EXPECT_EQ(bytes, (std::vector<uint8_t>{1, 1, 2, 3, 4}));
}

TEST(Buffer, NegativeEqualsReturnsFalse) {
  std::vector<uint8_t> bytes = {1, 2, 3};
  Buffer first(bytes);
  Buffer second(bytes);

  EXPECT_FALSE(first.equals(second, -1));
  EXPECT_FALSE(first.equals(first, -1));
}

TEST(Buffer, EmptyRangeOperations) {
  Buffer first;
  Buffer second;
  std::vector<uint8_t> byte = {1};
  Buffer nonempty(byte);

  EXPECT_TRUE(first.equals(second));
  EXPECT_TRUE(first.equals(second, 0));
  EXPECT_TRUE(first.equals(nonempty, 0));

  auto shared_out = std::make_shared<Buffer>();
  first.copy(0, 0, shared_out);
  first.copy(0, 0, second);
  first.copy(0, 0, static_cast<uint8_t *>(nullptr));
  first.copy(0, 0, static_cast<uint8_t *>(nullptr),
             std::numeric_limits<uint32_t>::max());
  first.copy_from(std::numeric_limits<uint32_t>::max(), nullptr,
                  std::numeric_limits<uint32_t>::max(), 0);
  EXPECT_EQ(first.size(), 0U);
}

TEST(Buffer, TaggedSmallNegative) {
  std::shared_ptr<Buffer> buffer;
  ASSERT_TRUE(allocate_buffer(8, &buffer));

  EXPECT_EQ(buffer->put_tagged_int64_unchecked(0, -1), 4U);
  uint32_t bytes_read = 0;
  EXPECT_EQ(buffer->get_tagged_int64(0, &bytes_read), -1);
  EXPECT_EQ(bytes_read, 4U);

  constexpr uint64_t kMaxSmallUnsigned = 0x7fffffff;
  EXPECT_EQ(buffer->put_tagged_uint64_unchecked(0, kMaxSmallUnsigned), 4U);
  EXPECT_EQ(buffer->get_tagged_uint64(0, &bytes_read), kMaxSmallUnsigned);
  EXPECT_EQ(bytes_read, 4U);

  Buffer streaming;
  streaming.write_tagged_int64(-1);
  Error error;
  EXPECT_EQ(streaming.read_tagged_int64(error), -1);
  ASSERT_TRUE(error.ok()) << error.to_string();

  streaming.writer_index(0);
  ASSERT_TRUE(streaming.reader_index(0, error));
  streaming.write_tagged_uint64(kMaxSmallUnsigned);
  EXPECT_EQ(streaming.read_tagged_uint64(error), kMaxSmallUnsigned);
  ASSERT_TRUE(error.ok()) << error.to_string();
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
  StdInputStream stream(one_byte_stream, 8);
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
  StdInputStream stream(one_byte_stream, 2);
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
  StdInputStream stream(one_byte_stream, 2);
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
  StdInputStream stream(one_byte_stream, 2);
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

TEST(Buffer, StreamShrinkBufferBestEffortUsesConfiguredBufferSize) {
  constexpr uint32_t kConfiguredBufferSize = 32768;
  constexpr uint32_t kPayloadSize = kConfiguredBufferSize * 2;
  std::string payload(kPayloadSize, '\x7');
  std::istringstream source(payload);
  StdInputStream stream(source, kConfiguredBufferSize);
  Buffer reader(stream);
  Error error;

  reader.skip(5000, error);
  ASSERT_TRUE(error.ok()) << error.to_string();
  EXPECT_EQ(reader.reader_index(), 5000U);

  // Below configured input buffer size, shrink should be a no-op.
  reader.shrink_input_buffer();
  EXPECT_EQ(reader.reader_index(), 5000U);

  reader.skip(kConfiguredBufferSize, error);
  ASSERT_TRUE(error.ok()) << error.to_string();
  ASSERT_GT(reader.reader_index(), kConfiguredBufferSize);

  const uint32_t remaining_before = reader.remaining_size();
  reader.shrink_input_buffer();
  EXPECT_EQ(reader.reader_index(), 0U);
  EXPECT_EQ(reader.size(), remaining_before);
}

TEST(Buffer, StreamReadErrorWhenInsufficientData) {
  std::vector<uint8_t> raw{0x01, 0x02, 0x03};
  OneByteIStream one_byte_stream(raw);
  StdInputStream stream(one_byte_stream, 2);
  Buffer reader(stream);
  Error error;
  EXPECT_EQ(reader.read_uint32(error), 0U);
  EXPECT_FALSE(error.ok());
  EXPECT_EQ(error.code(), ErrorCode::BufferOutOfBound);
}

TEST(Buffer, StreamGrowthStaysGeometricAcrossReads) {
  std::string payload(8, '\x7');
  std::istringstream source(payload);
  StdInputStream stream(source, 4);
  Buffer reader(stream);
  Error error;

  for (uint32_t i = 0; i < 4; ++i) {
    EXPECT_EQ(reader.read_uint8(error), 7U);
    ASSERT_TRUE(error.ok()) << error.to_string();
  }
  EXPECT_EQ(reader.size(), 4U);

  EXPECT_EQ(reader.read_uint8(error), 7U);
  ASSERT_TRUE(error.ok()) << error.to_string();
  EXPECT_EQ(reader.size(), 8U);
}

TEST(Buffer, StreamFillDoubleGrowsFromBufferedBytes) {
  std::vector<uint8_t> raw(17, 0x7);
  OneByteIStream one_byte_stream(raw);
  StdInputStream stream(one_byte_stream, 4);

  auto fill_result = stream.fill_buffer(100);
  EXPECT_FALSE(fill_result.ok());
  ASSERT_FALSE(one_byte_stream.read_sizes().empty());
  EXPECT_EQ(one_byte_stream.read_sizes().front(), 4);
  EXPECT_LT(stream.get_buffer().size(), 100U);
  EXPECT_LE(stream.get_buffer().size(), 32U);
}

TEST(Buffer, OutputStreamThresholdFlushOnWriteBytes) {
  CountingOutputStream writer;
  Buffer *buffer = writer.get_buffer();
  ASSERT_NE(buffer, nullptr);

  std::vector<uint8_t> payload(5000, 7);
  buffer->write_bytes(payload.data(), static_cast<uint32_t>(payload.size()));

  EXPECT_EQ(buffer->writer_index(), 0U);
  EXPECT_EQ(writer.data().size(), payload.size());
  EXPECT_GE(writer.write_calls(), 1U);
}

TEST(Buffer, OutputStreamThresholdFlushCanBeTemporarilyDisabled) {
  CountingOutputStream writer;
  Buffer *buffer = writer.get_buffer();
  ASSERT_NE(buffer, nullptr);
  writer.enter_flush_barrier();

  std::vector<uint8_t> payload(5000, 7);
  buffer->write_bytes(payload.data(), static_cast<uint32_t>(payload.size()));

  EXPECT_EQ(buffer->writer_index(), payload.size());
  EXPECT_EQ(writer.data().size(), 0U);

  writer.exit_flush_barrier();
  writer.try_flush();
  ASSERT_FALSE(writer.has_error()) << writer.error().to_string();
  EXPECT_EQ(buffer->writer_index(), 0U);
  EXPECT_EQ(writer.data().size(), payload.size());
}

TEST(Buffer, OutputStreamForceFlush) {
  CountingOutputStream writer;
  Buffer *buffer = writer.get_buffer();
  ASSERT_NE(buffer, nullptr);

  std::vector<uint8_t> payload{1, 2, 3, 4, 5};
  buffer->write_bytes(payload.data(), static_cast<uint32_t>(payload.size()));
  EXPECT_EQ(buffer->writer_index(), payload.size());

  writer.force_flush();
  ASSERT_FALSE(writer.has_error()) << writer.error().to_string();
  EXPECT_EQ(buffer->writer_index(), 0U);
  EXPECT_EQ(writer.data(), payload);
  EXPECT_EQ(writer.flush_calls(), 1U);
}

TEST(Buffer, OutputStreamRebindDetachesPreviousBufferBacklink) {
  CountingOutputStream writer;
  std::shared_ptr<Buffer> first;
  std::shared_ptr<Buffer> second;
  allocate_buffer(16, &first);
  allocate_buffer(16, &second);

  first->bind_output_stream(&writer);
  second->bind_output_stream(&writer);
  EXPECT_FALSE(first->has_output_stream());
  EXPECT_TRUE(second->has_output_stream());

  writer.enter_flush_barrier();
  std::vector<uint8_t> second_payload(5000, 7);
  second->write_bytes(second_payload.data(),
                      static_cast<uint32_t>(second_payload.size()));
  EXPECT_EQ(second->writer_index(), second_payload.size());
  writer.exit_flush_barrier();

  std::vector<uint8_t> first_payload(5000, 3);
  first->write_bytes(first_payload.data(),
                     static_cast<uint32_t>(first_payload.size()));

  // A stale backlink on `first` would call try_flush() and flush `second`
  // because `second` is still the stream's active buffer.
  EXPECT_EQ(second->writer_index(), second_payload.size());
  EXPECT_EQ(writer.data().size(), 0U);
}
} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
