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
#include <stdexcept>
#include <string>

#include "macros.h"
#include "string_util.h"

namespace fory {

bool detail::utf8_to_utf16_checked(const char *utf8, size_t n,
                                   bool is_little_endian,
                                   std::u16string &utf16) {
  utf16.clear();
  utf16.reserve(n);

  std::array<char16_t, 32> output;
  size_t output_size = 0;
  size_t i = 0;
  while (i < n) {
    while (i < n && output_size < output.size()) {
      const uint8_t byte = static_cast<uint8_t>(utf8[i]);
      if (byte >= 0x80) {
        break;
      }
      output[output_size++] = static_cast<char16_t>(byte);
      ++i;
    }
    if (output_size == output.size()) {
      utf16.append(output.data(), output_size);
      output_size = 0;
      continue;
    }
    if (i == n) {
      break;
    }

    const uint8_t byte = static_cast<uint8_t>(utf8[i]);
    size_t byte_count;
    size_t code_unit_count;
    if (byte < 0xE0) {
      byte_count = 2;
      code_unit_count = 1;
    } else if (byte < 0xF0) {
      byte_count = 3;
      code_unit_count = 1;
    } else {
      byte_count = 4;
      code_unit_count = 2;
    }

    if (FORY_PREDICT_FALSE(byte_count > n - i)) {
      return false;
    }

    // Four-byte sequences emit two code units. Flush before either sequence
    // shape would cross the fixed scratch boundary.
    if (FORY_PREDICT_FALSE(output_size + code_unit_count > output.size())) {
      utf16.append(output.data(), output_size);
      output_size = 0;
    }

    if (byte_count == 2) {
      uint16_t utf16_char =
          ((byte & 0x1F) << 6) | (static_cast<uint8_t>(utf8[i + 1]) & 0x3F);
      if (!is_little_endian) {
        utf16_char = swap_bytes(utf16_char);
      }
      output[output_size++] = static_cast<char16_t>(utf16_char);
    } else if (byte_count == 3) {
      uint16_t utf16_char = ((byte & 0x0F) << 12) |
                            ((static_cast<uint8_t>(utf8[i + 1]) & 0x3F) << 6) |
                            (static_cast<uint8_t>(utf8[i + 2]) & 0x3F);
      if (!is_little_endian) {
        utf16_char = swap_bytes(utf16_char);
      }
      output[output_size++] = static_cast<char16_t>(utf16_char);
    } else {
      const uint32_t code_point =
          ((byte & 0x07) << 18) |
          ((static_cast<uint8_t>(utf8[i + 1]) & 0x3F) << 12) |
          ((static_cast<uint8_t>(utf8[i + 2]) & 0x3F) << 6) |
          (static_cast<uint8_t>(utf8[i + 3]) & 0x3F);
      uint16_t high_surrogate =
          static_cast<uint16_t>(0xD800 + ((code_point - 0x10000) >> 10));
      uint16_t low_surrogate =
          static_cast<uint16_t>(0xDC00 + (code_point & 0x3FF));
      if (!is_little_endian) {
        high_surrogate = swap_bytes(high_surrogate);
        low_surrogate = swap_bytes(low_surrogate);
      }
      output[output_size++] = static_cast<char16_t>(high_surrogate);
      output[output_size++] = static_cast<char16_t>(low_surrogate);
    }
    i += byte_count;
  }
  utf16.append(output.data(), output_size);
  return true;
}

std::u16string utf8_to_utf16(const std::string &utf8, bool is_little_endian) {
  std::u16string utf16;
  if (FORY_PREDICT_FALSE(!detail::utf8_to_utf16_checked(
          utf8.data(), utf8.size(), is_little_endian, utf16))) {
    throw std::invalid_argument("Invalid UTF-8 encoding.");
  }
  return utf16;
}

#if defined(FORY_HAS_IMMINTRIN)

FORY_TARGET_AVX2_ATTR std::string utf16_to_utf8(const std::u16string &utf16,
                                                bool is_little_endian) {
  std::string utf8;
  utf8.reserve(utf16.size() *
               3); // reserve enough space to avoid frequent reallocations

  const __m256i limit1 = _mm256_set1_epi16(0x80);
  const __m256i limit2 = _mm256_set1_epi16(0x800);
  const __m256i surrogate_high_start =
      _mm256_set1_epi16(static_cast<int16_t>(0xD800));
  const __m256i surrogate_high_end =
      _mm256_set1_epi16(static_cast<int16_t>(0xDBFF));
  const __m256i surrogate_low_start =
      _mm256_set1_epi16(static_cast<int16_t>(0xDC00));
  const __m256i surrogate_low_end =
      _mm256_set1_epi16(static_cast<int16_t>(0xDFFF));

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
        utf16_to_utf8(utf16[i + j], output);
      }
    } else {
      // Mix of 1, 2, and 3 byte characters
      for (int j = 0; j < 16; ++j) {
        if (_mm256_testz_si256(high_surrogate_mask, high_surrogate_mask) &&
            j + 1 < 16 &&
            !_mm256_testz_si256(low_surrogate_mask, low_surrogate_mask)) {
          // Surrogate pair
          utf16_surrogate_pair_to_utf8(utf16[i + j], utf16[i + j + 1], output);
          ++j;
        } else {
          utf16_to_utf8(utf16[i + j], output);
        }
      }
    }

    utf8.append(buffer, output - buffer);
    output = buffer; // reset output buffer pointer
    i += 16;
  }

  // Handle remaining characters
  while (i < n) {
    if (i + 1 < n && utf16[i] >= 0xD800 && utf16[i] <= 0xDBFF &&
        utf16[i + 1] >= 0xDC00 && utf16[i + 1] <= 0xDFFF) {
      // Surrogate pair
      utf16_surrogate_pair_to_utf8(utf16[i], utf16[i + 1], output);
      ++i;
    } else {
      utf16_to_utf8(utf16[i], output);
    }
    ++i;
  }
  utf8.append(buffer, output - buffer);

  return utf8;
}

#elif defined(FORY_HAS_NEON)

std::string utf16_to_utf8(const std::u16string &utf16, bool is_little_endian) {
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
        utf16_to_utf8(utf16[i + j], output);
      }
    } else {
      for (int j = 0; j < 8; ++j) {
        if (vmaxvq_u16(high_surrogate_mask) == 0 && j + 1 < 8 &&
            vmaxvq_u16(low_surrogate_mask) != 0) {
          utf16_surrogate_pair_to_utf8(utf16[i + j], utf16[i + j + 1], output);
          ++j;
        } else {
          utf16_to_utf8(utf16[i + j], output);
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
      utf16_surrogate_pair_to_utf8(utf16[i], utf16[i + 1], output);
      ++i;
    } else {
      utf16_to_utf8(utf16[i], output);
    }
    ++i;
  }
  utf8.append(buffer, output - buffer);

  return utf8;
}

#elif defined(FORY_HAS_RISCV_VECTOR)

std::string utf16_to_utf8(const std::u16string &utf16, bool is_little_endian) {
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
        utf16_to_utf8(vget_vx_u16m1(in, j), output);
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
          utf16_surrogate_pair_to_utf8(vget_vx_u16m1(in, j),
                                       vget_vx_u16m1(in, j + 1), output);
          ++j;
        } else {
          utf16_to_utf8(vget_vx_u16m1(in, j), output);
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
      utf16_surrogate_pair_to_utf8(utf16[i], utf16[i + 1], output);
      ++i;
    } else {
      utf16_to_utf8(utf16[i], output);
    }
    ++i;
  }
  utf8.append(buffer, output - buffer);

  return utf8;
}

#else

// Fallback implementation without SIMD acceleration
std::string utf16_to_utf8(const std::u16string &utf16, bool is_little_endian) {
  std::string utf8;
  utf8.reserve(utf16.size() *
               3); // reserve enough space to avoid frequent reallocations

  size_t i = 0;
  size_t n = utf16.size();
  char buffer[4]; // Buffer to hold temporary UTF-8 bytes
  char *output = buffer;

  while (i < n) {
    uint16_t code_unit = utf16[i];
    if (!is_little_endian) {
      code_unit = swap_bytes(code_unit);
    }
    if (i + 1 < n && code_unit >= 0xD800 && code_unit <= 0xDBFF &&
        utf16[i + 1] >= 0xDC00 && utf16[i + 1] <= 0xDFFF) {
      // Surrogate pair
      uint16_t high = code_unit;
      uint16_t low = utf16[i + 1];
      if (!is_little_endian) {
        low = swap_bytes(low);
      }
      utf16_surrogate_pair_to_utf8(high, low, output);
      utf8.append(buffer, output - buffer);
      output = buffer;
      ++i;
    } else {
      utf16_to_utf8(code_unit, output);
      utf8.append(buffer, output - buffer);
      output = buffer;
    }
    ++i;
  }
  return utf8;
}

#endif

} // namespace fory
