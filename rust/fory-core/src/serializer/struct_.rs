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
use crate::resolver::context::{ReadContext, WriteContext};
use crate::serializer::{Serializer, StructSerializer};
use crate::types::{RefFlag, RefMode, TypeId};
use crate::util::ENABLE_FORY_DEBUG_OUTPUT;
use std::any::Any;

#[inline(always)]
pub fn actual_type_id(_type_id: u32, register_by_name: bool, compatible: bool) -> u32 {
    if compatible {
        if register_by_name {
            TypeId::NAMED_COMPATIBLE_STRUCT as u32
        } else {
            TypeId::COMPATIBLE_STRUCT as u32
        }
    } else if register_by_name {
        TypeId::NAMED_STRUCT as u32
    } else {
        TypeId::STRUCT as u32
    }
}

#[inline(always)]
pub fn write_type_info<T: Serializer>(context: &mut WriteContext) -> Result<(), Error> {
    let rs_type_id = std::any::TypeId::of::<T>();
    let type_id = T::fory_get_type_id(context.get_type_resolver())?;
    context.write_any_type_info(type_id as u32, rs_type_id)?;
    Ok(())
}

#[inline(always)]
pub fn read_type_info<T: Serializer>(context: &mut ReadContext) -> Result<(), Error> {
    context.read_any_type_info()?;
    Ok(())
}

#[inline(always)]
pub fn read_type_info_fast<T: StructSerializer>(context: &mut ReadContext) -> Result<(), Error> {
    if context.is_compatible() || context.is_xlang() {
        return read_type_info::<T>(context);
    }
    let local_type_id = context
        .get_type_resolver()
        .get_type_id_by_index(T::fory_type_index())?;
    let local_type_id_u32 = local_type_id as u32;
    if !crate::types::needs_user_type_id(local_type_id_u32) {
        return read_type_info::<T>(context);
    }
    let remote_type_id = context.reader.read_u8()? as u32;
    ensure!(
        local_type_id_u32 == remote_type_id,
        Error::type_mismatch(local_type_id_u32, remote_type_id)
    );
    let remote_user_type_id = context.reader.read_varuint32()?;
    let local_user_type_id = context
        .get_type_resolver()
        .get_user_type_id_by_index(&std::any::TypeId::of::<T>(), T::fory_type_index())?;
    if remote_user_type_id != local_user_type_id {
        return Err(Error::type_error(format!(
            "User type id mismatch: local {} vs remote {}",
            local_user_type_id, remote_user_type_id
        )));
    }
    Ok(())
}

#[inline(always)]
pub fn write<T: Serializer>(
    this: &T,
    context: &mut WriteContext,
    ref_mode: RefMode,
    write_type_info: bool,
) -> Result<(), Error> {
    match ref_mode {
        RefMode::None => {}
        RefMode::NullOnly => {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        RefMode::Tracking => {
            // For ref tracking mode, write RefValue flag and reserve a ref_id
            // so this struct participates in reference tracking.
            context.writer.write_i8(RefFlag::RefValue as i8);
            context.ref_writer.reserve_ref_id();
        }
    }
    if write_type_info {
        T::fory_write_type_info(context)?;
    }
    this.fory_write_data(context)
}

pub type BeforeWriteFieldFunc =
    fn(struct_name: &str, field_name: &str, field_value: &dyn Any, context: &mut WriteContext);
pub type AfterWriteFieldFunc =
    fn(struct_name: &str, field_name: &str, field_value: &dyn Any, context: &mut WriteContext);
pub type BeforeReadFieldFunc = fn(struct_name: &str, field_name: &str, context: &mut ReadContext);
pub type AfterReadFieldFunc =
    fn(struct_name: &str, field_name: &str, field_value: &dyn Any, context: &mut ReadContext);

fn default_before_write_field(
    struct_name: &str,
    field_name: &str,
    _field_value: &dyn Any,
    context: &mut WriteContext,
) {
    if ENABLE_FORY_DEBUG_OUTPUT {
        println!(
            "before_write_field:\tstruct={struct_name},\tfield={field_name},\twriter_len={}",
            context.writer.len()
        );
    }
}

fn default_after_write_field(
    struct_name: &str,
    field_name: &str,
    _field_value: &dyn Any,
    context: &mut WriteContext,
) {
    if ENABLE_FORY_DEBUG_OUTPUT {
        println!(
            "after_write_field:\tstruct={struct_name},\tfield={field_name},\twriter_len={}",
            context.writer.len()
        );
    }
}

fn default_before_read_field(struct_name: &str, field_name: &str, context: &mut ReadContext) {
    if ENABLE_FORY_DEBUG_OUTPUT {
        println!(
            "before_read_field:\tstruct={struct_name},\tfield={field_name},\treader_cursor={}",
            context.reader.get_cursor()
        );
    }
}

fn default_after_read_field(
    struct_name: &str,
    field_name: &str,
    _field_value: &dyn Any,
    context: &mut ReadContext,
) {
    if ENABLE_FORY_DEBUG_OUTPUT {
        println!(
            "after_read_field:\tstruct={struct_name},\tfield={field_name},\treader_cursor={}",
            context.reader.get_cursor()
        );
    }
}

static mut BEFORE_WRITE_FIELD_FUNC: BeforeWriteFieldFunc = default_before_write_field;
static mut AFTER_WRITE_FIELD_FUNC: AfterWriteFieldFunc = default_after_write_field;
static mut BEFORE_READ_FIELD_FUNC: BeforeReadFieldFunc = default_before_read_field;
static mut AFTER_READ_FIELD_FUNC: AfterReadFieldFunc = default_after_read_field;

pub fn set_before_write_field_func(func: BeforeWriteFieldFunc) {
    unsafe { BEFORE_WRITE_FIELD_FUNC = func }
}

pub fn set_after_write_field_func(func: AfterWriteFieldFunc) {
    unsafe { AFTER_WRITE_FIELD_FUNC = func }
}

pub fn set_before_read_field_func(func: BeforeReadFieldFunc) {
    unsafe { BEFORE_READ_FIELD_FUNC = func }
}

pub fn set_after_read_field_func(func: AfterReadFieldFunc) {
    unsafe { AFTER_READ_FIELD_FUNC = func }
}

pub fn reset_struct_debug_hooks() {
    unsafe {
        BEFORE_WRITE_FIELD_FUNC = default_before_write_field;
        AFTER_WRITE_FIELD_FUNC = default_after_write_field;
        BEFORE_READ_FIELD_FUNC = default_before_read_field;
        AFTER_READ_FIELD_FUNC = default_after_read_field;
    }
}

/// Debug method to hook into struct serialization
pub fn struct_before_write_field(
    struct_name: &str,
    field_name: &str,
    field_value: &dyn Any,
    context: &mut WriteContext,
) {
    unsafe { BEFORE_WRITE_FIELD_FUNC(struct_name, field_name, field_value, context) }
}

/// Debug method to hook into struct serialization
pub fn struct_after_write_field(
    struct_name: &str,
    field_name: &str,
    field_value: &dyn Any,
    context: &mut WriteContext,
) {
    unsafe { AFTER_WRITE_FIELD_FUNC(struct_name, field_name, field_value, context) }
}

/// Debug method to hook into struct deserialization
pub fn struct_before_read_field(struct_name: &str, field_name: &str, context: &mut ReadContext) {
    unsafe { BEFORE_READ_FIELD_FUNC(struct_name, field_name, context) }
}

/// Debug method to hook into struct deserialization
pub fn struct_after_read_field(
    struct_name: &str,
    field_name: &str,
    field_value: &dyn Any,
    context: &mut ReadContext,
) {
    unsafe { AFTER_READ_FIELD_FUNC(struct_name, field_name, field_value, context) }
}
