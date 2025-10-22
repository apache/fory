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
use crate::meta::get_latin1_length;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::util::read_basic_type_info;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::mem;

enum StrEncoding {
    Latin1 = 0,
    Utf16 = 1,
    Utf8 = 2,
}

impl Serializer for String {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        if !context.is_xlang() {
            // Fast path: non-xlang mode always uses UTF-8 without encoding header
            context.writer.write_varuint32(self.len() as u32);
            context.writer.write_utf8_string(self);
            return Ok(());
        }

        // xlang mode: use encoding header for optimal format selection
        let mut len = get_latin1_length(self);
        if len >= 0 {
            let bitor = (len as u64) << 2 | StrEncoding::Latin1 as u64;
            context.writer.write_varuint36_small(bitor);
            context.writer.write_latin1_string(self);
        } else if context.is_compress_string() {
            // todo: support `writeNumUtf16BytesForUtf8Encoding` like in java
            len = self.len() as i32;
            let bitor = (len as u64) << 2 | StrEncoding::Utf8 as u64;
            context.writer.write_varuint36_small(bitor);
            context.writer.write_utf8_string(self);
        } else {
            let utf16: Vec<u16> = self.encode_utf16().collect();
            let bitor = (utf16.len() as u64 * 2) << 2 | StrEncoding::Utf16 as u64;
            context.writer.write_varuint36_small(bitor);
            context.writer.write_utf16_bytes(&utf16);
        }
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        if !context.is_xlang() {
            // Fast path: non-xlang mode always uses UTF-8 without encoding header
            let len = context.reader.read_varuint32()? as usize;
            return context.reader.read_utf8_string(len);
        }

        // xlang mode: read encoding header and decode accordingly
        let bitor = context.reader.read_varuint36small()?;
        let len = bitor >> 2;
        let encoding = bitor & 0b11;
        let encoding = match encoding {
            0 => StrEncoding::Latin1,
            1 => StrEncoding::Utf16,
            2 => StrEncoding::Utf8,
            _ => {
                return Err(Error::encoding_error(format!(
                    "wrong encoding value: {}",
                    encoding
                )))
            }
        };
        let s = match encoding {
            StrEncoding::Latin1 => context.reader.read_latin1_string(len as usize),
            StrEncoding::Utf16 => context.reader.read_utf16_string(len as usize),
            StrEncoding::Utf8 => context.reader.read_utf8_string(len as usize),
        }?;
        Ok(s)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::STRING as u32)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::STRING as u32)
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
        context.writer.write_varuint32(TypeId::STRING as u32);
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
