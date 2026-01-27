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
#include <chrono>

namespace fory {
namespace serialization {

// ============================================================================
// Temporal Type Aliases
// ============================================================================

/// Duration: absolute length of time as nanoseconds
using Duration = std::chrono::nanoseconds;

/// Timestamp: point in time as nanoseconds since Unix epoch (Jan 1, 1970 UTC)
using Timestamp = std::chrono::time_point<std::chrono::system_clock,
                                          std::chrono::nanoseconds>;

/// Date: naive date without timezone as days since Unix epoch
struct Date {
  int32_t days_since_epoch; // Days since Jan 1, 1970 UTC

  Date() : days_since_epoch(0) {}
  explicit Date(int32_t days) : days_since_epoch(days) {}

  bool operator==(const Date &other) const {
    return days_since_epoch == other.days_since_epoch;
  }

  bool operator!=(const Date &other) const { return !(*this == other); }
};

// ============================================================================
// Duration Serializer
// ============================================================================

/// Serializer for Duration (std::chrono::nanoseconds)
/// Per xlang spec: serialized as int64 nanosecond count
template <> struct Serializer<Duration> {
  static constexpr TypeId type_id = TypeId::DURATION;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
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
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(duration, ctx);
  }

  static inline void write_data(const Duration &duration, WriteContext &ctx) {
    int64_t nanos = duration.count();
    ctx.write_bytes(&nanos, sizeof(int64_t));
  }

  static inline void write_data_generic(const Duration &duration,
                                        WriteContext &ctx, bool has_generics) {
    write_data(duration, ctx);
  }

  static inline Duration read(ReadContext &ctx, RefMode ref_mode,
                              bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return Duration(0);
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return Duration(0);
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return Duration(0);
      }
    }
    return read_data(ctx);
  }

  static inline Duration read_data(ReadContext &ctx) {
    int64_t nanos;
    ctx.read_bytes(&nanos, sizeof(int64_t), ctx.error());
    return Duration(nanos);
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
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
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
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
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
      return Timestamp(std::chrono::nanoseconds(0));
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return Timestamp(std::chrono::nanoseconds(0));
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return Timestamp(std::chrono::nanoseconds(0));
      }
    }
    return read_data(ctx);
  }

  static inline Timestamp read_data(ReadContext &ctx) {
    int64_t seconds = ctx.read_int64(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return Timestamp(std::chrono::nanoseconds(0));
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
/// Per xlang spec: serialized as int32 day count since Unix epoch
template <> struct Serializer<Date> {
  static constexpr TypeId type_id = TypeId::DATE;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
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
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(date, ctx);
  }

  static inline void write_data(const Date &date, WriteContext &ctx) {
    ctx.write_bytes(&date.days_since_epoch, sizeof(int32_t));
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
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
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
    Date date;
    ctx.read_bytes(&date.days_since_epoch, sizeof(int32_t), ctx.error());
    return date;
  }

  static inline Date read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                         const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
