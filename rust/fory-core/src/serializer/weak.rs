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

//! Serialization support for [`RcWeak`] and [`ArcWeak`].

use super::codec::{codec_read_type_info, codec_read_type_info_static, Codec};
use super::pointee::{PointeeCodec, PointeeSerializer};
use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::meta::FieldType;
use crate::resolver::{RefFlag, RefMode, TypeInfo, TypeResolver};
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use crate::types::{ArcWeak, RcWeak};
use std::marker::PhantomData;
use std::rc::Rc;
use std::sync::Arc;

pub struct RcWeakCodec<T: ?Sized, C, const NULLABLE: bool, const TRACK_REF: bool>(
    PhantomData<(*const T, C)>,
);

pub struct ArcWeakCodec<T: ?Sized, C, const NULLABLE: bool, const TRACK_REF: bool>(
    PhantomData<(*const T, C)>,
);

#[inline(always)]
fn reserve_weak_cell<W>(context: &mut ReadContext) -> Result<(), Error> {
    let bytes = std::mem::size_of::<W>();
    if bytes != 0 {
        context.reserve_graph_memory(bytes)?;
    }
    Ok(())
}

#[inline(always)]
fn reserve_strong<T: ?Sized, C: PointeeSerializer<T>>(
    context: &mut ReadContext,
) -> Result<(), Error> {
    let bytes = C::POINTEE_SIZE;
    if bytes != 0 {
        context.reserve_graph_memory(bytes)?;
    }
    Ok(())
}

#[cold]
#[inline(never)]
fn rc_weak_tracking_error() -> Error {
    Error::invalid_ref(
        "RcWeak requires track_ref to be enabled. \
         Use Fory::builder().track_ref(true).build()",
    )
}

#[cold]
#[inline(never)]
fn arc_weak_tracking_error() -> Error {
    Error::invalid_ref(
        "ArcWeak requires track_ref to be enabled. \
         Use Fory::builder().track_ref(true).build()",
    )
}

#[cold]
#[inline(never)]
fn weak_write_mode_error(owner: &str) -> Error {
    Error::invalid_ref(format!(
        "{owner} requires RefMode::Tracking for serialization"
    ))
}

#[cold]
#[inline(never)]
fn weak_read_mode_error(owner: &str) -> Error {
    Error::invalid_ref(format!(
        "{owner} requires RefMode::Tracking for deserialization"
    ))
}

#[cold]
#[inline(never)]
fn weak_untracked_value(owner: &str) -> Error {
    Error::invalid_ref(format!("{owner} cannot contain an untracked strong value"))
}

#[inline(always)]
fn rc_weak_body<T: ?Sized + 'static>(
    value: &RcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
) -> Result<Option<Rc<T>>, Error> {
    if !context.is_track_ref() {
        return Err(rc_weak_tracking_error());
    }
    if ref_mode != RefMode::Tracking {
        return Err(weak_write_mode_error("RcWeak"));
    }
    let Some(value) = value.upgrade() else {
        context.writer.write_i8(RefFlag::Null as i8);
        return Ok(None);
    };
    if context
        .ref_writer
        .try_write_rc_ref(&mut context.writer, &value)
    {
        return Ok(None);
    }
    Ok(Some(value))
}

#[inline(always)]
fn write_rc_weak<T: ?Sized + 'static, C: PointeeSerializer<T>>(
    value: &RcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    write_type_info: bool,
) -> Result<(), Error> {
    let Some(value) = rc_weak_body(value, context, ref_mode)? else {
        return Ok(());
    };
    C::write_pointee(&value, context, RefMode::None, write_type_info)
}

#[inline(always)]
fn write_rc_weak_field<T: ?Sized + 'static, V: 'static, C: PointeeCodec<T, V>>(
    value: &RcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    write_type_info: bool,
    has_generics: bool,
) -> Result<(), Error> {
    let Some(value) = rc_weak_body(value, context, ref_mode)? else {
        return Ok(());
    };
    C::write_pointee_with_mode(
        &value,
        context,
        RefMode::None,
        write_type_info,
        has_generics,
    )
}

#[inline(always)]
fn write_rc_weak_with_type_info<T: ?Sized + 'static, C: PointeeSerializer<T>>(
    value: &RcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    type_info: &Rc<TypeInfo>,
) -> Result<(), Error> {
    let Some(value) = rc_weak_body(value, context, ref_mode)? else {
        return Ok(());
    };
    C::write_pointee_with_type_info(&value, context, RefMode::None, type_info)
}

#[inline(always)]
fn write_rc_weak_field_with_type_info<T: ?Sized + 'static, V: 'static, C: PointeeCodec<T, V>>(
    value: &RcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    type_info: &Rc<TypeInfo>,
    has_generics: bool,
) -> Result<(), Error> {
    let Some(value) = rc_weak_body(value, context, ref_mode)? else {
        return Ok(());
    };
    C::write_pointee_field_with_type_info(&value, context, RefMode::None, type_info, has_generics)
}

#[inline(always)]
fn read_rc_inner<T: ?Sized + 'static, C: PointeeSerializer<T>>(
    context: &mut ReadContext,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<C::Target, Error> {
    reserve_strong::<T, C>(context)?;
    if let Some(type_info) = type_info {
        return C::read_with_type_info(context, RefMode::None, type_info);
    }
    // The weak envelope has consumed its ref flag; the child still owns any
    // inline dynamic metadata before its body.
    C::read(context, RefMode::None, read_type_info)
}

#[inline(always)]
fn read_rc_inner_with_type<T: ?Sized + 'static, V: 'static, C: PointeeCodec<T, V>>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<C::Target, Error> {
    reserve_strong::<T, C>(context)?;
    // The weak envelope owns only reference framing. A compatible
    // metadata-bearing child still owns its inline TypeInfo before its body,
    // while declared carrier children consume the remote schema directly.
    if codec_read_type_info::<C::Target, C>(context, remote_field_type) {
        return C::read(context, RefMode::None, true);
    }
    C::read_data_with_type(context, remote_field_type)
}

macro_rules! read_rc_weak_owner {
    ($context:ident, $ref_mode:expr, $read_inner:expr) => {{
        if $ref_mode != RefMode::Tracking {
            return Err(weak_read_mode_error("RcWeak"));
        }
        match $context.ref_reader.read_ref_flag(&mut $context.reader)? {
            RefFlag::Null => {
                reserve_weak_cell::<std::rc::Weak<T>>($context)?;
                Ok(RcWeak::new())
            }
            RefFlag::RefValue => {
                // The writer assigns the strong target's ID before its body.
                // Reserve that slot now, but publish only the final Rc after
                // the complete child read succeeds.
                let ref_id = $context.ref_reader.reserve_ref_id();
                $context.inc_depth()?;
                let value = $read_inner?;
                $context.dec_depth();
                let strong = Rc::from(value);
                reserve_weak_cell::<std::rc::Weak<T>>($context)?;
                $context.ref_reader.store_rc_ref_at(ref_id, strong.clone());
                Ok(RcWeak::from(&strong))
            }
            RefFlag::Ref => {
                let ref_id = $context.ref_reader.read_ref_id(&mut $context.reader)?;
                reserve_weak_cell::<std::rc::Weak<T>>($context)?;
                if let Some(strong) = $context.ref_reader.get_rc_ref::<T>(ref_id) {
                    return Ok(RcWeak::from(&strong));
                }
                let weak = RcWeak::new();
                let callback_weak = weak.clone();
                $context.ref_reader.add_callback(Box::new(move |reader| {
                    if let Some(strong) = reader.get_rc_ref::<T>(ref_id) {
                        callback_weak.update(Rc::downgrade(&strong));
                    }
                }));
                Ok(weak)
            }
            RefFlag::NotNullValue => Err(weak_untracked_value("RcWeak")),
        }
    }};
}

#[inline(always)]
fn read_rc_weak<T: ?Sized + 'static, C: PointeeSerializer<T>>(
    context: &mut ReadContext,
    ref_mode: RefMode,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<RcWeak<T>, Error>
where
    Rc<T>: From<C::Target>,
    RcWeak<T>: Default,
{
    read_rc_weak_owner!(
        context,
        ref_mode,
        read_rc_inner::<T, C>(context, read_type_info, type_info)
    )
}

#[inline(always)]
fn read_rc_weak_with_type<T: ?Sized + 'static, V: 'static, C: PointeeCodec<T, V>>(
    context: &mut ReadContext,
    ref_mode: RefMode,
    remote_field_type: &FieldType,
) -> Result<RcWeak<T>, Error>
where
    Rc<T>: From<C::Target>,
    RcWeak<T>: Default,
{
    read_rc_weak_owner!(
        context,
        ref_mode,
        read_rc_inner_with_type::<T, V, C>(context, remote_field_type)
    )
}

impl<T, C, const NULLABLE: bool, const TRACK_REF: bool> Serializer
    for RcWeakCodec<T, C, NULLABLE, TRACK_REF>
where
    T: ?Sized + 'static,
    C: PointeeSerializer<T>,
    Rc<T>: From<C::Target>,
    RcWeak<T>: Default,
{
    type Target = RcWeak<T>;

    #[inline(always)]
    fn reserved_space() -> usize {
        4
    }

    #[cold]
    #[inline(never)]
    fn write_data(_: &RcWeak<T>, _: &mut WriteContext) -> Result<(), Error> {
        Err(Error::not_allowed(
            "RcWeak must be written through its reference-tracking envelope",
        ))
    }

    #[cold]
    #[inline(never)]
    fn read_data(_: &mut ReadContext) -> Result<RcWeak<T>, Error> {
        Err(Error::not_allowed(
            "RcWeak must be read through its reference-tracking envelope",
        ))
    }

    #[inline(always)]
    fn write(
        value: &RcWeak<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        write_rc_weak::<T, C>(value, context, ref_mode, write_type_info)
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
        value: &RcWeak<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        write_rc_weak_with_type_info::<T, C>(value, context, ref_mode, type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<RcWeak<T>, Error> {
        read_rc_weak::<T, C>(context, ref_mode, read_type_info, None)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<RcWeak<T>, Error> {
        read_rc_weak::<T, C>(context, ref_mode, false, Some(type_info))
    }

    #[inline(always)]
    fn default_value(context: &mut ReadContext) -> Result<RcWeak<T>, Error> {
        reserve_weak_cell::<std::rc::Weak<T>>(context)?;
        Ok(RcWeak::new())
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

    const REQUIRES_SCOPED_ACCESS: bool = true;

    #[inline(always)]
    fn dynamic_type_id(value: &RcWeak<T>) -> Result<Option<std::any::TypeId>, Error> {
        match value.upgrade() {
            Some(value) => C::pointee_dynamic_type_id(&value),
            None => Ok(None),
        }
    }
}

impl<T, V, C, const NULLABLE: bool, const TRACK_REF: bool> Codec<RcWeak<T>>
    for RcWeakCodec<T, C, NULLABLE, TRACK_REF>
where
    T: ?Sized + 'static,
    V: 'static,
    C: Serializer<Target = V> + PointeeCodec<T, V>,
    Rc<T>: From<C::Target>,
    RcWeak<T>: Default,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        let mut field_type = C::field_type(type_resolver)?;
        field_type.nullable = NULLABLE;
        field_type.track_ref = true;
        Ok(field_type)
    }

    #[inline(always)]
    fn write_field(value: &RcWeak<T>, context: &mut WriteContext) -> Result<(), Error> {
        Self::write_with_mode(
            value,
            context,
            RefMode::Tracking,
            super::codec::codec_write_type_info::<C::Target, C>(context),
            true,
        )
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<RcWeak<T>, Error> {
        <Self as Serializer>::read(
            context,
            RefMode::Tracking,
            codec_read_type_info_static::<C::Target, C>(context),
        )
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<RcWeak<T>, Error> {
        read_rc_weak_with_type::<T, V, C>(context, RefMode::Tracking, remote_field_type)
    }

    #[inline(always)]
    fn write_with_mode(
        value: &RcWeak<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_rc_weak_field::<T, V, C>(value, context, ref_mode, write_type_info, has_generics)
    }

    #[inline(always)]
    fn write_with_type_info(
        value: &RcWeak<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_rc_weak_field_with_type_info::<T, V, C>(
            value,
            context,
            ref_mode,
            type_info,
            has_generics,
        )
    }

    #[inline(always)]
    fn read_type_info_value(
        context: &mut ReadContext,
    ) -> Result<super::codec::CodecReadType, Error> {
        C::read_type_info_value(context)
    }
}

#[inline(always)]
fn arc_weak_body<T: ?Sized + Send + Sync + 'static>(
    value: &ArcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
) -> Result<Option<Arc<T>>, Error> {
    if !context.is_track_ref() {
        return Err(arc_weak_tracking_error());
    }
    if ref_mode != RefMode::Tracking {
        return Err(weak_write_mode_error("ArcWeak"));
    }
    let Some(value) = value.upgrade() else {
        context.writer.write_i8(RefFlag::Null as i8);
        return Ok(None);
    };
    if context
        .ref_writer
        .try_write_arc_ref(&mut context.writer, &value)
    {
        return Ok(None);
    }
    Ok(Some(value))
}

#[inline(always)]
fn write_arc_weak<T: ?Sized + Send + Sync + 'static, C: PointeeSerializer<T>>(
    value: &ArcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    write_type_info: bool,
) -> Result<(), Error> {
    let Some(value) = arc_weak_body(value, context, ref_mode)? else {
        return Ok(());
    };
    C::write_pointee(&value, context, RefMode::None, write_type_info)
}

#[inline(always)]
fn write_arc_weak_field<T: ?Sized + Send + Sync + 'static, V: 'static, C: PointeeCodec<T, V>>(
    value: &ArcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    write_type_info: bool,
    has_generics: bool,
) -> Result<(), Error> {
    let Some(value) = arc_weak_body(value, context, ref_mode)? else {
        return Ok(());
    };
    C::write_pointee_with_mode(
        &value,
        context,
        RefMode::None,
        write_type_info,
        has_generics,
    )
}

#[inline(always)]
fn write_arc_weak_with_type_info<T: ?Sized + Send + Sync + 'static, C: PointeeSerializer<T>>(
    value: &ArcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    type_info: &Rc<TypeInfo>,
) -> Result<(), Error> {
    let Some(value) = arc_weak_body(value, context, ref_mode)? else {
        return Ok(());
    };
    C::write_pointee_with_type_info(&value, context, RefMode::None, type_info)
}

#[inline(always)]
fn write_arc_weak_field_with_type_info<
    T: ?Sized + Send + Sync + 'static,
    V: 'static,
    C: PointeeCodec<T, V>,
>(
    value: &ArcWeak<T>,
    context: &mut WriteContext,
    ref_mode: RefMode,
    type_info: &Rc<TypeInfo>,
    has_generics: bool,
) -> Result<(), Error> {
    let Some(value) = arc_weak_body(value, context, ref_mode)? else {
        return Ok(());
    };
    C::write_pointee_field_with_type_info(&value, context, RefMode::None, type_info, has_generics)
}

#[inline(always)]
fn read_arc_inner<T: ?Sized + Send + Sync + 'static, C: PointeeSerializer<T>>(
    context: &mut ReadContext,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<C::Target, Error> {
    reserve_strong::<T, C>(context)?;
    if let Some(type_info) = type_info {
        return C::read_with_type_info(context, RefMode::None, type_info);
    }
    // The weak envelope has consumed its ref flag; the child still owns any
    // inline dynamic metadata before its body.
    C::read(context, RefMode::None, read_type_info)
}

#[inline(always)]
fn read_arc_inner_with_type<
    T: ?Sized + Send + Sync + 'static,
    V: 'static,
    C: PointeeCodec<T, V>,
>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<C::Target, Error> {
    reserve_strong::<T, C>(context)?;
    // The weak envelope owns only reference framing. A compatible
    // metadata-bearing child still owns its inline TypeInfo before its body,
    // while declared carrier children consume the remote schema directly.
    if codec_read_type_info::<C::Target, C>(context, remote_field_type) {
        return C::read(context, RefMode::None, true);
    }
    C::read_data_with_type(context, remote_field_type)
}

macro_rules! read_arc_weak_owner {
    ($context:ident, $ref_mode:expr, $read_inner:expr) => {{
        if $ref_mode != RefMode::Tracking {
            return Err(weak_read_mode_error("ArcWeak"));
        }
        match $context.ref_reader.read_ref_flag(&mut $context.reader)? {
            RefFlag::Null => {
                reserve_weak_cell::<std::sync::Weak<T>>($context)?;
                Ok(ArcWeak::new())
            }
            RefFlag::RefValue => {
                // The writer assigns the strong target's ID before its body.
                // Reserve that slot now, but publish only the final Arc after
                // the complete child read succeeds.
                let ref_id = $context.ref_reader.reserve_ref_id();
                $context.inc_depth()?;
                let value = $read_inner?;
                $context.dec_depth();
                let strong = Arc::from(value);
                reserve_weak_cell::<std::sync::Weak<T>>($context)?;
                $context.ref_reader.store_arc_ref_at(ref_id, strong.clone());
                Ok(ArcWeak::from(&strong))
            }
            RefFlag::Ref => {
                let ref_id = $context.ref_reader.read_ref_id(&mut $context.reader)?;
                reserve_weak_cell::<std::sync::Weak<T>>($context)?;
                let weak = ArcWeak::new();
                if let Some(strong) = $context.ref_reader.get_arc_ref::<T>(ref_id) {
                    weak.update(Arc::downgrade(&strong));
                } else {
                    let callback_weak = weak.clone();
                    $context.ref_reader.add_callback(Box::new(move |reader| {
                        if let Some(strong) = reader.get_arc_ref::<T>(ref_id) {
                            callback_weak.update(Arc::downgrade(&strong));
                        }
                    }));
                }
                Ok(weak)
            }
            RefFlag::NotNullValue => Err(weak_untracked_value("ArcWeak")),
        }
    }};
}

#[inline(always)]
fn read_arc_weak<T: ?Sized + Send + Sync + 'static, C: PointeeSerializer<T>>(
    context: &mut ReadContext,
    ref_mode: RefMode,
    read_type_info: bool,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<ArcWeak<T>, Error>
where
    Arc<T>: From<C::Target>,
    ArcWeak<T>: Default,
{
    read_arc_weak_owner!(
        context,
        ref_mode,
        read_arc_inner::<T, C>(context, read_type_info, type_info)
    )
}

#[inline(always)]
fn read_arc_weak_with_type<T: ?Sized + Send + Sync + 'static, V: 'static, C: PointeeCodec<T, V>>(
    context: &mut ReadContext,
    ref_mode: RefMode,
    remote_field_type: &FieldType,
) -> Result<ArcWeak<T>, Error>
where
    Arc<T>: From<C::Target>,
    ArcWeak<T>: Default,
{
    read_arc_weak_owner!(
        context,
        ref_mode,
        read_arc_inner_with_type::<T, V, C>(context, remote_field_type)
    )
}

impl<T, C, const NULLABLE: bool, const TRACK_REF: bool> Serializer
    for ArcWeakCodec<T, C, NULLABLE, TRACK_REF>
where
    T: ?Sized + Send + Sync + 'static,
    C: PointeeSerializer<T>,
    Arc<T>: From<C::Target>,
    ArcWeak<T>: Default,
{
    type Target = ArcWeak<T>;

    #[inline(always)]
    fn reserved_space() -> usize {
        4
    }

    #[cold]
    #[inline(never)]
    fn write_data(_: &ArcWeak<T>, _: &mut WriteContext) -> Result<(), Error> {
        Err(Error::not_allowed(
            "ArcWeak must be written through its reference-tracking envelope",
        ))
    }

    #[cold]
    #[inline(never)]
    fn read_data(_: &mut ReadContext) -> Result<ArcWeak<T>, Error> {
        Err(Error::not_allowed(
            "ArcWeak must be read through its reference-tracking envelope",
        ))
    }

    #[inline(always)]
    fn write(
        value: &ArcWeak<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        write_arc_weak::<T, C>(value, context, ref_mode, write_type_info)
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
        value: &ArcWeak<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        write_arc_weak_with_type_info::<T, C>(value, context, ref_mode, type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<ArcWeak<T>, Error> {
        read_arc_weak::<T, C>(context, ref_mode, read_type_info, None)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<ArcWeak<T>, Error> {
        read_arc_weak::<T, C>(context, ref_mode, false, Some(type_info))
    }

    #[inline(always)]
    fn default_value(context: &mut ReadContext) -> Result<ArcWeak<T>, Error> {
        reserve_weak_cell::<std::sync::Weak<T>>(context)?;
        Ok(ArcWeak::new())
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

    const REQUIRES_SCOPED_ACCESS: bool = true;

    #[inline(always)]
    fn dynamic_type_id(value: &ArcWeak<T>) -> Result<Option<std::any::TypeId>, Error> {
        match value.upgrade() {
            Some(value) => C::pointee_dynamic_type_id(&value),
            None => Ok(None),
        }
    }
}

impl<T, V, C, const NULLABLE: bool, const TRACK_REF: bool> Codec<ArcWeak<T>>
    for ArcWeakCodec<T, C, NULLABLE, TRACK_REF>
where
    T: ?Sized + Send + Sync + 'static,
    V: 'static,
    C: Serializer<Target = V> + PointeeCodec<T, V>,
    Arc<T>: From<C::Target>,
    ArcWeak<T>: Default,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        let mut field_type = C::field_type(type_resolver)?;
        field_type.nullable = NULLABLE;
        field_type.track_ref = true;
        Ok(field_type)
    }

    #[inline(always)]
    fn write_field(value: &ArcWeak<T>, context: &mut WriteContext) -> Result<(), Error> {
        Self::write_with_mode(
            value,
            context,
            RefMode::Tracking,
            super::codec::codec_write_type_info::<C::Target, C>(context),
            true,
        )
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<ArcWeak<T>, Error> {
        <Self as Serializer>::read(
            context,
            RefMode::Tracking,
            codec_read_type_info_static::<C::Target, C>(context),
        )
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<ArcWeak<T>, Error> {
        read_arc_weak_with_type::<T, V, C>(context, RefMode::Tracking, remote_field_type)
    }

    #[inline(always)]
    fn write_with_mode(
        value: &ArcWeak<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_arc_weak_field::<T, V, C>(value, context, ref_mode, write_type_info, has_generics)
    }

    #[inline(always)]
    fn write_with_type_info(
        value: &ArcWeak<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_arc_weak_field_with_type_info::<T, V, C>(
            value,
            context,
            ref_mode,
            type_info,
            has_generics,
        )
    }

    #[inline(always)]
    fn read_type_info_value(
        context: &mut ReadContext,
    ) -> Result<super::codec::CodecReadType, Error> {
        C::read_type_info_value(context)
    }
}

impl_unsized_carrier_serializer!(RcWeak, RcWeakCodec);

impl_single_carrier_serializer!(RcWeakSerializer, RcWeak, RcWeakCodec, wrapper = true);

impl_unsized_carrier_serializer!(ArcWeak, ArcWeakCodec, bounds = [Send + Sync]);

impl_single_carrier_serializer!(
    ArcWeakSerializer,
    ArcWeak,
    ArcWeakCodec,
    wrapper = true,
    bounds = [Send + Sync]
);
