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

  inline void IncreaseCursor(uint32_t val) {
    buffer_->IncreaseWriterIndex(val);
  }

  inline void Grow(uint32_t needed_size) { buffer_->Grow(needed_size); }

  virtual int GetOffset(int i) const = 0;

  void SetOffsetAndSize(int i, uint32_t size) {
    SetOffsetAndSize(i, buffer_->writer_index(), size);
  }

  void SetOffsetAndSize(int i, uint32_t absolute_offset, uint32_t size);

  void ZeroOutPaddingBytes(uint32_t num_bytes);

  void SetNullAt(int i) {
    util::SetBit(buffer_->data() + starting_offset_ + bytes_before_bitmap_, i);
  }

  void SetNotNullAt(int i) {
    util::ClearBit(buffer_->data() + starting_offset_ + bytes_before_bitmap_,
                   i);
  }

  bool IsNullAt(int i) const;

  virtual void Write(int i, int8_t value) = 0;

  virtual void Write(int i, bool value) = 0;

  virtual void Write(int i, int16_t value) = 0;

  virtual void Write(int i, int32_t value) = 0;

  virtual void Write(int i, float value) = 0;

  virtual void Write(int i, int64_t value) = 0;

  virtual void Write(int i, double value) = 0;

  void WriteLong(int i, int64_t value) {
    buffer_->UnsafePut(GetOffset(i), value);
  }

  void WriteDouble(int i, double value) {
    buffer_->UnsafePut(GetOffset(i), value);
  }

  void WriteString(int i, std::string_view value);

  void WriteBytes(int i, const uint8_t *input, uint32_t length);

  void WriteUnaligned(int i, const uint8_t *input, uint32_t offset,
                      uint32_t num_bytes);

  void WriteRow(int i, const std::shared_ptr<Row> &row_data);

  void WriteArray(int i, const std::shared_ptr<ArrayData> &array_data);

  void WriteMap(int i, const std::shared_ptr<MapData> &map_data);

  void WriteAligned(int i, const uint8_t *input, uint32_t offset,
                    uint32_t num_bytes);

  void WriteDirectly(int64_t value);

  void WriteDirectly(uint32_t offset, int64_t value);

  void SetBuffer(std::shared_ptr<Buffer> buffer) {
    buffer_ = buffer;
    for (auto child : children_) {
      child->SetBuffer(buffer);
    }
  }

  virtual ~Writer() = default;

protected:
  explicit Writer(int bytes_before_bitmap);

  explicit Writer(Writer *parent_writer, int bytes_before_bitmap);

  std::shared_ptr<Buffer> buffer_;

  // The offset of the global buffer where we start to WriteString this
  // structure.
  uint32_t starting_offset_;

  // avoid polymorphic setNullAt/setNotNullAt to inline for performance.
  // array use 8 byte for numElements
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

  void Reset();

  int GetOffset(int i) const override {
    return starting_offset_ + header_in_bytes_ + 8 * i;
  }

  void Write(int i, int8_t value) override;

  void Write(int i, bool value) override;

  void Write(int i, int16_t value) override;

  void Write(int i, int32_t value) override;

  void Write(int i, int64_t value) override;

  void Write(int i, float value) override;

  void Write(int i, double value) override;

  std::shared_ptr<Row> ToRow();

private:
  SchemaPtr schema_;
  uint32_t header_in_bytes_;
  uint32_t fixed_size_;
};

/// Must call reset(numElements) before use this writer to writer an array every
/// time.
class ArrayWriter : public Writer {
public:
  explicit ArrayWriter(ListTypePtr type);

  explicit ArrayWriter(ListTypePtr type, Writer *writer);

  void Reset(uint32_t num_elements);

  int GetOffset(int i) const override {
    return starting_offset_ + header_in_bytes_ + i * element_size_;
  }

  void Write(int i, int8_t value) override;

  void Write(int i, bool value) override;

  void Write(int i, int16_t value) override;

  void Write(int i, int32_t value) override;

  void Write(int i, int64_t value) override;

  void Write(int i, float value) override;

  void Write(int i, double value) override;

  /// note: this will create a new buffer, won't take ownership of writer's
  /// buffer
  std::shared_ptr<ArrayData> CopyToArrayData();

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
