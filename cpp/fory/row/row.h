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

#include <iostream>

#include "fory/row/schema.h"
#include "fory/util/bit_util.h"
#include "fory/util/buffer.h"

namespace fory {
namespace row {

class ArrayData;

class MapData;

class Row;

class Getter {
public:
  virtual ~Getter() = default;

  virtual std::shared_ptr<Buffer> buffer() const = 0;

  virtual int base_offset() const = 0;

  virtual int size_bytes() const = 0;

  virtual bool is_null_at(int i) const = 0;

  virtual int get_offset(int i) const = 0;

  int8_t get_int8(int i) const {
    return buffer()->get_byte_as<int8_t>(get_offset(i));
  }

  int8_t get_uint8(int i) const {
    return buffer()->get_byte_as<uint8_t>(get_offset(i));
  }

  bool get_boolean(int i) const {
    return buffer()->get_byte_as<uint8_t>(get_offset(i)) != 0;
  }

  int16_t get_int16(int i) const {
    return buffer()->get<int16_t>(get_offset(i));
  }

  int32_t get_int32(int i) const {
    return buffer()->get<int32_t>(get_offset(i));
  }

  int64_t get_int64(int i) const {
    return buffer()->get<int64_t>(get_offset(i));
  }

  uint64_t get_uint64(int i) const {
    return buffer()->get<uint64_t>(get_offset(i));
  }

  float get_float(int i) const { return buffer()->get<float>(get_offset(i)); }

  double get_double(int i) const {
    return buffer()->get<double>(get_offset(i));
  }

  int get_binary(int i, uint8_t **out) const;

  std::vector<uint8_t> get_binary(int i) const;

  std::string get_string(int i) const;

  std::shared_ptr<Row> get_struct(int i, StructTypePtr struct_type) const;

  virtual std::shared_ptr<Row> get_struct(int i) const = 0;

  std::shared_ptr<ArrayData> get_array(int i, ListTypePtr array_type) const;

  virtual std::shared_ptr<ArrayData> get_array(int i) const = 0;

  std::shared_ptr<MapData> get_map(int i, MapTypePtr map_type) const;

  virtual std::shared_ptr<MapData> get_map(int i) const = 0;

  virtual std::string to_string() const = 0;

protected:
  void append_value(std::stringstream &ss, int i, DataTypePtr type) const;
};

class Setter {
public:
  virtual ~Setter() = default;

  virtual std::shared_ptr<Buffer> buffer() const = 0;

  virtual int get_offset(int i) const = 0;

  virtual void set_null_at(int i) = 0;

  virtual void set_not_null_at(int i) = 0;

  void set_int8(int i, int8_t value) {
    buffer()->unsafe_put_byte<int8_t>(get_offset(i), value);
  }

  void set_uint8(int i, uint8_t value) {
    buffer()->unsafe_put_byte<uint8_t>(get_offset(i), value);
  }

  void set_boolean(int i, bool value) {
    buffer()->unsafe_put_byte<bool>(get_offset(i), value);
  }

  void set_int16(int i, int16_t value) {
    buffer()->unsafe_put<int16_t>(get_offset(i), value);
  }

  void set_int32(int i, int32_t value) {
    buffer()->unsafe_put<int32_t>(get_offset(i), value);
  }

  void set_int64(int i, int64_t value) {
    buffer()->unsafe_put<int64_t>(get_offset(i), value);
  }

  void set_float(int i, float value) {
    buffer()->unsafe_put<float>(get_offset(i), value);
  }

  void set_double(int i, double value) {
    buffer()->unsafe_put<double>(get_offset(i), value);
  }
};

class Row : public Getter, Setter {
public:
  explicit Row(const SchemaPtr &schema);

  ~Row() override = default;

  void point_to(std::shared_ptr<Buffer> buffer, int offset, int size_in_bytes);

  std::shared_ptr<Buffer> buffer() const override { return buffer_; }

  int base_offset() const override { return base_offset_; }

  int size_bytes() const override { return size_bytes_; }

  SchemaPtr schema() const { return schema_; }

  int num_fields() const { return num_fields_; }

  bool is_null_at(int i) const override {
    return util::get_bit(buffer_->data() + base_offset_,
                         static_cast<uint32_t>(i));
  }

  int get_offset(int i) const override {
    return base_offset_ + bitmap_width_bytes_ + i * 8;
  }

  std::shared_ptr<Row> get_struct(int i) const override {
    return Getter::get_struct(
        i, std::dynamic_pointer_cast<StructType>(schema_->field(i)->type()));
  }

  std::shared_ptr<ArrayData> get_array(int i) const override {
    return Getter::get_array(
        i, std::dynamic_pointer_cast<ListType>(schema_->field(i)->type()));
  }

  std::shared_ptr<MapData> get_map(int i) const override {
    return Getter::get_map(
        i, std::dynamic_pointer_cast<MapType>(schema_->field(i)->type()));
  }

  void set_null_at(int i) override {
    util::set_bit(buffer()->data() + base_offset_, i);
  }

  void set_not_null_at(int i) override {
    util::clear_bit(buffer()->data() + base_offset_, i);
  }

  std::string to_string() const override;

private:
  SchemaPtr schema_;
  const int num_fields_;
  mutable std::shared_ptr<Buffer> buffer_;
  int base_offset_;
  int size_bytes_;
  int bitmap_width_bytes_;
};

std::ostream &operator<<(std::ostream &os, const Row &data);

class ArrayData : public Getter, Setter {
public:
  static std::shared_ptr<ArrayData> from(const std::vector<int32_t> &vec);

  static std::shared_ptr<ArrayData> from(const std::vector<int64_t> &vec);

  static std::shared_ptr<ArrayData> from(const std::vector<float> &vec);

  static std::shared_ptr<ArrayData> from(const std::vector<double> &vec);

  explicit ArrayData(ListTypePtr type);

  ~ArrayData() override = default;

  void point_to(std::shared_ptr<Buffer> buffer, uint32_t offset,
                uint32_t size_bytes);

  std::shared_ptr<Buffer> buffer() const override { return buffer_; }

  int base_offset() const override { return base_offset_; }

  int size_bytes() const override { return size_bytes_; }

  ListTypePtr type() const { return type_; }

  int num_elements() const { return num_elements_; }

  bool is_null_at(int i) const override {
    return util::get_bit(buffer_->data() + base_offset_ + 8,
                         static_cast<uint32_t>(i));
  }

  int get_offset(int i) const override {
    return element_offset_ + i * element_size_;
  }

  std::shared_ptr<Row> get_struct(int i) const override {
    return Getter::get_struct(
        i, std::dynamic_pointer_cast<StructType>(type_->value_type()));
  }

  std::shared_ptr<ArrayData> get_array(int i) const override {
    return Getter::get_array(
        i, std::dynamic_pointer_cast<ListType>(type_->value_type()));
  }

  std::shared_ptr<MapData> get_map(int i) const override {
    return Getter::get_map(
        i, std::dynamic_pointer_cast<MapType>(type_->value_type()));
  }

  void set_null_at(int i) override {
    util::set_bit(buffer_->data() + base_offset_ + 8, i);
    // we assume the corresponding column was already 0
    // or will be set to 0 later by the caller side
  }

  void set_not_null_at(int i) override {
    util::clear_bit(buffer_->data() + base_offset_ + 8, i);
  }

  std::string to_string() const override;

  static int calculate_header_in_bytes(int num_elements);

  static int *get_dimensions(ArrayData &array, int num_dimensions);

private:
  ListTypePtr type_;
  int element_size_;
  mutable std::shared_ptr<Buffer> buffer_;
  int num_elements_;
  uint32_t element_offset_;
  uint32_t base_offset_;
  uint32_t size_bytes_;
};

std::ostream &operator<<(std::ostream &os, const ArrayData &data);

class MapData {
public:
  explicit MapData(MapTypePtr type);

  void point_to(std::shared_ptr<Buffer> buffer, uint32_t offset,
                uint32_t size_bytes);

  MapTypePtr type() { return type_; }

  int num_elements() { return keys_->num_elements(); }

  std::shared_ptr<ArrayData> keys_array() { return keys_; }

  std::shared_ptr<ArrayData> values_array() { return values_; }

  std::shared_ptr<Buffer> buffer() { return buffer_; }

  uint32_t base_offset() { return base_offset_; }

  uint32_t size_bytes() { return size_bytes_; }

  std::string to_string() const;

private:
  MapTypePtr type_;
  std::shared_ptr<ArrayData> keys_;
  std::shared_ptr<ArrayData> values_;
  mutable std::shared_ptr<Buffer> buffer_;
  uint32_t base_offset_;
  uint32_t size_bytes_;
};

std::ostream &operator<<(std::ostream &os, const MapData &data);

} // namespace row
} // namespace fory
