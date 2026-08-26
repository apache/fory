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
#include <algorithm>
#include <cstring>

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

static constexpr uint32_t k_type_meta_hash_shift = 12;

FORY_ALWAYS_INLINE int64_t type_meta_header_hash(int64_t header) {
  return static_cast<int64_t>(static_cast<uint64_t>(header) >>
                              k_type_meta_hash_shift);
}

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
  if (first_type_info_ == nullptr) {
    first_type_info_ = type_info;
    buffer_.write_uint8(0); // (index << 1), index=0
    buffer_.write_bytes(type_info->type_def.data(), type_info->type_def.size());
    return;
  }
  if (type_info == first_type_info_) {
    buffer_.write_uint8(1); // (index << 1) | 1, index=0
    return;
  }

  const uint64_t key =
      static_cast<uint64_t>(reinterpret_cast<uintptr_t>(type_info));
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
  uint32_t index = static_cast<uint32_t>(write_type_info_index_map_.size() + 1);
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
  if (config_->track_ref) {
    ref_writer_.reset();
  }
  // Clear meta map for streaming TypeMeta.
  if (!write_type_info_index_map_.empty()) {
    write_type_info_index_map_.clear();
  }
  first_type_info_ = nullptr;
  current_dyn_depth_ = 0;
  buffer_.clear_output_stream();
  output_stream_ = nullptr;
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
      type_resolver_(std::move(type_resolver)), current_dyn_depth_(0) {
  FORY_CHECK(config.max_graph_memory_bytes > 0)
      << "max_graph_memory_bytes must be positive";
  FORY_CHECK(config.max_unbacked_container_items >= 0)
      << "max_unbacked_container_items must be non-negative";
}

ReadContext::~ReadContext() = default;

// Static decoders for NAMED_ENUM namespace/type_name - shared across calls
static const MetaStringDecoder k_namespace_decoder('.', '_');
static const MetaStringDecoder k_type_name_decoder('$', '_');

Result<const TypeInfo *, Error>
ReadContext::read_enum_type_info(const std::type_index &type,
                                 uint32_t base_type_id) {
  FORY_TRY(expected_type_info, type_resolver_->get_type_info(type));
  return read_enum_type_info_owner(expected_type_info, base_type_id);
}

Result<const TypeInfo *, Error>
ReadContext::read_enum_type_info(uint32_t base_type_id) {
  return read_enum_type_info_owner(nullptr, base_type_id);
}

Result<const TypeInfo *, Error>
ReadContext::read_enum_type_info_owner(const TypeInfo *expected_type_info,
                                       uint32_t base_type_id) {
  FORY_TRY(type_info, read_any_type_info_owner(expected_type_info));
  const bool matches_enum =
      base_type_id == static_cast<uint32_t>(TypeId::ENUM) &&
      (type_info->type_id == static_cast<uint32_t>(TypeId::ENUM) ||
       type_info->type_id == static_cast<uint32_t>(TypeId::NAMED_ENUM));
  if (FORY_PREDICT_FALSE(!matches_enum &&
                         !type_id_matches(type_info->type_id, base_type_id))) {
    return Unexpected(Error::type_mismatch(type_info->type_id, base_type_id));
  }
  return type_info;
}

static constexpr uint64_t k_min_remote_type_meta_limit = 8192;
static constexpr uint64_t k_max_remote_type_meta_keys = 8192;

Result<std::string, Error>
ReadContext::check_remote_type_meta_limit(const TypeMeta &type_meta) {
  std::string key;
  if (type_meta.register_by_name) {
    key.reserve(type_meta.namespace_str.size() + type_meta.type_name.size() +
                2);
    key.push_back('n');
    key.append(type_meta.namespace_str);
    key.push_back('\0');
    key.append(type_meta.type_name);
  } else {
    key = "i" + std::to_string(type_meta.user_type_id);
  }

  auto *entry = remote_schema_versions_by_type_.find(key);
  if (FORY_PREDICT_FALSE(
          entry == nullptr &&
          static_cast<uint64_t>(remote_schema_versions_by_type_.size()) >=
              k_max_remote_type_meta_keys)) {
    return Unexpected(Error::invalid_data(
        "Remote TypeMeta logical type limit 8192 exceeded"));
  }

  const uint32_t versions_for_type = entry == nullptr ? 0 : entry->second;
  if (FORY_PREDICT_FALSE(versions_for_type >=
                         config_->max_schema_versions_per_type)) {
    return Unexpected(Error::invalid_data(
        "Remote schema version limit exceeded for one type. The data may be "
        "malicious. If the data is not malicious, please increase "
        "max_schema_versions_per_type=" +
        std::to_string(config_->max_schema_versions_per_type)));
  }

  const uint64_t accepted_type_count =
      static_cast<uint64_t>(remote_schema_versions_by_type_.size()) +
      (entry == nullptr ? 1 : 0);
  const uint64_t max_average = config_->max_average_schema_versions_per_type;
  if (FORY_PREDICT_FALSE(
          total_accepted_schema_versions_ >= k_min_remote_type_meta_limit &&
          total_accepted_schema_versions_ / accepted_type_count >=
              max_average)) {
    return Unexpected(Error::invalid_data(
        "Remote schema version limit exceeded globally. The data may be "
        "malicious. If the data is not malicious, please increase "
        "max_average_schema_versions_per_type=" +
        std::to_string(config_->max_average_schema_versions_per_type)));
  }

  return key;
}

void ReadContext::record_remote_type_meta(const std::string &type_key) {
  auto *entry = remote_schema_versions_by_type_.find(type_key);
  if (entry == nullptr) {
    remote_schema_versions_by_type_[type_key] = 1;
  } else {
    ++entry->second;
  }
  ++total_accepted_schema_versions_;
}

namespace {

FORY_ALWAYS_INLINE bool has_local_meta_hash(const TypeInfo *type_info,
                                            int64_t meta_hash) {
  if (type_info == nullptr) {
    return false;
  }
  if (type_info->type_meta != nullptr) {
    return type_info->type_meta->hash == meta_hash;
  }
  if (type_info->type_def.size() < sizeof(int64_t)) {
    return false;
  }
  int64_t local_header = 0;
  std::memcpy(&local_header, type_info->type_def.data(), sizeof(local_header));
  return type_meta_header_hash(local_header) == meta_hash;
}

FORY_NOINLINE Result<const TypeInfo *, Error> unexpected_type_meta_owner() {
  return Unexpected(Error::type_error(
      "Remote type metadata does not match the declared C++ type"));
}

FORY_ALWAYS_INLINE Result<const TypeInfo *, Error>
require_expected_type(const TypeInfo *type_info,
                      const TypeInfo *expected_type_info) {
  if (FORY_PREDICT_FALSE(expected_type_info != nullptr &&
                         type_info != expected_type_info)) {
    return unexpected_type_meta_owner();
  }
  return type_info;
}

} // namespace

Result<const TypeInfo *, Error> ReadContext::read_type_meta() {
  return read_type_meta_owner(nullptr);
}

bool ReadContext::matches_expected_type(const TypeInfo *concrete_owner,
                                        const TypeInfo *expected_type_info) {
  return expected_type_info == nullptr || concrete_owner == expected_type_info;
}

Result<const TypeInfo *, Error>
ReadContext::read_type_meta_owner(const TypeInfo *expected_type_info) {
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
    if (FORY_PREDICT_FALSE(index >= reading_type_infos_.size())) {
      return Unexpected(Error::invalid(
          "Meta index out of bounds: " + std::to_string(index) +
          ", size: " + std::to_string(reading_type_infos_.size())));
    }
    const ReadTypeInfo &entry = reading_type_infos_[index];
    if (FORY_PREDICT_FALSE(
            !matches_expected_type(entry.concrete_owner, expected_type_info))) {
      return unexpected_type_meta_owner();
    }
    return entry.type_info;
  }

  // New type - read TypeMeta inline
  // Read the 8-byte header first for caching
  int64_t meta_header = buffer_->read_int64(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }
  const int64_t meta_hash = type_meta_header_hash(meta_header);

  // The top 52 header bits are the protocol TypeMeta identity. The current
  // frame's low bits only describe how to skip this frame's opaque body.
  if (has_local_meta_hash(expected_type_info, meta_hash)) {
    FORY_RETURN_NOT_OK(
        TypeMeta::skip_bytes_for_validated_header(*buffer_, meta_header));
    reading_type_infos_.push_back(
        ReadTypeInfo{expected_type_info, expected_type_info});
    return expected_type_info;
  }

  if (cached_meta_type_info_ != nullptr &&
      cached_meta_type_info_->type_info.type_meta->hash == meta_hash) {
    // Header-cache hits intentionally skip without rehashing. Entries reach
    // this cache only after a successful TypeMeta parse and 52-bit
    // metadata-hash validation. Do not add body/hash/schema-limit/exact-local
    // checks here; the miss path owns them before publish.
    const CachedTypeInfo *cached = cached_meta_type_info_;
    if (FORY_PREDICT_FALSE(!matches_expected_type(cached->concrete_owner,
                                                  expected_type_info))) {
      return unexpected_type_meta_owner();
    }
    FORY_RETURN_NOT_OK(
        TypeMeta::skip_bytes_for_validated_header(*buffer_, meta_header));
    const TypeInfo *type_info = &cached->type_info;
    reading_type_infos_.push_back(
        ReadTypeInfo{type_info, cached->concrete_owner});
    return type_info;
  }

  auto *cache_entry = parsed_type_infos_.find(meta_hash);
  if (cache_entry != nullptr) {
    // Header-cache hits intentionally skip without rehashing. Entries reach
    // this cache only after a successful TypeMeta parse and 52-bit
    // metadata-hash validation. Do not add body/hash/schema-limit/exact-local
    // checks here; the miss path owns them before publish.
    const CachedTypeInfo *cached = cache_entry->second;
    if (FORY_PREDICT_FALSE(!matches_expected_type(cached->concrete_owner,
                                                  expected_type_info))) {
      return unexpected_type_meta_owner();
    }
    FORY_RETURN_NOT_OK(
        TypeMeta::skip_bytes_for_validated_header(*buffer_, meta_header));
    cached_meta_type_info_ = cached;
    const TypeInfo *type_info = &cached->type_info;
    reading_type_infos_.push_back(
        ReadTypeInfo{type_info, cached->concrete_owner});
    return type_info;
  }

  // Not in cache - parse the TypeMeta
  FORY_TRY(parsed_meta, TypeMeta::from_bytes_with_header(
                            *buffer_, meta_header, config_->max_type_fields,
                            config_->max_type_meta_bytes));

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

  if (expected_type_info != nullptr &&
      !matches_expected_type(local_type_info, expected_type_info)) {
    return unexpected_type_meta_owner();
  }

  if (local_type_info) {
    if (has_local_meta_hash(local_type_info, meta_hash)) {
      reading_type_infos_.push_back(
          ReadTypeInfo{local_type_info, local_type_info});
      return local_type_info;
    }
  }

  FORY_TRY(remote_schema_key, check_remote_type_meta_limit(*parsed_meta));

  // Create TypeInfo with local compatible dispatch IDs assigned.
  auto cached = std::make_unique<CachedTypeInfo>();
  TypeInfo *type_info = &cached->type_info;
  cached->concrete_owner = local_type_info;
  if (local_type_info) {
    // Have local type - assign dispatch IDs by comparing schemas.
    // Note: Extension types don't have type_meta (only structs do)
    if (local_type_info->type_meta) {
      FORY_RETURN_NOT_OK(TypeMeta::assign_local_dispatch_ids(
          local_type_info->type_meta.get(), parsed_meta->field_infos));
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

  cached_type_infos_.push_back(std::move(cached));
  const CachedTypeInfo *cached_ptr = cached_type_infos_.back().get();
  const TypeInfo *raw_ptr = &cached_ptr->type_info;
  parsed_type_infos_[meta_hash] = cached_ptr;
  cached_meta_type_info_ = cached_ptr;
  record_remote_type_meta(remote_schema_key);

  reading_type_infos_.push_back(
      ReadTypeInfo{raw_ptr, cached_ptr->concrete_owner});
  return raw_ptr;
}

Result<const TypeInfo *, Error>
ReadContext::get_type_info_by_index(size_t index) const {
  if (index >= reading_type_infos_.size()) {
    return Unexpected(Error::invalid(
        "Meta index out of bounds: " + std::to_string(index) +
        ", size: " + std::to_string(reading_type_infos_.size())));
  }
  return reading_type_infos_[index].type_info;
}

Result<const TypeInfo *, Error> ReadContext::read_any_type_info() {
  return read_any_type_info_owner(nullptr);
}

Result<const TypeInfo *, Error>
ReadContext::read_any_type_info_owner(const TypeInfo *expected_type_info) {
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
    return require_expected_type(type_info, expected_type_info);
  }
  case TypeId::COMPATIBLE_STRUCT:
  case TypeId::NAMED_COMPATIBLE_STRUCT:
    // Read type meta inline using streaming protocol
    return read_type_meta_owner(expected_type_info);
  case TypeId::NAMED_ENUM:
  case TypeId::NAMED_EXT:
  case TypeId::NAMED_STRUCT:
  case TypeId::NAMED_UNION: {
    if (config_->compatible) {
      // Read type meta inline using streaming protocol
      return read_type_meta_owner(expected_type_info);
    }
    meta_string_table_active_ = true;
    FORY_TRY(namespace_str,
             meta_string_table_.read_string(*buffer_, k_namespace_decoder));
    FORY_TRY(type_name,
             meta_string_table_.read_string(*buffer_, k_type_name_decoder));
    FORY_TRY(type_info,
             type_resolver_->get_type_info_by_name(namespace_str, type_name));
    return require_expected_type(type_info, expected_type_info);
  }
  default: {
    // All types must be registered in type_resolver
    FORY_TRY(type_info, type_resolver_->get_type_info_by_id(type_id));
    return require_expected_type(type_info, expected_type_info);
  }
  }
}

const TypeInfo *ReadContext::read_any_type_info(Error &error) {
  return read_any_type_info_owner(error, nullptr);
}

const TypeInfo *
ReadContext::read_any_type_info_owner(Error &error,
                                      const TypeInfo *expected_type_info) {
  auto result = read_any_type_info_owner(expected_type_info);
  if (!result.ok()) {
    error = std::move(result).error();
    return nullptr;
  }
  return result.value();
}

bool ReadContext::set_graph_memory_exceeded(size_t bytes, size_t remaining) {
  set_error(Error::invalid_data(
      "estimated graph memory request " + std::to_string(bytes) +
      " bytes exceeds max_graph_memory_bytes remaining budget " +
      std::to_string(remaining) + " bytes"));
  return false;
}

bool ReadContext::set_unbacked_container_items_exceeded(size_t items,
                                                        size_t remaining) {
  set_error(Error::invalid_data(
      "container read work request " + std::to_string(items) +
      " items exceeds max_unbacked_container_items remaining budget " +
      std::to_string(remaining) + " items"));
  return false;
}

void ReadContext::reset() {
  // Clear error state first
  error_ = Error();
  // Wire-level skip paths can reserve reference slots even when local
  // reference tracking is disabled, so every root must clear this state.
  ref_reader_.reset();
  reading_type_infos_.clear();
  current_dyn_depth_ = 0;
  remaining_unbacked_container_items_ = 0;
  // Root deserialization overwrites the remaining graph budget before any
  // serializer can reserve, so reset avoids an extra hot cleanup store here.
  if (meta_string_table_active_) {
    meta_string_table_.reset();
    meta_string_table_active_ = false;
  }
}

} // namespace serialization
} // namespace fory
