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
// std::optional serializer
// ============================================================================

/// Serializer for std::optional<T>
///
/// Serializes optional values with null handling.
/// Uses NOT_NULL_VALUE_FLAG for values, NULL_FLAG for nullopt.
template <typename T> struct Serializer<std::optional<T>> {
  // Use the inner type's type_id
  static constexpr TypeId type_id = Serializer<T>::type_id;

  static Result<void, Error> write(const std::optional<T> &opt,
                                   WriteContext &ctx, bool write_ref,
                                   bool write_type) {
    constexpr bool inner_requires_ref = requires_ref_metadata_v<T>;

    if (!write_ref) {
      if (!opt.has_value()) {
        return Unexpected(Error::invalid(
            "std::optional requires write_ref=true to encode null state"));
      }
      return Serializer<T>::write(*opt, ctx, false, write_type);
    }

    if (!opt.has_value()) {
      ctx.write_int8(NULL_FLAG);
      return Result<void, Error>();
    }

    if constexpr (inner_requires_ref) {
      return Serializer<T>::write(*opt, ctx, true, write_type);
    } else {
      ctx.write_int8(NOT_NULL_VALUE_FLAG);
      return Serializer<T>::write(*opt, ctx, false, write_type);
    }
  }

  static Result<void, Error> write_data(const std::optional<T> &opt,
                                        WriteContext &ctx) {
    if (!opt.has_value()) {
      return Unexpected(
          Error::invalid("std::optional write_data requires value present"));
    }
    return Serializer<T>::write_data(*opt, ctx);
  }

  static Result<void, Error> write_data_generic(const std::optional<T> &opt,
                                                WriteContext &ctx,
                                                bool has_generics) {
    if (!opt.has_value()) {
      return Unexpected(
          Error::invalid("std::optional write_data requires value present"));
    }
    return Serializer<T>::write_data_generic(*opt, ctx, has_generics);
  }

  static Result<std::optional<T>, Error> read(ReadContext &ctx, bool read_ref,
                                              bool read_type) {
    constexpr bool inner_requires_ref = requires_ref_metadata_v<T>;

    if (!read_ref) {
      FORY_TRY(value, Serializer<T>::read(ctx, false, read_type));
      return std::optional<T>(std::move(value));
    }

    const uint32_t flag_pos = ctx.buffer().reader_index();
    FORY_TRY(flag, ctx.read_int8());

    if (flag == NULL_FLAG) {
      return std::optional<T>(std::nullopt);
    }

    if constexpr (inner_requires_ref) {
      // Rewind so the inner serializer can consume the reference metadata.
      ctx.buffer().ReaderIndex(flag_pos);
      FORY_TRY(value, Serializer<T>::read(ctx, true, read_type));
      return std::optional<T>(std::move(value));
    }

    if (flag != NOT_NULL_VALUE_FLAG && flag != REF_VALUE_FLAG) {
      return Unexpected(
          Error::invalid_ref("Unexpected reference flag for std::optional: " +
                             std::to_string(static_cast<int>(flag))));
    }

    FORY_TRY(value, Serializer<T>::read(ctx, false, read_type));
    return std::optional<T>(std::move(value));
  }

  static Result<std::optional<T>, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    constexpr bool inner_requires_ref = requires_ref_metadata_v<T>;

    if (!read_ref) {
      FORY_TRY(value,
               Serializer<T>::read_with_type_info(ctx, false, type_info));
      return std::optional<T>(std::move(value));
    }

    const uint32_t flag_pos = ctx.buffer().reader_index();
    FORY_TRY(flag, ctx.read_int8());

    if (flag == NULL_FLAG) {
      return std::optional<T>(std::nullopt);
    }

    if constexpr (inner_requires_ref) {
      // Rewind so the inner serializer can consume the reference metadata.
      ctx.buffer().ReaderIndex(flag_pos);
      FORY_TRY(value, Serializer<T>::read_with_type_info(ctx, true, type_info));
      return std::optional<T>(std::move(value));
    }

    if (flag != NOT_NULL_VALUE_FLAG && flag != REF_VALUE_FLAG) {
      return Unexpected(
          Error::invalid_ref("Unexpected reference flag for std::optional: " +
                             std::to_string(static_cast<int>(flag))));
    }

    FORY_TRY(value, Serializer<T>::read_with_type_info(ctx, false, type_info));
    return std::optional<T>(std::move(value));
  }

  static Result<std::optional<T>, Error> read_data(ReadContext &ctx) {
    FORY_TRY(value, Serializer<T>::read_data(ctx));
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
template <typename T> struct Serializer<std::shared_ptr<T>> {
  static constexpr TypeId type_id =
      SharedPtrTypeIdHelper<T, std::is_polymorphic_v<T>>::value;

  static Result<void, Error> write(const std::shared_ptr<T> &ptr,
                                   WriteContext &ctx, bool write_ref,
                                   bool write_type, bool has_generics = false) {
    constexpr bool inner_requires_ref = requires_ref_metadata_v<T>;
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle write_ref=false case (similar to Rust)
    if (!write_ref) {
      if (!ptr) {
        return Unexpected(Error::invalid(
            "std::shared_ptr requires write_ref=true to encode null state"));
      }
      // For polymorphic types, serialize the concrete type dynamically
      if constexpr (is_polymorphic) {
        std::type_index concrete_type_id = std::type_index(typeid(*ptr));
        FORY_TRY(type_info,
                 ctx.type_resolver().get_type_info(concrete_type_id));
        if (write_type) {
          FORY_RETURN_NOT_OK(ctx.write_any_typeinfo(
              static_cast<uint32_t>(TypeId::UNKNOWN), concrete_type_id));
        }
        const void *value_ptr = ptr.get();
        return type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
      } else {
        return Serializer<T>::write(*ptr, ctx, inner_requires_ref, write_type);
      }
    }

    // Handle write_ref=true case
    if (!ptr) {
      ctx.write_int8(NULL_FLAG);
      return Result<void, Error>();
    }

    if (ctx.track_ref()) {
      if (ctx.ref_writer().try_write_shared_ref(ctx, ptr)) {
        return Result<void, Error>();
      }
    } else {
      ctx.write_int8(NOT_NULL_VALUE_FLAG);
    }

    // For polymorphic types, serialize the concrete type dynamically
    if constexpr (is_polymorphic) {
      // Get the concrete type_index from the actual object
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));

      // Look up the TypeInfo for the concrete type
      FORY_TRY(type_info, ctx.type_resolver().get_type_info(concrete_type_id));

      // Write type info if requested
      if (write_type) {
        FORY_RETURN_NOT_OK(ctx.write_any_typeinfo(
            static_cast<uint32_t>(TypeId::UNKNOWN), concrete_type_id));
      }

      // Call the harness with the raw pointer (which points to DerivedType)
      // The harness will static_cast it back to the concrete type
      const void *value_ptr = ptr.get();
      return type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
    } else {
      // Non-polymorphic path
      return Serializer<T>::write(*ptr, ctx, inner_requires_ref, write_type);
    }
  }

  static Result<void, Error> write_data(const std::shared_ptr<T> &ptr,
                                        WriteContext &ctx) {
    if (!ptr) {
      return Unexpected(Error::invalid(
          "std::shared_ptr write_data requires non-null pointer"));
    }

    // For polymorphic types, use harness to serialize the concrete type
    if constexpr (std::is_polymorphic_v<T>) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      FORY_TRY(type_info, ctx.type_resolver().get_type_info(concrete_type_id));
      const void *value_ptr = ptr.get();
      return type_info->harness.write_data_fn(value_ptr, ctx, false);
    } else {
      return Serializer<T>::write_data(*ptr, ctx);
    }
  }

  static Result<void, Error> write_data_generic(const std::shared_ptr<T> &ptr,
                                                WriteContext &ctx,
                                                bool has_generics) {
    if (!ptr) {
      return Unexpected(Error::invalid(
          "std::shared_ptr write_data requires non-null pointer"));
    }

    // For polymorphic types, use harness to serialize the concrete type
    if constexpr (std::is_polymorphic_v<T>) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      FORY_TRY(type_info, ctx.type_resolver().get_type_info(concrete_type_id));
      const void *value_ptr = ptr.get();
      return type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
    } else {
      return Serializer<T>::write_data_generic(*ptr, ctx, has_generics);
    }
  }

  static Result<std::shared_ptr<T>, Error> read(ReadContext &ctx, bool read_ref,
                                                bool read_type) {
    constexpr bool inner_requires_ref = requires_ref_metadata_v<T>;
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle read_ref=false case (similar to Rust)
    if (!read_ref) {
      if constexpr (is_polymorphic) {
        // For polymorphic types, we must read type info when read_type=true
        if (!read_type) {
          return Unexpected(Error::type_error(
              "Cannot deserialize polymorphic std::shared_ptr<T> "
              "without type info (read_type=false)"));
        }
        // Read type info from stream to get the concrete type
        FORY_TRY(type_info, ctx.read_any_typeinfo());
        // Now use read_with_type_info with the concrete type info
        return read_with_type_info(ctx, read_ref, *type_info);
      } else {
        FORY_TRY(value,
                 Serializer<T>::read(ctx, inner_requires_ref, read_type));
        return std::make_shared<T>(std::move(value));
      }
    }

    // Handle read_ref=true case
    FORY_TRY(flag, ctx.read_int8());
    if (flag == NULL_FLAG) {
      return std::shared_ptr<T>(nullptr);
    }
    const bool tracking_refs = ctx.track_ref();
    if (flag == REF_FLAG) {
      if (!tracking_refs) {
        return Unexpected(Error::invalid_ref(
            "Reference flag encountered when reference tracking disabled"));
      }
      FORY_TRY(ref_id, ctx.read_varuint32());
      return ctx.ref_reader().template get_shared_ref<T>(ref_id);
    }

    if (flag != NOT_NULL_VALUE_FLAG && flag != REF_VALUE_FLAG) {
      return Unexpected(
          Error::invalid_ref("Unexpected reference flag value: " +
                             std::to_string(static_cast<int>(flag))));
    }

    uint32_t reserved_ref_id = 0;
    if (flag == REF_VALUE_FLAG) {
      if (!tracking_refs) {
        return Unexpected(Error::invalid_ref(
            "REF_VALUE flag encountered when reference tracking disabled"));
      }
      reserved_ref_id = ctx.ref_reader().reserve_ref_id();
    }

    // For polymorphic types, read type info AFTER handling ref flags
    if constexpr (is_polymorphic) {
      if (!read_type) {
        return Unexpected(Error::type_error(
            "Cannot deserialize polymorphic std::shared_ptr<T> "
            "without type info (read_type=false)"));
      }
      // Read type info from stream to get the concrete type
      FORY_TRY(type_info, ctx.read_any_typeinfo());

      // Use the harness to deserialize the concrete type
      if (!type_info->harness.read_data_fn) {
        return Unexpected(Error::type_error(
            "No harness read function for polymorphic type deserialization"));
      }
      FORY_TRY(raw_ptr, type_info->harness.read_data_fn(ctx));
      T *obj_ptr = static_cast<T *>(raw_ptr);
      auto result = std::shared_ptr<T>(obj_ptr);
      if (flag == REF_VALUE_FLAG) {
        ctx.ref_reader().store_shared_ref_at(reserved_ref_id, result);
      }
      return result;
    } else {
      // Non-polymorphic path
      FORY_TRY(value, Serializer<T>::read(ctx, inner_requires_ref, read_type));
      auto result = std::make_shared<T>(std::move(value));
      if (flag == REF_VALUE_FLAG) {
        ctx.ref_reader().store_shared_ref_at(reserved_ref_id, result);
      }
      return result;
    }
  }

  static Result<std::shared_ptr<T>, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    constexpr bool inner_requires_ref = requires_ref_metadata_v<T>;
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle read_ref=false case (similar to Rust)
    if (!read_ref) {
      // For polymorphic types, use the harness to deserialize the concrete type
      if constexpr (is_polymorphic) {
        if (!type_info.harness.read_data_fn) {
          return Unexpected(Error::type_error(
              "No harness read function for polymorphic type deserialization"));
        }
        FORY_TRY(raw_ptr, type_info.harness.read_data_fn(ctx));
        T *obj_ptr = static_cast<T *>(raw_ptr);
        return std::shared_ptr<T>(obj_ptr);
      } else {
        // Non-polymorphic path
        FORY_TRY(value, Serializer<T>::read_with_type_info(
                            ctx, inner_requires_ref, type_info));
        return std::make_shared<T>(std::move(value));
      }
    }

    // Handle read_ref=true case
    FORY_TRY(flag, ctx.read_int8());
    if (flag == NULL_FLAG) {
      return std::shared_ptr<T>(nullptr);
    }
    const bool tracking_refs = ctx.track_ref();
    if (flag == REF_FLAG) {
      if (!tracking_refs) {
        return Unexpected(Error::invalid_ref(
            "Reference flag encountered when reference tracking disabled"));
      }
      FORY_TRY(ref_id, ctx.read_varuint32());
      return ctx.ref_reader().template get_shared_ref<T>(ref_id);
    }

    if (flag != NOT_NULL_VALUE_FLAG && flag != REF_VALUE_FLAG) {
      return Unexpected(
          Error::invalid_ref("Unexpected reference flag value: " +
                             std::to_string(static_cast<int>(flag))));
    }

    uint32_t reserved_ref_id = 0;
    if (flag == REF_VALUE_FLAG) {
      if (!tracking_refs) {
        return Unexpected(Error::invalid_ref(
            "REF_VALUE flag encountered when reference tracking disabled"));
      }
      reserved_ref_id = ctx.ref_reader().reserve_ref_id();
    }

    // For polymorphic types, use the harness to deserialize the concrete type
    if constexpr (is_polymorphic) {
      // The type_info contains information about the CONCRETE type, not T
      // Use the harness to deserialize it
      if (!type_info.harness.read_data_fn) {
        return Unexpected(Error::type_error(
            "No harness read function for polymorphic type deserialization"));
      }

      FORY_TRY(raw_ptr, type_info.harness.read_data_fn(ctx));

      // The harness returns void* pointing to the concrete type
      // Cast the void* to T* (this works because the concrete type derives from
      // T)
      T *obj_ptr = static_cast<T *>(raw_ptr);
      auto result = std::shared_ptr<T>(obj_ptr);
      if (flag == REF_VALUE_FLAG) {
        ctx.ref_reader().store_shared_ref_at(reserved_ref_id, result);
      }
      return result;
    } else {
      // Non-polymorphic path
      FORY_TRY(value, Serializer<T>::read_with_type_info(
                          ctx, inner_requires_ref, type_info));
      auto result = std::make_shared<T>(std::move(value));
      if (flag == REF_VALUE_FLAG) {
        ctx.ref_reader().store_shared_ref_at(reserved_ref_id, result);
      }

      return result;
    }
  }

  static Result<std::shared_ptr<T>, Error> read_data(ReadContext &ctx) {
    FORY_TRY(value, Serializer<T>::read_data(ctx));
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
template <typename T> struct Serializer<std::unique_ptr<T>> {
  static constexpr TypeId type_id =
      UniquePtrTypeIdHelper<T, std::is_polymorphic_v<T>>::value;

  static Result<void, Error> write(const std::unique_ptr<T> &ptr,
                                   WriteContext &ctx, bool write_ref,
                                   bool write_type) {
    constexpr bool inner_requires_ref = requires_ref_metadata_v<T>;
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle write_ref=false case (similar to Rust)
    if (!write_ref) {
      if (!ptr) {
        return Unexpected(Error::invalid(
            "std::unique_ptr requires write_ref=true to encode null state"));
      }
      // For polymorphic types, serialize the concrete type dynamically
      if constexpr (is_polymorphic) {
        std::type_index concrete_type_id = std::type_index(typeid(*ptr));
        FORY_TRY(type_info,
                 ctx.type_resolver().get_type_info(concrete_type_id));
        if (write_type) {
          FORY_RETURN_NOT_OK(ctx.write_any_typeinfo(
              static_cast<uint32_t>(TypeId::UNKNOWN), concrete_type_id));
        }
        const void *value_ptr = ptr.get();
        return type_info->harness.write_data_fn(value_ptr, ctx, false);
      } else {
        return Serializer<T>::write(*ptr, ctx, inner_requires_ref, write_type);
      }
    }

    // Handle write_ref=true case
    if (!ptr) {
      ctx.write_int8(NULL_FLAG);
      return Result<void, Error>();
    }

    ctx.write_int8(NOT_NULL_VALUE_FLAG);

    // For polymorphic types, serialize the concrete type dynamically
    if constexpr (is_polymorphic) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      FORY_TRY(type_info, ctx.type_resolver().get_type_info(concrete_type_id));
      if (write_type) {
        FORY_RETURN_NOT_OK(ctx.write_any_typeinfo(
            static_cast<uint32_t>(TypeId::UNKNOWN), concrete_type_id));
      }
      const void *value_ptr = ptr.get();
      return type_info->harness.write_data_fn(value_ptr, ctx, false);
    } else {
      return Serializer<T>::write(*ptr, ctx, inner_requires_ref, write_type);
    }
  }

  static Result<void, Error> write_data(const std::unique_ptr<T> &ptr,
                                        WriteContext &ctx) {
    if (!ptr) {
      return Unexpected(Error::invalid(
          "std::unique_ptr write_data requires non-null pointer"));
    }
    // For polymorphic types, use harness to serialize the concrete type
    if constexpr (std::is_polymorphic_v<T>) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      FORY_TRY(type_info, ctx.type_resolver().get_type_info(concrete_type_id));
      const void *value_ptr = ptr.get();
      return type_info->harness.write_data_fn(value_ptr, ctx, false);
    } else {
      return Serializer<T>::write_data(*ptr, ctx);
    }
  }

  static Result<void, Error> write_data_generic(const std::unique_ptr<T> &ptr,
                                                WriteContext &ctx,
                                                bool has_generics) {
    if (!ptr) {
      return Unexpected(Error::invalid(
          "std::unique_ptr write_data requires non-null pointer"));
    }
    // For polymorphic types, use harness to serialize the concrete type
    if constexpr (std::is_polymorphic_v<T>) {
      std::type_index concrete_type_id = std::type_index(typeid(*ptr));
      FORY_TRY(type_info, ctx.type_resolver().get_type_info(concrete_type_id));
      const void *value_ptr = ptr.get();
      return type_info->harness.write_data_fn(value_ptr, ctx, has_generics);
    } else {
      return Serializer<T>::write_data_generic(*ptr, ctx, has_generics);
    }
  }

  static Result<std::unique_ptr<T>, Error> read(ReadContext &ctx, bool read_ref,
                                                bool read_type) {
    constexpr bool inner_requires_ref = requires_ref_metadata_v<T>;
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle read_ref=false case (similar to Rust)
    if (!read_ref) {
      if constexpr (is_polymorphic) {
        // For polymorphic types, we must read type info when read_type=true
        if (!read_type) {
          return Unexpected(Error::type_error(
              "Cannot deserialize polymorphic std::unique_ptr<T> "
              "without type info (read_type=false)"));
        }
        // Read type info from stream to get the concrete type
        FORY_TRY(type_info, ctx.read_any_typeinfo());
        // Now use read_with_type_info with the concrete type info
        return read_with_type_info(ctx, read_ref, *type_info);
      } else {
        FORY_TRY(value,
                 Serializer<T>::read(ctx, inner_requires_ref, read_type));
        return std::make_unique<T>(std::move(value));
      }
    }

    // Handle read_ref=true case
    FORY_TRY(flag, ctx.read_int8());
    if (flag == NULL_FLAG) {
      return std::unique_ptr<T>(nullptr);
    }
    if (flag != NOT_NULL_VALUE_FLAG) {
      return Unexpected(
          Error::invalid_ref("Unexpected reference flag for unique_ptr: " +
                             std::to_string(static_cast<int>(flag))));
    }

    // For polymorphic types, read type info AFTER handling ref flags
    if constexpr (is_polymorphic) {
      if (!read_type) {
        return Unexpected(Error::type_error(
            "Cannot deserialize polymorphic std::unique_ptr<T> "
            "without type info (read_type=false)"));
      }
      // Read type info from stream to get the concrete type
      FORY_TRY(type_info, ctx.read_any_typeinfo());

      // Use the harness to deserialize the concrete type
      if (!type_info->harness.read_data_fn) {
        return Unexpected(Error::type_error(
            "No harness read function for polymorphic type deserialization"));
      }
      FORY_TRY(raw_ptr, type_info->harness.read_data_fn(ctx));
      T *obj_ptr = static_cast<T *>(raw_ptr);
      return std::unique_ptr<T>(obj_ptr);
    } else {
      // Non-polymorphic path
      FORY_TRY(value, Serializer<T>::read(ctx, inner_requires_ref, read_type));
      return std::make_unique<T>(std::move(value));
    }
  }

  static Result<std::unique_ptr<T>, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    constexpr bool inner_requires_ref = requires_ref_metadata_v<T>;
    constexpr bool is_polymorphic = std::is_polymorphic_v<T>;

    // Handle read_ref=false case (similar to Rust)
    if (!read_ref) {
      // For polymorphic types, use the harness to deserialize the concrete type
      if constexpr (is_polymorphic) {
        if (!type_info.harness.read_data_fn) {
          return Unexpected(Error::type_error(
              "No harness read function for polymorphic type deserialization"));
        }
        FORY_TRY(raw_ptr, type_info.harness.read_data_fn(ctx));
        T *obj_ptr = static_cast<T *>(raw_ptr);
        return std::unique_ptr<T>(obj_ptr);
      } else {
        // Non-polymorphic path
        FORY_TRY(value, Serializer<T>::read_with_type_info(
                            ctx, inner_requires_ref, type_info));
        return std::make_unique<T>(std::move(value));
      }
    }

    // Handle read_ref=true case
    FORY_TRY(flag, ctx.read_int8());
    if (flag == NULL_FLAG) {
      return std::unique_ptr<T>(nullptr);
    }
    if (flag != NOT_NULL_VALUE_FLAG) {
      return Unexpected(
          Error::invalid_ref("Unexpected reference flag for unique_ptr: " +
                             std::to_string(static_cast<int>(flag))));
    }

    // For polymorphic types, use the harness to deserialize the concrete type
    if constexpr (is_polymorphic) {
      if (!type_info.harness.read_data_fn) {
        return Unexpected(Error::type_error(
            "No harness read function for polymorphic type deserialization"));
      }
      FORY_TRY(raw_ptr, type_info.harness.read_data_fn(ctx));
      T *obj_ptr = static_cast<T *>(raw_ptr);
      return std::unique_ptr<T>(obj_ptr);
    } else {
      // Non-polymorphic path
      FORY_TRY(value, Serializer<T>::read_with_type_info(
                          ctx, inner_requires_ref, type_info));
      return std::make_unique<T>(std::move(value));
    }
  }

  static Result<std::unique_ptr<T>, Error> read_data(ReadContext &ctx) {
    FORY_TRY(value, Serializer<T>::read_data(ctx));
    return std::make_unique<T>(std::move(value));
  }
};

} // namespace serialization
} // namespace fory
