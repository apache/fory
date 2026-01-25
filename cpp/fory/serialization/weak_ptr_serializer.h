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

#include "fory/serialization/serializer.h"
#include "fory/serialization/smart_ptr_serializers.h"
#include <memory>

namespace fory {
namespace serialization {

// ============================================================================
// SharedWeak<T> - A serializable weak pointer wrapper
// ============================================================================

/// A serializable wrapper around `std::weak_ptr<T>`.
///
/// `SharedWeak<T>` is designed for use in graph-like structures where nodes
/// may need to hold non-owning references to other nodes (e.g., parent
/// pointers), and you still want them to round-trip through serialization
/// while preserving reference identity.
///
/// Unlike a raw `std::weak_ptr<T>`, cloning `SharedWeak` keeps all clones
/// pointing to the same internal storage, so updates via deserialization
/// callbacks affect all copies. This is critical for forward reference
/// resolution.
///
/// ## When to use
///
/// Use this wrapper when your graph structure contains parent/child
/// relationships or other shared edges where a strong pointer would cause a
/// reference cycle. Storing a weak pointer avoids owning the target strongly,
/// but serialization will preserve the link by reference ID.
///
/// ## Example - Parent/Child Graph
///
/// ```cpp
/// struct Node {
///   int32_t value;
///   SharedWeak<Node> parent;              // Non-owning back-reference
///   std::vector<std::shared_ptr<Node>> children;  // Owning references
///   FORY_STRUCT(Node, value, parent, children);
/// };
///
/// auto parent = std::make_shared<Node>();
/// parent->value = 1;
///
/// auto child = std::make_shared<Node>();
/// child->value = 2;
/// child->parent = SharedWeak<Node>::from(parent);
/// parent->children.push_back(child);
///
/// // Serialize and deserialize
/// auto bytes = fory.serialize(parent);
/// auto result = fory.deserialize<std::shared_ptr<Node>>(bytes);
///
/// // Verify parent reference is preserved
/// assert(result->children[0]->parent.upgrade() == result);
/// ```
///
/// ## Thread Safety
///
/// The `update()` method is NOT thread-safe. This is acceptable because
/// deserialization is single-threaded. Do not share SharedWeak instances
/// across threads during deserialization.
///
/// ## Null handling
///
/// If the target `std::shared_ptr<T>` has been dropped or never assigned,
/// `upgrade()` returns `nullptr` and serialization will write a `NULL_FLAG`
/// instead of a reference ID.
template <typename T> class SharedWeak {
public:
  /// Default constructor - creates an empty weak pointer.
  SharedWeak() : inner_(std::make_shared<Inner>()) {}

  /// Create a SharedWeak from a shared_ptr.
  ///
  /// @param ptr The shared_ptr to create a weak reference to.
  /// @return A SharedWeak pointing to the same object.
  static SharedWeak from(const std::shared_ptr<T> &ptr) {
    SharedWeak result;
    result.inner_->weak = ptr;
    return result;
  }

  /// Create a SharedWeak from an existing weak_ptr.
  ///
  /// @param weak The weak_ptr to wrap.
  /// @return A SharedWeak wrapping the weak_ptr.
  static SharedWeak from_weak(std::weak_ptr<T> weak) {
    SharedWeak result;
    result.inner_->weak = std::move(weak);
    return result;
  }

  /// Try to upgrade to a strong shared_ptr.
  ///
  /// @return The shared_ptr if the target is still alive, nullptr otherwise.
  std::shared_ptr<T> upgrade() const { return inner_->weak.lock(); }

  /// Update the internal weak pointer.
  ///
  /// This method is used during deserialization to resolve forward references.
  /// All clones of this SharedWeak will see the update because they share
  /// the same internal storage.
  ///
  /// @param weak The new weak_ptr value.
  void update(std::weak_ptr<T> weak) { inner_->weak = std::move(weak); }

  /// Check if the weak pointer is expired.
  ///
  /// @return true if the target has been destroyed or was never set.
  bool expired() const { return inner_->weak.expired(); }

  /// Get the use count of the target object.
  ///
  /// @return The number of shared_ptr instances pointing to the target,
  ///         or 0 if the target has been destroyed.
  long use_count() const { return inner_->weak.use_count(); }

  /// Get the underlying weak_ptr.
  ///
  /// @return A copy of the internal weak_ptr.
  std::weak_ptr<T> get_weak() const { return inner_->weak; }

  /// Check if two SharedWeak instances point to the same target.
  ///
  /// @param other The other SharedWeak to compare.
  /// @return true if both point to the same object (or both are expired).
  bool owner_equals(const SharedWeak &other) const {
    return !inner_->weak.owner_before(other.inner_->weak) &&
           !other.inner_->weak.owner_before(inner_->weak);
  }

  /// Copy constructor - shares the internal storage.
  SharedWeak(const SharedWeak &other) = default;

  /// Move constructor.
  SharedWeak(SharedWeak &&other) noexcept = default;

  /// Copy assignment - shares the internal storage.
  SharedWeak &operator=(const SharedWeak &other) = default;

  /// Move assignment.
  SharedWeak &operator=(SharedWeak &&other) noexcept = default;

private:
  /// Internal storage that is shared across all copies.
  struct Inner {
    std::weak_ptr<T> weak;
  };

  /// Shared pointer to internal storage.
  /// All copies of SharedWeak share the same Inner instance.
  std::shared_ptr<Inner> inner_;
};

// ============================================================================
// TypeIndex for SharedWeak<T>
// ============================================================================

template <typename T> struct TypeIndex<SharedWeak<T>> {
  static constexpr uint64_t value =
      fnv1a_64_combine(fnv1a_64("fory::SharedWeak"), type_index<T>());
};

// ============================================================================
// is_nullable trait for SharedWeak<T>
// ============================================================================

template <typename T> struct is_nullable<SharedWeak<T>> : std::true_type {};

// ============================================================================
// nullable_element_type for SharedWeak<T>
// ============================================================================

template <typename T> struct nullable_element_type<SharedWeak<T>> {
  using type = T;
};

// ============================================================================
// is_shared_weak detection trait
// ============================================================================

template <typename T> struct is_shared_weak : std::false_type {};

template <typename T> struct is_shared_weak<SharedWeak<T>> : std::true_type {};

template <typename T>
inline constexpr bool is_shared_weak_v = is_shared_weak<T>::value;

// ============================================================================
// Serializer<SharedWeak<T>>
// ============================================================================

/// Serializer for SharedWeak<T>.
///
/// SharedWeak requires reference tracking to be enabled (track_ref=true).
/// During serialization:
/// - If the weak can upgrade to a strong pointer, writes a reference to it.
/// - If the weak is null/expired, writes NULL_FLAG.
///
/// During deserialization:
/// - NULL_FLAG: returns empty SharedWeak.
/// - REF_VALUE_FLAG: deserializes the object, stores it, returns weak to it.
/// - REF_FLAG: looks up existing ref; if not found (forward ref), registers
///   a callback to update the weak pointer later.
template <typename T> struct Serializer<SharedWeak<T>> {
  static_assert(!std::is_pointer_v<T>,
                "SharedWeak of raw pointer types is not supported");

  /// Use the inner type's type_id (same as Rust's behavior).
  static constexpr TypeId type_id = Serializer<T>::type_id;

  static inline void write_type_info(WriteContext &ctx) {
    Serializer<T>::write_type_info(ctx);
  }

  static inline void read_type_info(ReadContext &ctx) {
    Serializer<T>::read_type_info(ctx);
  }

  static inline void write(const SharedWeak<T> &weak, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    // SharedWeak requires track_ref to be enabled
    if (FORY_PREDICT_FALSE(!ctx.track_ref())) {
      ctx.set_error(
          Error::invalid_ref("SharedWeak requires track_ref to be enabled. Use "
                             "Fory::builder().track_ref(true).build()"));
      return;
    }

    // SharedWeak requires ref metadata - refuse RefMode::None
    if (FORY_PREDICT_FALSE(ref_mode == RefMode::None)) {
      ctx.set_error(
          Error::invalid_ref("SharedWeak requires ref_mode != RefMode::None"));
      return;
    }

    // Try to upgrade the weak pointer
    std::shared_ptr<T> strong = weak.upgrade();
    if (!strong) {
      // Weak is expired or empty - write null
      ctx.write_int8(NULL_FLAG);
      return;
    }

    // Try to write as reference to existing object
    if (ctx.ref_writer().try_write_shared_ref(ctx, strong)) {
      // Reference was written, we're done
      return;
    }

    // First occurrence - write type info and data
    if (write_type) {
      Serializer<T>::write_type_info(ctx);
    }
    Serializer<T>::write_data_generic(*strong, ctx, has_generics);
  }

  static inline void write_data(const SharedWeak<T> &weak, WriteContext &ctx) {
    ctx.set_error(Error::not_allowed(
        "SharedWeak should be written using write() to handle reference "
        "tracking properly"));
  }

  static inline void write_data_generic(const SharedWeak<T> &weak,
                                        WriteContext &ctx, bool has_generics) {
    ctx.set_error(Error::not_allowed(
        "SharedWeak should be written using write() to handle reference "
        "tracking properly"));
  }

  static inline SharedWeak<T> read(ReadContext &ctx, RefMode ref_mode,
                                   bool read_type) {
    // SharedWeak requires track_ref to be enabled
    if (FORY_PREDICT_FALSE(!ctx.track_ref())) {
      ctx.set_error(
          Error::invalid_ref("SharedWeak requires track_ref to be enabled"));
      return SharedWeak<T>();
    }

    // Read the reference flag
    int8_t flag = ctx.read_int8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return SharedWeak<T>();
    }

    switch (flag) {
    case NULL_FLAG:
      // Null weak pointer
      return SharedWeak<T>();

    case REF_VALUE_FLAG: {
      // First occurrence - deserialize the object
      uint32_t reserved_ref_id = ctx.ref_reader().reserve_ref_id();

      // Read type info if needed
      if (read_type) {
        Serializer<T>::read_type_info(ctx);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return SharedWeak<T>();
        }
      }

      // Read the data
      T data = Serializer<T>::read_data(ctx);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return SharedWeak<T>();
      }

      // Create shared_ptr and store it
      auto strong = std::make_shared<T>(std::move(data));
      ctx.ref_reader().store_shared_ref_at(reserved_ref_id, strong);

      // Return weak pointer to it
      return SharedWeak<T>::from(strong);
    }

    case REF_FLAG: {
      // Reference to existing object
      uint32_t ref_id = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return SharedWeak<T>();
      }

      // Try to get the referenced object
      auto ref_result = ctx.ref_reader().template get_shared_ref<T>(ref_id);
      if (ref_result.ok()) {
        // Object already deserialized - return weak pointer to it
        return SharedWeak<T>::from(ref_result.value());
      }

      // Forward reference - create empty weak and register callback
      SharedWeak<T> result;
      add_weak_update_callback(ctx.ref_reader(), ref_id, result);
      return result;
    }

    case NOT_NULL_VALUE_FLAG:
      ctx.set_error(
          Error::invalid_ref("SharedWeak cannot hold a NOT_NULL_VALUE ref"));
      return SharedWeak<T>();

    default:
      ctx.set_error(Error::invalid_ref("Unexpected reference flag value: " +
                                       std::to_string(static_cast<int>(flag))));
      return SharedWeak<T>();
    }
  }

  static inline SharedWeak<T> read_with_type_info(ReadContext &ctx,
                                                  RefMode ref_mode,
                                                  const TypeInfo &type_info) {
    // SharedWeak requires track_ref to be enabled
    if (FORY_PREDICT_FALSE(!ctx.track_ref())) {
      ctx.set_error(
          Error::invalid_ref("SharedWeak requires track_ref to be enabled"));
      return SharedWeak<T>();
    }

    // Read the reference flag
    int8_t flag = ctx.read_int8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return SharedWeak<T>();
    }

    switch (flag) {
    case NULL_FLAG:
      return SharedWeak<T>();

    case REF_VALUE_FLAG: {
      uint32_t reserved_ref_id = ctx.ref_reader().reserve_ref_id();

      // Read the data using type info
      T data =
          Serializer<T>::read_with_type_info(ctx, RefMode::None, type_info);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return SharedWeak<T>();
      }

      auto strong = std::make_shared<T>(std::move(data));
      ctx.ref_reader().store_shared_ref_at(reserved_ref_id, strong);

      return SharedWeak<T>::from(strong);
    }

    case REF_FLAG: {
      uint32_t ref_id = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return SharedWeak<T>();
      }

      auto ref_result = ctx.ref_reader().template get_shared_ref<T>(ref_id);
      if (ref_result.ok()) {
        return SharedWeak<T>::from(ref_result.value());
      }

      // Forward reference
      SharedWeak<T> result;
      add_weak_update_callback(ctx.ref_reader(), ref_id, result);
      return result;
    }

    case NOT_NULL_VALUE_FLAG:
      ctx.set_error(
          Error::invalid_ref("SharedWeak cannot hold a NOT_NULL_VALUE ref"));
      return SharedWeak<T>();

    default:
      ctx.set_error(Error::invalid_ref("Unexpected reference flag value: " +
                                       std::to_string(static_cast<int>(flag))));
      return SharedWeak<T>();
    }
  }

  static inline SharedWeak<T> read_data(ReadContext &ctx) {
    ctx.set_error(Error::not_allowed(
        "SharedWeak should be read using read() or read_with_type_info() to "
        "handle reference tracking properly"));
    return SharedWeak<T>();
  }

private:
  /// Add a callback to update the weak pointer when the strong pointer becomes
  /// available.
  static void add_weak_update_callback(RefReader &ref_reader, uint32_t ref_id,
                                       SharedWeak<T> &weak) {
    // Capture a copy of the SharedWeak - it shares internal storage
    SharedWeak<T> weak_copy = weak;
    ref_reader.add_update_callback(
        ref_id, [weak_copy, ref_id](const RefReader &reader) mutable {
          auto ref_result = reader.template get_shared_ref<T>(ref_id);
          if (ref_result.ok()) {
            weak_copy.update(std::weak_ptr<T>(ref_result.value()));
          }
        });
  }
};

} // namespace serialization
} // namespace fory
