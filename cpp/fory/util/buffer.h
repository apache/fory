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

namespace fory {

// A buffer class for storing raw bytes with various methods for reading and
// writing the bytes.
class Buffer {
public:
  Buffer();

  Buffer(uint8_t *data, uint32_t size, bool own_data = true)
      : data_(data), size_(size), own_data_(own_data),
        wrapped_vector_(nullptr) {
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
        reader_index_(0), wrapped_vector_(&vec) {}

  Buffer(Buffer &&buffer) noexcept;

  Buffer &operator=(Buffer &&buffer) noexcept;

  virtual ~Buffer();

  /// \brief Return a pointer to the buffer's data
  FORY_ALWAYS_INLINE uint8_t *data() const { return data_; }

  /// \brief Return the buffer's size in bytes
  FORY_ALWAYS_INLINE uint32_t size() const { return size_; }

  FORY_ALWAYS_INLINE bool own_data() const { return own_data_; }

  FORY_ALWAYS_INLINE uint32_t writer_index() { return writer_index_; }

  FORY_ALWAYS_INLINE uint32_t reader_index() { return reader_index_; }

  /// \brief Return the remaining bytes available for reading
  FORY_ALWAYS_INLINE uint32_t remaining_size() const {
    return size_ - reader_index_;
  }

  FORY_ALWAYS_INLINE void WriterIndex(uint32_t writer_index) {
    FORY_CHECK(writer_index < std::numeric_limits<uint32_t>::max())
        << "Buffer overflow writer_index" << writer_index_
        << " target writer_index " << writer_index;
    writer_index_ = writer_index;
  }

  FORY_ALWAYS_INLINE void IncreaseWriterIndex(uint32_t diff) {
    uint64_t writer_index = writer_index_ + diff;
    FORY_CHECK(writer_index < std::numeric_limits<uint32_t>::max())
        << "Buffer overflow writer_index" << writer_index_ << " diff " << diff;
    writer_index_ = writer_index;
  }

  FORY_ALWAYS_INLINE void ReaderIndex(uint32_t reader_index) {
    FORY_CHECK(reader_index < std::numeric_limits<uint32_t>::max())
        << "Buffer overflow reader_index" << reader_index_
        << " target reader_index " << reader_index;
    reader_index_ = reader_index;
  }

  FORY_ALWAYS_INLINE void IncreaseReaderIndex(uint32_t diff) {
    uint64_t reader_index = reader_index_ + diff;
    FORY_CHECK(reader_index < std::numeric_limits<uint32_t>::max())
        << "Buffer overflow reader_index" << reader_index_ << " diff " << diff;
    reader_index_ = reader_index;
  }

  // Unsafe methods don't check bound
  template <typename T>
  FORY_ALWAYS_INLINE void UnsafePut(uint32_t offset, T value) {
    reinterpret_cast<T *>(data_ + offset)[0] = value;
  }

  template <typename T> FORY_ALWAYS_INLINE T UnsafeGet(uint32_t offset) {
    return reinterpret_cast<const T *>(data_ + offset)[0];
  }

  template <typename T, typename = std::enable_if_t<std::disjunction_v<
                            std::is_same<T, int8_t>, std::is_same<T, uint8_t>,
                            std::is_same<T, bool>>>>
  FORY_ALWAYS_INLINE T UnsafeGetByteAs(uint32_t offset) {
    return data_[offset];
  }

  template <typename T, typename = std::enable_if_t<std::disjunction_v<
                            std::is_same<T, int8_t>, std::is_same<T, uint8_t>,
                            std::is_same<T, bool>>>>
  FORY_ALWAYS_INLINE void UnsafePutByte(uint32_t offset, T value) {
    data_[offset] = value;
  }

  FORY_ALWAYS_INLINE void UnsafePut(uint32_t offset, const void *data,
                                    const uint32_t length) {
    memcpy(data_ + offset, data, (size_t)length);
  }

  FORY_ALWAYS_INLINE void PutInt24(uint32_t offset, int32_t value) {
    data_[offset] = static_cast<uint8_t>(value);
    data_[offset + 1] = static_cast<uint8_t>(value >> 8);
    data_[offset + 2] = static_cast<uint8_t>(value >> 16);
  }

  template <typename T> FORY_ALWAYS_INLINE T Get(uint32_t relative_offset) {
    FORY_CHECK(relative_offset < size_) << "Out of range " << relative_offset
                                        << " should be less than " << size_;
    T value = reinterpret_cast<const T *>(data_ + relative_offset)[0];
    return value;
  }

  template <typename T, typename = std::enable_if_t<std::disjunction_v<
                            std::is_same<T, int8_t>, std::is_same<T, uint8_t>,
                            std::is_same<T, bool>>>>
  FORY_ALWAYS_INLINE T GetByteAs(uint32_t relative_offset) {
    FORY_CHECK(relative_offset < size_) << "Out of range " << relative_offset
                                        << " should be less than " << size_;
    return data_[relative_offset];
  }

  FORY_ALWAYS_INLINE bool GetBool(uint32_t offset) {
    return GetByteAs<bool>(offset);
  }

  FORY_ALWAYS_INLINE int8_t GetInt8(uint32_t offset) {
    return GetByteAs<int8_t>(offset);
  }

  FORY_ALWAYS_INLINE int16_t GetInt16(uint32_t offset) {
    return Get<int16_t>(offset);
  }

  FORY_ALWAYS_INLINE int32_t GetInt24(uint32_t offset) {
    FORY_CHECK(offset + 3 <= size_)
        << "Out of range " << offset << " should be less than " << size_;
    int32_t b0 = data_[offset];
    int32_t b1 = data_[offset + 1];
    int32_t b2 = data_[offset + 2];
    return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16);
  }

  FORY_ALWAYS_INLINE int32_t GetInt32(uint32_t offset) {
    return Get<int32_t>(offset);
  }

  FORY_ALWAYS_INLINE int64_t GetInt64(uint32_t offset) {
    return Get<int64_t>(offset);
  }

  FORY_ALWAYS_INLINE float GetFloat(uint32_t offset) {
    return Get<float>(offset);
  }

  FORY_ALWAYS_INLINE double GetDouble(uint32_t offset) {
    return Get<double>(offset);
  }

  FORY_ALWAYS_INLINE Result<void, Error>
  GetBytesAsInt64(uint32_t offset, uint32_t length, int64_t *target) {
    if (length == 0) {
      *target = 0;
      return Result<void, Error>();
    }
    if (size_ - (offset + 8) > 0) {
      uint64_t mask = 0xffffffffffffffff;
      uint64_t x = (mask >> (8 - length) * 8);
      *target = GetInt64(offset) & x;
    } else {
      if (size_ - (offset + length) < 0) {
        return Unexpected(Error::out_of_bound("buffer out of bound"));
      }
      int64_t result = 0;
      for (size_t i = 0; i < length; i++) {
        result = result | ((int64_t)(data_[offset + i])) << (i * 8);
      }
      *target = result;
    }
    return Result<void, Error>();
  }

  /// Put unsigned varint32 at offset using optimized bulk writes.
  /// Returns number of bytes written (1-5).
  /// Uses bit manipulation to build encoded value, then single memory write.
  FORY_ALWAYS_INLINE uint32_t PutVarUint32(uint32_t offset, uint32_t value) {
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

  /// Get unsigned varint32 from offset using optimized bulk read.
  /// Fast path: bulk read 4 bytes + bit extraction when enough bytes available.
  /// Slow path: byte-by-byte for buffer edge cases.
  FORY_ALWAYS_INLINE uint32_t GetVarUint32(uint32_t offset,
                                           uint32_t *readBytesLength) {
    // Fast path: need at least 5 bytes for safe bulk read (4 bytes + potential
    // 5th)
    if (FORY_PREDICT_TRUE(size_ - offset >= 5)) {
      uint32_t bulk = *reinterpret_cast<uint32_t *>(data_ + offset);

      uint32_t result = bulk & 0x7F;
      if ((bulk & 0x80) == 0) {
        *readBytesLength = 1;
        return result;
      }
      // Extract bits 7-13 from bulk (at positions 8-14 after shift)
      result |= (bulk >> 1) & 0x3F80;
      if ((bulk & 0x8000) == 0) {
        *readBytesLength = 2;
        return result;
      }
      // Extract bits 14-20 from bulk (at positions 16-22 after shift)
      result |= (bulk >> 2) & 0x1FC000;
      if ((bulk & 0x800000) == 0) {
        *readBytesLength = 3;
        return result;
      }
      // Extract bits 21-27 from bulk (at positions 24-30 after shift)
      result |= (bulk >> 3) & 0xFE00000;
      if ((bulk & 0x80000000) == 0) {
        *readBytesLength = 4;
        return result;
      }
      // 5th byte for bits 28-31 (only 4 bits used for uint32, but mask with
      // 0x7F per varint spec)
      result |= static_cast<uint32_t>(data_[offset + 4] & 0x7F) << 28;
      *readBytesLength = 5;
      return result;
    }
    // Slow path: byte-by-byte read
    return GetVarUint32Slow(offset, readBytesLength);
  }

  /// Slow path for GetVarUint32 when not enough bytes for bulk read.
  uint32_t GetVarUint32Slow(uint32_t offset, uint32_t *readBytesLength) {
    uint32_t position = offset;
    int b = data_[position++];
    uint32_t result = b & 0x7F;
    if ((b & 0x80) != 0) {
      b = data_[position++];
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = data_[position++];
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = data_[position++];
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = data_[position++];
            result |= (b & 0x7F) << 28;
          }
        }
      }
    }
    *readBytesLength = position - offset;
    return result;
  }

  /// Put unsigned varint64 at offset using optimized bulk writes.
  /// Returns number of bytes written (1-9).
  /// Uses PVL (Progressive Variable-length Long) encoding per xlang spec.
  FORY_ALWAYS_INLINE uint32_t PutVarUint64(uint32_t offset, uint64_t value) {
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

  /// Get unsigned varint64 from offset using optimized bulk read.
  /// Fast path: bulk read 8 bytes + bit extraction when enough bytes available.
  /// Slow path: byte-by-byte for buffer edge cases.
  /// Uses PVL (Progressive Variable-length Long) encoding per xlang spec.
  FORY_ALWAYS_INLINE uint64_t GetVarUint64(uint32_t offset,
                                           uint32_t *readBytesLength) {
    // Fast path: need at least 9 bytes for safe bulk read
    if (FORY_PREDICT_TRUE(size_ - offset >= 9)) {
      uint64_t bulk = *reinterpret_cast<uint64_t *>(data_ + offset);

      uint64_t result = bulk & 0x7F;
      if ((bulk & 0x80) == 0) {
        *readBytesLength = 1;
        return result;
      }
      result |= (bulk >> 1) & 0x3F80;
      if ((bulk & 0x8000) == 0) {
        *readBytesLength = 2;
        return result;
      }
      result |= (bulk >> 2) & 0x1FC000;
      if ((bulk & 0x800000) == 0) {
        *readBytesLength = 3;
        return result;
      }
      result |= (bulk >> 3) & 0xFE00000;
      if ((bulk & 0x80000000) == 0) {
        *readBytesLength = 4;
        return result;
      }
      result |= (bulk >> 4) & 0x7F0000000ULL;
      if ((bulk & 0x8000000000ULL) == 0) {
        *readBytesLength = 5;
        return result;
      }
      result |= (bulk >> 5) & 0x3F800000000ULL;
      if ((bulk & 0x800000000000ULL) == 0) {
        *readBytesLength = 6;
        return result;
      }
      result |= (bulk >> 6) & 0x1FC0000000000ULL;
      if ((bulk & 0x80000000000000ULL) == 0) {
        *readBytesLength = 7;
        return result;
      }
      result |= (bulk >> 7) & 0xFE000000000000ULL;
      if ((bulk & 0x8000000000000000ULL) == 0) {
        *readBytesLength = 8;
        return result;
      }
      // 9th byte for bits 56-63
      result |= static_cast<uint64_t>(data_[offset + 8]) << 56;
      *readBytesLength = 9;
      return result;
    }
    // Slow path: byte-by-byte read
    return GetVarUint64Slow(offset, readBytesLength);
  }

  /// Slow path for GetVarUint64 when not enough bytes for bulk read.
  uint64_t GetVarUint64Slow(uint32_t offset, uint32_t *readBytesLength) {
    uint32_t position = offset;
    uint64_t result = 0;
    int shift = 0;
    for (int i = 0; i < 8; ++i) {
      uint8_t b = data_[position++];
      result |= static_cast<uint64_t>(b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        *readBytesLength = position - offset;
        return result;
      }
      shift += 7;
    }
    uint8_t last = data_[position++];
    result |= static_cast<uint64_t>(last) << 56;
    *readBytesLength = position - offset;
    return result;
  }

  /// Read uint64_t using tagged encoding at given offset.
  /// Similar to GetVarUint64 but for tagged encoding:
  /// - If bit 0 is 0: read 4 bytes, return value >> 1
  /// - If bit 0 is 1: read 1 byte flag + 8 bytes uint64
  FORY_ALWAYS_INLINE uint64_t GetTaggedUint64(uint32_t offset,
                                              uint32_t *readBytesLength) {
    uint32_t i = *reinterpret_cast<const uint32_t *>(data_ + offset);
    if ((i & 0b1) != 0b1) {
      *readBytesLength = 4;
      return static_cast<uint64_t>(i >> 1);
    } else {
      *readBytesLength = 9;
      return *reinterpret_cast<const uint64_t *>(data_ + offset + 1);
    }
  }

  /// Read int64_t using tagged encoding at given offset.
  /// - If bit 0 is 0: read 4 bytes as signed int, return value >> 1
  /// (arithmetic)
  /// - If bit 0 is 1: read 1 byte flag + 8 bytes int64
  FORY_ALWAYS_INLINE int64_t GetTaggedInt64(uint32_t offset,
                                            uint32_t *readBytesLength) {
    int32_t i = *reinterpret_cast<const int32_t *>(data_ + offset);
    if ((i & 0b1) != 0b1) {
      *readBytesLength = 4;
      return static_cast<int64_t>(i >> 1); // Arithmetic shift for signed
    } else {
      *readBytesLength = 9;
      return *reinterpret_cast<const int64_t *>(data_ + offset + 1);
    }
  }

  /// Write uint64_t using tagged encoding at given offset. Returns bytes
  /// written.
  /// - If value is in [0, 0x7fffffff]: write 4 bytes (value << 1), return 4
  /// - Otherwise: write 1 byte flag + 8 bytes uint64, return 9
  FORY_ALWAYS_INLINE uint32_t PutTaggedUint64(uint32_t offset, uint64_t value) {
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

  /// Write int64_t using tagged encoding at given offset. Returns bytes
  /// written.
  /// - If value is in [-1073741824, 1073741823]: write 4 bytes (value << 1),
  /// return 4
  /// - Otherwise: write 1 byte flag + 8 bytes int64, return 9
  FORY_ALWAYS_INLINE uint32_t PutTaggedInt64(uint32_t offset, int64_t value) {
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

  /// Write uint8_t value to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteUint8(uint8_t value) {
    Grow(1);
    UnsafePutByte(writer_index_, value);
    IncreaseWriterIndex(1);
  }

  /// Write int8_t value to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteInt8(int8_t value) {
    Grow(1);
    UnsafePutByte(writer_index_, static_cast<uint8_t>(value));
    IncreaseWriterIndex(1);
  }

  /// Write uint16_t value as fixed 2 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteUint16(uint16_t value) {
    Grow(2);
    UnsafePut<uint16_t>(writer_index_, value);
    IncreaseWriterIndex(2);
  }

  /// Write int16_t value as fixed 2 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteInt16(int16_t value) {
    Grow(2);
    UnsafePut<int16_t>(writer_index_, value);
    IncreaseWriterIndex(2);
  }

  /// Write int24 value as fixed 3 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteInt24(int32_t value) {
    Grow(3);
    PutInt24(writer_index_, value);
    IncreaseWriterIndex(3);
  }

  /// Write int32_t value as fixed 4 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteInt32(int32_t value) {
    Grow(4);
    UnsafePut<int32_t>(writer_index_, value);
    IncreaseWriterIndex(4);
  }

  /// Write uint32_t value as fixed 4 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteUint32(uint32_t value) {
    Grow(4);
    UnsafePut<uint32_t>(writer_index_, value);
    IncreaseWriterIndex(4);
  }

  /// Write int64_t value as fixed 8 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteInt64(int64_t value) {
    Grow(8);
    UnsafePut<int64_t>(writer_index_, value);
    IncreaseWriterIndex(8);
  }

  /// Write float value as fixed 4 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteFloat(float value) {
    Grow(4);
    UnsafePut<float>(writer_index_, value);
    IncreaseWriterIndex(4);
  }

  /// Write double value as fixed 8 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteDouble(double value) {
    Grow(8);
    UnsafePut<double>(writer_index_, value);
    IncreaseWriterIndex(8);
  }

  /// Write uint32_t value as varint to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteVarUint32(uint32_t value) {
    Grow(8); // bulk write may write 8 bytes for varint32
    uint32_t len = PutVarUint32(writer_index_, value);
    IncreaseWriterIndex(len);
  }

  /// Write int32_t value as varint (zigzag encoded) to buffer at current
  /// writer index. Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteVarInt32(int32_t value) {
    uint32_t zigzag = (static_cast<uint32_t>(value) << 1) ^
                      static_cast<uint32_t>(value >> 31);
    WriteVarUint32(zigzag);
  }

  /// Write uint64_t value as varint to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteVarUint64(uint64_t value) {
    Grow(9); // Max 9 bytes for varint64
    uint32_t len = PutVarUint64(writer_index_, value);
    IncreaseWriterIndex(len);
  }

  /// Write int64_t value as varint (zigzag encoded) to buffer at current
  /// writer index. Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteVarInt64(int64_t value) {
    uint64_t zigzag = (static_cast<uint64_t>(value) << 1) ^
                      static_cast<uint64_t>(value >> 63);
    WriteVarUint64(zigzag);
  }

  /// Write uint64_t value as varuint36small to buffer at current writer index.
  /// This is the special variable-length encoding used for string headers
  /// in xlang protocol. Optimized for small values (< 0x80).
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteVarUint36Small(uint64_t value) {
    Grow(8); // Need 8 bytes for safe bulk write
    uint32_t offset = writer_index_;
    if (value < 0x80) {
      data_[offset] = static_cast<uint8_t>(value);
      IncreaseWriterIndex(1);
      return;
    }
    // Build encoded value with continuation bits interleaved
    uint64_t encoded = (value & 0x7F) | 0x80;
    encoded |= ((value & 0x3F80) << 1);
    if (value < 0x4000) {
      *reinterpret_cast<uint16_t *>(data_ + offset) =
          static_cast<uint16_t>(encoded);
      IncreaseWriterIndex(2);
      return;
    }
    encoded |= ((value & 0x1FC000) << 2) | 0x8000;
    if (value < 0x200000) {
      *reinterpret_cast<uint32_t *>(data_ + offset) =
          static_cast<uint32_t>(encoded);
      IncreaseWriterIndex(3);
      return;
    }
    encoded |= ((value & 0xFE00000) << 3) | 0x800000;
    if (value < 0x10000000) {
      *reinterpret_cast<uint32_t *>(data_ + offset) =
          static_cast<uint32_t>(encoded);
      IncreaseWriterIndex(4);
      return;
    }
    // 5 bytes: bits 28-35 (up to 36 bits total)
    encoded |= ((value & 0xFF0000000ULL) << 4) | 0x80000000;
    *reinterpret_cast<uint64_t *>(data_ + offset) = encoded;
    IncreaseWriterIndex(5);
  }

  /// Write raw bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  FORY_ALWAYS_INLINE void WriteBytes(const void *data, uint32_t length) {
    Grow(length);
    UnsafePut(writer_index_, data, length);
    IncreaseWriterIndex(length);
  }

  // ===========================================================================
  // Safe read methods with bounds checking
  // All methods accept Error* as parameter for reduced overhead.
  // On success, error->ok() remains true. On failure, error is set.
  // ===========================================================================

  /// Read uint8_t value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint8_t ReadUint8(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 1 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 1, size_);
      return 0;
    }
    uint8_t value = data_[reader_index_];
    reader_index_ += 1;
    return value;
  }

  /// Read int8_t value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE int8_t ReadInt8(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 1 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 1, size_);
      return 0;
    }
    int8_t value = static_cast<int8_t>(data_[reader_index_]);
    reader_index_ += 1;
    return value;
  }

  /// Read uint16_t value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint16_t ReadUint16(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 2 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 2, size_);
      return 0;
    }
    uint16_t value =
        reinterpret_cast<const uint16_t *>(data_ + reader_index_)[0];
    reader_index_ += 2;
    return value;
  }

  /// Read int16_t value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE int16_t ReadInt16(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 2 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 2, size_);
      return 0;
    }
    int16_t value = reinterpret_cast<const int16_t *>(data_ + reader_index_)[0];
    reader_index_ += 2;
    return value;
  }

  /// Read int24 value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE int32_t ReadInt24(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 3 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 3, size_);
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
  FORY_ALWAYS_INLINE uint32_t ReadUint32(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 4 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 4, size_);
      return 0;
    }
    uint32_t value =
        reinterpret_cast<const uint32_t *>(data_ + reader_index_)[0];
    reader_index_ += 4;
    return value;
  }

  /// Read int32_t value from buffer (fixed 4 bytes). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE int32_t ReadInt32(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 4 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 4, size_);
      return 0;
    }
    int32_t value = reinterpret_cast<const int32_t *>(data_ + reader_index_)[0];
    reader_index_ += 4;
    return value;
  }

  /// Read uint64_t value from buffer (fixed 8 bytes). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE uint64_t ReadUint64(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 8 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 8, size_);
      return 0;
    }
    uint64_t value =
        reinterpret_cast<const uint64_t *>(data_ + reader_index_)[0];
    reader_index_ += 8;
    return value;
  }

  /// Read int64_t value from buffer (fixed 8 bytes). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE int64_t ReadInt64(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 8 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 8, size_);
      return 0;
    }
    int64_t value = reinterpret_cast<const int64_t *>(data_ + reader_index_)[0];
    reader_index_ += 8;
    return value;
  }

  /// Read float value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE float ReadFloat(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 4 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 4, size_);
      return 0.0f;
    }
    float value = reinterpret_cast<const float *>(data_ + reader_index_)[0];
    reader_index_ += 4;
    return value;
  }

  /// Read double value from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE double ReadDouble(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 8 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 8, size_);
      return 0.0;
    }
    double value = reinterpret_cast<const double *>(data_ + reader_index_)[0];
    reader_index_ += 8;
    return value;
  }

  /// Read uint32_t value as varint from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint32_t ReadVarUint32(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 1 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 1, size_);
      return 0;
    }
    uint32_t read_bytes = 0;
    uint32_t value = GetVarUint32(reader_index_, &read_bytes);
    IncreaseReaderIndex(read_bytes);
    return value;
  }

  /// Read int32_t value as varint (zigzag encoded). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE int32_t ReadVarInt32(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 1 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 1, size_);
      return 0;
    }
    uint32_t read_bytes = 0;
    uint32_t raw = GetVarUint32(reader_index_, &read_bytes);
    IncreaseReaderIndex(read_bytes);
    return static_cast<int32_t>((raw >> 1) ^ (~(raw & 1) + 1));
  }

  /// Read uint64_t value as varint from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint64_t ReadVarUint64(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 1 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 1, size_);
      return 0;
    }
    uint32_t read_bytes = 0;
    uint64_t value = GetVarUint64(reader_index_, &read_bytes);
    IncreaseReaderIndex(read_bytes);
    return value;
  }

  /// Read int64_t value as varint (zigzag encoded). Sets error on bounds
  /// violation.
  FORY_ALWAYS_INLINE int64_t ReadVarInt64(Error &error) {
    uint64_t raw = ReadVarUint64(error);
    return static_cast<int64_t>((raw >> 1) ^ (~(raw & 1) + 1));
  }

  /// Write int64_t value using tagged encoding.
  /// If value is in [-1073741824, 1073741823], encode as 4 bytes: ((value as
  /// i32) << 1). Otherwise write as 9 bytes: 0b1 | little-endian 8 bytes i64.
  FORY_ALWAYS_INLINE void WriteTaggedInt64(int64_t value) {
    constexpr int64_t HALF_MIN_INT_VALUE = -1073741824; // INT32_MIN / 2
    constexpr int64_t HALF_MAX_INT_VALUE = 1073741823;  // INT32_MAX / 2
    if (value >= HALF_MIN_INT_VALUE && value <= HALF_MAX_INT_VALUE) {
      WriteInt32(static_cast<int32_t>(value) << 1);
    } else {
      Grow(9);
      data_[writer_index_] = 0b1;
      UnsafePut<int64_t>(writer_index_ + 1, value);
      IncreaseWriterIndex(9);
    }
  }

  /// Read int64_t value using tagged encoding. Sets error on bounds violation.
  /// If bit 0 is 0, return value >> 1 (arithmetic shift).
  /// Otherwise, skip flag byte and read 8 bytes as int64.
  FORY_ALWAYS_INLINE int64_t ReadTaggedInt64(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 4 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 4, size_);
      return 0;
    }
    int32_t i = reinterpret_cast<const int32_t *>(data_ + reader_index_)[0];
    if ((i & 0b1) != 0b1) {
      reader_index_ += 4;
      return static_cast<int64_t>(i >> 1); // arithmetic right shift
    } else {
      if (FORY_PREDICT_FALSE(reader_index_ + 9 > size_)) {
        error.set_buffer_out_of_bound(reader_index_, 9, size_);
        return 0;
      }
      int64_t value =
          reinterpret_cast<const int64_t *>(data_ + reader_index_ + 1)[0];
      reader_index_ += 9;
      return value;
    }
  }

  /// Write uint64_t value using tagged encoding.
  /// If value is in [0, 0x7fffffff], encode as 4 bytes: ((value as u32) << 1).
  /// Otherwise write as 9 bytes: 0b1 | little-endian 8 bytes u64.
  FORY_ALWAYS_INLINE void WriteTaggedUint64(uint64_t value) {
    constexpr uint64_t MAX_SMALL_VALUE = 0x7fffffff; // INT32_MAX as u64
    if (value <= MAX_SMALL_VALUE) {
      WriteInt32(static_cast<int32_t>(value) << 1);
    } else {
      Grow(9);
      data_[writer_index_] = 0b1;
      UnsafePut<uint64_t>(writer_index_ + 1, value);
      IncreaseWriterIndex(9);
    }
  }

  /// Read uint64_t value using tagged encoding. Sets error on bounds violation.
  /// If bit 0 is 0, return value >> 1.
  /// Otherwise, skip flag byte and read 8 bytes as uint64.
  FORY_ALWAYS_INLINE uint64_t ReadTaggedUint64(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 4 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 4, size_);
      return 0;
    }
    uint32_t i = reinterpret_cast<const uint32_t *>(data_ + reader_index_)[0];
    if ((i & 0b1) != 0b1) {
      reader_index_ += 4;
      return static_cast<uint64_t>(i >> 1);
    } else {
      if (FORY_PREDICT_FALSE(reader_index_ + 9 > size_)) {
        error.set_buffer_out_of_bound(reader_index_, 9, size_);
        return 0;
      }
      uint64_t value =
          reinterpret_cast<const uint64_t *>(data_ + reader_index_ + 1)[0];
      reader_index_ += 9;
      return value;
    }
  }

  /// Read uint64_t value as varuint36small. Sets error on bounds violation.
  FORY_ALWAYS_INLINE uint64_t ReadVarUint36Small(Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + 1 > size_)) {
      error.set_buffer_out_of_bound(reader_index_, 1, size_);
      return 0;
    }
    uint32_t offset = reader_index_;
    // Fast path: need at least 8 bytes for safe bulk read
    if (FORY_PREDICT_TRUE(size_ - offset >= 8)) {
      uint64_t bulk = *reinterpret_cast<uint64_t *>(data_ + offset);

      uint64_t result = bulk & 0x7F;
      if ((bulk & 0x80) == 0) {
        IncreaseReaderIndex(1);
        return result;
      }
      result |= (bulk >> 1) & 0x3F80;
      if ((bulk & 0x8000) == 0) {
        IncreaseReaderIndex(2);
        return result;
      }
      result |= (bulk >> 2) & 0x1FC000;
      if ((bulk & 0x800000) == 0) {
        IncreaseReaderIndex(3);
        return result;
      }
      result |= (bulk >> 3) & 0xFE00000;
      if ((bulk & 0x80000000) == 0) {
        IncreaseReaderIndex(4);
        return result;
      }
      // 5th byte for bits 28-35 (up to 36 bits)
      result |= (bulk >> 4) & 0xFF0000000ULL;
      IncreaseReaderIndex(5);
      return result;
    }
    // Slow path: byte-by-byte read
    uint32_t position = offset;
    uint8_t b = data_[position++];
    uint64_t result = b & 0x7F;
    if ((b & 0x80) != 0) {
      b = data_[position++];
      result |= static_cast<uint64_t>(b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = data_[position++];
        result |= static_cast<uint64_t>(b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = data_[position++];
          result |= static_cast<uint64_t>(b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = data_[position++];
            result |= static_cast<uint64_t>(b & 0xFF) << 28;
          }
        }
      }
    }
    IncreaseReaderIndex(position - offset);
    return result;
  }

  /// Read raw bytes from buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE void ReadBytes(void *data, uint32_t length, Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + length > size_)) {
      error.set_buffer_out_of_bound(reader_index_, length, size_);
      return;
    }
    Copy(reader_index_, length, static_cast<uint8_t *>(data));
    IncreaseReaderIndex(length);
  }

  /// Skip bytes in buffer. Sets error on bounds violation.
  FORY_ALWAYS_INLINE void Skip(uint32_t length, Error &error) {
    if (FORY_PREDICT_FALSE(reader_index_ + length > size_)) {
      error.set_buffer_out_of_bound(reader_index_, length, size_);
      return;
    }
    IncreaseReaderIndex(length);
  }

  /// Return true if both buffers are the same size and contain the same bytes
  /// up to the number of compared bytes
  bool Equals(const Buffer &other, int64_t nbytes) const;

  /// Return true if both buffers are the same size and contain the same bytes
  bool Equals(const Buffer &other) const;

  FORY_ALWAYS_INLINE void Grow(uint32_t min_capacity) {
    uint32_t len = writer_index_ + min_capacity;
    if (len > size_) {
      // NOTE: over allocate by 1.5 or 2 ?
      // see: Doubling isn't a great overallocation practice
      // see
      // https://github.com/facebook/folly/blob/master/folly/docs/FBVector.md
      // for discussion.
      auto new_size = util::RoundNumberOfBytesToNearestWord(len * 2);
      Reserve(new_size);
    }
  }

  /// Reserve buffer to new_size
  void Reserve(uint32_t new_size) {
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
            // Copy existing data before switching to owned buffer
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

  /// Copy a section of the buffer into a new Buffer.
  void Copy(uint32_t start, uint32_t nbytes,
            std::shared_ptr<Buffer> &out) const;

  /// Copy a section of the buffer into a new Buffer.
  void Copy(uint32_t start, uint32_t nbytes, Buffer &out) const;

  /// Copy a section of the buffer.
  void Copy(uint32_t start, uint32_t nbytes, uint8_t *out) const;

  /// Copy a section of the buffer.
  void Copy(uint32_t start, uint32_t nbytes, uint8_t *out,
            uint32_t offset) const;

  /// Copy data from `src` yo buffer
  void CopyFrom(uint32_t offset, const uint8_t *src, uint32_t src_offset,
                uint32_t nbytes);

  /// Zero all bytes in padding
  void ZeroPadding() {
    // A zero-size buffer_ can have a null data pointer
    if (size_ != 0) {
      memset(data_, 0, static_cast<size_t>(size_));
    }
  }

  /// \brief Copy buffer contents into a new std::string
  /// \return std::string
  /// \note Can throw std::bad_alloc if buffer is large
  std::string ToString() const;

  std::string Hex() const;

private:
  uint8_t *data_;
  uint32_t size_;
  bool own_data_;
  uint32_t writer_index_;
  uint32_t reader_index_;
  std::vector<uint8_t> *wrapped_vector_ = nullptr;
};

/// \brief Allocate a fixed-size mutable buffer from the default memory pool
///
/// \param[in] size size of buffer to allocate
/// \param[out] out the allocated buffer (contains padding)
///
/// \return success or not
bool AllocateBuffer(uint32_t size, std::shared_ptr<Buffer> *out);

bool AllocateBuffer(uint32_t size, Buffer **out);

Buffer *AllocateBuffer(uint32_t size);

} // namespace fory
