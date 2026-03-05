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

#include <cstdint>
#include <istream>
#include <map>
#include <memory>
#include <sstream>
#include <streambuf>
#include <string>
#include <utility>
#include <vector>

#include "gtest/gtest.h"

#include "fory/serialization/fory.h"
#include "fory/util/stream.h"

namespace fory {
namespace serialization {
namespace test {

struct StreamPoint {
  int32_t x;
  int32_t y;

  bool operator==(const StreamPoint &other) const {
    return x == other.x && y == other.y;
  }

  FORY_STRUCT(StreamPoint, x, y);
};

struct StreamEnvelope {
  std::string name;
  std::vector<int32_t> values;
  std::map<std::string, int64_t> metrics;
  StreamPoint point;
  bool active;

  bool operator==(const StreamEnvelope &other) const {
    return name == other.name && values == other.values &&
           metrics == other.metrics && point == other.point &&
           active == other.active;
  }

  FORY_STRUCT(StreamEnvelope, name, values, metrics, point, active);
};

struct SharedIntPair {
  std::shared_ptr<int32_t> first;
  std::shared_ptr<int32_t> second;

  FORY_STRUCT(SharedIntPair, first, second);
};

class OneByteStreamBuf final : public std::streambuf {
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

class OneByteIStream final : public std::istream {
public:
  explicit OneByteIStream(std::vector<uint8_t> data)
      : std::istream(nullptr), buf_(std::move(data)) {
    rdbuf(&buf_);
  }

private:
  OneByteStreamBuf buf_;
};

class OneByteOutputStreamBuf final : public std::streambuf {
public:
  OneByteOutputStreamBuf() = default;

  const std::vector<uint8_t> &data() const { return data_; }

protected:
  std::streamsize xsputn(const char *s, std::streamsize count) override {
    if (count <= 0) {
      return 0;
    }
    data_.insert(data_.end(), reinterpret_cast<const uint8_t *>(s),
                 reinterpret_cast<const uint8_t *>(s + count));
    return count;
  }

  int_type overflow(int_type ch) override {
    if (traits_type::eq_int_type(ch, traits_type::eof())) {
      return traits_type::not_eof(ch);
    }
    data_.push_back(static_cast<uint8_t>(traits_type::to_char_type(ch)));
    return ch;
  }

private:
  std::vector<uint8_t> data_;
};

class OneByteOStream final : public std::ostream {
public:
  OneByteOStream() : std::ostream(nullptr) { rdbuf(&buf_); }

  std::vector<uint8_t> data() const { return buf_.data(); }

private:
  OneByteOutputStreamBuf buf_;
};

static inline void register_stream_types(Fory &fory) {
  uint32_t type_id = 1;
  fory.register_struct<StreamPoint>(type_id++);
  fory.register_struct<StreamEnvelope>(type_id++);
  fory.register_struct<SharedIntPair>(type_id++);
}

TEST(StreamSerializationTest, PrimitiveAndStringRoundTrip) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();

  auto number_bytes_result = fory.serialize<int64_t>(-9876543212345LL);
  ASSERT_TRUE(number_bytes_result.ok())
      << number_bytes_result.error().to_string();
  OneByteIStream number_source(std::move(number_bytes_result).value());
  StdInputStream number_stream(number_source, 8);
  auto number_result = fory.deserialize<int64_t>(number_stream);
  ASSERT_TRUE(number_result.ok()) << number_result.error().to_string();
  EXPECT_EQ(number_result.value(), -9876543212345LL);

  auto string_bytes_result = fory.serialize<std::string>("stream-hello-世界");
  ASSERT_TRUE(string_bytes_result.ok())
      << string_bytes_result.error().to_string();
  OneByteIStream string_source(std::move(string_bytes_result).value());
  StdInputStream string_stream(string_source, 8);
  auto string_result = fory.deserialize<std::string>(string_stream);
  ASSERT_TRUE(string_result.ok()) << string_result.error().to_string();
  EXPECT_EQ(string_result.value(), "stream-hello-世界");
}

TEST(StreamSerializationTest, StructRoundTrip) {
  auto fory = Fory::builder().xlang(true).track_ref(true).build();
  register_stream_types(fory);

  StreamEnvelope original{
      "payload-name",
      {1, 3, 5, 7, 9},
      {{"count", 5}, {"sum", 25}, {"max", 9}},
      {42, -7},
      true,
  };

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  OneByteIStream source(std::move(bytes_result).value());
  StdInputStream stream(source, 4);
  auto result = fory.deserialize<StreamEnvelope>(stream);
  ASSERT_TRUE(result.ok()) << result.error().to_string();
  EXPECT_EQ(result.value(), original);
}

TEST(StreamSerializationTest, SequentialDeserializeFromSingleStream) {
  auto fory = Fory::builder().xlang(true).track_ref(true).build();
  register_stream_types(fory);

  StreamEnvelope envelope{
      "batch", {10, 20, 30}, {{"a", 1}, {"b", 2}}, {9, 8}, false,
  };

  std::vector<uint8_t> bytes;
  ASSERT_TRUE(fory.serialize_to(bytes, static_cast<int32_t>(12345)).ok());
  ASSERT_TRUE(fory.serialize_to(bytes, std::string("next-value")).ok());
  ASSERT_TRUE(fory.serialize_to(bytes, envelope).ok());

  OneByteIStream source(bytes);
  StdInputStream stream(source, 3);

  auto first = fory.deserialize<int32_t>(stream);
  ASSERT_TRUE(first.ok()) << first.error().to_string();
  EXPECT_EQ(first.value(), 12345);
  const uint32_t first_reader_index = stream.get_buffer().reader_index();
  EXPECT_GT(first_reader_index, 0U);

  auto second = fory.deserialize<std::string>(stream);
  ASSERT_TRUE(second.ok()) << second.error().to_string();
  EXPECT_EQ(second.value(), "next-value");
  const uint32_t second_reader_index = stream.get_buffer().reader_index();
  EXPECT_GT(second_reader_index, first_reader_index);

  auto third = fory.deserialize<StreamEnvelope>(stream);
  ASSERT_TRUE(third.ok()) << third.error().to_string();
  EXPECT_EQ(third.value(), envelope);
  const uint32_t third_reader_index = stream.get_buffer().reader_index();
  EXPECT_GT(third_reader_index, second_reader_index);

  EXPECT_EQ(stream.get_buffer().remaining_size(), 0U);
}

TEST(StreamSerializationTest, SharedPointerIdentityRoundTrip) {
  auto fory = Fory::builder().xlang(true).track_ref(true).build();
  register_stream_types(fory);

  auto shared = std::make_shared<int32_t>(2026);
  SharedIntPair pair{shared, shared};

  auto bytes_result = fory.serialize(pair);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  OneByteIStream source(std::move(bytes_result).value());
  StdInputStream stream(source, 2);
  auto result = fory.deserialize<SharedIntPair>(stream);
  ASSERT_TRUE(result.ok()) << result.error().to_string();
  ASSERT_NE(result.value().first, nullptr);
  ASSERT_NE(result.value().second, nullptr);
  EXPECT_EQ(*result.value().first, 2026);
  EXPECT_EQ(result.value().first, result.value().second);
}

TEST(StreamSerializationTest, TruncatedStreamReturnsError) {
  auto fory = Fory::builder().xlang(true).track_ref(true).build();
  register_stream_types(fory);

  StreamEnvelope original{
      "truncated", {1, 2, 3, 4}, {{"k", 99}}, {7, 7}, true,
  };
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  std::vector<uint8_t> truncated = std::move(bytes_result).value();
  ASSERT_GT(truncated.size(), 1u);
  truncated.pop_back();

  OneByteIStream source(truncated);
  StdInputStream stream(source, 4);
  auto result = fory.deserialize<StreamEnvelope>(stream);
  EXPECT_FALSE(result.ok());
}

TEST(StreamSerializationTest, SerializeToOutputStreamRoundTrip) {
  auto fory = Fory::builder().xlang(true).track_ref(true).build();
  register_stream_types(fory);

  StreamEnvelope original{
      "writer-roundtrip", {2, 4, 6, 8}, {{"x", 1}, {"y", 2}}, {5, -9}, true,
  };

  OneByteOStream out;
  StdOutputStream writer(out);
  auto write_result = fory.serialize(writer, original);
  ASSERT_TRUE(write_result.ok()) << write_result.error().to_string();
  ASSERT_GT(write_result.value(), 0U);

  auto bytes = out.data();
  auto roundtrip = fory.deserialize<StreamEnvelope>(bytes);
  ASSERT_TRUE(roundtrip.ok()) << roundtrip.error().to_string();
  EXPECT_EQ(roundtrip.value(), original);
}

TEST(StreamSerializationTest, SerializeToOStreamOverloadParity) {
  auto fory = Fory::builder().xlang(true).track_ref(true).build();
  register_stream_types(fory);

  StreamEnvelope original{
      "ostream-overload", {11, 22, 33}, {{"k", 99}}, {1, 2}, false,
  };

  auto expected = fory.serialize(original);
  ASSERT_TRUE(expected.ok()) << expected.error().to_string();

  OneByteOStream out;
  auto write_result = fory.serialize(out, original);
  ASSERT_TRUE(write_result.ok()) << write_result.error().to_string();
  EXPECT_EQ(out.data(), expected.value());
}

TEST(StreamSerializationTest,
     StructDeserializeFromStreamBackedBufferShrinksAfterEachStruct) {
  auto fory = Fory::builder().xlang(true).track_ref(true).build();
  register_stream_types(fory);

  std::vector<int32_t> first_values;
  std::vector<int32_t> second_values;
  first_values.reserve(6000);
  second_values.reserve(6000);
  for (int32_t i = 0; i < 6000; ++i) {
    first_values.push_back(i);
    second_values.push_back(6000 - i);
  }

  StreamEnvelope first{
      "first", std::move(first_values), {{"a", 11}, {"b", 22}}, {7, 8}, true,
  };
  StreamEnvelope second{
      "second", std::move(second_values), {{"c", 33}, {"d", 44}}, {9, 10},
      false,
  };

  std::vector<uint8_t> bytes;
  ASSERT_TRUE(fory.serialize_to(bytes, first).ok());
  ASSERT_TRUE(fory.serialize_to(bytes, second).ok());

  std::string payload(reinterpret_cast<const char *>(bytes.data()),
                      bytes.size());
  std::istringstream source(payload);
  StdInputStream stream(source, 4096);
  Buffer &buffer = stream.get_buffer();

  auto first_result = fory.deserialize<StreamEnvelope>(buffer);
  ASSERT_TRUE(first_result.ok()) << first_result.error().to_string();
  EXPECT_EQ(first_result.value(), first);
  EXPECT_EQ(buffer.reader_index(), 0U);

  auto second_result = fory.deserialize<StreamEnvelope>(buffer);
  ASSERT_TRUE(second_result.ok()) << second_result.error().to_string();
  EXPECT_EQ(second_result.value(), second);
  EXPECT_EQ(buffer.reader_index(), 0U);
}

} // namespace test
} // namespace serialization
} // namespace fory
