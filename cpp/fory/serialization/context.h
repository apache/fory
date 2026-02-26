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

#include "fory/meta/meta_string.h"
#include "fory/serialization/config.h"
#include "fory/serialization/ref_resolver.h"
#include "fory/serialization/type_info.h"
#include "fory/type/type.h"
#include "fory/util/buffer.h"
#include "fory/util/error.h"
#include "fory/util/flat_int_map.h"
#include "fory/util/result.h"

#include "absl/container/flat_hash_map.h"

#include <cassert>
#include <typeindex>

namespace fory {
namespace serialization {

// Forward declarations
class TypeResolver;
class ReadContext;

/// RAII helper to automatically decrease dynamic depth when leaving scope.
/// Used for tracking nested polymorphic type deserialization depth.
class DynDepthGuard {
public:
  explicit DynDepthGuard(ReadContext &ctx) : ctx_(ctx) {}

  ~DynDepthGuard();

  // Non-copyable, non-movable
  DynDepthGuard(const DynDepthGuard &) = delete;
  DynDepthGuard &operator=(const DynDepthGuard &) = delete;
  DynDepthGuard(DynDepthGuard &&) = delete;
  DynDepthGuard &operator=(DynDepthGuard &&) = delete;

private:
  ReadContext &ctx_;
};

/// write context for serialization operations.
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
  /// Construct write context with configuration and type resolver.
  /// Takes ownership of the type resolver.
  explicit WriteContext(const Config &config,
                        std::unique_ptr<TypeResolver> type_resolver);

  /// Destructor
  ~WriteContext();

  // ===========================================================================
  // Error State Management
  // Error is accumulated during serialization and checked at the end.
  // This trades slower error path for faster success path performance.
  // ===========================================================================

  /// Check if an error has occurred during serialization.
  FORY_ALWAYS_INLINE bool has_error() const { return !error_.ok(); }

  /// Set error if no error exists yet (preserves root cause).
  FORY_ALWAYS_INLINE void set_error(Error err) {
    if (error_.ok()) {
      error_ = std::move(err);
    }
  }

  /// get the accumulated error (moved out).
  FORY_ALWAYS_INLINE Error take_error() { return std::move(error_); }

  /// get const reference to the error.
  FORY_ALWAYS_INLINE const Error &error() const { return error_; }

  /// get mutable reference to the error for buffer read operations.
  /// The buffer read methods use Error& pattern for performance.
  FORY_ALWAYS_INLINE Error &error() { return error_; }

  /// get reference to internal output buffer.
  inline Buffer &buffer() { return buffer_; }

  /// get const reference to internal output buffer.
  inline const Buffer &buffer() const { return buffer_; }

  /// get reference writer for tracking shared references.
  inline RefWriter &ref_writer() { return ref_writer_; }

  /// get associated type resolver.
  inline TypeResolver &type_resolver() { return *type_resolver_; }

  /// get associated type resolver (const).
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

  /// get maximum allowed dynamic nesting depth for polymorphic types.
  inline uint32_t max_dyn_depth() const { return config_->max_dyn_depth; }

  /// get current dynamic nesting depth.
  inline uint32_t current_dyn_depth() const { return current_dyn_depth_; }

  /// Increase dynamic nesting depth by 1.
  ///
  /// @return Error if max dynamic depth exceeded, success otherwise.
  inline Result<void, Error> increase_dyn_depth() {
    if (current_dyn_depth_ >= config_->max_dyn_depth) {
      return Unexpected<Error>(
          Error::depth_exceed("Max dynamic serialization depth exceeded: " +
                              std::to_string(config_->max_dyn_depth)));
    }
    current_dyn_depth_++;
    return Result<void, Error>();
  }

  /// Decrease dynamic nesting depth by 1.
  inline void decrease_dyn_depth() {
    if (current_dyn_depth_ > 0) {
      current_dyn_depth_--;
    }
  }

  /// write uint8_t value to buffer.
  FORY_ALWAYS_INLINE void write_uint8(uint8_t value) {
    buffer().write_uint8(value);
  }

  /// write int8_t value to buffer.
  FORY_ALWAYS_INLINE void write_int8(int8_t value) {
    buffer().write_int8(value);
  }

  /// write uint16_t value to buffer.
  FORY_ALWAYS_INLINE void write_uint16(uint16_t value) {
    buffer().write_uint16(value);
  }

  /// write uint32_t value to buffer.
  FORY_ALWAYS_INLINE void write_uint32(uint32_t value) {
    buffer().write_uint32(value);
  }

  /// write int64_t value to buffer.
  FORY_ALWAYS_INLINE void write_int64(int64_t value) {
    buffer().write_int64(value);
  }

  /// write uint32_t value as varint to buffer.
  FORY_ALWAYS_INLINE void write_var_uint32(uint32_t value) {
    buffer().write_var_uint32(value);
  }

  /// write int32_t value as zigzag varint to buffer.
  FORY_ALWAYS_INLINE void write_varint32(int32_t value) {
    buffer().write_var_int32(value);
  }

  /// write uint64_t value as varint to buffer.
  FORY_ALWAYS_INLINE void write_var_uint64(uint64_t value) {
    buffer().write_var_uint64(value);
  }

  /// write int64_t value as zigzag varint to buffer.
  FORY_ALWAYS_INLINE void write_varint64(int64_t value) {
    buffer().write_var_int64(value);
  }

  /// write uint64_t value using tagged encoding to buffer.
  FORY_ALWAYS_INLINE void write_tagged_uint64(uint64_t value) {
    buffer().write_tagged_uint64(value);
  }

  /// write int64_t value using tagged encoding to buffer.
  FORY_ALWAYS_INLINE void write_tagged_int64(int64_t value) {
    buffer().write_tagged_int64(value);
  }

  /// write uint64_t value as varuint36small to buffer.
  /// This is the special variable-length encoding used for string headers.
  FORY_ALWAYS_INLINE void write_var_uint36_small(uint64_t value) {
    buffer().write_var_uint36_small(value);
  }

  /// write raw bytes to buffer.
  FORY_ALWAYS_INLINE void write_bytes(const void *data, uint32_t length) {
    buffer().write_bytes(data, length);
  }

  /// write TypeMeta inline using streaming protocol.
  /// First occurrence: writes (index << 1) | 0 followed by TypeDef bytes.
  /// Subsequent occurrences: writes (index << 1) | 1 as reference.
  Result<void, Error> write_type_meta(const std::type_index &type_id);

  /// write TypeMeta inline using TypeInfo pointer (fast path).
  /// First occurrence: writes (index << 1) | 0 followed by TypeDef bytes.
  /// Subsequent occurrences: writes (index << 1) | 1 as reference.
  void write_type_meta(const TypeInfo *type_info);

  /// write type information for polymorphic types.
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
  write_any_type_info(uint32_t fory_type_id,
                      const std::type_index &concrete_type_id);

  /// write type information using an existing TypeInfo.
  /// Avoids extra type lookup when the TypeInfo is already known.
  Result<void, Error> write_any_type_info(const TypeInfo *type_info);

  /// Fast path for writing struct type info - does a single type lookup
  /// and handles all struct type categories (STRUCT, NAMED_STRUCT,
  /// COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT).
  ///
  /// @param type_id The type_index for the struct type
  /// @return Success or error
  Result<void, Error> write_struct_type_info(const std::type_index &type_id);

  /// Fastest path for writing struct type info when TypeInfo is already known.
  /// Avoids type_index creation and lookup overhead.
  ///
  /// @param type_info Pointer to the TypeInfo (must be valid)
  /// @return Success or error
  Result<void, Error> write_struct_type_info(const TypeInfo *type_info);

  /// Fastest path - write struct type_id directly without any lookups.
  /// Use this when the type_id is already known (e.g., from a cache).
  /// Only for STRUCT types (not NAMED_STRUCT, COMPATIBLE_STRUCT, etc.)
  ///
  /// @param type_id The pre-computed Fory type_id
  inline void write_struct_type_id_direct(uint32_t type_id,
                                          uint32_t user_type_id) {
    buffer_.write_uint8(static_cast<uint8_t>(type_id));
    switch (static_cast<TypeId>(type_id)) {
    case TypeId::ENUM:
    case TypeId::STRUCT:
    case TypeId::COMPATIBLE_STRUCT:
    case TypeId::EXT:
    case TypeId::TYPED_UNION:
      FORY_CHECK(user_type_id != kInvalidUserTypeId)
          << "User type id is required for struct type";
      buffer_.write_var_uint32(user_type_id);
      break;
    default:
      break;
    }
  }

  /// get the type_id for a type. Used to cache type_id for fast writes.
  /// Returns 0 if type is not registered.
  uint32_t get_type_id_for_cache(const std::type_index &type_idx);

  /// write type info for a registered enum type.
  /// Looks up the type info and delegates to write_any_type_info.
  Result<void, Error> write_enum_type_info(const std::type_index &type);

  /// write type info for a registered enum type using TypeInfo pointer.
  /// Avoids type_index creation and lookup overhead.
  Result<void, Error> write_enum_type_info(const TypeInfo *type_info);

  /// write type info for a registered enum type using compile-time type lookup.
  /// Faster than the std::type_index version - uses type_index<E>().
  template <typename E> Result<void, Error> write_enum_type_info();

  /// reset context for reuse.
  void reset();

private:
  // Error state - accumulated during serialization, checked at the end
  Error error_;

  Buffer buffer_;
  const Config *config_;
  std::unique_ptr<TypeResolver> type_resolver_;
  RefWriter ref_writer_;
  uint32_t current_dyn_depth_;

  // Meta sharing state (for streaming inline TypeMeta)
  // Maps TypeInfo* to index for reference tracking - uses map size as counter
  util::FlatIntMap<uint64_t, uint32_t> write_type_info_index_map_;
  // Fast path for the common single-type stream: avoid hash map lookups.
  const TypeInfo *first_type_info_ = nullptr;
  bool has_first_type_info_ = false;
  bool type_info_index_map_active_ = false;
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
  /// get maximum allowed collection size.
  inline uint32_t max_collection_size() const { return config_->max_collection_size; }

  /// get maximum allowed map size.
  inline uint32_t max_map_size() const { return config_->max_map_size; }
  
  /// Construct read context with configuration and type resolver.
  /// Takes ownership of the type resolver.
  explicit ReadContext(const Config &config,
                       std::unique_ptr<TypeResolver> type_resolver);

  /// Destructor
  ~ReadContext();

  // ===========================================================================
  // Error State Management
  // Error is accumulated during deserialization and checked at the end.
  // This trades slower error path for faster success path performance.
  // ===========================================================================

  /// Check if an error has occurred during deserialization.
  FORY_ALWAYS_INLINE bool has_error() const { return !error_.ok(); }

  /// Set error if no error exists yet (preserves root cause).
  FORY_ALWAYS_INLINE void set_error(Error err) {
    if (error_.ok()) {
      error_ = std::move(err);
    }
  }

  /// get the accumulated error (moved out).
  FORY_ALWAYS_INLINE Error take_error() { return std::move(error_); }

  /// get const reference to the error.
  FORY_ALWAYS_INLINE const Error &error() const { return error_; }

  /// get mutable reference to the error for buffer read operations.
  /// The buffer read methods use Error& pattern for performance.
  FORY_ALWAYS_INLINE Error &error() { return error_; }

  /// Attach an input buffer for the duration of current deserialization call.
  inline void attach(Buffer &buffer) { buffer_ = &buffer; }

  /// Detach the buffer after deserialization is complete.
  inline void detach() { buffer_ = nullptr; }

  /// get reference to input buffer.
  inline Buffer &buffer() {
    assert(buffer_ != nullptr);
    return *buffer_;
  }

  /// get const reference to input buffer.
  inline const Buffer &buffer() const {
    assert(buffer_ != nullptr);
    return *buffer_;
  }

  /// get reference reader for reconstructing shared references.
  inline RefReader &ref_reader() { return ref_reader_; }

  /// get associated type resolver.
  inline TypeResolver &type_resolver() { return *type_resolver_; }

  /// get associated type resolver (const).
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

  /// get maximum allowed dynamic nesting depth for polymorphic types.
  inline uint32_t max_dyn_depth() const { return config_->max_dyn_depth; }

  /// get current dynamic nesting depth.
  inline uint32_t current_dyn_depth() const { return current_dyn_depth_; }

  /// Increase dynamic nesting depth by 1.
  ///
  /// @return Error if max dynamic depth exceeded, success otherwise.
  inline Result<void, Error> increase_dyn_depth() {
    if (current_dyn_depth_ >= config_->max_dyn_depth) {
      return Unexpected<Error>(
          Error::depth_exceed("Max dynamic deserialization depth exceeded: " +
                              std::to_string(config_->max_dyn_depth)));
    }
    current_dyn_depth_++;
    return Result<void, Error>();
  }

  /// Decrease dynamic nesting depth by 1.
  inline void decrease_dyn_depth() {
    if (current_dyn_depth_ > 0) {
      current_dyn_depth_--;
    }
  }

  // ===========================================================================
  // Read methods with Error& parameter
  // All methods accept Error& as parameter for reduced overhead.
  // On success, error.ok() remains true. On failure, error is set.
  // ===========================================================================

  /// Read uint8_t value from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE uint8_t read_uint8(Error &error) {
    return buffer().read_uint8(error);
  }

  /// Read int8_t value from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE int8_t read_int8(Error &error) {
    return buffer().read_int8(error);
  }

  /// Read uint16_t value from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE uint16_t read_uint16(Error &error) {
    return buffer().read_uint16(error);
  }

  /// Read int16_t value from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE int16_t read_int16(Error &error) {
    return buffer().read_int16(error);
  }

  /// Read uint32_t value from buffer (fixed 4 bytes). Sets error on failure.
  FORY_ALWAYS_INLINE uint32_t read_uint32(Error &error) {
    return buffer().read_uint32(error);
  }

  /// Read int32_t value from buffer (fixed 4 bytes). Sets error on failure.
  FORY_ALWAYS_INLINE int32_t read_int32(Error &error) {
    return buffer().read_int32(error);
  }

  /// Read uint64_t value from buffer (fixed 8 bytes). Sets error on failure.
  FORY_ALWAYS_INLINE uint64_t read_uint64(Error &error) {
    return buffer().read_uint64(error);
  }

  /// Read int64_t value from buffer (fixed 8 bytes). Sets error on failure.
  FORY_ALWAYS_INLINE int64_t read_int64(Error &error) {
    return buffer().read_int64(error);
  }

  /// Read float value from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE float read_float(Error &error) {
    return buffer().read_float(error);
  }

  /// Read double value from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE double read_double(Error &error) {
    return buffer().read_double(error);
  }

  /// Read uint32_t value as varint from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE uint32_t read_var_uint32(Error &error) {
    return buffer().read_var_uint32(error);
  }

  /// Read int32_t value as zigzag varint from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE int32_t read_varint32(Error &error) {
    return buffer().read_var_int32(error);
  }

  /// Read uint64_t value as varint from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE uint64_t read_var_uint64(Error &error) {
    return buffer().read_var_uint64(error);
  }

  /// Read int64_t value as zigzag varint from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE int64_t read_varint64(Error &error) {
    return buffer().read_var_int64(error);
  }

  /// Read uint64_t value using tagged encoding from buffer. Sets error on
  /// failure.
  FORY_ALWAYS_INLINE uint64_t read_tagged_uint64(Error &error) {
    return buffer().read_tagged_uint64(error);
  }

  /// Read int64_t value using tagged encoding from buffer. Sets error on
  /// failure.
  FORY_ALWAYS_INLINE int64_t read_tagged_int64(Error &error) {
    return buffer().read_tagged_int64(error);
  }

  /// Read uint64_t value as varuint36small from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE uint64_t read_var_uint36_small(Error &error) {
    return buffer().read_var_uint36_small(error);
  }

  /// Read raw bytes from buffer. Sets error on failure.
  FORY_ALWAYS_INLINE void read_bytes(void *data, uint32_t length,
                                     Error &error) {
    buffer().read_bytes(data, length, error);
  }

  /// skip bytes in buffer. Sets error on failure.
  FORY_ALWAYS_INLINE void skip(uint32_t length, Error &error) {
    buffer().skip(length, error);
  }

  Result<const TypeInfo *, Error>
  read_enum_type_info(const std::type_index &type, uint32_t base_type_id);

  /// Read enum type info without type_index (fast path).
  Result<const TypeInfo *, Error> read_enum_type_info(uint32_t base_type_id);

  /// Read TypeMeta inline using streaming protocol.
  /// Reads index marker: if LSB=0, reads new TypeDef inline; if LSB=1, returns
  /// reference.
  /// @return const pointer to TypeInfo, or error
  Result<const TypeInfo *, Error> read_type_meta();

  /// get TypeInfo by meta index (internal use for reference lookups).
  /// @return const pointer to TypeInfo if found, error otherwise
  Result<const TypeInfo *, Error> get_type_info_by_index(size_t index) const;

  /// Read type information dynamically from buffer based on type ID.
  /// This mirrors Rust's read_any_type_info implementation.
  ///
  /// Handles different type categories:
  /// - COMPATIBLE_STRUCT/NAMED_COMPATIBLE_STRUCT: read meta_index
  /// - NAMED_ENUM/NAMED_STRUCT/NAMED_EXT: read namespace/type_name
  ///   (as raw strings if share_meta is disabled, or meta_index if enabled)
  /// - Other types: look up by type_id
  ///
  /// @return const pointer to TypeInfo for the read type, or error
  Result<const TypeInfo *, Error> read_any_type_info();

  /// Read type information dynamically from buffer based on type ID.
  /// Same as above but uses context's error state instead of returning Result.
  /// @param error Output parameter to receive any error
  /// @return const pointer to TypeInfo for the read type, or nullptr on error
  const TypeInfo *read_any_type_info(Error &error);

  /// reset context for reuse.
  void reset();

private:
  // Error state - accumulated during deserialization, checked at the end
  Error error_;

  Buffer *buffer_;
  const Config *config_;
  std::unique_ptr<TypeResolver> type_resolver_;
  RefReader ref_reader_;
  uint32_t current_dyn_depth_;

  // Meta sharing state (for compatible mode)
  // Per-message storage for TypeInfo objects not cached across messages.
  std::vector<std::unique_ptr<TypeInfo>> owned_reading_type_infos_;
  // Persistent cache storage for TypeInfo objects keyed by meta header.
  std::vector<std::unique_ptr<TypeInfo>> cached_type_infos_;
  // Index-based access (pointers to owned_reading_type_infos_ or type_resolver)
  std::vector<const TypeInfo *> reading_type_infos_;
  // Cache by meta_header (pointers to cached_type_infos_)
  absl::flat_hash_map<int64_t, const TypeInfo *> parsed_type_infos_;
  // Fast path for repeated type meta headers.
  int64_t last_meta_header_ = 0;
  const TypeInfo *last_meta_type_info_ = nullptr;
  bool has_last_meta_header_ = false;

  // Dynamic meta strings used for named type/class info.
  meta::MetaStringTable meta_string_table_;
};

/// Implementation of DynDepthGuard destructor
inline DynDepthGuard::~DynDepthGuard() { ctx_.decrease_dyn_depth(); }

} // namespace serialization
} // namespace fory

// Include type_resolver.h at the end to get MetaWriterResolver and
// MetaReaderResolver definitions
#include "fory/serialization/type_resolver.h"
