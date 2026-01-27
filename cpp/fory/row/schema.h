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

/// \file schema.h
/// \brief Type system for Fory row format.
///
/// This header defines the schema types used by the Fory row format for
/// binary serialization. The type system includes:
/// - Primitive types: bool, int8/16/32/64, float16/32/64
/// - Variable-width types: string (utf8), binary
/// - Temporal types: timestamp, duration, date32
/// - Decimal type with configurable precision and scale
/// - Composite types: list, map, struct
///
/// Usage example:
/// \code
///   auto schema = fory::schema({
///     fory::field("id", fory::int64()),
///     fory::field("name", fory::utf8()),
///     fory::field("scores", fory::list(fory::float32())),
///   });
/// \endcode

#pragma once

#include "fory/type/type.h"
#include "fory/util/buffer.h"
#include <climits>
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace fory {
namespace row {

class DataType;
class Field;
class Schema;
class ListType;
class MapType;
class StructType;
class FixedWidthType;

using DataTypePtr = std::shared_ptr<DataType>;
using FieldPtr = std::shared_ptr<Field>;
using SchemaPtr = std::shared_ptr<Schema>;
using ListTypePtr = std::shared_ptr<ListType>;
using MapTypePtr = std::shared_ptr<MapType>;
using StructTypePtr = std::shared_ptr<StructType>;

/// Base class for all data types in the Fory type system.
class DataType : public std::enable_shared_from_this<DataType> {
public:
  explicit DataType(TypeId id) : id_(id) {}
  virtual ~DataType() = default;

  TypeId id() const { return id_; }

  virtual std::string name() const = 0;

  virtual std::string ToString() const { return name(); }

  virtual bool Equals(const DataType &other) const { return id_ == other.id_; }

  bool Equals(const DataTypePtr &other) const {
    if (!other) {
      return false;
    }
    return Equals(*other);
  }

  virtual int num_fields() const { return 0; }

  virtual FieldPtr field(int /*i*/) const { return nullptr; }

  virtual std::vector<FieldPtr> fields() const { return {}; }

  virtual int bit_width() const { return -1; }

protected:
  TypeId id_;
};

/// Fixed-width primitive types with known bit width.
class FixedWidthType : public DataType {
public:
  explicit FixedWidthType(TypeId id, int bit_width)
      : DataType(id), bit_width_(bit_width) {}

  int bit_width() const override { return bit_width_; }

  int byte_width() const { return (bit_width_ + 7) / 8; }

private:
  int bit_width_;
};

/// Boolean type (stored as 8-bit value).
class BooleanType : public FixedWidthType {
public:
  BooleanType() : FixedWidthType(TypeId::BOOL, 8) {}
  std::string name() const override { return "bool"; }
};

/// Signed 8-bit integer.
class Int8Type : public FixedWidthType {
public:
  Int8Type() : FixedWidthType(TypeId::INT8, 8) {}
  std::string name() const override { return "int8"; }
};

/// Signed 16-bit integer.
class Int16Type : public FixedWidthType {
public:
  Int16Type() : FixedWidthType(TypeId::INT16, 16) {}
  std::string name() const override { return "int16"; }
};

/// Signed 32-bit integer.
class Int32Type : public FixedWidthType {
public:
  Int32Type() : FixedWidthType(TypeId::INT32, 32) {}
  std::string name() const override { return "int32"; }
};

/// Signed 64-bit integer.
class Int64Type : public FixedWidthType {
public:
  Int64Type() : FixedWidthType(TypeId::INT64, 64) {}
  std::string name() const override { return "int64"; }
};

/// 16-bit floating point (half precision).
class Float16Type : public FixedWidthType {
public:
  Float16Type() : FixedWidthType(TypeId::FLOAT16, 16) {}
  std::string name() const override { return "float16"; }
};

/// 32-bit floating point (single precision).
class Float32Type : public FixedWidthType {
public:
  Float32Type() : FixedWidthType(TypeId::FLOAT32, 32) {}
  std::string name() const override { return "float"; }
};

/// 64-bit floating point (double precision).
class Float64Type : public FixedWidthType {
public:
  Float64Type() : FixedWidthType(TypeId::FLOAT64, 64) {}
  std::string name() const override { return "double"; }
};

/// UTF-8 encoded string (variable-width).
class StringType : public DataType {
public:
  StringType() : DataType(TypeId::STRING) {}
  std::string name() const override { return "utf8"; }
};

/// Raw binary data (variable-width).
class BinaryType : public DataType {
public:
  BinaryType() : DataType(TypeId::BINARY) {}
  std::string name() const override { return "binary"; }
};

/// Duration stored as 64-bit integer.
class DurationType : public FixedWidthType {
public:
  DurationType() : FixedWidthType(TypeId::DURATION, 64) {}
  std::string name() const override { return "duration"; }
};

/// Timestamp stored as 64-bit integer (microseconds since epoch).
class TimestampType : public FixedWidthType {
public:
  TimestampType() : FixedWidthType(TypeId::TIMESTAMP, 64) {}
  std::string name() const override { return "timestamp"; }
};

/// Date stored as 32-bit integer (days since epoch).
class LocalDateType : public FixedWidthType {
public:
  LocalDateType() : FixedWidthType(TypeId::DATE, 32) {}
  std::string name() const override { return "date32"; }
};

/// Decimal type with configurable precision and scale.
class DecimalType : public DataType {
public:
  DecimalType(int precision = 38, int scale = 18)
      : DataType(TypeId::DECIMAL), precision_(precision), scale_(scale) {}

  std::string name() const override { return "decimal"; }

  std::string ToString() const override {
    return "decimal(" + std::to_string(precision_) + ", " +
           std::to_string(scale_) + ")";
  }

  int precision() const { return precision_; }
  int scale() const { return scale_; }

private:
  int precision_;
  int scale_;
};

/// A named field with type, nullability, and optional metadata.
class Field {
public:
  Field(std::string name, DataTypePtr type, bool nullable = true,
        std::unordered_map<std::string, std::string> metadata = {})
      : name_(std::move(name)), type_(std::move(type)), nullable_(nullable),
        metadata_(std::move(metadata)) {}

  const std::string &name() const { return name_; }

  const DataTypePtr &type() const { return type_; }

  bool nullable() const { return nullable_; }

  const std::unordered_map<std::string, std::string> &metadata() const {
    return metadata_;
  }

  std::string ToString() const {
    return name_ + ": " + type_->ToString() + (nullable_ ? "" : " not null");
  }

  bool Equals(const Field &other) const {
    return name_ == other.name_ && type_->Equals(*other.type_) &&
           nullable_ == other.nullable_;
  }

  bool Equals(const FieldPtr &other) const {
    if (!other) {
      return false;
    }
    return Equals(*other);
  }

private:
  std::string name_;
  DataTypePtr type_;
  bool nullable_;
  std::unordered_map<std::string, std::string> metadata_;
};

/// Variable-length list of elements with uniform type.
class ListType : public DataType {
public:
  explicit ListType(DataTypePtr value_type)
      : DataType(TypeId::LIST),
        value_field_(std::make_shared<Field>("item", std::move(value_type))) {}

  explicit ListType(FieldPtr value_field)
      : DataType(TypeId::LIST), value_field_(std::move(value_field)) {}

  std::string name() const override { return "list"; }

  std::string ToString() const override {
    return "list<" + value_field_->type()->ToString() + ">";
  }

  const DataTypePtr &value_type() const { return value_field_->type(); }

  const FieldPtr &value_field() const { return value_field_; }

  int num_fields() const override { return 1; }

  FieldPtr field(int i) const override {
    if (i == 0) {
      return value_field_;
    }
    return nullptr;
  }

  std::vector<FieldPtr> fields() const override { return {value_field_}; }

  bool Equals(const DataType &other) const override {
    if (!DataType::Equals(other)) {
      return false;
    }
    const auto &other_list = static_cast<const ListType &>(other);
    return value_field_->type()->Equals(other_list.value_field_->type());
  }

private:
  FieldPtr value_field_;
};

/// Struct type: a sequence of named fields (like a row or record).
class StructType : public DataType {
public:
  explicit StructType(std::vector<FieldPtr> fields)
      : DataType(TypeId::STRUCT), fields_(std::move(fields)) {
    for (size_t i = 0; i < fields_.size(); ++i) {
      name_to_index_[fields_[i]->name()] = static_cast<int>(i);
    }
  }

  std::string name() const override { return "struct"; }

  std::string ToString() const override {
    std::string result = "struct<";
    for (size_t i = 0; i < fields_.size(); ++i) {
      if (i > 0) {
        result += ", ";
      }
      result += fields_[i]->ToString();
    }
    result += ">";
    return result;
  }

  int num_fields() const override { return static_cast<int>(fields_.size()); }

  FieldPtr field(int i) const override {
    if (i >= 0 && i < static_cast<int>(fields_.size())) {
      return fields_[i];
    }
    return nullptr;
  }

  std::vector<FieldPtr> fields() const override { return fields_; }

  FieldPtr GetFieldByName(const std::string &name) const {
    auto it = name_to_index_.find(name);
    if (it != name_to_index_.end()) {
      return fields_[it->second];
    }
    return nullptr;
  }

  int GetFieldIndex(const std::string &name) const {
    auto it = name_to_index_.find(name);
    if (it != name_to_index_.end()) {
      return it->second;
    }
    return -1;
  }

  bool Equals(const DataType &other) const override {
    if (!DataType::Equals(other)) {
      return false;
    }
    const auto &other_struct = static_cast<const StructType &>(other);
    if (fields_.size() != other_struct.fields_.size()) {
      return false;
    }
    for (size_t i = 0; i < fields_.size(); ++i) {
      if (!fields_[i]->Equals(other_struct.fields_[i])) {
        return false;
      }
    }
    return true;
  }

private:
  std::vector<FieldPtr> fields_;
  std::unordered_map<std::string, int> name_to_index_;
};

/// Map type: a collection of key-value pairs.
class MapType : public DataType {
public:
  MapType(DataTypePtr key_type, DataTypePtr item_type, bool keys_sorted = false)
      : DataType(TypeId::MAP),
        key_field_(std::make_shared<Field>("key", std::move(key_type), false)),
        item_field_(
            std::make_shared<Field>("value", std::move(item_type), true)),
        keys_sorted_(keys_sorted) {}

  std::string name() const override { return "map"; }

  std::string ToString() const override {
    return "map<" + key_field_->type()->ToString() + ", " +
           item_field_->type()->ToString() + ">";
  }

  const DataTypePtr &key_type() const { return key_field_->type(); }

  const DataTypePtr &item_type() const { return item_field_->type(); }

  const FieldPtr &key_field() const { return key_field_; }

  const FieldPtr &item_field() const { return item_field_; }

  bool keys_sorted() const { return keys_sorted_; }

  int num_fields() const override { return 2; }

  FieldPtr field(int i) const override {
    if (i == 0) {
      return key_field_;
    }
    if (i == 1) {
      return item_field_;
    }
    return nullptr;
  }

  std::vector<FieldPtr> fields() const override {
    return {key_field_, item_field_};
  }

  bool Equals(const DataType &other) const override {
    if (!DataType::Equals(other)) {
      return false;
    }
    const auto &other_map = static_cast<const MapType &>(other);
    return key_field_->type()->Equals(other_map.key_field_->type()) &&
           item_field_->type()->Equals(other_map.item_field_->type());
  }

private:
  FieldPtr key_field_;
  FieldPtr item_field_;
  bool keys_sorted_;
};

/// Schema: a collection of named fields defining a row structure.
class Schema {
public:
  explicit Schema(std::vector<FieldPtr> fields,
                  std::unordered_map<std::string, std::string> metadata = {})
      : fields_(std::move(fields)), metadata_(std::move(metadata)) {
    for (size_t i = 0; i < fields_.size(); ++i) {
      name_to_index_[fields_[i]->name()] = static_cast<int>(i);
    }
  }

  int num_fields() const { return static_cast<int>(fields_.size()); }

  FieldPtr field(int i) const {
    if (i >= 0 && i < static_cast<int>(fields_.size())) {
      return fields_[i];
    }
    return nullptr;
  }

  const std::vector<FieldPtr> &fields() const { return fields_; }

  std::vector<std::string> field_names() const {
    std::vector<std::string> names;
    names.reserve(fields_.size());
    for (const auto &f : fields_) {
      names.push_back(f->name());
    }
    return names;
  }

  FieldPtr GetFieldByName(const std::string &name) const {
    auto it = name_to_index_.find(name);
    if (it != name_to_index_.end()) {
      return fields_[it->second];
    }
    return nullptr;
  }

  int GetFieldIndex(const std::string &name) const {
    auto it = name_to_index_.find(name);
    if (it != name_to_index_.end()) {
      return it->second;
    }
    return -1;
  }

  const std::unordered_map<std::string, std::string> &metadata() const {
    return metadata_;
  }

  std::string ToString() const {
    std::string result;
    for (size_t i = 0; i < fields_.size(); ++i) {
      if (i > 0) {
        result += "\n";
      }
      result += fields_[i]->ToString();
    }
    return result;
  }

  bool Equals(const Schema &other) const {
    if (fields_.size() != other.fields_.size()) {
      return false;
    }
    for (size_t i = 0; i < fields_.size(); ++i) {
      if (!fields_[i]->Equals(other.fields_[i])) {
        return false;
      }
    }
    return true;
  }

  bool Equals(const SchemaPtr &other) const {
    if (!other) {
      return false;
    }
    return Equals(*other);
  }

  /// Serialize this schema to a byte vector.
  std::vector<uint8_t> ToBytes() const;

  /// Serialize this schema to a Buffer.
  void ToBytes(Buffer &buffer) const;

  /// Deserialize a schema from a byte vector.
  static SchemaPtr FromBytes(const std::vector<uint8_t> &bytes);

  /// Deserialize a schema from a Buffer.
  static SchemaPtr FromBytes(Buffer &buffer);

private:
  std::vector<FieldPtr> fields_;
  std::unordered_map<std::string, int> name_to_index_;
  std::unordered_map<std::string, std::string> metadata_;
};

// ============================================================================
// Factory functions for creating types
// ============================================================================

inline DataTypePtr boolean() { return std::make_shared<BooleanType>(); }
inline DataTypePtr int8() { return std::make_shared<Int8Type>(); }
inline DataTypePtr int16() { return std::make_shared<Int16Type>(); }
inline DataTypePtr int32() { return std::make_shared<Int32Type>(); }
inline DataTypePtr int64() { return std::make_shared<Int64Type>(); }
inline DataTypePtr float16() { return std::make_shared<Float16Type>(); }
inline DataTypePtr float32() { return std::make_shared<Float32Type>(); }
inline DataTypePtr float64() { return std::make_shared<Float64Type>(); }
inline DataTypePtr utf8() { return std::make_shared<StringType>(); }
inline DataTypePtr binary() { return std::make_shared<BinaryType>(); }
inline DataTypePtr duration() { return std::make_shared<DurationType>(); }
inline DataTypePtr timestamp() { return std::make_shared<TimestampType>(); }
inline DataTypePtr date32() { return std::make_shared<LocalDateType>(); }

inline DataTypePtr decimal(int precision = 38, int scale = 18) {
  return std::make_shared<DecimalType>(precision, scale);
}

inline ListTypePtr list(DataTypePtr value_type) {
  return std::make_shared<ListType>(std::move(value_type));
}

inline ListTypePtr list(FieldPtr value_field) {
  return std::make_shared<ListType>(std::move(value_field));
}

inline DataTypePtr struct_(std::vector<FieldPtr> fields) {
  return std::make_shared<StructType>(std::move(fields));
}

inline MapTypePtr map(DataTypePtr key_type, DataTypePtr item_type,
                      bool keys_sorted = false) {
  return std::make_shared<MapType>(std::move(key_type), std::move(item_type),
                                   keys_sorted);
}

inline FieldPtr field(std::string name, DataTypePtr type,
                      bool nullable = true) {
  return std::make_shared<Field>(std::move(name), std::move(type), nullable);
}

inline FieldPtr field(std::string name, DataTypePtr type, bool nullable,
                      std::unordered_map<std::string, std::string> metadata) {
  return std::make_shared<Field>(std::move(name), std::move(type), nullable,
                                 std::move(metadata));
}

inline SchemaPtr schema(std::vector<FieldPtr> fields) {
  return std::make_shared<Schema>(std::move(fields));
}

inline SchemaPtr schema(std::vector<FieldPtr> fields,
                        std::unordered_map<std::string, std::string> metadata) {
  return std::make_shared<Schema>(std::move(fields), std::move(metadata));
}

/// Returns the byte width of a fixed-width type, or -1 for variable-width
/// types.
inline int64_t get_byte_width(const DataTypePtr &dtype) {
  int bit_width = dtype->bit_width();
  if (bit_width > 0) {
    return bit_width / CHAR_BIT;
  }
  return -1;
}

} // namespace row
} // namespace fory
