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


};

static_assert(sizeof(float16_t) == 2);
static_assert(std::is_trivial_v<float16_t>);
static_assert(std::is_standard_layout_v<float16_t>);

}  // namespace fory

