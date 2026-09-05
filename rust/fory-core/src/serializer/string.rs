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
use std::convert::TryFrom;
use std::sync::Arc;

#[allow(dead_code)]
enum StrEncoding {
    Latin1 = 0,
    Utf16 = 1,
    Utf8 = 2,
}

#[inline(always)]
pub(super) fn checked_string_len(len: u64) -> Result<usize, Error> {
    match usize::try_from(len) {
        Ok(len) => Ok(len),
        Err(_) => Err(string_len_overflow(len)),
    }
}

#[cold]
#[inline(never)]
fn string_len_overflow(len: u64) -> Error {
    Error::invalid_data(format!(
        "string byte length {len} exceeds the platform address space"
    ))
}

impl Serializer for String {
    type Target = Self;

    const READ_DATA_ALWAYS_ADVANCES: bool = true;

    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        let header = (value.len() as i32 as u64) << 2 | StrEncoding::Utf8 as u64;
        context.writer.write_var_u36_small(header);
        context.writer.write_utf8_string(value);
        Ok(())
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let header = context.reader.read_var_u36_small()?;
        let len = checked_string_len(header >> 2)?;
        match header & 0b11 {
            0 => context.reader.read_latin1_string(len),
            1 => context.reader.read_utf16_string(len),
            2 if context.is_check_string_read() => context.reader.read_utf8_string(len),
            // SAFETY: Disabling string checks is an explicit trusted-input contract. The default
            // configuration validates UTF-8 before constructing a String.
            2 => unsafe { context.reader.read_utf8_string_unchecked(len) },
            encoding => Err(Error::encoding_error(format!(
                "wrong encoding value: {}",
                encoding
            ))),
        }
    }

    #[inline(always)]
    fn default_value(_: &mut ReadContext) -> Result<Self, Error> {
        Ok(String::new())
    }

    #[inline(always)]
    fn read_arc_any(
        context: &mut ReadContext,
    ) -> Result<Arc<dyn std::any::Any + Send + Sync>, Error> {
        Ok(Arc::new(Self::read_data(context)?))
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        std::mem::size_of::<i32>()
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::STRING
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(TypeId::STRING as u8);
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checked_length_range() {
        assert_eq!(checked_string_len(17).unwrap(), 17);

        let beyond_u32 = u64::from(u32::MAX) + 1;
        if usize::BITS > u32::BITS {
            assert_eq!(checked_string_len(beyond_u32).unwrap() as u64, beyond_u32);
        } else {
            assert!(checked_string_len(beyond_u32).is_err());
        }
    }
}
