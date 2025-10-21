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
use crate::serializer::{ForyDefault, Serializer};
use crate::types::{RefFlag, TypeId};

#[inline(always)]
pub fn actual_type_id(type_id: u32, register_by_name: bool, _compatible: bool) -> u32 {
    if register_by_name {
        TypeId::NAMED_ENUM as u32
    } else {
        (type_id << 8) + TypeId::ENUM as u32
    }
}

#[inline(always)]
pub fn write<T: Serializer>(
    this: &T,
    context: &mut WriteContext,
    write_ref_info: bool,
    write_type_info: bool,
) -> Result<(), Error> {
    if write_ref_info {
        context.writer.write_i8(RefFlag::NotNullValue as i8);
    }
    if write_type_info {
        T::fory_write_type_info(context)?;
    }
    this.fory_write_data(context)
}

#[inline(always)]
pub fn write_type_info<T: Serializer>(context: &mut WriteContext) -> Result<(), Error> {
    let type_id = T::fory_get_type_id(context.get_type_resolver())?;
    context.writer.write_varuint32(type_id);
    let is_named_enum = type_id & 0xff == TypeId::NAMED_ENUM as u32;
    if !is_named_enum {
        return Ok(());
    }
    let rs_type_id = std::any::TypeId::of::<T>();
    if context.is_share_meta() {
        let meta_index = context.push_meta(rs_type_id)? as u32;
        context.writer.write_varuint32(meta_index);
    } else {
        let type_info = context.get_type_resolver().get_type_info(&rs_type_id)?;
        let namespace = type_info.get_namespace();
        let type_name = type_info.get_type_name();
        context.write_meta_string_bytes(namespace)?;
        context.write_meta_string_bytes(type_name)?;
    }
    Ok(())
}

#[inline(always)]
pub fn read<T: Serializer + ForyDefault>(
    context: &mut ReadContext,
    read_ref_info: bool,
    read_type_info: bool,
) -> Result<T, Error> {
    let ref_flag = if read_ref_info {
        context.reader.read_i8()?
    } else {
        RefFlag::NotNullValue as i8
    };
    if ref_flag == RefFlag::Null as i8 {
        Ok(T::fory_default())
    } else if ref_flag == (RefFlag::NotNullValue as i8) || ref_flag == (RefFlag::RefValue as i8) {
        if read_type_info {
            T::fory_read_type_info(context)?;
        }
        T::fory_read_data(context)
    } else if ref_flag == (RefFlag::Ref as i8) {
        Err(Error::invalid_ref("Invalid ref, enum type is not a ref"))
    } else {
        Err(Error::invalid_data(format!(
            "Unknown ref flag: {}",
            ref_flag
        )))
    }
}

#[inline(always)]
pub fn read_type_info<T: Serializer>(context: &mut ReadContext) -> Result<(), Error> {
    let local_type_id = T::fory_get_type_id(context.get_type_resolver())?;
    let remote_type_id = context.reader.read_varuint32()?;
    ensure!(
        local_type_id == remote_type_id,
        Error::type_mismatch(local_type_id, remote_type_id)
    );
    let is_named_enum = local_type_id & 0xff == TypeId::NAMED_ENUM as u32;
    if !is_named_enum {
        return Ok(());
    }
    if context.is_share_meta() {
        let _meta_index = context.reader.read_varuint32()?;
    } else {
        let _namespace_msb = context.read_meta_string()?;
        let _type_name_msb = context.read_meta_string()?;
    }
    Ok(())
}
