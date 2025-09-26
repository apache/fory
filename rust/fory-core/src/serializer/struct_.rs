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

use crate::fory::Fory;
use crate::meta::{
    FieldInfo, TypeMeta, NAMESPACE_ENCODER, NAMESPACE_ENCODINGS, TYPE_NAME_ENCODER,
    TYPE_NAME_ENCODINGS,
};
use crate::resolver::context::{ReadContext, WriteContext};
use crate::serializer::{Serializer, StructSerializer};
use crate::types::{Mode, RefFlag, TypeId};

#[inline(always)]
pub fn actual_type_id(type_id: u32, register_by_name: bool, mode: &Mode) -> u32 {
    if mode == &Mode::Compatible {
        if register_by_name {
            TypeId::NAMED_COMPATIBLE_STRUCT as u32
        } else {
            (type_id << 8) + TypeId::COMPATIBLE_STRUCT as u32
        }
    } else if register_by_name {
        TypeId::NAMED_STRUCT as u32
    } else {
        (type_id << 8) + TypeId::STRUCT as u32
    }
}

#[inline(always)]
pub fn type_def<T: Serializer + StructSerializer>(
    fory: &Fory,
    type_id: u32,
    namespace: &str,
    type_name: &str,
    register_by_name: bool,
    field_infos: &[FieldInfo],
) -> Vec<u8> {
    let sorted_field_names = T::get_sorted_field_names(fory);
    let mut sorted_field_infos = Vec::with_capacity(field_infos.len());
    for name in &sorted_field_names {
        if let Some(info) = field_infos.iter().find(|f| &f.field_name == name) {
            sorted_field_infos.push(info.clone());
        } else {
            panic!("Field {} not found in field_infos", name);
        }
    }
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
        sorted_field_infos,
    );
    meta.to_bytes().unwrap()
}

#[inline(always)]
pub fn write_type_info<T: Serializer>(context: &mut WriteContext, _is_field: bool) {
    let type_id = T::get_type_id(context.get_fory());
    context.writer.write_var_uint32(type_id);
    if *context.get_fory().get_mode() == Mode::Compatible {
        let meta_index = context.push_meta(std::any::TypeId::of::<T>()) as u32;
        context.writer.write_var_uint32(meta_index);
    }
}

#[inline(always)]
pub fn read_type_info<T: Serializer>(context: &mut ReadContext, _is_field: bool) {
    let remote_type_id = context.reader.read_var_uint32();
    assert_eq!(remote_type_id, T::get_type_id(context.get_fory()));
    if *context.get_fory().get_mode() == Mode::Compatible {
        let _meta_index = context.reader.read_var_uint32();
    }
}

#[inline(always)]
pub fn serialize<T: Serializer>(this: &T, context: &mut WriteContext, _is_field: bool) {
    match context.get_fory().get_mode() {
        // currently same
        Mode::SchemaConsistent => {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
            T::write_type_info(context, false);
            this.write(context, true);
        }
        Mode::Compatible => {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
            T::write_type_info(context, false);
            this.write(context, true);
        }
    }
}
