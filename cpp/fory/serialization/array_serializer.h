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
#include "fory/util/bit_util.h"
#include <array>

namespace fory {
namespace serialization {

// ============================================================================
// Primitive Array Serializers (std::array only)
// ============================================================================
// Note: std::vector is handled by container_serializers.h

/// Serializer for std::array<T, N> of primitives (non-bool)
/// Per xlang spec, primitive arrays are serialized as:
/// | unsigned varint: length | raw binary data |
template <typename T, size_t N>
struct Serializer<
    std::array<T, N>,
    std::enable_if_t<std::is_arithmetic_v<T> && !std::is_same_v<T, bool>>> {
  // Map C++ type to array TypeId
  static constexpr TypeId type_id = []() {
    if constexpr (std::is_same_v<T, int8_t> || std::is_same_v<T, uint8_t>) {
      return TypeId::INT8_ARRAY;
    } else if constexpr (std::is_same_v<T, int16_t> ||
                         std::is_same_v<T, uint16_t>) {
      return TypeId::INT16_ARRAY;
    } else if constexpr (std::is_same_v<T, int32_t> ||
                         std::is_same_v<T, uint32_t>) {
      return TypeId::INT32_ARRAY;
    } else if constexpr (std::is_same_v<T, int64_t> ||
                         std::is_same_v<T, uint64_t>) {
      return TypeId::INT64_ARRAY;
    } else if constexpr (std::is_same_v<T, float>) {
      return TypeId::FLOAT32_ARRAY;
    } else if constexpr (std::is_same_v<T, double>) {
      return TypeId::FLOAT64_ARRAY;
    } else {
      return TypeId::ARRAY; // Generic array
    }
  }();

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

  static inline void write(const std::array<T, N> &arr, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data_generic(arr, ctx, has_generics);
  }

  static inline void write_data(const std::array<T, N> &arr,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    // bulk write may write 8 bytes for varint32
    constexpr size_t max_size = 8 + N * sizeof(T);
    buffer.grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    // write array length in bytes
    writer_index += buffer.put_var_uint32(writer_index,
                                          static_cast<uint32_t>(N * sizeof(T)));

    // write data
    if constexpr (N > 0) {
      if constexpr (FORY_LITTLE_ENDIAN || sizeof(T) == 1) {
        // Fast path: direct memory copy on little-endian or for single-byte
        // types
        buffer.unsafe_put(writer_index, arr.data(), N * sizeof(T));
      } else {
        // Slow path: element-by-element write on big-endian machines
        for (size_t i = 0; i < N; ++i) {
          T value = util::to_little_endian(arr[i]);
          buffer.unsafe_put(writer_index + i * sizeof(T), &value, sizeof(T));
        }
      }
    }
    buffer.writer_index(writer_index + N * sizeof(T));
  }

  static inline void write_data_generic(const std::array<T, N> &arr,
                                        WriteContext &ctx, bool has_generics) {
    write_data(arr, ctx);
  }

  static inline std::array<T, N> read(ReadContext &ctx, RefMode ref_mode,
                                      bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::array<T, N>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::array<T, N>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::array<T, N>();
      }
    }
    return read_data(ctx);
  }

  static inline std::array<T, N> read_data(ReadContext &ctx) {
    // Read array length in bytes
    uint32_t size_bytes = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::array<T, N>();
    }

    uint32_t length = size_bytes / sizeof(T);
    if (length != N) {
      ctx.set_error(Error::invalid_data("Array size mismatch: expected " +
                                        std::to_string(N) + " but got " +
                                        std::to_string(length)));
      return std::array<T, N>();
    }

    std::array<T, N> arr;
    if constexpr (N > 0) {
      if constexpr (FORY_LITTLE_ENDIAN || sizeof(T) == 1) {
        // Fast path: direct memory copy on little-endian or for single-byte
        // types
        ctx.read_bytes(arr.data(), N * sizeof(T), ctx.error());
      } else {
        // Slow path: element-by-element read on big-endian machines
        for (size_t i = 0; i < N; ++i) {
          T value;
          ctx.read_bytes(&value, sizeof(T), ctx.error());
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            return arr;
          }
          arr[i] =
              util::to_little_endian(value); // to_little_endian swaps on BE
        }
      }
    }
    return arr;
  }

  static inline std::array<T, N>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

/// Serializer for std::array<bool, N>
/// Boolean arrays need special handling due to bool size differences
template <size_t N> struct Serializer<std::array<bool, N>> {
  static constexpr TypeId type_id = TypeId::BOOL_ARRAY;

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

  static inline void write(const std::array<bool, N> &arr, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data_generic(arr, ctx, has_generics);
  }

  static inline void write_data(const std::array<bool, N> &arr,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    // bulk write may write 8 bytes for varint32
    constexpr size_t max_size = 8 + N;
    buffer.grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    // write array length
    writer_index +=
        buffer.put_var_uint32(writer_index, static_cast<uint32_t>(N));

    // write each boolean as a byte
    for (size_t i = 0; i < N; ++i) {
      buffer.unsafe_put_byte(writer_index + i,
                             static_cast<uint8_t>(arr[i] ? 1 : 0));
    }
    buffer.writer_index(writer_index + N);
  }

  static inline void write_data_generic(const std::array<bool, N> &arr,
                                        WriteContext &ctx, bool has_generics) {
    write_data(arr, ctx);
  }

  static inline std::array<bool, N> read(ReadContext &ctx, RefMode ref_mode,
                                         bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::array<bool, N>();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::array<bool, N>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::array<bool, N>();
      }
    }
    return read_data(ctx);
  }

  static inline std::array<bool, N> read_data(ReadContext &ctx) {
    // Read array length
    uint32_t length = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::array<bool, N>();
    }
    if (length != N) {
      ctx.set_error(Error::invalid_data("Array size mismatch: expected " +
                                        std::to_string(N) + " but got " +
                                        std::to_string(length)));
      return std::array<bool, N>();
    }
    std::array<bool, N> arr;
    for (size_t i = 0; i < N; ++i) {
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return arr;
      }
      uint8_t byte = ctx.read_uint8(ctx.error());
      arr[i] = (byte != 0);
    }
    return arr;
  }

  static inline std::array<bool, N>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
