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

#include "fory/util/macros.h"
#include <cstdint>

namespace fory {
namespace serialization {

/// Controls how reference and null flags are handled during serialization.
///
/// This enum combines nullable semantics and reference tracking into one
/// parameter, enabling fine-grained control per type and per field:
/// - `None` = non-nullable, no ref tracking (primitives)
/// - `NullOnly` = nullable, no circular ref tracking
/// - `Tracking` = nullable, with circular ref tracking (std::shared_ptr)
enum class RefMode : uint8_t {
  /// skip ref handling entirely. No ref/null flags are written/read.
  /// Used for non-nullable primitives or when caller handles ref externally.
  None = 0,

  /// Only null check without reference tracking.
  /// write: NullFlag (-3) for null, NotNullValueFlag (-1) for non-null.
  /// Read: Read flag and return default on null.
  NullOnly = 1,

  /// Full reference tracking with circular reference support.
  /// write: Uses RefWriter which writes NullFlag, RefFlag+ref_id, or
  /// RefValueFlag. Read: Uses RefReader with full reference resolution.
  Tracking = 2,
};

/// Create RefMode from nullable and ref_tracking flags.
/// This is a constexpr function for compile-time field metadata conversion.
FORY_ALWAYS_INLINE constexpr RefMode make_ref_mode(bool nullable,
                                                   bool ref_tracking) {
  if (ref_tracking) {
    return RefMode::Tracking;
  }
  if (nullable) {
    return RefMode::NullOnly;
  }
  return RefMode::None;
}

/// Check if this mode reads/writes ref flags.
/// Fast path check - single comparison.
FORY_ALWAYS_INLINE constexpr bool has_ref_flag(RefMode mode) {
  return mode != RefMode::None;
}

/// Check if this mode tracks circular references.
FORY_ALWAYS_INLINE constexpr bool tracks_refs(RefMode mode) {
  return mode == RefMode::Tracking;
}

/// Check if this mode handles nullable values.
FORY_ALWAYS_INLINE constexpr bool is_ref_mode_nullable(RefMode mode) {
  return mode != RefMode::None;
}

} // namespace serialization
} // namespace fory
