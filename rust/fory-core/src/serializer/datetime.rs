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
use crate::types::{FieldType, ForyGeneralList};
use crate::util::EPOCH;
use anyhow::anyhow;
use chrono::{DateTime, Days, NaiveDate, NaiveDateTime};
use std::mem;

impl Serializer for NaiveDateTime {
    fn read(context: &mut ReadContext) -> Result<Self, Error> {
        let timestamp = context.reader.u64();
        DateTime::from_timestamp_millis(timestamp as i64)
            .map(|dt| dt.naive_utc())
            .ok_or(Error::from(anyhow!(
                "Date out of range, timestamp:{timestamp}"
            )))
    }

    fn write(&self, context: &mut WriteContext) {
        context.writer.u64(self.and_utc().timestamp_millis() as u64);
    }

    fn reserved_space() -> usize {
        mem::size_of::<u64>()
    }

    fn get_type_id(_fory: &Fory) -> i16 {
        FieldType::TIMESTAMP.into()
    }
}

impl ForyGeneralList for NaiveDateTime {}

impl Serializer for NaiveDate {
    fn write(&self, context: &mut WriteContext) {
        let days_since_epoch = self.signed_duration_since(EPOCH).num_days();
        context.writer.u64(days_since_epoch as u64);
    }

    fn reserved_space() -> usize {
        mem::size_of::<u64>()
    }

    fn read(context: &mut ReadContext) -> Result<Self, Error> {
        let days = context.reader.u64();
        EPOCH
            .checked_add_days(Days::new(days))
            .ok_or(Error::from(anyhow!(
                "Date out of range, {days} days since epoch"
            )))
    }

    fn get_type_id(_fory: &Fory) -> i16 {
        FieldType::LOCAL_DATE.into()
    }
}

impl ForyGeneralList for NaiveDate {}
