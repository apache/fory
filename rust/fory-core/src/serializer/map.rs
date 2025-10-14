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

use crate::ensure;
use crate::error::Error;
use crate::fory::Fory;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::serializer::{
    read_ref_info_data, read_type_info, write_ref_info_data, write_type_info, ForyDefault,
    Serializer,
};
use crate::types::{TypeId, SIZE_OF_REF_AND_TYPE};
use std::collections::{BTreeMap, HashMap};

const MAX_CHUNK_SIZE: u8 = 255;

const TRACKING_KEY_REF: u8 = 0b1;
pub const KEY_NULL: u8 = 0b10;
const DECL_KEY_TYPE: u8 = 0b100;
const TRACKING_VALUE_REF: u8 = 0b1000;
pub const VALUE_NULL: u8 = 0b10000;
const DECL_VALUE_TYPE: u8 = 0b100000;

fn check_and_write_null<K: Serializer + Eq + std::hash::Hash, V: Serializer>(
    fory: &Fory,
    context: &mut WriteContext,
    is_field: bool,
    key: &K,
    value: &V,
) -> Result<bool, Error> {
    if key.fory_is_none() && value.fory_is_none() {
        context.writer.write_u8(KEY_NULL | VALUE_NULL);
        return Ok(true);
    }
    if key.fory_is_none() {
        let mut chunk_header = KEY_NULL;
        let skip_ref_flag;
        if is_field {
            skip_ref_flag = crate::serializer::get_skip_ref_flag::<V>(fory)?;
            chunk_header |= DECL_VALUE_TYPE;
        } else {
            skip_ref_flag = false;
            chunk_header |= TRACKING_VALUE_REF;
        }
        context.writer.write_u8(chunk_header);

        write_ref_info_data(value, fory, context, is_field, skip_ref_flag, false)?;
        return Ok(true);
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
        write_ref_info_data(key, fory, context, is_field, skip_ref_flag, false)?;
        return Ok(true);
    }
    Ok(false)
}

fn write_chunk_size(context: &mut WriteContext, header_offset: usize, size: u8) {
    context.writer.set_bytes(header_offset + 1, &[size]);
}

fn write_map_data<'a, K, V, I>(
    iter: I,
    length: usize,
    fory: &Fory,
    context: &mut WriteContext,
    is_field: bool,
) -> Result<(), Error>
where
    K: Serializer + ForyDefault + 'a + Eq + std::hash::Hash,
    V: Serializer + ForyDefault + 'a,
    I: Iterator<Item = (&'a K, &'a V)>,
{
    context.writer.write_varuint32(length as u32);
    if length == 0 {
        return Ok(());
    }
    let reserved_space = (K::fory_reserved_space() + SIZE_OF_REF_AND_TYPE) * length
        + (V::fory_reserved_space() + SIZE_OF_REF_AND_TYPE) * length;
    context.writer.reserve(reserved_space);

    let mut header_offset = 0;
    let mut pair_counter: u8 = 0;
    let mut need_write_header = true;
    let mut skip_key_ref_flag = false;
    let mut skip_val_ref_flag = false;
    for (key, value) in iter {
        if need_write_header {
            if check_and_write_null(fory, context, is_field, key, value)? {
                continue;
            }
            header_offset = context.writer.len();
            context.writer.write_i16(-1);
            let mut chunk_header = 0;
            if is_field {
                chunk_header |= DECL_KEY_TYPE | DECL_VALUE_TYPE;
            }
            skip_key_ref_flag = !K::fory_is_polymorphic();
            skip_val_ref_flag = !V::fory_is_polymorphic();
            if !skip_key_ref_flag {
                chunk_header |= TRACKING_KEY_REF;
            }
            if !skip_val_ref_flag {
                chunk_header |= TRACKING_VALUE_REF;
            }
            K::fory_write_type_info(fory, context, is_field)?;
            V::fory_write_type_info(fory, context, is_field)?;
            context.writer.set_bytes(header_offset, &[chunk_header]);
            need_write_header = false;
        }
        if key.fory_is_none() || value.fory_is_none() {
            write_chunk_size(context, header_offset, pair_counter);
            pair_counter = 0;
            need_write_header = true;
            check_and_write_null(fory, context, is_field, key, value)?;
            continue;
        }
        if K::fory_is_polymorphic() || K::fory_is_shared_ref() {
            key.fory_write(fory, context, is_field)?;
        } else {
            write_ref_info_data(key, fory, context, is_field, skip_key_ref_flag, true)?;
        }
        if V::fory_is_polymorphic() || V::fory_is_shared_ref() {
            value.fory_write(fory, context, is_field)?;
        } else {
            write_ref_info_data(value, fory, context, is_field, skip_val_ref_flag, true)?;
        }
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
    Ok(())
}

impl<K: Serializer + ForyDefault + Eq + std::hash::Hash, V: Serializer + ForyDefault> Serializer
    for HashMap<K, V>
{
    fn fory_write_data(
        &self,
        fory: &Fory,
        context: &mut WriteContext,
        is_field: bool,
    ) -> Result<(), Error> {
        write_map_data(self.iter(), self.len(), fory, context, is_field)
    }

    fn fory_read_data(
        fory: &Fory,
        context: &mut ReadContext,
        _is_field: bool,
    ) -> Result<Self, Error> {
        let len = context.reader.read_varuint32()?;
        let mut map = HashMap::<K, V>::with_capacity(len as usize);
        if len == 0 {
            return Ok(map);
        }
        let mut len_counter = 0;
        loop {
            if len_counter == len {
                break;
            }
            let header = context.reader.read_u8()?;
            if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
                map.insert(K::fory_default(), V::fory_default());
                len_counter += 1;
                continue;
            }
            let key_declared = (header & DECL_KEY_TYPE) != 0;
            let value_declared = (header & DECL_VALUE_TYPE) != 0;
            if header & KEY_NULL != 0 {
                let skip_ref_flag = if value_declared {
                    crate::serializer::get_skip_ref_flag::<V>(fory)?
                } else {
                    false
                };
                let value =
                    read_ref_info_data(fory, context, value_declared, skip_ref_flag, false)?;
                map.insert(K::fory_default(), value);
                len_counter += 1;
                continue;
            }
            if header & VALUE_NULL != 0 {
                let skip_ref_flag = if key_declared {
                    crate::serializer::get_skip_ref_flag::<K>(fory)?
                } else {
                    false
                };
                let key = read_ref_info_data(fory, context, key_declared, skip_ref_flag, false)?;
                map.insert(key, V::fory_default());
                len_counter += 1;
                continue;
            }
            let chunk_size = context.reader.read_u8()?;
            K::fory_read_type_info(fory, context, key_declared)?;
            V::fory_read_type_info(fory, context, value_declared)?;

            let cur_len = len_counter + chunk_size as u32;
            ensure!(
                cur_len <= len,
                "current length {} exceeds total length {}",
                cur_len,
                len
            );
            assert!(len_counter + chunk_size as u32 <= len);
            for _ in 0..chunk_size {
                let key = if K::fory_is_polymorphic() {
                    K::fory_read(fory, context, key_declared)?
                } else {
                    // let skip_ref_flag = crate::serializer::get_skip_ref_flag::<K>(context.get_fory());
                    read_ref_info_data(fory, context, key_declared, true, true)?
                };
                let value = if V::fory_is_polymorphic() {
                    V::fory_read(fory, context, value_declared)?
                } else {
                    // let skip_ref_flag = crate::serializer::get_skip_ref_flag::<V>(context.get_fory());
                    read_ref_info_data(fory, context, value_declared, true, true)?
                };
                map.insert(key, value);
            }
            len_counter += chunk_size as u32;
        }
        Ok(map)
    }

    fn fory_reserved_space() -> usize {
        size_of::<i32>()
    }

    fn fory_get_type_id(_fory: &Fory) -> Result<u32, Error> {
        Ok(TypeId::MAP as u32)
    }

    fn fory_type_id_dyn(&self, _fory: &Fory) -> Result<u32, Error> {
        Ok(TypeId::MAP as u32)
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(
        fory: &Fory,
        context: &mut WriteContext,
        is_field: bool,
    ) -> Result<(), Error> {
        write_type_info::<Self>(fory, context, is_field)
    }

    fn fory_read_type_info(
        fory: &Fory,
        context: &mut ReadContext,
        is_field: bool,
    ) -> Result<(), Error> {
        read_type_info::<Self>(fory, context, is_field)
    }
}

impl<K, V> ForyDefault for HashMap<K, V> {
    fn fory_default() -> Self {
        HashMap::new()
    }
}

impl<K: Serializer + ForyDefault + Ord + std::hash::Hash, V: Serializer + ForyDefault> Serializer
    for BTreeMap<K, V>
{
    fn fory_write_data(
        &self,
        fory: &Fory,
        context: &mut WriteContext,
        is_field: bool,
    ) -> Result<(), Error> {
        write_map_data(self.iter(), self.len(), fory, context, is_field)
    }

    fn fory_read_data(
        fory: &Fory,
        context: &mut ReadContext,
        _is_field: bool,
    ) -> Result<Self, Error> {
        let len = context.reader.read_varuint32()?;
        let mut map = BTreeMap::<K, V>::new();
        if len == 0 {
            return Ok(map);
        }
        let mut len_counter = 0;
        loop {
            if len_counter == len {
                break;
            }
            let header = context.reader.read_u8()?;
            if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
                map.insert(K::fory_default(), V::fory_default());
                len_counter += 1;
                continue;
            }
            let key_declared = (header & DECL_KEY_TYPE) != 0;
            let value_declared = (header & DECL_VALUE_TYPE) != 0;
            if header & KEY_NULL != 0 {
                let skip_ref_flag = if value_declared {
                    crate::serializer::get_skip_ref_flag::<V>(fory)?
                } else {
                    false
                };
                let value =
                    read_ref_info_data(fory, context, value_declared, skip_ref_flag, false)?;
                map.insert(K::fory_default(), value);
                len_counter += 1;
                continue;
            }
            if header & VALUE_NULL != 0 {
                let skip_ref_flag = if key_declared {
                    crate::serializer::get_skip_ref_flag::<K>(fory)?
                } else {
                    false
                };
                let key = read_ref_info_data(fory, context, key_declared, skip_ref_flag, false)?;
                map.insert(key, V::fory_default());
                len_counter += 1;
                continue;
            }
            let chunk_size = context.reader.read_u8()?;
            K::fory_read_type_info(fory, context, key_declared)?;
            V::fory_read_type_info(fory, context, value_declared)?;
            assert!(len_counter + chunk_size as u32 <= len);
            for _ in 0..chunk_size {
                let key = if K::fory_is_polymorphic() {
                    K::fory_read(fory, context, key_declared)?
                } else {
                    read_ref_info_data(fory, context, key_declared, true, true)?
                };
                let value = if V::fory_is_polymorphic() {
                    V::fory_read(fory, context, value_declared)?
                } else {
                    read_ref_info_data(fory, context, value_declared, true, true)?
                };
                map.insert(key, value);
            }
            len_counter += chunk_size as u32;
        }
        Ok(map)
    }

    fn fory_reserved_space() -> usize {
        size_of::<i32>()
    }

    fn fory_get_type_id(_fory: &Fory) -> Result<u32, Error> {
        Ok(TypeId::MAP as u32)
    }

    fn fory_type_id_dyn(&self, _fory: &Fory) -> Result<u32, Error> {
        Ok(TypeId::MAP as u32)
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(
        fory: &Fory,
        context: &mut WriteContext,
        is_field: bool,
    ) -> Result<(), Error> {
        write_type_info::<Self>(fory, context, is_field)
    }

    fn fory_read_type_info(
        fory: &Fory,
        context: &mut ReadContext,
        is_field: bool,
    ) -> Result<(), Error> {
        read_type_info::<Self>(fory, context, is_field)
    }
}

impl<K, V> ForyDefault for BTreeMap<K, V> {
    fn fory_default() -> Self {
        BTreeMap::new()
    }
}
