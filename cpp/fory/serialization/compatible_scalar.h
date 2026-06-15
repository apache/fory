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

#include "fory/serialization/ref_mode.h"
#include "fory/util/bfloat16.h"
#include "fory/util/float16.h"

#include <cstdint>
#include <string>
#include <string_view>

namespace fory {
namespace serialization {

class ReadContext;
class Decimal;

bool compatible_scalar_field_types(uint32_t local_type_id,
                                   uint32_t remote_type_id);

bool read_compatible_scalar_present(ReadContext &ctx, RefMode ref_mode);

bool read_compatible_bool(ReadContext &ctx, uint32_t remote_type_id,
                          std::string_view field);
int8_t read_compatible_int8(ReadContext &ctx, uint32_t remote_type_id,
                            std::string_view field);
uint8_t read_compatible_uint8(ReadContext &ctx, uint32_t remote_type_id,
                              std::string_view field);
int16_t read_compatible_int16(ReadContext &ctx, uint32_t remote_type_id,
                              std::string_view field);
uint16_t read_compatible_uint16(ReadContext &ctx, uint32_t remote_type_id,
                                std::string_view field);
int32_t read_compatible_int32(ReadContext &ctx, uint32_t remote_type_id,
                              std::string_view field);
uint32_t read_compatible_uint32(ReadContext &ctx, uint32_t remote_type_id,
                                std::string_view field);
int64_t read_compatible_int64(ReadContext &ctx, uint32_t remote_type_id,
                              std::string_view field);
uint64_t read_compatible_uint64(ReadContext &ctx, uint32_t remote_type_id,
                                std::string_view field);
float16_t read_compatible_float16(ReadContext &ctx, uint32_t remote_type_id,
                                  std::string_view field);
bfloat16_t read_compatible_bfloat16(ReadContext &ctx, uint32_t remote_type_id,
                                    std::string_view field);
float read_compatible_float32(ReadContext &ctx, uint32_t remote_type_id,
                              std::string_view field);
double read_compatible_float64(ReadContext &ctx, uint32_t remote_type_id,
                               std::string_view field);
Decimal read_compatible_decimal(ReadContext &ctx, uint32_t remote_type_id,
                                std::string_view field);
std::string read_compatible_string(ReadContext &ctx, uint32_t remote_type_id,
                                   std::string_view field);

} // namespace serialization
} // namespace fory
