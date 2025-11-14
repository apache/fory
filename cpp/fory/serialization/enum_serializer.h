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

  static inline Result<void, Error> write(E value, WriteContext &ctx,
                                          bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    return write_data(value, ctx);
  }

  static inline Result<void, Error> write_data(E value, WriteContext &ctx) {
    OrdinalType ordinal{};
    if (!Metadata::to_ordinal(value, &ordinal)) {
      return Unexpected(Error::unknown_enum("Unknown enum value"));
    }
    return Serializer<OrdinalType>::write_data(ordinal, ctx);
  }

  static inline Result<void, Error>
  write_data_generic(E value, WriteContext &ctx, bool has_generics) {
    (void)has_generics;
    return write_data(value, ctx);
  }

  static inline Result<E, Error> read(ReadContext &ctx, bool read_ref,
                                      bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return E{};
    }
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline Result<E, Error> read_data(ReadContext &ctx) {
    FORY_TRY(ordinal, Serializer<OrdinalType>::read_data(ctx));
    E value{};
    if (!Metadata::from_ordinal(ordinal, &value)) {
      return Unexpected(Error::unknown_enum("Invalid ordinal value"));
    }
    return value;
  }
};

} // namespace serialization
} // namespace fory