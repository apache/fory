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

#include "fory/serialization/context.h"
#include "fory/meta/meta_string.h"
#include "fory/serialization/type_resolver.h"
#include "fory/thirdparty/MurmurHash3.h"
#include "fory/type/type.h"

namespace fory {
namespace serialization {

using namespace meta;

// ============================================================================
// Meta String Encoding Constants (shared between encoder and writer)
// ============================================================================

static constexpr uint32_t kSmallStringThreshold = 16;

// Package/namespace encoder: dots and underscores as special chars
static const MetaStringEncoder kNamespaceEncoder('.', '_');

// Type name encoder: dollar sign and underscores as special chars
static const MetaStringEncoder kTypeNameEncoder('$', '_');

// Allowed encodings for package/namespace (same as Java's pkgEncodings)
static const std::vector<MetaEncoding> kPkgEncodings = {
    MetaEncoding::UTF8, MetaEncoding::ALL_TO_LOWER_SPECIAL,
    MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL};

// Allowed encodings for type name (same as Java's typeNameEncodings)
static const std::vector<MetaEncoding> kTypeNameEncodings = {
    MetaEncoding::UTF8, MetaEncoding::ALL_TO_LOWER_SPECIAL,
    MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL,
    MetaEncoding::FIRST_TO_LOWER_SPECIAL};

// ============================================================================
// encode_meta_string - Pre-encode meta strings during registration
// ============================================================================

Result<std::shared_ptr<CachedMetaString>, Error>
encode_meta_string(const std::string &value, bool is_namespace) {
  auto result = std::make_shared<CachedMetaString>();
  result->original = value;

  if (value.empty()) {
    result->bytes.clear();
    result->encoding = static_cast<uint8_t>(MetaEncoding::UTF8);
    result->hash = 0;
    return result;
  }

  // Choose encoder and encodings based on whether this is namespace or type
  // name
  const auto &encoder = is_namespace ? kNamespaceEncoder : kTypeNameEncoder;
  const auto &encodings = is_namespace ? kPkgEncodings : kTypeNameEncodings;

  // Encode the string
  FORY_TRY(encoded, encoder.encode(value, encodings));
  result->bytes = std::move(encoded.bytes);
  result->encoding = static_cast<uint8_t>(encoded.encoding);

  // Pre-compute hash for large strings (>16 bytes)
  if (result->bytes.size() > kSmallStringThreshold) {
    int64_t hash_out[2] = {0, 0};
    MurmurHash3_x64_128(result->bytes.data(),
                        static_cast<int>(result->bytes.size()), 47, hash_out);
    result->hash = hash_out[0];
  } else {
    result->hash = 0;
  }

  return result;
}

// ============================================================================
// WriteContext Implementation
// ============================================================================

WriteContext::WriteContext(const Config &config,
                           std::shared_ptr<TypeResolver> type_resolver)
    : buffer_(), config_(&config), type_resolver_(std::move(type_resolver)),
      current_dyn_depth_(0) {}

WriteContext::~WriteContext() = default;

Result<size_t, Error> WriteContext::push_meta(const std::type_index &type_id) {
  auto it = write_type_id_index_map_.find(type_id);
  if (it != write_type_id_index_map_.end()) {
    return it->second;
  }

  size_t index = write_type_defs_.size();
  FORY_TRY(type_info, type_resolver_->get_type_info(type_id));
  write_type_defs_.push_back(type_info->type_def);
  write_type_id_index_map_[type_id] = index;
  return index;
}

size_t WriteContext::push_meta(const TypeInfo *type_info) {
  auto it = write_type_info_index_map_.find(type_info);
  if (it != write_type_info_index_map_.end()) {
    return it->second;
  }

  size_t index = write_type_defs_.size();
  write_type_defs_.push_back(type_info->type_def);
  write_type_info_index_map_[type_info] = index;
  return index;
}

void WriteContext::write_meta(size_t offset) {
  size_t current_pos = buffer_.writer_index();
  // Update the meta offset field (written as -1 initially)
  int32_t meta_size = static_cast<int32_t>(current_pos - offset - 4);
  buffer_.UnsafePut<int32_t>(offset, meta_size);
  // Write all collected TypeMetas
  buffer_.WriteVarUint32(static_cast<uint32_t>(write_type_defs_.size()));
  for (size_t i = 0; i < write_type_defs_.size(); ++i) {
    const auto &type_def = write_type_defs_[i];
    buffer_.WriteBytes(type_def.data(), type_def.size());
  }
}

bool WriteContext::meta_empty() const { return write_type_defs_.empty(); }

/// Write pre-encoded meta string to buffer (avoids re-encoding on each write)
static void write_encoded_meta_string(Buffer &buffer,
                                      const CachedMetaString &encoded) {
  const uint32_t encoded_len = static_cast<uint32_t>(encoded.bytes.size());
  uint32_t header = encoded_len << 1; // last bit 0 => new string
  buffer.WriteVarUint32(header);

  if (encoded_len > kSmallStringThreshold) {
    // For large strings, write pre-computed hash
    buffer.WriteInt64(encoded.hash);
  } else {
    // For small strings, write encoding byte
    buffer.WriteInt8(static_cast<int8_t>(encoded.encoding));
  }

  if (encoded_len > 0) {
    buffer.WriteBytes(encoded.bytes.data(), encoded_len);
  }
}

Result<void, Error>
WriteContext::write_enum_typeinfo(const std::type_index &type) {
  auto type_info_result = type_resolver_->get_type_info(type);
  if (!type_info_result.ok()) {
    // Enum not registered, write plain ENUM type id
    buffer_.WriteVarUint32(static_cast<uint32_t>(TypeId::ENUM));
    return Result<void, Error>();
  }

  const auto &type_info = type_info_result.value();
  uint32_t type_id = type_info->type_id;
  uint32_t type_id_low = type_id & 0xff;

  buffer_.WriteVarUint32(type_id);

  if (type_id_low == static_cast<uint32_t>(TypeId::NAMED_ENUM)) {
    if (config_->compatible) {
      // Write meta_index
      FORY_TRY(meta_index, push_meta(type));
      buffer_.WriteVarUint32(static_cast<uint32_t>(meta_index));
    } else {
      // Write pre-encoded namespace and type_name
      if (type_info->encoded_namespace && type_info->encoded_type_name) {
        write_encoded_meta_string(buffer_, *type_info->encoded_namespace);
        write_encoded_meta_string(buffer_, *type_info->encoded_type_name);
      } else {
        return Unexpected(
            Error::invalid("Encoded meta strings not initialized for enum"));
      }
    }
  }
  // For plain ENUM, just writing type_id is sufficient

  return Result<void, Error>();
}

Result<void, Error>
WriteContext::write_enum_typeinfo(const TypeInfo *type_info) {
  if (!type_info) {
    // Enum not registered, write plain ENUM type id
    buffer_.WriteVarUint32(static_cast<uint32_t>(TypeId::ENUM));
    return Result<void, Error>();
  }

  uint32_t type_id = type_info->type_id;
  uint32_t type_id_low = type_id & 0xff;

  buffer_.WriteVarUint32(type_id);

  if (type_id_low == static_cast<uint32_t>(TypeId::NAMED_ENUM)) {
    if (config_->compatible) {
      // Write meta_index using TypeInfo pointer (fast path)
      size_t meta_index = push_meta(type_info);
      buffer_.WriteVarUint32(static_cast<uint32_t>(meta_index));
    } else {
      // Write pre-encoded namespace and type_name
      if (type_info->encoded_namespace && type_info->encoded_type_name) {
        write_encoded_meta_string(buffer_, *type_info->encoded_namespace);
        write_encoded_meta_string(buffer_, *type_info->encoded_type_name);
      } else {
        return Unexpected(
            Error::invalid("Encoded meta strings not initialized for enum"));
      }
    }
  }
  // For plain ENUM, just writing type_id is sufficient

  return Result<void, Error>();
}

Result<const TypeInfo *, Error>
WriteContext::write_any_typeinfo(uint32_t fory_type_id,
                                 const std::type_index &concrete_type_id) {
  // Check if it's an internal type
  if (is_internal_type(fory_type_id)) {
    buffer_.WriteVarUint32(fory_type_id);
    auto type_info = type_resolver_->get_type_info_by_id(fory_type_id);
    if (!type_info) {
      return Unexpected(
          Error::type_error("Type info for internal type not found"));
    }
    return type_info.get();
  }

  // Get type info for the concrete type
  FORY_TRY(type_info, type_resolver_->get_type_info(concrete_type_id));
  uint32_t type_id = type_info->type_id;

  // Write type_id
  buffer_.WriteVarUint32(type_id);

  // Handle different type categories based on low byte
  uint32_t type_id_low = type_id & 0xff;
  switch (type_id_low) {
  case static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT):
  case static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT): {
    // Write meta_index
    FORY_TRY(meta_index, push_meta(concrete_type_id));
    buffer_.WriteVarUint32(static_cast<uint32_t>(meta_index));
    break;
  }
  case static_cast<uint32_t>(TypeId::NAMED_ENUM):
  case static_cast<uint32_t>(TypeId::NAMED_EXT):
  case static_cast<uint32_t>(TypeId::NAMED_STRUCT): {
    if (config_->compatible) {
      // Write meta_index (share_meta is effectively compatible in C++)
      FORY_TRY(meta_index, push_meta(concrete_type_id));
      buffer_.WriteVarUint32(static_cast<uint32_t>(meta_index));
    } else {
      // Write pre-encoded namespace and type_name
      if (type_info->encoded_namespace && type_info->encoded_type_name) {
        write_encoded_meta_string(buffer_, *type_info->encoded_namespace);
        write_encoded_meta_string(buffer_, *type_info->encoded_type_name);
      } else {
        return Unexpected(
            Error::invalid("Encoded meta strings not initialized for type"));
      }
    }
    break;
  }
  default:
    // For other types, just writing type_id is sufficient
    break;
  }

  return type_info;
}

Result<void, Error>
WriteContext::write_struct_type_info(const std::type_index &type_id) {
  // Get type info with single lookup
  FORY_TRY(type_info, type_resolver_->get_type_info(type_id));
  uint32_t fory_type_id = type_info->type_id;

  // Write type_id
  buffer_.WriteVarUint32(fory_type_id);

  // Handle different struct type categories based on low byte
  uint32_t type_id_low = fory_type_id & 0xff;
  switch (type_id_low) {
  case static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT):
  case static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT): {
    // Write meta_index
    FORY_TRY(meta_index, push_meta(type_id));
    buffer_.WriteVarUint32(static_cast<uint32_t>(meta_index));
    break;
  }
  case static_cast<uint32_t>(TypeId::NAMED_STRUCT): {
    if (config_->compatible) {
      // Write meta_index
      FORY_TRY(meta_index, push_meta(type_id));
      buffer_.WriteVarUint32(static_cast<uint32_t>(meta_index));
    } else {
      // Write pre-encoded namespace and type_name
      if (type_info->encoded_namespace && type_info->encoded_type_name) {
        write_encoded_meta_string(buffer_, *type_info->encoded_namespace);
        write_encoded_meta_string(buffer_, *type_info->encoded_type_name);
      } else {
        return Unexpected(
            Error::invalid("Encoded meta strings not initialized for struct"));
      }
    }
    break;
  }
  default:
    // STRUCT type - just writing type_id is sufficient
    break;
  }

  return Result<void, Error>();
}

Result<void, Error>
WriteContext::write_struct_type_info(const TypeInfo *type_info) {
  uint32_t fory_type_id = type_info->type_id;

  // Write type_id
  buffer_.WriteVarUint32(fory_type_id);

  // Handle different struct type categories based on low byte
  uint32_t type_id_low = fory_type_id & 0xff;
  switch (type_id_low) {
  case static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT):
  case static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT): {
    // Write meta_index using TypeInfo pointer (fast path)
    size_t meta_index = push_meta(type_info);
    buffer_.WriteVarUint32(static_cast<uint32_t>(meta_index));
    break;
  }
  case static_cast<uint32_t>(TypeId::NAMED_STRUCT): {
    if (config_->compatible) {
      // Write meta_index using TypeInfo pointer (fast path)
      size_t meta_index = push_meta(type_info);
      buffer_.WriteVarUint32(static_cast<uint32_t>(meta_index));
    } else {
      // Write pre-encoded namespace and type_name
      if (type_info->encoded_namespace && type_info->encoded_type_name) {
        write_encoded_meta_string(buffer_, *type_info->encoded_namespace);
        write_encoded_meta_string(buffer_, *type_info->encoded_type_name);
      } else {
        return Unexpected(
            Error::invalid("Encoded meta strings not initialized for struct"));
      }
    }
    break;
  }
  default:
    // STRUCT type - just writing type_id is sufficient
    break;
  }

  return Result<void, Error>();
}

void WriteContext::reset() {
  ref_writer_.reset();
  // Clear meta vectors/maps - they're typically small or empty
  // in non-compatible mode, so clear() is efficient
  write_type_defs_.clear();
  write_type_id_index_map_.clear();
  write_type_info_index_map_.clear();
  current_dyn_depth_ = 0;
  // Reset buffer indices for reuse - no memory operations needed
  buffer_.WriterIndex(0);
  buffer_.ReaderIndex(0);
}

uint32_t WriteContext::get_type_id_for_cache(const std::type_index &type_idx) {
  auto result = type_resolver_->get_type_info(type_idx);
  if (!result.ok()) {
    return 0;
  }
  return result.value()->type_id;
}

// ============================================================================
// ReadContext Implementation
// ============================================================================

ReadContext::ReadContext(const Config &config,
                         std::shared_ptr<TypeResolver> type_resolver)
    : buffer_(nullptr), config_(&config),
      type_resolver_(std::move(type_resolver)), current_dyn_depth_(0) {}

ReadContext::~ReadContext() = default;

// Static decoders for NAMED_ENUM namespace/type_name - shared across calls
static const MetaStringDecoder kNamespaceDecoder('.', '_');
static const MetaStringDecoder kTypeNameDecoder('$', '_');

Result<std::shared_ptr<TypeInfo>, Error>
ReadContext::read_enum_type_info(const std::type_index &type,
                                 uint32_t base_type_id) {
  (void)type;
  return read_enum_type_info(base_type_id);
}

Result<std::shared_ptr<TypeInfo>, Error>
ReadContext::read_enum_type_info(uint32_t base_type_id) {
  FORY_TRY(type_info, read_any_typeinfo());
  uint32_t type_id_low = type_info->type_id & 0xff;
  // Accept both ENUM and NAMED_ENUM as compatible types
  if (type_id_low != static_cast<uint32_t>(TypeId::ENUM) &&
      type_id_low != static_cast<uint32_t>(TypeId::NAMED_ENUM)) {
    return Unexpected(Error::type_mismatch(type_info->type_id, base_type_id));
  }
  return type_info;
}

// Maximum number of parsed type defs to cache (avoid OOM from malicious input)
static constexpr size_t kMaxParsedNumTypeDefs = 8192;

Result<size_t, Error> ReadContext::load_type_meta(int32_t meta_offset) {
  size_t current_pos = buffer_->reader_index();
  size_t meta_start = current_pos + meta_offset;
  buffer_->ReaderIndex(static_cast<uint32_t>(meta_start));

  // Load all TypeMetas
  FORY_TRY(meta_size, buffer_->ReadVarUint32());
  reading_type_infos_.reserve(meta_size);

  for (uint32_t i = 0; i < meta_size; i++) {
    // Read the 8-byte header first for caching
    FORY_TRY(meta_header, buffer_->ReadInt64());

    // Check if we already parsed this type meta (cache lookup by header)
    auto cache_it = parsed_type_infos_.find(meta_header);
    if (cache_it != parsed_type_infos_.end()) {
      // Found in cache - reuse and skip the bytes
      reading_type_infos_.push_back(cache_it->second);
      FORY_RETURN_NOT_OK(TypeMeta::skip_bytes(*buffer_, meta_header));
      continue;
    }

    // Not in cache - parse the TypeMeta
    FORY_TRY(parsed_meta,
             TypeMeta::from_bytes_with_header(*buffer_, meta_header));

    // Find local TypeInfo to get field_id mapping
    std::shared_ptr<TypeInfo> local_type_info = nullptr;
    if (parsed_meta->register_by_name) {
      local_type_info = type_resolver_->get_type_info_by_name(
          parsed_meta->namespace_str, parsed_meta->type_name);
    } else {
      local_type_info =
          type_resolver_->get_type_info_by_id(parsed_meta->type_id);
    }

    // Create TypeInfo with field_ids assigned
    std::shared_ptr<TypeInfo> type_info;
    if (local_type_info) {
      // Have local type - assign field_ids by comparing schemas
      // Note: Extension types don't have type_meta (only structs do)
      if (local_type_info->type_meta) {
        TypeMeta::assign_field_ids(local_type_info->type_meta.get(),
                                   parsed_meta->field_infos);
      }
      type_info = std::make_shared<TypeInfo>();
      type_info->type_id = local_type_info->type_id;
      type_info->type_meta = parsed_meta;
      type_info->type_def = local_type_info->type_def;
      // CRITICAL: Copy the harness from the registered type_info
      type_info->harness = local_type_info->harness;
      type_info->name_to_index = local_type_info->name_to_index;
      type_info->namespace_name = local_type_info->namespace_name;
      type_info->type_name = local_type_info->type_name;
      type_info->register_by_name = local_type_info->register_by_name;
    } else {
      // No local type - create stub TypeInfo with parsed meta
      type_info = std::make_shared<TypeInfo>();
      type_info->type_id = parsed_meta->type_id;
      type_info->type_meta = parsed_meta;
    }

    // Cache the parsed TypeInfo (with size limit to prevent OOM)
    if (parsed_type_infos_.size() < kMaxParsedNumTypeDefs) {
      parsed_type_infos_[meta_header] = type_info;
    }

    reading_type_infos_.push_back(type_info);
  }

  // Calculate size of meta section
  size_t meta_end = buffer_->reader_index();
  size_t meta_section_size = meta_end - meta_start;

  // Restore buffer position
  buffer_->ReaderIndex(static_cast<uint32_t>(current_pos));
  return meta_section_size;
}

Result<std::shared_ptr<TypeInfo>, Error>
ReadContext::get_type_info_by_index(size_t index) const {
  if (index >= reading_type_infos_.size()) {
    return Unexpected(Error::invalid(
        "Meta index out of bounds: " + std::to_string(index) +
        ", size: " + std::to_string(reading_type_infos_.size())));
  }
  return reading_type_infos_[index];
}

Result<std::shared_ptr<TypeInfo>, Error> ReadContext::read_any_typeinfo() {
  FORY_TRY(type_id, buffer_->ReadVarUint32());
  uint32_t type_id_low = type_id & 0xff;

  // Mirror Rust's read_any_typeinfo using switch for jump table generation
  switch (type_id_low) {
  case static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT):
  case static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT): {
    FORY_TRY(meta_index, buffer_->ReadVarUint32());
    return get_type_info_by_index(meta_index);
  }
  case static_cast<uint32_t>(TypeId::NAMED_ENUM):
  case static_cast<uint32_t>(TypeId::NAMED_EXT):
  case static_cast<uint32_t>(TypeId::NAMED_STRUCT): {
    if (config_->compatible) {
      FORY_TRY(meta_index, buffer_->ReadVarUint32());
      return get_type_info_by_index(meta_index);
    }
    FORY_TRY(namespace_str,
             meta_string_table_.read_string(*buffer_, kNamespaceDecoder));
    FORY_TRY(type_name,
             meta_string_table_.read_string(*buffer_, kTypeNameDecoder));
    auto type_info =
        type_resolver_->get_type_info_by_name(namespace_str, type_name);
    if (!type_info) {
      return Unexpected(Error::type_error(
          "Name harness not found: " + namespace_str + "." + type_name));
    }
    return type_info;
  }
  default: {
    // All types must be registered in type_resolver
    auto type_info = type_resolver_->get_type_info_by_id(type_id);
    if (!type_info) {
      return Unexpected(Error::type_error("Type not found for type_id: " +
                                          std::to_string(type_id)));
    }
    return type_info;
  }
  }
}

void ReadContext::reset() {
  ref_reader_.reset();
  reading_type_infos_.clear();
  parsed_type_infos_.clear();
  current_dyn_depth_ = 0;
  meta_string_table_.reset();
}

} // namespace serialization
} // namespace fory
