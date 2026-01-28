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
#include "fory/serialization/type_info.h"
#include "fory/serialization/type_resolver.h"
#include "fory/type/type.h"
#include "fory/util/error.h"
#include "fory/util/result.h"

#include <any>
#include <typeindex>

namespace fory {
namespace serialization {

// ============================================================================
// std::any Serializer
// ============================================================================

/// Serializer for std::any.
///
/// std::any serialization requires explicit registration of allowed value
/// types via register_any_type<T>(). Only registered types can be serialized
/// or deserialized.
template <> struct Serializer<std::any> {
  static constexpr TypeId type_id = TypeId::UNKNOWN;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.set_error(Error::invalid("std::any requires runtime type info"));
  }

  static inline void read_type_info(ReadContext &ctx) {
    ctx.set_error(Error::invalid("std::any requires runtime type info"));
  }

  static inline void write(const std::any &value, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    (void)has_generics;
    if (ref_mode != RefMode::None) {
      if (!value.has_value()) {
        ctx.write_int8(NULL_FLAG);
        return;
      }
      write_not_null_ref_flag(ctx, ref_mode);
    } else if (FORY_PREDICT_FALSE(!value.has_value())) {
      ctx.set_error(Error::invalid("std::any requires non-empty value"));
      return;
    }

    const std::type_index concrete_type_id(value.type());
    auto type_info_res = ctx.type_resolver().get_type_info(concrete_type_id);
    if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
      ctx.set_error(Error::type_error("std::any type is not registered"));
      return;
    }
    const TypeInfo *type_info = type_info_res.value();
    if (FORY_PREDICT_FALSE(type_info->harness.any_write_fn == nullptr)) {
      ctx.set_error(Error::type_error("std::any type is not registered"));
      return;
    }

    if (write_type) {
      uint32_t fory_type_id = type_info->type_id;
      uint32_t type_id_arg = is_internal_type(fory_type_id)
                                 ? fory_type_id
                                 : static_cast<uint32_t>(TypeId::UNKNOWN);
      auto write_res = ctx.write_any_typeinfo(type_id_arg, concrete_type_id);
      if (FORY_PREDICT_FALSE(!write_res.ok())) {
        ctx.set_error(std::move(write_res).error());
        return;
      }
    }

    type_info->harness.any_write_fn(value, ctx);
  }

  static inline void write_data(const std::any &, WriteContext &ctx) {
    ctx.set_error(Error::invalid("std::any requires type info for writing"));
  }

  static inline void write_data_generic(const std::any &value,
                                        WriteContext &ctx, bool has_generics) {
    (void)has_generics;
    write_data(value, ctx);
  }

  static inline std::any read(ReadContext &ctx, RefMode ref_mode,
                              bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::any();
    }
    if (FORY_PREDICT_FALSE(!read_type)) {
      ctx.set_error(Error::invalid("std::any requires read_type=true"));
      return std::any();
    }

    const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::any();
    }

    if (FORY_PREDICT_FALSE(type_info->harness.any_read_fn == nullptr)) {
      ctx.set_error(Error::type_error("std::any type is not registered"));
      return std::any();
    }

    return type_info->harness.any_read_fn(ctx);
  }

  static inline std::any read_data(ReadContext &ctx) {
    ctx.set_error(Error::invalid("std::any requires type info for reading"));
    return std::any();
  }

  static inline std::any read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                             const TypeInfo &type_info) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::any();
    }

    if (FORY_PREDICT_FALSE(type_info.harness.any_read_fn == nullptr)) {
      ctx.set_error(Error::type_error("std::any type is not registered"));
      return std::any();
    }

    return type_info.harness.any_read_fn(ctx);
  }
};

/// Register a type so it can be serialized inside std::any.
///
/// For internal types (primitives, string, temporal), registration does not
/// require prior type registration. For user-defined types, the type must be
/// registered with TypeResolver first (e.g., via Fory::register_struct).
template <typename T>
Result<void, Error> register_any_type(TypeResolver &resolver) {
  return resolver.template register_any_type<T>();
}

} // namespace serialization
} // namespace fory
