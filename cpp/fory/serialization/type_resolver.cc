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

#include "fory/serialization/type_resolver.h"
#include "fory/meta/meta_string.h"
#include "fory/serialization/context.h"
#include "fory/thirdparty/MurmurHash3.h"
#include "fory/type/type.h"
#include <algorithm>
#include <cstring>
#include <map>
#include <unordered_map>

namespace fory {
namespace serialization {

using namespace meta;

// Constants from xlang spec
constexpr size_t SMALL_NUM_FIELDS_THRESHOLD = 0b11111;
constexpr uint8_t REGISTER_BY_NAME_FLAG = 0b100000;
constexpr size_t FIELD_NAME_SIZE_THRESHOLD = 0b1111;
constexpr size_t BIG_NAME_THRESHOLD = 0b111111;
constexpr int64_t META_SIZE_MASK = 0xfff;
// constexpr int64_t COMPRESS_META_FLAG = 0b1 << 13;
constexpr int64_t HAS_FIELDS_META_FLAG = 0b1 << 12;
constexpr int8_t NUM_HASH_BITS = 50;

// ============================================================================
// FieldType Implementation
// ============================================================================

Result<void, Error> FieldType::write_to(Buffer &buffer, bool write_flag,
                                        bool nullable_val) const {
  uint32_t header = type_id;
  if (write_flag) {
    header <<= 2;
    if (nullable_val) {
      header |= 2;
    }
  }
  buffer.WriteVarUint32(header);

  // Write generics for list/set/map
  if (type_id == static_cast<uint32_t>(TypeId::LIST) ||
      type_id == static_cast<uint32_t>(TypeId::SET)) {
    if (generics.empty()) {
      return Unexpected(Error::invalid("List/Set must have element type"));
    }
    FORY_RETURN_IF_ERROR(
        generics[0].write_to(buffer, true, generics[0].nullable));
  } else if (type_id == static_cast<uint32_t>(TypeId::MAP)) {
    if (generics.size() < 2) {
      return Unexpected(Error::invalid("Map must have key and value types"));
    }
    FORY_RETURN_IF_ERROR(
        generics[0].write_to(buffer, true, generics[0].nullable));
    FORY_RETURN_IF_ERROR(
        generics[1].write_to(buffer, true, generics[1].nullable));
  }

  return {};
}

Result<FieldType, Error> FieldType::read_from(Buffer &buffer, bool read_flag,
                                              bool nullable_val,
                                              bool ref_tracking_val) {
  Error error;
  uint32_t header = buffer.ReadVarUint32(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  uint32_t tid;
  bool null;
  bool ref_track;
  if (read_flag) {
    // Header layout: type_id:N bits | nullable:1 bit | ref_tracking:1 bit
    tid = header >> 2;
    null = (header & 0b10) != 0;
    ref_track = (header & 0b01) != 0;
  } else {
    tid = header;
    null = nullable_val;
    ref_track = ref_tracking_val;
  }

  FieldType ft(tid, null, ref_track);

  // Read generics for list/set/map
  if (tid == static_cast<uint32_t>(TypeId::LIST) ||
      tid == static_cast<uint32_t>(TypeId::SET)) {
    FORY_TRY(generic, FieldType::read_from(buffer, true, false));
    ft.generics.push_back(std::move(generic));
  } else if (tid == static_cast<uint32_t>(TypeId::MAP)) {
    FORY_TRY(key, FieldType::read_from(buffer, true, false));
    FORY_TRY(val, FieldType::read_from(buffer, true, false));
    ft.generics.push_back(std::move(key));
    ft.generics.push_back(std::move(val));
  }

  return ft;
}

// ============================================================================
// FieldInfo Implementation
// ============================================================================

Result<std::vector<uint8_t>, Error> FieldInfo::to_bytes() const {
  Buffer buffer;

  // Write field header:
  // header: | field_name_encoding:2bits | size:4bits | nullability:1bit |
  // ref_tracking:1bit |
  const bool use_tag_id = field_id >= 0;
  uint8_t encoding_idx = use_tag_id ? 3 : 0; // TAG_ID or UTF8
  size_t name_size =
      use_tag_id ? static_cast<size_t>(field_id) + 1 : field_name.size();
  uint8_t header =
      (std::min(FIELD_NAME_SIZE_THRESHOLD, name_size - 1) << 2) & 0x3C;

  if (field_type.ref_tracking) {
    header |= 1; // bit 0 for ref tracking
  }
  if (field_type.nullable) {
    header |= 2; // bit 1 for nullable
  }
  header |= (encoding_idx << 6);

  buffer.WriteUint8(header);

  if (name_size - 1 >= FIELD_NAME_SIZE_THRESHOLD) {
    buffer.WriteVarUint32(name_size - 1 - FIELD_NAME_SIZE_THRESHOLD);
  }

  // Write field type
  FORY_RETURN_NOT_OK(field_type.write_to(buffer, false, field_type.nullable));

  // Write field name only when tag ID is not used.
  if (!use_tag_id) {
    buffer.WriteBytes(reinterpret_cast<const uint8_t *>(field_name.data()),
                      field_name.size());
  }

  // CRITICAL FIX: Use writer_index() not size() to get actual bytes written!
  return std::vector<uint8_t>(buffer.data(),
                              buffer.data() + buffer.writer_index());
}

Result<FieldInfo, Error> FieldInfo::from_bytes(Buffer &buffer) {
  // Read field header
  Error error;
  uint8_t header = buffer.ReadUint8(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  // Decode header layout:
  // bit  0: ref tracking flag
  // bit  1: nullability flag
  // bits 2-5: size (0-14, 15 means extended)
  // bits 6-7: field name encoding index
  uint8_t encoding_idx = static_cast<uint8_t>(header >> 6);
  bool use_tag_id = encoding_idx == 3;
  bool ref_tracking = (header & 0b01u) != 0;
  bool nullable = (header & 0b10u) != 0;
  size_t name_size = ((header >> 2) & FIELD_NAME_SIZE_THRESHOLD);
  if (name_size == FIELD_NAME_SIZE_THRESHOLD) {
    uint32_t extra = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    name_size += extra;
  }
  name_size += 1;

  // Read field type with nullable and ref_tracking from header
  FORY_TRY(field_type,
           FieldType::read_from(buffer, false, nullable, ref_tracking));

  if (use_tag_id) {
    FieldInfo info("", std::move(field_type));
    info.field_id = static_cast<int16_t>(name_size - 1);
    return info;
  }

  // Read and decode field name. Java encodes field names using
  // MetaString with encodings:
  //   UTF8 / ALL_TO_LOWER_SPECIAL / LOWER_UPPER_DIGIT_SPECIAL
  // and writes the encoding index into the top 2 bits.
  // We mirror that here using MetaStringDecoder with '$' and '_' as
  // special characters (same as Encoders.FIELD_NAME_DECODER).

  std::vector<uint8_t> name_bytes(name_size);
  buffer.ReadBytes(name_bytes.data(), static_cast<uint32_t>(name_size), error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  static const MetaStringDecoder kFieldNameDecoder('$', '_');

  FORY_TRY(encoding, ToMetaEncoding(encoding_idx));
  FORY_TRY(decoded_name, kFieldNameDecoder.decode(name_bytes.data(),
                                                  name_bytes.size(), encoding));

  return FieldInfo(decoded_name, std::move(field_type));
}

// ============================================================================
// TypeMeta Implementation
// ============================================================================

namespace {

// Meta string encodings for namespace and type name, aligned with
// rust/fory-core/src/meta/type_meta.rs and Java Encoders.
static const MetaEncoding kNamespaceEncodings[] = {
    MetaEncoding::UTF8, MetaEncoding::ALL_TO_LOWER_SPECIAL,
    MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL};

static const MetaEncoding kTypeNameEncodings[] = {
    MetaEncoding::UTF8, MetaEncoding::ALL_TO_LOWER_SPECIAL,
    MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL,
    MetaEncoding::FIRST_TO_LOWER_SPECIAL};

inline Result<void, Error> write_meta_name(Buffer &buffer,
                                           const std::string &name) {
  const uint8_t encoding_idx = 0; // UTF8 in both encoding tables
  const size_t len = name.size();

  if (len >= BIG_NAME_THRESHOLD) {
    uint8_t header =
        static_cast<uint8_t>((BIG_NAME_THRESHOLD << 2) | encoding_idx);
    buffer.WriteUint8(header);
    buffer.WriteVarUint32(static_cast<uint32_t>(len - BIG_NAME_THRESHOLD));
  } else {
    uint8_t header = static_cast<uint8_t>((len << 2) | encoding_idx);
    buffer.WriteUint8(header);
  }

  if (!name.empty()) {
    buffer.WriteBytes(reinterpret_cast<const uint8_t *>(name.data()),
                      static_cast<uint32_t>(name.size()));
  }
  return Result<void, Error>();
}

inline Result<std::string, Error>
read_meta_name(Buffer &buffer, const MetaStringDecoder &decoder,
               const MetaEncoding *encodings, size_t enc_count) {
  Error error;
  uint8_t header = buffer.ReadUint8(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }
  uint8_t encoding_idx = header & 0x3u;
  uint8_t length_prefix = header >> 2;

  if (encoding_idx >= enc_count) {
    return Unexpected(
        Error::encoding_error("Invalid meta string encoding index: " +
                              std::to_string(static_cast<int>(encoding_idx))));
  }

  size_t length = length_prefix;
  if (length >= BIG_NAME_THRESHOLD) {
    uint32_t extra = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    length = BIG_NAME_THRESHOLD + static_cast<size_t>(extra);
  }

  std::vector<uint8_t> bytes(length);
  if (length > 0) {
    buffer.ReadBytes(bytes.data(), static_cast<uint32_t>(length), error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
  }

  MetaEncoding encoding = encodings[encoding_idx];
  FORY_TRY(result, decoder.decode(bytes.data(), bytes.size(), encoding));
  return result;
}

} // namespace

TypeMeta TypeMeta::from_fields(uint32_t tid, const std::string &ns,
                               const std::string &name, bool by_name,
                               std::vector<FieldInfo> fields) {
  for (const auto &field : fields) {
    FORY_CHECK(!field.field_name.empty())
        << "Type '" << name << "' contains a field with empty name";
  }
  TypeMeta meta;
  meta.type_id = tid;
  meta.namespace_str = ns;
  meta.type_name = name;
  meta.register_by_name = by_name;
  meta.field_infos = std::move(fields);
  meta.hash = 0; // Will be computed during serialization
  return meta;
}

Result<std::vector<uint8_t>, Error> TypeMeta::to_bytes() const {
  Buffer layer_buffer;

  // Write meta header
  size_t num_fields = field_infos.size();
  uint8_t meta_header =
      static_cast<uint8_t>(std::min(num_fields, SMALL_NUM_FIELDS_THRESHOLD));
  if (register_by_name) {
    meta_header |= REGISTER_BY_NAME_FLAG;
  }
  layer_buffer.WriteUint8(meta_header);

  if (num_fields >= SMALL_NUM_FIELDS_THRESHOLD) {
    layer_buffer.WriteVarUint32(num_fields - SMALL_NUM_FIELDS_THRESHOLD);
  }

  // Write namespace and type name (if registered by name) using the
  // same compact meta string format as Rust/Java.
  if (register_by_name) {
    FORY_RETURN_NOT_OK(write_meta_name(layer_buffer, namespace_str));
    FORY_RETURN_NOT_OK(write_meta_name(layer_buffer, type_name));
  } else {
    layer_buffer.WriteVarUint32(type_id);
  }

  // Write field infos
  for (const auto &field : field_infos) {
    FORY_TRY(field_bytes, field.to_bytes());
    layer_buffer.WriteBytes(field_bytes.data(), field_bytes.size());
  }

  // Now write global binary header
  Buffer result_buffer;
  const uint32_t layer_size = layer_buffer.writer_index();
  int64_t meta_size = layer_size;
  int64_t header = std::min(META_SIZE_MASK, meta_size);

  bool write_meta_fields_flag = !field_infos.empty();
  if (write_meta_fields_flag) {
    header |= HAS_FIELDS_META_FLAG;
  }

  // Compute hash
  std::vector<uint8_t> layer_data(layer_buffer.data(),
                                  layer_buffer.data() + layer_size);
  int64_t meta_hash = compute_hash(layer_data);
  header |= (meta_hash << (64 - NUM_HASH_BITS));

  result_buffer.WriteBytes(reinterpret_cast<const uint8_t *>(&header),
                           sizeof(header));
  if (meta_size >= META_SIZE_MASK) {
    result_buffer.WriteVarUint32(meta_size - META_SIZE_MASK);
  }
  result_buffer.WriteBytes(layer_data.data(), layer_data.size());
  // Use actual bytes written to construct return vector
  return std::vector<uint8_t>(result_buffer.data(),
                              result_buffer.data() +
                                  result_buffer.writer_index());
}

Result<std::unique_ptr<TypeMeta>, Error>
TypeMeta::from_bytes(Buffer &buffer, const TypeMeta *local_type_info) {
  size_t start_pos = buffer.reader_index();

  // Read global binary header
  Error error;
  int64_t header;
  buffer.ReadBytes(&header, sizeof(header), error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  size_t header_size = sizeof(header);
  int64_t meta_size = header & META_SIZE_MASK;
  if (meta_size == META_SIZE_MASK) {
    uint32_t before = buffer.reader_index();
    uint32_t extra = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    meta_size += extra;
    uint32_t after = buffer.reader_index();
    header_size += (after - before);
  }
  int64_t meta_hash = header >> (64 - NUM_HASH_BITS);
  // Read meta header
  uint8_t meta_header = buffer.ReadUint8(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  bool register_by_name = (meta_header & REGISTER_BY_NAME_FLAG) != 0;
  size_t num_fields = meta_header & SMALL_NUM_FIELDS_THRESHOLD;
  if (num_fields == SMALL_NUM_FIELDS_THRESHOLD) {
    uint32_t extra = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    num_fields += extra;
  }

  // Read type ID or namespace/type name
  uint32_t type_id = 0;
  std::string namespace_str;
  std::string type_name;

  if (register_by_name) {
    static const MetaStringDecoder kNamespaceDecoder('.', '_');
    static const MetaStringDecoder kTypeNameDecoder('$', '_');

    FORY_TRY(ns, read_meta_name(buffer, kNamespaceDecoder, kNamespaceEncodings,
                                sizeof(kNamespaceEncodings) /
                                    sizeof(kNamespaceEncodings[0])));
    namespace_str = std::move(ns);

    FORY_TRY(tn, read_meta_name(buffer, kTypeNameDecoder, kTypeNameEncodings,
                                sizeof(kTypeNameEncodings) /
                                    sizeof(kTypeNameEncodings[0])));
    type_name = std::move(tn);
  } else {
    uint32_t tid = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    type_id = tid;
  }

  // Read field infos
  std::vector<FieldInfo> field_infos;
  field_infos.reserve(num_fields);
  for (size_t i = 0; i < num_fields; ++i) {
    FORY_TRY(field, FieldInfo::from_bytes(buffer));
    field_infos.push_back(std::move(field));
  }

  // NOTE: Do NOT sort remote fields! They are already in the sender's sorted
  // order, which matches the data order. Re-sorting would cause misalignment
  // with the serialized data.

  // Assign field IDs by comparing with local type
  if (local_type_info != nullptr) {
    assign_field_ids(local_type_info, field_infos);
  }

  // CRITICAL FIX: Ensure we consume exactly meta_size bytes
  size_t current_pos = buffer.reader_index();
  size_t expected_end_pos = start_pos + header_size + meta_size;
  if (current_pos < expected_end_pos) {
    size_t remaining = expected_end_pos - current_pos;
    buffer.IncreaseReaderIndex(remaining);
  }

  auto meta = std::make_unique<TypeMeta>();
  meta->hash = meta_hash;
  meta->type_id = type_id;
  meta->namespace_str = std::move(namespace_str);
  meta->type_name = std::move(type_name);
  meta->register_by_name = register_by_name;
  meta->field_infos = std::move(field_infos);

  return meta;
}

Result<std::unique_ptr<TypeMeta>, Error>
TypeMeta::from_bytes_with_header(Buffer &buffer, int64_t header) {
  Error error;
  int64_t meta_size = header & META_SIZE_MASK;
  size_t header_size = 0;
  if (meta_size == META_SIZE_MASK) {
    uint32_t before = buffer.reader_index();
    uint32_t extra = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    meta_size += extra;
    uint32_t after = buffer.reader_index();
    header_size = (after - before);
  }
  int64_t meta_hash = header >> (64 - NUM_HASH_BITS);

  size_t start_pos = buffer.reader_index();

  // Read meta header
  uint8_t meta_header = buffer.ReadUint8(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  bool register_by_name = (meta_header & REGISTER_BY_NAME_FLAG) != 0;
  size_t num_fields = meta_header & SMALL_NUM_FIELDS_THRESHOLD;
  if (num_fields == SMALL_NUM_FIELDS_THRESHOLD) {
    uint32_t extra = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    num_fields += extra;
  }

  // Read type ID or namespace/type name
  uint32_t type_id = 0;
  std::string namespace_str;
  std::string type_name;

  if (register_by_name) {
    static const MetaStringDecoder kNamespaceDecoder('.', '_');
    static const MetaStringDecoder kTypeNameDecoder('$', '_');

    FORY_TRY(ns, read_meta_name(buffer, kNamespaceDecoder, kNamespaceEncodings,
                                sizeof(kNamespaceEncodings) /
                                    sizeof(kNamespaceEncodings[0])));
    namespace_str = std::move(ns);

    FORY_TRY(tn, read_meta_name(buffer, kTypeNameDecoder, kTypeNameEncodings,
                                sizeof(kTypeNameEncodings) /
                                    sizeof(kTypeNameEncodings[0])));
    type_name = std::move(tn);
  } else {
    uint32_t tid = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    type_id = tid;
  }

  // Read field infos
  std::vector<FieldInfo> field_infos;
  field_infos.reserve(num_fields);
  for (size_t i = 0; i < num_fields; ++i) {
    FORY_TRY(field, FieldInfo::from_bytes(buffer));
    field_infos.push_back(std::move(field));
  }

  // NOTE: Do NOT sort remote fields! They are already in the sender's sorted
  // order, which matches the data order.

  // CRITICAL FIX: Ensure we consume exactly meta_size bytes
  size_t current_pos = buffer.reader_index();
  size_t expected_end_pos = start_pos + meta_size - header_size;
  if (current_pos < expected_end_pos) {
    size_t remaining = expected_end_pos - current_pos;
    buffer.IncreaseReaderIndex(remaining);
  }

  auto meta = std::make_unique<TypeMeta>();
  meta->hash = meta_hash;
  meta->type_id = type_id;
  meta->namespace_str = std::move(namespace_str);
  meta->type_name = std::move(type_name);
  meta->register_by_name = register_by_name;
  meta->field_infos = std::move(field_infos);

  return meta;
}

Result<void, Error> TypeMeta::skip_bytes(Buffer &buffer, int64_t header) {
  Error error;
  int64_t meta_size = header & META_SIZE_MASK;
  if (meta_size == META_SIZE_MASK) {
    uint32_t extra = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    meta_size += extra;
  }
  buffer.Skip(static_cast<uint32_t>(meta_size), error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }
  return Result<void, Error>();
}

Result<void, Error>
TypeMeta::check_struct_version(int32_t read_version, int32_t local_version,
                               const std::string &type_name) {
  if (read_version != local_version) {
    return Unexpected(Error::type_error(
        "Read class " + type_name + " version " + std::to_string(read_version) +
        " is not consistent with " + std::to_string(local_version) +
        ", please align struct field types and names, or use compatible mode "
        "of Fory by Fory#compatible(true)"));
  }
  return {};
}

// ============================================================================
// Field Sorting (following xlang spec and Rust implementation)
// ============================================================================

namespace {

bool is_primitive_type(uint32_t type_id) {
  return type_id >= static_cast<uint32_t>(TypeId::BOOL) &&
         type_id <= static_cast<uint32_t>(TypeId::FLOAT64);
}

int32_t get_primitive_type_size(uint32_t type_id) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::BOOL:
  case TypeId::INT8:
  case TypeId::UINT8:
    return 1;
  case TypeId::INT16:
  case TypeId::UINT16:
  case TypeId::FLOAT16:
    return 2;
  case TypeId::INT32:
  case TypeId::VARINT32:
  case TypeId::UINT32:
  case TypeId::VAR_UINT32:
  case TypeId::FLOAT32:
    return 4;
  case TypeId::INT64:
  case TypeId::VARINT64:
  case TypeId::TAGGED_INT64:
  case TypeId::UINT64:
  case TypeId::VAR_UINT64:
  case TypeId::TAGGED_UINT64:
  case TypeId::FLOAT64:
    return 8;
  default:
    return 0;
  }
}

/// Check if a type ID represents a compressed (varint/tagged) type.
/// This must match Java's Types.isCompressedType() exactly for consistent
/// field ordering. Java only considers VARINT32, VAR_UINT32, VARINT64,
/// VAR_UINT64, TAGGED_INT64, and TAGGED_UINT64 as compressed.
/// Note: INT32, INT64, UINT32, UINT64 are NOT compressed - they are fixed-size.
bool is_compress(uint32_t type_id) {
  return type_id == static_cast<uint32_t>(TypeId::VARINT32) ||
         type_id == static_cast<uint32_t>(TypeId::VARINT64) ||
         type_id == static_cast<uint32_t>(TypeId::TAGGED_INT64) ||
         type_id == static_cast<uint32_t>(TypeId::VAR_UINT32) ||
         type_id == static_cast<uint32_t>(TypeId::VAR_UINT64) ||
         type_id == static_cast<uint32_t>(TypeId::TAGGED_UINT64);
}

std::string field_sort_key(const FieldInfo &field) {
  if (field.field_id >= 0) {
    return std::to_string(field.field_id);
  }
  return field.field_name;
}

// Numeric field sorter (for primitive fields)
bool numeric_sorter(const FieldInfo &a, const FieldInfo &b) {
  uint32_t a_id = a.field_type.type_id;
  uint32_t b_id = b.field_type.type_id;
  bool a_nullable = a.field_type.nullable;
  bool b_nullable = b.field_type.nullable;
  bool compress_a = is_compress(a_id);
  bool compress_b = is_compress(b_id);
  int32_t size_a = get_primitive_type_size(a_id);
  int32_t size_b = get_primitive_type_size(b_id);

  // Sort by: nullable (false first), compress (false first), size (larger
  // first), type_id (descending to match Java), field_name
  if (a_nullable != b_nullable)
    return !a_nullable; // non-nullable first
  if (compress_a != compress_b)
    return !compress_a; // fixed-size first
  if (size_a != size_b)
    return size_a > size_b; // larger size first
  if (a_id != b_id)
    return a_id > b_id; // type_id descending to match Java
  std::string a_key = field_sort_key(a);
  std::string b_key = field_sort_key(b);
  if (a_key != b_key) {
    return a_key < b_key;
  }
  return a.field_name < b.field_name;
}

// Type then name sorter (for internal type fields like STRING)
// Sorts by: type_id (ascending), field_name
bool type_then_name_sorter(const FieldInfo &a, const FieldInfo &b) {
  if (a.field_type.type_id != b.field_type.type_id) {
    return a.field_type.type_id < b.field_type.type_id;
  }
  std::string a_key = field_sort_key(a);
  std::string b_key = field_sort_key(b);
  if (a_key != b_key) {
    return a_key < b_key;
  }
  return a.field_name < b.field_name;
}

// Name sorter (for list/set/map/other fields)
// Sorts by: field_name only
bool name_sorter(const FieldInfo &a, const FieldInfo &b) {
  std::string a_key = field_sort_key(a);
  std::string b_key = field_sort_key(b);
  if (a_key != b_key) {
    return a_key < b_key;
  }
  return a.field_name < b.field_name;
}

// Check if a type ID is a "final" type for field group 2 in field ordering.
// Final types are STRING, DURATION, TIMESTAMP, DATE, DECIMAL, BINARY,
// ARRAY, and primitive arrays.
// These are types with fixed serializers that don't need type info written.
// Excludes: ENUM (13-14), STRUCT (15-18), EXT (19-20), LIST (21), SET (22), MAP
// (23) Note: LIST/SET/MAP are checked separately before this function is
// called.
bool is_final_type_for_grouping(uint32_t type_id) {
  return type_id == static_cast<uint32_t>(TypeId::STRING) ||
         (type_id >= static_cast<uint32_t>(TypeId::DURATION) &&
          type_id <= static_cast<uint32_t>(TypeId::BINARY)) ||
         type_id == static_cast<uint32_t>(TypeId::ARRAY) ||
         (type_id >= static_cast<uint32_t>(TypeId::BOOL_ARRAY) &&
          type_id <= static_cast<uint32_t>(TypeId::FLOAT64_ARRAY));
}

} // anonymous namespace

std::vector<FieldInfo>
TypeMeta::sort_field_infos(std::vector<FieldInfo> fields) {
  // Group fields according to xlang spec
  std::vector<FieldInfo> primitive_fields;
  std::vector<FieldInfo> nullable_primitive_fields;
  std::vector<FieldInfo> internal_type_fields;
  std::vector<FieldInfo> list_fields;
  std::vector<FieldInfo> set_fields;
  std::vector<FieldInfo> map_fields;
  std::vector<FieldInfo> other_fields;

  for (auto &field : fields) {
    uint32_t type_id = field.field_type.type_id;
    bool nullable = field.field_type.nullable;

    if (is_primitive_type(type_id)) {
      if (nullable) {
        nullable_primitive_fields.push_back(std::move(field));
      } else {
        primitive_fields.push_back(std::move(field));
      }
    } else if (type_id == static_cast<uint32_t>(TypeId::LIST)) {
      list_fields.push_back(std::move(field));
    } else if (type_id == static_cast<uint32_t>(TypeId::SET)) {
      set_fields.push_back(std::move(field));
    } else if (type_id == static_cast<uint32_t>(TypeId::MAP)) {
      map_fields.push_back(std::move(field));
    } else if (is_final_type_for_grouping(type_id)) {
      internal_type_fields.push_back(std::move(field));
    } else {
      other_fields.push_back(std::move(field));
    }
  }

  // Sort each group
  std::sort(primitive_fields.begin(), primitive_fields.end(), numeric_sorter);
  std::sort(nullable_primitive_fields.begin(), nullable_primitive_fields.end(),
            numeric_sorter);
  std::sort(internal_type_fields.begin(), internal_type_fields.end(),
            type_then_name_sorter);
  std::sort(list_fields.begin(), list_fields.end(), name_sorter);
  std::sort(set_fields.begin(), set_fields.end(), name_sorter);
  std::sort(map_fields.begin(), map_fields.end(), name_sorter);
  std::sort(other_fields.begin(), other_fields.end(), name_sorter);

  // Combine sorted groups
  std::vector<FieldInfo> sorted;
  sorted.reserve(fields.size());
  sorted.insert(sorted.end(), std::make_move_iterator(primitive_fields.begin()),
                std::make_move_iterator(primitive_fields.end()));
  sorted.insert(sorted.end(),
                std::make_move_iterator(nullable_primitive_fields.begin()),
                std::make_move_iterator(nullable_primitive_fields.end()));
  sorted.insert(sorted.end(),
                std::make_move_iterator(internal_type_fields.begin()),
                std::make_move_iterator(internal_type_fields.end()));
  sorted.insert(sorted.end(), std::make_move_iterator(list_fields.begin()),
                std::make_move_iterator(list_fields.end()));
  sorted.insert(sorted.end(), std::make_move_iterator(set_fields.begin()),
                std::make_move_iterator(set_fields.end()));
  sorted.insert(sorted.end(), std::make_move_iterator(map_fields.begin()),
                std::make_move_iterator(map_fields.end()));
  sorted.insert(sorted.end(), std::make_move_iterator(other_fields.begin()),
                std::make_move_iterator(other_fields.end()));

  return sorted;
}

// ============================================================================
// Field ID Assignment (KEY FUNCTION for schema evolution!)
// ============================================================================

void TypeMeta::assign_field_ids(const TypeMeta *local_type,
                                std::vector<FieldInfo> &remote_fields) {
  const auto &local_fields = local_type->field_infos;

  // Primary mapping: field name -> sorted index in local schema
  std::unordered_map<std::string, size_t> local_field_index_map;
  local_field_index_map.reserve(local_fields.size());
  for (size_t i = 0; i < local_fields.size(); ++i) {
    local_field_index_map.emplace(local_fields[i].field_name, i);
  }
  // Tag ID mapping when field IDs are explicitly configured.
  std::unordered_map<int16_t, size_t> local_field_id_map;
  local_field_id_map.reserve(local_fields.size());
  for (size_t i = 0; i < local_fields.size(); ++i) {
    if (local_fields[i].field_id >= 0) {
      local_field_id_map.emplace(local_fields[i].field_id, i);
    }
  }

  // Track which local fields have already been matched so that each
  // local field is bound to at most one remote field when we fall
  // back to type-based matching.
  std::vector<bool> used(local_fields.size(), false);

  // Normalize user-defined type IDs so that different encodings of the
  // same logical category (STRUCT vs COMPATIBLE_STRUCT, ENUM vs
  // NAMED_ENUM, EXT vs NAMED_EXT, and their ID-based variants) are
  // treated as equal for schema-evolution matching.
  auto normalize_type_id = [](uint32_t tid) {
    uint32_t low = tid & 0xffu;
    switch (static_cast<TypeId>(low)) {
    case TypeId::STRUCT:
    case TypeId::COMPATIBLE_STRUCT:
    case TypeId::NAMED_STRUCT:
    case TypeId::NAMED_COMPATIBLE_STRUCT:
    case TypeId::UNKNOWN:
      // UNKNOWN is used for polymorphic types (e.g., Java interfaces) and
      // should match STRUCT for cross-language schema evolution.
      return static_cast<uint32_t>(TypeId::STRUCT);
    case TypeId::ENUM:
    case TypeId::NAMED_ENUM:
      return static_cast<uint32_t>(TypeId::ENUM);
    case TypeId::EXT:
    case TypeId::NAMED_EXT:
      return static_cast<uint32_t>(TypeId::EXT);
    default:
      return tid;
    }
  };

  // Recursive logical type comparison that ignores language-specific
  // encoding details (such as embedded user IDs or named vs unnamed
  // variants) while still matching full generic structure.
  std::function<bool(const FieldType &, const FieldType &)> types_match;
  types_match = [&](const FieldType &a, const FieldType &b) -> bool {
    if (normalize_type_id(a.type_id) != normalize_type_id(b.type_id)) {
      return false;
    }
    if (a.generics.size() != b.generics.size()) {
      return false;
    }
    for (size_t i = 0; i < a.generics.size(); ++i) {
      if (!types_match(a.generics[i], b.generics[i])) {
        return false;
      }
    }
    return true;
  };

  // For each remote field, assign field ID (sorted index in local schema)
  for (auto &remote_field : remote_fields) {
    size_t local_index = static_cast<size_t>(-1);

    // 0) If remote field carries a tag ID, map directly by ID.
    if (remote_field.field_id >= 0) {
      auto id_it = local_field_id_map.find(remote_field.field_id);
      if (id_it != local_field_id_map.end()) {
        local_index = id_it->second;
      }
    }

    // 1) Try exact name + type match first (fast path for same-language
    //    schemas and most C++-only cases).
    if (local_index == static_cast<size_t>(-1)) {
      auto it = local_field_index_map.find(remote_field.field_name);
      if (it != local_field_index_map.end()) {
        size_t idx = it->second;
        const FieldInfo &local_field = local_fields[idx];
        if (types_match(remote_field.field_type, local_field.field_type)) {
          local_index = idx;
        }
      }
    }

    // 2) Fallback: match by type signature and position when field names
    //    are not available or differ across languages.
    if (local_index == static_cast<size_t>(-1)) {
      for (size_t i = 0; i < local_fields.size(); ++i) {
        if (used[i]) {
          continue;
        }
        if (types_match(remote_field.field_type, local_fields[i].field_type)) {
          local_index = i;
          break;
        }
      }
    }

    if (local_index == static_cast<size_t>(-1)) {
      // No suitable local field found -> mark as skipped.
      remote_field.field_id = -1;
    } else {
      remote_field.field_id = static_cast<int16_t>(local_index);
      used[local_index] = true;
    }
  }
}

int64_t TypeMeta::compute_hash(const std::vector<uint8_t> &meta_bytes) {
  // Compute hash using MurmurHash3_x64_128 to match Rust/Java
  // TypeMeta implementation. We take the high 64 bits and then
  // keep only the lower NUM_HASH_BITS bits.
  int64_t hash_out[2] = {0, 0};
  MurmurHash3_x64_128(meta_bytes.data(), static_cast<int>(meta_bytes.size()),
                      47, hash_out);

  // hash_out[0] is the low 64 bits, hash_out[1] the high 64 bits.
  uint64_t high = static_cast<uint64_t>(hash_out[1]);
  uint64_t mask = (NUM_HASH_BITS >= 64) ? ~uint64_t{0}
                                        : ((uint64_t{1} << NUM_HASH_BITS) - 1);
  return static_cast<int64_t>(high & mask);
}

namespace {

std::string ToSnakeCase(const std::string &name) {
  bool all_lower = true;
  for (char c : name) {
    unsigned char uc = static_cast<unsigned char>(c);
    if (!(static_cast<bool>(std::islower(uc)) ||
          static_cast<bool>(std::isdigit(uc)) || c == '_')) {
      all_lower = false;
      break;
    }
  }
  if (all_lower) {
    return name;
  }

  std::string result;
  result.reserve(name.size() * 2);
  std::optional<char> prev;

  for (size_t i = 0; i < name.size(); ++i) {
    char ch = name[i];
    if (ch == '_') {
      result.push_back('_');
      prev = ch;
      continue;
    }

    if (static_cast<bool>(std::isupper(static_cast<unsigned char>(ch)))) {
      bool need_underscore = false;
      if (prev.has_value()) {
        char prev_ch = *prev;
        bool prev_lower_or_digit = static_cast<bool>(
            std::islower(static_cast<unsigned char>(prev_ch)) ||
            std::isdigit(static_cast<unsigned char>(prev_ch)));
        bool prev_upper = static_cast<bool>(
            std::isupper(static_cast<unsigned char>(prev_ch)));

        bool next_is_lower = false;
        if (i + 1 < name.size()) {
          char next = name[i + 1];
          next_is_lower =
              static_cast<bool>(std::islower(static_cast<unsigned char>(next)));
        }

        if (prev_lower_or_digit || (prev_upper && next_is_lower)) {
          need_underscore = true;
        }
      }
      if (need_underscore && !result.empty() && result.back() != '_') {
        result.push_back('_');
      }
      result.push_back(
          static_cast<char>(std::tolower(static_cast<unsigned char>(ch))));
    } else {
      result.push_back(ch);
    }
    prev = ch;
  }

  return result;
}

} // anonymous namespace

std::string TypeMeta::compute_struct_fingerprint(
    const std::vector<FieldInfo> &field_infos) {
  // Computes the fingerprint string for a struct type used in schema
  // versioning.
  //
  // Fingerprint Format:
  //   Each field contributes: <field_id_or_name>,<type_id>,<ref>,<nullable>;
  //   Fields are sorted lexicographically by field identifier (tag ID string
  //   if present, otherwise snake_case field name).
  //
  // Field Components:
  //   - field_id_or_name: tag ID as string if configured, otherwise snake_case
  //   field name
  //   - type_id: Fory TypeId as decimal string (e.g., "4" for INT32)
  //   - ref: "1" if reference tracking enabled, "0" otherwise (always "0" in
  //   C++)
  //   - nullable: "1" if null flag is written, "0" otherwise
  //
  // Example fingerprints:
  //   - With tag IDs: "0,4,0,0;1,12,0,1;"
  //   - With field names: "age,4,0,0;name,12,0,1;"

  // Copy fields and sort lexicographically by field identifier for fingerprint
  std::vector<FieldInfo> sorted_fields = field_infos;
  std::sort(sorted_fields.begin(), sorted_fields.end(),
            [](const FieldInfo &a, const FieldInfo &b) {
              std::string a_id = a.field_id >= 0 ? std::to_string(a.field_id)
                                                 : ToSnakeCase(a.field_name);
              std::string b_id = b.field_id >= 0 ? std::to_string(b.field_id)
                                                 : ToSnakeCase(b.field_name);
              return a_id < b_id;
            });

  std::string fingerprint;
  // Reserve a rough estimate to avoid reallocations
  fingerprint.reserve(sorted_fields.size() * 24);

  for (const auto &fi : sorted_fields) {
    std::string field_id_or_name = fi.field_id >= 0
                                       ? std::to_string(fi.field_id)
                                       : ToSnakeCase(fi.field_name);
    fingerprint.append(field_id_or_name);
    fingerprint.push_back(',');

    // Java's ObjectSerializer.getTypeId returns Types.UNKNOWN (0) for:
    // - abstract classes, interfaces, and enum types
    // - user-defined struct types (in xlang mode)
    // This aligns the hash computation with Java's behavior.
    uint32_t effective_type_id = fi.field_type.type_id;
    if (effective_type_id == static_cast<uint32_t>(TypeId::ENUM) ||
        effective_type_id == static_cast<uint32_t>(TypeId::NAMED_ENUM) ||
        effective_type_id == static_cast<uint32_t>(TypeId::STRUCT) ||
        effective_type_id == static_cast<uint32_t>(TypeId::NAMED_STRUCT)) {
      effective_type_id = static_cast<uint32_t>(TypeId::UNKNOWN);
    }
    fingerprint.append(std::to_string(effective_type_id));
    fingerprint.push_back(',');
    // Use field-level ref tracking flag from FORY_FIELD_TAGS or fory::field<>
    fingerprint.push_back(fi.field_type.ref_tracking ? '1' : '0');
    fingerprint.push_back(',');
    fingerprint.append(fi.field_type.nullable ? "1;" : "0;");
  }

  return fingerprint;
}

int32_t TypeMeta::compute_struct_version(const TypeMeta &meta) {
  std::string fingerprint = compute_struct_fingerprint(meta.field_infos);

  int64_t hash_out[2] = {0, 0};
  MurmurHash3_x64_128(reinterpret_cast<const uint8_t *>(fingerprint.data()),
                      static_cast<int>(fingerprint.size()), 47, hash_out);

  // Use the low 64 bits and then keep low 32 bits as i32.
  uint64_t low = static_cast<uint64_t>(hash_out[0]);
  uint32_t version = static_cast<uint32_t>(low & 0xFFFF'FFFFu);
#if defined(FORY_DEBUG) || defined(ENABLE_FORY_DEBUG_OUTPUT)
  // DEBUG: Print fingerprint for debugging version mismatch
  std::cerr << "[xlang][debug] struct_version type_name=" << meta.type_name
            << ", fingerprint=\"" << fingerprint
            << "\" version=" << static_cast<int32_t>(version) << std::endl;
#endif
  return static_cast<int32_t>(version);
}

// ============================================================================
// TypeInfo::deep_clone Implementation
// ============================================================================

std::unique_ptr<TypeInfo> TypeInfo::deep_clone() const {
  auto cloned = std::make_unique<TypeInfo>();
  cloned->type_id = type_id;
  cloned->namespace_name = namespace_name;
  cloned->type_name = type_name;
  cloned->register_by_name = register_by_name;
  cloned->is_external = is_external;
  cloned->sorted_indices = sorted_indices;
  cloned->name_to_index = name_to_index;
  cloned->type_def = type_def;
  cloned->harness = harness;

  // Deep clone unique_ptr members
  if (type_meta) {
    cloned->type_meta = std::make_unique<TypeMeta>(*type_meta);
  }
  if (encoded_namespace) {
    cloned->encoded_namespace = std::make_unique<CachedMetaString>();
    cloned->encoded_namespace->original = encoded_namespace->original;
    cloned->encoded_namespace->bytes = encoded_namespace->bytes;
    cloned->encoded_namespace->encoding = encoded_namespace->encoding;
    cloned->encoded_namespace->hash = encoded_namespace->hash;
  }
  if (encoded_type_name) {
    cloned->encoded_type_name = std::make_unique<CachedMetaString>();
    cloned->encoded_type_name->original = encoded_type_name->original;
    cloned->encoded_type_name->bytes = encoded_type_name->bytes;
    cloned->encoded_type_name->encoding = encoded_type_name->encoding;
    cloned->encoded_type_name->hash = encoded_type_name->hash;
  }

  return cloned;
}

// ============================================================================
// encode_meta_string Implementation
// ============================================================================

Result<std::unique_ptr<CachedMetaString>, Error>
encode_meta_string(const std::string &value, bool is_namespace) {
  auto cached = std::make_unique<CachedMetaString>();
  cached->original = value;

  if (value.empty()) {
    // For empty strings, use a minimal encoding
    cached->encoding = 0; // UTF8
    cached->bytes.clear();
    cached->hash = 0;
    return cached;
  }

  // Use MetaStringEncoder to encode the string
  static const MetaStringEncoder kNamespaceEncoder('.', '_');
  static const MetaStringEncoder kTypeNameEncoder('$', '_');

  static const std::vector<MetaEncoding> kNamespaceEncodings = {
      MetaEncoding::UTF8, MetaEncoding::ALL_TO_LOWER_SPECIAL,
      MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL};

  static const std::vector<MetaEncoding> kTypeNameEncodings = {
      MetaEncoding::UTF8, MetaEncoding::ALL_TO_LOWER_SPECIAL,
      MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL,
      MetaEncoding::FIRST_TO_LOWER_SPECIAL};

  if (is_namespace) {
    FORY_TRY(result, kNamespaceEncoder.encode(value, kNamespaceEncodings));
    cached->encoding = static_cast<uint8_t>(result.encoding);
    cached->bytes = std::move(result.bytes);
  } else {
    FORY_TRY(result, kTypeNameEncoder.encode(value, kTypeNameEncodings));
    cached->encoding = static_cast<uint8_t>(result.encoding);
    cached->bytes = std::move(result.bytes);
  }

  // Compute hash if needed (for now, just use 0)
  cached->hash = 0;

  return cached;
}

Result<const TypeInfo *, Error>
TypeResolver::get_type_info(const std::type_index &type_index) const {
  // For runtime polymorphic lookups (e.g., smart pointers with dynamic types)
  auto it = type_info_by_runtime_type_.find(type_index);
  if (it == type_info_by_runtime_type_.end()) {
    return Unexpected(Error::type_error("TypeInfo not found for type_index"));
  }
  return it->second;
}

Result<std::unique_ptr<TypeResolver>, Error>
TypeResolver::build_final_type_resolver() {
  auto final_resolver = std::make_unique<TypeResolver>();

  // Copy configuration
  final_resolver->compatible_ = compatible_;
  final_resolver->xlang_ = xlang_;
  final_resolver->check_struct_version_ = check_struct_version_;
  final_resolver->track_ref_ = track_ref_;
  final_resolver->finalized_ = true;

  // Build mapping from old pointers to new pointers for rebuilding lookup maps
  absl::flat_hash_map<const TypeInfo *, TypeInfo *> ptr_map;

  // Deep clone all existing TypeInfo objects
  for (const auto &info : type_infos_) {
    auto cloned = info->deep_clone();
    TypeInfo *new_ptr = cloned.get();
    ptr_map[info.get()] = new_ptr;
    final_resolver->type_infos_.push_back(std::move(cloned));
  }

  // Rebuild lookup maps with new pointers
  for (const auto &[key, old_ptr] : type_info_by_ctid_) {
    final_resolver->type_info_by_ctid_.put(key, ptr_map[old_ptr]);
  }
  for (const auto &[key, old_ptr] : type_info_by_id_) {
    final_resolver->type_info_by_id_.put(key, ptr_map[old_ptr]);
  }
  for (const auto &[key, old_ptr] : type_info_by_name_) {
    final_resolver->type_info_by_name_[key] = ptr_map[old_ptr];
  }
  for (const auto &[key, old_ptr] : type_info_by_runtime_type_) {
    final_resolver->type_info_by_runtime_type_[key] = ptr_map[old_ptr];
  }
  for (const auto &[key, old_ptr] : partial_type_infos_) {
    final_resolver->partial_type_infos_.put(key, ptr_map[old_ptr]);
  }

  // Process all partial type infos to build complete type metadata
  for (const auto &[rust_type_id, partial_ptr] :
       final_resolver->partial_type_infos_) {
    // Call the harness's sorted_field_infos function to get complete field info
    auto fields_result =
        partial_ptr->harness.sorted_field_infos_fn(*final_resolver);
    if (!fields_result.ok()) {
      return Unexpected(fields_result.error());
    }
    auto sorted_fields = std::move(fields_result).value();

    // Build complete TypeMeta
    TypeMeta meta = TypeMeta::from_fields(
        partial_ptr->type_id, partial_ptr->namespace_name,
        partial_ptr->type_name, partial_ptr->register_by_name,
        std::move(sorted_fields));

    // Serialize TypeMeta to bytes
    auto type_def_result = meta.to_bytes();
    if (!type_def_result.ok()) {
      return Unexpected(type_def_result.error());
    }

    // Update the TypeInfo in place
    partial_ptr->type_def = std::move(type_def_result).value();

    // Parse the serialized TypeMeta back to create unique_ptr<TypeMeta>
    Buffer buffer(partial_ptr->type_def.data(),
                  static_cast<uint32_t>(partial_ptr->type_def.size()), false);
    buffer.WriterIndex(static_cast<uint32_t>(partial_ptr->type_def.size()));
    auto parsed_meta_result = TypeMeta::from_bytes(buffer, nullptr);
    if (!parsed_meta_result.ok()) {
      return Unexpected(parsed_meta_result.error());
    }
    partial_ptr->type_meta = std::move(parsed_meta_result).value();
  }

  // Clear partial_type_infos in the final resolver since they're all completed
  final_resolver->partial_type_infos_.clear();

  return final_resolver;
}

std::unique_ptr<TypeResolver> TypeResolver::clone() const {
  auto cloned = std::make_unique<TypeResolver>();

  // Copy configuration
  cloned->compatible_ = compatible_;
  cloned->xlang_ = xlang_;
  cloned->check_struct_version_ = check_struct_version_;
  cloned->track_ref_ = track_ref_;
  cloned->finalized_ = finalized_;

  // Build mapping from old pointers to new pointers
  absl::flat_hash_map<const TypeInfo *, TypeInfo *> ptr_map;

  // Deep clone all TypeInfo objects
  for (const auto &info : type_infos_) {
    auto cloned_info = info->deep_clone();
    TypeInfo *new_ptr = cloned_info.get();
    ptr_map[info.get()] = new_ptr;
    cloned->type_infos_.push_back(std::move(cloned_info));
  }

  // Rebuild lookup maps with new pointers
  for (const auto &[key, old_ptr] : type_info_by_ctid_) {
    cloned->type_info_by_ctid_.put(key, ptr_map[old_ptr]);
  }
  for (const auto &[key, old_ptr] : type_info_by_id_) {
    cloned->type_info_by_id_.put(key, ptr_map[old_ptr]);
  }
  for (const auto &[key, old_ptr] : type_info_by_name_) {
    cloned->type_info_by_name_[key] = ptr_map[old_ptr];
  }
  for (const auto &[key, old_ptr] : type_info_by_runtime_type_) {
    cloned->type_info_by_runtime_type_[key] = ptr_map[old_ptr];
  }
  // Note: Don't copy partial_type_infos_ - clone should only be used on
  // finalized resolvers

  return cloned;
}

void TypeResolver::register_builtin_types() {
  // Register internal type IDs without harnesses (deserialization is static)
  // These are needed so read_any_typeinfo can find them by type_id
  auto register_type_id_only = [this](TypeId type_id) {
    auto info = std::make_unique<TypeInfo>();
    info->type_id = static_cast<uint32_t>(type_id);
    info->register_by_name = false;
    info->is_external = false;
    TypeInfo *raw_ptr = info.get();
    type_infos_.push_back(std::move(info));
    type_info_by_id_.put(raw_ptr->type_id, raw_ptr);
  };

  // Primitive types
  register_type_id_only(TypeId::BOOL);
  register_type_id_only(TypeId::INT8);
  register_type_id_only(TypeId::INT16);
  register_type_id_only(TypeId::INT32);
  register_type_id_only(TypeId::VARINT32);
  register_type_id_only(TypeId::INT64);
  register_type_id_only(TypeId::VARINT64);
  register_type_id_only(TypeId::TAGGED_INT64);
  register_type_id_only(TypeId::UINT8);
  register_type_id_only(TypeId::UINT16);
  register_type_id_only(TypeId::UINT32);
  register_type_id_only(TypeId::VAR_UINT32);
  register_type_id_only(TypeId::UINT64);
  register_type_id_only(TypeId::VAR_UINT64);
  register_type_id_only(TypeId::TAGGED_UINT64);
  register_type_id_only(TypeId::FLOAT16);
  register_type_id_only(TypeId::FLOAT32);
  register_type_id_only(TypeId::FLOAT64);
  register_type_id_only(TypeId::STRING);

  // Primitive array types
  register_type_id_only(TypeId::BOOL_ARRAY);
  register_type_id_only(TypeId::INT8_ARRAY);
  register_type_id_only(TypeId::INT16_ARRAY);
  register_type_id_only(TypeId::INT32_ARRAY);
  register_type_id_only(TypeId::INT64_ARRAY);
  register_type_id_only(TypeId::UINT8_ARRAY);
  register_type_id_only(TypeId::UINT16_ARRAY);
  register_type_id_only(TypeId::UINT32_ARRAY);
  register_type_id_only(TypeId::UINT64_ARRAY);
  register_type_id_only(TypeId::FLOAT16_ARRAY);
  register_type_id_only(TypeId::FLOAT32_ARRAY);
  register_type_id_only(TypeId::FLOAT64_ARRAY);
  register_type_id_only(TypeId::BINARY);

  // Collection types
  register_type_id_only(TypeId::LIST);
  register_type_id_only(TypeId::SET);
  register_type_id_only(TypeId::MAP);

  // User types (base IDs without registration prefix)
  register_type_id_only(TypeId::STRUCT);
  register_type_id_only(TypeId::ENUM);
  register_type_id_only(TypeId::EXT);

  // Other internal types
  register_type_id_only(TypeId::UNION);
  register_type_id_only(TypeId::NONE);
  register_type_id_only(TypeId::DURATION);
  register_type_id_only(TypeId::TIMESTAMP);
  register_type_id_only(TypeId::DATE);
  register_type_id_only(TypeId::DECIMAL);
  register_type_id_only(TypeId::ARRAY);
}

} // namespace serialization
} // namespace fory
