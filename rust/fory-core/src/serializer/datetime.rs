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
use crate::serializer::util::read_basic_type_info;
use crate::serializer::ForyDefault;
use crate::serializer::Serializer;
use crate::types::TypeId;
use crate::util::EPOCH;
use chrono::{NaiveDate, NaiveDateTime};
use std::mem;

impl Serializer for NaiveDateTime {
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        let dt = self.and_utc();
        let micros = dt.timestamp() * 1_000_000 + dt.timestamp_subsec_micros() as i64;
        context.writer.write_i64(micros);
        Ok(())
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let micros = context.reader.read_i64()?;
        use chrono::TimeDelta;
        let duration = TimeDelta::microseconds(micros);
        #[allow(deprecated)]
        let epoch_datetime = NaiveDateTime::from_timestamp(0, 0);
        let result = epoch_datetime + duration;
        Ok(result)
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

    fn fory_static_type_id() -> TypeId {
        TypeId::TIMESTAMP
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_varuint32(TypeId::TIMESTAMP as u32);
        Ok(())
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

impl Serializer for NaiveDate {
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        let days_since_epoch = self.signed_duration_since(EPOCH).num_days();
        context.writer.write_i32(days_since_epoch as i32);
        Ok(())
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let days = context.reader.read_i32()?;
        use chrono::TimeDelta;
        let duration = TimeDelta::days(days as i64);
        let result = EPOCH + duration;
        Ok(result)
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

    fn fory_static_type_id() -> TypeId {
        TypeId::LOCAL_DATE
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_varuint32(TypeId::LOCAL_DATE as u32);
        Ok(())
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
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
