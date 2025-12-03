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

#include "fory/util/error.h"
#include "fory/util/logging.h"
#include "fory/util/macros.h"
#include <type_traits>
#include <utility>

namespace fory {

/// Helper type to disambiguate error construction in Result<T, E>
///
/// This is similar to std::unexpected in C++23.
/// Use this to explicitly construct a Result with an error value.
///
/// Example:
/// ```cpp
/// Result<int, Error> compute() {
///     if (error_condition) {
///         return Unexpected(Error::invalid("computation failed"));
///     }
///     return 42;
/// }
/// ```
template <typename E> class Unexpected {
public:
  explicit Unexpected(const E &e) : error_(e) {}
  explicit Unexpected(E &&e) : error_(std::move(e)) {}

  const E &error() const & { return error_; }
  E &error() & { return error_; }
  E &&error() && { return std::move(error_); }
  const E &&error() const && { return std::move(error_); }

private:
  E error_;
};

/// Result<T, E> - A type that represents either success (T) or failure (E)
///
/// This is a zero-cost abstraction similar to C++23 std::expected<T, E>.
/// It stores either a value of type T (on success) or an error of type E (on
/// failure), using a union for efficient storage with no heap allocation.
///
/// The implementation uses manual lifetime management via placement new and
/// explicit destructor calls to ensure zero-cost abstraction while supporting
/// non-trivial types.
///
/// ## Usage Examples
///
/// ```cpp
/// // Function returning Result
/// Result<int, Error> divide(int a, int b) {
///     if (b == 0) {
///         return Unexpected(Error::invalid("division by zero"));
///     }
///     return a / b;
/// }
///
/// // Using the result
/// auto result = divide(10, 2);
/// if (result.has_value()) {
///     std::cout << "Result: " << result.value() << std::endl;
/// } else {
///     std::cout << "Error: " << result.error().message() << std::endl;
/// }
///
/// // Alternative: using ok()
/// if (result.ok()) {
///     int val = *result;  // operator* for value access
/// }
/// ```
template <typename T, typename E> class Result {
private:
  // Union for zero-cost storage - only one is active at a time
  union Storage {
    T value_;
    E error_;

    // Empty constructors/destructors - we manage lifetime manually
    Storage() {}
    ~Storage() {}
  };

  Storage storage_;
  bool has_value_;

  // Helper to destroy the active member
  void destroy() {
    if (has_value_) {
      storage_.value_.~T();
    } else {
      storage_.error_.~E();
    }
  }

public:
  // Type traits
  using value_type = T;
  using error_type = E;

  // Construct with value (success case)
  Result(const T &value) : has_value_(true) { new (&storage_.value_) T(value); }

  Result(T &&value) : has_value_(true) {
    new (&storage_.value_) T(std::move(value));
  }

  // Construct with error (failure case) via Unexpected
  Result(const Unexpected<E> &unexpected) : has_value_(false) {
    new (&storage_.error_) E(unexpected.error());
  }

  Result(Unexpected<E> &&unexpected) : has_value_(false) {
    new (&storage_.error_) E(std::move(unexpected.error()));
  }

  // Destructor
  ~Result() { destroy(); }

  // Copy constructor
  Result(const Result &other) : has_value_(other.has_value_) {
    if (has_value_) {
      new (&storage_.value_) T(other.storage_.value_);
    } else {
      new (&storage_.error_) E(other.storage_.error_);
    }
  }

  // Move constructor
  Result(Result &&other) noexcept(
      std::is_nothrow_move_constructible<T>::value &&
      std::is_nothrow_move_constructible<E>::value)
      : has_value_(other.has_value_) {
    if (has_value_) {
      new (&storage_.value_) T(std::move(other.storage_.value_));
    } else {
      new (&storage_.error_) E(std::move(other.storage_.error_));
    }
  }

  // Copy assignment
  Result &operator=(const Result &other) {
    if (this != &other) {
      if (has_value_ == other.has_value_) {
        // Same state - just assign
        if (has_value_) {
          storage_.value_ = other.storage_.value_;
        } else {
          storage_.error_ = other.storage_.error_;
        }
      } else {
        // Different state - destroy and reconstruct
        destroy();
        has_value_ = other.has_value_;
        if (has_value_) {
          new (&storage_.value_) T(other.storage_.value_);
        } else {
          new (&storage_.error_) E(other.storage_.error_);
        }
      }
    }
    return *this;
  }

  // Move assignment
  Result &operator=(Result &&other) noexcept(
      std::is_nothrow_move_constructible<T>::value &&
      std::is_nothrow_move_constructible<E>::value &&
      std::is_nothrow_move_assignable<T>::value &&
      std::is_nothrow_move_assignable<E>::value) {
    if (this != &other) {
      if (has_value_ == other.has_value_) {
        // Same state - just move assign
        if (has_value_) {
          storage_.value_ = std::move(other.storage_.value_);
        } else {
          storage_.error_ = std::move(other.storage_.error_);
        }
      } else {
        // Different state - destroy and reconstruct
        destroy();
        has_value_ = other.has_value_;
        if (has_value_) {
          new (&storage_.value_) T(std::move(other.storage_.value_));
        } else {
          new (&storage_.error_) E(std::move(other.storage_.error_));
        }
      }
    }
    return *this;
  }

  // Observers

  /// Returns true if the Result contains a value (success)
  constexpr bool has_value() const noexcept { return has_value_; }

  /// Returns true if the Result contains a value (success)
  /// This is an alias for has_value()
  constexpr bool ok() const noexcept { return has_value_; }

  /// Returns true if the Result contains a value (success)
  constexpr explicit operator bool() const noexcept { return has_value_; }

  // Value accessors (must check has_value() first!)

  /// Returns a reference to the contained value
  /// Undefined behavior if !has_value()
  T &value() & {
    FORY_CHECK(has_value_) << "Cannot access value of error Result";
    return storage_.value_;
  }

  const T &value() const & {
    FORY_CHECK(has_value_) << "Cannot access value of error Result";
    return storage_.value_;
  }

  T &&value() && {
    FORY_CHECK(has_value_) << "Cannot access value of error Result";
    return std::move(storage_.value_);
  }

  const T &&value() const && {
    FORY_CHECK(has_value_) << "Cannot access value of error Result";
    return std::move(storage_.value_);
  }

  /// Returns the contained value or a default value
  template <typename U> T value_or(U &&default_value) const & {
    return has_value_ ? storage_.value_
                      : static_cast<T>(std::forward<U>(default_value));
  }

  template <typename U> T value_or(U &&default_value) && {
    return has_value_ ? std::move(storage_.value_)
                      : static_cast<T>(std::forward<U>(default_value));
  }

  // Error accessors (must check !has_value() first!)

  /// Returns a reference to the contained error
  /// Undefined behavior if has_value()
  E &error() & {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return storage_.error_;
  }

  const E &error() const & {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return storage_.error_;
  }

  E &&error() && {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return std::move(storage_.error_);
  }

  const E &&error() const && {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return std::move(storage_.error_);
  }

  // Convenience operators

  /// Dereference operator for value access
  T &operator*() & { return value(); }
  const T &operator*() const & { return value(); }
  T &&operator*() && { return std::move(*this).value(); }
  const T &&operator*() const && { return std::move(*this).value(); }

  /// Arrow operator for member access
  T *operator->() {
    FORY_CHECK(has_value_) << "Cannot access value of error Result";
    return &storage_.value_;
  }

  const T *operator->() const {
    FORY_CHECK(has_value_) << "Cannot access value of error Result";
    return &storage_.value_;
  }
};

/// Result<void, E> - Specialization for operations that don't return a value
///
/// This specialization is for operations that either succeed (with no return
/// value) or fail with an error. Uses union-based storage to avoid heap
/// allocation for the common success case.
///
/// ## Usage Example
///
/// ```cpp
/// Result<void, Error> write_file(const std::string& path) {
///     if (!can_write(path)) {
///         return Unexpected(Error::io_error("cannot write to file"));
///     }
///     // ... perform write ...
///     return Result<void, Error>();  // Success
/// }
/// ```
template <typename E> class Result<void, E> {
private:
  // Union-based storage avoids heap allocation
  union Storage {
    char dummy_; // Placeholder for success state (1 byte)
    E error_;

    Storage() : dummy_(0) {}
    ~Storage() {}
  };

  Storage storage_;
  bool has_value_;

  void destroy() {
    if (!has_value_) {
      storage_.error_.~E();
    }
  }

public:
  // Type traits
  using error_type = E;

  // Construct success result
  Result() : has_value_(true) { storage_.dummy_ = 0; }

  // Construct error result
  Result(const Unexpected<E> &unexpected) : has_value_(false) {
    new (&storage_.error_) E(unexpected.error());
  }

  Result(Unexpected<E> &&unexpected) : has_value_(false) {
    new (&storage_.error_) E(std::move(unexpected.error()));
  }

  // Destructor
  ~Result() { destroy(); }

  // Copy constructor
  Result(const Result &other) : has_value_(other.has_value_) {
    if (!has_value_) {
      new (&storage_.error_) E(other.storage_.error_);
    } else {
      storage_.dummy_ = 0;
    }
  }

  // Move constructor
  Result(Result &&other) noexcept(std::is_nothrow_move_constructible<E>::value)
      : has_value_(other.has_value_) {
    if (!has_value_) {
      new (&storage_.error_) E(std::move(other.storage_.error_));
    } else {
      storage_.dummy_ = 0;
    }
  }

  // Copy assignment
  Result &operator=(const Result &other) {
    if (this != &other) {
      if (has_value_ == other.has_value_) {
        if (!has_value_) {
          storage_.error_ = other.storage_.error_;
        }
      } else {
        destroy();
        has_value_ = other.has_value_;
        if (!has_value_) {
          new (&storage_.error_) E(other.storage_.error_);
        } else {
          storage_.dummy_ = 0;
        }
      }
    }
    return *this;
  }

  // Move assignment
  Result &operator=(Result &&other) noexcept(
      std::is_nothrow_move_constructible<E>::value &&
      std::is_nothrow_move_assignable<E>::value) {
    if (this != &other) {
      if (has_value_ == other.has_value_) {
        if (!has_value_) {
          storage_.error_ = std::move(other.storage_.error_);
        }
      } else {
        destroy();
        has_value_ = other.has_value_;
        if (!has_value_) {
          new (&storage_.error_) E(std::move(other.storage_.error_));
        } else {
          storage_.dummy_ = 0;
        }
      }
    }
    return *this;
  }

  // Observers

  /// Returns true if the Result represents success
  constexpr bool has_value() const noexcept { return has_value_; }

  /// Returns true if the Result represents success
  constexpr bool ok() const noexcept { return has_value_; }

  /// Returns true if the Result represents success
  constexpr explicit operator bool() const noexcept { return has_value_; }

  // Error accessors

  /// Returns a reference to the contained error
  E &error() & {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return storage_.error_;
  }

  const E &error() const & {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return storage_.error_;
  }

  E &&error() && {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return std::move(storage_.error_);
  }

  const E &&error() const && {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return std::move(storage_.error_);
  }
};

/// Result<T&, E> - Specialization for reference types
///
/// This specialization allows Result to hold references instead of values.
/// Internally stores a pointer, but exposes a reference interface.
///
/// IMPORTANT: The referenced object must outlive this Result.
/// This is the same lifetime contract as raw C++ references.
///
/// Copy/move semantics re-seat the pointer (like std::reference_wrapper),
/// rather than copying the referenced object.
///
/// ## Usage Example
///
/// ```cpp
/// Result<int&, Error> find_element(std::vector<int>& vec, int target) {
///     for (auto& elem : vec) {
///         if (elem == target) return elem;
///     }
///     return Unexpected(Error::not_found("element not found"));
/// }
///
/// std::vector<int> data = {1, 2, 3};
/// auto result = find_element(data, 2);
/// if (result.ok()) {
///     result.value() = 42;  // Modifies data[1]
/// }
/// ```
template <typename T, typename E> class Result<T &, E> {
private:
  union Storage {
    T *value_ptr_;
    E error_;

    Storage() : value_ptr_(nullptr) {}
    ~Storage() {}
  };

  Storage storage_;
  bool has_value_;

  void destroy() {
    if (!has_value_) {
      storage_.error_.~E();
    }
  }

public:
  using value_type = T &;
  using error_type = E;

  // Construct from reference
  Result(T &value) : has_value_(true) { storage_.value_ptr_ = &value; }

  // Construct from error via Unexpected
  Result(const Unexpected<E> &unexpected) : has_value_(false) {
    new (&storage_.error_) E(unexpected.error());
  }

  Result(Unexpected<E> &&unexpected) : has_value_(false) {
    new (&storage_.error_) E(std::move(unexpected.error()));
  }

  ~Result() { destroy(); }

  // Copy constructor - copies pointer (shallow, like reference_wrapper)
  Result(const Result &other) : has_value_(other.has_value_) {
    if (has_value_) {
      storage_.value_ptr_ = other.storage_.value_ptr_;
    } else {
      new (&storage_.error_) E(other.storage_.error_);
    }
  }

  // Move constructor
  Result(Result &&other) noexcept(std::is_nothrow_move_constructible<E>::value)
      : has_value_(other.has_value_) {
    if (has_value_) {
      storage_.value_ptr_ = other.storage_.value_ptr_;
    } else {
      new (&storage_.error_) E(std::move(other.storage_.error_));
    }
  }

  // Copy assignment - re-seats pointer (like reference_wrapper)
  Result &operator=(const Result &other) {
    if (this != &other) {
      if (has_value_ == other.has_value_) {
        if (has_value_) {
          storage_.value_ptr_ = other.storage_.value_ptr_;
        } else {
          storage_.error_ = other.storage_.error_;
        }
      } else {
        destroy();
        has_value_ = other.has_value_;
        if (has_value_) {
          storage_.value_ptr_ = other.storage_.value_ptr_;
        } else {
          new (&storage_.error_) E(other.storage_.error_);
        }
      }
    }
    return *this;
  }

  // Move assignment
  Result &operator=(Result &&other) noexcept(
      std::is_nothrow_move_constructible<E>::value &&
      std::is_nothrow_move_assignable<E>::value) {
    if (this != &other) {
      if (has_value_ == other.has_value_) {
        if (has_value_) {
          storage_.value_ptr_ = other.storage_.value_ptr_;
        } else {
          storage_.error_ = std::move(other.storage_.error_);
        }
      } else {
        destroy();
        has_value_ = other.has_value_;
        if (has_value_) {
          storage_.value_ptr_ = other.storage_.value_ptr_;
        } else {
          new (&storage_.error_) E(std::move(other.storage_.error_));
        }
      }
    }
    return *this;
  }

  // Observers

  /// Returns true if the Result contains a reference (success)
  constexpr bool has_value() const noexcept { return has_value_; }

  /// Returns true if the Result contains a reference (success)
  constexpr bool ok() const noexcept { return has_value_; }

  /// Returns true if the Result contains a reference (success)
  constexpr explicit operator bool() const noexcept { return has_value_; }

  // Value accessors - return T&

  /// Returns the referenced value
  /// Undefined behavior if !has_value()
  T &value() {
    FORY_CHECK(has_value_) << "Cannot access value of error Result";
    return *storage_.value_ptr_;
  }

  T &value() const {
    FORY_CHECK(has_value_) << "Cannot access value of error Result";
    return *storage_.value_ptr_;
  }

  /// Returns the referenced value or a fallback reference
  /// Note: default_value must be an lvalue reference
  T &value_or(T &default_value) const {
    return has_value_ ? *storage_.value_ptr_ : default_value;
  }

  // Error accessors

  /// Returns a reference to the contained error
  /// Undefined behavior if has_value()
  E &error() & {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return storage_.error_;
  }

  const E &error() const & {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return storage_.error_;
  }

  E &&error() && {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return std::move(storage_.error_);
  }

  const E &&error() const && {
    FORY_CHECK(!has_value_) << "Cannot access error of successful Result";
    return std::move(storage_.error_);
  }

  // Convenience operators

  /// Dereference operator for value access
  T &operator*() const { return value(); }

  /// Arrow operator for member access
  T *operator->() const {
    FORY_CHECK(has_value_) << "Cannot access value of error Result";
    return storage_.value_ptr_;
  }
};

/// Result<T&&, E> - Explicitly disallowed
///
/// Rvalue references in Result don't make semantic sense.
/// If you have an rvalue, move it into a Result<T, E> instead.
template <typename T, typename E> class Result<T &&, E> {
  static_assert(sizeof(T) == 0,
                "Result<T&&, E> is not supported. Use Result<T, E> for values "
                "or Result<T&, E> for references.");
};

// Convenience macros

/// Return early if Result is an error
#define FORY_RETURN_NOT_OK(expr)                                               \
  do {                                                                         \
    auto _result = (expr);                                                     \
    if (FORY_PREDICT_FALSE(!_result.ok())) {                                   \
      return ::fory::Unexpected(std::move(_result).error());                   \
    }                                                                          \
  } while (0)

/// Return early if Result is an error (alias for FORY_RETURN_NOT_OK)
#define FORY_RETURN_IF_ERROR(expr) FORY_RETURN_NOT_OK(expr)

/// Return early if Result is an error, with additional cleanup
#define FORY_RETURN_NOT_OK_ELSE(expr, else_)                                   \
  do {                                                                         \
    auto _result = (expr);                                                     \
    if (!_result.ok()) {                                                       \
      else_;                                                                   \
      return ::fory::Unexpected(std::move(_result).error());                   \
    }                                                                          \
  } while (0)

/// Check that Result is OK, abort if not
#define FORY_CHECK_OK_PREPEND(expr, msg)                                       \
  do {                                                                         \
    auto _result = (expr);                                                     \
    FORY_CHECK(_result.ok()) << (msg) << ": " << _result.error().to_string();  \
  } while (0)

#define FORY_CHECK_OK(expr) FORY_CHECK_OK_PREPEND(expr, "Bad result")

/// Assign value from Result<T, E> or return error
#define FORY_ASSIGN_OR_RETURN(lhs, rexpr)                                      \
  do {                                                                         \
    auto _result = (rexpr);                                                    \
    if (FORY_PREDICT_FALSE(!_result.ok())) {                                   \
      return ::fory::Unexpected(std::move(_result).error());                   \
    }                                                                          \
    lhs = std::move(_result).value();                                          \
  } while (0)

/// Declare and assign value from Result<T, E> or return error
///
/// ⚠️  IMPORTANT: This macro expands to multiple statements.
/// Always use braces with control flow statements!
///
/// ✅ CORRECT:
/// ```cpp
/// if (condition) {
///     FORY_TRY(data, load_data());
/// }
/// ```
///
/// ❌ WRONG:
/// ```cpp
/// if (condition)
///     FORY_TRY(data, load_data());  // BREAKS!
/// ```
#define FORY_TRY(var, expr)                                                    \
  auto _result_##var = (expr);                                                 \
  if (FORY_PREDICT_FALSE(!_result_##var.ok())) {                               \
    return ::fory::Unexpected(std::move(_result_##var).error());               \
  }                                                                            \
  auto var = std::move(_result_##var).value()

// Output operators
template <typename T, typename E>
inline std::ostream &operator<<(std::ostream &os, const Result<T, E> &r) {
  if (r.ok()) {
    return os << "Ok(" << r.value() << ")";
  } else {
    return os << "Err(" << r.error() << ")";
  }
}

template <typename E>
inline std::ostream &operator<<(std::ostream &os, const Result<void, E> &r) {
  if (r.ok()) {
    return os << "Ok()";
  } else {
    return os << "Err(" << r.error() << ")";
  }
}

template <typename T, typename E>
inline std::ostream &operator<<(std::ostream &os, const Result<T &, E> &r) {
  if (r.ok()) {
    return os << "Ok(&" << r.value() << ")";
  } else {
    return os << "Err(" << r.error() << ")";
  }
}

} // namespace fory
