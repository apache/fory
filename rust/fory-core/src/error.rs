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
use std::io;

use thiserror::Error;

#[derive(Error, Debug)]
#[non_exhaustive]
pub enum Error {
    #[error("Type mismatch: type_a = {0}, type_b = {1}")]
    TypeMismatch(u32, u32),

    #[error("Buffer out of bound: {0} + {1} > {2}")]
    BufferOutOfBound(usize, usize, usize),

    #[error("IO error: {0}")]
    Io(#[from] io::Error),

    #[error("{0}")]
    EncodeError(Cow<'static, str>),

    #[error("{0}")]
    InvalidData(Cow<'static, str>),

    #[error("{0}")]
    InvalidRef(Cow<'static, str>),

    #[error("{0}")]
    UnknownEnum(Cow<'static, str>),

    #[error("{0}")]
    TypeError(Cow<'static, str>),

    #[error("{0}")]
    EncodingError(Cow<'static, str>),

    #[error("{0}")]
    DepthExceeded(Cow<'static, str>),

    /// Do not construct this variant directly; use [`Error::unknown`] instead.
    #[error("{0}")]
    Unknown(Cow<'static, str>),
}

impl Error {
    /// Creates a new [`Error::Unknown`] from a string or static message.
    ///
    /// This function is a convenient way to produce an error message
    /// from a literal, `String`, or any type that can be converted into
    /// a [`Cow<'static, str>`].
    ///
    /// # Example
    /// ```
    /// use fory_core::error::Error;
    ///
    /// let err = Error::unknown("Something went wrong");
    /// let err = Error::unknown(format!("ID:{} not found", 1));
    /// ```
    #[inline(always)]
    pub fn unknown<S: Into<Cow<'static, str>>>(s: S) -> Self {
        Error::Unknown(s.into())
    }
}

/// Ensures a condition is true; otherwise returns an [`Error`].
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

/// Returns early with an [`Error`].
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
