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

#include "fory/util/macros.h"
#include <chrono>
#include <cstdint>
#include <ctime>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <type_traits>

namespace fory {

namespace util {

//
// Byte-swap 16-bit, 32-bit and 64-bit values. based on arrow/util/bit-util.h
//

// Swap the byte order (i.e. endianess)
static inline int64_t byte_swap(int64_t value) {
  return FORY_BYTE_SWAP64(value);
}

static inline uint64_t byte_swap(uint64_t value) {
  return static_cast<uint64_t>(FORY_BYTE_SWAP64(value));
}

static inline int32_t byte_swap(int32_t value) {
  return FORY_BYTE_SWAP32(value);
}

static inline uint32_t byte_swap(uint32_t value) {
  return static_cast<uint32_t>(FORY_BYTE_SWAP32(value));
}

static inline int16_t byte_swap(int16_t value) {
  constexpr auto m = static_cast<int16_t>(0xff);
  return static_cast<int16_t>(((value >> 8) & m) | ((value & m) << 8));
}

static inline uint16_t byte_swap(uint16_t value) {
  return static_cast<uint16_t>(byte_swap(static_cast<int16_t>(value)));
}

static inline float byte_swap(float value) {
  auto *ptr = reinterpret_cast<uint32_t *>(&value);
  uint32_t i = FORY_BYTE_SWAP32(*ptr);
  auto *f = reinterpret_cast<float *>(&i);
  return *(f);
}

static inline double byte_swap(double value) {
  auto *ptr = reinterpret_cast<uint64_t *>(&value);
  uint64_t i = FORY_BYTE_SWAP64(*ptr);
  auto *d = reinterpret_cast<double *>(&i);
  return *d;
}

// write_string the swapped bytes into dst. Src and dst cannot overlap.
static inline void byte_swap(void *dst, const void *src, int len) {
  switch (len) {
  case 1:
    *reinterpret_cast<int8_t *>(dst) = *reinterpret_cast<const int8_t *>(src);
    return;
  case 2:
    *reinterpret_cast<int16_t *>(dst) =
        byte_swap(*reinterpret_cast<const int16_t *>(src));
    return;
  case 4:
    *reinterpret_cast<int32_t *>(dst) =
        byte_swap(*reinterpret_cast<const int32_t *>(src));
    return;
  case 8:
    *reinterpret_cast<int64_t *>(dst) =
        byte_swap(*reinterpret_cast<const int64_t *>(src));
    return;
  default:
    break;
  }

  auto d = reinterpret_cast<uint8_t *>(dst);
  auto s = reinterpret_cast<const uint8_t *>(src);
  for (int i = 0; i < len; ++i) {
    d[i] = s[len - i - 1];
  }
}

// Convert to little/big endian format from the machine's native endian format.
template <typename T>
using IsEndianConvertibleType =
    std::disjunction<std::is_same<T, int64_t>, std::is_same<T, uint64_t>,
                     std::is_same<T, int32_t>, std::is_same<T, uint32_t>,
                     std::is_same<T, int16_t>, std::is_same<T, uint16_t>,
                     std::is_same<T, float>, std::is_same<T, double>>;

template <typename T>
using EnableIfIsEndianConvertibleType =
    typename std::enable_if<IsEndianConvertibleType<T>::value, T>::type;

template <typename T, typename = EnableIfIsEndianConvertibleType<T>>
static inline T to_big_endian(T value) {
  if constexpr (FORY_LITTLE_ENDIAN) {
    return byte_swap(value);
  } else {
    return value;
  }
}

template <typename T, typename = EnableIfIsEndianConvertibleType<T>>
static inline T to_little_endian(T value) {
  if constexpr (FORY_LITTLE_ENDIAN) {
    return value;
  } else {
    return byte_swap(value);
  }
}

// Bitmask selecting the k-th bit in a byte
static constexpr uint8_t k_bitmask[] = {1, 2, 4, 8, 16, 32, 64, 128};

// the bitwise complement version of k_bitmask
static constexpr uint8_t k_flipped_bitmask[] = {254, 253, 251, 247,
                                                239, 223, 191, 127};

constexpr bool is_multiple_of64(int64_t n) { return (n & 63) == 0; }

constexpr bool is_multiple_of8(int64_t n) { return (n & 7) == 0; }

static inline bool get_bit(const uint8_t *bits, uint32_t i) {
  return static_cast<bool>((bits[i >> 3] >> (i & 0x07)) & 1);
}

static inline void clear_bit(uint8_t *bits, int64_t i) {
  bits[i / 8] &= k_flipped_bitmask[i % 8];
}

static inline void set_bit(uint8_t *bits, int64_t i) {
  bits[i / 8] |= k_bitmask[i % 8];
}

static inline void set_bit_to(uint8_t *bits, int64_t i, bool bit_is_set) {
  // https://graphics.stanford.edu/~seander/bithacks.html
  // "Conditionally set or clear bits without branching"
  // NOTE: this seems to confuse Valgrind as it reads from potentially
  // uninitialized memory
  bits[i / 8] ^=
      static_cast<uint8_t>(-static_cast<uint8_t>(bit_is_set) ^ bits[i / 8]) &
      k_bitmask[i % 8];
}

static inline int round_number_of_bytes_to_nearest_word(int num_bytes) {
  int remainder = num_bytes & 0x07;
  if (remainder == 0) {
    return num_bytes;
  } else {
    return num_bytes + (8 - remainder);
  }
}

static inline std::string hex(uint8_t *data, int32_t length) {
  constexpr char hex[] = "0123456789abcdef";
  std::string result;
  for (int i = 0; i < length; i++) {
    uint8_t val = data[i];
    result.push_back(hex[val >> 4]);
    result.push_back(hex[val & 0xf]);
  }
  return result;
}

} // namespace util

} // namespace fory
