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

#include <cstdint>

namespace fory {
namespace serialization {

/// Configuration for Fory serialization behavior.
///
/// This struct holds all configurable options for the serialization framework.
/// Use ForyBuilder to construct instances with custom settings.
struct Config {
  /// Enable compatible mode for schema evolution.
  /// When enabled, supports reading data serialized with different schema
  /// versions.
  bool compatible = false;

  /// Enable cross-language (xlang) serialization mode.
  /// When enabled, includes metadata for cross-language compatibility.
  bool xlang = true;

  /// Enable struct version checking.
  /// When enabled, validates type hashes to detect schema mismatches.
  bool check_struct_version = false;

  /// Maximum allowed nesting depth for dynamically-typed objects (polymorphic
  /// types like shared_ptr<Base>, unique_ptr<Base>). This prevents stack
  /// overflow from deeply nested structures in dynamic serialization scenarios.
  /// Default is 5 levels deep.
  uint32_t max_dyn_depth = 5;

  /// Enable reference tracking for shared and circular references.
  /// When enabled, avoids duplicating shared objects and handles cycles.
  bool track_ref = true;

  // max limits fot map,lists and collections
  uint32_t max_string_length =
      64 * 1024 * 1024; // 64MB default max string length
  uint32_t max_collection_size =
      10 * 1000 * 1000;                    // 1M default max collection size
  uint32_t max_map_size = 1 * 1000 * 1000; // 1M default max map size

  /// Default constructor with sensible defaults
  Config() = default;
};

} // namespace serialization
} // namespace fory
