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

#include "fory/util/error.h"
#include "fory/util/result.h"

#include "absl/container/flat_hash_map.h"

#include <any>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <typeinfo>
#include <utility>
#include <vector>

namespace fory {
namespace serialization {

/// Reference flags used in protocol.
enum class RefFlag : int8_t {
  Null = -3,
  Ref = -2,
  NotNullValue = -1,
  RefValue = 0,
};

// Retain constants for existing call sites.
constexpr int8_t NULL_FLAG = static_cast<int8_t>(RefFlag::Null);
constexpr int8_t REF_FLAG = static_cast<int8_t>(RefFlag::Ref);
constexpr int8_t NOT_NULL_VALUE_FLAG =
    static_cast<int8_t>(RefFlag::NotNullValue);
constexpr int8_t REF_VALUE_FLAG = static_cast<int8_t>(RefFlag::RefValue);

/// Reference tracking for shared and circular references during serialization.
class RefWriter {
public:
  RefWriter() : next_id_(0) {}

  /// Try to write a shared reference. Returns true if the pointer was already
  /// serialized and a reference entry was emitted, false otherwise.
  template <typename Writer, typename T>
  bool try_write_shared_ref(Writer &writer, const std::shared_ptr<T> &ptr) {
    if (!ptr) {
      return false;
    }

    auto address = reinterpret_cast<uintptr_t>(ptr.get());
    auto it = ptr_to_id_.find(address);
    if (it != ptr_to_id_.end()) {
      writer.write_int8(static_cast<int8_t>(RefFlag::Ref));
      writer.write_varuint32(it->second);
      return true;
    }

    uint32_t new_id = next_id_++;
    ptr_to_id_.emplace(address, new_id);
    writer.write_int8(static_cast<int8_t>(RefFlag::RefValue));
    return false;
  }

  /// Reserve a ref_id slot without storing a pointer.
  /// Used for types (like structs) that Java tracks but C++ doesn't reference.
  /// This keeps ref ID numbering in sync across languages.
  uint32_t reserve_ref_id() { return next_id_++; }

  /// Reset resolver for reuse in new serialization.
  /// Clears all tracked references.
  void reset() {
    ptr_to_id_.clear();
    next_id_ = 0;
  }

private:
  absl::flat_hash_map<uintptr_t, uint32_t> ptr_to_id_;
  uint32_t next_id_;
};

class RefReader {
public:
  using UpdateCallback = std::function<void(const RefReader &)>;

  RefReader() = default;

  template <typename T> uint32_t store_shared_ref(std::shared_ptr<T> ptr) {
    // Store as shared_ptr<void> for type erasure, maintaining reference count
    refs_.emplace_back(std::shared_ptr<void>(ptr, ptr.get()));
    return static_cast<uint32_t>(refs_.size() - 1);
  }

  template <typename T>
  void store_shared_ref_at(uint32_t ref_id, std::shared_ptr<T> ptr) {
    if (ref_id >= refs_.size()) {
      refs_.resize(ref_id + 1);
    }
    // Store as shared_ptr<void> for type erasure
    refs_[ref_id] = std::shared_ptr<void>(ptr, ptr.get());
  }

  template <typename T>
  Result<std::shared_ptr<T>, Error> get_shared_ref(uint32_t ref_id) const {
    if (ref_id >= refs_.size()) {
      return Unexpected(Error::invalid_ref("Invalid reference ID: " +
                                           std::to_string(ref_id)));
    }

    const std::shared_ptr<void> &stored = refs_[ref_id];
    if (!stored) {
      return Unexpected(Error::invalid_ref("Reference not resolved: " +
                                           std::to_string(ref_id)));
    }

    // Alias constructor: create shared_ptr<T> that shares ownership with stored
    // This works for polymorphic types because the void* points to the actual
    // derived object
    return std::shared_ptr<T>(stored, static_cast<T *>(stored.get()));
  }

  template <typename T>
  void add_update_callback(uint32_t ref_id, std::shared_ptr<T> *target) {
    callbacks_.emplace_back([ref_id, target](const RefReader &reader) {
      auto ref_result = reader.template get_shared_ref<T>(ref_id);
      if (ref_result.ok()) {
        *target = ref_result.value();
      }
    });
  }

  void resolve_callbacks() {
    for (const auto &cb : callbacks_) {
      cb(*this);
    }
    callbacks_.clear();
  }

  void reset() {
    resolve_callbacks();
    refs_.clear();
    callbacks_.clear();
  }

  template <typename Reader>
  Result<RefFlag, Error> read_ref_flag(Reader &reader) const {
    FORY_TRY(flag, reader.read_int8());
    return static_cast<RefFlag>(flag);
  }

  template <typename Reader>
  Result<uint32_t, Error> read_ref_id(Reader &reader) const {
    return reader.read_varuint32();
  }

  uint32_t reserve_ref_id() {
    refs_.emplace_back();
    return static_cast<uint32_t>(refs_.size() - 1);
  }

private:
  std::vector<std::shared_ptr<void>> refs_;
  std::vector<UpdateCallback> callbacks_;
};

} // namespace serialization
} // namespace fory
