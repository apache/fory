// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

use std::borrow::Cow;
use std::sync::OnceLock;

use thiserror::Error;

/// Global flag to check if FORY_PANIC_ON_ERROR environment variable is set.
static PANIC_ON_ERROR: OnceLock<bool> = OnceLock::new();

/// Check if FORY_PANIC_ON_ERROR environment variable is set.
#[inline]
pub fn should_panic_on_error() -> bool {
    *PANIC_ON_ERROR.get_or_init(|| {
        std::env::var("FORY_PANIC_ON_ERROR")
            .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
            .unwrap_or(false)
    })
}

/// Error type for Fory serialization and deserialization operations.
///
/// # IMPORTANT: Always Use Static Constructor Functions
///
/// **DO NOT** construct error variants directly using the enum syntax.
/// **ALWAYS** use the provided static constructor functions instead.
///
/// ## Why Use Static Functions?
///
/// The static constructor functions provide:
/// - Automatic type conversion via `Into<Cow<'static, str>>`
/// - Consistent error creation across the codebase
/// - Better ergonomics (no need for manual `.into()` calls)
/// - Future-proof API if error construction logic needs to change
///
/// ## Examples
///
/// ```rust
/// use fory_core::error::Error;
///
/// // ✅ CORRECT: Use static functions
/// let err = Error::type_error("Expected string type");
/// let err = Error::invalid_data(format!("Invalid value: {}", 42));
/// let err = Error::type_mismatch(1, 2);
///
/// // ❌ WRONG: Do not construct directly
/// // let err = Error::TypeError("Expected string type".into());
/// // let err = Error::InvalidData(format!("Invalid value: {}", 42).into());
/// ```
///
/// ## Available Constructor Functions
///
/// - [`Error::type_mismatch`] - For type ID mismatches
/// - [`Error::buffer_out_of_bound`] - For buffer boundary violations
/// - [`Error::encode_error`] - For encoding failures
/// - [`Error::invalid_data`] - For invalid or corrupted data
/// - [`Error::invalid_ref`] - For invalid reference IDs
/// - [`Error::unknown_enum`] - For unknown enum variants
/// - [`Error::type_error`] - For general type errors
/// - [`Error::encoding_error`] - For encoding format errors
/// - [`Error::depth_exceed`] - For exceeding maximum nesting depth
/// - [`Error::unsupported`] - For unsupported operations
/// - [`Error::not_allowed`] - For disallowed operations
/// - [`Error::unknown`] - For generic errors
///
/// ## Debug Mode: FORY_PANIC_ON_ERROR
///
/// For easier debugging, you can set the `FORY_PANIC_ON_ERROR` environment variable to make
/// the program panic at the exact location where an error is created. This helps identify
/// the error source with a full stack trace.
///
/// ```bash
/// RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=1 cargo run
/// # or
/// RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=true cargo test
/// ```
///
/// When enabled, any error created via the static constructor functions will panic immediately
/// with the error message, allowing you to see the exact call stack in your debugger or
/// panic output. Use `RUST_BACKTRACE=1` together with `FORY_PANIC_ON_ERROR` to get a full
/// stack trace showing exactly where the error was created.
#[derive(Error, Debug)]
#[non_exhaustive]
pub enum Error {
    /// Type mismatch between local and remote type IDs.
    ///
    /// Do not construct this variant directly; use [`Error::type_mismatch`] instead.
    #[error("Type mismatch: type_a = {0}, type_b = {1}")]
    TypeMismatch(u32, u32),

    /// Buffer boundary violation during read/write operations.
    ///
    /// Do not construct this variant directly; use [`Error::buffer_out_of_bound`] instead.
    #[error("Buffer out of bound: {0} + {1} > {2}")]
    BufferOutOfBound(usize, usize, usize),

    /// Error during data encoding.
    ///
    /// Do not construct this variant directly; use [`Error::encode_error`] instead.
    #[error("{0}")]
    EncodeError(Cow<'static, str>),

    /// Invalid or corrupted data encountered.
    ///
    /// Do not construct this variant directly; use [`Error::invalid_data`] instead.
    #[error("{0}")]
    InvalidData(Cow<'static, str>),

    /// Invalid reference ID encountered.
    ///
    /// Do not construct this variant directly; use [`Error::invalid_ref`] instead.
    #[error("{0}")]
    InvalidRef(Cow<'static, str>),

    /// Unknown enum variant encountered.
    ///
    /// Do not construct this variant directly; use [`Error::unknown_enum`] instead.
    #[error("{0}")]
    UnknownEnum(Cow<'static, str>),

    /// General type-related error.
    ///
    /// Do not construct this variant directly; use [`Error::type_error`] instead.
    #[error("{0}")]
    TypeError(Cow<'static, str>),

    /// Error in encoding format or conversion.
    ///
    /// Do not construct this variant directly; use [`Error::encoding_error`] instead.
    #[error("{0}")]
    EncodingError(Cow<'static, str>),

    /// Maximum nesting depth exceeded.
    ///
    /// Do not construct this variant directly; use [`Error::depth_exceed`] instead.
    #[error("{0}")]
    DepthExceed(Cow<'static, str>),

    /// Unsupported operation or feature.
    ///
    /// Do not construct this variant directly; use [`Error::unsupported`] instead.
    #[error("{0}")]
    Uunsupported(Cow<'static, str>),

    /// Operation not allowed in current context.
    ///
    /// Do not construct this variant directly; use [`Error::not_allowed`] instead.
    #[error("{0}")]
    NotAllowed(Cow<'static, str>),

    /// Generic unknown error.
    ///
    /// Do not construct this variant directly; use [`Error::unknown`] instead.
    #[error("{0}")]
    Unknown(Cow<'static, str>),

    /// Struct version mismatch between local and remote schemas.
    ///
    /// Do not construct this variant directly; use [`Error::struct_version_mismatch`] instead.
    #[error("{0}")]
    StructVersionMismatch(Cow<'static, str>),
}

impl Error {
    /// Creates a new [`Error::TypeMismatch`] with the given type IDs.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::type_mismatch(1, 2);
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn type_mismatch(type_a: u32, type_b: u32) -> Self {
        let err = Error::TypeMismatch(type_a, type_b);
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::BufferOutOfBound`] with the given bounds.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::buffer_out_of_bound(10, 20, 25);
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn buffer_out_of_bound(offset: usize, length: usize, capacity: usize) -> Self {
        let err = Error::BufferOutOfBound(offset, length, capacity);
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::EncodeError`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::encode_error("Failed to encode");
    /// let err = Error::encode_error(format!("Failed to encode field {}", "name"));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn encode_error<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::EncodeError(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::InvalidData`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::invalid_data("Invalid data format");
    /// let err = Error::invalid_data(format!("Invalid data at position {}", 42));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn invalid_data<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::InvalidData(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::InvalidRef`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::invalid_ref("Invalid reference");
    /// let err = Error::invalid_ref(format!("Invalid ref id {}", 123));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn invalid_ref<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::InvalidRef(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::UnknownEnum`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::unknown_enum("Unknown enum variant");
    /// let err = Error::unknown_enum(format!("Unknown variant {}", 5));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn unknown_enum<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::UnknownEnum(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::TypeError`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::type_error("Type error");
    /// let err = Error::type_error(format!("Expected type {}", "String"));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn type_error<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::TypeError(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::EncodingError`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::encoding_error("Encoding failed");
    /// let err = Error::encoding_error(format!("Failed to encode as {}", "UTF-8"));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn encoding_error<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::EncodingError(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::DepthExceed`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::depth_exceed("Max depth exceeded");
    /// let err = Error::depth_exceed(format!("Depth {} exceeds max {}", 100, 64));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn depth_exceed<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::DepthExceed(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::Uunsupported`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::unsupported("Unsupported operation");
    /// let err = Error::unsupported(format!("Type {} not supported", "MyType"));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn unsupported<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::Uunsupported(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::NotAllowed`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::not_allowed("Operation not allowed");
    /// let err = Error::not_allowed(format!("Cannot perform {}", "delete"));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn not_allowed<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::NotAllowed(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::StructVersionMismatch`] from a string or static message.
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::struct_version_mismatch("Version mismatch");
    /// let err = Error::struct_version_mismatch(format!("Class {} version mismatch", "Foo"));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn struct_version_mismatch<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::StructVersionMismatch(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }

    /// Creates a new [`Error::Unknown`] from a string or static message.
    ///
    /// This function is a convenient way to produce an error message
    /// from a literal, `String`, or any type that can be converted into
    /// a [`Cow<'static, str>`].
    ///
    /// If `FORY_PANIC_ON_ERROR` environment variable is set, this will panic with the error message.
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::unknown("Something went wrong");
    /// let err = Error::unknown(format!("ID:{} not found", 1));
    /// ```
    #[inline(always)]
    #[track_caller]
    pub fn unknown<S: Into<Cow<'static, str>>>(s: S) -> Self {
        let err = Error::Unknown(s.into());
        if should_panic_on_error() {
            panic!("FORY_PANIC_ON_ERROR: {}", err);
        }
        err
    }
}

/// Ensures a condition is true; otherwise returns an [`enum@Error`].
///
/// # Examples
/// ```
/// use fory_core::ensure;
/// use fory_core::error::Error;
///
/// fn check_value(n: i32) -> Result<(), Error> {
///     ensure!(n > 0, "value must be positive");
///     ensure!(n < 10, "value {} too large", n);
///     Ok(())
/// }
/// ```
#[macro_export]
macro_rules! ensure {
    ($cond:expr, $msg:literal) => {
        if !$cond {
            return Err($crate::error::Error::unknown($msg));
        }
    };
    ($cond:expr, $err:expr) => {
        if !$cond {
            return Err($err);
        }
    };
    ($cond:expr, $fmt:expr, $($arg:tt)*) => {
        if !$cond {
            return Err($crate::error::Error::unknown(format!($fmt, $($arg)*)));
        }
    };
}

/// Returns early with an [`enum@Error`].
///
/// # Examples
/// ```
/// use fory_core::bail;
/// use fory_core::error::Error;
///
/// fn fail_fast() -> Result<(), Error> {
///     bail!("something went wrong");
/// }
/// ```
#[macro_export]
macro_rules! bail {
    ($err:expr) => {
        return Err($crate::error::Error::unknown($err))
    };
    ($fmt:expr, $($arg:tt)*) => {
        return Err($crate::error::Error::unknown(format!($fmt, $($arg)*)))
    };
}

/// Returns early with a [`Error::NotAllowed`].
///
/// # Examples
/// ```
/// use fory_core::not_allowed;
/// use fory_core::error::Error;
///
/// fn check_operation() -> Result<(), Error> {
///     not_allowed!("operation not allowed");
/// }
///
/// fn check_operation_with_context(op: &str) -> Result<(), Error> {
///     not_allowed!("operation {} not allowed", op);
/// }
/// ```
#[macro_export]
macro_rules! not_allowed {
    ($err:expr) => {
        return Err($crate::error::Error::not_allowed($err))
    };
    ($fmt:expr, $($arg:tt)*) => {
        return Err($crate::error::Error::not_allowed(format!($fmt, $($arg)*)))
    };
}
