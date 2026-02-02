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
use crate::serializer::Serializer;
use crate::types::TypeId;
use crate::types::{is_user_type, ENUM, NAMED_ENUM, UNION};

#[inline(always)]
pub(crate) fn read_basic_type_info<T: Serializer>(context: &mut ReadContext) -> Result<(), Error> {
    let local_type_id = T::fory_get_type_id(context.get_type_resolver())?;
    let remote_type_id = context.reader.read_u8()? as u32;
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
///
/// Keep as const fn for compile time evaluation or constant folding
#[inline]
pub const fn field_need_read_type_info(type_id: u32) -> bool {
    if type_id == ENUM || type_id == NAMED_ENUM || type_id == UNION {
        return false;
    }
    is_user_type(type_id)
}

/// Keep as const fn for compile time evaluation or constant folding
pub const fn field_need_write_type_info(static_type_id: TypeId) -> bool {
    let static_type_id = static_type_id as u32;
    if static_type_id == ENUM || static_type_id == NAMED_ENUM || static_type_id == UNION {
        return false;
    }
    is_user_type(static_type_id)
}

/// Keep as const fn for compile time evaluation or constant folding
///
/// In xlang mode with nullable=false default:
/// - If nullable=true: always need to write ref flag (to handle null values)
/// - If nullable=false: no ref flag needed (value is always present, no null handling required)
///
/// This aligns with the xlang protocol where:
/// - Non-optional types (nullable=false) skip the ref flag entirely
/// - Optional types (nullable=true) write a ref flag to indicate null vs non-null
#[inline]
pub const fn field_need_write_ref_into(_type_id: u32, nullable: bool) -> bool {
    // Only write ref flag when nullable is true (value can be null)
    // When nullable=false, the value is always present, no ref flag needed
    nullable
}

#[inline(always)]
pub fn write_dyn_data_generic<T: Serializer>(
    value: &T,
    context: &mut WriteContext,
    has_generics: bool,
) -> Result<(), Error> {
    let any_value = value.as_any();
    let concrete_type_id = any_value.type_id();
    let serializer_fn = context
        .write_any_typeinfo(T::fory_static_type_id() as u32, concrete_type_id)?
        .get_harness()
        .get_write_data_fn();
    serializer_fn(any_value, context, has_generics)
}
