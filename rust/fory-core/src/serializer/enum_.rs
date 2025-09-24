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
use crate::meta::{
    TypeMeta, NAMESPACE_ENCODER, NAMESPACE_ENCODINGS, TYPE_NAME_ENCODER, TYPE_NAME_ENCODINGS,
};
use crate::resolver::context::{ReadContext, WriteContext};
use crate::serializer::Serializer;
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
    namespace: &str,
    type_name: &str,
    register_by_name: bool,
) -> Vec<u8> {
    let namespace_metastring = NAMESPACE_ENCODER
        .encode_with_encodings(namespace, NAMESPACE_ENCODINGS)
        .unwrap();
    let type_name_metastring = TYPE_NAME_ENCODER
        .encode_with_encodings(type_name, TYPE_NAME_ENCODINGS)
        .unwrap();
    let meta = TypeMeta::from_fields(
        type_id,
        namespace_metastring,
        type_name_metastring,
        register_by_name,
        vec![],
    );
    meta.to_bytes().unwrap()
}

#[inline(always)]
pub fn write_type_info<T: Serializer>(context: &mut WriteContext, is_field: bool) {
    if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
        let type_id = T::get_type_id(context.get_fory());
        context.writer.var_uint32(type_id);
        if type_id & 0xff == TypeId::NAMED_ENUM as u32 {
            let meta_index = context.push_meta(std::any::TypeId::of::<T>()) as u32;
            context.writer.var_uint32(meta_index);
        }
    }
}

#[inline(always)]
pub fn read_type_info<T: Serializer>(context: &mut ReadContext, is_field: bool) {
    if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
        let local_type_id = T::get_type_id(context.get_fory());
        let remote_type_id = context.reader.var_uint32();
        assert_eq!(local_type_id, remote_type_id);
        if local_type_id & 0xff == TypeId::NAMED_ENUM as u32 {
            let _meta_index = context.reader.var_uint32();
        }
    }
}

#[inline(always)]
pub fn read_compatible<T: Serializer>(context: &mut ReadContext) -> Result<T, Error> {
    T::read_type_info(context, true);
    T::read(context)
}

#[inline(always)]
pub fn serialize<T: Serializer>(this: &T, context: &mut WriteContext, is_field: bool) {
    context.writer.i8(RefFlag::NotNullValue as i8);
    T::write_type_info(context, is_field);
    this.write(context, is_field);
}

#[inline(always)]
pub fn deserialize<T: Serializer>(context: &mut ReadContext, is_field: bool) -> Result<T, Error> {
    let ref_flag = context.reader.i8();
    if ref_flag == RefFlag::Null as i8 {
        Ok(T::default())
    } else if ref_flag == (RefFlag::NotNullValue as i8) {
        T::read_type_info(context, false);
        T::read(context)
    } else {
        unimplemented!()
    }
}
