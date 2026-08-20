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

use std::collections::BTreeMap;

use crate::error::Error;
use crate::types::{Date, Duration, Timestamp};

use super::reader::{ArrayView, MapView};
use super::writer::{ArrayWriter, MapWriter, ValueWriter};

/// Static Row Format behavior for one schema value.
///
/// This trait is public because `ForyRow` implementations are generated in
/// downstream crates. Most applications should derive `ForyRow` instead of
/// implementing it directly.
#[doc(hidden)]
pub trait RowValue {
    /// Zero-copy projection returned when this value is read.
    type View<'a>;

    /// Natural fixed width, or `None` for an offset-addressed value.
    const FIXED_SIZE: Option<usize>;

    /// Writes exactly one value to its container-selected destination.
    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error>;

    /// Reads exactly one value from its container-resolved bytes.
    fn read<'a>(bytes: &'a [u8]) -> Result<Self::View<'a>, Error>;

    /// Returns true when this value should set its container null bit.
    fn is_null(&self) -> bool {
        false
    }

    /// Produces the projection for a set null bit.
    fn read_null<'a>() -> Result<Self::View<'a>, Error> {
        Err(Error::invalid_data(
            "null row value cannot be read as a non-optional type",
        ))
    }
}

/// A self-contained Standard Row Format root.
///
/// Derived structs, arrays, and maps implement this marker. Scalar, string,
/// binary, and optional values are field/element values rather than row roots.
pub trait Row: RowValue {}

macro_rules! impl_fixed_row_value {
    ($ty:ty, $size:expr) => {
        impl RowValue for $ty {
            type View<'a> = Self;

            const FIXED_SIZE: Option<usize> = Some($size);

            #[inline(always)]
            fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
                writer.write_bytes(&self.to_le_bytes())
            }

            #[inline(always)]
            fn read(bytes: &[u8]) -> Result<Self, Error> {
                Ok(Self::from_le_bytes(read_fixed(bytes)?))
            }
        }
    };
}

impl RowValue for bool {
    type View<'a> = Self;

    const FIXED_SIZE: Option<usize> = Some(1);

    #[inline(always)]
    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        writer.write_bytes(&[u8::from(*self)])
    }

    #[inline(always)]
    fn read(bytes: &[u8]) -> Result<Self, Error> {
        match read_fixed::<1>(bytes)?[0] {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(Error::invalid_data("row boolean must be encoded as 0 or 1")),
        }
    }
}

impl RowValue for i8 {
    type View<'a> = Self;

    const FIXED_SIZE: Option<usize> = Some(1);

    #[inline(always)]
    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        writer.write_bytes(&self.to_le_bytes())
    }

    #[inline(always)]
    fn read(bytes: &[u8]) -> Result<Self, Error> {
        Ok(Self::from_le_bytes(read_fixed(bytes)?))
    }
}

impl_fixed_row_value!(i16, 2);
impl_fixed_row_value!(i32, 4);
impl_fixed_row_value!(i64, 8);
impl_fixed_row_value!(f32, 4);
impl_fixed_row_value!(f64, 8);

impl RowValue for String {
    type View<'a> = &'a str;

    const FIXED_SIZE: Option<usize> = None;

    #[inline(always)]
    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        writer.write_bytes(self.as_bytes())
    }

    #[inline]
    fn read(bytes: &[u8]) -> Result<&str, Error> {
        std::str::from_utf8(bytes).map_err(|_| Error::invalid_data("invalid UTF-8 in row string"))
    }
}

impl RowValue for &str {
    type View<'a> = &'a str;

    const FIXED_SIZE: Option<usize> = None;

    #[inline(always)]
    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        writer.write_bytes(self.as_bytes())
    }

    #[inline]
    fn read(bytes: &[u8]) -> Result<&str, Error> {
        std::str::from_utf8(bytes).map_err(|_| Error::invalid_data("invalid UTF-8 in row string"))
    }
}

impl RowValue for Vec<u8> {
    type View<'a> = &'a [u8];

    const FIXED_SIZE: Option<usize> = None;

    #[inline(always)]
    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        writer.write_bytes(self)
    }

    #[inline(always)]
    fn read(bytes: &[u8]) -> Result<&[u8], Error> {
        Ok(bytes)
    }
}

impl RowValue for &[u8] {
    type View<'a> = &'a [u8];

    const FIXED_SIZE: Option<usize> = None;

    #[inline(always)]
    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        writer.write_bytes(self)
    }

    #[inline(always)]
    fn read(bytes: &[u8]) -> Result<&[u8], Error> {
        Ok(bytes)
    }
}

impl<T: RowValue> RowValue for Option<T> {
    type View<'a> = Option<T::View<'a>>;

    const FIXED_SIZE: Option<usize> = T::FIXED_SIZE;

    #[inline(always)]
    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        match self {
            Some(value) => value.write(writer),
            None => Err(Error::invalid_data(
                "a null row value must be written by its container",
            )),
        }
    }

    #[inline(always)]
    fn read<'a>(bytes: &'a [u8]) -> Result<Self::View<'a>, Error> {
        T::read(bytes).map(Some)
    }

    #[inline(always)]
    fn is_null(&self) -> bool {
        self.is_none()
    }

    #[inline(always)]
    fn read_null<'a>() -> Result<Self::View<'a>, Error> {
        Ok(None)
    }
}

impl RowValue for Date {
    type View<'a> = Self;

    const FIXED_SIZE: Option<usize> = Some(4);

    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        let days = i32::try_from(self.epoch_days()).map_err(|_| {
            Error::invalid_data(format!(
                "row date day count {} exceeds date32 range",
                self.epoch_days()
            ))
        })?;
        writer.write_bytes(&days.to_le_bytes())
    }

    fn read(bytes: &[u8]) -> Result<Self, Error> {
        let days = i32::from_le_bytes(read_fixed(bytes)?);
        Ok(Date::from_epoch_days(i64::from(days)))
    }
}

impl RowValue for Timestamp {
    type View<'a> = Self;

    const FIXED_SIZE: Option<usize> = Some(8);

    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        writer.write_bytes(&self.to_epoch_micros()?.to_le_bytes())
    }

    fn read(bytes: &[u8]) -> Result<Self, Error> {
        Ok(Timestamp::from_epoch_micros(i64::from_le_bytes(
            read_fixed(bytes)?,
        )))
    }
}

impl RowValue for Duration {
    type View<'a> = Self;

    const FIXED_SIZE: Option<usize> = Some(8);

    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        writer.write_bytes(&self.to_micros()?.to_le_bytes())
    }

    fn read(bytes: &[u8]) -> Result<Self, Error> {
        Ok(Duration::from_micros(i64::from_le_bytes(read_fixed(
            bytes,
        )?)))
    }
}

impl<T: RowValue, const N: usize> RowValue for [T; N] {
    type View<'a> = ArrayView<'a, T>;

    const FIXED_SIZE: Option<usize> = None;

    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        let mut array_writer = ArrayWriter::<T>::new(N, writer.into_variable()?)?;
        for (index, value) in self.iter().enumerate() {
            array_writer.write(index, value)?;
        }
        Ok(())
    }

    fn read(bytes: &[u8]) -> Result<Self::View<'_>, Error> {
        let view = ArrayView::new(bytes)?;
        if view.len() != N {
            return Err(Error::invalid_data(format!(
                "row fixed array expected {N} elements, found {}",
                view.len()
            )));
        }
        Ok(view)
    }
}

impl<T: RowValue, const N: usize> Row for [T; N] {}

impl<T: RowValue> RowValue for Vec<T> {
    type View<'a> = ArrayView<'a, T>;

    const FIXED_SIZE: Option<usize> = None;

    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        let mut array_writer = ArrayWriter::<T>::new(self.len(), writer.into_variable()?)?;
        for (index, value) in self.iter().enumerate() {
            array_writer.write(index, value)?;
        }
        Ok(())
    }

    fn read(bytes: &[u8]) -> Result<Self::View<'_>, Error> {
        ArrayView::new(bytes)
    }
}

impl<T: RowValue> Row for Vec<T> {}

impl<K, V> RowValue for BTreeMap<K, V>
where
    K: RowValue + Ord,
    V: RowValue,
{
    type View<'a> = MapView<'a, K, V>;

    const FIXED_SIZE: Option<usize> = None;

    fn write(&self, writer: ValueWriter<'_, '_>) -> Result<(), Error> {
        let mut map_writer = MapWriter::new(writer.into_variable()?);
        map_writer.write(self)
    }

    fn read(bytes: &[u8]) -> Result<Self::View<'_>, Error> {
        MapView::new(bytes)
    }
}

impl<K, V> Row for BTreeMap<K, V>
where
    K: RowValue + Ord,
    V: RowValue,
{
}

fn read_fixed<const N: usize>(bytes: &[u8]) -> Result<[u8; N], Error> {
    if bytes.len() != N {
        return Err(Error::invalid_data(format!(
            "row fixed-width value expected {N} bytes, found {}",
            bytes.len()
        )));
    }
    let mut value = [0u8; N];
    value.copy_from_slice(bytes);
    Ok(value)
}
