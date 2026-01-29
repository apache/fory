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

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "fory/row/row.h"
#include "fory/util/bit_util.h"
#include "fory/util/buffer.h"
#include "fory/util/logging.h"

namespace fory {
namespace row {

class Writer {
public:
  std::shared_ptr<Buffer> &buffer() { return buffer_; }

  inline uint32_t cursor() const { return buffer_->writer_index(); }

  inline uint32_t size() const {
    return buffer_->writer_index() - starting_offset_;
  }

  inline uint32_t starting_offset() const { return starting_offset_; }

  inline std::vector<Writer *> &children() { return children_; }

  inline void increase_cursor(uint32_t val) {
    buffer_->increase_writer_index(val);
  }

  inline void grow(uint32_t needed_size) { buffer_->grow(needed_size); }

  virtual int get_offset(int i) const = 0;

  void set_offset_and_size(int i, uint32_t size) {
    set_offset_and_size(i, buffer_->writer_index(), size);
  }

  void set_offset_and_size(int i, uint32_t absolute_offset, uint32_t size);

  void zero_out_padding_bytes(uint32_t num_bytes);

  void set_null_at(int i) {
    util::set_bit(buffer_->data() + starting_offset_ + bytes_before_bitmap_, i);
  }

  void set_not_null_at(int i) {
    util::clear_bit(buffer_->data() + starting_offset_ + bytes_before_bitmap_,
                    i);
  }

  bool is_null_at(int i) const;

  virtual void write(int i, int8_t value) = 0;

  virtual void write(int i, bool value) = 0;

  virtual void write(int i, int16_t value) = 0;

  virtual void write(int i, int32_t value) = 0;

  virtual void write(int i, float value) = 0;

  virtual void write(int i, int64_t value) = 0;

  virtual void write(int i, double value) = 0;

  void write_long(int i, int64_t value) {
    buffer_->unsafe_put(get_offset(i), value);
  }

  void write_double(int i, double value) {
    buffer_->unsafe_put(get_offset(i), value);
  }

  void write_string(int i, std::string_view value);

  void write_bytes(int i, const uint8_t *input, uint32_t length);

  void write_unaligned(int i, const uint8_t *input, uint32_t offset,
                       uint32_t num_bytes);

  void write_row(int i, const std::shared_ptr<Row> &row_data);

  void write_array(int i, const std::shared_ptr<ArrayData> &array_data);

  void write_map(int i, const std::shared_ptr<MapData> &map_data);

  void write_aligned(int i, const uint8_t *input, uint32_t offset,
                     uint32_t num_bytes);

  void write_directly(int64_t value);

  void write_directly(uint32_t offset, int64_t value);

  void set_buffer(std::shared_ptr<Buffer> buffer) {
    buffer_ = buffer;
    for (auto child : children_) {
      child->set_buffer(buffer);
    }
  }

  virtual ~Writer() = default;

protected:
  explicit Writer(int bytes_before_bitmap);

  explicit Writer(Writer *parent_writer, int bytes_before_bitmap);

  std::shared_ptr<Buffer> buffer_;

  // The offset of the global buffer where we start to write_string this
  // structure.
  uint32_t starting_offset_;

  // avoid polymorphic set_null_at/set_not_null_at to inline for performance.
  // array use 8 byte for num_elements
  int bytes_before_bitmap_;
  // hold children writer to update buffer recursively.
  std::vector<Writer *> children_;
};

/// Must call `reset()`/`reset(buffer)` before use this writer to write a row.
class RowWriter : public Writer {
public:
  explicit RowWriter(const SchemaPtr &schema);

  explicit RowWriter(const SchemaPtr &schema, Writer *writer);

  SchemaPtr schema() { return schema_; }

  void reset();

  int get_offset(int i) const override {
    return starting_offset_ + header_in_bytes_ + 8 * i;
  }

  void write(int i, int8_t value) override;

  void write(int i, bool value) override;

  void write(int i, int16_t value) override;

  void write(int i, int32_t value) override;

  void write(int i, int64_t value) override;

  void write(int i, float value) override;

  void write(int i, double value) override;

  std::shared_ptr<Row> to_row();

private:
  SchemaPtr schema_;
  uint32_t header_in_bytes_;
  uint32_t fixed_size_;
};

/// Must call reset(num_elements) before use this writer to writer an array
/// every time.
class ArrayWriter : public Writer {
public:
  explicit ArrayWriter(ListTypePtr type);

  explicit ArrayWriter(ListTypePtr type, Writer *writer);

  void reset(uint32_t num_elements);

  int get_offset(int i) const override {
    return starting_offset_ + header_in_bytes_ + i * element_size_;
  }

  void write(int i, int8_t value) override;

  void write(int i, bool value) override;

  void write(int i, int16_t value) override;

  void write(int i, int32_t value) override;

  void write(int i, int64_t value) override;

  void write(int i, float value) override;

  void write(int i, double value) override;

  /// note: this will create a new buffer, won't take ownership of writer's
  /// buffer
  std::shared_ptr<ArrayData> copy_to_array_data();

  int size() { return cursor() - starting_offset_; }

  ListTypePtr type() { return type_; }

private:
  ListTypePtr type_;
  int element_size_;
  int num_elements_;
  int header_in_bytes_;
};

} // namespace row
} // namespace fory
