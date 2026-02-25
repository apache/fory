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

#include "fory/serialization/serializer.h"
#include <cstdint>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <unordered_map>

namespace fory {
namespace serialization {

// ============================================================================
// Map KV Header Constants
// ============================================================================

/// Maximum number of key-value pairs in a single chunk
constexpr uint8_t MAX_CHUNK_SIZE = 255;

/// Bit flags for map key-value header
constexpr uint8_t TRACKING_KEY_REF = 0b000001;
constexpr uint8_t KEY_NULL = 0b000010;
constexpr uint8_t DECL_KEY_TYPE = 0b000100;
constexpr uint8_t TRACKING_VALUE_REF = 0b001000;
constexpr uint8_t VALUE_NULL = 0b010000;
constexpr uint8_t DECL_VALUE_TYPE = 0b100000;

// ============================================================================
// Type Info Methods
// ============================================================================

/// write type info for a type to buffer.
template <typename T> inline void write_type_info(WriteContext &ctx) {
  Serializer<T>::write_type_info(ctx);
}

/// Read and validate type info for a type from buffer.
template <typename T> inline void read_type_info(ReadContext &ctx) {
  Serializer<T>::read_type_info(ctx);
}

/// Read polymorphic type info from buffer. Returns nullptr on error.
inline const TypeInfo *read_polymorphic_type_info(ReadContext &ctx) {
  return ctx.read_any_type_info(ctx.error());
}

// ============================================================================
// Helper Functions for Map Serialization
// ============================================================================

/// Helper to reserve capacity if the container supports it
template <typename MapType, typename = void> struct MapReserver {
  static void reserve(MapType &map, uint32_t size) {
    // No-op for containers without reserve (like std::map)
  }
};

template <typename MapType>
struct MapReserver<MapType,
                   std::void_t<decltype(std::declval<MapType>().reserve(0))>> {
  static void reserve(MapType &map, uint32_t size) { map.reserve(size); }
};

/// write chunk size at header offset
inline void write_chunk_size(WriteContext &ctx, size_t header_offset,
                             uint8_t size) {
  // header_offset points to the header byte, size is at offset + 1
  ctx.buffer().unsafe_put_byte(header_offset + 1, size);
}

/// Check if we need to write type info for a field type
/// Keep as constexpr for compile time evaluation or constant folding
template <typename T> inline constexpr bool need_to_write_type_for_field() {
  // This matches the Rust implementation's need_to_write_type_for_field
  // Note: Rust includes UNKNOWN, but C++ uses BOUND as a sentinel and doesn't
  // have an UNKNOWN type, so we only check for STRUCT and EXT variants
  constexpr TypeId tid = Serializer<T>::type_id;
  return tid == TypeId::STRUCT || tid == TypeId::COMPATIBLE_STRUCT ||
         tid == TypeId::NAMED_STRUCT ||
         tid == TypeId::NAMED_COMPATIBLE_STRUCT || tid == TypeId::EXT ||
         tid == TypeId::NAMED_EXT;
}

// ============================================================================
// Map Data Writing - Fast Path (Non-Polymorphic)
// ============================================================================

/// write map data for non-polymorphic, non-shared-ref maps
/// This is the optimized fast path for common cases like map<string, int>
template <typename K, typename V, typename MapType>
inline void write_map_data_fast(const MapType &map, WriteContext &ctx,
                                bool has_generics) {
  static_assert(!is_polymorphic_v<K> && !is_polymorphic_v<V>,
                "Fast path is for non-polymorphic types only");
  static_assert(!is_shared_ref_v<K> && !is_shared_ref_v<V>,
                "Fast path is for non-shared-ref types only");

  // write total length
  ctx.write_var_uint32(static_cast<uint32_t>(map.size()));

  if (map.empty()) {
    return;
  }

  // Determine if keys/values are declared types (no type info needed)
  const bool is_key_declared =
      has_generics && !need_to_write_type_for_field<K>();
  const bool is_val_declared =
      has_generics && !need_to_write_type_for_field<V>();

  // State for chunked writing
  size_t header_offset = 0;
  uint8_t pair_counter = 0;
  bool need_write_header = true;

  for (const auto &[key, value] : map) {
    // For fast path, we assume no null values (primitives/strings)
    // If nullability is needed, use the slow path

    if (need_write_header) {
      // reserve space for header (1 byte) + chunk size (1 byte)
      header_offset = ctx.buffer().writer_index();
      ctx.write_uint16(0); // Placeholder for header and chunk size
      uint8_t chunk_header = 0;
      if (is_key_declared) {
        chunk_header |= DECL_KEY_TYPE;
      } else {
        write_type_info<K>(ctx);
      }
      if (is_val_declared) {
        chunk_header |= DECL_VALUE_TYPE;
      } else {
        write_type_info<V>(ctx);
      }

      // write chunk header at reserved position
      ctx.buffer().unsafe_put_byte(header_offset, chunk_header);
      need_write_header = false;
    }

    // write key and value data
    if (has_generics && is_generic_type_v<K>) {
      Serializer<K>::write_data_generic(key, ctx, true);
    } else {
      Serializer<K>::write_data(key, ctx);
    }

    if (has_generics && is_generic_type_v<V>) {
      Serializer<V>::write_data_generic(value, ctx, true);
    } else {
      Serializer<V>::write_data(value, ctx);
    }

    pair_counter++;
    if (pair_counter == MAX_CHUNK_SIZE) {
      write_chunk_size(ctx, header_offset, pair_counter);
      pair_counter = 0;
      need_write_header = true;
    }
  }

  // write final chunk size
  if (pair_counter > 0) {
    write_chunk_size(ctx, header_offset, pair_counter);
  }
}

// ============================================================================
// Map Data Writing - Slow Path (Polymorphic/Shared-Ref)
// ============================================================================

/// write map data for polymorphic or shared-ref maps
/// This is the versatile slow path that handles all edge cases
template <typename K, typename V, typename MapType>
inline void write_map_data_slow(const MapType &map, WriteContext &ctx,
                                bool has_generics) {
  // write total length
  ctx.write_var_uint32(static_cast<uint32_t>(map.size()));

  if (map.empty()) {
    return;
  }

  // Type characteristics
  constexpr bool key_is_polymorphic = is_polymorphic_v<K>;
  constexpr bool val_is_polymorphic = is_polymorphic_v<V>;
  constexpr bool key_is_shared_ref = is_shared_ref_v<K>;
  constexpr bool val_is_shared_ref = is_shared_ref_v<V>;

  const bool is_key_declared =
      has_generics && !need_to_write_type_for_field<K>();
  const bool is_val_declared =
      has_generics && !need_to_write_type_for_field<V>();

  // State for chunked writing
  size_t header_offset = 0;
  uint8_t pair_counter = 0;
  bool need_write_header = true;

  // Track current chunk's types for polymorphic handling
  const TypeInfo *current_key_type_info = nullptr;
  const TypeInfo *current_val_type_info = nullptr;

  for (const auto &[key, value] : map) {
    // Check if key or value is null (for nullable types: optional, shared_ptr,
    // unique_ptr, weak_ptr)
    bool key_is_none = false;
    bool value_is_none = false;
    if constexpr (is_nullable_v<K>) {
      key_is_none = is_null_value(key);
    }
    if constexpr (is_nullable_v<V>) {
      value_is_none = is_null_value(value);
    }

    // Handle null entries - write as separate single-entry chunks
    if (key_is_none || value_is_none) {
      // Finish current chunk if any
      if (pair_counter > 0) {
        write_chunk_size(ctx, header_offset, pair_counter);
        pair_counter = 0;
        need_write_header = true;
      }

      if (key_is_none && value_is_none) {
        ctx.write_uint8(KEY_NULL | VALUE_NULL);
        continue;
      } else if (value_is_none) {
        // Non-null key, null value
        // Java writes: chunk_header, then ref_flag, then type_info, then data
        uint8_t chunk_header = VALUE_NULL;
        // Only track refs for shared_ptr types when global ref tracking enabled
        bool write_ref = key_is_shared_ref && ctx.track_ref();
        if (write_ref) {
          chunk_header |= TRACKING_KEY_REF;
        }
        if (is_key_declared && !key_is_polymorphic) {
          chunk_header |= DECL_KEY_TYPE;
        }
        ctx.write_uint8(chunk_header);

        // write ref flag first if tracking refs
        if (write_ref) {
          write_not_null_ref_flag(ctx, RefMode::NullOnly);
        }

        // Then write type info if not declared
        if (!(chunk_header & DECL_KEY_TYPE)) {
          if constexpr (key_is_polymorphic) {
            auto concrete_type_id = get_concrete_type_id(key);
            if (concrete_type_id ==
                std::type_index(typeid(std::shared_ptr<void>))) {
              ctx.set_error(Error::type_error(
                  "Polymorphic key shared_ptr must not point to void"));
              return;
            }
            auto type_info_res =
                ctx.type_resolver().get_type_info(concrete_type_id);
            if (!type_info_res.ok()) {
              ctx.set_error(std::move(type_info_res).error());
              return;
            }
            auto res = ctx.write_any_type_info(type_info_res.value()->type_id,
                                               concrete_type_id);
            if (!res.ok()) {
              ctx.set_error(std::move(res).error());
              return;
            }
          } else {
            write_type_info<K>(ctx);
          }
        }

        // write key data (ref flag and type info already written)
        if (has_generics && is_generic_type_v<K>) {
          Serializer<K>::write_data_generic(key, ctx, has_generics);
        } else {
          Serializer<K>::write_data(key, ctx);
        }
        continue;
      } else {
        // key_is_none
        // Java writes: chunk_header, then ref_flag, then type_info, then data
        uint8_t chunk_header = KEY_NULL;
        // Only track refs for shared_ptr types when global ref tracking enabled
        bool write_ref = val_is_shared_ref && ctx.track_ref();
        if (write_ref) {
          chunk_header |= TRACKING_VALUE_REF;
        }
        if (is_val_declared && !val_is_polymorphic) {
          chunk_header |= DECL_VALUE_TYPE;
        }
        ctx.write_uint8(chunk_header);

        // write ref flag first if tracking refs
        if (write_ref) {
          write_not_null_ref_flag(ctx, RefMode::NullOnly);
        }

        // Then write type info if not declared
        if (!(chunk_header & DECL_VALUE_TYPE)) {
          if constexpr (val_is_polymorphic) {
            auto concrete_type_id = get_concrete_type_id(value);
            if (concrete_type_id ==
                std::type_index(typeid(std::shared_ptr<void>))) {
              ctx.set_error(Error::type_error(
                  "Polymorphic value shared_ptr must not point to void"));
              return;
            }
            auto type_info_res =
                ctx.type_resolver().get_type_info(concrete_type_id);
            if (!type_info_res.ok()) {
              ctx.set_error(std::move(type_info_res).error());
              return;
            }
            auto res = ctx.write_any_type_info(type_info_res.value()->type_id,
                                               concrete_type_id);
            if (!res.ok()) {
              ctx.set_error(std::move(res).error());
              return;
            }
          } else {
            write_type_info<V>(ctx);
          }
        }

        // write value data (ref flag and type info already written)
        if (has_generics && is_generic_type_v<V>) {
          Serializer<V>::write_data_generic(value, ctx, has_generics);
        } else {
          Serializer<V>::write_data(value, ctx);
        }
        continue;
      }
    }

    // get type IDs for polymorphic types
    const TypeInfo *key_type_info = nullptr;
    const TypeInfo *val_type_info = nullptr;
    if constexpr (key_is_polymorphic) {
      auto concrete_type_id = get_concrete_type_id(key);
      auto key_type_info_res =
          ctx.type_resolver().get_type_info(concrete_type_id);
      if (!key_type_info_res.ok()) {
        ctx.set_error(std::move(key_type_info_res).error());
        return;
      }
      key_type_info = key_type_info_res.value();
    }
    if constexpr (val_is_polymorphic) {
      auto concrete_type_id = get_concrete_type_id(value);
      auto val_type_info_res =
          ctx.type_resolver().get_type_info(concrete_type_id);
      if (!val_type_info_res.ok()) {
        ctx.set_error(std::move(val_type_info_res).error());
        return;
      }
      val_type_info = val_type_info_res.value();
    }

    // Check if we need to start a new chunk due to type changes
    bool types_changed = false;
    if constexpr (key_is_polymorphic || val_is_polymorphic) {
      if constexpr (key_is_polymorphic) {
        types_changed = key_type_info != current_key_type_info;
      }
      if constexpr (val_is_polymorphic) {
        types_changed =
            types_changed || (val_type_info != current_val_type_info);
      }
    }

    if (need_write_header || types_changed) {
      // Finish previous chunk if types changed
      if (types_changed && pair_counter > 0) {
        write_chunk_size(ctx, header_offset, pair_counter);
        pair_counter = 0;
      }

      // write new chunk header
      header_offset = ctx.buffer().writer_index();
      ctx.write_uint16(0); // Placeholder for header and chunk size

      uint8_t chunk_header = 0;
      // Set key flags - only track refs for shared_ptr when global ref tracking
      // enabled
      if (key_is_shared_ref && ctx.track_ref()) {
        chunk_header |= TRACKING_KEY_REF;
      }
      if (is_key_declared && !key_is_polymorphic) {
        chunk_header |= DECL_KEY_TYPE;
      }

      // Set value flags - only track refs for shared_ptr when global ref
      // tracking enabled
      if (val_is_shared_ref && ctx.track_ref()) {
        chunk_header |= TRACKING_VALUE_REF;
      }
      if (is_val_declared && !val_is_polymorphic) {
        chunk_header |= DECL_VALUE_TYPE;
      }

      // write chunk header at reserved position
      ctx.buffer().unsafe_put_byte(header_offset, chunk_header);

      // write type info if needed
      // Matches Rust: write type info here in map, then call serializer with
      // write_type=false
      if (!is_key_declared || key_is_polymorphic) {
        if constexpr (key_is_polymorphic) {
          auto concrete_type_id = get_concrete_type_id(key);
          if (FORY_PREDICT_FALSE(key_type_info == nullptr)) {
            auto type_info_res =
                ctx.type_resolver().get_type_info(concrete_type_id);
            if (!type_info_res.ok()) {
              ctx.set_error(std::move(type_info_res).error());
              return;
            }
            key_type_info = type_info_res.value();
          }
          auto res =
              ctx.write_any_type_info(key_type_info->type_id, concrete_type_id);
          if (!res.ok()) {
            ctx.set_error(std::move(res).error());
            return;
          }
        } else {
          write_type_info<K>(ctx);
        }
      }

      if (!is_val_declared || val_is_polymorphic) {
        if constexpr (val_is_polymorphic) {
          auto concrete_type_id = get_concrete_type_id(value);
          if (FORY_PREDICT_FALSE(val_type_info == nullptr)) {
            auto type_info_res =
                ctx.type_resolver().get_type_info(concrete_type_id);
            if (!type_info_res.ok()) {
              ctx.set_error(std::move(type_info_res).error());
              return;
            }
            val_type_info = type_info_res.value();
          }
          auto res =
              ctx.write_any_type_info(val_type_info->type_id, concrete_type_id);
          if (!res.ok()) {
            ctx.set_error(std::move(res).error());
            return;
          }
        } else {
          write_type_info<V>(ctx);
        }
      }

      need_write_header = false;
      current_key_type_info = key_type_info;
      current_val_type_info = val_type_info;
    }

    // write key-value pair
    // For shared_ptr with ref tracking: write ref flag + data
    // For other types: null cases already handled via KEY_NULL/VALUE_NULL,
    // so just write data directly
    if constexpr (key_is_shared_ref) {
      if (ctx.track_ref()) {
        Serializer<K>::write(key, ctx, RefMode::Tracking, false, has_generics);
      } else {
        Serializer<K>::write_data(key, ctx);
      }
    } else {
      if (has_generics && is_generic_type_v<K>) {
        Serializer<K>::write_data_generic(key, ctx, has_generics);
      } else {
        Serializer<K>::write_data(key, ctx);
      }
    }

    if constexpr (val_is_shared_ref) {
      if (ctx.track_ref()) {
        Serializer<V>::write(value, ctx, RefMode::Tracking, false,
                             has_generics);
      } else {
        Serializer<V>::write_data(value, ctx);
      }
    } else {
      if (has_generics && is_generic_type_v<V>) {
        Serializer<V>::write_data_generic(value, ctx, has_generics);
      } else {
        Serializer<V>::write_data(value, ctx);
      }
    }

    pair_counter++;
    if (pair_counter == MAX_CHUNK_SIZE) {
      write_chunk_size(ctx, header_offset, pair_counter);
      pair_counter = 0;
      need_write_header = true;
      current_key_type_info = nullptr;
      current_val_type_info = nullptr;
    }
  }

  // write final chunk size
  if (pair_counter > 0) {
    write_chunk_size(ctx, header_offset, pair_counter);
  }
}

// ============================================================================
// Map Data Reading - Fast Path (Non-Polymorphic)
// ============================================================================

/// Read map data for non-polymorphic, non-shared-ref maps
template <typename K, typename V, typename MapType>
inline MapType read_map_data_fast(ReadContext &ctx, uint32_t length) {
  static_assert(!is_polymorphic_v<K> && !is_polymorphic_v<V>,
                "Fast path is for non-polymorphic types only");
  static_assert(!is_shared_ref_v<K> && !is_shared_ref_v<V>,
                "Fast path is for non-shared-ref types only");

  MapType result;
  if(length > ctx.config().max_map_size) {
    ctx.set_error(Error::invalid_data);
    return result;
  }
  MapReserver<MapType>::reserve(result, length);

  if (length == 0) {
    return result;
  }

  uint32_t len_counter = 0;

  while (len_counter < length) {
    uint8_t header = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }

    // Handle null entries - insert with default-constructed key/value
    if ((header & KEY_NULL) && (header & VALUE_NULL)) {
      // Both null - insert with default values
      result.emplace(K{}, V{});
      len_counter++;
      continue;
    }
    if (header & KEY_NULL) {
      // Null key, non-null value
      // Java writes: header, then type info (if not declared), then value data
      bool value_declared = (header & DECL_VALUE_TYPE) != 0;
      bool track_value_ref = (header & TRACKING_VALUE_REF) != 0;

      // Read type info if not declared
      if (!value_declared) {
        read_type_info<V>(ctx);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      }

      // Read value - consume ref flag if tracking, then read data
      V value;
      if (track_value_ref) {
        value = Serializer<V>::read(ctx, RefMode::Tracking, false);
      } else {
        value = Serializer<V>::read_data(ctx);
      }
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return MapType{};
      }
      result.emplace(K{}, std::move(value));
      len_counter++;
      continue;
    }
    if (header & VALUE_NULL) {
      // Non-null key, null value
      // Java writes: header, then type info (if not declared), then key data
      bool key_declared = (header & DECL_KEY_TYPE) != 0;
      bool track_key_ref = (header & TRACKING_KEY_REF) != 0;

      // Read type info if not declared
      if (!key_declared) {
        read_type_info<K>(ctx);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      }

      // Read key - consume ref flag if tracking, then read data
      K key;
      if (track_key_ref) {
        key = Serializer<K>::read(ctx, RefMode::Tracking, false);
      } else {
        key = Serializer<K>::read_data(ctx);
      }
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return MapType{};
      }
      result.emplace(std::move(key), V{});
      len_counter++;
      continue;
    }

    // Read chunk size
    uint8_t chunk_size = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }

    // Read type info if not declared
    if (!(header & DECL_KEY_TYPE)) {
      read_type_info<K>(ctx);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return MapType{};
      }
    }
    if (!(header & DECL_VALUE_TYPE)) {
      read_type_info<V>(ctx);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return MapType{};
      }
    }

    uint32_t cur_len = len_counter + chunk_size;
    if (cur_len > length) {
      ctx.set_error(Error::invalid_data("Chunk size exceeds total map length"));
      return MapType{};
    }

    // Read chunk_size pairs
    for (uint8_t i = 0; i < chunk_size; ++i) {
      K key = Serializer<K>::read_data(ctx);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return MapType{};
      }
      V value = Serializer<V>::read_data(ctx);
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return MapType{};
      }
      result.emplace(std::move(key), std::move(value));
    }

    len_counter += chunk_size;
  }

  return result;
}

// ============================================================================
// Map Data Reading - Slow Path (Polymorphic/Shared-Ref)
// ============================================================================

/// Read map data for polymorphic or shared-ref maps
template <typename K, typename V, typename MapType>
inline MapType read_map_data_slow(ReadContext &ctx, uint32_t length) {
  MapType result;
  MapReserver<MapType>::reserve(result, length);

  if (length == 0) {
    return result;
  }

  constexpr bool key_is_polymorphic = is_polymorphic_v<K>;
  constexpr bool val_is_polymorphic = is_polymorphic_v<V>;
  constexpr bool key_is_shared_ref = is_shared_ref_v<K>;
  constexpr bool val_is_shared_ref = is_shared_ref_v<V>;

  uint32_t len_counter = 0;

  while (len_counter < length) {
    uint8_t header = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }

    // Handle null entries
    if ((header & KEY_NULL) && (header & VALUE_NULL)) {
      // Both key and value are null - insert with default-constructed values
      result.emplace(K{}, V{});
      len_counter++;
      continue;
    }

    if (header & KEY_NULL) {
      // Null key, non-null value
      // Java writes: chunk_header, then ref_flag, then type_info, then data
      bool track_value_ref = (header & TRACKING_VALUE_REF) != 0;
      bool value_declared = (header & DECL_VALUE_TYPE) != 0;

      // Consume ref flag first if tracking refs
      bool has_value = true;
      if (track_value_ref || val_is_shared_ref) {
        has_value = read_null_only_flag(ctx, RefMode::NullOnly);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      }

      if (!has_value) {
        // Value is null reference
        result.emplace(K{}, V{});
        len_counter++;
        continue;
      }

      // Now read type info if needed
      const TypeInfo *value_type_info = nullptr;
      if (!value_declared || val_is_polymorphic) {
        if constexpr (val_is_polymorphic) {
          value_type_info = read_polymorphic_type_info(ctx);
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            return MapType{};
          }
        } else {
          read_type_info<V>(ctx);
        }
      }

      // Read value data (ref flag already consumed above)
      V value;
      if constexpr (val_is_polymorphic) {
        // For polymorphic types, use read_with_type_info
        value = Serializer<V>::read_with_type_info(ctx, RefMode::None,
                                                   *value_type_info);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      } else {
        // Read data directly - ref flag already consumed
        value = Serializer<V>::read_data(ctx);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      }
      // Insert with default-constructed key and the read value
      result.emplace(K{}, std::move(value));
      len_counter++;
      continue;
    }

    if (header & VALUE_NULL) {
      // Non-null key, null value
      // Java writes: chunk_header, then ref_flag, then type_info, then data
      bool track_key_ref = (header & TRACKING_KEY_REF) != 0;
      bool key_declared = (header & DECL_KEY_TYPE) != 0;

      // Consume ref flag first if tracking refs
      bool has_key = true;
      if (track_key_ref || key_is_shared_ref) {
        has_key = read_null_only_flag(ctx, RefMode::NullOnly);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      }

      if (!has_key) {
        // Key is null reference
        result.emplace(K{}, V{});
        len_counter++;
        continue;
      }

      // Now read type info if needed
      const TypeInfo *key_type_info = nullptr;
      if (!key_declared || key_is_polymorphic) {
        if constexpr (key_is_polymorphic) {
          key_type_info = read_polymorphic_type_info(ctx);
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            return MapType{};
          }
        } else {
          read_type_info<K>(ctx);
        }
      }

      // Read key data (ref flag already consumed above)
      K key;
      if constexpr (key_is_polymorphic) {
        key = Serializer<K>::read_with_type_info(ctx, RefMode::None,
                                                 *key_type_info);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      } else {
        // Read data directly - ref flag already consumed
        key = Serializer<K>::read_data(ctx);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      }
      // Insert with the read key and default-constructed value
      result.emplace(std::move(key), V{});
      len_counter++;
      continue;
    }

    // Non-null key and value chunk
    uint8_t chunk_size = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }
    bool key_declared = (header & DECL_KEY_TYPE) != 0;
    bool value_declared = (header & DECL_VALUE_TYPE) != 0;
    bool track_key_ref = (header & TRACKING_KEY_REF) != 0;
    bool track_value_ref = (header & TRACKING_VALUE_REF) != 0;

    // Read type info if not declared
    const TypeInfo *key_type_info = nullptr;
    const TypeInfo *value_type_info = nullptr;

    if (!key_declared || key_is_polymorphic) {
      if constexpr (key_is_polymorphic) {
        key_type_info = read_polymorphic_type_info(ctx);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      } else {
        read_type_info<K>(ctx);
      }
    }
    if (!value_declared || val_is_polymorphic) {
      if constexpr (val_is_polymorphic) {
        value_type_info = read_polymorphic_type_info(ctx);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      } else {
        read_type_info<V>(ctx);
      }
    }

    uint32_t cur_len = len_counter + chunk_size;
    if (cur_len > length) {
      ctx.set_error(Error::invalid_data("Chunk size exceeds total map length"));
      return MapType{};
    }

    // Read chunk_size pairs
    // NOTE: In cross-language serialization, the SENDER determines whether ref
    // flags are written via header flags. The local C++ type traits (like
    // val_is_shared_ref) should NOT influence whether we read ref flags - only
    // the header flags from the wire format matter.
    bool key_read_ref = track_key_ref;
    bool val_read_ref = track_value_ref;

    for (uint8_t i = 0; i < chunk_size; ++i) {
      // Read key - use type info if available (polymorphic case)
      K key;
      if constexpr (key_is_polymorphic) {
        // TRACKING_KEY_REF means full ref tracking for shared_ptr
        key = Serializer<K>::read_with_type_info(
            ctx, key_read_ref ? RefMode::Tracking : RefMode::None,
            *key_type_info);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      } else if (key_read_ref) {
        // TRACKING_KEY_REF means full ref tracking for shared_ptr
        key = Serializer<K>::read(ctx, RefMode::Tracking, false);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      } else {
        // No ref flag - read data directly
        key = Serializer<K>::read_data(ctx);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      }

      // Read value - use type info if available (polymorphic case)
      V value;
      if constexpr (val_is_polymorphic) {
        // TRACKING_VALUE_REF means full ref tracking for shared_ptr
        value = Serializer<V>::read_with_type_info(
            ctx, val_read_ref ? RefMode::Tracking : RefMode::None,
            *value_type_info);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      } else if (val_read_ref) {
        // TRACKING_VALUE_REF means full ref tracking for shared_ptr
        value = Serializer<V>::read(ctx, RefMode::Tracking, false);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      } else {
        // No ref flag - read data directly
        value = Serializer<V>::read_data(ctx);
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return MapType{};
        }
      }

      result.emplace(std::move(key), std::move(value));
    }

    len_counter += chunk_size;
  }

  return result;
}

// ============================================================================
// std::map serializer
// ============================================================================

template <typename K, typename V, typename... Args>
struct Serializer<std::map<K, V, Args...>> {
  static constexpr TypeId type_id = TypeId::MAP;
  using MapType = std::map<K, V, Args...>;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    const TypeInfo *type_info = ctx.read_any_type_info(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      ctx.set_error(Error::type_mismatch(type_info->type_id,
                                         static_cast<uint32_t>(type_id)));
    }
  }

  // Match Rust signature: fory_write(&self, context, write_ref_info,
  // write_type_info, has_generics)
  static inline void write(const MapType &map, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);

    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    write_data_generic(map, ctx, has_generics);
  }

  static inline void write_data(const MapType &map, WriteContext &ctx) {
    constexpr bool is_fast_path =
        !is_polymorphic_v<K> && !is_polymorphic_v<V> && !is_shared_ref_v<K> &&
        !is_shared_ref_v<V> && !is_nullable_v<K> && !is_nullable_v<V>;

    if constexpr (is_fast_path) {
      write_map_data_fast<K, V>(map, ctx, false);
    } else {
      write_map_data_slow<K, V>(map, ctx, true);
    }
  }

  static inline void write_data_generic(const MapType &map, WriteContext &ctx,
                                        bool has_generics) {
    constexpr bool is_fast_path =
        !is_polymorphic_v<K> && !is_polymorphic_v<V> && !is_shared_ref_v<K> &&
        !is_shared_ref_v<V> && !is_nullable_v<K> && !is_nullable_v<V>;

    if constexpr (is_fast_path) {
      write_map_data_fast<K, V>(map, ctx, has_generics);
    } else {
      write_map_data_slow<K, V>(map, ctx, has_generics);
    }
  }

  static inline MapType read(ReadContext &ctx, RefMode ref_mode,
                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }
    if (!has_value) {
      return MapType{};
    }

    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return MapType{};
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return MapType{};
      }
    }

    uint32_t length = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }

    constexpr bool is_fast_path =
        !is_polymorphic_v<K> && !is_polymorphic_v<V> && !is_shared_ref_v<K> &&
        !is_shared_ref_v<V> && !is_nullable_v<K> && !is_nullable_v<V>;

    if constexpr (is_fast_path) {
      return read_map_data_fast<K, V, MapType>(ctx, length);
    } else {
      return read_map_data_slow<K, V, MapType>(ctx, length);
    }
  }

  static inline MapType read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                            const TypeInfo &type_info) {
    // Type info already validated, skip redundant type read
    return read(ctx, ref_mode, false); // read_type=false
  }

  static inline MapType read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }

    constexpr bool is_fast_path =
        !is_polymorphic_v<K> && !is_polymorphic_v<V> && !is_shared_ref_v<K> &&
        !is_shared_ref_v<V> && !is_nullable_v<K> && !is_nullable_v<V>;

    if constexpr (is_fast_path) {
      return read_map_data_fast<K, V, MapType>(ctx, length);
    } else {
      return read_map_data_slow<K, V, MapType>(ctx, length);
    }
  }
};

// ============================================================================
// std::unordered_map serializer
// ============================================================================

template <typename K, typename V, typename... Args>
struct Serializer<std::unordered_map<K, V, Args...>> {
  static constexpr TypeId type_id = TypeId::MAP;
  using MapType = std::unordered_map<K, V, Args...>;

  static inline void write(const MapType &map, WriteContext &ctx,
                           RefMode ref_mode, bool write_type) {
    write_not_null_ref_flag(ctx, ref_mode);

    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    constexpr bool is_fast_path =
        !is_polymorphic_v<K> && !is_polymorphic_v<V> && !is_shared_ref_v<K> &&
        !is_shared_ref_v<V> && !is_nullable_v<K> && !is_nullable_v<V>;

    if constexpr (is_fast_path) {
      write_map_data_fast<K, V>(map, ctx, false);
    } else {
      write_map_data_slow<K, V>(map, ctx, true);
    }
  }

  static inline void write_data(const MapType &map, WriteContext &ctx) {
    constexpr bool is_fast_path =
        !is_polymorphic_v<K> && !is_polymorphic_v<V> && !is_shared_ref_v<K> &&
        !is_shared_ref_v<V> && !is_nullable_v<K> && !is_nullable_v<V>;

    if constexpr (is_fast_path) {
      write_map_data_fast<K, V>(map, ctx, false);
    } else {
      write_map_data_slow<K, V>(map, ctx, true);
    }
  }

  static inline void write_data_generic(const MapType &map, WriteContext &ctx,
                                        bool has_generics) {
    constexpr bool is_fast_path =
        !is_polymorphic_v<K> && !is_polymorphic_v<V> && !is_shared_ref_v<K> &&
        !is_shared_ref_v<V> && !is_nullable_v<K> && !is_nullable_v<V>;

    if constexpr (is_fast_path) {
      write_map_data_fast<K, V>(map, ctx, has_generics);
    } else {
      write_map_data_slow<K, V>(map, ctx, has_generics);
    }
  }

  static inline MapType read(ReadContext &ctx, RefMode ref_mode,
                             bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }
    if (!has_value) {
      return MapType{};
    }

    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return MapType{};
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return MapType{};
      }
    }

    uint32_t length = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }

    constexpr bool is_fast_path =
        !is_polymorphic_v<K> && !is_polymorphic_v<V> && !is_shared_ref_v<K> &&
        !is_shared_ref_v<V> && !is_nullable_v<K> && !is_nullable_v<V>;

    if constexpr (is_fast_path) {
      return read_map_data_fast<K, V, MapType>(ctx, length);
    } else {
      return read_map_data_slow<K, V, MapType>(ctx, length);
    }
  }

  static inline MapType read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                            const TypeInfo &type_info) {
    // Type info already validated, skip redundant type read
    return read(ctx, ref_mode, false); // read_type=false
  }

  static inline MapType read_data(ReadContext &ctx) {
    uint32_t length = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return MapType{};
    }

    constexpr bool is_fast_path =
        !is_polymorphic_v<K> && !is_polymorphic_v<V> && !is_shared_ref_v<K> &&
        !is_shared_ref_v<V> && !is_nullable_v<K> && !is_nullable_v<V>;

    if constexpr (is_fast_path) {
      return read_map_data_fast<K, V, MapType>(ctx, length);
    } else {
      return read_map_data_slow<K, V, MapType>(ctx, length);
    }
  }
};

} // namespace serialization
} // namespace fory
