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

#pragma once

#include "platform.h"
#include <array>
#include <cstdint>
#include <string>
#include <string_view>
#include <utility>

namespace fory {

static inline bool isAsciiFallback(const char *data, size_t size) {
  size_t i = 0;
  // Loop through 8-byte chunks
  for (; i + 7 < size; i += 8) {
    // Load 8 bytes from the string
    uint64_t chunk = *reinterpret_cast<const uint64_t *>(data + i);
    // Check if any byte in the 64-bit chunk is >= 128
    // This checks if any of the top bits of each byte are set
    if (chunk & 0x8080808080808080ULL) {
      return false;
    }
  }
  for (; i < size; ++i) {
    if (static_cast<unsigned char>(data[i]) >= 128) {
      return false;
    }
  }
  return true;
}

static inline bool isLatin1Fallback(const uint16_t *data, size_t size) {
  for (size_t i = 0; i < size; ++i) {
    if (data[i] > 0xFF) {
      return false;
    }
  }
  return true;
}

std::string utf16ToUtf8(const std::u16string &utf16, bool is_little_endian);

std::u16string utf8ToUtf16(const std::string &utf8, bool is_little_endian);

// inline

// Swap bytes to convert from big endian to little endian
inline uint16_t swapBytes(uint16_t value) {
  return (value >> 8) | (value << 8);
}

inline void utf16ToUtf8(uint16_t code_unit, char *&output) {
  if (code_unit < 0x80) {
    *output++ = static_cast<char>(code_unit);
  } else if (code_unit < 0x800) {
    *output++ = static_cast<char>(0xC0 | (code_unit >> 6));
    *output++ = static_cast<char>(0x80 | (code_unit & 0x3F));
  } else {
    *output++ = static_cast<char>(0xE0 | (code_unit >> 12));
    *output++ = static_cast<char>(0x80 | ((code_unit >> 6) & 0x3F));
    *output++ = static_cast<char>(0x80 | (code_unit & 0x3F));
  }
}

inline void utf16SurrogatePairToUtf8(uint16_t high, uint16_t low, char *&utf8) {
  uint32_t code_point = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);
  *utf8++ = static_cast<char>((code_point >> 18) | 0xF0);
  *utf8++ = static_cast<char>(((code_point >> 12) & 0x3F) | 0x80);
  *utf8++ = static_cast<char>(((code_point >> 6) & 0x3F) | 0x80);
  *utf8++ = static_cast<char>((code_point & 0x3F) | 0x80);
}

static inline bool hasSurrogatePairFallback(const uint16_t *data, size_t size) {
  for (size_t i = 0; i < size; ++i) {
    auto c = data[i];
    if (c >= 0xD800 && c <= 0xDFFF) {
      return true;
    }
  }
  return false;
}

namespace detail {

constexpr bool is_upper_ascii(char c) { return c >= 'A' && c <= 'Z'; }

constexpr bool is_lower_ascii(char c) { return c >= 'a' && c <= 'z'; }

constexpr bool is_digit_ascii(char c) { return c >= '0' && c <= '9'; }

constexpr char to_lower_ascii(char c) {
  return is_upper_ascii(c) ? static_cast<char>(c - 'A' + 'a') : c;
}

constexpr bool needs_snake_separator(std::string_view name, size_t index) {
  if (index == 0)
    return false;
  char curr = name[index];
  if (!is_upper_ascii(curr))
    return false;

  char prev = name[index - 1];
  if (is_lower_ascii(prev) || is_digit_ascii(prev))
    return true;

  if (is_upper_ascii(prev) && index + 1 < name.size()) {
    char next = name[index + 1];
    if (is_lower_ascii(next))
      return true;
  }
  return false;
}

constexpr size_t snake_case_length(std::string_view name) {
  size_t length = 0;
  for (size_t i = 0; i < name.size(); ++i) {
    char c = name[i];
    if (c == '_' || c == '-') {
      ++length;
      continue;
    }
    if (needs_snake_separator(name, i)) {
      ++length;
    }
    ++length;
  }
  return length;
}

template <size_t MaxLength>
constexpr std::pair<std::array<char, MaxLength + 1>, size_t>
to_snake_case(std::string_view name) {
  std::array<char, MaxLength + 1> buffer{};
  size_t pos = 0;
  for (size_t i = 0; i < name.size(); ++i) {
    char c = name[i];
    if (c == '_' || c == '-') {
      buffer[pos++] = '_';
      continue;
    }
    if (needs_snake_separator(name, i)) {
      buffer[pos++] = '_';
    }
    buffer[pos++] = is_upper_ascii(c) ? to_lower_ascii(c) : c;
  }
  buffer[pos] = '\0';
  return {buffer, pos};
}

} // namespace detail

template <size_t MaxLength>
constexpr std::pair<std::array<char, MaxLength + 1>, size_t>
to_snake_case(std::string_view name) {
  return detail::to_snake_case<MaxLength>(name);
}

constexpr size_t snake_case_length(std::string_view name) {
  return detail::snake_case_length(name);
}
#if defined(FORY_HAS_IMMINTRIN)

inline bool isAscii(const char *data, size_t length) {
  constexpr size_t VECTOR_SIZE = 32;
  const auto *ptr = reinterpret_cast<const __m256i *>(data);
  const auto *end = ptr + length / VECTOR_SIZE;
  const __m256i mask = _mm256_set1_epi8(0x80);

  for (; ptr < end; ++ptr) {
    __m256i vec = _mm256_loadu_si256(ptr);
    __m256i cmp = _mm256_and_si256(vec, mask);
    if (!_mm256_testz_si256(cmp, cmp))
      return false;
  }

  return isAsciiFallback(data + (length / VECTOR_SIZE) * VECTOR_SIZE,
                         length % VECTOR_SIZE);
}

inline bool isLatin1(const uint16_t *data, size_t length) {
  constexpr size_t VECTOR_SIZE = 16;
  const auto *ptr = reinterpret_cast<const __m256i *>(data);
  const auto *end = ptr + length / VECTOR_SIZE;

  const __m256i mask = _mm256_set1_epi16(0x00FF);

  for (; ptr < end; ++ptr) {
    __m256i vec = _mm256_loadu_si256(ptr);
    __m256i cmp = _mm256_cmpgt_epi16(vec, mask);
    if (!_mm256_testz_si256(cmp, cmp)) {
      return false;
    }
  }

  return isLatin1Fallback(data + (length / VECTOR_SIZE) * VECTOR_SIZE,
                          length % VECTOR_SIZE);
}
inline bool utf16HasSurrogatePairs(const uint16_t *data, size_t length) {
  // Direct fallback implementation - SIMD versions were consistently slower
  // due to early-exit characteristics: surrogate pairs are rare and when
  // present, often appear early in strings, making SIMD setup overhead
  // outweigh any vectorization benefits.
  return hasSurrogatePairFallback(data, length);
}

#elif defined(FORY_HAS_NEON)
inline bool isAscii(const char *data, size_t length) {
  size_t i = 0;
  uint8x16_t mostSignificantBit = vdupq_n_u8(0x80);
  for (; i + 15 < length; i += 16) {
    uint8x16_t chunk = vld1q_u8(reinterpret_cast<const uint8_t *>(&data[i]));
    uint8x16_t result = vandq_u8(chunk, mostSignificantBit);
    if (vmaxvq_u8(result) != 0) {
      return false;
    }
  }
  // Check the remaining characters
  return isAsciiFallback(data + i, length - i);
}

inline bool isLatin1(const uint16_t *data, size_t length) {
  size_t i = 0;
  uint16x8_t maxAllowed = vdupq_n_u16(0xFF);
  for (; i + 7 < length; i += 8) {
    uint16x8_t chunk = vld1q_u16(&data[i]);
    uint16x8_t cmp = vcgtq_u16(chunk, maxAllowed);
    if (vmaxvq_u16(cmp) != 0) {
      return false;
    }
  }
  // Check the remaining elements
  return isLatin1Fallback(data + i, length - i);
}

inline bool utf16HasSurrogatePairs(const uint16_t *data, size_t length) {
  // Direct fallback implementation - SIMD versions were consistently slower
  // due to early-exit characteristics: surrogate pairs are rare and when
  // present, often appear early in strings, making SIMD setup overhead
  // outweigh any vectorization benefits.
  return hasSurrogatePairFallback(data, length);
}
#elif defined(FORY_HAS_SSE2)
inline bool isAscii(const char *data, size_t length) {
  const __m128i mostSignificantBit = _mm_set1_epi8(static_cast<char>(0x80));
  size_t i = 0;
  for (; i + 15 < length; i += 16) {
    __m128i chunk =
        _mm_loadu_si128(reinterpret_cast<const __m128i *>(&data[i]));
    __m128i result = _mm_and_si128(chunk, mostSignificantBit);
    if (_mm_movemask_epi8(result) != 0) {
      return false;
    }
  }
  // Check the remaining characters
  return isAsciiFallback(data + i, length - i);
}

inline bool isLatin1(const uint16_t *data, size_t length) {
  const __m128i maxAllowed = _mm_set1_epi16(0xFF);
  size_t i = 0;
  for (; i + 7 < length; i += 8) {
    __m128i chunk =
        _mm_loadu_si128(reinterpret_cast<const __m128i *>(&data[i]));
    __m128i cmp = _mm_cmpgt_epi16(chunk, maxAllowed);
    if (_mm_movemask_epi8(cmp) != 0) {
      return false;
    }
  }
  // Check the remaining elements
  return isLatin1Fallback(data + i, length - i);
}

inline bool utf16HasSurrogatePairs(const uint16_t *data, size_t length) {
  // Direct fallback implementation - SIMD versions were consistently slower
  // due to early-exit characteristics: surrogate pairs are rare and when
  // present, often appear early in strings, making SIMD setup overhead
  // outweigh any vectorization benefits.
  return hasSurrogatePairFallback(data, length);
}
#else
inline bool isAscii(const char *data, size_t length) {
  return isAsciiFallback(data, length);
}

inline bool isLatin1(const uint16_t *data, size_t length) {
  return isLatin1Fallback(data, length);
}

inline bool utf16HasSurrogatePairs(const uint16_t *data, size_t length) {
  return hasSurrogatePairFallback(data, length);
}
#endif

inline bool isAscii(const std::string &str) {
  return isAscii(str.data(), str.size());
}

inline bool isLatin1(const std::u16string &str) {
  const std::uint16_t *data =
      reinterpret_cast<const std::uint16_t *>(str.data());
  return isLatin1(data, str.size());
}

inline bool utf16HasSurrogatePairs(const std::u16string &str) {
  // Inline implementation for best performance
  for (size_t i = 0; i < str.size(); ++i) {
    auto c = str[i];
    if (c >= 0xD800 && c <= 0xDFFF) {
      return true;
    }
  }
  return false;
}

} // namespace fory
