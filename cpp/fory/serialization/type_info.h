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

#include "fory/serialization/ref_mode.h"
#include "fory/util/error.h"
#include "fory/util/result.h"

#include "absl/container/flat_hash_map.h"

#include <any>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace fory {
namespace serialization {

// Forward declarations - these allow Harness function pointers to compile
// without including the full headers
class WriteContext;
class ReadContext;
class TypeResolver;
class TypeMeta;
class FieldInfo;

// ============================================================================
// Harness - Function pointers for type-erased serialization
// ============================================================================

/// Harness holds type-erased function pointers for serialization operations.
/// This allows TypeResolver to work with any registered type without
/// knowing the concrete type at compile time.
///
/// Error handling: All functions store errors in the context via
/// ctx.set_error(). Callers should check ctx.has_error() after calling these
/// functions.
struct Harness {
  using WriteFn = void (*)(const void *value, WriteContext &ctx,
                           RefMode ref_mode, bool write_type_info,
                           bool has_generics);
  using ReadFn = void *(*)(ReadContext & ctx, RefMode ref_mode,
                           bool read_type_info);
  using WriteDataFn = void (*)(const void *value, WriteContext &ctx,
                               bool has_generics);
  using ReadDataFn = void *(*)(ReadContext & ctx);
  using ReadCompatibleFn = void *(*)(ReadContext & ctx,
                                     const struct TypeInfo *type_info);
  using SortedFieldInfosFn =
      Result<std::vector<FieldInfo>, Error> (*)(TypeResolver &);
  using AnyWriteFn = void (*)(const std::any &value, WriteContext &ctx);
  using AnyReadFn = std::any (*)(ReadContext &ctx);

  Harness() = default;
  Harness(WriteFn write, ReadFn read, WriteDataFn write_data,
          ReadDataFn read_data, SortedFieldInfosFn sorted_fields,
          ReadCompatibleFn read_compatible = nullptr)
      : write_fn(write), read_fn(read), write_data_fn(write_data),
        read_data_fn(read_data), sorted_field_infos_fn(sorted_fields),
        read_compatible_fn(read_compatible) {}

  bool valid() const {
    return write_fn != nullptr && read_fn != nullptr &&
           write_data_fn != nullptr && read_data_fn != nullptr &&
           sorted_field_infos_fn != nullptr;
  }

  WriteFn write_fn = nullptr;
  ReadFn read_fn = nullptr;
  WriteDataFn write_data_fn = nullptr;
  ReadDataFn read_data_fn = nullptr;
  SortedFieldInfosFn sorted_field_infos_fn = nullptr;
  ReadCompatibleFn read_compatible_fn = nullptr;
  AnyWriteFn any_write_fn = nullptr;
  AnyReadFn any_read_fn = nullptr;
};

// ============================================================================
// TypeInfo - Type metadata and serialization information
// ============================================================================

/// CachedMetaString holds the pre-encoded form of a meta string
/// for efficient writing without re-encoding on each serialization.
/// This extends EncodedMetaString (from meta_string.h) with additional
/// cached data like the original string and pre-computed hash.
struct CachedMetaString {
  std::string original;       // Original string value
  std::vector<uint8_t> bytes; // Encoded bytes
  uint8_t encoding;           // Encoding type (MetaEncoding)
  int64_t hash;               // MurmurHash3 for large strings (>16 bytes)

  CachedMetaString() : encoding(0), hash(0) {}
};

/// TypeInfo holds metadata about a type for serialization purposes.
/// This is used by read_any_typeinfo() and write_any_typeinfo() to track
/// type information across language boundaries (xlang serialization).
struct TypeInfo {
  uint32_t type_id = 0;
  std::string namespace_name;
  std::string type_name;
  bool register_by_name = false;
  bool is_external = false;
  std::unique_ptr<TypeMeta> type_meta;
  std::vector<size_t> sorted_indices;
  absl::flat_hash_map<std::string, size_t> name_to_index;
  std::vector<uint8_t> type_def;
  Harness harness;
  // Pre-encoded meta strings for efficient writing (avoids re-encoding on each
  // write)
  std::unique_ptr<CachedMetaString> encoded_namespace;
  std::unique_ptr<CachedMetaString> encoded_type_name;

  TypeInfo() = default;

  // Non-copyable due to unique_ptr members
  TypeInfo(const TypeInfo &) = delete;
  TypeInfo &operator=(const TypeInfo &) = delete;

  // Movable
  TypeInfo(TypeInfo &&) = default;
  TypeInfo &operator=(TypeInfo &&) = default;

  /// Creates a deep clone of this TypeInfo.
  /// All unique_ptr members are cloned into new instances.
  std::unique_ptr<TypeInfo> deep_clone() const;
};

} // namespace serialization
} // namespace fory
