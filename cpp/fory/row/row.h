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

  virtual bool IsNullAt(int i) const = 0;

  virtual int GetOffset(int i) const = 0;

  int8_t GetInt8(int i) const {
    return buffer()->GetByteAs<int8_t>(GetOffset(i));
  }

  int8_t GetUInt8(int i) const {
    return buffer()->GetByteAs<uint8_t>(GetOffset(i));
  }

  bool GetBoolean(int i) const {
    return buffer()->GetByteAs<uint8_t>(GetOffset(i)) != 0;
  }

  int16_t GetInt16(int i) const { return buffer()->Get<int16_t>(GetOffset(i)); }

  int32_t GetInt32(int i) const { return buffer()->Get<int32_t>(GetOffset(i)); }

  int64_t GetInt64(int i) const { return buffer()->Get<int64_t>(GetOffset(i)); }

  uint64_t GetUint64(int i) const {
    return buffer()->Get<uint64_t>(GetOffset(i));
  }

  float GetFloat(int i) const { return buffer()->Get<float>(GetOffset(i)); }

  double GetDouble(int i) const { return buffer()->Get<double>(GetOffset(i)); }

  int GetBinary(int i, uint8_t **out) const;

  std::vector<uint8_t> GetBinary(int i) const;

  std::string GetString(int i) const;

  std::shared_ptr<Row> GetStruct(int i, StructTypePtr struct_type) const;

  virtual std::shared_ptr<Row> GetStruct(int i) const = 0;

  std::shared_ptr<ArrayData> GetArray(int i, ListTypePtr array_type) const;

  virtual std::shared_ptr<ArrayData> GetArray(int i) const = 0;

  std::shared_ptr<MapData> GetMap(int i, MapTypePtr map_type) const;

  virtual std::shared_ptr<MapData> GetMap(int i) const = 0;

  virtual std::string ToString() const = 0;

protected:
  void AppendValue(std::stringstream &ss, int i, DataTypePtr type) const;
};

class Setter {
public:
  virtual ~Setter() = default;

  virtual std::shared_ptr<Buffer> buffer() const = 0;

  virtual int GetOffset(int i) const = 0;

  virtual void SetNullAt(int i) = 0;

  virtual void SetNotNullAt(int i) = 0;

  void SetInt8(int i, int8_t value) {
    buffer()->UnsafePutByte<int8_t>(GetOffset(i), value);
  }

  void SetUInt8(int i, uint8_t value) {
    buffer()->UnsafePutByte<uint8_t>(GetOffset(i), value);
  }

  void SetBoolean(int i, bool value) {
    buffer()->UnsafePutByte<bool>(GetOffset(i), value);
  }

  void SetInt16(int i, int16_t value) {
    buffer()->UnsafePut<int16_t>(GetOffset(i), value);
  }

  void SetInt32(int i, int32_t value) {
    buffer()->UnsafePut<int32_t>(GetOffset(i), value);
  }

  void SetInt64(int i, int64_t value) {
    buffer()->UnsafePut<int64_t>(GetOffset(i), value);
  }

  void SetFloat(int i, float value) {
    buffer()->UnsafePut<float>(GetOffset(i), value);
  }

  void SetDouble(int i, double value) {
    buffer()->UnsafePut<double>(GetOffset(i), value);
  }
};

class Row : public Getter, Setter {
public:
  explicit Row(const SchemaPtr &schema);

  ~Row() override = default;

  void PointTo(std::shared_ptr<Buffer> buffer, int offset, int size_in_bytes);

  std::shared_ptr<Buffer> buffer() const override { return buffer_; }

  int base_offset() const override { return base_offset_; }

  int size_bytes() const override { return size_bytes_; }

  SchemaPtr schema() const { return schema_; }

  int num_fields() const { return num_fields_; }

  bool IsNullAt(int i) const override {
    return util::GetBit(buffer_->data() + base_offset_,
                        static_cast<uint32_t>(i));
  }

  int GetOffset(int i) const override {
    return base_offset_ + bitmap_width_bytes_ + i * 8;
  }

  std::shared_ptr<Row> GetStruct(int i) const override {
    return Getter::GetStruct(
        i, std::dynamic_pointer_cast<StructType>(schema_->field(i)->type()));
  }

  std::shared_ptr<ArrayData> GetArray(int i) const override {
    return Getter::GetArray(
        i, std::dynamic_pointer_cast<ListType>(schema_->field(i)->type()));
  }

  std::shared_ptr<MapData> GetMap(int i) const override {
    return Getter::GetMap(
        i, std::dynamic_pointer_cast<MapType>(schema_->field(i)->type()));
  }

  void SetNullAt(int i) override {
    util::SetBit(buffer()->data() + base_offset_, i);
  }

  void SetNotNullAt(int i) override {
    util::ClearBit(buffer()->data() + base_offset_, i);
  }

  std::string ToString() const override;

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
  static std::shared_ptr<ArrayData> From(const std::vector<int32_t> &vec);

  static std::shared_ptr<ArrayData> From(const std::vector<int64_t> &vec);

  static std::shared_ptr<ArrayData> From(const std::vector<float> &vec);

  static std::shared_ptr<ArrayData> From(const std::vector<double> &vec);

  explicit ArrayData(ListTypePtr type);

  ~ArrayData() override = default;

  void PointTo(std::shared_ptr<Buffer> buffer, uint32_t offset,
               uint32_t size_bytes);

  std::shared_ptr<Buffer> buffer() const override { return buffer_; }

  int base_offset() const override { return base_offset_; }

  int size_bytes() const override { return size_bytes_; }

  ListTypePtr type() const { return type_; }

  int num_elements() const { return num_elements_; }

  bool IsNullAt(int i) const override {
    return util::GetBit(buffer_->data() + base_offset_ + 8,
                        static_cast<uint32_t>(i));
  }

  int GetOffset(int i) const override {
    return element_offset_ + i * element_size_;
  }

  std::shared_ptr<Row> GetStruct(int i) const override {
    return Getter::GetStruct(
        i, std::dynamic_pointer_cast<StructType>(type_->value_type()));
  }

  std::shared_ptr<ArrayData> GetArray(int i) const override {
    return Getter::GetArray(
        i, std::dynamic_pointer_cast<ListType>(type_->value_type()));
  }

  std::shared_ptr<MapData> GetMap(int i) const override {
    return Getter::GetMap(
        i, std::dynamic_pointer_cast<MapType>(type_->value_type()));
  }

  void SetNullAt(int i) override {
    util::SetBit(buffer_->data() + base_offset_ + 8, i);
    // we assume the corresponding column was already 0
    // or will be set to 0 later by the caller side
  }

  void SetNotNullAt(int i) override {
    util::ClearBit(buffer_->data() + base_offset_ + 8, i);
  }

  std::string ToString() const override;

  static int CalculateHeaderInBytes(int num_elements);

  static int *GetDimensions(ArrayData &array, int numDimensions);

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

  void PointTo(std::shared_ptr<Buffer> buffer, uint32_t offset,
               uint32_t size_bytes);

  MapTypePtr type() { return type_; }

  int num_elements() { return keys_->num_elements(); }

  std::shared_ptr<ArrayData> keys_array() { return keys_; }

  std::shared_ptr<ArrayData> values_array() { return values_; }

  std::shared_ptr<Buffer> buffer() { return buffer_; }

  uint32_t base_offset() { return base_offset_; }

  uint32_t size_bytes() { return size_bytes_; }

  std::string ToString() const;

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
