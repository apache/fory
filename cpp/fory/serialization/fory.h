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

#include "fory/serialization/array_serializer.h"
#include "fory/serialization/collection_serializer.h"
#include "fory/serialization/config.h"
#include "fory/serialization/context.h"
#include "fory/serialization/map_serializer.h"
#include "fory/serialization/serializer.h"
#include "fory/serialization/smart_ptr_serializers.h"
#include "fory/serialization/struct_serializer.h"
#include "fory/serialization/temporal_serializers.h"
#include "fory/serialization/type_resolver.h"
#include "fory/util/buffer.h"
#include "fory/util/error.h"
#include "fory/util/pool.h"
#include "fory/util/result.h"
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace fory {
namespace serialization {

// Forward declaration
class Fory;

/// Builder class for creating Fory instances with custom configuration.
///
/// Use this class to construct a Fory instance with specific settings.
/// The builder pattern ensures clean, readable configuration code.
///
/// Example:
/// ```cpp
/// auto fory = Fory::builder()
///     .compatible(true)
///     .xlang(true)
///     .check_struct_version(false)
///     .max_depth(128)
///     .build();
/// ```
class ForyBuilder {
public:
  /// Default constructor with sensible defaults
  ForyBuilder() = default;

  /// Enable/disable compatible mode for schema evolution.
  ForyBuilder &compatible(bool enable) {
    config_.compatible = enable;
    return *this;
  }

  /// Enable/disable cross-language (xlang) serialization mode.
  ForyBuilder &xlang(bool enable) {
    config_.xlang = enable;
    return *this;
  }

  /// Enable/disable struct version checking.
  ForyBuilder &check_struct_version(bool enable) {
    config_.check_struct_version = enable;
    return *this;
  }

  /// Set maximum allowed nesting depth.
  ForyBuilder &max_depth(uint32_t depth) {
    config_.max_depth = depth;
    return *this;
  }

  /// Enable/disable reference tracking for shared/circular references.
  ForyBuilder &track_ref(bool enable) {
    config_.track_ref = enable;
    return *this;
  }

  /// Provide a custom type resolver instance.
  ForyBuilder &type_resolver(std::shared_ptr<TypeResolver> resolver) {
    type_resolver_ = std::move(resolver);
    return *this;
  }

  /// Build the final Fory instance.
  Fory build();

private:
  Config config_;
  std::shared_ptr<TypeResolver> type_resolver_;

  friend class Fory;
};

/// Main Fory serialization class.
///
/// This class provides serialization and deserialization functionality
/// for C++ objects. Create instances using the builder pattern via
/// Fory::builder().
///
/// Example:
/// ```cpp
/// // Create Fory instance
/// auto fory = Fory::builder().xlang(true).build();
///
/// // Serialize
/// MyStruct obj{...};
/// auto bytes_result = fory.serialize(obj);
/// if (bytes_result.ok()) {
///   std::vector<uint8_t> bytes = bytes_result.value();
/// }
///
/// // Deserialize
/// auto obj_result = fory.deserialize<MyStruct>(bytes.data(), bytes.size());
/// if (obj_result.ok()) {
///   MyStruct obj = obj_result.value();
/// }
/// ```
class Fory {
public:
  /// Create a builder for configuring Fory instance.
  static ForyBuilder builder() { return ForyBuilder(); }

  // ============================================================================
  // Serialization Methods
  // ============================================================================

  /// Serialize an object to a byte vector.
  ///
  /// @param obj Object to serialize (const reference).
  /// @return Vector of bytes on success, error on failure.
  template <typename T>
  Result<std::vector<uint8_t>, Error> serialize(const T &obj) {
    // Acquire WriteContext (with owned buffer) from pool
    auto ctx_handle = write_ctx_pool_.acquire();
    WriteContext &ctx = *ctx_handle;
    // RAII guard to ensure context is properly cleaned up
    struct ContextGuard {
      WriteContext &ctx;
      ~ContextGuard() { ctx.reset(); }
    } guard{ctx};

    // Serialize to the context's buffer
    FORY_RETURN_NOT_OK(serialize_to_impl(obj, ctx, ctx.buffer()));

    // Copy buffer data to vector
    std::vector<uint8_t> result(ctx.buffer().writer_index());
    std::memcpy(result.data(), ctx.buffer().data(),
                ctx.buffer().writer_index());
    return result;
  }

  /// Serialize an object to an existing buffer.
  ///
  /// @param obj Object to serialize (const reference).
  /// @param buffer Output buffer to write to.
  /// @return Number of bytes written on success, error on failure.
  template <typename T>
  Result<size_t, Error> serialize_to(const T &obj, Buffer &buffer) {
    // Acquire WriteContext from pool
    auto ctx_handle = write_ctx_pool_.acquire();
    WriteContext &ctx = *ctx_handle;
    // RAII guard to ensure context is properly cleaned up
    struct ContextGuard {
      WriteContext &ctx;
      ~ContextGuard() { ctx.reset(); }
    } guard{ctx};

    // Serialize using the provided buffer (not the context's buffer)
    return serialize_to_impl(obj, ctx, buffer);
  }

  /// Serialize an object to a byte vector (in-place).
  ///
  /// @param obj Object to serialize (const reference).
  /// @param output Output vector to write to (will be resized as needed).
  /// @return Number of bytes written on success, error on failure.
  template <typename T>
  Result<size_t, Error> serialize_to(const T &obj,
                                     std::vector<uint8_t> &output) {
    // Acquire WriteContext (with owned buffer) from pool
    auto ctx_handle = write_ctx_pool_.acquire();
    WriteContext &ctx = *ctx_handle;
    // RAII guard to ensure context is properly cleaned up
    struct ContextGuard {
      WriteContext &ctx;
      ~ContextGuard() { ctx.reset(); }
    } guard{ctx};

    // Serialize to the context's buffer
    FORY_TRY(bytes_written, serialize_to_impl(obj, ctx, ctx.buffer()));

    // Resize output vector and copy data
    output.resize(ctx.buffer().writer_index());
    std::memcpy(output.data(), ctx.buffer().data(),
                ctx.buffer().writer_index());
    return bytes_written;
  }

  // ============================================================================
  // Deserialization Methods
  // ============================================================================

  /// Deserialize an object from a byte array.
  ///
  /// @param data Pointer to serialized data. Must not be nullptr.
  /// @param size Size of data in bytes.
  /// @return Deserialized object on success, error on failure.
  template <typename T>
  Result<T, Error> deserialize(const uint8_t *data, size_t size) {
    if (data == nullptr) {
      return Unexpected(Error::invalid("Data pointer is null"));
    }
    if (size == 0) {
      return Unexpected(Error::invalid("Data size is zero"));
    }

    Buffer buffer(const_cast<uint8_t *>(data), static_cast<uint32_t>(size),
                  false);

    // Read and validate header
    FORY_TRY(header, read_header(buffer));

    // Check for null object
    if (header.is_null) {
      return Unexpected(Error::invalid_data("Cannot deserialize null object"));
    }

    // Check endianness compatibility
    if (header.is_little_endian != is_little_endian_system()) {
      return Unexpected(
          Error::unsupported("Cross-endian deserialization not yet supported"));
    }

    return deserialize_from<T>(buffer);
  }

  /// Core deserialization method that takes an explicit ReadContext.
  ///
  /// @param ctx ReadContext to use for deserialization.
  /// @param buffer Input buffer to read from (should be attached to ctx).
  /// @return Deserialized object on success, error on failure.
  template <typename T>
  Result<T, Error> deserialize_from(ReadContext &ctx, Buffer &buffer) {
    // Load TypeMetas at the beginning in compatible mode
    if (ctx.is_compatible()) {
      auto meta_offset_result = buffer.ReadInt32();
      FORY_RETURN_IF_ERROR(meta_offset_result);
      int32_t meta_offset = meta_offset_result.value();
      if (meta_offset != -1) {
        FORY_RETURN_NOT_OK(ctx.load_type_meta(meta_offset));
      }
    }

    auto result = Serializer<T>::read(ctx, true, true);

    if (result.ok()) {
      ctx.ref_reader().resolve_callbacks();
    }
    return result;
  }

  /// Deserialize an object from an existing buffer.
  ///
  /// @param buffer Input buffer to read from.
  /// @return Deserialized object on success, error on failure.
  template <typename T> Result<T, Error> deserialize_from(Buffer &buffer) {
    auto ctx_handle = read_ctx_pool_.acquire();
    ReadContext &ctx = *ctx_handle;
    ctx.attach(buffer);
    struct ReadContextCleanup {
      ReadContext &ctx;
      ~ReadContextCleanup() {
        ctx.reset();
        ctx.detach();
      }
    } cleanup{ctx};

    return deserialize_from<T>(ctx, buffer);
  }

  /// Deserialize an object from a byte vector.
  ///
  /// @param data Vector containing serialized data.
  /// @return Deserialized object on success, error on failure.
  template <typename T>
  Result<T, Error> deserialize_from(const std::vector<uint8_t> &data) {
    return deserialize<T>(data.data(), data.size());
  }

  /// Get reference to configuration.
  const Config &config() const { return config_; }

  /// Access the underlying type resolver.
  TypeResolver &type_resolver() { return *type_resolver_; }
  const TypeResolver &type_resolver() const { return *type_resolver_; }

  // ==========================================================================
  // Type Registration Helpers
  // ==========================================================================

  /// Register a struct type with a numeric identifier.
  template <typename T> Result<void, Error> register_struct(uint32_t type_id) {
    return type_resolver_->template register_by_id<T>(type_id);
  }

  /// Register a struct type with an explicit namespace and name.
  template <typename T>
  Result<void, Error> register_struct(const std::string &ns,
                                      const std::string &type_name) {
    return type_resolver_->template register_by_name<T>(ns, type_name);
  }

  /// Register a struct type using only a type name (default namespace).
  template <typename T>
  Result<void, Error> register_struct(const std::string &type_name) {
    return type_resolver_->template register_by_name<T>("", type_name);
  }

  /// Register an external serializer type with a numeric identifier.
  template <typename T>
  Result<void, Error> register_extension_type(uint32_t type_id) {
    return type_resolver_->template register_ext_type_by_id<T>(type_id);
  }

  /// Register an external serializer with namespace and name.
  template <typename T>
  Result<void, Error> register_extension_type(const std::string &ns,
                                              const std::string &type_name) {
    return type_resolver_->template register_ext_type_by_name<T>(ns, type_name);
  }

  /// Register an external serializer using a type name (default namespace).
  template <typename T>
  Result<void, Error> register_extension_type(const std::string &type_name) {
    return type_resolver_->template register_ext_type_by_name<T>("", type_name);
  }

private:
  /// Core serialization implementation that takes WriteContext and Buffer.
  /// All other serialization methods forward to this one.
  ///
  /// @param obj Object to serialize (const reference).
  /// @param ctx WriteContext to use for serialization.
  /// @param buffer Output buffer to write to (should be attached to ctx).
  /// @return Number of bytes written on success, error on failure.
  template <typename T>
  Result<size_t, Error> serialize_to_impl(const T &obj, WriteContext &ctx,
                                          Buffer &buffer) {
    size_t start_pos = buffer.writer_index();

    // Write Fory header
    write_header(buffer, false, config_.xlang, is_little_endian_system(), false,
                 Language::CPP);

    // Reserve space for meta offset in compatible mode
    size_t meta_start_offset = 0;
    if (ctx.is_compatible()) {
      meta_start_offset = buffer.writer_index();
      buffer.WriteInt32(-1); // Placeholder for meta offset (fixed 4 bytes)
    }

    FORY_RETURN_NOT_OK(Serializer<T>::write(obj, ctx, true, true));

    // Write collected TypeMetas at the end in compatible mode
    if (ctx.is_compatible() && !ctx.meta_empty()) {
      ctx.write_meta(meta_start_offset);
    }

    return buffer.writer_index() - start_pos;
  }

  /// Private constructor - use builder() instead!
  explicit Fory(const Config &config, std::shared_ptr<TypeResolver> resolver)
      : config_(config), type_resolver_(std::move(resolver)),
        finalized_resolver_(), finalized_once_flag_(),
        write_ctx_pool_([this]() {
          return std::make_unique<WriteContext>(config_,
                                                get_finalized_resolver());
        }),
        read_ctx_pool_([this]() {
          return std::make_unique<ReadContext>(config_,
                                               get_finalized_resolver());
        }) {}

  Config config_;
  std::shared_ptr<TypeResolver> type_resolver_;
  mutable std::shared_ptr<TypeResolver> finalized_resolver_;
  mutable std::once_flag finalized_once_flag_;
  util::Pool<WriteContext> write_ctx_pool_;
  util::Pool<ReadContext> read_ctx_pool_;

  /// Get or build finalized resolver (lazy, thread-safe, one-time
  /// initialization). This mirrors Rust's OnceLock pattern.
  std::shared_ptr<TypeResolver> get_finalized_resolver() const {
    std::call_once(finalized_once_flag_, [this]() {
      auto final_result = type_resolver_->build_final_type_resolver();
      FORY_CHECK(final_result.ok())
          << "Failed to build finalized TypeResolver: "
          << final_result.error().to_string();
      finalized_resolver_ = std::move(final_result).value();
    });
    return finalized_resolver_->clone();
  }

  friend class ForyBuilder;
};

// ============================================================================
// ForyBuilder Implementation
// ============================================================================

inline Fory ForyBuilder::build() {
  if (!type_resolver_) {
    type_resolver_ = std::make_shared<TypeResolver>();
  }
  type_resolver_->apply_config(config_);

  // Don't build final resolver yet - it will be built lazily on first use
  // This matches Rust's OnceLock pattern
  return Fory(config_, type_resolver_);
}

} // namespace serialization
} // namespace fory
