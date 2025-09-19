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
use crate::fory::Fory;
use crate::meta::get_latin1_length;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::Serializer;
use crate::types::{ForyGeneralList, Mode, TypeId};
use std::mem;

enum StrEncoding {
    Latin1 = 0,
    Utf16 = 1,
    Utf8 = 2,
}

impl Serializer for String {
    fn reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn write(&self, context: &mut WriteContext, is_field: bool) {
        if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
            context.writer.var_uint32(TypeId::STRING as u32);
        }
        let mut len = get_latin1_length(self);
        if len >= 0 {
            let bitor = (len as u64) << 2 | StrEncoding::Latin1 as u64;
            context.writer.var_uint36_small(bitor);
            context.writer.latin1_string(self);
        } else {
            len = self.len() as i32;
            let bitor = (len as u64) << 2 | StrEncoding::Utf8 as u64;
            context.writer.var_uint36_small(bitor);
            context.writer.utf8_string(self);
        }
        // println!("write after: {:?}", context.writer.dump());
    }

    fn read(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
            let remote_type_id = context.reader.var_uint32();
            assert_eq!(remote_type_id, TypeId::STRING as u32);
        }
        // println!("read before: {:?}", context.reader.slice_after_cursor());
        let bitor = context.reader.var_uint36_small();
        // println!("read bitor:{:?}", bitor);
        let len = bitor >> 2;
        let encoding = bitor & 0b11;
        let encoding = match encoding {
            0 => StrEncoding::Latin1,
            1 => StrEncoding::Utf16,
            2 => StrEncoding::Utf8,
            _ => {
                panic!("wrong encoding value: {}", encoding);
            }
        };
        let s = match encoding {
            StrEncoding::Latin1 => context.reader.latin1_string(len as usize),
            StrEncoding::Utf16 => context.reader.utf16_string(len as usize),
            StrEncoding::Utf8 => context.reader.utf8_string(len as usize),
        };
        Ok(s)
    }

    fn get_type_id(_fory: &Fory) -> u32 {
        TypeId::STRING as u32
    }
}

impl ForyGeneralList for String {}
