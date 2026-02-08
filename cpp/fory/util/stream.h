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

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <istream>
#include <limits>
#include <memory>
#include <streambuf>
#include <vector>

#include "fory/util/error.h"
#include "fory/util/logging.h"
#include "fory/util/result.h"

namespace fory {

class ForyInputStreamBuf final : public std::streambuf {
public:
  explicit ForyInputStreamBuf(std::istream &stream, uint32_t buffer_size = 4096)
      : stream_(&stream),
        buffer_(std::max<uint32_t>(buffer_size, static_cast<uint32_t>(1))) {
    char *base = buffer_.data();
    setg(base, base, base);
  }

  Result<void, Error> fill_buffer(uint32_t min_fill_size) {
    if (min_fill_size == 0) {
      return Result<void, Error>();
    }
    if (remaining_size() >= min_fill_size) {
      return Result<void, Error>();
    }

    const uint32_t read_pos = reader_index();
    const uint32_t cur_size = size();
    const uint64_t required =
        static_cast<uint64_t>(cur_size) + (min_fill_size - remaining_size());
    if (required > std::numeric_limits<uint32_t>::max()) {
      return Unexpected(
          Error::out_of_bound("stream buffer size exceeds uint32 range"));
    }
    if (required > buffer_.size()) {
      uint64_t new_size = static_cast<uint64_t>(buffer_.size()) * 2;
      if (new_size < required) {
        new_size = required;
      }
      reserve(static_cast<uint32_t>(new_size));
    }

    char *base = buffer_.data();
    uint32_t write_pos = cur_size;
    setg(base, base + read_pos, base + write_pos);
    while (remaining_size() < min_fill_size) {
      uint32_t writable = static_cast<uint32_t>(buffer_.size()) - write_pos;
      if (writable == 0) {
        uint64_t new_size = static_cast<uint64_t>(buffer_.size()) * 2 + 1;
        if (new_size > std::numeric_limits<uint32_t>::max()) {
          return Unexpected(
              Error::out_of_bound("stream buffer size exceeds uint32 range"));
        }
        reserve(static_cast<uint32_t>(new_size));
        base = buffer_.data();
        writable = static_cast<uint32_t>(buffer_.size()) - write_pos;
        setg(base, base + read_pos, base + write_pos);
      }
      std::streambuf *source = stream_->rdbuf();
      if (source == nullptr) {
        return Unexpected(Error::io_error("input stream has no stream buffer"));
      }
      const std::streamsize read_bytes = source->sgetn(
          base + write_pos, static_cast<std::streamsize>(writable));
      if (read_bytes <= 0) {
        return Unexpected(Error::buffer_out_of_bound(read_pos, min_fill_size,
                                                     remaining_size()));
      }
      write_pos += static_cast<uint32_t>(read_bytes);
      setg(base, base + read_pos, base + write_pos);
    }
    return Result<void, Error>();
  }

  void rewind(uint32_t size) {
    const uint32_t consumed = reader_index();
    FORY_CHECK(size <= consumed)
        << "rewind size " << size << " exceeds consumed bytes " << consumed;
    setg(eback(), gptr() - static_cast<std::ptrdiff_t>(size), egptr());
  }

  void consume(uint32_t size) {
    const uint32_t available = remaining_size();
    FORY_CHECK(size <= available)
        << "consume size " << size << " exceeds available bytes " << available;
    gbump(static_cast<int>(size));
  }

  void reader_index(uint32_t index) {
    const uint32_t cur_size = size();
    FORY_CHECK(index <= cur_size) << "reader_index " << index
                                  << " exceeds stream buffer size " << cur_size;
    setg(eback(), eback() + static_cast<std::ptrdiff_t>(index), egptr());
  }

  uint8_t *data() { return reinterpret_cast<uint8_t *>(eback()); }

  uint32_t size() const { return static_cast<uint32_t>(egptr() - eback()); }

  uint32_t reader_index() const {
    return static_cast<uint32_t>(gptr() - eback());
  }

  uint32_t remaining_size() const {
    return static_cast<uint32_t>(egptr() - gptr());
  }

protected:
  std::streamsize xsgetn(char *s, std::streamsize count) override {
    std::streamsize copied = 0;
    while (copied < count) {
      auto available = static_cast<std::streamsize>(egptr() - gptr());
      if (available == 0) {
        if (!fill_buffer(1).ok()) {
          break;
        }
        available = static_cast<std::streamsize>(egptr() - gptr());
      }
      const std::streamsize n = std::min(available, count - copied);
      std::memcpy(s + copied, gptr(), static_cast<size_t>(n));
      gbump(static_cast<int>(n));
      copied += n;
    }
    return copied;
  }

  int_type underflow() override {
    if (gptr() < egptr()) {
      return traits_type::to_int_type(*gptr());
    }
    if (!fill_buffer(1).ok()) {
      return traits_type::eof();
    }
    return traits_type::to_int_type(*gptr());
  }

private:
  void reserve(uint32_t new_size) {
    const uint32_t old_size = size();
    const uint32_t old_reader_index = reader_index();
    buffer_.resize(new_size);
    char *base = buffer_.data();
    setg(base, base + old_reader_index, base + old_size);
  }

  std::istream *stream_;
  std::vector<char> buffer_;
};

class ForyInputStream final : public std::basic_istream<char> {
public:
  explicit ForyInputStream(std::istream &stream, uint32_t buffer_size = 4096)
      : std::basic_istream<char>(nullptr), streambuf_(stream, buffer_size) {
    this->init(&streambuf_);
  }

  explicit ForyInputStream(std::shared_ptr<std::istream> stream,
                           uint32_t buffer_size = 4096)
      : std::basic_istream<char>(nullptr), stream_owner_(std::move(stream)),
        streambuf_(*stream_owner_, buffer_size) {
    FORY_CHECK(stream_owner_ != nullptr) << "stream must not be null";
    this->init(&streambuf_);
  }

  Result<void, Error> fill_buffer(uint32_t min_fill_size) {
    return streambuf_.fill_buffer(min_fill_size);
  }

  void rewind(uint32_t size) { streambuf_.rewind(size); }

  void consume(uint32_t size) { streambuf_.consume(size); }

  void reader_index(uint32_t index) { streambuf_.reader_index(index); }

  uint8_t *data() { return streambuf_.data(); }

  uint32_t size() const { return streambuf_.size(); }

  uint32_t reader_index() const { return streambuf_.reader_index(); }

  uint32_t remaining_size() const { return streambuf_.remaining_size(); }

private:
  std::shared_ptr<std::istream> stream_owner_;
  ForyInputStreamBuf streambuf_;
};

} // namespace fory
