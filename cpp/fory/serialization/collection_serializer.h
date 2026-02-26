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
#include <deque>
#include <forward_list>
#include <limits>
#include <list>
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
struct CollectionHeader {
  bool track_ref;
  bool has_null;
  bool is_declared_type;
  bool is_same_type;

  /// encode header to single byte
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
  static inline CollectionHeader default_header(bool track_ref) {
    return {
        track_ref, // track_ref
        false,     // has_null
        true,      // is_declared_type
        true,      // is_same_type
    };
  }

  /// Create header for potentially polymorphic collections
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
template <typename T> inline constexpr bool need_type_for_collection_elem() {
  constexpr TypeId tid = Serializer<T>::type_id;
  return tid == TypeId::STRUCT || tid == TypeId::COMPATIBLE_STRUCT ||
         tid == TypeId::NAMED_STRUCT ||
         tid == TypeId::NAMED_COMPATIBLE_STRUCT || tid == TypeId::EXT ||
         tid == TypeId::NAMED_EXT;
}

/// write collection data for non-polymorphic, non-shared-ref elements.
template <typename T, typename Container>
inline void write_collection_data_fast(const Container &coll, WriteContext &ctx,
                                       bool has_generics) {
  static_assert(!is_polymorphic_v<T>,
                "Fast path is for non-polymorphic types only");
  static_assert(!is_shared_ref_v<T>,
                "Fast path is for non-shared-ref types only");

  // write length
  ctx.write_var_uint32(static_cast<uint32_t>(coll.size()));

  if (coll.empty()) {
    return;
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

  // write header
  ctx.write_uint8(bitmap);

  // write element type info if not declared
  if (!is_elem_declared) {
    Serializer<ElemType>::write_type_info(ctx);
  }

  // write elements
  if constexpr (is_nullable_v<T>) {
    using Inner = nullable_element_t<T>;
    if (has_null) {
      for (const auto &elem : coll) {
        if (is_null_value(elem)) {
          ctx.write_int8(NULL_FLAG);
        } else {
          ctx.write_int8(NOT_NULL_VALUE_FLAG);
          if (is_elem_declared) {
            Serializer<Inner>::write_data(deref_nullable(elem), ctx);
          } else {
            Serializer<Inner>::write(deref_nullable(elem), ctx, RefMode::None,
                                     false);
          }
        }
      }
    } else {
      for (const auto &elem : coll) {
        if (is_elem_declared) {
          Serializer<Inner>::write_data(deref_nullable(elem), ctx);
        } else {
          Serializer<Inner>::write(deref_nullable(elem), ctx, RefMode::None,
                                   false);
        }
      }
    }
  } else {
    for (const auto &elem : coll) {
      if (is_elem_declared) {
        if constexpr (is_generic_type_v<T>) {
          Serializer<T>::write_data_generic(elem, ctx, true);
        } else {
          Serializer<T>::write_data(elem, ctx);
        }
      } else {
        Serializer<T>::write(elem, ctx, RefMode::None, false);
      }
    }
  }
}

/// write collection data for polymorphic or shared-ref elements.
template <typename T, typename Container>
inline void write_collection_data_slow(const Container &coll, WriteContext &ctx,
                                       bool has_generics) {
  // write length
  ctx.write_var_uint32(static_cast<uint32_t>(coll.size()));

  if (coll.empty()) {
    return;
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
  // Only set TRACKING_REF if element is shared ref AND global ref tracking is
  // enabled
  if constexpr (elem_is_shared_ref) {
    if (ctx.track_ref()) {
      bitmap |= COLL_TRACKING_REF;
    }
  }

  // write header
  ctx.write_uint8(bitmap);

  // write element type info if IS_SAME_TYPE && !IS_DECL_ELEMENT_TYPE
  if (is_same_type && !(bitmap & COLL_DECL_ELEMENT_TYPE)) {
    if constexpr (elem_is_polymorphic) {
      // write concrete type info for polymorphic elements
      auto type_info_res = ctx.type_resolver().get_type_info(first_type);
      if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      auto write_res =
          ctx.write_any_type_info(type_info_res.value()->type_id, first_type);
      if (FORY_PREDICT_FALSE(!write_res.ok())) {
        ctx.set_error(std::move(write_res).error());
        return;
      }
    } else {
      Serializer<ElemType>::write_type_info(ctx);
    }
  }

  // Determine if we're actually tracking refs for this collection
  const bool tracking_refs = (bitmap & COLL_TRACKING_REF) != 0;

  // write elements
  if (is_same_type) {
    // All elements have same type - type info written once above
    if (tracking_refs) {
      // Track refs - write ref flag per element per xlang spec
      for (const auto &elem : coll) {
        Serializer<T>::write(elem, ctx, RefMode::Tracking, false, has_generics);
      }
    } else if (!has_null) {
      // No nulls, no ref tracking - write data directly without null flag
      for (const auto &elem : coll) {
        if constexpr (is_nullable_v<T>) {
          using Inner = nullable_element_t<T>;
          Serializer<Inner>::write_data(deref_nullable(elem), ctx);
        } else if constexpr (elem_is_shared_ref) {
          // For shared_ptr, use write_data which handles polymorphic types
          Serializer<T>::write_data(elem, ctx);
        } else {
          if constexpr (is_generic_type_v<T>) {
            Serializer<T>::write_data_generic(elem, ctx, has_generics);
          } else {
            Serializer<T>::write_data(elem, ctx);
          }
        }
      }
    } else {
      // Has null elements - write with null flag
      for (const auto &elem : coll) {
        Serializer<T>::write(elem, ctx, RefMode::NullOnly, false, has_generics);
      }
    }
  } else {
    // Heterogeneous types - write type info per element
    if (tracking_refs) {
      // Track refs - write ref flag + type info per element
      for (const auto &elem : coll) {
        Serializer<T>::write(elem, ctx, RefMode::Tracking, true, has_generics);
      }
    } else if (!has_null) {
      // No nulls - write without null flag (RefMode::None)
      for (const auto &elem : coll) {
        Serializer<T>::write(elem, ctx, RefMode::None, true, has_generics);
      }
    } else {
      // Has null elements - write with null flag (RefMode::NullOnly)
      for (const auto &elem : coll) {
        Serializer<T>::write(elem, ctx, RefMode::NullOnly, true, has_generics);
      }
    }
  }
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
inline Container read_collection_data_slow(ReadContext &ctx, uint32_t length) {
  Container result;
  if constexpr (has_reserve_v<Container>) {
    if(length > ctx.max_collection_size() ) {
      ctx.set_error(Error::invalid_data("invalid collection size"));
      return result;
    }
    result.reserve(length);
  }

  if (length == 0) {
    return result;
  }

  constexpr bool elem_is_polymorphic = is_polymorphic_v<T>;

  uint8_t bitmap = ctx.read_uint8(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return result;
  }

  bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
  bool has_null = (bitmap & COLL_HAS_NULL) != 0;
  bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
  bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

  // Read element type info if IS_SAME_TYPE && !IS_DECL_ELEMENT_TYPE
  const TypeInfo *elem_type_info = nullptr;
  if (is_same_type && !is_decl_type) {
    elem_type_info = ctx.read_any_type_info(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return result;
    }
  }

  // Read elements
  if (is_same_type) {
    if (track_ref) {
      for (uint32_t i = 0; i < length; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        if constexpr (elem_is_polymorphic) {
          // Use RefMode::Tracking to read ref flag per element
          auto elem = Serializer<T>::read_with_type_info(ctx, RefMode::Tracking,
                                                         *elem_type_info);
          collection_insert(result, std::move(elem));
        } else {
          auto elem = Serializer<T>::read(ctx, RefMode::Tracking, false);
          collection_insert(result, std::move(elem));
        }
      }
    } else if (!has_null) {
      for (uint32_t i = 0; i < length; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        if constexpr (elem_is_polymorphic) {
          auto elem = Serializer<T>::read_with_type_info(ctx, RefMode::None,
                                                         *elem_type_info);
          collection_insert(result, std::move(elem));
        } else {
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          collection_insert(result, std::move(elem));
        }
      }
    } else {
      // Has null elements
      for (uint32_t i = 0; i < length; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        bool has_value = read_null_only_flag(ctx, RefMode::NullOnly);
        if (!has_value) {
          if constexpr (has_push_back_v<Container, T>) {
            result.push_back(T{});
          }
          // For sets, skip null elements
        } else {
          if constexpr (elem_is_polymorphic) {
            auto elem = Serializer<T>::read_with_type_info(ctx, RefMode::None,
                                                           *elem_type_info);
            collection_insert(result, std::move(elem));
          } else {
            auto elem = Serializer<T>::read(ctx, RefMode::None, false);
            collection_insert(result, std::move(elem));
          }
        }
      }
    }
  } else {
    // Heterogeneous types - read type info per element
    if (has_null && !track_ref) {
      // has_null but no tracking ref - read nullability flag per element
      for (uint32_t i = 0; i < length; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        bool has_value = read_null_only_flag(ctx, RefMode::NullOnly);
        if (!has_value) {
          if constexpr (has_push_back_v<Container, T>) {
            result.push_back(T{});
          }
        } else {
          // Read type info + data without ref flag
          auto elem = Serializer<T>::read(ctx, RefMode::None, true);
          collection_insert(result, std::move(elem));
        }
      }
    } else {
      // Read ref flags based on Fory config
      for (uint32_t i = 0; i < length; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        auto elem = Serializer<T>::read(
            ctx, track_ref ? RefMode::Tracking : RefMode::None, true);
        collection_insert(result, std::move(elem));
      }
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

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::vector<T, Alloc> &vec, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data(vec, ctx);
  }

  static inline void write_data(const std::vector<T, Alloc> &vec,
                                WriteContext &ctx) {
    uint64_t total_bytes = static_cast<uint64_t>(vec.size()) * sizeof(T);
    if (total_bytes > std::numeric_limits<uint32_t>::max()) {
      ctx.set_error(Error::invalid("Vector byte size exceeds uint32_t range"));
      return;
    }
    Buffer &buffer = ctx.buffer();
    // bulk write may write 8 bytes for varint32
    size_t max_size = 8 + total_bytes;
    buffer.grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index +=
        buffer.put_var_uint32(writer_index, static_cast<uint32_t>(total_bytes));
    if (total_bytes > 0) {
      buffer.unsafe_put(writer_index, vec.data(),
                        static_cast<uint32_t>(total_bytes));
    }
    buffer.writer_index(writer_index + static_cast<uint32_t>(total_bytes));
  }

  static inline void write_data_generic(const std::vector<T, Alloc> &vec,
                                        WriteContext &ctx, bool has_generics) {
    write_data(vec, ctx);
  }

  static inline std::vector<T, Alloc> read(ReadContext &ctx, RefMode ref_mode,
                                           bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::vector<T, Alloc>();
    }

    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::vector<T, Alloc>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::vector<T, Alloc>();
      }
    }
    return read_data(ctx);
  }

  static inline std::vector<T, Alloc>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }

  static inline std::vector<T, Alloc> read_data(ReadContext &ctx) {
    uint32_t total_bytes_u32 = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::vector<T, Alloc>();
    }
    if (sizeof(T) == 0) {
      return std::vector<T, Alloc>();
    }
    if (total_bytes_u32 % sizeof(T) != 0) {
      ctx.set_error(Error::invalid_data(
          "Vector byte size not aligned with element size"));
      return std::vector<T, Alloc>();
    }
    size_t elem_count = total_bytes_u32 / sizeof(T);
    std::vector<T, Alloc> result(elem_count);
    if (total_bytes_u32 > 0) {
      ctx.read_bytes(result.data(), static_cast<uint32_t>(total_bytes_u32),
                     ctx.error());
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

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline std::vector<T, Alloc> read(ReadContext &ctx, RefMode ref_mode,
                                           bool read_type) {
    // List-level reference flag
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::vector<T, Alloc>();
    }

    // Optional type info for polymorphic containers
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::vector<T, Alloc>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::vector<T, Alloc>();
      }
    }

    // Length written via write_var_uint32_small7
    uint32_t length = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::vector<T, Alloc>();
    }
    // Per xlang spec: header and type_info are omitted when length is 0
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
      uint8_t bitmap = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::vector<T, Alloc>();
      }
      bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
      bool has_null = (bitmap & COLL_HAS_NULL) != 0;
      bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
      bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

      // Read element type info if IS_SAME_TYPE is set but IS_DECL_ELEMENT_TYPE
      // is not.
      if (is_same_type && !is_decl_type) {
        const TypeInfo *elem_type_info = ctx.read_any_type_info(ctx.error());
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return std::vector<T, Alloc>();
        }
        using ElemType = nullable_element_t<T>;
        uint32_t expected =
            static_cast<uint32_t>(Serializer<ElemType>::type_id);
        if (!type_id_matches(elem_type_info->type_id, expected)) {
          ctx.set_error(
              Error::type_mismatch(elem_type_info->type_id, expected));
          return std::vector<T, Alloc>();
        }
      }

      std::vector<T, Alloc> result;
      if(length > ctx.max_collection_size()) {
        ctx.set_error(Error::invalid_data("invalid collection size"));
        return result;
      }
      result.reserve(length);

      // Fast path: no tracking, no nulls, elements have declared type
      if (!track_ref && !has_null && is_same_type) {
        for (uint32_t i = 0; i < length; ++i) {
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            return result;
          }
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.push_back(std::move(elem));
        }
        return result;
      }

      // General path: handle HAS_NULL and/or TRACKING_REF
      for (uint32_t i = 0; i < length; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        if (track_ref) {
          auto elem = Serializer<T>::read(ctx, RefMode::Tracking, false);
          result.push_back(std::move(elem));
        } else if (has_null) {
          bool has_value_elem = read_null_only_flag(ctx, RefMode::NullOnly);
          if (!has_value_elem) {
            result.emplace_back();
          } else {
            if constexpr (is_nullable_v<T>) {
              using Inner = nullable_element_t<T>;
              auto inner = Serializer<Inner>::read(ctx, RefMode::None, false);
              result.emplace_back(std::move(inner));
            } else {
              auto elem = Serializer<T>::read(ctx, RefMode::None, false);
              result.push_back(std::move(elem));
            }
          }
        } else {
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.push_back(std::move(elem));
        }
      }

      return result;
    }
  }

  static inline void write(const std::vector<T, Alloc> &vec, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    // write ref flag if requested
    write_not_null_ref_flag(ctx, ref_mode);

    // write type info if requested
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    write_data_generic(vec, ctx, has_generics);
  }

  static inline void write_data(const std::vector<T, Alloc> &vec,
                                WriteContext &ctx) {
    ctx.write_var_uint32(static_cast<uint32_t>(vec.size()));
    for (const auto &elem : vec) {
      Serializer<T>::write_data(elem, ctx);
    }
  }

  static inline void write_data_generic(const std::vector<T, Alloc> &vec,
                                        WriteContext &ctx, bool has_generics) {
    // Dispatch to fast or slow path based on element type characteristics
    constexpr bool is_fast_path = !is_polymorphic_v<T> && !is_shared_ref_v<T>;

    if constexpr (is_fast_path) {
      write_collection_data_fast<T>(vec, ctx, has_generics);
    } else {
      write_collection_data_slow<T>(vec, ctx, has_generics);
    }
  }

  static inline std::vector<T, Alloc>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }

  static inline std::vector<T, Alloc> read_data(ReadContext &ctx) {
    uint32_t size = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::vector<T, Alloc>();
    }
    std::vector<T, Alloc> result;
    result.reserve(size);
    for (uint32_t i = 0; i < size; ++i) {
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return result;
      }
      auto elem = Serializer<T>::read_data(ctx);
      result.push_back(std::move(elem));
    }
    return result;
  }
};

/// Specialized serializer for std::vector<bool>
template <typename Alloc> struct Serializer<std::vector<bool, Alloc>> {
  static constexpr TypeId type_id = TypeId::BOOL_ARRAY;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::vector<bool, Alloc> &vec,
                           WriteContext &ctx, RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }
    write_data(vec, ctx);
  }

  static inline void write_data(const std::vector<bool, Alloc> &vec,
                                WriteContext &ctx) {
    Buffer &buffer = ctx.buffer();
    // bulk write may write 8 bytes for varint32
    size_t max_size = 8 + vec.size();
    buffer.grow(static_cast<uint32_t>(max_size));
    uint32_t writer_index = buffer.writer_index();
    writer_index +=
        buffer.put_var_uint32(writer_index, static_cast<uint32_t>(vec.size()));
    for (size_t i = 0; i < vec.size(); ++i) {
      buffer.unsafe_put_byte(writer_index + i,
                             static_cast<uint8_t>(vec[i] ? 1 : 0));
    }
    buffer.writer_index(writer_index + vec.size());
  }

  static inline void write_data_generic(const std::vector<bool, Alloc> &vec,
                                        WriteContext &ctx, bool has_generics) {
    write_data(vec, ctx);
  }

  static inline std::vector<bool, Alloc>
  read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::vector<bool, Alloc>();
    }

    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::vector<bool, Alloc>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::vector<bool, Alloc>();
      }
    }
    return read_data(ctx);
  }

  static inline std::vector<bool, Alloc> read_data(ReadContext &ctx) {
    uint32_t size = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::vector<bool, Alloc>();
    }
    std::vector<bool, Alloc> result(size);
    // Fast path: bulk read all bytes at once if we have enough buffer
    Buffer &buffer = ctx.buffer();
    if (size > 0 && buffer.reader_index() + size <= buffer.size()) {
      const uint8_t *src = buffer.data() + buffer.reader_index();
      for (uint32_t i = 0; i < size; ++i) {
        result[i] = (src[i] != 0);
      }
      buffer.increase_reader_index(size);
    } else {
      // Fallback: read byte-by-byte with bounds checking
      for (uint32_t i = 0; i < size; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        uint8_t byte = ctx.read_uint8(ctx.error());
        result[i] = (byte != 0);
      }
    }
    return result;
  }
};

// ============================================================================
// std::list serializer
// ============================================================================

template <typename T, typename Alloc> struct Serializer<std::list<T, Alloc>> {
  static constexpr TypeId type_id = TypeId::LIST;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline std::list<T, Alloc> read(ReadContext &ctx, RefMode ref_mode,
                                         bool read_type) {
    // List-level reference flag
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::list<T, Alloc>();
    }

    // Optional type info for polymorphic containers
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::list<T, Alloc>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::list<T, Alloc>();
      }
    }

    // Length written via write_var_uint32_small7
    uint32_t length = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::list<T, Alloc>();
    }
    // Per xlang spec: header and type_info are omitted when length is 0
    if (length == 0) {
      return std::list<T, Alloc>();
    }

    // Dispatch to slow path for polymorphic/shared-ref elements
    constexpr bool is_slow_path = is_polymorphic_v<T> || is_shared_ref_v<T>;
    if constexpr (is_slow_path) {
      return read_collection_data_slow<T, std::list<T, Alloc>>(ctx, length);
    } else {
      // Fast path for non-polymorphic, non-shared-ref elements

      // Elements header bitmap (CollectionFlags)
      uint8_t bitmap = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::list<T, Alloc>();
      }
      bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
      bool has_null = (bitmap & COLL_HAS_NULL) != 0;
      bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
      bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

      // Read element type info if IS_SAME_TYPE is set but IS_DECL_ELEMENT_TYPE
      // is not.
      if (is_same_type && !is_decl_type) {
        const TypeInfo *elem_type_info = ctx.read_any_type_info(ctx.error());
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return std::list<T, Alloc>();
        }
        using ElemType = nullable_element_t<T>;
        uint32_t expected =
            static_cast<uint32_t>(Serializer<ElemType>::type_id);
        if (!type_id_matches(elem_type_info->type_id, expected)) {
          ctx.set_error(
              Error::type_mismatch(elem_type_info->type_id, expected));
          return std::list<T, Alloc>();
        }
      }

      std::list<T, Alloc> result;

      // Fast path: no tracking, no nulls, elements have declared type
      if (!track_ref && !has_null && is_same_type) {
        for (uint32_t i = 0; i < length; ++i) {
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            return result;
          }
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.push_back(std::move(elem));
        }
        return result;
      }

      // General path: handle HAS_NULL and/or TRACKING_REF
      for (uint32_t i = 0; i < length; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        if (track_ref) {
          auto elem = Serializer<T>::read(ctx, RefMode::Tracking, false);
          result.push_back(std::move(elem));
        } else if (has_null) {
          bool has_value_elem = read_null_only_flag(ctx, RefMode::NullOnly);
          if (!has_value_elem) {
            result.emplace_back();
          } else {
            if constexpr (is_nullable_v<T>) {
              using Inner = nullable_element_t<T>;
              auto inner = Serializer<Inner>::read(ctx, RefMode::None, false);
              result.emplace_back(std::move(inner));
            } else {
              auto elem = Serializer<T>::read(ctx, RefMode::None, false);
              result.push_back(std::move(elem));
            }
          }
        } else {
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.push_back(std::move(elem));
        }
      }

      return result;
    }
  }

  static inline void write(const std::list<T, Alloc> &lst, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    // write ref flag if requested
    write_not_null_ref_flag(ctx, ref_mode);

    // write type info if requested
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    write_data_generic(lst, ctx, has_generics);
  }

  static inline void write_data(const std::list<T, Alloc> &lst,
                                WriteContext &ctx) {
    ctx.write_var_uint32(static_cast<uint32_t>(lst.size()));
    for (const auto &elem : lst) {
      Serializer<T>::write_data(elem, ctx);
    }
  }

  static inline void write_data_generic(const std::list<T, Alloc> &lst,
                                        WriteContext &ctx, bool has_generics) {
    // Dispatch to fast or slow path based on element type characteristics
    constexpr bool is_fast_path = !is_polymorphic_v<T> && !is_shared_ref_v<T>;

    if constexpr (is_fast_path) {
      write_collection_data_fast<T>(lst, ctx, has_generics);
    } else {
      write_collection_data_slow<T>(lst, ctx, has_generics);
    }
  }

  static inline std::list<T, Alloc>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }

  static inline std::list<T, Alloc> read_data(ReadContext &ctx) {
    uint32_t size = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::list<T, Alloc>();
    }
    std::list<T, Alloc> result;
    for (uint32_t i = 0; i < size; ++i) {
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return result;
      }
      auto elem = Serializer<T>::read_data(ctx);
      result.push_back(std::move(elem));
    }
    return result;
  }
};

// ============================================================================
// std::deque serializer
// ============================================================================

template <typename T, typename Alloc> struct Serializer<std::deque<T, Alloc>> {
  static constexpr TypeId type_id = TypeId::LIST;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline std::deque<T, Alloc> read(ReadContext &ctx, RefMode ref_mode,
                                          bool read_type) {
    // Deque-level reference flag
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::deque<T, Alloc>();
    }

    // Optional type info for polymorphic containers
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::deque<T, Alloc>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::deque<T, Alloc>();
      }
    }

    // Length written via write_var_uint32_small7
    uint32_t length = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::deque<T, Alloc>();
    }
    // Per xlang spec: header and type_info are omitted when length is 0
    if (length == 0) {
      return std::deque<T, Alloc>();
    }

    // Dispatch to slow path for polymorphic/shared-ref elements
    constexpr bool is_slow_path = is_polymorphic_v<T> || is_shared_ref_v<T>;
    if constexpr (is_slow_path) {
      return read_collection_data_slow<T, std::deque<T, Alloc>>(ctx, length);
    } else {
      // Fast path for non-polymorphic, non-shared-ref elements

      // Elements header bitmap (CollectionFlags)
      uint8_t bitmap = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::deque<T, Alloc>();
      }
      bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
      bool has_null = (bitmap & COLL_HAS_NULL) != 0;
      bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
      bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

      // Read element type info if IS_SAME_TYPE is set but IS_DECL_ELEMENT_TYPE
      // is not.
      if (is_same_type && !is_decl_type) {
        const TypeInfo *elem_type_info = ctx.read_any_type_info(ctx.error());
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return std::deque<T, Alloc>();
        }
        using ElemType = nullable_element_t<T>;
        uint32_t expected =
            static_cast<uint32_t>(Serializer<ElemType>::type_id);
        if (!type_id_matches(elem_type_info->type_id, expected)) {
          ctx.set_error(
              Error::type_mismatch(elem_type_info->type_id, expected));
          return std::deque<T, Alloc>();
        }
      }

      std::deque<T, Alloc> result;

      // Fast path: no tracking, no nulls, elements have declared type
      if (!track_ref && !has_null && is_same_type) {
        for (uint32_t i = 0; i < length; ++i) {
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            return result;
          }
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.push_back(std::move(elem));
        }
        return result;
      }

      // General path: handle HAS_NULL and/or TRACKING_REF
      for (uint32_t i = 0; i < length; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        if (track_ref) {
          auto elem = Serializer<T>::read(ctx, RefMode::Tracking, false);
          result.push_back(std::move(elem));
        } else if (has_null) {
          bool has_value_elem = read_null_only_flag(ctx, RefMode::NullOnly);
          if (!has_value_elem) {
            result.emplace_back();
          } else {
            if constexpr (is_nullable_v<T>) {
              using Inner = nullable_element_t<T>;
              auto inner = Serializer<Inner>::read(ctx, RefMode::None, false);
              result.emplace_back(std::move(inner));
            } else {
              auto elem = Serializer<T>::read(ctx, RefMode::None, false);
              result.push_back(std::move(elem));
            }
          }
        } else {
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.push_back(std::move(elem));
        }
      }

      return result;
    }
  }

  static inline void write(const std::deque<T, Alloc> &deq, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    // write ref flag if requested
    write_not_null_ref_flag(ctx, ref_mode);

    // write type info if requested
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    write_data_generic(deq, ctx, has_generics);
  }

  static inline void write_data(const std::deque<T, Alloc> &deq,
                                WriteContext &ctx) {
    ctx.write_var_uint32(static_cast<uint32_t>(deq.size()));
    for (const auto &elem : deq) {
      Serializer<T>::write_data(elem, ctx);
    }
  }

  static inline void write_data_generic(const std::deque<T, Alloc> &deq,
                                        WriteContext &ctx, bool has_generics) {
    // Dispatch to fast or slow path based on element type characteristics
    constexpr bool is_fast_path = !is_polymorphic_v<T> && !is_shared_ref_v<T>;

    if constexpr (is_fast_path) {
      write_collection_data_fast<T>(deq, ctx, has_generics);
    } else {
      write_collection_data_slow<T>(deq, ctx, has_generics);
    }
  }

  static inline std::deque<T, Alloc>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }

  static inline std::deque<T, Alloc> read_data(ReadContext &ctx) {
    uint32_t size = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::deque<T, Alloc>();
    }
    std::deque<T, Alloc> result;
    for (uint32_t i = 0; i < size; ++i) {
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return result;
      }
      auto elem = Serializer<T>::read_data(ctx);
      result.push_back(std::move(elem));
    }
    return result;
  }
};

// ============================================================================
// std::forward_list serializer
// ============================================================================

template <typename T, typename Alloc>
struct Serializer<std::forward_list<T, Alloc>> {
  static constexpr TypeId type_id = TypeId::LIST;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline std::forward_list<T, Alloc>
  read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    // List-level reference flag
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::forward_list<T, Alloc>();
    }

    // Optional type info for polymorphic containers
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::forward_list<T, Alloc>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::forward_list<T, Alloc>();
      }
    }

    // Length written via write_var_uint32_small7
    uint32_t length = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::forward_list<T, Alloc>();
    }
    // Per xlang spec: header and type_info are omitted when length is 0
    if (length == 0) {
      return std::forward_list<T, Alloc>();
    }

    // Read elements into a temporary vector then build forward_list
    // (forward_list doesn't have push_back, only push_front)
    std::vector<T> temp;
    temp.reserve(length);

    // Dispatch to slow path for polymorphic/shared-ref elements
    constexpr bool is_slow_path = is_polymorphic_v<T> || is_shared_ref_v<T>;
    if constexpr (is_slow_path) {
      temp = read_collection_data_slow<T, std::vector<T>>(ctx, length);
    } else {
      // Fast path for non-polymorphic, non-shared-ref elements

      // Elements header bitmap (CollectionFlags)
      uint8_t bitmap = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::forward_list<T, Alloc>();
      }
      bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
      bool has_null = (bitmap & COLL_HAS_NULL) != 0;
      bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
      bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

      // Read element type info if IS_SAME_TYPE is set but IS_DECL_ELEMENT_TYPE
      // is not.
      if (is_same_type && !is_decl_type) {
        const TypeInfo *elem_type_info = ctx.read_any_type_info(ctx.error());
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return std::forward_list<T, Alloc>();
        }
        using ElemType = nullable_element_t<T>;
        uint32_t expected =
            static_cast<uint32_t>(Serializer<ElemType>::type_id);
        if (!type_id_matches(elem_type_info->type_id, expected)) {
          ctx.set_error(
              Error::type_mismatch(elem_type_info->type_id, expected));
          return std::forward_list<T, Alloc>();
        }
      }

      // Fast path: no tracking, no nulls, elements have declared type
      if (!track_ref && !has_null && is_same_type) {
        for (uint32_t i = 0; i < length; ++i) {
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            break;
          }
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          temp.push_back(std::move(elem));
        }
      } else {
        // General path: handle HAS_NULL and/or TRACKING_REF
        for (uint32_t i = 0; i < length; ++i) {
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            break;
          }
          if (track_ref) {
            auto elem = Serializer<T>::read(ctx, RefMode::Tracking, false);
            temp.push_back(std::move(elem));
          } else if (has_null) {
            bool has_value_elem = read_null_only_flag(ctx, RefMode::NullOnly);
            if (!has_value_elem) {
              temp.emplace_back();
            } else {
              if constexpr (is_nullable_v<T>) {
                using Inner = nullable_element_t<T>;
                auto inner = Serializer<Inner>::read(ctx, RefMode::None, false);
                temp.emplace_back(std::move(inner));
              } else {
                auto elem = Serializer<T>::read(ctx, RefMode::None, false);
                temp.push_back(std::move(elem));
              }
            }
          } else {
            auto elem = Serializer<T>::read(ctx, RefMode::None, false);
            temp.push_back(std::move(elem));
          }
        }
      }
    }

    // Build forward_list in reverse order using push_front
    std::forward_list<T, Alloc> result;
    for (auto it = temp.rbegin(); it != temp.rend(); ++it) {
      result.push_front(std::move(*it));
    }
    return result;
  }

  static inline void write(const std::forward_list<T, Alloc> &lst,
                           WriteContext &ctx, RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    // write ref flag if requested
    write_not_null_ref_flag(ctx, ref_mode);

    // write type info if requested
    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    write_data_generic(lst, ctx, has_generics);
  }

  static inline void write_data(const std::forward_list<T, Alloc> &lst,
                                WriteContext &ctx) {
    // forward_list doesn't have size(), so we need to count elements first
    uint32_t size = 0;
    for (const auto &elem : lst) {
      (void)elem;
      ++size;
    }
    ctx.write_var_uint32(size);
    for (const auto &elem : lst) {
      Serializer<T>::write_data(elem, ctx);
    }
  }

  static inline void write_data_generic(const std::forward_list<T, Alloc> &lst,
                                        WriteContext &ctx, bool has_generics) {
    // Convert to vector first for efficient writing (forward_list has no size)
    std::vector<std::reference_wrapper<const T>> temp;
    for (const auto &elem : lst) {
      temp.push_back(std::cref(elem));
    }

    // write length
    ctx.write_var_uint32(static_cast<uint32_t>(temp.size()));

    if (temp.empty()) {
      return;
    }

    // Dispatch to fast or slow path based on element type characteristics
    constexpr bool is_fast_path = !is_polymorphic_v<T> && !is_shared_ref_v<T>;

    if constexpr (is_fast_path) {
      // Check for null elements
      bool has_null = false;
      if constexpr (is_nullable_v<T>) {
        for (const auto &elem_ref : temp) {
          if (is_null_value(elem_ref.get())) {
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

      // write header
      ctx.write_uint8(bitmap);

      // write element type info if not declared
      if (!is_elem_declared) {
        Serializer<ElemType>::write_type_info(ctx);
      }

      // write elements
      if constexpr (is_nullable_v<T>) {
        using Inner = nullable_element_t<T>;
        if (has_null) {
          for (const auto &elem_ref : temp) {
            const auto &elem = elem_ref.get();
            if (is_null_value(elem)) {
              ctx.write_int8(NULL_FLAG);
            } else {
              ctx.write_int8(NOT_NULL_VALUE_FLAG);
              if (is_elem_declared) {
                Serializer<Inner>::write_data(deref_nullable(elem), ctx);
              } else {
                Serializer<Inner>::write(deref_nullable(elem), ctx,
                                         RefMode::None, false);
              }
            }
          }
        } else {
          for (const auto &elem_ref : temp) {
            const auto &elem = elem_ref.get();
            if (is_elem_declared) {
              Serializer<Inner>::write_data(deref_nullable(elem), ctx);
            } else {
              Serializer<Inner>::write(deref_nullable(elem), ctx, RefMode::None,
                                       false);
            }
          }
        }
      } else {
        for (const auto &elem_ref : temp) {
          const auto &elem = elem_ref.get();
          if (is_elem_declared) {
            if constexpr (is_generic_type_v<T>) {
              Serializer<T>::write_data_generic(elem, ctx, true);
            } else {
              Serializer<T>::write_data(elem, ctx);
            }
          } else {
            Serializer<T>::write(elem, ctx, RefMode::None, false);
          }
        }
      }
    } else {
      // Slow path for polymorphic or shared-ref elements
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

      for (const auto &elem_ref : temp) {
        const auto &elem = elem_ref.get();
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
      // Only set TRACKING_REF if element is shared ref AND global ref tracking
      // is enabled
      if constexpr (elem_is_shared_ref) {
        if (ctx.track_ref()) {
          bitmap |= COLL_TRACKING_REF;
        }
      }

      // write header
      ctx.write_uint8(bitmap);

      // write element type info if IS_SAME_TYPE && !IS_DECL_ELEMENT_TYPE
      if (is_same_type && !(bitmap & COLL_DECL_ELEMENT_TYPE)) {
        if constexpr (elem_is_polymorphic) {
          auto type_info_res = ctx.type_resolver().get_type_info(first_type);
          if (FORY_PREDICT_FALSE(!type_info_res.ok())) {
            ctx.set_error(std::move(type_info_res).error());
            return;
          }
          auto write_res = ctx.write_any_type_info(
              type_info_res.value()->type_id, first_type);
          if (FORY_PREDICT_FALSE(!write_res.ok())) {
            ctx.set_error(std::move(write_res).error());
            return;
          }
        } else {
          Serializer<ElemType>::write_type_info(ctx);
        }
      }

      // write elements
      if (is_same_type) {
        if (!has_null) {
          if constexpr (elem_is_shared_ref) {
            for (const auto &elem_ref : temp) {
              Serializer<T>::write(elem_ref.get(), ctx, RefMode::NullOnly,
                                   false, has_generics);
            }
          } else {
            for (const auto &elem_ref : temp) {
              const auto &elem = elem_ref.get();
              if constexpr (is_nullable_v<T>) {
                using Inner = nullable_element_t<T>;
                Serializer<Inner>::write_data(deref_nullable(elem), ctx);
              } else {
                if constexpr (is_generic_type_v<T>) {
                  Serializer<T>::write_data_generic(elem, ctx, has_generics);
                } else {
                  Serializer<T>::write_data(elem, ctx);
                }
              }
            }
          }
        } else {
          for (const auto &elem_ref : temp) {
            Serializer<T>::write(elem_ref.get(), ctx, RefMode::NullOnly, false,
                                 has_generics);
          }
        }
      } else {
        if (!has_null) {
          if constexpr (elem_is_shared_ref) {
            for (const auto &elem_ref : temp) {
              Serializer<T>::write(elem_ref.get(), ctx, RefMode::NullOnly, true,
                                   has_generics);
            }
          } else {
            for (const auto &elem_ref : temp) {
              Serializer<T>::write(elem_ref.get(), ctx, RefMode::None, true,
                                   has_generics);
            }
          }
        } else {
          for (const auto &elem_ref : temp) {
            Serializer<T>::write(elem_ref.get(), ctx, RefMode::NullOnly, true,
                                 has_generics);
          }
        }
      }
    }
  }

  static inline std::forward_list<T, Alloc>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }

  static inline std::forward_list<T, Alloc> read_data(ReadContext &ctx) {
    uint32_t size = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::forward_list<T, Alloc>();
    }
    std::vector<T> temp;
    temp.reserve(size);
    for (uint32_t i = 0; i < size; ++i) {
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        break;
      }
      auto elem = Serializer<T>::read_data(ctx);
      temp.push_back(std::move(elem));
    }
    // Build forward_list in reverse order
    std::forward_list<T, Alloc> result;
    for (auto it = temp.rbegin(); it != temp.rend(); ++it) {
      result.push_front(std::move(*it));
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

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::set<T, Args...> &set, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);

    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    write_data_generic(set, ctx, has_generics);
  }

  static inline void write_data(const std::set<T, Args...> &set,
                                WriteContext &ctx) {
    ctx.write_var_uint32(static_cast<uint32_t>(set.size()));
    for (const auto &elem : set) {
      Serializer<T>::write_data(elem, ctx);
    }
  }

  static inline void write_data_generic(const std::set<T, Args...> &set,
                                        WriteContext &ctx, bool has_generics) {
    // Dispatch to fast or slow path based on element type characteristics
    constexpr bool is_fast_path = !is_polymorphic_v<T> && !is_shared_ref_v<T>;

    if constexpr (is_fast_path) {
      write_collection_data_fast<T>(set, ctx, has_generics);
    } else {
      write_collection_data_slow<T>(set, ctx, has_generics);
    }
  }

  static inline std::set<T, Args...> read(ReadContext &ctx, RefMode ref_mode,
                                          bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::set<T, Args...>();
    }

    // Read type info
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::set<T, Args...>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::set<T, Args...>();
      }
    }

    // Read set size
    uint32_t size = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::set<T, Args...>();
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
      uint8_t bitmap = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::set<T, Args...>();
      }
      bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
      bool has_null = (bitmap & COLL_HAS_NULL) != 0;
      bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
      bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

      if (is_same_type && !is_decl_type) {
        const TypeInfo *elem_type_info = ctx.read_any_type_info(ctx.error());
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return std::set<T, Args...>();
        }
        uint32_t expected = static_cast<uint32_t>(Serializer<T>::type_id);
        if (!type_id_matches(elem_type_info->type_id, expected)) {
          ctx.set_error(
              Error::type_mismatch(elem_type_info->type_id, expected));
          return std::set<T, Args...>();
        }
      }

      std::set<T, Args...> result;
      if (!track_ref && !has_null && is_same_type) {
        for (uint32_t i = 0; i < size; ++i) {
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            return result;
          }
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.insert(std::move(elem));
        }
        return result;
      }

      for (uint32_t i = 0; i < size; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        if (track_ref) {
          auto elem = Serializer<T>::read(ctx, RefMode::Tracking, false);
          result.insert(std::move(elem));
        } else if (has_null) {
          bool has_value_elem = read_null_only_flag(ctx, RefMode::NullOnly);
          if (has_value_elem) {
            auto elem = Serializer<T>::read(ctx, RefMode::None, false);
            result.insert(std::move(elem));
          }
        } else {
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.insert(std::move(elem));
        }
      }
      return result;
    }
  }

  static inline std::set<T, Args...>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }

  static inline std::set<T, Args...> read_data(ReadContext &ctx) {
    uint32_t size = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::set<T, Args...>();
    }
    std::set<T, Args...> result;
    for (uint32_t i = 0; i < size; ++i) {
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return result;
      }
      auto elem = Serializer<T>::read_data(ctx);
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

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_uint8(static_cast<uint8_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    uint32_t actual = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(actual, static_cast<uint32_t>(type_id))) {
      ctx.set_error(
          Error::type_mismatch(actual, static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::unordered_set<T, Args...> &set,
                           WriteContext &ctx, RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);

    if (write_type) {
      ctx.write_uint8(static_cast<uint8_t>(type_id));
    }

    write_data_generic(set, ctx, has_generics);
  }

  static inline void write_data(const std::unordered_set<T, Args...> &set,
                                WriteContext &ctx) {
    ctx.write_var_uint32(static_cast<uint32_t>(set.size()));
    for (const auto &elem : set) {
      Serializer<T>::write_data(elem, ctx);
    }
  }

  static inline void
  write_data_generic(const std::unordered_set<T, Args...> &set,
                     WriteContext &ctx, bool has_generics) {
    // Dispatch to fast or slow path based on element type characteristics
    constexpr bool is_fast_path = !is_polymorphic_v<T> && !is_shared_ref_v<T>;

    if constexpr (is_fast_path) {
      write_collection_data_fast<T>(set, ctx, has_generics);
    } else {
      write_collection_data_slow<T>(set, ctx, has_generics);
    }
  }

  static inline std::unordered_set<T, Args...>
  read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::unordered_set<T, Args...>();
    }

    // Read type info
    if (read_type) {
      uint32_t type_id_read = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::unordered_set<T, Args...>();
      }
      if (type_id_read != static_cast<uint32_t>(type_id)) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::unordered_set<T, Args...>();
      }
    }

    // Read set size
    uint32_t size = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::unordered_set<T, Args...>();
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
      uint8_t bitmap = ctx.read_uint8(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::unordered_set<T, Args...>();
      }
      bool track_ref = (bitmap & COLL_TRACKING_REF) != 0;
      bool has_null = (bitmap & COLL_HAS_NULL) != 0;
      bool is_decl_type = (bitmap & COLL_DECL_ELEMENT_TYPE) != 0;
      bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

      if (is_same_type && !is_decl_type) {
        const TypeInfo *elem_type_info = ctx.read_any_type_info(ctx.error());
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return std::unordered_set<T, Args...>();
        }
        uint32_t expected = static_cast<uint32_t>(Serializer<T>::type_id);
        if (!type_id_matches(elem_type_info->type_id, expected)) {
          ctx.set_error(
              Error::type_mismatch(elem_type_info->type_id, expected));
          return std::unordered_set<T, Args...>();
        }
      }

      std::unordered_set<T, Args...> result;
      result.reserve(size);
      if (!track_ref && !has_null && is_same_type) {
        for (uint32_t i = 0; i < size; ++i) {
          if (FORY_PREDICT_FALSE(ctx.has_error())) {
            return result;
          }
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.insert(std::move(elem));
        }
        return result;
      }

      for (uint32_t i = 0; i < size; ++i) {
        if (FORY_PREDICT_FALSE(ctx.has_error())) {
          return result;
        }
        if (track_ref) {
          auto elem = Serializer<T>::read(ctx, RefMode::Tracking, false);
          result.insert(std::move(elem));
        } else if (has_null) {
          bool has_value_elem = read_null_only_flag(ctx, RefMode::NullOnly);
          if (has_value_elem) {
            auto elem = Serializer<T>::read(ctx, RefMode::None, false);
            result.insert(std::move(elem));
          }
        } else {
          auto elem = Serializer<T>::read(ctx, RefMode::None, false);
          result.insert(std::move(elem));
        }
      }
      return result;
    }
  }

  static inline std::unordered_set<T, Args...>
  read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                      const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }

  static inline std::unordered_set<T, Args...> read_data(ReadContext &ctx) {
    uint32_t size = ctx.read_var_uint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return std::unordered_set<T, Args...>();
    }
    std::unordered_set<T, Args...> result;
    result.reserve(size);
    for (uint32_t i = 0; i < size; ++i) {
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return result;
      }
      auto elem = Serializer<T>::read_data(ctx);
      result.insert(std::move(elem));
    }
    return result;
  }
};

} // namespace serialization
} // namespace fory
