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
#include <vector>

#include "fory/util/error.h"
#include "fory/util/result.h"

namespace fory {

class Buffer;

class StreamReader : public std::enable_shared_from_this<StreamReader> {
public:
  virtual ~StreamReader() = default;

  virtual Result<void, Error> fill_buffer(uint32_t min_fill_size) = 0;

  virtual Result<void, Error> read_to(uint8_t *dst, uint32_t length) = 0;

  virtual Result<void, Error> skip(uint32_t size) = 0;

  virtual Result<void, Error> unread(uint32_t size) = 0;

  virtual Buffer &get_buffer() = 0;

  // Bind the reader to an external Buffer. Passing nullptr rebinds to the
  // reader-owned internal buffer.
  virtual void bind_buffer(Buffer *buffer) = 0;
};

class ForyInputStream final : public StreamReader {
public:
  explicit ForyInputStream(std::istream &stream, uint32_t buffer_size = 4096);

  explicit ForyInputStream(std::shared_ptr<std::istream> stream,
                           uint32_t buffer_size = 4096);

  ~ForyInputStream() override;

  Result<void, Error> fill_buffer(uint32_t min_fill_size) override;

  Result<void, Error> read_to(uint8_t *dst, uint32_t length) override;

  Result<void, Error> skip(uint32_t size) override;

  Result<void, Error> unread(uint32_t size) override;

  Buffer &get_buffer() override;

  void bind_buffer(Buffer *buffer) override;

private:
  uint32_t remaining_size() const;

  void reserve(uint32_t new_size);

  std::shared_ptr<std::istream> stream_owner_;
  std::istream *stream_ = nullptr;
  std::vector<uint8_t> data_;
  Buffer *buffer_ = nullptr;
  std::unique_ptr<Buffer> owned_buffer_;
};

} // namespace fory
