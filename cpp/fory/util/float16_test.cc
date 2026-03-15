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
    {0x7FC00000u, 0x7E00u}, //qNaN
    {0x7F800001u, 0x7E00u}, //smallest sNaN, quieted
    {0x7FC02000u, 0x7E01u}, //qNaN with small payload should be preserved
    {0x7FDFF000u, 0x7EFFu}, //qNaN with large payload should be truncated
    {0xFFC02000u, 0xFE01u}, // negative qNaN with small payload should be preserved
    {0x7FA00000u, 0x7F00u}, // sNaN with payload, quieted
    {0xFFA00000u, 0xFF00u}, // negative sNaN with payload, quieted
  };
  for (const auto &[input_bits, expected_half_bits] : cases) {
    const uint16_t got = convert_bits(bits_to_float(input_bits));
    EXPECT_HALF_EQ(got, expected_half_bits);
    EXPECT_HALF_EQ(static_cast<uint16_t>(got & 0x7C00), 0x7C00); // Exponent bits should be all 1s for NaNs
    EXPECT_NE(got & 0x03FF, 0); // Fraction bits should not be all 0s for NaNs
    EXPECT_NE(got & 0x0200, 0); // The quiet bit (bit 9 of the fraction) should be set for NaNs
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
    if (const auto fraction = static_cast<uint16_t>(half & 0x03FF); exponent == 0x1F && fraction != 0) {
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
  const float tie_to_zero = std::ldexp(1.0f, -25); // halfway between 0 and the smallest subnormal
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

  const double zero_subnormal_midpoint = half_bits_to_double(0x0001) * 0.5; // halfway between 0 and the smallest subnormal
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
      continue; // skip midpoints that aren't exactly representable in float32, since they won't round to either half value
    }

    const uint16_t expected = (lower & 1u) == 0 ? lower : upper; // which one is even?
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
  // The true midpoint between 0x3BFF and 0x3C00 is 1 - 2^-12; ties to even → 0x3C00
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

  // qNaN with payload bit 0 set (0x7E01): f16 mantissa=0x201; 0x201<<13=0x402000
  // f32 = 0x7F800000 | 0x402000 = 0x7FC02000
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
    const auto expected_pos =
        static_cast<float>(half_bits_to_double(frac));
    EXPECT_EQ(float16_t::from_bits(frac).to_float(), expected_pos)
        << "to_float mismatch for subnormal 0x" << std::hex << frac;
    const auto neg_bits = static_cast<uint16_t>(frac | 0x8000);
    const auto expected_neg =
        static_cast<float>(half_bits_to_double(neg_bits));
    EXPECT_EQ(float16_t::from_bits(neg_bits).to_float(), expected_neg)
        << "to_float mismatch for negative subnormal 0x" << std::hex << neg_bits;
  }
}

TEST(Float16ToFloatTest, AllNormalsMatchReference) {
  for (uint16_t exp = 1; exp <= 30; ++exp) {
    for (uint16_t frac = 0; frac <= 0x03FF; ++frac) {
      const auto bits = static_cast<uint16_t>((exp << 10) | frac);
      const auto expected_pos =
          static_cast<float>(half_bits_to_double(bits));
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
      EXPECT_TRUE(std::isnan(h_float))
          << "to_float of NaN bits=0x" << std::hex << half_bits << " must be NaN";
      EXPECT_EQ(h2.to_bits() & 0x7C00u, 0x7C00u)
          << "NaN round-trip exp must be all-ones for bits=0x" << std::hex << half_bits;
      EXPECT_NE(h2.to_bits() & 0x03FFu, 0u)
          << "NaN round-trip frac must be non-zero for bits=0x" << std::hex << half_bits;
      EXPECT_NE(h2.to_bits() & 0x0200u, 0u)
          << "NaN round-trip quiet bit must be set for bits=0x" << std::hex << half_bits;
      EXPECT_EQ(h2.to_bits() & 0x8000u, static_cast<uint16_t>(half_bits & 0x8000u))
          << "NaN sign must be preserved for bits=0x" << std::hex << half_bits;
    } else {
      // Non-NaN: must round-trip exactly
      EXPECT_EQ(h2.to_bits(), half_bits)
          << "Round-trip failed for bits=0x" << std::hex << half_bits;
    }
  }
}

} // namespace
} // namespace fory
