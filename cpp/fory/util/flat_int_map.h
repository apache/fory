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
#include <cstring>
#include <limits>
#include <memory>
#include <type_traits>
#include <utility>

#if defined(_MSC_VER)
#include <intrin.h>
#endif

#include "fory/util/logging.h"
#include "fory/util/result.h"

namespace fory {
namespace util {

namespace detail {
/// Cross-platform count trailing zeros for 64-bit integers.
/// Returns the number of trailing zero bits (0-63).
/// Behavior is undefined if value is 0.
inline int ctz64(uint64_t value) {
#if defined(_MSC_VER)
  unsigned long index;
  _BitScanForward64(&index, value);
  return static_cast<int>(index);
#else
  return __builtin_ctzll(value);
#endif
}
} // namespace detail

/// A specialized open-addressed hash map optimized for integer keys (uint32_t
/// or uint64_t). Designed for read-heavy workloads: insert can be slow, but
/// lookup must be ultra-fast.
///
/// Features:
/// - Auto-grow when load factor exceeded
/// - Configurable load factor (lower = faster lookup, more memory)
/// - Cache-friendly linear probing
/// - Power-of-2 sizing for fast modulo via bitmasking
///
/// Constraints:
/// - Key max value is reserved as empty marker (cannot store max<K>)
/// - No deletion support
/// - Not thread-safe
/// - Value type must be trivially copyable and small for cache efficiency
///
/// Usage:
/// - Use FlatIntPtrMap<K, T> for pointer values (stores T*)
/// - Use FlatIntMap<K, V> for small scalar values (int, uint32_t, etc.)
template <typename K, typename V> class FlatIntMap {
  static_assert(std::is_same_v<K, uint32_t> || std::is_same_v<K, uint64_t>,
                "FlatIntMap key type must be uint32_t or uint64_t");
  static_assert(std::is_trivially_copyable_v<V>,
                "FlatIntMap value type must be trivially copyable");

public:
  static constexpr K k_empty = std::numeric_limits<K>::max();
  static constexpr float k_default_load_factor = 0.5f; // Low for fast lookup

  struct Entry {
    K key;
    V value;
  };

  class Iterator {
  public:
    Iterator(Entry *entries, size_t capacity, size_t index)
        : entries_(entries), capacity_(capacity), index_(index) {
      while (index_ < capacity_ && entries_[index_].key == k_empty)
        ++index_;
    }
    std::pair<K, V> operator*() const {
      return {entries_[index_].key, entries_[index_].value};
    }
    const Entry *operator->() const { return &entries_[index_]; }
    Iterator &operator++() {
      ++index_;
      while (index_ < capacity_ && entries_[index_].key == k_empty)
        ++index_;
      return *this;
    }
    bool operator==(const Iterator &other) const {
      return index_ == other.index_;
    }
    bool operator!=(const Iterator &other) const { return !(*this == other); }

  private:
    Entry *entries_;
    size_t capacity_;
    size_t index_;
  };

  explicit FlatIntMap(size_t initial_capacity = 64,
                      float load_factor = k_default_load_factor)
      : load_factor_(load_factor) {
    capacity_ = next_power_of_2(initial_capacity < 8 ? 8 : initial_capacity);
    mask_ = capacity_ - 1;
    shift_ = 64 - detail::ctz64(capacity_); // 64 - log2(capacity)
    grow_threshold_ = static_cast<size_t>(capacity_ * load_factor_);
    entries_ = std::make_unique<Entry[]>(capacity_);
    clear_entries(entries_.get(), capacity_);
    size_ = 0;
  }

  FlatIntMap(const FlatIntMap &other)
      : capacity_(other.capacity_), mask_(other.mask_), shift_(other.shift_),
        size_(other.size_), load_factor_(other.load_factor_),
        grow_threshold_(other.grow_threshold_) {
    entries_ = std::make_unique<Entry[]>(capacity_);
    std::memcpy(entries_.get(), other.entries_.get(),
                capacity_ * sizeof(Entry));
  }

  FlatIntMap(FlatIntMap &&other) noexcept
      : entries_(std::move(other.entries_)), capacity_(other.capacity_),
        mask_(other.mask_), shift_(other.shift_), size_(other.size_),
        load_factor_(other.load_factor_),
        grow_threshold_(other.grow_threshold_) {
    other.capacity_ = 0;
    other.mask_ = 0;
    other.shift_ = 0;
    other.size_ = 0;
  }

  FlatIntMap &operator=(const FlatIntMap &other) {
    if (this != &other) {
      capacity_ = other.capacity_;
      mask_ = other.mask_;
      shift_ = other.shift_;
      size_ = other.size_;
      load_factor_ = other.load_factor_;
      grow_threshold_ = other.grow_threshold_;
      entries_ = std::make_unique<Entry[]>(capacity_);
      std::memcpy(entries_.get(), other.entries_.get(),
                  capacity_ * sizeof(Entry));
    }
    return *this;
  }

  FlatIntMap &operator=(FlatIntMap &&other) noexcept {
    if (this != &other) {
      entries_ = std::move(other.entries_);
      capacity_ = other.capacity_;
      mask_ = other.mask_;
      shift_ = other.shift_;
      size_ = other.size_;
      load_factor_ = other.load_factor_;
      grow_threshold_ = other.grow_threshold_;
      other.capacity_ = 0;
      other.mask_ = 0;
      other.shift_ = 0;
      other.size_ = 0;
    }
    return *this;
  }

  /// Insert or update a key-value pair. May trigger grow.
  /// @param key Must not be k_empty (max value of K)
  /// @param value The value to store
  /// @return Previous value if key existed, otherwise default-constructed V
  V put(K key, V value) {
    FORY_CHECK(key != k_empty) << "Cannot use max value as key (reserved)";
    if (size_ >= grow_threshold_) {
      grow();
    }
    size_t idx = find_slot_for_insert(key);
    if (entries_[idx].key == k_empty) {
      entries_[idx].key = key;
      entries_[idx].value = value;
      ++size_;
      return V{};
    }
    V prev = entries_[idx].value;
    entries_[idx].value = value;
    return prev;
  }

  /// Insert or update via subscript operator. May trigger grow.
  /// @param key Must not be k_empty (max value of K)
  /// @return Reference to the value (existing or newly inserted)
  V &operator[](K key) {
    FORY_CHECK(key != k_empty) << "Cannot use max value as key (reserved)";
    if (size_ >= grow_threshold_) {
      grow();
    }
    size_t idx = find_slot_for_insert(key);
    if (entries_[idx].key == k_empty) {
      entries_[idx].key = key;
      ++size_;
    }
    return entries_[idx].value;
  }

  /// Ultra-fast lookup. Returns pointer to Entry or nullptr.
  FORY_ALWAYS_INLINE Entry *find(K key) {
    if (FORY_PREDICT_FALSE(key == k_empty))
      return nullptr;
    Entry *entries = entries_.get();
    size_t mask = mask_;
    size_t idx = place(key);
    while (true) {
      Entry *entry = &entries[idx];
      K k = entry->key;
      if (k == key)
        return entry;
      if (k == k_empty)
        return nullptr;
      idx = (idx + 1) & mask;
    }
  }

  FORY_ALWAYS_INLINE const Entry *find(K key) const {
    return const_cast<FlatIntMap *>(this)->find(key);
  }

  /// get value if found, otherwise return default_value.
  /// @param key The key to look up
  /// @param default_value Value to return if key not found
  /// @return The stored value or default_value
  FORY_ALWAYS_INLINE V get_or_default(K key, V default_value) const {
    if (FORY_PREDICT_FALSE(key == k_empty))
      return default_value;
    Entry *entries = entries_.get();
    size_t mask = mask_;
    size_t idx = place(key);
    while (true) {
      Entry *entry = &entries[idx];
      K k = entry->key;
      if (k == key)
        return entry->value;
      if (k == k_empty)
        return default_value;
      idx = (idx + 1) & mask;
    }
  }

  FORY_ALWAYS_INLINE bool contains(K key) const {
    if (FORY_PREDICT_FALSE(key == k_empty))
      return false;
    Entry *entries = entries_.get();
    size_t mask = mask_;
    size_t idx = place(key);
    while (true) {
      K k = entries[idx].key;
      if (k == key)
        return true;
      if (k == k_empty)
        return false;
      idx = (idx + 1) & mask;
    }
  }
  size_t size() const { return size_; }
  size_t capacity() const { return capacity_; }
  bool empty() const { return size_ == 0; }

  void clear() {
    clear_entries(entries_.get(), capacity_);
    size_ = 0;
  }

  Iterator begin() { return Iterator(entries_.get(), capacity_, 0); }
  Iterator end() { return Iterator(entries_.get(), capacity_, capacity_); }
  Iterator begin() const {
    return Iterator(const_cast<Entry *>(entries_.get()), capacity_, 0);
  }
  Iterator end() const {
    return Iterator(const_cast<Entry *>(entries_.get()), capacity_, capacity_);
  }

private:
  static void clear_entries(Entry *entries, size_t count) {
    for (size_t i = 0; i < count; ++i) {
      entries[i].key = k_empty;
    }
  }

  void grow() {
    size_t new_capacity = capacity_ * 2;
    int new_shift = shift_ - 1; // Double capacity = one less shift
    size_t new_mask = new_capacity - 1;
    auto new_entries = std::make_unique<Entry[]>(new_capacity);
    clear_entries(new_entries.get(), new_capacity);

    // Rehash all existing entries
    for (size_t i = 0; i < capacity_; ++i) {
      if (entries_[i].key != k_empty) {
        size_t idx = place(entries_[i].key, new_shift);
        while (new_entries[idx].key != k_empty) {
          idx = (idx + 1) & new_mask;
        }
        new_entries[idx] = entries_[i];
      }
    }

    entries_ = std::move(new_entries);
    capacity_ = new_capacity;
    mask_ = new_mask;
    shift_ = new_shift;
    grow_threshold_ = static_cast<size_t>(capacity_ * load_factor_);
  }

  size_t find_slot_for_insert(K key) {
    size_t mask = mask_;
    size_t idx = place(key);
    while (entries_[idx].key != k_empty && entries_[idx].key != key) {
      idx = (idx + 1) & mask;
    }
    return idx;
  }

  // Fibonacci hashing: multiply by golden ratio, use high bits.
  // Same as Java LongMap - single multiply, no mask needed for index.
  static constexpr uint64_t k_golden_ratio = 0x9E3779B97F4A7C15ULL;

  FORY_ALWAYS_INLINE size_t place(K key) const {
    return static_cast<size_t>((static_cast<uint64_t>(key) * k_golden_ratio) >>
                               shift_);
  }

  FORY_ALWAYS_INLINE static size_t place(K key, int shift) {
    return static_cast<size_t>((static_cast<uint64_t>(key) * k_golden_ratio) >>
                               shift);
  }

  static size_t next_power_of_2(size_t n) {
    if (n == 0)
      return 1;
    --n;
    n |= n >> 1;
    n |= n >> 2;
    n |= n >> 4;
    n |= n >> 8;
    n |= n >> 16;
    n |= n >> 32;
    return n + 1;
  }

  std::unique_ptr<Entry[]> entries_;
  size_t capacity_;
  size_t mask_;
  int shift_;
  size_t size_;
  float load_factor_;
  size_t grow_threshold_;
};

/// FlatIntPtrMap - Map with pointer values for cache-friendly storage of large
/// objects. User declares FlatIntPtrMap<K, T> to store T* values.
template <typename K, typename T> using FlatIntPtrMap = FlatIntMap<K, T *>;

/// Convenience type aliases for uint64_t keys
template <typename T> using U64PtrMap = FlatIntPtrMap<uint64_t, T>;
template <typename V> using U64Map = FlatIntMap<uint64_t, V>;

/// Convenience type aliases for uint32_t keys
template <typename T> using U32PtrMap = FlatIntPtrMap<uint32_t, T>;
template <typename V> using U32Map = FlatIntMap<uint32_t, V>;

} // namespace util
} // namespace fory
