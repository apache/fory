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

use std::any::Any;

/// Object-safe access to a concrete target's [`Any`] identity.
///
/// Application traits serialized by [`crate::register_trait_type!`] extend this
/// trait instead of extending [`crate::Serializer`].
pub trait ForyObject: Any {
    fn as_any(&self) -> &dyn Any;
}

impl<T: Any> ForyObject for T {
    #[inline(always)]
    fn as_any(&self) -> &dyn Any {
        self
    }
}

/// Generates serializers for a closed set of concrete application-trait targets.
///
/// The listed types are runtime target types. Their registered ordinary serializer,
/// external structural serializer, or manual serializer determines wire behavior.
///
/// ```rust,ignore
/// use fory::{register_trait_type, ForyObject};
///
/// trait Animal: ForyObject {
///     fn name(&self) -> &str;
/// }
///
/// register_trait_type!(Animal, Dog, third_party::Cat);
/// ```
///
/// Use `sync` when the application trait and all targets are `Send + Sync`:
///
/// ```rust,ignore
/// register_trait_type!(sync Animal, Dog, third_party::Cat);
/// ```
///
/// Generated serializer and codec names are private by default. Prefix the trait
/// with a Rust visibility when those names must be exported:
///
/// ```rust,ignore
/// register_trait_type!(pub Animal, Dog, third_party::Cat);
/// register_trait_type!(pub sync SharedAnimal, Dog, third_party::Cat);
/// ```
#[macro_export]
macro_rules! register_trait_type {
    ($vis:vis sync $trait_name:ident, $($target:ty),+ $(,)?) => {
        $crate::register_trait_type!(@dispatch $trait_name, $($target),+);
        $crate::register_trait_type!(@box [$vis] $trait_name, $($target),+);
        $crate::register_trait_type!(@rc [$vis] $trait_name, $($target),+);
        $crate::register_trait_type!(@arc [$vis] $trait_name, $($target),+);
    };

    ($vis:vis $trait_name:ident, $($target:ty),+ $(,)?) => {
        $crate::register_trait_type!(@dispatch $trait_name, $($target),+);
        $crate::register_trait_type!(@box [$vis] $trait_name, $($target),+);
        $crate::register_trait_type!(@rc [$vis] $trait_name, $($target),+);
    };

    (@dispatch $trait_name:ident, $($target:ty),+) => {
        $crate::paste::paste! {
            #[allow(dead_code)]
            struct [<$trait_name ForyDispatch>];

            #[allow(dead_code)]
            impl [<$trait_name ForyDispatch>] {
                #[inline(always)]
                fn any_ref(value: &dyn $trait_name) -> &dyn std::any::Any {
                    <dyn $trait_name as $crate::ForyObject>::as_any(value)
                }

                #[inline(always)]
                fn ensure_member(
                    target_type_id: std::any::TypeId,
                ) -> Result<(), $crate::Error> {
                    if !($(target_type_id == std::any::TypeId::of::<$target>())||+) {
                        return Err(Self::unlisted(target_type_id));
                    }
                    Ok(())
                }

                #[inline(always)]
                fn checked_any(
                    value: &dyn $trait_name,
                ) -> Result<&dyn std::any::Any, $crate::Error> {
                    let any = Self::any_ref(value);
                    Self::ensure_member(any.type_id())?;
                    Ok(any)
                }

                #[inline(always)]
                fn target_id(
                    type_info: &$crate::TypeInfo,
                ) -> Result<std::any::TypeId, $crate::Error> {
                    type_info
                        .get_harness()
                        .target_type_id()
                        .ok_or_else(Self::missing)
                }

                #[inline(always)]
                fn check_type_info(
                    target_type_id: std::any::TypeId,
                    type_info: &$crate::TypeInfo,
                ) -> Result<(), $crate::Error> {
                    $crate::serializer::any::check_erased_target_type(type_info)?;
                    let resolved_target_type_id = Self::target_id(type_info)?;
                    if resolved_target_type_id != target_type_id {
                        return Err(Self::downcast(
                            resolved_target_type_id,
                            target_type_id,
                        ));
                    }
                    Ok(())
                }

                #[inline(always)]
                fn resolve_type_info(
                    context: &mut $crate::WriteContext,
                    target_type_id: std::any::TypeId,
                ) -> Result<std::rc::Rc<$crate::TypeInfo>, $crate::Error> {
                    let type_info = context.get_target_type_info(&target_type_id)?;
                    Self::check_type_info(target_type_id, &type_info)?;
                    Ok(type_info)
                }

                #[inline(always)]
                fn write_type_info_value(
                    context: &mut $crate::WriteContext,
                    target_type_id: std::any::TypeId,
                ) -> Result<std::rc::Rc<$crate::TypeInfo>, $crate::Error> {
                    Self::ensure_member(target_type_id)?;
                    let type_info = Self::resolve_type_info(context, target_type_id)?;
                    context.write_resolved_type_info(
                        $crate::TypeId::UNKNOWN as u32,
                        type_info,
                    )
                }

                #[inline(always)]
                fn write_harness(
                    any: &dyn std::any::Any,
                    context: &mut $crate::WriteContext,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<(), $crate::Error> {
                    type_info.get_harness().write_data(any, context)
                }

                #[inline(always)]
                fn write_checked(
                    any: &dyn std::any::Any,
                    context: &mut $crate::WriteContext,
                    write_type_info: bool,
                ) -> Result<(), $crate::Error> {
                    let type_info = Self::resolve_type_info(context, any.type_id())?;
                    let type_info = if write_type_info {
                        context.write_resolved_type_info(
                            $crate::TypeId::UNKNOWN as u32,
                            type_info,
                        )?
                    } else {
                        type_info
                    };
                    Self::write_harness(any, context, &type_info)
                }

                #[inline(always)]
                fn write_data(
                    value: &dyn $trait_name,
                    context: &mut $crate::WriteContext,
                    write_type_info: bool,
                ) -> Result<(), $crate::Error> {
                    let any = Self::checked_any(value)?;
                    Self::write_checked(any, context, write_type_info)
                }

                #[inline(always)]
                fn read_box(
                    context: &mut $crate::ReadContext,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<Box<dyn $trait_name>, $crate::Error> {
                    let target_type_id = Self::target_id(type_info)?;
                    $crate::serializer::any::check_erased_target_type(type_info)?;
                    $(
                        if target_type_id == std::any::TypeId::of::<$target>() {
                            let erased = type_info
                                .get_harness()
                                .read_box_any(context, type_info)?;
                            let concrete = erased.downcast::<$target>().map_err(|value| {
                                Self::downcast(target_type_id, value.as_ref().type_id())
                            })?;
                            let value: Box<dyn $trait_name> = concrete;
                            return Ok(value);
                        }
                    )+
                    Err(Self::unlisted(target_type_id))
                }

                #[inline(always)]
                fn read_rc(
                    context: &mut $crate::ReadContext,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<std::rc::Rc<dyn $trait_name>, $crate::Error> {
                    let target_type_id = Self::target_id(type_info)?;
                    $crate::serializer::any::check_erased_target_type(type_info)?;
                    $(
                        if target_type_id == std::any::TypeId::of::<$target>() {
                            let erased = type_info
                                .get_harness()
                                .read_rc_any(context, type_info)?;
                            let concrete = erased.downcast::<$target>().map_err(|value| {
                                Self::downcast(target_type_id, value.as_ref().type_id())
                            })?;
                            let value: std::rc::Rc<dyn $trait_name> = concrete;
                            return Ok(value);
                        }
                    )+
                    Err(Self::unlisted(target_type_id))
                }

                #[cold]
                #[inline(never)]
                fn unlisted(target_type_id: std::any::TypeId) -> $crate::Error {
                    $crate::Error::type_error(format!(
                        "target TypeId {:?} is not listed for application trait {}",
                        target_type_id,
                        stringify!($trait_name),
                    ))
                }

                #[cold]
                #[inline(never)]
                fn missing() -> $crate::Error {
                    $crate::Error::type_error(format!(
                        "application trait {} metadata has no checked local target registration",
                        stringify!($trait_name),
                    ))
                }

                #[cold]
                #[inline(never)]
                fn downcast(
                    expected: std::any::TypeId,
                    actual: std::any::TypeId,
                ) -> $crate::Error {
                    $crate::Error::type_error(format!(
                        "application trait {} expected target TypeId {:?}, got {:?}",
                        stringify!($trait_name),
                        expected,
                        actual,
                    ))
                }

                #[cold]
                #[inline(never)]
                fn missing_ref(ref_id: u32) -> $crate::Error {
                    $crate::Error::invalid_data(format!(
                        "dyn {} reference {} not found",
                        stringify!($trait_name),
                        ref_id,
                    ))
                }
            }
        }
    };

    (@box [$vis:vis] $trait_name:ident, $($target:ty),+) => {
        $crate::paste::paste! {
            #[allow(dead_code)]
            impl [<$trait_name ForyDispatch>] {
                #[cold]
                #[inline(never)]
                fn null_box_value() -> $crate::Error {
                    $crate::Error::invalid_ref(concat!(
                        "Box<dyn ",
                        stringify!($trait_name),
                        "> cannot be null",
                    ))
                }

                #[cold]
                #[inline(never)]
                fn missing_box_metadata() -> $crate::Error {
                    $crate::Error::invalid_data(concat!(
                        "Box<dyn ",
                        stringify!($trait_name),
                        "> requires concrete type metadata",
                    ))
                }
            }

            impl $crate::Serializer for Box<dyn $trait_name> {
                type Target = Self;
                #[inline(always)]
                fn write_data(
                    value: &Self,
                    context: &mut $crate::WriteContext,
                ) -> Result<(), $crate::Error> {
                    [<$trait_name ForyDispatch>]::write_data(
                        value.as_ref(),
                        context,
                        false,
                    )
                }

                #[cold]
                #[inline(never)]
                fn read_data(
                    _context: &mut $crate::ReadContext,
                ) -> Result<Self, $crate::Error> {
                    Err($crate::Error::not_allowed(concat!(
                        "Box<dyn ",
                        stringify!($trait_name),
                        "> requires concrete type metadata",
                    )))
                }

                #[inline(always)]
                fn write_type_info_value(
                    context: &mut $crate::WriteContext,
                    target_type_id: std::any::TypeId,
                ) -> Result<std::rc::Rc<$crate::TypeInfo>, $crate::Error> {
                    [<$trait_name ForyDispatch>]::write_type_info_value(
                        context,
                        target_type_id,
                    )
                }

                #[inline(always)]
                fn write_with_type_info(
                    value: &Self,
                    context: &mut $crate::WriteContext,
                    ref_mode: $crate::RefMode,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<(), $crate::Error> {
                    let any = [<$trait_name ForyDispatch>]::checked_any(value.as_ref())?;
                    [<$trait_name ForyDispatch>]::check_type_info(any.type_id(), type_info)?;
                    if ref_mode != $crate::RefMode::None {
                        context.writer.write_i8($crate::RefFlag::NotNullValue as i8);
                    }
                    [<$trait_name ForyDispatch>]::write_harness(
                        any,
                        context,
                        type_info,
                    )
                }

                #[inline(always)]
                fn write(
                    value: &Self,
                    context: &mut $crate::WriteContext,
                    ref_mode: $crate::RefMode,
                    write_type_info: bool,
                ) -> Result<(), $crate::Error> {
                    let any = [<$trait_name ForyDispatch>]::checked_any(value.as_ref())?;
                    if ref_mode != $crate::RefMode::None {
                        context.writer.write_i8($crate::RefFlag::NotNullValue as i8);
                    }
                    [<$trait_name ForyDispatch>]::write_checked(
                        any,
                        context,
                        write_type_info,
                    )
                }

                #[inline(always)]
                fn read(
                    context: &mut $crate::ReadContext,
                    ref_mode: $crate::RefMode,
                    read_type_info: bool,
                ) -> Result<Self, $crate::Error> {
                    context.inc_depth()?;
                    let result = (|| {
                        if ref_mode != $crate::RefMode::None
                            && context.reader.read_i8()?
                                != $crate::RefFlag::NotNullValue as i8
                        {
                            return Err([<$trait_name ForyDispatch>]::null_box_value());
                        }
                        if !read_type_info {
                            return Err([<$trait_name ForyDispatch>]::missing_box_metadata());
                        }
                        let type_info = context.read_any_type_info()?;
                        [<$trait_name ForyDispatch>]::read_box(context, &type_info)
                    })();
                    context.dec_depth();
                    result
                }

                #[inline(always)]
                fn read_with_type_info(
                    context: &mut $crate::ReadContext,
                    ref_mode: $crate::RefMode,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<Self, $crate::Error> {
                    context.inc_depth()?;
                    let result = (|| {
                        if ref_mode != $crate::RefMode::None
                            && context.reader.read_i8()?
                                != $crate::RefFlag::NotNullValue as i8
                        {
                            return Err([<$trait_name ForyDispatch>]::null_box_value());
                        }
                        [<$trait_name ForyDispatch>]::read_box(context, type_info)
                    })();
                    context.dec_depth();
                    result
                }

                #[inline(always)]
                fn write_type_info(
                    _context: &mut $crate::WriteContext,
                ) -> Result<(), $crate::Error> {
                    Ok(())
                }

                #[inline(always)]
                fn read_type_info(
                    _context: &mut $crate::ReadContext,
                ) -> Result<(), $crate::Error> {
                    Ok(())
                }

                #[inline(always)]
                fn static_type_id() -> $crate::TypeId {
                    $crate::TypeId::UNKNOWN
                }

                #[inline(always)]
                fn reserved_space() -> usize {
                    $crate::type_id::SIZE_OF_REF_AND_TYPE
                }

                const IS_POLYMORPHIC: bool = true;

                const IS_WRAPPER: bool = true;

                #[inline(always)]
                fn dynamic_type_id(
                    value: &Self,
                ) -> Result<Option<std::any::TypeId>, $crate::Error> {
                    Ok(Some(
                        [<$trait_name ForyDispatch>]::any_ref(value.as_ref()).type_id(),
                    ))
                }
            }

            $crate::register_trait_type!(
                @codec
                [$vis]
                [<$trait_name BoxCodec>],
                Box<dyn $trait_name>,
                Box<dyn $trait_name>
            );
        }
    };

    (@rc [$vis:vis] $trait_name:ident, $($target:ty),+) => {
        $crate::paste::paste! {
            #[allow(dead_code)]
            impl [<$trait_name ForyDispatch>] {
                #[cold]
                #[inline(never)]
                fn null_rc_value() -> $crate::Error {
                    $crate::Error::invalid_ref(concat!(
                        "Rc<dyn ",
                        stringify!($trait_name),
                        "> cannot be null",
                    ))
                }

                #[cold]
                #[inline(never)]
                fn missing_rc_metadata() -> $crate::Error {
                    $crate::Error::invalid_data(concat!(
                        "Rc<dyn ",
                        stringify!($trait_name),
                        "> requires concrete type metadata",
                    ))
                }
            }

            #[allow(dead_code)]
            #[doc = concat!("Serializer for `Rc<dyn ", stringify!($trait_name), ">`.")]
            $vis struct [<$trait_name RcSerializer>](std::marker::PhantomData<()>);

            impl $crate::Serializer for [<$trait_name RcSerializer>] {
                type Target = std::rc::Rc<dyn $trait_name>;
                #[inline(always)]
                fn write_data(
                    value: &Self::Target,
                    context: &mut $crate::WriteContext,
                ) -> Result<(), $crate::Error> {
                    [<$trait_name ForyDispatch>]::write_data(
                        value.as_ref(),
                        context,
                        false,
                    )
                }

                #[cold]
                #[inline(never)]
                fn read_data(
                    _context: &mut $crate::ReadContext,
                ) -> Result<Self::Target, $crate::Error> {
                    Err($crate::Error::not_allowed(concat!(
                        "Rc<dyn ",
                        stringify!($trait_name),
                        "> requires concrete type metadata",
                    )))
                }

                #[inline(always)]
                fn write_type_info_value(
                    context: &mut $crate::WriteContext,
                    target_type_id: std::any::TypeId,
                ) -> Result<std::rc::Rc<$crate::TypeInfo>, $crate::Error> {
                    [<$trait_name ForyDispatch>]::write_type_info_value(
                        context,
                        target_type_id,
                    )
                }

                #[inline(always)]
                fn write_with_type_info(
                    value: &Self::Target,
                    context: &mut $crate::WriteContext,
                    ref_mode: $crate::RefMode,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<(), $crate::Error> {
                    let any = [<$trait_name ForyDispatch>]::checked_any(value.as_ref())?;
                    [<$trait_name ForyDispatch>]::check_type_info(any.type_id(), type_info)?;
                    if ref_mode != $crate::RefMode::None
                        && context
                            .ref_writer
                            .try_write_rc_ref(&mut context.writer, value)
                    {
                        return Ok(());
                    }
                    [<$trait_name ForyDispatch>]::write_harness(
                        any,
                        context,
                        type_info,
                    )
                }

                #[inline(always)]
                fn write(
                    value: &Self::Target,
                    context: &mut $crate::WriteContext,
                    ref_mode: $crate::RefMode,
                    write_type_info: bool,
                ) -> Result<(), $crate::Error> {
                    let any = [<$trait_name ForyDispatch>]::checked_any(value.as_ref())?;
                    if ref_mode != $crate::RefMode::None
                        && context
                            .ref_writer
                            .try_write_rc_ref(&mut context.writer, value)
                    {
                        return Ok(());
                    }
                    [<$trait_name ForyDispatch>]::write_checked(
                        any,
                        context,
                        write_type_info,
                    )
                }

                #[inline(always)]
                fn read(
                    context: &mut $crate::ReadContext,
                    ref_mode: $crate::RefMode,
                    read_type_info: bool,
                ) -> Result<Self::Target, $crate::Error> {
                    let ref_flag = if ref_mode != $crate::RefMode::None {
                        context.ref_reader.read_ref_flag(&mut context.reader)?
                    } else {
                        $crate::RefFlag::NotNullValue
                    };
                    match ref_flag {
                        $crate::RefFlag::Null => {
                            Err([<$trait_name ForyDispatch>]::null_rc_value())
                        }
                        $crate::RefFlag::Ref => {
                            let ref_id =
                                context.ref_reader.read_ref_id(&mut context.reader)?;
                            context
                                .ref_reader
                                .get_rc_ref::<dyn $trait_name>(ref_id)
                                .ok_or_else(|| {
                                    [<$trait_name ForyDispatch>]::missing_ref(ref_id)
                                })
                        }
                        $crate::RefFlag::NotNullValue => {
                            context.inc_depth()?;
                            let result = (|| {
                                if !read_type_info {
                                    return Err(
                                        [<$trait_name ForyDispatch>]::missing_rc_metadata()
                                    );
                                }
                                let type_info = context.read_any_type_info()?;
                                [<$trait_name ForyDispatch>]::read_rc(context, &type_info)
                            })();
                            context.dec_depth();
                            result
                        }
                        $crate::RefFlag::RefValue => {
                            let ref_id = context.ref_reader.reserve_ref_id();
                            context.inc_depth()?;
                            let result = (|| {
                                if !read_type_info {
                                    return Err(
                                        [<$trait_name ForyDispatch>]::missing_rc_metadata()
                                    );
                                }
                                let type_info = context.read_any_type_info()?;
                                [<$trait_name ForyDispatch>]::read_rc(context, &type_info)
                            })();
                            context.dec_depth();
                            let value = result?;
                            context.ref_reader.store_rc_ref_at(ref_id, value.clone());
                            Ok(value)
                        }
                    }
                }

                #[inline(always)]
                fn read_with_type_info(
                    context: &mut $crate::ReadContext,
                    ref_mode: $crate::RefMode,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<Self::Target, $crate::Error> {
                    let ref_flag = if ref_mode != $crate::RefMode::None {
                        context.ref_reader.read_ref_flag(&mut context.reader)?
                    } else {
                        $crate::RefFlag::NotNullValue
                    };
                    match ref_flag {
                        $crate::RefFlag::Null => {
                            Err([<$trait_name ForyDispatch>]::null_rc_value())
                        }
                        $crate::RefFlag::Ref => {
                            let ref_id =
                                context.ref_reader.read_ref_id(&mut context.reader)?;
                            context
                                .ref_reader
                                .get_rc_ref::<dyn $trait_name>(ref_id)
                                .ok_or_else(|| {
                                    [<$trait_name ForyDispatch>]::missing_ref(ref_id)
                                })
                        }
                        $crate::RefFlag::NotNullValue => {
                            context.inc_depth()?;
                            let result =
                                [<$trait_name ForyDispatch>]::read_rc(context, type_info);
                            context.dec_depth();
                            result
                        }
                        $crate::RefFlag::RefValue => {
                            let ref_id = context.ref_reader.reserve_ref_id();
                            context.inc_depth()?;
                            let result =
                                [<$trait_name ForyDispatch>]::read_rc(context, type_info);
                            context.dec_depth();
                            let value = result?;
                            context.ref_reader.store_rc_ref_at(ref_id, value.clone());
                            Ok(value)
                        }
                    }
                }

                #[inline(always)]
                fn write_type_info(
                    _context: &mut $crate::WriteContext,
                ) -> Result<(), $crate::Error> {
                    Ok(())
                }

                #[inline(always)]
                fn read_type_info(
                    _context: &mut $crate::ReadContext,
                ) -> Result<(), $crate::Error> {
                    Ok(())
                }

                #[inline(always)]
                fn static_type_id() -> $crate::TypeId {
                    $crate::TypeId::UNKNOWN
                }

                #[inline(always)]
                fn reserved_space() -> usize {
                    $crate::type_id::SIZE_OF_REF_AND_TYPE
                }

                const IS_POLYMORPHIC: bool = true;

                const IS_SHARED_REF: bool = true;

                const IS_WRAPPER: bool = true;

                #[inline(always)]
                fn dynamic_type_id(
                    value: &Self::Target,
                ) -> Result<Option<std::any::TypeId>, $crate::Error> {
                    Ok(Some(
                        [<$trait_name ForyDispatch>]::any_ref(value.as_ref()).type_id(),
                    ))
                }
            }

            $crate::register_trait_type!(
                @codec
                [$vis]
                [<$trait_name RcCodec>],
                [<$trait_name RcSerializer>],
                std::rc::Rc<dyn $trait_name>
            );
        }
    };

    (@arc [$vis:vis] $trait_name:ident, $($target:ty),+) => {
        $crate::paste::paste! {
            const _: () = {
                fn assert_sync<T: ?Sized + Send + Sync>() {}
                let _ = assert_sync::<dyn $trait_name>;
                $(let _ = assert_sync::<$target>;)+
            };

            #[allow(dead_code)]
            impl [<$trait_name ForyDispatch>] {
                #[cold]
                #[inline(never)]
                fn null_arc_value() -> $crate::Error {
                    $crate::Error::invalid_ref(concat!(
                        "Arc<dyn ",
                        stringify!($trait_name),
                        "> cannot be null",
                    ))
                }

                #[cold]
                #[inline(never)]
                fn missing_arc_metadata() -> $crate::Error {
                    $crate::Error::invalid_data(concat!(
                        "Arc<dyn ",
                        stringify!($trait_name),
                        "> requires concrete type metadata",
                    ))
                }

                #[inline(always)]
                fn read_arc(
                    context: &mut $crate::ReadContext,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<std::sync::Arc<dyn $trait_name>, $crate::Error> {
                    let target_type_id = Self::target_id(type_info)?;
                    $crate::serializer::any::check_erased_target_type(type_info)?;
                    $(
                        if target_type_id == std::any::TypeId::of::<$target>() {
                            let erased = type_info
                                .get_harness()
                                .read_arc_any(context, type_info)?;
                            let concrete = erased.downcast::<$target>().map_err(|value| {
                                Self::downcast(target_type_id, value.as_ref().type_id())
                            })?;
                            let value: std::sync::Arc<dyn $trait_name> = concrete;
                            return Ok(value);
                        }
                    )+
                    Err(Self::unlisted(target_type_id))
                }
            }

            #[allow(dead_code)]
            #[doc = concat!("Serializer for `Arc<dyn ", stringify!($trait_name), ">`.")]
            $vis struct [<$trait_name ArcSerializer>](std::marker::PhantomData<()>);

            impl $crate::Serializer for [<$trait_name ArcSerializer>] {
                type Target = std::sync::Arc<dyn $trait_name>;
                #[inline(always)]
                fn write_data(
                    value: &Self::Target,
                    context: &mut $crate::WriteContext,
                ) -> Result<(), $crate::Error> {
                    [<$trait_name ForyDispatch>]::write_data(
                        value.as_ref(),
                        context,
                        false,
                    )
                }

                #[cold]
                #[inline(never)]
                fn read_data(
                    _context: &mut $crate::ReadContext,
                ) -> Result<Self::Target, $crate::Error> {
                    Err($crate::Error::not_allowed(concat!(
                        "Arc<dyn ",
                        stringify!($trait_name),
                        "> requires concrete type metadata",
                    )))
                }

                #[inline(always)]
                fn write_type_info_value(
                    context: &mut $crate::WriteContext,
                    target_type_id: std::any::TypeId,
                ) -> Result<std::rc::Rc<$crate::TypeInfo>, $crate::Error> {
                    [<$trait_name ForyDispatch>]::write_type_info_value(
                        context,
                        target_type_id,
                    )
                }

                #[inline(always)]
                fn write_with_type_info(
                    value: &Self::Target,
                    context: &mut $crate::WriteContext,
                    ref_mode: $crate::RefMode,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<(), $crate::Error> {
                    let any = [<$trait_name ForyDispatch>]::checked_any(value.as_ref())?;
                    [<$trait_name ForyDispatch>]::check_type_info(any.type_id(), type_info)?;
                    if ref_mode != $crate::RefMode::None
                        && context
                            .ref_writer
                            .try_write_arc_ref(&mut context.writer, value)
                    {
                        return Ok(());
                    }
                    [<$trait_name ForyDispatch>]::write_harness(
                        any,
                        context,
                        type_info,
                    )
                }

                #[inline(always)]
                fn write(
                    value: &Self::Target,
                    context: &mut $crate::WriteContext,
                    ref_mode: $crate::RefMode,
                    write_type_info: bool,
                ) -> Result<(), $crate::Error> {
                    let any = [<$trait_name ForyDispatch>]::checked_any(value.as_ref())?;
                    if ref_mode != $crate::RefMode::None
                        && context
                            .ref_writer
                            .try_write_arc_ref(&mut context.writer, value)
                    {
                        return Ok(());
                    }
                    [<$trait_name ForyDispatch>]::write_checked(
                        any,
                        context,
                        write_type_info,
                    )
                }

                #[inline(always)]
                fn read(
                    context: &mut $crate::ReadContext,
                    ref_mode: $crate::RefMode,
                    read_type_info: bool,
                ) -> Result<Self::Target, $crate::Error> {
                    let ref_flag = if ref_mode != $crate::RefMode::None {
                        context.ref_reader.read_ref_flag(&mut context.reader)?
                    } else {
                        $crate::RefFlag::NotNullValue
                    };
                    match ref_flag {
                        $crate::RefFlag::Null => {
                            Err([<$trait_name ForyDispatch>]::null_arc_value())
                        }
                        $crate::RefFlag::Ref => {
                            let ref_id =
                                context.ref_reader.read_ref_id(&mut context.reader)?;
                            context
                                .ref_reader
                                .get_arc_ref::<dyn $trait_name>(ref_id)
                                .ok_or_else(|| {
                                    [<$trait_name ForyDispatch>]::missing_ref(ref_id)
                                })
                        }
                        $crate::RefFlag::NotNullValue => {
                            context.inc_depth()?;
                            let result = (|| {
                                if !read_type_info {
                                    return Err(
                                        [<$trait_name ForyDispatch>]::missing_arc_metadata()
                                    );
                                }
                                let type_info = context.read_any_type_info()?;
                                [<$trait_name ForyDispatch>]::read_arc(context, &type_info)
                            })();
                            context.dec_depth();
                            result
                        }
                        $crate::RefFlag::RefValue => {
                            let ref_id = context.ref_reader.reserve_ref_id();
                            context.inc_depth()?;
                            let result = (|| {
                                if !read_type_info {
                                    return Err(
                                        [<$trait_name ForyDispatch>]::missing_arc_metadata()
                                    );
                                }
                                let type_info = context.read_any_type_info()?;
                                [<$trait_name ForyDispatch>]::read_arc(context, &type_info)
                            })();
                            context.dec_depth();
                            let value = result?;
                            context.ref_reader.store_arc_ref_at(ref_id, value.clone());
                            Ok(value)
                        }
                    }
                }

                #[inline(always)]
                fn read_with_type_info(
                    context: &mut $crate::ReadContext,
                    ref_mode: $crate::RefMode,
                    type_info: &std::rc::Rc<$crate::TypeInfo>,
                ) -> Result<Self::Target, $crate::Error> {
                    let ref_flag = if ref_mode != $crate::RefMode::None {
                        context.ref_reader.read_ref_flag(&mut context.reader)?
                    } else {
                        $crate::RefFlag::NotNullValue
                    };
                    match ref_flag {
                        $crate::RefFlag::Null => {
                            Err([<$trait_name ForyDispatch>]::null_arc_value())
                        }
                        $crate::RefFlag::Ref => {
                            let ref_id =
                                context.ref_reader.read_ref_id(&mut context.reader)?;
                            context
                                .ref_reader
                                .get_arc_ref::<dyn $trait_name>(ref_id)
                                .ok_or_else(|| {
                                    [<$trait_name ForyDispatch>]::missing_ref(ref_id)
                                })
                        }
                        $crate::RefFlag::NotNullValue => {
                            context.inc_depth()?;
                            let result =
                                [<$trait_name ForyDispatch>]::read_arc(context, type_info);
                            context.dec_depth();
                            result
                        }
                        $crate::RefFlag::RefValue => {
                            let ref_id = context.ref_reader.reserve_ref_id();
                            context.inc_depth()?;
                            let result =
                                [<$trait_name ForyDispatch>]::read_arc(context, type_info);
                            context.dec_depth();
                            let value = result?;
                            context.ref_reader.store_arc_ref_at(ref_id, value.clone());
                            Ok(value)
                        }
                    }
                }

                #[inline(always)]
                fn write_type_info(
                    _context: &mut $crate::WriteContext,
                ) -> Result<(), $crate::Error> {
                    Ok(())
                }

                #[inline(always)]
                fn read_type_info(
                    _context: &mut $crate::ReadContext,
                ) -> Result<(), $crate::Error> {
                    Ok(())
                }

                #[inline(always)]
                fn static_type_id() -> $crate::TypeId {
                    $crate::TypeId::UNKNOWN
                }

                #[inline(always)]
                fn reserved_space() -> usize {
                    $crate::type_id::SIZE_OF_REF_AND_TYPE
                }

                const IS_POLYMORPHIC: bool = true;

                const IS_SHARED_REF: bool = true;

                const IS_WRAPPER: bool = true;

                #[inline(always)]
                fn dynamic_type_id(
                    value: &Self::Target,
                ) -> Result<Option<std::any::TypeId>, $crate::Error> {
                    Ok(Some(
                        [<$trait_name ForyDispatch>]::any_ref(value.as_ref()).type_id(),
                    ))
                }
            }

            $crate::register_trait_type!(
                @codec
                [$vis]
                [<$trait_name ArcCodec>],
                [<$trait_name ArcSerializer>],
                std::sync::Arc<dyn $trait_name>
            );
        }
    };

    (@codec [$vis:vis] $codec:ident, $provider:ty, $target:ty) => {
        #[allow(dead_code)]
        #[doc(hidden)]
        $vis struct $codec<const NULLABLE: bool, const TRACK_REF: bool>;

        impl<const NULLABLE: bool, const TRACK_REF: bool> $crate::Serializer
            for $codec<NULLABLE, TRACK_REF>
        {
            type Target = $target;

            #[inline(always)]
            fn write_data(
                value: &Self::Target,
                context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                <$provider as $crate::Serializer>::write_data(value, context)
            }

            #[inline(always)]
            fn read_data(
                context: &mut $crate::ReadContext,
            ) -> Result<Self::Target, $crate::Error> {
                <$provider as $crate::Serializer>::read_data(context)
            }

            #[inline(always)]
            fn default_value(
                context: &mut $crate::ReadContext,
            ) -> Result<Self::Target, $crate::Error> {
                <$provider as $crate::Serializer>::default_value(context)
            }

            #[inline(always)]
            fn write(
                value: &Self::Target,
                context: &mut $crate::WriteContext,
                ref_mode: $crate::RefMode,
                write_type_info: bool,
            ) -> Result<(), $crate::Error> {
                <$provider as $crate::Serializer>::write(
                    value,
                    context,
                    ref_mode,
                    write_type_info,
                )
            }

            #[inline(always)]
            fn write_type_info_value(
                context: &mut $crate::WriteContext,
                target_type_id: std::any::TypeId,
            ) -> Result<std::rc::Rc<$crate::TypeInfo>, $crate::Error> {
                <$provider as $crate::Serializer>::write_type_info_value(
                    context,
                    target_type_id,
                )
            }

            #[inline(always)]
            fn write_with_type_info(
                value: &Self::Target,
                context: &mut $crate::WriteContext,
                ref_mode: $crate::RefMode,
                type_info: &std::rc::Rc<$crate::TypeInfo>,
            ) -> Result<(), $crate::Error> {
                <$provider as $crate::Serializer>::write_with_type_info(
                    value,
                    context,
                    ref_mode,
                    type_info,
                )
            }

            #[inline(always)]
            fn read(
                context: &mut $crate::ReadContext,
                ref_mode: $crate::RefMode,
                read_type_info: bool,
            ) -> Result<Self::Target, $crate::Error> {
                <$provider as $crate::Serializer>::read(
                    context,
                    ref_mode,
                    read_type_info,
                )
            }

            #[inline(always)]
            fn read_with_type_info(
                context: &mut $crate::ReadContext,
                ref_mode: $crate::RefMode,
                type_info: &std::rc::Rc<$crate::TypeInfo>,
            ) -> Result<Self::Target, $crate::Error> {
                <$provider as $crate::Serializer>::read_with_type_info(
                    context,
                    ref_mode,
                    type_info,
                )
            }

            #[inline(always)]
            fn write_type_info(
                _context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                Ok(())
            }

            #[inline(always)]
            fn read_type_info(
                _context: &mut $crate::ReadContext,
            ) -> Result<(), $crate::Error> {
                Ok(())
            }

            #[inline(always)]
            fn static_type_id() -> $crate::TypeId {
                $crate::TypeId::UNKNOWN
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                <$provider as $crate::Serializer>::reserved_space()
            }

            const IS_OPTIONAL: bool = <$provider as $crate::Serializer>::IS_OPTIONAL;

            const IS_POLYMORPHIC: bool = <$provider as $crate::Serializer>::IS_POLYMORPHIC;

            const IS_SHARED_REF: bool = <$provider as $crate::Serializer>::IS_SHARED_REF;

            const IS_WRAPPER: bool = <$provider as $crate::Serializer>::IS_WRAPPER;

            const REQUIRES_SCOPED_ACCESS: bool =
                <$provider as $crate::Serializer>::REQUIRES_SCOPED_ACCESS;

            #[inline(always)]
            fn dynamic_type_id(
                value: &Self::Target,
            ) -> Result<Option<std::any::TypeId>, $crate::Error> {
                <$provider as $crate::Serializer>::dynamic_type_id(value)
            }

        }

        impl<const NULLABLE: bool, const TRACK_REF: bool>
            $crate::serializer::codec::Codec<$target>
            for $codec<NULLABLE, TRACK_REF>
        {
            #[inline(always)]
            fn field_type(
                _type_resolver: &$crate::TypeResolver,
            ) -> Result<$crate::meta::FieldType, $crate::Error> {
                Ok($crate::meta::FieldType::new_with_ref(
                    $crate::TypeId::UNKNOWN as u32,
                    NULLABLE,
                    TRACK_REF,
                    Vec::new(),
                ))
            }

            #[inline(always)]
            fn field_reserved_space() -> usize {
                <$provider as $crate::Serializer>::reserved_space()
                    + $crate::type_id::SIZE_OF_REF_AND_TYPE
            }

            #[inline(always)]
            fn write_field(
                value: &$target,
                context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                <$provider as $crate::Serializer>::write(
                    value,
                    context,
                    if TRACK_REF {
                        $crate::RefMode::Tracking
                    } else if NULLABLE {
                        $crate::RefMode::NullOnly
                    } else {
                        $crate::RefMode::None
                    },
                    true,
                )
            }

            #[inline(always)]
            fn read_field(
                context: &mut $crate::ReadContext,
            ) -> Result<$target, $crate::Error> {
                <$provider as $crate::Serializer>::read(
                    context,
                    if TRACK_REF {
                        $crate::RefMode::Tracking
                    } else if NULLABLE {
                        $crate::RefMode::NullOnly
                    } else {
                        $crate::RefMode::None
                    },
                    true,
                )
            }

            #[inline(always)]
            fn read_field_with_type(
                context: &mut $crate::ReadContext,
                remote_field_type: &$crate::meta::FieldType,
            ) -> Result<$target, $crate::Error> {
                <$provider as $crate::Serializer>::read(
                    context,
                    $crate::serializer::codec::field_ref_mode(remote_field_type),
                    true,
                )
            }

            #[inline(always)]
            fn write_with_mode(
                value: &$target,
                context: &mut $crate::WriteContext,
                ref_mode: $crate::RefMode,
                write_type_info: bool,
                _has_generics: bool,
            ) -> Result<(), $crate::Error> {
                <$provider as $crate::Serializer>::write(
                    value,
                    context,
                    ref_mode,
                    write_type_info,
                )
            }

            #[inline(always)]
            fn write_with_type_info(
                value: &$target,
                context: &mut $crate::WriteContext,
                ref_mode: $crate::RefMode,
                type_info: &std::rc::Rc<$crate::TypeInfo>,
                _has_generics: bool,
            ) -> Result<(), $crate::Error> {
                <$provider as $crate::Serializer>::write_with_type_info(
                    value,
                    context,
                    ref_mode,
                    type_info,
                )
            }

            #[inline(always)]
            fn read_type_info_value(
                context: &mut $crate::ReadContext,
            ) -> Result<$crate::serializer::codec::CodecReadType, $crate::Error> {
                context
                    .read_any_type_info()
                    .map($crate::serializer::codec::CodecReadType::TypeInfo)
            }
        }
    };
}

#[cfg(test)]
mod tests {
    use crate::{ForyObject, Serializer};
    use std::rc::Rc;
    use std::sync::Arc;

    trait PrivateAnimal: ForyObject {}

    struct PrivateDog;

    impl PrivateAnimal for PrivateDog {}

    register_trait_type!(PrivateAnimal, PrivateDog);

    pub mod public_api {
        use crate::ForyObject;

        pub trait PublicAnimal: ForyObject {}

        pub struct PublicDog;

        impl PublicAnimal for PublicDog {}

        register_trait_type!(pub PublicAnimal, PublicDog);

        pub trait SharedAnimal: ForyObject + Send + Sync {}

        impl SharedAnimal for PublicDog {}

        register_trait_type!(pub sync SharedAnimal, PublicDog);
    }

    fn assert_target<S, T>()
    where
        S: Serializer<Target = T>,
        T: 'static,
    {
    }

    #[test]
    fn trait_visibility_forms_compile() {
        assert_target::<PrivateAnimalRcSerializer, Rc<dyn PrivateAnimal>>();
        assert_target::<public_api::PublicAnimalRcSerializer, Rc<dyn public_api::PublicAnimal>>();
        assert_target::<public_api::SharedAnimalArcSerializer, Arc<dyn public_api::SharedAnimal>>();
    }
}
