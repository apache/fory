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
#include <ostream>
#include <string>
#include <utility>

namespace fory {

/// Error codes for Fory operations.
enum class ErrorCode : char {
  OK = 0,
  OutOfMemory = 1,
  OutOfBound = 2,
  KeyError = 3,
  TypeError = 4,
  Invalid = 5,
  IOError = 6,
  UnknownError = 7,
  EncodeError = 8,
  InvalidData = 9,
  InvalidRef = 10,
  UnknownEnum = 11,
  EncodingError = 12,
  DepthExceed = 13,
  Unsupported = 14,
  NotAllowed = 15,
  StructVersionMismatch = 16,
  TypeMismatch = 17,
  BufferOutOfBound = 18,
};

/// Error class for Fory serialization and deserialization operations.
///
/// # IMPORTANT: Always Use Static Constructor Functions
///
/// **DO NOT** construct errors directly using the constructor.
/// **ALWAYS** use the provided static factory functions instead.
///
/// ## Why Use Static Functions?
///
/// The static factory functions provide:
/// - Consistent error creation across the codebase
/// - Better ergonomics and readability
/// - Future-proof API if error construction logic needs to change
/// - Clear semantic meaning for each error type
///
/// ## Examples
///
/// ```cpp
/// // ✅ CORRECT: Use static factory functions
/// auto err = Error::type_error("Expected string type");
/// auto err = Error::invalid_data("Invalid value: " + std::to_string(42));
/// auto err = Error::type_mismatch(1, 2);
///
/// // ❌ WRONG: Do not construct directly
/// // auto err = Error(ErrorCode::TypeError, "Expected string type");
/// ```
///
/// ## Available Constructor Functions
///
/// - Error::type_mismatch() - For type ID mismatches
/// - Error::buffer_out_of_bound() - For buffer boundary violations
/// - Error::encode_error() - For encoding failures
/// - Error::invalid_data() - For invalid or corrupted data
/// - Error::invalid_ref() - For invalid reference IDs
/// - Error::unknown_enum() - For unknown enum variants
/// - Error::type_error() - For general type errors
/// - Error::encoding_error() - For encoding format errors
/// - Error::depth_exceed() - For exceeding maximum nesting depth
/// - Error::unsupported() - For unsupported operations
/// - Error::not_allowed() - For disallowed operations
/// - Error::out_of_memory() - For memory allocation failures
/// - Error::out_of_bound() - For index out of bounds
/// - Error::key_error() - For key not found errors
/// - Error::io_error() - For I/O errors
/// - Error::invalid() - For general invalid state
/// - Error::unknown() - For generic errors
class Error {
public:
  // Static factory functions - Use these instead of constructors!

  /// Creates a type mismatch error with the given type IDs.
  static Error type_mismatch(uint32_t type_a, uint32_t type_b) {
    return Error(ErrorCode::TypeMismatch,
                 "Type mismatch: type_a = " + std::to_string(type_a) +
                     ", type_b = " + std::to_string(type_b));
  }

  /// Creates a buffer out of bound error.
  static Error buffer_out_of_bound(size_t offset, size_t length,
                                   size_t capacity) {
    return Error(ErrorCode::BufferOutOfBound,
                 "Buffer out of bound: " + std::to_string(offset) + " + " +
                     std::to_string(length) + " > " + std::to_string(capacity));
  }

  /// Creates an encoding error.
  static Error encode_error(const std::string &msg) {
    return Error(ErrorCode::EncodeError, msg);
  }

  /// Creates an invalid data error.
  static Error invalid_data(const std::string &msg) {
    return Error(ErrorCode::InvalidData, msg);
  }

  /// Creates an invalid reference error.
  static Error invalid_ref(const std::string &msg) {
    return Error(ErrorCode::InvalidRef, msg);
  }

  /// Creates an unknown enum error.
  static Error unknown_enum(const std::string &msg) {
    return Error(ErrorCode::UnknownEnum, msg);
  }

  /// Creates a type error.
  static Error type_error(const std::string &msg) {
    return Error(ErrorCode::TypeError, msg);
  }

  /// Creates an encoding format error.
  static Error encoding_error(const std::string &msg) {
    return Error(ErrorCode::EncodingError, msg);
  }

  /// Creates a depth exceeded error.
  static Error depth_exceed(const std::string &msg) {
    return Error(ErrorCode::DepthExceed, msg);
  }

  /// Creates an unsupported operation error.
  static Error unsupported(const std::string &msg) {
    return Error(ErrorCode::Unsupported, msg);
  }

  /// Creates a not allowed operation error.
  static Error not_allowed(const std::string &msg) {
    return Error(ErrorCode::NotAllowed, msg);
  }

  /// Creates a struct version mismatch error.
  static Error struct_version_mismatch(const std::string &msg) {
    return Error(ErrorCode::StructVersionMismatch, msg);
  }

  /// Creates an out of memory error.
  static Error out_of_memory(const std::string &msg) {
    return Error(ErrorCode::OutOfMemory, msg);
  }

  /// Creates an out of bound error.
  static Error out_of_bound(const std::string &msg) {
    return Error(ErrorCode::OutOfBound, msg);
  }

  /// Creates a key error.
  static Error key_error(const std::string &msg) {
    return Error(ErrorCode::KeyError, msg);
  }

  /// Creates an I/O error.
  static Error io_error(const std::string &msg) {
    return Error(ErrorCode::IOError, msg);
  }

  /// Creates a general invalid state error.
  static Error invalid(const std::string &msg) {
    return Error(ErrorCode::Invalid, msg);
  }

  /// Creates a generic unknown error.
  ///
  /// This is a convenient way to produce an error message
  /// from any string.
  static Error unknown(const std::string &msg) {
    return Error(ErrorCode::UnknownError, msg);
  }

  // Accessors
  ErrorCode code() const { return state_->code_; }
  const std::string &message() const { return state_->msg_; }

  /// Returns a string representation of this error.
  std::string to_string() const;

  /// Returns the error code as a string.
  std::string code_as_string() const;

  /// Converts a string to an ErrorCode.
  static ErrorCode string_to_code(const std::string &str);

  // Copy and move semantics
  Error(const Error &other) : state_(new ErrorState(*other.state_)) {}
  Error(Error &&) noexcept = default;
  Error &operator=(const Error &other) {
    if (this != &other) {
      state_.reset(new ErrorState(*other.state_));
    }
    return *this;
  }
  Error &operator=(Error &&) noexcept = default;

  ~Error() = default;

private:
  // Private error state to optimize stack copies
  // Using unique_ptr makes Result<T, Error> cheaper to copy/move
  struct ErrorState {
    ErrorCode code_;
    std::string msg_;

    ErrorState(ErrorCode code, std::string msg)
        : code_(code), msg_(std::move(msg)) {}
  };

  // Private constructor - use static factory functions instead!
  Error(ErrorCode code, std::string msg)
      : state_(new ErrorState(code, std::move(msg))) {}

  std::unique_ptr<ErrorState> state_;
};

inline std::ostream &operator<<(std::ostream &os, const Error &e) {
  return os << e.to_string();
}

} // namespace fory
