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

#include "fory/row/schema.h"
#include "fory/meta/meta_string.h"
#include "fory/type/type.h"
#include <algorithm>
#include <stdexcept>

namespace fory {
namespace row {

using namespace meta;

namespace {

constexpr uint8_t SCHEMA_VERSION = 1;
constexpr int FIELD_NAME_SIZE_THRESHOLD = 15;

// Field name encodings used for schema serialization
static const std::vector<MetaEncoding> FIELD_NAME_ENCODINGS = {
    MetaEncoding::UTF8, MetaEncoding::ALL_TO_LOWER_SPECIAL,
    MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL};

// Encoder and decoder for field names
static MetaStringEncoder FIELD_NAME_ENCODER('$', '_');
static MetaStringDecoder FIELD_NAME_DECODER('$', '_');

Result<void, Error> WriteField(Buffer &buffer, const Field &field);
Result<FieldPtr, Error> ReadField(Buffer &buffer);
Result<void, Error> WriteType(Buffer &buffer, const DataType &type);
Result<DataTypePtr, Error> ReadType(Buffer &buffer);

Result<void, Error> WriteField(Buffer &buffer, const Field &field) {
  auto encode_result =
      FIELD_NAME_ENCODER.encode(field.name(), FIELD_NAME_ENCODINGS);
  if (!encode_result.ok()) {
    return Unexpected(encode_result.error());
  }
  EncodedMetaString encoded = std::move(encode_result.value());

  // Find encoding index
  int encoding_index = 0;
  for (size_t i = 0; i < FIELD_NAME_ENCODINGS.size(); ++i) {
    if (FIELD_NAME_ENCODINGS[i] == encoded.encoding) {
      encoding_index = static_cast<int>(i);
      break;
    }
  }

  size_t name_size = encoded.bytes.size();

  // Build header byte
  int header = encoding_index & 0x03; // bits 0-1: encoding
  bool big_size = name_size > static_cast<size_t>(FIELD_NAME_SIZE_THRESHOLD);
  if (big_size) {
    header |= (FIELD_NAME_SIZE_THRESHOLD << 2); // bits 2-5: max value
  } else {
    header |=
        ((static_cast<int>(name_size) - 1) << 2); // bits 2-5: name size - 1
  }
  if (field.nullable()) {
    header |= 0x40; // bit 6: nullable
  }
  buffer.WriteUint8(static_cast<uint8_t>(header));

  if (big_size) {
    buffer.WriteVarUint32(
        static_cast<uint32_t>(name_size - FIELD_NAME_SIZE_THRESHOLD));
  }
  buffer.WriteBytes(encoded.bytes.data(),
                    static_cast<uint32_t>(encoded.bytes.size()));

  return WriteType(buffer, *field.type());
}

Result<FieldPtr, Error> ReadField(Buffer &buffer) {
  Error error;
  uint8_t header_byte = buffer.ReadUint8(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }
  int header = header_byte;
  int encoding_index = header & 0x03;
  int name_size_minus1 = (header >> 2) & 0x0F;
  bool nullable = (header & 0x40) != 0;

  size_t name_size;
  if (name_size_minus1 == FIELD_NAME_SIZE_THRESHOLD) {
    uint32_t extra = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    name_size = extra + FIELD_NAME_SIZE_THRESHOLD;
  } else {
    name_size = name_size_minus1 + 1;
  }

  std::vector<uint8_t> name_bytes(name_size);
  buffer.ReadBytes(name_bytes.data(), static_cast<uint32_t>(name_size), error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }

  MetaEncoding encoding = FIELD_NAME_ENCODINGS[encoding_index];
  auto decode_result =
      FIELD_NAME_DECODER.decode(name_bytes.data(), name_size, encoding);
  if (!decode_result.ok()) {
    return Unexpected(decode_result.error());
  }
  std::string name = std::move(decode_result.value());

  FORY_TRY(type, ReadType(buffer));
  return std::make_shared<Field>(std::move(name), std::move(type), nullable);
}

Result<void, Error> WriteType(Buffer &buffer, const DataType &type) {
  buffer.WriteUint8(static_cast<uint8_t>(type.id()));

  if (auto *decimal_type = dynamic_cast<const DecimalType *>(&type)) {
    buffer.WriteUint8(static_cast<uint8_t>(decimal_type->precision()));
    buffer.WriteUint8(static_cast<uint8_t>(decimal_type->scale()));
  } else if (auto *list_type = dynamic_cast<const ListType *>(&type)) {
    FORY_RETURN_NOT_OK(WriteField(buffer, *list_type->value_field()));
  } else if (auto *map_type = dynamic_cast<const MapType *>(&type)) {
    FORY_RETURN_NOT_OK(WriteField(buffer, *map_type->key_field()));
    FORY_RETURN_NOT_OK(WriteField(buffer, *map_type->item_field()));
  } else if (auto *struct_type = dynamic_cast<const StructType *>(&type)) {
    buffer.WriteVarUint32(struct_type->num_fields());
    for (const auto &field : struct_type->fields()) {
      FORY_RETURN_NOT_OK(WriteField(buffer, *field));
    }
  }

  return Result<void, Error>();
}

Result<DataTypePtr, Error> ReadType(Buffer &buffer) {
  Error error;
  uint8_t type_id_byte = buffer.ReadUint8(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }
  TypeId type_id = static_cast<TypeId>(type_id_byte);

  switch (type_id) {
  case TypeId::BOOL:
    return boolean();
  case TypeId::INT8:
    return int8();
  case TypeId::INT16:
    return int16();
  case TypeId::INT32:
    return int32();
  case TypeId::INT64:
    return int64();
  case TypeId::FLOAT16:
    return float16();
  case TypeId::FLOAT32:
    return float32();
  case TypeId::FLOAT64:
    return float64();
  case TypeId::STRING:
    return utf8();
  case TypeId::BINARY:
    return binary();
  case TypeId::DURATION:
    return duration();
  case TypeId::TIMESTAMP:
    return timestamp();
  case TypeId::DATE:
    return date32();
  case TypeId::DECIMAL: {
    uint8_t precision = buffer.ReadUint8(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    uint8_t scale = buffer.ReadUint8(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    return decimal(precision, scale);
  }
  case TypeId::LIST: {
    FORY_TRY(value_field, ReadField(buffer));
    return std::static_pointer_cast<DataType>(
        std::make_shared<ListType>(std::move(value_field)));
  }
  case TypeId::MAP: {
    FORY_TRY(key_field, ReadField(buffer));
    FORY_TRY(item_field, ReadField(buffer));
    return std::static_pointer_cast<DataType>(
        std::make_shared<MapType>(key_field->type(), item_field->type()));
  }
  case TypeId::STRUCT: {
    uint32_t struct_num_fields = buffer.ReadVarUint32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    std::vector<FieldPtr> fields;
    fields.reserve(struct_num_fields);
    for (uint32_t i = 0; i < struct_num_fields; ++i) {
      FORY_TRY(struct_field, ReadField(buffer));
      fields.push_back(std::move(struct_field));
    }
    return std::static_pointer_cast<DataType>(
        std::make_shared<StructType>(std::move(fields)));
  }
  default:
    return Unexpected(Error::invalid_data("Unknown type id: " +
                                          std::to_string(type_id_byte)));
  }
}

} // anonymous namespace

std::vector<uint8_t> Schema::ToBytes() const {
  Buffer buffer;
  ToBytes(buffer);
  std::vector<uint8_t> result(buffer.writer_index());
  buffer.Copy(0, buffer.writer_index(), result.data());
  return result;
}

void Schema::ToBytes(Buffer &buffer) const {
  buffer.WriteUint8(SCHEMA_VERSION);
  buffer.WriteVarUint32(num_fields());
  for (const auto &f : fields_) {
    auto result = WriteField(buffer, *f);
    if (!result.ok()) {
      throw std::runtime_error(result.error().message());
    }
  }
}

SchemaPtr Schema::FromBytes(const std::vector<uint8_t> &bytes) {
  Buffer buffer(const_cast<uint8_t *>(bytes.data()),
                static_cast<uint32_t>(bytes.size()), false);
  return FromBytes(buffer);
}

SchemaPtr Schema::FromBytes(Buffer &buffer) {
  Error error;
  uint8_t version = buffer.ReadUint8(error);
  if (!error.ok()) {
    throw std::runtime_error(error.message());
  }
  if (version != SCHEMA_VERSION) {
    throw std::runtime_error(
        "Unsupported schema version: " + std::to_string(version) +
        ", expected: " + std::to_string(SCHEMA_VERSION));
  }

  uint32_t num_fields = buffer.ReadVarUint32(error);
  if (!error.ok()) {
    throw std::runtime_error(error.message());
  }

  std::vector<FieldPtr> fields;
  fields.reserve(num_fields);
  for (uint32_t i = 0; i < num_fields; ++i) {
    auto field_result = ReadField(buffer);
    if (!field_result.ok()) {
      throw std::runtime_error(field_result.error().message());
    }
    fields.push_back(std::move(field_result.value()));
  }

  return std::make_shared<Schema>(std::move(fields));
}

} // namespace row
} // namespace fory
