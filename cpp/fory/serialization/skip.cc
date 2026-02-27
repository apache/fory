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

namespace fory {
namespace serialization {

namespace {

// Compute RefMode from field type at runtime.
//
// Per xlang protocol and Java's ObjectSerializer.write_other_field_value:
// - In xlang mode with ref=false (default), fields only write
//   ref/null flag if they are nullable
// - Primitives never have ref flags (handled separately)
} // namespace

void skip_varint(ReadContext &ctx) {
  // skip varint by reading it
  ctx.read_var_uint64(ctx.error());
}

void skip_string(ReadContext &ctx) {
  // Read string length + encoding
  uint64_t size_encoding = ctx.read_var_uint64(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  uint64_t size = size_encoding >> 2;

  // skip string data
  ctx.buffer().increase_reader_index(size, ctx.error());
}

void skip_list(ReadContext &ctx, const FieldType &field_type) {
  // Read list length
  uint64_t length = ctx.read_var_uint64(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }

  // Read elements header
  uint8_t header = ctx.read_uint8(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }

  bool track_ref = (header & 0b1) != 0;
  bool has_null = (header & 0b10) != 0;
  bool is_declared_type = (header & 0b100) != 0;

  // If not declared type, skip element type info once
  if (!is_declared_type) {
    ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
  }

  // get element type
  FieldType elem_type;
  if (!field_type.generics.empty()) {
    elem_type = field_type.generics[0];
  } else {
    // Unknown element type, need to read type info for each element
    elem_type.type_id = 0; // Unknown
    elem_type.nullable = false;
  }

  // skip each element
  for (uint64_t i = 0; i < length; ++i) {
    if (track_ref) {
      // Read and check ref flag
      int8_t ref_flag = ctx.read_int8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return;
      }
      if (ref_flag == NULL_FLAG || ref_flag == REF_FLAG) {
        continue; // Null or reference, already handled
      }
    } else if (has_null) {
      // Read null flag
      int8_t null_flag = ctx.read_int8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return;
      }
      if (null_flag == NULL_FLAG) {
        continue; // Null value
      }
    }

    // skip element value
    skip_field_value(ctx, elem_type, RefMode::None); // No ref flag for elements
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
  }
}

void skip_set(ReadContext &ctx, const FieldType &field_type) {
  // Set has same format as list
  skip_list(ctx, field_type);
}

void skip_map(ReadContext &ctx, const FieldType &field_type) {
  // Read map length
  uint64_t total_length = ctx.read_var_uint64(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }

  // get key and value types
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
    uint8_t chunk_header = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }

    // Read chunk size
    uint8_t chunk_size = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }

    // Extract flags from chunk header
    bool key_track_ref = (chunk_header & 0b1) != 0;
    bool key_has_null = (chunk_header & 0b10) != 0;
    bool value_track_ref = (chunk_header & 0b1000) != 0;
    bool value_has_null = (chunk_header & 0b10000) != 0;

    // skip key-value pairs in this chunk
    for (uint8_t i = 0; i < chunk_size; ++i) {
      // skip key with ref flag if needed
      if (key_track_ref || key_has_null) {
        int8_t key_ref = ctx.read_int8(ctx.error());
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return;
        }
        if (key_ref != NOT_NULL_VALUE_FLAG && key_ref != REF_VALUE_FLAG) {
          continue; // Null or ref, skip value too
        }
      }
      skip_field_value(ctx, key_type, RefMode::None);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return;
      }

      // skip value with ref flag if needed
      if (value_track_ref || value_has_null) {
        int8_t val_ref = ctx.read_int8(ctx.error());
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return;
        }
        if (val_ref != NOT_NULL_VALUE_FLAG && val_ref != REF_VALUE_FLAG) {
          continue; // Null or ref
        }
      }
      skip_field_value(ctx, value_type, RefMode::None);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return;
      }
    }

    read_count += chunk_size;
  }
}

void skip_struct(ReadContext &ctx, const FieldType &) {
  // Struct fields in compatible mode are serialized with type_id and
  // optionally meta_index, followed by field values. We use the loaded
  // TypeMeta to skip all fields for the remote struct.
  //
  // Type categories:
  // - COMPATIBLE_STRUCT/NAMED_COMPATIBLE_STRUCT: type_id + meta_index + fields
  // - NAMED_STRUCT in compatible mode: type_id + meta_index + fields
  // - STRUCT: type_id + struct_version + fields (no meta_index)

  if (!ctx.is_compatible()) {
    ctx.set_error(Error::unsupported(
        "Struct skipping is only supported in compatible mode"));
    return;
  }

  // Read remote type_id
  uint32_t remote_type_id = ctx.read_uint8(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }

  uint32_t user_type_id = 0;
  switch (static_cast<TypeId>(remote_type_id)) {
  case TypeId::ENUM:
  case TypeId::STRUCT:
  case TypeId::EXT:
  case TypeId::TYPED_UNION:
    user_type_id = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    break;
  default:
    break;
  }
  TypeId remote_tid = static_cast<TypeId>(remote_type_id);
  bool has_user_type_id =
      remote_tid == TypeId::ENUM || remote_tid == TypeId::STRUCT ||
      remote_tid == TypeId::EXT || remote_tid == TypeId::TYPED_UNION;

  const TypeInfo *type_info = nullptr;

  if (remote_tid == TypeId::COMPATIBLE_STRUCT ||
      remote_tid == TypeId::NAMED_COMPATIBLE_STRUCT ||
      remote_tid == TypeId::NAMED_STRUCT) {
    // These types write TypeMeta inline using streaming protocol
    auto type_info_res = ctx.read_type_meta();
    if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
      ctx.set_error(std::move(type_info_res).error());
      return;
    }
    type_info = type_info_res.value();
  } else {
    // Plain STRUCT: look up by type_id, read struct_version if enabled
    auto type_info_res =
        has_user_type_id
            ? ctx.type_resolver().get_user_type_info_by_id(remote_type_id,
                                                           user_type_id)
            : ctx.type_resolver().get_type_info_by_id(remote_type_id);
    if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
      ctx.set_error(std::move(type_info_res).error());
      return;
    }
    type_info = type_info_res.value();

    // STRUCT writes struct_version if class version checking is enabled
    // For now we skip 4 bytes for the version hash
    // TODO: make this conditional based on config
    (void)ctx.read_int32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
  }

  if (!type_info || !type_info->type_meta) {
    ctx.set_error(
        Error::type_error("TypeInfo or TypeMeta not found for struct skip"));
    return;
  }

  const auto &field_infos = type_info->type_meta->get_field_infos();

  for (const auto &fi : field_infos) {
    // Use precomputed ref_mode from field metadata
    skip_field_value(ctx, fi.field_type, fi.field_type.ref_mode);
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
  }
}

void skip_ext(ReadContext &ctx, const FieldType &) {
  // EXT fields in compatible mode are serialized with type_id followed by
  // ext data. For named ext, meta_index is also written after type_id.
  // We read the type_id, look up the registered ext harness, and call its
  // read_data function to consume the data bytes.

  if (!ctx.is_compatible()) {
    ctx.set_error(Error::unsupported(
        "Ext skipping is only supported in compatible mode"));
    return;
  }

  // Read remote type_id from buffer - Java always writes type_id for ext
  uint32_t remote_type_id = ctx.read_uint8(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }

  uint32_t user_type_id = 0;
  switch (static_cast<TypeId>(remote_type_id)) {
  case TypeId::ENUM:
  case TypeId::STRUCT:
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::EXT:
  case TypeId::TYPED_UNION:
    user_type_id = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    break;
  default:
    break;
  }
  TypeId remote_tid = static_cast<TypeId>(remote_type_id);

  const TypeInfo *type_info = nullptr;

  if (remote_tid == TypeId::NAMED_EXT) {
    // Named ext in compatible mode: read TypeMeta inline using streaming
    // protocol
    auto type_info_res = ctx.read_type_meta();
    if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
      ctx.set_error(std::move(type_info_res).error());
      return;
    }
    type_info = type_info_res.value();
  } else {
    // ID-based ext: look up by remote type_id we just read
    auto type_info_res = ctx.type_resolver().get_user_type_info_by_id(
        remote_type_id, user_type_id);
    if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
      ctx.set_error(std::move(type_info_res).error());
      return;
    }
    type_info = type_info_res.value();
  }

  if (!type_info) {
    ctx.set_error(Error::type_error("TypeInfo not found for ext type: " +
                                    std::to_string(remote_type_id)));
    return;
  }

  if (!type_info->harness.valid() || !type_info->harness.read_data_fn) {
    ctx.set_error(
        Error::type_error("Ext harness not found or incomplete for type: " +
                          std::to_string(remote_type_id)));
    return;
  }

  // Check and increase dynamic depth for polymorphic deserialization
  auto depth_res = ctx.increase_dyn_depth();
  if (FORY_PREDICT_FALSE(!depth_res.ok())) {
    ctx.set_error(std::move(depth_res).error());
    return;
  }
  DynDepthGuard dyn_depth_guard(ctx);

  // Call the harness read_data_fn to skip the data
  // The result is a pointer we need to delete
  void *ptr = type_info->harness.read_data_fn(ctx);
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  if (ptr) {
    // We just wanted to skip, but harness allocates memory - need to clean up
    // This is not ideal but works for now. A better approach would be to
    // have a dedicated skip_data function in harness.
    // For now, we use operator delete which works for POD types.
    // TODO: Consider adding a harness.skip_data_fn or harness.delete_fn
    ::operator delete(ptr);
  }
}

void skip_unknown(ReadContext &ctx) {
  // UNKNOWN type means the actual type info is written inline.
  // We need to read the type info and then skip based on the actual type.
  // This is used for polymorphic fields like List<Animal>.
  const TypeInfo *type_info = ctx.read_any_type_info(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  if (!type_info) {
    ctx.set_error(Error::type_error("TypeInfo not found for UNKNOWN skip"));
    return;
  }

  // Check the actual type and skip accordingly
  TypeId actual_tid = static_cast<TypeId>(type_info->type_id);

  switch (actual_tid) {
  case TypeId::STRUCT:
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT: {
    // For struct types, we already have the type_info with field_infos
    if (!type_info->type_meta) {
      ctx.set_error(
          Error::type_error("TypeMeta not found for UNKNOWN struct skip"));
      return;
    }
    const auto &field_infos = type_info->type_meta->get_field_infos();
    for (const auto &fi : field_infos) {
      // Use precomputed ref_mode from field metadata
      skip_field_value(ctx, fi.field_type, fi.field_type.ref_mode);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return;
      }
    }
    return;
  }
  default: {
    // For non-struct types (primitives, arrays, maps, etc.),
    // recursively call skip_field_value with the actual type
    FieldType actual_field_type;
    actual_field_type.type_id = type_info->type_id;
    actual_field_type.nullable = false;
    skip_field_value(ctx, actual_field_type, RefMode::None);
    return;
  }
  }
}

void skip_union(ReadContext &ctx) {
  // Read the variant index
  (void)ctx.read_var_uint32(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  // Read ref flag for the union value (Any-style)
  int8_t ref_flag = ctx.read_int8(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  if (ref_flag == NULL_FLAG) {
    return;
  }
  if (ref_flag == REF_FLAG) {
    (void)ctx.read_var_uint32(ctx.error());
    return;
  }
  if (ref_flag != NOT_NULL_VALUE_FLAG && ref_flag != REF_VALUE_FLAG) {
    ctx.set_error(
        Error::invalid_data("Unknown reference flag: " +
                            std::to_string(static_cast<int>(ref_flag))));
    return;
  }

  // Read and skip the alternative's type info
  const TypeInfo *type_info = ctx.read_any_type_info(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  if (!type_info) {
    ctx.set_error(
        Error::type_error("TypeInfo not found for UNION alternative skip"));
    return;
  }

  // skip the alternative's value
  FieldType alt_field_type;
  alt_field_type.type_id = type_info->type_id;
  alt_field_type.nullable = false;
  skip_field_value(ctx, alt_field_type, RefMode::None);
}

void skip_field_value(ReadContext &ctx, const FieldType &field_type,
                      RefMode ref_mode) {
  // Read ref flag if needed
  if (ref_mode != RefMode::None) {
    int8_t ref_flag = ctx.read_int8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (ref_flag == NULL_FLAG || ref_flag == REF_FLAG) {
      return; // Null or reference, nothing more to skip
    }
  }

  // skip based on the logical TypeId (already in 0..255)
  TypeId tid = static_cast<TypeId>(field_type.type_id);

  switch (tid) {
  case TypeId::BOOL:
  case TypeId::INT8:
  case TypeId::FLOAT8:
    ctx.buffer().increase_reader_index(1, ctx.error());
    return;

  case TypeId::INT16:
  case TypeId::BFLOAT16:
  case TypeId::FLOAT16:
    ctx.buffer().increase_reader_index(2, ctx.error());
    return;

  case TypeId::INT32: {
    // INT32 values are encoded as varint32 in the C++
    // serializer, so we must consume a varint32 here
    // instead of assuming fixed-width encoding.
    ctx.read_varint32(ctx.error());
    return;
  }

  case TypeId::FLOAT32:
    ctx.buffer().increase_reader_index(4, ctx.error());
    return;

  case TypeId::INT64: {
    // INT64 values are encoded as varint64 in the C++
    // serializer, so we must consume a varint64 here
    // instead of assuming fixed-width encoding.
    ctx.read_varint64(ctx.error());
    return;
  }

  case TypeId::FLOAT64:
    ctx.buffer().increase_reader_index(8, ctx.error());
    return;

  case TypeId::VARINT32:
  case TypeId::VARINT64:
    skip_varint(ctx);
    return;

  case TypeId::STRING:
    skip_string(ctx);
    return;

  case TypeId::LIST:
    skip_list(ctx, field_type);
    return;

  case TypeId::SET:
    skip_set(ctx, field_type);
    return;

  case TypeId::MAP:
    skip_map(ctx, field_type);
    return;

  case TypeId::DURATION: {
    // Duration is stored as fixed 8-byte nanosecond count.
    constexpr uint32_t k_bytes = static_cast<uint32_t>(sizeof(int64_t));
    ctx.buffer().increase_reader_index(k_bytes, ctx.error());
    return;
  }
  case TypeId::TIMESTAMP: {
    // Timestamp is stored as int64 seconds + uint32 nanoseconds.
    constexpr uint32_t k_bytes =
        static_cast<uint32_t>(sizeof(int64_t) + sizeof(uint32_t));
    ctx.buffer().increase_reader_index(k_bytes, ctx.error());
    return;
  }

  case TypeId::DATE: {
    // Date is stored as fixed 4-byte day count.
    constexpr uint32_t k_bytes = static_cast<uint32_t>(sizeof(int32_t));
    ctx.buffer().increase_reader_index(k_bytes, ctx.error());
    return;
  }

  case TypeId::STRUCT:
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
    skip_struct(ctx, field_type);
    return;

  // Primitive arrays
  case TypeId::BOOL_ARRAY:
  case TypeId::INT8_ARRAY:
  case TypeId::INT16_ARRAY:
  case TypeId::INT32_ARRAY:
  case TypeId::INT64_ARRAY:
  case TypeId::FLOAT8_ARRAY:
  case TypeId::FLOAT16_ARRAY:
  case TypeId::BFLOAT16_ARRAY:
  case TypeId::FLOAT32_ARRAY:
  case TypeId::FLOAT64_ARRAY: {
    if (tid == TypeId::FLOAT8_ARRAY) {
      ctx.buffer().increase_reader_index(1, ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return;
      }
    }
    // Read array length
    uint32_t len = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }

    // Calculate element size
    size_t elem_size = 1;
    switch (tid) {
    case TypeId::INT16_ARRAY:
    case TypeId::FLOAT16_ARRAY:
    case TypeId::BFLOAT16_ARRAY:
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

    ctx.buffer().increase_reader_index(len * elem_size, ctx.error());
    return;
  }

  case TypeId::BINARY: {
    // Read binary length
    uint32_t len = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    ctx.buffer().increase_reader_index(len, ctx.error());
    return;
  }

  case TypeId::ENUM:
  case TypeId::NAMED_ENUM: {
    // Enums are serialized as ordinal varuint32 values.
    ctx.read_var_uint32(ctx.error());
    return;
  }

  case TypeId::EXT:
  case TypeId::NAMED_EXT:
    skip_ext(ctx, field_type);
    return;

  case TypeId::UNKNOWN:
    skip_unknown(ctx);
    return;

  case TypeId::UNION:
    skip_union(ctx);
    return;
  case TypeId::TYPED_UNION:
  case TypeId::NAMED_UNION:
    skip_union(ctx);
    return;

  case TypeId::NONE:
    // NONE (not-applicable/monostate) has no data to skip
    return;

  default:
    ctx.set_error(
        Error::type_error("Unknown field type to skip: " +
                          std::to_string(static_cast<uint32_t>(tid))));
    return;
  }
}

} // namespace serialization
} // namespace fory
