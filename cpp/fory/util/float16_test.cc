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

#include <cmath>
#include <cstring>
#include <limits>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "fory/serialization/basic_serializer.h"
#include "fory/serialization/fory.h"
#include "fory/type/type.h"
#include "fory/util/buffer.h"
#include "fory/util/float16.h"
#include "gtest/gtest.h"

namespace fory {
namespace {

#define EXPECT_HALF_EQ(actual, expected)                                       \
  EXPECT_EQ((actual), (expected))                                              \
      << "actual=0x" << std::hex << (actual) << " expected=0x" << (expected)

float bits_to_float(uint32_t bits) {
  float value = 0;
  std::memcpy(&value, &bits, sizeof(value));
  return value;
}

uint32_t float_to_bits(float value) {
  uint32_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

double half_bits_to_double(const uint16_t bits) {
  const auto sign = static_cast<uint16_t>(bits >> 15);
  const auto exponent = static_cast<uint16_t>((bits >> 10) & 0x1F);
  const auto fraction = static_cast<uint16_t>(bits & 0x03FF);

  const double sign_scale = sign == 0 ? 1.0 : -1.0;
  if (exponent == 0x1F) {
    if (fraction == 0) {
      return sign == 0 ? std::numeric_limits<double>::infinity()
                       : -std::numeric_limits<double>::infinity();
    }
    return std::numeric_limits<double>::quiet_NaN();
  }
  if (exponent == 0) {
    if (fraction == 0) {
      return sign == 0 ? 0.0 : -0.0;
    }
    return sign_scale * std::ldexp(static_cast<double>(fraction), -24);
  }
  return sign_scale * std::ldexp(1.0 + static_cast<double>(fraction) / 1024.0,
                                 static_cast<int>(exponent) - 15);
}

uint16_t convert_bits(float value) {
  return float16_t::from_float(value).to_bits();
}

void ExpectSignSymmetry(float value) {
  if (std::isnan(value)) {
    return;
  }
  const uint16_t positive = convert_bits(std::fabs(value));
  const uint16_t negative = convert_bits(-std::fabs(value));
  EXPECT_HALF_EQ(static_cast<uint16_t>(positive ^ negative), 0x8000);
}

TEST(Float16FromFloatTest, HandlesSignedZerosAndInfinities) {
  const uint16_t positive_zero = convert_bits(0.0f);
  const uint16_t negative_zero = convert_bits(-0.0f);
  EXPECT_HALF_EQ(positive_zero, 0x0000);
  EXPECT_HALF_EQ(negative_zero, 0x8000);
  EXPECT_HALF_EQ(static_cast<uint16_t>(positive_zero & 0x7FFF), 0x0000);
  EXPECT_HALF_EQ(static_cast<uint16_t>(negative_zero & 0x7FFF), 0x0000);

  EXPECT_HALF_EQ(convert_bits(std::numeric_limits<float>::infinity()), 0x7C00);
  EXPECT_HALF_EQ(convert_bits(-std::numeric_limits<float>::infinity()), 0xFC00);
}

TEST(Float16FromFloatTest, PreservesNaNPayloadAndQuietsSignalingNaNs) {
  struct Case {
    uint32_t input_bits;
    uint16_t expected_half_bits;
  };
  const Case cases[] = {
      {0x7FC00000u, 0x7E00u}, // qNaN
      {0x7F800001u, 0x7E00u}, // smallest sNaN, quieted
      {0x7FC02000u, 0x7E01u}, // qNaN with small payload should be preserved
      {0x7FDFF000u, 0x7EFFu}, // qNaN with large payload should be truncated
      {0xFFC02000u,
       0xFE01u}, // negative qNaN with small payload should be preserved
      {0x7FA00000u, 0x7F00u}, // sNaN with payload, quieted
      {0xFFA00000u, 0xFF00u}, // negative sNaN with payload, quieted
  };
  for (const auto &[input_bits, expected_half_bits] : cases) {
    const uint16_t got = convert_bits(bits_to_float(input_bits));
    EXPECT_HALF_EQ(got, expected_half_bits);
    EXPECT_HALF_EQ(static_cast<uint16_t>(got & 0x7C00),
                   0x7C00);     // Exponent bits should be all 1s for NaNs
    EXPECT_NE(got & 0x03FF, 0); // Fraction bits should not be all 0s for NaNs
    EXPECT_NE(
        got & 0x0200,
        0); // The quiet bit (bit 9 of the fraction) should be set for NaNs
  }

  const uint16_t a = convert_bits(bits_to_float(0x7FC02000u));
  const uint16_t b = convert_bits(bits_to_float(0x7FC04000u));
  EXPECT_NE(a, b);
}

// min normal 2^-14, min subnormal 2^-24
TEST(Float16FromFloatTest, MinNormalMinSubnormalMaxSubnormal) {
  // Min normal: 2^-14
  EXPECT_HALF_EQ(convert_bits(std::ldexp(1.0f, -14)), 0x0400);
  EXPECT_HALF_EQ(convert_bits(-std::ldexp(1.0f, -14)), 0x8400);
  // Min positive subnormal: 2^-24
  EXPECT_HALF_EQ(convert_bits(std::ldexp(1.0f, -24)), 0x0001);
  EXPECT_HALF_EQ(convert_bits(-std::ldexp(1.0f, -24)), 0x8001);
  // Max subnormal: 1023 * 2^-24
  EXPECT_HALF_EQ(convert_bits(std::ldexp(1023.0f, -24)), 0x03FF);
  EXPECT_HALF_EQ(convert_bits(-std::ldexp(1023.0f, -24)), 0x83FF);
  // Sign symmetry
  ExpectSignSymmetry(std::ldexp(1.0f, -14));
  ExpectSignSymmetry(std::ldexp(1.0f, -24));
}

TEST(Float16FromFloatTest, PreservesEveryFiniteExactlyRepresentableHalfValue) {
  for (uint32_t bits = 0; bits <= 0xFFFF; ++bits) {
    const auto half = static_cast<uint16_t>(bits);
    const auto exponent = static_cast<uint16_t>((half >> 10) & 0x1F);
    if (const auto fraction = static_cast<uint16_t>(half & 0x03FF);
        exponent == 0x1F && fraction != 0) {
      continue;
    }
    const auto value = static_cast<float>(half_bits_to_double(half));
    EXPECT_HALF_EQ(convert_bits(value), half);
    if (value != 0.0f) {
      ExpectSignSymmetry(value);
    }
  }
}

TEST(Float16FromFloatTest, PreservesAllSubnormalHalfValues) {
  for (uint16_t fraction = 1; fraction <= 0x03FF; ++fraction) {
    const uint16_t positive = fraction;
    const auto negative = static_cast<uint16_t>(fraction | 0x8000);
    EXPECT_HALF_EQ(
        convert_bits(static_cast<float>(half_bits_to_double(positive))),
        positive);
    EXPECT_HALF_EQ(
        convert_bits(static_cast<float>(half_bits_to_double(negative))),
        negative);
  }
}

TEST(Float16FromFloatTest, SubnormalGradualUnderflowAndTieToZero) {
  const float tie_to_zero =
      std::ldexp(1.0f, -25); // halfway between 0 and the smallest subnormal
  EXPECT_HALF_EQ(convert_bits(tie_to_zero), 0x0000);
  EXPECT_HALF_EQ(convert_bits(-tie_to_zero), 0x8000);

  EXPECT_HALF_EQ(convert_bits(std::nextafter(tie_to_zero, 1.0f)), 0x0001);
  EXPECT_HALF_EQ(convert_bits(-std::nextafter(tie_to_zero, 1.0f)), 0x8001);

  for (uint16_t lower = 0x0001; lower < 0x03FF; ++lower) {
    const auto upper = static_cast<uint16_t>(lower + 1);
    const auto midpoint = static_cast<float>(
        (half_bits_to_double(lower) + half_bits_to_double(upper)) * 0.5);
    const float just_below = std::nextafter(midpoint, 0.0f);
    const float just_above =
        std::nextafter(midpoint, std::numeric_limits<float>::infinity());
    EXPECT_HALF_EQ(convert_bits(just_below), lower);
    EXPECT_HALF_EQ(convert_bits(just_above), upper);
    EXPECT_HALF_EQ(convert_bits(-just_below),
                   static_cast<uint16_t>(lower | 0x8000));
    EXPECT_HALF_EQ(convert_bits(-just_above),
                   static_cast<uint16_t>(upper | 0x8000));
    // Exact midpoint: ties to even (lowest bit of the even one is 0)
    const double exact_mid_d =
        (half_bits_to_double(lower) + half_bits_to_double(upper)) * 0.5;
    const auto exact_mid_f = static_cast<float>(exact_mid_d);
    if (static_cast<double>(exact_mid_f) == exact_mid_d) {
      const uint16_t even = (lower & 1u) == 0 ? lower : upper;
      EXPECT_HALF_EQ(convert_bits(exact_mid_f), even);
      EXPECT_HALF_EQ(convert_bits(-exact_mid_f),
                     static_cast<uint16_t>(even | 0x8000));
    }
  }
}

TEST(Float16FromFloatTest, RoundsToNearestTiesToEvenAcrossMidpoints) {

  const double zero_subnormal_midpoint =
      half_bits_to_double(0x0001) *
      0.5; // halfway between 0 and the smallest subnormal
  const auto zero_subnormal_midpoint_float =
      static_cast<float>(zero_subnormal_midpoint);
  ASSERT_EQ(static_cast<double>(zero_subnormal_midpoint_float),
            zero_subnormal_midpoint);
  EXPECT_HALF_EQ(convert_bits(zero_subnormal_midpoint_float), 0x0000);
  EXPECT_HALF_EQ(convert_bits(-zero_subnormal_midpoint_float), 0x8000);

  for (uint16_t lower = 0x0001; lower < 0x7BFF; ++lower) {
    const auto upper = static_cast<uint16_t>(lower + 1);
    if ((upper & 0x7C00) == 0x7C00) {
      continue; // skip inf/NaN boundaries
    }

    const double midpoint =
        (half_bits_to_double(lower) + half_bits_to_double(upper)) * 0.5;
    const auto midpoint_as_float = static_cast<float>(midpoint);
    if (static_cast<double>(midpoint_as_float) != midpoint) {
      continue; // skip midpoints that aren't exactly representable in float32,
                // since they won't round to either half value
    }

    const uint16_t expected =
        (lower & 1u) == 0 ? lower : upper; // which one is even?
    EXPECT_HALF_EQ(convert_bits(midpoint_as_float), expected);
    EXPECT_HALF_EQ(convert_bits(-midpoint_as_float),
                   static_cast<uint16_t>(expected | 0x8000));
  }
}

TEST(Float16FromFloatTest, OverflowBoundariesAndLargeFloat32Inputs) {
  EXPECT_HALF_EQ(convert_bits(65504.0f), 0x7BFF);
  EXPECT_HALF_EQ(convert_bits(-65504.0f), 0xFBFF);

  EXPECT_HALF_EQ(convert_bits(65519.0f), 0x7BFF);
  EXPECT_HALF_EQ(convert_bits(-65519.0f), 0xFBFF);

  EXPECT_HALF_EQ(convert_bits(65520.0f), 0x7C00);
  EXPECT_HALF_EQ(convert_bits(-65520.0f), 0xFC00);

  EXPECT_HALF_EQ(convert_bits(std::nextafter(65520.0f, 0.0f)), 0x7BFF);
  EXPECT_HALF_EQ(convert_bits(std::nextafter(
                     65520.0f, std::numeric_limits<float>::infinity())),
                 0x7C00);

  EXPECT_HALF_EQ(convert_bits(65510.0f), 0x7BFF);
  EXPECT_HALF_EQ(convert_bits(65512.0f), 0x7BFF);
  EXPECT_HALF_EQ(convert_bits(65518.0f), 0x7BFF);
  EXPECT_HALF_EQ(convert_bits(65530.0f), 0x7C00);

  EXPECT_HALF_EQ(convert_bits(std::numeric_limits<float>::max()), 0x7C00);
  EXPECT_HALF_EQ(convert_bits(-std::numeric_limits<float>::max()), 0xFC00);
}

TEST(Float16FromFloatTest, Float32SubnormalsAndMinNormalUnderflowToZero) {
  EXPECT_HALF_EQ(convert_bits(std::numeric_limits<float>::denorm_min()),
                 0x0000);
  EXPECT_HALF_EQ(convert_bits(-std::numeric_limits<float>::denorm_min()),
                 0x8000);
  EXPECT_HALF_EQ(convert_bits(bits_to_float(0x00000100u)), 0x0000);
  EXPECT_HALF_EQ(convert_bits(bits_to_float(0x00400000u)), 0x0000);
  EXPECT_HALF_EQ(convert_bits(bits_to_float(0x007FFFFFu)), 0x0000);
  EXPECT_HALF_EQ(convert_bits(bits_to_float(0x807FFFFFu)), 0x8000);
  EXPECT_HALF_EQ(convert_bits(std::numeric_limits<float>::min()), 0x0000);
  EXPECT_HALF_EQ(convert_bits(-std::numeric_limits<float>::min()), 0x8000);
}

TEST(Float16FromFloatTest, IntegerAndUlpRegressionCases) {
  for (int value = 1; value <= 2048; ++value) {
    const uint16_t half = convert_bits(static_cast<float>(value));
    EXPECT_EQ(half_bits_to_double(half), static_cast<double>(value));
    ExpectSignSymmetry(static_cast<float>(value));
  }
  EXPECT_HALF_EQ(convert_bits(2049.0f), 0x6800);

  constexpr float one = 1.0f;
  const float one_half_ulp = std::ldexp(1.0f, -11);
  const float one_full_ulp = std::ldexp(1.0f, -10);
  EXPECT_HALF_EQ(convert_bits(one + one_full_ulp), 0x3C01);
  // 1 + half-ULP (midpoint between 0x3C00 and 0x3C01): ties to even → 0x3C00
  EXPECT_HALF_EQ(convert_bits(one + one_half_ulp), 0x3C00);
  // 1 - 2^-11 = 2047/2048 is EXACTLY 0x3BFF in float16 — no rounding occurs
  EXPECT_HALF_EQ(convert_bits(one - one_half_ulp), 0x3BFF);
  // The true midpoint between 0x3BFF and 0x3C00 is 1 - 2^-12; ties to even →
  // 0x3C00
  EXPECT_HALF_EQ(convert_bits(one - std::ldexp(1.0f, -12)), 0x3C00);
  EXPECT_HALF_EQ(
      convert_bits(std::nextafter(one + one_half_ulp,
                                  std::numeric_limits<float>::infinity())),
      0x3C01);
}

TEST(Float16FromFloatTest, SignSymmetryForNonNaNBitPatterns) {
  for (uint32_t bits = 0; bits < 0xFFFFFFFFu; bits += 104729u) {
    const float value = bits_to_float(bits);
    if (std::isnan(value)) {
      continue;
    }
    ExpectSignSymmetry(value);
  }
}

TEST(Float16Test, FromBitsRoundTrip) {
  for (uint32_t bits = 0; bits <= 0xFFFF; ++bits) {
    const auto half_bits = static_cast<uint16_t>(bits);
    EXPECT_HALF_EQ(float16_t::from_bits(half_bits).to_bits(), half_bits);
  }
}

// ============================================================
// to_float() tests — testing both directions
// ============================================================

TEST(Float16ToFloatTest, SignedZerosAndInfinities) {
  // +0: value is 0.0 and sign bit is clear
  const float pos_zero = float16_t::from_bits(0x0000).to_float();
  EXPECT_EQ(pos_zero, 0.0f);
  EXPECT_FALSE(std::signbit(pos_zero));

  // -0: value is 0.0 but sign bit is set
  const float neg_zero = float16_t::from_bits(0x8000).to_float();
  EXPECT_EQ(neg_zero, -0.0f);
  EXPECT_TRUE(std::signbit(neg_zero));

  // +Inf
  const float pos_inf = float16_t::from_bits(0x7C00).to_float();
  EXPECT_TRUE(std::isinf(pos_inf));
  EXPECT_GT(pos_inf, 0.0f);

  // -Inf
  const float neg_inf = float16_t::from_bits(0xFC00).to_float();
  EXPECT_TRUE(std::isinf(neg_inf));
  EXPECT_LT(neg_inf, 0.0f);
}

TEST(Float16ToFloatTest, NaNPayloadAndSignPreservation) {
  // Canonical positive qNaN (0x7E00): maps to f32 canonical qNaN 0x7FC00000
  // f16 mantissa=0x200; 0x200<<13 = 0x400000 (f32 quiet bit), so f32=0x7FC00000
  const float qnan_pos = float16_t::from_bits(0x7E00).to_float();
  EXPECT_TRUE(std::isnan(qnan_pos));
  EXPECT_FALSE(std::signbit(qnan_pos));
  EXPECT_EQ(float_to_bits(qnan_pos), 0x7FC00000u);

  // Canonical negative qNaN (0xFE00): f32 = 0xFFC00000
  const float qnan_neg = float16_t::from_bits(0xFE00).to_float();
  EXPECT_TRUE(std::isnan(qnan_neg));
  EXPECT_TRUE(std::signbit(qnan_neg));
  EXPECT_EQ(float_to_bits(qnan_neg), 0xFFC00000u);

  // qNaN with payload bit 0 set (0x7E01): f16 mantissa=0x201;
  // 0x201<<13=0x402000 f32 = 0x7F800000 | 0x402000 = 0x7FC02000
  const float qnan_payload = float16_t::from_bits(0x7E01).to_float();
  EXPECT_TRUE(std::isnan(qnan_payload));
  EXPECT_FALSE(std::signbit(qnan_payload));
  EXPECT_EQ(float_to_bits(qnan_payload), 0x7FC02000u);

  // qNaN with full payload (0x7EFF): f16 mantissa=0x2FF; 0x2FF<<13=0x5FE000
  // f32 = 0x7F800000 | 0x5FE000 = 0x7FDFE000
  const float qnan_full = float16_t::from_bits(0x7EFF).to_float();
  EXPECT_TRUE(std::isnan(qnan_full));
  EXPECT_EQ(float_to_bits(qnan_full), 0x7FDFE000u);

  // Negative qNaN with payload (0xFE01): f32 = 0xFFC02000
  const float qnan_neg_payload = float16_t::from_bits(0xFE01).to_float();
  EXPECT_TRUE(std::isnan(qnan_neg_payload));
  EXPECT_TRUE(std::signbit(qnan_neg_payload));
  EXPECT_EQ(float_to_bits(qnan_neg_payload), 0xFFC02000u);

  // to_float() faithfully preserves all 1024 NaN bit patterns.
  // The f16 quiet bit (bit 9) maps to the f32 quiet bit (bit 22) via <<13.
  for (uint16_t frac = 1; frac <= 0x03FF; ++frac) {
    const auto nan_bits = static_cast<uint16_t>(0x7C00 | frac);
    const float f = float16_t::from_bits(nan_bits).to_float();
    EXPECT_TRUE(std::isnan(f)) << "bits=0x" << std::hex << nan_bits;
    // f16 quiet bit (bit 9) preserved as f32 quiet bit (bit 22)
    const bool f16_quiet = (frac & 0x0200u) != 0;
    const bool f32_quiet = (float_to_bits(f) & 0x00400000u) != 0u;
    EXPECT_EQ(f16_quiet, f32_quiet)
        << "quiet bit not preserved for bits=0x" << std::hex << nan_bits;
    // Negative counterpart: sign preserved
    const auto neg_nan_bits = static_cast<uint16_t>(nan_bits | 0x8000);
    const float fn = float16_t::from_bits(neg_nan_bits).to_float();
    EXPECT_TRUE(std::isnan(fn));
    EXPECT_TRUE(std::signbit(fn))
        << "sign not preserved for bits=0x" << std::hex << neg_nan_bits;
  }
}

// min normal 2^-14 and min subnormal 2^-24
TEST(Float16ToFloatTest, BoundaryValues) {
  // Max finite: 65504
  EXPECT_EQ(float16_t::from_bits(0x7BFF).to_float(), 65504.0f);
  EXPECT_EQ(float16_t::from_bits(0xFBFF).to_float(), -65504.0f);

  // Min normal: 2^-14
  EXPECT_EQ(float16_t::from_bits(0x0400).to_float(), std::ldexp(1.0f, -14));
  EXPECT_EQ(float16_t::from_bits(0x8400).to_float(), -std::ldexp(1.0f, -14));

  // Min positive subnormal: 2^-24
  EXPECT_EQ(float16_t::from_bits(0x0001).to_float(), std::ldexp(1.0f, -24));
  EXPECT_EQ(float16_t::from_bits(0x8001).to_float(), -std::ldexp(1.0f, -24));

  // Max subnormal: 1023 * 2^-24
  EXPECT_EQ(float16_t::from_bits(0x03FF).to_float(), std::ldexp(1023.0f, -24));
  EXPECT_EQ(float16_t::from_bits(0x83FF).to_float(), -std::ldexp(1023.0f, -24));
}

TEST(Float16ToFloatTest, NormalValueSpotChecks) {
  EXPECT_EQ(float16_t::from_bits(0x3C00).to_float(), 1.0f);
  EXPECT_EQ(float16_t::from_bits(0xBC00).to_float(), -1.0f);
  EXPECT_EQ(float16_t::from_bits(0x4000).to_float(), 2.0f);
  EXPECT_EQ(float16_t::from_bits(0xC000).to_float(), -2.0f);
  EXPECT_EQ(float16_t::from_bits(0x3800).to_float(), 0.5f);
  EXPECT_EQ(float16_t::from_bits(0xB800).to_float(), -0.5f);
  EXPECT_EQ(float16_t::from_bits(0x3E00).to_float(), 1.5f);
  EXPECT_EQ(float16_t::from_bits(0x4200).to_float(), 3.0f);
  // Exponent range: 2^15 at exp=30
  EXPECT_EQ(float16_t::from_bits(0x7800).to_float(), std::ldexp(1.0f, 15));
  // 2^-14 at exp=1
  EXPECT_EQ(float16_t::from_bits(0x0400).to_float(), std::ldexp(1.0f, -14));
}

TEST(Float16ToFloatTest, AllSubnormalsMatchReference) {
  for (uint16_t frac = 1; frac <= 0x03FF; ++frac) {
    const auto expected_pos = static_cast<float>(half_bits_to_double(frac));
    EXPECT_EQ(float16_t::from_bits(frac).to_float(), expected_pos)
        << "to_float mismatch for subnormal 0x" << std::hex << frac;
    const auto neg_bits = static_cast<uint16_t>(frac | 0x8000);
    const auto expected_neg = static_cast<float>(half_bits_to_double(neg_bits));
    EXPECT_EQ(float16_t::from_bits(neg_bits).to_float(), expected_neg)
        << "to_float mismatch for negative subnormal 0x" << std::hex
        << neg_bits;
  }
}

TEST(Float16ToFloatTest, AllNormalsMatchReference) {
  for (uint16_t exp = 1; exp <= 30; ++exp) {
    for (uint16_t frac = 0; frac <= 0x03FF; ++frac) {
      const auto bits = static_cast<uint16_t>((exp << 10) | frac);
      const auto expected_pos = static_cast<float>(half_bits_to_double(bits));
      EXPECT_EQ(float16_t::from_bits(bits).to_float(), expected_pos)
          << "to_float mismatch for normal 0x" << std::hex << bits;
      const auto neg_bits = static_cast<uint16_t>(bits | 0x8000);
      const auto expected_neg =
          static_cast<float>(half_bits_to_double(neg_bits));
      EXPECT_EQ(float16_t::from_bits(neg_bits).to_float(), expected_neg)
          << "to_float mismatch for negative normal 0x" << std::hex << neg_bits;
    }
  }
}

// verify bit preservation for all non-NaN; for NaN validate chosen policy.

TEST(Float16Test, StressAllBitPatternsViaToFloat) {
  for (uint32_t bits = 0; bits <= 0xFFFF; ++bits) {
    const auto half_bits = static_cast<uint16_t>(bits);
    const float16_t h = float16_t::from_bits(half_bits);
    const float h_float = h.to_float();
    const float16_t h2 = float16_t::from_float(h_float);

    const auto exp = static_cast<uint16_t>((half_bits >> 10) & 0x1F);
    const auto frac = static_cast<uint16_t>(half_bits & 0x03FF);

    if (exp == 0x1F && frac != 0) {
      // NaN: validate chosen policy (always quieted, sign preserved)
      EXPECT_TRUE(std::isnan(h_float)) << "to_float of NaN bits=0x" << std::hex
                                       << half_bits << " must be NaN";
      EXPECT_EQ(h2.to_bits() & 0x7C00u, 0x7C00u)
          << "NaN round-trip exp must be all-ones for bits=0x" << std::hex
          << half_bits;
      EXPECT_NE(h2.to_bits() & 0x03FFu, 0u)
          << "NaN round-trip frac must be non-zero for bits=0x" << std::hex
          << half_bits;
      EXPECT_NE(h2.to_bits() & 0x0200u, 0u)
          << "NaN round-trip quiet bit must be set for bits=0x" << std::hex
          << half_bits;
      EXPECT_EQ(h2.to_bits() & 0x8000u,
                static_cast<uint16_t>(half_bits & 0x8000u))
          << "NaN sign must be preserved for bits=0x" << std::hex << half_bits;
    } else {
      // Non-NaN: must round-trip exactly
      EXPECT_EQ(h2.to_bits(), half_bits)
          << "Round-trip failed for bits=0x" << std::hex << half_bits;
    }
  }
}

// ============================================================
// 3.3 Classification tests
// ============================================================

// Representative bit patterns used across classification tests.
// Named constants avoid magic numbers and make intent clear.
constexpr uint16_t kPosZero = 0x0000;    // +0
constexpr uint16_t kNegZero = 0x8000;    // -0
constexpr uint16_t kPosInf = 0x7C00;     // +Inf
constexpr uint16_t kNegInf = 0xFC00;     // -Inf
constexpr uint16_t kQNaN = 0x7E00;       // canonical quiet NaN
constexpr uint16_t kNegQNaN = 0xFE00;    // negative quiet NaN
constexpr uint16_t kSNaN = 0x7C01;       // smallest signaling NaN
constexpr uint16_t kNegSNaN = 0xFC01;    // negative signaling NaN
constexpr uint16_t kMinSubnorm = 0x0001; // smallest positive subnormal (2^-24)
constexpr uint16_t kMaxSubnorm = 0x03FF; // largest positive subnormal
constexpr uint16_t kMinNormal = 0x0400;  // smallest positive normal (2^-14)
constexpr uint16_t kOne = 0x3C00;        // 1.0
constexpr uint16_t kNegOne = 0xBC00;     // -1.0
constexpr uint16_t kMaxFinite = 0x7BFF;  // 65504

static float16_t H(uint16_t b) { return float16_t::from_bits(b); }

TEST(Float16ClassificationTest, IsNaN) {
  EXPECT_TRUE(float16_t::is_nan(H(kQNaN)));
  EXPECT_TRUE(float16_t::is_nan(H(kNegQNaN)));
  EXPECT_TRUE(float16_t::is_nan(H(kSNaN)));
  EXPECT_TRUE(float16_t::is_nan(H(kNegSNaN)));
  EXPECT_TRUE(float16_t::is_nan(H(0x7FFFu))); // NaN with all fraction bits set
  EXPECT_TRUE(
      float16_t::is_nan(H(0xFFFFu))); // negative NaN, all fraction bits set
  // All 1024 non-zero fraction values with exp=0x1F are NaN
  for (uint16_t frac = 1; frac <= 0x03FF; ++frac) {
    EXPECT_TRUE(float16_t::is_nan(H(static_cast<uint16_t>(0x7C00u | frac))));
    EXPECT_TRUE(float16_t::is_nan(H(static_cast<uint16_t>(0xFC00u | frac))));
  }
  EXPECT_FALSE(float16_t::is_nan(H(kPosInf)));
  EXPECT_FALSE(float16_t::is_nan(H(kNegInf)));
  EXPECT_FALSE(float16_t::is_nan(H(kPosZero)));
  EXPECT_FALSE(float16_t::is_nan(H(kNegZero)));
  EXPECT_FALSE(float16_t::is_nan(H(kOne)));
  EXPECT_FALSE(float16_t::is_nan(H(kNegOne)));
  EXPECT_FALSE(float16_t::is_nan(H(kMinSubnorm)));
  EXPECT_FALSE(float16_t::is_nan(H(kMaxFinite)));
}

TEST(Float16ClassificationTest, IsInf) {
  EXPECT_TRUE(float16_t::is_inf(H(kPosInf)));
  EXPECT_TRUE(float16_t::is_inf(H(kNegInf)));
  EXPECT_FALSE(float16_t::is_inf(H(kQNaN)));
  EXPECT_FALSE(float16_t::is_inf(H(kSNaN)));
  EXPECT_FALSE(float16_t::is_inf(H(kPosZero)));
  EXPECT_FALSE(float16_t::is_inf(H(kNegZero)));
  EXPECT_FALSE(float16_t::is_inf(H(kOne)));
  EXPECT_FALSE(float16_t::is_inf(H(kMaxFinite)));
  EXPECT_FALSE(float16_t::is_inf(H(kMinSubnorm)));
}

TEST(Float16ClassificationTest, IsInfWithSign) {
  // sign == 0: either infinity
  EXPECT_TRUE(float16_t::is_inf(H(kPosInf), 0));
  EXPECT_TRUE(float16_t::is_inf(H(kNegInf), 0));
  EXPECT_FALSE(float16_t::is_inf(H(kQNaN), 0));
  EXPECT_FALSE(float16_t::is_inf(H(kOne), 0));

  // sign > 0: +Inf only
  EXPECT_TRUE(float16_t::is_inf(H(kPosInf), +1));
  EXPECT_FALSE(float16_t::is_inf(H(kNegInf), +1));
  EXPECT_FALSE(float16_t::is_inf(H(kOne), +1));

  // sign < 0: -Inf only
  EXPECT_TRUE(float16_t::is_inf(H(kNegInf), -1));
  EXPECT_FALSE(float16_t::is_inf(H(kPosInf), -1));
  EXPECT_FALSE(float16_t::is_inf(H(kNegOne), -1));
}

TEST(Float16ClassificationTest, IsZero) {
  EXPECT_TRUE(float16_t::is_zero(H(kPosZero)));
  EXPECT_TRUE(float16_t::is_zero(H(kNegZero)));
  EXPECT_FALSE(float16_t::is_zero(H(kMinSubnorm)));
  EXPECT_FALSE(float16_t::is_zero(H(kOne)));
  EXPECT_FALSE(float16_t::is_zero(H(kPosInf)));
  EXPECT_FALSE(float16_t::is_zero(H(kQNaN)));
}

TEST(Float16ClassificationTest, Signbit) {
  EXPECT_FALSE(float16_t::signbit(H(kPosZero)));
  EXPECT_TRUE(float16_t::signbit(H(kNegZero)));
  EXPECT_FALSE(float16_t::signbit(H(kOne)));
  EXPECT_TRUE(float16_t::signbit(H(kNegOne)));
  EXPECT_FALSE(float16_t::signbit(H(kPosInf)));
  EXPECT_TRUE(float16_t::signbit(H(kNegInf)));
  EXPECT_FALSE(float16_t::signbit(H(kQNaN)));
  EXPECT_TRUE(float16_t::signbit(H(kNegQNaN)));
  EXPECT_FALSE(float16_t::signbit(H(kMinSubnorm)));
  EXPECT_TRUE(
      float16_t::signbit(H(static_cast<uint16_t>(kMinSubnorm | 0x8000u))));
}

TEST(Float16ClassificationTest, IsSubnormal) {
  EXPECT_TRUE(float16_t::is_subnormal(H(kMinSubnorm)));
  EXPECT_TRUE(float16_t::is_subnormal(H(kMaxSubnorm)));
  // All 1023 positive subnormals
  for (uint16_t frac = 1; frac <= 0x03FFu; ++frac) {
    EXPECT_TRUE(float16_t::is_subnormal(H(frac)));
    EXPECT_TRUE(
        float16_t::is_subnormal(H(static_cast<uint16_t>(frac | 0x8000u))));
  }
  EXPECT_FALSE(float16_t::is_subnormal(H(kPosZero)));
  EXPECT_FALSE(float16_t::is_subnormal(H(kNegZero)));
  EXPECT_FALSE(float16_t::is_subnormal(H(kMinNormal)));
  EXPECT_FALSE(float16_t::is_subnormal(H(kOne)));
  EXPECT_FALSE(float16_t::is_subnormal(H(kPosInf)));
  EXPECT_FALSE(float16_t::is_subnormal(H(kQNaN)));
}

TEST(Float16ClassificationTest, IsNormal) {
  EXPECT_TRUE(float16_t::is_normal(H(kMinNormal)));
  EXPECT_TRUE(float16_t::is_normal(H(kOne)));
  EXPECT_TRUE(float16_t::is_normal(H(kNegOne)));
  EXPECT_TRUE(float16_t::is_normal(H(kMaxFinite)));
  // All 30 exponent values (1..30), all 1024 mantissa values
  for (uint16_t exp = 1; exp <= 30; ++exp) {
    for (uint16_t frac = 0; frac <= 0x03FFu; ++frac) {
      EXPECT_TRUE(
          float16_t::is_normal(H(static_cast<uint16_t>((exp << 10) | frac))));
    }
  }
  EXPECT_FALSE(float16_t::is_normal(H(kPosZero)));
  EXPECT_FALSE(float16_t::is_normal(H(kNegZero)));
  EXPECT_FALSE(float16_t::is_normal(H(kMinSubnorm)));
  EXPECT_FALSE(float16_t::is_normal(H(kPosInf)));
  EXPECT_FALSE(float16_t::is_normal(H(kNegInf)));
  EXPECT_FALSE(float16_t::is_normal(H(kQNaN)));
  EXPECT_FALSE(float16_t::is_normal(H(kSNaN)));
}

TEST(Float16ClassificationTest, IsFinite) {
  EXPECT_TRUE(float16_t::is_finite(H(kPosZero)));
  EXPECT_TRUE(float16_t::is_finite(H(kNegZero)));
  EXPECT_TRUE(float16_t::is_finite(H(kMinSubnorm)));
  EXPECT_TRUE(float16_t::is_finite(H(kOne)));
  EXPECT_TRUE(float16_t::is_finite(H(kNegOne)));
  EXPECT_TRUE(float16_t::is_finite(H(kMaxFinite)));
  EXPECT_TRUE(float16_t::is_finite(H(kMinNormal)));
  EXPECT_FALSE(float16_t::is_finite(H(kPosInf)));
  EXPECT_FALSE(float16_t::is_finite(H(kNegInf)));
  EXPECT_FALSE(float16_t::is_finite(H(kQNaN)));
  EXPECT_FALSE(float16_t::is_finite(H(kSNaN)));
  EXPECT_FALSE(float16_t::is_finite(H(kNegQNaN)));
}

TEST(Float16ClassificationTest, MutualExclusionAcrossAllBitPatterns) {
  // Every bit pattern falls into exactly one of: zero, subnormal, normal,
  // inf, nan.  Also validate that is_finite == !inf && !nan.
  for (uint32_t b = 0; b <= 0xFFFFu; ++b) {
    const float16_t h = float16_t::from_bits(static_cast<uint16_t>(b));
    const bool z = float16_t::is_zero(h);
    const bool sub = float16_t::is_subnormal(h);
    const bool nor = float16_t::is_normal(h);
    const bool inf = float16_t::is_inf(h);
    const bool nan = float16_t::is_nan(h);
    // Exactly one category is true
    const int count = static_cast<int>(z) + static_cast<int>(sub) +
                      static_cast<int>(nor) + static_cast<int>(inf) +
                      static_cast<int>(nan);
    EXPECT_EQ(count, 1) << "bits=0x" << std::hex << b;
    // is_finite agrees with !inf && !nan
    EXPECT_EQ(float16_t::is_finite(h), !inf && !nan)
        << "bits=0x" << std::hex << b;
  }
}

// ============================================================
// to_string tests
// ============================================================

TEST(Float16ToStringTest, MatchesToFloat) {
  // to_string must equal std::to_string of the float32 value
  const uint16_t cases[] = {kPosZero,   kNegZero, kOne,   kNegOne,
                            kPosInf,    kNegInf,  kQNaN,  kMinSubnorm,
                            kMaxFinite, 0x4000u,  0x3800u};
  for (uint16_t b : cases) {
    const float16_t h = H(b);
    EXPECT_EQ(float16_t::to_string(h), std::to_string(h.to_float()))
        << "bits=0x" << std::hex << b;
  }
}

TEST(Float16ToStringTest, SpecialValues) {
  // Normal finite values produce digit strings
  const std::string s_one = float16_t::to_string(H(kOne));
  EXPECT_FALSE(s_one.empty());
  EXPECT_NE(s_one.find('1'), std::string::npos);

  // ±Inf: std::to_string of ±infinity is implementation-defined but non-empty
  EXPECT_FALSE(float16_t::to_string(H(kPosInf)).empty());
  EXPECT_FALSE(float16_t::to_string(H(kNegInf)).empty());

  // NaN: std::to_string of NaN is non-empty
  EXPECT_FALSE(float16_t::to_string(H(kQNaN)).empty());
}

// ============================================================
// 3.4 Arithmetic tests
// ============================================================

// Helper: convert two float16 bit patterns through an operation and return
// bits.
static uint16_t add_bits(uint16_t a, uint16_t b) {
  return float16_t::add(H(a), H(b)).to_bits();
}
static uint16_t sub_bits(uint16_t a, uint16_t b) {
  return float16_t::sub(H(a), H(b)).to_bits();
}
static uint16_t mul_bits(uint16_t a, uint16_t b) {
  return float16_t::mul(H(a), H(b)).to_bits();
}
static uint16_t div_bits(uint16_t a, uint16_t b) {
  return float16_t::div(H(a), H(b)).to_bits();
}

TEST(Float16ArithmeticTest, AddBasic) {
  // 1.0 + 1.0 = 2.0
  EXPECT_HALF_EQ(add_bits(kOne, kOne), 0x4000u);
  // 1.0 + (-1.0) = +0
  EXPECT_HALF_EQ(add_bits(kOne, kNegOne), 0x0000u);
  // 1.0 + 0 = 1.0
  EXPECT_HALF_EQ(add_bits(kOne, kPosZero), kOne);
  // -0 + -0 = -0
  EXPECT_HALF_EQ(add_bits(kNegZero, kNegZero), kNegZero);
  // +Inf + 1.0 = +Inf
  EXPECT_HALF_EQ(add_bits(kPosInf, kOne), kPosInf);
  // +Inf + (-Inf) = NaN
  {
    const float16_t r = float16_t::add(H(kPosInf), H(kNegInf));
    EXPECT_TRUE(float16_t::is_nan(r));
  }
  // NaN propagates
  EXPECT_TRUE(float16_t::is_nan(float16_t::add(H(kQNaN), H(kOne))));
}

TEST(Float16ArithmeticTest, SubBasic) {
  // 2.0 - 1.0 = 1.0
  EXPECT_HALF_EQ(sub_bits(0x4000u, kOne), kOne);
  // 1.0 - 1.0 = +0
  EXPECT_HALF_EQ(sub_bits(kOne, kOne), kPosZero);
  // 0 - 1.0 = -1.0
  EXPECT_HALF_EQ(sub_bits(kPosZero, kOne), kNegOne);
  // -Inf - (-Inf) = NaN
  EXPECT_TRUE(float16_t::is_nan(float16_t::sub(H(kNegInf), H(kNegInf))));
  // NaN propagates
  EXPECT_TRUE(float16_t::is_nan(float16_t::sub(H(kQNaN), H(kOne))));
}

TEST(Float16ArithmeticTest, MulBasic) {
  // 2.0 * 3.0 = 6.0  (0x4600)
  EXPECT_HALF_EQ(mul_bits(0x4000u, 0x4200u), 0x4600u);
  // 1.0 * -1.0 = -1.0
  EXPECT_HALF_EQ(mul_bits(kOne, kNegOne), kNegOne);
  // -1.0 * -1.0 = 1.0
  EXPECT_HALF_EQ(mul_bits(kNegOne, kNegOne), kOne);
  // 0 * +Inf = NaN
  EXPECT_TRUE(float16_t::is_nan(float16_t::mul(H(kPosZero), H(kPosInf))));
  // +Inf * 2.0 = +Inf
  EXPECT_HALF_EQ(mul_bits(kPosInf, 0x4000u), kPosInf);
  // NaN propagates
  EXPECT_TRUE(float16_t::is_nan(float16_t::mul(H(kQNaN), H(kOne))));
}

TEST(Float16ArithmeticTest, DivBasic) {
  // 1.0 / 2.0 = 0.5  (0x3800)
  EXPECT_HALF_EQ(div_bits(kOne, 0x4000u), 0x3800u);
  // 6.0 / 3.0 = 2.0
  EXPECT_HALF_EQ(div_bits(0x4600u, 0x4200u), 0x4000u);
  // 1.0 / 0 = +Inf
  EXPECT_HALF_EQ(div_bits(kOne, kPosZero), kPosInf);
  // -1.0 / 0 = -Inf
  EXPECT_HALF_EQ(div_bits(kNegOne, kPosZero), kNegInf);
  // 0 / 0 = NaN
  EXPECT_TRUE(float16_t::is_nan(float16_t::div(H(kPosZero), H(kPosZero))));
  // NaN propagates
  EXPECT_TRUE(float16_t::is_nan(float16_t::div(H(kQNaN), H(kOne))));
}

TEST(Float16ArithmeticTest, NegAndAbs) {
  // neg: flip sign bit
  EXPECT_HALF_EQ(float16_t::neg(H(kOne)).to_bits(), kNegOne);
  EXPECT_HALF_EQ(float16_t::neg(H(kNegOne)).to_bits(), kOne);
  EXPECT_HALF_EQ(float16_t::neg(H(kPosZero)).to_bits(), kNegZero);
  EXPECT_HALF_EQ(float16_t::neg(H(kNegZero)).to_bits(), kPosZero);
  EXPECT_HALF_EQ(float16_t::neg(H(kPosInf)).to_bits(), kNegInf);
  EXPECT_HALF_EQ(float16_t::neg(H(kNegInf)).to_bits(), kPosInf);
  // neg(NaN): flip sign, keep NaN
  EXPECT_TRUE(float16_t::is_nan(float16_t::neg(H(kQNaN))));
  EXPECT_TRUE(float16_t::signbit(float16_t::neg(H(kQNaN))));

  // abs: clear sign bit
  EXPECT_HALF_EQ(float16_t::abs(H(kNegOne)).to_bits(), kOne);
  EXPECT_HALF_EQ(float16_t::abs(H(kOne)).to_bits(), kOne);
  EXPECT_HALF_EQ(float16_t::abs(H(kNegZero)).to_bits(), kPosZero);
  EXPECT_HALF_EQ(float16_t::abs(H(kNegInf)).to_bits(), kPosInf);
  // abs of negative NaN → positive NaN
  EXPECT_TRUE(float16_t::is_nan(float16_t::abs(H(kNegQNaN))));
  EXPECT_FALSE(float16_t::signbit(float16_t::abs(H(kNegQNaN))));
}

TEST(Float16ArithmeticTest, OperatorOverloads) {
  const float16_t one = H(kOne);
  const float16_t two = H(0x4000u);

  // Binary operators
  EXPECT_HALF_EQ((one + one).to_bits(), 0x4000u);
  EXPECT_HALF_EQ((two - one).to_bits(), kOne);
  EXPECT_HALF_EQ((two * two).to_bits(), 0x4400u); // 4.0
  EXPECT_HALF_EQ((two / two).to_bits(), kOne);

  // Unary minus
  EXPECT_HALF_EQ((-one).to_bits(), kNegOne);
  // Unary plus (identity)
  EXPECT_HALF_EQ((+one).to_bits(), kOne);
}

TEST(Float16ArithmeticTest, CompoundAssignmentOperators) {
  float16_t v = H(kOne);

  v += H(kOne);
  EXPECT_HALF_EQ(v.to_bits(), 0x4000u); // 2.0

  v -= H(kOne);
  EXPECT_HALF_EQ(v.to_bits(), kOne); // 1.0

  v *= H(0x4000u);                      // *= 2.0
  EXPECT_HALF_EQ(v.to_bits(), 0x4000u); // 2.0

  v /= H(0x4000u);                   // /= 2.0
  EXPECT_HALF_EQ(v.to_bits(), kOne); // 1.0
}

TEST(Float16ArithmeticTest, RoundingThroughFloat32) {
  // 1.0 + epsilon should produce 1.0 if epsilon is below the rounding
  // threshold, or 1.0 + ULP if large enough.  Verify the float32→float16
  // round-trip path.
  const float16_t one = H(kOne);
  // Adding min subnormal (~6e-8) to 1.0: too small to shift 1.0 in f16,
  // so result should still be 1.0 (ties-to-even with the even side).
  const float16_t result = one + H(kMinSubnorm);
  EXPECT_HALF_EQ(result.to_bits(), kOne);
  // Adding one full f16 ULP (2^-10) to 1.0 must advance to 0x3C01.
  // Actual 1 ULP for 1.0 is 2^-10. Build it from_float:
  const float16_t result2 =
      float16_t::add(H(kOne), float16_t::from_float(std::ldexp(1.0f, -10)));
  EXPECT_HALF_EQ(result2.to_bits(), 0x3C01u);
}

// ============================================================
// 3.5 Optional math tests
// ============================================================

TEST(Float16MathTest, Sqrt) {
  // sqrt(4.0) = 2.0
  EXPECT_HALF_EQ(float16_t::sqrt(H(0x4400u)).to_bits(), 0x4000u);
  // sqrt(1.0) = 1.0
  EXPECT_HALF_EQ(float16_t::sqrt(H(kOne)).to_bits(), kOne);
  // sqrt(0) = 0
  EXPECT_HALF_EQ(float16_t::sqrt(H(kPosZero)).to_bits(), kPosZero);
  // sqrt(+Inf) = +Inf
  EXPECT_HALF_EQ(float16_t::sqrt(H(kPosInf)).to_bits(), kPosInf);
  // sqrt(negative) = NaN
  EXPECT_TRUE(float16_t::is_nan(float16_t::sqrt(H(kNegOne))));
}

TEST(Float16MathTest, MinMax) {
  // min(1.0, 2.0) = 1.0
  EXPECT_HALF_EQ(float16_t::min(H(kOne), H(0x4000u)).to_bits(), kOne);
  // max(1.0, 2.0) = 2.0
  EXPECT_HALF_EQ(float16_t::max(H(kOne), H(0x4000u)).to_bits(), 0x4000u);
  // min(-1.0, 1.0) = -1.0
  EXPECT_HALF_EQ(float16_t::min(H(kNegOne), H(kOne)).to_bits(), kNegOne);
  // max(-1.0, 1.0) = 1.0
  EXPECT_HALF_EQ(float16_t::max(H(kNegOne), H(kOne)).to_bits(), kOne);
  // fmin(x, NaN) = x (NaN is suppressed by fmin)
  EXPECT_HALF_EQ(float16_t::min(H(kOne), H(kQNaN)).to_bits(), kOne);
  EXPECT_HALF_EQ(float16_t::max(H(kOne), H(kQNaN)).to_bits(), kOne);
  // min/max with -0 and +0: sign handling
  // fmin(-0, +0) is implementation-defined between -0 and +0, but result is
  // zero
  EXPECT_TRUE(float16_t::is_zero(float16_t::min(H(kNegZero), H(kPosZero))));
  EXPECT_TRUE(float16_t::is_zero(float16_t::max(H(kNegZero), H(kPosZero))));
}

TEST(Float16MathTest, Copysign) {
  // copysign(1.0, -2.0) = -1.0
  EXPECT_HALF_EQ(float16_t::copysign(H(kOne), H(kNegOne)).to_bits(), kNegOne);
  // copysign(-1.0, 1.0) = 1.0
  EXPECT_HALF_EQ(float16_t::copysign(H(kNegOne), H(kOne)).to_bits(), kOne);
  // copysign(+Inf, -1.0) = -Inf
  EXPECT_HALF_EQ(float16_t::copysign(H(kPosInf), H(kNegOne)).to_bits(),
                 kNegInf);
  // copysign(NaN, -1.0): NaN with sign flipped
  const float16_t r = float16_t::copysign(H(kQNaN), H(kNegOne));
  EXPECT_TRUE(float16_t::is_nan(r));
  EXPECT_TRUE(float16_t::signbit(r));
}

TEST(Float16MathTest, FloorCeilTruncRound) {
  // Values: 1.5, -1.5, 1.0, -1.0
  const float16_t one_point_five = float16_t::from_float(1.5f);
  const float16_t neg_one_point_five = float16_t::from_float(-1.5f);

  // floor
  EXPECT_EQ(float16_t::floor(one_point_five).to_float(), 1.0f);
  EXPECT_EQ(float16_t::floor(neg_one_point_five).to_float(), -2.0f);
  EXPECT_EQ(float16_t::floor(H(kOne)).to_float(), 1.0f);
  EXPECT_EQ(float16_t::floor(H(kNegOne)).to_float(), -1.0f);

  // ceil
  EXPECT_EQ(float16_t::ceil(one_point_five).to_float(), 2.0f);
  EXPECT_EQ(float16_t::ceil(neg_one_point_five).to_float(), -1.0f);
  EXPECT_EQ(float16_t::ceil(H(kOne)).to_float(), 1.0f);

  // trunc
  EXPECT_EQ(float16_t::trunc(one_point_five).to_float(), 1.0f);
  EXPECT_EQ(float16_t::trunc(neg_one_point_five).to_float(), -1.0f);

  // round (half away from zero)
  EXPECT_EQ(float16_t::round(one_point_five).to_float(), 2.0f);
  EXPECT_EQ(float16_t::round(neg_one_point_five).to_float(), -2.0f);
  EXPECT_EQ(float16_t::round(float16_t::from_float(1.4f)).to_float(), 1.0f);
  EXPECT_EQ(float16_t::round(float16_t::from_float(-1.4f)).to_float(), -1.0f);

  // round_to_even (half to even / banker's rounding)
  // 0.5 rounds to 0.0 (even), 1.5 rounds to 2.0 (even)
  const float16_t half = float16_t::from_float(0.5f);
  EXPECT_EQ(float16_t::round_to_even(half).to_float(), 0.0f);
  EXPECT_EQ(float16_t::round_to_even(one_point_five).to_float(), 2.0f);
  EXPECT_EQ(float16_t::round_to_even(neg_one_point_five).to_float(), -2.0f);

  // Special values: floor/ceil/trunc/round of ±Inf = ±Inf
  EXPECT_TRUE(std::isinf(float16_t::floor(H(kPosInf)).to_float()));
  EXPECT_TRUE(std::isinf(float16_t::ceil(H(kNegInf)).to_float()));
  // NaN propagates
  EXPECT_TRUE(float16_t::is_nan(float16_t::floor(H(kQNaN))));
  EXPECT_TRUE(float16_t::is_nan(float16_t::ceil(H(kQNaN))));
  EXPECT_TRUE(float16_t::is_nan(float16_t::trunc(H(kQNaN))));
  EXPECT_TRUE(float16_t::is_nan(float16_t::round(H(kQNaN))));
  EXPECT_TRUE(float16_t::is_nan(float16_t::round_to_even(H(kQNaN))));
}

// ============================================================
// §3.8 / §6  Buffer write_f16 / read_f16 tests
// ============================================================

TEST(Float16BufferTest, WriteReadRoundTrip) {
  const uint16_t cases[] = {
      0x0000, // +0
      0x8000, // -0
      0x7C00, // +Inf
      0xFC00, // -Inf
      0x7E00, // canonical qNaN
      0x3C00, // 1.0
      0xBC00, // -1.0
      0x7BFF, // 65504 (max finite)
      0x0001, // min subnormal (2^-24)
      0x0400, // min normal (2^-14)
      0x03FF, // max subnormal
      0x4000, // 2.0
  };
  for (uint16_t bits : cases) {
    std::shared_ptr<Buffer> buf;
    allocate_buffer(16, &buf);
    buf->write_f16(float16_t::from_bits(bits));
    buf->reader_index(0);
    Error err;
    const float16_t got = buf->read_f16(err);
    ASSERT_TRUE(err.ok()) << "read_f16 error for bits=0x" << std::hex << bits;
    EXPECT_EQ(got.to_bits(), bits)
        << "round-trip failed for bits=0x" << std::hex << bits;
  }
}

TEST(Float16BufferTest, WireFormatGoldenLittleEndian) {
  // IEEE 754 binary16 wire format: little-endian (low byte first).
  struct Case {
    uint16_t bits;
    uint8_t lo;
    uint8_t hi;
  };
  const Case cases[] = {
      {0x3C00, 0x00, 0x3C}, // 1.0
      {0x7C00, 0x00, 0x7C}, // +Inf
      {0x8000, 0x00, 0x80}, // -0
      {0x0001, 0x01, 0x00}, // min subnormal
      {0x7BFF, 0xFF, 0x7B}, // 65504 max finite
      {0xFC00, 0x00, 0xFC}, // -Inf
  };
  for (const auto &c : cases) {
    std::shared_ptr<Buffer> buf;
    allocate_buffer(4, &buf);
    buf->write_f16(float16_t::from_bits(c.bits));
    EXPECT_EQ(buf->get<uint8_t>(0), c.lo)
        << "lo byte mismatch for bits=0x" << std::hex << c.bits;
    EXPECT_EQ(buf->get<uint8_t>(1), c.hi)
        << "hi byte mismatch for bits=0x" << std::hex << c.bits;
  }
}

TEST(Float16BufferTest, ReadBoundsErrorOnEmpty) {
  // size_=0 means no bytes available; reading 2 must fail.
  std::shared_ptr<Buffer> buf;
  allocate_buffer(0, &buf);
  Error error;
  const float16_t result = buf->read_f16(error);
  EXPECT_FALSE(error.ok());
  EXPECT_EQ(result.to_bits(), 0x0000u);
}

TEST(Float16BufferTest, ReadBoundsErrorOnOneByte) {
  // Only 1 byte of capacity; reading 2 must fail.
  std::shared_ptr<Buffer> buf;
  allocate_buffer(1, &buf);
  Error error;
  buf->read_f16(error);
  EXPECT_FALSE(error.ok());
}

TEST(Float16BufferTest, MultipleValuesSequential) {
  const uint16_t vals[] = {0x3C00, 0x4000, 0x7BFF, 0x0001, 0x7E00};
  std::shared_ptr<Buffer> buf;
  allocate_buffer(32, &buf);
  for (uint16_t v : vals) {
    buf->write_f16(float16_t::from_bits(v));
  }
  buf->reader_index(0);
  for (uint16_t expected : vals) {
    Error err;
    const float16_t got = buf->read_f16(err);
    ASSERT_TRUE(err.ok());
    EXPECT_EQ(got.to_bits(), expected);
  }
}

// ============================================================
// §3.8 / §6  Serializer<float16_t> type-ID check
// ============================================================

TEST(Float16SerializerTest, TypeId) {
  using namespace fory::serialization;
  EXPECT_EQ(static_cast<uint32_t>(Serializer<float16_t>::type_id),
            static_cast<uint32_t>(fory::TypeId::FLOAT16));
  EXPECT_EQ(static_cast<uint32_t>(fory::TypeId::FLOAT16), 17u);
}

// ============================================================
// §6  Full Fory struct round-trip tests
// ============================================================

namespace {

struct Float16Scalar {
  float16_t value;
  bool operator==(const Float16Scalar &o) const {
    return value.to_bits() == o.value.to_bits();
  }
  FORY_STRUCT(Float16Scalar, value);
};

struct Float16Vector {
  std::vector<float16_t> values;
  bool operator==(const Float16Vector &o) const {
    if (values.size() != o.values.size())
      return false;
    for (size_t i = 0; i < values.size(); ++i) {
      if (values[i].to_bits() != o.values[i].to_bits())
        return false;
    }
    return true;
  }
  FORY_STRUCT(Float16Vector, values);
};

struct Float16Map {
  std::map<std::string, float16_t> named_values;
  bool operator==(const Float16Map &o) const {
    if (named_values.size() != o.named_values.size())
      return false;
    for (const auto &kv : named_values) {
      auto it = o.named_values.find(kv.first);
      if (it == o.named_values.end())
        return false;
      if (kv.second.to_bits() != it->second.to_bits())
        return false;
    }
    return true;
  }
  FORY_STRUCT(Float16Map, named_values);
};

struct Float16Optional {
  std::optional<float16_t> opt_value;
  bool operator==(const Float16Optional &o) const {
    if (opt_value.has_value() != o.opt_value.has_value())
      return false;
    if (!opt_value.has_value())
      return true;
    return opt_value->to_bits() == o.opt_value->to_bits();
  }
  FORY_STRUCT(Float16Optional, opt_value);
};

fory::serialization::Fory make_xlang_fory() {
  return fory::serialization::Fory::builder()
      .xlang(true)
      .track_ref(false)
      .build();
}

} // namespace

TEST(Float16SerializerTest, ScalarRoundTrip) {
  auto fory = make_xlang_fory();
  fory.register_struct<Float16Scalar>(200);

  const uint16_t cases[] = {
      0x0000, 0x8000, 0x3C00, 0xBC00, 0x7C00,
      0xFC00, 0x7BFF, 0x0001, 0x0400, 0x7E00,
  };
  for (uint16_t bits : cases) {
    Float16Scalar original{float16_t::from_bits(bits)};
    auto ser = fory.serialize(original);
    ASSERT_TRUE(ser.ok()) << "serialize failed bits=0x" << std::hex << bits
                          << ": " << ser.error().to_string();
    auto deser =
        fory.deserialize<Float16Scalar>(ser.value().data(), ser.value().size());
    ASSERT_TRUE(deser.ok()) << "deserialize failed bits=0x" << std::hex << bits
                            << ": " << deser.error().to_string();
    EXPECT_EQ(deser.value(), original)
        << "round-trip mismatch bits=0x" << std::hex << bits;
  }
}

TEST(Float16SerializerTest, VectorRoundTrip) {
  auto fory = make_xlang_fory();
  fory.register_struct<Float16Vector>(201);

  Float16Vector original;
  original.values = {
      float16_t::from_float(0.0f),  float16_t::from_float(1.0f),
      float16_t::from_float(-1.0f),
      float16_t::from_bits(0x7C00), // +Inf
      float16_t::from_bits(0x7BFF), // max finite
      float16_t::from_bits(0x0001), // min subnormal
  };
  auto ser = fory.serialize(original);
  ASSERT_TRUE(ser.ok()) << ser.error().to_string();
  auto deser =
      fory.deserialize<Float16Vector>(ser.value().data(), ser.value().size());
  ASSERT_TRUE(deser.ok()) << deser.error().to_string();
  EXPECT_EQ(deser.value(), original);
}

TEST(Float16SerializerTest, EmptyVectorRoundTrip) {
  auto fory = make_xlang_fory();
  fory.register_struct<Float16Vector>(201);

  Float16Vector original;
  auto ser = fory.serialize(original);
  ASSERT_TRUE(ser.ok()) << ser.error().to_string();
  auto deser =
      fory.deserialize<Float16Vector>(ser.value().data(), ser.value().size());
  ASSERT_TRUE(deser.ok()) << deser.error().to_string();
  EXPECT_EQ(deser.value(), original);
}

TEST(Float16SerializerTest, MapRoundTrip) {
  auto fory = make_xlang_fory();
  fory.register_struct<Float16Map>(202);

  Float16Map original;
  original.named_values["one"] = float16_t::from_float(1.0f);
  original.named_values["neg_inf"] = float16_t::from_bits(0xFC00);
  original.named_values["max"] = float16_t::from_bits(0x7BFF);
  original.named_values["zero"] = float16_t::from_float(0.0f);

  auto ser = fory.serialize(original);
  ASSERT_TRUE(ser.ok()) << ser.error().to_string();
  auto deser =
      fory.deserialize<Float16Map>(ser.value().data(), ser.value().size());
  ASSERT_TRUE(deser.ok()) << deser.error().to_string();
  EXPECT_EQ(deser.value(), original);
}

TEST(Float16SerializerTest, OptionalPresentRoundTrip) {
  auto fory = make_xlang_fory();
  fory.register_struct<Float16Optional>(203);

  Float16Optional original;
  original.opt_value = float16_t::from_float(1.5f);

  auto ser = fory.serialize(original);
  ASSERT_TRUE(ser.ok()) << ser.error().to_string();
  auto deser =
      fory.deserialize<Float16Optional>(ser.value().data(), ser.value().size());
  ASSERT_TRUE(deser.ok()) << deser.error().to_string();
  EXPECT_EQ(deser.value(), original);
}

TEST(Float16SerializerTest, OptionalAbsentRoundTrip) {
  auto fory = make_xlang_fory();
  fory.register_struct<Float16Optional>(203);

  Float16Optional original; // opt_value is nullopt
  auto ser = fory.serialize(original);
  ASSERT_TRUE(ser.ok()) << ser.error().to_string();
  auto deser =
      fory.deserialize<Float16Optional>(ser.value().data(), ser.value().size());
  ASSERT_TRUE(deser.ok()) << deser.error().to_string();
  EXPECT_EQ(deser.value(), original);
}

TEST(Float16SerializerTest, WireGoldenOnePointZero) {
  // 1.0 = 0x3C00; serialized data bytes must contain [0x00, 0x3C] (LE).
  auto fory = make_xlang_fory();
  fory.register_struct<Float16Scalar>(200);

  Float16Scalar s{float16_t::from_bits(0x3C00)};
  auto ser = fory.serialize(s);
  ASSERT_TRUE(ser.ok()) << ser.error().to_string();

  const std::vector<uint8_t> &bytes = ser.value();
  bool found = false;
  for (size_t i = 0; i + 1 < bytes.size(); ++i) {
    if (bytes[i] == 0x00 && bytes[i + 1] == 0x3C) {
      found = true;
      break;
    }
  }
  EXPECT_TRUE(found)
      << "wire bytes [0x00, 0x3C] for 1.0 not found in serialized output";
}

} // namespace
} // namespace fory
