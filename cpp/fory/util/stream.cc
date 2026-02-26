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

#include "fory/util/stream.h"

#include <algorithm>
#include <cstring>
#include <limits>

#include "fory/util/buffer.h"
#include "fory/util/logging.h"

namespace fory {

ForyInputStream::ForyInputStream(std::istream &stream, uint32_t buffer_size)
    : stream_(&stream),
      data_(std::max<uint32_t>(buffer_size, static_cast<uint32_t>(1))),
      owned_buffer_(std::make_unique<Buffer>()) {
  bind_buffer(owned_buffer_.get());
}

ForyInputStream::ForyInputStream(std::shared_ptr<std::istream> stream,
                                 uint32_t buffer_size)
    : stream_owner_(std::move(stream)), stream_(stream_owner_.get()),
      data_(std::max<uint32_t>(buffer_size, static_cast<uint32_t>(1))),
      owned_buffer_(std::make_unique<Buffer>()) {
  FORY_CHECK(stream_owner_ != nullptr) << "stream must not be null";
  bind_buffer(owned_buffer_.get());
}

ForyInputStream::~ForyInputStream() = default;

Result<void, Error> ForyInputStream::fill_buffer(uint32_t min_fill_size) {
  if (min_fill_size == 0 || remaining_size() >= min_fill_size) {
    return Result<void, Error>();
  }

  const uint32_t read_pos = buffer_->reader_index_;
  const uint64_t required = static_cast<uint64_t>(buffer_->size_) +
                            (min_fill_size - remaining_size());
  if (required > std::numeric_limits<uint32_t>::max()) {
    return Unexpected(
        Error::out_of_bound("stream buffer size exceeds uint32 range"));
  }
  if (required > data_.size()) {
    uint64_t new_size = static_cast<uint64_t>(data_.size()) * 2;
    if (new_size < required) {
      new_size = required;
    }
    reserve(static_cast<uint32_t>(new_size));
  }

  uint32_t write_pos = buffer_->size_;
  while (remaining_size() < min_fill_size) {
    uint32_t writable = static_cast<uint32_t>(data_.size()) - write_pos;
    if (writable == 0) {
      uint64_t new_size = static_cast<uint64_t>(data_.size()) * 2 + 1;
      if (new_size > std::numeric_limits<uint32_t>::max()) {
        return Unexpected(
            Error::out_of_bound("stream buffer size exceeds uint32 range"));
      }
      reserve(static_cast<uint32_t>(new_size));
      writable = static_cast<uint32_t>(data_.size()) - write_pos;
    }
    std::streambuf *source = stream_->rdbuf();
    if (source == nullptr) {
      return Unexpected(Error::io_error("input stream has no stream buffer"));
    }
    const std::streamsize read_bytes =
        source->sgetn(reinterpret_cast<char *>(data_.data() + write_pos),
                      static_cast<std::streamsize>(writable));
    if (read_bytes <= 0) {
      return Unexpected(Error::buffer_out_of_bound(read_pos, min_fill_size,
                                                   remaining_size()));
    }
    write_pos += static_cast<uint32_t>(read_bytes);
    buffer_->size_ = write_pos;
    buffer_->writer_index_ = write_pos;
  }
  return Result<void, Error>();
}

Result<void, Error> ForyInputStream::read_to(uint8_t *dst, uint32_t length) {
  const uint32_t read_pos = buffer_->reader_index_;
  const uint32_t total_length = length;
  uint32_t available = remaining_size();
  if (available >= length) {
    std::memcpy(dst, buffer_->data_ + buffer_->reader_index_,
                static_cast<size_t>(length));
    buffer_->reader_index_ += length;
    return Result<void, Error>();
  }

  if (available > 0) {
    std::memcpy(dst, buffer_->data_ + buffer_->reader_index_,
                static_cast<size_t>(available));
    buffer_->reader_index_ += available;
    dst += available;
    length -= available;
  }

  std::streambuf *source = stream_->rdbuf();
  if (source == nullptr) {
    return Unexpected(Error::io_error("input stream has no stream buffer"));
  }
  uint32_t copied = 0;
  while (copied < length) {
    const std::streamsize read_bytes =
        source->sgetn(reinterpret_cast<char *>(dst + copied),
                      static_cast<std::streamsize>(length - copied));
    if (read_bytes <= 0) {
      return Unexpected(
          Error::buffer_out_of_bound(read_pos, total_length, buffer_->size_));
    }
    copied += static_cast<uint32_t>(read_bytes);
  }
  return Result<void, Error>();
}

Result<void, Error> ForyInputStream::skip(uint32_t size) {
  const uint32_t read_pos = buffer_->reader_index_;
  const uint32_t total_size = size;
  uint32_t available = remaining_size();
  if (available >= size) {
    buffer_->reader_index_ += size;
    return Result<void, Error>();
  }

  buffer_->reader_index_ += available;
  size -= available;

  std::streambuf *source = stream_->rdbuf();
  if (source == nullptr) {
    return Unexpected(Error::io_error("input stream has no stream buffer"));
  }
  char discard[4096];
  uint32_t skipped = 0;
  while (skipped < size) {
    const uint32_t chunk = std::min<uint32_t>(
        size - skipped, static_cast<uint32_t>(sizeof(discard)));
    const std::streamsize read_bytes =
        source->sgetn(discard, static_cast<std::streamsize>(chunk));
    if (read_bytes <= 0) {
      return Unexpected(
          Error::buffer_out_of_bound(read_pos, total_size, buffer_->size_));
    }
    skipped += static_cast<uint32_t>(read_bytes);
  }
  return Result<void, Error>();
}

Result<void, Error> ForyInputStream::unread(uint32_t size) {
  if (FORY_PREDICT_FALSE(size > buffer_->reader_index_)) {
    return Unexpected(Error::buffer_out_of_bound(buffer_->reader_index_, size,
                                                 buffer_->size_));
  }
  buffer_->reader_index_ -= size;
  return Result<void, Error>();
}

Buffer &ForyInputStream::get_buffer() { return *buffer_; }

uint32_t ForyInputStream::remaining_size() const {
  return buffer_->size_ - buffer_->reader_index_;
}

void ForyInputStream::reserve(uint32_t new_size) {
  data_.resize(new_size);
  buffer_->data_ = data_.data();
}

void ForyInputStream::bind_buffer(Buffer *buffer) {
  FORY_CHECK(buffer != nullptr) << "buffer must not be null";
  if (buffer_ != nullptr && buffer_ != buffer) {
    buffer_->stream_reader_ = nullptr;
  }
  buffer_ = buffer;
  buffer_->data_ = data_.data();
  buffer_->size_ = 0;
  buffer_->own_data_ = false;
  buffer_->writer_index_ = 0;
  buffer_->reader_index_ = 0;
  buffer_->wrapped_vector_ = nullptr;
  buffer_->stream_reader_ = this;
}

} // namespace fory
