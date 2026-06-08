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

use super::codec::{field_ref_mode, Codec};
use crate::context::ReadContext;
use crate::error::Error;
use crate::meta::{FieldInfo, FieldType};
use crate::resolver::{RefFlag, RefMode};
use crate::serializer::{ForyDefault, Serializer};
use crate::type_id;
use crate::types::{bfloat16::bfloat16, float16::float16, Decimal};
use num_bigint::BigInt;
use num_traits::{One, Signed, ToPrimitive, Zero};
use std::any::Any;

const MAX_COMPATIBLE_DECIMAL_DIGITS: i32 = 256;
const MAX_COMPATIBLE_NUMERIC_TEXT_LEN: usize = 320;

enum ScalarValue {
    Bool(bool),
    String(String),
    Int(BigInt),
    Float(FloatValue),
    Decimal(Decimal),
}

#[derive(Clone, Copy)]
enum FloatValue {
    F16(float16),
    BF16(bfloat16),
    F32(f32),
    F64(f64),
}

struct ParsedNumber {
    decimal: Decimal,
    negative_zero: bool,
}

#[inline(never)]
pub(super) fn read_scalar_field<T, C>(
    context: &mut ReadContext,
    local_field_type: &FieldType,
    remote_field_type: &FieldType,
) -> Result<Option<T>, Error>
where
    T: 'static,
    C: Codec<T> + ?Sized,
{
    if !scalar_field_types_compatible(local_field_type, remote_field_type) {
        return Ok(None);
    }
    if !read_present_ref(context, remote_field_type)? {
        return Ok(Some(C::default_value()));
    }
    let converted = read_and_convert(context, local_field_type.type_id, remote_field_type)?;
    boxed_to_value(converted).map(Some)
}

#[inline(never)]
pub(super) fn read_scalar_option_field<T>(
    context: &mut ReadContext,
    local_field_type: &FieldType,
    remote_field_type: &FieldType,
) -> Result<Option<Option<T>>, Error>
where
    T: 'static,
{
    if !scalar_field_types_compatible(local_field_type, remote_field_type) {
        return Ok(None);
    }
    if !read_present_ref(context, remote_field_type)? {
        return Ok(Some(None));
    }
    let converted = read_and_convert(context, local_field_type.type_id, remote_field_type)?;
    boxed_to_value(converted).map(|value| Some(Some(value)))
}

macro_rules! scalar_target_reader {
    ($read:ident, $read_option:ident, $ty:ty, $payload:ident) => {
        #[inline(never)]
        pub(super) fn $read(
            context: &mut ReadContext,
            local_type: u32,
            remote_field: &FieldInfo,
        ) -> Result<$ty, Error> {
            let remote_field_type = &remote_field.field_type;
            if !read_present_ref(context, remote_field_type)? {
                return Ok(<$ty as ForyDefault>::fory_default());
            }
            // The doubled compatible arm is reached only after schema-pair
            // classification accepts a scalar pair. This dispatch only chooses
            // the remote wire payload reader.
            $payload(context, local_type, remote_field_type.type_id)
        }

        #[inline(never)]
        pub(super) fn $read_option(
            context: &mut ReadContext,
            local_type: u32,
            remote_field: &FieldInfo,
        ) -> Result<Option<$ty>, Error> {
            let remote_field_type = &remote_field.field_type;
            if !read_present_ref(context, remote_field_type)? {
                return Ok(None);
            }
            $payload(context, local_type, remote_field_type.type_id).map(Some)
        }
    };
}

scalar_target_reader!(
    read_bool_target,
    read_bool_option_target,
    bool,
    read_bool_payload
);
scalar_target_reader!(
    read_string_target,
    read_string_option_target,
    String,
    read_string_payload
);
scalar_target_reader!(read_i8_target, read_i8_option_target, i8, read_i8_payload);
scalar_target_reader!(
    read_i16_target,
    read_i16_option_target,
    i16,
    read_i16_payload
);
scalar_target_reader!(
    read_i32_target,
    read_i32_option_target,
    i32,
    read_i32_payload
);
scalar_target_reader!(read_u8_target, read_u8_option_target, u8, read_u8_payload);
scalar_target_reader!(
    read_u16_target,
    read_u16_option_target,
    u16,
    read_u16_payload
);
scalar_target_reader!(
    read_u32_target,
    read_u32_option_target,
    u32,
    read_u32_payload
);
scalar_target_reader!(
    read_u64_target,
    read_u64_option_target,
    u64,
    read_u64_payload
);
scalar_target_reader!(
    read_f32_target,
    read_f32_option_target,
    f32,
    read_f32_payload
);
scalar_target_reader!(
    read_f64_target,
    read_f64_option_target,
    f64,
    read_f64_payload
);
scalar_target_reader!(
    read_float16_target,
    read_float16_option_target,
    float16,
    read_float16_payload
);
scalar_target_reader!(
    read_bfloat16_target,
    read_bfloat16_option_target,
    bfloat16,
    read_bfloat16_payload
);
scalar_target_reader!(
    read_decimal_target,
    read_decimal_option_target,
    Decimal,
    read_decimal_payload
);

#[inline(never)]
pub(super) fn read_i64_target(
    context: &mut ReadContext,
    local_type: u32,
    remote_field: &FieldInfo,
) -> Result<i64, Error> {
    let remote_field_type = &remote_field.field_type;
    if !read_present_ref(context, remote_field_type)? {
        return Ok(<i64 as ForyDefault>::fory_default());
    }
    read_i64_payload(context, local_type, remote_field_type.type_id)
}

#[inline(never)]
pub(super) fn read_i64_option_target(
    context: &mut ReadContext,
    local_type: u32,
    remote_field: &FieldInfo,
) -> Result<Option<i64>, Error> {
    let remote_field_type = &remote_field.field_type;
    if !read_present_ref(context, remote_field_type)? {
        return Ok(None);
    }
    read_i64_payload(context, local_type, remote_field_type.type_id).map(Some)
}

#[inline(always)]
fn read_i64_payload(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<i64, Error> {
    match remote_type {
        type_id::BOOL => match context.reader.read_u8()? {
            0 => Ok(0),
            1 => Ok(1),
            _ => Err(conversion_error(
                remote_type,
                local_type,
                "invalid bool payload",
            )),
        },
        type_id::INT8 => Ok(i64::from(context.reader.read_i8()?)),
        type_id::INT16 => Ok(i64::from(context.reader.read_i16()?)),
        type_id::INT32 => Ok(i64::from(context.reader.read_i32()?)),
        type_id::VARINT32 => Ok(i64::from(context.reader.read_var_i32()?)),
        type_id::INT64 => context.reader.read_i64(),
        type_id::VARINT64 => context.reader.read_var_i64(),
        type_id::TAGGED_INT64 => context.reader.read_tagged_i64(),
        type_id::UINT8 => Ok(i64::from(context.reader.read_u8()?)),
        type_id::UINT16 => Ok(i64::from(context.reader.read_u16()?)),
        type_id::UINT32 => Ok(i64::from(context.reader.read_u32()?)),
        type_id::VAR_UINT32 => Ok(i64::from(context.reader.read_var_u32()?)),
        type_id::UINT64 => u64_to_i64(context.reader.read_u64()?, remote_type, local_type),
        type_id::VAR_UINT64 => u64_to_i64(context.reader.read_var_u64()?, remote_type, local_type),
        type_id::TAGGED_UINT64 => {
            u64_to_i64(context.reader.read_tagged_u64()?, remote_type, local_type)
        }
        _ => read_i64_cold(context, local_type, remote_type),
    }
}

#[inline(always)]
fn u64_to_i64(value: u64, remote_type: u32, local_type: u32) -> Result<i64, Error> {
    i64::try_from(value)
        .map_err(|_| conversion_error(remote_type, local_type, "integer value is out of range"))
}

#[cold]
#[inline(never)]
fn read_i64_cold(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<i64, Error> {
    match remote_type {
        type_id::FLOAT16 | type_id::BFLOAT16 | type_id::FLOAT32 | type_id::FLOAT64 => {
            let value = read_float_value(context, remote_type)?;
            float_to_integral_num(value, remote_type, local_type, false)
        }
        type_id::STRING => {
            let value = String::fory_read_data(context)?;
            string_to_integral_num(&value, remote_type, local_type, false)
        }
        type_id::DECIMAL => {
            let value = Decimal::fory_read_data(context)?;
            decimal_to_integral_num(&value, remote_type, local_type, false)
        }
        _ => Err(Error::invalid_data("invalid compatible scalar remote type")),
    }
}

#[inline(always)]
fn read_bool_payload(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<bool, Error> {
    let value = match remote_type {
        type_id::BOOL => {
            return match context.reader.read_u8()? {
                0 => Ok(false),
                1 => Ok(true),
                _ => Err(conversion_error(
                    remote_type,
                    local_type,
                    "invalid bool payload",
                )),
            };
        }
        type_id::INT8
        | type_id::INT16
        | type_id::INT32
        | type_id::VARINT32
        | type_id::INT64
        | type_id::VARINT64
        | type_id::TAGGED_INT64 => read_i64_payload(context, local_type, remote_type)?,
        type_id::UINT8
        | type_id::UINT16
        | type_id::UINT32
        | type_id::VAR_UINT32
        | type_id::UINT64
        | type_id::VAR_UINT64
        | type_id::TAGGED_UINT64 => {
            let unsigned = read_u64_payload(context, local_type, remote_type)?;
            if unsigned == 0 {
                return Ok(false);
            }
            if unsigned == 1 {
                return Ok(true);
            }
            return Err(conversion_error(
                remote_type,
                local_type,
                "numeric value is not 0 or 1",
            ));
        }
        _ => return read_bool_cold(context, local_type, remote_type),
    };
    match value {
        0 => Ok(false),
        1 => Ok(true),
        _ => Err(conversion_error(
            remote_type,
            local_type,
            "numeric value is not 0 or 1",
        )),
    }
}

#[inline(always)]
fn read_string_payload(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<String, Error> {
    match remote_type {
        type_id::BOOL => match context.reader.read_u8()? {
            0 => Ok("false".to_string()),
            1 => Ok("true".to_string()),
            _ => Err(conversion_error(
                remote_type,
                local_type,
                "invalid bool payload",
            )),
        },
        type_id::STRING => String::fory_read_data(context),
        type_id::INT8
        | type_id::INT16
        | type_id::INT32
        | type_id::VARINT32
        | type_id::INT64
        | type_id::VARINT64
        | type_id::TAGGED_INT64 => {
            read_i64_payload(context, local_type, remote_type).map(|value| value.to_string())
        }
        type_id::UINT8
        | type_id::UINT16
        | type_id::UINT32
        | type_id::VAR_UINT32
        | type_id::UINT64
        | type_id::VAR_UINT64
        | type_id::TAGGED_UINT64 => {
            read_u64_payload(context, local_type, remote_type).map(|value| value.to_string())
        }
        _ => read_string_cold(context, local_type, remote_type),
    }
}

macro_rules! signed_payload {
    ($name:ident, $ty:ty) => {
        #[inline(always)]
        fn $name(
            context: &mut ReadContext,
            local_type: u32,
            remote_type: u32,
        ) -> Result<$ty, Error> {
            let value = read_i64_payload(context, local_type, remote_type)?;
            <$ty>::try_from(value).map_err(|_| {
                conversion_error(remote_type, local_type, "integer value is out of range")
            })
        }
    };
}

signed_payload!(read_i8_payload, i8);
signed_payload!(read_i16_payload, i16);
signed_payload!(read_i32_payload, i32);

#[inline(always)]
fn read_u64_payload(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<u64, Error> {
    match remote_type {
        type_id::BOOL => match context.reader.read_u8()? {
            0 => Ok(0),
            1 => Ok(1),
            _ => Err(conversion_error(
                remote_type,
                local_type,
                "invalid bool payload",
            )),
        },
        type_id::INT8 => signed_to_u64(
            i64::from(context.reader.read_i8()?),
            remote_type,
            local_type,
        ),
        type_id::INT16 => signed_to_u64(
            i64::from(context.reader.read_i16()?),
            remote_type,
            local_type,
        ),
        type_id::INT32 => signed_to_u64(
            i64::from(context.reader.read_i32()?),
            remote_type,
            local_type,
        ),
        type_id::VARINT32 => signed_to_u64(
            i64::from(context.reader.read_var_i32()?),
            remote_type,
            local_type,
        ),
        type_id::INT64 => signed_to_u64(context.reader.read_i64()?, remote_type, local_type),
        type_id::VARINT64 => signed_to_u64(context.reader.read_var_i64()?, remote_type, local_type),
        type_id::TAGGED_INT64 => {
            signed_to_u64(context.reader.read_tagged_i64()?, remote_type, local_type)
        }
        type_id::UINT8 => Ok(u64::from(context.reader.read_u8()?)),
        type_id::UINT16 => Ok(u64::from(context.reader.read_u16()?)),
        type_id::UINT32 => Ok(u64::from(context.reader.read_u32()?)),
        type_id::VAR_UINT32 => Ok(u64::from(context.reader.read_var_u32()?)),
        type_id::UINT64 => context.reader.read_u64(),
        type_id::VAR_UINT64 => context.reader.read_var_u64(),
        type_id::TAGGED_UINT64 => context.reader.read_tagged_u64(),
        _ => read_u64_cold(context, local_type, remote_type),
    }
}

#[inline(always)]
fn signed_to_u64(value: i64, remote_type: u32, local_type: u32) -> Result<u64, Error> {
    u64::try_from(value)
        .map_err(|_| conversion_error(remote_type, local_type, "integer value is out of range"))
}

macro_rules! unsigned_payload {
    ($name:ident, $ty:ty) => {
        #[inline(always)]
        fn $name(
            context: &mut ReadContext,
            local_type: u32,
            remote_type: u32,
        ) -> Result<$ty, Error> {
            let value = read_u64_payload(context, local_type, remote_type)?;
            <$ty>::try_from(value).map_err(|_| {
                conversion_error(remote_type, local_type, "integer value is out of range")
            })
        }
    };
}

unsigned_payload!(read_u8_payload, u8);
unsigned_payload!(read_u16_payload, u16);
unsigned_payload!(read_u32_payload, u32);

#[inline(always)]
fn read_f32_payload(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<f32, Error> {
    match remote_type {
        type_id::BOOL => match context.reader.read_u8()? {
            0 => Ok(0.0),
            1 => Ok(1.0),
            _ => Err(conversion_error(
                remote_type,
                local_type,
                "invalid bool payload",
            )),
        },
        type_id::INT8
        | type_id::INT16
        | type_id::INT32
        | type_id::VARINT32
        | type_id::INT64
        | type_id::VARINT64
        | type_id::TAGGED_INT64 => {
            let value = read_i64_payload(context, local_type, remote_type)?;
            signed_integer_to_f32(value, remote_type, local_type)
        }
        type_id::UINT8
        | type_id::UINT16
        | type_id::UINT32
        | type_id::VAR_UINT32
        | type_id::UINT64
        | type_id::VAR_UINT64
        | type_id::TAGGED_UINT64 => {
            let value = read_u64_payload(context, local_type, remote_type)?;
            unsigned_integer_to_f32(value, remote_type, local_type)
        }
        type_id::FLOAT16 => {
            let value = context.reader.read_f16()?;
            checked_float16(value, remote_type, local_type).map(float16::to_f32)
        }
        type_id::BFLOAT16 => {
            let value = context.reader.read_bf16()?;
            checked_bfloat16(value, remote_type, local_type).map(bfloat16::to_f32)
        }
        type_id::FLOAT32 => checked_f32(context.reader.read_f32()?, remote_type, local_type),
        type_id::FLOAT64 => f64_to_f32_exact(context.reader.read_f64()?, remote_type, local_type),
        _ => read_f32_cold(context, local_type, remote_type),
    }
}

#[inline(always)]
fn read_f64_payload(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<f64, Error> {
    match remote_type {
        type_id::BOOL => match context.reader.read_u8()? {
            0 => Ok(0.0),
            1 => Ok(1.0),
            _ => Err(conversion_error(
                remote_type,
                local_type,
                "invalid bool payload",
            )),
        },
        type_id::INT8
        | type_id::INT16
        | type_id::INT32
        | type_id::VARINT32
        | type_id::INT64
        | type_id::VARINT64
        | type_id::TAGGED_INT64 => {
            let value = read_i64_payload(context, local_type, remote_type)?;
            signed_integer_to_f64(value, remote_type, local_type)
        }
        type_id::UINT8
        | type_id::UINT16
        | type_id::UINT32
        | type_id::VAR_UINT32
        | type_id::UINT64
        | type_id::VAR_UINT64
        | type_id::TAGGED_UINT64 => {
            let value = read_u64_payload(context, local_type, remote_type)?;
            unsigned_integer_to_f64(value, remote_type, local_type)
        }
        type_id::FLOAT16 => {
            let value = context.reader.read_f16()?;
            checked_float16(value, remote_type, local_type).map(|value| f64::from(value.to_f32()))
        }
        type_id::BFLOAT16 => {
            let value = context.reader.read_bf16()?;
            checked_bfloat16(value, remote_type, local_type).map(|value| f64::from(value.to_f32()))
        }
        type_id::FLOAT32 => {
            checked_f32(context.reader.read_f32()?, remote_type, local_type).map(f64::from)
        }
        type_id::FLOAT64 => checked_f64(context.reader.read_f64()?, remote_type, local_type),
        _ => read_f64_cold(context, local_type, remote_type),
    }
}

#[inline(always)]
fn read_float16_payload(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<float16, Error> {
    match remote_type {
        type_id::FLOAT16 => checked_float16(context.reader.read_f16()?, remote_type, local_type),
        _ => match remote_type {
            type_id::STRING => {
                let value = String::fory_read_data(context)?;
                string_to_float16_value(&value, remote_type, local_type)
            }
            type_id::DECIMAL => {
                let value = Decimal::fory_read_data(context)?;
                decimal_to_float16(&value, false, remote_type, local_type)
            }
            _ => {
                let value = read_f32_payload(context, local_type, remote_type)?;
                f32_to_float16_exact(value, remote_type, local_type)
            }
        },
    }
}

#[inline(always)]
fn read_bfloat16_payload(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<bfloat16, Error> {
    match remote_type {
        type_id::BFLOAT16 => checked_bfloat16(context.reader.read_bf16()?, remote_type, local_type),
        _ => match remote_type {
            type_id::STRING => {
                let value = String::fory_read_data(context)?;
                string_to_bfloat16_value(&value, remote_type, local_type)
            }
            type_id::DECIMAL => {
                let value = Decimal::fory_read_data(context)?;
                decimal_to_bfloat16(&value, false, remote_type, local_type)
            }
            _ => {
                let value = read_f32_payload(context, local_type, remote_type)?;
                f32_to_bfloat16_exact(value, remote_type, local_type)
            }
        },
    }
}

#[inline(always)]
fn read_decimal_payload(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<Decimal, Error> {
    match remote_type {
        type_id::BOOL => match context.reader.read_u8()? {
            0 => Ok(Decimal::new(BigInt::zero(), 0)),
            1 => Ok(Decimal::new(BigInt::one(), 0)),
            _ => Err(conversion_error(
                remote_type,
                local_type,
                "invalid bool payload",
            )),
        },
        type_id::INT8
        | type_id::INT16
        | type_id::INT32
        | type_id::VARINT32
        | type_id::INT64
        | type_id::VARINT64
        | type_id::TAGGED_INT64 => {
            let value = read_i64_payload(context, local_type, remote_type)?;
            Ok(Decimal::new(BigInt::from(value), 0))
        }
        type_id::UINT8
        | type_id::UINT16
        | type_id::UINT32
        | type_id::VAR_UINT32
        | type_id::UINT64
        | type_id::VAR_UINT64
        | type_id::TAGGED_UINT64 => {
            let value = read_u64_payload(context, local_type, remote_type)?;
            Ok(Decimal::new(BigInt::from(value), 0))
        }
        type_id::FLOAT16 | type_id::BFLOAT16 | type_id::FLOAT32 | type_id::FLOAT64 => {
            let value = read_float_value(context, remote_type)?;
            float_to_decimal_value(value, remote_type, local_type)
        }
        type_id::STRING => {
            let value = String::fory_read_data(context)?;
            string_to_decimal_value(&value, remote_type, local_type).map(|(decimal, _)| decimal)
        }
        type_id::DECIMAL => canonical_decimal(Decimal::fory_read_data(context)?),
        _ => Err(Error::invalid_data("invalid compatible scalar remote type")),
    }
}

#[cold]
#[inline(never)]
fn read_bool_cold(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<bool, Error> {
    match remote_type {
        type_id::FLOAT16 | type_id::BFLOAT16 | type_id::FLOAT32 | type_id::FLOAT64 => {
            let value = read_float_value(context, remote_type)?;
            float_to_bool_value(value, remote_type, local_type)
        }
        type_id::STRING => {
            let value = String::fory_read_data(context)?;
            string_to_bool_value(&value, remote_type, local_type)
        }
        type_id::DECIMAL => {
            let value = Decimal::fory_read_data(context)?;
            decimal_to_bool_value(&value, remote_type, local_type)
        }
        _ => Err(Error::invalid_data("invalid compatible scalar remote type")),
    }
}

#[cold]
#[inline(never)]
fn read_string_cold(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<String, Error> {
    match remote_type {
        type_id::FLOAT16 | type_id::BFLOAT16 | type_id::FLOAT32 | type_id::FLOAT64 => {
            let value = read_float_value(context, remote_type)?;
            float_to_string(value, remote_type, local_type)
        }
        type_id::DECIMAL => {
            let value = canonical_decimal(Decimal::fory_read_data(context)?)?;
            Ok(decimal_to_string(&value))
        }
        _ => Err(Error::invalid_data("invalid compatible scalar remote type")),
    }
}

#[cold]
#[inline(never)]
fn read_u64_cold(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<u64, Error> {
    match remote_type {
        type_id::FLOAT16 | type_id::BFLOAT16 | type_id::FLOAT32 | type_id::FLOAT64 => {
            let value = read_float_value(context, remote_type)?;
            float_to_integral_num(value, remote_type, local_type, true)
        }
        type_id::STRING => {
            let value = String::fory_read_data(context)?;
            string_to_integral_num(&value, remote_type, local_type, true)
        }
        type_id::DECIMAL => {
            let value = Decimal::fory_read_data(context)?;
            decimal_to_integral_num(&value, remote_type, local_type, true)
        }
        _ => Err(Error::invalid_data("invalid compatible scalar remote type")),
    }
}

#[cold]
#[inline(never)]
fn read_f32_cold(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<f32, Error> {
    match remote_type {
        type_id::STRING => {
            let value = String::fory_read_data(context)?;
            string_to_f32_value(&value, remote_type, local_type)
        }
        type_id::DECIMAL => {
            let value = Decimal::fory_read_data(context)?;
            decimal_to_f32(&value, false, remote_type, local_type)
        }
        _ => Err(Error::invalid_data("invalid compatible scalar remote type")),
    }
}

#[cold]
#[inline(never)]
fn read_f64_cold(
    context: &mut ReadContext,
    local_type: u32,
    remote_type: u32,
) -> Result<f64, Error> {
    match remote_type {
        type_id::STRING => {
            let value = String::fory_read_data(context)?;
            string_to_f64_value(&value, remote_type, local_type)
        }
        type_id::DECIMAL => {
            let value = Decimal::fory_read_data(context)?;
            decimal_to_f64(&value, false, remote_type, local_type)
        }
        _ => Err(Error::invalid_data("invalid compatible scalar remote type")),
    }
}

#[inline(always)]
fn read_float_value(context: &mut ReadContext, remote_type: u32) -> Result<FloatValue, Error> {
    match remote_type {
        type_id::FLOAT16 => Ok(FloatValue::F16(context.reader.read_f16()?)),
        type_id::BFLOAT16 => Ok(FloatValue::BF16(context.reader.read_bf16()?)),
        type_id::FLOAT32 => Ok(FloatValue::F32(context.reader.read_f32()?)),
        type_id::FLOAT64 => Ok(FloatValue::F64(context.reader.read_f64()?)),
        _ => Err(Error::invalid_data("invalid compatible scalar remote type")),
    }
}

#[inline(always)]
fn integer_exact_in_binary(value: u64, precision: u32) -> bool {
    if value == 0 {
        return true;
    }
    let bits = u64::BITS - value.leading_zeros();
    bits <= precision || value.trailing_zeros() >= bits - precision
}

#[inline(always)]
fn signed_integer_to_f32(value: i64, remote_type: u32, local_type: u32) -> Result<f32, Error> {
    let magnitude = value.unsigned_abs();
    if integer_exact_in_binary(magnitude, f32::MANTISSA_DIGITS) {
        Ok(value as f32)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "integer value is not exactly representable by target float",
        ))
    }
}

#[inline(always)]
fn unsigned_integer_to_f32(value: u64, remote_type: u32, local_type: u32) -> Result<f32, Error> {
    if integer_exact_in_binary(value, f32::MANTISSA_DIGITS) {
        Ok(value as f32)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "integer value is not exactly representable by target float",
        ))
    }
}

#[inline(always)]
fn signed_integer_to_f64(value: i64, remote_type: u32, local_type: u32) -> Result<f64, Error> {
    let magnitude = value.unsigned_abs();
    if integer_exact_in_binary(magnitude, f64::MANTISSA_DIGITS) {
        Ok(value as f64)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "integer value is not exactly representable by target float",
        ))
    }
}

#[inline(always)]
fn unsigned_integer_to_f64(value: u64, remote_type: u32, local_type: u32) -> Result<f64, Error> {
    if integer_exact_in_binary(value, f64::MANTISSA_DIGITS) {
        Ok(value as f64)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "integer value is not exactly representable by target float",
        ))
    }
}

#[inline(always)]
fn f64_to_f32_exact(value: f64, remote_type: u32, local_type: u32) -> Result<f32, Error> {
    if value.is_nan() {
        return Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ));
    }
    if value.is_infinite() {
        return Ok(if value.is_sign_negative() {
            f32::NEG_INFINITY
        } else {
            f32::INFINITY
        });
    }
    let converted = value as f32;
    if f64::from(converted) == value
        && (converted != 0.0 || converted.is_sign_negative() == value.is_sign_negative())
    {
        Ok(converted)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "float value is not exactly representable by target float",
        ))
    }
}

#[inline(always)]
fn f32_to_float16_exact(value: f32, remote_type: u32, local_type: u32) -> Result<float16, Error> {
    if value.is_nan() {
        return Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ));
    }
    if value.is_infinite() {
        return Ok(if value.is_sign_negative() {
            float16::NEG_INFINITY
        } else {
            float16::INFINITY
        });
    }
    let converted = float16::from_f32(value);
    let roundtrip = converted.to_f32();
    if roundtrip == value
        && (roundtrip != 0.0 || roundtrip.is_sign_negative() == value.is_sign_negative())
    {
        Ok(converted)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "float value is not exactly representable by target float",
        ))
    }
}

#[inline(always)]
fn f32_to_bfloat16_exact(value: f32, remote_type: u32, local_type: u32) -> Result<bfloat16, Error> {
    if value.is_nan() {
        return Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ));
    }
    if value.is_infinite() {
        return Ok(if value.is_sign_negative() {
            bfloat16::NEG_INFINITY
        } else {
            bfloat16::INFINITY
        });
    }
    let converted = bfloat16::from_f32(value);
    let roundtrip = converted.to_f32();
    if roundtrip == value
        && (roundtrip != 0.0 || roundtrip.is_sign_negative() == value.is_sign_negative())
    {
        Ok(converted)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "float value is not exactly representable by target float",
        ))
    }
}

#[inline(always)]
fn checked_f32(value: f32, remote_type: u32, local_type: u32) -> Result<f32, Error> {
    if value.is_nan() {
        Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ))
    } else {
        Ok(value)
    }
}

#[inline(always)]
fn checked_f64(value: f64, remote_type: u32, local_type: u32) -> Result<f64, Error> {
    if value.is_nan() {
        Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ))
    } else {
        Ok(value)
    }
}

#[inline(always)]
fn checked_float16(value: float16, remote_type: u32, local_type: u32) -> Result<float16, Error> {
    if value.is_nan() {
        Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ))
    } else {
        Ok(value)
    }
}

#[inline(always)]
fn checked_bfloat16(value: bfloat16, remote_type: u32, local_type: u32) -> Result<bfloat16, Error> {
    if value.is_nan() {
        Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ))
    } else {
        Ok(value)
    }
}

#[inline(always)]
pub(super) fn scalar_field_types_compatible(local: &FieldType, remote: &FieldType) -> bool {
    crate::meta::compatible_scalar_field_pair(local, remote)
}

#[inline(always)]
fn numeric_type(type_id: u32) -> bool {
    matches!(
        type_id,
        type_id::INT8
            | type_id::INT16
            | type_id::INT32
            | type_id::VARINT32
            | type_id::INT64
            | type_id::VARINT64
            | type_id::TAGGED_INT64
            | type_id::UINT8
            | type_id::UINT16
            | type_id::UINT32
            | type_id::VAR_UINT32
            | type_id::UINT64
            | type_id::VAR_UINT64
            | type_id::TAGGED_UINT64
            | type_id::FLOAT16
            | type_id::BFLOAT16
            | type_id::FLOAT32
            | type_id::FLOAT64
            | type_id::DECIMAL
    )
}

#[inline(always)]
fn read_present_ref(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<bool, Error> {
    match field_ref_mode(remote_field_type) {
        RefMode::None => Ok(true),
        RefMode::NullOnly => {
            let flag = context.reader.read_i8()?;
            match flag {
                value if value == RefFlag::Null as i8 => Ok(false),
                value if value == RefFlag::NotNullValue as i8 => Ok(true),
                _ => Err(Error::invalid_data(format!(
                    "invalid compatible scalar null flag {flag}"
                ))),
            }
        }
        RefMode::Tracking => Err(Error::invalid_data(
            "trackingRef scalar conversion is not supported",
        )),
    }
}

#[inline(always)]
fn read_and_convert(
    context: &mut ReadContext,
    local_type: u32,
    remote_field_type: &FieldType,
) -> Result<Box<dyn Any>, Error> {
    let value = read_scalar_value(context, remote_field_type.type_id)?;
    convert_scalar(value, local_type, remote_field_type.type_id)
}

fn read_scalar_value(context: &mut ReadContext, type_id: u32) -> Result<ScalarValue, Error> {
    let value = match type_id {
        type_id::BOOL => {
            let value = context.reader.read_u8()?;
            match value {
                0 => ScalarValue::Bool(false),
                1 => ScalarValue::Bool(true),
                _ => {
                    return Err(conversion_error(
                        type_id::BOOL,
                        type_id::BOOL,
                        "invalid bool payload",
                    ))
                }
            }
        }
        type_id::INT8 => ScalarValue::Int(BigInt::from(context.reader.read_i8()?)),
        type_id::INT16 => ScalarValue::Int(BigInt::from(context.reader.read_i16()?)),
        type_id::INT32 => ScalarValue::Int(BigInt::from(context.reader.read_i32()?)),
        type_id::VARINT32 => ScalarValue::Int(BigInt::from(context.reader.read_var_i32()?)),
        type_id::INT64 => ScalarValue::Int(BigInt::from(context.reader.read_i64()?)),
        type_id::VARINT64 => ScalarValue::Int(BigInt::from(context.reader.read_var_i64()?)),
        type_id::TAGGED_INT64 => ScalarValue::Int(BigInt::from(context.reader.read_tagged_i64()?)),
        type_id::UINT8 => ScalarValue::Int(BigInt::from(context.reader.read_u8()?)),
        type_id::UINT16 => ScalarValue::Int(BigInt::from(context.reader.read_u16()?)),
        type_id::UINT32 => ScalarValue::Int(BigInt::from(context.reader.read_u32()?)),
        type_id::VAR_UINT32 => ScalarValue::Int(BigInt::from(context.reader.read_var_u32()?)),
        type_id::UINT64 => ScalarValue::Int(BigInt::from(context.reader.read_u64()?)),
        type_id::VAR_UINT64 => ScalarValue::Int(BigInt::from(context.reader.read_var_u64()?)),
        type_id::TAGGED_UINT64 => ScalarValue::Int(BigInt::from(context.reader.read_tagged_u64()?)),
        type_id::FLOAT16 => ScalarValue::Float(FloatValue::F16(context.reader.read_f16()?)),
        type_id::BFLOAT16 => ScalarValue::Float(FloatValue::BF16(context.reader.read_bf16()?)),
        type_id::FLOAT32 => ScalarValue::Float(FloatValue::F32(context.reader.read_f32()?)),
        type_id::FLOAT64 => ScalarValue::Float(FloatValue::F64(context.reader.read_f64()?)),
        type_id::STRING => ScalarValue::String(String::fory_read_data(context)?),
        type_id::DECIMAL => ScalarValue::Decimal(Decimal::fory_read_data(context)?),
        _ => {
            return Err(conversion_error(
                type_id,
                type_id,
                "unsupported scalar type",
            ))
        }
    };
    Ok(value)
}

fn convert_scalar(
    value: ScalarValue,
    local_type: u32,
    remote_type: u32,
) -> Result<Box<dyn Any>, Error> {
    match local_type {
        type_id::BOOL => Box::new(value_to_bool(value, remote_type, local_type)?).into_ok(),
        type_id::STRING => Box::new(value_to_string(value, remote_type, local_type)?).into_ok(),
        _ if numeric_type(local_type) => value_to_number(value, local_type, remote_type),
        _ => Err(conversion_error(
            remote_type,
            local_type,
            "unsupported scalar target",
        )),
    }
}

trait IntoOk {
    fn into_ok(self) -> Result<Box<dyn Any>, Error>;
}

impl<T: Any> IntoOk for Box<T> {
    #[inline(always)]
    fn into_ok(self) -> Result<Box<dyn Any>, Error> {
        Ok(self)
    }
}

fn value_to_bool(value: ScalarValue, remote_type: u32, local_type: u32) -> Result<bool, Error> {
    match value {
        ScalarValue::Bool(value) => Ok(value),
        ScalarValue::String(value) => match value.as_str() {
            "0" | "false" => Ok(false),
            "1" | "true" => Ok(true),
            _ => Err(conversion_error(
                remote_type,
                local_type,
                "string is not an exact bool literal",
            )),
        },
        value => {
            if numeric_zero(&value, remote_type, local_type)? {
                Ok(false)
            } else if numeric_one(&value, remote_type, local_type)? {
                Ok(true)
            } else {
                Err(conversion_error(
                    remote_type,
                    local_type,
                    "numeric value is not 0 or 1",
                ))
            }
        }
    }
}

fn value_to_string(value: ScalarValue, remote_type: u32, local_type: u32) -> Result<String, Error> {
    match value {
        ScalarValue::Bool(value) => Ok(if value { "true" } else { "false" }.to_string()),
        ScalarValue::String(value) => Ok(value),
        ScalarValue::Int(value) => Ok(value.to_string()),
        ScalarValue::Float(value) => float_to_string(value, remote_type, local_type),
        ScalarValue::Decimal(value) => Ok(decimal_to_string(&canonical_decimal(value)?)),
    }
}

fn value_to_number(
    value: ScalarValue,
    local_type: u32,
    remote_type: u32,
) -> Result<Box<dyn Any>, Error> {
    match local_type {
        type_id::INT8 => Box::new(value_to_i8(value, remote_type, local_type)?).into_ok(),
        type_id::INT16 => Box::new(value_to_i16(value, remote_type, local_type)?).into_ok(),
        type_id::INT32 | type_id::VARINT32 => {
            Box::new(value_to_i32(value, remote_type, local_type)?).into_ok()
        }
        type_id::INT64 | type_id::VARINT64 | type_id::TAGGED_INT64 => {
            Box::new(value_to_i64(value, remote_type, local_type)?).into_ok()
        }
        type_id::UINT8 => Box::new(value_to_u8(value, remote_type, local_type)?).into_ok(),
        type_id::UINT16 => Box::new(value_to_u16(value, remote_type, local_type)?).into_ok(),
        type_id::UINT32 | type_id::VAR_UINT32 => {
            Box::new(value_to_u32(value, remote_type, local_type)?).into_ok()
        }
        type_id::UINT64 | type_id::VAR_UINT64 | type_id::TAGGED_UINT64 => {
            Box::new(value_to_u64(value, remote_type, local_type)?).into_ok()
        }
        type_id::FLOAT16 => Box::new(value_to_float16(value, remote_type, local_type)?).into_ok(),
        type_id::BFLOAT16 => Box::new(value_to_bfloat16(value, remote_type, local_type)?).into_ok(),
        type_id::FLOAT32 => Box::new(value_to_f32(value, remote_type, local_type)?).into_ok(),
        type_id::FLOAT64 => Box::new(value_to_f64(value, remote_type, local_type)?).into_ok(),
        type_id::DECIMAL => Box::new(value_to_decimal(value, remote_type, local_type)?).into_ok(),
        _ => Err(conversion_error(
            remote_type,
            local_type,
            "unsupported numeric target",
        )),
    }
}

macro_rules! signed_target {
    ($name:ident, $ty:ty) => {
        fn $name(value: ScalarValue, remote_type: u32, local_type: u32) -> Result<$ty, Error> {
            value_to_integral::<$ty>(value, remote_type, local_type, false)
        }
    };
}

macro_rules! unsigned_target {
    ($name:ident, $ty:ty) => {
        fn $name(value: ScalarValue, remote_type: u32, local_type: u32) -> Result<$ty, Error> {
            value_to_integral::<$ty>(value, remote_type, local_type, true)
        }
    };
}

signed_target!(value_to_i8, i8);
signed_target!(value_to_i16, i16);
signed_target!(value_to_i32, i32);
signed_target!(value_to_i64, i64);
unsigned_target!(value_to_u8, u8);
unsigned_target!(value_to_u16, u16);
unsigned_target!(value_to_u32, u32);
unsigned_target!(value_to_u64, u64);

fn value_to_integral<T>(
    value: ScalarValue,
    remote_type: u32,
    local_type: u32,
    unsigned: bool,
) -> Result<T, Error>
where
    T: FromBigInt,
{
    let (decimal, _) = value_to_decimal_parts(value, remote_type, local_type)?;
    decimal_to_integral_num(&decimal, remote_type, local_type, unsigned)
}

fn string_to_integral_num<T>(
    value: &str,
    remote_type: u32,
    local_type: u32,
    unsigned: bool,
) -> Result<T, Error>
where
    T: FromBigInt,
{
    let (decimal, _) = string_to_decimal_value(value, remote_type, local_type)?;
    decimal_to_integral_num(&decimal, remote_type, local_type, unsigned)
}

fn float_to_integral_num<T>(
    value: FloatValue,
    remote_type: u32,
    local_type: u32,
    unsigned: bool,
) -> Result<T, Error>
where
    T: FromBigInt,
{
    let (decimal, _) = finite_float_decimal(value, remote_type, local_type)?;
    decimal_to_integral_num(&decimal, remote_type, local_type, unsigned)
}

fn decimal_to_integral_num<T>(
    decimal: &Decimal,
    remote_type: u32,
    local_type: u32,
    unsigned: bool,
) -> Result<T, Error>
where
    T: FromBigInt,
{
    let value = decimal_to_integral(decimal, remote_type, local_type)?;
    if unsigned && value.is_negative() {
        return Err(conversion_error(
            remote_type,
            local_type,
            "negative value cannot convert to unsigned integer",
        ));
    }
    T::from_bigint(&value)
        .ok_or_else(|| conversion_error(remote_type, local_type, "integer value is out of range"))
}

fn string_to_bool_value(value: &str, remote_type: u32, local_type: u32) -> Result<bool, Error> {
    match value {
        "0" | "false" => Ok(false),
        "1" | "true" => Ok(true),
        _ => Err(conversion_error(
            remote_type,
            local_type,
            "string is not an exact bool literal",
        )),
    }
}

fn decimal_to_bool_value(
    value: &Decimal,
    remote_type: u32,
    local_type: u32,
) -> Result<bool, Error> {
    if value.unscaled.is_zero() {
        Ok(false)
    } else if decimal_eq(value, &Decimal::new(BigInt::one(), 0)) {
        Ok(true)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "numeric value is not 0 or 1",
        ))
    }
}

fn float_to_bool_value(
    value: FloatValue,
    remote_type: u32,
    local_type: u32,
) -> Result<bool, Error> {
    if float_is_nan(value) || float_is_infinite(value) {
        return Err(conversion_error(
            remote_type,
            local_type,
            "non-finite float is not convertible to bool",
        ));
    }
    if float_is_zero(value) {
        return Ok(false);
    }
    let decimal = canonical_float_decimal(value, remote_type, local_type)?;
    decimal_to_bool_value(&decimal, remote_type, local_type)
}

fn value_to_decimal(
    value: ScalarValue,
    remote_type: u32,
    local_type: u32,
) -> Result<Decimal, Error> {
    let (decimal, _) = value_to_decimal_parts(value, remote_type, local_type)?;
    canonical_decimal(decimal)
}

fn value_to_decimal_parts(
    value: ScalarValue,
    remote_type: u32,
    local_type: u32,
) -> Result<(Decimal, bool), Error> {
    match value {
        ScalarValue::Bool(value) => Ok((
            Decimal::new(BigInt::from(if value { 1 } else { 0 }), 0),
            false,
        )),
        ScalarValue::String(value) => {
            let parsed = parse_number(&value).ok_or_else(|| {
                conversion_error(remote_type, local_type, "invalid numeric literal")
            })?;
            Ok((parsed.decimal, parsed.negative_zero))
        }
        ScalarValue::Int(value) => Ok((Decimal::new(value, 0), false)),
        ScalarValue::Decimal(value) => canonical_decimal(value).map(|value| (value, false)),
        ScalarValue::Float(value) => finite_float_decimal(value, remote_type, local_type),
    }
}

fn string_to_decimal_value(
    value: &str,
    remote_type: u32,
    local_type: u32,
) -> Result<(Decimal, bool), Error> {
    let parsed = parse_number(value)
        .ok_or_else(|| conversion_error(remote_type, local_type, "invalid numeric literal"))?;
    Ok((parsed.decimal, parsed.negative_zero))
}

fn float_to_decimal_value(
    value: FloatValue,
    remote_type: u32,
    local_type: u32,
) -> Result<Decimal, Error> {
    finite_float_decimal(value, remote_type, local_type).map(|(decimal, _)| decimal)
}

fn string_to_f32_value(value: &str, remote_type: u32, local_type: u32) -> Result<f32, Error> {
    let (decimal, negative_zero) = string_to_decimal_value(value, remote_type, local_type)?;
    decimal_to_f32(&decimal, negative_zero, remote_type, local_type)
}

fn string_to_f64_value(value: &str, remote_type: u32, local_type: u32) -> Result<f64, Error> {
    let (decimal, negative_zero) = string_to_decimal_value(value, remote_type, local_type)?;
    decimal_to_f64(&decimal, negative_zero, remote_type, local_type)
}

fn string_to_float16_value(
    value: &str,
    remote_type: u32,
    local_type: u32,
) -> Result<float16, Error> {
    let (decimal, negative_zero) = string_to_decimal_value(value, remote_type, local_type)?;
    decimal_to_float16(&decimal, negative_zero, remote_type, local_type)
}

fn string_to_bfloat16_value(
    value: &str,
    remote_type: u32,
    local_type: u32,
) -> Result<bfloat16, Error> {
    let (decimal, negative_zero) = string_to_decimal_value(value, remote_type, local_type)?;
    decimal_to_bfloat16(&decimal, negative_zero, remote_type, local_type)
}

trait FromBigInt: Any + Sized {
    fn from_bigint(value: &BigInt) -> Option<Self>;
}

macro_rules! impl_from_bigint {
    ($($ty:ty => $method:ident),* $(,)?) => {
        $(
            impl FromBigInt for $ty {
                #[inline(always)]
                fn from_bigint(value: &BigInt) -> Option<Self> {
                    value.$method()
                }
            }
        )*
    };
}

impl_from_bigint!(
    i8 => to_i8,
    i16 => to_i16,
    i32 => to_i32,
    i64 => to_i64,
    u8 => to_u8,
    u16 => to_u16,
    u32 => to_u32,
    u64 => to_u64,
);

fn value_to_f32(value: ScalarValue, remote_type: u32, local_type: u32) -> Result<f32, Error> {
    match value {
        ScalarValue::Float(value) => float_to_f32_value(value, remote_type, local_type),
        value => {
            let (decimal, negative_zero) = value_to_decimal_parts(value, remote_type, local_type)?;
            decimal_to_f32(&decimal, negative_zero, remote_type, local_type)
        }
    }
}

fn decimal_to_f32(
    decimal: &Decimal,
    negative_zero: bool,
    remote_type: u32,
    local_type: u32,
) -> Result<f32, Error> {
    let value = if decimal.unscaled.is_zero() && negative_zero {
        -0.0
    } else {
        decimal_to_string(decimal)
            .parse::<f32>()
            .map_err(|_| conversion_error(remote_type, local_type, "float value is out of range"))?
    };
    if !value.is_finite() {
        return Err(conversion_error(
            remote_type,
            local_type,
            "float value is not finite",
        ));
    }
    let actual = canonical_float_decimal(FloatValue::F32(value), local_type, local_type)?;
    if decimal_eq(&actual, decimal) {
        Ok(value)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "decimal value is not exactly representable by target float",
        ))
    }
}

fn value_to_f64(value: ScalarValue, remote_type: u32, local_type: u32) -> Result<f64, Error> {
    match value {
        ScalarValue::Float(value) => float_to_f64_value(value, remote_type, local_type),
        value => {
            let (decimal, negative_zero) = value_to_decimal_parts(value, remote_type, local_type)?;
            decimal_to_f64(&decimal, negative_zero, remote_type, local_type)
        }
    }
}

fn decimal_to_f64(
    decimal: &Decimal,
    negative_zero: bool,
    remote_type: u32,
    local_type: u32,
) -> Result<f64, Error> {
    let value = if decimal.unscaled.is_zero() && negative_zero {
        -0.0
    } else {
        decimal_to_string(decimal)
            .parse::<f64>()
            .map_err(|_| conversion_error(remote_type, local_type, "float value is out of range"))?
    };
    if !value.is_finite() {
        return Err(conversion_error(
            remote_type,
            local_type,
            "float value is not finite",
        ));
    }
    let actual = canonical_float_decimal(FloatValue::F64(value), local_type, local_type)?;
    if decimal_eq(&actual, decimal) {
        Ok(value)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "decimal value is not exactly representable by target float",
        ))
    }
}

fn value_to_float16(
    value: ScalarValue,
    remote_type: u32,
    local_type: u32,
) -> Result<float16, Error> {
    match value {
        ScalarValue::Float(value) => float_to_float16_value(value, remote_type, local_type),
        value => {
            let (decimal, negative_zero) = value_to_decimal_parts(value, remote_type, local_type)?;
            decimal_to_float16(&decimal, negative_zero, remote_type, local_type)
        }
    }
}

fn decimal_to_float16(
    decimal: &Decimal,
    negative_zero: bool,
    remote_type: u32,
    local_type: u32,
) -> Result<float16, Error> {
    let value = decimal_to_f32(decimal, negative_zero, remote_type, type_id::FLOAT32)?;
    let value = float16::from_f32(value);
    if !value.is_finite() {
        return Err(conversion_error(
            remote_type,
            local_type,
            "float value is not finite",
        ));
    }
    let actual = canonical_float_decimal(FloatValue::F16(value), local_type, local_type)?;
    if decimal_eq(&actual, decimal) {
        Ok(value)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "decimal value is not exactly representable by target float",
        ))
    }
}

fn value_to_bfloat16(
    value: ScalarValue,
    remote_type: u32,
    local_type: u32,
) -> Result<bfloat16, Error> {
    match value {
        ScalarValue::Float(value) => float_to_bfloat16_value(value, remote_type, local_type),
        value => {
            let (decimal, negative_zero) = value_to_decimal_parts(value, remote_type, local_type)?;
            decimal_to_bfloat16(&decimal, negative_zero, remote_type, local_type)
        }
    }
}

fn decimal_to_bfloat16(
    decimal: &Decimal,
    negative_zero: bool,
    remote_type: u32,
    local_type: u32,
) -> Result<bfloat16, Error> {
    let value = decimal_to_f32(decimal, negative_zero, remote_type, type_id::FLOAT32)?;
    let value = bfloat16::from_f32(value);
    if !value.is_finite() {
        return Err(conversion_error(
            remote_type,
            local_type,
            "float value is not finite",
        ));
    }
    let actual = canonical_float_decimal(FloatValue::BF16(value), local_type, local_type)?;
    if decimal_eq(&actual, decimal) {
        Ok(value)
    } else {
        Err(conversion_error(
            remote_type,
            local_type,
            "decimal value is not exactly representable by target float",
        ))
    }
}

fn finite_float_decimal(
    value: FloatValue,
    remote_type: u32,
    local_type: u32,
) -> Result<(Decimal, bool), Error> {
    if float_is_nan(value) {
        return Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ));
    }
    if float_is_infinite(value) {
        return Err(conversion_error(
            remote_type,
            local_type,
            "infinity is only convertible to floating targets",
        ));
    }
    let negative_zero = float_is_negative_zero(value);
    canonical_float_decimal(value, remote_type, local_type).map(|decimal| (decimal, negative_zero))
}

fn float_to_f32_value(value: FloatValue, remote_type: u32, local_type: u32) -> Result<f32, Error> {
    if float_is_nan(value) {
        return Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ));
    }
    if float_is_infinite(value) {
        return Ok(if float_sign_negative(value) {
            f32::NEG_INFINITY
        } else {
            f32::INFINITY
        });
    }
    let negative_zero = float_is_negative_zero(value);
    let decimal = canonical_float_decimal(value, remote_type, local_type)?;
    decimal_to_f32(&decimal, negative_zero, remote_type, local_type)
}

fn float_to_f64_value(value: FloatValue, remote_type: u32, local_type: u32) -> Result<f64, Error> {
    if float_is_nan(value) {
        return Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ));
    }
    if float_is_infinite(value) {
        return Ok(if float_sign_negative(value) {
            f64::NEG_INFINITY
        } else {
            f64::INFINITY
        });
    }
    let negative_zero = float_is_negative_zero(value);
    let decimal = canonical_float_decimal(value, remote_type, local_type)?;
    decimal_to_f64(&decimal, negative_zero, remote_type, local_type)
}

fn float_to_float16_value(
    value: FloatValue,
    remote_type: u32,
    local_type: u32,
) -> Result<float16, Error> {
    if float_is_nan(value) {
        return Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ));
    }
    if float_is_infinite(value) {
        return Ok(if float_sign_negative(value) {
            float16::NEG_INFINITY
        } else {
            float16::INFINITY
        });
    }
    let negative_zero = float_is_negative_zero(value);
    let decimal = canonical_float_decimal(value, remote_type, local_type)?;
    decimal_to_float16(&decimal, negative_zero, remote_type, local_type)
}

fn float_to_bfloat16_value(
    value: FloatValue,
    remote_type: u32,
    local_type: u32,
) -> Result<bfloat16, Error> {
    if float_is_nan(value) {
        return Err(conversion_error(
            remote_type,
            local_type,
            "NaN is not convertible",
        ));
    }
    if float_is_infinite(value) {
        return Ok(if float_sign_negative(value) {
            bfloat16::NEG_INFINITY
        } else {
            bfloat16::INFINITY
        });
    }
    let negative_zero = float_is_negative_zero(value);
    let decimal = canonical_float_decimal(value, remote_type, local_type)?;
    decimal_to_bfloat16(&decimal, negative_zero, remote_type, local_type)
}

fn float_to_string(value: FloatValue, remote_type: u32, local_type: u32) -> Result<String, Error> {
    if !float_is_finite(value) {
        return Err(conversion_error(
            remote_type,
            local_type,
            "numeric string conversion requires a finite float",
        ));
    }
    if float_is_zero(value) {
        return Ok(if float_sign_negative(value) {
            "-0.0".to_string()
        } else {
            "0.0".to_string()
        });
    }
    let decimal = canonical_float_decimal(value, remote_type, local_type)?;
    let mut text = decimal_to_string(&decimal);
    if !text.contains('.') {
        text.push_str(".0");
    }
    Ok(text)
}

fn numeric_zero(value: &ScalarValue, remote_type: u32, local_type: u32) -> Result<bool, Error> {
    match value {
        ScalarValue::Int(value) => Ok(value.is_zero()),
        ScalarValue::Decimal(value) => Ok(value.unscaled.is_zero()),
        ScalarValue::Float(value) => {
            if float_is_nan(*value) || float_is_infinite(*value) {
                return Err(conversion_error(
                    remote_type,
                    local_type,
                    "non-finite float is not convertible to bool",
                ));
            }
            Ok(float_is_zero(*value))
        }
        _ => Ok(false),
    }
}

fn numeric_one(value: &ScalarValue, remote_type: u32, local_type: u32) -> Result<bool, Error> {
    match value {
        ScalarValue::Int(value) => Ok(value == &BigInt::one()),
        ScalarValue::Decimal(value) => Ok(decimal_eq(value, &Decimal::new(BigInt::one(), 0))),
        ScalarValue::Float(value) => {
            if float_is_nan(*value) || float_is_infinite(*value) {
                return Err(conversion_error(
                    remote_type,
                    local_type,
                    "non-finite float is not convertible to bool",
                ));
            }
            let decimal = canonical_float_decimal(*value, remote_type, local_type)?;
            Ok(decimal_eq(&decimal, &Decimal::new(BigInt::one(), 0)))
        }
        _ => Ok(false),
    }
}

fn parse_number(input: &str) -> Option<ParsedNumber> {
    let bytes = input.as_bytes();
    if bytes.is_empty() || bytes.len() > MAX_COMPATIBLE_NUMERIC_TEXT_LEN {
        return None;
    }
    let mut pos = 0;
    let negative = if bytes[pos] == b'-' {
        pos += 1;
        if pos == bytes.len() {
            return None;
        }
        true
    } else {
        false
    };
    let int_start = pos;
    let mut significant_digits = 0usize;
    let mut seen_nonzero = false;
    if bytes[pos] == b'0' {
        count_significant_digit(bytes[pos], &mut seen_nonzero, &mut significant_digits);
        pos += 1;
        if pos < bytes.len() && bytes[pos].is_ascii_digit() {
            return None;
        }
    } else if bytes[pos].is_ascii_digit() && bytes[pos] != b'0' {
        count_significant_digit(bytes[pos], &mut seen_nonzero, &mut significant_digits);
        pos += 1;
        while pos < bytes.len() && bytes[pos].is_ascii_digit() {
            count_significant_digit(bytes[pos], &mut seen_nonzero, &mut significant_digits);
            pos += 1;
        }
    } else {
        return None;
    }
    let int_end = pos;
    let mut frac_start = pos;
    let mut frac_end = pos;
    if pos < bytes.len() && bytes[pos] == b'.' {
        pos += 1;
        frac_start = pos;
        while pos < bytes.len() && bytes[pos].is_ascii_digit() {
            count_significant_digit(bytes[pos], &mut seen_nonzero, &mut significant_digits);
            pos += 1;
        }
        if pos == frac_start {
            return None;
        }
        frac_end = pos;
    }
    let mut exponent = 0i64;
    if pos < bytes.len() && (bytes[pos] == b'e' || bytes[pos] == b'E') {
        pos += 1;
        let exp_negative = if pos < bytes.len() && bytes[pos] == b'-' {
            pos += 1;
            true
        } else {
            false
        };
        if pos == bytes.len() {
            return None;
        }
        if bytes[pos] == b'0' {
            pos += 1;
            if pos < bytes.len() && bytes[pos].is_ascii_digit() {
                return None;
            }
        } else if bytes[pos].is_ascii_digit() && bytes[pos] != b'0' {
            while pos < bytes.len() && bytes[pos].is_ascii_digit() {
                exponent = exponent
                    .checked_mul(10)?
                    .checked_add((bytes[pos] - b'0') as i64)?;
                if exponent > i64::from(MAX_COMPATIBLE_DECIMAL_DIGITS) {
                    return None;
                }
                pos += 1;
            }
        } else {
            return None;
        }
        if exp_negative {
            exponent = -exponent;
        }
    }
    if pos != bytes.len() {
        return None;
    }
    if significant_digits > MAX_COMPATIBLE_DECIMAL_DIGITS as usize {
        return None;
    }
    let scale = (frac_end - frac_start) as i64 - exponent;
    if !compatible_decimal_shape(significant_digits, scale) {
        return None;
    }
    let mut digits = String::with_capacity(int_end - int_start + frac_end - frac_start);
    digits.push_str(&input[int_start..int_end]);
    digits.push_str(&input[frac_start..frac_end]);
    let mut unscaled = BigInt::parse_bytes(digits.as_bytes(), 10)?;
    if negative {
        unscaled = -unscaled;
    }
    let decimal = normalize_decimal_parts(unscaled, scale)?;
    Some(ParsedNumber {
        negative_zero: negative && decimal.unscaled.is_zero(),
        decimal,
    })
}

fn count_significant_digit(byte: u8, seen_nonzero: &mut bool, significant_digits: &mut usize) {
    if byte != b'0' || *seen_nonzero {
        *seen_nonzero = true;
        *significant_digits += 1;
    }
}

fn compatible_decimal_shape(significant_digits: usize, scale: i64) -> bool {
    if scale > i64::from(MAX_COMPATIBLE_DECIMAL_DIGITS) {
        return false;
    }
    if scale < 0 && significant_digits as i64 + (-scale) > i64::from(MAX_COMPATIBLE_DECIMAL_DIGITS)
    {
        return false;
    }
    true
}

fn normalize_decimal_parts(mut unscaled: BigInt, scale: i64) -> Option<Decimal> {
    if scale < 0 {
        let extra_digits = -scale;
        if extra_digits > MAX_COMPATIBLE_DECIMAL_DIGITS as i64
            || decimal_digit_count(&unscaled) as i64 + extra_digits
                > MAX_COMPATIBLE_DECIMAL_DIGITS as i64
        {
            return None;
        }
        let factor = pow10(extra_digits.try_into().ok()?)?;
        unscaled *= factor;
        return Some(Decimal::new(unscaled, 0));
    }
    let mut scale = scale;
    canonicalize_decimal_i64(&mut unscaled, &mut scale);
    if scale > i64::from(MAX_COMPATIBLE_DECIMAL_DIGITS)
        || decimal_digit_count(&unscaled) > MAX_COMPATIBLE_DECIMAL_DIGITS as usize
    {
        return None;
    }
    Some(Decimal::new(unscaled, scale as i32))
}

fn canonical_decimal(mut decimal: Decimal) -> Result<Decimal, Error> {
    if decimal.unscaled.is_zero() {
        decimal.scale = 0;
        return Ok(decimal);
    }
    if decimal.scale < 0 {
        let extra_digits = -i64::from(decimal.scale);
        if extra_digits > i64::from(MAX_COMPATIBLE_DECIMAL_DIGITS)
            || decimal_digit_count(&decimal.unscaled) as i64 + extra_digits
                > i64::from(MAX_COMPATIBLE_DECIMAL_DIGITS)
        {
            return Err(conversion_error(
                type_id::DECIMAL,
                type_id::DECIMAL,
                "converted decimal exceeds compatible conversion bounds",
            ));
        }
        let factor = pow10(extra_digits as u32).ok_or_else(|| {
            conversion_error(
                type_id::DECIMAL,
                type_id::DECIMAL,
                "converted decimal exceeds compatible conversion bounds",
            )
        })?;
        decimal.unscaled *= factor;
        decimal.scale = 0;
    }
    canonicalize_decimal(&mut decimal.unscaled, &mut decimal.scale);
    if !compatible_decimal_bounds(&decimal.unscaled, decimal.scale) {
        return Err(conversion_error(
            type_id::DECIMAL,
            type_id::DECIMAL,
            "converted decimal exceeds compatible conversion bounds",
        ));
    }
    Ok(decimal)
}

fn canonicalize_decimal(unscaled: &mut BigInt, scale: &mut i32) {
    if unscaled.is_zero() {
        *scale = 0;
        return;
    }
    let ten = BigInt::from(10);
    while *scale > 0 && (&*unscaled % &ten).is_zero() {
        *unscaled /= &ten;
        *scale -= 1;
    }
}

fn canonicalize_decimal_i64(unscaled: &mut BigInt, scale: &mut i64) {
    if unscaled.is_zero() {
        *scale = 0;
        return;
    }
    let ten = BigInt::from(10);
    while *scale > 0 && (&*unscaled % &ten).is_zero() {
        *unscaled /= &ten;
        *scale -= 1;
    }
}

fn decimal_to_integral(
    decimal: &Decimal,
    remote_type: u32,
    local_type: u32,
) -> Result<BigInt, Error> {
    let decimal = canonical_decimal(decimal.clone())?;
    if decimal.scale == 0 {
        return Ok(decimal.unscaled);
    }
    let divisor = pow10(decimal.scale as u32).ok_or_else(|| {
        conversion_error(
            remote_type,
            local_type,
            "converted decimal exceeds compatible conversion bounds",
        )
    })?;
    let remainder = &decimal.unscaled % &divisor;
    if !remainder.is_zero() {
        return Err(conversion_error(
            remote_type,
            local_type,
            "decimal value is not integral",
        ));
    }
    Ok(decimal.unscaled / divisor)
}

fn decimal_eq(left: &Decimal, right: &Decimal) -> bool {
    match (
        canonical_decimal(left.clone()),
        canonical_decimal(right.clone()),
    ) {
        (Ok(left), Ok(right)) => left == right,
        _ => false,
    }
}

fn decimal_to_string(decimal: &Decimal) -> String {
    let decimal = canonical_decimal(decimal.clone()).expect("canonical decimal");
    if decimal.unscaled.is_zero() {
        return "0".to_string();
    }
    if decimal.scale == 0 {
        return decimal.unscaled.to_string();
    }
    let negative = decimal.unscaled.is_negative();
    let digits = decimal.unscaled.abs().to_string();
    let scale = decimal.scale as usize;
    let mut out = String::new();
    if negative {
        out.push('-');
    }
    if digits.len() > scale {
        let point = digits.len() - scale;
        out.push_str(&digits[..point]);
        out.push('.');
        out.push_str(&digits[point..]);
    } else {
        out.push_str("0.");
        for _ in 0..(scale - digits.len()) {
            out.push('0');
        }
        out.push_str(&digits);
    }
    out
}

fn canonical_float_decimal(
    value: FloatValue,
    remote_type: u32,
    local_type: u32,
) -> Result<Decimal, Error> {
    let (negative, mantissa, exp2) = float_parts(value)
        .ok_or_else(|| conversion_error(remote_type, local_type, "non-finite float"))?;
    if mantissa == 0 {
        return Ok(Decimal::new(BigInt::zero(), 0));
    }
    let mut unscaled = BigInt::from(mantissa);
    let mut scale = 0i32;
    if exp2 >= 0 {
        unscaled <<= exp2 as usize;
    } else {
        let factor = pow5((-exp2) as u32).ok_or_else(|| {
            conversion_error(
                remote_type,
                local_type,
                "float decimal expansion is too large",
            )
        })?;
        unscaled *= factor;
        scale = -exp2;
    }
    if negative {
        unscaled = -unscaled;
    }
    canonical_decimal(Decimal::new(unscaled, scale))
}

fn float_parts(value: FloatValue) -> Option<(bool, u64, i32)> {
    match value {
        FloatValue::F16(value) => binary_float_parts(value.to_f32().to_bits() as u64, 8, 23, 127),
        FloatValue::BF16(value) => binary_float_parts(value.to_f32().to_bits() as u64, 8, 23, 127),
        FloatValue::F32(value) => binary_float_parts(value.to_bits() as u64, 8, 23, 127),
        FloatValue::F64(value) => binary_float_parts(value.to_bits(), 11, 52, 1023),
    }
}

fn binary_float_parts(
    bits: u64,
    exp_bits: u32,
    mant_bits: u32,
    bias: i32,
) -> Option<(bool, u64, i32)> {
    let sign = (bits >> (exp_bits + mant_bits)) != 0;
    let exp_mask = (1u64 << exp_bits) - 1;
    let mant_mask = (1u64 << mant_bits) - 1;
    let exp = ((bits >> mant_bits) & exp_mask) as i32;
    let mant = bits & mant_mask;
    if exp == exp_mask as i32 {
        return None;
    }
    if exp == 0 {
        if mant == 0 {
            Some((sign, 0, 0))
        } else {
            Some((sign, mant, 1 - bias - mant_bits as i32))
        }
    } else {
        Some((
            sign,
            (1u64 << mant_bits) | mant,
            exp - bias - mant_bits as i32,
        ))
    }
}

fn float_is_zero(value: FloatValue) -> bool {
    match value {
        FloatValue::F16(value) => value.is_zero(),
        FloatValue::BF16(value) => value.is_zero(),
        FloatValue::F32(value) => value == 0.0,
        FloatValue::F64(value) => value == 0.0,
    }
}

fn float_is_negative_zero(value: FloatValue) -> bool {
    float_is_zero(value) && float_sign_negative(value)
}

fn float_sign_negative(value: FloatValue) -> bool {
    match value {
        FloatValue::F16(value) => value.is_sign_negative(),
        FloatValue::BF16(value) => value.is_sign_negative(),
        FloatValue::F32(value) => value.is_sign_negative(),
        FloatValue::F64(value) => value.is_sign_negative(),
    }
}

fn float_is_nan(value: FloatValue) -> bool {
    match value {
        FloatValue::F16(value) => value.is_nan(),
        FloatValue::BF16(value) => value.is_nan(),
        FloatValue::F32(value) => value.is_nan(),
        FloatValue::F64(value) => value.is_nan(),
    }
}

fn float_is_infinite(value: FloatValue) -> bool {
    match value {
        FloatValue::F16(value) => value.is_infinite(),
        FloatValue::BF16(value) => value.is_infinite(),
        FloatValue::F32(value) => value.is_infinite(),
        FloatValue::F64(value) => value.is_infinite(),
    }
}

fn float_is_finite(value: FloatValue) -> bool {
    match value {
        FloatValue::F16(value) => value.is_finite(),
        FloatValue::BF16(value) => value.is_finite(),
        FloatValue::F32(value) => value.is_finite(),
        FloatValue::F64(value) => value.is_finite(),
    }
}

fn pow10(exp: u32) -> Option<BigInt> {
    if exp > MAX_COMPATIBLE_DECIMAL_DIGITS as u32 {
        return None;
    }
    Some(BigInt::from(10).pow(exp))
}

fn pow5(exp: u32) -> Option<BigInt> {
    if exp > MAX_COMPATIBLE_DECIMAL_DIGITS as u32 {
        return None;
    }
    Some(BigInt::from(5).pow(exp))
}

fn compatible_decimal_bounds(unscaled: &BigInt, scale: i32) -> bool {
    scale <= MAX_COMPATIBLE_DECIMAL_DIGITS
        && decimal_digit_count(unscaled) <= MAX_COMPATIBLE_DECIMAL_DIGITS as usize
}

fn decimal_digit_count(value: &BigInt) -> usize {
    let magnitude = value.abs();
    if magnitude >= BigInt::from(10).pow(MAX_COMPATIBLE_DECIMAL_DIGITS as u32) {
        return MAX_COMPATIBLE_DECIMAL_DIGITS as usize + 1;
    }
    magnitude.to_string().len()
}

fn boxed_to_value<T: 'static>(value: Box<dyn Any>) -> Result<T, Error> {
    value
        .downcast::<T>()
        .map(|value| *value)
        .map_err(|_| Error::invalid_data("compatible scalar conversion produced wrong target type"))
}

fn conversion_error(remote_type: u32, local_type: u32, detail: &str) -> Error {
    Error::invalid_data(format!(
        "compatible scalar conversion from remote type {remote_type} to local type {local_type} failed: {detail}"
    ))
}
