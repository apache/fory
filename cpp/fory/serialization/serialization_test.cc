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
#include "fory/thirdparty/MurmurHash3.h"
#include "gtest/gtest.h"
#include <algorithm>
#include <any>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <limits>
#include <map>
#include <memory>
#include <string>
#include <thread>
#include <typeinfo>
#include <unordered_map>
#include <variant>
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
enum class SignedScopedStatus : int32_t { NEG = -3, ZERO = 0, LARGE = 42 };
FORY_ENUM(SignedScopedStatus, NEG, ZERO, LARGE);
enum class SparseStatus : int32_t { UNKNOWN = 4096, OK = 8192 };
FORY_ENUM(SparseStatus, UNKNOWN, OK);

enum OldStatus : int32_t { OLD_NEG = -7, OLD_ZERO = 0, OLD_POS = 13 };
FORY_ENUM(::OldStatus, OLD_NEG, OLD_ZERO, OLD_POS);

static std::atomic<int> g_ext_destructor_calls{0};

struct ExtWithDestructor {
  int32_t value = 0;

  ~ExtWithDestructor() { g_ext_destructor_calls.fetch_add(1); }

  FORY_STRUCT(ExtWithDestructor, value);
};

struct IdLimitExt {
  int32_t value = 0;

  bool operator==(const IdLimitExt &other) const {
    return value == other.value;
  }

  FORY_STRUCT(IdLimitExt, value);
};

namespace fory {
namespace serialization {

struct TypeMetaTestReader {};

template <> struct Serializer<TypeMetaTestReader> {
  static Result<const TypeInfo *, Error>
  read_expected(ReadContext &ctx, const TypeInfo *expected) {
    return ctx.read_type_meta_owner(expected);
  }
};

namespace test {

namespace {

uint64_t compute_type_meta_hash_bits_for_test(const uint8_t *meta_bytes,
                                              size_t meta_size,
                                              uint64_t header_low_bits) {
  constexpr uint32_t kHashShift = 12;
  constexpr uint64_t kHashBitsMask = UINT64_MAX << kHashShift;
  std::vector<uint8_t> hash_input(meta_size + 2);
  std::memcpy(hash_input.data(), meta_bytes, meta_size);
  hash_input[meta_size] = static_cast<uint8_t>(header_low_bits);
  hash_input[meta_size + 1] = static_cast<uint8_t>(header_low_bits >> 8);
  int64_t hash_out[2] = {0, 0};
  MurmurHash3_x64_128(hash_input.data(), static_cast<int>(hash_input.size()),
                      47, hash_out);
  uint64_t shifted = static_cast<uint64_t>(hash_out[0]) << kHashShift;
  if (static_cast<int64_t>(shifted) < 0) {
    shifted = ~shifted + 1;
  }
  return shifted & kHashBitsMask;
}

uint64_t
compute_body_only_type_meta_hash_bits_for_test(const uint8_t *meta_bytes,
                                               size_t meta_size) {
  constexpr uint32_t kHashShift = 12;
  constexpr uint64_t kHashBitsMask = UINT64_MAX << kHashShift;
  int64_t hash_out[2] = {0, 0};
  MurmurHash3_x64_128(meta_bytes, static_cast<int>(meta_size), 47, hash_out);
  uint64_t shifted = static_cast<uint64_t>(hash_out[0]) << kHashShift;
  if (static_cast<int64_t>(shifted) < 0) {
    shifted = ~shifted + 1;
  }
  return shifted & kHashBitsMask;
}

} // namespace

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
  fory.register_enum<SignedScopedStatus>(type_id++);
  fory.register_enum<SparseStatus>(type_id++);
  fory.register_enum<OldStatus>(type_id++);
}

inline std::vector<uint8_t> buffer_bytes(Buffer &buffer) {
  return std::vector<uint8_t>(buffer.data(),
                              buffer.data() + buffer.writer_index());
}

template <typename T>
void test_roundtrip(const T &original, bool should_equal = true) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
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

template <typename T> void expect_any_roundtrip(Fory &fory, const T &value) {
  std::any original = value;
  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  auto deserialize_result =
      fory.deserialize<std::any>(serialize_result.value());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();

  std::any decoded = std::move(deserialize_result).value();
  EXPECT_EQ(decoded.type(), typeid(T));
  const auto *decoded_value = std::any_cast<T>(&decoded);
  ASSERT_NE(decoded_value, nullptr);
  EXPECT_EQ(*decoded_value, value);
}

// ============================================================================
// Primitive Type Tests
// ============================================================================

TEST(SerializationTest, BoolRoundtrip) {
  test_roundtrip(true);
  test_roundtrip(false);
}

TEST(SerializationTest, HarnessDestroyRunsRegisteredDestructor) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  auto register_result = fory.register_extension_type<::ExtWithDestructor>(1);
  ASSERT_TRUE(register_result.ok()) << register_result.error().message();
  auto type_info_result =
      fory.type_resolver().get_type_info<::ExtWithDestructor>();
  ASSERT_TRUE(type_info_result.ok()) << type_info_result.error().message();

  g_ext_destructor_calls.store(0);
  void *ptr = new ExtWithDestructor();
  type_info_result.value()->harness.destroy_fn(ptr);
  EXPECT_EQ(g_ext_destructor_calls.load(), 1);
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

TEST(SerializationTest, StringReadsCheckBodyBeforeAllocation) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  constexpr uint32_t huge_length = std::numeric_limits<uint32_t>::max() - 1;

  for (StringEncoding encoding :
       {StringEncoding::LATIN1, StringEncoding::UTF16, StringEncoding::UTF8}) {
    Buffer buffer;
    buffer.write_var_uint36_small((static_cast<uint64_t>(huge_length) << 2) |
                                  static_cast<uint64_t>(encoding));
    ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
    read_ctx.attach(buffer);

    auto result = detail::read_string_data(read_ctx);

    EXPECT_TRUE(result.empty());
    EXPECT_TRUE(read_ctx.has_error());
  }
}

TEST(SerializationTest, U16StringReadsCheckBodyBeforeAllocation) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  constexpr uint32_t huge_length = std::numeric_limits<uint32_t>::max() - 1;

  for (StringEncoding encoding :
       {StringEncoding::LATIN1, StringEncoding::UTF16, StringEncoding::UTF8}) {
    Buffer buffer;
    buffer.write_var_uint36_small((static_cast<uint64_t>(huge_length) << 2) |
                                  static_cast<uint64_t>(encoding));
    ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
    read_ctx.attach(buffer);

    auto result = detail::read_u16string_data(read_ctx);

    EXPECT_TRUE(result.empty());
    EXPECT_TRUE(read_ctx.has_error());
  }
}

TEST(SerializationTest, U16StringUtf8ScratchBoundary) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  std::string utf8(31, 'a');
  utf8.append("\xF0\x9F\x98\x80", 4);

  Buffer buffer;
  buffer.write_var_uint36_small((static_cast<uint64_t>(utf8.size()) << 2) |
                                static_cast<uint64_t>(StringEncoding::UTF8));
  buffer.write_bytes(utf8.data(), static_cast<uint32_t>(utf8.size()));
  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(buffer);

  auto result = detail::read_u16string_data(read_ctx);

  std::u16string expected(31, u'a');
  expected.push_back(0xD83D);
  expected.push_back(0xDE00);
  EXPECT_FALSE(read_ctx.has_error());
  EXPECT_EQ(result, expected);
}

TEST(SerializationTest, U16StringRejectsMalformedUtf8) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  const std::vector<uint8_t> malformed = {0xF0, 0x9F, 0x98};

  Buffer buffer;
  buffer.write_var_uint36_small((static_cast<uint64_t>(malformed.size()) << 2) |
                                static_cast<uint64_t>(StringEncoding::UTF8));
  buffer.write_bytes(malformed.data(), static_cast<uint32_t>(malformed.size()));
  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(buffer);

  auto result = detail::read_u16string_data(read_ctx);

  EXPECT_TRUE(result.empty());
  EXPECT_TRUE(read_ctx.has_error());
}

TEST(SerializationTest, Latin1ReadHandlesUnalignedBody) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  const std::string ascii = "abcdefghij";
  alignas(uint64_t) std::array<uint8_t, 32> storage{};
  Buffer buffer(storage.data() + 1, static_cast<uint32_t>(ascii.size() + 1),
                false);
  buffer.write_var_uint36_small((static_cast<uint64_t>(ascii.size()) << 2) |
                                static_cast<uint64_t>(StringEncoding::LATIN1));
  buffer.write_bytes(ascii.data(), static_cast<uint32_t>(ascii.size()));
  ASSERT_NE(reinterpret_cast<uintptr_t>(buffer.data() + 1) % alignof(uint64_t),
            0U);

  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(buffer);
  auto result = detail::read_string_data(read_ctx);

  EXPECT_FALSE(read_ctx.has_error());
  EXPECT_EQ(result, ascii);
}

TEST(SerializationTest, PrimitiveVectorReadsCheckBodyBeforeAllocation) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  Buffer buffer;
  buffer.write_var_uint32(4096);
  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(buffer);

  auto result = Serializer<std::vector<int32_t>>::read_data(read_ctx);

  EXPECT_TRUE(result.empty());
  EXPECT_TRUE(read_ctx.has_error());
}

TEST(SerializationTest, BoolVectorReadsCheckBodyBeforeAllocation) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  Buffer buffer;
  buffer.write_var_uint32(4096);
  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(buffer);

  auto result = Serializer<std::vector<bool>>::read_data(read_ctx);

  EXPECT_TRUE(result.empty());
  EXPECT_TRUE(read_ctx.has_error());
}

TEST(SerializationTest, PrimitiveVectorWriteChecksCompleteRange) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  WriteContext write_ctx(fory.config(), fory.type_resolver().clone());

  EXPECT_FALSE(detail::reserve_primitive_vector(
      write_ctx, std::numeric_limits<uint32_t>::max()));
  EXPECT_TRUE(write_ctx.has_error());
  EXPECT_EQ(write_ctx.buffer().writer_index(), 0U);
}

TEST(SerializationTest, FixedPrimitiveArrayRejectsWrongByteSize) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  Buffer buffer;
  buffer.write_var_uint32(sizeof(int32_t) + 1);
  buffer.write_uint32(1);
  buffer.write_uint8(0);
  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(buffer);

  auto result = Serializer<std::array<int32_t, 1>>::read_data(read_ctx);

  EXPECT_EQ(result[0], 0);
  ASSERT_TRUE(read_ctx.has_error());
  EXPECT_EQ(read_ctx.error().code(), ErrorCode::InvalidData);
}

TEST(SerializationTest, DecimalReadsCheckBodyBeforeAllocation) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  Buffer buffer;
  buffer.write_var_int32(0);
  buffer.write_var_uint64((static_cast<uint64_t>(4096) << 2) | 1ULL);
  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(buffer);

  auto result = Serializer<Decimal>::read_data(read_ctx);

  EXPECT_TRUE(result.is_zero());
  EXPECT_TRUE(read_ctx.has_error());
}

TEST(SerializationTest, DecimalDirectLimits) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();

  for (int32_t scale :
       {-detail::MAX_DECIMAL_SCALE, detail::MAX_DECIMAL_SCALE}) {
    Decimal original = Decimal::from_int64(1, scale);
    auto bytes = fory.serialize(original);
    ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
    auto decoded =
        fory.deserialize<Decimal>(bytes.value().data(), bytes.value().size());
    ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
    EXPECT_EQ(decoded.value(), original);
  }

  std::vector<uint8_t> max_magnitude(detail::MAX_DECIMAL_MAGNITUDE_BYTES, 0xFF);
  Decimal max_value(0, false, std::move(max_magnitude));
  auto max_bytes = fory.serialize(max_value);
  ASSERT_TRUE(max_bytes.ok()) << max_bytes.error().to_string();
  auto max_decoded = fory.deserialize<Decimal>(max_bytes.value().data(),
                                               max_bytes.value().size());
  ASSERT_TRUE(max_decoded.ok()) << max_decoded.error().to_string();
  EXPECT_EQ(max_decoded.value(), max_value);

  for (int32_t scale :
       {std::numeric_limits<int32_t>::min(), -detail::MAX_DECIMAL_SCALE - 1,
        detail::MAX_DECIMAL_SCALE + 1, std::numeric_limits<int32_t>::max()}) {
    WriteContext write_ctx(fory.config(), fory.type_resolver().clone());
    Serializer<Decimal>::write_data(Decimal::from_int64(1, scale), write_ctx);
    ASSERT_TRUE(write_ctx.has_error());
    EXPECT_EQ(write_ctx.buffer().writer_index(), 0);

    Buffer buffer;
    buffer.write_var_int32(scale);
    buffer.write_var_uint64(encode_decimal_zigzag64(1) << 1);
    ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
    read_ctx.attach(buffer);
    Decimal decoded = Serializer<Decimal>::read_data(read_ctx);
    EXPECT_TRUE(decoded.is_zero());
    ASSERT_TRUE(read_ctx.has_error());
    EXPECT_NE(read_ctx.error().to_string().find("scale exceeds"),
              std::string::npos);
  }

  std::vector<uint8_t> oversized_magnitude(
      detail::MAX_DECIMAL_MAGNITUDE_BYTES + 1, 0xFF);
  WriteContext write_ctx(fory.config(), fory.type_resolver().clone());
  Serializer<Decimal>::write_data(
      Decimal(0, false, std::move(oversized_magnitude)), write_ctx);
  ASSERT_TRUE(write_ctx.has_error());
  EXPECT_EQ(write_ctx.buffer().writer_index(), 0);

  Buffer buffer;
  buffer.write_var_int32(0);
  const uint64_t meta =
      (static_cast<uint64_t>(detail::MAX_DECIMAL_MAGNITUDE_BYTES + 1) << 1);
  buffer.write_var_uint64((meta << 1) | 1ULL);
  ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
  read_ctx.attach(buffer);
  Decimal decoded = Serializer<Decimal>::read_data(read_ctx);
  EXPECT_TRUE(decoded.is_zero());
  ASSERT_TRUE(read_ctx.has_error());
  EXPECT_NE(read_ctx.error().to_string().find("magnitude length exceeds"),
            std::string::npos);
}

TEST(SerializationTest, DurationRoundtrip) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  std::vector<Duration> values = {
      Duration(std::chrono::nanoseconds(0)),
      Duration(std::chrono::seconds(12) + std::chrono::nanoseconds(345678901)),
      Duration(-std::chrono::seconds(7) - std::chrono::milliseconds(45) -
               std::chrono::nanoseconds(67)),
      Duration(std::chrono::nanoseconds(-1)),
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  Date original(-1);

  EXPECT_EQ(original.days_since_epoch(), -1);

  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(serialize_result).value();
  Buffer expected;
  expected.write_uint8(0b1);
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();

  Buffer zero_big_encoding;
  zero_big_encoding.write_uint8(0b1);
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
  trailing_zero_payload.write_uint8(0b1);
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
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

  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  std::vector<TestCase> cases = {
      {Duration(std::chrono::nanoseconds(1234567890)), 1, 234567890},
      {Duration(std::chrono::nanoseconds(-1234567890)), -1, -234567890},
      {Duration(std::chrono::nanoseconds(-1)), 0, -1},
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

TEST(SerializationTest, DurationCarrierBounds) {
  struct TestCase {
    int64_t seconds;
    int32_t nanos;
    int64_t expected;
  };
  const std::vector<TestCase> valid = {
      {-9'223'372'037, 145'224'192, std::numeric_limits<int64_t>::min()},
      {9'223'372'036, 854'775'807, std::numeric_limits<int64_t>::max()},
      {9'223'372'037, -200'000'000, 9'223'372'036'800'000'000},
  };
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();

  for (const TestCase &test_case : valid) {
    Buffer buffer;
    buffer.write_var_int64(test_case.seconds);
    buffer.write_int32(test_case.nanos);
    ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
    read_ctx.attach(buffer);
    Duration value = Serializer<Duration>::read_data(read_ctx);
    ASSERT_FALSE(read_ctx.has_error()) << read_ctx.error().to_string();
    EXPECT_EQ(value.count(), test_case.expected);
  }

  for (const auto &parts :
       {std::pair<int64_t, int32_t>{-9'223'372'037, 145'224'191},
        std::pair<int64_t, int32_t>{9'223'372'036, 854'775'808}}) {
    Buffer buffer;
    buffer.write_var_int64(parts.first);
    buffer.write_int32(parts.second);
    ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
    read_ctx.attach(buffer);
    Serializer<Duration>::read_data(read_ctx);
    EXPECT_TRUE(read_ctx.has_error());
  }
}

TEST(SerializationTest, TimestampCarrierBounds) {
  struct TestCase {
    int64_t seconds;
    uint32_t nanos;
    int64_t expected;
  };
  const std::vector<TestCase> valid = {
      {-9'223'372'037, 145'224'192, std::numeric_limits<int64_t>::min()},
      {9'223'372'036, 854'775'807, std::numeric_limits<int64_t>::max()},
      {-9'223'372'038, 4'294'967'295, -9'223'372'033'705'032'705},
  };
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();

  for (const TestCase &test_case : valid) {
    Buffer buffer;
    buffer.write_int64(test_case.seconds);
    buffer.write_uint32(test_case.nanos);
    ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
    read_ctx.attach(buffer);
    Timestamp value = Serializer<Timestamp>::read_data(read_ctx);
    ASSERT_FALSE(read_ctx.has_error()) << read_ctx.error().to_string();
    EXPECT_EQ(value.time_since_epoch().count(), test_case.expected);
  }

  for (const auto &parts :
       {std::pair<int64_t, uint32_t>{-9'223'372'037, 145'224'191},
        std::pair<int64_t, uint32_t>{9'223'372'036, 854'775'808}}) {
    Buffer buffer;
    buffer.write_int64(parts.first);
    buffer.write_uint32(parts.second);
    ReadContext read_ctx(fory.config(), fory.type_resolver().clone());
    read_ctx.attach(buffer);
    Serializer<Timestamp>::read_data(read_ctx);
    EXPECT_TRUE(read_ctx.has_error());
  }
}

TEST(SerializationTest, ChronoTemporalBounds) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();

  Buffer duration_buffer;
  duration_buffer.write_var_int64(9'223'372'036);
  duration_buffer.write_int32(854'775'808);
  ReadContext duration_ctx(fory.config(), fory.type_resolver().clone());
  duration_ctx.attach(duration_buffer);
  Serializer<std::chrono::nanoseconds>::read_data(duration_ctx);
  EXPECT_TRUE(duration_ctx.has_error());

  using ChronoTimestamp = std::chrono::time_point<std::chrono::system_clock,
                                                  std::chrono::nanoseconds>;
  Buffer timestamp_buffer;
  timestamp_buffer.write_int64(-9'223'372'037);
  timestamp_buffer.write_uint32(145'224'191);
  ReadContext timestamp_ctx(fory.config(), fory.type_resolver().clone());
  timestamp_ctx.attach(timestamp_buffer);
  Serializer<ChronoTimestamp>::read_data(timestamp_ctx);
  EXPECT_TRUE(timestamp_ctx.has_error());
}

TEST(SerializationTest, DurationSkipConsumesSecondsAndNanosecondsPayload) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  WriteContext write_ctx(fory.config(), fory.type_resolver().clone());
  Serializer<Duration>::write_data(Duration(std::chrono::nanoseconds(-1)),
                                   write_ctx);
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

TEST(SerializationTest, ResetClearsWireReferenceSlots) {
  Config config;
  config.track_ref = false;
  ReadContext ctx(config, std::make_unique<TypeResolver>());
  Buffer buffer;
  buffer.write_int8(REF_VALUE_FLAG);
  ctx.attach(buffer);

  skip_field_value(ctx, FieldType(static_cast<uint32_t>(TypeId::NONE), false),
                   RefMode::Tracking);
  ASSERT_FALSE(ctx.has_error()) << ctx.error().to_string();
  EXPECT_EQ(ctx.ref_reader().reserve_ref_id(), 1U);

  ctx.detach();
  ctx.reset();
  EXPECT_EQ(ctx.ref_reader().reserve_ref_id(), 0U);
}

TEST(SerializationTest, SkipNestedCollectionsChecksDepth) {
  Config config;
  config.track_ref = false;
  config.max_dyn_depth = 1;
  ReadContext ctx(config, std::make_unique<TypeResolver>());

  FieldType scalar(static_cast<uint32_t>(TypeId::INT32), false);
  FieldType inner(static_cast<uint32_t>(TypeId::LIST), false, false,
                  {std::move(scalar)});
  FieldType outer(static_cast<uint32_t>(TypeId::LIST), false, false,
                  {std::move(inner)});

  Buffer buffer;
  buffer.write_var_uint32(1);
  buffer.write_uint8(0b1100);
  buffer.write_var_uint32(1);
  buffer.write_uint8(0b1100);
  buffer.write_int32(42);
  ctx.attach(buffer);

  skip_field_value(ctx, outer, RefMode::None);
  ASSERT_TRUE(ctx.has_error());
  EXPECT_EQ(ctx.error().code(), ErrorCode::DepthExceed);
  EXPECT_EQ(ctx.current_dyn_depth(), 1U);

  ctx.detach();
  ctx.reset();
  EXPECT_EQ(ctx.current_dyn_depth(), 0U);

  Buffer next_buffer;
  next_buffer.write_int32(42);
  ctx.attach(next_buffer);
  skip_field_value(ctx, FieldType(static_cast<uint32_t>(TypeId::INT32), false),
                   RefMode::None);
  ASSERT_FALSE(ctx.has_error()) << ctx.error().to_string();
  EXPECT_EQ(next_buffer.reader_index(), next_buffer.writer_index());
}

TEST(SerializationTest, SkipNoneListConsumesBudget) {
  Config config;
  ReadContext ctx(config, std::make_unique<TypeResolver>());
  Buffer buffer;
  buffer.write_var_uint32(std::numeric_limits<uint32_t>::max());
  buffer.write_uint8(0b1100);
  ctx.attach(buffer);

  FieldType list(static_cast<uint32_t>(TypeId::LIST), false, false,
                 {FieldType(static_cast<uint32_t>(TypeId::NONE), false)});
  skip_field_value(ctx, list, RefMode::None);
  ASSERT_TRUE(ctx.has_error());
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

TEST(SerializationTest, TruncatedWideCharsReturnZero) {
  Config config;
  Buffer buffer;

  ReadContext char16_ctx(config, std::make_unique<TypeResolver>());
  char16_ctx.attach(buffer);
  EXPECT_EQ(Serializer<char16_t>::read_data(char16_ctx), u'\0');
  ASSERT_TRUE(char16_ctx.has_error());
  EXPECT_EQ(char16_ctx.error().code(), ErrorCode::BufferOutOfBound);

  ReadContext char32_ctx(config, std::make_unique<TypeResolver>());
  char32_ctx.attach(buffer);
  EXPECT_EQ(Serializer<char32_t>::read_data(char32_ctx), U'\0');
  ASSERT_TRUE(char32_ctx.has_error());
  EXPECT_EQ(char32_ctx.error().code(), ErrorCode::BufferOutOfBound);
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  fory.register_enum<SignedScopedStatus>(1);

  auto bytes_result = fory.serialize(SignedScopedStatus::LARGE);
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  fory.register_enum<SignedScopedStatus>(1);

  auto bytes_result = fory.serialize(SignedScopedStatus::NEG);
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

  auto roundtrip =
      fory.deserialize<SignedScopedStatus>(bytes.data(), bytes.size());
  ASSERT_TRUE(roundtrip.ok())
      << "Deserialization failed: " << roundtrip.error().to_string();
  EXPECT_EQ(roundtrip.value(), SignedScopedStatus::NEG);
}

TEST(SerializationTest, EnumOrdinalMappingRejectsInvalidOrdinal) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  fory.register_enum<SignedScopedStatus>(1);

  auto bytes_result = fory.serialize(SignedScopedStatus::NEG);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  size_t offset = 1;
  // With registration, type_id + user_type_id take 2 bytes, ordinal is at
  // offset + 3 Replace the valid ordinal with an invalid one (99 as varuint32)
  bytes[offset + 3] = 99;

  auto decode =
      fory.deserialize<SignedScopedStatus>(bytes.data(), bytes.size());
  EXPECT_FALSE(decode.ok());
}

TEST(SerializationTest, SparseEnumSerializesExplicitValue) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
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

TEST(SerializationTest, UnorderedMapStringIntRoundtrip) {
  test_roundtrip(std::unordered_map<std::string, int32_t>{});
  test_roundtrip(std::unordered_map<std::string, int32_t>{{"one", 1}});
  test_roundtrip(std::unordered_map<std::string, int32_t>{
      {"one", 1}, {"two", 2}, {"three", 3}});
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
  auto fory = Fory::builder().xlang(true).compatible(true).build();

  uint8_t invalid_data[] = {0xFF, 0xFF, 0xFF};
  auto result = fory.deserialize<int32_t>(invalid_data, 3);
  EXPECT_FALSE(result.ok());
}

TEST(SerializationTest, DeserializeNullPointer) {
  auto fory = Fory::builder().xlang(true).compatible(true).build();
  auto result = fory.deserialize<int32_t>(nullptr, 0);
  EXPECT_FALSE(result.ok());
}

TEST(SerializationTest, DeserializeZeroSize) {
  auto fory = Fory::builder().xlang(true).compatible(true).build();
  uint8_t data[] = {0x01};
  auto result = fory.deserialize<int32_t>(data, 0);
  EXPECT_FALSE(result.ok());
}

TEST(SerializationTest, DeserializeRejectsXlangProtocolMismatch) {
  auto writer = Fory::builder().xlang(true).compatible(false).build();
  auto reader = Fory::builder().xlang(false).compatible(false).build();

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

TEST(SerializationTest, RootHeaderUsesXlangBitZero) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  auto bytes_result = fory.serialize<int32_t>(123);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();
  ASSERT_FALSE(bytes_result.value().empty());
  EXPECT_EQ(bytes_result.value()[0], 0x01);
}

TEST(SerializationTest, DeserializeRejectsRootHeaderReservedBits) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  auto bytes_result = fory.serialize<int32_t>(123);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  bytes[0] = 0x05;
  auto result = fory.deserialize<int32_t>(bytes.data(), bytes.size());
  ASSERT_FALSE(result.ok());
  EXPECT_EQ(result.error().code(), ErrorCode::InvalidData);
}

TEST(SerializationTest, DeserializeRejectsOutOfBandRootHeader) {
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  auto bytes_result = fory.serialize<int32_t>(123);
  ASSERT_TRUE(bytes_result.ok())
      << "Serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  bytes[0] = 0x03;
  auto result = fory.deserialize<int32_t>(bytes.data(), bytes.size());
  ASSERT_FALSE(result.ok());
  EXPECT_EQ(result.error().code(), ErrorCode::InvalidData);
}

TEST(SerializationTest, RegistrationByIdFailureDoesNotLeakTypeInfo) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
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
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  TypeResolver &resolver = fory.type_resolver();

  ASSERT_TRUE(fory.register_struct<::SimpleStruct>("demo.SharedType").ok());

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

  auto dotted_type_name =
      fory.register_struct<::NestedStruct>("demo", "Nested.Type");
  EXPECT_FALSE(dotted_type_name.ok());
  EXPECT_EQ(dotted_type_name.error().code(), ErrorCode::Invalid);
}

static std::vector<uint8_t> make_remote_type_meta(const std::string &type_name,
                                                  const std::string &field) {
  std::vector<FieldInfo> fields;
  fields.emplace_back(
      field, FieldType(static_cast<uint32_t>(TypeId::VARINT32), false));
  TypeMeta meta =
      TypeMeta::from_fields(static_cast<uint32_t>(TypeId::NAMED_STRUCT),
                            "example", type_name, true, 0, std::move(fields));
  auto bytes = meta.to_bytes();
  EXPECT_TRUE(bytes.ok()) << "TypeMeta serialization failed: "
                          << bytes.error().to_string();
  return bytes.value();
}

static std::vector<uint8_t>
make_remote_non_struct_type_meta(TypeId type_id, const std::string &type_name) {
  TypeMeta meta =
      TypeMeta::from_fields(static_cast<uint32_t>(type_id), "example",
                            type_name, true, 0, std::vector<FieldInfo>{});
  auto bytes = meta.to_bytes();
  EXPECT_TRUE(bytes.ok()) << "TypeMeta serialization failed: "
                          << bytes.error().to_string();
  return bytes.value();
}

static Result<const TypeInfo *, Error>
append_and_read_type_meta(ReadContext &ctx, const std::vector<uint8_t> &bytes) {
  Buffer buffer;
  buffer.write_var_uint32(0);
  buffer.write_bytes(bytes.data(), static_cast<uint32_t>(bytes.size()));
  ctx.reset();
  ctx.attach(buffer);
  auto result = ctx.read_type_meta();
  ctx.detach();
  return result;
}

static std::vector<uint8_t>
make_type_meta_hash_frame(const std::vector<uint8_t> &type_def,
                          uint32_t body_size, bool append_sentinel = false,
                          uint64_t low_flags = 0) {
  uint64_t header = 0;
  std::memcpy(&header, type_def.data(), sizeof(header));
  constexpr uint32_t kHashShift = 12;
  constexpr uint32_t kMetaSizeMask = 0xff;
  const uint32_t header_size = std::min(body_size, kMetaSizeMask);
  const uint64_t current_header =
      ((header >> kHashShift) << kHashShift) | low_flags | header_size;
  Buffer buffer;
  buffer.write_var_uint32(0);
  buffer.write_int64(static_cast<int64_t>(current_header));
  if (body_size >= kMetaSizeMask) {
    buffer.write_var_uint32(body_size - kMetaSizeMask);
  }
  std::vector<uint8_t> body(body_size, 0xa1);
  buffer.write_bytes(body.data(), static_cast<uint32_t>(body.size()));
  if (append_sentinel) {
    buffer.write_uint8(0xc3);
  }
  return buffer_bytes(buffer);
}

template <typename Expected>
static void expect_static_meta_ref_mismatch(ReadContext &ctx,
                                            const TypeInfo *first_type_info) {
  Buffer first;
  first.write_var_uint32(0);
  first.write_bytes(first_type_info->type_def.data(),
                    static_cast<uint32_t>(first_type_info->type_def.size()));
  ctx.reset();
  ctx.attach(first);
  auto first_result = ctx.read_type_meta();
  ASSERT_TRUE(first_result.ok()) << first_result.error().to_string();
  EXPECT_EQ(first_result.value(), first_type_info);
  ctx.detach();

  Buffer reference;
  reference.write_uint8(static_cast<uint8_t>(first_type_info->type_id));
  reference.write_var_uint32(1);
  ctx.attach(reference);
  Serializer<Expected>::read_type_info(ctx);
  EXPECT_TRUE(ctx.has_error());
  ctx.detach();
}

template <typename Expected>
static void expect_static_meta_cold_mismatch(ReadContext &ctx,
                                             const TypeInfo *remote_type_info) {
  Buffer frame;
  frame.write_uint8(static_cast<uint8_t>(remote_type_info->type_id));
  frame.write_var_uint32(0);
  frame.write_bytes(remote_type_info->type_def.data(),
                    static_cast<uint32_t>(remote_type_info->type_def.size()));
  ctx.reset();
  ctx.attach(frame);
  Serializer<Expected>::read_type_info(ctx);
  EXPECT_TRUE(ctx.has_error());
  ctx.detach();

  Buffer reference;
  reference.write_var_uint32(1);
  ctx.attach(reference);
  auto leaked_reference = ctx.read_type_meta();
  EXPECT_FALSE(leaked_reference.ok());
  ctx.detach();
}

template <typename Expected>
static void
expect_cached_owner_mismatch(ReadContext &ctx, TypeId remote_type_id,
                             const std::vector<uint8_t> &remote_type_meta) {
  auto cached = append_and_read_type_meta(ctx, remote_type_meta);
  ASSERT_TRUE(cached.ok()) << cached.error().to_string();

  Buffer frame;
  frame.write_uint8(static_cast<uint8_t>(remote_type_id));
  frame.write_var_uint32(0);
  frame.write_bytes(remote_type_meta.data(),
                    static_cast<uint32_t>(remote_type_meta.size()));
  ctx.reset();
  ctx.attach(frame);
  Serializer<Expected>::read_type_info(ctx);
  EXPECT_TRUE(ctx.has_error());
  ctx.detach();

  Buffer reference;
  reference.write_var_uint32(1);
  ctx.attach(reference);
  auto leaked_reference = ctx.read_type_meta();
  EXPECT_FALSE(leaked_reference.ok());
  ctx.detach();
}

template <typename Collection>
static void
read_collection_owner_mismatch(ReadContext &ctx,
                               const std::vector<uint8_t> &remote_type_meta,
                               uint32_t marker, bool reset_context) {
  Buffer frame;
  frame.write_var_uint32(1);
  frame.write_uint8(COLL_IS_SAME_TYPE);
  frame.write_uint8(static_cast<uint8_t>(TypeId::NAMED_STRUCT));
  frame.write_var_uint32(marker);
  if ((marker & 1) == 0) {
    frame.write_bytes(remote_type_meta.data(),
                      static_cast<uint32_t>(remote_type_meta.size()));
  }
  if (reset_context) {
    ctx.reset();
  }
  ctx.attach(frame);
  auto value = Serializer<Collection>::read_data(ctx);
  EXPECT_TRUE(value.empty());
  EXPECT_TRUE(ctx.has_error());
  ctx.detach();
}

template <typename Map>
static void
read_map_owner_mismatch(ReadContext &ctx,
                        const std::vector<uint8_t> &remote_type_meta,
                        uint32_t marker, bool reset_context) {
  Buffer frame;
  frame.write_var_uint32(1);
  frame.write_uint8(DECL_KEY_TYPE);
  frame.write_uint8(1);
  frame.write_uint8(static_cast<uint8_t>(TypeId::NAMED_STRUCT));
  frame.write_var_uint32(marker);
  if ((marker & 1) == 0) {
    frame.write_bytes(remote_type_meta.data(),
                      static_cast<uint32_t>(remote_type_meta.size()));
  }
  if (reset_context) {
    ctx.reset();
  }
  ctx.attach(frame);
  auto value = Serializer<Map>::read_data(ctx);
  EXPECT_TRUE(value.empty());
  EXPECT_TRUE(ctx.has_error());
  ctx.detach();
}

static void expect_missing_meta_ref(ReadContext &ctx, uint32_t marker) {
  Buffer reference;
  reference.write_var_uint32(marker);
  ctx.attach(reference);
  auto leaked_reference = ctx.read_type_meta();
  EXPECT_FALSE(leaked_reference.ok());
  ctx.detach();
}

TEST(SerializationTest, TypeMetaCachesUseHeaderHash) {
  std::vector<uint8_t> first_bytes =
      make_remote_type_meta("CachedA", "first_value");
  std::vector<uint8_t> second_bytes =
      make_remote_type_meta("CachedB", "second_value");
  ASSERT_GT(first_bytes.size(), sizeof(uint64_t));
  const size_t max_body_size =
      std::max(first_bytes.size(), second_bytes.size()) - sizeof(uint64_t);

  Config config;
  config.compatible = true;
  config.max_type_meta_bytes = static_cast<uint32_t>(max_body_size);
  ReadContext ctx(config, std::make_unique<TypeResolver>());

  auto first = append_and_read_type_meta(ctx, first_bytes);
  ASSERT_TRUE(first.ok()) << first.error().to_string();
  auto second = append_and_read_type_meta(ctx, second_bytes);
  ASSERT_TRUE(second.ok()) << second.error().to_string();

  constexpr uint32_t kCurrentBodySize = 0x100;
  constexpr uint64_t kCurrentFlags = 0x0f00;
  auto hit_bytes = make_type_meta_hash_frame(first_bytes, kCurrentBodySize,
                                             true, kCurrentFlags);
  Buffer encoded_hit(hit_bytes);
  Error header_error;
  EXPECT_EQ(encoded_hit.read_var_uint32(header_error), 0U);
  const uint64_t current_header =
      static_cast<uint64_t>(encoded_hit.read_int64(header_error));
  EXPECT_EQ(current_header & 0xffU, 0xffU);
  EXPECT_EQ(current_header & kCurrentFlags, kCurrentFlags);
  EXPECT_EQ(encoded_hit.read_var_uint32(header_error), 1U);
  ASSERT_TRUE(header_error.ok()) << header_error.to_string();

  Buffer hit_buffer(hit_bytes);
  ctx.reset();
  ctx.attach(hit_buffer);
  auto hit = ctx.read_type_meta();
  ASSERT_TRUE(hit.ok()) << hit.error().to_string();
  EXPECT_EQ(hit.value(), first.value());
  Error error;
  EXPECT_EQ(ctx.read_uint8(error), 0xc3);
  EXPECT_TRUE(error.ok()) << error.to_string();
  ctx.detach();
}

TEST(SerializationTest, TypeMetaHashHitChecksBounds) {
  std::vector<uint8_t> bytes = make_remote_type_meta("CachedBounds", "value");
  ASSERT_GT(bytes.size(), sizeof(uint64_t));
  const uint32_t body_size =
      static_cast<uint32_t>(bytes.size() - sizeof(uint64_t));
  ASSERT_LT(body_size + 1, 0xffU);

  Config config;
  config.compatible = true;
  config.max_type_meta_bytes = body_size;
  ReadContext ctx(config, std::make_unique<TypeResolver>());
  auto first = append_and_read_type_meta(ctx, bytes);
  ASSERT_TRUE(first.ok()) << first.error().to_string();

  uint64_t header = 0;
  std::memcpy(&header, bytes.data(), sizeof(header));
  constexpr uint32_t kHashShift = 12;
  const uint32_t current_body_size = body_size + 1;
  const uint64_t current_header =
      ((header >> kHashShift) << kHashShift) | current_body_size;

  Buffer truncated_builder;
  truncated_builder.write_var_uint32(0);
  truncated_builder.write_int64(static_cast<int64_t>(current_header));
  std::vector<uint8_t> body(current_body_size - 1, 0xa1);
  truncated_builder.write_bytes(body.data(),
                                static_cast<uint32_t>(body.size()));
  std::vector<uint8_t> truncated_bytes = buffer_bytes(truncated_builder);
  Buffer truncated(truncated_bytes);
  ctx.reset();
  ctx.attach(truncated);
  auto hit = ctx.read_type_meta();
  ASSERT_FALSE(hit.ok());
  EXPECT_EQ(hit.error().code(), ErrorCode::BufferOutOfBound);
  ctx.detach();

  Buffer reference;
  reference.write_var_uint32(1);
  ctx.attach(reference);
  auto leaked_reference = ctx.read_type_meta();
  EXPECT_FALSE(leaked_reference.ok());
  ctx.detach();
}

TEST(SerializationTest, ExactLocalTypeMetaIsRootLocal) {
  auto fory = Fory::builder().xlang(true).compatible(true).build();
  ASSERT_TRUE(
      fory.register_enum<SignedScopedStatus>("example", "LocalOnly").ok());
  auto local_type_info =
      fory.type_resolver().get_type_info<SignedScopedStatus>();
  ASSERT_TRUE(local_type_info.ok()) << local_type_info.error().to_string();
  const std::vector<uint8_t> &exact = local_type_info.value()->type_def;
  ASSERT_GT(exact.size(), sizeof(uint64_t));
  const uint32_t exact_body_size =
      static_cast<uint32_t>(exact.size() - sizeof(uint64_t));

  Config config = fory.config();
  config.max_type_meta_bytes = exact_body_size;
  ReadContext ctx(config, fory.type_resolver().clone());
  auto first = append_and_read_type_meta(ctx, exact);
  ASSERT_TRUE(first.ok()) << first.error().to_string();

  auto changed_frame = make_type_meta_hash_frame(exact, exact_body_size + 1);
  Buffer changed(changed_frame);
  ctx.reset();
  ctx.attach(changed);
  auto second = ctx.read_type_meta();
  EXPECT_FALSE(second.ok());
  if (!second.ok()) {
    EXPECT_EQ(second.error().code(), ErrorCode::InvalidData);
  }
  ctx.detach();
}

TEST(SerializationTest, ExpectedLocalTypeMetaStaysRootLocal) {
  using UnionType = std::variant<int32_t, std::string>;
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .max_schema_versions_per_type(1)
                  .build();
  ASSERT_TRUE(
      fory.register_struct<SimpleStruct>("example", "ExpectedStruct").ok());
  ASSERT_TRUE(
      fory.register_enum<SignedScopedStatus>("example", "ExpectedEnum").ok());
  ASSERT_TRUE(
      fory.register_extension_type<IdLimitExt>("example", "ExpectedExt").ok());
  ASSERT_TRUE(fory.register_union<UnionType>("example", "ExpectedUnion").ok());
  auto finalized = fory.serialize(SimpleStruct{});
  ASSERT_TRUE(finalized.ok()) << finalized.error().to_string();

  ReadContext ctx(fory.config(), fory.type_resolver().clone());
  auto struct_info = ctx.type_resolver().get_type_info<SimpleStruct>();
  auto enum_info = ctx.type_resolver().get_type_info<SignedScopedStatus>();
  auto ext_info = ctx.type_resolver().get_type_info<IdLimitExt>();
  auto union_info = ctx.type_resolver().get_type_info<UnionType>();
  ASSERT_TRUE(struct_info.ok());
  ASSERT_TRUE(enum_info.ok());
  ASSERT_TRUE(ext_info.ok());
  ASSERT_TRUE(union_info.ok());

  auto check_local_hit = [&](const TypeInfo *expected) {
    ASSERT_GT(expected->type_def.size(), sizeof(uint64_t));
    const uint32_t body_size =
        static_cast<uint32_t>(expected->type_def.size() - sizeof(uint64_t) + 1);
    ASSERT_LT(body_size, 0xffU);
    auto frame_bytes =
        make_type_meta_hash_frame(expected->type_def, body_size, true, 0x0f00);
    Buffer frame(frame_bytes);
    ctx.reset();
    ctx.attach(frame);
    auto result = Serializer<TypeMetaTestReader>::read_expected(ctx, expected);
    ASSERT_TRUE(result.ok()) << result.error().to_string();
    EXPECT_EQ(result.value(), expected);
    Error error;
    EXPECT_EQ(ctx.read_uint8(error), 0xc3);
    EXPECT_TRUE(error.ok()) << error.to_string();
    ctx.detach();

    Buffer dynamic_frame(frame_bytes);
    ctx.reset();
    ctx.attach(dynamic_frame);
    auto dynamic_result = ctx.read_type_meta();
    EXPECT_FALSE(dynamic_result.ok());
    ctx.detach();
  };

  check_local_hit(struct_info.value());
  check_local_hit(enum_info.value());
  check_local_hit(ext_info.value());
  check_local_hit(union_info.value());

  auto enum_remote = append_and_read_type_meta(
      ctx, make_remote_non_struct_type_meta(TypeId::NAMED_EXT, "ExpectedEnum"));
  EXPECT_TRUE(enum_remote.ok()) << enum_remote.error().to_string();
  auto ext_remote = append_and_read_type_meta(
      ctx, make_remote_non_struct_type_meta(TypeId::NAMED_ENUM, "ExpectedExt"));
  EXPECT_TRUE(ext_remote.ok()) << ext_remote.error().to_string();
  auto union_remote = append_and_read_type_meta(
      ctx,
      make_remote_non_struct_type_meta(TypeId::NAMED_EXT, "ExpectedUnion"));
  EXPECT_TRUE(union_remote.ok()) << union_remote.error().to_string();
}

TEST(SerializationTest, LocalTypeMetaPrecedesRemoteCache) {
  auto fory = Fory::builder().xlang(true).compatible(true).build();
  ASSERT_TRUE(
      fory.register_enum<SignedScopedStatus>("example", "WarmLocal").ok());
  auto finalized = fory.serialize(SignedScopedStatus::ZERO);
  ASSERT_TRUE(finalized.ok()) << finalized.error().to_string();
  auto expected = fory.type_resolver().get_type_info<SignedScopedStatus>();
  ASSERT_TRUE(expected.ok()) << expected.error().to_string();
  const std::vector<uint8_t> &type_def = expected.value()->type_def;
  ASSERT_GT(type_def.size(), sizeof(uint64_t));

  Config config;
  config.compatible = true;
  ReadContext ctx(config, std::make_unique<TypeResolver>());
  auto cached = append_and_read_type_meta(ctx, type_def);
  ASSERT_TRUE(cached.ok()) << cached.error().to_string();
  ASSERT_NE(cached.value(), expected.value());

  const uint32_t body_size =
      static_cast<uint32_t>(type_def.size() - sizeof(uint64_t) + 1);
  auto frame_bytes =
      make_type_meta_hash_frame(type_def, body_size, true, 0x0f00);
  Buffer frame(frame_bytes);
  ctx.reset();
  ctx.attach(frame);
  auto local =
      Serializer<TypeMetaTestReader>::read_expected(ctx, expected.value());
  ASSERT_TRUE(local.ok()) << local.error().to_string();
  EXPECT_EQ(local.value(), expected.value());
  Error error;
  EXPECT_EQ(ctx.read_uint8(error), 0xc3);
  EXPECT_TRUE(error.ok()) << error.to_string();
  ctx.detach();
}

TEST(SerializationTest, StaticTypeMetaChecksOwner) {
  using UnionA = std::variant<int32_t, std::string>;
  using UnionB = std::variant<int64_t, std::string>;
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .max_schema_versions_per_type(1)
                  .build();
  ASSERT_TRUE(fory.register_struct<SimpleStruct>("example", "StructA").ok());
  ASSERT_TRUE(fory.register_struct<ComplexStruct>("example", "StructB").ok());
  ASSERT_TRUE(fory.register_enum<SignedScopedStatus>("example", "EnumA").ok());
  ASSERT_TRUE(fory.register_enum<SparseStatus>("example", "EnumB").ok());
  ASSERT_TRUE(fory.register_union<UnionA>("example", "UnionA").ok());
  ASSERT_TRUE(fory.register_union<UnionB>("example", "UnionB").ok());
  auto finalized = fory.serialize(SimpleStruct{});
  ASSERT_TRUE(finalized.ok()) << finalized.error().to_string();

  ReadContext ctx(fory.config(), fory.type_resolver().clone());
  auto struct_b = ctx.type_resolver().get_type_info<ComplexStruct>();
  auto enum_b = ctx.type_resolver().get_type_info<SparseStatus>();
  auto union_b = ctx.type_resolver().get_type_info<UnionB>();
  ASSERT_TRUE(struct_b.ok());
  ASSERT_TRUE(enum_b.ok());
  ASSERT_TRUE(union_b.ok());

  expect_static_meta_ref_mismatch<SimpleStruct>(ctx, struct_b.value());
  expect_static_meta_ref_mismatch<SignedScopedStatus>(ctx, enum_b.value());
  expect_static_meta_ref_mismatch<UnionA>(ctx, union_b.value());

  expect_static_meta_cold_mismatch<SimpleStruct>(ctx, struct_b.value());
  auto struct_remote = append_and_read_type_meta(
      ctx, make_remote_type_meta("StructB", "remote_value"));
  EXPECT_TRUE(struct_remote.ok()) << struct_remote.error().to_string();

  expect_static_meta_cold_mismatch<SignedScopedStatus>(ctx, enum_b.value());
  auto enum_remote = append_and_read_type_meta(
      ctx, make_remote_non_struct_type_meta(TypeId::NAMED_EXT, "EnumB"));
  EXPECT_TRUE(enum_remote.ok()) << enum_remote.error().to_string();

  expect_static_meta_cold_mismatch<UnionA>(ctx, union_b.value());
  auto union_remote = append_and_read_type_meta(
      ctx, make_remote_non_struct_type_meta(TypeId::NAMED_EXT, "UnionB"));
  EXPECT_TRUE(union_remote.ok()) << union_remote.error().to_string();
}

TEST(SerializationTest, CachedTypeMetaChecksOwner) {
  using UnionA = std::variant<int32_t, std::string>;
  auto fory = Fory::builder().xlang(true).compatible(true).build();
  ASSERT_TRUE(fory.register_struct<SimpleStruct>("example", "CacheA").ok());
  ASSERT_TRUE(
      fory.register_enum<SignedScopedStatus>("example", "CacheEnumA").ok());
  ASSERT_TRUE(fory.register_union<UnionA>("example", "CacheUnionA").ok());
  auto finalized = fory.serialize(SimpleStruct{});
  ASSERT_TRUE(finalized.ok()) << finalized.error().to_string();
  ReadContext ctx(fory.config(), fory.type_resolver().clone());

  expect_cached_owner_mismatch<SimpleStruct>(
      ctx, TypeId::NAMED_STRUCT,
      make_remote_type_meta("RemoteStructB", "remote_value"));
  expect_cached_owner_mismatch<SignedScopedStatus>(
      ctx, TypeId::NAMED_ENUM,
      make_remote_non_struct_type_meta(TypeId::NAMED_ENUM, "RemoteEnumB"));
  expect_cached_owner_mismatch<UnionA>(
      ctx, TypeId::NAMED_UNION,
      make_remote_non_struct_type_meta(TypeId::NAMED_UNION, "RemoteUnionB"));
}

TEST(SerializationTest, StaticCollectionChecksOwner) {
  using Collection = std::vector<std::shared_ptr<SimpleStruct>>;
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .max_schema_versions_per_type(1)
                  .build();
  ASSERT_TRUE(
      fory.register_struct<SimpleStruct>("example", "CollectionA").ok());
  ASSERT_TRUE(
      fory.register_struct<ComplexStruct>("example", "CollectionB").ok());
  auto finalized = fory.serialize(SimpleStruct{});
  ASSERT_TRUE(finalized.ok()) << finalized.error().to_string();

  auto first = make_remote_type_meta("CollectionB", "first_remote");
  auto second = make_remote_type_meta("CollectionB", "second_remote");

  {
    ReadContext ctx(fory.config(), fory.type_resolver().clone());
    read_collection_owner_mismatch<Collection>(ctx, first, 0, true);
    expect_missing_meta_ref(ctx, 1);
    auto accepted = append_and_read_type_meta(ctx, second);
    ASSERT_TRUE(accepted.ok()) << accepted.error().to_string();
  }

  {
    ReadContext ctx(fory.config(), fory.type_resolver().clone());
    auto cached = append_and_read_type_meta(ctx, first);
    ASSERT_TRUE(cached.ok()) << cached.error().to_string();
    read_collection_owner_mismatch<Collection>(ctx, first, 0, true);
    expect_missing_meta_ref(ctx, 1);
  }

  {
    ReadContext ctx(fory.config(), fory.type_resolver().clone());
    auto first_root = append_and_read_type_meta(ctx, first);
    ASSERT_TRUE(first_root.ok()) << first_root.error().to_string();
    read_collection_owner_mismatch<Collection>(ctx, {}, 1, false);
    expect_missing_meta_ref(ctx, 3);
  }
}

TEST(SerializationTest, StaticMapChecksOwner) {
  using Map = std::map<int32_t, SimpleStruct>;
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .max_schema_versions_per_type(1)
                  .build();
  ASSERT_TRUE(fory.register_struct<SimpleStruct>("example", "MapA").ok());
  ASSERT_TRUE(fory.register_struct<ComplexStruct>("example", "MapB").ok());
  auto finalized = fory.serialize(SimpleStruct{});
  ASSERT_TRUE(finalized.ok()) << finalized.error().to_string();

  auto first = make_remote_type_meta("MapB", "first_remote");
  auto second = make_remote_type_meta("MapB", "second_remote");

  {
    ReadContext ctx(fory.config(), fory.type_resolver().clone());
    read_map_owner_mismatch<Map>(ctx, first, 0, true);
    expect_missing_meta_ref(ctx, 1);
    auto accepted = append_and_read_type_meta(ctx, second);
    ASSERT_TRUE(accepted.ok()) << accepted.error().to_string();
  }

  {
    ReadContext ctx(fory.config(), fory.type_resolver().clone());
    auto cached = append_and_read_type_meta(ctx, first);
    ASSERT_TRUE(cached.ok()) << cached.error().to_string();
    read_map_owner_mismatch<Map>(ctx, first, 0, true);
    expect_missing_meta_ref(ctx, 1);
  }

  {
    ReadContext ctx(fory.config(), fory.type_resolver().clone());
    auto first_root = append_and_read_type_meta(ctx, first);
    ASSERT_TRUE(first_root.ok()) << first_root.error().to_string();
    read_map_owner_mismatch<Map>(ctx, {}, 1, false);
    expect_missing_meta_ref(ctx, 3);
  }
}

TEST(SerializationTest, RemoteSchemaLimitRejectsExtraVersions) {
  Config config;
  config.compatible = true;
  config.max_schema_versions_per_type = 1;
  ReadContext ctx(config, std::make_unique<TypeResolver>());

  auto first = append_and_read_type_meta(
      ctx, make_remote_type_meta("Unknown", "first_value"));
  ASSERT_TRUE(first.ok()) << first.error().to_string();

  auto second = append_and_read_type_meta(
      ctx, make_remote_type_meta("Unknown", "second_value"));
  EXPECT_FALSE(second.ok());
  ASSERT_FALSE(second.ok());
  EXPECT_EQ(second.error().code(), ErrorCode::InvalidData);
}

TEST(SerializationTest, RemoteNonStructTypeMetaUsesSchemaLimit) {
  Config config;
  config.compatible = true;
  config.max_schema_versions_per_type = 1;
  ReadContext ctx(config, std::make_unique<TypeResolver>());

  auto first = append_and_read_type_meta(
      ctx, make_remote_non_struct_type_meta(TypeId::NAMED_ENUM, "RemoteEnum"));
  ASSERT_TRUE(first.ok()) << first.error().to_string();

  auto second = append_and_read_type_meta(
      ctx, make_remote_non_struct_type_meta(TypeId::NAMED_EXT, "RemoteEnum"));
  EXPECT_FALSE(second.ok());
  ASSERT_FALSE(second.ok());
  EXPECT_EQ(second.error().code(), ErrorCode::InvalidData);
}

TEST(SerializationTest, ExactLocalNonStructTypeMetaBypassesLimit) {
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .max_schema_versions_per_type(1)
                  .build();
  ASSERT_TRUE(
      fory.register_enum<SignedScopedStatus>("example", "SharedEnum").ok());
  auto local_type_info =
      fory.type_resolver().get_type_info<SignedScopedStatus>();
  ASSERT_TRUE(local_type_info.ok()) << local_type_info.error().to_string();
  std::vector<uint8_t> exact = local_type_info.value()->type_def;
  ReadContext ctx(fory.config(), fory.type_resolver().clone());

  auto first = append_and_read_type_meta(ctx, exact);
  ASSERT_TRUE(first.ok()) << first.error().to_string();
  auto second = append_and_read_type_meta(
      ctx, make_remote_non_struct_type_meta(TypeId::NAMED_EXT, "SharedEnum"));
  ASSERT_TRUE(second.ok()) << second.error().to_string();
}

TEST(SerializationTest, ExactLocalNamedUnionTypeMetaBypassesLimit) {
  using LocalUnion = std::variant<int32_t, std::string>;
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .max_schema_versions_per_type(1)
                  .build();
  ASSERT_TRUE(fory.register_union<LocalUnion>("example", "SharedUnion").ok());
  auto local_type_info = fory.type_resolver().get_type_info<LocalUnion>();
  ASSERT_TRUE(local_type_info.ok()) << local_type_info.error().to_string();
  std::vector<uint8_t> exact = local_type_info.value()->type_def;
  ASSERT_FALSE(exact.empty());
  ReadContext ctx(fory.config(), fory.type_resolver().clone());

  auto first = append_and_read_type_meta(ctx, exact);
  ASSERT_TRUE(first.ok()) << first.error().to_string();
  auto second = append_and_read_type_meta(
      ctx, make_remote_non_struct_type_meta(TypeId::NAMED_EXT, "SharedUnion"));
  ASSERT_TRUE(second.ok()) << second.error().to_string();
}

TEST(SerializationTest, RemoteSchemaLimitKeepsUnknownTypesSeparate) {
  Config config;
  config.compatible = true;
  config.max_schema_versions_per_type = 1;
  ReadContext ctx(config, std::make_unique<TypeResolver>());

  auto first = append_and_read_type_meta(
      ctx, make_remote_type_meta("UnknownA", "value"));
  ASSERT_TRUE(first.ok()) << first.error().to_string();
  auto second = append_and_read_type_meta(
      ctx, make_remote_type_meta("UnknownB", "value"));
  EXPECT_TRUE(second.ok()) << second.error().to_string();
}

TEST(SerializationTest, RemoteSchemaKeyLimitPersists) {
  constexpr uint32_t kKeyLimit = 8192;
  Config config;
  config.compatible = true;
  config.max_schema_versions_per_type = 2;
  ReadContext ctx(config, std::make_unique<TypeResolver>());

  std::vector<uint8_t> first_bytes;
  for (uint32_t i = 0; i < kKeyLimit; ++i) {
    auto bytes = make_remote_type_meta("Remote" + std::to_string(i), "value");
    if (i == 0) {
      first_bytes = bytes;
    }
    auto accepted = append_and_read_type_meta(ctx, bytes);
    ASSERT_TRUE(accepted.ok()) << i << ": " << accepted.error().to_string();
  }

  auto rejected_bytes = make_remote_type_meta("RemoteOverflow", "value");
  auto rejected = append_and_read_type_meta(ctx, rejected_bytes);
  ASSERT_FALSE(rejected.ok());
  EXPECT_EQ(rejected.error().code(), ErrorCode::InvalidData);
  EXPECT_NE(rejected.error().message().find("logical type limit"),
            std::string::npos);

  auto rejected_again = append_and_read_type_meta(ctx, rejected_bytes);
  ASSERT_FALSE(rejected_again.ok());
  EXPECT_EQ(rejected_again.error().code(), ErrorCode::InvalidData);

  auto existing_version = append_and_read_type_meta(
      ctx, make_remote_type_meta("Remote0", "second_value"));
  ASSERT_TRUE(existing_version.ok()) << existing_version.error().to_string();

  auto cached_hit = append_and_read_type_meta(ctx, first_bytes);
  ASSERT_TRUE(cached_hit.ok()) << cached_hit.error().to_string();
}

TEST(SerializationTest, IdEnumDoesNotUseTypeMetaLimits) {
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .max_type_meta_bytes(1)
                  .max_schema_versions_per_type(1)
                  .build();
  ASSERT_TRUE(fory.register_enum<SignedScopedStatus>(101).ok());

  auto bytes = fory.serialize(SignedScopedStatus::LARGE);
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
  std::vector<uint8_t> payload = std::move(bytes).value();
  auto decoded =
      fory.deserialize<SignedScopedStatus>(payload.data(), payload.size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(decoded.value(), SignedScopedStatus::LARGE);
}

TEST(SerializationTest, IdExtDoesNotUseTypeMetaLimits) {
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .max_type_meta_bytes(1)
                  .max_schema_versions_per_type(1)
                  .build();
  ASSERT_TRUE(fory.register_extension_type<IdLimitExt>(102).ok());

  auto bytes = fory.serialize(IdLimitExt{42});
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
  std::vector<uint8_t> payload = std::move(bytes).value();
  auto decoded = fory.deserialize<IdLimitExt>(payload.data(), payload.size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(decoded.value(), IdLimitExt{42});
}

TEST(SerializationTest, LocalTypeMetaFinalizationIgnoresReceiveBodyLimit) {
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .max_type_meta_bytes(1)
                  .build();
  ASSERT_TRUE(fory.register_struct<SimpleStruct>(103).ok());

  auto bytes = fory.serialize(SimpleStruct{1, 2});
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
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

TEST(SerializationTest, TypeMetaHeaderUses52BitMetadataHash) {
  std::vector<FieldInfo> fields;
  fields.emplace_back(
      "value", FieldType(static_cast<uint32_t>(TypeId::VARINT32), false));
  TypeMeta meta = TypeMeta::from_fields(static_cast<uint32_t>(TypeId::STRUCT),
                                        "", "S", false, 1, std::move(fields));
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();

  const std::vector<uint8_t> &bytes = bytes_result.value();
  ASSERT_GT(bytes.size(), sizeof(uint64_t));
  uint64_t header = 0;
  std::memcpy(&header, bytes.data(), sizeof(header));

  constexpr uint64_t kMetaSizeMask = 0xff;
  constexpr uint64_t kCompressMetaFlag = 0x100;
  constexpr uint64_t kReservedBitsMask = 0xe00;
  constexpr uint32_t kHashShift = 12;

  EXPECT_EQ(header & kCompressMetaFlag, 0);
  EXPECT_EQ(header & kReservedBitsMask, 0);
  ASSERT_NE(header & kMetaSizeMask, kMetaSizeMask);
  uint64_t meta_size = header & kMetaSizeMask;
  ASSERT_EQ(bytes.size(), sizeof(uint64_t) + meta_size);
  ASSERT_GT(meta_size, 0);
  uint8_t body_header = bytes[sizeof(uint64_t)];
  EXPECT_EQ(body_header & 0x80, 0x80);
  EXPECT_EQ(body_header & 0x40, 0);
  EXPECT_EQ(body_header & 0x20, 0);
  EXPECT_EQ(body_header & 0x1F, 1);

  std::vector<uint8_t> parse_bytes = bytes;
  Buffer buffer(parse_bytes);
  auto parsed = TypeMeta::from_bytes(buffer, nullptr);
  ASSERT_TRUE(parsed.ok()) << parsed.error().to_string();
  EXPECT_EQ(static_cast<int64_t>(header >> kHashShift),
            parsed.value()->get_hash());
}

TEST(SerializationTest, TypeMetaParsesDeepFieldTypeIteratively) {
  constexpr uint32_t kDepth = 4000;
  constexpr uint64_t kMetaSizeMask = 0xff;

  Buffer body;
  body.write_uint8(0x81);
  body.write_var_uint32(1);
  body.write_uint8(0xc0);
  body.write_uint8(static_cast<uint8_t>(TypeId::LIST));
  for (uint32_t i = 1; i < kDepth; ++i) {
    body.write_var_uint32(static_cast<uint32_t>(TypeId::LIST) << 2);
  }
  body.write_var_uint32(static_cast<uint32_t>(TypeId::NONE) << 2);
  ASSERT_LE(body.writer_index(), 4096U);

  const uint32_t meta_size = body.writer_index();
  uint64_t header = std::min<uint64_t>(kMetaSizeMask, meta_size);
  header |=
      compute_type_meta_hash_bits_for_test(body.data(), meta_size, header);

  Buffer encoded;
  encoded.write_bytes(reinterpret_cast<const uint8_t *>(&header),
                      sizeof(header));
  encoded.write_var_uint32(meta_size - kMetaSizeMask);
  encoded.write_bytes(body.data(), meta_size);

  auto parsed = TypeMeta::from_bytes(encoded, nullptr);
  ASSERT_TRUE(parsed.ok()) << parsed.error().to_string();
  EXPECT_EQ(encoded.reader_index(), encoded.writer_index());
  ASSERT_EQ(parsed.value()->field_infos.size(), 1U);

  const FieldType *field_type = &parsed.value()->field_infos.front().field_type;
  for (uint32_t i = 0; i < kDepth; ++i) {
    ASSERT_EQ(field_type->type_id, static_cast<uint32_t>(TypeId::LIST));
    ASSERT_EQ(field_type->generics.size(), 1U);
    field_type = &field_type->generics.front();
  }
  EXPECT_EQ(field_type->type_id, static_cast<uint32_t>(TypeId::NONE));
  EXPECT_TRUE(field_type->generics.empty());

  uint64_t expected_fingerprint = FieldType::compute_compatible_fingerprint(
      static_cast<uint32_t>(TypeId::NONE), {});
  std::vector<FieldType> child(1);
  for (uint32_t i = 0; i < kDepth; ++i) {
    child[0].compatible_fingerprint = expected_fingerprint;
    expected_fingerprint = FieldType::compute_compatible_fingerprint(
        static_cast<uint32_t>(TypeId::LIST), child);
  }
  EXPECT_EQ(
      parsed.value()->field_infos.front().field_type.compatible_fingerprint,
      expected_fingerprint);
}

TEST(SerializationTest, TypeMetaCannotReadPastDeclaredBody) {
  Buffer body;
  body.write_uint8(0x81);
  body.write_var_uint32(1);
  body.write_uint8(0xc0);
  body.write_uint8(static_cast<uint8_t>(TypeId::LIST));

  const uint32_t meta_size = body.writer_index();
  uint64_t header = meta_size;
  header |=
      compute_type_meta_hash_bits_for_test(body.data(), meta_size, header);

  Buffer encoded;
  encoded.write_bytes(reinterpret_cast<const uint8_t *>(&header),
                      sizeof(header));
  encoded.write_bytes(body.data(), meta_size);
  encoded.write_var_uint32(static_cast<uint32_t>(TypeId::NONE) << 2);
  encoded.write_uint8(0x7f);

  auto parsed = TypeMeta::from_bytes(encoded, nullptr);
  ASSERT_FALSE(parsed.ok());
  EXPECT_LE(encoded.reader_index(), sizeof(header) + meta_size);

  Buffer body_with_trailing;
  body_with_trailing.write_bytes(body.data(), meta_size);
  body_with_trailing.write_var_uint32(static_cast<uint32_t>(TypeId::NONE) << 2);
  body_with_trailing.write_uint8(0x7f);

  auto parsed_with_header = TypeMeta::from_bytes_with_header(
      body_with_trailing, static_cast<int64_t>(header));
  ASSERT_FALSE(parsed_with_header.ok());
  EXPECT_LE(body_with_trailing.reader_index(), meta_size);
}

TEST(SerializationTest, TypeMetaRejectsMaxTypeFields) {
  std::vector<FieldInfo> fields;
  fields.emplace_back(
      "first", FieldType(static_cast<uint32_t>(TypeId::VARINT32), false));
  fields.emplace_back(
      "second", FieldType(static_cast<uint32_t>(TypeId::VARINT32), false));
  TypeMeta meta = TypeMeta::from_fields(static_cast<uint32_t>(TypeId::STRUCT),
                                        "", "S", false, 1, std::move(fields));
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();
  std::vector<uint8_t> bytes = bytes_result.value();
  Buffer buffer(bytes);
  Error error;
  int64_t header = 0;
  buffer.read_bytes(&header, sizeof(header), error);
  ASSERT_TRUE(error.ok()) << error.to_string();

  auto parsed = TypeMeta::from_bytes_with_header(buffer, header, 1, 4096);
  ASSERT_FALSE(parsed.ok());
  EXPECT_NE(parsed.error().to_string().find("max_type_fields"),
            std::string::npos);
}

TEST(SerializationTest, TypeMetaRejectsMaxTypeMetaBytes) {
  std::vector<FieldInfo> fields;
  fields.emplace_back(
      "value", FieldType(static_cast<uint32_t>(TypeId::VARINT32), false));
  TypeMeta meta = TypeMeta::from_fields(static_cast<uint32_t>(TypeId::STRUCT),
                                        "", "S", false, 1, std::move(fields));
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();
  std::vector<uint8_t> bytes = bytes_result.value();
  Buffer buffer(bytes);
  Error error;
  int64_t header = 0;
  buffer.read_bytes(&header, sizeof(header), error);
  ASSERT_TRUE(error.ok()) << error.to_string();

  auto parsed = TypeMeta::from_bytes_with_header(buffer, header, 512, 1);
  ASSERT_FALSE(parsed.ok());
  EXPECT_NE(parsed.error().to_string().find("max_type_meta_bytes"),
            std::string::npos);
}

TEST(SerializationTest, TypeMetaRejectsBodyOnlyHeaderHash) {
  TypeMeta meta =
      TypeMeta::from_fields(static_cast<uint32_t>(TypeId::STRUCT), "", "S",
                            false, 1, std::vector<FieldInfo>{});
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  ASSERT_GT(bytes.size(), sizeof(uint64_t));
  uint64_t header = 0;
  std::memcpy(&header, bytes.data(), sizeof(header));

  constexpr uint32_t kHashShift = 12;
  constexpr uint64_t kHashBitsMask = UINT64_MAX << kHashShift;
  uint64_t body_only_hash = compute_body_only_type_meta_hash_bits_for_test(
      bytes.data() + sizeof(uint64_t), bytes.size() - sizeof(uint64_t));
  ASSERT_NE(header & kHashBitsMask, body_only_hash);
  header = body_only_hash | (header & ~kHashBitsMask);
  std::memcpy(bytes.data(), &header, sizeof(header));

  Buffer buffer(bytes);
  auto parsed = TypeMeta::from_bytes(buffer, nullptr);
  ASSERT_FALSE(parsed.ok());
  EXPECT_EQ(parsed.error().code(), ErrorCode::InvalidData);
  EXPECT_NE(parsed.error().to_string().find("metadata hash"),
            std::string::npos);
}

TEST(SerializationTest, TypeMetaNonStructHeaderUsesDenseKindCode) {
  TypeMeta meta =
      TypeMeta::from_fields(static_cast<uint32_t>(TypeId::ENUM), "", "E", false,
                            7, std::vector<FieldInfo>{});
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  ASSERT_GT(bytes.size(), sizeof(uint64_t));
  EXPECT_EQ(bytes[sizeof(uint64_t)], 0x00);

  Buffer buffer(bytes);
  auto parsed = TypeMeta::from_bytes(buffer, nullptr);
  ASSERT_TRUE(parsed.ok()) << parsed.error().to_string();
  EXPECT_EQ(parsed.value()->get_type_id(), static_cast<uint32_t>(TypeId::ENUM));
}

TEST(SerializationTest, TypeMetaRejectsNonStructReservedKindBits) {
  TypeMeta meta =
      TypeMeta::from_fields(static_cast<uint32_t>(TypeId::ENUM), "", "E", false,
                            7, std::vector<FieldInfo>{});
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  bytes[sizeof(uint64_t)] |= 0x10;
  uint64_t header = 0;
  std::memcpy(&header, bytes.data(), sizeof(header));
  ASSERT_NE(header & 0xff, 0xff);
  header &= ~(UINT64_MAX << 12);
  header |= compute_type_meta_hash_bits_for_test(
      bytes.data() + sizeof(uint64_t), bytes.size() - sizeof(uint64_t), header);
  std::memcpy(bytes.data(), &header, sizeof(header));

  Buffer buffer(bytes);
  auto parsed = TypeMeta::from_bytes(buffer, nullptr);
  ASSERT_FALSE(parsed.ok());
  EXPECT_EQ(parsed.error().code(), ErrorCode::InvalidData);
  EXPECT_NE(parsed.error().to_string().find("kind header"), std::string::npos);
}

TEST(SerializationTest, TypeMetaRejectsReservedHeaderBits) {
  TypeMeta meta =
      TypeMeta::from_fields(static_cast<uint32_t>(TypeId::STRUCT), "", "S",
                            false, 1, std::vector<FieldInfo>{});
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  uint64_t header = 0;
  std::memcpy(&header, bytes.data(), sizeof(header));
  header |= 0x200;
  std::memcpy(bytes.data(), &header, sizeof(header));

  Buffer buffer(bytes);
  auto parsed = TypeMeta::from_bytes(buffer, nullptr);
  ASSERT_FALSE(parsed.ok());
  EXPECT_EQ(parsed.error().code(), ErrorCode::InvalidData);
}

TEST(SerializationTest, TypeMetaRejectsUnsupportedCompressedHeader) {
  TypeMeta meta =
      TypeMeta::from_fields(static_cast<uint32_t>(TypeId::STRUCT), "", "S",
                            false, 1, std::vector<FieldInfo>{});
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  uint64_t header = 0;
  std::memcpy(&header, bytes.data(), sizeof(header));
  header |= 0x100;
  std::memcpy(bytes.data(), &header, sizeof(header));

  Buffer buffer(bytes);
  auto parsed = TypeMeta::from_bytes(buffer, nullptr);
  ASSERT_FALSE(parsed.ok());
  EXPECT_EQ(parsed.error().code(), ErrorCode::InvalidData);
}

TEST(SerializationTest, TypeMetaRejectsBodyHashMismatchAfterParse) {
  TypeMeta meta =
      TypeMeta::from_fields(static_cast<uint32_t>(TypeId::STRUCT), "", "S",
                            false, 1, std::vector<FieldInfo>{});
  auto bytes_result = meta.to_bytes();
  ASSERT_TRUE(bytes_result.ok())
      << "TypeMeta serialization failed: " << bytes_result.error().to_string();

  std::vector<uint8_t> bytes = bytes_result.value();
  ASSERT_GT(bytes.size(), sizeof(uint64_t));
  bytes.back() ^= 0x01;

  Buffer buffer(bytes);
  auto parsed = TypeMeta::from_bytes(buffer, nullptr);
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
  EXPECT_FALSE(fory1.config().check_struct_version);
  EXPECT_EQ(fory1.config().max_dyn_depth, 10);
  EXPECT_FALSE(fory1.config().track_ref);

  auto default_xlang = Fory::builder().xlang(true).build();
  auto default_native = Fory::builder().xlang(false).build();
  auto compatible_xlang = Fory::builder().xlang(true).compatible(true).build();
  auto explicit_schema_consistent =
      Fory::builder().compatible(false).xlang(true).build();
  auto explicit_schema_consistent_reverse_order =
      Fory::builder().xlang(true).compatible(false).build();
  auto compatible_with_version_check = Fory::builder()
                                           .xlang(true)
                                           .compatible(true)
                                           .check_struct_version(true)
                                           .build();

  EXPECT_TRUE(default_xlang.config().compatible);
  EXPECT_TRUE(default_native.config().compatible);
  EXPECT_TRUE(compatible_xlang.config().compatible);
  EXPECT_FALSE(compatible_xlang.config().check_struct_version);
  EXPECT_FALSE(explicit_schema_consistent.config().compatible);
  EXPECT_FALSE(explicit_schema_consistent_reverse_order.config().compatible);
  EXPECT_FALSE(compatible_with_version_check.config().check_struct_version);
}

// ============================================================================
// Thread Safety Tests
// ============================================================================

TEST(SerializationTest, ThreadSafeForyMultiThread) {
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(false)
                  .track_ref(false)
                  .build_thread_safe();
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
  auto fory = Fory::builder()
                  .xlang(true)
                  .compatible(false)
                  .track_ref(false)
                  .build_thread_safe();
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

TEST(SerializationTest, TemporalCarriersAreHashable) {
  std::unordered_map<Date, std::string> date_map;
  date_map[Date(0)] = "epoch";
  date_map[Date(18954)] = "future";
  date_map[Date(-1)] = "past";
  EXPECT_EQ(date_map.size(), 3u);
  EXPECT_EQ(date_map[Date(0)], "epoch");

  std::unordered_map<Duration, std::string> dur_map;
  dur_map[Duration(std::chrono::nanoseconds(0))] = "zero";
  dur_map[Duration(std::chrono::seconds(1))] = "one_sec";
  dur_map[Duration(std::chrono::nanoseconds(-1))] = "neg";
  EXPECT_EQ(dur_map.size(), 3u);
  EXPECT_EQ(dur_map[Duration(std::chrono::nanoseconds(0))], "zero");

  std::unordered_map<Timestamp, std::string> ts_map;
  ts_map[Timestamp()] = "epoch";
  ts_map[Timestamp(std::chrono::nanoseconds(1000000000LL))] = "one_sec";
  EXPECT_EQ(ts_map.size(), 2u);
  EXPECT_EQ(ts_map[Timestamp()], "epoch");
}

TEST(SerializationTest, DurationChronoConversion) {
  auto ns = std::chrono::seconds(5) + std::chrono::nanoseconds(123);
  Duration d(ns);
  EXPECT_EQ(d.to_chrono(), ns);
  EXPECT_EQ(d.count(), ns.count());

  Duration zero;
  EXPECT_EQ(zero.count(), 0);
  EXPECT_EQ(zero.to_chrono(), std::chrono::nanoseconds(0));
}

TEST(SerializationTest, TimestampChronoConversion) {
  auto ns = std::chrono::nanoseconds(1700000000000000000LL);
  Timestamp ts(ns);
  EXPECT_EQ(ts.time_since_epoch(), ns);
  EXPECT_EQ(ts.to_chrono().time_since_epoch(), ns);

  Timestamp epoch;
  EXPECT_EQ(epoch.time_since_epoch().count(), 0);
}

TEST(SerializationTest, TemporalAnyUsesCanonicalCarriers) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();
  ASSERT_TRUE(register_any_type<Duration>(fory.type_resolver()).ok());
  ASSERT_TRUE(register_any_type<Timestamp>(fory.type_resolver()).ok());

  expect_any_roundtrip(
      fory, Duration(std::chrono::seconds(12) + std::chrono::nanoseconds(34)));
  expect_any_roundtrip(fory,
                       Timestamp(std::chrono::nanoseconds(1234567890123LL)));
}

TEST(SerializationTest, ChronoTemporalAnyRegistrationIsRejected) {
  using ChronoTimestamp = std::chrono::time_point<std::chrono::system_clock,
                                                  std::chrono::nanoseconds>;
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();

  auto chrono_duration_registration =
      register_any_type<std::chrono::nanoseconds>(fory.type_resolver());
  ASSERT_FALSE(chrono_duration_registration.ok());
  EXPECT_NE(chrono_duration_registration.error().to_string().find(
                "explicit static targets"),
            std::string::npos);

  ASSERT_TRUE(register_any_type<Duration>(fory.type_resolver()).ok());
  ASSERT_TRUE(register_any_type<Timestamp>(fory.type_resolver()).ok());

  auto chrono_timestamp_registration =
      register_any_type<ChronoTimestamp>(fory.type_resolver());
  ASSERT_FALSE(chrono_timestamp_registration.ok());
  EXPECT_NE(chrono_timestamp_registration.error().to_string().find(
                "explicit static targets"),
            std::string::npos);

  expect_any_roundtrip(fory, Duration(std::chrono::nanoseconds(-1)));
  expect_any_roundtrip(fory, Timestamp(std::chrono::nanoseconds(1000000000LL)));
}

TEST(SerializationTest, ForyOwnedAndChronoShareWireEncoding) {
  auto fory =
      Fory::builder().xlang(true).compatible(false).track_ref(false).build();

  auto ns = std::chrono::seconds(7) + std::chrono::nanoseconds(654321);
  Duration fory_dur(ns);
  std::chrono::nanoseconds chrono_dur = ns;

  auto fory_bytes = fory.serialize(fory_dur);
  auto chrono_bytes = fory.serialize(chrono_dur);
  ASSERT_TRUE(fory_bytes.ok());
  ASSERT_TRUE(chrono_bytes.ok());
  EXPECT_EQ(fory_bytes.value(), chrono_bytes.value());

  auto decoded_from_fory = fory.deserialize<std::chrono::nanoseconds>(
      fory_bytes.value().data(), fory_bytes.value().size());
  ASSERT_TRUE(decoded_from_fory.ok());
  EXPECT_EQ(decoded_from_fory.value(), ns);

  auto decoded_from_chrono = fory.deserialize<Duration>(
      chrono_bytes.value().data(), chrono_bytes.value().size());
  ASSERT_TRUE(decoded_from_chrono.ok());
  EXPECT_EQ(decoded_from_chrono.value(), fory_dur);
}

} // namespace test
} // namespace serialization
} // namespace fory
