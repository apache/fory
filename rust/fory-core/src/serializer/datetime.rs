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

use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::serializer::util::read_basic_type_info;
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use crate::types::{Date, Duration, Timestamp};
use std::sync::Arc;

macro_rules! temporal_hooks {
    ($ty:ty, $type_id:expr, $reserved:expr, $default:expr) => {
        #[inline(always)]
        fn default_value(_: &mut ReadContext) -> Result<Self, Error> {
            Ok($default)
        }

        #[inline(always)]
        fn read_arc_any(
            context: &mut ReadContext,
        ) -> Result<Arc<dyn std::any::Any + Send + Sync>, Error> {
            Ok(Arc::new(Self::read_data(context)?))
        }

        #[inline(always)]
        fn reserved_space() -> usize {
            $reserved
        }

        #[inline(always)]
        fn static_type_id() -> TypeId {
            $type_id
        }

        #[inline(always)]
        fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
            context.writer.write_u8($type_id as u8);
            Ok(())
        }

        #[inline(always)]
        fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
            read_basic_type_info::<$ty>(context)
        }
    };
}

impl Serializer for Timestamp {
    type Target = Self;

    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_i64(value.seconds());
        context.writer.write_u32(value.subsec_nanos());
        Ok(())
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Timestamp::new(context.reader.read_i64()?, context.reader.read_u32()?)
    }

    temporal_hooks!(
        Timestamp,
        TypeId::TIMESTAMP,
        std::mem::size_of::<i64>() + std::mem::size_of::<u32>(),
        Timestamp::default()
    );
}

impl Serializer for Date {
    type Target = Self;

    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        let days = value.epoch_days();
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
    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let days = if context.is_xlang() {
            context.reader.read_var_i64()?
        } else {
            i64::from(context.reader.read_i32()?)
        };
        Ok(Date::from_epoch_days(days))
    }

    temporal_hooks!(Date, TypeId::DATE, 9, Date::default());
}

impl Serializer for Duration {
    type Target = Self;

    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_var_i64(value.seconds());
        context.writer.write_i32(value.subsec_nanos() as i32);
        Ok(())
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Duration::new(context.reader.read_var_i64()?, context.reader.read_i32()?)
    }

    temporal_hooks!(
        Duration,
        TypeId::DURATION,
        9 + std::mem::size_of::<i32>(),
        Duration::default()
    );
}

#[cfg(feature = "chrono")]
mod chrono_support {
    use super::*;
    use chrono::{Duration as ChronoDuration, NaiveDate, NaiveDateTime};

    impl Serializer for NaiveDateTime {
        type Target = Self;

        #[inline(always)]
        fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
            Timestamp::write_data(&Timestamp::from(*value), context)
        }

        #[inline(always)]
        fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Timestamp::read_data(context)?.try_into()
        }

        temporal_hooks!(
            NaiveDateTime,
            TypeId::TIMESTAMP,
            Timestamp::reserved_space(),
            NaiveDateTime::default()
        );
    }

    impl Serializer for NaiveDate {
        type Target = Self;

        #[inline(always)]
        fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
            Date::write_data(&Date::from(*value), context)
        }

        #[inline(always)]
        fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Date::read_data(context)?.try_into()
        }

        temporal_hooks!(
            NaiveDate,
            TypeId::DATE,
            Date::reserved_space(),
            NaiveDate::default()
        );
    }

    impl Serializer for ChronoDuration {
        type Target = Self;

        #[inline(always)]
        fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
            Duration::write_data(&Duration::try_from(*value)?, context)
        }

        #[inline(always)]
        fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
            Duration::read_data(context)?.try_into()
        }

        temporal_hooks!(
            ChronoDuration,
            TypeId::DURATION,
            Duration::reserved_space(),
            ChronoDuration::zero()
        );
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
    fn duration_negative_nanoseconds() {
        assert_eq!(
            Duration::new(0, -1).unwrap(),
            Duration::from_normalized(-1, 999_999_999).unwrap()
        );
    }

    #[cfg(feature = "chrono")]
    #[test]
    fn chrono_temporal_serialization() {
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
