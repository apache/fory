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
#include <string>
#include <vector>

#include "fory/util/bit_util.h"
#include "fory/util/error.h"
#include "fory/util/logging.h"
#include "fory/util/result.h"
#include "fory/util/stream.h"

namespace fory {

// A buffer class for storing raw bytes with various methods for reading and
// writing the bytes.
class Buffer {
public:
  Buffer();

  Buffer(uint8_t *data, uint32_t size, bool own_data = true)
      : data_(data), size_(size), own_data_(own_data), wrapped_vector_(nullptr),
        stream_(nullptr) {
    writer_index_ = 0;
    reader_index_ = 0;
  }

  /// Wrap an existing vector for zero-copy serialization.
  /// The buffer will append to the vector starting from its current size.
  /// After serialization, the vector is resized to writer_index().
  ///
  /// @param vec The vector to wrap (must outlive this Buffer).
  explicit Buffer(std::vector<uint8_t> &vec)
      : data_(vec.data()), size_(static_cast<uint32_t>(vec.size())),
        own_data_(false), writer_index_(static_cast<uint32_t>(vec.size())),
        reader_index_(0), wrapped_vector_(&vec), stream_(nullptr) {}

  explicit Buffer(ForyInputStream &stream)
      : data_(stream.data()), size_(stream.size()), own_data_(false),
        writer_index_(stream.size()), reader_index_(stream.reader_index()),
        wrapped_vector_(nullptr), stream_(&stream) {}

  explicit Buffer(std::shared_ptr<ForyInputStream> stream)
      : data_(nullptr), size_(0), own_data_(false), writer_index_(0),
        reader_index_(0), wrapped_vector_(nullptr), stream_(stream.get()),
        stream_owner_(std::move(stream)) {
    FORY_CHECK(stream_ != nullptr) << "stream must not be null";
    data_ = stream_->data();
    size_ = stream_->size();
    writer_index_ = stream_->size();
    reader_index_ = stream_->reader_index();
  }

  Buffer(Buffer &&buffer) noexcept;

  Buffer &operator=(Buffer &&buffer) noexcept;

  virtual ~Buffer();

  /// \brief Return a pointer to the buffer's data
  FORY_ALWAYS_INLINE uint8_t *data() const { return data_; }

  /// \brief Return the buffer's size in bytes
  FORY_ALWAYS_INLINE uint32_t size() const { return size_; }

  FORY_ALWAYS_INLINE bool own_data() const { return own_data_; }

  FORY_ALWAYS_INLINE bool is_stream_backed() const {
    return stream_ != nullptr;
  }

  FORY_ALWAYS_INLINE void sync_stream_reader_index() {
    if (stream_ != nullptr) {
      stream_->reader_index(reader_index_);
    }
  }

  FORY_ALWAYS_INLINE uint32_t writer_index() { return writer_index_; }

  FORY_ALWAYS_INLINE uint32_t reader_index() { return reader_index_; }

  /// \brief Return the remaining bytes available for reading
  FORY_ALWAYS_INLINE uint32_t remaining_size() const {
    return size_ - reader_index_;
  }

  FORY_ALWAYS_INLINE bool ensure_size(uint64_t target_size, Error &error) {
    if (FORY_PREDICT_TRUE(target_size <= size_)) {
      return true;
    }
    if (FORY_PREDICT_FALSE(target_size >
                           std::numeric_limits<uint32_t>::max())) {
      error.set_error(ErrorCode::OutOfBound,
                      "reader index exceeds uint32 range");
      return false;
    }
    const auto target_u32 = static_cast<uint32_t>(target_size);
    if (FORY_PREDICT_TRUE(stream_ == nullptr)) {
      error.set_buffer_out_of_bound(target_u32, 0, size_);
      return false;
    }
    if (FORY_PREDICT_FALSE(!fill_to(target_u32, error))) {
      return false;
    }
    if (FORY_PREDICT_FALSE(target_size > size_)) {
      error.set_buffer_out_of_bound(target_u32, 0, size_);
      return false;
    }
    return true;
  }

  FORY_ALWAYS_INLINE void writer_index(uint32_t writer_index) {
    FORY_CHECK(writer_index < std::numeric_limits<uint32_t>::max())
        << "Buffer overflow writer_index" << writer_index_
        << " target writer_index " << writer_index;
    writer_index_ = writer_index;
  }

  FORY_ALWAYS_INLINE void increase_writer_index(uint32_t diff) {
    uint64_t writer_index = writer_index_ + diff;
    FORY_CHECK(writer_index < std::numeric_limits<uint32_t>::max())
        << "Buffer overflow writer_index" << writer_index_ << " diff " << diff;
    writer_index_ = writer_index;
  }

  FORY_ALWAYS_INLINE void reader_index(uint32_t reader_index) {
    if (FORY_PREDICT_FALSE(reader_index > size_ && stream_ != nullptr)) {
      Error error;
      const bool ok = fill_to(reader_index, error);
      FORY_CHECK(ok)
          << "failed to fill stream buffer while setting reader index: "
          << error.to_string();
    }
    FORY_CHECK(reader_index <= size_)
        << "Buffer overflow reader_index " << reader_index_
        << " target reader_index " << reader_index << " size " << size_;
    reader_index_ = reader_index;
  }

  FORY_ALWAYS_INLINE void increase_reader_index(uint32_t diff, Error &error) {
    const uint64_t target = static_cast<uint64_t>(reader_index_) + diff;
    if (FORY_PREDICT_FALSE(target > std::numeric_limits<uint32_t>::max())) {
      error.set_error(ErrorCode::OutOfBound,
                      "reader index exceeds uint32 range");
      return;
    }
    if (FORY_PREDICT_FALSE(target > size_)) {
      if (FORY_PREDICT_TRUE(stream_ == nullptr)) {
        error.set_buffer_out_of_bound(reader_index_, diff, size_);
        return;
      }
      if (FORY_PREDICT_FALSE(!fill_to(static_cast<uint32_t>(target), error))) {
        if (error.ok()) {
          error.set_buffer_out_of_bound(reader_index_, diff, size_);
        }
        return;
      }
      if (FORY_PREDICT_FALSE(target > size_)) {
        error.set_buffer_out_of_bound(reader_index_, diff, size_);
        return;
      }
    }
    reader_index_ = static_cast<uint32_t>(target);
  }

  // Unsafe methods don't check bound
  template <typename T>
  FORY_ALWAYS_INLINE void unsafe_put(uint32_t offset, T value) {
    reinterpret_cast<T *>(data_ + offset)[0] = value;
  }

  template <typename T> FORY_ALWAYS_INLINE T unsafe_get(uint32_t offset) {
    return reinterpret_cast<const T *>(data_ + offset)[0];
  }

  template <typename T, typename = std::enable_if_t<std::disjunction_v<
                            std::is_same<T, int8_t>, std::is_same<T, uint8_t>,
                            std::is_same<T, bool>>>>
  FORY_ALWAYS_INLINE T unsafe_get_byte_as(uint32_t offset) {
    return data_[offset];
  }

  template <typename T, typename = std::enable_if_t<std::disjunction_v<
                            std::is_same<T, int8_t>, std::is_same<T, uint8_t>,
                            std::is_same<T, bool>>>>
  FORY_ALWAYS_INLINE void unsafe_put_byte(uint32_t offset, T value) {
    data_[offset] = value;
  }

  FORY_ALWAYS_INLINE void unsafe_put(uint32_t offset, const void *data,
                                     const uint32_t length) {
    memcpy(data_ + offset, data, (size_t)length);
  }

  FORY_ALWAYS_INLINE void put_int24(uint32_t offset, int32_t value) {
    data_[offset] = static_cast<uint8_t>(value);
    data_[offset + 1] = static_cast<uint8_t>(value >> 8);
    data_[offset + 2] = static_cast<uint8_t>(value >> 16);
  }

  template <typename T> FORY_ALWAYS_INLINE T get(uint32_t relative_offset) {
    FORY_CHECK(relative_offset + sizeof(T) <= size_)
        << "Out of range " << relative_offset << " should be less than "
        << size_;
    T value = reinterpret_cast<const T *>(data_ + relative_offset)[0];
    return value;
  }

  template <typename T, typename = std::enable_if_t<std::disjunction_v<
                            std::is_same<T, int8_t>, std::is_same<T, uint8_t>,
                            std::is_same<T, bool>>>>
  FORY_ALWAYS_INLINE T get_byte_as(uint32_t relative_offset) {
    FORY_CHECK(relative_offset < size_) << "Out of range " << relative_offset
                                        << " should be less than " << size_;
    return data_[relative_offset];
  }

  FORY_ALWAYS_INLINE bool get_bool(uint32_t offset) {
    return get_byte_as<bool>(offset);
  }

  FORY_ALWAYS_INLINE int8_t get_int8(uint32_t offset) {
    return get_byte_as<int8_t>(offset);
  }

  FORY_ALWAYS_INLINE int16_t get_int16(uint32_t offset) {
    return get<int16_t>(offset);
  }

  FORY_ALWAYS_INLINE int32_t get_int24(uint32_t offset) {
    FORY_CHECK(offset + 3 <= size_)
        << "Out of range " << offset << " should be less than " << size_;
    int32_t b0 = data_[offset];
    int32_t b1 = data_[offset + 1];
    int32_t b2 = data_[offset + 2];
    return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16);
  }

  FORY_ALWAYS_INLINE int32_t get_int32(uint32_t offset) {
    return get<int32_t>(offset);
  }

  FORY_ALWAYS_INLINE int64_t get_int64(uint32_t offset) {
    return get<int64_t>(offset);
  }

  FORY_ALWAYS_INLINE float get_float(uint32_t offset) {
    return get<float>(offset);
  }

  FORY_ALWAYS_INLINE double get_double(uint32_t offset) {
    return get<double>(offset);
  }

  FORY_ALWAYS_INLINE Result<void, Error>
  get_bytes_as_int64(uint32_t offset, uint32_t length, int64_t *target) {
    if (length == 0) {
      *target = 0;
      return Result<void, Error>();
    }
    if (FORY_PREDICT_FALSE(length > 8)) {
      return Unexpected(Error::invalid_data(
          "get_bytes_as_int64 length should be in range [0, 8]"));
    }
    if (FORY_PREDICT_FALSE(offset > size_ || length > size_ - offset)) {
      return Unexpected(Error::buffer_out_of_bound(offset, length, size_));
    }
    if (size_ - offset >= 8) {
      uint64_t mask = std::numeric_limits<uint64_t>::max();
      uint64_t x = (mask >> (8 - length) * 8);
      *target =
          static_cast<int64_t>(static_cast<uint64_t>(get_int64(offset)) & x);
      return Result<void, Error>();
    }
    int64_t result = 0;
    for (size_t i = 0; i < length; i++) {
      result = result | ((int64_t)(data_[offset + i])) << (i * 8);
    }
    *target = result;
    return Result<void, Error>();
  }

  /// Put unsigned varint32 at offset using optimized bulk writes.
  /// Returns number of bytes written (1-5).
  /// Uses bit manipulation to build encoded value, then single memory write.
  FORY_ALWAYS_INLINE uint32_t put_var_uint32(uint32_t offset, uint32_t value) {
    if (value < 0x80) {
      data_[offset] = static_cast<uint8_t>(value);
      return 1;
    }
    // Build encoded value: place data bits with continuation bits interleaved
    // byte0: bits 0-6 + continuation at bit 7
    // byte1: bits 7-13 + continuation at bit 15 (in uint16/32/64)
    // etc.
    uint64_t encoded = (value & 0x7F) | 0x80;
    encoded |= (static_cast<uint64_t>(value & 0x3F80) << 1);
    if (value < 0x4000) {
      *reinterpret_cast<uint16_t *>(data_ + offset) =
          static_cast<uint16_t>(encoded);
      return 2;
    }
    encoded |= (static_cast<uint64_t>(value & 0x1FC000) << 2) | 0x8000;
    if (value < 0x200000) {
      *reinterpret_cast<uint32_t *>(data_ + offset) =
          static_cast<uint32_t>(encoded);
      return 3;
    }
    encoded |= (static_cast<uint64_t>(value & 0xFE00000) << 3) | 0x800000;
    if (value < 0x10000000) {
      *reinterpret_cast<uint32_t *>(data_ + offset) =
          static_cast<uint32_t>(encoded);
      return 4;
    }
    encoded |= (static_cast<uint64_t>(value >> 28) << 32) | 0x80000000;
    *reinterpret_cast<uint64_t *>(data_ + offset) = encoded;
    return 5;
  }

  /// get unsigned varint32 from offset using optimized bulk read.
  /// Fast path: bulk read 4 bytes + bit extraction when enough bytes available.
  /// Slow path: byte-by-byte for buffer edge cases.
  FORY_ALWAYS_INLINE uint32_t get_var_uint32(uint32_t offset,
                                             uint32_t *read_bytes_length) {
    if (FORY_PREDICT_FALSE(offset >= size_)) {
      *read_bytes_length = 0;
      return 0;
    }
    // Fast path: need at least 5 bytes for safe bulk read (4 bytes + potential
    // 5th)
    if (FORY_PREDICT_TRUE(size_ - offset >= 5)) {
      uint32_t bulk = *reinterpret_cast<uint32_t *>(data_ + offset);

      uint32_t result = bulk & 0x7F;
      if ((bulk & 0x80) == 0) {
        *read_bytes_length = 1;
        return result;
      }
      // Extract bits 7-13 from bulk (at positions 8-14 after shift)
      result |= (bulk >> 1) & 0x3F80;
      if ((bulk & 0x8000) == 0) {
        *read_bytes_length = 2;
        return result;
      }
      // Extract bits 14-20 from bulk (at positions 16-22 after shift)
      result |= (bulk >> 2) & 0x1FC000;
      if ((bulk & 0x800000) == 0) {
        *read_bytes_length = 3;
        return result;
      }
      // Extract bits 21-27 from bulk (at positions 24-30 after shift)
      result |= (bulk >> 3) & 0xFE00000;
      if ((bulk & 0x80000000) == 0) {
        *read_bytes_length = 4;
        return result;
      }
      // 5th byte for bits 28-31 (only 4 bits used for uint32, but mask with
      // 0x7F per varint spec)
      result |= static_cast<uint32_t>(data_[offset + 4] & 0x7F) << 28;
      *read_bytes_length = 5;
      return result;
    }
    // Slow path: byte-by-byte read
    return read_var_uint32_slow(offset, read_bytes_length);
  }

  /// Slow path for varuint32 decode when not enough bytes for bulk read.
  uint32_t read_var_uint32_slow(uint32_t offset, uint32_t *read_bytes_length) {
    if (FORY_PREDICT_FALSE(offset >= size_)) {
      *read_bytes_length = 0;
      return 0;
    }
    uint32_t position = offset;
    int b = data_[position++];
    uint32_t result = b & 0x7F;
    if ((b & 0x80) != 0) {
      if (FORY_PREDICT_FALSE(position >= size_)) {
        *read_bytes_length = 0;
        return 0;
      }
      b = data_[position++];
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        if (FORY_PREDICT_FALSE(position >= size_)) {
          *read_bytes_length = 0;
          return 0;
        }
        b = data_[position++];
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          if (FORY_PREDICT_FALSE(position >= size_)) {
            *read_bytes_length = 0;
            return 0;
          }
          b = data_[position++];
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            if (FORY_PREDICT_FALSE(position >= size_)) {
              *read_bytes_length = 0;
              return 0;
            }
            b = data_[position++];
            result |= (b & 0x7F) << 28;
          }
        }
      }
    }
    *read_bytes_length = position - offset;
    return result;
  }

  /// Put unsigned varint64 at offset using optimized bulk writes.
  /// Returns number of bytes written (1-9).
  /// Uses PVL (Progressive Variable-length Long) encoding per xlang spec.
  FORY_ALWAYS_INLINE uint32_t put_var_uint64(uint32_t offset, uint64_t value) {
    if (value < 0x80) {
      data_[offset] = static_cast<uint8_t>(value);
      return 1;
    }
    // Build encoded value with continuation bits interleaved
    uint64_t encoded = (value & 0x7F) | 0x80;
    encoded |= ((value & 0x3F80) << 1);
    if (value < 0x4000) {
      *reinterpret_cast<uint16_t *>(data_ + offset) =
          static_cast<uint16_t>(encoded);
      return 2;
    }
    encoded |= ((value & 0x1FC000) << 2) | 0x8000;
    if (value < 0x200000) {
      *reinterpret_cast<uint32_t *>(data_ + offset) =
          static_cast<uint32_t>(encoded);
      return 3;
    }
    encoded |= ((value & 0xFE00000) << 3) | 0x800000;
    if (value < 0x10000000) {
      *reinterpret_cast<uint32_t *>(data_ + offset) =
          static_cast<uint32_t>(encoded);
      return 4;
    }
    encoded |= ((value & 0x7F0000000ULL) << 4) | 0x80000000;
    if (value < 0x800000000ULL) {
      *reinterpret_cast<uint64_t *>(data_ + offset) = encoded;
      return 5;
    }
    encoded |= ((value & 0x3F800000000ULL) << 5) | 0x8000000000ULL;
    if (value < 0x40000000000ULL) {
      *reinterpret_cast<uint64_t *>(data_ + offset) = encoded;
      return 6;
    }
    encoded |= ((value & 0x1FC0000000000ULL) << 6) | 0x800000000000ULL;
    if (value < 0x2000000000000ULL) {
      *reinterpret_cast<uint64_t *>(data_ + offset) = encoded;
      return 7;
    }
    encoded |= ((value & 0xFE000000000000ULL) << 7) | 0x80000000000000ULL;
    if (value < 0x100000000000000ULL) {
      *reinterpret_cast<uint64_t *>(data_ + offset) = encoded;
      return 8;
    }
    // 9 bytes: write 8 bytes + 1 byte for bits 56-63
    encoded |= 0x8000000000000000ULL;
    *reinterpret_cast<uint64_t *>(data_ + offset) = encoded;
    data_[offset + 8] = static_cast<uint8_t>(value >> 56);
    return 9;
  }

  /// get unsigned varint64 from offset using optimized bulk read.
  /// Fast path: bulk read 8 bytes + bit extraction when enough bytes available.
  /// Slow path: byte-by-byte for buffer edge cases.
  /// Uses PVL (Progressive Variable-length Long) encoding per xlang spec.
  FORY_ALWAYS_INLINE uint64_t get_var_uint64(uint32_t offset,
                                             uint32_t *read_bytes_length) {
    if (FORY_PREDICT_FALSE(offset >= size_)) {
      *read_bytes_length = 0;
      return 0;
    }
    // Fast path: need at least 9 bytes for safe bulk read
    if (FORY_PREDICT_TRUE(size_ - offset >= 9)) {
      uint64_t bulk = *reinterpret_cast<uint64_t *>(data_ + offset);

      uint64_t result = bulk & 0x7F;
      if ((bulk & 0x80) == 0) {
        *read_bytes_length = 1;
        return result;
      }
      result |= (bulk >> 1) & 0x3F80;
      if ((bulk & 0x8000) == 0) {
        *read_bytes_length = 2;
        return result;
      }
      result |= (bulk >> 2) & 0x1FC000;
      if ((bulk & 0x800000) == 0) {
        *read_bytes_length = 3;
        return result;
      }
      result |= (bulk >> 3) & 0xFE00000;
      if ((bulk & 0x80000000) == 0) {
        *read_bytes_length = 4;
        return result;
      }
      result |= (bulk >> 4) & 0x7F0000000ULL;
      if ((bulk & 0x8000000000ULL) == 0) {
        *read_bytes_length = 5;
        return result;
      }
      result |= (bulk >> 5) & 0x3F800000000ULL;
      if ((bulk & 0x800000000000ULL) == 0) {
        *read_bytes_length = 6;
        return result;
      }
      result |= (bulk >> 6) & 0x1FC0000000000ULL;
      if ((bulk & 0x80000000000000ULL) == 0) {
        *read_bytes_length = 7;
        return result;
      }
      result |= (bulk >> 7) & 0xFE000000000000ULL;
      if ((bulk & 0x8000000000000000ULL) == 0) {
        *read_bytes_length = 8;
        return result;
      }
      // 9th byte for bits 56-63
      result |= static_cast<uint64_t>(data_[offset + 8]) << 56;
      *read_bytes_length = 9;
      return result;
    }
    // Slow path: byte-by-byte read
    return get_var_uint64_slow(offset, read_bytes_length);
  }

  /// Slow path for get_var_uint64 when not enough bytes for bulk read.
  uint64_t get_var_uint64_slow(uint32_t offset, uint32_t *read_bytes_length) {
    if (FORY_PREDICT_FALSE(offset >= size_)) {
      *read_bytes_length = 0;
      return 0;
    }
    uint32_t position = offset;
    uint64_t result = 0;
    int shift = 0;
    for (int i = 0; i < 8; ++i) {
      if (FORY_PREDICT_FALSE(position >= size_)) {
        *read_bytes_length = 0;
        return 0;
      }
      uint8_t b = data_[position++];
      result |= static_cast<uint64_t>(b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        *read_bytes_length = position - offset;
        return result;
      }
      shift += 7;
    }
    if (FORY_PREDICT_FALSE(position >= size_)) {
      *read_bytes_length = 0;
      return 0;
    }
    uint8_t last = data_[position++];
    result |= static_cast<uint64_t>(last) << 56;
    *read_bytes_length = position - offset;
    return result;
  }

  /// Read uint64_t using tagged encoding at given offset.
  /// Similar to get_var_uint64 but for tagged encoding:
  /// - If bit 0 is 0: read 4 bytes, return value >> 1
  /// - If bit 0 is 1: read 1 byte flag + 8 bytes uint64
  FORY_ALWAYS_INLINE uint64_t get_tagged_uint64(uint32_t offset,
                                                uint32_t *read_bytes_length) {
    uint32_t i = *reinterpret_cast<const uint32_t *>(data_ + offset);
    if ((i & 0b1) != 0b1) {
      *read_bytes_length = 4;
      return static_cast<uint64_t>(i >> 1);
    } else {
      *read_bytes_length = 9;
      return *reinterpret_cast<const uint64_t *>(data_ + offset + 1);
    }
  }

  /// Read int64_t using tagged encoding at given offset.
  /// - If bit 0 is 0: read 4 bytes as signed int, return value >> 1
  /// (arithmetic)
  /// - If bit 0 is 1: read 1 byte flag + 8 bytes int64
  FORY_ALWAYS_INLINE int64_t get_tagged_int64(uint32_t offset,
                                              uint32_t *read_bytes_length) {
    int32_t i = *reinterpret_cast<const int32_t *>(data_ + offset);
    if ((i & 0b1) != 0b1) {
      *read_bytes_length = 4;
      return static_cast<int64_t>(i >> 1); // Arithmetic shift for signed
    } else {
      *read_bytes_length = 9;
      return *reinterpret_cast<const int64_t *>(data_ + offset + 1);
    }
  }

  /// write uint64_t using tagged encoding at given offset. Returns bytes
  /// written.
  /// - If value is in [0, 0x7fffffff]: write 4 bytes (value << 1), return 4
  /// - Otherwise: write 1 byte flag + 8 bytes uint64, return 9
  FORY_ALWAYS_INLINE uint32_t put_tagged_uint64(uint32_t offset,
                                                uint64_t value) {
    constexpr uint64_t MAX_SMALL_VALUE = 0x7fffffff; // INT32_MAX as u64
    if (value <= MAX_SMALL_VALUE) {
      *reinterpret_cast<int32_t *>(data_ + offset) = static_cast<int32_t>(value)
                                                     << 1;
      return 4;
    } else {
      data_[offset] = 0b1;
      *reinterpret_cast<uint64_t *>(data_ + offset + 1) = value;
      return 9;
    }
  }

  /// write int64_t using tagged encoding at given offset. Returns bytes
  /// written.
  /// - If value is in [-1073741824, 1073741823]: write 4 bytes (value << 1),
  /// return 4
  /// - Otherwise: write 1 byte flag + 8 bytes int64, return 9
  FORY_ALWAYS_INLINE uint32_t put_tagged_int64(uint32_t offset, int64_t value) {
    constexpr int64_t MIN_SMALL_VALUE = -1073741824; // -2^30
    constexpr int64_t MAX_SMALL_VALUE = 1073741823;  // 2^30 - 1
    if (value >= MIN_SMALL_VALUE && value <= MAX_SMALL_VALUE) {
      *reinterpret_cast<int32_t *>(data_ + offset) = static_cast<int32_t>(value)
                                                     << 1;
      return 4;
    } else {
      data_[offset] = 0b1;
      *reinterpret_cast<int64_t *>(data_ + offset + 1) = value;
      return 9;
    }
  }

  /// write uint8_t value to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_uint8(uint8_t value) {
    grow(1);
    unsafe_put_byte(writer_index_, value);
    increase_writer_index(1);
  }

  /// write int8_t value to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_int8(int8_t value) {
    grow(1);
    unsafe_put_byte(writer_index_, static_cast<uint8_t>(value));
    increase_writer_index(1);
  }

  /// write uint16_t value as fixed 2 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_uint16(uint16_t value) {
    grow(2);
    unsafe_put<uint16_t>(writer_index_, value);
    increase_writer_index(2);
  }

  /// write int16_t value as fixed 2 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_int16(int16_t value) {
    grow(2);
    unsafe_put<int16_t>(writer_index_, value);
    increase_writer_index(2);
  }

  /// write int24 value as fixed 3 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_int24(int32_t value) {
    grow(3);
    put_int24(writer_index_, value);
    increase_writer_index(3);
  }

  /// write int32_t value as fixed 4 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_int32(int32_t value) {
    grow(4);
    unsafe_put<int32_t>(writer_index_, value);
    increase_writer_index(4);
  }

  /// write uint32_t value as fixed 4 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_uint32(uint32_t value) {
    grow(4);
    unsafe_put<uint32_t>(writer_index_, value);
    increase_writer_index(4);
  }

  /// write int64_t value as fixed 8 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_int64(int64_t value) {
    grow(8);
    unsafe_put<int64_t>(writer_index_, value);
    increase_writer_index(8);
  }

  /// write float value as fixed 4 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_float(float value) {
    grow(4);
    unsafe_put<float>(writer_index_, value);
    increase_writer_index(4);
  }

  /// write double value as fixed 8 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_double(double value) {
    grow(8);
    unsafe_put<double>(writer_index_, value);
    increase_writer_index(8);
  }

  /// write uint32_t value as varint to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_var_uint32(uint32_t value) {
    grow(8); // bulk write may write 8 bytes for varint32
    uint32_t len = put_var_uint32(writer_index_, value);
    increase_writer_index(len);
  }

  /// write int32_t value as varint (zigzag encoded) to buffer at current
  /// writer index. Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_var_int32(int32_t value) {
    uint32_t zigzag = (static_cast<uint32_t>(value) << 1) ^
                      static_cast<uint32_t>(value >> 31);
    write_var_uint32(zigzag);
  }

  /// write uint64_t value as varint to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_var_uint64(uint64_t value) {
    grow(9); // Max 9 bytes for varint64
    uint32_t len = put_var_uint64(writer_index_, value);
    increase_writer_index(len);
  }

  /// write int64_t value as varint (zigzag encoded) to buffer at current
  /// writer index. Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_var_int64(int64_t value) {
    uint64_t zigzag = (static_cast<uint64_t>(value) << 1) ^
                      static_cast<uint64_t>(value >> 63);
    write_var_uint64(zigzag);
  }

  /// write uint64_t value as varuint36small to buffer at current writer index.
  /// This is the special variable-length encoding used for string headers
  /// in xlang protocol. Optimized for small values (< 0x80).
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_var_uint36_small(uint64_t value) {
    grow(8); // Need 8 bytes for safe bulk write
    uint32_t offset = writer_index_;
    if (value < 0x80) {
      data_[offset] = static_cast<uint8_t>(value);
      increase_writer_index(1);
      return;
    }
    // Build encoded value with continuation bits interleaved
    uint64_t encoded = (value & 0x7F) | 0x80;
    encoded |= ((value & 0x3F80) << 1);
    if (value < 0x4000) {
      *reinterpret_cast<uint16_t *>(data_ + offset) =
          static_cast<uint16_t>(encoded);
      increase_writer_index(2);
      return;
    }
    encoded |= ((value & 0x1FC000) << 2) | 0x8000;
    if (value < 0x200000) {
      *reinterpret_cast<uint32_t *>(data_ + offset) =
          static_cast<uint32_t>(encoded);
      increase_writer_index(3);
      return;
    }
    encoded |= ((value & 0xFE00000) << 3) | 0x800000;
    if (value < 0x10000000) {
      *reinterpret_cast<uint32_t *>(data_ + offset) =
          static_cast<uint32_t>(encoded);
      increase_writer_index(4);
      return;
    }
    // 5 bytes: bits 28-35 (up to 36 bits total)
    encoded |= ((value & 0xFF0000000ULL) << 4) | 0x80000000;
    *reinterpret_cast<uint64_t *>(data_ + offset) = encoded;
    increase_writer_index(5);
  }

  /// write raw bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void write_bytes(const void *data, uint32_t length) {
    grow(length);
    unsafe_put(writer_index_, data, length);
    increase_writer_index(length);
  }

  // ===========================================================================
  // Safe read methods with bounds checking
  // All methods accept Error* as parameter for reduced overhead.
  // On success, error->ok() remains true. On failure, error is set.
  // ===========================================================================

  /// Read uint8_t value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint8_t read_uint8(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(1, error))) {
      return 0;
    }
    uint8_t value = data_[reader_index_];
    reader_index_ += 1;
    return value;
  }

  /// Read int8_t value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE int8_t read_int8(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(1, error))) {
      return 0;
    }
    int8_t value = static_cast<int8_t>(data_[reader_index_]);
    reader_index_ += 1;
    return value;
  }

  /// Read uint16_t value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint16_t read_uint16(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(2, error))) {
      return 0;
    }
    uint16_t value =
        reinterpret_cast<const uint16_t *>(data_ + reader_index_)[0];
    reader_index_ += 2;
    return value;
  }

  /// Read int16_t value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE int16_t read_int16(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(2, error))) {
      return 0;
    }
    int16_t value = reinterpret_cast<const int16_t *>(data_ + reader_index_)[0];
    reader_index_ += 2;
    return value;
  }

  /// Read int24 value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE int32_t read_int24(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(3, error))) {
      return 0;
    }
    int32_t b0 = data_[reader_index_];
    int32_t b1 = data_[reader_index_ + 1];
    int32_t b2 = data_[reader_index_ + 2];
    reader_index_ += 3;
    return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16);
  }

  /// Read uint32_t value from buffer (fixed 4 bytes). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE uint32_t read_uint32(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(4, error))) {
      return 0;
    }
    uint32_t value =
        reinterpret_cast<const uint32_t *>(data_ + reader_index_)[0];
    reader_index_ += 4;
    return value;
  }

  /// Read int32_t value from buffer (fixed 4 bytes). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE int32_t read_int32(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(4, error))) {
      return 0;
    }
    int32_t value = reinterpret_cast<const int32_t *>(data_ + reader_index_)[0];
    reader_index_ += 4;
    return value;
  }

  /// Read uint64_t value from buffer (fixed 8 bytes). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE uint64_t read_uint64(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(8, error))) {
      return 0;
    }
    uint64_t value =
        reinterpret_cast<const uint64_t *>(data_ + reader_index_)[0];
    reader_index_ += 8;
    return value;
  }

  /// Read int64_t value from buffer (fixed 8 bytes). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE int64_t read_int64(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(8, error))) {
      return 0;
    }
    int64_t value = reinterpret_cast<const int64_t *>(data_ + reader_index_)[0];
    reader_index_ += 8;
    return value;
  }

  /// Read float value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE float read_float(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(4, error))) {
      return 0.0f;
    }
    float value = reinterpret_cast<const float *>(data_ + reader_index_)[0];
    reader_index_ += 4;
    return value;
  }

  /// Read double value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE double read_double(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(8, error))) {
      return 0.0;
    }
    double value = reinterpret_cast<const double *>(data_ + reader_index_)[0];
    reader_index_ += 8;
    return value;
  }

  /// Read uint32_t value as varint from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint32_t read_var_uint32(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(1, error))) {
      return 0;
    }
    if (FORY_PREDICT_FALSE(size_ - reader_index_ < 5)) {
      return read_var_uint32_slow(error);
    }
    uint32_t offset = reader_index_;
    uint32_t bulk = *reinterpret_cast<uint32_t *>(data_ + offset);

    uint32_t result = bulk & 0x7F;
    if ((bulk & 0x80) == 0) {
      reader_index_ = offset + 1;
      return result;
    }
    result |= (bulk >> 1) & 0x3F80;
    if ((bulk & 0x8000) == 0) {
      reader_index_ = offset + 2;
      return result;
    }
    result |= (bulk >> 2) & 0x1FC000;
    if ((bulk & 0x800000) == 0) {
      reader_index_ = offset + 3;
      return result;
    }
    result |= (bulk >> 3) & 0xFE00000;
    if ((bulk & 0x80000000) == 0) {
      reader_index_ = offset + 4;
      return result;
    }
    result |= static_cast<uint32_t>(data_[offset + 4] & 0x7F) << 28;
    reader_index_ = offset + 5;
    return result;
  }

  /// Read int32_t value as varint (zigzag encoded). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE int32_t read_var_int32(Error &error) {
    uint32_t raw = read_var_uint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return 0;
    }
    return static_cast<int32_t>((raw >> 1) ^ (~(raw & 1) + 1));
  }

  /// Read uint64_t value as varint from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint64_t read_var_uint64(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(1, error))) {
      return 0;
    }
    if (FORY_PREDICT_FALSE(size_ - reader_index_ < 9)) {
      return read_var_uint64_slow(error);
    }
    uint32_t read_bytes = 0;
    uint64_t value = get_var_uint64(reader_index_, &read_bytes);
    if (FORY_PREDICT_FALSE(read_bytes == 0)) {
      error.set_buffer_out_of_bound(reader_index_, 1, size_);
      return 0;
    }
    increase_reader_index(read_bytes, error);
    return value;
  }

  /// Read int64_t value as varint (zigzag encoded). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE int64_t read_var_int64(Error &error) {
    uint64_t raw = read_var_uint64(error);
    return static_cast<int64_t>((raw >> 1) ^ (~(raw & 1) + 1));
  }

  /// write int64_t value using tagged encoding.
  /// If value is in [-1073741824, 1073741823], encode as 4 bytes: ((value as
  /// i32) << 1). Otherwise write as 9 bytes: 0b1 | little-endian 8 bytes i64.
  FORY_ALWAYS_INLINE void write_tagged_int64(int64_t value) {
    constexpr int64_t HALF_MIN_INT_VALUE = -1073741824; // INT32_MIN / 2
    constexpr int64_t HALF_MAX_INT_VALUE = 1073741823;  // INT32_MAX / 2
    if (value >= HALF_MIN_INT_VALUE && value <= HALF_MAX_INT_VALUE) {
      write_int32(static_cast<int32_t>(value) << 1);
    } else {
      grow(9);
      data_[writer_index_] = 0b1;
      unsafe_put<int64_t>(writer_index_ + 1, value);
      increase_writer_index(9);
    }
  }

  /// Read int64_t value using tagged encoding. Sets error on bounds violation.
  /// If bit 0 is 0, return value >> 1 (arithmetic shift).
  /// Otherwise, skip flag byte and read 8 bytes as int64.
  FORY_ALWAYS_INLINE int64_t read_tagged_int64(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(4, error))) {
      return 0;
    }
    int32_t i = reinterpret_cast<const int32_t *>(data_ + reader_index_)[0];
    if ((i & 0b1) != 0b1) {
      reader_index_ += 4;
      return static_cast<int64_t>(i >> 1); // arithmetic right shift
    } else {
      if (FORY_PREDICT_FALSE(!ensure_readable(9, error))) {
        return 0;
      }
      int64_t value =
          reinterpret_cast<const int64_t *>(data_ + reader_index_ + 1)[0];
      reader_index_ += 9;
      return value;
    }
  }

  /// write uint64_t value using tagged encoding.
  /// If value is in [0, 0x7fffffff], encode as 4 bytes: ((value as u32) << 1).
  /// Otherwise write as 9 bytes: 0b1 | little-endian 8 bytes u64.
  FORY_ALWAYS_INLINE void write_tagged_uint64(uint64_t value) {
    constexpr uint64_t MAX_SMALL_VALUE = 0x7fffffff; // INT32_MAX as u64
    if (value <= MAX_SMALL_VALUE) {
      write_int32(static_cast<int32_t>(value) << 1);
    } else {
      grow(9);
      data_[writer_index_] = 0b1;
      unsafe_put<uint64_t>(writer_index_ + 1, value);
      increase_writer_index(9);
    }
  }

  /// Read uint64_t value using tagged encoding. Sets error on bounds violation.
  /// If bit 0 is 0, return value >> 1.
  /// Otherwise, skip flag byte and read 8 bytes as uint64.
  FORY_ALWAYS_INLINE uint64_t read_tagged_uint64(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(4, error))) {
      return 0;
    }
    uint32_t i = reinterpret_cast<const uint32_t *>(data_ + reader_index_)[0];
    if ((i & 0b1) != 0b1) {
      reader_index_ += 4;
      return static_cast<uint64_t>(i >> 1);
    } else {
      if (FORY_PREDICT_FALSE(!ensure_readable(9, error))) {
        return 0;
      }
      uint64_t value =
          reinterpret_cast<const uint64_t *>(data_ + reader_index_ + 1)[0];
      reader_index_ += 9;
      return value;
    }
  }

  /// Read uint64_t value as varuint36small. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint64_t read_var_uint36_small(Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(1, error))) {
      return 0;
    }
    uint32_t offset = reader_index_;
    if (FORY_PREDICT_FALSE(size_ - offset < 8)) {
      return read_var_uint36_small_slow(error);
    }
    // Fast path: need at least 8 bytes for safe bulk read.
    uint64_t bulk = *reinterpret_cast<uint64_t *>(data_ + offset);
    uint64_t result = bulk & 0x7F;
    if ((bulk & 0x80) == 0) {
      increase_reader_index(1, error);
      return result;
    }
    result |= (bulk >> 1) & 0x3F80;
    if ((bulk & 0x8000) == 0) {
      increase_reader_index(2, error);
      return result;
    }
    result |= (bulk >> 2) & 0x1FC000;
    if ((bulk & 0x800000) == 0) {
      increase_reader_index(3, error);
      return result;
    }
    result |= (bulk >> 3) & 0xFE00000;
    if ((bulk & 0x80000000) == 0) {
      increase_reader_index(4, error);
      return result;
    }
    // 5th byte for bits 28-35 (up to 36 bits)
    result |= (bulk >> 4) & 0xFF0000000ULL;
    increase_reader_index(5, error);
    return result;
  }

  /// Read raw bytes from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE void read_bytes(void *data, uint32_t length,
                                     Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(length, error))) {
      return;
    }
    copy(reader_index_, length, static_cast<uint8_t *>(data));
    increase_reader_index(length, error);
  }

  /// skip bytes in buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE void skip(uint32_t length, Error &error) {
    if (FORY_PREDICT_FALSE(!ensure_readable(length, error))) {
      return;
    }
    increase_reader_index(length, error);
  }

  /// Return true if both buffers are the same size and contain the same bytes
  /// up to the number of compared bytes
  bool equals(const Buffer &other, int64_t nbytes) const;

  /// Return true if both buffers are the same size and contain the same bytes
  bool equals(const Buffer &other) const;

  FORY_ALWAYS_INLINE void grow(uint32_t min_capacity) {
    uint32_t len = writer_index_ + min_capacity;
    if (len > size_) {
      // NOTE: over allocate by 1.5 or 2 ?
      // see: Doubling isn't a great overallocation practice
      // see
      // https://github.com/facebook/folly/blob/master/folly/docs/FBVector.md
      // for discussion.
      auto new_size = util::round_number_of_bytes_to_nearest_word(len * 2);
      reserve(new_size);
    }
  }

  /// reserve buffer to new_size
  void reserve(uint32_t new_size) {
    if (new_size > size_) {
      if (wrapped_vector_) {
        // Resize the underlying vector - zero-copy path
        wrapped_vector_->resize(new_size);
        data_ = wrapped_vector_->data();
        size_ = new_size;
      } else {
        uint8_t *new_ptr;
        if (own_data_) {
          new_ptr = static_cast<uint8_t *>(
              realloc(data_, static_cast<size_t>(new_size)));
        } else {
          new_ptr =
              static_cast<uint8_t *>(malloc(static_cast<size_t>(new_size)));
          if (new_ptr) {
            // copy existing data before switching to owned buffer
            if (data_ && size_ > 0) {
              std::memcpy(new_ptr, data_, size_);
            }
            own_data_ = true;
          }
        }
        if (new_ptr) {
          data_ = new_ptr;
          size_ = new_size;
        } else {
          FORY_CHECK(false)
              << "Out of memory when grow buffer, needed_size " << size_;
        }
      }
    }
  }

  /// Check if this buffer wraps a vector.
  bool wraps_vector() const { return wrapped_vector_ != nullptr; }

  /// copy a section of the buffer into a new Buffer.
  void copy(uint32_t start, uint32_t nbytes,
            std::shared_ptr<Buffer> &out) const;

  /// copy a section of the buffer into a new Buffer.
  void copy(uint32_t start, uint32_t nbytes, Buffer &out) const;

  /// copy a section of the buffer.
  void copy(uint32_t start, uint32_t nbytes, uint8_t *out) const;

  /// copy a section of the buffer.
  void copy(uint32_t start, uint32_t nbytes, uint8_t *out,
            uint32_t offset) const;

  /// copy data from `src` yo buffer
  void copy_from(uint32_t offset, const uint8_t *src, uint32_t src_offset,
                 uint32_t nbytes);

  /// Zero all bytes in padding
  void zero_padding() {
    // A zero-size buffer_ can have a null data pointer
    if (size_ != 0) {
      memset(data_, 0, static_cast<size_t>(size_));
    }
  }

  /// \brief copy buffer contents into a new std::string
  /// \return std::string
  /// \note Can throw std::bad_alloc if buffer is large
  std::string to_string() const;

  std::string hex() const;

private:
  FORY_ALWAYS_INLINE bool fill_to(uint32_t target_size, Error &error) {
    if (FORY_PREDICT_TRUE(target_size <= size_)) {
      return true;
    }
    if (FORY_PREDICT_TRUE(stream_ == nullptr)) {
      return false;
    }
    stream_->reader_index(reader_index_);
    auto fill_result = stream_->fill_buffer(target_size - reader_index_);
    if (FORY_PREDICT_FALSE(!fill_result.ok())) {
      error = std::move(fill_result).error();
      return false;
    }
    data_ = stream_->data();
    size_ = stream_->size();
    reader_index_ = stream_->reader_index();
    writer_index_ = size_;
    return true;
  }

  FORY_ALWAYS_INLINE bool ensure_readable(uint32_t length, Error &error) {
    const uint64_t target = static_cast<uint64_t>(reader_index_) + length;
    if (FORY_PREDICT_TRUE(target <= size_)) {
      return true;
    }
    if (FORY_PREDICT_FALSE(!ensure_size(target, error))) {
      if (error.ok()) {
        error.set_buffer_out_of_bound(reader_index_, length, size_);
      }
      return false;
    }
    if (FORY_PREDICT_FALSE(target > size_)) {
      error.set_buffer_out_of_bound(reader_index_, length, size_);
      return false;
    }
    return true;
  }

  FORY_ALWAYS_INLINE uint32_t read_var_uint32_slow(Error &error) {
    uint32_t position = reader_index_;
    uint32_t result = 0;
    for (int i = 0; i < 5; ++i) {
      const uint64_t target = static_cast<uint64_t>(position) + 1;
      if (FORY_PREDICT_FALSE(!ensure_size(target, error))) {
        return 0;
      }
      uint8_t b = data_[position++];
      result |= static_cast<uint32_t>(b & 0x7F) << (i * 7);
      if ((b & 0x80) == 0) {
        reader_index_ = position;
        return result;
      }
    }
    error.set_error(ErrorCode::InvalidData, "Invalid var_uint32 encoding");
    return 0;
  }

  FORY_ALWAYS_INLINE uint64_t read_var_uint64_slow(Error &error) {
    uint32_t position = reader_index_;
    uint64_t result = 0;
    for (int i = 0; i < 8; ++i) {
      const uint64_t target = static_cast<uint64_t>(position) + 1;
      if (FORY_PREDICT_FALSE(!ensure_size(target, error))) {
        return 0;
      }
      uint8_t b = data_[position++];
      result |= static_cast<uint64_t>(b & 0x7F) << (i * 7);
      if ((b & 0x80) == 0) {
        reader_index_ = position;
        return result;
      }
    }
    const uint64_t target = static_cast<uint64_t>(position) + 1;
    if (FORY_PREDICT_FALSE(!ensure_size(target, error))) {
      return 0;
    }
    uint8_t b = data_[position++];
    result |= static_cast<uint64_t>(b) << 56;
    reader_index_ = position;
    return result;
  }

  FORY_ALWAYS_INLINE uint64_t read_var_uint36_small_slow(Error &error) {
    uint32_t position = reader_index_;
    const uint64_t first_target = static_cast<uint64_t>(position) + 1;
    if (FORY_PREDICT_FALSE(!ensure_size(first_target, error))) {
      return 0;
    }
    uint8_t b = data_[position++];
    uint64_t result = b & 0x7F;
    if ((b & 0x80) == 0) {
      reader_index_ = position;
      return result;
    }

    const uint64_t second_target = static_cast<uint64_t>(position) + 1;
    if (FORY_PREDICT_FALSE(!ensure_size(second_target, error))) {
      return 0;
    }
    b = data_[position++];
    result |= static_cast<uint64_t>(b & 0x7F) << 7;
    if ((b & 0x80) == 0) {
      reader_index_ = position;
      return result;
    }

    const uint64_t third_target = static_cast<uint64_t>(position) + 1;
    if (FORY_PREDICT_FALSE(!ensure_size(third_target, error))) {
      return 0;
    }
    b = data_[position++];
    result |= static_cast<uint64_t>(b & 0x7F) << 14;
    if ((b & 0x80) == 0) {
      reader_index_ = position;
      return result;
    }

    const uint64_t fourth_target = static_cast<uint64_t>(position) + 1;
    if (FORY_PREDICT_FALSE(!ensure_size(fourth_target, error))) {
      return 0;
    }
    b = data_[position++];
    result |= static_cast<uint64_t>(b & 0x7F) << 21;
    if ((b & 0x80) == 0) {
      reader_index_ = position;
      return result;
    }

    const uint64_t fifth_target = static_cast<uint64_t>(position) + 1;
    if (FORY_PREDICT_FALSE(!ensure_size(fifth_target, error))) {
      return 0;
    }
    b = data_[position++];
    result |= static_cast<uint64_t>(b) << 28;
    reader_index_ = position;
    return result;
  }

  uint8_t *data_;
  uint32_t size_;
  bool own_data_;
  uint32_t writer_index_;
  uint32_t reader_index_;
  std::vector<uint8_t> *wrapped_vector_ = nullptr;
  ForyInputStream *stream_ = nullptr;
  std::shared_ptr<ForyInputStream> stream_owner_;
};

/// \brief Allocate a fixed-size mutable buffer from the default memory pool
///
/// \param[in] size size of buffer to allocate
/// \param[out] out the allocated buffer (contains padding)
///
/// \return success or not
bool allocate_buffer(uint32_t size, std::shared_ptr<Buffer> *out);

bool allocate_buffer(uint32_t size, Buffer **out);

Buffer *allocate_buffer(uint32_t size);

} // namespace fory
