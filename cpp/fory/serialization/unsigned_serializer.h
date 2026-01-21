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
#include <array>
#include <cstdint>
#include <vector>

namespace fory {
namespace serialization {

// ============================================================================
// Unsigned Integer Type Serializers (xlang=false mode only)
// These serializers use distinct TypeIds for unsigned types.
// Unsigned types are NOT supported in xlang mode per the xlang spec.
// ============================================================================

/// uint8_t serializer (native mode only)
template <> struct Serializer<uint8_t> {
  static constexpr TypeId type_id = TypeId::UINT8;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(actual != static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(uint8_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data(value, ctx);
  }

  static inline void write_data(uint8_t value, WriteContext &ctx) {
    ctx.write_uint8(value);
  }

  static inline void write_data_generic(uint8_t value, WriteContext &ctx,
                                        bool) {
    write_data(value, ctx);
  }

  static inline uint8_t read(ReadContext &ctx, RefMode ref_mode,
                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return 0;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return ctx.read_uint8(ctx.error());
  }

  static inline uint8_t read_data(ReadContext &ctx) {
    return ctx.read_uint8(ctx.error());
  }

  static inline uint8_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline uint8_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                            const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// uint16_t serializer (native mode only)
template <> struct Serializer<uint16_t> {
  static constexpr TypeId type_id = TypeId::UINT16;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(actual != static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(uint16_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data(value, ctx);
  }

  static inline void write_data(uint16_t value, WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(uint16_t));
  }

  static inline void write_data_generic(uint16_t value, WriteContext &ctx,
                                        bool) {
    write_data(value, ctx);
  }

  static inline uint16_t read(ReadContext &ctx, RefMode ref_mode,
                              bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return 0;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return ctx.read_uint16(ctx.error());
  }

  static inline uint16_t read_data(ReadContext &ctx) {
    return ctx.read_uint16(ctx.error());
  }

  static inline uint16_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline uint16_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                             const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// uint32_t serializer - uses VAR_UINT32 to match Rust xlang mode
template <> struct Serializer<uint32_t> {
  static constexpr TypeId type_id = TypeId::VAR_UINT32;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(actual != static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(uint32_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data(value, ctx);
  }

  static inline void write_data(uint32_t value, WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(uint32_t));
  }

  static inline void write_data_generic(uint32_t value, WriteContext &ctx,
                                        bool) {
    write_data(value, ctx);
  }

  static inline uint32_t read(ReadContext &ctx, RefMode ref_mode,
                              bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return 0;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return ctx.read_uint32(ctx.error());
  }

  static inline uint32_t read_data(ReadContext &ctx) {
    return ctx.read_uint32(ctx.error());
  }

  static inline uint32_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline uint32_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                             const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// uint64_t serializer - uses VAR_UINT64 to match Rust xlang mode
template <> struct Serializer<uint64_t> {
  static constexpr TypeId type_id = TypeId::VAR_UINT64;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(actual != static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(uint64_t value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data(value, ctx);
  }

  static inline void write_data(uint64_t value, WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(uint64_t));
  }

  static inline void write_data_generic(uint64_t value, WriteContext &ctx,
                                        bool) {
    write_data(value, ctx);
  }

  static inline uint64_t read(ReadContext &ctx, RefMode ref_mode,
                              bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return 0;
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return ctx.read_uint64(ctx.error());
  }

  static inline uint64_t read_data(ReadContext &ctx) {
    return ctx.read_uint64(ctx.error());
  }

  static inline uint64_t read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline uint64_t read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                             const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

// ============================================================================
// Unsigned Integer Array Serializers (std::array, native mode only)
// ============================================================================

/// Serializer for std::array<uint8_t, N> (native mode only)
/// Note: uint8_t arrays use INT8_ARRAY (BINARY) type which is compatible
template <size_t N> struct Serializer<std::array<uint8_t, N>> {
  static constexpr TypeId type_id = TypeId::INT8_ARRAY;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(
            !type_id_matches(actual, static_cast<uint32_t>(type_id)))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::array<uint8_t, N> &arr, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data_generic(arr, ctx, has_generics);
  }

  static inline void write_data(const std::array<uint8_t, N> &arr,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    constexpr size_t max_size = 8 + N * sizeof(uint8_t);
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index += buffer.PutVarUint32(writer_index, static_cast<uint32_t>(N));
    if constexpr (N > 0) {
      buffer.UnsafePut(writer_index, arr.data(), N * sizeof(uint8_t));
    }
    buffer.WriterIndex(writer_index + N * sizeof(uint8_t));
  }

  static inline void write_data_generic(const std::array<uint8_t, N> &arr,
                                        WriteContext &ctx, bool) {
    write_data(arr, ctx);
  }

  static inline std::array<uint8_t, N> read(ReadContext &ctx, RefMode ref_mode,
                                            bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return std::array<uint8_t, N>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline std::array<uint8_t, N> read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(length != N || length * sizeof(uint8_t) >
                                              ctx.buffer().remaining_size())) {
      ctx.set_error(Error::invalid_data("Array size mismatch: expected " +
                                        std::to_string(N) + " but got " +
                                        std::to_string(length)));
      return std::array<uint8_t, N>();
    }
    std::array<uint8_t, N> arr;
    if constexpr (N > 0) {
      ctx.read_bytes(arr.data(), N * sizeof(uint8_t), ctx.error());
    }
    return arr;
  }

  static inline std::array<uint8_t, N>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// Serializer for std::array<uint16_t, N> (native mode only)
template <size_t N> struct Serializer<std::array<uint16_t, N>> {
  static constexpr TypeId type_id = TypeId::UINT16_ARRAY;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(
            !type_id_matches(actual, static_cast<uint32_t>(type_id)))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::array<uint16_t, N> &arr,
                           WriteContext &ctx, RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data_generic(arr, ctx, has_generics);
  }

  static inline void write_data(const std::array<uint16_t, N> &arr,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    constexpr size_t max_size = 8 + N * sizeof(uint16_t);
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index += buffer.PutVarUint32(writer_index, static_cast<uint32_t>(N));
    if constexpr (N > 0) {
      buffer.UnsafePut(writer_index, arr.data(), N * sizeof(uint16_t));
    }
    buffer.WriterIndex(writer_index + N * sizeof(uint16_t));
  }

  static inline void write_data_generic(const std::array<uint16_t, N> &arr,
                                        WriteContext &ctx, bool) {
    write_data(arr, ctx);
  }

  static inline std::array<uint16_t, N> read(ReadContext &ctx, RefMode ref_mode,
                                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return std::array<uint16_t, N>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline std::array<uint16_t, N> read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(length != N || length * sizeof(uint16_t) >
                                              ctx.buffer().remaining_size())) {
      ctx.set_error(Error::invalid_data("Array size mismatch: expected " +
                                        std::to_string(N) + " but got " +
                                        std::to_string(length)));
      return std::array<uint16_t, N>();
    }
    std::array<uint16_t, N> arr;
    if constexpr (N > 0) {
      ctx.read_bytes(arr.data(), N * sizeof(uint16_t), ctx.error());
    }
    return arr;
  }

  static inline std::array<uint16_t, N>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// Serializer for std::array<uint32_t, N> (native mode only)
template <size_t N> struct Serializer<std::array<uint32_t, N>> {
  static constexpr TypeId type_id = TypeId::UINT32_ARRAY;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(
            !type_id_matches(actual, static_cast<uint32_t>(type_id)))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::array<uint32_t, N> &arr,
                           WriteContext &ctx, RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data_generic(arr, ctx, has_generics);
  }

  static inline void write_data(const std::array<uint32_t, N> &arr,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    constexpr size_t max_size = 8 + N * sizeof(uint32_t);
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index += buffer.PutVarUint32(writer_index, static_cast<uint32_t>(N));
    if constexpr (N > 0) {
      buffer.UnsafePut(writer_index, arr.data(), N * sizeof(uint32_t));
    }
    buffer.WriterIndex(writer_index + N * sizeof(uint32_t));
  }

  static inline void write_data_generic(const std::array<uint32_t, N> &arr,
                                        WriteContext &ctx, bool) {
    write_data(arr, ctx);
  }

  static inline std::array<uint32_t, N> read(ReadContext &ctx, RefMode ref_mode,
                                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return std::array<uint32_t, N>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline std::array<uint32_t, N> read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(length != N || length * sizeof(uint32_t) >
                                              ctx.buffer().remaining_size())) {
      ctx.set_error(Error::invalid_data("Array size mismatch: expected " +
                                        std::to_string(N) + " but got " +
                                        std::to_string(length)));
      return std::array<uint32_t, N>();
    }
    std::array<uint32_t, N> arr;
    if constexpr (N > 0) {
      ctx.read_bytes(arr.data(), N * sizeof(uint32_t), ctx.error());
    }
    return arr;
  }

  static inline std::array<uint32_t, N>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// Serializer for std::array<uint64_t, N> (native mode only)
template <size_t N> struct Serializer<std::array<uint64_t, N>> {
  static constexpr TypeId type_id = TypeId::UINT64_ARRAY;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(
            !type_id_matches(actual, static_cast<uint32_t>(type_id)))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::array<uint64_t, N> &arr,
                           WriteContext &ctx, RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data_generic(arr, ctx, has_generics);
  }

  static inline void write_data(const std::array<uint64_t, N> &arr,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    constexpr size_t max_size = 8 + N * sizeof(uint64_t);
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index += buffer.PutVarUint32(writer_index, static_cast<uint32_t>(N));
    if constexpr (N > 0) {
      buffer.UnsafePut(writer_index, arr.data(), N * sizeof(uint64_t));
    }
    buffer.WriterIndex(writer_index + N * sizeof(uint64_t));
  }

  static inline void write_data_generic(const std::array<uint64_t, N> &arr,
                                        WriteContext &ctx, bool) {
    write_data(arr, ctx);
  }

  static inline std::array<uint64_t, N> read(ReadContext &ctx, RefMode ref_mode,
                                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return std::array<uint64_t, N>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline std::array<uint64_t, N> read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(length != N || length * sizeof(uint64_t) >
                                              ctx.buffer().remaining_size())) {
      ctx.set_error(Error::invalid_data("Array size mismatch: expected " +
                                        std::to_string(N) + " but got " +
                                        std::to_string(length)));
      return std::array<uint64_t, N>();
    }
    std::array<uint64_t, N> arr;
    if constexpr (N > 0) {
      ctx.read_bytes(arr.data(), N * sizeof(uint64_t), ctx.error());
    }
    return arr;
  }

  static inline std::array<uint64_t, N>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

// ============================================================================
// std::vector serializers for unsigned types (native mode only)
// ============================================================================

/// Serializer for std::vector<uint8_t> (binary data)
/// Note: uint8_t vectors use BINARY type
template <> struct Serializer<std::vector<uint8_t>> {
  static constexpr TypeId type_id = TypeId::BINARY;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(
            !type_id_matches(actual, static_cast<uint32_t>(type_id)))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::vector<uint8_t> &vec, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data_generic(vec, ctx, has_generics);
  }

  static inline void write_data(const std::vector<uint8_t> &vec,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    size_t max_size = 8 + vec.size();
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index +=
        buffer.PutVarUint32(writer_index, static_cast<uint32_t>(vec.size()));
    if (!vec.empty()) {
      buffer.UnsafePut(writer_index, vec.data(), vec.size());
    }
    buffer.WriterIndex(writer_index + static_cast<uint32_t>(vec.size()));
  }

  static inline void write_data_generic(const std::vector<uint8_t> &vec,
                                        WriteContext &ctx, bool) {
    write_data(vec, ctx);
  }

  static inline std::vector<uint8_t> read(ReadContext &ctx, RefMode ref_mode,
                                          bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return std::vector<uint8_t>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline std::vector<uint8_t> read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(length > ctx.buffer().remaining_size())) {
      ctx.set_error(
          Error::invalid_data("Invalid length: " + std::to_string(length)));
      return std::vector<uint8_t>();
    }
    std::vector<uint8_t> vec(length);
    if (length > 0) {
      ctx.read_bytes(vec.data(), length, ctx.error());
    }
    return vec;
  }

  static inline std::vector<uint8_t> read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline std::vector<uint8_t>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// Serializer for std::vector<uint16_t> (native mode only)
template <> struct Serializer<std::vector<uint16_t>> {
  static constexpr TypeId type_id = TypeId::UINT16_ARRAY;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(
            !type_id_matches(actual, static_cast<uint32_t>(type_id)))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::vector<uint16_t> &vec, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data_generic(vec, ctx, has_generics);
  }

  static inline void write_data(const std::vector<uint16_t> &vec,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    size_t data_size = vec.size() * sizeof(uint16_t);
    size_t max_size = 8 + data_size;
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index +=
        buffer.PutVarUint32(writer_index, static_cast<uint32_t>(vec.size()));
    if (!vec.empty()) {
      buffer.UnsafePut(writer_index, vec.data(), data_size);
    }
    buffer.WriterIndex(writer_index + static_cast<uint32_t>(data_size));
  }

  static inline void write_data_generic(const std::vector<uint16_t> &vec,
                                        WriteContext &ctx, bool) {
    write_data(vec, ctx);
  }

  static inline std::vector<uint16_t> read(ReadContext &ctx, RefMode ref_mode,
                                           bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return std::vector<uint16_t>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline std::vector<uint16_t> read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(length * sizeof(uint16_t) >
                           ctx.buffer().remaining_size())) {
      ctx.set_error(
          Error::invalid_data("Invalid length: " + std::to_string(length)));
      return std::vector<uint16_t>();
    }
    std::vector<uint16_t> vec(length);
    if (length > 0) {
      ctx.read_bytes(vec.data(), length * sizeof(uint16_t), ctx.error());
    }
    return vec;
  }

  static inline std::vector<uint16_t> read_data_generic(ReadContext &ctx,
                                                        bool) {
    return read_data(ctx);
  }

  static inline std::vector<uint16_t>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// Serializer for std::vector<uint32_t> (native mode only)
template <> struct Serializer<std::vector<uint32_t>> {
  static constexpr TypeId type_id = TypeId::UINT32_ARRAY;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(
            !type_id_matches(actual, static_cast<uint32_t>(type_id)))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::vector<uint32_t> &vec, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data_generic(vec, ctx, has_generics);
  }

  static inline void write_data(const std::vector<uint32_t> &vec,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    size_t data_size = vec.size() * sizeof(uint32_t);
    size_t max_size = 8 + data_size;
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index +=
        buffer.PutVarUint32(writer_index, static_cast<uint32_t>(vec.size()));
    if (!vec.empty()) {
      buffer.UnsafePut(writer_index, vec.data(), data_size);
    }
    buffer.WriterIndex(writer_index + static_cast<uint32_t>(data_size));
  }

  static inline void write_data_generic(const std::vector<uint32_t> &vec,
                                        WriteContext &ctx, bool) {
    write_data(vec, ctx);
  }

  static inline std::vector<uint32_t> read(ReadContext &ctx, RefMode ref_mode,
                                           bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return std::vector<uint32_t>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline std::vector<uint32_t> read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(length * sizeof(uint32_t) >
                           ctx.buffer().remaining_size())) {
      ctx.set_error(
          Error::invalid_data("Invalid length: " + std::to_string(length)));
      return std::vector<uint32_t>();
    }
    std::vector<uint32_t> vec(length);
    if (length > 0) {
      ctx.read_bytes(vec.data(), length * sizeof(uint32_t), ctx.error());
    }
    return vec;
  }

  static inline std::vector<uint32_t> read_data_generic(ReadContext &ctx,
                                                        bool) {
    return read_data(ctx);
  }

  static inline std::vector<uint32_t>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

/// Serializer for std::vector<uint64_t> (native mode only)
template <> struct Serializer<std::vector<uint64_t>> {
  static constexpr TypeId type_id = TypeId::UINT64_ARRAY;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(
            !type_id_matches(actual, static_cast<uint32_t>(type_id)))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::vector<uint64_t> &vec, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data_generic(vec, ctx, has_generics);
  }

  static inline void write_data(const std::vector<uint64_t> &vec,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    size_t data_size = vec.size() * sizeof(uint64_t);
    size_t max_size = 8 + data_size;
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index +=
        buffer.PutVarUint32(writer_index, static_cast<uint32_t>(vec.size()));
    if (!vec.empty()) {
      buffer.UnsafePut(writer_index, vec.data(), data_size);
    }
    buffer.WriterIndex(writer_index + static_cast<uint32_t>(data_size));
  }

  static inline void write_data_generic(const std::vector<uint64_t> &vec,
                                        WriteContext &ctx, bool) {
    write_data(vec, ctx);
  }

  static inline std::vector<uint64_t> read(ReadContext &ctx, RefMode ref_mode,
                                           bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return std::vector<uint64_t>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(type_id_read != static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline std::vector<uint64_t> read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(length * sizeof(uint64_t) >
                           ctx.buffer().remaining_size())) {
      ctx.set_error(
          Error::invalid_data("Invalid length: " + std::to_string(length)));
      return std::vector<uint64_t>();
    }
    std::vector<uint64_t> vec(length);
    if (length > 0) {
      ctx.read_bytes(vec.data(), length * sizeof(uint64_t), ctx.error());
    }
    return vec;
  }

  static inline std::vector<uint64_t> read_data_generic(ReadContext &ctx,
                                                        bool) {
    return read_data(ctx);
  }

  static inline std::vector<uint64_t>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
