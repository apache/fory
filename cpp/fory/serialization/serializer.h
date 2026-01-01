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

#include "fory/meta/type_traits.h"
#include "fory/serialization/context.h"
#include "fory/serialization/ref_mode.h"
#include "fory/serialization/ref_resolver.h"
#include "fory/serialization/serializer_traits.h"
#include "fory/type/type.h"
#include "fory/util/buffer.h"
#include "fory/util/error.h"
#include "fory/util/result.h"
#include <cstdint>
#include <string>

namespace fory {
namespace serialization {

// ============================================================================
// Error Handling Macros for Serialization
// ============================================================================

/// Return early if the error pointer indicates an error.
/// Use this macro when reading struct fields with the Error* pattern.
/// The macro checks the error state and returns an Unexpected with the error.
///
/// Example usage:
/// ```cpp
/// Error error;
/// int32_t value = buffer.ReadVarInt32(error);
/// FORY_RETURN_IF_SERDE_ERROR(error);
/// // Use value...
/// ```
#define FORY_RETURN_IF_SERDE_ERROR(error_ptr)                                  \
  do {                                                                         \
    if (FORY_PREDICT_FALSE(!(error_ptr)->ok())) {                              \
      return ::fory::Unexpected(std::move(*(error_ptr)));                      \
    }                                                                          \
  } while (0)

// ============================================================================
// Protocol Constants
// ============================================================================

/// Fory protocol magic number (0x62d4)
constexpr uint16_t MAGIC_NUMBER = 0x62d4;

/// Language identifiers
/// Must match Java's Language enum ordinal values
enum class Language : uint8_t {
  XLANG = 0,
  JAVA = 1,
  PYTHON = 2,
  CPP = 3,
  GO = 4,
  JAVASCRIPT = 5,
  RUST = 6,
  DART = 7,
  SCALA = 8,
  KOTLIN = 9,
};

/// Detect if system is little endian
inline bool is_little_endian_system() {
  uint32_t test = 1;
  return *reinterpret_cast<uint8_t *>(&test) == 1;
}

// ============================================================================
// Header Reading
// ============================================================================

/// Fory header information
struct HeaderInfo {
  uint16_t magic;
  bool is_null;
  bool is_little_endian;
  bool is_xlang;
  bool is_oob;
  Language language;
  uint32_t meta_start_offset; // 0 if not present
};

/// Read Fory protocol header from buffer.
///
/// @param buffer Input buffer
/// @return Header information or error
inline Result<HeaderInfo, Error> read_header(Buffer &buffer) {
  // Check minimum header size (3 bytes: magic + flags)
  if (buffer.reader_index() + 3 > buffer.size()) {
    return Unexpected(
        Error::buffer_out_of_bound(buffer.reader_index(), 3, buffer.size()));
  }

  HeaderInfo info;
  uint32_t start_pos = buffer.reader_index();

  // Read magic number
  info.magic = buffer.Get<uint16_t>(start_pos);
  if (info.magic != MAGIC_NUMBER) {
    return Unexpected(
        Error::invalid_data("Invalid magic number: expected 0x62d4, got 0x" +
                            std::to_string(info.magic)));
  }

  // Read flags byte
  uint8_t flags = buffer.GetByteAs<uint8_t>(start_pos + 2);
  info.is_null = (flags & (1 << 0)) != 0;
  info.is_little_endian = (flags & (1 << 1)) != 0;
  info.is_xlang = (flags & (1 << 2)) != 0;
  info.is_oob = (flags & (1 << 3)) != 0;

  // Update reader index (3 bytes consumed: magic + flags)
  buffer.IncreaseReaderIndex(3);

  // Java writes a language byte after header in xlang mode - read and ignore it
  if (info.is_xlang) {
    Error error;
    uint8_t lang_byte = buffer.ReadUint8(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    info.language = static_cast<Language>(lang_byte);
  } else {
    info.language = Language::JAVA;
  }

  // Note: Meta start offset would be read here if present
  info.meta_start_offset = 0;

  return info;
}

// ============================================================================
// Reference Metadata Helpers
// ============================================================================

/// Write ref flag for NullOnly mode (not null case).
/// Fast path: primitives, strings, time types use this.
FORY_ALWAYS_INLINE void write_not_null_ref_flag(WriteContext &ctx,
                                                RefMode ref_mode) {
  if (ref_mode != RefMode::None) {
    ctx.write_int8(NOT_NULL_VALUE_FLAG);
  }
}

/// Read ref flag for NullOnly mode.
/// Returns true if value present, false if null or error.
/// Fast path: primitives, strings, time types use this.
FORY_ALWAYS_INLINE bool read_null_only_flag(ReadContext &ctx,
                                            RefMode ref_mode) {
  if (ref_mode == RefMode::None) {
    return true;
  }
  int8_t flag = ctx.read_int8(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return false;
  }
  if (flag == NULL_FLAG) {
    return false;
  }
  // NotNullValue or RefValue both mean "continue reading" for non-trackable
  // types
  if (flag == NOT_NULL_VALUE_FLAG || flag == REF_VALUE_FLAG) {
    return true;
  }
  if (flag == REF_FLAG) {
    uint32_t ref_id = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return false;
    }
    ctx.set_error(Error::invalid_ref(
        "Unexpected reference flag for non-referencable value, ref id: " +
        std::to_string(ref_id)));
    return false;
  }

  ctx.set_error(Error::invalid_data("Unknown reference flag: " +
                                    std::to_string(static_cast<int>(flag))));
  return false;
}

// ============================================================================
// Type Info Helpers
// ============================================================================

/// Check if a type ID matches, allowing struct variants to match STRUCT.
inline bool type_id_matches(uint32_t actual, uint32_t expected) {
  if (actual == expected)
    return true;
  uint32_t low_actual = actual & 0xffu;
  // For structs, allow STRUCT/COMPATIBLE_STRUCT/NAMED_*/etc.
  if (expected == static_cast<uint32_t>(TypeId::STRUCT)) {
    return low_actual == static_cast<uint32_t>(TypeId::STRUCT) ||
           low_actual == static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT) ||
           low_actual == static_cast<uint32_t>(TypeId::NAMED_STRUCT) ||
           low_actual == static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT);
  }
  return low_actual == expected;
}

// ============================================================================
// Core Serializer API
// ============================================================================

/// Primary serializer template - triggers compile error for unregistered
/// types.
///
/// All types must either:
/// 1. Have a Serializer specialization (primitives, containers)
/// 2. Be registered with FORY_STRUCT macro (user-defined types)
template <typename T, typename Enable> struct Serializer {
  static_assert(meta::AlwaysFalse<T>,
                "Type T must be registered with FORY_STRUCT or have a "
                "Serializer specialization");
};

} // namespace serialization
} // namespace fory

// Include all specialized serializers
#include "fory/serialization/basic_serializer.h"
#include "fory/serialization/enum_serializer.h"
#include "fory/serialization/string_serializer.h"
#include "fory/serialization/unsigned_serializer.h"
