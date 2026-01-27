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
#include "fory/type/type.h"
#include "fory/util/error.h"
#include <cstdint>
#include <variant>

namespace fory {
namespace serialization {

// ============================================================================
// std::monostate Serializer
// ============================================================================

/// Serializer for std::monostate (empty variant alternative)
///
/// std::monostate represents an empty state in variants. It has no data
/// to serialize, so serialization is a no-op.
template <> struct Serializer<std::monostate> {
  static constexpr TypeId type_id =
      TypeId::NONE; // Use NONE for empty/not-applicable type

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    // Read and validate type_id for monostate
    ctx.read_varuint32(ctx.error());
    // Accept any type since monostate is just a marker with no data
  }

  static inline void write(const std::monostate &, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    (void)has_generics;
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    // No data to write for monostate
  }

  static inline void write_data(const std::monostate &, WriteContext &ctx) {
    // No data to write for monostate
  }

  static inline void write_data_generic(const std::monostate &,
                                        WriteContext &ctx, bool has_generics) {
    // No data to write for monostate
  }

  static inline std::monostate read(ReadContext &ctx, RefMode ref_mode,
                                    bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return std::monostate{};
    }
    if (read_type) {
      read_type_info(ctx);
    }
    return std::monostate{};
  }

  static inline std::monostate read_data(ReadContext &ctx) {
    // No data to read for monostate
    return std::monostate{};
  }

  static inline std::monostate read_with_type_info(ReadContext &ctx,
                                                   RefMode ref_mode,
                                                   const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

// ============================================================================
// std::variant Serialization Helpers
// ============================================================================

/// Helper to write variant data by dispatching to the correct alternative's
/// serializer based on the active index.
template <typename Variant, size_t Index = 0>
inline void write_variant_by_index(const Variant &variant, WriteContext &ctx,
                                   size_t active_index, RefMode ref_mode,
                                   bool write_type) {
  if constexpr (Index < std::variant_size_v<Variant>) {
    if (Index == active_index) {
      using AlternativeType = std::variant_alternative_t<Index, Variant>;
      const auto &value = std::get<Index>(variant);
      Serializer<AlternativeType>::write(value, ctx, ref_mode, write_type);
    } else {
      write_variant_by_index<Variant, Index + 1>(variant, ctx, active_index,
                                                 ref_mode, write_type);
    }
  } else {
    // Should never reach here if active_index is valid
    ctx.set_error(Error::invalid_data("Invalid variant index: " +
                                      std::to_string(active_index)));
  }
}

/// Helper to read variant data by dispatching to the correct alternative's
/// serializer based on the stored index.
template <typename Variant, size_t Index = 0>
inline Variant read_variant_by_index(ReadContext &ctx, size_t stored_index,
                                     RefMode ref_mode, bool read_type) {
  if constexpr (Index < std::variant_size_v<Variant>) {
    if (Index == stored_index) {
      using AlternativeType = std::variant_alternative_t<Index, Variant>;
      AlternativeType value =
          Serializer<AlternativeType>::read(ctx, ref_mode, read_type);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        // Return a default-constructed variant with the first alternative
        return Variant{};
      }
      return Variant{std::in_place_index<Index>, std::move(value)};
    } else {
      return read_variant_by_index<Variant, Index + 1>(ctx, stored_index,
                                                       ref_mode, read_type);
    }
  } else {
    // Invalid index - set error and return default
    ctx.set_error(Error::invalid_data("Invalid variant index during read: " +
                                      std::to_string(stored_index)));
    return Variant{};
  }
}

// ============================================================================
// std::variant Serializer
// ============================================================================

/// Serializer for std::variant<Ts...>
///
/// Serializes variant by storing:
/// 1. The active alternative index (as varuint32)
/// 2. The value of the active alternative
///
/// This allows the deserializer to determine which alternative to construct
/// and forward to the appropriate serializer.
///
/// Example:
/// ```cpp
/// std::variant<int, std::string, double> v = "hello";
/// // Serializes: index=1, then string data
/// ```
template <typename... Ts> struct Serializer<std::variant<Ts...>> {
  // Use UNION type_id since variant is a sum type (union) that can hold
  // one of several alternative types. The actual alternative type is determined
  // by the stored index.
  static constexpr TypeId type_id = TypeId::UNION;

  using VariantType = std::variant<Ts...>;

  static inline void write_type_info(WriteContext &ctx) {
    // Write UNION type_id to indicate variant/sum type
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    // Read and validate UNION type_id
    uint32_t type_id_read = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(type_id_read, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const VariantType &variant, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    (void)has_generics;
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data(variant, ctx);
  }

  static inline void write_data(const VariantType &variant, WriteContext &ctx) {
    // Write the active variant index
    size_t active_index = variant.index();
    ctx.write_varuint32(static_cast<uint32_t>(active_index));

    // Dispatch to the appropriate alternative's serializer
    // In xlang/compatible mode, write type info for the alternative
    bool write_alt_type = ctx.is_xlang() || ctx.is_compatible();
    RefMode alt_ref_mode = ctx.is_xlang() ? RefMode::Tracking : RefMode::None;
    write_variant_by_index(variant, ctx, active_index, alt_ref_mode,
                           write_alt_type);
  }

  static inline void write_data_generic(const VariantType &variant,
                                        WriteContext &ctx, bool has_generics) {
    write_data(variant, ctx);
  }

  static inline VariantType read(ReadContext &ctx, RefMode ref_mode,
                                 bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (!has_value) {
      return VariantType{};
    }
    if (read_type) {
      read_type_info(ctx);
    }
    return read_data(ctx);
  }

  static inline VariantType read_data(ReadContext &ctx) {
    // Read the stored variant index
    uint32_t stored_index = ctx.read_varuint32(ctx.error());
    // Validate index is within bounds
    if (FORY_PREDICT_FALSE(stored_index >= std::variant_size_v<VariantType>)) {
      ctx.set_error(Error::invalid_data(
          "Variant index out of bounds: " + std::to_string(stored_index) +
          " (max: " + std::to_string(std::variant_size_v<VariantType> - 1) +
          ")"));
      return VariantType{};
    }

    // Dispatch to the appropriate alternative's serializer
    // In xlang/compatible mode, read type info for the alternative
    bool read_alt_type = ctx.is_xlang() || ctx.is_compatible();
    RefMode alt_ref_mode = ctx.is_xlang() ? RefMode::Tracking : RefMode::None;
    return read_variant_by_index<VariantType>(ctx, stored_index, alt_ref_mode,
                                              read_alt_type);
  }

  static inline VariantType read_with_type_info(ReadContext &ctx,
                                                RefMode ref_mode,
                                                const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
