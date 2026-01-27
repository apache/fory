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

// Note: encode_meta_string is now implemented in type_resolver.cc

// ============================================================================
// WriteContext Implementation
// ============================================================================

WriteContext::WriteContext(const Config &config,
                           std::unique_ptr<TypeResolver> type_resolver)
    : buffer_(), config_(&config), type_resolver_(std::move(type_resolver)),
      current_dyn_depth_(0) {}

WriteContext::~WriteContext() = default;

Result<void, Error>
WriteContext::write_type_meta(const std::type_index &type_id) {
  // Resolve type_index to TypeInfo* and delegate to the TypeInfo* version
  // This ensures consistent indexing when the same type is written via
  // either type_index or TypeInfo* path
  FORY_TRY(type_info, type_resolver_->get_type_info(type_id));
  write_type_meta(type_info);
  return Result<void, Error>();
}

void WriteContext::write_type_meta(const TypeInfo *type_info) {
  auto it = write_type_info_index_map_.find(type_info);
  if (it != write_type_info_index_map_.end()) {
    // Reference to previously written type: (index << 1) | 1, LSB=1
    buffer_.WriteVarUint32(static_cast<uint32_t>((it->second << 1) | 1));
    return;
  }

  // New type: index << 1, LSB=0, followed by TypeDef bytes inline
  size_t index = write_type_info_index_map_.size();
  buffer_.WriteVarUint32(static_cast<uint32_t>(index << 1));
  write_type_info_index_map_[type_info] = index;

  // Write TypeDef bytes inline
  buffer_.WriteBytes(type_info->type_def.data(), type_info->type_def.size());
}

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
  FORY_TRY(type_info, type_resolver_->get_type_info(type));
  uint32_t type_id = type_info->type_id;
  uint32_t type_id_low = type_id & 0xff;

  buffer_.WriteVarUint32(type_id);

  if (type_id_low == static_cast<uint32_t>(TypeId::NAMED_ENUM)) {
    if (config_->compatible) {
      // Write type meta inline using streaming protocol
      FORY_RETURN_NOT_OK(write_type_meta(type));
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
    return Unexpected(Error::type_error("Enum type not registered"));
  }

  uint32_t type_id = type_info->type_id;
  uint32_t type_id_low = type_id & 0xff;

  buffer_.WriteVarUint32(type_id);

  if (type_id_low == static_cast<uint32_t>(TypeId::NAMED_ENUM)) {
    if (config_->compatible) {
      // Write type meta inline using streaming protocol
      write_type_meta(type_info);
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
    FORY_TRY(type_info, type_resolver_->get_type_info_by_id(fory_type_id));
    return type_info;
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
    // Write type meta inline using streaming protocol
    FORY_RETURN_NOT_OK(write_type_meta(concrete_type_id));
    break;
  }
  case static_cast<uint32_t>(TypeId::NAMED_ENUM):
  case static_cast<uint32_t>(TypeId::NAMED_EXT):
  case static_cast<uint32_t>(TypeId::NAMED_STRUCT):
  case static_cast<uint32_t>(TypeId::NAMED_UNION): {
    if (config_->compatible) {
      // Write type meta inline using streaming protocol
      FORY_RETURN_NOT_OK(write_type_meta(concrete_type_id));
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
    // Write type meta inline using streaming protocol
    FORY_RETURN_NOT_OK(write_type_meta(type_id));
    break;
  }
  case static_cast<uint32_t>(TypeId::NAMED_STRUCT): {
    if (config_->compatible) {
      // Write type meta inline using streaming protocol
      FORY_RETURN_NOT_OK(write_type_meta(type_id));
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
    // Write type meta inline using streaming protocol
    write_type_meta(type_info);
    break;
  }
  case static_cast<uint32_t>(TypeId::NAMED_STRUCT): {
    if (config_->compatible) {
      // Write type meta inline using streaming protocol
      write_type_meta(type_info);
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
  // Clear error state first
  error_ = Error();
  ref_writer_.reset();
  // Clear meta map for streaming TypeMeta (size is used as counter)
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
                         std::unique_ptr<TypeResolver> type_resolver)
    : buffer_(nullptr), config_(&config),
      type_resolver_(std::move(type_resolver)), current_dyn_depth_(0) {}

ReadContext::~ReadContext() = default;

// Static decoders for NAMED_ENUM namespace/type_name - shared across calls
static const MetaStringDecoder kNamespaceDecoder('.', '_');
static const MetaStringDecoder kTypeNameDecoder('$', '_');

Result<const TypeInfo *, Error>
ReadContext::read_enum_type_info(const std::type_index &type,
                                 uint32_t base_type_id) {
  (void)type;
  return read_enum_type_info(base_type_id);
}

Result<const TypeInfo *, Error>
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

Result<const TypeInfo *, Error> ReadContext::read_type_meta() {
  Error error;
  // Read the index marker
  uint32_t index_marker = buffer_->ReadVarUint32(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  bool is_ref = (index_marker & 1) == 1;
  size_t index = index_marker >> 1;

  if (is_ref) {
    // Reference to previously read type
    return get_type_info_by_index(index);
  }

  // New type - read TypeMeta inline
  // Read the 8-byte header first for caching
  int64_t meta_header = buffer_->ReadInt64(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  // Check if we already parsed this type meta (cache lookup by header)
  auto cache_it = parsed_type_infos_.find(meta_header);
  if (cache_it != parsed_type_infos_.end()) {
    // Found in cache - reuse and skip the bytes
    reading_type_infos_.push_back(cache_it->second);
    FORY_RETURN_NOT_OK(TypeMeta::skip_bytes(*buffer_, meta_header));
    return cache_it->second;
  }

  // Not in cache - parse the TypeMeta
  FORY_TRY(parsed_meta,
           TypeMeta::from_bytes_with_header(*buffer_, meta_header));

  // Find local TypeInfo to get field_id mapping (optional for schema evolution)
  const TypeInfo *local_type_info = nullptr;
  if (parsed_meta->register_by_name) {
    auto result = type_resolver_->get_type_info_by_name(
        parsed_meta->namespace_str, parsed_meta->type_name);
    if (result.ok()) {
      local_type_info = result.value();
    }
  } else {
    auto result = type_resolver_->get_type_info_by_id(parsed_meta->type_id);
    if (result.ok()) {
      local_type_info = result.value();
    }
  }

  // Create TypeInfo with field_ids assigned
  auto type_info = std::make_unique<TypeInfo>();
  if (local_type_info) {
    // Have local type - assign field_ids by comparing schemas
    // Note: Extension types don't have type_meta (only structs do)
    if (local_type_info->type_meta) {
      TypeMeta::assign_field_ids(local_type_info->type_meta.get(),
                                 parsed_meta->field_infos);
    }
    type_info->type_id = local_type_info->type_id;
    type_info->type_meta = std::move(parsed_meta);
    type_info->type_def = local_type_info->type_def;
    // CRITICAL: Copy the harness from the registered type_info
    type_info->harness = local_type_info->harness;
    type_info->name_to_index = local_type_info->name_to_index;
    type_info->namespace_name = local_type_info->namespace_name;
    type_info->type_name = local_type_info->type_name;
    type_info->register_by_name = local_type_info->register_by_name;
  } else {
    // No local type - create stub TypeInfo with parsed meta
    type_info->type_id = parsed_meta->type_id;
    type_info->type_meta = std::move(parsed_meta);
  }

  // Get raw pointer before moving into storage
  const TypeInfo *raw_ptr = type_info.get();

  // Store in primary storage
  owned_reading_type_infos_.push_back(std::move(type_info));

  // Cache the parsed TypeInfo (with size limit to prevent OOM)
  if (parsed_type_infos_.size() < kMaxParsedNumTypeDefs) {
    parsed_type_infos_[meta_header] = raw_ptr;
  }

  reading_type_infos_.push_back(raw_ptr);
  return raw_ptr;
}

Result<const TypeInfo *, Error>
ReadContext::get_type_info_by_index(size_t index) const {
  if (index >= reading_type_infos_.size()) {
    return Unexpected(Error::invalid(
        "Meta index out of bounds: " + std::to_string(index) +
        ", size: " + std::to_string(reading_type_infos_.size())));
  }
  return reading_type_infos_[index];
}

Result<const TypeInfo *, Error> ReadContext::read_any_typeinfo() {
  Error error;
  uint32_t type_id = buffer_->ReadVarUint32(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }
  uint32_t type_id_low = type_id & 0xff;

  // Use streaming protocol for type meta
  switch (type_id_low) {
  case static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT):
  case static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT): {
    // Read type meta inline using streaming protocol
    return read_type_meta();
  }
  case static_cast<uint32_t>(TypeId::NAMED_ENUM):
  case static_cast<uint32_t>(TypeId::NAMED_EXT):
  case static_cast<uint32_t>(TypeId::NAMED_STRUCT):
  case static_cast<uint32_t>(TypeId::NAMED_UNION): {
    if (config_->compatible) {
      // Read type meta inline using streaming protocol
      return read_type_meta();
    }
    FORY_TRY(namespace_str,
             meta_string_table_.read_string(*buffer_, kNamespaceDecoder));
    FORY_TRY(type_name,
             meta_string_table_.read_string(*buffer_, kTypeNameDecoder));
    FORY_TRY(type_info,
             type_resolver_->get_type_info_by_name(namespace_str, type_name));
    return type_info;
  }
  default: {
    // All types must be registered in type_resolver
    FORY_TRY(type_info, type_resolver_->get_type_info_by_id(type_id));
    return type_info;
  }
  }
}

const TypeInfo *ReadContext::read_any_typeinfo(Error &error) {
  auto result = read_any_typeinfo();
  if (!result.ok()) {
    error = std::move(result).error();
    return nullptr;
  }
  return result.value();
}

void ReadContext::reset() {
  // Clear error state first
  error_ = Error();
  ref_reader_.reset();
  reading_type_infos_.clear();
  parsed_type_infos_.clear();
  owned_reading_type_infos_.clear();
  current_dyn_depth_ = 0;
  meta_string_table_.reset();
}

} // namespace serialization
} // namespace fory
