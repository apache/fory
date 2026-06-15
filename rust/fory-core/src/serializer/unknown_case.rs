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
use crate::error::Error;
use crate::resolver::{RefFlag, RefMode};
use crate::serializer::any::check_erased_any_payload_type;
use crate::serializer::{ForyDefault, Serializer};
use crate::type_id::{self, TypeId};
use crate::types::UnknownCase;
use std::any::Any;
use std::sync::Arc;

#[doc(hidden)]
pub fn write_payload(context: &mut WriteContext, unknown: &UnknownCase) -> Result<(), Error> {
    if write_typed_payload(context, unknown)? {
        return Ok(());
    }
    unknown
        .value_arc()
        .fory_write(context, RefMode::Tracking, true, false)
}

fn write_typed_payload(context: &mut WriteContext, unknown: &UnknownCase) -> Result<bool, Error> {
    let type_id = unknown.type_id();
    if type_id == type_id::UNKNOWN && unknown.downcast_ref::<()>().is_some() {
        context.writer.write_i8(RefFlag::Null as i8);
        return Ok(true);
    }
    if !has_typed_value(unknown) {
        return Ok(false);
    }
    // UnknownCase carriers intentionally keep only a wire type id plus the
    // polymorphic value. For internal numeric ids, the id byte is the complete
    // Any type metadata. Scalar Any payloads are not ref-tracked, so their ref
    // metadata is always NotNullValue before the original numeric encoding.
    // Other types fall back to the normal Arc<dyn Any + Send + Sync> path.
    context.writer.write_i8(RefFlag::NotNullValue as i8);
    context.writer.write_u8(type_id as u8);
    match type_id {
        type_id::BOOL => context
            .writer
            .write_bool(*unknown.downcast_ref::<bool>().unwrap()),
        type_id::INT8 => context
            .writer
            .write_i8(*unknown.downcast_ref::<i8>().unwrap()),
        type_id::INT16 => context
            .writer
            .write_i16(*unknown.downcast_ref::<i16>().unwrap()),
        type_id::INT32 => context
            .writer
            .write_i32(*unknown.downcast_ref::<i32>().unwrap()),
        type_id::VARINT32 => context
            .writer
            .write_var_i32(*unknown.downcast_ref::<i32>().unwrap()),
        type_id::INT64 => context
            .writer
            .write_i64(*unknown.downcast_ref::<i64>().unwrap()),
        type_id::VARINT64 => context
            .writer
            .write_var_i64(*unknown.downcast_ref::<i64>().unwrap()),
        type_id::TAGGED_INT64 => context
            .writer
            .write_tagged_i64(*unknown.downcast_ref::<i64>().unwrap()),
        type_id::UINT8 => context
            .writer
            .write_u8(*unknown.downcast_ref::<u8>().unwrap()),
        type_id::UINT16 => context
            .writer
            .write_u16(*unknown.downcast_ref::<u16>().unwrap()),
        type_id::UINT32 => context
            .writer
            .write_u32(*unknown.downcast_ref::<u32>().unwrap()),
        type_id::VAR_UINT32 => context
            .writer
            .write_var_u32(*unknown.downcast_ref::<u32>().unwrap()),
        type_id::UINT64 => context
            .writer
            .write_u64(*unknown.downcast_ref::<u64>().unwrap()),
        type_id::VAR_UINT64 => context
            .writer
            .write_var_u64(*unknown.downcast_ref::<u64>().unwrap()),
        type_id::TAGGED_UINT64 => context
            .writer
            .write_tagged_u64(*unknown.downcast_ref::<u64>().unwrap()),
        _ => return Ok(false),
    }
    Ok(true)
}

fn has_typed_value(unknown: &UnknownCase) -> bool {
    match unknown.type_id() {
        type_id::BOOL => unknown.downcast_ref::<bool>().is_some(),
        type_id::INT8 => unknown.downcast_ref::<i8>().is_some(),
        type_id::INT16 => unknown.downcast_ref::<i16>().is_some(),
        type_id::INT32 | type_id::VARINT32 => unknown.downcast_ref::<i32>().is_some(),
        type_id::INT64 | type_id::VARINT64 | type_id::TAGGED_INT64 => {
            unknown.downcast_ref::<i64>().is_some()
        }
        type_id::UINT8 => unknown.downcast_ref::<u8>().is_some(),
        type_id::UINT16 => unknown.downcast_ref::<u16>().is_some(),
        type_id::UINT32 | type_id::VAR_UINT32 => unknown.downcast_ref::<u32>().is_some(),
        type_id::UINT64 | type_id::VAR_UINT64 | type_id::TAGGED_UINT64 => {
            unknown.downcast_ref::<u64>().is_some()
        }
        _ => false,
    }
}

#[doc(hidden)]
pub fn read_payload(context: &mut ReadContext, case_id: u32) -> Result<UnknownCase, Error> {
    let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader)?;
    match ref_flag {
        RefFlag::Null => Ok(UnknownCase::new(case_id, ())),
        RefFlag::Ref => {
            let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
            let value = context
                .ref_reader
                .get_arc_ref::<dyn std::any::Any + Send + Sync>(ref_id)
                .ok_or_else(|| {
                    Error::invalid_data(format!("UnknownCase ref {} not found", ref_id))
                })?;
            Ok(UnknownCase::from_runtime(
                case_id,
                TypeId::UNKNOWN as u32,
                value,
            ))
        }
        RefFlag::NotNullValue | RefFlag::RefValue => {
            let ref_id = if matches!(ref_flag, RefFlag::RefValue) {
                // The wire ref id belongs to the unknown payload itself. Reserve it
                // before reading nested payload fields so their own refs keep the
                // same ids written by the normal reference engine.
                Some(context.ref_reader.reserve_ref_id())
            } else {
                None
            };
            // The unknown-case serializer owns only the union payload envelope. It must
            // not add a depth frame here: the decoded Any value is not a new nesting
            // boundary by itself, and real nested payload serializers perform their
            // own depth checks.
            let type_info = context.read_any_type_info()?;
            check_erased_any_payload_type(&type_info)?;
            let boxed = type_info
                .get_harness()
                .read_polymorphic_data_as_send_sync_any(context, &type_info)?;
            let value: Arc<dyn std::any::Any + Send + Sync> = Arc::from(boxed);
            if let Some(ref_id) = ref_id {
                context.ref_reader.store_arc_ref_at(ref_id, value.clone());
            }
            Ok(UnknownCase::from_runtime(
                case_id,
                type_info.get_type_id() as u32,
                value,
            ))
        }
    }
}

impl ForyDefault for UnknownCase {
    fn fory_default() -> Self {
        UnknownCase::new(0, ())
    }
}

impl Serializer for UnknownCase {
    fn fory_write(
        &self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        _has_generics: bool,
    ) -> Result<(), Error> {
        let _ = ref_mode;
        let _ = write_type_info;
        write_payload(context, self)
    }

    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_payload(context, self)
    }

    fn fory_read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        let _ = ref_mode;
        let _ = read_type_info;
        read_payload(context, 0)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        read_payload(context, 0)
    }
    fn fory_read_data_as_send_sync_any(
        context: &mut ReadContext,
    ) -> Result<Box<dyn Any + Send + Sync>, Error> {
        Ok(crate::serializer::box_send_sync(read_payload(context, 0)?))
    }

    fn fory_get_type_id(_: &crate::resolver::TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::UNKNOWN)
    }

    fn fory_type_id_dyn(
        &self,
        _type_resolver: &crate::resolver::TypeResolver,
    ) -> Result<TypeId, Error> {
        Ok(TypeId::UNKNOWN)
    }

    fn fory_static_type_id() -> TypeId {
        TypeId::UNKNOWN
    }

    fn as_any(&self) -> &dyn Any {
        self
    }
}
