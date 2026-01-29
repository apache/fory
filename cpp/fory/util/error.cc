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

#include "fory/util/error.h"
#include <assert.h>
#include <string>
#include <unordered_map>

namespace std {
template <> struct hash<fory::ErrorCode> {
  size_t operator()(const fory::ErrorCode &t) const { return size_t(t); }
};
} // namespace std

namespace fory {

#define ERROR_CODE_OK "OK"
#define ERROR_CODE_OUT_OF_MEMORY "Out of memory"
#define ERROR_CODE_OUT_OF_BOUND "Out of bound"
#define ERROR_CODE_KEY_ERROR "Key error"
#define ERROR_CODE_TYPE_ERROR "Type error"
#define ERROR_CODE_INVALID "Invalid"
#define ERROR_CODE_IO_ERROR "IOError"
#define ERROR_CODE_UNKNOWN_ERROR "Unknown error"
#define ERROR_CODE_ENCODE_ERROR "encode error"
#define ERROR_CODE_INVALID_DATA "Invalid data"
#define ERROR_CODE_INVALID_REF "Invalid ref"
#define ERROR_CODE_UNKNOWN_ENUM "Unknown enum"
#define ERROR_CODE_ENCODING_ERROR "Encoding error"
#define ERROR_CODE_DEPTH_EXCEED "Depth exceed"
#define ERROR_CODE_UNSUPPORTED "Unsupported"
#define ERROR_CODE_NOT_ALLOWED "Not allowed"
#define ERROR_CODE_STRUCT_VERSION_MISMATCH "Struct version mismatch"
#define ERROR_CODE_TYPE_MISMATCH "Type mismatch"
#define ERROR_CODE_BUFFER_OUT_OF_BOUND "Buffer out of bound"

std::string Error::to_string() const {
  std::string result = code_as_string();
  if (!state_->msg_.empty()) {
    result += ": ";
    result += state_->msg_;
  }
  return result;
}

std::string Error::code_as_string() const {
  static std::unordered_map<ErrorCode, std::string> code_to_str = {
      {ErrorCode::OK, ERROR_CODE_OK},
      {ErrorCode::OutOfMemory, ERROR_CODE_OUT_OF_MEMORY},
      {ErrorCode::OutOfBound, ERROR_CODE_OUT_OF_BOUND},
      {ErrorCode::KeyError, ERROR_CODE_KEY_ERROR},
      {ErrorCode::TypeError, ERROR_CODE_TYPE_ERROR},
      {ErrorCode::Invalid, ERROR_CODE_INVALID},
      {ErrorCode::IOError, ERROR_CODE_IO_ERROR},
      {ErrorCode::UnknownError, ERROR_CODE_UNKNOWN_ERROR},
      {ErrorCode::EncodeError, ERROR_CODE_ENCODE_ERROR},
      {ErrorCode::InvalidData, ERROR_CODE_INVALID_DATA},
      {ErrorCode::InvalidRef, ERROR_CODE_INVALID_REF},
      {ErrorCode::UnknownEnum, ERROR_CODE_UNKNOWN_ENUM},
      {ErrorCode::EncodingError, ERROR_CODE_ENCODING_ERROR},
      {ErrorCode::DepthExceed, ERROR_CODE_DEPTH_EXCEED},
      {ErrorCode::Unsupported, ERROR_CODE_UNSUPPORTED},
      {ErrorCode::NotAllowed, ERROR_CODE_NOT_ALLOWED},
      {ErrorCode::StructVersionMismatch, ERROR_CODE_STRUCT_VERSION_MISMATCH},
      {ErrorCode::TypeMismatch, ERROR_CODE_TYPE_MISMATCH},
      {ErrorCode::BufferOutOfBound, ERROR_CODE_BUFFER_OUT_OF_BOUND},
  };

  auto it = code_to_str.find(state_->code_);
  if (it == code_to_str.end()) {
    return ERROR_CODE_UNKNOWN_ERROR;
  }
  return it->second;
}

ErrorCode Error::string_to_code(const std::string &str) {
  static std::unordered_map<std::string, ErrorCode> str_to_code = {
      {ERROR_CODE_OK, ErrorCode::OK},
      {ERROR_CODE_OUT_OF_MEMORY, ErrorCode::OutOfMemory},
      {ERROR_CODE_OUT_OF_BOUND, ErrorCode::OutOfBound},
      {ERROR_CODE_KEY_ERROR, ErrorCode::KeyError},
      {ERROR_CODE_TYPE_ERROR, ErrorCode::TypeError},
      {ERROR_CODE_INVALID, ErrorCode::Invalid},
      {ERROR_CODE_IO_ERROR, ErrorCode::IOError},
      {ERROR_CODE_UNKNOWN_ERROR, ErrorCode::UnknownError},
      {ERROR_CODE_ENCODE_ERROR, ErrorCode::EncodeError},
      {ERROR_CODE_INVALID_DATA, ErrorCode::InvalidData},
      {ERROR_CODE_INVALID_REF, ErrorCode::InvalidRef},
      {ERROR_CODE_UNKNOWN_ENUM, ErrorCode::UnknownEnum},
      {ERROR_CODE_ENCODING_ERROR, ErrorCode::EncodingError},
      {ERROR_CODE_DEPTH_EXCEED, ErrorCode::DepthExceed},
      {ERROR_CODE_UNSUPPORTED, ErrorCode::Unsupported},
      {ERROR_CODE_NOT_ALLOWED, ErrorCode::NotAllowed},
      {ERROR_CODE_STRUCT_VERSION_MISMATCH, ErrorCode::StructVersionMismatch},
      {ERROR_CODE_TYPE_MISMATCH, ErrorCode::TypeMismatch},
      {ERROR_CODE_BUFFER_OUT_OF_BOUND, ErrorCode::BufferOutOfBound},
  };

  auto it = str_to_code.find(str);
  if (it == str_to_code.end()) {
    return ErrorCode::UnknownError;
  }
  return it->second;
}

} // namespace fory
