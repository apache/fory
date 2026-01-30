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

#include "fory/serialization/context.h"
#include "fory/serialization/ref_mode.h"
#include "fory/serialization/type_resolver.h"
#include "fory/type/type.h"
#include "fory/util/error.h"

namespace fory {
namespace serialization {

/// skip a field value in the buffer based on type information
/// This is used during schema evolution to skip fields that don't exist in the
/// local type
///
/// @param ctx Read context (errors are set on ctx.error_)
/// @param field_type Field type information
/// @param ref_mode Reference mode for the field
void skip_field_value(ReadContext &ctx, const FieldType &field_type,
                      RefMode ref_mode);

/// skip a varint value
void skip_varint(ReadContext &ctx);

/// skip a string value
void skip_string(ReadContext &ctx);

/// skip a list value
void skip_list(ReadContext &ctx, const FieldType &field_type);

/// skip a set value
void skip_set(ReadContext &ctx, const FieldType &field_type);

/// skip a map value
void skip_map(ReadContext &ctx, const FieldType &field_type);

/// skip a union (variant) value
void skip_union(ReadContext &ctx);

/// skip a struct value
void skip_struct(ReadContext &ctx, const FieldType &field_type);

/// skip an ext (extension) value
void skip_ext(ReadContext &ctx, const FieldType &field_type);

/// skip an unknown (polymorphic) value
/// The actual type info is written inline in the buffer
void skip_unknown(ReadContext &ctx);

} // namespace serialization
} // namespace fory
