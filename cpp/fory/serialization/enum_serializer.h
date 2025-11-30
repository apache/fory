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
/// Writes the enum ordinal (underlying integral value) to match the xlang
/// specification for value-based enums.
template <typename E>
struct Serializer<E, std::enable_if_t<std::is_enum_v<E>>> {
  static constexpr TypeId type_id = TypeId::ENUM;

  using Metadata = meta::EnumMetadata<E>;
  using OrdinalType = typename Metadata::OrdinalType;

  static inline Result<void, Error> write_type_info(WriteContext &ctx) {
    // Use compile-time type lookup for faster enum type info writing
    return ctx.write_enum_typeinfo<E>();
  }

  static inline Result<void, Error> read_type_info(ReadContext &ctx) {
    FORY_TRY(type_info, ctx.read_any_typeinfo());
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      return Unexpected(Error::type_mismatch(type_info->type_id,
                                             static_cast<uint32_t>(type_id)));
    }
    return Result<void, Error>();
  }

  static inline Result<void, Error> write(E value, WriteContext &ctx,
                                          bool write_ref, bool write_type,
                                          bool has_generics = false) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      FORY_RETURN_NOT_OK(write_type_info(ctx));
    }
    return write_data_generic(value, ctx, has_generics);
  }

  static inline Result<void, Error> write_data(E value, WriteContext &ctx) {
    OrdinalType ordinal{};
    if (!Metadata::to_ordinal(value, &ordinal)) {
      return Unexpected(Error::unknown_enum("Unknown enum value"));
    }
    // Enums are encoded as unsigned varints in the xlang spec and in
    // the Java implementation (see EnumSerializer.xwrite).  Use
    // varuint32 here instead of the generic int32 zig-zag encoding so
    // that ordinal bytes are identical across languages.
    ctx.write_varuint32(static_cast<uint32_t>(ordinal));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(E value, WriteContext &ctx, bool has_generics) {
    (void)has_generics;
    return write_data(value, ctx);
  }

  static inline Result<E, Error> read(ReadContext &ctx, bool read_ref,
                                      bool read_type) {
    // Java xlang object serializer treats enum fields (in the
    // "other" group) as nullable values with an explicit null flag
    // in front of the ordinal, but does not use the general
    // reference-tracking protocol for them. When reading xlang
    // payloads and the caller did not request reference metadata,
    // mirror that layout: consume a single null/not-null flag and
    // then read the ordinal.
    if (ctx.is_xlang() && !read_ref) {
      FORY_TRY(flag, ctx.read_int8());
      if (flag == NULL_FLAG) {
        // Represent Java null as the default enum value.
        return E{};
      }
      // For NOT_NULL_VALUE_FLAG or REF_VALUE_FLAG we simply proceed to
      // read the ordinal; schema-consistent named enums are handled at
      // a higher layer via type metadata.
      return read_data(ctx);
    }

    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return E{};
    }
    if (read_type) {
      // Use overload without type_index (fast path)
      FORY_RETURN_NOT_OK(
          ctx.read_enum_type_info(static_cast<uint32_t>(type_id)));
    }
    return read_data(ctx);
  }

  static inline Result<E, Error> read_data(ReadContext &ctx) {
    FORY_TRY(raw_ordinal, ctx.read_varuint32());
    OrdinalType ordinal = static_cast<OrdinalType>(raw_ordinal);
    E value{};
    if (!Metadata::from_ordinal(ordinal, &value)) {
      return Unexpected(
          Error::unknown_enum("Invalid ordinal value: " +
                              std::to_string(static_cast<long long>(ordinal))));
    }
    return value;
  }
};

} // namespace serialization
} // namespace fory
