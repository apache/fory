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

#include "fory/row/writer.h"
#include <cassert>
#include <iostream>
#include <memory>

namespace fory {
namespace row {

Writer::Writer(int bytes_before_bitmap)
    : bytes_before_bitmap_(bytes_before_bitmap) {}

Writer::Writer(Writer *parent_writer, int bytes_before_bitmap)
    : buffer_(parent_writer->buffer()),
      bytes_before_bitmap_(bytes_before_bitmap) {
  parent_writer->children().push_back(this);
}

void Writer::set_offset_and_size(int i, uint32_t absolute_offset,
                                 uint32_t size) {
  const uint64_t relative_offset = absolute_offset - starting_offset_;
  const int64_t offset_and_size = (relative_offset << 32) | (int64_t)size;
  write(i, offset_and_size);
}

void Writer::zero_out_padding_bytes(uint32_t num_bytes) {
  if ((num_bytes & 0x07) > 0) {
    buffer_->unsafe_put<int64_t>(
        buffer_->writer_index() + ((num_bytes >> 3) << 3), 0);
  }
}

bool Writer::is_null_at(int i) const {
  return util::get_bit(buffer_->data() + starting_offset_ +
                           bytes_before_bitmap_,
                       static_cast<uint32_t>(i));
}

void Writer::write_string(int i, std::string_view value) {
  write_bytes(i, reinterpret_cast<const uint8_t *>(value.data()),
              static_cast<int32_t>(value.size()));
}

void Writer::write_bytes(int i, const uint8_t *input, uint32_t length) {
  write_unaligned(i, input, 0, length);
}

void Writer::write_unaligned(int i, const uint8_t *input, uint32_t offset,
                             uint32_t num_bytes) {
  int round_size = util::round_number_of_bytes_to_nearest_word(num_bytes);
  buffer_->grow(round_size);
  zero_out_padding_bytes(num_bytes);
  buffer_->unsafe_put(cursor(), input + offset, num_bytes);
  set_offset_and_size(i, num_bytes);
  buffer_->increase_writer_index(round_size);
}

void Writer::write_row(int i, const std::shared_ptr<Row> &row_data) {
  write_aligned(i, row_data->buffer()->data(), row_data->base_offset(),
                row_data->size_bytes());
}

void Writer::write_array(int i, const std::shared_ptr<ArrayData> &array_data) {
  write_aligned(i, array_data->buffer()->data(), array_data->base_offset(),
                array_data->size_bytes());
}

void Writer::write_map(int i, const std::shared_ptr<MapData> &map_data) {
  write_aligned(i, map_data->buffer()->data(), map_data->base_offset(),
                map_data->size_bytes());
}

void Writer::write_aligned(int i, const uint8_t *input, uint32_t offset,
                           uint32_t num_bytes) {
  buffer_->grow(num_bytes);
  buffer_->unsafe_put(cursor(), input + offset, num_bytes);
  set_offset_and_size(i, num_bytes);
  buffer_->increase_writer_index(num_bytes);
}

void Writer::write_directly(int64_t value) {
  buffer_->grow(8);
  buffer_->unsafe_put(cursor(), value);
  buffer_->increase_writer_index(8);
}

void Writer::write_directly(uint32_t offset, int64_t value) {
  buffer_->unsafe_put(offset, value);
}

RowWriter::RowWriter(const SchemaPtr &schema) : Writer(0), schema_(schema) {
  starting_offset_ = 0;
  allocate_buffer(schema->num_fields() * 8, &buffer_);
  header_in_bytes_ =
      static_cast<int32_t>(((schema->num_fields() + 63) / 64) * 8);
  fixed_size_ = header_in_bytes_ + schema->num_fields() * 8;
}

RowWriter::RowWriter(const SchemaPtr &schema, Writer *parent_writer)
    : Writer(parent_writer, 0), schema_(schema) {
  // Since we must call reset before use this writer,
  // there's no need to set starting_offset_ here.
  header_in_bytes_ =
      static_cast<int32_t>(((schema->num_fields() + 63) / 64) * 8);
  fixed_size_ = header_in_bytes_ + schema->num_fields() * 8;
}

void RowWriter::reset() {
  starting_offset_ = cursor();
  grow(fixed_size_);
  buffer_->increase_writer_index(fixed_size_);
  int end = starting_offset_ + header_in_bytes_;
  for (int i = starting_offset_; i < end; i += 8) {
    buffer_->unsafe_put<int64_t>(i, 0L);
  }
}

void RowWriter::write(int i, int8_t value) {
  int offset = get_offset(i);
  buffer_->unsafe_put<int64_t>(offset, 0L);
  buffer_->unsafe_put_byte<int8_t>(offset, value);
}

void RowWriter::write(int i, bool value) {
  int offset = get_offset(i);
  buffer_->unsafe_put<int64_t>(offset, 0L);
  buffer_->unsafe_put_byte<bool>(offset, value);
}

void RowWriter::write(int i, int16_t value) {
  int offset = get_offset(i);
  buffer_->unsafe_put<int64_t>(offset, 0L);
  buffer_->unsafe_put(offset, value);
}

void RowWriter::write(int i, int32_t value) {
  int offset = get_offset(i);
  buffer_->unsafe_put<int64_t>(offset, 0L);
  buffer_->unsafe_put(offset, value);
}

void RowWriter::write(int i, int64_t value) {
  buffer_->unsafe_put(get_offset(i), value);
}

void RowWriter::write(int i, float value) {
  int offset = get_offset(i);
  buffer_->unsafe_put<int64_t>(offset, 0L);
  buffer_->unsafe_put(offset, value);
}

void RowWriter::write(int i, double value) {
  buffer_->unsafe_put(get_offset(i), value);
}

std::shared_ptr<Row> RowWriter::to_row() {
  auto row = std::make_shared<Row>(schema_);
  row->point_to(buffer_, starting_offset_, size());
  return row;
}

ArrayWriter::ArrayWriter(ListTypePtr type) : Writer(8), type_(std::move(type)) {
  allocate_buffer(64, &buffer_);
  starting_offset_ = 0;
  int width = get_byte_width(type_->value_type());
  // variable-length element type
  if (width < 0) {
    element_size_ = 8;
  } else {
    element_size_ = width;
  }
}

ArrayWriter::ArrayWriter(ListTypePtr type, Writer *writer)
    : Writer(writer, 8), type_(std::move(type)) {
  starting_offset_ = 0;
  int width = get_byte_width(type_->value_type());
  // variable-length element type
  if (width < 0) {
    element_size_ = 8;
  } else {
    element_size_ = width;
  }
}

void ArrayWriter::reset(uint32_t num_elements) {
  starting_offset_ = cursor();
  num_elements_ = num_elements;
  // num_elements use 8 byte, null_bits_size_in_bytes use multiple of 8 byte
  header_in_bytes_ = 8 + ((num_elements + 63) / 64) * 8;
  uint64_t data_size = num_elements_ * (uint64_t)element_size_;
  FORY_CHECK(data_size < std::numeric_limits<int>::max());
  int fixed_part_bytes =
      util::round_number_of_bytes_to_nearest_word(static_cast<int>(data_size));
  assert((fixed_part_bytes >= data_size) && "too much elements");
  buffer_->grow(header_in_bytes_ + fixed_part_bytes);

  // write num_elements and clear out null bits to header
  // store num_elements in header in aligned 8 byte, although num_elements is 4
  // byte int
  buffer_->unsafe_put<uint64_t>(starting_offset_, num_elements);
  for (int i = starting_offset_ + 8, end = starting_offset_ + header_in_bytes_;
       i < end; i += 8) {
    buffer_->unsafe_put<uint64_t>(i, 0L);
  }

  // fill 0 into 8-bytes alignment part
  for (int i = 0; i < fixed_part_bytes; i = i + 8) {
    buffer_->unsafe_put<uint64_t>(starting_offset_ + header_in_bytes_ + i, 0);
  }
  buffer_->increase_writer_index(header_in_bytes_ + fixed_part_bytes);
}

void ArrayWriter::write(int i, int8_t value) {
  buffer_->unsafe_put_byte<int8_t>(get_offset(i), value);
}

void ArrayWriter::write(int i, bool value) {
  buffer_->unsafe_put_byte<bool>(get_offset(i), value);
}

void ArrayWriter::write(int i, int16_t value) {
  buffer_->unsafe_put(get_offset(i), value);
}

void ArrayWriter::write(int i, int32_t value) {
  buffer_->unsafe_put(get_offset(i), value);
}

void ArrayWriter::write(int i, int64_t value) {
  buffer_->unsafe_put(get_offset(i), value);
}

void ArrayWriter::write(int i, float value) {
  buffer_->unsafe_put(get_offset(i), value);
}

void ArrayWriter::write(int i, double value) {
  buffer_->unsafe_put(get_offset(i), value);
}

std::shared_ptr<ArrayData> ArrayWriter::copy_to_array_data() {
  auto array_data = std::make_shared<ArrayData>(type_);
  std::shared_ptr<Buffer> buf;
  allocate_buffer(size(), &buf);
  buffer_->copy(starting_offset_, size(), buf);
  array_data->point_to(buf, 0, size());
  return array_data;
}

} // namespace row
} // namespace fory
