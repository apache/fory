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
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::{need_to_write_type_for_field, RefFlag, RefMode, PRIMITIVE_ARRAY_TYPES};

const TRACKING_REF: u8 = 0b1;

pub const HAS_NULL: u8 = 0b10;

// Whether collection elements type is declare type.
pub const DECL_ELEMENT_TYPE: u8 = 0b100;

//  Whether collection elements type same.
pub const IS_SAME_TYPE: u8 = 0b1000;

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
    context.writer.write_var_uint32(len as u32);
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
    let len = context.reader.read_varuint32()?;
    if len == 0 {
        return Ok(C::from_iter(std::iter::empty()));
    }
    let remaining = context
        .reader
        .bf
        .len()
        .saturating_sub(context.reader.cursor);
    if len as usize > remaining {
        return Err(Error::invalid_data(format!(
            "collection length {} exceeds buffer remaining {}",
            len, remaining
        )));
    }
    context.check_collection_size(len as usize)?;
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
    let len = context.reader.read_varuint32()?;
    if len == 0 {
        return Ok(Vec::new());
    }
    let remaining = context
        .reader
        .bf
        .len()
        .saturating_sub(context.reader.cursor);
    if len as usize > remaining {
        return Err(Error::invalid_data(format!(
            "collection length {} exceeds buffer remaining {}",
            len, remaining
        )));
    }
    context.check_collection_size(len as usize)?;
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

    let mut vec = Vec::with_capacity(len as usize);
    if is_same_type {
        let type_info = if !is_declared {
            context.read_any_type_info()?
        } else {
            T::fory_get_type_info(context.get_type_resolver())?
        };
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
    } else {
        for _ in 0..len {
            vec.push(T::fory_read(context, elem_ref_mode, true)?);
        }
    }
    Ok(vec)
}

/// Slow but versatile collection deserialization for dynamic trait object and shared/circular reference.
pub fn read_collection_data_dyn_ref<C, T>(context: &mut ReadContext, len: u32) -> Result<C, Error>
where
    T: Serializer + ForyDefault,
    C: FromIterator<T>,
{
    // Read header
    let header = context.reader.read_u8()?;
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
        (0..len)
            .map(|_| T::fory_read(context, elem_ref_mode, true))
            .collect::<Result<C, Error>>()
    }
}
