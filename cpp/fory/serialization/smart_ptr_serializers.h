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
#include <memory>
#include <optional>
#include <string>

namespace fory {
namespace serialization {

// ============================================================================
// Helper for polymorphic deserialization
// ============================================================================

/// Reads polymorphic data using the appropriate harness function based on mode.
/// In compatible mode, uses read_compatible_fn if available to handle schema
/// evolution. Otherwise, uses read_data_fn for direct deserialization.
/// Returns nullptr and sets ctx.error() on failure.
inline void *read_polymorphic_harness_data(ReadContext &ctx,
                                           const TypeInfo *type_info) {
  if (ctx.is_compatible()) {
    if (!type_info->harness.read_compatible_fn) {
      ctx.set_error(Error::type_error(
          "No harness read_compatible function for polymorphic type "
          "deserialization in compatible mode"));
      return nullptr;
    }
    return type_info->harness.read_compatible_fn(ctx, type_info);
  }
  if (!type_info->harness.read_data_fn) {
    ctx.set_error(Error::type_error(
        "No harness read function for polymorphic type deserialization"));
    return nullptr;
  }
  return type_info->harness.read_data_fn(ctx);
}

/// Overload for TypeInfo reference.
inline void *read_polymorphic_harness_data(ReadContext &ctx,
                                           const TypeInfo &type_info) {
  return read_polymorphic_harness_data(ctx, &type_info);
}

// ============================================================================
// std::optional serializer
// ============================================================================

/// Serializer for std::optional<T>
///
/// Serializes optional values with null handling.
/// Uses NOT_NULL_VALUE_FLAG for values, NULL_FLAG for nullopt.
template <typename T> struct Serializer<std::optional<T>> {
  // Use the inner type's type_id
  static constexpr TypeId type_id = Serializer<T>::type_id;

  static inline void write_type_info(WriteContext &ctx) {
    Serializer<T>::write_type_info(ctx);
  }

  static inline void read_type_info(ReadContext &ctx) {
    Serializer<T>::read_type_info(ctx);
  }

  static inline void write(const std::optional<T> &opt, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    constexpr bool inner_is_nullable = is_nullable_v<T>;

    if (ref_mode == RefMode::None) {
      if (!opt.has_value()) {
        ctx.set_error(Error::invalid("std::optional requires ref_mode != "
                                     "RefMode::None to encode null state"));
        return;
      }
      Serializer<T>::write(*opt, ctx, RefMode::None, write_type, has_generics);
      return;
    }

    if (!opt.has_value()) {
      ctx.write_int8(NULL_FLAG);
      return;
    }

    if constexpr (inner_is_nullable) {
      Serializer<T>::write(*opt, ctx, RefMode::NullOnly, write_type,
                           has_generics);
    } else {
      ctx.write_int8(NOT_NULL_VALUE_FLAG);
      Serializer<T>::write(*opt, ctx, RefMode::None, write_type, has_generics);
    }
  }

  static inline void write_data(const std::optional<T> &opt,
                                WriteContext &ctx) {
    if (!opt.has_value()) {
      ctx.set_error(
          Error::invalid("std::optional write_data requires value present"));
      return;
    }
    Serializer<T>::write_data(*opt, ctx);
  }

  static inline void write_data_generic(const std::optional<T> &opt,
                                        WriteContext &ctx, bool has_generics) {
    if (!opt.has_value()) {
      ctx.set_error(
          Error::invalid("std::optional write_data requires value present"));
      return;
    }
    Serializer<T>::write_data_generic(*opt, ctx, has_generics);
  }

  static inline std::optional<T> read(ReadContext &ctx, RefMode ref_mode,
                                      bool read_type) {
    constexpr bool inner_is_nullable = is_nullable_v<T>;
    if (ref_mode == RefMode::None) {
      T value = Serializer<T>::read(ctx, RefMode::None, read_type);
      if (ctx.has_error()) {
        return std::nullopt;
      }
      return std::optional<T>(std::move(value));
    }

    const uint32_t flag_pos = ctx.buffer().reader_index();
    int8_t flag = ctx.read_int8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::nullopt;
    }

    if (flag == NULL_FLAG) {
      return std::optional<T>(std::nullopt);
    }

    if constexpr (inner_is_nullable) {
      // Rewind so the inner serializer can consume the reference metadata.
      ctx.buffer().ReaderIndex(flag_pos);
      // Pass ref_mode directly - let inner serializer handle ref tracking
      T value = Serializer<T>::read(ctx, ref_mode, read_type);
      if (ctx.has_error()) {
        return std::nullopt;
      }
      return std::optional<T>(std::move(value));
    }

    if (flag != NOT_NULL_VALUE_FLAG && flag != REF_VALUE_FLAG) {
      ctx.set_error(
          Error::invalid_ref("Unexpected reference flag for std::optional: " +
                             std::to_string(static_cast<int>(flag))));
      return std::nullopt;
    }

    T value = Serializer<T>::read(ctx, RefMode::None, read_type);
    if (ctx.has_error()) {
      return std::nullopt;
    }
    return std::optional<T>(std::move(value));
  }

  static inline std::optional<T>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    constexpr bool inner_is_nullable = is_nullable_v<T>;

    if (ref_mode == RefMode::None) {
      T value =
          Serializer<T>::read_with_type_info(ctx, RefMode::None, type_info);
      if (ctx.has_error()) {
        return std::nullopt;
      }
      return std::optional<T>(std::move(value));
    }

    const uint32_t flag_pos = ctx.buffer().reader_index();
    int8_t flag = ctx.read_int8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::nullopt;
    }

    if (flag == NULL_FLAG) {
      return std::optional<T>(std::nullopt);
    }

    if constexpr (inner_is_nullable) {
      // Rewind so the inner serializer can consume the reference metadata.
      ctx.buffer().ReaderIndex(flag_pos);
      // Pass ref_mode directly - let inner serializer handle ref tracking
      T value = Serializer<T>::read_with_type_info(ctx, ref_mode, type_info);
      if (ctx.has_error()) {
        return std::nullopt;
      }
      return std::optional<T>(std::move(value));
    }

    if (flag != NOT_NULL_VALUE_FLAG && flag != REF_VALUE_FLAG) {
      ctx.set_error(
          Error::invalid_ref("Unexpected reference flag for std::optional: " +
                             std::to_string(static_cast<int>(flag))));
      return std::nullopt;
    }

    T value = Serializer<T>::read_with_type_info(ctx, RefMode::None, type_info);
    if (ctx.has_error()) {
      return std::nullopt;
    }
    return std::optional<T>(std::move(value));
  }

  static inline std::optional<T> read_data(ReadContext &ctx) {
    T value = Serializer<T>::read_data(ctx);
    if (ctx.has_error()) {
      return std::nullopt;
    }
    return std::optional<T>(std::move(value));
  }
};

// ============================================================================
// std::shared_ptr serializer
// ============================================================================

// Helper to get type_id for shared_ptr without instantiating Serializer for
// polymorphic types
template <typename T, bool IsPolymorphic> struct SharedPtrTypeIdHelper {
  static constexpr TypeId value = Serializer<T>::type_id;
};

template <typename T> struct SharedPtrTypeIdHelper<T, true> {
  static constexpr TypeId value = TypeId::UNKNOWN;
};

/// Serializer for std::shared_ptr<T>
///
/// Supports reference tracking for shared and circular references.
/// When reference tracking is enabled, identical shared_ptr instances
/// will serialize only once and use reference IDs for subsequent occurrences.
///
/// Note: The element type T must be a value type (struct/class or primitive).
/// Raw pointers and nullable wrappers are not allowed as they would require
/// nested ref metadata handling, which complicates the protocol.
template <typename T> struct Serializer<std::shared_ptr<T>> {
  static_assert(!std::is_pointer_v<T>,
                "shared_ptr of raw pointer types is not supported");
  static_assert(!is_nullable_v<T>,
                "shared_ptr of nullable types "
                "(optional/shared_ptr/unique_ptr/weak_ptr) is not supported. "
                "Use the wrapper type directly instead.");

  static constexpr TypeId type_id =
      SharedPtrTypeIdHelper<T, std::is_polymorphic_v<T>>::value;

  static inline void write_type_info(WriteContext &ctx) {
    if constexpr (std::is_polymorphic_v<T>) {
      // For polymorphic types, type info must be written dynamically
      ctx.write_varuint32(static_cast<uint32_t>(TypeId::UNKNOWN));
    } else {
      Serializer<T>::write_type_info(ctx);
    }
  }

  static inline void read_type_info(ReadContext &ctx) {
    if constexpr (std::is_polymorphic_v<T>) {
      // For polymorphic types, type info is read dynamically
      ctx.read_any_typeinfo(ctx.error());
      // Type checking is done at value read time
    } else {
      Serializer<T>::read_type_info(ctx);
    }
  }

  static inline void write(const std::shared_ptr<T> &ptr, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle ref_mode == RefMode::None case (similar to Rust)
    if (ref_mode == RefMode::None) {
      if (!ptr) {
        ctx.set_error(Error::invalid("std::shared_ptr requires ref_mode != "
                                     "RefMode::None to encode null state"));
        return;
      }
      // For polymorphic types, serialize the concrete type dynamically
      if constexpr (is_polymorphic) {
        std::type_index concrete_type_id = std::type_index(typeid(*ptr));
        auto type_info_res =
            ctx.type_resolver().get_type_info(concrete_type_id);
        if (!type_info_res.ok()) {
          ctx.set_error(std::move(type_info_res).error());
          return;
        }
        const TypeInfo *type_info = type_info_res.value();
        if (write_type) {
          auto write_res = ctx.write_any_typeinfo(
              static_cast<uint32_t>(TypeId::UNKNOWN), concrete_type_id);
          if (!write_res.ok()) {
            ctx.set_error(std::move(write_res).error());
            return;
          }
        }
        const void *value_ptr = ptr.get();
        type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
      } else {
        // T is guaranteed to be a value type by static_assert.
        Serializer<T>::write(*ptr, ctx, RefMode::None, write_type);
      }
      return;
    }

    // Handle ref_mode != RefMode::None case
    if (!ptr) {
      ctx.write_int8(NULL_FLAG);
      return;
    }

    if (ctx.track_ref()) {
      if (ctx.ref_writer().try_write_shared_ref(ctx, ptr)) {
        return;
      }
    } else {
      ctx.write_int8(NOT_NULL_VALUE_FLAG);
    }

    // For polymorphic types, serialize the concrete type dynamically
    if constexpr (is_polymorphic) {
      // Get the concrete type_index from the actual object
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));

      // Look up the TypeInfo for the concrete type
      auto type_info_res = ctx.type_resolver().get_type_info(concrete_type_id);
      if (!type_info_res.ok()) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      const TypeInfo *type_info = type_info_res.value();

      // Write type info if requested
      if (write_type) {
        auto write_res = ctx.write_any_typeinfo(
            static_cast<uint32_t>(TypeId::UNKNOWN), concrete_type_id);
        if (!write_res.ok()) {
          ctx.set_error(std::move(write_res).error());
          return;
        }
      }

      // Call the harness with the raw pointer (which points to DerivedType)
      // The harness will static_cast it back to the concrete type
      const void *value_ptr = ptr.get();
      type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
    } else {
      // T is guaranteed to be a value type by static_assert.
      Serializer<T>::write(*ptr, ctx, RefMode::None, write_type);
    }
  }

  static inline void write_data(const std::shared_ptr<T> &ptr,
                                WriteContext &ctx) {
    if (!ptr) {
      ctx.set_error(Error::invalid(
          "std::shared_ptr write_data requires non-null pointer"));
      return;
    }

    // For polymorphic types, use harness to serialize the concrete type
    if constexpr (std::is_polymorphic_v<T>) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      auto type_info_res = ctx.type_resolver().get_type_info(concrete_type_id);
      if (!type_info_res.ok()) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      const TypeInfo *type_info = type_info_res.value();
      const void *value_ptr = ptr.get();
      type_info->harness.write_data_fn(value_ptr, ctx, false);
    } else {
      Serializer<T>::write_data(*ptr, ctx);
    }
  }

  static inline void write_data_generic(const std::shared_ptr<T> &ptr,
                                        WriteContext &ctx, bool has_generics) {
    if (!ptr) {
      ctx.set_error(Error::invalid(
          "std::shared_ptr write_data requires non-null pointer"));
      return;
    }

    // For polymorphic types, use harness to serialize the concrete type
    if constexpr (std::is_polymorphic_v<T>) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      auto type_info_res = ctx.type_resolver().get_type_info(concrete_type_id);
      if (!type_info_res.ok()) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      const TypeInfo *type_info = type_info_res.value();
      const void *value_ptr = ptr.get();
      type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
    } else {
      Serializer<T>::write_data_generic(*ptr, ctx, has_generics);
    }
  }

  static inline std::shared_ptr<T> read(ReadContext &ctx, RefMode ref_mode,
                                        bool read_type) {
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle ref_mode == RefMode::None case (similar to Rust)
    if (ref_mode == RefMode::None) {
      if constexpr (is_polymorphic) {
        if (read_type) {
          // Polymorphic path: read type info from stream to get concrete type
          const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
          if (ctx.has_error()) {
            return nullptr;
          }
          // Now use read_with_type_info with the concrete type info
          return read_with_type_info(ctx, ref_mode, *type_info);
        } else {
          // Monomorphic path: read_type=false means field is marked monomorphic
          // Use Serializer<T>::read directly without dynamic type dispatch
          // Note: abstract types cannot use monomorphic path
          if constexpr (std::is_abstract_v<T>) {
            ctx.set_error(Error::unsupported(
                "Cannot use monomorphic deserialization for abstract type"));
            return nullptr;
          } else {
            T value = Serializer<T>::read(ctx, RefMode::None, false);
            if (ctx.has_error()) {
              return nullptr;
            }
            return std::make_shared<T>(std::move(value));
          }
        }
      } else {
        // T is guaranteed to be a value type (not pointer or nullable wrapper)
        // by static_assert, so no inner ref metadata needed.
        T value = Serializer<T>::read(ctx, RefMode::None, read_type);
        if (ctx.has_error()) {
          return nullptr;
        }
        return std::make_shared<T>(std::move(value));
      }
    }

    // Handle ref_mode != RefMode::None case
    int8_t flag = ctx.read_int8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return nullptr;
    }
    if (flag == NULL_FLAG) {
      return nullptr;
    }
    const bool tracking_refs = ctx.track_ref();
    if (flag == REF_FLAG) {
      if (!tracking_refs) {
        ctx.set_error(Error::invalid_ref(
            "Reference flag encountered when reference tracking disabled"));
        return nullptr;
      }
      uint32_t ref_id = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return nullptr;
      }
      auto res = ctx.ref_reader().template get_shared_ref<T>(ref_id);
      if (!res.ok()) {
        ctx.set_error(std::move(res).error());
        return nullptr;
      }
      return res.value();
    }

    if (flag != NOT_NULL_VALUE_FLAG && flag != REF_VALUE_FLAG) {
      ctx.set_error(Error::invalid_ref("Unexpected reference flag value: " +
                                       std::to_string(static_cast<int>(flag))));
      return nullptr;
    }

    uint32_t reserved_ref_id = 0;
    const bool is_first_occurrence = flag == REF_VALUE_FLAG;
    if (is_first_occurrence) {
      if (!tracking_refs) {
        ctx.set_error(Error::invalid_ref(
            "REF_VALUE flag encountered when reference tracking disabled"));
        return nullptr;
      }
      reserved_ref_id = ctx.ref_reader().reserve_ref_id();
    }

    // For polymorphic types, read type info AFTER handling ref flags
    if constexpr (is_polymorphic) {
      if (read_type) {
        // Polymorphic path: read type info and use harness for deserialization
        // Check and increase dynamic depth for polymorphic deserialization
        auto depth_res = ctx.increase_dyn_depth();
        if (!depth_res.ok()) {
          ctx.set_error(std::move(depth_res).error());
          return nullptr;
        }
        DynDepthGuard dyn_depth_guard(ctx);

        // Read type info from stream to get the concrete type
        const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
        if (ctx.has_error()) {
          return nullptr;
        }

        // Use the harness to deserialize the concrete type
        void *raw_ptr = read_polymorphic_harness_data(ctx, type_info);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return nullptr;
        }
        T *obj_ptr = static_cast<T *>(raw_ptr);
        auto result = std::shared_ptr<T>(obj_ptr);
        if (is_first_occurrence) {
          ctx.ref_reader().store_shared_ref_at(reserved_ref_id, result);
        }
        return result;
      } else {
        // Monomorphic path: read_type=false means field is marked monomorphic
        // Use Serializer<T>::read directly without dynamic type dispatch
        // Note: abstract types cannot use monomorphic path
        if constexpr (std::is_abstract_v<T>) {
          ctx.set_error(Error::unsupported(
              "Cannot use monomorphic deserialization for abstract type"));
          return nullptr;
        } else {
          // For circular references: pre-allocate and store BEFORE reading
          if (is_first_occurrence) {
            auto result = std::make_shared<T>();
            ctx.ref_reader().store_shared_ref_at(reserved_ref_id, result);
            T value = Serializer<T>::read(ctx, RefMode::None, false);
            if (ctx.has_error()) {
              return nullptr;
            }
            *result = std::move(value);
            return result;
          } else {
            T value = Serializer<T>::read(ctx, RefMode::None, false);
            if (ctx.has_error()) {
              return nullptr;
            }
            return std::make_shared<T>(std::move(value));
          }
        }
      }
    } else {
      // Non-polymorphic path: T is guaranteed to be a value type (not pointer
      // or nullable wrapper) by static_assert, so no inner ref metadata needed.
      //
      // For circular references: we need to pre-allocate the shared_ptr and
      // store it BEFORE reading the struct fields. This allows forward
      // references (like selfRef pointing back to the parent) to resolve.
      if (is_first_occurrence) {
        // Pre-allocate with default construction and store immediately
        auto result = std::make_shared<T>();
        ctx.ref_reader().store_shared_ref_at(reserved_ref_id, result);
        // Read struct data - forward refs can now find this object
        T value = Serializer<T>::read(ctx, RefMode::None, read_type);
        if (ctx.has_error()) {
          return nullptr;
        }
        // Move-assign the read value into the pre-allocated object
        *result = std::move(value);
        return result;
      } else {
        // Not first occurrence, just read and wrap
        T value = Serializer<T>::read(ctx, RefMode::None, read_type);
        if (ctx.has_error()) {
          return nullptr;
        }
        return std::make_shared<T>(std::move(value));
      }
    }
  }

  static inline std::shared_ptr<T>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle ref_mode == RefMode::None case (similar to Rust)
    if (ref_mode == RefMode::None) {
      // For polymorphic types, use the harness to deserialize the concrete type
      if constexpr (is_polymorphic) {
        void *raw_ptr = read_polymorphic_harness_data(ctx, type_info);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return nullptr;
        }
        T *obj_ptr = static_cast<T *>(raw_ptr);
        return std::shared_ptr<T>(obj_ptr);
      } else {
        // T is guaranteed to be a value type by static_assert.
        T value =
            Serializer<T>::read_with_type_info(ctx, RefMode::None, type_info);
        if (ctx.has_error()) {
          return nullptr;
        }
        return std::make_shared<T>(std::move(value));
      }
    }

    // Handle ref_mode != RefMode::None case
    int8_t flag = ctx.read_int8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return nullptr;
    }

    if (flag == NULL_FLAG) {
      return nullptr;
    }
    const bool tracking_refs = ctx.track_ref();
    if (flag == REF_FLAG) {
      if (!tracking_refs) {
        ctx.set_error(Error::invalid_ref(
            "Reference flag encountered when reference tracking disabled"));
        return nullptr;
      }
      uint32_t ref_id = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return nullptr;
      }
      auto res = ctx.ref_reader().template get_shared_ref<T>(ref_id);
      if (!res.ok()) {
        ctx.set_error(std::move(res).error());
        return nullptr;
      }
      return res.value();
    }

    if (flag != NOT_NULL_VALUE_FLAG && flag != REF_VALUE_FLAG) {
      ctx.set_error(Error::invalid_ref("Unexpected reference flag value: " +
                                       std::to_string(static_cast<int>(flag))));
      return nullptr;
    }

    uint32_t reserved_ref_id = 0;
    if (flag == REF_VALUE_FLAG) {
      if (!tracking_refs) {
        ctx.set_error(Error::invalid_ref(
            "REF_VALUE flag encountered when reference tracking disabled"));
        return nullptr;
      }
      reserved_ref_id = ctx.ref_reader().reserve_ref_id();
    }

    // For polymorphic types, use the harness to deserialize the concrete type
    if constexpr (is_polymorphic) {
      // Check and increase dynamic depth for polymorphic deserialization
      auto depth_res = ctx.increase_dyn_depth();
      if (!depth_res.ok()) {
        ctx.set_error(std::move(depth_res).error());
        return nullptr;
      }
      DynDepthGuard dyn_depth_guard(ctx);

      // Use the harness to deserialize the concrete type
      void *raw_ptr = read_polymorphic_harness_data(ctx, type_info);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return nullptr;
      }
      T *obj_ptr = static_cast<T *>(raw_ptr);
      auto result = std::shared_ptr<T>(obj_ptr);
      if (flag == REF_VALUE_FLAG) {
        ctx.ref_reader().store_shared_ref_at(reserved_ref_id, result);
      }
      return result;
    } else {
      // T is guaranteed to be a value type by static_assert.
      // For circular references: pre-allocate and store BEFORE reading
      const bool is_first_occurrence = flag == REF_VALUE_FLAG;
      if (is_first_occurrence) {
        auto result = std::make_shared<T>();
        ctx.ref_reader().store_shared_ref_at(reserved_ref_id, result);
        T value =
            Serializer<T>::read_with_type_info(ctx, RefMode::None, type_info);
        if (ctx.has_error()) {
          return nullptr;
        }
        *result = std::move(value);
        return result;
      } else {
        T value =
            Serializer<T>::read_with_type_info(ctx, RefMode::None, type_info);
        if (ctx.has_error()) {
          return nullptr;
        }
        return std::make_shared<T>(std::move(value));
      }
    }
  }

  static inline std::shared_ptr<T> read_data(ReadContext &ctx) {
    T value = Serializer<T>::read_data(ctx);
    if (ctx.has_error()) {
      return nullptr;
    }
    return std::make_shared<T>(std::move(value));
  }
};

// ============================================================================
// std::unique_ptr serializer
// ============================================================================

// Helper to get type_id for unique_ptr without instantiating Serializer for
// polymorphic types
template <typename T, bool IsPolymorphic> struct UniquePtrTypeIdHelper {
  static constexpr TypeId value = Serializer<T>::type_id;
};

template <typename T> struct UniquePtrTypeIdHelper<T, true> {
  static constexpr TypeId value = TypeId::UNKNOWN;
};

/// Serializer for std::unique_ptr<T>
///
/// Note: unique_ptr does not support reference tracking since
/// it represents exclusive ownership. Each unique_ptr is serialized
/// independently.
///
/// Note: The element type T must be a value type (struct/class or primitive).
/// Raw pointers and nullable wrappers are not allowed as they would require
/// nested ref metadata handling, which complicates the protocol.
template <typename T> struct Serializer<std::unique_ptr<T>> {
  static_assert(!std::is_pointer_v<T>,
                "unique_ptr of raw pointer types is not supported");
  static_assert(!is_nullable_v<T>,
                "unique_ptr of nullable types "
                "(optional/shared_ptr/unique_ptr/weak_ptr) is not supported. "
                "Use the wrapper type directly instead.");

  static constexpr TypeId type_id =
      UniquePtrTypeIdHelper<T, std::is_polymorphic_v<T>>::value;

  static inline void write(const std::unique_ptr<T> &ptr, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle ref_mode == RefMode::None case (similar to Rust)
    if (ref_mode == RefMode::None) {
      if (!ptr) {
        ctx.set_error(Error::invalid("std::unique_ptr requires ref_mode != "
                                     "RefMode::None to encode null state"));
        return;
      }
      // For polymorphic types, serialize the concrete type dynamically
      if constexpr (is_polymorphic) {
        std::type_index concrete_type_id = std::type_index(typeid(*ptr));
        auto type_info_res =
            ctx.type_resolver().get_type_info(concrete_type_id);
        if (!type_info_res.ok()) {
          ctx.set_error(std::move(type_info_res).error());
          return;
        }
        const TypeInfo *type_info = type_info_res.value();
        if (write_type) {
          auto write_res = ctx.write_any_typeinfo(
              static_cast<uint32_t>(TypeId::UNKNOWN), concrete_type_id);
          if (!write_res.ok()) {
            ctx.set_error(std::move(write_res).error());
            return;
          }
        }
        const void *value_ptr = ptr.get();
        type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
      } else {
        // T is guaranteed to be a value type by static_assert.
        Serializer<T>::write(*ptr, ctx, RefMode::None, write_type);
      }
      return;
    }

    // Handle ref_mode != RefMode::None case
    if (!ptr) {
      ctx.write_int8(NULL_FLAG);
      return;
    }

    ctx.write_int8(NOT_NULL_VALUE_FLAG);

    // For polymorphic types, serialize the concrete type dynamically
    if constexpr (is_polymorphic) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      auto type_info_res = ctx.type_resolver().get_type_info(concrete_type_id);
      if (!type_info_res.ok()) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      const TypeInfo *type_info = type_info_res.value();
      if (write_type) {
        auto write_res = ctx.write_any_typeinfo(
            static_cast<uint32_t>(TypeId::UNKNOWN), concrete_type_id);
        if (!write_res.ok()) {
          ctx.set_error(std::move(write_res).error());
          return;
        }
      }
      const void *value_ptr = ptr.get();
      type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
    } else {
      // T is guaranteed to be a value type by static_assert.
      Serializer<T>::write(*ptr, ctx, RefMode::None, write_type);
    }
  }

  static inline void write_data(const std::unique_ptr<T> &ptr,
                                WriteContext &ctx) {
    if (!ptr) {
      ctx.set_error(Error::invalid(
          "std::unique_ptr write_data requires non-null pointer"));
      return;
    }
    // For polymorphic types, use harness to serialize the concrete type
    if constexpr (std::is_polymorphic_v<T>) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      auto type_info_res = ctx.type_resolver().get_type_info(concrete_type_id);
      if (!type_info_res.ok()) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      const TypeInfo *type_info = type_info_res.value();
      const void *value_ptr = ptr.get();
      type_info->harness.write_data_fn(value_ptr, ctx, false);
    } else {
      Serializer<T>::write_data(*ptr, ctx);
    }
  }

  static inline void write_data_generic(const std::unique_ptr<T> &ptr,
                                        WriteContext &ctx, bool has_generics) {
    if (!ptr) {
      ctx.set_error(Error::invalid(
          "std::unique_ptr write_data requires non-null pointer"));
      return;
    }
    // For polymorphic types, use harness to serialize the concrete type
    if constexpr (std::is_polymorphic_v<T>) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      auto type_info_res = ctx.type_resolver().get_type_info(concrete_type_id);
      if (!type_info_res.ok()) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      const TypeInfo *type_info = type_info_res.value();
      const void *value_ptr = ptr.get();
      type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
    } else {
      Serializer<T>::write_data_generic(*ptr, ctx, has_generics);
    }
  }

  static inline std::unique_ptr<T> read(ReadContext &ctx, RefMode ref_mode,
                                        bool read_type) {
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle ref_mode == RefMode::None case (similar to Rust)
    if (ref_mode == RefMode::None) {
      if constexpr (is_polymorphic) {
        if (read_type) {
          // Polymorphic path: read type info from stream to get concrete type
          const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
          if (ctx.has_error()) {
            return nullptr;
          }
          // Now use read_with_type_info with the concrete type info
          return read_with_type_info(ctx, ref_mode, *type_info);
        } else {
          // Monomorphic path: read_type=false means field is marked monomorphic
          // Use Serializer<T>::read directly without dynamic type dispatch
          // Note: abstract types cannot use monomorphic path
          if constexpr (std::is_abstract_v<T>) {
            ctx.set_error(Error::unsupported(
                "Cannot use monomorphic deserialization for abstract type"));
            return nullptr;
          } else {
            T value = Serializer<T>::read(ctx, RefMode::None, false);
            if (ctx.has_error()) {
              return nullptr;
            }
            return std::make_unique<T>(std::move(value));
          }
        }
      } else {
        // T is guaranteed to be a value type by static_assert.
        T value = Serializer<T>::read(ctx, RefMode::None, read_type);
        if (ctx.has_error()) {
          return nullptr;
        }
        return std::make_unique<T>(std::move(value));
      }
    }

    // Handle ref_mode != RefMode::None case
    int8_t flag = ctx.read_int8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return nullptr;
    }
    if (flag == NULL_FLAG) {
      return nullptr;
    }
    if (flag != NOT_NULL_VALUE_FLAG) {
      ctx.set_error(
          Error::invalid_ref("Unexpected reference flag for unique_ptr: " +
                             std::to_string(static_cast<int>(flag))));
      return nullptr;
    }

    // For polymorphic types, read type info AFTER handling ref flags
    if constexpr (is_polymorphic) {
      if (read_type) {
        // Polymorphic path: read type info and use harness for deserialization
        // Check and increase dynamic depth for polymorphic deserialization
        auto depth_res = ctx.increase_dyn_depth();
        if (!depth_res.ok()) {
          ctx.set_error(std::move(depth_res).error());
          return nullptr;
        }
        DynDepthGuard dyn_depth_guard(ctx);

        // Read type info from stream to get the concrete type
        const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
        if (ctx.has_error()) {
          return nullptr;
        }

        // Use the harness to deserialize the concrete type
        void *raw_ptr = read_polymorphic_harness_data(ctx, type_info);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return nullptr;
        }
        T *obj_ptr = static_cast<T *>(raw_ptr);
        return std::unique_ptr<T>(obj_ptr);
      } else {
        // Monomorphic path: read_type=false means field is marked monomorphic
        // Use Serializer<T>::read directly without dynamic type dispatch
        // Note: abstract types cannot use monomorphic path
        if constexpr (std::is_abstract_v<T>) {
          ctx.set_error(Error::unsupported(
              "Cannot use monomorphic deserialization for abstract type"));
          return nullptr;
        } else {
          T value = Serializer<T>::read(ctx, RefMode::None, false);
          if (ctx.has_error()) {
            return nullptr;
          }
          return std::make_unique<T>(std::move(value));
        }
      }
    } else {
      // T is guaranteed to be a value type by static_assert.
      T value = Serializer<T>::read(ctx, RefMode::None, read_type);
      if (ctx.has_error()) {
        return nullptr;
      }
      return std::make_unique<T>(std::move(value));
    }
  }

  static inline std::unique_ptr<T>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle ref_mode == RefMode::None case (similar to Rust)
    if (ref_mode == RefMode::None) {
      // For polymorphic types, use the harness to deserialize the concrete type
      if constexpr (is_polymorphic) {
        void *raw_ptr = read_polymorphic_harness_data(ctx, type_info);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return nullptr;
        }
        T *obj_ptr = static_cast<T *>(raw_ptr);
        return std::unique_ptr<T>(obj_ptr);
      } else {
        // T is guaranteed to be a value type by static_assert.
        T value =
            Serializer<T>::read_with_type_info(ctx, RefMode::None, type_info);
        if (ctx.has_error()) {
          return nullptr;
        }
        return std::make_unique<T>(std::move(value));
      }
    }

    // Handle ref_mode != RefMode::None case
    int8_t flag = ctx.read_int8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return nullptr;
    }
    if (flag == NULL_FLAG) {
      return nullptr;
    }
    if (flag != NOT_NULL_VALUE_FLAG) {
      ctx.set_error(
          Error::invalid_ref("Unexpected reference flag for unique_ptr: " +
                             std::to_string(static_cast<int>(flag))));
      return nullptr;
    }

    // For polymorphic types, use the harness to deserialize the concrete type
    if constexpr (is_polymorphic) {
      // Check and increase dynamic depth for polymorphic deserialization
      auto depth_res = ctx.increase_dyn_depth();
      if (!depth_res.ok()) {
        ctx.set_error(std::move(depth_res).error());
        return nullptr;
      }
      DynDepthGuard dyn_depth_guard(ctx);

      // Use the harness to deserialize the concrete type
      void *raw_ptr = read_polymorphic_harness_data(ctx, type_info);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return nullptr;
      }
      T *obj_ptr = static_cast<T *>(raw_ptr);
      return std::unique_ptr<T>(obj_ptr);
    } else {
      // T is guaranteed to be a value type by static_assert.
      T value =
          Serializer<T>::read_with_type_info(ctx, RefMode::None, type_info);
      if (ctx.has_error()) {
        return nullptr;
      }
      return std::make_unique<T>(std::move(value));
    }
  }

  static inline std::unique_ptr<T> read_data(ReadContext &ctx) {
    T value = Serializer<T>::read_data(ctx);
    if (ctx.has_error()) {
      return nullptr;
    }
    return std::make_unique<T>(std::move(value));
  }
};

} // namespace serialization
} // namespace fory
