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

#include "fory/meta/enum_info.h"
#include "fory/serialization/context.h"
#include "fory/serialization/serializer.h"
#include "fory/type/type.h"
#include "fory/util/error.h"
#include "fory/util/result.h"
#include <cstdint>
#include <type_traits>

#ifdef FORY_DEBUG
#include <iostream>
#endif

namespace fory {
namespace serialization {

/// Serializer specialization for enum types.
///
/// Writes enum values using xlang-compatible uint32 wire ids. Registered enums
/// with contiguous zero-based values keep ordinal encoding; enums with sparse
/// non-negative declared values preserve those declared values on the wire.
template <typename E>
struct Serializer<E, std::enable_if_t<std::is_enum_v<E>>> {
  static constexpr TypeId type_id = TypeId::ENUM;

  using Metadata = meta::EnumMetadata<E>;
  using OrdinalType = typename Metadata::OrdinalType;
  using EnumInfo = meta::EnumInfo<E>;

  static constexpr bool supports_explicit_wire_values() {
    if constexpr (!EnumInfo::defined) {
      return true;
    }
    for (std::size_t i = 0; i < EnumInfo::size; ++i) {
      const auto raw = static_cast<OrdinalType>(EnumInfo::values[i]);
      if constexpr (std::is_signed_v<OrdinalType>) {
        if (raw < 0) {
          return false;
        }
      }
      using Unsigned = std::make_unsigned_t<OrdinalType>;
      if (static_cast<Unsigned>(raw) != static_cast<Unsigned>(i)) {
        return true;
      }
    }
    return false;
  }

  static inline void write_type_info(WriteContext &ctx) {
    // Use compile-time type lookup for faster enum type info writing
    ctx.write_enum_type_info<E>();
  }

  static inline void read_type_info(ReadContext &ctx) {
    auto type_info_res = ctx.read_enum_type_info(
        std::type_index(typeid(E)), static_cast<uint32_t>(type_id));
    if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
      ctx.set_error(std::move(type_info_res).error());
      return;
    }
  }

  static inline void write(E value, WriteContext &ctx, RefMode ref_mode,
                           bool write_type, bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      write_type_info(ctx);
    }
    write_data_generic(value, ctx, has_generics);
  }

  static inline void write_data(E value, WriteContext &ctx) {
    if constexpr (supports_explicit_wire_values()) {
      if constexpr (EnumInfo::defined) {
        if (!EnumInfo::contains(value)) {
          ctx.set_error(Error::unknown_enum("Unknown enum value"));
          return;
        }
      }
      const auto raw_value = static_cast<OrdinalType>(value);
      if constexpr (std::is_signed_v<OrdinalType>) {
        if (raw_value < 0) {
          ctx.set_error(
              Error::unknown_enum("Negative enum values are not supported"));
          return;
        }
      }
      ctx.write_var_uint32(static_cast<uint32_t>(raw_value));
      return;
    }
    OrdinalType ordinal{};
    if (!Metadata::to_ordinal(value, &ordinal)) {
      ctx.set_error(Error::unknown_enum("Unknown enum value"));
      return;
    }
    // Enums are encoded as unsigned varints in the xlang spec
    ctx.write_var_uint32(static_cast<uint32_t>(ordinal));
  }

  static inline void write_data_generic(E value, WriteContext &ctx, bool) {
    write_data(value, ctx);
  }

  static inline E read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    // Handle null/ref flag if requested.
    // In compatible mode, the caller (read_struct_fields_compatible) determines
    // whether to pass read_ref=true based on the remote TypeDef's nullable
    // flag. In non-compatible mode, read_ref is based on C++ type traits. When
    // reading through std::optional, the optional serializer already handles
    // the null flag and calls us with read_ref=false.
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return E{};
    }
    if (read_type) {
      read_type_info(ctx);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return E{};
      }
    }
    return read_data(ctx);
  }

  static inline E read_data(ReadContext &ctx) {
    uint32_t raw_ordinal = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return E{};
    }
    if constexpr (supports_explicit_wire_values()) {
      if (raw_ordinal >
          static_cast<uint32_t>(std::numeric_limits<OrdinalType>::max())) {
        ctx.set_error(Error::unknown_enum(
            "Invalid enum value: " +
            std::to_string(static_cast<unsigned long long>(raw_ordinal))));
        return E{};
      }
      const auto value = static_cast<E>(static_cast<OrdinalType>(raw_ordinal));
      if constexpr (EnumInfo::defined) {
        if (!EnumInfo::contains(value)) {
          ctx.set_error(Error::unknown_enum(
              "Invalid enum value: " +
              std::to_string(static_cast<unsigned long long>(raw_ordinal))));
          return E{};
        }
      }
      return value;
    }
    OrdinalType ordinal = static_cast<OrdinalType>(raw_ordinal);
    E value{};
    if (!Metadata::from_ordinal(ordinal, &value)) {
      ctx.set_error(
          Error::unknown_enum("Invalid ordinal value: " +
                              std::to_string(static_cast<long long>(ordinal))));
      return E{};
    }
    return value;
  }

  static inline E read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                      const TypeInfo &) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
