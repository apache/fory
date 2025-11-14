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
      : data_(data), size_(size), own_data_(own_data) {
    writer_index_ = 0;
    reader_index_ = 0;
  }

  Buffer(Buffer &&buffer) noexcept;

  Buffer &operator=(Buffer &&buffer) noexcept;

  virtual ~Buffer();

  /// \brief Return a pointer to the buffer's data
  inline uint8_t *data() const { return data_; }

  /// \brief Return the buffer's size in bytes
  inline uint32_t size() const { return size_; }

  inline bool own_data() const { return own_data_; }

  inline uint32_t writer_index() { return writer_index_; }

  inline uint32_t reader_index() { return reader_index_; }

  inline void WriterIndex(uint32_t writer_index) {
    FORY_CHECK(writer_index < std::numeric_limits<int>::max())
        << "Buffer overflow writer_index" << writer_index_
        << " target writer_index " << writer_index;
    writer_index_ = writer_index;
  }

  inline void IncreaseWriterIndex(uint32_t diff) {
    int64_t writer_index = writer_index_ + diff;
    FORY_CHECK(writer_index < std::numeric_limits<int>::max())
        << "Buffer overflow writer_index" << writer_index_ << " diff " << diff;
    writer_index_ = writer_index;
  }

  inline void ReaderIndex(uint32_t reader_index) {
    FORY_CHECK(reader_index < std::numeric_limits<int>::max())
        << "Buffer overflow reader_index" << reader_index_
        << " target reader_index " << reader_index;
    reader_index_ = reader_index;
  }

  inline void IncreaseReaderIndex(uint32_t diff) {
    int64_t reader_index = reader_index_ + diff;
    FORY_CHECK(reader_index < std::numeric_limits<int>::max())
        << "Buffer overflow reader_index" << reader_index_ << " diff " << diff;
    reader_index_ = reader_index;
  }

  // Unsafe methods don't check bound
  template <typename T> inline void UnsafePut(uint32_t offset, T value) {
    reinterpret_cast<T *>(data_ + offset)[0] = value;
  }

  template <typename T,
            typename = meta::EnableIfIsOneOf<T, int8_t, uint8_t, bool>>
  inline void UnsafePutByte(uint32_t offset, T value) {
    data_[offset] = value;
  }

  inline void UnsafePut(uint32_t offset, const void *data,
                        const uint32_t length) {
    memcpy(data_ + offset, data, (size_t)length);
  }

  template <typename T> inline T Get(uint32_t relative_offset) {
    FORY_CHECK(relative_offset < size_) << "Out of range " << relative_offset
                                        << " should be less than " << size_;
    T value = reinterpret_cast<const T *>(data_ + relative_offset)[0];
    return value;
  }

  template <typename T,
            typename = meta::EnableIfIsOneOf<T, int8_t, uint8_t, bool>>
  inline T GetByteAs(uint32_t relative_offset) {
    FORY_CHECK(relative_offset < size_) << "Out of range " << relative_offset
                                        << " should be less than " << size_;
    return data_[relative_offset];
  }

  inline bool GetBool(uint32_t offset) { return GetByteAs<bool>(offset); }

  inline int8_t GetInt8(uint32_t offset) { return GetByteAs<int8_t>(offset); }

  inline int16_t GetInt16(uint32_t offset) { return Get<int16_t>(offset); }

  inline int32_t GetInt32(uint32_t offset) { return Get<int32_t>(offset); }

  inline int64_t GetInt64(uint32_t offset) { return Get<int64_t>(offset); }

  inline float GetFloat(uint32_t offset) { return Get<float>(offset); }

  inline double GetDouble(uint32_t offset) { return Get<double>(offset); }

  inline Result<void, Error> GetBytesAsInt64(uint32_t offset, uint32_t length,
                                             int64_t *target) {
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

  inline uint32_t PutVarUint32(uint32_t offset, int32_t value) {
    if (value >> 7 == 0) {
      data_[offset] = (int8_t)value;
      return 1;
    }
    if (value >> 14 == 0) {
      data_[offset++] = (int8_t)((value & 0x7F) | 0x80);
      data_[offset++] = (int8_t)(value >> 7);
      return 2;
    }
    if (value >> 21 == 0) {
      data_[offset++] = (int8_t)((value & 0x7F) | 0x80);
      data_[offset++] = (int8_t)(value >> 7 | 0x80);
      data_[offset++] = (int8_t)(value >> 14);
      return 3;
    }
    if (value >> 28 == 0) {
      data_[offset++] = (int8_t)((value & 0x7F) | 0x80);
      data_[offset++] = (int8_t)(value >> 7 | 0x80);
      data_[offset++] = (int8_t)(value >> 14 | 0x80);
      data_[offset++] = (int8_t)(value >> 21);
      return 4;
    }
    data_[offset++] = (int8_t)((value & 0x7F) | 0x80);
    data_[offset++] = (int8_t)(value >> 7 | 0x80);
    data_[offset++] = (int8_t)(value >> 14 | 0x80);
    data_[offset++] = (int8_t)(value >> 21 | 0x80);
    data_[offset++] = (int8_t)(value >> 28);
    return 5;
  }

  inline int32_t GetVarUint32(uint32_t offset, uint32_t *readBytesLength) {
    uint32_t position = offset;
    int b = data_[position++];
    int result = b & 0x7F;
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

  /// Put unsigned varint64 at offset. Returns number of bytes written (1-9).
  /// Uses PVL (Progressive Variable-length Long) encoding per xlang spec.
  inline uint32_t PutVarUint64(uint32_t offset, uint64_t value) {
    uint32_t position = offset;
    while (value >= 0x80) {
      data_[position++] = static_cast<uint8_t>((value & 0x7F) | 0x80);
      value >>= 7;
    }
    data_[position++] = static_cast<uint8_t>(value);
    return position - offset;
  }

  /// Get unsigned varint64 from offset. Writes number of bytes read to
  /// readBytesLength. Uses PVL (Progressive Variable-length Long) encoding per
  /// xlang spec.
  inline uint64_t GetVarUint64(uint32_t offset, uint32_t *readBytesLength) {
    uint32_t position = offset;
    uint64_t result = 0;
    int shift = 0;
    while (true) {
      uint8_t b = data_[position++];
      result |= static_cast<uint64_t>(b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        break;
      }
      shift += 7;
    }
    *readBytesLength = position - offset;
    return result;
  }

  /// Write uint8_t value to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  inline void WriteUint8(uint8_t value) {
    Grow(1);
    UnsafePutByte(writer_index_, value);
    IncreaseWriterIndex(1);
  }

  /// Write int8_t value to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  inline void WriteInt8(int8_t value) {
    Grow(1);
    UnsafePutByte(writer_index_, static_cast<uint8_t>(value));
    IncreaseWriterIndex(1);
  }

  /// Write uint16_t value as fixed 2 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  inline void WriteUint16(uint16_t value) {
    Grow(2);
    UnsafePut<uint16_t>(writer_index_, value);
    IncreaseWriterIndex(2);
  }

  /// Write int32_t value as fixed 4 bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  inline void WriteInt32(int32_t value) {
    Grow(4);
    UnsafePut<int32_t>(writer_index_, value);
    IncreaseWriterIndex(4);
  }

  /// Write uint32_t value as varint to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  inline void WriteVarUint32(uint32_t value) {
    Grow(5); // Max 5 bytes for varint32
    uint32_t len = PutVarUint32(writer_index_, value);
    IncreaseWriterIndex(len);
  }

  /// Write int32_t value as varint to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  inline void WriteVarInt32(int32_t value) {
    Grow(5); // Max 5 bytes for varint32
    uint32_t len = PutVarUint32(writer_index_, value);
    IncreaseWriterIndex(len);
  }

  /// Write uint64_t value as varint to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  inline void WriteVarUint64(uint64_t value) {
    Grow(9); // Max 9 bytes for varint64
    uint32_t len = PutVarUint64(writer_index_, value);
    IncreaseWriterIndex(len);
  }

  /// Write raw bytes to buffer at current writer index.
  /// Automatically grows buffer and advances writer index.
  inline void WriteBytes(const void *data, uint32_t length) {
    Grow(length);
    UnsafePut(writer_index_, data, length);
    IncreaseWriterIndex(length);
  }

  /// Read uint8_t value from buffer at current reader index.
  /// Advances reader index and checks bounds.
  inline Result<uint8_t, Error> ReadUint8() {
    if (reader_index_ + 1 > size_) {
      return Unexpected(Error::buffer_out_of_bound(reader_index_, 1, size_));
    }
    uint8_t value = GetByteAs<uint8_t>(reader_index_);
    IncreaseReaderIndex(1);
    return value;
  }

  /// Read int8_t value from buffer at current reader index.
  /// Advances reader index and checks bounds.
  inline Result<int8_t, Error> ReadInt8() {
    if (reader_index_ + 1 > size_) {
      return Unexpected(Error::buffer_out_of_bound(reader_index_, 1, size_));
    }
    int8_t value = GetByteAs<int8_t>(reader_index_);
    IncreaseReaderIndex(1);
    return value;
  }

  /// Read int32_t value as fixed 4 bytes from buffer at current reader index.
  /// Advances reader index and checks bounds.
  inline Result<int32_t, Error> ReadInt32() {
    if (reader_index_ + 4 > size_) {
      return Unexpected(Error::buffer_out_of_bound(reader_index_, 4, size_));
    }
    int32_t value = Get<int32_t>(reader_index_);
    IncreaseReaderIndex(4);
    return value;
  }

  /// Read uint32_t value as varint from buffer at current reader index.
  /// Advances reader index and checks bounds.
  inline Result<uint32_t, Error> ReadVarUint32() {
    if (reader_index_ + 1 > size_) {
      return Unexpected(Error::buffer_out_of_bound(reader_index_, 1, size_));
    }
    uint32_t read_bytes = 0;
    uint32_t value = GetVarUint32(reader_index_, &read_bytes);
    IncreaseReaderIndex(read_bytes);
    return value;
  }

  /// Read int32_t value as varint from buffer at current reader index.
  /// Advances reader index and checks bounds.
  inline Result<int32_t, Error> ReadVarInt32() {
    if (reader_index_ + 1 > size_) {
      return Unexpected(Error::buffer_out_of_bound(reader_index_, 1, size_));
    }
    uint32_t read_bytes = 0;
    int32_t value = GetVarUint32(reader_index_, &read_bytes);
    IncreaseReaderIndex(read_bytes);
    return value;
  }

  /// Read uint64_t value as varint from buffer at current reader index.
  /// Advances reader index and checks bounds.
  inline Result<uint64_t, Error> ReadVarUint64() {
    if (reader_index_ + 1 > size_) {
      return Unexpected(Error::buffer_out_of_bound(reader_index_, 1, size_));
    }
    uint32_t read_bytes = 0;
    uint64_t value = GetVarUint64(reader_index_, &read_bytes);
    IncreaseReaderIndex(read_bytes);
    return value;
  }

  /// Read raw bytes from buffer at current reader index.
  /// Advances reader index and checks bounds.
  inline Result<void, Error> ReadBytes(void *data, uint32_t length) {
    if (reader_index_ + length > size_) {
      return Unexpected(
          Error::buffer_out_of_bound(reader_index_, length, size_));
    }
    Copy(reader_index_, length, static_cast<uint8_t *>(data));
    IncreaseReaderIndex(length);
    return Result<void, Error>();
  }

  /// Skip bytes in buffer by advancing reader index.
  /// Checks bounds to ensure we don't skip past the end.
  inline Result<void, Error> Skip(uint32_t length) {
    if (reader_index_ + length > size_) {
      return Unexpected(
          Error::buffer_out_of_bound(reader_index_, length, size_));
    }
    IncreaseReaderIndex(length);
    return Result<void, Error>();
  }

  /// Return true if both buffers are the same size and contain the same bytes
  /// up to the number of compared bytes
  bool Equals(const Buffer &other, int64_t nbytes) const;

  /// Return true if both buffers are the same size and contain the same bytes
  bool Equals(const Buffer &other) const;

  inline void Grow(uint32_t min_capacity) {
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
      uint8_t *new_ptr;
      if (own_data_) {
        new_ptr = static_cast<uint8_t *>(
            realloc(data_, static_cast<size_t>(new_size)));
      } else {
        new_ptr = static_cast<uint8_t *>(malloc(static_cast<size_t>(new_size)));
        if (new_ptr) {
          own_data_ = true;
        }
      }
      if (new_ptr) {
        data_ = new_ptr;
        size_ = new_size;
      } else {
        FORY_CHECK(false) << "Out of memory when grow buffer, needed_size "
                          << size_;
      }
    }
  }

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
