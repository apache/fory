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

  static Result<void, Error> write(const std::array<T, N> &arr,
                                   WriteContext &ctx, bool write_ref,
                                   bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    return write_data(arr, ctx);
  }

  static Result<void, Error> write_data(const std::array<T, N> &arr,
                                        WriteContext &ctx) {
    // Write array length
    ctx.write_varuint32(static_cast<uint32_t>(N));

    // Write raw binary data
    if constexpr (N > 0) {
      ctx.write_bytes(arr.data(), N * sizeof(T));
    }
    return Result<void, Error>();
  }

  static Result<void, Error> write_data_generic(const std::array<T, N> &arr,
                                                WriteContext &ctx,
                                                bool has_generics) {
    return write_data(arr, ctx);
  }

  static Result<std::array<T, N>, Error> read(ReadContext &ctx, bool read_ref,
                                              bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::array<T, N>();
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static Result<std::array<T, N>, Error> read_data(ReadContext &ctx) {
    // Read array length
    FORY_TRY(length, ctx.read_varuint32());

    if (length != N) {
      return Unexpected(Error::invalid_data("Array size mismatch: expected " +
                                            std::to_string(N) + " but got " +
                                            std::to_string(length)));
    }

    std::array<T, N> arr;
    if constexpr (N > 0) {
      FORY_RETURN_NOT_OK(ctx.read_bytes(arr.data(), N * sizeof(T)));
    }
    return arr;
  }
};

/// Serializer for std::array<bool, N>
/// Boolean arrays need special handling due to bool size differences
template <size_t N> struct Serializer<std::array<bool, N>> {
  static constexpr TypeId type_id = TypeId::BOOL_ARRAY;

  static Result<void, Error> write(const std::array<bool, N> &arr,
                                   WriteContext &ctx, bool write_ref,
                                   bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    return write_data(arr, ctx);
  }

  static Result<void, Error> write_data(const std::array<bool, N> &arr,
                                        WriteContext &ctx) {
    // Write array length
    ctx.write_varuint32(static_cast<uint32_t>(N));

    // Write each boolean as a byte (per spec, bool is serialized as int16,
    // but for arrays we use packed bytes for efficiency)
    for (size_t i = 0; i < N; ++i) {
      ctx.write_uint8(arr[i] ? 1 : 0);
    }
    return Result<void, Error>();
  }

  static Result<void, Error> write_data_generic(const std::array<bool, N> &arr,
                                                WriteContext &ctx,
                                                bool has_generics) {
    return write_data(arr, ctx);
  }

  static Result<std::array<bool, N>, Error>
  read(ReadContext &ctx, bool read_ref, bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::array<bool, N>();
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static Result<std::array<bool, N>, Error> read_data(ReadContext &ctx) {
    // Read array length
    FORY_TRY(length, ctx.read_varuint32());
    if (length != N) {
      return Unexpected(Error::invalid_data("Array size mismatch: expected " +
                                            std::to_string(N) + " but got " +
                                            std::to_string(length)));
    }
    std::array<bool, N> arr;
    for (size_t i = 0; i < N; ++i) {
      FORY_TRY(byte, ctx.read_uint8());
      arr[i] = (byte != 0);
    }
    return arr;
  }
};

} // namespace serialization
} // namespace fory
