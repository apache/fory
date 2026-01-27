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
use std::time::Duration;

impl Serializer for NaiveDateTime {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        let dt = self.and_utc();
        let seconds = dt.timestamp();
        let nanos = dt.timestamp_subsec_nanos();
        context.writer.write_i64(seconds);
        context.writer.write_u32(nanos);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let seconds = context.reader.read_i64()?;
        let nanos = context.reader.read_u32()?;
        #[allow(deprecated)]
        let result = NaiveDateTime::from_timestamp(seconds, nanos);
        Ok(result)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        mem::size_of::<i64>() + mem::size_of::<u32>()
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::TIMESTAMP as u32)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::TIMESTAMP as u32)
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId {
        TypeId::TIMESTAMP
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_varuint32(TypeId::TIMESTAMP as u32);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

impl Serializer for NaiveDate {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        let days_since_epoch = self.signed_duration_since(EPOCH).num_days();
        context.writer.write_i32(days_since_epoch as i32);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let days = context.reader.read_i32()?;
        use chrono::TimeDelta;
        let duration = TimeDelta::days(days as i64);
        let result = EPOCH + duration;
        Ok(result)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::DATE as u32)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::DATE as u32)
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId {
        TypeId::DATE
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_varuint32(TypeId::DATE as u32);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

impl ForyDefault for NaiveDateTime {
    #[inline(always)]
    fn fory_default() -> Self {
        NaiveDateTime::default()
    }
}

impl ForyDefault for NaiveDate {
    #[inline(always)]
    fn fory_default() -> Self {
        NaiveDate::default()
    }
}

impl Serializer for Duration {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        let secs = self.as_secs() as i64;
        let nanos = self.subsec_nanos() as i32;
        context.writer.write_varint64(secs);
        context.writer.write_i32(nanos);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let secs = context.reader.read_varint64()? as u64;
        let nanos = context.reader.read_i32()? as u32;
        Ok(Duration::new(secs, nanos))
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        9 + mem::size_of::<i32>() // max varint64 is 9 bytes + 4 bytes for i32
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::DURATION as u32)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::DURATION as u32)
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId {
        TypeId::DURATION
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_varuint32(TypeId::DURATION as u32);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

impl ForyDefault for Duration {
    #[inline(always)]
    fn fory_default() -> Self {
        Duration::ZERO
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fory::Fory;

    #[test]
    fn test_duration_serialization() {
        let fory = Fory::default();

        // Test various durations
        let test_cases = vec![
            Duration::ZERO,
            Duration::new(0, 0),
            Duration::new(1, 0),
            Duration::new(0, 1),
            Duration::new(123, 456789),
            Duration::new(u64::MAX, 999_999_999),
        ];

        for duration in test_cases {
            let bytes = fory.serialize(&duration).unwrap();
            let deserialized: Duration = fory.deserialize(&bytes).unwrap();
            assert_eq!(
                duration, deserialized,
                "Failed for duration: {:?}",
                duration
            );
        }
    }
}
