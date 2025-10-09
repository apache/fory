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
use crate::fory::Fory;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::Serializer;
use crate::serializer::{read_type_info, write_type_info, ForyDefault};
use crate::types::TypeId;
use crate::util::EPOCH;
use anyhow::anyhow;
use chrono::{DateTime, Days, NaiveDate, NaiveDateTime};
use std::mem;

impl Serializer for NaiveDateTime {
    fn fory_write_data(&self, _fory: &Fory, context: &mut WriteContext, _is_field: bool) {
        let dt = self.and_utc();
        let micros = dt.timestamp() * 1_000_000 + dt.timestamp_subsec_micros() as i64;
        context.writer.write_i64(micros);
    }

    fn fory_read_data(
        _fory: &Fory,
        context: &mut ReadContext,
        _is_field: bool,
    ) -> Result<Self, Error> {
        let micros = context.reader.read_i64();
        let seconds = micros / 1_000_000;
        let subsec_micros = (micros % 1_000_000) as u32;
        let nanos = subsec_micros * 1_000;
        DateTime::from_timestamp(seconds, nanos)
            .map(|dt| dt.naive_utc())
            .ok_or(Error::from(anyhow!(
                "Date out of range, timestamp micros: {micros}"
            )))
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<u64>()
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        TypeId::TIMESTAMP as u32
    }

    fn fory_type_id_dyn(&self, _fory: &Fory) -> u32 {
        TypeId::TIMESTAMP as u32
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(fory: &Fory, context: &mut WriteContext, is_field: bool) {
        write_type_info::<Self>(fory, context, is_field);
    }

    fn fory_read_type_info(fory: &Fory, context: &mut ReadContext, is_field: bool) {
        read_type_info::<Self>(fory, context, is_field);
    }
}

impl Serializer for NaiveDate {
    fn fory_write_data(&self, _fory: &Fory, context: &mut WriteContext, _is_field: bool) {
        let days_since_epoch = self.signed_duration_since(EPOCH).num_days();
        context.writer.write_i32(days_since_epoch as i32);
    }

    fn fory_read_data(
        _fory: &Fory,
        context: &mut ReadContext,
        _is_field: bool,
    ) -> Result<Self, Error> {
        let days = context.reader.read_i32();
        EPOCH
            .checked_add_days(Days::new(days as u64))
            .ok_or(Error::from(anyhow!(
                "Date out of range, {days} days since epoch"
            )))
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        TypeId::LOCAL_DATE as u32
    }

    fn fory_type_id_dyn(&self, _fory: &Fory) -> u32 {
        TypeId::LOCAL_DATE as u32
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(fory: &Fory, context: &mut WriteContext, is_field: bool) {
        write_type_info::<Self>(fory, context, is_field);
    }

    fn fory_read_type_info(fory: &Fory, context: &mut ReadContext, is_field: bool) {
        read_type_info::<Self>(fory, context, is_field);
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
