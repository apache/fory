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

static constexpr uint32_t k_small_string_threshold = 16;

// Package/namespace encoder: dots and underscores as special chars
static const MetaStringEncoder k_namespace_encoder('.', '_');

// Type name encoder: dollar sign and underscores as special chars
static const MetaStringEncoder k_type_name_encoder('$', '_');

// Allowed encodings for package/namespace (same as Java's pkg_encodings)
static const std::vector<MetaEncoding> k_pkg_encodings = {
    MetaEncoding::UTF8, MetaEncoding::ALL_TO_LOWER_SPECIAL,
    MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL};

// Allowed encodings for type name (same as Java's type_name_encodings)
static const std::vector<MetaEncoding> k_type_name_encodings = {
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
      current_dyn_depth_(0), write_type_info_index_map_(8) {}

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
  const uint64_t key =
      static_cast<uint64_t>(reinterpret_cast<uintptr_t>(type_info));
  if (!type_info_index_map_active_) {
    if (!has_first_type_info_) {
      has_first_type_info_ = true;
      first_type_info_ = type_info;
      buffer_.write_uint8(0); // (index << 1), index=0
      buffer_.write_bytes(type_info->type_def.data(),
                          type_info->type_def.size());
      return;
    }
    if (type_info == first_type_info_) {
      buffer_.write_uint8(1); // (index << 1) | 1, index=0
      return;
    }
    type_info_index_map_active_ = true;
    write_type_info_index_map_.clear();
    const uint64_t first_key =
        static_cast<uint64_t>(reinterpret_cast<uintptr_t>(first_type_info_));
    write_type_info_index_map_.put(first_key, 0);
  } else if (type_info == first_type_info_) {
    buffer_.write_uint8(1); // (index << 1) | 1, index=0
    return;
  }

  if (auto *entry = write_type_info_index_map_.find(key)) {
    // Reference to previously written type: (index << 1) | 1, LSB=1
    uint32_t marker = static_cast<uint32_t>((entry->value << 1) | 1);
    if (marker < 0x80) {
      buffer_.write_uint8(static_cast<uint8_t>(marker));
    } else {
      buffer_.write_var_uint32(marker);
    }
    return;
  }

  // New type: index << 1, LSB=0, followed by TypeDef bytes inline
  uint32_t index = static_cast<uint32_t>(write_type_info_index_map_.size());
  uint32_t marker = static_cast<uint32_t>(index << 1);
  if (marker < 0x80) {
    buffer_.write_uint8(static_cast<uint8_t>(marker));
  } else {
    buffer_.write_var_uint32(marker);
  }
  write_type_info_index_map_.put(key, index);

  // write TypeDef bytes inline
  buffer_.write_bytes(type_info->type_def.data(), type_info->type_def.size());
}

/// write pre-encoded meta string to buffer (avoids re-encoding on each write)
static void write_encoded_meta_string(Buffer &buffer,
                                      const CachedMetaString &encoded) {
  const uint32_t encoded_len = static_cast<uint32_t>(encoded.bytes.size());
  uint32_t header = encoded_len << 1; // last bit 0 => new string
  buffer.write_var_uint32(header);

  if (encoded_len > k_small_string_threshold) {
    // For large strings, write pre-computed hash
    buffer.write_int64(encoded.hash);
  } else if (encoded_len > 0) {
    // For small strings, write encoding byte
    buffer.write_int8(static_cast<int8_t>(encoded.encoding));
  }

  if (encoded_len > 0) {
    buffer.write_bytes(encoded.bytes.data(), encoded_len);
  }
}

Result<void, Error>
WriteContext::write_enum_type_info(const std::type_index &type) {
  FORY_TRY(type_info, type_resolver_->get_type_info(type));
  uint32_t type_id = type_info->type_id;
  buffer_.write_uint8(static_cast<uint8_t>(type_id));
  if (type_id == static_cast<uint32_t>(TypeId::ENUM)) {
    if (type_info->user_type_id == kInvalidUserTypeId) {
      return Unexpected(Error::type_error("User type id is required for enum"));
    }
    buffer_.write_var_uint32(type_info->user_type_id);
  } else if (type_id == static_cast<uint32_t>(TypeId::NAMED_ENUM)) {
    if (config_->compatible) {
      // write type meta inline using streaming protocol
      FORY_RETURN_NOT_OK(write_type_meta(type));
    } else {
      // write pre-encoded namespace and type_name
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
WriteContext::write_enum_type_info(const TypeInfo *type_info) {
  if (!type_info) {
    return Unexpected(Error::type_error("Enum type not registered"));
  }

  uint32_t type_id = type_info->type_id;

  buffer_.write_uint8(static_cast<uint8_t>(type_id));
  if (type_id == static_cast<uint32_t>(TypeId::ENUM)) {
    if (type_info->user_type_id == kInvalidUserTypeId) {
      return Unexpected(Error::type_error("User type id is required for enum"));
    }
    buffer_.write_var_uint32(type_info->user_type_id);
  } else if (type_id == static_cast<uint32_t>(TypeId::NAMED_ENUM)) {
    if (config_->compatible) {
      // write type meta inline using streaming protocol
      write_type_meta(type_info);
    } else {
      // write pre-encoded namespace and type_name
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
WriteContext::write_any_type_info(uint32_t fory_type_id,
                                  const std::type_index &concrete_type_id) {
  // Check if it's an internal type
  if (is_internal_type(fory_type_id)) {
    // write type_id
    buffer_.write_uint8(static_cast<uint8_t>(fory_type_id));
    FORY_TRY(type_info, type_resolver_->get_type_info_by_id(fory_type_id));
    return type_info;
  }

  // get type info for the concrete type
  FORY_TRY(type_info, type_resolver_->get_type_info(concrete_type_id));
  uint32_t type_id = type_info->type_id;

  // write type_id
  buffer_.write_uint8(static_cast<uint8_t>(type_id));

  // Handle different type categories based on low byte
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::ENUM:
  case TypeId::STRUCT:
  case TypeId::EXT:
  case TypeId::TYPED_UNION:
    buffer_.write_var_uint32(type_info->user_type_id);
    break;
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
    // write type meta inline using streaming protocol
    FORY_RETURN_NOT_OK(write_type_meta(concrete_type_id));
    break;
  case TypeId::NAMED_ENUM:
  case TypeId::NAMED_EXT:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_UNION:
    if (config_->compatible) {
      // write type meta inline using streaming protocol
      FORY_RETURN_NOT_OK(write_type_meta(concrete_type_id));
    } else {
      // write pre-encoded namespace and type_name
      if (type_info->encoded_namespace && type_info->encoded_type_name) {
        write_encoded_meta_string(buffer_, *type_info->encoded_namespace);
        write_encoded_meta_string(buffer_, *type_info->encoded_type_name);
      } else {
        return Unexpected(
            Error::invalid("Encoded meta strings not initialized for type"));
      }
    }
    break;
  default:
    // For other types, just writing type_id is sufficient
    break;
  }

  return type_info;
}

Result<void, Error>
WriteContext::write_any_type_info(const TypeInfo *type_info) {
  if (FORY_PREDICT_FALSE(type_info == nullptr)) {
    return Unexpected(Error::invalid("TypeInfo is null"));
  }
  uint32_t type_id = type_info->type_id;
  buffer_.write_uint8(static_cast<uint8_t>(type_id));

  switch (static_cast<TypeId>(type_id)) {
  case TypeId::ENUM:
  case TypeId::STRUCT:
  case TypeId::EXT:
  case TypeId::TYPED_UNION:
    buffer_.write_var_uint32(type_info->user_type_id);
    break;
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
    // write type meta inline using streaming protocol
    write_type_meta(type_info);
    break;
  case TypeId::NAMED_ENUM:
  case TypeId::NAMED_EXT:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_UNION:
    if (config_->compatible) {
      // write type meta inline using streaming protocol
      write_type_meta(type_info);
    } else {
      // write pre-encoded namespace and type_name
      if (type_info->encoded_namespace && type_info->encoded_type_name) {
        write_encoded_meta_string(buffer_, *type_info->encoded_namespace);
        write_encoded_meta_string(buffer_, *type_info->encoded_type_name);
      } else {
        return Unexpected(
            Error::invalid("Encoded meta strings not initialized for type"));
      }
    }
    break;
  default:
    // For other types, just writing type_id is sufficient
    break;
  }

  return Result<void, Error>();
}

Result<void, Error>
WriteContext::write_struct_type_info(const std::type_index &type_id) {
  // get type info with single lookup
  FORY_TRY(type_info, type_resolver_->get_type_info(type_id));
  uint32_t fory_type_id = type_info->type_id;

  // write type_id
  buffer_.write_uint8(static_cast<uint8_t>(fory_type_id));
  switch (static_cast<TypeId>(fory_type_id)) {
  case TypeId::ENUM:
  case TypeId::STRUCT:
  case TypeId::EXT:
  case TypeId::TYPED_UNION:
    buffer_.write_var_uint32(type_info->user_type_id);
    break;
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
    // write type meta inline using streaming protocol
    FORY_RETURN_NOT_OK(write_type_meta(type_id));
    break;
  case TypeId::NAMED_STRUCT:
    if (config_->compatible) {
      // write type meta inline using streaming protocol
      FORY_RETURN_NOT_OK(write_type_meta(type_id));
    } else {
      // write pre-encoded namespace and type_name
      if (type_info->encoded_namespace && type_info->encoded_type_name) {
        write_encoded_meta_string(buffer_, *type_info->encoded_namespace);
        write_encoded_meta_string(buffer_, *type_info->encoded_type_name);
      } else {
        return Unexpected(
            Error::invalid("Encoded meta strings not initialized for struct"));
      }
    }
    break;
  default:
    // STRUCT type - just writing type_id is sufficient
    break;
  }

  return Result<void, Error>();
}

Result<void, Error>
WriteContext::write_struct_type_info(const TypeInfo *type_info) {
  uint32_t fory_type_id = type_info->type_id;

  // write type_id
  buffer_.write_uint8(static_cast<uint8_t>(fory_type_id));
  switch (static_cast<TypeId>(fory_type_id)) {
  case TypeId::ENUM:
  case TypeId::STRUCT:
  case TypeId::EXT:
  case TypeId::TYPED_UNION:
    buffer_.write_var_uint32(type_info->user_type_id);
    break;
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
    // write type meta inline using streaming protocol
    write_type_meta(type_info);
    break;
  case TypeId::NAMED_STRUCT:
    if (config_->compatible) {
      // write type meta inline using streaming protocol
      write_type_meta(type_info);
    } else {
      // write pre-encoded namespace and type_name
      if (type_info->encoded_namespace && type_info->encoded_type_name) {
        write_encoded_meta_string(buffer_, *type_info->encoded_namespace);
        write_encoded_meta_string(buffer_, *type_info->encoded_type_name);
      } else {
        return Unexpected(
            Error::invalid("Encoded meta strings not initialized for struct"));
      }
    }
    break;
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
  first_type_info_ = nullptr;
  has_first_type_info_ = false;
  type_info_index_map_active_ = false;
  current_dyn_depth_ = 0;
  // reset buffer indices for reuse - no memory operations needed
  buffer_.writer_index(0);
  buffer_.reader_index(0);
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
static const MetaStringDecoder k_namespace_decoder('.', '_');
static const MetaStringDecoder k_type_name_decoder('$', '_');

Result<const TypeInfo *, Error>
ReadContext::read_enum_type_info(const std::type_index &type,
                                 uint32_t base_type_id) {
  (void)type;
  return read_enum_type_info(base_type_id);
}

Result<const TypeInfo *, Error>
ReadContext::read_enum_type_info(uint32_t base_type_id) {
  Error error;
  uint32_t type_id = buffer_->read_uint8(error);
  if (type_id == static_cast<uint32_t>(TypeId::ENUM)) {
    uint32_t user_type_id = buffer_->read_var_uint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    FORY_TRY(type_info,
             type_resolver_->get_user_type_info_by_id(type_id, user_type_id));
    return type_info;
  } else if (type_id == static_cast<uint32_t>(TypeId::NAMED_ENUM)) {
    if (config_->compatible) {
      // Read type meta inline using streaming protocol
      return read_type_meta();
    }
    FORY_TRY(namespace_str,
             meta_string_table_.read_string(*buffer_, k_namespace_decoder));
    FORY_TRY(type_name,
             meta_string_table_.read_string(*buffer_, k_type_name_decoder));
    FORY_TRY(type_info,
             type_resolver_->get_type_info_by_name(namespace_str, type_name));
    return type_info;
  }

  return Unexpected(Error::type_mismatch(type_id, base_type_id));
}

// Maximum number of parsed type defs to cache (avoid OOM from malicious input)
static constexpr size_t k_max_parsed_num_type_defs = 8192;

Result<const TypeInfo *, Error> ReadContext::read_type_meta() {
  Error error;
  // Read the index marker
  uint32_t index_marker = buffer_->read_var_uint32(error);
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
  int64_t meta_header = buffer_->read_int64(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  // Check if we already parsed this type meta (cache lookup by header)
  if (has_last_meta_header_ && meta_header == last_meta_header_) {
    // Fast path: same header as last parsed
    const TypeInfo *cached = last_meta_type_info_;
    reading_type_infos_.push_back(cached);
    if (cached && !cached->type_def.empty()) {
      const size_t type_def_size = cached->type_def.size();
      if (type_def_size >= sizeof(int64_t) &&
          type_def_size <= std::numeric_limits<uint32_t>::max()) {
        Error skip_error;
        buffer_->skip(static_cast<uint32_t>(type_def_size - sizeof(int64_t)),
                      skip_error);
        if (FORY_PREDICT_FALSE(!skip_error.ok())) {
          return Unexpected(std::move(skip_error));
        }
        return cached;
      }
    }
    FORY_RETURN_NOT_OK(TypeMeta::skip_bytes(*buffer_, meta_header));
    return cached;
  }

  auto cache_it = parsed_type_infos_.find(meta_header);
  if (cache_it != parsed_type_infos_.end()) {
    // Found in cache - reuse and skip the bytes
    const TypeInfo *cached = cache_it->second;
    reading_type_infos_.push_back(cached);
    has_last_meta_header_ = true;
    last_meta_header_ = meta_header;
    last_meta_type_info_ = cached;
    if (cached && !cached->type_def.empty()) {
      const size_t type_def_size = cached->type_def.size();
      if (type_def_size >= sizeof(int64_t) &&
          type_def_size <= std::numeric_limits<uint32_t>::max()) {
        Error skip_error;
        buffer_->skip(static_cast<uint32_t>(type_def_size - sizeof(int64_t)),
                      skip_error);
        if (FORY_PREDICT_FALSE(!skip_error.ok())) {
          return Unexpected(std::move(skip_error));
        }
        return cached;
      }
    }
    FORY_RETURN_NOT_OK(TypeMeta::skip_bytes(*buffer_, meta_header));
    return cached;
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
    if (parsed_meta->user_type_id != kInvalidUserTypeId) {
      auto result = type_resolver_->get_user_type_info_by_id(
          parsed_meta->type_id, parsed_meta->user_type_id);
      if (result.ok()) {
        local_type_info = result.value();
      }
    } else {
      auto result = type_resolver_->get_type_info_by_id(parsed_meta->type_id);
      if (result.ok()) {
        local_type_info = result.value();
      }
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
    type_info->user_type_id = local_type_info->user_type_id;
    type_info->type_meta = std::move(parsed_meta);
    type_info->type_def = local_type_info->type_def;
    // CRITICAL: copy the harness from the registered type_info
    type_info->harness = local_type_info->harness;
    type_info->name_to_index = local_type_info->name_to_index;
    type_info->namespace_name = local_type_info->namespace_name;
    type_info->type_name = local_type_info->type_name;
    type_info->register_by_name = local_type_info->register_by_name;
  } else {
    // No local type - create stub TypeInfo with parsed meta
    type_info->type_id = parsed_meta->type_id;
    type_info->user_type_id = parsed_meta->user_type_id;
    type_info->type_meta = std::move(parsed_meta);
  }

  // get raw pointer before moving into storage
  const TypeInfo *raw_ptr = type_info.get();

  // Store in primary storage
  if (parsed_type_infos_.size() < k_max_parsed_num_type_defs) {
    cached_type_infos_.push_back(std::move(type_info));
    raw_ptr = cached_type_infos_.back().get();
    parsed_type_infos_[meta_header] = raw_ptr;
    has_last_meta_header_ = true;
    last_meta_header_ = meta_header;
    last_meta_type_info_ = raw_ptr;
  } else {
    owned_reading_type_infos_.push_back(std::move(type_info));
    raw_ptr = owned_reading_type_infos_.back().get();
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

Result<const TypeInfo *, Error> ReadContext::read_any_type_info() {
  Error error;
  uint32_t type_id = buffer_->read_uint8(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::ENUM:
  case TypeId::STRUCT:
  case TypeId::EXT:
  case TypeId::TYPED_UNION: {
    uint32_t user_type_id = buffer_->read_var_uint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    FORY_TRY(type_info,
             type_resolver_->get_user_type_info_by_id(type_id, user_type_id));
    return type_info;
  }
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
    // Read type meta inline using streaming protocol
    return read_type_meta();
  case TypeId::NAMED_ENUM:
  case TypeId::NAMED_EXT:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_UNION: {
    if (config_->compatible) {
      // Read type meta inline using streaming protocol
      return read_type_meta();
    }
    FORY_TRY(namespace_str,
             meta_string_table_.read_string(*buffer_, k_namespace_decoder));
    FORY_TRY(type_name,
             meta_string_table_.read_string(*buffer_, k_type_name_decoder));
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

const TypeInfo *ReadContext::read_any_type_info(Error &error) {
  auto result = read_any_type_info();
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
  owned_reading_type_infos_.clear();
  current_dyn_depth_ = 0;
  meta_string_table_.reset();
}

} // namespace serialization
} // namespace fory
