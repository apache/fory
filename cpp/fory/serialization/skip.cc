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

#include "fory/serialization/skip.h"
#include "fory/serialization/ref_resolver.h"
#include "fory/serialization/serializer.h"
#include "fory/util/result.h"

namespace fory {
namespace serialization {

Result<void, Error> skip_varint(ReadContext &ctx) {
  // Skip varint by reading it
  FORY_TRY(_, ctx.read_varuint64());
  return {};
}

Result<void, Error> skip_string(ReadContext &ctx) {
  // Read string length + encoding
  FORY_TRY(size_encoding, ctx.read_varuint64());
  uint64_t size = size_encoding >> 2;

  // Skip string data
  ctx.buffer().IncreaseReaderIndex(size);
  return {};
}

Result<void, Error> skip_list(ReadContext &ctx, const FieldType &field_type) {
  // Read list length
  FORY_TRY(length, ctx.read_varuint64());

  // Read elements header
  FORY_TRY(header, ctx.read_uint8());

  bool track_ref = (header & 0b1) != 0;
  bool has_null = (header & 0b10) != 0;
  bool is_declared_type = (header & 0b100) != 0;

  // If not declared type, skip element type info once
  if (!is_declared_type) {
    FORY_TRY(_, ctx.read_uint8());
  }

  // Get element type
  FieldType elem_type;
  if (!field_type.generics.empty()) {
    elem_type = field_type.generics[0];
  } else {
    // Unknown element type, need to read type info for each element
    elem_type.type_id = 0; // Unknown
    elem_type.nullable = false;
  }

  // Skip each element
  for (uint64_t i = 0; i < length; ++i) {
    if (track_ref) {
      // Read and check ref flag
      FORY_TRY(ref_flag, ctx.read_int8());
      if (ref_flag == NULL_FLAG || ref_flag == REF_FLAG) {
        continue; // Null or reference, already handled
      }
    } else if (has_null) {
      // Read null flag
      FORY_TRY(null_flag, ctx.read_int8());
      if (null_flag == NULL_FLAG) {
        continue; // Null value
      }
    }

    // Skip element value
    FORY_RETURN_NOT_OK(
        skip_field_value(ctx, elem_type, false)); // No ref flag for elements
  }

  return {};
}

Result<void, Error> skip_set(ReadContext &ctx, const FieldType &field_type) {
  // Set has same format as list
  return skip_list(ctx, field_type);
}

Result<void, Error> skip_map(ReadContext &ctx, const FieldType &field_type) {
  // Read map length
  FORY_TRY(total_length, ctx.read_varuint64());

  // Get key and value types
  FieldType key_type, value_type;
  if (field_type.generics.size() >= 2) {
    key_type = field_type.generics[0];
    value_type = field_type.generics[1];
  } else {
    // Unknown types
    key_type.type_id = 0;
    value_type.type_id = 0;
  }

  uint64_t read_count = 0;
  while (read_count < total_length) {
    // Read chunk header
    FORY_TRY(chunk_header, ctx.read_uint8());

    // Read chunk size
    FORY_TRY(chunk_size, ctx.read_uint8());

    // Extract flags from chunk header
    bool key_track_ref = (chunk_header & 0b1) != 0;
    bool key_has_null = (chunk_header & 0b10) != 0;
    bool value_track_ref = (chunk_header & 0b1000) != 0;
    bool value_has_null = (chunk_header & 0b10000) != 0;

    // Skip key-value pairs in this chunk
    for (uint8_t i = 0; i < chunk_size; ++i) {
      // Skip key with ref flag if needed
      if (key_track_ref || key_has_null) {
        FORY_TRY(key_ref, ctx.read_int8());
        if (key_ref != NOT_NULL_VALUE_FLAG && key_ref != REF_VALUE_FLAG) {
          continue; // Null or ref, skip value too
        }
      }
      FORY_RETURN_NOT_OK(skip_field_value(ctx, key_type, false));

      // Skip value with ref flag if needed
      if (value_track_ref || value_has_null) {
        FORY_TRY(val_ref, ctx.read_int8());
        if (val_ref != NOT_NULL_VALUE_FLAG && val_ref != REF_VALUE_FLAG) {
          continue; // Null or ref
        }
      }
      FORY_RETURN_NOT_OK(skip_field_value(ctx, value_type, false));
    }

    read_count += chunk_size;
  }

  return {};
}

Result<void, Error> skip_struct(ReadContext &ctx, const FieldType &field_type) {
  // TODO: Implement proper struct skipping with type meta
  // For now, return error as this is complex and requires read_any_typeinfo()
  return Unexpected(
      Error::type_error("Struct skipping not yet fully implemented"));
}

Result<void, Error> skip_field_value(ReadContext &ctx,
                                     const FieldType &field_type,
                                     bool read_ref_flag) {
  // Read ref flag if needed
  if (read_ref_flag) {
    FORY_TRY(ref_flag, ctx.read_int8());
    if (ref_flag == NULL_FLAG || ref_flag == REF_FLAG) {
      return {}; // Null or reference, nothing more to skip
    }
  }

  // Skip based on type ID
  TypeId tid = static_cast<TypeId>(field_type.type_id);

  switch (tid) {
  case TypeId::BOOL:
  case TypeId::INT8:
    ctx.buffer().IncreaseReaderIndex(1);
    return {};

  case TypeId::INT16:
  case TypeId::FLOAT16:
    ctx.buffer().IncreaseReaderIndex(2);
    return {};

  case TypeId::INT32:
  case TypeId::FLOAT32:
    ctx.buffer().IncreaseReaderIndex(4);
    return {};

  case TypeId::INT64:
  case TypeId::FLOAT64:
    ctx.buffer().IncreaseReaderIndex(8);
    return {};

  case TypeId::VAR_INT32:
  case TypeId::VAR_INT64:
    return skip_varint(ctx);

  case TypeId::STRING:
    return skip_string(ctx);

  case TypeId::LIST:
    return skip_list(ctx, field_type);

  case TypeId::SET:
    return skip_set(ctx, field_type);

  case TypeId::MAP:
    return skip_map(ctx, field_type);

  case TypeId::STRUCT:
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
    return skip_struct(ctx, field_type);

  // Primitive arrays
  case TypeId::BOOL_ARRAY:
  case TypeId::INT8_ARRAY:
  case TypeId::INT16_ARRAY:
  case TypeId::INT32_ARRAY:
  case TypeId::INT64_ARRAY:
  case TypeId::FLOAT16_ARRAY:
  case TypeId::FLOAT32_ARRAY:
  case TypeId::FLOAT64_ARRAY: {
    // Read array length
    FORY_TRY(len, ctx.read_varuint32());

    // Calculate element size
    size_t elem_size = 1;
    switch (tid) {
    case TypeId::INT16_ARRAY:
    case TypeId::FLOAT16_ARRAY:
      elem_size = 2;
      break;
    case TypeId::INT32_ARRAY:
    case TypeId::FLOAT32_ARRAY:
      elem_size = 4;
      break;
    case TypeId::INT64_ARRAY:
    case TypeId::FLOAT64_ARRAY:
      elem_size = 8;
      break;
    default:
      break;
    }

    ctx.buffer().IncreaseReaderIndex(len * elem_size);
    return {};
  }

  case TypeId::BINARY: {
    // Read binary length
    FORY_TRY(len, ctx.read_varuint32());
    ctx.buffer().IncreaseReaderIndex(len);
    return {};
  }

  default:
    return Unexpected(
        Error::type_error("Unknown field type to skip: " +
                          std::to_string(static_cast<uint32_t>(tid))));
  }
}

} // namespace serialization
} // namespace fory
