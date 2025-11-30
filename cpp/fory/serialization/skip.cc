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

namespace {

// Mirror Rust's field_need_write_ref_into(type_id, nullable) for runtime
// computation of whether a field writes ref/null flags before the value.
inline bool field_need_write_ref_into_runtime(const FieldType &field_type) {
  if (field_type.nullable) {
    return true;
  }
  uint32_t internal = field_type.type_id & 0xffu;
  TypeId tid = static_cast<TypeId>(internal);
  switch (tid) {
  case TypeId::BOOL:
  case TypeId::INT8:
  case TypeId::INT16:
  case TypeId::INT32:
  case TypeId::INT64:
  case TypeId::FLOAT32:
  case TypeId::FLOAT64:
    return false;
  default:
    return true;
  }
}

} // namespace

Result<void, Error> skip_varint(ReadContext &ctx) {
  // Skip varint by reading it
  FORY_RETURN_NOT_OK(ctx.read_varuint64());
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
    FORY_RETURN_NOT_OK(ctx.read_uint8());
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
  // Struct fields in compatible mode are serialized with type_id and
  // meta_index followed by field values. We use the loaded TypeMeta to
  // skip all fields for the remote struct.

  if (!ctx.is_compatible()) {
    return Unexpected(Error::unsupported(
        "Struct skipping is only supported in compatible mode"));
  }

  // Read remote type_id (ignored for now) and meta_index
  FORY_TRY(remote_type_id, ctx.read_varuint32());
  (void)remote_type_id;

  FORY_TRY(meta_index, ctx.read_varuint32());
  FORY_TRY(type_info, ctx.get_type_info_by_index(meta_index));
  if (!type_info || !type_info->type_meta) {
    return Unexpected(
        Error::type_error("TypeInfo or TypeMeta not found for struct skip"));
  }

  const auto &field_infos = type_info->type_meta->get_field_infos();

  for (const auto &fi : field_infos) {
    bool read_ref = field_need_write_ref_into_runtime(fi.field_type);
    FORY_RETURN_NOT_OK(skip_field_value(ctx, fi.field_type, read_ref));
  }

  return Result<void, Error>();
}

Result<void, Error> skip_ext(ReadContext &ctx, const FieldType &field_type) {
  // EXT fields in compatible mode are serialized with type_id and meta_index
  // (for named ext) or just the user type_id (for id-based ext).
  // We look up the registered ext harness and call its read_data function.

  if (!ctx.is_compatible()) {
    return Unexpected(Error::unsupported(
        "Ext skipping is only supported in compatible mode"));
  }

  uint32_t full_type_id = field_type.type_id;
  uint32_t low = full_type_id & 0xffu;
  TypeId tid = static_cast<TypeId>(low);

  std::shared_ptr<TypeInfo> type_info;

  if (tid == TypeId::NAMED_EXT) {
    // Named ext: read type_id and meta_index
    FORY_TRY(remote_type_id, ctx.read_varuint32());
    (void)remote_type_id;

    FORY_TRY(meta_index, ctx.read_varuint32());
    FORY_TRY(type_info_result, ctx.get_type_info_by_index(meta_index));
    type_info = type_info_result;
  } else {
    // ID-based ext: look up by full type_id
    // The ext fields in TypeMeta store the user type_id (high bits | EXT)
    type_info = ctx.type_resolver().get_type_info_by_id(full_type_id);
  }

  if (!type_info) {
    return Unexpected(Error::type_error("TypeInfo not found for ext type: " +
                                        std::to_string(full_type_id)));
  }

  if (!type_info->harness.valid() || !type_info->harness.read_data_fn) {
    return Unexpected(
        Error::type_error("Ext harness not found or incomplete for type: " +
                          std::to_string(full_type_id)));
  }

  // Check and increase dynamic depth for polymorphic deserialization
  FORY_RETURN_NOT_OK(ctx.increase_dyn_depth());
  DynDepthGuard dyn_depth_guard(ctx);

  // Call the harness read_data_fn to skip the data
  // The result is a pointer we need to delete
  FORY_TRY(ptr, type_info->harness.read_data_fn(ctx));
  if (ptr) {
    // We just wanted to skip, but harness allocates memory - need to clean up
    // This is not ideal but works for now. A better approach would be to
    // have a dedicated skip_data function in harness.
    // For now, we use operator delete which works for POD types.
    // TODO: Consider adding a harness.skip_data_fn or harness.delete_fn
    ::operator delete(ptr);
  }

  return Result<void, Error>();
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

  // Skip based on low 8 bits of the type ID.
  //
  // xlang user types encode the user type id in the high bits and the
  // logical TypeId in the low 8 bits (see Java XtypeResolver and Rust
  // TypeId conventions). For skipping we only care about the logical
  // category (STRUCT/ENUM/EXT/etc.), so mask off the user id portion.
  uint32_t low = field_type.type_id & 0xffu;
  TypeId tid = static_cast<TypeId>(low);

  switch (tid) {
  case TypeId::BOOL:
  case TypeId::INT8:
    ctx.buffer().IncreaseReaderIndex(1);
    return {};

  case TypeId::INT16:
  case TypeId::FLOAT16:
    ctx.buffer().IncreaseReaderIndex(2);
    return {};

  case TypeId::INT32: {
    // INT32 values are encoded as varint32 in the C++
    // serializer, so we must consume a varint32 here
    // instead of assuming fixed-width encoding.
    FORY_TRY(ignored_i32, ctx.read_varint32());
    (void)ignored_i32;
    return {};
  }

  case TypeId::FLOAT32:
    ctx.buffer().IncreaseReaderIndex(4);
    return {};

  case TypeId::INT64: {
    // INT64 values are encoded as varint64 in the C++
    // serializer, so we must consume a varint64 here
    // instead of assuming fixed-width encoding.
    FORY_TRY(ignored_i64, ctx.read_varint64());
    (void)ignored_i64;
    return {};
  }

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

  case TypeId::DURATION:
  case TypeId::TIMESTAMP: {
    // Duration/Timestamp are stored as fixed 8-byte
    // nanosecond counts.
    constexpr uint32_t kBytes = static_cast<uint32_t>(sizeof(int64_t));
    if (ctx.buffer().reader_index() + kBytes > ctx.buffer().size()) {
      return Unexpected(Error::buffer_out_of_bound(
          ctx.buffer().reader_index(), kBytes, ctx.buffer().size()));
    }
    ctx.buffer().IncreaseReaderIndex(kBytes);
    return {};
  }

  case TypeId::LOCAL_DATE: {
    // LocalDate is stored as fixed 4-byte day count.
    constexpr uint32_t kBytes = static_cast<uint32_t>(sizeof(int32_t));
    if (ctx.buffer().reader_index() + kBytes > ctx.buffer().size()) {
      return Unexpected(Error::buffer_out_of_bound(
          ctx.buffer().reader_index(), kBytes, ctx.buffer().size()));
    }
    ctx.buffer().IncreaseReaderIndex(kBytes);
    return {};
  }

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

  case TypeId::ENUM:
  case TypeId::NAMED_ENUM: {
    // Enums are serialized as ordinal varuint32 values.
    FORY_TRY(ignored, ctx.read_varuint32());
    (void)ignored;
    return {};
  }

  case TypeId::EXT:
  case TypeId::NAMED_EXT:
    return skip_ext(ctx, field_type);

  default:
    return Unexpected(
        Error::type_error("Unknown field type to skip: " +
                          std::to_string(static_cast<uint32_t>(tid))));
  }
}

} // namespace serialization
} // namespace fory
