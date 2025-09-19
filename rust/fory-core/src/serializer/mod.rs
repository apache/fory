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
use crate::types::RefFlag;
use anyhow::anyhow;

mod any;
mod bool;
pub mod collection;
mod datetime;
mod list;
mod map;
mod number;
mod option;
mod primitive_list;
mod set;
pub mod skip;
mod string;

pub fn serialize<T: Serializer + 'static>(record: &T, context: &mut WriteContext, is_field: bool) {
    if record.is_none() {
        context.writer.i8(RefFlag::Null as i8);
    } else {
        context.writer.i8(RefFlag::NotNullValue as i8);
        record.write(context, is_field);
    }
}

pub fn deserialize<T: Serializer + Default>(
    context: &mut ReadContext,
    is_field: bool,
) -> Result<T, Error> {
    let ref_flag = context.reader.i8();
    if ref_flag == (RefFlag::NotNullValue as i8) || ref_flag == (RefFlag::RefValue as i8) {
        T::read(context, is_field)
    } else if ref_flag == (RefFlag::Null as i8) {
        Ok(T::default())
        // Err(anyhow!("Try to deserialize non-option type to null"))?
    } else if ref_flag == (RefFlag::Ref as i8) {
        Err(Error::Ref)
    } else {
        Err(anyhow!("Unknown ref flag, value:{ref_flag}"))?
    }
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

    /// Entry point of the serialization.
    ///
    /// Step 1: write the type flag and type flag into the buffer.
    /// Step 2: invoke the write function to write the Rust object.
    fn serialize(&self, context: &mut WriteContext, is_field: bool) {
        serialize(self, context, is_field);
    }

    fn read(context: &mut ReadContext, is_field: bool) -> Result<Self, Error>;

    fn deserialize(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        deserialize(context, is_field)
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
        namespace: Vec<u8>,
        type_name: Vec<u8>,
        register_by_name: bool,
    ) -> Vec<u8>;

    fn type_index() -> u32;
    fn actual_type_id(type_id: u32) -> u32;

    fn read_compatible(context: &mut ReadContext) -> Result<Self, Error>;

    fn get_sorted_field_names(fory: &Fory) -> Vec<String>;
}
