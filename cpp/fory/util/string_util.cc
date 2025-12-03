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

#include <chrono>
#include <string>

#include "macros.h"
#include "string_util.h"

namespace fory {

std::u16string utf8ToUtf16SIMD(const std::string &utf8, bool is_little_endian) {
  std::u16string utf16;
  utf16.reserve(utf8.size()); // Reserve space to avoid frequent reallocations

  char buffer[64]; // Buffer to hold temporary UTF-16 results
  char16_t *output =
      reinterpret_cast<char16_t *>(buffer); // Use char16_t for output

  size_t i = 0;
  size_t n = utf8.size();

  while (i + 32 <= n) {

    for (int j = 0; j < 32; ++j) {
      uint8_t byte = utf8[i + j];

      if (byte < 0x80) {
        // 1-byte character (ASCII)
        *output++ = static_cast<char16_t>(byte);
      } else if (byte < 0xE0) {
        // 2-byte character
        uint16_t utf16_char = ((byte & 0x1F) << 6) | (utf8[i + j + 1] & 0x3F);
        if (!is_little_endian) {
          utf16_char = (utf16_char >> 8) |
                       (utf16_char << 8); // Swap bytes for big-endian
        }
        *output++ = utf16_char;
        ++j;
      } else if (byte < 0xF0) {
        // 3-byte character
        uint16_t utf16_char = ((byte & 0x0F) << 12) |
                              ((utf8[i + j + 1] & 0x3F) << 6) |
                              (utf8[i + j + 2] & 0x3F);
        if (!is_little_endian) {
          utf16_char = (utf16_char >> 8) |
                       (utf16_char << 8); // Swap bytes for big-endian
        }
        *output++ = utf16_char;
        j += 2;
      } else {
        // 4-byte character (surrogate pair handling required)
        uint32_t code_point =
            ((byte & 0x07) << 18) | ((utf8[i + j + 1] & 0x3F) << 12) |
            ((utf8[i + j + 2] & 0x3F) << 6) | (utf8[i + j + 3] & 0x3F);

        // Convert the code point to a surrogate pair
        uint16_t high_surrogate = 0xD800 + ((code_point - 0x10000) >> 10);
        uint16_t low_surrogate = 0xDC00 + (code_point & 0x3FF);

        if (!is_little_endian) {
          high_surrogate = (high_surrogate >> 8) |
                           (high_surrogate << 8); // Swap bytes for big-endian
          low_surrogate = (low_surrogate >> 8) |
                          (low_surrogate << 8); // Swap bytes for big-endian
        }

        *output++ = high_surrogate;
        *output++ = low_surrogate;

        j += 3;
      }
    }

    // Append the processed buffer to the final utf16 string
    utf16.append(reinterpret_cast<char16_t *>(buffer),
                 output - reinterpret_cast<char16_t *>(buffer));
    output =
        reinterpret_cast<char16_t *>(buffer); // Reset output buffer pointer
    i += 32;
  }

  // Handle remaining characters
  while (i < n) {
    uint8_t byte = utf8[i];

    if (byte < 0x80) {
      *output++ = static_cast<char16_t>(byte);
    } else if (byte < 0xE0) {
      uint16_t utf16_char = ((byte & 0x1F) << 6) | (utf8[i + 1] & 0x3F);
      if (!is_little_endian) {
        utf16_char =
            (utf16_char >> 8) | (utf16_char << 8); // Swap bytes for big-endian
      }
      *output++ = utf16_char;
      ++i;
    } else if (byte < 0xF0) {
      uint16_t utf16_char = ((byte & 0x0F) << 12) |
                            ((utf8[i + 1] & 0x3F) << 6) | (utf8[i + 2] & 0x3F);
      if (!is_little_endian) {
        utf16_char =
            (utf16_char >> 8) | (utf16_char << 8); // Swap bytes for big-endian
      }
      *output++ = utf16_char;
      i += 2;
    } else {
      uint32_t code_point = ((byte & 0x07) << 18) |
                            ((utf8[i + 1] & 0x3F) << 12) |
                            ((utf8[i + 2] & 0x3F) << 6) | (utf8[i + 3] & 0x3F);

      uint16_t high_surrogate = 0xD800 + ((code_point - 0x10000) >> 10);
      uint16_t low_surrogate = 0xDC00 + (code_point & 0x3FF);

      if (!is_little_endian) {
        high_surrogate = (high_surrogate >> 8) | (high_surrogate << 8);
        low_surrogate = (low_surrogate >> 8) | (low_surrogate << 8);
      }

      *output++ = high_surrogate;
      *output++ = low_surrogate;

      i += 3;
    }

    ++i;
  }

  // Append the last part of the buffer to the utf16 string
  utf16.append(reinterpret_cast<char16_t *>(buffer),
               output - reinterpret_cast<char16_t *>(buffer));

  return utf16;
}

#if defined(FORY_HAS_IMMINTRIN)

FORY_TARGET_AVX2_ATTR std::string utf16ToUtf8(const std::u16string &utf16,
                                              bool is_little_endian) {
  std::string utf8;
  utf8.reserve(utf16.size() *
               3); // Reserve enough space to avoid frequent reallocations

  const __m256i limit1 = _mm256_set1_epi16(0x80);
  const __m256i limit2 = _mm256_set1_epi16(0x800);
  const __m256i surrogate_high_start = _mm256_set1_epi16(0xD800);
  const __m256i surrogate_high_end = _mm256_set1_epi16(0xDBFF);
  const __m256i surrogate_low_start = _mm256_set1_epi16(0xDC00);
  const __m256i surrogate_low_end = _mm256_set1_epi16(0xDFFF);

  char buffer[64]; // Buffer to hold temporary UTF-8 bytes
  char *output = buffer;

  size_t i = 0;
  size_t n = utf16.size();

  while (i + 16 <= n) {
    __m256i in =
        _mm256_loadu_si256(reinterpret_cast<const __m256i *>(utf16.data() + i));

    if (!is_little_endian) {
      in = _mm256_or_si256(
          _mm256_slli_epi16(in, 8),
          _mm256_srli_epi16(in, 8)); // Swap bytes for big-endian
    }

    __m256i mask1 = _mm256_cmpgt_epi16(in, limit1);
    __m256i mask2 = _mm256_cmpgt_epi16(in, limit2);
    __m256i high_surrogate_mask =
        _mm256_and_si256(_mm256_cmpgt_epi16(in, surrogate_high_start),
                         _mm256_cmpgt_epi16(in, surrogate_high_end));
    __m256i low_surrogate_mask =
        _mm256_and_si256(_mm256_cmpgt_epi16(in, surrogate_low_start),
                         _mm256_cmpgt_epi16(in, surrogate_low_end));

    if (_mm256_testz_si256(mask1, mask1)) {
      // All values < 0x80, 1 byte per character
      for (int j = 0; j < 16; ++j) {
        *output++ = static_cast<char>(utf16[i + j]);
      }
    } else if (_mm256_testz_si256(mask2, mask2)) {
      // All values < 0x800, 2 bytes per character
      for (int j = 0; j < 16; ++j) {
        utf16ToUtf8(utf16[i + j], output);
      }
    } else {
      // Mix of 1, 2, and 3 byte characters
      for (int j = 0; j < 16; ++j) {
        if (_mm256_testz_si256(high_surrogate_mask, high_surrogate_mask) &&
            j + 1 < 16 &&
            !_mm256_testz_si256(low_surrogate_mask, low_surrogate_mask)) {
          // Surrogate pair
          utf16SurrogatePairToUtf8(utf16[i + j], utf16[i + j + 1], output);
          ++j;
        } else {
          utf16ToUtf8(utf16[i + j], output);
        }
      }
    }

    utf8.append(buffer, output - buffer);
    output = buffer; // Reset output buffer pointer
    i += 16;
  }

  // Handle remaining characters
  while (i < n) {
    if (i + 1 < n && utf16[i] >= 0xD800 && utf16[i] <= 0xDBFF &&
        utf16[i + 1] >= 0xDC00 && utf16[i + 1] <= 0xDFFF) {
      // Surrogate pair
      utf16SurrogatePairToUtf8(utf16[i], utf16[i + 1], output);
      ++i;
    } else {
      utf16ToUtf8(utf16[i], output);
    }
    ++i;
  }
  utf8.append(buffer, output - buffer);

  return utf8;
}

std::u16string utf8ToUtf16(const std::string &utf8, bool is_little_endian) {
  return utf8ToUtf16SIMD(utf8, is_little_endian);
}

#elif defined(FORY_HAS_NEON)

std::string utf16ToUtf8(const std::u16string &utf16, bool is_little_endian) {
  std::string utf8;
  utf8.reserve(utf16.size() * 3);

  uint16x8_t limit1 = vdupq_n_u16(0x80);
  uint16x8_t limit2 = vdupq_n_u16(0x800);
  uint16x8_t surrogate_high_start = vdupq_n_u16(0xD800);
  uint16x8_t surrogate_high_end = vdupq_n_u16(0xDBFF);
  uint16x8_t surrogate_low_start = vdupq_n_u16(0xDC00);
  uint16x8_t surrogate_low_end = vdupq_n_u16(0xDFFF);

  char buffer[64];
  char *output = buffer;
  size_t i = 0;
  size_t n = utf16.size();

  while (i + 8 <= n) {
    uint16x8_t in =
        vld1q_u16(reinterpret_cast<const uint16_t *>(utf16.data() + i));
    if (!is_little_endian) {
      in = vorrq_u16(vshlq_n_u16(in, 8),
                     vshrq_n_u16(in, 8)); // Swap bytes for big-endian
    }

    uint16x8_t mask1 = vcgtq_u16(in, limit1);
    uint16x8_t mask2 = vcgtq_u16(in, limit2);
    uint16x8_t high_surrogate_mask = vandq_u16(
        vcgtq_u16(in, surrogate_high_start), vcltq_u16(in, surrogate_high_end));
    uint16x8_t low_surrogate_mask = vandq_u16(
        vcgtq_u16(in, surrogate_low_start), vcltq_u16(in, surrogate_low_end));

    if (vmaxvq_u16(mask1) == 0) {
      for (int j = 0; j < 8; ++j) {
        *output++ = static_cast<char>(utf16[i + j]);
      }
    } else if (vmaxvq_u16(mask2) == 0) {
      for (int j = 0; j < 8; ++j) {
        utf16ToUtf8(utf16[i + j], output);
      }
    } else {
      for (int j = 0; j < 8; ++j) {
        if (vmaxvq_u16(high_surrogate_mask) == 0 && j + 1 < 8 &&
            vmaxvq_u16(low_surrogate_mask) != 0) {
          utf16SurrogatePairToUtf8(utf16[i + j], utf16[i + j + 1], output);
          ++j;
        } else {
          utf16ToUtf8(utf16[i + j], output);
        }
      }
    }

    utf8.append(buffer, output - buffer);
    output = buffer;
    i += 8;
  }

  while (i < n) {
    if (i + 1 < n && utf16[i] >= 0xD800 && utf16[i] <= 0xDBFF &&
        utf16[i + 1] >= 0xDC00 && utf16[i + 1] <= 0xDFFF) {
      utf16SurrogatePairToUtf8(utf16[i], utf16[i + 1], output);
      ++i;
    } else {
      utf16ToUtf8(utf16[i], output);
    }
    ++i;
  }
  utf8.append(buffer, output - buffer);

  return utf8;
}

std::u16string utf8ToUtf16(const std::string &utf8, bool is_little_endian) {
  return utf8ToUtf16SIMD(utf8, is_little_endian);
}

#elif defined(FORY_HAS_RISCV_VECTOR)

std::string utf16ToUtf8(const std::u16string &utf16, bool is_little_endian) {
  std::string utf8;
  utf8.reserve(utf16.size() * 3);

  auto limit1 = vmv_v_x_u16m1(0x80, 8);
  auto limit2 = vmv_v_x_u16m1(0x800, 8);
  auto surrogate_high_start = vmv_v_x_u16m1(0xD800, 8);
  auto surrogate_high_end = vmv_v_x_u16m1(0xDBFF, 8);
  auto surrogate_low_start = vmv_v_x_u16m1(0xDC00, 8);
  auto surrogate_low_end = vmv_v_x_u16m1(0xDFFF, 8);

  char buffer[48];
  char *output = buffer;
  size_t i = 0;
  size_t n = utf16.size();

  while (i + 8 <= n) {
    auto in =
        vle16_v_u16m1(reinterpret_cast<const uint16_t *>(utf16.data() + i), 8);
    if (!is_little_endian) {
      in = vor_vv_u16m1(vsrl_vx_u16m1(in, 8, 8), vsll_vx_u16m1(in, 8, 8), 8);
    }

    auto mask1 = vmsgt_vx_u16m1(in, 0x80, 8);
    auto mask2 = vmsgt_vx_u16m1(in, 0x800, 8);
    auto high_surrogate_mask = vmand_vv_u16m1(vmsgt_vx_u16m1(in, 0xD800, 8),
                                              vmslt_vx_u16m1(in, 0xDBFF, 8), 8);
    auto low_surrogate_mask = vmand_vv_u16m1(vmsgt_vx_u16m1(in, 0xDC00, 8),
                                             vmslt_vx_u16m1(in, 0xDFFF, 8), 8);

    if (vmslt_vx_u16m1(mask1, 0, 8)) {
      for (int j = 0; j < 8; ++j) {
        *output++ = static_cast<char>(vget_vx_u16m1(in, j));
      }
    } else if (vmslt_vx_u16m1(mask2, 0, 8)) {
      for (int j = 0; j < 8; ++j) {
        utf16ToUtf8(vget_vx_u16m1(in, j), output);
      }
    } else {
      for (int j = 0; j < 8; ++j) {
        if (vfirst_m_b8(
                vmand_vv_b8(high_surrogate_mask,
                            vmsne_vx_u8m1_b8(vmv_v_x_u8m1(0, 8), 0, 8))) &&
            j + 1 < 8 &&
            vfirst_m_b8(
                vmand_vv_b8(low_surrogate_mask,
                            vmsne_vx_u8m1_b8(vmv_v_x_u8m1(0, 8), 0, 8)))) {
          utf16SurrogatePairToUtf8(vget_vx_u16m1(in, j),
                                   vget_vx_u16m1(in, j + 1), output);
          ++j;
        } else {
          utf16ToUtf8(vget_vx_u16m1(in, j), output);
        }
      }
    }

    utf8.append(buffer, output - buffer);
    output = buffer;
    i += 8;
  }

  while (i < n) {
    if (i + 1 < n && utf16[i] >= 0xD800 && utf16[i] <= 0xDBFF &&
        utf16[i + 1] >= 0xDC00 && utf16[i + 1] <= 0xDFFF) {
      utf16SurrogatePairToUtf8(utf16[i], utf16[i + 1], output);
      ++i;
    } else {
      utf16ToUtf8(utf16[i], output);
    }
    ++i;
  }
  utf8.append(buffer, output - buffer);

  return utf8;
}

std::u16string utf8ToUtf16(const std::string &utf8, bool is_little_endian) {
  return utf8ToUtf16SIMD(utf8, is_little_endian);
}

#else

// Fallback implementation without SIMD acceleration
std::string utf16ToUtf8(const std::u16string &utf16, bool is_little_endian) {
  std::string utf8;
  utf8.reserve(utf16.size() *
               3); // Reserve enough space to avoid frequent reallocations

  size_t i = 0;
  size_t n = utf16.size();
  char buffer[4]; // Buffer to hold temporary UTF-8 bytes
  char *output = buffer;

  while (i < n) {
    uint16_t code_unit = utf16[i];
    if (!is_little_endian) {
      code_unit = swapBytes(code_unit);
    }
    if (i + 1 < n && code_unit >= 0xD800 && code_unit <= 0xDBFF &&
        utf16[i + 1] >= 0xDC00 && utf16[i + 1] <= 0xDFFF) {
      // Surrogate pair
      uint16_t high = code_unit;
      uint16_t low = utf16[i + 1];
      if (!is_little_endian) {
        low = swapBytes(low);
      }
      utf16SurrogatePairToUtf8(high, low, output);
      utf8.append(buffer, output - buffer);
      output = buffer;
      ++i;
    } else {
      utf16ToUtf8(code_unit, output);
      utf8.append(buffer, output - buffer);
      output = buffer;
    }
    ++i;
  }
  return utf8;
}

// Fallback implementation without SIMD acceleration
std::u16string utf8ToUtf16(const std::string &utf8, bool is_little_endian) {
  std::u16string utf16;   // Resulting UTF-16 string
  size_t i = 0;           // Index for traversing the UTF-8 string
  size_t n = utf8.size(); // Total length of the UTF-8 string

  // Loop through each byte of the UTF-8 string
  while (i < n) {
    uint32_t code_point = 0;   // The Unicode code point
    unsigned char c = utf8[i]; // Current byte of the UTF-8 string

    // Determine the number of bytes for this character based on its first byte
    if ((c & 0x80) == 0) {
      // 1-byte character (ASCII)
      code_point = c;
      ++i;
    } else if ((c & 0xE0) == 0xC0) {
      // 2-byte character
      code_point = c & 0x1F;
      code_point = (code_point << 6) | (utf8[i + 1] & 0x3F);
      i += 2;
    } else if ((c & 0xF0) == 0xE0) {
      // 3-byte character
      code_point = c & 0x0F;
      code_point = (code_point << 6) | (utf8[i + 1] & 0x3F);
      code_point = (code_point << 6) | (utf8[i + 2] & 0x3F);
      i += 3;
    } else if ((c & 0xF8) == 0xF0) {
      // 4-byte character
      code_point = c & 0x07;
      code_point = (code_point << 6) | (utf8[i + 1] & 0x3F);
      code_point = (code_point << 6) | (utf8[i + 2] & 0x3F);
      code_point = (code_point << 6) | (utf8[i + 3] & 0x3F);
      i += 4;
    } else {
      // Invalid UTF-8 byte sequence
      throw std::invalid_argument("Invalid UTF-8 encoding.");
    }

    // If the code point is beyond the BMP range, use surrogate pairs
    if (code_point >= 0x10000) {
      code_point -= 0x10000; // Subtract 0x10000 to get the surrogate pair
      uint16_t high_surrogate = 0xD800 + (code_point >> 10);  // High surrogate
      uint16_t low_surrogate = 0xDC00 + (code_point & 0x3FF); // Low surrogate

      // If not little-endian, swap bytes of the surrogates
      if (!is_little_endian) {
        high_surrogate = (high_surrogate >> 8) | (high_surrogate << 8);
        low_surrogate = (low_surrogate >> 8) | (low_surrogate << 8);
      }

      // Add both high and low surrogates to the UTF-16 string
      utf16.push_back(high_surrogate);
      utf16.push_back(low_surrogate);
    } else {
      // For code points within the BMP range, directly store as a 16-bit value
      uint16_t utf16_char = static_cast<uint16_t>(code_point);

      // If not little-endian, swap the bytes of the 16-bit character
      if (!is_little_endian) {
        utf16_char = (utf16_char >> 8) | (utf16_char << 8);
      }

      // Add the UTF-16 character to the string
      utf16.push_back(utf16_char);
    }
  }

  // Return the resulting UTF-16 string
  return utf16;
}

#endif

} // namespace fory
