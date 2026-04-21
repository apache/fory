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

#include "fory/serialization/serializer.h"

#include <cstdint>
#include <initializer_list>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace fory {
namespace serialization {

inline void normalize_decimal_magnitude(std::vector<uint8_t> &magnitude_le) {
  while (!magnitude_le.empty() && magnitude_le.back() == 0) {
    magnitude_le.pop_back();
  }
}

/// Exact decimal value represented as `unscaled_value * 10^-scale`.
class Decimal {
public:
  Decimal() : scale_(0), negative_(false) {}

  Decimal(int32_t scale, bool negative, std::vector<uint8_t> magnitude_le)
      : scale_(scale), negative_(negative),
        magnitude_le_(std::move(magnitude_le)) {
    normalize_decimal_magnitude(magnitude_le_);
    if (magnitude_le_.empty()) {
      negative_ = false;
    }
  }

  static Decimal from_int64(int64_t value, int32_t scale = 0) {
    if (value == 0) {
      return Decimal(scale, false, {});
    }
    const bool negative = value < 0;
    uint64_t magnitude = negative
                             ? (value == std::numeric_limits<int64_t>::min()
                                    ? (uint64_t{1} << 63)
                                    : static_cast<uint64_t>(-value))
                             : static_cast<uint64_t>(value);
    std::vector<uint8_t> magnitude_le;
    while (magnitude != 0) {
      magnitude_le.push_back(static_cast<uint8_t>(magnitude & 0xFF));
      magnitude >>= 8;
    }
    return Decimal(scale, negative, std::move(magnitude_le));
  }

  static Decimal from_bytes(int32_t scale, bool negative,
                            std::initializer_list<uint8_t> magnitude_le) {
    return Decimal(scale, negative, std::vector<uint8_t>(magnitude_le));
  }

  int32_t scale() const { return scale_; }

  bool negative() const { return negative_; }

  const std::vector<uint8_t> &magnitude_le() const { return magnitude_le_; }

  bool is_zero() const { return magnitude_le_.empty(); }

  bool operator==(const Decimal &other) const {
    return scale_ == other.scale_ && negative_ == other.negative_ &&
           magnitude_le_ == other.magnitude_le_;
  }

  bool operator!=(const Decimal &other) const { return !(*this == other); }

private:
  int32_t scale_;
  bool negative_;
  std::vector<uint8_t> magnitude_le_;
};

inline uint64_t encode_decimal_zigzag64(int64_t value) {
  if (value >= 0) {
    return static_cast<uint64_t>(value) << 1;
  }
  return (static_cast<uint64_t>(~value) << 1) | 1ULL;
}

inline int64_t decode_decimal_zigzag64(uint64_t value) {
  if ((value & 1ULL) == 0) {
    return static_cast<int64_t>(value >> 1);
  }
  return ~static_cast<int64_t>(value >> 1);
}

inline bool
decimal_magnitude_to_uint64(const std::vector<uint8_t> &magnitude_le,
                            uint64_t &value) {
  if (magnitude_le.size() > sizeof(uint64_t)) {
    return false;
  }
  value = 0;
  for (size_t i = 0; i < magnitude_le.size(); ++i) {
    value |= static_cast<uint64_t>(magnitude_le[i]) << (i * 8);
  }
  return true;
}

inline bool decimal_try_get_int64(const Decimal &decimal, int64_t &value) {
  if (decimal.is_zero()) {
    value = 0;
    return true;
  }

  uint64_t magnitude = 0;
  if (!decimal_magnitude_to_uint64(decimal.magnitude_le(), magnitude)) {
    return false;
  }

  if (!decimal.negative()) {
    if (magnitude >
        static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
      return false;
    }
    value = static_cast<int64_t>(magnitude);
    return true;
  }

  if (magnitude == (uint64_t{1} << 63)) {
    value = std::numeric_limits<int64_t>::min();
    return true;
  }
  if (magnitude > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
    return false;
  }
  value = -static_cast<int64_t>(magnitude);
  return true;
}

inline bool can_use_small_decimal_encoding(const Decimal &decimal,
                                           int64_t &small_value) {
  if (!decimal_try_get_int64(decimal, small_value)) {
    return false;
  }
  return encode_decimal_zigzag64(small_value) <=
         static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
}

template <> struct Serializer<Decimal> {
  static constexpr TypeId type_id = TypeId::DECIMAL;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const Decimal &value, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    (void)has_generics;
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(const Decimal &value, WriteContext &ctx) {
    ctx.write_var_int32(value.scale());
    int64_t small_value = 0;
    if (can_use_small_decimal_encoding(value, small_value)) {
      ctx.write_var_uint64(encode_decimal_zigzag64(small_value) << 1);
      return;
    }

    if (value.is_zero()) {
      ctx.set_error(
          Error::invalid_data("Zero must use the small decimal encoding"));
      return;
    }
    if (value.magnitude_le().size() >
        static_cast<size_t>(std::numeric_limits<uint32_t>::max())) {
      ctx.set_error(Error::invalid_data(
          "Decimal magnitude length exceeds uint32_t range"));
      return;
    }

    uint64_t meta = (static_cast<uint64_t>(value.magnitude_le().size()) << 1) |
                    (value.negative() ? 1ULL : 0ULL);
    ctx.write_var_uint64((meta << 1) | 1ULL);
    ctx.write_bytes(value.magnitude_le().data(),
                    static_cast<uint32_t>(value.magnitude_le().size()));
  }

  static inline void write_data_generic(const Decimal &value, WriteContext &ctx,
                                        bool has_generics) {
    (void)has_generics;
    write_data(value, ctx);
  }

  static inline Decimal read(ReadContext &ctx, RefMode ref_mode,
                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return Decimal();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return Decimal();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return Decimal();
      }
    }
    return read_data(ctx);
  }

  static inline Decimal read_data(ReadContext &ctx) {
    int32_t scale = ctx.read_var_int32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return Decimal();
    }
    uint64_t header = ctx.read_var_uint64(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return Decimal();
    }
    if ((header & 1ULL) == 0) {
      return Decimal::from_int64(decode_decimal_zigzag64(header >> 1), scale);
    }

    uint64_t meta = header >> 1;
    uint64_t length64 = meta >> 1;
    if (length64 == 0) {
      ctx.set_error(Error::invalid_data("Invalid decimal magnitude length 0"));
      return Decimal();
    }
    if (length64 > ctx.config().max_binary_size) {
      ctx.set_error(Error::invalid_data("Binary size exceeds max_binary_size"));
      return Decimal();
    }
    if (length64 > std::numeric_limits<uint32_t>::max()) {
      ctx.set_error(Error::invalid_data("Invalid decimal magnitude length " +
                                        std::to_string(length64)));
      return Decimal();
    }

    uint32_t length = static_cast<uint32_t>(length64);
    std::vector<uint8_t> payload(length);
    ctx.buffer().read_bytes(payload.data(), length, ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return Decimal();
    }
    if (payload.back() == 0) {
      ctx.set_error(Error::invalid_data(
          "Non-canonical decimal payload: trailing zero byte"));
      return Decimal();
    }

    return Decimal(scale, (meta & 1ULL) != 0, std::move(payload));
  }

  static inline Decimal read_data_generic(ReadContext &ctx, bool has_generics) {
    (void)has_generics;
    return read_data(ctx);
  }

  static inline Decimal read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                            const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
