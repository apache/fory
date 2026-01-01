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

#include "fory/serialization/collection_serializer.h"
#include "fory/serialization/serializer.h"
#include <cstdint>
#include <tuple>
#include <type_traits>
#include <utility>

namespace fory {
namespace serialization {

// ============================================================================
// Compile-time Tuple Homogeneity Detection
// ============================================================================

/// Check if all types in a tuple are the same type at compile time.
template <typename... Ts> struct is_tuple_homogeneous : std::false_type {};

template <> struct is_tuple_homogeneous<> : std::true_type {};

template <typename T> struct is_tuple_homogeneous<T> : std::true_type {};

template <typename T, typename U, typename... Rest>
struct is_tuple_homogeneous<T, U, Rest...>
    : std::conditional_t<std::is_same_v<T, U> && !is_polymorphic_v<T> &&
                             !is_shared_ref_v<T>,
                         is_tuple_homogeneous<T, Rest...>, std::false_type> {};

template <typename... Ts>
inline constexpr bool is_tuple_homogeneous_v =
    is_tuple_homogeneous<Ts...>::value;

/// Get the first type of a tuple (for homogeneous tuple element type)
template <typename Tuple> struct tuple_first_type;

template <typename T, typename... Rest>
struct tuple_first_type<std::tuple<T, Rest...>> {
  using type = T;
};

template <typename Tuple>
using tuple_first_type_t = typename tuple_first_type<Tuple>::type;

// ============================================================================
// Tuple Serialization Helpers
// ============================================================================

/// Write tuple elements directly (non-xlang mode)
template <typename Tuple, size_t... Is>
inline void write_tuple_elements_direct(const Tuple &tuple, WriteContext &ctx,
                                        std::index_sequence<Is...>) {
  // Use fold expression to write each element
  (
      [&]() {
        if (ctx.has_error())
          return;
        using ElemType = std::tuple_element_t<Is, Tuple>;
        const auto &elem = std::get<Is>(tuple);
        // For nullable/shared types, use full write with ref tracking
        if constexpr (is_nullable_v<ElemType> || is_shared_ref_v<ElemType> ||
                      is_polymorphic_v<ElemType>) {
          Serializer<ElemType>::write(elem, ctx, RefMode::NullOnly, false,
                                      false);
        } else {
          Serializer<ElemType>::write_data(elem, ctx);
        }
      }(),
      ...);
}

/// Write tuple elements with type info (xlang/compatible mode, heterogeneous)
template <typename Tuple, size_t... Is>
inline void write_tuple_elements_heterogeneous(const Tuple &tuple,
                                               WriteContext &ctx,
                                               std::index_sequence<Is...>) {
  // Write each element with its type info
  (
      [&]() {
        if (ctx.has_error())
          return;
        using ElemType = std::tuple_element_t<Is, Tuple>;
        Serializer<ElemType>::write(std::get<Is>(tuple), ctx, RefMode::NullOnly,
                                    true, false);
      }(),
      ...);
}

/// Write tuple elements without type info (xlang/compatible mode, homogeneous)
template <typename Tuple, size_t... Is>
inline void write_tuple_elements_homogeneous(const Tuple &tuple,
                                             WriteContext &ctx,
                                             std::index_sequence<Is...>) {
  // Write each element data only (type info written once in header)
  (
      [&]() {
        if (ctx.has_error())
          return;
        using ElemType = std::tuple_element_t<Is, Tuple>;
        Serializer<ElemType>::write_data(std::get<Is>(tuple), ctx);
      }(),
      ...);
}

/// Read tuple elements directly (non-xlang mode)
template <typename Tuple, size_t... Is>
inline Tuple read_tuple_elements_direct(ReadContext &ctx,
                                        std::index_sequence<Is...>) {
  Tuple result;

  // Use fold expression to read each element
  (
      [&]() {
        if (ctx.has_error())
          return;
        using ElemType = std::tuple_element_t<Is, Tuple>;
        // For nullable/shared types, use full read with ref tracking
        if constexpr (is_nullable_v<ElemType> || is_shared_ref_v<ElemType> ||
                      is_polymorphic_v<ElemType>) {
          std::get<Is>(result) =
              Serializer<ElemType>::read(ctx, RefMode::NullOnly, false);
        } else {
          std::get<Is>(result) = Serializer<ElemType>::read_data(ctx);
        }
      }(),
      ...);

  return result;
}

/// Read tuple elements with type info (xlang/compatible mode, heterogeneous)
template <typename Tuple, size_t... Is>
inline Tuple read_tuple_elements_heterogeneous(ReadContext &ctx,
                                               uint32_t length,
                                               std::index_sequence<Is...>) {
  Tuple result;
  uint32_t index = 0;

  // Read each element with its type info, handling length mismatch
  (
      [&]() {
        if (ctx.has_error())
          return;
        using ElemType = std::tuple_element_t<Is, Tuple>;
        if (index < length) {
          std::get<Is>(result) =
              Serializer<ElemType>::read(ctx, RefMode::NullOnly, true);
          ++index;
        }
        // If index >= length, use default-constructed value
      }(),
      ...);

  // Skip any extra elements beyond tuple size
  while (index < length && !ctx.has_error()) {
    // Skip value - read type and skip data
    ctx.read_any_typeinfo(ctx.error());
    ++index;
  }

  return result;
}

/// Read tuple elements without type info (xlang/compatible mode, homogeneous)
template <typename Tuple, size_t... Is>
inline Tuple read_tuple_elements_homogeneous(ReadContext &ctx, uint32_t length,
                                             std::index_sequence<Is...>) {
  Tuple result;
  uint32_t index = 0;

  // Read each element data only (type info read once in header)
  (
      [&]() {
        if (ctx.has_error())
          return;
        using ElemType = std::tuple_element_t<Is, Tuple>;
        if (index < length) {
          std::get<Is>(result) = Serializer<ElemType>::read_data(ctx);
          ++index;
        }
        // If index >= length, use default-constructed value
      }(),
      ...);

  // Skip any extra elements beyond tuple size
  using ElemType = tuple_first_type_t<Tuple>;
  while (index < length && !ctx.has_error()) {
    Serializer<ElemType>::read_data(ctx);
    ++index;
  }

  return result;
}

// ============================================================================
// std::tuple Serializer
// ============================================================================

/// Empty tuple serializer
template <> struct Serializer<std::tuple<>> {
  static constexpr TypeId type_id = TypeId::LIST;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      ctx.set_error(Error::type_mismatch(type_info->type_id,
                                         static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const std::tuple<> &, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data(std::tuple<>(), ctx);
  }

  static inline void write_data(const std::tuple<> &, WriteContext &ctx) {
    ctx.write_varuint32(0); // length = 0
  }

  static inline void write_data_generic(const std::tuple<> &tuple,
                                        WriteContext &ctx, bool has_generics) {
    write_data(tuple, ctx);
  }

  static inline std::tuple<> read(ReadContext &ctx, RefMode ref_mode,
                                  bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return std::tuple<>();
    }

    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return std::tuple<>();
      }
      if (!type_id_matches(type_id_read, static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return std::tuple<>();
      }
    }
    return read_data(ctx);
  }

  static inline std::tuple<> read_data(ReadContext &ctx) {
    ctx.read_varuint32(ctx.error()); // Ignore length - empty tuple
    return std::tuple<>();
  }

  static inline std::tuple<> read_with_type_info(ReadContext &ctx,
                                                 RefMode ref_mode,
                                                 const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

/// Generic tuple serializer for tuples with 1+ elements
template <typename... Ts> struct Serializer<std::tuple<Ts...>> {
  static constexpr TypeId type_id = TypeId::LIST;
  static constexpr size_t tuple_size = sizeof...(Ts);
  static constexpr bool is_homogeneous = is_tuple_homogeneous_v<Ts...>;

  using TupleType = std::tuple<Ts...>;
  using IndexSeq = std::index_sequence_for<Ts...>;

  static inline void write_type_info(WriteContext &ctx) {
    ctx.write_varuint32(static_cast<uint32_t>(type_id));
  }

  static inline void read_type_info(ReadContext &ctx) {
    const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return;
    }
    if (!type_id_matches(type_info->type_id, static_cast<uint32_t>(type_id))) {
      ctx.set_error(Error::type_mismatch(type_info->type_id,
                                         static_cast<uint32_t>(type_id)));
    }
  }

  static inline void write(const TupleType &tuple, WriteContext &ctx,
                           RefMode ref_mode, bool write_type,
                           bool has_generics = false) {
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      ctx.write_varuint32(static_cast<uint32_t>(type_id));
    }
    write_data_generic(tuple, ctx, has_generics);
  }

  static inline void write_data(const TupleType &tuple, WriteContext &ctx) {
    if (!ctx.is_compatible() && !ctx.is_xlang()) {
      // Non-compatible mode: write elements directly without collection header
      write_tuple_elements_direct(tuple, ctx, IndexSeq{});
      return;
    }

    // xlang/compatible mode: use collection protocol

    // Write length
    ctx.write_varuint32(static_cast<uint32_t>(tuple_size));

    if constexpr (tuple_size == 0) {
      return;
    }

    // Build header bitmap - always heterogeneous for tuples in xlang mode
    uint8_t bitmap = 0;
    ctx.write_uint8(bitmap);

    // Write elements with type info per element
    write_tuple_elements_heterogeneous(tuple, ctx, IndexSeq{});
  }

  static inline void write_data_generic(const TupleType &tuple,
                                        WriteContext &ctx, bool has_generics) {
    write_data(tuple, ctx);
  }

  static inline TupleType read(ReadContext &ctx, RefMode ref_mode,
                               bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return TupleType{};
    }

    if (read_type) {
      uint32_t type_id_read = ctx.read_varuint32(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return TupleType{};
      }
      if (!type_id_matches(type_id_read, static_cast<uint32_t>(type_id))) {
        ctx.set_error(
            Error::type_mismatch(type_id_read, static_cast<uint32_t>(type_id)));
        return TupleType{};
      }
    }

    return read_data(ctx);
  }

  static inline TupleType read_data(ReadContext &ctx) {
    if (!ctx.is_compatible() && !ctx.is_xlang()) {
      // Non-compatible mode: read elements directly
      return read_tuple_elements_direct<TupleType>(ctx, IndexSeq{});
    }

    // xlang/compatible mode: read collection protocol
    uint32_t length = ctx.read_varuint32(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return TupleType{};
    }

    if (length == 0) {
      return TupleType{};
    }

    // Read header bitmap
    uint8_t bitmap = ctx.read_uint8(ctx.error());
    if (FORY_PREDICT_FALSE(ctx.has_error())) {
      return TupleType{};
    }

    bool is_same_type = (bitmap & COLL_IS_SAME_TYPE) != 0;

    if (is_same_type) {
      // Read element type info once
      ctx.read_any_typeinfo(ctx.error());
      if (FORY_PREDICT_FALSE(ctx.has_error())) {
        return TupleType{};
      }

      return read_tuple_elements_homogeneous<TupleType>(ctx, length,
                                                        IndexSeq{});
    } else {
      return read_tuple_elements_heterogeneous<TupleType>(ctx, length,
                                                          IndexSeq{});
    }
  }

  static inline TupleType read_with_type_info(ReadContext &ctx,
                                              RefMode ref_mode,
                                              const TypeInfo &type_info) {
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
