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
      initial_buffer_size_(
          std::max<uint32_t>(buffer_size, static_cast<uint32_t>(1))),
      owned_buffer_(std::make_unique<Buffer>()) {
  bind_buffer(owned_buffer_.get());
}

ForyInputStream::ForyInputStream(std::shared_ptr<std::istream> stream,
                                 uint32_t buffer_size)
    : stream_owner_(std::move(stream)), stream_(stream_owner_.get()),
      data_(std::max<uint32_t>(buffer_size, static_cast<uint32_t>(1))),
      initial_buffer_size_(
          std::max<uint32_t>(buffer_size, static_cast<uint32_t>(1))),
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
  const uint32_t deficit = min_fill_size - remaining_size();
  constexpr uint64_t k_max_u32 = std::numeric_limits<uint32_t>::max();
  const uint64_t required = static_cast<uint64_t>(buffer_->size_) + deficit;
  if (required > k_max_u32) {
    return Unexpected(
        Error::out_of_bound("stream buffer size exceeds uint32 range"));
  }
  if (required > data_.size()) {
    uint64_t new_size =
        std::max<uint64_t>(required, static_cast<uint64_t>(data_.size()) * 2);
    if (new_size > k_max_u32) {
      new_size = k_max_u32;
    }
    reserve(static_cast<uint32_t>(new_size));
  }

  std::streambuf *source = stream_->rdbuf();
  if (source == nullptr) {
    return Unexpected(Error::io_error("input stream has no stream buffer"));
  }
  uint32_t write_pos = buffer_->size_;
  while (remaining_size() < min_fill_size) {
    uint32_t writable = static_cast<uint32_t>(data_.size()) - write_pos;
    const std::streamsize read_bytes =
        source->sgetn(reinterpret_cast<char *>(data_.data() + write_pos),
                      static_cast<std::streamsize>(writable));
    if (read_bytes <= 0) {
      return Unexpected(Error::buffer_out_of_bound(read_pos, min_fill_size,
                                                   remaining_size()));
    }
    write_pos += static_cast<uint32_t>(read_bytes);
    buffer_->size_ = write_pos;
  }
  return Result<void, Error>();
}

Result<void, Error> ForyInputStream::read_to(uint8_t *dst, uint32_t length) {
  if (length == 0) {
    return Result<void, Error>();
  }
  Error error;
  if (FORY_PREDICT_FALSE(!buffer_->ensure_readable(length, error))) {
    return Unexpected(std::move(error));
  }
  std::memcpy(dst, buffer_->data_ + buffer_->reader_index_,
              static_cast<size_t>(length));
  buffer_->reader_index_ += length;
  return Result<void, Error>();
}

Result<void, Error> ForyInputStream::skip(uint32_t size) {
  if (size == 0) {
    return Result<void, Error>();
  }
  Error error;
  buffer_->increase_reader_index(size, error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
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

void ForyInputStream::shrink_buffer() {
  if (buffer_ == nullptr) {
    return;
  }

  const uint32_t read_pos = buffer_->reader_index_;
  const uint32_t remaining = remaining_size();
  if (read_pos > 0) {
    if (remaining > 0) {
      std::memmove(data_.data(), data_.data() + read_pos,
                   static_cast<size_t>(remaining));
    }
    buffer_->reader_index_ = 0;
    buffer_->size_ = remaining;
    buffer_->writer_index_ = remaining;
  }

  const uint32_t current_capacity = static_cast<uint32_t>(data_.size());
  uint32_t target_capacity = current_capacity;
  if (current_capacity > initial_buffer_size_) {
    if (remaining == 0) {
      target_capacity = initial_buffer_size_;
    } else if (remaining <= current_capacity / 4) {
      const uint32_t doubled =
          remaining > std::numeric_limits<uint32_t>::max() / 2
              ? std::numeric_limits<uint32_t>::max()
              : remaining * 2;
      target_capacity = std::max<uint32_t>(
          initial_buffer_size_,
          std::max<uint32_t>(doubled, static_cast<uint32_t>(1)));
    }
  }

  if (target_capacity < current_capacity) {
    data_.resize(target_capacity);
    data_.shrink_to_fit();
    buffer_->data_ = data_.data();
  }
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
  Buffer *target = buffer == nullptr ? owned_buffer_.get() : buffer;
  if (target == nullptr) {
    if (buffer_ != nullptr) {
      buffer_->stream_reader_ = nullptr;
    }
    buffer_ = nullptr;
    return;
  }

  if (buffer_ == target) {
    buffer_->data_ = data_.data();
    buffer_->own_data_ = false;
    buffer_->wrapped_vector_ = nullptr;
    buffer_->stream_reader_ = this;
    return;
  }

  Buffer *source = buffer_;
  if (source != nullptr) {
    target->size_ = source->size_;
    target->writer_index_ = source->writer_index_;
    target->reader_index_ = source->reader_index_;
    source->stream_reader_ = nullptr;
  } else {
    target->size_ = 0;
    target->writer_index_ = 0;
    target->reader_index_ = 0;
  }

  buffer_ = target;
  buffer_->data_ = data_.data();
  buffer_->own_data_ = false;
  buffer_->wrapped_vector_ = nullptr;
  buffer_->stream_reader_ = this;
}

} // namespace fory
