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
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::Serializer;
use crate::types::{ForyGeneralList, Mode, TypeId, SIZE_OF_REF_AND_TYPE};
use std::collections::HashMap;
use std::mem;

const MAX_CHUNK_SIZE: u8 = 255;

const TRACKING_KEY_REF: u8 = 0b1;
pub const KEY_NULL: u8 = 0b10;
const DECL_KEY_TYPE: u8 = 0b100;
const TRACKING_VALUE_REF: u8 = 0b1000;
pub const VALUE_NULL: u8 = 0b10000;
const DECL_VALUE_TYPE: u8 = 0b100000;

fn check_and_write_null<K: Serializer + Eq + std::hash::Hash, V: Serializer>(
    context: &mut WriteContext,
    is_field: bool,
    key: &K,
    value: &V,
) -> bool {
    if key.fory_is_none() && value.fory_is_none() {
        context.writer.write_u8(KEY_NULL | VALUE_NULL);
        return true;
    }
    if key.fory_is_none() {
        let mut chunk_header = KEY_NULL;
        let skip_ref_flag;
        if is_field {
            skip_ref_flag = crate::serializer::get_skip_ref_flag::<V>(context.get_fory());
            chunk_header |= DECL_VALUE_TYPE;
        } else {
            skip_ref_flag = false;
            chunk_header |= TRACKING_VALUE_REF;
        }
        context.writer.write_u8(chunk_header);

        crate::serializer::write_data(value, context, is_field, skip_ref_flag, false);
        return true;
    }
    if value.fory_is_none() {
        let mut chunk_header = VALUE_NULL;
        let skip_ref_flag;
        if is_field {
            // skip_ref_flag = crate::serializer::get_skip_ref_flag::<V>(context.get_fory());
            skip_ref_flag = true;
            chunk_header |= DECL_KEY_TYPE;
        } else {
            skip_ref_flag = false;
            chunk_header |= TRACKING_KEY_REF;
        }
        context.writer.write_u8(chunk_header);
        crate::serializer::write_data(key, context, is_field, skip_ref_flag, false);
        return true;
    }
    false
}

fn write_chunk_size(context: &mut WriteContext, header_offset: usize, size: u8) {
    context.writer.set_bytes(header_offset + 1, &[size]);
}

impl<K: Serializer + Eq + std::hash::Hash, V: Serializer> Serializer for HashMap<K, V> {
    fn fory_write(&self, context: &mut WriteContext, is_field: bool) {
        let length = self.len();
        context.writer.write_var_uint32(length as u32);
        if length == 0 {
            return;
        }
        let reserved_space = (<K as Serializer>::fory_reserved_space() + SIZE_OF_REF_AND_TYPE)
            * self.len()
            + (<V as Serializer>::fory_reserved_space() + SIZE_OF_REF_AND_TYPE) * self.len();
        context.writer.reserve(reserved_space);

        let mut header_offset = 0;
        let mut pair_counter: u8 = 0;
        let mut need_write_header = true;
        let mut skip_key_ref_flag = false;
        let mut skip_val_ref_flag = false;
        for entry in self.iter() {
            let key = entry.0;
            let value = entry.1;
            if need_write_header {
                if check_and_write_null(context, is_field, key, value) {
                    continue;
                }
                header_offset = context.writer.len();
                context.writer.write_i16(-1);
                let mut chunk_header = 0;
                if is_field {
                    chunk_header |= DECL_KEY_TYPE;
                    chunk_header |= DECL_VALUE_TYPE;
                }
                // skip_key_ref_flag = crate::serializer::get_skip_ref_flag::<K>(context.get_fory());
                skip_key_ref_flag = true;
                // skip_val_ref_flag = crate::serializer::get_skip_ref_flag::<V>(context.get_fory());
                skip_val_ref_flag = true;
                if !skip_key_ref_flag {
                    chunk_header |= TRACKING_KEY_REF;
                }
                if !skip_val_ref_flag {
                    chunk_header |= TRACKING_VALUE_REF;
                }
                K::fory_write_type_info(context, is_field);
                V::fory_write_type_info(context, is_field);
                context.writer.set_bytes(header_offset, &[chunk_header]);
                need_write_header = false;
            }
            if key.fory_is_none() || value.fory_is_none() {
                write_chunk_size(context, header_offset, pair_counter);
                pair_counter = 0;
                need_write_header = true;
                check_and_write_null(context, is_field, key, value);
                continue;
            }
            crate::serializer::write_data(key, context, is_field, skip_key_ref_flag, true);
            crate::serializer::write_data(value, context, is_field, skip_val_ref_flag, true);
            pair_counter += 1;
            if pair_counter == MAX_CHUNK_SIZE {
                write_chunk_size(context, header_offset, pair_counter);
                pair_counter = 0;
                need_write_header = true;
            }
        }
        if pair_counter > 0 {
            write_chunk_size(context, header_offset, pair_counter);
        }
    }

    fn fory_read(context: &mut ReadContext) -> Result<Self, Error> {
        let len = context.reader.read_var_uint32();
        let mut map = HashMap::<K, V>::with_capacity(len as usize);
        if len == 0 {
            return Ok(map);
        }
        let mut len_counter = 0;
        loop {
            if len_counter == len {
                break;
            }
            let header = context.reader.read_u8();
            if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
                map.insert(K::default(), V::default());
                len_counter += 1;
                continue;
            }
            let key_declared = (header & DECL_KEY_TYPE) != 0;
            let _key_tracking_ref = (header & TRACKING_KEY_REF) != 0;
            let value_declared = (header & DECL_VALUE_TYPE) != 0;
            let _value_tracking_ref = (header & TRACKING_VALUE_REF) != 0;
            if header & KEY_NULL != 0 {
                // let skip_ref_flag = crate::serializer::get_skip_ref_flag::<V>(context.get_fory());
                let skip_ref_flag = if value_declared {
                    crate::serializer::get_skip_ref_flag::<V>(context.get_fory())
                } else {
                    false
                };
                let value =
                    crate::serializer::read_data(context, value_declared, skip_ref_flag, false)?;
                map.insert(K::default(), value);
                len_counter += 1;
                continue;
            }
            if header & VALUE_NULL != 0 {
                // let skip_ref_flag = crate::serializer::get_skip_ref_flag::<K>(context.get_fory());
                let skip_ref_flag = if key_declared {
                    crate::serializer::get_skip_ref_flag::<K>(context.get_fory())
                } else {
                    false
                };
                let key =
                    crate::serializer::read_data(context, key_declared, skip_ref_flag, false)?;
                map.insert(key, V::default());
                len_counter += 1;
                continue;
            }
            let chunk_size = context.reader.read_u8();
            K::fory_read_type_info(context, key_declared);
            V::fory_read_type_info(context, value_declared);
            assert!(len_counter + chunk_size as u32 <= len);
            for _ in (0..chunk_size).enumerate() {
                // let skip_ref_flag = crate::serializer::get_skip_ref_flag::<K>(context.get_fory());
                let key = crate::serializer::read_data(context, key_declared, true, true)?;
                // let skip_ref_flag = crate::serializer::get_skip_ref_flag::<V>(context.get_fory());
                let value = crate::serializer::read_data(context, value_declared, true, true)?;
                map.insert(key, value);
            }
            len_counter += chunk_size as u32;
        }
        Ok(map)
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
        if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
            context.writer.write_var_uint32(TypeId::MAP as u32);
        }
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
        if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
            let remote_collection_type_id = context.reader.read_var_uint32();
            assert_eq!(remote_collection_type_id, TypeId::MAP as u32);
        }
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        TypeId::MAP as u32
    }
}

impl<T1: Serializer + Eq + std::hash::Hash, T2: Serializer> ForyGeneralList for HashMap<T1, T2> {}
