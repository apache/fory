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

#include "fory/serialization/fory.h"
#include "fory/serialization/ref_resolver.h"
#include "fory/serialization/skip.h"
#include "gtest/gtest.h"
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <map>
#include <string>
#include <thread>
#include <vector>

#include "fory/type/type.h"

// ============================================================================
// Test Struct Definitions (FORY_STRUCT is declared inside each struct)
// ============================================================================

struct SimpleStruct {
  int32_t x;
  int32_t y;

  bool operator==(const SimpleStruct &other) const {
    return x == other.x && y == other.y;
  }
  FORY_STRUCT(SimpleStruct, x, y);
};

struct ComplexStruct {
  std::string name;
  int32_t age;
  std::vector<std::string> hobbies;

  bool operator==(const ComplexStruct &other) const {
    return name == other.name && age == other.age && hobbies == other.hobbies;
  }
  FORY_STRUCT(ComplexStruct, name, age, hobbies);
};

struct NestedStruct {
  SimpleStruct point;
  std::string label;

  bool operator==(const NestedStruct &other) const {
    return point == other.point && label == other.label;
  }
  FORY_STRUCT(NestedStruct, point, label);
};

enum class Color { RED, GREEN, BLUE };
enum class LegacyStatus : int32_t { NEG = -3, ZERO = 0, LARGE = 42 };
FORY_ENUM(LegacyStatus, NEG, ZERO, LARGE);
enum class SparseStatus : int32_t { UNKNOWN = 4096, OK = 8192 };
FORY_ENUM(SparseStatus, UNKNOWN, OK);

enum OldStatus : int32_t { OLD_NEG = -7, OLD_ZERO = 0, OLD_POS = 13 };
FORY_ENUM(::OldStatus, OLD_NEG, OLD_ZERO, OLD_POS);

namespace fory {
namespace serialization {
namespace test {

// ============================================================================
// Test Helpers
// ============================================================================

// Helper to register test struct types on a Fory instance
inline void register_test_types(Fory &fory) {
  uint32_t type_id = 1;

  // Register all struct types used in tests
  fory.register_struct<::SimpleStruct>(type_id++);
  fory.register_struct<::ComplexStruct>(type_id++);
  fory.register_struct<::NestedStruct>(type_id++);

  // Register all enum types used in tests
  fory.register_enum<Color>(type_id++);
  fory.register_enum<LegacyStatus>(type_id++);
  fory.register_enum<SparseStatus>(type_id++);
  fory.register_enum<OldStatus>(type_id++);
}

inline std::vector<uint8_t> buffer_bytes(Buffer &buffer) {
  return std::vector<uint8_t>(buffer.data(),
                              buffer.data() + buffer.writer_index());
}

template <typename T>
void test_roundtrip(const T &original, bool should_equal = true) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  register_test_types(fory);

  // Serialize
  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(serialize_result).value();
  ASSERT_GT(bytes.size(), 0) << "Serialized bytes should not be empty";

  // Deserialize
  auto deserialize_result = fory.deserialize<T>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();

  T deserialized = std::move(deserialize_result).value();

  // Compare
  if (should_equal) {
    EXPECT_EQ(original, deserialized);
  }
}

// ============================================================================
// Primitive Type Tests
// ============================================================================

TEST(SerializationTest, BoolRoundtrip) {
  test_roundtrip(true);
  test_roundtrip(false);
}

TEST(SerializationTest, Int8Roundtrip) {
  test_roundtrip<int8_t>(0);
  test_roundtrip<int8_t>(127);
  test_roundtrip<int8_t>(-128);
  test_roundtrip<int8_t>(42);
}

TEST(SerializationTest, Int16Roundtrip) {
  test_roundtrip<int16_t>(0);
  test_roundtrip<int16_t>(32767);
  test_roundtrip<int16_t>(-32768);
  test_roundtrip<int16_t>(1234);
}

TEST(SerializationTest, Int32Roundtrip) {
  test_roundtrip<int32_t>(0);
  test_roundtrip<int32_t>(2147483647);
  test_roundtrip<int32_t>(-2147483648);
  test_roundtrip<int32_t>(123456);
}

TEST(SerializationTest, Int64Roundtrip) {
  test_roundtrip<int64_t>(0);
  test_roundtrip<int64_t>(9223372036854775807LL);
  test_roundtrip<int64_t>(-9223372036854775807LL - 1);
  test_roundtrip<int64_t>(123456789012345LL);
}

TEST(SerializationTest, FloatRoundtrip) {
  test_roundtrip<float>(0.0f);
  test_roundtrip<float>(3.14159f);
  test_roundtrip<float>(-2.71828f);
  test_roundtrip<float>(1.23456e10f);
}

TEST(SerializationTest, DoubleRoundtrip) {
  test_roundtrip<double>(0.0);
  test_roundtrip<double>(3.141592653589793);
  test_roundtrip<double>(-2.718281828459045);
  test_roundtrip<double>(1.23456789012345e100);
}

TEST(SerializationTest, StringRoundtrip) {
  test_roundtrip(std::string(""));
  test_roundtrip(std::string("Hello, World!"));
  test_roundtrip(std::string("The quick brown fox jumps over the lazy dog"));
  test_roundtrip(std::string("UTF-8: 你好世界"));
}

TEST(SerializationTest, DurationRoundtrip) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  std::vector<Duration> values = {
      Duration(0),
      std::chrono::seconds(12) + Duration(345678901),
      -std::chrono::seconds(7) - std::chrono::milliseconds(45) - Duration(67),
      Duration(-1),
  };

  for (const Duration &original : values) {
    auto serialize_result = fory.serialize(original);
    ASSERT_TRUE(serialize_result.ok())
        << "Serialization failed: " << serialize_result.error().to_string();

    std::vector<uint8_t> bytes = std::move(serialize_result).value();
    auto deserialize_result =
        fory.deserialize<Duration>(bytes.data(), bytes.size());
    ASSERT_TRUE(deserialize_result.ok())
        << "Deserialization failed: " << deserialize_result.error().to_string();
    EXPECT_EQ(deserialize_result.value(), original);
  }
}

TEST(SerializationTest, DateExposesDaysSinceEpochAccessorAndRoundTrips) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  Date original(-1);

  EXPECT_EQ(original.days_since_epoch(), -1);

  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(serialize_result).value();
  Buffer expected;
  expected.write_uint8(0b10);
  expected.write_int8(NOT_NULL_VALUE_FLAG);
  expected.write_uint8(static_cast<uint8_t>(TypeId::DATE));
  expected.write_var_int64(-1);
  EXPECT_EQ(bytes, buffer_bytes(expected));
  auto deserialize_result = fory.deserialize<Date>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();
  EXPECT_EQ(deserialize_result.value(), original);
  EXPECT_EQ(deserialize_result.value().days_since_epoch(), -1);
}

TEST(SerializationTest, DateRejectsXlangDayCountsOutsideInt32Range) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  WriteContext write_ctx(fory.config(), fory.type_resolver().clone());
  write_ctx.write_var_int64(
      static_cast<int64_t>(std::numeric_limits<int32_t>::max()) + 1);
  ASSERT_FALSE(write_ctx.has_error()) << write_ctx.error().to_string();

  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(write_ctx.buffer());
  Date decoded = Serializer<Date>::read_data(read_ctx);
  (void)decoded;
  ASSERT_TRUE(read_ctx.has_error());
  EXPECT_NE(read_ctx.error().to_string().find("exceeds int32_t range"),
            std::string::npos);
}

TEST(SerializationTest, DateSkipConsumesVarInt64DayCount) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  WriteContext write_ctx(fory.config(), fory.type_resolver().clone());
  Serializer<Date>::write_data(Date(18954), write_ctx);
  ASSERT_FALSE(write_ctx.has_error()) << write_ctx.error().to_string();

  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(write_ctx.buffer());
  skip_field_value(read_ctx,
                   FieldType(static_cast<uint32_t>(TypeId::DATE), false),
                   RefMode::None);
  ASSERT_FALSE(read_ctx.has_error()) << read_ctx.error().to_string();
  EXPECT_EQ(read_ctx.buffer().reader_index(),
            write_ctx.buffer().writer_index());
}

TEST(SerializationTest, DecimalRoundTripsEdgeCases) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  std::vector<Decimal> values = {
      Decimal::from_int64(0, 0),
      Decimal::from_int64(0, 3),
      Decimal::from_int64(1, 0),
      Decimal::from_int64(-1, 0),
      Decimal::from_int64(12345, 2),
      Decimal::from_int64(std::numeric_limits<int64_t>::max(), 0),
      Decimal::from_int64(std::numeric_limits<int64_t>::min(), 0),
      Decimal::from_int64(4611686018427387903LL, 0),
      Decimal::from_int64(-4611686018427387904LL, 0),
      Decimal::from_bytes(0, false, {0, 0, 0, 0, 0, 0, 0, 128}),
      Decimal::from_bytes(0, true, {1, 0, 0, 0, 0, 0, 0, 128}),
      Decimal::from_bytes(37, false,
                          {21, 129, 57, 174, 40, 163, 223, 170, 197, 254, 21,
                           96, 165, 233, 224, 92}),
      Decimal::from_bytes(-17, true,
                          {21, 129, 57, 174, 40, 163, 223, 170, 197, 254, 21,
                           96, 165, 233, 224, 92}),
  };

  for (const Decimal &original : values) {
    auto serialize_result = fory.serialize(original);
    ASSERT_TRUE(serialize_result.ok())
        << "Serialization failed: " << serialize_result.error().to_string();

    std::vector<uint8_t> bytes = std::move(serialize_result).value();
    auto deserialize_result =
        fory.deserialize<Decimal>(bytes.data(), bytes.size());
    ASSERT_TRUE(deserialize_result.ok())
        << "Deserialization failed: " << deserialize_result.error().to_string();
    EXPECT_EQ(deserialize_result.value(), original);
  }
}

TEST(SerializationTest, DecimalRejectsNonCanonicalBigPayloads) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();

  Buffer zero_big_encoding;
  zero_big_encoding.write_uint8(0b10);
  zero_big_encoding.write_int8(NOT_NULL_VALUE_FLAG);
  zero_big_encoding.write_uint8(static_cast<uint8_t>(TypeId::DECIMAL));
  zero_big_encoding.write_var_int32(0);
  zero_big_encoding.write_var_uint64(1);

  auto zero_result = fory.deserialize<Decimal>(
      zero_big_encoding.data(), zero_big_encoding.writer_index());
  ASSERT_FALSE(zero_result.ok());
  EXPECT_NE(
      zero_result.error().to_string().find("Invalid decimal magnitude length"),
      std::string::npos);

  Buffer trailing_zero_payload;
  trailing_zero_payload.write_uint8(0b10);
  trailing_zero_payload.write_int8(NOT_NULL_VALUE_FLAG);
  trailing_zero_payload.write_uint8(static_cast<uint8_t>(TypeId::DECIMAL));
  trailing_zero_payload.write_var_int32(0);
  trailing_zero_payload.write_var_uint64(9);
  trailing_zero_payload.write_bytes("\x01\x00", 2);

  auto trailing_zero_result = fory.deserialize<Decimal>(
      trailing_zero_payload.data(), trailing_zero_payload.writer_index());
  ASSERT_FALSE(trailing_zero_result.ok());
  EXPECT_NE(trailing_zero_result.error().to_string().find("trailing zero byte"),
            std::string::npos);
}

TEST(SerializationTest, DecimalSkipConsumesScaleHeaderAndPayload) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  WriteContext write_ctx(fory.config(), fory.type_resolver().clone());
  Serializer<Decimal>::write_data(
      Decimal::from_bytes(37, false,
                          {21, 129, 57, 174, 40, 163, 223, 170, 197, 254, 21,
                           96, 165, 233, 224, 92}),
      write_ctx);
  ASSERT_FALSE(write_ctx.has_error()) << write_ctx.error().to_string();

  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(write_ctx.buffer());
  skip_field_value(read_ctx,
                   FieldType(static_cast<uint32_t>(TypeId::DECIMAL), false),
                   RefMode::None);
  ASSERT_FALSE(read_ctx.has_error()) << read_ctx.error().to_string();
  EXPECT_EQ(read_ctx.buffer().reader_index(),
            write_ctx.buffer().writer_index());
}

TEST(SerializationTest, DurationUsesSecondsAndNanosecondsPayload) {
  struct TestCase {
    Duration value;
    int64_t expected_seconds;
    int32_t expected_nanos;
  };

  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  std::vector<TestCase> cases = {
      {Duration(1234567890), 1, 234567890},
      {Duration(-1234567890), -1, -234567890},
      {Duration(-1), 0, -1},
  };

  for (const TestCase &test_case : cases) {
    WriteContext write_ctx(fory.config(), fory.type_resolver().clone());
    Serializer<Duration>::write_data(test_case.value, write_ctx);
    ASSERT_FALSE(write_ctx.has_error()) << write_ctx.error().to_string();

    Buffer expected;
    expected.write_var_int64(test_case.expected_seconds);
    expected.write_int32(test_case.expected_nanos);
    EXPECT_EQ(buffer_bytes(write_ctx.buffer()), buffer_bytes(expected));

    ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
    read_ctx.attach(write_ctx.buffer());
    Duration decoded = Serializer<Duration>::read_data(read_ctx);
    ASSERT_FALSE(read_ctx.has_error()) << read_ctx.error().to_string();
    EXPECT_EQ(decoded, test_case.value);
    EXPECT_EQ(read_ctx.buffer().reader_index(),
              write_ctx.buffer().writer_index());
  }
}

TEST(SerializationTest, DurationSkipConsumesSecondsAndNanosecondsPayload) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  WriteContext write_ctx(fory.config(), fory.type_resolver().clone());
  Serializer<Duration>::write_data(Duration(-1), write_ctx);
  ASSERT_FALSE(write_ctx.has_error()) << write_ctx.error().to_string();

  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(write_ctx.buffer());
  skip_field_value(read_ctx,
                   FieldType(static_cast<uint32_t>(TypeId::DURATION), false),
                   RefMode::None);
  ASSERT_FALSE(read_ctx.has_error()) << read_ctx.error().to_string();
  EXPECT_EQ(read_ctx.buffer().reader_index(),
            write_ctx.buffer().writer_index());
}

// ============================================================================
// Character Type Tests (C++ native only)
// ============================================================================

TEST(SerializationTest, CharRoundtrip) {
  test_roundtrip<char>('A');
  test_roundtrip<char>('z');
  test_roundtrip<char>('0');
  test_roundtrip<char>('\0');
  test_roundtrip<char>('\n');
  test_roundtrip<char>(static_cast<char>(127));
  test_roundtrip<char>(static_cast<char>(-128));
}

TEST(SerializationTest, Char16Roundtrip) {
  test_roundtrip<char16_t>(u'A');
  test_roundtrip<char16_t>(u'中');
  test_roundtrip<char16_t>(u'\0');
  test_roundtrip<char16_t>(static_cast<char16_t>(0xFFFF));
  test_roundtrip<char16_t>(static_cast<char16_t>(0x4E2D)); // 中
}

TEST(SerializationTest, Char32Roundtrip) {
  test_roundtrip<char32_t>(U'A');
  test_roundtrip<char32_t>(U'中');
  test_roundtrip<char32_t>(U'\0');
  test_roundtrip<char32_t>(static_cast<char32_t>(0x10FFFF)); // Max Unicode
  test_roundtrip<char32_t>(static_cast<char32_t>(0x1F600));  // Emoji 😀
}

// ============================================================================
// Enum Tests
// ============================================================================

TEST(SerializationTest, EnumRoundtrip) {
  test_roundtrip(Color::RED);
  test_roundtrip(Color::GREEN);
  test_roundtrip(Color::BLUE);
}

TEST(SerializationTest, OldEnumRoundtrip) {
  test_roundtrip(OldStatus::OLD_NEG);
  test_roundtrip(OldStatus::OLD_ZERO);
  test_roundtrip(OldStatus::OLD_POS);
}

TEST(SerializationTest, SparseEnumRoundtrip) {
  test_roundtrip(SparseStatus::UNKNOWN);
  test_roundtrip(SparseStatus::OK);
}

TEST(SerializationTest, EnumSerializesOrdinalValue) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  fory.register_enum<LegacyStatus>(1);

  auto bytes_result = fory.serialize(LegacyStatus::LARGE);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  // Xlang spec: enums are serialized as varuint32, not fixed int32_t.
  // With registration, we write type_id (ENUM) + user_type_id (1), both as
  // varuint32. Expected: 1 (header) + 1 (ref flag) + 1 (type id) +
  // 1 (user type id) + 1 (ordinal) = 5 bytes
  ASSERT_GE(bytes.size(), 1 + 1 + 1 + 1 + 1);
  size_t offset = 1;
  EXPECT_EQ(bytes[offset], static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  EXPECT_EQ(bytes[offset + 1], static_cast<uint8_t>(TypeId::ENUM));
  EXPECT_EQ(bytes[offset + 2], 1);
  // Ordinal 2 encoded as varuint32 is just 1 byte with value 2
  EXPECT_EQ(bytes[offset + 3], 2);
}

TEST(SerializationTest, OldEnumSerializesOrdinalValue) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  fory.register_enum<OldStatus>(1);

  auto bytes_result = fory.serialize(OldStatus::OLD_POS);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  // With registration, type_id + user_type_id take 2 bytes
  ASSERT_GE(bytes.size(), 1 + 1 + 1 + 1 + 1);
  size_t offset = 1;
  EXPECT_EQ(bytes[offset], static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  EXPECT_EQ(bytes[offset + 1], static_cast<uint8_t>(TypeId::ENUM));
  EXPECT_EQ(bytes[offset + 2], 1);
  // Ordinal 2 encoded as varuint32 is just 1 byte with value 2
  EXPECT_EQ(bytes[offset + 3], 2);
}

TEST(SerializationTest, EnumOrdinalMappingHandlesNonZeroStart) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  fory.register_enum<LegacyStatus>(1);

  auto bytes_result = fory.serialize(LegacyStatus::NEG);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  // With registration, type_id + user_type_id take 2 bytes
  ASSERT_GE(bytes.size(), 1 + 1 + 1 + 1 + 1);
  size_t offset = 1;
  EXPECT_EQ(bytes[offset], static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  EXPECT_EQ(bytes[offset + 1], static_cast<uint8_t>(TypeId::ENUM));
  EXPECT_EQ(bytes[offset + 2], 1);
  // Ordinal 0 encoded as varuint32 is just 1 byte with value 0
  EXPECT_EQ(bytes[offset + 3], 0);

  auto roundtrip = fory.deserialize<LegacyStatus>(bytes.data(), bytes.size());
  ASSERT_TRUE(roundtrip.ok())
      << "Deserialization failed: " << roundtrip.error().to_string();
  EXPECT_EQ(roundtrip.value(), LegacyStatus::NEG);
}

TEST(SerializationTest, EnumOrdinalMappingRejectsInvalidOrdinal) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  fory.register_enum<LegacyStatus>(1);

  auto bytes_result = fory.serialize(LegacyStatus::NEG);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  size_t offset = 1;
  // With registration, type_id + user_type_id take 2 bytes, ordinal is at
  // offset + 3 Replace the valid ordinal with an invalid one (99 as varuint32)
  bytes[offset + 3] = 99;

  auto decode = fory.deserialize<LegacyStatus>(bytes.data(), bytes.size());
  EXPECT_FALSE(decode.ok());
}

TEST(SerializationTest, SparseEnumSerializesExplicitValue) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  fory.register_enum<SparseStatus>(1);

  auto bytes_result = fory.serialize(SparseStatus::OK);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  ASSERT_GE(bytes.size(), 1 + 1 + 1 + 1 + 2);
  size_t offset = 1;
  EXPECT_EQ(bytes[offset], static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  EXPECT_EQ(bytes[offset + 1], static_cast<uint8_t>(TypeId::ENUM));
  EXPECT_EQ(bytes[offset + 2], 1);
  EXPECT_EQ(bytes[offset + 3], 0x80);
  EXPECT_EQ(bytes[offset + 4], 0x40);

  auto roundtrip = fory.deserialize<SparseStatus>(bytes.data(), bytes.size());
  ASSERT_TRUE(roundtrip.ok())
      << "Deserialization failed: " << roundtrip.error().to_string();
  EXPECT_EQ(roundtrip.value(), SparseStatus::OK);
}

TEST(SerializationTest, OldEnumOrdinalMappingHandlesNonZeroStart) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  fory.register_enum<OldStatus>(1);

  auto bytes_result = fory.serialize(OldStatus::OLD_NEG);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  // With registration, type_id + user_type_id take 2 bytes
  ASSERT_GE(bytes.size(), 1 + 1 + 1 + 1 + 1);
  size_t offset = 1;
  EXPECT_EQ(bytes[offset], static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  EXPECT_EQ(bytes[offset + 1], static_cast<uint8_t>(TypeId::ENUM));
  EXPECT_EQ(bytes[offset + 2], 1);
  // Ordinal 0 encoded as varuint32 is just 1 byte with value 0
  EXPECT_EQ(bytes[offset + 3], 0);

  auto roundtrip = fory.deserialize<OldStatus>(bytes.data(), bytes.size());
  ASSERT_TRUE(roundtrip.ok())
      << "Deserialization failed: " << roundtrip.error().to_string();
  EXPECT_EQ(roundtrip.value(), OldStatus::OLD_NEG);
}

// ============================================================================
// Container Type Tests
// ============================================================================

TEST(SerializationTest, VectorIntRoundtrip) {
  test_roundtrip(std::vector<int32_t>{});
  test_roundtrip(std::vector<int32_t>{1});
  test_roundtrip(std::vector<int32_t>{1, 2, 3, 4, 5});
  test_roundtrip(std::vector<int32_t>{-10, 0, 10, 20, 30});
}

TEST(SerializationTest, VectorStringRoundtrip) {
  test_roundtrip(std::vector<std::string>{});
  test_roundtrip(std::vector<std::string>{"hello"});
  test_roundtrip(std::vector<std::string>{"foo", "bar", "baz"});
}

TEST(SerializationTest, MapStringIntRoundtrip) {
  test_roundtrip(std::map<std::string, int32_t>{});
  test_roundtrip(std::map<std::string, int32_t>{{"one", 1}});
  test_roundtrip(
      std::map<std::string, int32_t>{{"one", 1}, {"two", 2}, {"three", 3}});
}

TEST(SerializationTest, NestedVectorRoundtrip) {
  test_roundtrip(std::vector<std::vector<int32_t>>{});
  test_roundtrip(std::vector<std::vector<int32_t>>{{1, 2}, {3, 4}, {5}});
}

// ============================================================================
// Struct Type Tests (using structs defined above)
// ============================================================================

TEST(SerializationTest, SimpleStructRoundtrip) {
  ::SimpleStruct s1{42, 100};
  test_roundtrip(s1);

  ::SimpleStruct s2{0, 0};
  test_roundtrip(s2);

  ::SimpleStruct s3{-10, -20};
  test_roundtrip(s3);
}

TEST(SerializationTest, ComplexStructRoundtrip) {
  ::ComplexStruct c1{"Alice", 30, {"reading", "coding", "gaming"}};
  test_roundtrip(c1);

  ::ComplexStruct c2{"Bob", 25, {}};
  test_roundtrip(c2);
}

TEST(SerializationTest, NestedStructRoundtrip) {
  ::NestedStruct n1{{10, 20}, "origin"};
  test_roundtrip(n1);

  ::NestedStruct n2{{-5, 15}, "point A"};
  test_roundtrip(n2);
}

// ============================================================================
// Error Handling Tests
// ============================================================================

TEST(SerializationTest, DeserializeInvalidData) {
  auto fory = Fory::builder().build();

  uint8_t invalid_data[] = {0xFF, 0xFF, 0xFF};
  auto result = fory.deserialize<int32_t>(invalid_data, 3);
  EXPECT_FALSE(result.ok());
}

TEST(SerializationTest, DeserializeNullPointer) {
  auto fory = Fory::builder().build();
  auto result = fory.deserialize<int32_t>(nullptr, 0);
  EXPECT_FALSE(result.ok());
}

TEST(SerializationTest, DeserializeZeroSize) {
  auto fory = Fory::builder().build();
  uint8_t data[] = {0x01};
  auto result = fory.deserialize<int32_t>(data, 0);
  EXPECT_FALSE(result.ok());
}

TEST(SerializationTest, DeserializeRejectsXlangProtocolMismatch) {
  auto writer = Fory::builder().xlang(true).build();
  auto reader = Fory::builder().xlang(false).build();

  auto bytes_result = writer.serialize<int32_t>(123);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  auto result = reader.deserialize<int32_t>(bytes_result.value().data(),
                                            bytes_result.value().size());
  EXPECT_FALSE(result.ok());
  ASSERT_FALSE(result.ok());
  EXPECT_EQ(result.error().code(), ErrorCode::InvalidData);
  EXPECT_NE(result.error().to_string().find("Protocol mismatch"),
            std::string::npos);
}

TEST(SerializationTest, RegistrationByIdFailureDoesNotLeakTypeInfo) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  TypeResolver &resolver = fory.type_resolver();

  ASSERT_TRUE(fory.register_struct<::SimpleStruct>(1).ok());

  auto duplicate = fory.register_struct<::NestedStruct>(1);
  EXPECT_FALSE(duplicate.ok());
  ASSERT_FALSE(duplicate.ok());
  EXPECT_EQ(duplicate.error().code(), ErrorCode::Invalid);

  auto nested_info = resolver.get_type_info<::NestedStruct>();
  EXPECT_FALSE(nested_info.ok());

  auto simple_info = resolver.get_type_info<::SimpleStruct>();
  ASSERT_TRUE(simple_info.ok());
  auto by_user_id =
      resolver.get_user_type_info_by_id(simple_info.value()->type_id, 1);
  ASSERT_TRUE(by_user_id.ok());
  EXPECT_EQ(by_user_id.value(), simple_info.value());
}

TEST(SerializationTest, RegistrationByNameFailureDoesNotLeakTypeInfo) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  TypeResolver &resolver = fory.type_resolver();

  ASSERT_TRUE(fory.register_struct<::SimpleStruct>("demo", "SharedType").ok());

  auto duplicate = fory.register_struct<::NestedStruct>("demo", "SharedType");
  EXPECT_FALSE(duplicate.ok());
  ASSERT_FALSE(duplicate.ok());
  EXPECT_EQ(duplicate.error().code(), ErrorCode::Invalid);

  auto nested_info = resolver.get_type_info<::NestedStruct>();
  EXPECT_FALSE(nested_info.ok());

  auto simple_info = resolver.get_type_info<::SimpleStruct>();
  ASSERT_TRUE(simple_info.ok());
  auto by_name = resolver.get_type_info_by_name("demo", "SharedType");
  ASSERT_TRUE(by_name.ok());
  EXPECT_EQ(by_name.value(), simple_info.value());
}

TEST(SerializationTest, TypeMetaRejectsOverConsumedDeclaredSize) {
  TypeMeta meta =
      TypeMeta::from_fields(static_cast<uint32_t>(TypeId::STRUCT), "", "S",
                            false, 1, std::vector<FieldInfo>{});
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  ASSERT_GE(bytes.size(), sizeof(int64_t));

  int64_t header = 0;
  std::memcpy(&header, bytes.data(), sizeof(header));
  // Corrupt declared meta_size to be much smaller than actual payload.
  header = (header & ~static_cast<int64_t>(0xFF)) | 0x01;
  std::memcpy(bytes.data(), &header, sizeof(header));

  Buffer buffer(bytes);
  auto parsed = TypeMeta::from_bytes(buffer, nullptr);
  EXPECT_FALSE(parsed.ok());
  ASSERT_FALSE(parsed.ok());
  EXPECT_EQ(parsed.error().code(), ErrorCode::InvalidData);
}

// ============================================================================
// Configuration Tests
// ============================================================================

TEST(SerializationTest, ConfigurationBuilder) {
  auto fory1 = Fory::builder()
                   .compatible(true)
                   .xlang(false)
                   .check_struct_version(true)
                   .max_dyn_depth(10)
                   .track_ref(false)
                   .build();

  EXPECT_TRUE(fory1.config().compatible);
  EXPECT_FALSE(fory1.config().xlang);
  EXPECT_TRUE(fory1.config().check_struct_version);
  EXPECT_EQ(fory1.config().max_dyn_depth, 10);
  EXPECT_FALSE(fory1.config().track_ref);
}

// ============================================================================
// Thread Safety Tests
// ============================================================================

TEST(SerializationTest, ThreadSafeForyMultiThread) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build_thread_safe();
  fory.register_struct<::ComplexStruct>(1);

  constexpr int k_num_threads = 8;
  constexpr int k_iterations_per_thread = 100;
  std::vector<std::thread> threads;
  std::atomic<int> success_count{0};

  for (int t = 0; t < k_num_threads; ++t) {
    threads.emplace_back([&, t]() {
      for (int i = 0; i < k_iterations_per_thread; ++i) {
        ::ComplexStruct original{"thread" + std::to_string(t) + "_iter" +
                                     std::to_string(i),
                                 t * 1000 + i,
                                 {"hobby1", "hobby2"}};

        auto bytes_result = fory.serialize(original);
        if (!bytes_result.ok())
          continue;

        auto deser_result = fory.deserialize<::ComplexStruct>(
            bytes_result.value().data(), bytes_result.value().size());
        if (deser_result.ok() && deser_result.value() == original) {
          success_count.fetch_add(1);
        }
      }
    });
  }

  for (auto &t : threads) {
    t.join();
  }

  EXPECT_EQ(success_count.load(), k_num_threads * k_iterations_per_thread);
}

TEST(SerializationTest, ThreadSafeForyRejectsRegistrationAfterFirstSerialize) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build_thread_safe();
  ASSERT_TRUE(fory.register_struct<::ComplexStruct>(1).ok());

  ::ComplexStruct original{"Alice", 30, {"reading", "coding"}};
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  auto late_registration = fory.register_struct<::SimpleStruct>(2);
  EXPECT_FALSE(late_registration.ok());
  ASSERT_FALSE(late_registration.ok());
  EXPECT_EQ(late_registration.error().code(), ErrorCode::Invalid);
  EXPECT_NE(late_registration.error().to_string().find("Cannot register types"),
            std::string::npos);
}

} // namespace test
} // namespace serialization
} // namespace fory
