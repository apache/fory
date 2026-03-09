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
  EXPECT_HALF_EQ(convert_bits(one + one_half_ulp), 0x3C00);
  EXPECT_HALF_EQ(convert_bits(one - one_half_ulp), 0x3C00);
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

} // namespace
} // namespace fory
