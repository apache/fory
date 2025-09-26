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
use crate::resolver::context::{ReadContext, WriteContext};
use crate::types::{Mode, RefFlag, PRIMITIVE_TYPES};

mod any;
mod bool;
pub mod collection;
mod datetime;
pub mod enum_;
mod list;
pub mod map;
mod number;
mod option;
mod primitive_list;
mod set;
pub mod skip;
mod string;
pub mod struct_;

pub fn write_data<T: Serializer + 'static>(
    record: &T,
    context: &mut WriteContext,
    is_field: bool,
    skip_ref_flag: bool,
    skip_type_info: bool,
) {
    if record.is_none() {
        context.writer.write_i8(RefFlag::Null as i8);
    } else {
        if !skip_ref_flag {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        if !skip_type_info {
            T::write_type_info(context, is_field);
        }
        record.write(context, is_field);
    }
}

pub fn read_data<T: Serializer + Default>(
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
                T::read_type_info(context, is_field);
            }
            T::read(context)
        } else {
            unimplemented!()
        }
    } else {
        if !skip_type_info {
            T::read_type_info(context, is_field);
        }
        T::read(context)
    }
}

pub fn get_skip_ref_flag<T: Serializer>(fory: &Fory) -> bool {
    let elem_type_id = T::get_type_id(fory);
    !T::is_option() && PRIMITIVE_TYPES.contains(&elem_type_id)
}

pub trait Serializer
where
    Self: Sized + Default + 'static,
{
    /// The possible max memory size of the type.
    /// Used to reserve the buffer space to avoid reallocation, which may hurt performance.
    fn reserved_space() -> usize;

    /// Write the data into the buffer.
    fn write(&self, context: &mut WriteContext, is_field: bool);

    fn write_type_info(context: &mut WriteContext, is_field: bool);

    /// Entry point of the serialization.
    ///
    /// Step 1: write the type flag and type flag into the buffer.
    /// Step 2: invoke the write function to write the Rust object.
    fn serialize(&self, context: &mut WriteContext, is_field: bool) {
        write_data(self, context, is_field, false, false);
    }

    fn read(context: &mut ReadContext) -> Result<Self, Error>;

    fn read_type_info(context: &mut ReadContext, is_field: bool);

    fn deserialize(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        read_data(context, is_field, false, false)
    }

    fn get_type_id(_fory: &Fory) -> u32;

    fn is_option() -> bool {
        false
    }

    fn is_none(&self) -> bool {
        false
    }
}

pub trait StructSerializer: Serializer + 'static {
    fn type_def(
        fory: &Fory,
        type_id: u32,
        namespace: &str,
        type_name: &str,
        register_by_name: bool,
    ) -> Vec<u8>;

    fn type_index() -> u32;
    fn actual_type_id(type_id: u32, register_by_name: bool, mode: &Mode) -> u32;

    fn read_compatible(context: &mut ReadContext) -> Result<Self, Error>;

    fn get_sorted_field_names(fory: &Fory) -> Vec<String>;
}
