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

#include "fory/util/float16.h"
#include <cstring>

namespace fory {

// Convert float32 to float16 using IEEE 754 round-to-nearest, ties-to-even.
//
// Layout of float32: [S|EEEEEEEE|MMMMMMMMMMMMMMMMMMMMMMM] (1+8+23 bits)
// Layout of float16: [S|EEEEE|MMMMMMMMMM]                (1+5+10 bits)
// Exponent bias: 127 (f32) vs 15 (f16).
float16_t float16_t::from_float(float f) noexcept {
  uint32_t bits;
  std::memcpy(&bits, &f, sizeof(bits));

  const uint32_t sign = bits & 0x80000000u;
  const uint32_t exp = (bits >> 23) & 0xFFu;
  const uint32_t mantissa = bits & 0x007FFFFFu;

  // NaN or Infinity (f32 exp = 0xFF)
  if (exp == 255u) {
    if (mantissa == 0u) {
      // ±Inf → ±Inf
      return from_bits(static_cast<uint16_t>((sign >> 16) | 0x7C00u));
    }
    // NaN → quiet NaN; map top 9 bits of f32 payload to f16 payload,
    // force quiet bit (bit 9 of f16 fraction).
    const uint32_t nan_payload = (mantissa >> 13) & 0x03FFu;
    const uint32_t quiet_bit = 0x0200u;
    return from_bits(static_cast<uint16_t>(
        (sign >> 16) | 0x7C00u | quiet_bit | nan_payload));
  }

  // ±0 (also catches -0.0)
  if (exp == 0u && mantissa == 0u) {
    return from_bits(static_cast<uint16_t>(sign >> 16));
  }

  // Convert exponent bias: 127 → 15
  const int32_t exp16 = static_cast<int32_t>(exp) - 127 + 15;

  // Overflow → ±Inf
  if (exp16 >= 31) {
    return from_bits(static_cast<uint16_t>((sign >> 16) | 0x7C00u));
  }

  // Underflow: result is a f16 subnormal, or flushes to ±0.
  if (exp16 <= 0) {
    // Values with exp16 < -10 are too small even for the smallest f16
    // subnormal (2^-24); also handles all f32 subnormal inputs (exp==0).
    if (exp16 < -10) {
      return from_bits(static_cast<uint16_t>(sign >> 16));
    }
    // Assemble f16 subnormal.  The f32 normal implicit leading 1 participates
    // in the f16 mantissa, so include it explicitly.
    const uint32_t full_mantissa = (1u << 23) | mantissa;
    // Total right-shift to produce the 10-bit f16 subnormal mantissa:
    //   13 bits (to drop from 23 to 10) + (1 - exp16) for subnormal scaling.
    const int32_t shift_total = 13 + (1 - exp16);
    const uint32_t round_bit = 1u << (shift_total - 1);
    const uint32_t sticky_mask = round_bit - 1u;
    const bool sticky = (full_mantissa & sticky_mask) != 0u;
    const uint32_t mantissa16 = full_mantissa >> shift_total;
    // Round-to-nearest, ties-to-even
    const uint32_t result =
        ((full_mantissa & round_bit) != 0u &&
         (sticky || (mantissa16 & 1u) != 0u))
            ? mantissa16 + 1u
            : mantissa16;
    // Note: if rounding carries out of the subnormal mantissa the natural
    // carry produces the bit pattern for the f16 minimum normal (0x0400).
    return from_bits(static_cast<uint16_t>((sign >> 16) | result));
  }

  // Normal case: round 23-bit mantissa to 10 bits, discarding 13 bits.
  // Bit 12 is the round bit (half ULP); bits 11:0 form the sticky.
  const uint32_t round_bit = 1u << 12;
  const uint32_t sticky_mask = round_bit - 1u;
  const bool sticky = (mantissa & sticky_mask) != 0u;
  const uint32_t mantissa10 = mantissa >> 13;

  // Round-to-nearest, ties-to-even
  const uint32_t rounded =
      ((mantissa & round_bit) != 0u && (sticky || (mantissa10 & 1u) != 0u))
          ? mantissa10 + 1u
          : mantissa10;

  // Rounding may carry the mantissa past 10 bits: propagate into exponent.
  if (rounded > 0x03FFu) {
    const int32_t new_exp = exp16 + 1;
    if (new_exp >= 31) {
      return from_bits(static_cast<uint16_t>((sign >> 16) | 0x7C00u));
    }
    return from_bits(static_cast<uint16_t>(
        (sign >> 16) | (static_cast<uint32_t>(new_exp) << 10)));
  }

  return from_bits(static_cast<uint16_t>(
      (sign >> 16) | (static_cast<uint32_t>(exp16) << 10) | rounded));
}

// Convert float16 to float32 (exact: every f16 value is representable in f32).
float float16_t::to_float() const noexcept {
  const uint32_t sign = static_cast<uint32_t>(bits & 0x8000u) << 16;
  const uint32_t exp = (bits >> 10) & 0x1Fu;
  const uint32_t mantissa = bits & 0x03FFu;

  // NaN or Infinity (f16 exp = 0x1F)
  if (exp == 0x1Fu) {
    if (mantissa == 0u) {
      // ±Inf
      const uint32_t f32_bits = sign | 0x7F800000u;
      float result;
      std::memcpy(&result, &f32_bits, sizeof(result));
      return result;
    }
    // NaN: expand 10-bit f16 fraction to 23-bit f32 fraction by shifting
    // left 13 bits.  The f16 quiet bit (bit 9) maps to the f32 quiet bit
    // (bit 22), preserving quiet/signaling status and the payload.
    const uint32_t nan_payload = mantissa << 13;
    const uint32_t f32_bits = sign | 0x7F800000u | nan_payload;
    float result;
    std::memcpy(&result, &f32_bits, sizeof(result));
    return result;
  }

  // ±0
  if (exp == 0u && mantissa == 0u) {
    float result;
    std::memcpy(&result, &sign, sizeof(result));
    return result;
  }

  // Subnormal f16: normalize into a f32 normal.
  // f16 subnormals have true exponent -14 and no implicit leading 1.
  if (exp == 0u) {
    uint32_t m = mantissa;
    int32_t e = -14;
    // Shift left until the implicit leading 1 reaches bit 10.
    while ((m & 0x0400u) == 0u) {
      m <<= 1;
      e -= 1;
    }
    m &= 0x03FFu;  // strip implicit leading 1
    const uint32_t exp32 = static_cast<uint32_t>(e + 127);
    const uint32_t mantissa32 = m << 13;
    const uint32_t f32_bits = sign | (exp32 << 23) | mantissa32;
    float result;
    std::memcpy(&result, &f32_bits, sizeof(result));
    return result;
  }

  // Normal f16: remap exponent bias (15 → 127) and zero-extend mantissa
  // (10 → 23 bits).
  const uint32_t exp32 = exp - 15u + 127u;
  const uint32_t mantissa32 = mantissa << 13;
  const uint32_t f32_bits = sign | (exp32 << 23) | mantissa32;
  float result;
  std::memcpy(&result, &f32_bits, sizeof(result));
  return result;
}

}  // namespace fory
