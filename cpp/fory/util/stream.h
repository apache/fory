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
#include <istream>
#include <memory>
#include <ostream>
#include <vector>

#include "fory/util/error.h"
#include "fory/util/result.h"

namespace fory {

class Buffer;

class OutputStream {
public:
  explicit OutputStream(uint32_t buffer_size = 4096);

  virtual ~OutputStream();

  FORY_ALWAYS_INLINE Buffer *get_buffer() { return buffer_.get(); }

  FORY_ALWAYS_INLINE const Buffer *get_buffer() const { return buffer_.get(); }

  FORY_ALWAYS_INLINE void enter_flush_barrier() { flush_barrier_depth_++; }

  FORY_ALWAYS_INLINE void exit_flush_barrier() { flush_barrier_depth_--; }

  FORY_ALWAYS_INLINE bool try_flush() {
    if (FORY_PREDICT_FALSE(flush_barrier_depth_ != 0)) {
      return false;
    }
    const uint32_t bytes_before_flush = active_buffer_writer_index();
    if (FORY_PREDICT_FALSE(bytes_before_flush <= 4096)) {
      return false;
    }
    flush_buffer_data();
    if (FORY_PREDICT_FALSE(!error_.ok())) {
      return false;
    }
    return bytes_before_flush != 0;
  }

  FORY_ALWAYS_INLINE void force_flush() {
    if (FORY_PREDICT_FALSE(!error_.ok())) {
      return;
    }
    flush_buffer_data();
    if (FORY_PREDICT_FALSE(!error_.ok())) {
      return;
    }
    auto flush_result = flush_stream();
    if (FORY_PREDICT_FALSE(!flush_result.ok())) {
      set_error(std::move(flush_result).error());
    }
  }

  FORY_ALWAYS_INLINE uint32_t flush_barrier_depth() const {
    return flush_barrier_depth_;
  }

  FORY_ALWAYS_INLINE size_t flushed_bytes() const { return flushed_bytes_; }

  void reset();

  FORY_ALWAYS_INLINE bool has_error() const { return !error_.ok(); }

  FORY_ALWAYS_INLINE const Error &error() const { return error_; }

protected:
  virtual Result<void, Error> write_to_stream(const uint8_t *src,
                                              uint32_t length) = 0;

  virtual Result<void, Error> flush_stream() = 0;

private:
  void bind_buffer(Buffer *buffer);

  void unbind_buffer(Buffer *buffer);

  FORY_ALWAYS_INLINE Buffer *active_buffer() {
    return active_buffer_ == nullptr ? buffer_.get() : active_buffer_;
  }

  void flush_buffer_data();

  uint32_t active_buffer_writer_index();

  FORY_ALWAYS_INLINE void set_error(Error error) {
    if (error_.ok()) {
      error_ = std::move(error);
    }
  }

  std::unique_ptr<Buffer> buffer_;
  Buffer *active_buffer_ = nullptr;
  size_t flushed_bytes_ = 0;
  uint32_t flush_barrier_depth_ = 0;
  Error error_;

  friend class Buffer;
};

class InputStream : public std::enable_shared_from_this<InputStream> {
public:
  virtual ~InputStream() = default;

  virtual Result<void, Error> fill_buffer(uint32_t min_fill_size) = 0;

  virtual Result<void, Error> read_to(uint8_t *dst, uint32_t length) = 0;

  virtual Result<void, Error> skip(uint32_t size) = 0;

  virtual Result<void, Error> unread(uint32_t size) = 0;

  // Best-effort input-buffer compaction/reclaim hook. Callers may invoke this
  // frequently; implementations should return quickly unless configured
  // compaction thresholds are met.
  virtual void shrink_buffer() = 0;

  virtual Buffer &get_buffer() = 0;

  // Bind the reader to an external Buffer. Passing nullptr rebinds to the
  // reader-owned internal buffer.
  virtual void bind_buffer(Buffer *buffer) = 0;
};

class StdInputStream final : public InputStream {
public:
  explicit StdInputStream(std::istream &stream, uint32_t buffer_size = 4096);

  explicit StdInputStream(std::shared_ptr<std::istream> stream,
                          uint32_t buffer_size = 4096);

  ~StdInputStream() override;

  Result<void, Error> fill_buffer(uint32_t min_fill_size) override;

  Result<void, Error> read_to(uint8_t *dst, uint32_t length) override;

  Result<void, Error> skip(uint32_t size) override;

  Result<void, Error> unread(uint32_t size) override;

  void shrink_buffer() override;

  Buffer &get_buffer() override;

  void bind_buffer(Buffer *buffer) override;

private:
  uint32_t remaining_size() const;

  void reserve(uint32_t new_size);

  std::shared_ptr<std::istream> stream_owner_;
  std::istream *stream_ = nullptr;
  std::vector<uint8_t> data_;
  uint32_t initial_buffer_size_ = 1;
  Buffer *buffer_ = nullptr;
  std::unique_ptr<Buffer> owned_buffer_;
};

class StdOutputStream final : public OutputStream {
public:
  explicit StdOutputStream(std::ostream &stream);

  explicit StdOutputStream(std::shared_ptr<std::ostream> stream);

  ~StdOutputStream() override;

protected:
  Result<void, Error> write_to_stream(const uint8_t *src,
                                      uint32_t length) override;

  Result<void, Error> flush_stream() override;

private:
  std::shared_ptr<std::ostream> stream_owner_;
  std::ostream *stream_ = nullptr;
};

} // namespace fory
