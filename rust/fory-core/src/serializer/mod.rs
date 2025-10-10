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
use crate::meta::{MetaString, NAMESPACE_DECODER, TYPE_NAME_DECODER};
use crate::resolver::context::{ReadContext, WriteContext};
use crate::types::{Mode, RefFlag, TypeId, PRIMITIVE_TYPES};
use anyhow::anyhow;
use std::any::Any;

pub mod any;
mod arc;
mod bool;
mod box_;
pub mod collection;
mod datetime;
pub mod enum_;
mod list;
pub mod map;
mod mutex;
mod number;
mod option;
mod primitive_list;
mod rc;
mod refcell;
mod set;
pub mod skip;
mod string;
pub mod struct_;
pub mod trait_object;
pub mod weak;

pub fn write_ref_info_data<T: Serializer + 'static>(
    record: &T,
    fory: &Fory,
    context: &mut WriteContext,
    is_field: bool,
    skip_ref_flag: bool,
    skip_type_info: bool,
) {
    if record.fory_is_none() {
        context.writer.write_i8(RefFlag::Null as i8);
    } else {
        if !skip_ref_flag {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        if !skip_type_info {
            T::fory_write_type_info(fory, context, is_field);
        }
        record.fory_write_data(fory, context, is_field);
    }
}

pub fn read_ref_info_data<T: Serializer + ForyDefault>(
    fory: &Fory,
    context: &mut ReadContext,
    is_field: bool,
    skip_ref_flag: bool,
    skip_type_info: bool,
) -> Result<T, Error> {
    if !skip_ref_flag {
        let ref_flag = context.reader.read_i8();
        if ref_flag == RefFlag::Null as i8 {
            Ok(T::fory_default())
        } else if ref_flag == (RefFlag::NotNullValue as i8) {
            if !skip_type_info {
                T::fory_read_type_info(fory, context, is_field);
            }
            T::fory_read_data(fory, context, is_field)
        } else if ref_flag == (RefFlag::RefValue as i8) {
            // First time seeing this referenceable object
            if !skip_type_info {
                T::fory_read_type_info(fory, context, is_field);
            }
            T::fory_read_data(fory, context, is_field)
        } else if ref_flag == (RefFlag::Ref as i8) {
            // This is a reference to a previously deserialized object
            // For now, just return default - this should be handled by specific types
            Ok(T::fory_default())
        } else {
            unimplemented!("Unknown ref flag: {}", ref_flag)
        }
    } else {
        if !skip_type_info {
            T::fory_read_type_info(fory, context, is_field);
        }
        T::fory_read_data(fory, context, is_field)
    }
}

fn write_type_info<T: Serializer>(fory: &Fory, context: &mut WriteContext, is_field: bool) {
    if is_field {
        return;
    }
    let type_id = T::fory_get_type_id(fory);
    context.writer.write_varuint32(type_id);
}

fn read_type_info<T: Serializer>(fory: &Fory, context: &mut ReadContext, is_field: bool) {
    if is_field {
        return;
    }
    let local_type_id = T::fory_get_type_id(fory);
    let remote_type_id = context.reader.read_varuint32();
    assert_eq!(local_type_id, remote_type_id);
}

pub fn get_skip_ref_flag<T: Serializer>(fory: &Fory) -> bool {
    let elem_type_id = T::fory_get_type_id(fory);
    !T::fory_is_option() && PRIMITIVE_TYPES.contains(&elem_type_id)
}

pub trait ForyDefault: Sized {
    fn fory_default() -> Self;
}

// We can't add blanket impl for all T: Default because it conflicts with other impls.
// For example, upstream crates may add a new impl of trait `std::default::Default` for
// type `std::rc::Rc<(dyn std::any::Any + 'static)>` in future versions.
// impl<T: Default + Sized> ForyDefault for T {
//     fn fory_default() -> Self {
//         Default::default()
//     }
// }

pub trait Serializer: 'static {
    /// Entry point of the serialization.
    fn fory_write(&self, fory: &Fory, context: &mut WriteContext, is_field: bool)
    where
        Self: Sized,
    {
        write_ref_info_data(self, fory, context, is_field, false, false);
    }

    fn fory_read(fory: &Fory, context: &mut ReadContext, is_field: bool) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_ref_info_data(fory, context, is_field, false, false)
    }

    fn fory_is_option() -> bool
    where
        Self: Sized,
    {
        false
    }

    fn fory_is_none(&self) -> bool {
        false
    }

    fn fory_is_polymorphic() -> bool
    where
        Self: Sized,
    {
        false
    }

    fn fory_is_shared_ref() -> bool
    where
        Self: Sized,
    {
        false
    }

    fn fory_get_type_id(fory: &Fory) -> u32
    where
        Self: Sized,
    {
        fory.get_type_resolver()
            .get_type_info(std::any::TypeId::of::<Self>())
            .get_type_id()
    }

    fn fory_type_id_dyn(&self, fory: &Fory) -> u32;

    /// The possible max memory size of the type.
    /// Used to reserve the buffer space to avoid reallocation, which may hurt performance.
    fn fory_reserved_space() -> usize
    where
        Self: Sized,
    {
        0
    }

    fn fory_write_type_info(fory: &Fory, context: &mut WriteContext, _is_field: bool)
    where
        Self: Sized,
    {
        // default implementation only for ext/named_ext
        let type_id = Self::fory_get_type_id(fory);
        context.writer.write_varuint32(type_id);
        if type_id & 0xff == TypeId::EXT as u32 {
            return;
        }
        let rs_type_id = std::any::TypeId::of::<Self>();
        if fory.is_share_meta() {
            let meta_index = context.push_meta(fory, rs_type_id) as u32;
            context.writer.write_varuint32(meta_index);
        } else {
            let type_info = {
                let type_resolver = fory.get_type_resolver();
                type_resolver.get_type_info(rs_type_id)
            };
            let namespace = type_info.get_namespace().to_owned();
            let type_name = type_info.get_type_name().to_owned();
            context.write_meta_string_bytes(&namespace);
            context.write_meta_string_bytes(&type_name);
        }
    }

    fn fory_read_type_info(fory: &Fory, context: &mut ReadContext, _is_field: bool)
    where
        Self: Sized,
    {
        // default implementation only for ext/named_ext
        let local_type_id = Self::fory_get_type_id(fory);
        let remote_type_id = context.reader.read_varuint32();
        assert_eq!(local_type_id, remote_type_id);
        if local_type_id & 0xff == TypeId::EXT as u32 {
            return;
        }
        if fory.is_share_meta() {
            let _meta_index = context.reader.read_varuint32();
        } else {
            let _namespace_msb = context.read_meta_string_bytes();
            let _type_name_msb = context.read_meta_string_bytes();
        }
    }

    // only used by struct/enum/ext
    fn fory_read_compatible(fory: &Fory, context: &mut ReadContext) -> Result<Self, Error>
    where
        Self: Sized,
    {
        // default logic only for ext/named_ext
        let remote_type_id = context.reader.read_varuint32();
        let local_type_id = Self::fory_get_type_id(fory);
        assert_eq!(remote_type_id, local_type_id);
        if local_type_id & 0xff == TypeId::EXT as u32 {
            let type_resolver = fory.get_type_resolver();
            type_resolver
                .get_ext_harness(local_type_id)
                .get_read_data_fn()(fory, context, true)
            .and_then(|b: Box<dyn Any>| {
                b.downcast::<Self>()
                    .map(|boxed_self| *boxed_self)
                    .map_err(|_| anyhow!("downcast to Self failed").into())
            })
        } else {
            let (namespace, type_name) = if fory.is_share_meta() {
                let meta_index = context.reader.read_varuint32();
                let type_def = context.get_meta(meta_index as usize);
                (type_def.get_namespace(), type_def.get_type_name())
            } else {
                let nsb = context.read_meta_string_bytes();
                let tsb = context.read_meta_string_bytes();
                let ns = NAMESPACE_DECODER.decode(&nsb.bytes, nsb.encoding)?;
                let ts = TYPE_NAME_DECODER.decode(&tsb.bytes, tsb.encoding)?;
                (ns, ts)
            };
            let type_resolver = fory.get_type_resolver();
            type_resolver
                .get_ext_name_harness(&namespace, &type_name)
                .get_read_data_fn()(fory, context, true)
            .and_then(|b: Box<dyn Any>| {
                b.downcast::<Self>()
                    .map(|boxed_self| *boxed_self)
                    .map_err(|_| anyhow!("downcast to Self failed").into())
            })
        }
    }

    /// Write/Read the data into the buffer. Need to be implemented.
    fn fory_write_data(&self, fory: &Fory, context: &mut WriteContext, is_field: bool);

    fn fory_read_data(
        fory: &Fory,
        context: &mut ReadContext,
        is_field: bool,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault;

    fn fory_concrete_type_id(&self) -> std::any::TypeId {
        std::any::TypeId::of::<Self>()
    }

    fn as_any(&self) -> &dyn Any;
}

pub trait StructSerializer: Serializer + 'static {
    fn fory_type_def(
        _fory: &Fory,
        _type_id: u32,
        _namespace: MetaString,
        _type_name: MetaString,
        _register_by_name: bool,
    ) -> Vec<u8> {
        Vec::default()
    }

    fn fory_type_index() -> u32 {
        unimplemented!()
    }
    fn fory_actual_type_id(type_id: u32, register_by_name: bool, mode: &Mode) -> u32 {
        struct_::actual_type_id(type_id, register_by_name, mode)
    }

    fn fory_get_sorted_field_names(_fory: &Fory) -> Vec<String> {
        unimplemented!()
    }
}
