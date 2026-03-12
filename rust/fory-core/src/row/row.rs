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

use crate::util::EPOCH;
use crate::{buffer::Writer, error::Error};
use byteorder::{ByteOrder, LittleEndian};
use chrono::{DateTime, Days, NaiveDate, NaiveDateTime};
use std::collections::BTreeMap;
use std::marker::PhantomData;

use super::{
    reader::{ArrayViewer, MapViewer},
    writer::{ArrayWriter, MapWriter},
};

pub trait Row<'a> {
    type ReadResult;

    fn write(v: &Self, writer: &mut Writer) -> Result<(), Error>;

    fn cast(bytes: &'a [u8]) -> Self::ReadResult;
}

fn read_i8_from_bytes(bytes: &[u8]) -> i8 {
    bytes[0] as i8
}

macro_rules! impl_row_for_number {
    ($tt: tt, $writer: expr ,$visitor: expr) => {
        impl<'a> Row<'a> for $tt {
            type ReadResult = Self;

            fn write(v: &Self, writer: &mut Writer) -> Result<(), Error> {
                $writer(writer, *v);
                Ok(())
            }

            fn cast(bytes: &[u8]) -> Self::ReadResult {
                $visitor(bytes)
            }
        }
    };
}
impl_row_for_number!(i8, Writer::write_i8, read_i8_from_bytes);
impl_row_for_number!(i16, Writer::write_i16, LittleEndian::read_i16);
impl_row_for_number!(i32, Writer::write_i32, LittleEndian::read_i32);
impl_row_for_number!(i64, Writer::write_i64, LittleEndian::read_i64);
impl_row_for_number!(f32, Writer::write_f32, LittleEndian::read_f32);
impl_row_for_number!(f64, Writer::write_f64, LittleEndian::read_f64);

impl<'a> Row<'a> for String {
    type ReadResult = &'a str;

    fn write(v: &Self, writer: &mut Writer) -> Result<(), Error> {
        writer.write_bytes(v.as_bytes());
        Ok(())
    }

    fn cast(bytes: &'a [u8]) -> Self::ReadResult {
        unsafe { std::str::from_utf8_unchecked(bytes) }
    }
}

impl Row<'_> for bool {
    type ReadResult = Self;

    fn write(v: &Self, writer: &mut Writer) -> Result<(), Error> {
        writer.write_u8(if *v { 1 } else { 0 });
        Ok(())
    }

    fn cast(bytes: &[u8]) -> Self::ReadResult {
        bytes[0] == 1
    }
}

/// ArrayGetter for fixed-size arrays, wrapping the underlying ArrayViewer
pub struct FixedArrayGetter<'a, T, const N: usize> {
    array_data: ArrayViewer<'a>,
    _marker: PhantomData<T>,
}

impl<'a, T: Row<'a>, const N: usize> FixedArrayGetter<'a, T, N> {
    pub fn size(&self) -> usize {
        self.array_data.num_elements()
    }

    pub fn get(&self, idx: usize) -> Result<T::ReadResult, Error> {
        if idx >= self.array_data.num_elements() {
            return Err(Error::buffer_out_of_bound(
                idx,
                1,
                self.array_data.num_elements(),
            ));
        }
        let bytes = self.array_data.get_field_bytes(idx);
        Ok(<T as Row>::cast(bytes))
    }
}

impl<'a, T: Row<'a>, const N: usize> Row<'a> for [T; N] {
    type ReadResult = FixedArrayGetter<'a, T, N>;

    fn write(v: &Self, writer: &mut Writer) -> Result<(), Error> {
        let mut array_writer = ArrayWriter::new(N, writer)?;
        for (idx, item) in v.iter().enumerate() {
            let callback_info = array_writer.write_start(idx);
            <T as Row>::write(item, array_writer.get_writer())?;
            array_writer.write_end(callback_info);
        }
        Ok(())
    }

    fn cast(row: &'a [u8]) -> Self::ReadResult {
        FixedArrayGetter {
            array_data: ArrayViewer::new(row),
            _marker: PhantomData::<T>,
        }
    }
}

impl Row<'_> for NaiveDate {
    type ReadResult = Result<NaiveDate, Error>;

    fn write(v: &Self, writer: &mut Writer) -> Result<(), Error> {
        let days_since_epoch = v.signed_duration_since(EPOCH).num_days();
        writer.write_u32(days_since_epoch as u32);
        Ok(())
    }

    fn cast(bytes: &[u8]) -> Self::ReadResult {
        let days = LittleEndian::read_u32(bytes);
        EPOCH
            .checked_add_days(Days::new(days.into()))
            .ok_or(Error::invalid_data(format!(
                "Date out of range, {days} days since epoch"
            )))
    }
}

impl Row<'_> for NaiveDateTime {
    type ReadResult = Result<NaiveDateTime, Error>;

    fn write(v: &Self, writer: &mut Writer) -> Result<(), Error> {
        writer.write_i64(v.and_utc().timestamp_millis());
        Ok(())
    }

    fn cast(bytes: &[u8]) -> Self::ReadResult {
        let timestamp = LittleEndian::read_u64(bytes);
        DateTime::from_timestamp_millis(timestamp as i64)
            .map(|dt| dt.naive_utc())
            .ok_or(Error::invalid_data(format!(
                "Date out of range, timestamp:{timestamp}"
            )))
    }
}

impl<'a> Row<'a> for Vec<u8> {
    type ReadResult = &'a [u8];

    fn write(v: &Self, writer: &mut Writer) -> Result<(), Error> {
        writer.write_bytes(v);
        Ok(())
    }

    fn cast(bytes: &'a [u8]) -> Self::ReadResult {
        bytes
    }
}

pub struct ArrayGetter<'a, T> {
    array_data: ArrayViewer<'a>,
    _marker: PhantomData<T>,
}

#[allow(clippy::needless_lifetimes)]
impl<'a, T: Row<'a>> ArrayGetter<'a, T> {
    pub fn size(&self) -> usize {
        self.array_data.num_elements()
    }

    pub fn get(&self, idx: usize) -> Result<T::ReadResult, Error> {
        if idx >= self.array_data.num_elements() {
            return Err(Error::buffer_out_of_bound(
                idx,
                1,
                self.array_data.num_elements(),
            ));
        }
        let bytes = self.array_data.get_field_bytes(idx);
        Ok(<T as Row>::cast(bytes))
    }
}

#[allow(clippy::needless_lifetimes)]
impl<'a, T: Row<'a>> Row<'a> for Vec<T> {
    type ReadResult = ArrayGetter<'a, T>;

    fn write<'b>(v: &Self, writer: &mut Writer<'b>) -> Result<(), Error> {
        let mut array_writer = ArrayWriter::new(v.len(), writer)?;
        for (idx, item) in v.iter().enumerate() {
            let callback_info = array_writer.write_start(idx);
            <T as Row>::write(item, array_writer.get_writer())?;
            array_writer.write_end(callback_info);
        }
        Ok(())
    }

    fn cast(row: &'a [u8]) -> Self::ReadResult {
        ArrayGetter {
            array_data: ArrayViewer::new(row),
            _marker: PhantomData::<T>,
        }
    }
}

pub struct MapGetter<'a, T1, T2>
where
    T1: Ord,
    T2: Ord,
{
    map_data: MapViewer<'a>,
    _key_marker: PhantomData<T1>,
    _value_marker: PhantomData<T2>,
}

impl<'a, T1: Row<'a> + Ord, T2: Row<'a> + Ord> MapGetter<'a, T1, T2> {
    pub fn to_btree_map(&'a self) -> Result<BTreeMap<T1::ReadResult, T2::ReadResult>, Error>
    where
        <T1 as Row<'a>>::ReadResult: Ord,
    {
        let mut map = BTreeMap::new();
        let keys = self.keys();
        let values = self.values();

        for i in 0..self.keys().size() {
            map.insert(keys.get(i)?, values.get(i)?);
        }
        Ok(map)
    }

    pub fn keys(&'a self) -> ArrayGetter<'a, T1> {
        ArrayGetter {
            array_data: ArrayViewer::new(self.map_data.get_key_row()),
            _marker: PhantomData::<T1>,
        }
    }

    pub fn values(&'a self) -> ArrayGetter<'a, T2> {
        ArrayGetter {
            array_data: ArrayViewer::new(self.map_data.get_value_row()),
            _marker: PhantomData::<T2>,
        }
    }
}

#[allow(clippy::needless_lifetimes)]
impl<'a, T1: Row<'a> + Ord, T2: Row<'a> + Ord> Row<'a> for BTreeMap<T1, T2> {
    type ReadResult = MapGetter<'a, T1, T2>;

    fn write<'b>(v: &Self, writer: &mut Writer<'b>) -> Result<(), Error> {
        let mut map_writer = MapWriter::new(writer);
        {
            let callback_info = map_writer.write_start(0);
            let mut array_writer = ArrayWriter::new(v.len(), map_writer.get_writer())?;
            for (idx, item) in v.keys().enumerate() {
                let callback_info = array_writer.write_start(idx);
                <T1 as Row>::write(item, array_writer.get_writer())?;
                array_writer.write_end(callback_info);
            }
            map_writer.write_end(callback_info);
        }
        {
            let mut array_writer = ArrayWriter::new(v.len(), map_writer.get_writer())?;
            for (idx, item) in v.values().enumerate() {
                let callback_info = array_writer.write_start(idx);
                <T2 as Row>::write(item, array_writer.get_writer())?;
                array_writer.write_end(callback_info);
            }
        }
        Ok(())
    }

    fn cast(row: &'a [u8]) -> Self::ReadResult {
        MapGetter {
            map_data: MapViewer::new(row),
            _key_marker: PhantomData::<T1>,
            _value_marker: PhantomData::<T2>,
        }
    }
}
