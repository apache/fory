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

#include "fory/serialization/array_serializer.h"
#include "fory/serialization/serializer.h"
#include <array>
#include <cstdint>
#include <limits>
#include <set>
#include <typeindex>
#include <unordered_set>
#include <vector>

namespace fory {
namespace serialization {

// ============================================================================
// Collection Header Constants
// ============================================================================

/// Collection header bit flags (per xlang spec section 5.4.4)
constexpr uint8_t COLL_TRACKING_REF = 0b0001;
constexpr uint8_t COLL_HAS_NULL = 0b0010;
constexpr uint8_t COLL_DECL_ELEMENT_TYPE = 0b0100;
constexpr uint8_t COLL_IS_SAME_TYPE = 0b1000;

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
// Collection Serialization Helpers
// ============================================================================

/// Check if we need to write type info for a collection element type.
/// Matches Rust's need_to_write_type_for_field.
template <typename T> inline constexpr bool need_type_for_collection_elem() {
  constexpr TypeId tid = Serializer<T>::type_id;
  return tid == TypeId::STRUCT || tid == TypeId::COMPATIBLE_STRUCT ||
         tid == TypeId::NAMED_STRUCT ||
         tid == TypeId::NAMED_COMPATIBLE_STRUCT || tid == TypeId::EXT ||
         tid == TypeId::NAMED_EXT;
}

/// Write collection data for non-polymorphic, non-shared-ref elements.
/// This is the fast path for common cases like vector<int>, vector<string>.
template <typename T, typename Container>
inline Result<void, Error> write_collection_data_fast(const Container &coll,
                                                      WriteContext &ctx,
                                                      bool has_generics) {
  static_assert(!is_polymorphic_v<T>,
                "Fast path is for non-polymorphic types only");
  static_assert(!is_shared_ref_v<T>,
                "Fast path is for non-shared-ref types only");

  // Write length
  ctx.write_varuint32(static_cast<uint32_t>(coll.size()));

  if (coll.empty()) {
    return Result<void, Error>();
  }

  // Check for null elements
  bool has_null = false;
  if constexpr (is_nullable_v<T>) {
    for (const auto &elem : coll) {
      if (is_null_value(elem)) {
        has_null = true;
        break;
      }
    }
  }

  // Build header bitmap
  uint8_t bitmap = COLL_IS_SAME_TYPE;
  if (has_null) {
    bitmap |= COLL_HAS_NULL;
  }

  // Determine if element type is declared
  using ElemType = nullable_element_t<T>;
  bool is_elem_declared =
      has_generics && !need_type_for_collection_elem<ElemType>();
  if (is_elem_declared) {
    bitmap |= COLL_DECL_ELEMENT_TYPE;
  }

  // Write header
  ctx.write_uint8(bitmap);

  // Write element type info if not declared
  if (!is_elem_declared) {
    FORY_RETURN_NOT_OK(Serializer<ElemType>::write_type_info(ctx));
  }

  // Write elements
  if constexpr (is_nullable_v<T>) {
    using Inner = nullable_element_t<T>;
    if (has_null) {
      for (const auto &elem : coll) {
        if (is_null_value(elem)) {
          ctx.write_int8(NULL_FLAG);
        } else {
          ctx.write_int8(NOT_NULL_VALUE_FLAG);
          if (is_elem_declared) {
            FORY_RETURN_NOT_OK(
                Serializer<Inner>::write_data(deref_nullable(elem), ctx));
          } else {
            FORY_RETURN_NOT_OK(Serializer<Inner>::write(deref_nullable(elem),
                                                        ctx, false, false));
          }
        }
      }
    } else {
      for (const auto &elem : coll) {
        if (is_elem_declared) {
          FORY_RETURN_NOT_OK(
              Serializer<Inner>::write_data(deref_nullable(elem), ctx));
        } else {
          FORY_RETURN_NOT_OK(Serializer<Inner>::write(deref_nullable(elem), ctx,
                                                      false, false));
        }
      }
    }
  } else {
    for (const auto &elem : coll) {
      if (is_elem_declared) {
        if constexpr (is_generic_type_v<T>) {
          FORY_RETURN_NOT_OK(
              Serializer<T>::write_data_generic(elem, ctx, true));
        } else {
          FORY_RETURN_NOT_OK(Serializer<T>::write_data(elem, ctx));
        }
      } else {
        FORY_RETURN_NOT_OK(Serializer<T>::write(elem, ctx, false, false));
      }
    }
  }

  return Result<void, Error>();
}

/// Write collection data for polymorphic or shared-ref elements.
/// This is the slow path that handles runtime type checking.
template <typename T, typename Container>
inline Result<void, Error> write_collection_data_slow(const Container &coll,
                                                      WriteContext &ctx,
                                                      bool has_generics) {
  // Write length
  ctx.write_varuint32(static_cast<uint32_t>(coll.size()));

  if (coll.empty()) {
    return Result<void, Error>();
  }

  constexpr bool elem_is_polymorphic = is_polymorphic_v<T>;
  constexpr bool elem_is_shared_ref = is_shared_ref_v<T>;

  using ElemType = nullable_element_t<T>;
  bool is_elem_declared =
      has_generics && !need_type_for_collection_elem<ElemType>();

  // Scan collection to determine header flags
  bool has_null = false;
  bool is_same_type = true;
  std::type_index first_type{typeid(void)};
  bool first_type_set = false;

  for (const auto &elem : coll) {
    // Check for nulls
    if constexpr (is_nullable_v<T>) {
      if (is_null_value(elem)) {
        has_null = true;
        continue;
      }
    }
    // Check runtime types for polymorphic elements
    if constexpr (elem_is_polymorphic) {
      if (is_same_type) {
        auto concrete_id = get_concrete_type_id(elem);
        if (!first_type_set) {
          first_type = concrete_id;
          first_type_set = true;
        } else if (concrete_id != first_type) {
          is_same_type = false;
        }
      }
    }
  }

  // If all polymorphic elements are null, treat as heterogeneous
  if constexpr (elem_is_polymorphic) {
    if (is_same_type && !first_type_set) {
      is_same_type = false;
    }
  }

  // Build header bitmap
  uint8_t bitmap = 0;
  if (has_null) {
    bitmap |= COLL_HAS_NULL;
  }
  if (is_elem_declared && !elem_is_polymorphic) {
    bitmap |= COLL_DECL_ELEMENT_TYPE;
  }
  if (is_same_type) {
    bitmap |= COLL_IS_SAME_TYPE;
  }
  if constexpr (elem_is_shared_ref) {
    bitmap |= COLL_TRACKING_REF;
  }

  // Write header
  ctx.write_uint8(bitmap);

  // Write element type info if IS_SAME_TYPE && !IS_DECL_ELEMENT_TYPE
  if (is_same_type && !(bitmap & COLL_DECL_ELEMENT_TYPE)) {
    if constexpr (elem_is_polymorphic) {
      // Write concrete type info for polymorphic elements
      FORY_RETURN_NOT_OK(ctx.write_any_typeinfo(
          static_cast<uint32_t>(TypeId::UNKNOWN), first_type));
    } else {
      FORY_RETURN_NOT_OK(Serializer<ElemType>::write_type_info(ctx));
    }
  }

  // Write elements
  if (is_same_type) {
    // All elements have same type - type info written once above
    if (!has_null) {
      if constexpr (elem_is_shared_ref) {
        // Write with ref flag, without type
        for (const auto &elem : coll) {
          FORY_RETURN_NOT_OK(
              Serializer<T>::write(elem, ctx, true, false, has_generics));
        }
      } else {
        // Write data directly
        for (const auto &elem : coll) {
          if constexpr (is_nullable_v<T>) {
            using Inner = nullable_element_t<T>;
            FORY_RETURN_NOT_OK(
                Serializer<Inner>::write_data(deref_nullable(elem), ctx));
          } else {
            if constexpr (is_generic_type_v<T>) {
              FORY_RETURN_NOT_OK(
                  Serializer<T>::write_data_generic(elem, ctx, has_generics));
            } else {
              FORY_RETURN_NOT_OK(Serializer<T>::write_data(elem, ctx));
            }
          }
        }
      }
    } else {
      // Has null elements - write with ref flag for null tracking
      for (const auto &elem : coll) {
        FORY_RETURN_NOT_OK(
            Serializer<T>::write(elem, ctx, true, false, has_generics));
      }
    }
  } else {
    // Heterogeneous types - write type info per element
    if (!has_null) {
      if constexpr (elem_is_shared_ref) {
        for (const auto &elem : coll) {
          FORY_RETURN_NOT_OK(
              Serializer<T>::write(elem, ctx, true, true, has_generics));
        }
      } else {
        for (const auto &elem : coll) {
          FORY_RETURN_NOT_OK(
              Serializer<T>::write(elem, ctx, false, true, has_generics));
        }
      }
    } else {
      // Has null elements
      for (const auto &elem : coll) {
        FORY_RETURN_NOT_OK(
            Serializer<T>::write(elem, ctx, true, true, has_generics));
      }
    }
  }

  return Result<void, Error>();
}

// Helper trait to detect if container has push_back
template <typename Container, typename T, typename = void>
struct has_push_back : std::false_type {};

template <typename Container, typename T>
struct has_push_back<Container, T,
                     std::void_t<decltype(std::declval<Container>().push_back(
                         std::declval<T>()))>> : std::true_type {};

template <typename Container, typename T>
inline constexpr bool has_push_back_v = has_push_back<Container, T>::value;

// Helper trait to detect if container has reserve
template <typename Container, typename = void>
struct has_reserve : std::false_type {};

template <typename Container>
struct has_reserve<Container,
                   std::void_t<decltype(std::declval<Container>().reserve(0))>>
    : std::true_type {};

template <typename Container>
inline constexpr bool has_reserve_v = has_reserve<Container>::value;

// Helper to insert element into container (vector or set)
template <typename Container, typename T>
inline void collection_insert(Container &result, T &&elem) {
  if constexpr (has_push_back_v<Container, T>) {
    result.push_back(std::forward<T>(elem));
  } else {
    result.insert(std::forward<T>(elem));
  }
}

/// Read collection data for polymorphic or shared-ref elements.
template <typename T, typename Container>
inline Result<Container, Error> read_collection_data_slow(ReadContext &ctx,
                                                          uint32_t length) {
  Container result;
  if constexpr (has_reserve_v<Container>) {
    result.reserve(length);
  }

  if (length == 0) {
    return result;
  }

  constexpr bool elem_is_polymorphic = is_polymorphic_v<T>;
  constexpr bool elem_is_shared_ref = is_shared_ref_v<T>;

  Error error;
  uint8_t bitmap = ctx.read_uint8(&error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
  bool has_null = (bitmap & COLL_HAS_NULL) != 0;
  bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
  bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

  // Read element type info if IS_SAME_TYPE && !IS_DECL_ELEMENT_TYPE
  const TypeInfo *elem_type_info = nullptr;
  if (is_same_type && !is_decl_type) {
    FORY_TRY(type_info, ctx.read_any_typeinfo());
    elem_type_info = type_info;
  }

  // Read elements
  if (is_same_type) {
    if (track_ref || elem_is_shared_ref) {
      for (uint32_t i = 0; i < length; ++i) {
        if constexpr (elem_is_polymorphic) {
          FORY_TRY(elem, Serializer<T>::read_with_type_info(ctx, true,
                                                            *elem_type_info));
          collection_insert(result, std::move(elem));
        } else {
          FORY_TRY(elem, Serializer<T>::read(ctx, true, false));
          collection_insert(result, std::move(elem));
        }
      }
    } else if (!has_null) {
      for (uint32_t i = 0; i < length; ++i) {
        if constexpr (elem_is_polymorphic) {
          FORY_TRY(elem, Serializer<T>::read_with_type_info(ctx, false,
                                                            *elem_type_info));
          collection_insert(result, std::move(elem));
        } else {
          FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
          collection_insert(result, std::move(elem));
        }
      }
    } else {
      // Has null elements
      for (uint32_t i = 0; i < length; ++i) {
        FORY_TRY(has_value, consume_ref_flag(ctx, true));
        if (!has_value) {
          if constexpr (has_push_back_v<Container, T>) {
            result.push_back(T{});
          }
          // For sets, skip null elements
        } else {
          if constexpr (elem_is_polymorphic) {
            FORY_TRY(elem, Serializer<T>::read_with_type_info(ctx, false,
                                                              *elem_type_info));
            collection_insert(result, std::move(elem));
          } else {
            FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
            collection_insert(result, std::move(elem));
          }
        }
      }
    }
  } else {
    // Heterogeneous types - read type info per element
    for (uint32_t i = 0; i < length; ++i) {
      FORY_TRY(elem, Serializer<T>::read(ctx, track_ref, true));
      collection_insert(result, std::move(elem));
    }
  }

  return result;
}

// ============================================================================
// std::vector serializer
// ============================================================================

/// Vector serializer for arithmetic (non-bool) types encoded as typed arrays
template <typename T, typename Alloc>
struct Serializer<
    std::vector<T, Alloc>,
    std::enable_if_t<std::is_arithmetic_v<T> && !std::is_same_v<T, bool>>> {
  static constexpr TypeId type_id = []() {
    if constexpr (std::is_same_v<T, int8_t> || std::is_same_v<T, uint8_t>) {
      return TypeId::BINARY;
    }
    return Serializer<std::array<T, 1>>::type_id;
  }();

  static inline Result<void, Error> write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
    return Result<void, Error>();
  }

  static inline Result<void, Error> read_type_info(ReadContext &ctx) {
    FORY_TRY(type_info, ctx.read_any_typeinfo());
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      return Unexpected(Error::type_mismatch(type_info->type_id,
                                             static_cast<uint32_t>(type_id)));
    }
    return {};
  }

  static inline Result<void, Error> write(const std::vector<T, Alloc> &vec,
                                          WriteContext &ctx, bool write_ref,
                                          bool write_type) {
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    return write_data(vec, ctx);
  }

  static inline Result<void, Error> write_data(const std::vector<T, Alloc> &vec,
                                               WriteContext &ctx) {
    uint64_t total_bytes = static_cast<uint64_t>(vec.size()) * sizeof(T);
    if (total_bytes > std::numeric_limits<uint32_t>::max()) {
      return Unexpected(
          Error::invalid("Vector byte size exceeds uint32_t range"));
    }
    Buffer &buffer = ctx.buffer();
    // bulk write may write 8 bytes for varint32
    size_t max_size = 8 + total_bytes;
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index +=
        buffer.PutVarUint32(writer_index, static_cast<uint32_t>(total_bytes));
    if (total_bytes > 0) {
      buffer.UnsafePut(writer_index, vec.data(),
                       static_cast<uint32_t>(total_bytes));
    }
    buffer.WriterIndex(writer_index + static_cast<uint32_t>(total_bytes));
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(const std::vector<T, Alloc> &vec, WriteContext &ctx,
                     bool has_generics) {
    (void)has_generics;
    return write_data(vec, ctx);
  }

  static inline Result<std::vector<T, Alloc>, Error>
  read(ReadContext &ctx, bool read_ref, bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::vector<T, Alloc>();
    }

    Error error;
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(&error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline Result<std::vector<T, Alloc>, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    // Type info already validated, skip redundant type read
    return read(ctx, read_ref, false); // read_type=false
  }

  static inline Result<std::vector<T, Alloc>, Error>
  read_data(ReadContext &ctx) {
    Error error;
    uint32_t total_bytes_u32 = ctx.read_varuint32(&error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    if (sizeof(T) == 0) {
      return std::vector<T, Alloc>();
    }
    if (total_bytes_u32 % sizeof(T) != 0) {
      return Unexpected(Error::invalid_data(
          "Vector byte size not aligned with element size"));
    }
    size_t elem_count = total_bytes_u32 / sizeof(T);
    std::vector<T, Alloc> result(elem_count);
    if (total_bytes_u32 > 0) {
      ctx.read_bytes(result.data(), static_cast<uint32_t>(total_bytes_u32),
                     &error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
    }
    return result;
  }
};

/// Vector serializer for non-bool, non-arithmetic types
template <typename T, typename Alloc>
struct Serializer<
    std::vector<T, Alloc>,
    std::enable_if_t<!std::is_same_v<T, bool> && !std::is_arithmetic_v<T>>> {
  static constexpr TypeId type_id = TypeId::LIST;

  static inline Result<void, Error> write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
    return Result<void, Error>();
  }

  static inline Result<void, Error> read_type_info(ReadContext &ctx) {
    FORY_TRY(type_info, ctx.read_any_typeinfo());
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      return Unexpected(Error::type_mismatch(type_info->type_id,
                                             static_cast<uint32_t>(type_id)));
    }
    return {};
  }

  static Result<std::vector<T, Alloc>, Error>
  read(ReadContext &ctx, bool read_ref, bool read_type) {
    // List-level reference flag (xwriteRef on Java side)
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::vector<T, Alloc>();
    }

    Error error;
    // Optional type info for polymorphic containers
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(&error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
      uint32_t low = type_id_read & 0xffu;
      if (low != static_cast<uint32_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }

    // Length written via writeVarUint32Small7
    uint32_t length = ctx.read_varuint32(&error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    // Per xlang spec: header and type_info are omitted when length is 0
    // This matches Rust's collection.rs behavior
    if (length == 0) {
      return std::vector<T, Alloc>();
    }

    // Dispatch to slow path for polymorphic/shared-ref elements
    constexpr bool is_slow_path = is_polymorphic_v<T> || is_shared_ref_v<T>;
    if constexpr (is_slow_path) {
      return read_collection_data_slow<T, std::vector<T, Alloc>>(ctx, length);
    } else {
      // Fast path for non-polymorphic, non-shared-ref elements

      // Elements header bitmap (CollectionFlags)
      uint8_t bitmap = ctx.read_uint8(&error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
      bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
      bool has_null = (bitmap & COLL_HAS_NULL) != 0;
      bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
      bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

      // Read element type info if IS_SAME_TYPE is set but IS_DECL_ELEMENT_TYPE
      // is not. This matches Rust/Java behavior in compatible mode.
      if (is_same_type && !is_decl_type) {
        FORY_TRY(elem_type_info, ctx.read_any_typeinfo());
        using ElemType = nullable_element_t<T>;
        uint32_t expected =
            static_cast<uint32_t>(Serializer<ElemType>::type_id);
        if (!type_id_matches(elem_type_info->type_id, expected)) {
          return Unexpected(
              Error::type_mismatch(elem_type_info->type_id, expected));
        }
      }

      std::vector<T, Alloc> result;
      result.reserve(length);

      // Fast path: no tracking, no nulls, elements have declared type
      if (!track_ref && !has_null && is_same_type) {
        for (uint32_t i = 0; i < length; ++i) {
          FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
          result.push_back(std::move(elem));
        }
        return result;
      }

      // General path: handle HAS_NULL and/or TRACKING_REF
      for (uint32_t i = 0; i < length; ++i) {
        if (track_ref) {
          FORY_TRY(elem, Serializer<T>::read(ctx, true, false));
          result.push_back(std::move(elem));
        } else if (has_null) {
          FORY_TRY(has_value_elem, consume_ref_flag(ctx, true));
          if (!has_value_elem) {
            result.emplace_back();
          } else {
            if constexpr (is_nullable_v<T>) {
              using Inner = nullable_element_t<T>;
              FORY_TRY(inner, Serializer<Inner>::read(ctx, false, false));
              result.emplace_back(std::move(inner));
            } else {
              FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
              result.push_back(std::move(elem));
            }
          }
        } else {
          FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
          result.push_back(std::move(elem));
        }
      }

      return result;
    }
  }

  // Match Rust signature: fory_write(&self, context, write_ref_info,
  // write_type_info, has_generics)
  static inline Result<void, Error> write(const std::vector<T, Alloc> &vec,
                                          WriteContext &ctx, bool write_ref,
                                          bool write_type,
                                          bool has_generics = false) {
    // Write ref flag if requested (per Rust)
    write_not_null_ref_flag(ctx, write_ref);

    // Write type info if requested (per Rust)
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }

    return write_data_generic(vec, ctx, has_generics);
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
    // Dispatch to fast or slow path based on element type characteristics
    constexpr bool is_fast_path = !is_polymorphic_v<T> && !is_shared_ref_v<T>;

    if constexpr (is_fast_path) {
      return write_collection_data_fast<T>(vec, ctx, has_generics);
    } else {
      return write_collection_data_slow<T>(vec, ctx, has_generics);
    }
  }

  static inline Result<std::vector<T, Alloc>, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    // Type info already validated, skip redundant type read
    return read(ctx, read_ref, false); // read_type=false
  }

  static inline Result<std::vector<T, Alloc>, Error>
  read_data(ReadContext &ctx) {
    Error error;
    uint32_t size = ctx.read_varuint32(&error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    std::vector<T, Alloc> result;
    result.reserve(size);
    for (uint32_t i = 0; i < size; ++i) {
      FORY_TRY(elem, Serializer<T>::read_data(ctx));
      result.push_back(std::move(elem));
    }
    return result;
  }
};

/// Specialized serializer for std::vector<bool>
template <typename Alloc> struct Serializer<std::vector<bool, Alloc>> {
  static constexpr TypeId type_id = TypeId::BOOL_ARRAY;

  static inline Result<void, Error> write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
    return Result<void, Error>();
  }

  static inline Result<void, Error> read_type_info(ReadContext &ctx) {
    FORY_TRY(type_info, ctx.read_any_typeinfo());
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      return Unexpected(Error::type_mismatch(type_info->type_id,
                                             static_cast<uint32_t>(type_id)));
    }
    return {};
  }

  // Match Rust signature: fory_write(&self, context, write_ref_info,
  // write_type_info, has_generics)
  static inline Result<void, Error> write(const std::vector<bool, Alloc> &vec,
                                          WriteContext &ctx, bool write_ref,
                                          bool write_type,
                                          bool has_generics = false) {
    (void)has_generics; // vector<bool> doesn't use generics
    write_not_null_ref_flag(ctx, write_ref);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    return write_data(vec, ctx);
  }

  static inline Result<void, Error>
  write_data(const std::vector<bool, Alloc> &vec, WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    // bulk write may write 8 bytes for varint32
    size_t max_size = 8 + vec.size();
    buffer.Grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index +=
        buffer.PutVarUint32(writer_index, static_cast<uint32_t>(vec.size()));
    for (size_t i = 0; i < vec.size(); ++i) {
      buffer.UnsafePutByte(writer_index + i,
                           static_cast<uint8_t>(vec[i] ? 1 : 0));
    }
    buffer.WriterIndex(writer_index + vec.size());
    return Result<void, Error>();
  }

  static inline Result<void, Error>
  write_data_generic(const std::vector<bool, Alloc> &vec, WriteContext &ctx,
                     bool has_generics) {
    (void)has_generics;
    return write_data(vec, ctx);
  }

  static inline Result<std::vector<bool, Alloc>, Error>
  read(ReadContext &ctx, bool read_ref, bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::vector<bool, Alloc>();
    }

    Error error;
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(&error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }
    return read_data(ctx);
  }

  static inline Result<std::vector<bool, Alloc>, Error>
  read_data(ReadContext &ctx) {
    Error error;
    uint32_t size = ctx.read_varuint32(&error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    std::vector<bool, Alloc> result(size);
    // Fast path: bulk read all bytes at once if we have enough buffer
    Buffer &buffer = ctx.buffer();
    if (size > 0 && buffer.reader_index() + size <= buffer.size()) {
      const uint8_t *src = buffer.data() + buffer.reader_index();
      for (uint32_t i = 0; i < size; ++i) {
        result[i] = (src[i] != 0);
      }
      buffer.IncreaseReaderIndex(size);
    } else {
      // Fallback: read byte-by-byte with bounds checking
      for (uint32_t i = 0; i < size; ++i) {
        uint8_t byte = ctx.read_uint8(&error);
        if (FORY_PREDICT_FALSE(!error.ok())) {
          return Unexpected(std::move(error));
        }
        result[i] = (byte != 0);
      }
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

  static inline Result<void, Error> write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
    return {};
  }

  static inline Result<void, Error> read_type_info(ReadContext &ctx) {
    FORY_TRY(type_info, ctx.read_any_typeinfo());
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      return Unexpected(Error::type_mismatch(type_info->type_id,
                                             static_cast<uint32_t>(type_id)));
    }
    return {};
  }

  // Match Rust signature: fory_write(&self, context, write_ref_info,
  // write_type_info, has_generics)
  static inline Result<void, Error> write(const std::set<T, Args...> &set,
                                          WriteContext &ctx, bool write_ref,
                                          bool write_type,
                                          bool has_generics = false) {
    write_not_null_ref_flag(ctx, write_ref);

    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }

    return write_data_generic(set, ctx, has_generics);
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
    // Dispatch to fast or slow path based on element type characteristics
    constexpr bool is_fast_path = !is_polymorphic_v<T> && !is_shared_ref_v<T>;

    if constexpr (is_fast_path) {
      return write_collection_data_fast<T>(set, ctx, has_generics);
    } else {
      return write_collection_data_slow<T>(set, ctx, has_generics);
    }
  }

  static inline Result<std::set<T, Args...>, Error>
  read(ReadContext &ctx, bool read_ref, bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::set<T, Args...>();
    }

    Error error;
    // Read type info
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(&error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }

    // Read set size
    uint32_t size = ctx.read_varuint32(&error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    // Per xlang spec: header and type_info are omitted when length is 0
    if (size == 0) {
      return std::set<T, Args...>();
    }

    // Dispatch to slow path for polymorphic/shared-ref elements
    constexpr bool is_slow_path = is_polymorphic_v<T> || is_shared_ref_v<T>;
    if constexpr (is_slow_path) {
      return read_collection_data_slow<T, std::set<T, Args...>>(ctx, size);
    } else {
      // Fast path for non-polymorphic, non-shared-ref elements

      // Read elements header bitmap (CollectionFlags) in xlang mode
      uint8_t bitmap = ctx.read_uint8(&error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
      bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
      bool has_null = (bitmap & COLL_HAS_NULL) != 0;
      bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
      bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

      if (is_same_type && !is_decl_type) {
        FORY_TRY(elem_type_info, ctx.read_any_typeinfo());
        uint32_t expected = static_cast<uint32_t>(Serializer<T>::type_id);
        if (!type_id_matches(elem_type_info->type_id, expected)) {
          return Unexpected(
              Error::type_mismatch(elem_type_info->type_id, expected));
        }
      }

      std::set<T, Args...> result;
      if (!track_ref && !has_null && is_same_type) {
        for (uint32_t i = 0; i < size; ++i) {
          FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
          result.insert(std::move(elem));
        }
        return result;
      }

      for (uint32_t i = 0; i < size; ++i) {
        if (track_ref) {
          FORY_TRY(elem, Serializer<T>::read(ctx, true, false));
          result.insert(std::move(elem));
        } else if (has_null) {
          FORY_TRY(has_value_elem, consume_ref_flag(ctx, true));
          if (has_value_elem) {
            FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
            result.insert(std::move(elem));
          }
        } else {
          FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
          result.insert(std::move(elem));
        }
      }
      return result;
    }
  }

  static inline Result<std::set<T, Args...>, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    // Type info already validated, skip redundant type read
    return read(ctx, read_ref, false); // read_type=false
  }

  static inline Result<std::set<T, Args...>, Error>
  read_data(ReadContext &ctx) {
    Error error;
    uint32_t size = ctx.read_varuint32(&error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
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

  static inline Result<void, Error> write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
    return {};
  }

  static inline Result<void, Error> read_type_info(ReadContext &ctx) {
    FORY_TRY(type_info, ctx.read_any_typeinfo());
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      return Unexpected(Error::type_mismatch(type_info->type_id,
                                             static_cast<uint32_t>(type_id)));
    }
    return {};
  }

  // Match Rust signature: fory_write(&self, context, write_ref_info,
  // write_type_info, has_generics)
  static inline Result<void, Error>
  write(const std::unordered_set<T, Args...> &set, WriteContext &ctx,
        bool write_ref, bool write_type, bool has_generics = false) {
    write_not_null_ref_flag(ctx, write_ref);

    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }

    return write_data_generic(set, ctx, has_generics);
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
    // Dispatch to fast or slow path based on element type characteristics
    constexpr bool is_fast_path = !is_polymorphic_v<T> && !is_shared_ref_v<T>;

    if constexpr (is_fast_path) {
      return write_collection_data_fast<T>(set, ctx, has_generics);
    } else {
      return write_collection_data_slow<T>(set, ctx, has_generics);
    }
  }

  static inline Result<std::unordered_set<T, Args...>, Error>
  read(ReadContext &ctx, bool read_ref, bool read_type) {
    FORY_TRY(has_value, consume_ref_flag(ctx, read_ref));
    if (!has_value) {
      return std::unordered_set<T, Args...>();
    }

    Error error;
    // Read type info
    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(&error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        return Unexpected(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
      }
    }

    // Read set size
    uint32_t size = ctx.read_varuint32(&error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }

    // Per xlang spec: header and type_info are omitted when length is 0
    if (size == 0) {
      return std::unordered_set<T, Args...>();
    }

    // Dispatch to slow path for polymorphic/shared-ref elements
    constexpr bool is_slow_path = is_polymorphic_v<T> || is_shared_ref_v<T>;
    if constexpr (is_slow_path) {
      return read_collection_data_slow<T, std::unordered_set<T, Args...>>(ctx,
                                                                          size);
    } else {
      // Fast path for non-polymorphic, non-shared-ref elements

      // Read elements header bitmap (CollectionFlags) in xlang mode
      uint8_t bitmap = ctx.read_uint8(&error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
      bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
      bool has_null = (bitmap & COLL_HAS_NULL) != 0;
      bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
      bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

      if (is_same_type && !is_decl_type) {
        FORY_TRY(elem_type_info, ctx.read_any_typeinfo());
        uint32_t expected = static_cast<uint32_t>(Serializer<T>::type_id);
        if (!type_id_matches(elem_type_info->type_id, expected)) {
          return Unexpected(
              Error::type_mismatch(elem_type_info->type_id, expected));
        }
      }

      std::unordered_set<T, Args...> result;
      result.reserve(size);
      if (!track_ref && !has_null && is_same_type) {
        for (uint32_t i = 0; i < size; ++i) {
          FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
          result.insert(std::move(elem));
        }
        return result;
      }

      for (uint32_t i = 0; i < size; ++i) {
        if (track_ref) {
          FORY_TRY(elem, Serializer<T>::read(ctx, true, false));
          result.insert(std::move(elem));
        } else if (has_null) {
          FORY_TRY(has_value_elem, consume_ref_flag(ctx, true));
          if (has_value_elem) {
            FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
            result.insert(std::move(elem));
          }
        } else {
          FORY_TRY(elem, Serializer<T>::read(ctx, false, false));
          result.insert(std::move(elem));
        }
      }
      return result;
    }
  }

  static inline Result<std::unordered_set<T, Args...>, Error>
  read_with_type_info(ReadContext &ctx, bool read_ref,
                      const TypeInfo &type_info) {
    // Type info already validated, skip redundant type read
    return read(ctx, read_ref, false); // read_type=false
  }

  static inline Result<std::unordered_set<T, Args...>, Error>
  read_data(ReadContext &ctx) {
    Error error;
    uint32_t size = ctx.read_varuint32(&error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
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
