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

#include <cstdint>
#include <type_traits>
namespace fory {

// A 16-bit floating point representation with 1 sign bit, 5 exponent bits, and 10 mantissa bits.
struct float16_t {
    uint16_t bits;

    // Internal bit access
    [[nodiscard]] uint16_t to_bits() const noexcept {
        return bits;
    }
    [[nodiscard]] static float16_t from_bits(const uint16_t bits) noexcept {
        float16_t half{};
        half.bits = bits;
        return half;
    }

    // Conversions
    [[nodiscard]] float to_float() const noexcept;
    [[nodiscard]] static float16_t from_float(float f) noexcept;

    // ---- Classification (IEEE 754-consistent) ----

    // True if the value is a NaN (quiet or signaling).
    [[nodiscard]] static bool is_nan(float16_t h) noexcept {
        return (h.bits & 0x7C00u) == 0x7C00u && (h.bits & 0x03FFu) != 0u;
    }

    // True if the value is positive or negative infinity.
    [[nodiscard]] static bool is_inf(float16_t h) noexcept {
        return (h.bits & 0x7FFFu) == 0x7C00u;
    }

    // True if the value is infinity of the requested sign.
    //   sign > 0  →  +Inf only
    //   sign < 0  →  -Inf only
    //   sign == 0 →  either +Inf or -Inf
    [[nodiscard]] static bool is_inf(float16_t h, int sign) noexcept {
        if (sign == 0) return is_inf(h);
        return sign > 0 ? h.bits == 0x7C00u : h.bits == 0xFC00u;
    }

    // True if the value is +0 or -0.
    [[nodiscard]] static bool is_zero(float16_t h) noexcept {
        return (h.bits & 0x7FFFu) == 0u;
    }

    // True if the sign bit is set (value is negative or negative zero/NaN).
    [[nodiscard]] static bool signbit(float16_t h) noexcept {
        return (h.bits & 0x8000u) != 0u;
    }

    // True if the value is a subnormal (denormal): exp == 0, mantissa != 0.
    [[nodiscard]] static bool is_subnormal(float16_t h) noexcept {
        return (h.bits & 0x7C00u) == 0u && (h.bits & 0x03FFu) != 0u;
    }

    // True if the value is a normal number (not zero, subnormal, Inf, or NaN).
    [[nodiscard]] static bool is_normal(float16_t h) noexcept {
        const uint16_t exp = h.bits & 0x7C00u;
        return exp != 0u && exp != 0x7C00u;
    }

    // True if the value is finite (not Inf and not NaN).
    [[nodiscard]] static bool is_finite(float16_t h) noexcept {
        return (h.bits & 0x7C00u) != 0x7C00u;
    }
};

static_assert(sizeof(float16_t) == 2);
static_assert(std::is_trivial_v<float16_t>);
static_assert(std::is_standard_layout_v<float16_t>);

}  // namespace fory

