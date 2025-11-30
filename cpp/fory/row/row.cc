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

#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <typeinfo>
#include <utility>
#include <vector>

#include "fory/row/row.h"
#include "fory/util/logging.h"

namespace fory {
namespace row {

int Getter::GetBinary(int i, uint8_t **out) const {
  if (IsNullAt(i))
    return -1;
  auto offsetAndSize = GetUint64(i);
  auto relative_offset = static_cast<uint32_t>(offsetAndSize >> 32);
  auto size = static_cast<uint32_t>(offsetAndSize);
  *out = buffer()->data() + base_offset() + relative_offset;
  return size;
}

std::vector<uint8_t> Getter::GetBinary(int i) const {
  if (IsNullAt(i))
    return std::vector<uint8_t>();
  auto offsetAndSize = GetUint64(i);
  auto relative_offset = static_cast<uint32_t>(offsetAndSize >> 32);
  auto size = static_cast<uint32_t>(offsetAndSize);
  auto start = buffer()->data() + base_offset() + relative_offset;
  return std::vector<uint8_t>(start, start + size);
}

std::string Getter::GetString(int i) const {
  uint8_t *binary;
  int size = GetBinary(i, &binary);
  if (size == -1) {
    return std::string("");
  } else {
    return std::string(reinterpret_cast<char *>(binary),
                       static_cast<size_t>(size));
  }
}

std::shared_ptr<Row> Getter::GetStruct(int i, StructTypePtr struct_type) const {
  if (IsNullAt(i))
    return nullptr;
  auto offsetAndSize = GetUint64(i);
  auto relative_offset = static_cast<uint32_t>(offsetAndSize >> 32);
  auto size = static_cast<uint32_t>(offsetAndSize);
  auto row_schema = schema(struct_type->fields());
  std::shared_ptr<Row> row = std::make_shared<Row>(row_schema);
  row->PointTo(buffer(), base_offset() + relative_offset, size);
  return row;
}

std::shared_ptr<ArrayData> Getter::GetArray(int i,
                                            ListTypePtr array_type) const {
  if (IsNullAt(i))
    return nullptr;
  auto offsetAndSize = GetUint64(i);
  auto relative_offset = static_cast<uint32_t>(offsetAndSize >> 32);
  auto size = static_cast<uint32_t>(offsetAndSize);
  auto arr = std::make_shared<ArrayData>(array_type);
  arr->PointTo(buffer(), base_offset() + relative_offset, size);
  return arr;
}

std::shared_ptr<MapData> Getter::GetMap(int i, MapTypePtr map_type) const {
  if (IsNullAt(i))
    return nullptr;
  auto offsetAndSize = GetUint64(i);
  auto relative_offset = static_cast<uint32_t>(offsetAndSize >> 32);
  auto size = static_cast<uint32_t>(offsetAndSize);
  auto map_data = std::make_shared<MapData>(map_type);
  map_data->PointTo(buffer(), base_offset() + relative_offset, size);
  return map_data;
}

void Getter::AppendValue(std::stringstream &ss, int i, DataTypePtr type) const {
  TypeId type_id = type->id();
  if (type_id == TypeId::INT8) {
    ss << static_cast<int>(GetInt8(i));
  } else if (type_id == TypeId::BOOL) {
    ss << GetBoolean(i);
  } else if (type_id == TypeId::INT16) {
    ss << GetInt16(i);
  } else if (type_id == TypeId::INT32) {
    ss << GetInt32(i);
  } else if (type_id == TypeId::INT64) {
    ss << GetInt64(i);
  } else if (type_id == TypeId::FLOAT32) {
    ss << GetFloat(i);
  } else if (type_id == TypeId::FLOAT64) {
    ss << GetDouble(i);
  } else if (type_id == TypeId::STRING) {
    ss << GetString(i);
  } else if (type_id == TypeId::LIST) {
    ss << GetArray(i)->ToString();
  } else if (type_id == TypeId::MAP) {
    ss << GetMap(i)->ToString();
  } else if (type_id == TypeId::STRUCT) {
    ss << GetStruct(i)->ToString();
  } else if (type_id == TypeId::BINARY) {
    ss << GetString(i);
  } else {
    ss << "unsupported type " << type->ToString();
  }
}

Row::Row(const SchemaPtr &schema)
    : schema_(schema), num_fields_(schema->num_fields()) {
  base_offset_ = 0;
  size_bytes_ = 0;
  bitmap_width_bytes_ = ((num_fields_ + 63) / 64) * 8;
}

void Row::PointTo(std::shared_ptr<Buffer> buffer, int offset,
                  int size_in_bytes) {
  buffer_ = std::move(buffer);
  base_offset_ = offset;
  size_bytes_ = size_in_bytes;
}

std::string Row::ToString() const {
  if (!buffer_) {
    return std::string("null");
  } else {
    std::stringstream ss;
    ss << "{";
    for (int i = 0; i < num_fields_; i++) {
      if (i != 0) {
        ss << ", ";
      }
      auto f = schema_->field(i);
      ss << f->name() << "=";
      if (IsNullAt(i)) {
        ss << "null";
      } else {
        auto type = f->type();
        AppendValue(ss, i, type);
      }
    }
    ss << "}";
    return ss.str();
  }
}

std::ostream &operator<<(std::ostream &os, const Row &data) {
  os << data.ToString();
  return os;
}

template <typename value_type>
std::shared_ptr<ArrayData> ArrayDataFrom(const value_type *data,
                                         int num_elements, int element_size,
                                         const ListTypePtr &type) {
  auto array_data = std::make_shared<ArrayData>(type);
  std::shared_ptr<Buffer> buffer;
  auto header_bytes = ArrayData::CalculateHeaderInBytes(num_elements);
  auto size_bytes = header_bytes + num_elements * element_size;
  AllocateBuffer(static_cast<int32_t>(size_bytes), &buffer);
  buffer->ZeroPadding();
  buffer->UnsafePut(0, static_cast<int64_t>(num_elements));
  buffer->CopyFrom(header_bytes, reinterpret_cast<const uint8_t *>(data), 0,
                   static_cast<int32_t>(num_elements * element_size));
  array_data->PointTo(buffer, 0, size_bytes);
  return array_data;
}

std::shared_ptr<ArrayData> ArrayData::From(const std::vector<int32_t> &vec) {
  return ArrayDataFrom(vec.data(), static_cast<int>(vec.size()), 4,
                       list(int32()));
}

std::shared_ptr<ArrayData> ArrayData::From(const std::vector<int64_t> &vec) {
  return ArrayDataFrom(vec.data(), static_cast<int>(vec.size()), 8,
                       list(int64()));
}

std::shared_ptr<ArrayData> ArrayData::From(const std::vector<float> &vec) {
  return ArrayDataFrom(vec.data(), static_cast<int>(vec.size()), 4,
                       list(float32()));
}

std::shared_ptr<ArrayData> ArrayData::From(const std::vector<double> &vec) {
  return ArrayDataFrom(vec.data(), static_cast<int>(vec.size()), 8,
                       list(float64()));
}

ArrayData::ArrayData(ListTypePtr type) : type_(std::move(type)) {
  int width = get_byte_width(type_->value_type());
  // variable-length element type
  if (width < 0) {
    element_size_ = 8;
  } else {
    element_size_ = width;
  }
}

void ArrayData::PointTo(std::shared_ptr<Buffer> buffer, uint32_t offset,
                        uint32_t size_bytes) {
  num_elements_ = static_cast<int>(buffer->Get<int64_t>(offset));
  buffer_ = std::move(buffer);
  base_offset_ = offset;
  size_bytes_ = size_bytes;
  element_offset_ = offset + CalculateHeaderInBytes(num_elements_);
}

int ArrayData::CalculateHeaderInBytes(int num_elements) {
  return 8 + ((num_elements + 63) / 64) * 8;
}

int *ArrayData::GetDimensions(ArrayData &array, int num_dims) {
  // use deep-first search to search to numDimensions-1 layer to get dimensions.
  int depth = 0;
  auto dimensions = new int[num_dims];
  std::vector<int> start_from_lefts(num_dims);
  std::vector<const ArrayData *> arrs(num_dims); // root to current node
  ArrayData &arr = array;
  while (depth < num_dims) {
    arrs[depth] = &arr;
    int size = arr.num_elements();
    dimensions[depth] = size;
    if (depth == num_dims - 1) {
      break;
    }
    bool all_null = true;
    if (start_from_lefts[depth] == size) {
      // this node's subtree has all be traversed, but no node has depth count
      // to num_dims-1.
      start_from_lefts[depth] = 0;
      depth--;
      continue;
    }
    for (int i = start_from_lefts[depth]; i < size; i++) {
      if (!arr.IsNullAt(i)) {
        arr = *arr.GetArray(i);
        all_null = false;
        break;
      }
    }
    if (all_null) {
      // start_from_lefts[depth-1] = 0;
      depth--; // move up to parent node
      start_from_lefts[depth]++;
      arr = *arrs[depth];
    } else {
      depth++;
    }
    if (depth <= 0) {
      return nullptr;
    }
  }

  return dimensions;
}

std::string ArrayData::ToString() const {
  if (!buffer_) {
    return std::string("null");
  } else {
    std::stringstream ss;
    ss << "[";
    for (int i = 0; i < num_elements_; i++) {
      if (i != 0) {
        ss << ", ";
      }
      if (IsNullAt(i)) {
        ss << "null";
      } else {
        auto type = type_->value_type();
        AppendValue(ss, i, type);
      }
    }
    ss << "]";
    return ss.str();
  }
}

std::ostream &operator<<(std::ostream &os, const ArrayData &data) {
  os << data.ToString();
  return os;
}

MapData::MapData(MapTypePtr type) : type_(std::move(type)) {
  keys_ = std::make_shared<ArrayData>(list(type_->key_type()));
  values_ = std::make_shared<ArrayData>(list(type_->item_type()));
}

void MapData::PointTo(std::shared_ptr<Buffer> buffer, uint32_t offset,
                      uint32_t size_bytes) {
  buffer_ = std::move(buffer);
  base_offset_ = offset;
  size_bytes_ = size_bytes;
  auto key_array_size = static_cast<int32_t>(buffer_->Get<uint64_t>(offset));
  int32_t value_array_size = size_bytes - 8 - key_array_size;
  keys_->PointTo(buffer_, offset + 8, key_array_size);
  values_->PointTo(buffer_, offset + 8 + key_array_size, value_array_size);
}

std::string MapData::ToString() const {
  if (!buffer_) {
    return std::string("null");
  } else {
    std::stringstream ss;
    ss << "Map(" << keys_->ToString() << ", " << values_->ToString() << ")";
    return ss.str();
  }
}

std::ostream &operator<<(std::ostream &os, const MapData &data) {
  os << data.ToString();
  return os;
}

} // namespace row
} // namespace fory
