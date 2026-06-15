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

use crate::context::ReadContext;
use crate::context::WriteContext;
use crate::error::Error;
use crate::resolver::TypeResolver;
use crate::serializer::util::read_basic_type_info;
use crate::serializer::ForyDefault;
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use crate::types::{Date, Duration, Timestamp};
use std::mem;

impl Serializer for Timestamp {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_i64(self.seconds());
        context.writer.write_u32(self.subsec_nanos());
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let seconds = context.reader.read_i64()?;
        let nanos = context.reader.read_u32()?;
        Timestamp::new(seconds, nanos)
    }
    #[inline]
    fn fory_read_data_as_send_sync_any(
        context: &mut ReadContext,
    ) -> Result<Box<dyn std::any::Any + Send + Sync>, Error>
    where
        Self: Sized + ForyDefault,
    {
        Ok(crate::serializer::box_send_sync(Self::fory_read_data(
            context,
        )?))
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

impl ForyDefault for Timestamp {
    #[inline(always)]
    fn fory_default() -> Self {
        Timestamp::default()
    }
}

impl Serializer for Date {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        let days = self.epoch_days();
        if context.is_xlang() {
            context.writer.write_var_i64(days);
        } else {
            let native_days = i32::try_from(days).map_err(|_| {
                Error::invalid_data(format!("date day count {} exceeds native i32 range", days))
            })?;
            context.writer.write_i32(native_days);
        }
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let days = if context.is_xlang() {
            context.reader.read_var_i64()?
        } else {
            i64::from(context.reader.read_i32()?)
        };
        Ok(Date::from_epoch_days(days))
    }
    #[inline]
    fn fory_read_data_as_send_sync_any(
        context: &mut ReadContext,
    ) -> Result<Box<dyn std::any::Any + Send + Sync>, Error>
    where
        Self: Sized + ForyDefault,
    {
        Ok(crate::serializer::box_send_sync(Self::fory_read_data(
            context,
        )?))
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        9
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

impl ForyDefault for Date {
    #[inline(always)]
    fn fory_default() -> Self {
        Date::default()
    }
}

impl Serializer for Duration {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_var_i64(self.seconds());
        context.writer.write_i32(self.subsec_nanos() as i32);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let seconds = context.reader.read_var_i64()?;
        let nanos = context.reader.read_i32()?;
        Duration::new(seconds, nanos)
    }
    #[inline]
    fn fory_read_data_as_send_sync_any(
        context: &mut ReadContext,
    ) -> Result<Box<dyn std::any::Any + Send + Sync>, Error>
    where
        Self: Sized + ForyDefault,
    {
        Ok(crate::serializer::box_send_sync(Self::fory_read_data(
            context,
        )?))
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        9 + mem::size_of::<i32>()
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
        Duration::default()
    }
}

#[cfg(feature = "chrono")]
mod chrono_support {
    use super::*;
    use chrono::{Duration as ChronoDuration, NaiveDate, NaiveDateTime};

    impl Serializer for NaiveDateTime {
        #[inline(always)]
        fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
            Timestamp::from(*self).fory_write_data(context)
        }

        #[inline(always)]
        fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Timestamp::fory_read_data(context)?.try_into()
        }
        #[inline]
        fn fory_read_data_as_send_sync_any(
            context: &mut ReadContext,
        ) -> Result<Box<dyn std::any::Any + Send + Sync>, Error>
        where
            Self: Sized + ForyDefault,
        {
            Ok(crate::serializer::box_send_sync(Self::fory_read_data(
                context,
            )?))
        }

        #[inline(always)]
        fn fory_reserved_space() -> usize {
            Timestamp::fory_reserved_space()
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
            Timestamp::fory_write_type_info(context)
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

    impl Serializer for NaiveDate {
        #[inline(always)]
        fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
            Date::from(*self).fory_write_data(context)
        }

        #[inline(always)]
        fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Date::fory_read_data(context)?.try_into()
        }
        #[inline]
        fn fory_read_data_as_send_sync_any(
            context: &mut ReadContext,
        ) -> Result<Box<dyn std::any::Any + Send + Sync>, Error>
        where
            Self: Sized + ForyDefault,
        {
            Ok(crate::serializer::box_send_sync(Self::fory_read_data(
                context,
            )?))
        }

        #[inline(always)]
        fn fory_reserved_space() -> usize {
            Date::fory_reserved_space()
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
            Date::fory_write_type_info(context)
        }

        #[inline(always)]
        fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
            read_basic_type_info::<Self>(context)
        }
    }

    impl ForyDefault for NaiveDate {
        #[inline(always)]
        fn fory_default() -> Self {
            NaiveDate::default()
        }
    }

    impl Serializer for ChronoDuration {
        #[inline(always)]
        fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
            Duration::try_from(*self)?.fory_write_data(context)
        }

        #[inline(always)]
        fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Duration::fory_read_data(context)?.try_into()
        }
        #[inline]
        fn fory_read_data_as_send_sync_any(
            context: &mut ReadContext,
        ) -> Result<Box<dyn std::any::Any + Send + Sync>, Error>
        where
            Self: Sized + ForyDefault,
        {
            Ok(crate::serializer::box_send_sync(Self::fory_read_data(
                context,
            )?))
        }

        #[inline(always)]
        fn fory_reserved_space() -> usize {
            Duration::fory_reserved_space()
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
            Duration::fory_write_type_info(context)
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
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fory::Fory;

    #[test]
    fn test_temporal_carrier_serialization() {
        let fory = Fory::builder().xlang(false).compatible(false).build();

        let timestamps = [
            Timestamp::UNIX_EPOCH,
            Timestamp::new(1, 0).unwrap(),
            Timestamp::new(-1, 999_999_999).unwrap(),
        ];
        for timestamp in timestamps {
            let bytes = fory.serialize(&timestamp).unwrap();
            let deserialized: Timestamp = fory.deserialize(&bytes).unwrap();
            assert_eq!(timestamp, deserialized);
        }

        let dates = [
            Date::UNIX_EPOCH,
            Date::from_epoch_days(-1),
            Date::from_epoch_days(18_628),
        ];
        for date in dates {
            let bytes = fory.serialize(&date).unwrap();
            let deserialized: Date = fory.deserialize(&bytes).unwrap();
            assert_eq!(date, deserialized);
        }

        let durations = [
            Duration::ZERO,
            Duration::new(1, 0).unwrap(),
            Duration::new(0, -1).unwrap(),
            Duration::new(-123, 456_789).unwrap(),
        ];
        for duration in durations {
            let bytes = fory.serialize(&duration).unwrap();
            let deserialized: Duration = fory.deserialize(&bytes).unwrap();
            assert_eq!(duration, deserialized);
        }
    }

    #[test]
    fn test_duration_normalizes_negative_nanoseconds() {
        assert_eq!(
            Duration::new(0, -1).unwrap(),
            Duration::from_normalized(-1, 999_999_999).unwrap()
        );
    }

    #[cfg(feature = "chrono")]
    #[test]
    fn test_chrono_temporal_feature_serialization() {
        use chrono::{DateTime, Duration as ChronoDuration, NaiveDate, NaiveDateTime};

        let fory = Fory::builder().xlang(false).compatible(false).build();
        let date = NaiveDate::from_ymd_opt(2024, 2, 3).unwrap();
        let timestamp = DateTime::from_timestamp(100, 1).unwrap().naive_utc();
        let duration = ChronoDuration::nanoseconds(-1);

        let bytes = fory.serialize(&date).unwrap();
        let deserialized: NaiveDate = fory.deserialize(&bytes).unwrap();
        assert_eq!(date, deserialized);

        let bytes = fory.serialize(&timestamp).unwrap();
        let deserialized: NaiveDateTime = fory.deserialize(&bytes).unwrap();
        assert_eq!(timestamp, deserialized);

        let bytes = fory.serialize(&duration).unwrap();
        let deserialized: ChronoDuration = fory.deserialize(&bytes).unwrap();
        assert_eq!(duration, deserialized);
    }
}
