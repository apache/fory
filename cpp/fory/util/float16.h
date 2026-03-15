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

#include <cmath>
#include <cstdint>
#include <string>
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

    // ---- String representation ----

    [[nodiscard]] static std::string to_string(float16_t h) {
        return std::to_string(h.to_float());
    }

    // ---- Arithmetic (computed in float32, rounded back to float16) ----

    [[nodiscard]] static float16_t add(float16_t a, float16_t b) noexcept {
        return from_float(a.to_float() + b.to_float());
    }
    [[nodiscard]] static float16_t sub(float16_t a, float16_t b) noexcept {
        return from_float(a.to_float() - b.to_float());
    }
    [[nodiscard]] static float16_t mul(float16_t a, float16_t b) noexcept {
        return from_float(a.to_float() * b.to_float());
    }
    [[nodiscard]] static float16_t div(float16_t a, float16_t b) noexcept {
        return from_float(a.to_float() / b.to_float());
    }

    // Negate: flip sign bit directly (exact, no rounding).
    [[nodiscard]] static float16_t neg(float16_t a) noexcept {
        return from_bits(static_cast<uint16_t>(a.bits ^ 0x8000u));
    }

    // Absolute value: clear sign bit directly (exact, no rounding).
    [[nodiscard]] static float16_t abs(float16_t a) noexcept {
        return from_bits(static_cast<uint16_t>(a.bits & 0x7FFFu));
    }

    // ---- Optional math (computed in float32, rounded back) ----

    [[nodiscard]] static float16_t sqrt(float16_t a) noexcept {
        return from_float(std::sqrt(a.to_float()));
    }
    // fmin/fmax propagate NaN the same way as IEEE minNum/maxNum.
    [[nodiscard]] static float16_t min(float16_t a, float16_t b) noexcept {
        return from_float(std::fmin(a.to_float(), b.to_float()));
    }
    [[nodiscard]] static float16_t max(float16_t a, float16_t b) noexcept {
        return from_float(std::fmax(a.to_float(), b.to_float()));
    }
    // copysign: take magnitude from |a|, sign from b — exact, bit operation.
    [[nodiscard]] static float16_t copysign(float16_t a, float16_t b) noexcept {
        return from_bits(static_cast<uint16_t>((a.bits & 0x7FFFu) | (b.bits & 0x8000u)));
    }
    [[nodiscard]] static float16_t floor(float16_t a) noexcept {
        return from_float(std::floor(a.to_float()));
    }
    [[nodiscard]] static float16_t ceil(float16_t a) noexcept {
        return from_float(std::ceil(a.to_float()));
    }
    [[nodiscard]] static float16_t trunc(float16_t a) noexcept {
        return from_float(std::trunc(a.to_float()));
    }
    // round: round half away from zero (matches std::round semantics).
    [[nodiscard]] static float16_t round(float16_t a) noexcept {
        return from_float(std::round(a.to_float()));
    }
    // round_to_even: round half to even (banker's rounding, matches std::nearbyint
    // with default IEEE rounding mode).
    [[nodiscard]] static float16_t round_to_even(float16_t a) noexcept {
        return from_float(std::nearbyint(a.to_float()));
    }

    // ---- Compound assignment operators ----

    float16_t& operator+=(float16_t rhs) noexcept {
        *this = add(*this, rhs); return *this;
    }
    float16_t& operator-=(float16_t rhs) noexcept {
        *this = sub(*this, rhs); return *this;
    }
    float16_t& operator*=(float16_t rhs) noexcept {
        *this = mul(*this, rhs); return *this;
    }
    float16_t& operator/=(float16_t rhs) noexcept {
        *this = div(*this, rhs); return *this;
    }
};

static_assert(sizeof(float16_t) == 2);
static_assert(std::is_trivial_v<float16_t>);
static_assert(std::is_standard_layout_v<float16_t>);

// ---- Free-function operator overloads ----

[[nodiscard]] inline float16_t operator+(float16_t a, float16_t b) noexcept {
    return float16_t::add(a, b);
}
[[nodiscard]] inline float16_t operator-(float16_t a, float16_t b) noexcept {
    return float16_t::sub(a, b);
}
[[nodiscard]] inline float16_t operator*(float16_t a, float16_t b) noexcept {
    return float16_t::mul(a, b);
}
[[nodiscard]] inline float16_t operator/(float16_t a, float16_t b) noexcept {
    return float16_t::div(a, b);
}
[[nodiscard]] inline float16_t operator-(float16_t a) noexcept {
    return float16_t::neg(a);
}
[[nodiscard]] inline float16_t operator+(float16_t a) noexcept {
    return a;
}

}  // namespace fory

