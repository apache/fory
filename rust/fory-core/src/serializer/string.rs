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
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::mem;

#[allow(dead_code)]
enum StrEncoding {
    Latin1 = 0,
    Utf16 = 1,
    Utf8 = 2,
}

impl Serializer for String {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        let bitor = (self.len() as i32 as u64) << 2 | StrEncoding::Utf8 as u64;
        context.writer.write_var_uint36_small(bitor);
        context.writer.write_utf8_string(self);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        // xlang mode: read encoding header and decode accordingly
        let bitor = context.reader.read_varuint36small()?;
        let len = bitor >> 2;
        let encoding = bitor & 0b11;
        let len_usize = usize::try_from(len).map_err(|_| {
            Error::invalid_data(format!(
                "string length {} overflows usize on this platform",
                len
            ))
        })?;
        let byte_len = match encoding {
            1 => len_usize.saturating_mul(2),
            _ => len_usize,
        };
        let remaining = context
            .reader
            .bf
            .len()
            .saturating_sub(context.reader.cursor);
        if byte_len > remaining {
            return Err(Error::invalid_data(format!(
                "string byte length {} exceeds buffer remaining {}",
                byte_len, remaining
            )));
        }
        context.check_string_bytes(byte_len)?;
        let s = match encoding {
            0 => context.reader.read_latin1_string(len as usize),
            1 => context.reader.read_utf16_string(len as usize),
            2 => context.reader.read_utf8_string(len as usize),
            _ => {
                return Err(Error::encoding_error(format!(
                    "wrong encoding value: {}",
                    encoding
                )))
            }
        }?;
        Ok(s)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::STRING)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::STRING)
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        TypeId::STRING
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(TypeId::STRING as u8);
        Ok(())
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

impl ForyDefault for String {
    #[inline(always)]
    fn fory_default() -> Self {
        String::new()
    }
}
