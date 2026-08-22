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

use super::codec::{
    codec_read_type_info, codec_read_type_info_static, codec_ref_mode, codec_write_type_info,
    field_ref_mode, Codec,
};
use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::meta::FieldType;
use crate::resolver::{RefFlag, RefMode, TypeInfo, TypeResolver};
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use std::marker::PhantomData;
use std::rc::Rc;

pub struct RcCodec<T, C, const NULLABLE: bool, const TRACK_REF: bool>(PhantomData<(T, C)>);

#[cold]
#[inline(never)]
fn shared_rc_child() -> Error {
    Error::not_allowed("Rc<T> where T is a shared ref type is not allowed")
}

#[cold]
#[inline(never)]
fn missing_rc_ref(ref_id: u32) -> Error {
    Error::invalid_ref(format!("Rc reference {ref_id} not found"))
}

#[inline(always)]
fn check_child<T: 'static, C: Serializer<Target = T>>() -> Result<(), Error> {
    // Nested shared owners would compete for reference framing and identity
    // while both wrappers remain transparent on the wire.
    if C::IS_SHARED_REF {
        Err(shared_rc_child())
    } else {
        Ok(())
    }
}

#[inline(always)]
fn reserve_rc<T>(context: &mut ReadContext) -> Result<(), Error> {
    let bytes = std::mem::size_of::<T>();
    if bytes != 0 {
        context.reserve_graph_memory(bytes)?;
    }
    Ok(())
}

#[inline(always)]
fn write_inner<T: 'static, C: Serializer<Target = T>>(
    value: &T,
    context: &mut WriteContext,
    write_type_info: bool,
) -> Result<(), Error> {
    check_child::<T, C>()?;
    C::write(value, context, RefMode::None, write_type_info)
}

#[inline(always)]
fn write_inner_with_type_info<T: 'static, C: Serializer<Target = T>>(
    value: &T,
    context: &mut WriteContext,
    type_info: &Rc<TypeInfo>,
) -> Result<(), Error> {
    check_child::<T, C>()?;
    C::write_with_type_info(value, context, RefMode::None, type_info)
}

#[inline(always)]
fn write_inner_field<T: 'static, C: Codec<T>>(
    value: &T,
    context: &mut WriteContext,
    write_type_info: bool,
    has_generics: bool,
) -> Result<(), Error> {
    check_child::<T, C>()?;
    C::write_with_mode(value, context, RefMode::None, write_type_info, has_generics)
}

#[inline(always)]
fn write_inner_field_with_type_info<T: 'static, C: Codec<T>>(
    value: &T,
    context: &mut WriteContext,
    type_info: &Rc<TypeInfo>,
    has_generics: bool,
) -> Result<(), Error> {
    check_child::<T, C>()?;
    <C as Codec<T>>::write_with_type_info(value, context, RefMode::None, type_info, has_generics)
}

#[inline(always)]
fn write_ref<T>(value: &Rc<T>, context: &mut WriteContext, ref_mode: RefMode) -> bool {
    match ref_mode {
        RefMode::None => true,
        RefMode::NullOnly => {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
            true
        }
        RefMode::Tracking => !context
            .ref_writer
            .try_write_rc_ref(&mut context.writer, value),
    }
}

#[inline(always)]
fn read_inner<T: 'static, C: Serializer<Target = T>>(
    context: &mut ReadContext,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<T, Error> {
    check_child::<T, C>()?;
    reserve_rc::<T>(context)?;
    if let Some(type_info) = type_info {
        return C::read_with_type_info(context, RefMode::None, type_info);
    }
    if read_type_info {
        C::read_type_info(context)?;
    }
    C::read_data(context)
}

#[inline(always)]
fn read_inner_with_type<T: 'static, C: Codec<T>>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<T, Error> {
    check_child::<T, C>()?;
    reserve_rc::<T>(context)?;
    // The Rc envelope owns only reference framing. A compatible
    // metadata-bearing child still owns its inline TypeInfo before its body,
    // while declared carrier children consume the remote schema directly.
    if codec_read_type_info::<T, C>(context, remote_field_type) {
        return C::read(context, RefMode::None, true);
    }
    C::read_data_with_type(context, remote_field_type)
}

impl<T, C, const NULLABLE: bool, const TRACK_REF: bool> Serializer
    for RcCodec<T, C, NULLABLE, TRACK_REF>
where
    T: 'static,
    C: Serializer<Target = T>,
{
    type Target = Rc<T>;

    #[inline(always)]
    fn reserved_space() -> usize {
        4
    }

    #[inline(always)]
    fn write_data(value: &Rc<T>, context: &mut WriteContext) -> Result<(), Error> {
        write_inner::<T, C>(value, context, false)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Rc<T>, Error> {
        Ok(Rc::new(read_inner::<T, C>(context, false, None)?))
    }

    #[inline(always)]
    fn write(
        value: &Rc<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        if !write_ref(value, context, ref_mode) {
            return Ok(());
        }
        write_inner::<T, C>(value, context, write_type_info)
    }

    #[inline(always)]
    fn write_type_info_value(
        context: &mut WriteContext,
        target_type_id: std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        C::write_type_info_value(context, target_type_id)
    }

    #[inline(always)]
    fn write_with_type_info(
        value: &Rc<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        if !write_ref(value, context, ref_mode) {
            return Ok(());
        }
        write_inner_with_type_info::<T, C>(value, context, type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Rc<T>, Error> {
        read_rc::<T, C>(context, ref_mode, read_type_info, None)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Rc<T>, Error> {
        read_rc::<T, C>(context, ref_mode, false, Some(type_info))
    }

    #[inline(always)]
    fn default_value(context: &mut ReadContext) -> Result<Rc<T>, Error> {
        check_child::<T, C>()?;
        reserve_rc::<T>(context)?;
        Ok(Rc::new(C::default_value(context)?))
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        C::write_type_info(context)
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        C::read_type_info(context)
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        C::static_type_id()
    }

    #[inline(always)]
    fn metadata_target_type_id() -> std::any::TypeId {
        C::metadata_target_type_id()
    }

    const IS_POLYMORPHIC: bool = C::IS_POLYMORPHIC;

    const IS_SHARED_REF: bool = true;

    const IS_WRAPPER: bool = true;

    const REQUIRES_SCOPED_ACCESS: bool = C::REQUIRES_SCOPED_ACCESS;

    #[inline(always)]
    fn dynamic_type_id(value: &Rc<T>) -> Result<Option<std::any::TypeId>, Error> {
        C::dynamic_type_id(value)
    }
}

impl<T, C, const NULLABLE: bool, const TRACK_REF: bool> Codec<Rc<T>>
    for RcCodec<T, C, NULLABLE, TRACK_REF>
where
    T: 'static,
    C: Codec<T>,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        let mut field_type = C::field_type(type_resolver)?;
        field_type.nullable = NULLABLE;
        field_type.track_ref = TRACK_REF;
        Ok(field_type)
    }

    #[inline(always)]
    fn write_field(value: &Rc<T>, context: &mut WriteContext) -> Result<(), Error> {
        Self::write_with_mode(
            value,
            context,
            codec_ref_mode::<T, C, NULLABLE, TRACK_REF>(),
            codec_write_type_info::<T, C>(context),
            true,
        )
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<Rc<T>, Error> {
        <Self as Serializer>::read(
            context,
            codec_ref_mode::<T, C, NULLABLE, TRACK_REF>(),
            codec_read_type_info_static::<T, C>(context),
        )
    }

    #[inline(always)]
    fn read_data_with_type(
        context: &mut ReadContext,
        remote_data_type: &FieldType,
    ) -> Result<Rc<T>, Error> {
        check_child::<T, C>()?;
        reserve_rc::<T>(context)?;
        Ok(Rc::new(C::read_data_with_type(context, remote_data_type)?))
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<Rc<T>, Error> {
        read_rc_with_type::<T, C>(
            context,
            field_ref_mode(remote_field_type),
            remote_field_type,
        )
    }

    #[inline(always)]
    fn write_with_mode(
        value: &Rc<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if !write_ref(value, context, ref_mode) {
            return Ok(());
        }
        write_inner_field::<T, C>(value, context, write_type_info, has_generics)
    }

    #[inline(always)]
    fn write_with_type_info(
        value: &Rc<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
        has_generics: bool,
    ) -> Result<(), Error> {
        if !write_ref(value, context, ref_mode) {
            return Ok(());
        }
        write_inner_field_with_type_info::<T, C>(value, context, type_info, has_generics)
    }

    #[inline(always)]
    fn read_type_info_value(
        context: &mut ReadContext,
    ) -> Result<super::codec::CodecReadType, Error> {
        C::read_type_info_value(context)
    }
}

macro_rules! read_rc_owner {
    ($context:ident, $ref_mode:expr, $read_inner:expr, $default:expr) => {
        match $ref_mode {
            RefMode::None => Ok(Rc::new($read_inner?)),
            RefMode::NullOnly => {
                if $context.reader.read_i8()? == RefFlag::Null as i8 {
                    return $default;
                }
                Ok(Rc::new($read_inner?))
            }
            RefMode::Tracking => match $context.ref_reader.read_ref_flag(&mut $context.reader)? {
                RefFlag::Null => $default,
                RefFlag::Ref => {
                    let ref_id = $context.ref_reader.read_ref_id(&mut $context.reader)?;
                    $context
                        .ref_reader
                        .get_rc_ref::<T>(ref_id)
                        .ok_or_else(|| missing_rc_ref(ref_id))
                }
                RefFlag::NotNullValue => Ok(Rc::new($read_inner?)),
                RefFlag::RefValue => {
                    let ref_id = $context.ref_reader.reserve_ref_id();
                    let value = Rc::new($read_inner?);
                    $context.ref_reader.store_rc_ref_at(ref_id, value.clone());
                    Ok(value)
                }
            },
        }
    };
}

#[inline(always)]
fn read_rc<T: 'static, C: Serializer<Target = T>>(
    context: &mut ReadContext,
    ref_mode: RefMode,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<Rc<T>, Error> {
    read_rc_owner!(
        context,
        ref_mode,
        read_inner::<T, C>(context, read_type_info, type_info),
        <RcCodec<T, C, false, false> as Serializer>::default_value(context)
    )
}

#[inline(always)]
fn read_rc_with_type<T: 'static, C: Codec<T>>(
    context: &mut ReadContext,
    ref_mode: RefMode,
    remote_field_type: &FieldType,
) -> Result<Rc<T>, Error> {
    read_rc_owner!(
        context,
        ref_mode,
        read_inner_with_type::<T, C>(context, remote_field_type),
        <RcCodec<T, C, false, false> as Serializer>::default_value(context)
    )
}

impl_single_carrier_serializer!(RcSerializer, Rc, RcCodec, wrapper = true);
