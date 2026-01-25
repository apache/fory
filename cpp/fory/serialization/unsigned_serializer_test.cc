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
#include "gtest/gtest.h"
#include <array>
#include <cstdint>
#include <limits>
#include <vector>

namespace fory {
namespace serialization {
namespace test {

// ============================================================================
// Test Struct with Unsigned Fields
// ============================================================================

struct UnsignedStruct {
  uint8_t u8_val;
  uint16_t u16_val;
  uint32_t u32_val;
  uint64_t u64_val;

  bool operator==(const UnsignedStruct &other) const {
    return u8_val == other.u8_val && u16_val == other.u16_val &&
           u32_val == other.u32_val && u64_val == other.u64_val;
  }
  FORY_STRUCT(UnsignedStruct, u8_val, u16_val, u32_val, u64_val);
};

struct UnsignedArrayStruct {
  std::vector<uint8_t> u8_vec;
  std::vector<uint16_t> u16_vec;
  std::vector<uint32_t> u32_vec;
  std::vector<uint64_t> u64_vec;

  bool operator==(const UnsignedArrayStruct &other) const {
    return u8_vec == other.u8_vec && u16_vec == other.u16_vec &&
           u32_vec == other.u32_vec && u64_vec == other.u64_vec;
  }
  FORY_STRUCT(UnsignedArrayStruct, u8_vec, u16_vec, u32_vec, u64_vec);
};

// ============================================================================
// Test Helper for Native Mode (xlang=false)
// Unsigned types are only supported in native mode (xlang=false)
// ============================================================================

template <typename T> void test_roundtrip_native(const T &original) {
  // Test with xlang=false (native mode) - the only supported mode for unsigned
  // types
  auto fory = Fory::builder().xlang(false).track_ref(false).build();

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
  EXPECT_EQ(original, deserialized);
}

// ============================================================================
// Unsigned Primitive Type Tests (Native Mode Only)
// ============================================================================

TEST(UnsignedSerializerTest, Uint8NativeRoundtrip) {
  test_roundtrip_native<uint8_t>(0);
  test_roundtrip_native<uint8_t>(127);
  test_roundtrip_native<uint8_t>(255);
  test_roundtrip_native<uint8_t>(42);
}

TEST(UnsignedSerializerTest, Uint16NativeRoundtrip) {
  test_roundtrip_native<uint16_t>(0);
  test_roundtrip_native<uint16_t>(32767);
  test_roundtrip_native<uint16_t>(65535);
  test_roundtrip_native<uint16_t>(12345);
}

TEST(UnsignedSerializerTest, Uint32NativeRoundtrip) {
  test_roundtrip_native<uint32_t>(0);
  test_roundtrip_native<uint32_t>(2147483647);
  test_roundtrip_native<uint32_t>(4294967295U);
  test_roundtrip_native<uint32_t>(123456789);
}

TEST(UnsignedSerializerTest, Uint64NativeRoundtrip) {
  test_roundtrip_native<uint64_t>(0);
  test_roundtrip_native<uint64_t>(9223372036854775807ULL);
  test_roundtrip_native<uint64_t>(18446744073709551615ULL);
  test_roundtrip_native<uint64_t>(123456789012345ULL);
}

// ============================================================================
// Unsigned Vector Tests (Native Mode Only)
// ============================================================================

TEST(UnsignedSerializerTest, VectorUint8NativeRoundtrip) {
  test_roundtrip_native(std::vector<uint8_t>{});
  test_roundtrip_native(std::vector<uint8_t>{0, 127, 255});
  test_roundtrip_native(std::vector<uint8_t>{1, 2, 3, 4, 5});
}

TEST(UnsignedSerializerTest, VectorUint16NativeRoundtrip) {
  test_roundtrip_native(std::vector<uint16_t>{});
  test_roundtrip_native(std::vector<uint16_t>{0, 32767, 65535});
  test_roundtrip_native(std::vector<uint16_t>{1000, 2000, 3000});
}

TEST(UnsignedSerializerTest, VectorUint32NativeRoundtrip) {
  test_roundtrip_native(std::vector<uint32_t>{});
  test_roundtrip_native(std::vector<uint32_t>{0, 2147483647, 4294967295U});
  test_roundtrip_native(std::vector<uint32_t>{100000, 200000, 300000});
}

TEST(UnsignedSerializerTest, VectorUint64NativeRoundtrip) {
  test_roundtrip_native(std::vector<uint64_t>{});
  test_roundtrip_native(std::vector<uint64_t>{0, 9223372036854775807ULL,
                                              18446744073709551615ULL});
  test_roundtrip_native(
      std::vector<uint64_t>{1000000000000ULL, 2000000000000ULL});
}

// ============================================================================
// Unsigned Array Tests (Native Mode Only)
// ============================================================================

TEST(UnsignedSerializerTest, ArrayUint8NativeRoundtrip) {
  test_roundtrip_native(std::array<uint8_t, 3>{0, 127, 255});
  test_roundtrip_native(std::array<uint8_t, 5>{1, 2, 3, 4, 5});
}

TEST(UnsignedSerializerTest, ArrayUint16NativeRoundtrip) {
  test_roundtrip_native(std::array<uint16_t, 3>{0, 32767, 65535});
  test_roundtrip_native(std::array<uint16_t, 3>{1000, 2000, 3000});
}

TEST(UnsignedSerializerTest, ArrayUint32NativeRoundtrip) {
  test_roundtrip_native(std::array<uint32_t, 3>{0, 2147483647, 4294967295U});
  test_roundtrip_native(std::array<uint32_t, 3>{100000, 200000, 300000});
}

TEST(UnsignedSerializerTest, ArrayUint64NativeRoundtrip) {
  test_roundtrip_native(std::array<uint64_t, 3>{0, 9223372036854775807ULL,
                                                18446744073709551615ULL});
  test_roundtrip_native(
      std::array<uint64_t, 2>{1000000000000ULL, 2000000000000ULL});
}

// ============================================================================
// Struct with Unsigned Fields Tests (Native Mode Only)
// ============================================================================

TEST(UnsignedSerializerTest, UnsignedStructNativeRoundtrip) {
  auto fory = Fory::builder().xlang(false).track_ref(false).build();
  fory.register_struct<UnsignedStruct>(1);

  UnsignedStruct original{42, 1234, 567890, 9876543210ULL};

  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(serialize_result).value();

  auto deserialize_result =
      fory.deserialize<UnsignedStruct>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();

  EXPECT_EQ(original, deserialize_result.value());
}

TEST(UnsignedSerializerTest, UnsignedArrayStructNativeRoundtrip) {
  auto fory = Fory::builder().xlang(false).track_ref(false).build();
  fory.register_struct<UnsignedArrayStruct>(1);

  UnsignedArrayStruct original{
      {1, 2, 3}, {100, 200}, {10000, 20000}, {1000000}};

  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(serialize_result).value();

  auto deserialize_result =
      fory.deserialize<UnsignedArrayStruct>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();

  EXPECT_EQ(original, deserialize_result.value());
}

// ============================================================================
// Edge Cases - Boundary Values
// ============================================================================

TEST(UnsignedSerializerTest, BoundaryValues) {
  // Test min/max values for each unsigned type
  test_roundtrip_native<uint8_t>(std::numeric_limits<uint8_t>::min());
  test_roundtrip_native<uint8_t>(std::numeric_limits<uint8_t>::max());

  test_roundtrip_native<uint16_t>(std::numeric_limits<uint16_t>::min());
  test_roundtrip_native<uint16_t>(std::numeric_limits<uint16_t>::max());

  test_roundtrip_native<uint32_t>(std::numeric_limits<uint32_t>::min());
  test_roundtrip_native<uint32_t>(std::numeric_limits<uint32_t>::max());

  test_roundtrip_native<uint64_t>(std::numeric_limits<uint64_t>::min());
  test_roundtrip_native<uint64_t>(std::numeric_limits<uint64_t>::max());
}

// ============================================================================
// Type ID Verification Tests
// ============================================================================

TEST(UnsignedSerializerTest, UnsignedTypeIdsAreDistinct) {
  // Verify that unsigned types use distinct TypeIds
  // uint8_t and uint16_t use fixed encoding (UINT8, UINT16)
  // uint32_t and uint64_t use variable encoding (VAR_UINT32, VAR_UINT64) to
  // match Rust xlang mode
  EXPECT_EQ(static_cast<uint32_t>(Serializer<uint8_t>::type_id),
            static_cast<uint32_t>(TypeId::UINT8));
  EXPECT_EQ(static_cast<uint32_t>(Serializer<uint16_t>::type_id),
            static_cast<uint32_t>(TypeId::UINT16));
  EXPECT_EQ(static_cast<uint32_t>(Serializer<uint32_t>::type_id),
            static_cast<uint32_t>(TypeId::VAR_UINT32));
  EXPECT_EQ(static_cast<uint32_t>(Serializer<uint64_t>::type_id),
            static_cast<uint32_t>(TypeId::VAR_UINT64));
}

TEST(UnsignedSerializerTest, UnsignedArrayTypeIdsAreDistinct) {
  // Verify that unsigned array types use distinct TypeIds
  EXPECT_EQ(static_cast<uint32_t>(Serializer<std::vector<uint16_t>>::type_id),
            static_cast<uint32_t>(TypeId::UINT16_ARRAY));
  EXPECT_EQ(static_cast<uint32_t>(Serializer<std::vector<uint32_t>>::type_id),
            static_cast<uint32_t>(TypeId::UINT32_ARRAY));
  EXPECT_EQ(static_cast<uint32_t>(Serializer<std::vector<uint64_t>>::type_id),
            static_cast<uint32_t>(TypeId::UINT64_ARRAY));
  // uint8_t vector uses BINARY type
  EXPECT_EQ(static_cast<uint32_t>(Serializer<std::vector<uint8_t>>::type_id),
            static_cast<uint32_t>(TypeId::BINARY));
}

} // namespace test
} // namespace serialization
} // namespace fory
