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
#include "fory/util/string_util.h"
#include <cstdint>
#include <limits>
#include <string>
#include <string_view>
#include <vector>

namespace fory {
namespace serialization {

// String encoding types as per xlang spec
enum class StringEncoding : uint8_t {
  LATIN1 = 0, // Latin1/ISO-8859-1
  UTF16 = 1,  // UTF-16
  UTF8 = 2,   // UTF-8
};

// ============================================================================
// Internal helper functions for string serialization
// ============================================================================

namespace detail {

/// write string data with UTF-8 encoding
inline void write_string_data(const char *data, size_t size,
                              WriteContext &ctx) {
  // Always use UTF-8 encoding for cross-language compatibility.
  // Per xlang spec: write size shifted left by 2 bits, with encoding
  // (UTF8) in the lower 2 bits. Use varuint36small encoding.
  uint64_t length = static_cast<uint64_t>(size);
  uint64_t size_with_encoding =
      (length << 2) | static_cast<uint64_t>(StringEncoding::UTF8);
  ctx.write_var_uint36_small(size_with_encoding);

  // write string bytes
  if (size > 0) {
    ctx.write_bytes(data, size);
  }
}

/// write UTF-16 string data, converting to UTF-8 or using native encoding
inline void write_u16string_data(const char16_t *data, size_t size,
                                 WriteContext &ctx) {
  if (size == 0) {
    // Empty string: write zero length with UTF8 encoding
    ctx.write_var_uint36_small(static_cast<uint64_t>(StringEncoding::UTF8));
    return;
  }

  const uint16_t *u16_data = reinterpret_cast<const uint16_t *>(data);

  // Check if string can be encoded as Latin1 (more compact)
  if (is_latin1(u16_data, size)) {
    // encode as Latin1 for compactness
    uint64_t size_with_encoding = (static_cast<uint64_t>(size) << 2) |
                                  static_cast<uint64_t>(StringEncoding::LATIN1);
    ctx.write_var_uint36_small(size_with_encoding);

    // write each char16_t as a single byte
    for (size_t i = 0; i < size; ++i) {
      ctx.write_uint8(static_cast<uint8_t>(data[i]));
    }
  } else {
    // Convert to UTF-8
    std::string utf8 = utf16_to_utf8(u16_data, size);
    uint64_t size_with_encoding = (static_cast<uint64_t>(utf8.size()) << 2) |
                                  static_cast<uint64_t>(StringEncoding::UTF8);
    ctx.write_var_uint36_small(size_with_encoding);
    if (!utf8.empty()) {
      ctx.write_bytes(utf8.data(), utf8.size());
    }
  }
}

/// Read string data and return as std::string
inline std::string read_string_data(ReadContext &ctx) {
  // Read size with encoding using varuint36small
  uint64_t size_with_encoding = ctx.read_var_uint36_small(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return std::string();
  }

  // Extract size and encoding from lower 2 bits
  uint64_t length = size_with_encoding >> 2;
  StringEncoding encoding =
      static_cast<StringEncoding>(size_with_encoding & 0x3);

  if (length == 0) {
    return std::string();
  }

  if (FORY_PREDICT_FALSE(!ctx.buffer().ensure_readable(length, ctx.error()))) {
    return std::string();
  }

  // Handle different encodings
  switch (encoding) {
  case StringEncoding::LATIN1: {
    std::vector<uint8_t> bytes(length);
    ctx.read_bytes(bytes.data(), length, ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::string();
    }
    return latin1_to_utf8(bytes.data(), length);
  }
  case StringEncoding::UTF16: {
    if (length % 2 != 0) {
      ctx.set_error(Error::invalid_data("UTF-16 length must be even"));
      return std::string();
    }
    std::vector<uint16_t> utf16_chars(length / 2);
    ctx.read_bytes(reinterpret_cast<uint8_t *>(utf16_chars.data()), length,
                   ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::string();
    }
    return utf16_to_utf8(utf16_chars.data(), utf16_chars.size());
  }
  case StringEncoding::UTF8: {
    // UTF-8: read bytes directly
    std::string result(length, '\0');
    ctx.read_bytes(&result[0], length, ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::string();
    }
    return result;
  }
  default:
    ctx.set_error(
        Error::encoding_error("Unknown string encoding: " +
                              std::to_string(static_cast<int>(encoding))));
    return std::string();
  }
}

/// Read string data and return as std::u16string
inline std::u16string read_u16string_data(ReadContext &ctx) {
  // Read size with encoding using varuint36small
  uint64_t size_with_encoding = ctx.read_var_uint36_small(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return std::u16string();
  }

  // Extract size and encoding from lower 2 bits
  uint64_t length = size_with_encoding >> 2;
  StringEncoding encoding =
      static_cast<StringEncoding>(size_with_encoding & 0x3);

  if (length == 0) {
    return std::u16string();
  }

  if (FORY_PREDICT_FALSE(!ctx.buffer().ensure_readable(length, ctx.error()))) {
    return std::u16string();
  }

  // Handle different encodings
  switch (encoding) {
  case StringEncoding::LATIN1: {
    // Latin1 bytes map directly to char16_t (codepoints 0-255)
    std::u16string result(length, u'\0');
    for (size_t i = 0; i < length; ++i) {
      result[i] = static_cast<char16_t>(ctx.read_uint8(ctx.error()));
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::u16string();
      }
    }
    return result;
  }
  case StringEncoding::UTF16: {
    if (length % 2 != 0) {
      ctx.set_error(Error::invalid_data("UTF-16 length must be even"));
      return std::u16string();
    }
    std::u16string result(length / 2, u'\0');
    ctx.read_bytes(reinterpret_cast<uint8_t *>(&result[0]), length,
                   ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::u16string();
    }
    return result;
  }
  case StringEncoding::UTF8: {
    // Read UTF-8 bytes and convert to UTF-16
    std::string utf8(length, '\0');
    ctx.read_bytes(&utf8[0], length, ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::u16string();
    }
    return utf8_to_utf16(utf8, true /* little endian */);
  }
  default:
    ctx.set_error(
        Error::encoding_error("Unknown string encoding: " +
                              std::to_string(static_cast<int>(encoding))));
    return std::u16string();
  }
}

} // namespace detail

// ============================================================================
// std::string Serializer
// ============================================================================

/// std::string serializer using UTF-8 encoding per xlang spec
template <> struct Serializer<std::string> {
  static constexpr TypeId type_id = TypeId::STRING;

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

  static inline void write(const std::string &value, WriteContext &ctx,
                           RefMode ref_mode, bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(const std::string &value, WriteContext &ctx) {
    detail::write_string_data(value.data(), value.size(), ctx);
  }

  static inline void write_data_generic(const std::string &value,
                                        WriteContext &ctx, bool) {
    write_data(value, ctx);
  }

  static inline std::string read(ReadContext &ctx, RefMode ref_mode,
                                 bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::string();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::string();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::string();
      }
    }
    return read_data(ctx);
  }

  static inline std::string read_data(ReadContext &ctx) {
    return detail::read_string_data(ctx);
  }

  static inline std::string read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline std::string
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

// ============================================================================
// std::string_view Serializer (write-only, reads as std::string)
// ============================================================================

/// std::string_view serializer - write-only for zero-copy serialization
/// Note: Deserialization returns std::string since string_view requires
/// stable backing storage
template <> struct Serializer<std::string_view> {
  static constexpr TypeId type_id = TypeId::STRING;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void write(std::string_view value, WriteContext &ctx,
                           RefMode ref_mode, bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(std::string_view value, WriteContext &ctx) {
    detail::write_string_data(value.data(), value.size(), ctx);
  }

  static inline void write_data_generic(std::string_view value,
                                        WriteContext &ctx, bool) {
    write_data(value, ctx);
  }
};

// ============================================================================
// std::u16string Serializer
// ============================================================================

/// std::u16string serializer with UTF-16 support
template <> struct Serializer<std::u16string> {
  static constexpr TypeId type_id = TypeId::STRING;

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

  static inline void write(const std::u16string &value, WriteContext &ctx,
                           RefMode ref_mode, bool write_type, bool = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data(value, ctx);
  }

  static inline void write_data(const std::u16string &value,
                                WriteContext &ctx) {
    detail::write_u16string_data(value.data(), value.size(), ctx);
  }

  static inline void write_data_generic(const std::u16string &value,
                                        WriteContext &ctx, bool) {
    write_data(value, ctx);
  }

  static inline std::u16string read(ReadContext &ctx, RefMode ref_mode,
                                    bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::u16string();
    }
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::u16string();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::u16string();
      }
    }
    return read_data(ctx);
  }

  static inline std::u16string read_data(ReadContext &ctx) {
    return detail::read_u16string_data(ctx);
  }

  static inline std::u16string read_data_generic(ReadContext &ctx, bool) {
    return read_data(ctx);
  }

  static inline std::u16string
  read_with_type_info(ReadContext &ctx, RefMode ref_mode, const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
