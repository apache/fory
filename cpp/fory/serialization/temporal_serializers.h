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
#include "fory/type/temporal.h"
#include <chrono>
#include <limits>

namespace fory {
namespace serialization {

// ============================================================================
// Duration Serializer
// ============================================================================

/// Serializer for Duration
/// Per xlang spec: serialized as signed varint64 seconds + signed int32
/// nanoseconds
template <> struct Serializer<Duration> {
  static constexpr TypeId type_id = TypeId::DURATION;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const Duration &duration, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data(duration, ctx);
  }

  static inline void write_data(const Duration &duration, WriteContext &ctx) {
    auto ns = duration.to_chrono();
    auto seconds = std::chrono::duration_cast<std::chrono::seconds>(ns);
    auto remainder = ns - seconds;
    ctx.write_var_int64(seconds.count());
    ctx.buffer().write_int32(static_cast<int32_t>(remainder.count()));
  }

  static inline void write_data_generic(const Duration &duration,
                                        WriteContext &ctx, bool has_generics) {
    write_data(duration, ctx);
  }

  static inline Duration read(ReadContext &ctx, RefMode ref_mode,
                              bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return Duration();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return Duration();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return Duration();
      }
    }
    return read_data(ctx);
  }

  static inline Duration read_data(ReadContext &ctx) {
    int64_t seconds = ctx.read_var_int64(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return Duration();
    }
    int32_t nanos = ctx.read_int32(ctx.error());
    return Duration(std::chrono::seconds(seconds) +
                    std::chrono::nanoseconds(nanos));
  }

  static inline Duration read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                             const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

// ============================================================================
// Timestamp Serializer
// ============================================================================

/// Serializer for Timestamp
/// Per xlang spec: serialized as int64 seconds + uint32 nanoseconds since Unix
/// epoch
template <> struct Serializer<Timestamp> {
  static constexpr TypeId type_id = TypeId::TIMESTAMP;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const Timestamp &timestamp, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data(timestamp, ctx);
  }

  static inline void write_data(const Timestamp &timestamp, WriteContext &ctx) {
    auto nanos = timestamp.time_since_epoch();
    auto seconds = std::chrono::duration_cast<std::chrono::seconds>(nanos);
    auto remainder = nanos - seconds;
    if (remainder.count() < 0) {
      seconds -= std::chrono::seconds(1);
      remainder += std::chrono::seconds(1);
    }
    int64_t seconds_count = seconds.count();
    uint32_t nanos_count = static_cast<uint32_t>(remainder.count());
    ctx.write_int64(seconds_count);
    ctx.write_uint32(nanos_count);
  }

  static inline void write_data_generic(const Timestamp &timestamp,
                                        WriteContext &ctx, bool has_generics) {
    write_data(timestamp, ctx);
  }

  static inline Timestamp read(ReadContext &ctx, RefMode ref_mode,
                               bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return Timestamp();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return Timestamp();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return Timestamp();
      }
    }
    return read_data(ctx);
  }

  static inline Timestamp read_data(ReadContext &ctx) {
    int64_t seconds = ctx.read_int64(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return Timestamp();
    }
    uint32_t nanos = ctx.read_uint32(ctx.error());
    return Timestamp(std::chrono::seconds(seconds) +
                     std::chrono::nanoseconds(nanos));
  }

  static inline Timestamp read_with_type_info(ReadContext &ctx,
                                              RefMode ref_mode,
                                              const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

// ============================================================================
// Date Serializer
// ============================================================================

/// Serializer for Date
/// Per xlang spec: serialized as signed varint64 day count since Unix epoch
template <> struct Serializer<Date> {
  static constexpr TypeId type_id = TypeId::DATE;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const Date &date, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data(date, ctx);
  }

  static inline void write_data(const Date &date, WriteContext &ctx) {
    ctx.write_var_int64(static_cast<int64_t>(date.days_since_epoch()));
  }

  static inline void write_data_generic(const Date &date, WriteContext &ctx,
                                        bool has_generics) {
    write_data(date, ctx);
  }

  static inline Date read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return Date();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return Date();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return Date();
      }
    }
    return read_data(ctx);
  }

  static inline Date read_data(ReadContext &ctx) {
    int64_t days = ctx.read_var_int64(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return Date();
    }
    if (FORY_PREDICT_FALSE(days < std::numeric_limits<int32_t>::min() ||
                           days > std::numeric_limits<int32_t>::max())) {
      ctx.set_error(Error::invalid_data(
          "Date day count " + std::to_string(days) + " exceeds int32_t range"));
      return Date();
    }
    return Date(static_cast<int32_t>(days));
  }

  static inline Date read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                         const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

// ============================================================================
// Chrono serializers
//
// These allow users to explicitly request chrono types as the deserialization
// target (e.g., fory.deserialize<std::chrono::nanoseconds>(...)).  They share
// the same wire encoding as the Fory-owned carrier serializers above and
// delegate to them via conversion
// ============================================================================

/// Serializer for std::chrono::nanoseconds
/// Per xlang spec: serialized as signed varint64 seconds + signed int32
/// nanoseconds
template <> struct Serializer<std::chrono::nanoseconds> {
  static constexpr TypeId type_id = TypeId::DURATION;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    Serializer<Duration>::read_type_info(ctx);
  }

  static inline void write(const std::chrono::nanoseconds &ns,
                           WriteContext &ctx, RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    Serializer<Duration>::write(Duration(ns), ctx, ref_mode, write_type,
                                has_generics);
  }

  static inline void write_data(const std::chrono::nanoseconds &ns,
                                WriteContext &ctx) {
    Serializer<Duration>::write_data(Duration(ns), ctx);
  }

  static inline void write_data_generic(const std::chrono::nanoseconds &ns,
                                        WriteContext &ctx, bool has_generics) {
    write_data(ns, ctx);
  }

  static inline std::chrono::nanoseconds
  read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    return Serializer<Duration>::read(ctx, ref_mode, read_type).to_chrono();
  }

  static inline std::chrono::nanoseconds read_data(ReadContext &ctx) {
    return Serializer<Duration>::read_data(ctx).to_chrono();
  }

  static inline std::chrono::nanoseconds
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

/// Serializer for std::chrono::time_point
/// Per xlang spec: serialized as int64 seconds + uint32 nanoseconds since Unix
/// epoch
template <>
struct Serializer<std::chrono::time_point<std::chrono::system_clock,
                                          std::chrono::nanoseconds>> {
  using ChronoTs = std::chrono::time_point<std::chrono::system_clock,
                                           std::chrono::nanoseconds>;
  static constexpr TypeId type_id = TypeId::TIMESTAMP;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    Serializer<Timestamp>::read_type_info(ctx);
  }

  static inline void write(const ChronoTs &tp, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    Serializer<Timestamp>::write(Timestamp(tp), ctx, ref_mode, write_type,
                                 has_generics);
  }

  static inline void write_data(const ChronoTs &tp, WriteContext &ctx) {
    Serializer<Timestamp>::write_data(Timestamp(tp), ctx);
  }

  static inline void write_data_generic(const ChronoTs &tp, WriteContext &ctx,
                                        bool has_generics) {
    write_data(tp, ctx);
  }

  static inline ChronoTs read(ReadContext &ctx, RefMode ref_mode,
                              bool read_type) {
    return Serializer<Timestamp>::read(ctx, ref_mode, read_type).to_chrono();
  }

  static inline ChronoTs read_data(ReadContext &ctx) {
    return Serializer<Timestamp>::read_data(ctx).to_chrono();
  }

  static inline ChronoTs read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                             const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
