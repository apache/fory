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
use crate::resolver::{RefFlag, RefMode, TypeInfo};
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use std::any::Any;
use std::rc::Rc;
use std::sync::Arc;

#[inline]
fn is_erased_any_container_type(type_id: TypeId) -> bool {
    matches!(
        type_id,
        TypeId::LIST
            | TypeId::SET
            | TypeId::MAP
            | TypeId::BINARY
            | TypeId::ARRAY
            | TypeId::BOOL_ARRAY
            | TypeId::INT8_ARRAY
            | TypeId::INT16_ARRAY
            | TypeId::INT32_ARRAY
            | TypeId::INT64_ARRAY
            | TypeId::UINT8_ARRAY
            | TypeId::UINT16_ARRAY
            | TypeId::UINT32_ARRAY
            | TypeId::UINT64_ARRAY
            | TypeId::FLOAT8_ARRAY
            | TypeId::FLOAT16_ARRAY
            | TypeId::BFLOAT16_ARRAY
            | TypeId::FLOAT32_ARRAY
            | TypeId::FLOAT64_ARRAY
            | TypeId::U128_ARRAY
            | TypeId::INT128_ARRAY
            | TypeId::USIZE_ARRAY
            | TypeId::ISIZE_ARRAY
    )
}

#[cold]
#[inline(never)]
fn unsupported_erased_any_container() -> Error {
    Error::type_error(
        "built-in list, set, map, binary, and primitive-array values cannot be top-level erased \
         Any or application-trait payloads; register an exact custom EXT serializer for the whole \
         target or wrap it in a registered structural type",
    )
}

#[cold]
#[inline(never)]
fn erased_any_type_info_error(err: Error) -> Error {
    Error::type_error(format!(
        "{err}. Erased Any payloads require a registered concrete target"
    ))
}

#[cold]
#[inline(never)]
fn missing_dynamic_target() -> Error {
    Error::type_error("dynamic target metadata has no checked local serializer registration")
}

#[cold]
#[inline(never)]
fn mismatched_dynamic_target(expected: std::any::TypeId, actual: std::any::TypeId) -> Error {
    Error::type_error(format!(
        "dynamic target metadata expected TypeId {:?}, got {:?}",
        expected, actual,
    ))
}

#[cold]
#[inline(never)]
fn missing_any_ref(owner: &'static str, ref_id: u32) -> Error {
    Error::invalid_data(format!("{owner} reference {ref_id} not found"))
}

#[cold]
#[inline(never)]
fn box_any_null() -> Error {
    Error::invalid_ref("Box<dyn Any> cannot be null")
}

#[cold]
#[inline(never)]
fn box_any_metadata() -> Error {
    Error::invalid_data("Box<dyn Any> requires concrete type metadata")
}

#[cold]
#[inline(never)]
fn rc_any_null() -> Error {
    Error::invalid_ref("Rc<dyn Any> cannot be null")
}

#[cold]
#[inline(never)]
fn rc_any_metadata() -> Error {
    Error::invalid_data("Rc<dyn Any> requires concrete type metadata")
}

#[cold]
#[inline(never)]
fn arc_any_null() -> Error {
    Error::invalid_ref("Arc<dyn Any + Send + Sync> cannot be null")
}

#[cold]
#[inline(never)]
fn arc_any_metadata() -> Error {
    Error::invalid_data("Arc<dyn Any + Send + Sync> requires concrete type metadata")
}

#[doc(hidden)]
#[inline]
pub fn check_erased_target_type(type_info: &TypeInfo) -> Result<(), Error> {
    if is_erased_any_container_type(type_info.get_type_id()) {
        return Err(unsupported_erased_any_container());
    }
    Ok(())
}

#[inline(always)]
fn check_local_target(type_info: &TypeInfo) -> Result<(), Error> {
    type_info
        .get_harness()
        .target_type_id()
        .ok_or_else(missing_dynamic_target)
        .map(|_| ())
}

#[inline(always)]
fn check_resolved_target(
    target_type_id: std::any::TypeId,
    type_info: &TypeInfo,
) -> Result<(), Error> {
    check_erased_target_type(type_info)?;
    let resolved_target_type_id = type_info
        .get_harness()
        .target_type_id()
        .ok_or_else(missing_dynamic_target)?;
    if resolved_target_type_id != target_type_id {
        return Err(mismatched_dynamic_target(
            resolved_target_type_id,
            target_type_id,
        ));
    }
    Ok(())
}

#[inline]
fn get_erased_any_type_info(
    context: &WriteContext,
    target_type_id: &std::any::TypeId,
) -> Result<Rc<TypeInfo>, Error> {
    let type_info = context
        .get_target_type_info(target_type_id)
        .map_err(erased_any_type_info_error)?;
    check_erased_target_type(&type_info)?;
    Ok(type_info)
}

#[inline]
fn write_erased_any_type_info(
    context: &mut WriteContext,
    target_type_id: std::any::TypeId,
) -> Result<Rc<TypeInfo>, Error> {
    let type_info = context
        .get_target_type_info(&target_type_id)
        .map_err(erased_any_type_info_error)?;
    check_erased_target_type(&type_info)?;
    context.write_resolved_type_info(TypeId::UNKNOWN as u32, type_info)
}

#[inline(always)]
fn write_any_body(value: &dyn Any, context: &mut WriteContext) -> Result<(), Error> {
    let type_info = get_erased_any_type_info(context, &value.type_id())?;
    write_resolved_any_body(value, context, &type_info)
}

#[inline(always)]
fn write_resolved_any_body(
    value: &dyn Any,
    context: &mut WriteContext,
    type_info: &Rc<TypeInfo>,
) -> Result<(), Error> {
    check_resolved_target(value.type_id(), type_info)?;
    write_any_harness(value, context, type_info)
}

#[inline(always)]
fn write_any_harness(
    value: &dyn Any,
    context: &mut WriteContext,
    type_info: &Rc<TypeInfo>,
) -> Result<(), Error> {
    type_info.get_harness().write_data(value, context)
}

/// Reads a non-null `Box<dyn Any>` with concrete type metadata.
pub fn deserialize_any_box(context: &mut ReadContext) -> Result<Box<dyn Any>, Error> {
    read_box_any(context, RefMode::NullOnly, true, None)
}

impl Serializer for Box<dyn Any> {
    type Target = Self;
    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        write_any_body(value.as_ref(), context)
    }

    #[cold]
    #[inline(never)]
    fn read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Err(Error::not_allowed(
            "Box<dyn Any> requires concrete type metadata",
        ))
    }

    #[inline(always)]
    fn write_type_info_value(
        context: &mut WriteContext,
        target_type_id: std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        write_erased_any_type_info(context, target_type_id)
    }

    #[inline(always)]
    fn write_with_type_info(
        value: &Self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        write_box_any_resolved(value.as_ref(), context, ref_mode, type_info)
    }

    #[inline(always)]
    fn write(
        value: &Self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        write_box_any(value.as_ref(), context, ref_mode, write_type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_box_any(context, ref_mode, read_type_info, None)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Self, Error> {
        read_box_any(context, ref_mode, false, Some(type_info))
    }

    #[inline(always)]
    fn write_type_info(_: &mut WriteContext) -> Result<(), Error> {
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(_: &mut ReadContext) -> Result<(), Error> {
        Ok(())
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::UNKNOWN
    }

    const IS_POLYMORPHIC: bool = true;

    const IS_WRAPPER: bool = true;

    #[inline(always)]
    fn dynamic_type_id(value: &Self) -> Result<Option<std::any::TypeId>, Error> {
        Ok(Some(value.as_ref().type_id()))
    }
}

pub fn write_box_any(
    value: &dyn Any,
    context: &mut WriteContext,
    ref_mode: RefMode,
    write_type_info: bool,
) -> Result<(), Error> {
    let target_type_id = value.type_id();
    let type_info = get_erased_any_type_info(context, &target_type_id)?;
    if ref_mode != RefMode::None {
        context.writer.write_i8(RefFlag::NotNullValue as i8);
    }
    let type_info = if write_type_info {
        context.write_resolved_type_info(TypeId::UNKNOWN as u32, type_info)?
    } else {
        type_info
    };
    write_any_harness(value, context, &type_info)
}

#[inline(always)]
fn write_box_any_resolved(
    value: &dyn Any,
    context: &mut WriteContext,
    ref_mode: RefMode,
    type_info: &Rc<TypeInfo>,
) -> Result<(), Error> {
    check_resolved_target(value.type_id(), type_info)?;
    if ref_mode != RefMode::None {
        context.writer.write_i8(RefFlag::NotNullValue as i8);
    }
    write_any_harness(value, context, type_info)
}

pub fn read_box_any(
    context: &mut ReadContext,
    ref_mode: RefMode,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<Box<dyn Any>, Error> {
    context.inc_depth()?;
    let value = (|| {
        let ref_flag = if ref_mode != RefMode::None {
            context.reader.read_i8()?
        } else {
            RefFlag::NotNullValue as i8
        };
        if ref_flag != RefFlag::NotNullValue as i8 {
            return Err(box_any_null());
        }
        let owned_type_info;
        let type_info = if let Some(type_info) = type_info {
            type_info
        } else if read_type_info {
            owned_type_info = context.read_any_type_info()?;
            &owned_type_info
        } else {
            return Err(box_any_metadata());
        };
        check_local_target(type_info)?;
        check_erased_target_type(type_info)?;
        type_info.get_harness().read_box_any(context, type_info)
    })()?;
    context.dec_depth();
    Ok(value)
}

impl Serializer for Rc<dyn Any> {
    type Target = Self;
    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        write_any_body(value.as_ref(), context)
    }

    #[cold]
    #[inline(never)]
    fn read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Err(Error::not_allowed(
            "Rc<dyn Any> requires concrete type metadata",
        ))
    }

    #[inline(always)]
    fn write_type_info_value(
        context: &mut WriteContext,
        target_type_id: std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        write_erased_any_type_info(context, target_type_id)
    }

    #[inline(always)]
    fn write_with_type_info(
        value: &Self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        write_rc_any_resolved(value, context, ref_mode, type_info)
    }

    #[inline(always)]
    fn write(
        value: &Self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        if ref_mode != RefMode::None
            && context
                .ref_writer
                .try_write_rc_ref(&mut context.writer, value)
        {
            return Ok(());
        }
        let target_type_id = value.as_ref().type_id();
        let type_info = get_erased_any_type_info(context, &target_type_id)?;
        let type_info = if write_type_info {
            context.write_resolved_type_info(TypeId::UNKNOWN as u32, type_info)?
        } else {
            type_info
        };
        write_any_harness(value.as_ref(), context, &type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_rc_any(context, ref_mode, read_type_info, None)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Self, Error> {
        read_rc_any(context, ref_mode, false, Some(type_info))
    }

    #[inline(always)]
    fn write_type_info(_: &mut WriteContext) -> Result<(), Error> {
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(_: &mut ReadContext) -> Result<(), Error> {
        Ok(())
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::UNKNOWN
    }

    const IS_SHARED_REF: bool = true;

    const IS_POLYMORPHIC: bool = true;

    const IS_WRAPPER: bool = true;

    #[inline(always)]
    fn dynamic_type_id(value: &Self) -> Result<Option<std::any::TypeId>, Error> {
        Ok(Some(value.as_ref().type_id()))
    }
}

#[inline(always)]
fn write_rc_any_resolved(
    value: &Rc<dyn Any>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    type_info: &Rc<TypeInfo>,
) -> Result<(), Error> {
    let any = value.as_ref();
    check_resolved_target(any.type_id(), type_info)?;
    if ref_mode != RefMode::None
        && context
            .ref_writer
            .try_write_rc_ref(&mut context.writer, value)
    {
        return Ok(());
    }
    write_any_harness(any, context, type_info)
}

pub fn read_rc_any(
    context: &mut ReadContext,
    ref_mode: RefMode,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<Rc<dyn Any>, Error> {
    let ref_flag = if ref_mode != RefMode::None {
        context.ref_reader.read_ref_flag(&mut context.reader)?
    } else {
        RefFlag::NotNullValue
    };
    match ref_flag {
        RefFlag::Null => Err(rc_any_null()),
        RefFlag::Ref => {
            let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
            context
                .ref_reader
                .get_rc_ref::<dyn Any>(ref_id)
                .ok_or_else(|| missing_any_ref("Rc<dyn Any>", ref_id))
        }
        RefFlag::NotNullValue => read_new_rc_any(context, read_type_info, type_info),
        RefFlag::RefValue => {
            let ref_id = context.ref_reader.reserve_ref_id();
            let value = read_new_rc_any(context, read_type_info, type_info)?;
            context.ref_reader.store_rc_ref_at(ref_id, value.clone());
            Ok(value)
        }
    }
}

fn read_new_rc_any(
    context: &mut ReadContext,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<Rc<dyn Any>, Error> {
    context.inc_depth()?;
    let value = (|| {
        let owned_type_info;
        let type_info = if read_type_info {
            owned_type_info = context.read_any_type_info()?;
            &owned_type_info
        } else {
            type_info.ok_or_else(rc_any_metadata)?
        };
        check_local_target(type_info)?;
        check_erased_target_type(type_info)?;
        type_info.get_harness().read_rc_any(context, type_info)
    })()?;
    context.dec_depth();
    Ok(value)
}

impl Serializer for Arc<dyn Any + Send + Sync> {
    type Target = Self;
    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        write_any_body(value.as_ref(), context)
    }

    #[cold]
    #[inline(never)]
    fn read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Err(Error::not_allowed(
            "Arc<dyn Any + Send + Sync> requires concrete type metadata",
        ))
    }

    #[inline(always)]
    fn write_type_info_value(
        context: &mut WriteContext,
        target_type_id: std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        write_erased_any_type_info(context, target_type_id)
    }

    #[inline(always)]
    fn write_with_type_info(
        value: &Self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        write_arc_any_resolved(value, context, ref_mode, type_info)
    }

    #[inline(always)]
    fn write(
        value: &Self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        if ref_mode != RefMode::None
            && context
                .ref_writer
                .try_write_arc_ref(&mut context.writer, value)
        {
            return Ok(());
        }
        let target_type_id = value.as_ref().type_id();
        let type_info = get_erased_any_type_info(context, &target_type_id)?;
        let type_info = if write_type_info {
            context.write_resolved_type_info(TypeId::UNKNOWN as u32, type_info)?
        } else {
            type_info
        };
        write_any_harness(value.as_ref(), context, &type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_arc_any(context, ref_mode, read_type_info, None)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Self, Error> {
        read_arc_any(context, ref_mode, false, Some(type_info))
    }

    #[inline(always)]
    fn write_type_info(_: &mut WriteContext) -> Result<(), Error> {
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(_: &mut ReadContext) -> Result<(), Error> {
        Ok(())
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::UNKNOWN
    }

    const IS_SHARED_REF: bool = true;

    const IS_POLYMORPHIC: bool = true;

    const IS_WRAPPER: bool = true;

    #[inline(always)]
    fn dynamic_type_id(value: &Self) -> Result<Option<std::any::TypeId>, Error> {
        Ok(Some(value.as_ref().type_id()))
    }
}

#[inline(always)]
fn write_arc_any_resolved(
    value: &Arc<dyn Any + Send + Sync>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    type_info: &Rc<TypeInfo>,
) -> Result<(), Error> {
    let any = value.as_ref();
    check_resolved_target(any.type_id(), type_info)?;
    if ref_mode != RefMode::None
        && context
            .ref_writer
            .try_write_arc_ref(&mut context.writer, value)
    {
        return Ok(());
    }
    write_any_harness(any, context, type_info)
}

pub fn read_arc_any(
    context: &mut ReadContext,
    ref_mode: RefMode,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<Arc<dyn Any + Send + Sync>, Error> {
    let ref_flag = if ref_mode != RefMode::None {
        context.ref_reader.read_ref_flag(&mut context.reader)?
    } else {
        RefFlag::NotNullValue
    };
    match ref_flag {
        RefFlag::Null => Err(arc_any_null()),
        RefFlag::Ref => {
            let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
            context
                .ref_reader
                .get_arc_ref::<dyn Any + Send + Sync>(ref_id)
                .ok_or_else(|| missing_any_ref("Arc<dyn Any + Send + Sync>", ref_id))
        }
        RefFlag::NotNullValue => read_new_arc_any(context, read_type_info, type_info),
        RefFlag::RefValue => {
            let ref_id = context.ref_reader.reserve_ref_id();
            let value = read_new_arc_any(context, read_type_info, type_info)?;
            context.ref_reader.store_arc_ref_at(ref_id, value.clone());
            Ok(value)
        }
    }
}

fn read_new_arc_any(
    context: &mut ReadContext,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<Arc<dyn Any + Send + Sync>, Error> {
    context.inc_depth()?;
    let value = (|| {
        let owned_type_info;
        let type_info = if read_type_info {
            owned_type_info = context.read_any_type_info()?;
            &owned_type_info
        } else {
            type_info.ok_or_else(arc_any_metadata)?
        };
        check_local_target(type_info)?;
        check_erased_target_type(type_info)?;
        type_info.get_harness().read_arc_any(context, type_info)
    })()?;
    context.dec_depth();
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Config, Reader, TypeResolver};

    #[test]
    fn failed_depth_waits_for_reset() {
        let config = Config {
            max_dyn_depth: 1,
            ..Default::default()
        };
        let mut context = ReadContext::new(TypeResolver::default(), config);
        let null = [RefFlag::Null as i8 as u8];
        context.attach_reader(Reader::new(&null));

        let error = read_box_any(&mut context, RefMode::Tracking, false, None).unwrap_err();
        assert!(matches!(error, Error::InvalidRef(_)));
        let error = read_box_any(&mut context, RefMode::Tracking, false, None).unwrap_err();
        assert!(matches!(error, Error::DepthExceed(_)));

        context.reset();
        context.attach_reader(Reader::new(&null));
        let error = read_box_any(&mut context, RefMode::Tracking, false, None).unwrap_err();
        assert!(matches!(error, Error::InvalidRef(_)));
    }
}
