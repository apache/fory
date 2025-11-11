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
#include <set>
#include <unordered_set>
#include <vector>

namespace fory {
namespace serialization {

// ============================================================================
// Collection Header
// ============================================================================

/// Collection elements header encoding per xlang spec section 5.4.4.
///
/// This header encodes homogeneous collection metadata to avoid redundant
/// type/null/ref information for every element, resulting in 20-40% size
/// reduction and 10-30% speed improvement for collections.
///
/// Header bits (1 byte):
/// - Bit 0 (0b0001): track_ref - If elements use reference tracking
/// - Bit 1 (0b0010): has_null - If elements can be null
/// - Bit 2 (0b0100): is_declared_type - If elements are the declared type
/// - Bit 3 (0b1000): is_same_type - If all elements have the same type
///
/// Examples:
/// - 0b0000: All elements non-null, same declared type, no ref tracking
///   (most common case for primitive collections)
/// - 0b0100: Elements are declared type (no polymorphism needed)
/// - 0b1000: All elements same type (read type once, reuse for all)
/// - 0b1100: Same type + declared type (optimal for homogeneous collections)
struct CollectionHeader {
  bool track_ref;
  bool has_null;
  bool is_declared_type;
  bool is_same_type;

  /// Encode header to single byte
  inline uint8_t encode() const {
    uint8_t header = 0;
    if (track_ref)
      header |= 0b0001;
    if (has_null)
      header |= 0b0010;
    if (is_declared_type)
      header |= 0b0100;
    if (is_same_type)
      header |= 0b1000;
    return header;
  }

  /// Decode header from single byte
  static inline CollectionHeader decode(uint8_t byte) {
    return {
        (byte & 0b0001) != 0, // track_ref
        (byte & 0b0010) != 0, // has_null
        (byte & 0b0100) != 0, // is_declared_type
        (byte & 0b1000) != 0, // is_same_type
    };
  }

  /// Create default header for non-polymorphic, non-null collections
  /// (most common case: vector<int>, vector<string>, etc.)
  static inline CollectionHeader default_header(bool track_ref) {
    return {
        track_ref, // track_ref
        false,     // has_null
        true,      // is_declared_type
        true,      // is_same_type
    };
  }

  /// Create header for potentially polymorphic collections
  /// (e.g., vector<shared_ptr<Base>>)
  static inline CollectionHeader polymorphic_header(bool track_ref) {
    return {
        track_ref, // track_ref
        true,      // has_null
        false,     // is_declared_type
        false,     // is_same_type
    };
  }
};

// ============================================================================
// std::vector serializer
// ============================================================================

/// Vector serializer for non-bool types
template <typename T, typename Alloc>
struct Serializer<std::vector<T, Alloc>,
                  std::enable_if_t<!std::is_same_v<T, bool>>> {
  static constexpr TypeId type_id = TypeId::LIST;

  static inline Result<void, Error> write(const std::vector<T, Alloc> &vec,
                                          WriteContext &ctx, bool write_ref,
                                          bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);

    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    // Write collection size
    ctx.write_varuint32(static_cast<uint32_t>(vec.size()));

    // Write elements
    for (const auto &elem : vec) {
      FORY_RETURN_NOT_OK(Serializer<T>::write(elem, ctx, false, false));
    }

    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(const std::vector<T, Alloc> &vec,
                                               WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(vec.size()));
    for (const auto &elem : vec) {
      FORY_RETURN_NOT_OK(Serializer<T>::write_data(elem, ctx));
    }
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(const std::vector<T, Alloc> &vec, WriteContext &ctx,
                     bool has_generics) {
    ctx.write_varuint32(static_cast<uint32_t>(vec.size()));
    for (const auto &elem : vec) {
      if (has_generics && is_generic_type_v<T>) {
        FORY_RETURN_NOT_OK(Serializer<T>::write_data_generic(elem, ctx, true));
      } else {
        FORY_RETURN_NOT_OK(Serializer<T>::write_data(elem, ctx));
      }
    }
    return Result<void, Error>();
  }

  static inline Result<std::vector<T, Alloc>, Error>
  read(ReadContext &ctx, bool read_ref, bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::vector<T, Alloc>();
    }

    // Read type info
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }

    // Read collection size
    FORY_TRY(size, ctx.read_varuint32());

    // Read elements
    std::vector<T, Alloc> result;
    result.reserve(size);
    for (uint32_t i = 0; i < size; ++i) {
      FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
      result.push_back(std::move(elem));
    }

    return result;
  }

  static inline Result<std::vector<T, Alloc>, Error>
  read_data(ReadContext &ctx) {
    FORY_TRY(size, ctx.read_varuint32());
    std::vector<T, Alloc> result;
    result.reserve(size);
    for (uint32_t i = 0; i < size; ++i) {
      FORY_TRY(elem, Serializer<T>::read_data(ctx));
      result.push_back(std::move(elem));
    }
    return result;
  }
};

// ============================================================================
// std::set serializer
// ============================================================================

template <typename T, typename... Args>
struct Serializer<std::set<T, Args...>> {
  static constexpr TypeId type_id = TypeId::SET;

  static inline Result<void, Error> write(const std::set<T, Args...> &set,
                                          WriteContext &ctx, bool write_ref,
                                          bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);

    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    // Write set size
    ctx.write_varuint32(static_cast<uint32_t>(set.size()));

    // Write elements
    for (const auto &elem : set) {
      FORY_RETURN_NOT_OK(Serializer<T>::write(elem, ctx, false, false));
    }

    return Result<void, Error>();
  }

  static inline Result<void, Error> write_data(const std::set<T, Args...> &set,
                                               WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(set.size()));
    for (const auto &elem : set) {
      FORY_RETURN_NOT_OK(Serializer<T>::write_data(elem, ctx));
    }
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(const std::set<T, Args...> &set, WriteContext &ctx,
                     bool has_generics) {
    ctx.write_varuint32(static_cast<uint32_t>(set.size()));
    for (const auto &elem : set) {
      if (has_generics && is_generic_type_v<T>) {
        FORY_RETURN_NOT_OK(Serializer<T>::write_data_generic(elem, ctx, true));
      } else {
        FORY_RETURN_NOT_OK(Serializer<T>::write_data(elem, ctx));
      }
    }
    return Result<void, Error>();
  }

  static inline Result<std::set<T, Args...>, Error>
  read(ReadContext &ctx, bool read_ref, bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::set<T, Args...>();
    }

    // Read type info
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }

    // Read set size
    FORY_TRY(size, ctx.read_varuint32());

    // Read elements
    std::set<T, Args...> result;
    for (uint32_t i = 0; i < size; ++i) {
      FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
      result.insert(std::move(elem));
    }

    return result;
  }

  static inline Result<std::set<T, Args...>, Error>
  read_data(ReadContext &ctx) {
    FORY_TRY(size, ctx.read_varuint32());
    std::set<T, Args...> result;
    for (uint32_t i = 0; i < size; ++i) {
      FORY_TRY(elem, Serializer<T>::read_data(ctx));
      result.insert(std::move(elem));
    }
    return result;
  }
};

// ============================================================================
// std::unordered_set serializer
// ============================================================================

template <typename T, typename... Args>
struct Serializer<std::unordered_set<T, Args...>> {
  static constexpr TypeId type_id = TypeId::SET;

  static inline Result<void, Error>
  write(const std::unordered_set<T, Args...> &set, WriteContext &ctx,
        bool write_ref, bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);

    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    // Write set size
    ctx.write_varuint32(static_cast<uint32_t>(set.size()));

    // Write elements
    for (const auto &elem : set) {
      FORY_RETURN_NOT_OK(Serializer<T>::write(elem, ctx, false, false));
    }

    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data(const std::unordered_set<T, Args...> &set, WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(set.size()));
    for (const auto &elem : set) {
      FORY_RETURN_NOT_OK(Serializer<T>::write_data(elem, ctx));
    }
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(const std::unordered_set<T, Args...> &set,
                     WriteContext &ctx, bool has_generics) {
    ctx.write_varuint32(static_cast<uint32_t>(set.size()));
    for (const auto &elem : set) {
      if (has_generics && is_generic_type_v<T>) {
        FORY_RETURN_NOT_OK(Serializer<T>::write_data_generic(elem, ctx, true));
      } else {
        FORY_RETURN_NOT_OK(Serializer<T>::write_data(elem, ctx));
      }
    }
    return Result<void, Error>();
  }

  static inline Result<std::unordered_set<T, Args...>, Error>
  read(ReadContext &ctx, bool read_ref, bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::unordered_set<T, Args...>();
    }

    // Read type info
    if (read_type) {
      FORY_TRY(type_byte, ctx.read_uint8());
      if (type_byte != static_cast<uint8_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_byte, static_cast<uint8_t>(type_id)));
      }
    }

    // Read set size
    FORY_TRY(size, ctx.read_varuint32());

    // Read elements
    std::unordered_set<T, Args...> result;
    result.reserve(size);
    for (uint32_t i = 0; i < size; ++i) {
      FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
      result.insert(std::move(elem));
    }

    return result;
  }

  static inline Result<std::unordered_set<T, Args...>, Error>
  read_data(ReadContext &ctx) {
    FORY_TRY(size, ctx.read_varuint32());
    std::unordered_set<T, Args...> result;
    result.reserve(size);
    for (uint32_t i = 0; i < size; ++i) {
      FORY_TRY(elem, Serializer<T>::read_data(ctx));
      result.insert(std::move(elem));
    }
    return result;
  }
};

} // namespace serialization
} // namespace fory
