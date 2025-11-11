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

#include <codecvt>
#include <iostream>
#include <locale>
#include <random>

#include "fory/util/logging.h"
#include "platform.h"
#include "string_util.h"
#include "gtest/gtest.h"

namespace fory {

// Generate ASCII string
std::string generateAscii(size_t length) {
  const char charset[] =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  std::default_random_engine rng(std::random_device{}());
  std::uniform_int_distribution<> dist(0, sizeof(charset) - 2);

  std::string result;
  result.reserve(length);
  for (size_t i = 0; i < length; ++i) {
    result += charset[dist(rng)];
  }
  return result;
}

TEST(StringUtilTest, TestisAsciiLogic) {
  // Test strings with only Ascii characters
  EXPECT_TRUE(isAscii("Fory"));
  EXPECT_TRUE(isAscii(generateAscii(80)));

  // Test unaligned strings with only Ascii characters
  EXPECT_TRUE(isAscii(generateAscii(80) + "1"));
  EXPECT_TRUE(isAscii(generateAscii(80) + "12"));
  EXPECT_TRUE(isAscii(generateAscii(80) + "123"));

  // Test strings with non-Ascii characters
  EXPECT_FALSE(isAscii("你好, Fory"));
  EXPECT_FALSE(isAscii(generateAscii(80) + "你好"));
  EXPECT_FALSE(isAscii(generateAscii(80) + "1你好"));
  EXPECT_FALSE(isAscii(generateAscii(11) + "你"));
  EXPECT_FALSE(isAscii(generateAscii(10) + "你好"));
  EXPECT_FALSE(isAscii(generateAscii(9) + "性能好"));
  EXPECT_FALSE(isAscii("\u1234"));
  EXPECT_FALSE(isAscii("a\u1234"));
  EXPECT_FALSE(isAscii("ab\u1234"));
  EXPECT_FALSE(isAscii("abc\u1234"));
  EXPECT_FALSE(isAscii("abcd\u1234"));
  EXPECT_FALSE(isAscii("Javaone Keynote\u1234"));

  for (size_t i = 1; i < 256; i++) {
    EXPECT_TRUE(isAscii(std::string(i, '.') + "Fory"));
    EXPECT_FALSE(isAscii(std::string(i, '.') + "序列化"));
  }
}

TEST(StringUtilTest, TestisLatin1) {
  // Test strings with only Latin characters
  EXPECT_TRUE(isLatin1(u"Fory"));
  EXPECT_TRUE(isLatin1(u"\xE9")); // é in Latin-1
  EXPECT_TRUE(isLatin1(u"\xF1")); // ñ in Latin-1
  // Test strings with non-Latin characters
  EXPECT_FALSE(isLatin1(u"你好, Fory"));
  EXPECT_FALSE(isLatin1(u"a\u1234"));
  EXPECT_FALSE(isLatin1(u"ab\u1234"));
  EXPECT_FALSE(isLatin1(u"abc\u1234"));
  EXPECT_FALSE(isLatin1(u"abcd\u1234"));
  EXPECT_FALSE(isLatin1(u"Javaone Keynote\u1234"));
  EXPECT_TRUE(isLatin1(u"a\xFF")); // ÿ in Latin-1
  EXPECT_TRUE(isLatin1(u"\x80"));  //  in Latin-1
  for (size_t i = 1; i < 256; i++) {
    EXPECT_TRUE(isLatin1(std::u16string(i, '.') + u"Fory"));
    EXPECT_FALSE(isLatin1(std::u16string(i, '.') + u"序列化"));
    EXPECT_TRUE(isLatin1(std::u16string(i, '.') + u"a\xFF")); // ÿ in Latin-1
    EXPECT_TRUE(isLatin1(std::u16string(i, '.') + u"\x80"));  //  in Latin-1
    EXPECT_FALSE(isLatin1(std::u16string(i, '.') +
                          std::u16string({256}))); // Ā (not in Latin-1)
  }
}

TEST(StringUtilTest, ToSnakeCaseHandlesCommonPatterns) {
  constexpr std::string_view simple_name{"DisplayName"};
  constexpr size_t simple_len = snake_case_length(simple_name);
  constexpr auto simple_pair = to_snake_case<simple_len>(simple_name);
  const std::string_view simple_view(simple_pair.first.data(),
                                     simple_pair.second);
  constexpr std::string_view simple_expected{"display_name"};
  EXPECT_EQ(simple_view, simple_expected);
  EXPECT_EQ(simple_pair.second, simple_expected.size());

  constexpr std::string_view acronym_name{"HTTPRequest"};
  constexpr size_t acronym_len = snake_case_length(acronym_name);
  constexpr auto acronym_pair = to_snake_case<acronym_len>(acronym_name);
  const std::string_view acronym_view(acronym_pair.first.data(),
                                      acronym_pair.second);
  constexpr std::string_view acronym_expected{"http_request"};
  EXPECT_EQ(acronym_view, acronym_expected);
  EXPECT_EQ(acronym_pair.second, acronym_expected.size());
}

TEST(StringUtilTest, ToSnakeCaseHandlesDelimitersAndDigits) {
  constexpr std::string_view hyphen_name{"User-ID"};
  constexpr size_t hyphen_len = snake_case_length(hyphen_name);
  constexpr auto hyphen_pair = to_snake_case<hyphen_len>(hyphen_name);
  const std::string_view hyphen_view(hyphen_pair.first.data(),
                                     hyphen_pair.second);
  constexpr std::string_view hyphen_expected{"user_id"};
  EXPECT_EQ(hyphen_view, hyphen_expected);
  EXPECT_EQ(hyphen_pair.second, hyphen_expected.size());

  constexpr std::string_view digit_name{"Field1Value"};
  constexpr size_t digit_len = snake_case_length(digit_name);
  constexpr auto digit_pair = to_snake_case<digit_len>(digit_name);
  const std::string_view digit_view(digit_pair.first.data(), digit_pair.second);
  constexpr std::string_view digit_expected{"field1_value"};
  EXPECT_EQ(digit_view, digit_expected);
  EXPECT_EQ(digit_pair.second, digit_expected.size());

  constexpr std::string_view snake_name{"already_snake"};
  constexpr size_t snake_len = snake_case_length(snake_name);
  constexpr auto snake_pair = to_snake_case<snake_len>(snake_name);
  const std::string_view snake_view(snake_pair.first.data(), snake_pair.second);
  constexpr std::string_view snake_expected{"already_snake"};
  EXPECT_EQ(snake_view, snake_expected);
  EXPECT_EQ(snake_pair.second, snake_expected.size());
}

// Generate random UTF-16 string ensuring valid surrogate pairs
std::u16string generateRandomUTF16String(size_t length) {
  std::u16string str;
  std::mt19937 generator(std::random_device{}());
  std::uniform_int_distribution<uint32_t> distribution(0, 0x10FFFF);

  while (str.size() < length) {
    uint32_t code_point = distribution(generator);

    if (code_point <= 0xD7FF ||
        (code_point >= 0xE000 && code_point <= 0xFFFF)) {
      str.push_back(static_cast<char16_t>(code_point));
    } else if (code_point >= 0x10000 && code_point <= 0x10FFFF) {
      code_point -= 0x10000;
      str.push_back(static_cast<char16_t>((code_point >> 10) + 0xD800));
      str.push_back(static_cast<char16_t>((code_point & 0x3FF) + 0xDC00));
    }
  }

  return str;
}

TEST(StringUtilTest, TestUtf16HasSurrogatePairs) {
  EXPECT_FALSE(utf16HasSurrogatePairs(std::u16string({0x99, 0x100})));
  std::u16string utf16 = {0xD83D, 0xDE00}; // 😀 emoji
  EXPECT_TRUE(utf16HasSurrogatePairs(utf16));
  EXPECT_TRUE(utf16HasSurrogatePairs(generateRandomUTF16String(3) + u"性能好"));
  EXPECT_TRUE(
      utf16HasSurrogatePairs(generateRandomUTF16String(10) + u"性能好"));
  EXPECT_TRUE(
      utf16HasSurrogatePairs(generateRandomUTF16String(30) + u"性能好"));
  EXPECT_TRUE(
      utf16HasSurrogatePairs(generateRandomUTF16String(60) + u"性能好"));
  EXPECT_TRUE(
      utf16HasSurrogatePairs(generateRandomUTF16String(120) + u"性能好"));
  EXPECT_TRUE(
      utf16HasSurrogatePairs(generateRandomUTF16String(200) + u"性能好"));
  EXPECT_TRUE(
      utf16HasSurrogatePairs(generateRandomUTF16String(300) + u"性能好"));
}

// Testing Basic Logic
TEST(UTF16ToUTF8Test, BasicConversion) {
  std::u16string utf16 = u"Hello, 世界!";
  std::string utf8 = fory::utf16ToUtf8(utf16, true);
  ASSERT_EQ(utf8, u8"Hello, 世界!");
}

// Testing Empty String
TEST(UTF16ToUTF8Test, EmptyString) {
  std::u16string utf16 = u"";
  std::string utf8 = fory::utf16ToUtf8(utf16, true);
  ASSERT_EQ(utf8, "");
}

// Testing emoji
TEST(UTF16ToUTF8Test, SurrogatePairs) {
  std::u16string utf16 = {0xD83D, 0xDE00}; // 😀 emoji
  std::string utf8 = fory::utf16ToUtf8(utf16, true);
  ASSERT_EQ(utf8, "\xF0\x9F\x98\x80");
}

// Testing Boundary
TEST(UTF16ToUTF8Test, BoundaryValues) {
  std::u16string utf16 = {0x0000, 0xFFFF};
  std::string utf8 = fory::utf16ToUtf8(utf16, true);
  std::string expected_utf8 = std::string("\x00", 1) + "\xEF\xBF\xBF";
  ASSERT_EQ(utf8, expected_utf8);
}

// Testing Special Characters
TEST(UTF16ToUTF8Test, SpecialCharacters) {
  std::u16string utf16 = u" \n\t";
  std::string utf8 = fory::utf16ToUtf8(utf16, true);
  ASSERT_EQ(utf8, " \n\t");
}

// Testing LittleEndian
TEST(UTF16ToUTF8Test, LittleEndian) {
  std::u16string utf16 = {0x61, 0x62}; // "ab"
  std::string utf8 = fory::utf16ToUtf8(utf16, true);
  ASSERT_EQ(utf8, "ab");
}

// Testing BigEndian
TEST(UTF16ToUTF8Test, BigEndian) {
  std::u16string utf16 = {0xFFFE, 0xFFFE};
  std::string utf8 = fory::utf16ToUtf8(utf16, false);
  ASSERT_EQ(utf8, "\xEF\xBF\xBE\xEF\xBF\xBE");
}

// Generate random UTF-8 string
std::string generateRandomUTF8String(size_t length) {
  std::string str;
  std::mt19937 generator(std::random_device{}());
  std::uniform_int_distribution<uint32_t> distribution(0, 0x10FFFF);

  while (str.size() < length) {
    uint32_t code_point = distribution(generator);

    // Skip surrogate pairs (0xD800 to 0xDFFF) and other invalid Unicode code
    // points
    if ((code_point >= 0xD800 && code_point <= 0xDFFF) ||
        code_point > 0x10FFFF) {
      continue;
    }

    if (code_point <= 0x7F) {
      str.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7FF) {
      str.push_back(0xC0 | (code_point >> 6));
      str.push_back(0x80 | (code_point & 0x3F));
    } else if (code_point <= 0xFFFF) {
      str.push_back(0xE0 | (code_point >> 12));
      str.push_back(0x80 | ((code_point >> 6) & 0x3F));
      str.push_back(0x80 | (code_point & 0x3F));
    } else {
      str.push_back(0xF0 | (code_point >> 18));
      str.push_back(0x80 | ((code_point >> 12) & 0x3F));
      str.push_back(0x80 | ((code_point >> 6) & 0x3F));
      str.push_back(0x80 | (code_point & 0x3F));
    }
  }

  return str;
}

// Testing Basic Logic
TEST(UTF8ToUTF16Test, BasicConversion) {
  std::string utf8 = u8"Hello, 世界!";
  std::u16string utf16 = fory::utf8ToUtf16(utf8, true);
  ASSERT_EQ(utf16, u"Hello, 世界!");
}

// Testing Empty String
TEST(UTF8ToUTF16Test, EmptyString) {
  std::string utf8 = "";
  std::u16string utf16 = fory::utf8ToUtf16(utf8, true);
  ASSERT_EQ(utf16, u"");
}

// Testing emoji
TEST(UTF8ToUTF16Test, SurrogatePairs) {
  std::string utf8 = "\xF0\x9F\x98\x80"; // 😀 emoji
  std::u16string utf16 = fory::utf8ToUtf16(utf8, true);
  std::u16string expected_utf16 = {0xD83D, 0xDE00}; // Surrogate pair for emoji
  ASSERT_EQ(utf16, expected_utf16);
}

// Correct Boundary testing for U+FFFD (replacement character)
TEST(UTF8ToUTF16Test, BoundaryValues) {
  // "\xEF\xBF\xBD" is the UTF-8 encoding for U+FFFD (replacement character)
  std::string utf8 = "\xEF\xBF\xBD"; // U+FFFD in UTF-8
  std::u16string utf16 = fory::utf8ToUtf16(utf8, true);
  std::u16string expected_utf16 = {
      0xFFFD}; // Expected UTF-16 representation of U+FFFD
  ASSERT_EQ(utf16, expected_utf16);
}

// Testing Special Characters
TEST(UTF8ToUTF16Test, SpecialCharacters) {
  std::string utf8 = " \n\t";
  std::u16string utf16 = fory::utf8ToUtf16(utf8, true);
  ASSERT_EQ(utf16, u" \n\t");
}

// Testing LittleEndian
TEST(UTF8ToUTF16Test, LittleEndian) {
  std::string utf8 = "ab";
  std::u16string utf16 = fory::utf8ToUtf16(utf8, true);
  std::u16string expected_utf16 = {
      0x61, 0x62}; // Little-endian UTF-16 representation of "ab"
  ASSERT_EQ(utf16, expected_utf16);
}

// Correct BigEndian testing for BOM (Byte Order Mark)
TEST(UTF8ToUTF16Test, BigEndian) {
  std::string utf8 = "\xEF\xBB\xBF"; // BOM in UTF-8 (0xFEFF)
  std::u16string utf16 = fory::utf8ToUtf16(utf8, false); // Big-endian
  std::u16string expected_utf16 = {0xFFFE}; // Expected BOM in UTF-16
  ASSERT_EQ(utf16, expected_utf16);
}

// Testing round-trip conversion (UTF-8 -> UTF-16 -> UTF-8)
TEST(UTF8ToUTF16Test, RoundTripConversion) {
  std::string original_utf8 = u8"Hello, 世界!";
  std::u16string utf16 = fory::utf8ToUtf16(original_utf8, true);
  std::string utf8_converted_back = fory::utf16ToUtf8(utf16, true);
  ASSERT_EQ(original_utf8, utf8_converted_back);
}

} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
