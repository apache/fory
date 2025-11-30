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

#include <cstdint>
#include <string_view>

namespace fory {
namespace serialization {

// ============================================================================
// TypeIndex - Compile-time type index, faster replacement for std::type_index
// ============================================================================

// Cross-platform macro for function signature with type info
#if defined(_MSC_VER)
#define FORY_PRETTY_FUNCTION __FUNCSIG__
#elif defined(__clang__) || defined(__GNUC__)
#define FORY_PRETTY_FUNCTION __PRETTY_FUNCTION__
#else
#error "Unsupported compiler for compile-time type ID"
#endif

// Macros for generating file:line string at compile time
#ifndef FORY_STRINGIFY_IMPL
#define FORY_STRINGIFY_IMPL(x) #x
#define FORY_STRINGIFY(x) FORY_STRINGIFY_IMPL(x)
#endif

#ifndef FORY_FILE_LINE
#define FORY_FILE_LINE __FILE__ ":" FORY_STRINGIFY(__LINE__)
#endif

/// Compile-time FNV-1a 64-bit hash
constexpr uint64_t fnv1a_64(std::string_view str) {
  uint64_t hash = 14695981039346656037ULL;
  for (char c : str) {
    hash ^= static_cast<uint64_t>(static_cast<unsigned char>(c));
    hash *= 1099511628211ULL;
  }
  return hash;
}

/// Combine two hashes using FNV-1a
constexpr uint64_t fnv1a_64_combine(uint64_t h1, uint64_t h2) {
  h1 ^= h2;
  h1 *= 1099511628211ULL;
  return h1;
}

/// Combine three hashes
constexpr uint64_t fnv1a_64_combine(uint64_t h1, uint64_t h2, uint64_t h3) {
  return fnv1a_64_combine(fnv1a_64_combine(h1, h2), h3);
}

/// TypeIndex trait - primary template with no default implementation.
/// Specialized for primitives, containers, FORY_STRUCT, and FORY_ENUM types.
template <typename T, typename Enable = void> struct TypeIndex;

} // namespace serialization
} // namespace fory
