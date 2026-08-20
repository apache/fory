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
    field_ref_mode, field_type_with_ref_flags, generic_field_type, same_numeric_family, Codec,
    CodecReadType,
};
use super::primitive_list;
use crate::context::ReadContext;
use crate::context::WriteContext;
use crate::error::Error;
use crate::meta::FieldType;
use crate::resolver::{RefFlag, RefMode};
use crate::serializer::{core::read_value_type_info, Serializer};
use crate::type_id::{self, need_to_write_type_for_field, PRIMITIVE_ARRAY_TYPES};

pub const TRACKING_REF: u8 = 0b1;

pub const HAS_NULL: u8 = 0b10;

// Whether collection elements type is declare type.
pub const DECL_ELEMENT_TYPE: u8 = 0b100;

//  Whether collection elements type same.
pub const IS_SAME_TYPE: u8 = 0b1000;

const UNBACKED_CHECK_INTERVAL: usize = 1024;

#[inline(always)]
fn count_needs_bytes<T, const COUNT_ALLOCATES: bool, const ZST_NO_BACKING: bool>() -> bool {
    COUNT_ALLOCATES && (!ZST_NO_BACKING || std::mem::size_of::<T>() != 0)
}

#[inline(always)]
fn check_collection_len<T, const COUNT_ALLOCATES: bool, const ZST_NO_BACKING: bool>(
    context: &ReadContext,
    len: u32,
    element_read_always_advances: bool,
) -> Result<(), Error> {
    let len = len as usize;
    // Fixed arrays do not allocate from the wire count. Variable Vec-like ZST
    // owners also have no count-derived backing, while node and bucket owners do.
    if count_needs_bytes::<T, COUNT_ALLOCATES, ZST_NO_BACKING>() {
        let required = if element_read_always_advances {
            len
        } else {
            len.saturating_sub(context.remaining_unbacked_container_items())
        };
        context.reader.check_bound(required)?;
    }
    Ok(())
}

#[inline(always)]
pub(super) fn field_read_data_always_advances(field_type: &FieldType) -> bool {
    matches!(
        field_type.type_id,
        type_id::BOOL..=type_id::NAMED_ENUM
            | type_id::UNION..=type_id::NAMED_UNION
            | type_id::DURATION..=type_id::FLOAT64_ARRAY
            | type_id::U128..=type_id::ISIZE_ARRAY
    )
}

#[inline(always)]
pub(super) fn settle_unbacked_items(
    context: &mut ReadContext,
    completed: usize,
    start: usize,
) -> Result<(), Error> {
    let consumed = context.reader.get_cursor().saturating_sub(start);
    context.reserve_unbacked_container_items(completed.saturating_sub(consumed))
}

#[inline(always)]
fn value_read_data_always_advances<S: Serializer>(
    read_type: Option<&std::rc::Rc<crate::TypeInfo>>,
) -> bool {
    S::READ_DATA_ALWAYS_ADVANCES
        && read_type.map_or(true, |type_info| type_info.has_exact_local_schema())
}

#[inline(always)]
fn codec_read_data_always_advances<T: 'static, C: Codec<T>>(
    read_type: Option<&CodecReadType>,
) -> bool {
    C::READ_DATA_ALWAYS_ADVANCES
        && read_type.map_or(true, |read_type| match read_type {
            CodecReadType::Field(field_type) => field_read_data_always_advances(field_type),
            CodecReadType::TypeInfo(type_info) => type_info.has_exact_local_schema(),
        })
}

#[cold]
#[inline(never)]
fn graph_memory_overflow() -> Error {
    Error::invalid_data("graph memory estimate overflows")
}

#[cold]
#[inline(never)]
fn missing_collection_type() -> Error {
    Error::type_error("Unable to determine concrete type for polymorphic collection elements")
}

#[cold]
#[inline(never)]
fn primitive_collection_mismatch() -> Error {
    Error::type_error(
        "Vec<number> belongs to the `number_array` type, \
         and Vec<Option<number>> belongs to the `list` type. \
         You should not read data of type `number_array` as data of type `list`.",
    )
}

#[cold]
#[inline(never)]
fn collection_type_mismatch(expected: u32, actual: u32) -> Error {
    Error::type_mismatch(expected, actual)
}

#[cold]
#[inline(never)]
fn non_polymorphic_collection() -> Error {
    Error::type_error("Type inconsistent, target type is not polymorphic")
}

#[cold]
#[inline(never)]
fn not_primitive_array() -> Error {
    Error::type_error("array-compatible field is not a primitive array")
}

#[cold]
#[inline(never)]
fn invalid_primitive_array_len() -> Error {
    Error::invalid_data("Invalid data length")
}

#[cold]
#[inline(never)]
fn list_array_error(message: &'static str) -> Error {
    Error::type_error(message)
}

#[inline(always)]
fn reserve_collection_storage(
    context: &mut ReadContext,
    len: u32,
    elem_bytes: usize,
) -> Result<(), Error> {
    let len = len as usize;
    let bytes = len
        .checked_mul(elem_bytes)
        .ok_or_else(graph_memory_overflow)?;
    context.reserve_graph_memory(bytes)?;
    Ok(())
}

pub fn write_collection_type_info(
    context: &mut WriteContext,
    collection_type_id: u32,
) -> Result<(), Error> {
    context.writer.write_u8(collection_type_id as u8);
    Ok(())
}

macro_rules! collection_write_mode {
    (value, $T:ty, $S:ty, $value:expr, $context:expr, $ref_mode:expr, $write_type:expr, $has_generics:expr) => {
        <$S as Serializer>::write($value, $context, $ref_mode, $write_type)
    };
    (field, $T:ty, $C:ty, $value:expr, $context:expr, $ref_mode:expr, $write_type:expr, $has_generics:expr) => {
        <$C as Codec<$T>>::write_with_mode($value, $context, $ref_mode, $write_type, $has_generics)
    };
}

macro_rules! collection_write_with_info {
    (value, $T:ty, $S:ty, $value:expr, $context:expr, $ref_mode:expr, $type_info:expr, $has_generics:expr) => {
        <$S as Serializer>::write_with_type_info($value, $context, $ref_mode, $type_info)
    };
    (field, $T:ty, $C:ty, $value:expr, $context:expr, $ref_mode:expr, $type_info:expr, $has_generics:expr) => {
        <$C as Codec<$T>>::write_with_type_info(
            $value,
            $context,
            $ref_mode,
            $type_info,
            $has_generics,
        )
    };
}

macro_rules! collection_reserved_space {
    (value, $T:ty, $S:ty) => {
        <$S as Serializer>::reserved_space()
    };
    (field, $T:ty, $C:ty) => {
        <$C as Codec<$T>>::field_reserved_space()
    };
}

macro_rules! write_collection_dyn_body {
    ($layer:ident, $T:ident, $C:ident, $iter:expr, $context:expr, $has_generics:expr) => {{
        let context = &mut *$context;
        let has_generics = $has_generics;
        let elem_static_type_id = $C::static_type_id();
        let is_elem_declared = has_generics && !need_to_write_type_for_field(elem_static_type_id);
        let elem_is_polymorphic = $C::IS_POLYMORPHIC;
        let elem_is_shared_ref = $C::IS_SHARED_REF;
        let can_preinspect_dynamic_type = !elem_is_polymorphic || !$C::REQUIRES_SCOPED_ACCESS;

        let iter = $iter.into_iter();
        let mut has_null = elem_is_polymorphic && !can_preinspect_dynamic_type;
        let mut is_same_type = can_preinspect_dynamic_type;
        let mut first_type_id: Option<std::any::TypeId> = None;

        if can_preinspect_dynamic_type {
            for item in iter.clone() {
                if elem_is_polymorphic {
                    if let Some(dynamic_type_id) = $C::dynamic_type_id(item)? {
                        if is_same_type {
                            if let Some(first_id) = first_type_id {
                                if first_id != dynamic_type_id {
                                    is_same_type = false;
                                }
                            } else {
                                first_type_id = Some(dynamic_type_id);
                            }
                        }
                    } else {
                        has_null = true;
                    }
                } else if $C::is_none(item) {
                    has_null = true;
                }
            }
        }

        if elem_is_polymorphic && is_same_type && first_type_id.is_none() {
            // All elements are null, so each element must carry its own type metadata.
            is_same_type = false;
        }

        let mut header = 0u8;
        if has_null {
            header |= HAS_NULL;
        }
        if is_elem_declared {
            header |= DECL_ELEMENT_TYPE;
        }
        if is_same_type {
            header |= IS_SAME_TYPE;
        }
        if elem_is_shared_ref {
            header |= TRACKING_REF;
        }
        context.writer.write_u8(header);

        let type_info = if is_same_type && !is_elem_declared {
            if elem_is_polymorphic {
                let type_id = first_type_id.ok_or_else(missing_collection_type)?;
                Some($C::write_type_info_value(context, type_id)?)
            } else {
                $C::write_type_info(context)?;
                None
            }
        } else {
            None
        };
        let elem_ref_mode = if elem_is_shared_ref {
            RefMode::Tracking
        } else if has_null {
            RefMode::NullOnly
        } else {
            RefMode::None
        };

        if is_same_type {
            if let Some(type_info) = type_info.as_ref() {
                for item in iter {
                    collection_write_with_info!(
                        $layer,
                        $T,
                        $C,
                        item,
                        context,
                        elem_ref_mode,
                        type_info,
                        has_generics
                    )?;
                }
            } else if elem_ref_mode == RefMode::None {
                if has_generics {
                    for item in iter {
                        collection_write_mode!(
                            $layer,
                            $T,
                            $C,
                            item,
                            context,
                            RefMode::None,
                            false,
                            true
                        )?;
                    }
                } else {
                    for item in iter {
                        $C::write_data(item, context)?;
                    }
                }
            } else {
                for item in iter {
                    collection_write_mode!(
                        $layer,
                        $T,
                        $C,
                        item,
                        context,
                        elem_ref_mode,
                        false,
                        has_generics
                    )?;
                }
            }
        } else {
            for item in iter {
                collection_write_mode!(
                    $layer,
                    $T,
                    $C,
                    item,
                    context,
                    elem_ref_mode,
                    true,
                    has_generics
                )?;
            }
        }
        Ok(())
    }};
}

macro_rules! write_collection_body {
    (
        $layer:ident,
        $T:ident,
        $C:ident,
        $iter:expr,
        $context:expr,
        $has_generics:expr,
        $count_allocates:ident,
        $zst_no_backing:ident
    ) => {{
        let context = &mut *$context;
        let iter = $iter.into_iter();
        let len = iter.len();
        context.writer.write_var_u32(len as u32);
        if len == 0 {
            return Ok(());
        }
        let has_generics = $has_generics;
        if $C::IS_POLYMORPHIC || $C::IS_SHARED_REF {
            write_collection_dyn_body!($layer, $T, $C, iter, context, has_generics)?;
            return Ok(());
        }
        let mut header = IS_SAME_TYPE;
        let mut has_null = false;
        let elem_static_type_id = $C::static_type_id();
        let is_elem_declared = has_generics && !need_to_write_type_for_field(elem_static_type_id);
        if $C::IS_OPTIONAL {
            for item in iter.clone() {
                if $C::is_none(item) {
                    has_null = true;
                    break;
                }
            }
        }
        if has_null {
            header |= HAS_NULL;
        }
        if is_elem_declared {
            header |= DECL_ELEMENT_TYPE;
            context.writer.write_u8(header);
        } else {
            context.writer.write_u8(header);
            $C::write_type_info(context)?;
        }
        context
            .writer
            .reserve(len * collection_reserved_space!($layer, $T, $C));
        if !has_null {
            if has_generics {
                for item in iter {
                    collection_write_mode!(
                        $layer,
                        $T,
                        $C,
                        item,
                        context,
                        RefMode::None,
                        false,
                        true
                    )?;
                }
            } else {
                for item in iter {
                    $C::write_data(item, context)?;
                }
            }
        } else {
            // Null detection already inspected nullable holders. The selected
            // child layer owns the envelope, so each holder is accessed once.
            for item in iter {
                collection_write_mode!(
                    $layer,
                    $T,
                    $C,
                    item,
                    context,
                    RefMode::NullOnly,
                    false,
                    has_generics
                )?;
            }
        }

        Ok(())
    }};
}

pub fn write_collection_value_data<
    'a,
    T,
    S,
    I,
    const COUNT_ALLOCATES: bool,
    const ZST_NO_BACKING: bool,
>(
    iter: I,
    context: &mut WriteContext,
) -> Result<(), Error>
where
    T: 'static + 'a,
    S: Serializer<Target = T>,
    I: IntoIterator<Item = &'a T>,
    I::IntoIter: ExactSizeIterator + Clone,
{
    write_collection_body!(
        value,
        T,
        S,
        iter,
        context,
        false,
        COUNT_ALLOCATES,
        ZST_NO_BACKING
    )
}

pub fn write_collection_data<'a, T, C, I, const COUNT_ALLOCATES: bool, const ZST_NO_BACKING: bool>(
    iter: I,
    context: &mut WriteContext,
    has_generics: bool,
) -> Result<(), Error>
where
    T: 'static + 'a,
    C: Codec<T>,
    I: IntoIterator<Item = &'a T>,
    I::IntoIter: ExactSizeIterator + Clone,
{
    write_collection_body!(
        field,
        T,
        C,
        iter,
        context,
        has_generics,
        COUNT_ALLOCATES,
        ZST_NO_BACKING
    )
}

pub fn read_collection_type_info(
    context: &mut ReadContext,
    collection_type_id: u32,
) -> Result<(), Error> {
    let remote_collection_type_id = context.reader.read_u8()? as u32;
    if PRIMITIVE_ARRAY_TYPES.contains(&remote_collection_type_id) {
        return Err(primitive_collection_mismatch());
    }
    if collection_type_id != remote_collection_type_id {
        return Err(collection_type_mismatch(
            collection_type_id,
            remote_collection_type_id,
        ));
    }
    Ok(())
}

macro_rules! collection_read_type {
    (value, $T:ty, $S:ty, $context:expr) => {
        read_value_type_info::<$S>($context)?
    };
    (field, $T:ty, $C:ty, $context:expr) => {
        Some(<$C as Codec<$T>>::read_type_info_value($context)?)
    };
}

macro_rules! collection_read_element {
    (value, $T:ty, $S:ty, $context:expr, $read_type:expr) => {
        match $read_type {
            None => <$S as Serializer>::read_data($context),
            Some(type_info) => {
                <$S as Serializer>::read_with_type_info($context, RefMode::None, type_info)
            }
        }
    };
    (field, $T:ty, $C:ty, $context:expr, $read_type:expr) => {
        match $read_type {
            None => <$C as Serializer>::read_data($context),
            Some(CodecReadType::Field(field_type)) => {
                <$C as Codec<$T>>::read_data_with_type($context, field_type)
            }
            Some(CodecReadType::TypeInfo(type_info)) => {
                <$C as Codec<$T>>::read_data_with_type_info($context, type_info)
            }
        }
    };
}

macro_rules! element_read_data_always_advances {
    (value, $T:ty, $S:ty, $read_type:expr) => {
        value_read_data_always_advances::<$S>($read_type)
    };
    (field, $T:ty, $C:ty, $read_type:expr) => {
        codec_read_data_always_advances::<$T, $C>($read_type)
    };
}

macro_rules! collect_with_unbacked_budget {
    ($R:ty, $T:ty, $len:expr, $context:expr, $read:expr) => {{
        let mut window_start = $context.reader.get_cursor();
        let mut window_items = 0usize;
        let values = (0..$len)
            .map(|_| {
                let value: $T = ($read)?;
                window_items += 1;
                if window_items == UNBACKED_CHECK_INTERVAL {
                    settle_unbacked_items($context, window_items, window_start)?;
                    window_start = $context.reader.get_cursor();
                    window_items = 0;
                }
                Ok(value)
            })
            .collect::<Result<$R, Error>>()?;
        if window_items != 0 {
            settle_unbacked_items($context, window_items, window_start)?;
        }
        Ok(values)
    }};
}

macro_rules! read_collection_body {
    (
        $layer:ident,
        $R:ident,
        $T:ident,
        $C:ident,
        $context:expr,
        $count_allocates:ident,
        $zst_no_backing:ident
    ) => {{
        let context = &mut *$context;
        let len = context.reader.read_var_u32()?;
        reserve_collection_storage(context, len, std::mem::size_of::<$T>())?;
        if len == 0 {
            return Ok($R::from_iter(std::iter::empty()));
        }
        if $C::IS_POLYMORPHIC || $C::IS_SHARED_REF {
            return read_collection_data_dyn_ref::<$R, $T, $C, $count_allocates, $zst_no_backing>(
                context, len,
            );
        }
        let header = context.reader.read_u8()?;
        let declared = (header & DECL_ELEMENT_TYPE) != 0;
        let read_type = if declared {
            None
        } else {
            collection_read_type!($layer, $T, $C, context)
        };
        let has_null = (header & HAS_NULL) != 0;
        if (header & IS_SAME_TYPE) == 0 {
            return Err(non_polymorphic_collection());
        }
        let element_read_always_advances =
            has_null || element_read_data_always_advances!($layer, $T, $C, read_type.as_ref());
        check_collection_len::<$T, $count_allocates, $zst_no_backing>(
            context,
            len,
            element_read_always_advances,
        )?;
        if !has_null {
            if element_read_always_advances {
                (0..len)
                    .map(|_| collection_read_element!($layer, $T, $C, context, read_type.as_ref()))
                    .collect::<Result<$R, Error>>()
            } else {
                collect_with_unbacked_budget!(
                    $R,
                    $T,
                    len,
                    context,
                    collection_read_element!($layer, $T, $C, context, read_type.as_ref())
                )
            }
        } else {
            (0..len)
                .map(|_| {
                    let flag = context.reader.read_i8()?;
                    if flag == RefFlag::Null as i8 {
                        return $C::default_value(context);
                    }
                    collection_read_element!($layer, $T, $C, context, read_type.as_ref())
                })
                .collect::<Result<$R, Error>>()
        }
    }};
}

pub fn read_collection_value_data<
    R,
    T,
    S,
    const COUNT_ALLOCATES: bool,
    const ZST_NO_BACKING: bool,
>(
    context: &mut ReadContext,
) -> Result<R, Error>
where
    T: 'static,
    S: Serializer<Target = T>,
    R: FromIterator<T>,
{
    read_collection_body!(value, R, T, S, context, COUNT_ALLOCATES, ZST_NO_BACKING)
}

pub fn read_collection_data<R, T, C, const COUNT_ALLOCATES: bool, const ZST_NO_BACKING: bool>(
    context: &mut ReadContext,
) -> Result<R, Error>
where
    T: 'static,
    C: Codec<T>,
    R: FromIterator<T>,
{
    read_collection_body!(field, R, T, C, context, COUNT_ALLOCATES, ZST_NO_BACKING)
}

/// Read a LIST/SET body using the recursive field metadata supplied by its owner.
pub fn read_collection_data_with_type<
    R,
    T,
    C,
    const COUNT_ALLOCATES: bool,
    const ZST_NO_BACKING: bool,
>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<R, Error>
where
    T: 'static,
    C: Codec<T>,
    R: FromIterator<T>,
{
    let element_type = generic_field_type(remote_field_type, 0, "collection")?;
    let len = context.reader.read_var_u32()?;
    reserve_collection_storage(context, len, std::mem::size_of::<T>())?;
    if len == 0 {
        return Ok(R::from_iter(std::iter::empty()));
    }

    let header = context.reader.read_u8()?;
    let track_ref = (header & TRACKING_REF) != 0;
    let same_type = (header & IS_SAME_TYPE) != 0;
    let has_null = (header & HAS_NULL) != 0;
    let declared = (header & DECL_ELEMENT_TYPE) != 0;
    let ref_mode = if track_ref {
        RefMode::Tracking
    } else if has_null {
        RefMode::NullOnly
    } else {
        RefMode::None
    };

    if C::IS_POLYMORPHIC || C::IS_SHARED_REF {
        if same_type {
            if declared {
                let element_type = field_type_with_ref_flags(element_type, has_null, track_ref);
                let element_read_always_advances = ref_mode != RefMode::None
                    || (C::READ_DATA_ALWAYS_ADVANCES
                        && field_read_data_always_advances(&element_type));
                check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(
                    context,
                    len,
                    element_read_always_advances,
                )?;
                if element_read_always_advances {
                    return (0..len)
                        .map(|_| C::read_field_with_type(context, &element_type))
                        .collect::<Result<R, Error>>();
                }
                return collect_with_unbacked_budget!(
                    R,
                    T,
                    len,
                    context,
                    C::read_field_with_type(context, &element_type)
                );
            }
            let type_info = context.read_any_type_info()?;
            let element_read_always_advances = ref_mode != RefMode::None
                || (C::READ_DATA_ALWAYS_ADVANCES && type_info.has_exact_local_schema());
            check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(
                context,
                len,
                element_read_always_advances,
            )?;
            if element_read_always_advances {
                return (0..len)
                    .map(|_| C::read_with_type_info(context, ref_mode, &type_info))
                    .collect::<Result<R, Error>>();
            }
            return collect_with_unbacked_budget!(
                R,
                T,
                len,
                context,
                C::read_with_type_info(context, ref_mode, &type_info)
            );
        }
        check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(context, len, true)?;
        return (0..len)
            .map(|_| C::read(context, ref_mode, true))
            .collect::<Result<R, Error>>();
    }

    if !same_type {
        return Err(non_polymorphic_collection());
    }
    if declared {
        if has_null {
            check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(context, len, true)?;
            return (0..len)
                .map(|_| {
                    if context.reader.read_i8()? == RefFlag::Null as i8 {
                        C::default_value(context)
                    } else {
                        C::read_data_with_type(context, element_type)
                    }
                })
                .collect::<Result<R, Error>>();
        }
        let element_read_data_always_advances =
            C::READ_DATA_ALWAYS_ADVANCES && field_read_data_always_advances(element_type);
        check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(
            context,
            len,
            element_read_data_always_advances,
        )?;
        if element_read_data_always_advances {
            return (0..len)
                .map(|_| C::read_data_with_type(context, element_type))
                .collect::<Result<R, Error>>();
        }
        return collect_with_unbacked_budget!(
            R,
            T,
            len,
            context,
            C::read_data_with_type(context, element_type)
        );
    }

    let read_type = C::read_type_info_value(context)?;
    if has_null {
        check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(context, len, true)?;
        (0..len)
            .map(|_| {
                if context.reader.read_i8()? == RefFlag::Null as i8 {
                    C::default_value(context)
                } else {
                    match &read_type {
                        super::codec::CodecReadType::Field(field_type) => {
                            C::read_data_with_type(context, field_type)
                        }
                        super::codec::CodecReadType::TypeInfo(type_info) => {
                            C::read_data_with_type_info(context, type_info)
                        }
                    }
                }
            })
            .collect::<Result<R, Error>>()
    } else {
        let element_read_data_always_advances =
            codec_read_data_always_advances::<T, C>(Some(&read_type));
        check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(
            context,
            len,
            element_read_data_always_advances,
        )?;
        if element_read_data_always_advances {
            (0..len)
                .map(|_| match &read_type {
                    super::codec::CodecReadType::Field(field_type) => {
                        C::read_data_with_type(context, field_type)
                    }
                    super::codec::CodecReadType::TypeInfo(type_info) => {
                        C::read_data_with_type_info(context, type_info)
                    }
                })
                .collect::<Result<R, Error>>()
        } else {
            collect_with_unbacked_budget!(
                R,
                T,
                len,
                context,
                match &read_type {
                    super::codec::CodecReadType::Field(field_type) => {
                        C::read_data_with_type(context, field_type)
                    }
                    super::codec::CodecReadType::TypeInfo(type_info) => {
                        C::read_data_with_type_info(context, type_info)
                    }
                }
            )
        }
    }
}

/// Slow but versatile collection deserialization for dynamic trait object and shared/circular reference.
pub fn read_collection_data_dyn_ref<
    R,
    T,
    C,
    const COUNT_ALLOCATES: bool,
    const ZST_NO_BACKING: bool,
>(
    context: &mut ReadContext,
    len: u32,
) -> Result<R, Error>
where
    T: 'static,
    C: Serializer<Target = T>,
    R: FromIterator<T>,
{
    // Read header
    let header = context.reader.read_u8()?;
    // IMPORTANT: dynamic/shared-ref collection reads still obey the wire
    // header first. Local Rust type information must not override whether the
    // sender wrote ref flags for these elements. DO NOT REMOVE this comment.
    let is_track_ref = (header & TRACKING_REF) != 0;
    let is_same_type = (header & IS_SAME_TYPE) != 0;
    let has_null = (header & HAS_NULL) != 0;
    let is_declared = (header & DECL_ELEMENT_TYPE) != 0;

    // Compute RefMode from flags
    let elem_ref_mode = if is_track_ref {
        RefMode::Tracking
    } else if has_null {
        RefMode::NullOnly
    } else {
        RefMode::None
    };

    // Read elements
    if is_same_type {
        if is_declared {
            let element_read_always_advances =
                elem_ref_mode != RefMode::None || C::READ_DATA_ALWAYS_ADVANCES;
            check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(
                context,
                len,
                element_read_always_advances,
            )?;
            if element_read_always_advances {
                (0..len)
                    .map(|_| C::read(context, elem_ref_mode, false))
                    .collect::<Result<R, Error>>()
            } else {
                collect_with_unbacked_budget!(
                    R,
                    T,
                    len,
                    context,
                    C::read(context, elem_ref_mode, false)
                )
            }
        } else {
            let type_info = context.read_any_type_info()?;
            let element_read_always_advances = elem_ref_mode != RefMode::None
                || (C::READ_DATA_ALWAYS_ADVANCES && type_info.has_exact_local_schema());
            check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(
                context,
                len,
                element_read_always_advances,
            )?;
            if element_read_always_advances {
                (0..len)
                    .map(|_| C::read_with_type_info(context, elem_ref_mode, &type_info))
                    .collect::<Result<R, Error>>()
            } else {
                collect_with_unbacked_budget!(
                    R,
                    T,
                    len,
                    context,
                    C::read_with_type_info(context, elem_ref_mode, &type_info)
                )
            }
        }
    } else {
        check_collection_len::<T, COUNT_ALLOCATES, ZST_NO_BACKING>(context, len, true)?;
        (0..len)
            .map(|_| C::read(context, elem_ref_mode, true))
            .collect::<Result<R, Error>>()
    }
}

fn list_element_type_matches_array(
    list: &FieldType,
    array: &FieldType,
    require_unframed_element: bool,
) -> bool {
    primitive_list::element_type_id(array.type_id).is_some_and(|element_type_id| {
        if list.type_id != type_id::LIST
            || list.generics.len() != 1
            || list.nullable
            || list.track_ref
            || array.nullable
            || array.track_ref
        {
            return false;
        }
        let element = &list.generics[0];
        // Nullable element schema is allowed for list<T?> -> array<T>; actual
        // null elements fail in the dense-array reader. Ref-tracked
        // element framing is rejected here because this path stays primitive-only.
        if require_unframed_element && element.track_ref {
            return false;
        }
        primitive_element_type_matches(element_type_id, element.type_id)
    })
}

pub(super) fn compatible_list_array_field(local: &FieldType, remote: &FieldType) -> bool {
    (local.type_id == type_id::LIST && list_element_type_matches_array(local, remote, false))
        || (remote.type_id == type_id::LIST && list_element_type_matches_array(remote, local, true))
}

fn primitive_element_type_matches(array_element_type_id: u32, list_element_type_id: u32) -> bool {
    array_element_type_id == list_element_type_id
        || same_numeric_family(array_element_type_id, list_element_type_id)
}

#[inline(always)]
fn primitive_element_min_wire_size(element_type_id: u32) -> Option<usize> {
    match element_type_id {
        type_id::BOOL
        | type_id::INT8
        | type_id::UINT8
        | type_id::VARINT32
        | type_id::VARINT64
        | type_id::VAR_UINT32
        | type_id::VAR_UINT64 => Some(1),
        type_id::INT16 | type_id::UINT16 | type_id::FLOAT16 | type_id::BFLOAT16 => Some(2),
        type_id::INT32
        | type_id::UINT32
        | type_id::FLOAT32
        | type_id::TAGGED_INT64
        | type_id::TAGGED_UINT64 => Some(4),
        type_id::INT64 | type_id::UINT64 | type_id::FLOAT64 => Some(8),
        _ => None,
    }
}

fn read_primitive_array_with_codec<T, C>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<Vec<T>, Error>
where
    T: 'static,
    C: Codec<T>,
{
    let size_bytes = context.reader.read_var_u32()? as usize;
    let elem_size =
        primitive_list::element_size(remote_field_type.type_id).ok_or_else(not_primitive_array)?;
    if size_bytes % elem_size != 0 {
        return Err(invalid_primitive_array_len());
    }
    context.reader.check_bound(size_bytes)?;
    let len = size_bytes / elem_size;
    let element_type_id = primitive_list::element_type_id(remote_field_type.type_id)
        .ok_or_else(not_primitive_array)?;
    reserve_collection_storage(context, len as u32, std::mem::size_of::<T>())?;
    let element_type = FieldType::new(element_type_id, false, Vec::new());
    let mut vec = Vec::with_capacity(len);
    for _ in 0..len {
        vec.push(C::read_data_with_type(context, &element_type)?);
    }
    Ok(vec)
}

pub(super) fn read_list_as_primitive_vec<T, C>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<Vec<T>, Error>
where
    T: 'static,
    C: Codec<T>,
{
    let element_type = generic_field_type(remote_field_type, 0, "list")?;
    let len = context.reader.read_var_u32()?;
    let len_usize = len as usize;
    if len == 0 {
        return Ok(Vec::new());
    }
    let header = context.reader.read_u8()?;
    if (header & HAS_NULL) != 0 {
        return Err(list_array_error(
            "compatible list to array field requires non-null elements",
        ));
    }
    if (header & TRACKING_REF) != 0 {
        return Err(list_array_error(
            "array-compatible list declares reference-tracked elements",
        ));
    }
    if (header & IS_SAME_TYPE) == 0 {
        return Err(list_array_error(
            "array-compatible list must declare same-type elements",
        ));
    }
    if (header & DECL_ELEMENT_TYPE) == 0 {
        return Err(list_array_error(
            "array-compatible list must declare element type",
        ));
    }
    // Validate the header before measuring unread element data. The minimum
    // wire-width proof must finish before destination storage is reserved.
    let element_min_size =
        primitive_element_min_wire_size(element_type.type_id).ok_or_else(|| {
            list_array_error("array-compatible list element is not a supported primitive type")
        })?;
    let min_size_bytes = len_usize
        .checked_mul(element_min_size)
        .ok_or_else(invalid_primitive_array_len)?;
    context.reader.check_bound(min_size_bytes)?;
    reserve_collection_storage(context, len, std::mem::size_of::<T>())?;
    let mut vec = Vec::with_capacity(len_usize);
    for _ in 0..len {
        vec.push(C::read_data_with_type(context, element_type)?);
    }
    Ok(vec)
}

#[cold]
#[inline(never)]
pub(super) fn read_vec_compatible_mismatch<T, C>(
    context: &mut ReadContext,
    local_field_type: &FieldType,
    remote_field_type: &FieldType,
) -> Result<Option<Vec<T>>, Error>
where
    T: 'static,
    C: Codec<T>,
{
    if local_field_type.type_id == type_id::LIST
        && list_element_type_matches_array(local_field_type, remote_field_type, false)
    {
        return read_array_data_as_vec_bridge::<T, C>(context, remote_field_type).map(Some);
    }
    Ok(None)
}

fn read_array_data_as_vec_bridge<T, C>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<Vec<T>, Error>
where
    T: 'static,
    C: Codec<T>,
{
    if field_ref_mode(remote_field_type) != RefMode::None {
        let ref_flag = context.reader.read_i8()?;
        if ref_flag == RefFlag::Null as i8 {
            return Ok(Vec::new());
        }
    }
    if crate::serializer::util::field_need_read_type_info(remote_field_type.type_id) {
        let remote = context.reader.read_u8()? as u32;
        if remote != remote_field_type.type_id {
            return Err(collection_type_mismatch(remote_field_type.type_id, remote));
        }
    }
    read_primitive_array_with_codec::<T, C>(context, remote_field_type)
}

#[cold]
#[inline(never)]
pub(super) fn read_primitive_array_vec_mismatch<T, C>(
    context: &mut ReadContext,
    local_field_type: &FieldType,
    remote_field_type: &FieldType,
) -> Result<Option<Vec<T>>, Error>
where
    T: 'static,
    C: Codec<T>,
{
    if remote_field_type.type_id == type_id::LIST
        && !remote_field_type.generics.is_empty()
        && list_element_type_matches_array(remote_field_type, local_field_type, true)
    {
        if field_ref_mode(remote_field_type) != RefMode::None {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Ok(Some(Vec::new()));
            }
        }
        return read_list_as_primitive_vec::<T, C>(context, remote_field_type).map(Some);
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::serializer::codec::{I32Codec, I64Codec};
    use crate::serializer::VecSerializer;
    use crate::{Config, Fory, Reader, TypeId, TypeResolver};

    #[derive(Clone, Debug, PartialEq)]
    struct EmptyValue(u8);

    struct EmptyValueSerializer;

    impl Serializer for EmptyValueSerializer {
        type Target = EmptyValue;

        fn write_data(_: &EmptyValue, _: &mut WriteContext) -> Result<(), Error> {
            Ok(())
        }

        fn read_data(_: &mut ReadContext) -> Result<EmptyValue, Error> {
            Ok(EmptyValue(0))
        }

        fn default_value(_: &mut ReadContext) -> Result<EmptyValue, Error> {
            Ok(EmptyValue(0))
        }

        fn static_type_id() -> TypeId {
            TypeId::EXT
        }
    }

    #[test]
    fn empty_items_use_root_budget() {
        let mut fory = Fory::builder().max_unbacked_container_items(2).build();
        fory.register_serializer::<EmptyValueSerializer>(200)
            .unwrap();
        let mut oversized = fory
            .serialize_with::<VecSerializer<EmptyValueSerializer>>(&vec![EmptyValue(0); 3])
            .unwrap();
        // Leave one unread byte so the preallocation gate admits the third item;
        // the zero-byte bodies must still exhaust the root work budget.
        oversized.push(0);
        let error = fory
            .deserialize_with::<VecSerializer<EmptyValueSerializer>>(&oversized)
            .unwrap_err();
        assert!(error.to_string().contains("max_unbacked_container_items"));

        let valid = fory
            .serialize_with::<VecSerializer<EmptyValueSerializer>>(&vec![EmptyValue(0); 2])
            .unwrap();
        assert_eq!(
            fory.deserialize_with::<VecSerializer<EmptyValueSerializer>>(&valid)
                .unwrap(),
            vec![EmptyValue(0); 2]
        );
    }

    #[test]
    fn positive_items_ignore_zero_budget() {
        let fory = Fory::builder().max_unbacked_container_items(0).build();
        let value = vec![1i32, 2, 3];
        let bytes = fory.serialize_with::<VecSerializer<i32>>(&value).unwrap();
        assert_eq!(
            fory.deserialize_with::<VecSerializer<i32>>(&bytes).unwrap(),
            value
        );
    }

    #[test]
    fn count_allocation_uses_allowance() {
        let bytes = [3, IS_SAME_TYPE | DECL_ELEMENT_TYPE];
        let mut context = ReadContext::new(TypeResolver::default(), Config::default());
        context.remaining_graph_memory_bytes = usize::MAX;
        context.remaining_unbacked_container_items = 1;
        context.attach_reader(Reader::new(&bytes));

        let error = read_collection_value_data::<
            Vec<EmptyValue>,
            EmptyValue,
            EmptyValueSerializer,
            true,
            true,
        >(&mut context)
        .unwrap_err();
        assert!(matches!(error, Error::BufferOutOfBound(..)));
        assert_eq!(context.reader.get_cursor(), 2);
    }

    #[test]
    fn settles_periodic_window_and_tail() {
        let mut bytes = Vec::new();
        let mut writer = crate::Writer::from_buffer(&mut bytes);
        writer.write_var_u32(1025);
        writer.write_u8(IS_SAME_TYPE | DECL_ELEMENT_TYPE);
        writer.write_u8(0);
        let mut context = ReadContext::new(TypeResolver::default(), Config::default());
        context.remaining_graph_memory_bytes = usize::MAX;
        context.remaining_unbacked_container_items = 1024;
        context.attach_reader(Reader::new(&bytes));

        let error = read_collection_value_data::<
            Vec<EmptyValue>,
            EmptyValue,
            EmptyValueSerializer,
            true,
            true,
        >(&mut context)
        .unwrap_err();
        assert!(error.to_string().contains("max_unbacked_container_items"));
        assert_eq!(context.remaining_unbacked_container_items, 0);
    }

    #[test]
    fn list_array_checks_fixed_width_body() {
        let bytes = [2, IS_SAME_TYPE | DECL_ELEMENT_TYPE, 1, 0, 0, 0];
        let config = Config::default();
        let mut context = ReadContext::new(TypeResolver::default(), config);
        let graph_memory = 2 * std::mem::size_of::<i32>();
        context.remaining_graph_memory_bytes = graph_memory;
        context.attach_reader(Reader::new(&bytes));
        let remote = FieldType::new(
            type_id::LIST,
            false,
            vec![FieldType::new(type_id::INT32, false, Vec::new())],
        );

        let error = read_list_as_primitive_vec::<
            i32,
            I32Codec<{ type_id::INT32 as u8 }, false, false>,
        >(&mut context, &remote)
        .unwrap_err();

        assert!(matches!(error, Error::BufferOutOfBound(..)));
        assert_eq!(context.reader.get_cursor(), 2);
        assert_eq!(context.remaining_graph_memory_bytes, graph_memory);
    }

    #[test]
    fn list_array_checks_tagged_body() {
        let bytes = [2, IS_SAME_TYPE | DECL_ELEMENT_TYPE, 0, 0, 0, 0];
        let config = Config::default();
        let mut context = ReadContext::new(TypeResolver::default(), config);
        let graph_memory = 2 * std::mem::size_of::<i64>();
        context.remaining_graph_memory_bytes = graph_memory;
        context.attach_reader(Reader::new(&bytes));
        let remote = FieldType::new(
            type_id::LIST,
            false,
            vec![FieldType::new(type_id::TAGGED_INT64, false, Vec::new())],
        );

        let error = read_list_as_primitive_vec::<
            i64,
            I64Codec<{ type_id::TAGGED_INT64 as u8 }, false, false>,
        >(&mut context, &remote)
        .unwrap_err();

        assert!(matches!(error, Error::BufferOutOfBound(..)));
        assert_eq!(context.reader.get_cursor(), 2);
        assert_eq!(context.remaining_graph_memory_bytes, graph_memory);
    }
}
