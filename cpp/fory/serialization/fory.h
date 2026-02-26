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

#include "fory/serialization/any_serializer.h"
#include "fory/serialization/array_serializer.h"
#include "fory/serialization/collection_serializer.h"
#include "fory/serialization/config.h"
#include "fory/serialization/context.h"
#include "fory/serialization/map_serializer.h"
#include "fory/serialization/serializer.h"
#include "fory/serialization/smart_ptr_serializers.h"
#include "fory/serialization/struct_serializer.h"
#include "fory/serialization/temporal_serializers.h"
#include "fory/serialization/tuple_serializer.h"
#include "fory/serialization/type_resolver.h"
#include "fory/serialization/variant_serializer.h"
#include "fory/serialization/weak_ptr_serializer.h"
#include "fory/util/buffer.h"
#include "fory/util/error.h"
#include "fory/util/pool.h"
#include "fory/util/result.h"
#include "fory/util/stream.h"
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace fory {
namespace serialization {

// Forward declarations
class Fory;
class ThreadSafeFory;

/// Builder class for creating Fory instances with custom configuration.
///
/// Use this class to construct a Fory instance with specific settings.
/// The builder pattern ensures clean, readable configuration code.
///
/// Example:
/// ```cpp
/// // Single-threaded Fory (fastest, not thread-safe)
/// auto fory = Fory::builder()
///     .xlang(true)
///     .build();
///
/// // Thread-safe Fory (uses context pools)
/// auto fory = Fory::builder()
///     .xlang(true)
///     .build_thread_safe();
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

  /// Set maximum allowed nesting depth for dynamically-typed objects.
  ///
  /// This limits the maximum depth for nested polymorphic object serialization
  /// (e.g., shared_ptr<Base>, unique_ptr<Base>). This prevents stack overflow
  /// from deeply nested structures in dynamic serialization scenarios.
  ///
  /// Default value is 5.
  ForyBuilder &max_dyn_depth(uint32_t depth) {
    config_.max_dyn_depth = depth;
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

  /// Build a single-threaded Fory instance (fastest, not thread-safe).
  Fory build();

  /// Build a thread-safe Fory instance (uses context pools).
  ThreadSafeFory build_thread_safe();

private:
  Config config_;
  std::shared_ptr<TypeResolver> type_resolver_;

  /// Helper to get or create type resolver and finalize it
  std::shared_ptr<TypeResolver> get_finalized_resolver();

  friend class Fory;
  friend class ThreadSafeFory;
};

// ============================================================================
// RAII Guards for Context Management
// ============================================================================

/// RAII guard for WriteContext - resets on destruction.
class WriteContextGuard {
public:
  explicit WriteContextGuard(WriteContext &ctx) : ctx_(ctx) {}
  ~WriteContextGuard() { ctx_.reset(); }

  WriteContextGuard(const WriteContextGuard &) = delete;
  WriteContextGuard &operator=(const WriteContextGuard &) = delete;

private:
  WriteContext &ctx_;
};

/// RAII guard for ReadContext - resets and detaches on destruction.
class ReadContextGuard {
public:
  explicit ReadContextGuard(ReadContext &ctx) : ctx_(ctx) {}
  ~ReadContextGuard() {
    ctx_.reset();
    ctx_.detach();
  }

  ReadContextGuard(const ReadContextGuard &) = delete;
  ReadContextGuard &operator=(const ReadContextGuard &) = delete;

private:
  ReadContext &ctx_;
};

// ============================================================================
// BaseFory - Common base class for Fory implementations
// ============================================================================

/// Base class for Fory serialization implementations.
///
/// This class provides common functionality shared between single-threaded
/// and thread-safe Fory implementations, including configuration access
/// and type registration methods.
///
/// Users should not instantiate this class directly. Use Fory for
/// single-threaded scenarios or ThreadSafeFory for multi-threaded scenarios.
class BaseFory {
public:
  virtual ~BaseFory() = default;

  // ==========================================================================
  // Configuration Access
  // ==========================================================================

  /// get reference to the serialization configuration.
  ///
  /// The configuration contains settings like xlang mode, compatible mode,
  /// reference tracking, etc.
  ///
  /// @return Const reference to the Config object.
  const Config &config() const { return config_; }

  /// Access the underlying type resolver.
  ///
  /// The type resolver manages type registration and lookup for serialization.
  /// Use this for advanced type manipulation or to check registered types.
  ///
  /// @return Reference to the TypeResolver.
  TypeResolver &type_resolver() { return *type_resolver_; }

  /// Access the underlying type resolver (const version).
  ///
  /// @return Const reference to the TypeResolver.
  const TypeResolver &type_resolver() const { return *type_resolver_; }

  // ==========================================================================
  // Type Registration Methods
  // ==========================================================================

  /// Register a struct type with a numeric type ID.
  ///
  /// Use this method to register types for cross-language serialization
  /// where types are identified by numeric IDs. The type ID must be unique
  /// across all registered types and match the ID used in other languages.
  ///
  /// @tparam T The struct type to register (must be defined with FORY_STRUCT).
  /// @param type_id Unique numeric identifier for this type.
  /// @return Success or error if registration fails.
  ///
  /// Example:
  /// ```cpp
  /// struct MyStruct {
  ///   int32_t value;
  ///   FORY_STRUCT(MyStruct, value);
  /// };
  ///
  /// fory.register_struct<MyStruct>(1);
  /// ```
  template <typename T> Result<void, Error> register_struct(uint32_t type_id) {
    return register_type([this, type_id]() {
      return type_resolver_->template register_by_id<T>(type_id);
    });
  }

  /// Register a struct type with namespace and type name.
  ///
  /// Use this method for named type registration, which provides more
  /// flexibility for schema evolution and cross-language compatibility.
  ///
  /// @tparam T The struct type to register (must be defined with FORY_STRUCT).
  /// @param ns Namespace for the type (can be empty string).
  /// @param type_name Name of the type within the namespace.
  /// @return Success or error if registration fails.
  ///
  /// Example:
  /// ```cpp
  /// fory.register_struct<MyStruct>("com.example", "MyStruct");
  /// ```
  template <typename T>
  Result<void, Error> register_struct(const std::string &ns,
                                      const std::string &type_name) {
    return register_type([this, &ns, &type_name]() {
      return type_resolver_->template register_by_name<T>(ns, type_name);
    });
  }

  /// Register a struct type with type name only (no namespace).
  ///
  /// Convenience method for registering types without a namespace.
  ///
  /// @tparam T The struct type to register (must be defined with FORY_STRUCT).
  /// @param type_name Name of the type.
  /// @return Success or error if registration fails.
  ///
  /// Example:
  /// ```cpp
  /// fory.register_struct<MyStruct>("MyStruct");
  /// ```
  template <typename T>
  Result<void, Error> register_struct(const std::string &type_name) {
    return register_type([this, &type_name]() {
      return type_resolver_->template register_by_name<T>("", type_name);
    });
  }

  /// Register an enum type with a numeric type ID.
  ///
  /// Use this method to register enum types for cross-language serialization
  /// where types are identified by numeric IDs. The type ID must be unique
  /// across all registered types and match the ID used in other languages.
  ///
  /// @tparam T The enum type to register (must be defined with FORY_ENUM).
  /// @param type_id Unique numeric identifier for this type.
  /// @return Success or error if registration fails.
  ///
  /// Example:
  /// ```cpp
  /// enum class Color { RED, GREEN, BLUE };
  /// FORY_ENUM(Color, RED, GREEN, BLUE);
  ///
  /// fory.register_enum<Color>(1);
  /// ```
  template <typename T> Result<void, Error> register_enum(uint32_t type_id) {
    return register_type([this, type_id]() {
      return type_resolver_->template register_by_id<T>(type_id);
    });
  }

  /// Register an enum type with namespace and type name.
  ///
  /// Use this method for named type registration, which provides more
  /// flexibility for schema evolution and cross-language compatibility.
  ///
  /// @tparam T The enum type to register (must be defined with FORY_ENUM).
  /// @param ns Namespace for the type (can be empty string).
  /// @param type_name Name of the type within the namespace.
  /// @return Success or error if registration fails.
  ///
  /// Example:
  /// ```cpp
  /// fory.register_enum<Color>("com.example", "Color");
  /// ```
  template <typename T>
  Result<void, Error> register_enum(const std::string &ns,
                                    const std::string &type_name) {
    return register_type([this, &ns, &type_name]() {
      return type_resolver_->template register_by_name<T>(ns, type_name);
    });
  }

  /// Register an enum type with type name only (no namespace).
  ///
  /// Convenience method for registering enum types without a namespace.
  ///
  /// @tparam T The enum type to register (must be defined with FORY_ENUM).
  /// @param type_name Name of the type.
  /// @return Success or error if registration fails.
  ///
  /// Example:
  /// ```cpp
  /// fory.register_enum<Color>("Color");
  /// ```
  template <typename T>
  Result<void, Error> register_enum(const std::string &type_name) {
    return register_type([this, &type_name]() {
      return type_resolver_->template register_by_name<T>("", type_name);
    });
  }

  /// Register a union type with a numeric type ID.
  ///
  /// Use this method to register union types with generated custom serializers.
  ///
  /// @tparam T The union type to register (must provide Serializer<T>).
  /// @param type_id Unique numeric identifier for this union type.
  /// @return Success or error if registration fails.
  template <typename T> Result<void, Error> register_union(uint32_t type_id) {
    return register_type([this, type_id]() {
      return type_resolver_->template register_union_by_id<T>(type_id);
    });
  }

  /// Register a union type with namespace and type name.
  ///
  /// @tparam T The union type to register (must provide Serializer<T>).
  /// @param ns Namespace for the type (can be empty string).
  /// @param type_name Name of the type within the namespace.
  /// @return Success or error if registration fails.
  template <typename T>
  Result<void, Error> register_union(const std::string &ns,
                                     const std::string &type_name) {
    return register_type([this, &ns, &type_name]() {
      return type_resolver_->template register_union_by_name<T>(ns, type_name);
    });
  }

  /// Register a union type with type name only (no namespace).
  ///
  /// @tparam T The union type to register (must provide Serializer<T>).
  /// @param type_name Name of the type.
  /// @return Success or error if registration fails.
  template <typename T>
  Result<void, Error> register_union(const std::string &type_name) {
    return register_type([this, &type_name]() {
      return type_resolver_->template register_union_by_name<T>("", type_name);
    });
  }

  /// Register an extension type with a numeric type ID.
  ///
  /// Extension types allow custom serialization logic for types that
  /// don't fit the standard struct serialization pattern.
  ///
  /// @tparam T The extension type to register.
  /// @param type_id Unique numeric identifier for this type.
  /// @return Success or error if registration fails.
  template <typename T>
  Result<void, Error> register_extension_type(uint32_t type_id) {
    return register_type([this, type_id]() {
      return type_resolver_->template register_ext_type_by_id<T>(type_id);
    });
  }

  /// Register an extension type with namespace and type name.
  ///
  /// @tparam T The extension type to register.
  /// @param ns Namespace for the type (can be empty string).
  /// @param type_name Name of the type within the namespace.
  /// @return Success or error if registration fails.
  template <typename T>
  Result<void, Error> register_extension_type(const std::string &ns,
                                              const std::string &type_name) {
    return register_type([this, &ns, &type_name]() {
      return type_resolver_->template register_ext_type_by_name<T>(ns,
                                                                   type_name);
    });
  }

  /// Register an extension type with type name only (no namespace).
  ///
  /// @tparam T The extension type to register.
  /// @param type_name Name of the type.
  /// @return Success or error if registration fails.
  template <typename T>
  Result<void, Error> register_extension_type(const std::string &type_name) {
    return register_type([this, &type_name]() {
      return type_resolver_->template register_ext_type_by_name<T>("",
                                                                   type_name);
    });
  }

private:
  template <typename RegisterFn>
  Result<void, Error> register_type(RegisterFn &&fn) {
    std::lock_guard<std::mutex> lock(registration_mutex_);
    if (FORY_PREDICT_FALSE(registration_locked_)) {
      return Unexpected(Error::invalid(
          "Cannot register types after first serialize/deserialize call"));
    }
    return std::forward<RegisterFn>(fn)();
  }

protected:
  void lock_registration() const {
    std::lock_guard<std::mutex> lock(registration_mutex_);
    registration_locked_ = true;
  }

  /// Protected constructor - only derived classes can instantiate.
  explicit BaseFory(const Config &config,
                    std::shared_ptr<TypeResolver> resolver)
      : config_(config), type_resolver_(std::move(resolver)) {}

  // Non-copyable
  BaseFory(const BaseFory &) = delete;
  BaseFory &operator=(const BaseFory &) = delete;

  // Non-movable (to ensure stable 'this' pointer for pool lambdas)
  BaseFory(BaseFory &&) = delete;
  BaseFory &operator=(BaseFory &&) = delete;

  Config config_;
  std::shared_ptr<TypeResolver> type_resolver_;
  mutable std::mutex registration_mutex_;
  mutable bool registration_locked_{false};
};

// ============================================================================
// Fory - Single-threaded serialization (fastest)
// ============================================================================

/// Single-threaded Fory serialization class.
///
/// This class provides the fastest serialization by holding WriteContext and
/// ReadContext directly without pool overhead. NOT thread-safe - use one
/// instance per thread or use ThreadSafeFory for multi-threaded scenarios.
///
/// Example:
/// ```cpp
/// auto fory = Fory::builder().xlang(true).build();
/// fory.register_struct<MyStruct>(1);
///
/// MyStruct obj{...};
/// auto result = fory.serialize(obj);
/// ```
class Fory : public BaseFory {
public:
  static ForyBuilder builder() { return ForyBuilder(); }

  /// Serialize an object to a new byte vector.
  ///
  /// @tparam T The type of object to serialize.
  /// @param obj The object to serialize.
  /// @return Vector containing serialized bytes, or error.
  template <typename T>
  Result<std::vector<uint8_t>, Error> serialize(const T &obj) {
    if (FORY_PREDICT_FALSE(!finalized_)) {
      ensure_finalized();
    }
    WriteContextGuard guard(*write_ctx_);
    Buffer &buffer = write_ctx_->buffer();

    FORY_RETURN_NOT_OK(serialize_impl(obj, buffer));

    std::vector<uint8_t> result(buffer.writer_index());
    std::memcpy(result.data(), buffer.data(), buffer.writer_index());
    return result;
  }

  /// Serialize an object to an existing Buffer (fastest path).
  ///
  /// @tparam T The type of object to serialize.
  /// @param buffer The buffer to write to.
  /// @param obj The object to serialize.
  /// @return Number of bytes written, or error.
  template <typename T>
  FORY_ALWAYS_INLINE Result<size_t, Error> serialize_to(Buffer &buffer,
                                                        const T &obj) {
    if (FORY_PREDICT_FALSE(!finalized_)) {
      ensure_finalized();
    }
    // Swap in the caller's buffer so all writes go there.
    buffer.swap(write_ctx_->buffer());
    auto result = serialize_impl(obj, write_ctx_->buffer());
    buffer.swap(write_ctx_->buffer());
    // reset internal state after use without clobbering caller buffer.
    write_ctx_->reset();
    return result;
  }

  /// Serialize an object to an existing byte vector (zero-copy).
  ///
  /// This method appends serialized data directly to the output vector,
  /// avoiding any intermediate copies. The vector will be resized to
  /// fit the serialized data.
  ///
  /// @tparam T The type of object to serialize.
  /// @param output The vector to append to.
  /// @param obj The object to serialize.
  /// @return Number of bytes written, or error.
  template <typename T>
  Result<size_t, Error> serialize_to(std::vector<uint8_t> &output,
                                     const T &obj) {
    // Wrap the output vector in a Buffer for zero-copy serialization
    // writer_index starts at output.size() for appending
    Buffer buffer(output);

    // Forward to Buffer version
    auto result = serialize_to(buffer, obj);

    // Resize vector to actual written size
    output.resize(buffer.writer_index());
    return result;
  }

  /// Deserialize an object from a byte array.
  ///
  /// @tparam T The type of object to deserialize.
  /// @param data Pointer to serialized data.
  /// @param size Size of serialized data in bytes.
  /// @return Deserialized object, or error.
  template <typename T>
  Result<T, Error> deserialize(const uint8_t *data, size_t size) {
    if (FORY_PREDICT_FALSE(!finalized_)) {
      ensure_finalized();
    }
    if (data == nullptr) {
      return Unexpected(Error::invalid("Data pointer is null"));
    }
    if (size == 0) {
      return Unexpected(Error::invalid("Data size is zero"));
    }

    Buffer buffer(const_cast<uint8_t *>(data), static_cast<uint32_t>(size),
                  false);

    FORY_TRY(header, read_header(buffer));
    if (header.is_null) {
      return Unexpected(Error::invalid_data("Cannot deserialize null object"));
    }
    if (FORY_PREDICT_FALSE(header.is_xlang != config_.xlang)) {
      return Unexpected(Error::invalid_data(
          "Protocol mismatch: payload xlang=" +
          std::string(header.is_xlang ? "true" : "false") +
          ", local xlang=" + std::string(config_.xlang ? "true" : "false")));
    }

    read_ctx_->attach(buffer);
    ReadContextGuard guard(*read_ctx_);
    return deserialize_impl<T>(buffer);
  }

  /// Deserialize an object from a byte vector.
  ///
  /// @tparam T The type of object to deserialize.
  /// @param data Vector containing serialized data.
  /// @return Deserialized object, or error.
  template <typename T>
  Result<T, Error> deserialize(const std::vector<uint8_t> &data) {
    return deserialize<T>(data.data(), data.size());
  }

  /// Deserialize an object from a Buffer, updating the buffer's reader_index.
  ///
  /// This overload reads from the current reader_index position and updates
  /// the reader_index after deserialization, allowing multiple objects to
  /// be read from the same buffer sequentially.
  ///
  /// @tparam T The type of object to deserialize.
  /// @param buffer Buffer to read from. Its reader_index will be updated.
  /// @return Deserialized object, or error.
  template <typename T> Result<T, Error> deserialize(Buffer &buffer) {
    struct StreamShrinkGuard {
      Buffer *buffer = nullptr;
      ~StreamShrinkGuard() {
        if (buffer != nullptr) {
          buffer->shrink_stream_buffer();
        }
      }
    };
    StreamShrinkGuard shrink_guard{buffer.is_stream_backed() ? &buffer
                                                             : nullptr};

    if (FORY_PREDICT_FALSE(!finalized_)) {
      ensure_finalized();
    }
    auto header_result = read_header(buffer);
    if (FORY_PREDICT_FALSE(!header_result.ok())) {
      return Unexpected(std::move(header_result).error());
    }
    auto header = std::move(header_result).value();
    if (header.is_null) {
      return Unexpected(Error::invalid_data("Cannot deserialize null object"));
    }
    if (FORY_PREDICT_FALSE(header.is_xlang != config_.xlang)) {
      return Unexpected(Error::invalid_data(
          "Protocol mismatch: payload xlang=" +
          std::string(header.is_xlang ? "true" : "false") +
          ", local xlang=" + std::string(config_.xlang ? "true" : "false")));
    }

    read_ctx_->attach(buffer);
    ReadContextGuard guard(*read_ctx_);
    return deserialize_impl<T>(buffer);
  }

  /// Deserialize an object from a stream reader.
  ///
  /// This overload obtains the reader-owned Buffer via get_buffer() and
  /// continues deserialization on that buffer.
  ///
  /// @tparam T The type of object to deserialize.
  /// @param stream_reader Stream reader to read from.
  /// @return Deserialized object, or error.
  template <typename T>
  Result<T, Error> deserialize(StreamReader &stream_reader) {
    Buffer &buffer = stream_reader.get_buffer();
    return deserialize<T>(buffer);
  }

  /// Deserialize an object from ForyInputStream.
  ///
  /// @tparam T The type of object to deserialize.
  /// @param stream Input stream wrapper to read from.
  /// @return Deserialized object, or error.
  template <typename T> Result<T, Error> deserialize(ForyInputStream &stream) {
    return deserialize<T>(static_cast<StreamReader &>(stream));
  }

  // ==========================================================================
  // Advanced Access
  // ==========================================================================

  /// Access the internal WriteContext (for advanced use).
  ///
  /// Use this for direct manipulation of the serialization context.
  /// Most users should use the serialize() methods instead.
  WriteContext &write_context() { return *write_ctx_; }

  /// Access the internal ReadContext (for advanced use).
  ///
  /// Use this for direct manipulation of the deserialization context.
  /// Most users should use the deserialize() methods instead.
  ReadContext &read_context() { return *read_ctx_; }

private:
  /// Constructor for ForyBuilder - resolver will be finalized lazily.
  explicit Fory(const Config &config, std::shared_ptr<TypeResolver> resolver)
      : BaseFory(config, std::move(resolver)), finalized_(false),
        precomputed_header_(compute_header(config.xlang)) {}

  /// Constructor for ThreadSafeFory pool - resolver is already finalized.
  struct PreFinalized {};
  explicit Fory(const Config &config, std::shared_ptr<TypeResolver> resolver,
                PreFinalized)
      : BaseFory(config, std::move(resolver)), finalized_(false),
        precomputed_header_(compute_header(config.xlang)) {
    // Pre-finalized, immediately create contexts
    ensure_finalized();
  }

  /// Finalize the type resolver on first use.
  void ensure_finalized() {
    if (!finalized_) {
      lock_registration();
      auto final_result = type_resolver_->build_final_type_resolver();
      FORY_CHECK(final_result.ok())
          << "Failed to build finalized TypeResolver: "
          << final_result.error().to_string();
      // Replace with finalized resolver
      auto finalized_resolver = std::move(final_result).value();
      // Create contexts with cloned resolvers
      write_ctx_.emplace(config_, finalized_resolver->clone());
      read_ctx_.emplace(config_, finalized_resolver->clone());
      // Store finalized resolver
      type_resolver_ = std::move(finalized_resolver);
      finalized_ = true;
    }
  }

  /// Compute the precomputed header value.
  static uint8_t compute_header(bool xlang) {
    uint8_t flags = 0;
    if (xlang) {
      flags |= (1 << 1); // bit 1: xlang flag
    }
    return flags;
  }

  /// Core serialization implementation.
  /// TypeMeta is written inline using streaming protocol (no deferred writing).
  template <typename T>
  Result<size_t, Error> serialize_impl(const T &obj, Buffer &buffer) {
    size_t start_pos = buffer.writer_index();

    // write precomputed header (1 byte flags)
    buffer.grow(1);
    buffer.unsafe_put_byte(buffer.writer_index(), precomputed_header_);
    buffer.increase_writer_index(1);

    // Top-level serialization: use Tracking if ref tracking is enabled,
    // otherwise NullOnly for nullable handling
    // TypeMeta is written inline during serialization (streaming protocol)
    const RefMode top_level_ref_mode =
        write_ctx_->track_ref() ? RefMode::Tracking : RefMode::NullOnly;
    Serializer<T>::write(obj, *write_ctx_, top_level_ref_mode, true);
    // Check for errors at serialization boundary
    if (FORY_PREDICT_FALSE(write_ctx_->has_error())) {
      return Unexpected(write_ctx_->take_error());
    }

    return buffer.writer_index() - start_pos;
  }

  /// Core deserialization implementation.
  /// TypeMeta is read inline using streaming protocol.
  template <typename T> Result<T, Error> deserialize_impl(Buffer &buffer) {
    // Top-level deserialization: use Tracking if ref tracking is enabled,
    // otherwise NullOnly for nullable handling
    // TypeMeta is read inline during deserialization (streaming protocol)
    const RefMode top_level_ref_mode =
        read_ctx_->track_ref() ? RefMode::Tracking : RefMode::NullOnly;
    T result = Serializer<T>::read(*read_ctx_, top_level_ref_mode, true);
    // Check for errors at deserialization boundary
    if (FORY_PREDICT_FALSE(read_ctx_->has_error())) {
      return Unexpected(read_ctx_->take_error());
    }

    read_ctx_->ref_reader().resolve_callbacks();
    return result;
  }

  bool finalized_;
  uint8_t precomputed_header_;
  std::optional<WriteContext> write_ctx_;
  std::optional<ReadContext> read_ctx_;

  friend class ForyBuilder;
  friend class ThreadSafeFory;
};

// ============================================================================
// ThreadSafeFory - Thread-safe serialization with Fory pool
// ============================================================================

/// Thread-safe Fory serialization class.
///
/// This class uses a pool of Fory instances to provide thread-safe
/// serialization. Each thread acquires a Fory instance from the pool,
/// uses it for serialization/deserialization, and returns it when done.
///
/// Slightly slower than single-threaded Fory due to pool overhead, but
/// safe to use from multiple threads concurrently.
///
/// Example:
/// ```cpp
/// auto fory = Fory::builder().xlang(true).build_thread_safe();
/// fory.register_struct<MyStruct>(1);
///
/// // Can be used from multiple threads safely
/// std::thread t1([&]() {
///   auto result = fory.serialize(obj1);
/// });
/// std::thread t2([&]() {
///   auto result = fory.serialize(obj2);
/// });
/// ```
class ThreadSafeFory : public BaseFory {
public:
  template <typename T>
  Result<std::vector<uint8_t>, Error> serialize(const T &obj) {
    auto fory_handle = fory_pool_.acquire();
    return fory_handle->serialize(obj);
  }

  template <typename T>
  Result<size_t, Error> serialize_to(Buffer &buffer, const T &obj) {
    auto fory_handle = fory_pool_.acquire();
    return fory_handle->serialize_to(buffer, obj);
  }

  template <typename T>
  Result<size_t, Error> serialize_to(std::vector<uint8_t> &output,
                                     const T &obj) {
    auto fory_handle = fory_pool_.acquire();
    return fory_handle->serialize_to(output, obj);
  }

  template <typename T>
  Result<T, Error> deserialize(const uint8_t *data, size_t size) {
    auto fory_handle = fory_pool_.acquire();
    return fory_handle->template deserialize<T>(data, size);
  }

  template <typename T>
  Result<T, Error> deserialize(const std::vector<uint8_t> &data) {
    return deserialize<T>(data.data(), data.size());
  }

  template <typename T>
  Result<T, Error> deserialize(StreamReader &stream_reader) {
    auto fory_handle = fory_pool_.acquire();
    return fory_handle->template deserialize<T>(stream_reader);
  }

  template <typename T> Result<T, Error> deserialize(ForyInputStream &stream) {
    auto fory_handle = fory_pool_.acquire();
    return fory_handle->template deserialize<T>(stream);
  }

private:
  explicit ThreadSafeFory(const Config &config,
                          std::shared_ptr<TypeResolver> resolver)
      : BaseFory(config, std::move(resolver)), finalized_resolver_(),
        finalized_once_flag_(), fory_pool_([this]() {
          return std::unique_ptr<Fory>(new Fory(
              config_, get_finalized_resolver(), Fory::PreFinalized{}));
        }) {}

  std::shared_ptr<TypeResolver> get_finalized_resolver() const {
    std::call_once(finalized_once_flag_, [this]() {
      lock_registration();
      auto final_result = type_resolver_->build_final_type_resolver();
      FORY_CHECK(final_result.ok())
          << "Failed to build finalized TypeResolver: "
          << final_result.error().to_string();
      finalized_resolver_ = std::move(final_result).value();
    });
    return finalized_resolver_->clone();
  }

  mutable std::shared_ptr<TypeResolver> finalized_resolver_;
  mutable std::once_flag finalized_once_flag_;
  util::Pool<Fory> fory_pool_;

  friend class ForyBuilder;
};

// ============================================================================
// ForyBuilder Implementation
// ============================================================================

inline std::shared_ptr<TypeResolver> ForyBuilder::get_finalized_resolver() {
  if (!type_resolver_) {
    type_resolver_ = std::make_shared<TypeResolver>();
  }
  type_resolver_->apply_config(config_);
  auto final_result = type_resolver_->build_final_type_resolver();
  FORY_CHECK(final_result.ok()) << "Failed to build finalized TypeResolver: "
                                << final_result.error().to_string();
  return std::move(final_result).value();
}

inline Fory ForyBuilder::build() {
  if (!type_resolver_) {
    type_resolver_ = std::make_shared<TypeResolver>();
  }
  type_resolver_->apply_config(config_);
  // Don't finalize yet - allow type registration, finalize on first use
  return Fory(config_, type_resolver_);
}

inline ThreadSafeFory ForyBuilder::build_thread_safe() {
  if (!type_resolver_) {
    type_resolver_ = std::make_shared<TypeResolver>();
  }
  type_resolver_->apply_config(config_);
  // ThreadSafeFory builds finalized resolver lazily
  return ThreadSafeFory(config_, type_resolver_);
}

} // namespace serialization
} // namespace fory
