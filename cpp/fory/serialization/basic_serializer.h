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

#include "fory/serialization/context.h"
#include "fory/serialization/serializer_traits.h"
#include "fory/type/type.h"
#include "fory/util/error.h"
#include <cstdint>
#include <type_traits>

namespace fory {
namespace serialization {

// ============================================================================
// Primitive Type Serializers
// All primitive serializers use context-based error accumulation:
// - Write methods return void and set ctx.error_ on failure
// - Read methods return T directly and set ctx.error_ on failure
// - No per-operation error checks for primitives (buffer auto-grows on write,
//   errors accumulate on read)
// ============================================================================

/// Boolean serializer
template <> struct Serializer<bool> {
  static constexpr TypeId type_id = TypeId::BOOL;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(bool value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(bool value, WriteContext &ctx) {
    ctx.write_uint8(value ? 1 : 0);
  }

  static inline void write_data_generic(bool value, WriteContext &ctx, bool) {
    write_data(value, ctx);
  }

  static inline bool read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return false;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return false;
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return false;
      }
    }
    uint8_t value = ctx.read_uint8(ctx.error());
    return value != 0;
  }

  static inline bool read_data(ReadContext &ctx) {
    uint8_t value = ctx.read_uint8(ctx.error());
    return value != 0;
  }

  static inline bool read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline bool read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                         const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// int8_t serializer
template <> struct Serializer<int8_t> {
  static constexpr TypeId type_id = TypeId::INT8;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(int8_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(int8_t value, WriteContext &ctx) {
    ctx.write_int8(value);
  }

  static inline void write_data_generic(int8_t value, WriteContext &ctx, bool) {
    write_data(value, ctx);
  }

  static inline int8_t read(ReadContext &ctx, RefMode ref_mode,
                            bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return 0;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return 0;
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return 0;
      }
    }
    return ctx.read_int8(ctx.error());
  }

  static inline int8_t read_data(ReadContext &ctx) {
    return ctx.read_int8(ctx.error());
  }

  static inline int8_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline int8_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                           const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// int16_t serializer
template <> struct Serializer<int16_t> {
  static constexpr TypeId type_id = TypeId::INT16;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(int16_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(int16_t value, WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(int16_t));
  }

  static inline void write_data_generic(int16_t value, WriteContext &ctx,
                                        bool) {
    write_data(value, ctx);
  }

  static inline int16_t read(ReadContext &ctx, RefMode ref_mode,
                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return 0;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return 0;
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return 0;
      }
    }
    return ctx.read_int16(ctx.error());
  }

  static inline int16_t read_data(ReadContext &ctx) {
    return ctx.read_int16(ctx.error());
  }

  static inline int16_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline int16_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                            const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// int32_t serializer - uses VARINT32 to match Java xlang mode and Rust
template <> struct Serializer<int32_t> {
  static constexpr TypeId type_id = TypeId::VARINT32;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(int32_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(int32_t value, WriteContext &ctx) {
    ctx.write_varint32(value);
  }

  static inline void write_data_generic(int32_t value, WriteContext &ctx,
                                        bool) {
    write_data(value, ctx);
  }

  static inline int32_t read(ReadContext &ctx, RefMode ref_mode,
                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return 0;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return 0;
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return 0;
      }
    }
    return ctx.read_varint32(ctx.error());
  }

  static inline int32_t read_data(ReadContext &ctx) {
    return ctx.read_varint32(ctx.error());
  }

  static inline int32_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline int32_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                            const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// int64_t serializer - uses VARINT64 to match Java xlang mode and Rust
template <> struct Serializer<int64_t> {
  static constexpr TypeId type_id = TypeId::VARINT64;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(int64_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(int64_t value, WriteContext &ctx) {
    ctx.write_varint64(value);
  }

  static inline void write_data_generic(int64_t value, WriteContext &ctx,
                                        bool) {
    write_data(value, ctx);
  }

  static inline int64_t read(ReadContext &ctx, RefMode ref_mode,
                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return 0;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return 0;
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return 0;
      }
    }
    return ctx.read_varint64(ctx.error());
  }

  static inline int64_t read_data(ReadContext &ctx) {
    return ctx.read_varint64(ctx.error());
  }

  static inline int64_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline int64_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                            const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// float serializer
template <> struct Serializer<float> {
  static constexpr TypeId type_id = TypeId::FLOAT32;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(float value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(float value, WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(float));
  }

  static inline void write_data_generic(float value, WriteContext &ctx, bool) {
    write_data(value, ctx);
  }

  static inline float read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return 0.0f;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return 0.0f;
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return 0.0f;
      }
    }
    return ctx.read_float(ctx.error());
  }

  static inline float read_data(ReadContext &ctx) {
    return ctx.read_float(ctx.error());
  }

  static inline float read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline float read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                          const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// double serializer
template <> struct Serializer<double> {
  static constexpr TypeId type_id = TypeId::FLOAT64;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(double value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(double value, WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(double));
  }

  static inline void write_data_generic(double value, WriteContext &ctx, bool) {
    write_data(value, ctx);
  }

  static inline double read(ReadContext &ctx, RefMode ref_mode,
                            bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return 0.0;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return 0.0;
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return 0.0;
      }
    }
    return ctx.read_double(ctx.error());
  }

  static inline double read_data(ReadContext &ctx) {
    return ctx.read_double(ctx.error());
  }

  static inline double read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline double read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                           const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

// ============================================================================
// Character Type Serializers (C++ native only, not supported in xlang mode)
// ============================================================================

/// char serializer (C++ native only, type_id = 68)
template <> struct Serializer<char> {
  static constexpr TypeId type_id = TypeId::CHAR;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(char value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(char value, WriteContext &ctx) {
    ctx.write_int8(static_cast<int8_t>(value));
  }

  static inline void write_data_generic(char value, WriteContext &ctx, bool) {
    write_data(value, ctx);
  }

  static inline char read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return '\0';
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return '\0';
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return '\0';
      }
    }
    return read_data(ctx);
  }

  static inline char read_data(ReadContext &ctx) {
    return static_cast<char>(ctx.read_int8(ctx.error()));
  }

  static inline char read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline char read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                         const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// char16_t serializer (C++ native only, type_id = 69)
template <> struct Serializer<char16_t> {
  static constexpr TypeId type_id = TypeId::CHAR16;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(char16_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(char16_t value, WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(char16_t));
  }

  static inline void write_data_generic(char16_t value, WriteContext &ctx,
                                        bool) {
    write_data(value, ctx);
  }

  static inline char16_t read(ReadContext &ctx, RefMode ref_mode,
                              bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return u'\0';
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return u'\0';
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return u'\0';
      }
    }
    return read_data(ctx);
  }

  static inline char16_t read_data(ReadContext &ctx) {
    char16_t value;
    ctx.read_bytes(reinterpret_cast<uint8_t *>(&value), sizeof(char16_t),
                   ctx.error());
    return value;
  }

  static inline char16_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline char16_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                             const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// char32_t serializer (C++ native only, type_id = 70)
template <> struct Serializer<char32_t> {
  static constexpr TypeId type_id = TypeId::CHAR32;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (actual != static_cast<uint32_t>(type_id)) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(char32_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(char32_t value, WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(char32_t));
  }

  static inline void write_data_generic(char32_t value, WriteContext &ctx,
                                        bool) {
    write_data(value, ctx);
  }

  static inline char32_t read(ReadContext &ctx, RefMode ref_mode,
                              bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return U'\0';
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return U'\0';
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return U'\0';
      }
    }
    return read_data(ctx);
  }

  static inline char32_t read_data(ReadContext &ctx) {
    char32_t value;
    ctx.read_bytes(reinterpret_cast<uint8_t *>(&value), sizeof(char32_t),
                   ctx.error());
    return value;
  }

  static inline char32_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline char32_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                             const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
