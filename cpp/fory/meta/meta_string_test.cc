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

#include "fory/meta/meta_string.h"

#include <gtest/gtest.h>

namespace fory {
namespace meta {
namespace {

class MetaStringTest : public ::testing::Test {
protected:
  MetaStringEncoder encoder_{'_', '$'};
  MetaStringDecoder decoder_{'_', '$'};
};

// ============================================================================
// ToMetaEncoding tests
// ============================================================================

TEST_F(MetaStringTest, ToMetaEncodingValidValues) {
  EXPECT_EQ(ToMetaEncoding(0x00).value(), MetaEncoding::UTF8);
  EXPECT_EQ(ToMetaEncoding(0x01).value(), MetaEncoding::LOWER_SPECIAL);
  EXPECT_EQ(ToMetaEncoding(0x02).value(),
            MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL);
  EXPECT_EQ(ToMetaEncoding(0x03).value(), MetaEncoding::FIRST_TO_LOWER_SPECIAL);
  EXPECT_EQ(ToMetaEncoding(0x04).value(), MetaEncoding::ALL_TO_LOWER_SPECIAL);
}

TEST_F(MetaStringTest, ToMetaEncodingInvalidValue) {
  auto result = ToMetaEncoding(0x05);
  EXPECT_FALSE(result.ok());
}

// ============================================================================
// Encoder tests - Empty string
// ============================================================================

TEST_F(MetaStringTest, EncodeEmptyString) {
  auto result = encoder_.encode("");
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::UTF8);
  EXPECT_TRUE(result.value().bytes.empty());
}

// ============================================================================
// Encoder tests - LOWER_SPECIAL encoding
// ============================================================================

TEST_F(MetaStringTest, EncodeLowerSpecialSimple) {
  auto result = encoder_.encode("abc");
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::LOWER_SPECIAL);
  EXPECT_FALSE(result.value().bytes.empty());
}

TEST_F(MetaStringTest, EncodeLowerSpecialWithDot) {
  auto result = encoder_.encode("org.apache.fory");
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::LOWER_SPECIAL);
}

TEST_F(MetaStringTest, EncodeLowerSpecialWithUnderscore) {
  auto result = encoder_.encode("my_class_name");
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::LOWER_SPECIAL);
}

TEST_F(MetaStringTest, EncodeLowerSpecialWithDollar) {
  auto result = encoder_.encode("inner$class");
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::LOWER_SPECIAL);
}

// ============================================================================
// Encoder tests - LOWER_UPPER_DIGIT_SPECIAL encoding
// ============================================================================

TEST_F(MetaStringTest, EncodeLowerUpperDigitSpecialWithDigits) {
  auto result = encoder_.encode("class123");
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL);
}

TEST_F(MetaStringTest, EncodeLowerUpperDigitSpecialWithMixedCase) {
  auto result = encoder_.encode("MyClassName123");
  ASSERT_TRUE(result.ok());
  // Should pick appropriate encoding based on statistics
  EXPECT_TRUE(result.value().encoding ==
                  MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL ||
              result.value().encoding == MetaEncoding::ALL_TO_LOWER_SPECIAL);
}

// ============================================================================
// Encoder tests - FIRST_TO_LOWER_SPECIAL encoding
// ============================================================================

TEST_F(MetaStringTest, EncodeFirstToLowerSpecial) {
  auto result = encoder_.encode("Myclass");
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::FIRST_TO_LOWER_SPECIAL);
}

TEST_F(MetaStringTest, EncodeFirstToLowerSpecialPackage) {
  // "Org.apache.fory" has '.' which is not supported by
  // LOWER_UPPER_DIGIT_SPECIAL so it falls back to UTF8 or ALL_TO_LOWER_SPECIAL
  // depending on efficiency
  auto result = encoder_.encode("Org.apache.fory");
  ASSERT_TRUE(result.ok());
  // With '.' and uppercase, the encoder picks ALL_TO_LOWER_SPECIAL or UTF8
  // LOWER_SPECIAL only supports [a-z._$|] - no uppercase
  // FIRST_TO_LOWER_SPECIAL converts first char to lowercase then uses
  // LOWER_SPECIAL
  EXPECT_TRUE(result.value().encoding == MetaEncoding::FIRST_TO_LOWER_SPECIAL ||
              result.value().encoding == MetaEncoding::ALL_TO_LOWER_SPECIAL ||
              result.value().encoding == MetaEncoding::UTF8);
}

// ============================================================================
// Encoder tests - ALL_TO_LOWER_SPECIAL encoding
// ============================================================================

TEST_F(MetaStringTest, EncodeAllToLowerSpecial) {
  // Multiple uppercase letters should trigger ALL_TO_LOWER_SPECIAL
  // when it's more efficient than LOWER_UPPER_DIGIT_SPECIAL
  auto result = encoder_.encode("MyClass");
  ASSERT_TRUE(result.ok());
  // Depending on the efficiency calculation, could be ALL_TO_LOWER_SPECIAL
  // or LOWER_UPPER_DIGIT_SPECIAL
  EXPECT_TRUE(result.value().encoding == MetaEncoding::ALL_TO_LOWER_SPECIAL ||
              result.value().encoding ==
                  MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL);
}

// ============================================================================
// Encoder tests - UTF8 encoding
// ============================================================================

TEST_F(MetaStringTest, EncodeUTF8NonAscii) {
  auto result = encoder_.encode("hello\xC0world");
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::UTF8);
}

TEST_F(MetaStringTest, EncodeUTF8WithInvalidChars) {
  auto result = encoder_.encode("hello@world");
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::UTF8);
}

// ============================================================================
// Decoder tests - Empty string
// ============================================================================

TEST_F(MetaStringTest, DecodeEmptyString) {
  auto result = decoder_.decode(nullptr, 0, MetaEncoding::UTF8);
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value(), "");
}

// ============================================================================
// Decoder tests - UTF8 encoding
// ============================================================================

TEST_F(MetaStringTest, DecodeUTF8) {
  const std::string input = "hello world";
  auto result = decoder_.decode(reinterpret_cast<const uint8_t *>(input.data()),
                                input.size(), MetaEncoding::UTF8);
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value(), input);
}

// ============================================================================
// Roundtrip tests - Encode then Decode
// ============================================================================

TEST_F(MetaStringTest, RoundtripLowerSpecial) {
  const std::string input = "org.apache.fory";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());
  EXPECT_EQ(encode_result.value().encoding, MetaEncoding::LOWER_SPECIAL);

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, RoundtripLowerSpecialWithUnderscore) {
  const std::string input = "my_class_name";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, RoundtripFirstToLowerSpecial) {
  const std::string input = "Myclass";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());
  EXPECT_EQ(encode_result.value().encoding,
            MetaEncoding::FIRST_TO_LOWER_SPECIAL);

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, RoundtripLowerUpperDigitSpecial) {
  const std::string input = "Class123";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, RoundtripUTF8) {
  const std::string input = "hello@world#test";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());
  EXPECT_EQ(encode_result.value().encoding, MetaEncoding::UTF8);

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

// ============================================================================
// Roundtrip tests - Various class name patterns
// ============================================================================

TEST_F(MetaStringTest, RoundtripJavaPackageName) {
  const std::string input = "org.apache.fory.serializer";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, RoundtripCppNamespace) {
  const std::string input = "fory.serialization";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, RoundtripInnerClass) {
  const std::string input = "outer$inner";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, RoundtripSingleChar) {
  const std::string input = "a";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, RoundtripLongString) {
  const std::string input =
      "org.apache.fory.serialization.meta.string.test.long.class.name";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

// ============================================================================
// MetaStringTable tests
// ============================================================================

TEST_F(MetaStringTest, MetaStringTableReset) {
  MetaStringTable table;
  table.reset();
  // Just verify no crash
}

TEST_F(MetaStringTest, MetaStringTableReadSmallString) {
  MetaStringTable table;
  Buffer buffer;

  // Encode a small string (len <= 16)
  const std::string input = "test";
  auto encoded = encoder_.encode(input);
  ASSERT_TRUE(encoded.ok());

  // Write header: (len << 1) | 0 (not a reference)
  uint32_t header = static_cast<uint32_t>(encoded.value().bytes.size()) << 1;
  buffer.WriteVarUint32(header);
  // Write encoding byte
  buffer.WriteInt8(static_cast<int8_t>(encoded.value().encoding));
  // Write data
  buffer.WriteBytes(encoded.value().bytes.data(), encoded.value().bytes.size());

  buffer.ReaderIndex(0);
  auto result = table.read_string(buffer, decoder_);
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value(), input);
}

TEST_F(MetaStringTest, MetaStringTableReadReference) {
  MetaStringTable table;
  Buffer buffer;

  // First, write a string
  const std::string input = "test";
  auto encoded = encoder_.encode(input);
  ASSERT_TRUE(encoded.ok());

  uint32_t header = static_cast<uint32_t>(encoded.value().bytes.size()) << 1;
  buffer.WriteVarUint32(header);
  buffer.WriteInt8(static_cast<int8_t>(encoded.value().encoding));
  buffer.WriteBytes(encoded.value().bytes.data(), encoded.value().bytes.size());

  // Then write a reference to it (id=1, is_ref=true)
  uint32_t ref_header = (1 << 1) | 1;
  buffer.WriteVarUint32(ref_header);

  buffer.ReaderIndex(0);

  // Read the first string
  auto result1 = table.read_string(buffer, decoder_);
  ASSERT_TRUE(result1.ok());
  EXPECT_EQ(result1.value(), input);

  // Read the reference
  auto result2 = table.read_string(buffer, decoder_);
  ASSERT_TRUE(result2.ok());
  EXPECT_EQ(result2.value(), input);
}

TEST_F(MetaStringTest, MetaStringTableInvalidReference) {
  MetaStringTable table;
  Buffer buffer;

  // Write a reference to non-existent entry (id=1, is_ref=true)
  uint32_t ref_header = (1 << 1) | 1;
  buffer.WriteVarUint32(ref_header);

  buffer.ReaderIndex(0);
  auto result = table.read_string(buffer, decoder_);
  EXPECT_FALSE(result.ok());
}

TEST_F(MetaStringTest, MetaStringTableEmptyString) {
  MetaStringTable table;
  Buffer buffer;

  // Empty string: len=0, encoding=UTF8
  uint32_t header = 0 << 1; // len=0, not a reference
  buffer.WriteVarUint32(header);
  buffer.WriteInt8(0); // UTF8 encoding

  buffer.ReaderIndex(0);
  auto result = table.read_string(buffer, decoder_);
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value(), "");
}

// ============================================================================
// Special character encoding tests
// ============================================================================

TEST_F(MetaStringTest, SpecialCharactersInEncoder) {
  MetaStringEncoder encoder{'_', '$'};

  // Test with underscore as special char 1
  auto result1 = encoder.encode("test_name");
  ASSERT_TRUE(result1.ok());

  // Test with dollar as special char 2
  auto result2 = encoder.encode("test$name");
  ASSERT_TRUE(result2.ok());
}

TEST_F(MetaStringTest, DifferentSpecialCharacters) {
  // Use special chars that work with LOWER_UPPER_DIGIT_SPECIAL
  MetaStringEncoder encoder{'.', '/'};
  MetaStringDecoder decoder{'.', '/'};

  // Use a string that will encode with LOWER_UPPER_DIGIT_SPECIAL
  // which uses the special chars as positions 62 and 63
  const std::string input = "Test123.Path/Name";
  auto encode_result = encoder.encode(input);
  ASSERT_TRUE(encode_result.ok());

  auto decode_result = decoder.decode(encode_result.value().bytes.data(),
                                      encode_result.value().bytes.size(),
                                      encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

// ============================================================================
// Forced encoding tests
// ============================================================================

TEST_F(MetaStringTest, ForcedEncodingLowerSpecial) {
  const std::string input = "test";
  auto result = encoder_.encode(input, {MetaEncoding::LOWER_SPECIAL});
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::LOWER_SPECIAL);
}

TEST_F(MetaStringTest, ForcedEncodingUTF8) {
  const std::string input = "test";
  auto result = encoder_.encode(input, {MetaEncoding::UTF8});
  ASSERT_TRUE(result.ok());
  EXPECT_EQ(result.value().encoding, MetaEncoding::UTF8);
}

TEST_F(MetaStringTest, ForcedEncodingFallbackToUTF8) {
  // String with @ cannot be encoded with LOWER_SPECIAL
  const std::string input = "test@example";
  auto result = encoder_.encode(input, {MetaEncoding::LOWER_SPECIAL});
  ASSERT_TRUE(result.ok());
  // Should fall back to UTF8 since LOWER_SPECIAL can't encode @
  EXPECT_EQ(result.value().encoding, MetaEncoding::UTF8);
}

// ============================================================================
// Edge cases
// ============================================================================

TEST_F(MetaStringTest, AllLowercaseAlphabet) {
  const std::string input = "abcdefghijklmnopqrstuvwxyz";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());
  EXPECT_EQ(encode_result.value().encoding, MetaEncoding::LOWER_SPECIAL);

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, AllDigits) {
  const std::string input = "0123456789";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());
  EXPECT_EQ(encode_result.value().encoding,
            MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL);

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

TEST_F(MetaStringTest, MixedAlphaDigit) {
  const std::string input = "abc123xyz";
  auto encode_result = encoder_.encode(input);
  ASSERT_TRUE(encode_result.ok());
  EXPECT_EQ(encode_result.value().encoding,
            MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL);

  auto decode_result = decoder_.decode(encode_result.value().bytes.data(),
                                       encode_result.value().bytes.size(),
                                       encode_result.value().encoding);
  ASSERT_TRUE(decode_result.ok());
  EXPECT_EQ(decode_result.value(), input);
}

} // namespace
} // namespace meta
} // namespace fory
