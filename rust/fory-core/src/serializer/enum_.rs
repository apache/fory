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

use crate::context::{ReadContext, WriteContext};
use crate::ensure;
use crate::error::Error;
use crate::meta::FieldInfo;
use crate::resolver::{RefFlag, RefMode, TypeResolver};
use crate::serializer::Serializer;
use crate::type_id::TypeId;

#[inline(always)]
pub fn actual_type_id(_type_id: u32, register_by_name: bool, _compatible: bool) -> u32 {
    if register_by_name {
        TypeId::NAMED_ENUM as u32
    } else {
        TypeId::ENUM as u32
    }
}

#[inline(always)]
pub fn write<S: Serializer>(
    value: &S::Target,
    context: &mut WriteContext,
    ref_mode: RefMode,
    write_type_info: bool,
) -> Result<(), Error> {
    if ref_mode != RefMode::None {
        context.writer.write_i8(RefFlag::NotNullValue as i8);
    }
    if write_type_info {
        S::write_type_info(context)?;
    }
    S::write_data(value, context)
}

#[inline(always)]
pub fn write_type_info<S: Serializer>(context: &mut WriteContext) -> Result<(), Error> {
    let provider_type_id = std::any::TypeId::of::<S>();
    let type_info = context
        .get_type_resolver()
        .get_provider_type_info(&provider_type_id)?;
    let type_id = type_info.get_type_id();
    context.writer.write_u8(type_id as u8);
    if type_id == TypeId::ENUM {
        context.writer.write_var_u32(type_info.get_user_type_id());
        return Ok(());
    }
    if context.is_share_meta() {
        // Write type meta inline using streaming protocol
        context.write_type_meta(provider_type_id)?;
    } else {
        let namespace = type_info.get_namespace();
        let type_name = type_info.get_type_name();
        context.write_meta_string_bytes(namespace)?;
        context.write_meta_string_bytes(type_name)?;
    }
    Ok(())
}

#[inline(always)]
pub fn read<S: Serializer>(
    context: &mut ReadContext,
    ref_mode: RefMode,
    read_type_info: bool,
) -> Result<S::Target, Error> {
    let ref_flag = if ref_mode != RefMode::None {
        context.reader.read_i8()?
    } else {
        RefFlag::NotNullValue as i8
    };
    if ref_flag == RefFlag::Null as i8 {
        S::default_value(context)
    } else if ref_flag == (RefFlag::NotNullValue as i8) || ref_flag == (RefFlag::RefValue as i8) {
        if read_type_info {
            S::read_type_info(context)?;
        }
        S::read_data(context)
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
pub fn read_type_info<S: Serializer>(context: &mut ReadContext) -> Result<(), Error> {
    let local_type_id = context
        .get_type_resolver()
        .get_provider_type_info(&std::any::TypeId::of::<S>())?
        .get_type_id();
    let remote_type_id = context.reader.read_u8()?;
    ensure!(
        local_type_id as u8 == remote_type_id,
        Error::type_mismatch(local_type_id as u32, remote_type_id as u32)
    );
    if remote_type_id == TypeId::NAMED_ENUM as u8 {
        if context.is_share_meta() {
            // Read type meta inline using streaming protocol
            let _type_info = context.read_type_meta()?;
        } else {
            let _namespace_msb = context.read_meta_string()?;
            let _type_name_msb = context.read_meta_string()?;
        }
    } else {
        context.reader.read_var_u32()?;
    }
    Ok(())
}

pub trait NamedEnumVariantMetaTrait: 'static {
    fn sorted_field_names() -> &'static [&'static str] {
        &[]
    }

    #[allow(unused_variables)]
    fn fields_info(type_resolver: &TypeResolver) -> Result<Vec<FieldInfo>, Error> {
        Ok(Vec::default())
    }
}
