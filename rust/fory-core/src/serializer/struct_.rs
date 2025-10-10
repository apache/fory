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
use crate::meta::{FieldInfo, MetaString, TypeMeta};
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
    namespace: MetaString,
    type_name: MetaString,
    register_by_name: bool,
    mut field_infos: Vec<FieldInfo>,
) -> Vec<u8> {
    let sorted_field_names = T::fory_get_sorted_field_names(fory);
    let mut sorted_field_infos: Vec<FieldInfo> = Vec::with_capacity(field_infos.len());
    for name in sorted_field_names.iter() {
        let mut found = false;
        for i in 0..field_infos.len() {
            if &field_infos[i].field_name == name {
                // swap_remove is faster
                sorted_field_infos.push(field_infos.swap_remove(i));
                found = true;
                break;
            }
        }
        if !found {
            panic!("Field {} not found in field_infos", name);
        }
    }
    let meta = TypeMeta::from_fields(
        type_id,
        namespace,
        type_name,
        register_by_name,
        sorted_field_infos,
    );
    meta.to_bytes().unwrap()
}

#[inline(always)]
pub fn write_type_info<T: Serializer>(fory: &Fory, context: &mut WriteContext, _is_field: bool) {
    let type_id = T::fory_get_type_id(fory);
    context.writer.write_varuint32(type_id);
    let rs_type_id = std::any::TypeId::of::<T>();

    if type_id & 0xff == TypeId::NAMED_STRUCT as u32 {
        if fory.is_share_meta() {
            let meta_index = context.push_meta(fory, rs_type_id) as u32;
            context.writer.write_varuint32(meta_index);
        } else {
            let type_info = fory.get_type_resolver().get_type_info(rs_type_id);
            let namespace = type_info.get_namespace().to_owned();
            let type_name = type_info.get_type_name().to_owned();
            context.write_meta_string_bytes(&namespace);
            context.write_meta_string_bytes(&type_name);
        }
    } else if type_id & 0xff == TypeId::NAMED_COMPATIBLE_STRUCT as u32
        || type_id & 0xff == TypeId::COMPATIBLE_STRUCT as u32
    {
        let meta_index = context.push_meta(fory, rs_type_id) as u32;
        context.writer.write_varuint32(meta_index);
    }
}

#[inline(always)]
pub fn read_type_info<T: Serializer>(fory: &Fory, context: &mut ReadContext, _is_field: bool) {
    let remote_type_id = context.reader.read_varuint32();
    let local_type_id = T::fory_get_type_id(fory);
    assert_eq!(remote_type_id, local_type_id);

    if local_type_id & 0xff == TypeId::NAMED_STRUCT as u32 {
        if fory.is_share_meta() {
            let _meta_index = context.reader.read_varuint32();
        } else {
            let _namespace_msb = context.read_meta_string_bytes();
            let _type_name_msb = context.read_meta_string_bytes();
        }
    } else if local_type_id & 0xff == TypeId::NAMED_COMPATIBLE_STRUCT as u32
        || local_type_id & 0xff == TypeId::COMPATIBLE_STRUCT as u32
    {
        let _meta_index = context.reader.read_varuint32();
    }
}

#[inline(always)]
pub fn write<T: Serializer>(this: &T, fory: &Fory, context: &mut WriteContext, _is_field: bool) {
    match fory.get_mode() {
        // currently same
        Mode::SchemaConsistent => {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
            T::fory_write_type_info(fory, context, false);
            this.fory_write_data(fory, context, true);
        }
        Mode::Compatible => {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
            T::fory_write_type_info(fory, context, false);
            this.fory_write_data(fory, context, true);
        }
    }
}
