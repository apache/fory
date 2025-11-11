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
#include "fory/util/result.h"
#include "fory/util/string_util.h"
#include <cstdint>
#include <cstring>
#include <string>
#include <type_traits>

namespace fory {
namespace serialization {

// ============================================================================
// Primitive Type Serializers
// ============================================================================

/// Boolean serializer
template <> struct Serializer<bool> {
  static constexpr TypeId type_id = TypeId::BOOL;

  /// Write boolean with optional reference and type info
  static inline Result<void, Error> write(bool value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_uint8(value ? 1 : 0);
    return Result<void, Error>();
  }

  /// Write boolean data only (no type info)
  static inline Result<void, Error> write_data(bool value, WriteContext &ctx) {
    ctx.write_uint8(value ? 1 : 0);
    return Result<void, Error>();
  }

  /// Write boolean with generic optimization (unused for primitives)
  static inline Result<void, Error>
  write_data_generic(bool value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  /// Read boolean with optional reference and type info
  static inline Result<bool, Error> read(ReadContext &ctx, bool read_ref,
                                         bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return false;
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    FORY_TRY(value, ctx.read_uint8());
    return value != 0;
  }

  /// Read boolean data only (no type info)
  static inline Result<bool, Error> read_data(ReadContext &ctx) {
    FORY_TRY(value, ctx.read_uint8());
    return value != 0;
  }

  /// Read boolean with generic optimization (unused for primitives)
  static inline Result<bool, Error> read_data_generic(ReadContext &ctx,
                                                      bool has_generics) {
    return read_data(ctx);
  }

  /// Read boolean with type info (type info already validated)
  static inline Result<bool, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    // Type info already validated, skip redundant type read
    return read(ctx, read_ref, false); // read_type=false
  }
};

/// int8_t serializer
template <> struct Serializer<int8_t> {
  static constexpr TypeId type_id = TypeId::INT8;

  static inline Result<void, Error> write(int8_t value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_int8(value);
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(int8_t value,
                                               WriteContext &ctx) {
    ctx.write_int8(value);
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(int8_t value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<int8_t, Error> read(ReadContext &ctx, bool read_ref,
                                           bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return static_cast<int8_t>(0);
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    return ctx.read_int8();
  }

  static inline Result<int8_t, Error> read_data(ReadContext &ctx) {
    return ctx.read_int8();
  }

  static inline Result<int8_t, Error> read_data_generic(ReadContext &ctx,
                                                        bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<int8_t, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

/// int16_t serializer
template <> struct Serializer<int16_t> {
  static constexpr TypeId type_id = TypeId::INT16;

  static inline Result<void, Error> write(int16_t value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_bytes(&value, sizeof(int16_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(int16_t value,
                                               WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(int16_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(int16_t value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<int16_t, Error> read(ReadContext &ctx, bool read_ref,
                                            bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return static_cast<int16_t>(0);
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    int16_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(int16_t)));
    return value;
  }

  static inline Result<int16_t, Error> read_data(ReadContext &ctx) {
    int16_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(int16_t)));
    return value;
  }

  static inline Result<int16_t, Error> read_data_generic(ReadContext &ctx,
                                                         bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<int16_t, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

/// int32_t serializer
template <> struct Serializer<int32_t> {
  static constexpr TypeId type_id = TypeId::INT32;

  static inline Result<void, Error> write(int32_t value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_bytes(&value, sizeof(int32_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(int32_t value,
                                               WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(int32_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(int32_t value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<int32_t, Error> read(ReadContext &ctx, bool read_ref,
                                            bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return static_cast<int32_t>(0);
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    int32_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(int32_t)));
    return value;
  }

  static inline Result<int32_t, Error> read_data(ReadContext &ctx) {
    int32_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(int32_t)));
    return value;
  }

  static inline Result<int32_t, Error> read_data_generic(ReadContext &ctx,
                                                         bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<int32_t, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

/// int64_t serializer
template <> struct Serializer<int64_t> {
  static constexpr TypeId type_id = TypeId::INT64;

  static inline Result<void, Error> write(int64_t value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_bytes(&value, sizeof(int64_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(int64_t value,
                                               WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(int64_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(int64_t value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<int64_t, Error> read(ReadContext &ctx, bool read_ref,
                                            bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return static_cast<int64_t>(0);
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    int64_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(int64_t)));
    return value;
  }

  static inline Result<int64_t, Error> read_data(ReadContext &ctx) {
    int64_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(int64_t)));
    return value;
  }

  static inline Result<int64_t, Error> read_data_generic(ReadContext &ctx,
                                                         bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<int64_t, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

/// float serializer
template <> struct Serializer<float> {
  static constexpr TypeId type_id = TypeId::FLOAT32;

  static inline Result<void, Error> write(float value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_bytes(&value, sizeof(float));
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(float value, WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(float));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(float value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<float, Error> read(ReadContext &ctx, bool read_ref,
                                          bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return 0.0f;
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    float value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(float)));
    return value;
  }

  static inline Result<float, Error> read_data(ReadContext &ctx) {
    float value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(float)));
    return value;
  }

  static inline Result<float, Error> read_data_generic(ReadContext &ctx,
                                                       bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<float, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

/// double serializer
template <> struct Serializer<double> {
  static constexpr TypeId type_id = TypeId::FLOAT64;

  static inline Result<void, Error> write(double value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_bytes(&value, sizeof(double));
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(double value,
                                               WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(double));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(double value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<double, Error> read(ReadContext &ctx, bool read_ref,
                                           bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return 0.0;
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    double value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(double)));
    return value;
  }

  static inline Result<double, Error> read_data(ReadContext &ctx) {
    double value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(double)));
    return value;
  }

  static inline Result<double, Error> read_data_generic(ReadContext &ctx,
                                                        bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<double, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

// ============================================================================
// Unsigned Integer Type Serializers
// ============================================================================

/// uint8_t serializer
template <> struct Serializer<uint8_t> {
  static constexpr TypeId type_id = TypeId::INT8; // Same as int8

  static inline Result<void, Error> write(uint8_t value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_uint8(value);
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(uint8_t value,
                                               WriteContext &ctx) {
    ctx.write_uint8(value);
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(uint8_t value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<uint8_t, Error> read(ReadContext &ctx, bool read_ref,
                                            bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return static_cast<uint8_t>(0);
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    return ctx.read_uint8();
  }

  static inline Result<uint8_t, Error> read_data(ReadContext &ctx) {
    return ctx.read_uint8();
  }

  static inline Result<uint8_t, Error> read_data_generic(ReadContext &ctx,
                                                         bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<uint8_t, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

/// uint16_t serializer
template <> struct Serializer<uint16_t> {
  static constexpr TypeId type_id = TypeId::INT16;

  static inline Result<void, Error> write(uint16_t value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_bytes(&value, sizeof(uint16_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(uint16_t value,
                                               WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(uint16_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(uint16_t value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<uint16_t, Error> read(ReadContext &ctx, bool read_ref,
                                             bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return static_cast<uint16_t>(0);
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    uint16_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(uint16_t)));
    return value;
  }

  static inline Result<uint16_t, Error> read_data(ReadContext &ctx) {
    uint16_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(uint16_t)));
    return value;
  }

  static inline Result<uint16_t, Error> read_data_generic(ReadContext &ctx,
                                                          bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<uint16_t, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

/// uint32_t serializer
template <> struct Serializer<uint32_t> {
  static constexpr TypeId type_id = TypeId::INT32;

  static inline Result<void, Error> write(uint32_t value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_bytes(&value, sizeof(uint32_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(uint32_t value,
                                               WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(uint32_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(uint32_t value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<uint32_t, Error> read(ReadContext &ctx, bool read_ref,
                                             bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return static_cast<uint32_t>(0);
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    uint32_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(uint32_t)));
    return value;
  }

  static inline Result<uint32_t, Error> read_data(ReadContext &ctx) {
    uint32_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(uint32_t)));
    return value;
  }

  static inline Result<uint32_t, Error> read_data_generic(ReadContext &ctx,
                                                          bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<uint32_t, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

/// uint64_t serializer
template <> struct Serializer<uint64_t> {
  static constexpr TypeId type_id = TypeId::INT64;

  static inline Result<void, Error> write(uint64_t value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    ctx.write_bytes(&value, sizeof(uint64_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(uint64_t value,
                                               WriteContext &ctx) {
    ctx.write_bytes(&value, sizeof(uint64_t));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(uint64_t value, WriteContext &ctx, bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<uint64_t, Error> read(ReadContext &ctx, bool read_ref,
                                             bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return static_cast<uint64_t>(0);
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    uint64_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(uint64_t)));
    return value;
  }

  static inline Result<uint64_t, Error> read_data(ReadContext &ctx) {
    uint64_t value;
    FORY_RETURN_NOT_OK(ctx.read_bytes(&value, sizeof(uint64_t)));
    return value;
  }

  static inline Result<uint64_t, Error> read_data_generic(ReadContext &ctx,
                                                          bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<uint64_t, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

// ============================================================================
// String Serializer
// ============================================================================

/// std::string serializer with encoding detection per xlang spec
template <> struct Serializer<std::string> {
  static constexpr TypeId type_id = TypeId::STRING;

  // String encoding types as per xlang spec
  enum class StringEncoding : uint8_t {
    LATIN1 = 0, // Latin1/ISO-8859-1
    UTF16 = 1,  // UTF-16
    UTF8 = 2,   // UTF-8
  };

  /// Detect string encoding (simplified - assumes UTF-8 for now)
  static inline StringEncoding detect_encoding(const std::string &value) {
    // Simple heuristic: check if all characters are ASCII (< 128)
    bool is_latin1 = true;
    for (char c : value) {
      if (static_cast<unsigned char>(c) >= 128) {
        is_latin1 = false;
        break;
      }
    }
    // For simplicity, use LATIN1 for ASCII, UTF8 otherwise
    return is_latin1 ? StringEncoding::LATIN1 : StringEncoding::UTF8;
  }

  static inline Result<void, Error> write(const std::string &value,
                                          WriteContext &ctx, bool write_ref,
                                          bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    return write_data(value, ctx);
  }

  static inline Result<void, Error> write_data(const std::string &value,
                                               WriteContext &ctx) {
    // Detect encoding
    StringEncoding encoding = detect_encoding(value);

    // Per xlang spec: write size shifted left by 2 bits, with encoding in
    // lower 2 bits Note: Using varuint32 instead of varuint64 for practical
    // size limits
    uint32_t size_with_encoding = (static_cast<uint32_t>(value.size()) << 2) |
                                  static_cast<uint32_t>(encoding);
    ctx.write_varuint32(size_with_encoding);

    // Write string bytes
    if (!value.empty()) {
      ctx.write_bytes(value.data(), value.size());
    }
    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data_generic(const std::string &value,
                                                       WriteContext &ctx,
                                                       bool has_generics) {
    return write_data(value, ctx);
  }

  static inline Result<std::string, Error> read(ReadContext &ctx, bool read_ref,
                                                bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::string();
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

  static inline Result<std::string, Error> read_data(ReadContext &ctx) {
    // Read size with encoding
    FORY_TRY(size_with_encoding, ctx.read_varuint32());

    // Extract size and encoding from lower 2 bits
    uint32_t length = size_with_encoding >> 2;
    // Encoding available but unused for now - all strings treated as raw bytes
    // In full implementation, would convert UTF-16 to UTF-8 etc.
    // StringEncoding encoding = static_cast<StringEncoding>(size_with_encoding
    // & 0x3);

    if (length == 0) {
      return std::string();
    }

    // Read string bytes
    std::string result(length, '\0');
    FORY_RETURN_NOT_OK(ctx.read_bytes(&result[0], length));

    return result;
  }

  static inline Result<std::string, Error>
  read_data_generic(ReadContext &ctx, bool has_generics) {
    return read_data(ctx);
  }

  static inline Result<std::string, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    return read(ctx, read_ref, false);
  }
};

} // namespace serialization
} // namespace fory
