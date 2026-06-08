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

use super::codec::{field_ref_mode, generic_field_type, same_numeric_family, Codec};
use crate::context::ReadContext;
use crate::context::WriteContext;
use crate::ensure;
use crate::error::Error;
use crate::meta::FieldType;
use crate::resolver::{RefFlag, RefMode};
use crate::serializer::{ForyDefault, Serializer};
use crate::type_id::{self, need_to_write_type_for_field, TypeId, PRIMITIVE_ARRAY_TYPES};
use crate::types::{bfloat16::bfloat16, float16::float16};

const TRACKING_REF: u8 = 0b1;

pub const HAS_NULL: u8 = 0b10;

// Whether collection elements type is declare type.
pub const DECL_ELEMENT_TYPE: u8 = 0b100;

//  Whether collection elements type same.
pub const IS_SAME_TYPE: u8 = 0b1000;

#[cold]
fn collection_size_limit_exceeded(len: u32, max: u32) -> Error {
    Error::size_limit_exceeded(format!("Collection size {} exceeds limit {}", len, max))
}

fn check_collection_len<T: Serializer>(context: &ReadContext, len: u32) -> Result<(), Error> {
    if std::mem::size_of::<T>() == 0 {
        return Ok(());
    }
    let len = len as usize;
    let remaining = context.reader.slice_after_cursor().len();
    if len > remaining {
        let cursor = context.reader.get_cursor();
        return Err(Error::buffer_out_of_bound(cursor, len, cursor + remaining));
    }
    Ok(())
}

pub fn write_collection_type_info(
    context: &mut WriteContext,
    collection_type_id: u32,
) -> Result<(), Error> {
    context.writer.write_u8(collection_type_id as u8);
    Ok(())
}

pub fn write_collection_data<'a, T, I>(
    iter: I,
    context: &mut WriteContext,
    has_generics: bool,
) -> Result<(), Error>
where
    T: Serializer + 'a,
    I: IntoIterator<Item = &'a T>,
    I::IntoIter: ExactSizeIterator + Clone,
{
    let iter = iter.into_iter();
    let len = iter.len();
    context.writer.write_var_u32(len as u32);
    if len == 0 {
        return Ok(());
    }
    if T::fory_is_polymorphic() || T::fory_is_shared_ref() {
        return write_collection_data_dyn_ref(iter, context, has_generics);
    }
    let mut header = IS_SAME_TYPE;
    let mut has_null = false;
    let elem_static_type_id = T::fory_static_type_id();
    let is_elem_declared = has_generics && !need_to_write_type_for_field(elem_static_type_id);
    if T::fory_is_option() {
        // iter.clone() is zero-copy
        for item in iter.clone() {
            if item.fory_is_none() {
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
        T::fory_write_type_info(context)?;
    }
    // Pre-reserve buffer space to avoid per-element capacity checks in the write loop.
    context.writer.reserve(len * T::fory_reserved_space());
    if !has_null {
        for item in iter {
            item.fory_write_data_generic(context, has_generics)?;
        }
    } else {
        for item in iter {
            if item.fory_is_none() {
                context.writer.write_u8(RefFlag::Null as u8);
                continue;
            }
            context.writer.write_u8(RefFlag::NotNullValue as u8);
            item.fory_write_data_generic(context, has_generics)?;
        }
    }

    Ok(())
}

/// Slow but versatile collection serialization for dynamic trait object and shared/circular reference.
pub fn write_collection_data_dyn_ref<'a, T, I>(
    iter: I,
    context: &mut WriteContext,
    has_generics: bool,
) -> Result<(), Error>
where
    T: Serializer + 'a,
    I: IntoIterator<Item = &'a T>,
    I::IntoIter: ExactSizeIterator + Clone,
{
    let elem_static_type_id = T::fory_static_type_id();
    let is_elem_declared = has_generics && !need_to_write_type_for_field(elem_static_type_id);
    let elem_is_polymorphic = T::fory_is_polymorphic();
    let elem_is_shared_ref = T::fory_is_shared_ref();

    let iter = iter.into_iter();
    let mut has_null = false;
    let mut is_same_type = true;
    let mut first_type_id: Option<std::any::TypeId> = None;

    for item in iter.clone() {
        if item.fory_is_none() {
            has_null = true;
        } else if elem_is_polymorphic && is_same_type {
            let concrete_id = item.fory_concrete_type_id();
            if let Some(first_id) = first_type_id {
                if first_id != concrete_id {
                    is_same_type = false;
                }
            } else {
                first_type_id = Some(concrete_id);
            }
        }
    }

    if elem_is_polymorphic && is_same_type && first_type_id.is_none() {
        // All elements are null for a polymorphic collection; fallback to per-element typing.
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

    if is_same_type && !is_elem_declared {
        if elem_is_polymorphic {
            let type_id = first_type_id.ok_or_else(|| {
                Error::type_error(
                    "Unable to determine concrete type for polymorphic collection elements",
                )
            })?;
            context.write_any_type_info(T::fory_static_type_id() as u32, type_id)?;
        } else {
            T::fory_write_type_info(context)?;
        }
    }
    // Write elements data
    // Compute RefMode from flags
    let elem_ref_mode = if elem_is_shared_ref {
        RefMode::Tracking
    } else if has_null {
        RefMode::NullOnly
    } else {
        RefMode::None
    };

    if is_same_type {
        // All elements are same type
        if elem_ref_mode == RefMode::None {
            // No null elements, no tracking
            for item in iter {
                item.fory_write_data_generic(context, has_generics)?;
            }
        } else {
            // Has null or tracking
            for item in iter {
                item.fory_write(context, elem_ref_mode, false, has_generics)?;
            }
        }
    } else {
        // Different types (polymorphic elements with different types)
        for item in iter {
            item.fory_write(context, elem_ref_mode, true, has_generics)?;
        }
    }

    Ok(())
}

pub fn read_collection_type_info(
    context: &mut ReadContext,
    collection_type_id: u32,
) -> Result<(), Error> {
    let remote_collection_type_id = context.reader.read_u8()? as u32;
    if PRIMITIVE_ARRAY_TYPES.contains(&remote_collection_type_id) {
        return Err(Error::type_error(
            "Vec<number> belongs to the `number_array` type, \
            and Vec<Option<number>> belongs to the `list` type. \
            You should not read data of type `number_array` as data of type `list`.",
        ));
    }
    ensure!(
        collection_type_id == remote_collection_type_id,
        Error::type_mismatch(collection_type_id, remote_collection_type_id)
    );
    Ok(())
}

pub fn read_collection_data<C, T>(context: &mut ReadContext) -> Result<C, Error>
where
    T: Serializer + ForyDefault,
    C: FromIterator<T>,
{
    let len = context.reader.read_var_u32()?;
    if len == 0 {
        return Ok(C::from_iter(std::iter::empty()));
    }
    let max = context.max_collection_size();
    if len > max {
        return Err(collection_size_limit_exceeded(len, max));
    }
    if T::fory_is_polymorphic() || T::fory_is_shared_ref() {
        return read_collection_data_dyn_ref(context, len);
    }
    let header = context.reader.read_u8()?;
    let declared = (header & DECL_ELEMENT_TYPE) != 0;
    if !declared {
        // context.read_any_type_info();
        // TODO check whether type info consistent with T
        T::fory_read_type_info(context)?;
    }
    let has_null = (header & HAS_NULL) != 0;
    ensure!(
        (header & IS_SAME_TYPE) != 0,
        Error::type_error("Type inconsistent, target type is not polymorphic")
    );
    check_collection_len::<T>(context, len)?;
    if !has_null {
        (0..len)
            .map(|_| T::fory_read_data(context))
            .collect::<Result<C, Error>>()
    } else {
        (0..len)
            .map(|_| {
                let flag = context.reader.read_i8()?;
                if flag == RefFlag::Null as i8 {
                    return Ok(T::fory_default());
                }
                T::fory_read_data(context)
            })
            .collect::<Result<C, Error>>()
    }
}

#[inline(always)]
pub fn read_vec_data<T>(context: &mut ReadContext) -> Result<Vec<T>, Error>
where
    T: Serializer + ForyDefault,
{
    let len = context.reader.read_var_u32()?;
    if len == 0 {
        return Ok(Vec::new());
    }
    let max = context.max_collection_size();
    if len > max {
        return Err(collection_size_limit_exceeded(len, max));
    }
    if T::fory_is_polymorphic() || T::fory_is_shared_ref() {
        return read_vec_data_dyn_ref(context, len);
    }
    let header = context.reader.read_u8()?;
    let declared = (header & DECL_ELEMENT_TYPE) != 0;
    if !declared {
        T::fory_read_type_info(context)?;
    }
    let has_null = (header & HAS_NULL) != 0;
    ensure!(
        (header & IS_SAME_TYPE) != 0,
        Error::type_error("Type inconsistent, target type is not polymorphic")
    );
    check_collection_len::<T>(context, len)?;
    let mut vec = Vec::with_capacity(len as usize);
    if !has_null {
        for _ in 0..len {
            vec.push(T::fory_read_data(context)?);
        }
    } else {
        for _ in 0..len {
            let flag = context.reader.read_i8()?;
            if flag == RefFlag::Null as i8 {
                vec.push(T::fory_default());
            } else {
                vec.push(T::fory_read_data(context)?);
            }
        }
    }
    Ok(vec)
}

#[inline(always)]
fn read_vec_data_dyn_ref<T>(context: &mut ReadContext, len: u32) -> Result<Vec<T>, Error>
where
    T: Serializer + ForyDefault,
{
    let header = context.reader.read_u8()?;
    // IMPORTANT: collection readers must honor the ref/null bits written on
    // the wire, not the local element type shape that may imply a different
    // ref policy. Shared xlang tests intentionally deserialize one ref policy
    // and then serialize another local payload. DO NOT REMOVE this comment.
    let is_track_ref = (header & TRACKING_REF) != 0;
    let is_same_type = (header & IS_SAME_TYPE) != 0;
    let has_null = (header & HAS_NULL) != 0;
    let is_declared = (header & DECL_ELEMENT_TYPE) != 0;

    let elem_ref_mode = if is_track_ref {
        RefMode::Tracking
    } else if has_null {
        RefMode::NullOnly
    } else {
        RefMode::None
    };
    if is_same_type {
        let type_info = if !is_declared {
            context.read_any_type_info()?
        } else {
            T::fory_get_type_info(context.get_type_resolver())?
        };
        check_collection_len::<T>(context, len)?;
        let mut vec = Vec::with_capacity(len as usize);
        if elem_ref_mode == RefMode::None {
            for _ in 0..len {
                vec.push(T::fory_read_with_type_info(
                    context,
                    RefMode::None,
                    type_info.clone(),
                )?);
            }
        } else {
            for _ in 0..len {
                vec.push(T::fory_read_with_type_info(
                    context,
                    elem_ref_mode,
                    type_info.clone(),
                )?);
            }
        }
        Ok(vec)
    } else {
        check_collection_len::<T>(context, len)?;
        let mut vec = Vec::with_capacity(len as usize);
        for _ in 0..len {
            vec.push(T::fory_read(context, elem_ref_mode, true)?);
        }
        Ok(vec)
    }
}

/// Slow but versatile collection deserialization for dynamic trait object and shared/circular reference.
pub fn read_collection_data_dyn_ref<C, T>(context: &mut ReadContext, len: u32) -> Result<C, Error>
where
    T: Serializer + ForyDefault,
    C: FromIterator<T>,
{
    // Read header
    let header = context.reader.read_u8()?;
    // IMPORTANT: dynamic/shared-ref collection reads still obey the wire
    // header first. Local Rust type information must not override whether the
    // sender wrote ref flags for this payload. DO NOT REMOVE this comment.
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
        let type_info = if !is_declared {
            context.read_any_type_info()?
        } else {
            T::fory_get_type_info(context.get_type_resolver())?
        };
        check_collection_len::<T>(context, len)?;
        // All elements are same type
        if elem_ref_mode == RefMode::None {
            // No null elements, no tracking
            (0..len)
                .map(|_| T::fory_read_with_type_info(context, RefMode::None, type_info.clone()))
                .collect::<Result<C, Error>>()
        } else {
            // Has null or tracking - use ref mode
            (0..len)
                .map(|_| T::fory_read_with_type_info(context, elem_ref_mode, type_info.clone()))
                .collect::<Result<C, Error>>()
        }
    } else {
        check_collection_len::<T>(context, len)?;
        (0..len)
            .map(|_| T::fory_read(context, elem_ref_mode, true))
            .collect::<Result<C, Error>>()
    }
}

fn primitive_array_element_type_id(type_id: u32) -> Option<u32> {
    match type_id {
        type_id::BOOL_ARRAY => Some(type_id::BOOL),
        type_id::INT8_ARRAY => Some(type_id::INT8),
        type_id::INT16_ARRAY => Some(type_id::INT16),
        type_id::INT32_ARRAY => Some(type_id::INT32),
        type_id::INT64_ARRAY => Some(type_id::INT64),
        type_id::UINT8_ARRAY => Some(type_id::UINT8),
        type_id::UINT16_ARRAY => Some(type_id::UINT16),
        type_id::UINT32_ARRAY => Some(type_id::UINT32),
        type_id::UINT64_ARRAY => Some(type_id::UINT64),
        type_id::FLOAT16_ARRAY => Some(type_id::FLOAT16),
        type_id::BFLOAT16_ARRAY => Some(type_id::BFLOAT16),
        type_id::FLOAT32_ARRAY => Some(type_id::FLOAT32),
        type_id::FLOAT64_ARRAY => Some(type_id::FLOAT64),
        _ => None,
    }
}

fn primitive_array_element_size(type_id: u32) -> Option<usize> {
    match type_id {
        type_id::BOOL_ARRAY | type_id::INT8_ARRAY | type_id::UINT8_ARRAY => Some(1),
        type_id::INT16_ARRAY
        | type_id::UINT16_ARRAY
        | type_id::FLOAT16_ARRAY
        | type_id::BFLOAT16_ARRAY => Some(2),
        type_id::INT32_ARRAY | type_id::UINT32_ARRAY | type_id::FLOAT32_ARRAY => Some(4),
        type_id::INT64_ARRAY | type_id::UINT64_ARRAY | type_id::FLOAT64_ARRAY => Some(8),
        _ => None,
    }
}

fn primitive_array_type_matches_rust_type<T: 'static>(type_id: u32) -> bool {
    let rust_type = std::any::TypeId::of::<T>();
    match type_id {
        type_id::BOOL_ARRAY => rust_type == std::any::TypeId::of::<bool>(),
        type_id::INT8_ARRAY => rust_type == std::any::TypeId::of::<i8>(),
        type_id::INT16_ARRAY => rust_type == std::any::TypeId::of::<i16>(),
        type_id::INT32_ARRAY => rust_type == std::any::TypeId::of::<i32>(),
        type_id::INT64_ARRAY => rust_type == std::any::TypeId::of::<i64>(),
        type_id::UINT8_ARRAY => rust_type == std::any::TypeId::of::<u8>(),
        type_id::UINT16_ARRAY => rust_type == std::any::TypeId::of::<u16>(),
        type_id::UINT32_ARRAY => rust_type == std::any::TypeId::of::<u32>(),
        type_id::UINT64_ARRAY => rust_type == std::any::TypeId::of::<u64>(),
        type_id::FLOAT16_ARRAY => rust_type == std::any::TypeId::of::<float16>(),
        type_id::BFLOAT16_ARRAY => rust_type == std::any::TypeId::of::<bfloat16>(),
        type_id::FLOAT32_ARRAY => rust_type == std::any::TypeId::of::<f32>(),
        type_id::FLOAT64_ARRAY => rust_type == std::any::TypeId::of::<f64>(),
        _ => false,
    }
}

fn read_primitive_array_data_bulk<T: 'static>(
    context: &mut ReadContext,
    type_id: u32,
    size_bytes: usize,
    len: usize,
) -> Option<Result<Vec<T>, Error>> {
    if !primitive_array_type_matches_rust_type::<T>(type_id) {
        return None;
    }
    #[cfg(target_endian = "little")]
    {
        let mut vec: Vec<T> = Vec::with_capacity(len);
        let src = match context.reader.read_bytes(size_bytes) {
            Ok(src) => src,
            Err(error) => return Some(Err(error)),
        };
        unsafe {
            std::ptr::copy_nonoverlapping(src.as_ptr(), vec.as_mut_ptr() as *mut u8, size_bytes);
            vec.set_len(len);
        }
        Some(Ok(vec))
    }
    #[cfg(target_endian = "big")]
    {
        let _ = (context, size_bytes, len);
        None
    }
}

fn list_element_type_matches_array(
    list: &FieldType,
    array: &FieldType,
    require_unframed_element: bool,
) -> bool {
    primitive_array_element_type_id(array.type_id).is_some_and(|element_type_id| {
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
        // null payload elements fail in the dense-array reader. Ref-tracked
        // element framing is rejected here because this path stays primitive-only.
        if require_unframed_element && element.track_ref {
            return false;
        }
        primitive_array_element_type_matches(element_type_id, element.type_id)
    })
}

pub(super) fn compatible_list_array_field(local: &FieldType, remote: &FieldType) -> bool {
    (local.type_id == type_id::LIST && list_element_type_matches_array(local, remote, false))
        || (remote.type_id == type_id::LIST && list_element_type_matches_array(remote, local, true))
}

fn primitive_array_element_type_matches(
    array_element_type_id: u32,
    list_element_type_id: u32,
) -> bool {
    array_element_type_id == list_element_type_id
        || same_numeric_family(array_element_type_id, list_element_type_id)
}

fn read_primitive_array_data_with_codec<T, C>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<Vec<T>, Error>
where
    T: 'static,
    C: Codec<T>,
{
    let size_bytes = context.reader.read_var_u32()? as usize;
    let elem_size = primitive_array_element_size(remote_field_type.type_id)
        .ok_or_else(|| Error::type_error("array-compatible field is not a primitive array"))?;
    if size_bytes % elem_size != 0 {
        return Err(Error::invalid_data("Invalid data length"));
    }
    let max = context.max_binary_size() as usize;
    if size_bytes > max {
        return Err(Error::size_limit_exceeded(format!(
            "Binary size {} exceeds limit {}",
            size_bytes, max
        )));
    }
    let remaining = context.reader.slice_after_cursor().len();
    if size_bytes > remaining {
        let cursor = context.reader.get_cursor();
        return Err(Error::buffer_out_of_bound(
            cursor,
            size_bytes,
            cursor + remaining,
        ));
    }
    let len = size_bytes / elem_size;
    let element_type_id = primitive_array_element_type_id(remote_field_type.type_id)
        .ok_or_else(|| Error::type_error("array-compatible field is not a primitive array"))?;
    if let Some(result) =
        read_primitive_array_data_bulk::<T>(context, remote_field_type.type_id, size_bytes, len)
    {
        return result;
    }
    let element_type = FieldType::new(element_type_id, false, Vec::new());
    let mut vec = Vec::with_capacity(len);
    for _ in 0..len {
        vec.push(C::read_data_with_type(context, &element_type)?);
    }
    Ok(vec)
}

pub(super) trait CompatibleListArrayElement: Serializer + ForyDefault {
    fn read_list_array_element(
        context: &mut ReadContext,
        remote_type_id: u32,
    ) -> Result<Self, Error>;
}

macro_rules! compatible_exact_element {
    ($ty:ty, $type_id:expr, $reader:ident) => {
        impl CompatibleListArrayElement for $ty {
            #[inline(always)]
            fn read_list_array_element(
                context: &mut ReadContext,
                remote_type_id: u32,
            ) -> Result<Self, Error> {
                if remote_type_id == $type_id {
                    context.reader.$reader()
                } else {
                    Err(Error::type_mismatch(
                        <$ty as Serializer>::fory_static_type_id() as u32,
                        remote_type_id,
                    ))
                }
            }
        }
    };
}

macro_rules! compatible_integer_element {
    ($ty:ty, $fixed_type:expr, $var_type:expr, $fixed_reader:ident, $var_reader:ident) => {
        impl CompatibleListArrayElement for $ty {
            #[inline(always)]
            fn read_list_array_element(
                context: &mut ReadContext,
                remote_type_id: u32,
            ) -> Result<Self, Error> {
                match remote_type_id {
                    x if x == $fixed_type => context.reader.$fixed_reader(),
                    x if x == $var_type => context.reader.$var_reader(),
                    _ => Err(Error::type_mismatch(
                        <$ty as Serializer>::fory_static_type_id() as u32,
                        remote_type_id,
                    )),
                }
            }
        }
    };
}

macro_rules! compatible_tagged_integer_element {
    (
        $ty:ty,
        $fixed_type:expr,
        $var_type:expr,
        $tagged_type:expr,
        $fixed_reader:ident,
        $var_reader:ident,
        $tagged_reader:ident
    ) => {
        impl CompatibleListArrayElement for $ty {
            #[inline(always)]
            fn read_list_array_element(
                context: &mut ReadContext,
                remote_type_id: u32,
            ) -> Result<Self, Error> {
                match remote_type_id {
                    x if x == $fixed_type => context.reader.$fixed_reader(),
                    x if x == $var_type => context.reader.$var_reader(),
                    x if x == $tagged_type => context.reader.$tagged_reader(),
                    _ => Err(Error::type_mismatch(
                        <$ty as Serializer>::fory_static_type_id() as u32,
                        remote_type_id,
                    )),
                }
            }
        }
    };
}

impl CompatibleListArrayElement for bool {
    #[inline(always)]
    fn read_list_array_element(
        context: &mut ReadContext,
        remote_type_id: u32,
    ) -> Result<Self, Error> {
        if remote_type_id == type_id::BOOL {
            Ok(context.reader.read_u8()? == 1)
        } else {
            Err(Error::type_mismatch(type_id::BOOL, remote_type_id))
        }
    }
}

compatible_exact_element!(i8, type_id::INT8, read_i8);
compatible_exact_element!(i16, type_id::INT16, read_i16);
compatible_integer_element!(
    i32,
    type_id::INT32,
    type_id::VARINT32,
    read_i32,
    read_var_i32
);
compatible_tagged_integer_element!(
    i64,
    type_id::INT64,
    type_id::VARINT64,
    type_id::TAGGED_INT64,
    read_i64,
    read_var_i64,
    read_tagged_i64
);
compatible_exact_element!(u8, type_id::UINT8, read_u8);
compatible_exact_element!(u16, type_id::UINT16, read_u16);
compatible_integer_element!(
    u32,
    type_id::UINT32,
    type_id::VAR_UINT32,
    read_u32,
    read_var_u32
);
compatible_tagged_integer_element!(
    u64,
    type_id::UINT64,
    type_id::VAR_UINT64,
    type_id::TAGGED_UINT64,
    read_u64,
    read_var_u64,
    read_tagged_u64
);
compatible_exact_element!(float16, type_id::FLOAT16, read_f16);
compatible_exact_element!(bfloat16, type_id::BFLOAT16, read_bf16);
compatible_exact_element!(f32, type_id::FLOAT32, read_f32);
compatible_exact_element!(f64, type_id::FLOAT64, read_f64);
compatible_exact_element!(i128, type_id::INT128, read_i128);
compatible_exact_element!(u128, TypeId::U128 as u32, read_u128);
compatible_exact_element!(isize, type_id::ISIZE, read_isize);
compatible_exact_element!(usize, type_id::USIZE, read_usize);

fn read_non_nullable_list_data_with_type<T>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<Vec<T>, Error>
where
    T: CompatibleListArrayElement,
{
    let element_type = generic_field_type(remote_field_type, 0, "list")?;
    let len = context.reader.read_var_u32()?;
    if len == 0 {
        return Ok(Vec::new());
    }
    let max = context.max_collection_size();
    if len > max {
        return Err(Error::size_limit_exceeded(format!(
            "Collection size {} exceeds limit {}",
            len, max
        )));
    }
    let header = context.reader.read_u8()?;
    if (header & HAS_NULL) != 0 {
        return Err(Error::type_error(
            "compatible list to array field requires non-null elements",
        ));
    }
    if (header & TRACKING_REF) != 0 {
        return Err(Error::type_error(
            "array-compatible list declares reference-tracked elements",
        ));
    }
    if (header & IS_SAME_TYPE) == 0 {
        return Err(Error::type_error(
            "array-compatible list must declare same-type elements",
        ));
    }
    if (header & DECL_ELEMENT_TYPE) == 0 {
        return Err(Error::type_error(
            "array-compatible list must declare element type",
        ));
    }
    let mut vec = Vec::with_capacity(len as usize);
    for _ in 0..len {
        vec.push(T::read_list_array_element(context, element_type.type_id)?);
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
            return Err(Error::type_mismatch(remote_field_type.type_id, remote));
        }
    }
    read_primitive_array_data_with_codec::<T, C>(context, remote_field_type)
}

#[cold]
#[inline(never)]
pub(super) fn read_primitive_array_vec_compatible_mismatch<T>(
    context: &mut ReadContext,
    local_field_type: &FieldType,
    remote_field_type: &FieldType,
) -> Result<Option<Vec<T>>, Error>
where
    T: CompatibleListArrayElement,
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
        return read_non_nullable_list_data_with_type::<T>(context, remote_field_type).map(Some);
    }
    Ok(None)
}
