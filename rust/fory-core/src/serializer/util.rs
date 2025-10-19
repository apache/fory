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
use crate::serializer::{bool, Serializer};
use crate::types::TypeId;
use std::any::Any;

#[inline(always)]
pub(crate) fn read_basic_type_info<T: Serializer>(context: &mut ReadContext) -> Result<(), Error> {
    let local_type_id = T::fory_get_type_id(context.get_type_resolver())?;
    let remote_type_id = context.reader.read_varuint32()?;
    ensure!(
        local_type_id == remote_type_id,
        Error::type_mismatch(local_type_id, remote_type_id)
    );
    Ok(())
}

/// Check at runtime whether type info should be skipped for a given type id.
///
/// According to xlang_serialization_spec.md:
/// - For enums (ENUM/NAMED_ENUM), we should skip writing type info
/// - For structs and ext types, we should write type info
#[inline]
pub fn should_skip_type_info_at_runtime(type_id: u32) -> bool {
    let internal_type_id = (type_id & 0xff) as i8;
    if internal_type_id == TypeId::ENUM as i8 || internal_type_id == TypeId::NAMED_ENUM as i8 {
        true
    } else {
        false
    }
}

#[inline]
pub fn field_requires_ref_flag(type_id: u32, nullable: bool) -> bool {
    if nullable {
        return true;
    }
    let internal_type_id = (type_id & 0xff) as u32;
    match internal_type_id {
        x if x == TypeId::BOOL as u32
            || x == TypeId::INT8 as u32
            || x == TypeId::INT16 as u32
            || x == TypeId::INT32 as u32
            || x == TypeId::INT64 as u32
            || x == TypeId::FLOAT32 as u32
            || x == TypeId::FLOAT64 as u32 =>
        {
            false
        }
        _ => true,
    }
}

#[inline(always)]
pub fn write_dyn_data_generic<T: Serializer>(
    value: &T,
    context: &mut WriteContext,
    has_generics: bool,
) -> Result<(), Error> {
    let concrete_type_id: std::any::TypeId = value.type_id();
    let serializer_fn = context
        .write_any_typeinfo(concrete_type_id)?
        .get_harness()
        .get_write_data_fn();
    serializer_fn(value, context, has_generics)
}
