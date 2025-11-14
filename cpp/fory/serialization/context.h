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

#include "fory/serialization/config.h"
#include "fory/serialization/ref_resolver.h"
#include "fory/util/buffer.h"
#include "fory/util/error.h"
#include "fory/util/result.h"

#include <cassert>
#include <typeindex>

namespace fory {
namespace serialization {

// Forward declarations
class TypeResolver;
class ReadContext;
struct TypeInfo;

/// RAII helper to automatically decrease depth when leaving scope
class DepthGuard {
public:
  explicit DepthGuard(ReadContext &ctx) : ctx_(ctx) {}

  ~DepthGuard();

  // Non-copyable, non-movable
  DepthGuard(const DepthGuard &) = delete;
  DepthGuard &operator=(const DepthGuard &) = delete;
  DepthGuard(DepthGuard &&) = delete;
  DepthGuard &operator=(DepthGuard &&) = delete;

private:
  ReadContext &ctx_;
};

/// Write context for serialization operations.
///
/// This class maintains the state during serialization, including:
/// - Internal buffer for writing data (owned by context)
/// - Reference tracking for shared/circular references
/// - Configuration flags
/// - Depth tracking for preventing stack overflow
///
/// The WriteContext owns its own Buffer internally for reuse across
/// serialization calls when pooled.
///
/// Example:
/// ```cpp
/// WriteContext ctx(config, type_resolver);
/// ctx.write_uint8(42);
/// // Access buffer: ctx.buffer()
/// ```
class WriteContext {
public:
  /// Construct write context with configuration and shared type resolver.
  explicit WriteContext(const Config &config,
                        std::shared_ptr<TypeResolver> type_resolver);

  /// Destructor
  ~WriteContext();

  /// Get reference to internal output buffer.
  inline Buffer &buffer() { return buffer_; }

  /// Get const reference to internal output buffer.
  inline const Buffer &buffer() const { return buffer_; }

  /// Get reference writer for tracking shared references.
  inline RefWriter &ref_writer() { return ref_writer_; }

  /// Get associated type resolver.
  inline TypeResolver &type_resolver() { return *type_resolver_; }

  /// Get associated type resolver (const).
  inline const TypeResolver &type_resolver() const { return *type_resolver_; }

  /// Check if compatible mode is enabled.
  inline bool is_compatible() const { return config_->compatible; }

  /// Check if xlang mode is enabled.
  inline bool is_xlang() const { return config_->xlang; }

  /// Check if struct version checking is enabled.
  inline bool check_struct_version() const {
    return config_->check_struct_version;
  }

  /// Check if reference tracking is enabled.
  inline bool track_ref() const { return config_->track_ref; }

  /// Get maximum allowed nesting depth.
  inline uint32_t max_depth() const { return config_->max_depth; }

  /// Get current nesting depth.
  inline uint32_t current_depth() const { return current_depth_; }

  /// Increase nesting depth by 1.
  ///
  /// @return Error if max depth exceeded, success otherwise.
  inline Result<void, Error> increase_depth() {
    if (current_depth_ >= config_->max_depth) {
      return Unexpected(
          Error::depth_exceed("Max serialization depth exceeded: " +
                              std::to_string(config_->max_depth)));
    }
    current_depth_++;
    return Result<void, Error>();
  }

  /// Decrease nesting depth by 1.
  inline void decrease_depth() {
    if (current_depth_ > 0) {
      current_depth_--;
    }
  }

  /// Write uint8_t value to buffer.
  inline void write_uint8(uint8_t value) { buffer().WriteUint8(value); }

  /// Write int8_t value to buffer.
  inline void write_int8(int8_t value) { buffer().WriteInt8(value); }

  /// Write uint16_t value to buffer.
  inline void write_uint16(uint16_t value) { buffer().WriteUint16(value); }

  /// Write uint32_t value as varint to buffer.
  inline void write_varuint32(uint32_t value) {
    buffer().WriteVarUint32(value);
  }

  /// Write uint64_t value as varint to buffer.
  inline void write_varuint64(uint64_t value) {
    buffer().WriteVarUint64(value);
  }

  /// Write raw bytes to buffer.
  inline void write_bytes(const void *data, uint32_t length) {
    buffer().WriteBytes(data, length);
  }

  /// Push a TypeId's TypeMeta into the meta collection.
  /// Returns the index for writing as varint.
  Result<size_t, Error> push_meta(const std::type_index &type_id);

  /// Write all collected TypeMetas at the specified offset.
  /// Updates the meta_offset field at 'offset' to point to meta section.
  void write_meta(size_t offset);

  /// Check if any TypeMetas were collected.
  bool meta_empty() const;

  /// Write type information for polymorphic types.
  /// Handles different type categories:
  /// - Internal types: just write type_id
  /// - COMPATIBLE_STRUCT/NAMED_COMPATIBLE_STRUCT: write type_id and meta_index
  /// - NAMED_ENUM/NAMED_STRUCT/NAMED_EXT: write type_id, then
  /// namespace/type_name
  ///   (as raw strings if share_meta is disabled, or meta_index if enabled)
  /// - Other types: just write type_id
  ///
  /// @param fory_type_id The static Fory type ID
  /// @param concrete_type_id The runtime type_index for concrete type
  /// @return TypeInfo for the written type, or error
  Result<const TypeInfo *, Error>
  write_any_typeinfo(uint32_t fory_type_id,
                     const std::type_index &concrete_type_id);

  /// Reset context for reuse.
  void reset();

private:
  Buffer buffer_;
  const Config *config_;
  std::shared_ptr<TypeResolver> type_resolver_;
  RefWriter ref_writer_;
  uint32_t current_depth_;

  // Meta sharing state (for compatible mode)
  std::vector<std::vector<uint8_t>> write_type_defs_;
  std::unordered_map<std::type_index, size_t> write_type_id_index_map_;
};

/// Read context for deserialization operations.
///
/// This class maintains the state during deserialization, including:
/// - Input buffer for reading data
/// - Reference tracking for reconstructing shared/circular references
/// - Configuration flags
/// - Depth tracking for preventing stack overflow
///
/// Example:
/// ```cpp
/// Buffer buffer(data, size);
/// ReadContext ctx(config, type_resolver);
/// ctx.attach(buffer);
/// auto result = ctx.read_uint8();
/// if (result.ok()) {
///   uint8_t value = result.value();
/// }
/// ```
class ReadContext {
public:
  /// Construct read context with configuration and shared type resolver.
  explicit ReadContext(const Config &config,
                       std::shared_ptr<TypeResolver> type_resolver);

  /// Destructor
  ~ReadContext();

  /// Attach an input buffer for the duration of current deserialization call.
  inline void attach(Buffer &buffer) { buffer_ = &buffer; }

  /// Detach the buffer after deserialization is complete.
  inline void detach() { buffer_ = nullptr; }

  /// Get reference to input buffer.
  inline Buffer &buffer() {
    assert(buffer_ != nullptr);
    return *buffer_;
  }

  /// Get const reference to input buffer.
  inline const Buffer &buffer() const {
    assert(buffer_ != nullptr);
    return *buffer_;
  }

  /// Get reference reader for reconstructing shared references.
  inline RefReader &ref_reader() { return ref_reader_; }

  /// Get associated type resolver.
  inline TypeResolver &type_resolver() { return *type_resolver_; }

  /// Get associated type resolver (const).
  inline const TypeResolver &type_resolver() const { return *type_resolver_; }

  /// Check if compatible mode is enabled.
  inline bool is_compatible() const { return config_->compatible; }

  /// Check if xlang mode is enabled.
  inline bool is_xlang() const { return config_->xlang; }

  /// Check if struct version checking is enabled.
  inline bool check_struct_version() const {
    return config_->check_struct_version;
  }

  /// Check if reference tracking is enabled.
  inline bool track_ref() const { return config_->track_ref; }

  /// Get maximum allowed nesting depth.
  inline uint32_t max_depth() const { return config_->max_depth; }

  /// Get current nesting depth.
  inline uint32_t current_depth() const { return current_depth_; }

  /// Increase nesting depth by 1.
  ///
  /// @return Error if max depth exceeded, success otherwise.
  inline Result<void, Error> increase_depth() {
    if (current_depth_ >= config_->max_depth) {
      return Unexpected(
          Error::depth_exceed("Max deserialization depth exceeded: " +
                              std::to_string(config_->max_depth)));
    }
    current_depth_++;
    return Result<void, Error>();
  }

  /// Decrease nesting depth by 1.
  inline void decrease_depth() {
    if (current_depth_ > 0) {
      current_depth_--;
    }
  }

  /// Read uint8_t value from buffer.
  inline Result<uint8_t, Error> read_uint8() { return buffer().ReadUint8(); }

  /// Read int8_t value from buffer.
  inline Result<int8_t, Error> read_int8() { return buffer().ReadInt8(); }

  /// Read uint32_t value as varint from buffer.
  inline Result<uint32_t, Error> read_varuint32() {
    return buffer().ReadVarUint32();
  }

  /// Read uint64_t value as varint from buffer.
  inline Result<uint64_t, Error> read_varuint64() {
    return buffer().ReadVarUint64();
  }

  /// Read raw bytes from buffer.
  inline Result<void, Error> read_bytes(void *data, uint32_t length) {
    return buffer().ReadBytes(data, length);
  }

  /// Load all TypeMetas from buffer at the specified offset.
  /// After loading, the reader position is restored to where it was before.
  Result<void, Error> load_type_meta(int32_t meta_offset);

  /// Get TypeInfo by meta index.
  /// Returns TypeResolver::TypeInfo as void* to avoid incomplete type issues.
  /// Implementation casts it back to TypeResolver::TypeInfo*.
  Result<std::shared_ptr<void>, Error>
  get_type_info_by_index(size_t index) const;

  /// Read type information dynamically from buffer based on type ID.
  /// This mirrors Rust's read_any_typeinfo implementation.
  ///
  /// Handles different type categories:
  /// - COMPATIBLE_STRUCT/NAMED_COMPATIBLE_STRUCT: read meta_index
  /// - NAMED_ENUM/NAMED_STRUCT/NAMED_EXT: read namespace/type_name
  ///   (as raw strings if share_meta is disabled, or meta_index if enabled)
  /// - Other types: look up by type_id
  ///
  /// @return TypeInfo for the read type, or error
  Result<std::shared_ptr<TypeInfo>, Error> read_any_typeinfo();

  /// Reset context for reuse.
  void reset();

private:
  Buffer *buffer_;
  const Config *config_;
  std::shared_ptr<TypeResolver> type_resolver_;
  RefReader ref_reader_;
  uint32_t current_depth_;

  // Meta sharing state (for compatible mode)
  std::vector<std::shared_ptr<void>> reading_type_infos_;
  std::unordered_map<int64_t, std::shared_ptr<void>> parsed_type_infos_;
};

/// Implementation of DepthGuard destructor
inline DepthGuard::~DepthGuard() { ctx_.decrease_depth(); }

} // namespace serialization
} // namespace fory

// Include type_resolver.h at the end to get MetaWriterResolver and
// MetaReaderResolver definitions
#include "fory/serialization/type_resolver.h"
