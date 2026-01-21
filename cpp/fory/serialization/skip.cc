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
// Per xlang protocol and Java's ObjectSerializer.writeOtherFieldValue:
// - In xlang mode with refTracking=false (default), fields only write
//   ref/null flag if they are nullable
// - Primitives never have ref flags (handled separately)
} // namespace

void skip_varint(ReadContext &ctx) {
  // Skip varint by reading it
  ctx.read_varuint64(ctx.error());
}

void skip_string(ReadContext &ctx) {
  // Read string length + encoding
  uint64_t size_encoding = ctx.read_varuint64(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  uint64_t size = size_encoding >> 2;

  // Skip string data
  ctx.buffer().IncreaseReaderIndex(size);
}

void skip_list(ReadContext &ctx, const FieldType &field_type) {
  // Read list length
  uint64_t length = ctx.read_varuint64(ctx.error());
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

    // Skip element value
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
  uint64_t total_length = ctx.read_varuint64(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }

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

    // Skip key-value pairs in this chunk
    for (uint8_t i = 0; i < chunk_size; ++i) {
      // Skip key with ref flag if needed
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

      // Skip value with ref flag if needed
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
  uint32_t remote_type_id = ctx.read_varuint32(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }

  uint32_t type_id_low = remote_type_id & 0xff;
  TypeId remote_tid = static_cast<TypeId>(type_id_low);

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
        ctx.type_resolver().get_type_info_by_id(remote_type_id);
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
  uint32_t remote_type_id = ctx.read_varuint32(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }

  uint32_t low = remote_type_id & 0xffu;
  TypeId remote_tid = static_cast<TypeId>(low);

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
    auto type_info_res =
        ctx.type_resolver().get_type_info_by_id(remote_type_id);
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
  const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  if (!type_info) {
    ctx.set_error(Error::type_error("TypeInfo not found for UNKNOWN skip"));
    return;
  }

  // Check the actual type and skip accordingly
  uint32_t low = type_info->type_id & 0xffu;
  TypeId actual_tid = static_cast<TypeId>(low);

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
  (void)ctx.read_varuint32(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  // Read and skip the alternative's type info
  const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return;
  }
  if (!type_info) {
    ctx.set_error(
        Error::type_error("TypeInfo not found for UNION alternative skip"));
    return;
  }

  // Skip the alternative's value
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
    return;

  case TypeId::INT16:
  case TypeId::FLOAT16:
    ctx.buffer().IncreaseReaderIndex(2);
    return;

  case TypeId::INT32: {
    // INT32 values are encoded as varint32 in the C++
    // serializer, so we must consume a varint32 here
    // instead of assuming fixed-width encoding.
    ctx.read_varint32(ctx.error());
    return;
  }

  case TypeId::FLOAT32:
    ctx.buffer().IncreaseReaderIndex(4);
    return;

  case TypeId::INT64: {
    // INT64 values are encoded as varint64 in the C++
    // serializer, so we must consume a varint64 here
    // instead of assuming fixed-width encoding.
    ctx.read_varint64(ctx.error());
    return;
  }

  case TypeId::FLOAT64:
    ctx.buffer().IncreaseReaderIndex(8);
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

  case TypeId::DURATION:
  case TypeId::TIMESTAMP: {
    // Duration/Timestamp are stored as fixed 8-byte
    // nanosecond counts.
    constexpr uint32_t kBytes = static_cast<uint32_t>(sizeof(int64_t));
    if (ctx.buffer().reader_index() + kBytes > ctx.buffer().size()) {
      ctx.set_error(Error::buffer_out_of_bound(ctx.buffer().reader_index(),
                                               kBytes, ctx.buffer().size()));
      return;
    }
    ctx.buffer().IncreaseReaderIndex(kBytes);
    return;
  }

  case TypeId::LOCAL_DATE: {
    // LocalDate is stored as fixed 4-byte day count.
    constexpr uint32_t kBytes = static_cast<uint32_t>(sizeof(int32_t));
    if (ctx.buffer().reader_index() + kBytes > ctx.buffer().size()) {
      ctx.set_error(Error::buffer_out_of_bound(ctx.buffer().reader_index(),
                                               kBytes, ctx.buffer().size()));
      return;
    }
    ctx.buffer().IncreaseReaderIndex(kBytes);
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
  case TypeId::FLOAT16_ARRAY:
  case TypeId::FLOAT32_ARRAY:
  case TypeId::FLOAT64_ARRAY: {
    // Read array length
    uint32_t len = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }

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
    return;
  }

  case TypeId::BINARY: {
    // Read binary length
    uint32_t len = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    ctx.buffer().IncreaseReaderIndex(len);
    return;
  }

  case TypeId::ENUM:
  case TypeId::NAMED_ENUM: {
    // Enums are serialized as ordinal varuint32 values.
    ctx.read_varuint32(ctx.error());
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
