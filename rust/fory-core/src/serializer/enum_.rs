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
use crate::meta::{MetaString, TypeMeta};
use crate::resolver::context::{ReadContext, WriteContext};
use crate::serializer::{ForyDefault, Serializer};
use crate::types::{Mode, RefFlag, TypeId};

#[inline(always)]
pub fn actual_type_id(type_id: u32, register_by_name: bool, _mode: &Mode) -> u32 {
    if register_by_name {
        TypeId::NAMED_ENUM as u32
    } else {
        (type_id << 8) + TypeId::ENUM as u32
    }
}

#[inline(always)]
pub fn type_def(
    _fory: &Fory,
    type_id: u32,
    namespace: MetaString,
    type_name: MetaString,
    register_by_name: bool,
) -> Vec<u8> {
    let meta = TypeMeta::from_fields(type_id, namespace, type_name, register_by_name, vec![]);
    meta.to_bytes().unwrap()
}

#[inline(always)]
pub fn write_type_info<T: Serializer>(fory: &Fory, context: &mut WriteContext, is_field: bool) {
    if is_field {
        return;
    }
    let type_id = T::fory_get_type_id(fory);
    context.writer.write_varuint32(type_id);
    let is_named_enum = type_id & 0xff == TypeId::NAMED_ENUM as u32;
    if !is_named_enum {
        return;
    }
    let rs_type_id = std::any::TypeId::of::<T>();
    if fory.is_share_meta() {
        let meta_index = context.push_meta(fory, rs_type_id) as u32;
        context.writer.write_varuint32(meta_index);
    } else {
        let type_info = fory.get_type_resolver().get_type_info(rs_type_id);
        let namespace = type_info.get_namespace().to_owned();
        let type_name = type_info.get_type_name().to_owned();
        let resolver = fory.get_metastring_resolver();
        resolver
            .borrow_mut()
            .write_meta_string_bytes(context, &namespace);
        resolver
            .borrow_mut()
            .write_meta_string_bytes(context, &type_name);
    }
}

#[inline(always)]
pub fn read_type_info<T: Serializer>(fory: &Fory, context: &mut ReadContext, is_field: bool) {
    if is_field {
        return;
    }
    let local_type_id = T::fory_get_type_id(fory);
    let remote_type_id = context.reader.read_varuint32();
    assert_eq!(local_type_id, remote_type_id);
    let is_named_enum = local_type_id & 0xff == TypeId::NAMED_ENUM as u32;
    if !is_named_enum {
        return;
    }
    if fory.is_share_meta() {
        let _meta_index = context.reader.read_varuint32();
    } else {
        let resolver = fory.get_metastring_resolver();
        resolver.borrow_mut().read_meta_string_bytes(context);
        resolver.borrow_mut().read_meta_string_bytes(context);
    }
}

#[inline(always)]
pub fn read_compatible<T: Serializer + ForyDefault>(
    fory: &Fory,
    context: &mut ReadContext,
) -> Result<T, Error> {
    T::fory_read_type_info(fory, context, true);
    T::fory_read_data(fory, context, true)
}

#[inline(always)]
pub fn write<T: Serializer>(this: &T, fory: &Fory, context: &mut WriteContext, is_field: bool) {
    context.writer.write_i8(RefFlag::NotNullValue as i8);
    T::fory_write_type_info(fory, context, is_field);
    this.fory_write_data(fory, context, is_field);
}

#[inline(always)]
pub fn read<T: Serializer + ForyDefault>(
    fory: &Fory,
    context: &mut ReadContext,
    is_field: bool,
) -> Result<T, Error> {
    let ref_flag = context.reader.read_i8();
    if ref_flag == RefFlag::Null as i8 {
        Ok(T::fory_default())
    } else if ref_flag == (RefFlag::NotNullValue as i8) {
        T::fory_read_type_info(fory, context, false);
        T::fory_read_data(fory, context, is_field)
    } else {
        unimplemented!()
    }
}
