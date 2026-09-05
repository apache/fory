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

#include <string>

#include "fory/util/buffer.h"
#include "fory/util/logging.h"

namespace fory {

Buffer::Buffer() {
  data_ = nullptr;
  size_ = 0;
  own_data_ = false;
  writer_index_ = 0;
  reader_index_ = 0;
  discarded_reader_bytes_ = 0;
  wrapped_vector_ = nullptr;
  input_stream_ = nullptr;
  output_stream_ = nullptr;
}

Buffer::Buffer(Buffer &&buffer) noexcept {
  FORY_CHECK(buffer.output_stream_ == nullptr)
      << "Cannot move stream-writer-owned Buffer";
  data_ = buffer.data_;
  size_ = buffer.size_;
  own_data_ = buffer.own_data_;
  writer_index_ = buffer.writer_index_;
  reader_index_ = buffer.reader_index_;
  discarded_reader_bytes_ = buffer.discarded_reader_bytes_;
  wrapped_vector_ = buffer.wrapped_vector_;
  input_stream_ = buffer.input_stream_;
  input_stream_owner_ = std::move(buffer.input_stream_owner_);
  output_stream_ = buffer.output_stream_;
  rebind_input_stream_to_this();
  buffer.input_stream_ = nullptr;
  buffer.output_stream_ = nullptr;
  buffer.data_ = nullptr;
  buffer.size_ = 0;
  buffer.own_data_ = false;
  buffer.discarded_reader_bytes_ = 0;
  buffer.wrapped_vector_ = nullptr;
}

Buffer &Buffer::operator=(Buffer &&buffer) noexcept {
  FORY_CHECK(buffer.output_stream_ == nullptr)
      << "Cannot move stream-writer-owned Buffer";
  FORY_CHECK(output_stream_ == nullptr)
      << "Cannot assign to stream-writer-owned Buffer";
  detach_input_stream_from_this();
  if (own_data_) {
    free(data_);
    data_ = nullptr;
  }
  data_ = buffer.data_;
  size_ = buffer.size_;
  own_data_ = buffer.own_data_;
  writer_index_ = buffer.writer_index_;
  reader_index_ = buffer.reader_index_;
  discarded_reader_bytes_ = buffer.discarded_reader_bytes_;
  wrapped_vector_ = buffer.wrapped_vector_;
  input_stream_ = buffer.input_stream_;
  input_stream_owner_ = std::move(buffer.input_stream_owner_);
  output_stream_ = buffer.output_stream_;
  rebind_input_stream_to_this();
  buffer.input_stream_ = nullptr;
  buffer.output_stream_ = nullptr;
  buffer.data_ = nullptr;
  buffer.size_ = 0;
  buffer.own_data_ = false;
  buffer.discarded_reader_bytes_ = 0;
  buffer.wrapped_vector_ = nullptr;
  return *this;
}

Buffer::~Buffer() {
  clear_output_stream();
  detach_input_stream_from_this();
  if (own_data_) {
    free(data_);
    data_ = nullptr;
  }
}

bool Buffer::equals(const Buffer &other, int64_t nbytes) const {
  if (FORY_PREDICT_FALSE(nbytes < 0)) {
    return false;
  }
  if (nbytes == 0) {
    return true;
  }
  const uint64_t length = static_cast<uint64_t>(nbytes);
  return this == &other ||
         (size_ >= length && other.size_ >= length &&
          (data_ == other.data_ ||
           !memcmp(data_, other.data_, static_cast<size_t>(nbytes))));
}

bool Buffer::equals(const Buffer &other) const {
  if (size_ == 0 && other.size_ == 0) {
    return true;
  }
  return this == &other ||
         (size_ == other.size_ &&
          (data_ == other.data_ ||
           !memcmp(data_, other.data_, static_cast<size_t>(size_))));
}

void Buffer::copy(const uint32_t start, const uint32_t nbytes,
                  std::shared_ptr<Buffer> &out) const {
  FORY_CHECK(FORY_PREDICT_TRUE(out != nullptr))
      << "Cannot copy into a null Buffer";
  FORY_CHECK(FORY_PREDICT_TRUE(range_in_bounds(start, nbytes)))
      << "Buffer out of bound: " << start << " + " << nbytes << " > " << size_;
  FORY_CHECK(FORY_PREDICT_TRUE(nbytes <= out->size()))
      << "Buffer out of bound: 0 + " << nbytes << " > " << out->size();
  if (nbytes == 0) {
    return;
  }
  std::memmove(out->data(), data_ + start, static_cast<size_t>(nbytes));
}

void Buffer::copy(uint32_t start, uint32_t nbytes, Buffer &out) const {
  FORY_CHECK(FORY_PREDICT_TRUE(range_in_bounds(start, nbytes)))
      << "Buffer out of bound: " << start << " + " << nbytes << " > " << size_;
  FORY_CHECK(FORY_PREDICT_TRUE(nbytes <= out.size()))
      << "Buffer out of bound: 0 + " << nbytes << " > " << out.size();
  if (nbytes == 0) {
    return;
  }
  std::memmove(out.data(), data_ + start, static_cast<size_t>(nbytes));
}

void Buffer::copy(uint32_t start, uint32_t nbytes, uint8_t *out) const {
  copy(start, nbytes, out, 0);
}

void Buffer::copy(uint32_t start, uint32_t nbytes, uint8_t *out,
                  uint32_t offset) const {
  if (nbytes == 0) {
    return;
  }
  std::memcpy(out + offset, data_ + start, static_cast<size_t>(nbytes));
}

void Buffer::copy_from(uint32_t offset, const uint8_t *src, uint32_t src_offset,
                       uint32_t nbytes) {
  if (nbytes == 0) {
    return;
  }
  const uint64_t required_size = static_cast<uint64_t>(offset) + nbytes;
  FORY_CHECK(
      FORY_PREDICT_TRUE(required_size <= std::numeric_limits<uint32_t>::max()))
      << "Buffer overflow offset " << offset << " length " << nbytes;
  grow_to_fit(static_cast<uint32_t>(required_size));
  std::memcpy(data_ + offset, src + src_offset, static_cast<size_t>(nbytes));
}

std::string Buffer::to_string() const {
  return std::string(reinterpret_cast<const char *>(data_),
                     static_cast<size_t>(size_));
}

std::string Buffer::hex() const {
  return util::hex(data(), static_cast<int32_t>(size_));
}

bool allocate_buffer(uint32_t size, std::shared_ptr<Buffer> *out) {
  auto *data = static_cast<uint8_t *>(malloc(static_cast<size_t>(size)));
  if (data) {
    *out = std::make_shared<Buffer>(data, size);
    return true;
  } else {
    return false;
  }
}

bool allocate_buffer(uint32_t size, Buffer **out) {
  auto *data = static_cast<uint8_t *>(malloc(static_cast<size_t>(size)));
  if (data) {
    *out = new Buffer(data, size);
    return true;
  } else {
    return false;
  }
}

Buffer *allocate_buffer(uint32_t size) {
  auto data = static_cast<uint8_t *>(malloc(static_cast<size_t>(size)));
  if (data) {
    return new Buffer(data, size);
  } else {
    return nullptr;
  }
}

void Buffer::grow_checked(uint64_t required_size, uint32_t min_capacity) {
  FORY_CHECK(required_size < std::numeric_limits<uint32_t>::max())
      << "Buffer overflow writer_index" << writer_index_ << " diff "
      << min_capacity;
  grow_to_fit(static_cast<uint32_t>(required_size));
}

void Buffer::fail_range(uint32_t offset, uint32_t length) const {
  FORY_CHECK(false) << "Buffer out of bound: " << offset << " + " << length
                    << " > " << size_;
}

void Buffer::fail_writer_index(uint64_t target) const {
  FORY_CHECK(false) << "Buffer overflow writer_index " << writer_index_
                    << " target writer_index " << target << " size " << size_;
}

} // namespace fory
