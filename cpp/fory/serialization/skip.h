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
#include "fory/serialization/type_resolver.h"
#include "fory/type/type.h"
#include "fory/util/error.h"
#include "fory/util/result.h"

namespace fory {
namespace serialization {

/// Skip a field value in the buffer based on type information
/// This is used during schema evolution to skip fields that don't exist in the
/// local type
///
/// @param ctx Read context
/// @param field_type Field type information
/// @param read_ref_flag Whether to read reference flag
/// @return Result indicating success or error
Result<void, Error> skip_field_value(ReadContext &ctx,
                                     const FieldType &field_type,
                                     bool read_ref_flag);

/// Skip a varint value
Result<void, Error> skip_varint(ReadContext &ctx);

/// Skip a string value
Result<void, Error> skip_string(ReadContext &ctx);

/// Skip a list value
Result<void, Error> skip_list(ReadContext &ctx, const FieldType &field_type);

/// Skip a set value
Result<void, Error> skip_set(ReadContext &ctx, const FieldType &field_type);

/// Skip a map value
Result<void, Error> skip_map(ReadContext &ctx, const FieldType &field_type);

/// Skip a struct value
Result<void, Error> skip_struct(ReadContext &ctx, const FieldType &field_type);

} // namespace serialization
} // namespace fory
