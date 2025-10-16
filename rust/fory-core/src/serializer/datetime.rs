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

use crate::error::Error;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::Serializer;
use crate::serializer::{read_type_info, write_type_info, ForyDefault};
use crate::types::TypeId;
use crate::util::EPOCH;
use chrono::{DateTime, Days, NaiveDate, NaiveDateTime};
use std::mem;

impl Serializer for NaiveDateTime {
    fn fory_write_data(&self, context: &mut WriteContext, _is_field: bool) -> Result<(), Error> {
        let dt = self.and_utc();
        let micros = dt.timestamp() * 1_000_000 + dt.timestamp_subsec_micros() as i64;
        context.writer.write_i64(micros);
        Ok(())
    }

    fn fory_read_data(context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        let micros = context.reader.read_i64()?;
        let seconds = micros / 1_000_000;
        let subsec_micros = (micros % 1_000_000) as u32;
        let nanos = subsec_micros * 1_000;
        DateTime::from_timestamp(seconds, nanos)
            .map(|dt| dt.naive_utc())
            .ok_or(Error::InvalidData(
                format!("Date out of range, timestamp micros: {micros}").into(),
            ))
    }

    fn fory_read_data_into(
        context: &mut ReadContext,
        _is_field: bool,
        output: &mut Self,
    ) -> Result<(), Error> {
        let micros = context.reader.read_i64()?;
        let seconds = micros / 1_000_000;
        let subsec_micros = (micros % 1_000_000) as u32;
        let nanos = subsec_micros * 1_000;
        *output = DateTime::from_timestamp(seconds, nanos)
            .map(|dt| dt.naive_utc())
            .ok_or(Error::InvalidData(
                format!("Date out of range, timestamp micros: {micros}").into(),
            ))?;
        Ok(())
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<u64>()
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::TIMESTAMP as u32)
    }

    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::TIMESTAMP as u32)
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) -> Result<(), Error> {
        write_type_info::<Self>(context, is_field)
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) -> Result<(), Error> {
        read_type_info::<Self>(context, is_field)
    }
}

impl Serializer for NaiveDate {
    fn fory_write_data(&self, context: &mut WriteContext, _is_field: bool) -> Result<(), Error> {
        let days_since_epoch = self.signed_duration_since(EPOCH).num_days();
        context.writer.write_i32(days_since_epoch as i32);
        Ok(())
    }

    fn fory_read_data(context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        let days = context.reader.read_i32()?;
        EPOCH
            .checked_add_days(Days::new(days as u64))
            .ok_or(Error::InvalidData(
                format!("Date out of range, {days} days since epoch").into(),
            ))
    }

    fn fory_read_data_into(
        context: &mut ReadContext,
        _is_field: bool,
        output: &mut Self,
    ) -> Result<(), Error> {
        let days = context.reader.read_i32()?;
        *output = EPOCH
            .checked_add_days(Days::new(days as u64))
            .ok_or(Error::InvalidData(
                format!("Date out of range, {days} days since epoch").into(),
            ))?;
        Ok(())
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::LOCAL_DATE as u32)
    }

    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::LOCAL_DATE as u32)
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) -> Result<(), Error> {
        write_type_info::<Self>(context, is_field)
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) -> Result<(), Error> {
        read_type_info::<Self>(context, is_field)
    }
}

impl ForyDefault for NaiveDateTime {
    fn fory_default() -> Self {
        NaiveDateTime::default()
    }
}

impl ForyDefault for NaiveDate {
    fn fory_default() -> Self {
        NaiveDate::default()
    }
}
