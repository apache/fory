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
use chrono::{Duration as ChronoDuration, NaiveDate, NaiveDateTime};
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
    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::TIMESTAMP)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::TIMESTAMP)
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
        context.writer.write_u8(TypeId::TIMESTAMP as u8);
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
    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::DATE)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::DATE)
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
        context.writer.write_u8(TypeId::DATE as u8);
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
        let raw = self.as_secs();
        if raw > i64::MAX as u64 {
            return Err(Error::invalid_data(format!(
                "std::time::Duration seconds {} exceeds i64::MAX and cannot be encoded as varint64",
                raw
            )));
        }
        let secs = raw as i64;
        let nanos = self.subsec_nanos() as i32;
        context.writer.write_varint64(secs);
        context.writer.write_i32(nanos);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let secs = context.reader.read_varint64()?;
        if secs < 0 {
            return Err(Error::invalid_data(format!(
                "negative duration seconds {} cannot be represented as std::time::Duration; use chrono::Duration instead",
                secs
            )));
        }
        let nanos = context.reader.read_i32()?;
        if !(0..=999_999_999).contains(&nanos) {
            // negative nanos will also be rejected, even though the xlang spec actually allows it.
            // RFC 1040 (https://rust-lang.github.io/rfcs/1040-duration-reform.html#detailed-design) explicitly forbids negative nanoseconds.
            // If supporting for negative nanoseconds is really needed, we can implement **normalization** similar to chrono and Java.
            return Err(Error::invalid_data(format!(
                "duration nanoseconds {} out of valid range [0, 999_999_999] for std::time::Duration",
                nanos
            )));
        }
        Ok(Duration::new(secs as u64, nanos as u32))
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        9 + mem::size_of::<i32>() // max varint64 is 9 bytes + 4 bytes for i32
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::DURATION)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::DURATION)
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
        context.writer.write_u8(TypeId::DURATION as u8);
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

impl Serializer for ChronoDuration {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        let secs = self.num_seconds();
        let nanos = self.subsec_nanos();
        context.writer.write_varint64(secs);
        context.writer.write_i32(nanos);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let secs = context.reader.read_varint64()?;
        let nanos = context.reader.read_i32()?;
        if !(-999_999_999..=999_999_999).contains(&nanos) {
            // chrono supports negative nanoseconds by applying normalization internally.
            return Err(Error::invalid_data(format!(
                "duration nanoseconds {} out of valid range [-999_999_999, 999_999_999]",
                nanos
            )));
        }
        ChronoDuration::try_seconds(secs) // the maximum seconds chrono supports is i64::MAX / 1_000, which is smaller than what the spec allows(i64::MAX)
            .and_then(|d| d.checked_add(&ChronoDuration::nanoseconds(nanos as i64)))
            .ok_or_else(|| {
                Error::invalid_data(format!(
                    "duration seconds {} out of chrono::Duration valid range",
                    secs
                ))
            })
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        9 + mem::size_of::<i32>() // max varint64 is 9 bytes + 4 bytes for i32
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::DURATION)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::DURATION)
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
        context.writer.write_u8(TypeId::DURATION as u8);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

impl ForyDefault for ChronoDuration {
    #[inline(always)]
    fn fory_default() -> Self {
        ChronoDuration::zero()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fory::Fory;

    #[test]
    fn test_std_duration_serialization() {
        let fory = Fory::default();

        // Test various durations
        let test_cases = vec![
            Duration::ZERO,
            Duration::new(0, 0),
            Duration::new(1, 0),
            Duration::new(0, 1),
            Duration::new(123, 456789),
            Duration::new(i64::MAX as u64, 999_999_999),
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

    #[test]
    fn test_chrono_duration_serialization() {
        let fory = Fory::default();

        // Test various durations
        let test_cases = vec![
            ChronoDuration::zero(),
            ChronoDuration::new(0, 0).unwrap(),
            ChronoDuration::new(1, 0).unwrap(),
            ChronoDuration::new(0, 1).unwrap(),
            ChronoDuration::new(123, 456789).unwrap(),
            ChronoDuration::seconds(-1),
            ChronoDuration::nanoseconds(-1),
            ChronoDuration::microseconds(-456789),
            ChronoDuration::MAX,
            ChronoDuration::MIN,
        ];

        for duration in test_cases {
            let bytes = fory.serialize(&duration).unwrap();
            let deserialized: ChronoDuration = fory.deserialize(&bytes).unwrap();
            assert_eq!(
                duration, deserialized,
                "Failed for duration: {:?}",
                duration
            );
        }
    }

    #[test]
    fn test_chrono_duration_out_of_range_is_error() {
        let fory = Fory::default();
        let too_large = Duration::new(i64::MAX as u64, 0);
        let bytes = fory.serialize(&too_large).unwrap();
        let result: Result<ChronoDuration, _> = fory.deserialize(&bytes);
        assert!(
            result.is_err(),
            "out-of-range seconds should not be deserialized into chrono::Duration!"
        );
    }

    #[test]
    fn test_negative_std_duration_read_is_error() {
        let fory = Fory::default();
        let negative_duration = ChronoDuration::seconds(-1);
        let bytes = fory.serialize(&negative_duration).unwrap();
        let result: Result<Duration, _> = fory.deserialize(&bytes);
        assert!(
            result.is_err(),
            "negative duration should not be deserialized into std::time::Duration!"
        );
    }
}
