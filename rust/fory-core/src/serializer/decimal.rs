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

use crate::buffer::{Reader, Writer};
use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::serializer::util::read_basic_type_info;
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use crate::types::Decimal;
use num_bigint::{BigInt, Sign};
use std::convert::TryFrom;
use std::sync::Arc;

const MAX_DECIMAL_MAGNITUDE_BYTES: usize = 10_000;
const MAX_DECIMAL_SCALE: i32 = 10_000;

impl Serializer for Decimal {
    type Target = Self;

    const READ_DATA_ALWAYS_ADVANCES: bool = true;

    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        // Keep direct bounds checks because taking abs() overflows for i32::MIN.
        if value.scale < -MAX_DECIMAL_SCALE || value.scale > MAX_DECIMAL_SCALE {
            return Err(Error::encode_error(format!(
                "decimal scale {} exceeds supported range [{}, {}]",
                value.scale, -MAX_DECIMAL_SCALE, MAX_DECIMAL_SCALE
            )));
        }
        if value.unscaled.bits() > (MAX_DECIMAL_MAGNITUDE_BYTES as u64) * 8 {
            return Err(Error::encode_error(format!(
                "decimal magnitude exceeds {} bytes",
                MAX_DECIMAL_MAGNITUDE_BYTES
            )));
        }
        context.writer.write_var_i32(value.scale);
        write_decimal_unscaled(&value.unscaled, &mut context.writer)
    }

    #[inline(always)]
    #[allow(clippy::manual_range_contains)]
    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let scale = context.reader.read_var_i32()?;
        if scale < -MAX_DECIMAL_SCALE || scale > MAX_DECIMAL_SCALE {
            return Err(Error::invalid_data(format!(
                "decimal scale {} exceeds supported range [{}, {}]",
                scale, -MAX_DECIMAL_SCALE, MAX_DECIMAL_SCALE
            )));
        }
        let unscaled = read_decimal_unscaled(&mut context.reader)?;
        Ok(Self { unscaled, scale })
    }

    #[inline(always)]
    fn default_value(_: &mut ReadContext) -> Result<Self, Error> {
        Ok(Self {
            unscaled: BigInt::from(0),
            scale: 0,
        })
    }

    #[inline(always)]
    fn read_arc_any(
        context: &mut ReadContext,
    ) -> Result<Arc<dyn std::any::Any + Send + Sync>, Error> {
        Ok(Arc::new(Self::read_data(context)?))
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::DECIMAL
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_var_u32(TypeId::DECIMAL as u32);
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

fn write_decimal_unscaled(value: &BigInt, writer: &mut Writer) -> Result<(), Error> {
    if let Some(small_value) = can_use_small_encoding(value) {
        writer.write_var_u64(encode_zigzag64(small_value) << 1);
        return Ok(());
    }

    let (sign, magnitude_bytes) = value.to_bytes_le();
    if magnitude_bytes.is_empty() {
        return Err(Error::invalid_data(
            "zero must use the small decimal encoding".to_string(),
        ));
    }
    let meta = ((magnitude_bytes.len() as u64) << 1) | u64::from(matches!(sign, Sign::Minus));
    writer.write_var_u64((meta << 1) | 1);
    writer.write_bytes(&magnitude_bytes);
    Ok(())
}

fn read_decimal_unscaled(reader: &mut Reader) -> Result<BigInt, Error> {
    let header = reader.read_var_u64()?;
    if (header & 1) == 0 {
        return Ok(BigInt::from(decode_zigzag64(header >> 1)));
    }

    let meta = header >> 1;
    let sign = (meta & 1) != 0;
    let len = meta >> 1;
    if len == 0 {
        return Err(Error::invalid_data(
            "invalid decimal magnitude length 0".to_string(),
        ));
    }
    if len > MAX_DECIMAL_MAGNITUDE_BYTES as u64 {
        return Err(Error::invalid_data(format!(
            "decimal magnitude length {} exceeds limit {}",
            len, MAX_DECIMAL_MAGNITUDE_BYTES
        )));
    }
    let len = usize::try_from(len)
        .map_err(|_| Error::invalid_data(format!("invalid decimal magnitude length {}", len)))?;
    let magnitude_bytes = reader.read_bytes(len)?;
    if magnitude_bytes[len - 1] == 0 {
        return Err(Error::invalid_data(
            "non-canonical decimal magnitude: trailing zero byte".to_string(),
        ));
    }
    let magnitude = BigInt::from_bytes_le(Sign::Plus, magnitude_bytes);
    if magnitude == BigInt::from(0) {
        return Err(Error::invalid_data(
            "big decimal encoding must not represent zero".to_string(),
        ));
    }
    Ok(if sign { -magnitude } else { magnitude })
}

fn can_use_small_encoding(value: &BigInt) -> Option<i64> {
    let small_value = i64::try_from(value).ok()?;
    if (encode_zigzag64(small_value) & (1u64 << 63)) == 0 {
        Some(small_value)
    } else {
        None
    }
}

#[inline(always)]
fn encode_zigzag64(value: i64) -> u64 {
    ((value << 1) ^ (value >> 63)) as u64
}

#[inline(always)]
fn decode_zigzag64(value: u64) -> i64 {
    ((value >> 1) as i64) ^ -((value & 1) as i64)
}
