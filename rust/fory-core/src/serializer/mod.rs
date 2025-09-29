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
use crate::meta::MetaString;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::types::{Mode, RefFlag, PRIMITIVE_TYPES};

mod any;
mod arc;
mod bool;
mod box_;
pub mod collection;
mod datetime;
pub mod enum_;
mod list;
pub mod map;
mod number;
mod option;
mod primitive_list;
mod rc;
mod set;
pub mod skip;
mod string;
pub mod struct_;

pub fn write_ref_info_data<T: Serializer + 'static>(
    record: &T,
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
            T::fory_write_type_info(context, is_field);
        }
        record.fory_write_data(context, is_field);
    }
}

pub fn read_ref_info_data<T: Serializer + Default>(
    context: &mut ReadContext,
    is_field: bool,
    skip_ref_flag: bool,
    skip_type_info: bool,
) -> Result<T, Error> {
    if !skip_ref_flag {
        let ref_flag = context.reader.read_i8();
        if ref_flag == RefFlag::Null as i8 {
            Ok(T::default())
        } else if ref_flag == (RefFlag::NotNullValue as i8) {
            if !skip_type_info {
                T::fory_read_type_info(context, is_field);
            }
            T::fory_read_data(context, is_field)
        } else if ref_flag == (RefFlag::RefValue as i8) {
            // First time seeing this referenceable object
            if !skip_type_info {
                T::read_type_info(context, is_field);
            }
            T::fory_read_data(context, is_field)
        } else if ref_flag == (RefFlag::Ref as i8) {
            // This is a reference to a previously deserialized object
            // For now, just return default - this should be handled by specific types
            Ok(T::default())
        } else {
            unimplemented!("Unknown ref flag: {}", ref_flag)
        }
    } else {
        if !skip_type_info {
            T::fory_read_type_info(context, is_field);
        }
        T::fory_read_data(context, is_field)
    }
}

pub fn get_skip_ref_flag<T: Serializer>(fory: &Fory) -> bool {
    let elem_type_id = T::fory_get_type_id(fory);
    !T::fory_is_option() && PRIMITIVE_TYPES.contains(&elem_type_id)
}

pub trait Serializer
where
    Self: Sized + Default + 'static,
{
    /// Entry point of the serialization.
    fn fory_write(&self, context: &mut WriteContext, is_field: bool) {
        write_ref_info_data(self, context, is_field, false, false);
    }

    fn fory_read(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        read_ref_info_data(context, is_field, false, false)
    }

    fn fory_is_option() -> bool {
        false
    }

    fn fory_is_none(&self) -> bool {
        false
    }

    fn fory_get_type_id(fory: &Fory) -> u32 {
        fory.get_type_resolver()
            .get_type_info(std::any::TypeId::of::<Self>())
            .get_type_id()
    }

    /// The possible max memory size of the type.
    /// Used to reserve the buffer space to avoid reallocation, which may hurt performance.
    fn fory_reserved_space() -> usize {
        0
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
        if !is_field {
            let type_id = Self::fory_get_type_id(context.get_fory());
            context.writer.write_varuint32(type_id);
        }
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
        if !is_field {
            let remote_type_id = context.reader.read_varuint32();
            let local_type_id = Self::fory_get_type_id(context.get_fory());
            assert_eq!(remote_type_id, local_type_id);
        }
    }

    /// Write/Read the data into the buffer. Need to be implemented.
    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool);

    fn fory_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error>;
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

    fn fory_read_compatible(_context: &mut ReadContext) -> Result<Self, Error> {
        unimplemented!()
    }

    fn fory_get_sorted_field_names(_fory: &Fory) -> Vec<String> {
        unimplemented!()
    }
}
