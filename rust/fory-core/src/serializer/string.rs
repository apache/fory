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

use crate::buffer::Writer;
use crate::error::Error;
use crate::fory::Fory;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::Serializer;
use crate::types::{ForyGeneralList, TypeId};
use std::mem;

enum StrEncoding {
    Latin1 = 0,
    Utf16 = 1,
    Utf8 = 2,
}

fn best_coder(s: &str) -> StrEncoding {
    let chars: Vec<char> = s.chars().collect();
    let num_chars = chars.len();
    if num_chars == 0 {
        return StrEncoding::Latin1;
    }

    let sample_num = num_chars.min(64);
    let vectorized_len = sample_num / 4;
    let vectorized_chars = vectorized_len * 4;

    let mut ascii_count = 0;
    let mut latin1_count = 0;

    for i in 0..vectorized_len {
        let base = i * 4;
        for j in 0..4 {
            let c = chars[base + j] as u32;
            if c <= 0x7F {
                ascii_count += 1;
                latin1_count += 1;
            } else if c <= 0xFF {
                latin1_count += 1;
            }
        }
    }

    for &c in chars.iter().take(sample_num).skip(vectorized_chars) {
        let c = c as u32;
        if c <= 0x7F {
            ascii_count += 1;
            latin1_count += 1;
        } else if c <= 0xFF {
            latin1_count += 1;
        }
    }

    if latin1_count == num_chars || latin1_count == sample_num {
        StrEncoding::Latin1
    } else if (ascii_count as f64) >= sample_num as f64 * 0.5 {
        StrEncoding::Utf8
    } else {
        StrEncoding::Utf16
    }
}

impl Serializer for String {
    fn reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn write(&self, context: &mut WriteContext) {
        let encoding = best_coder(self);
        let mut buf = Writer::default();
        match encoding {
            StrEncoding::Latin1 => {
                let len = buf.latin1_string(self);
                let bitor = (len as u64) << 2 | StrEncoding::Latin1 as u64;
                context.writer.var_uint36_small(bitor);
            }
            StrEncoding::Utf16 => {
                let len = buf.utf16_string(self);
                let bitor = (len as u64) << 2 | StrEncoding::Utf16 as u64;
                context.writer.var_uint36_small(bitor);
            }
            StrEncoding::Utf8 => {
                let len = buf.utf8_string(self);
                let bitor = (len as u64) << 2 | StrEncoding::Utf8 as u64;
                context.writer.var_uint36_small(bitor);
            }
        }
        context.writer.bytes(buf.dump().as_slice());
    }

    fn read(context: &mut ReadContext) -> Result<Self, Error> {
        let bitor = context.reader.var_uint36_small();
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
