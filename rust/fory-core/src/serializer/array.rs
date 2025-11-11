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

use crate::error::Error;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::primitive_list;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::mem;
use std::mem::MaybeUninit;

use super::collection::{
    read_collection_type_info, write_collection_data, write_collection_type_info,
    DECL_ELEMENT_TYPE, HAS_NULL, IS_SAME_TYPE,
};
use super::list::{get_primitive_type_id, is_primitive_type};
use crate::ensure;
use crate::types::RefFlag;

// Collection header flags (matching collection.rs private constants)
const TRACKING_REF: u8 = 0b1;

/// Validates that the deserialized length matches the expected array size N.
#[inline(always)]
fn validate_array_length(actual: usize, expected: usize) -> Result<(), Error> {
    if actual != expected {
        return Err(Error::invalid_data(format!(
            "Array length mismatch: expected {}, got {}",
            expected, actual
        )));
    }
    Ok(())
}

/// Converts initialized MaybeUninit array to a regular array.
/// # Safety
/// All elements in the array must be initialized.
#[inline(always)]
unsafe fn assume_array_init<T, const N: usize>(arr: &[std::mem::MaybeUninit<T>; N]) -> [T; N] {
    std::ptr::read(arr as *const _ as *const [T; N])
}

/// Read primitive array directly without intermediate Vec allocation
#[inline]
fn read_primitive_array<T, const N: usize>(context: &mut ReadContext) -> Result<[T; N], Error>
where
    T: Serializer + ForyDefault,
{
    // Read the size in bytes
    let size_bytes = context.reader.read_varuint32()? as usize;
    let elem_size = mem::size_of::<T>();
    if size_bytes % elem_size != 0 {
        return Err(Error::invalid_data("Invalid data length"));
    }
    let len = size_bytes / elem_size;
    validate_array_length(len, N)?;
    // Handle zero-sized arrays
    if N == 0 {
        // Safe: std::mem::zeroed() is explicitly safe for zero-sized types
        return Ok(unsafe { std::mem::zeroed() });
    }
    // Create uninitialized array
    let mut arr: [MaybeUninit<T>; N] = unsafe { MaybeUninit::uninit().assume_init() };
    // Read bytes directly into array memory
    unsafe {
        let dst_ptr = arr.as_mut_ptr() as *mut u8;
        let src = context.reader.read_bytes(size_bytes)?;
        std::ptr::copy_nonoverlapping(src.as_ptr(), dst_ptr, size_bytes);
    }
    // Safety: all elements are now initialized with data from the reader
    Ok(unsafe { assume_array_init(&arr) })
}

/// Read complex (non-primitive) array directly without intermediate Vec allocation
#[inline]
fn read_complex_array<T, const N: usize>(context: &mut ReadContext) -> Result<[T; N], Error>
where
    T: Serializer + ForyDefault,
{
    // Read collection length
    let len = context.reader.read_varuint32()? as usize;
    validate_array_length(len, N)?;
    // Handle zero-sized arrays
    if N == 0 {
        // Safe: std::mem::zeroed() is explicitly safe for zero-sized types
        return Ok(unsafe { std::mem::zeroed() });
    }
    // Handle polymorphic or shared ref types - need to use collection logic
    if T::fory_is_polymorphic() || T::fory_is_shared_ref() {
        return read_complex_array_dyn_ref(context, len);
    }
    // Read header
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
    // Create uninitialized array
    let mut arr: [MaybeUninit<T>; N] = unsafe { MaybeUninit::uninit().assume_init() };
    // Read elements directly into array
    if !has_null {
        for elem_slot in &mut arr[..] {
            let elem = T::fory_read_data(context)?;
            elem_slot.write(elem);
        }
    } else {
        for elem_slot in &mut arr[..] {
            let flag = context.reader.read_i8()?;
            let elem = if flag == RefFlag::Null as i8 {
                T::fory_default()
            } else {
                T::fory_read_data(context)?
            };
            elem_slot.write(elem);
        }
    }
    // Safety: all elements are now initialized
    Ok(unsafe { std::ptr::read(&arr as *const _ as *const [T; N]) })
}

/// Read complex array with dynamic/polymorphic types
#[inline]
fn read_complex_array_dyn_ref<T, const N: usize>(
    context: &mut ReadContext,
    len: usize,
) -> Result<[T; N], Error>
where
    T: Serializer + ForyDefault,
{
    // Read header
    let header = context.reader.read_u8()?;
    let is_track_ref = (header & TRACKING_REF) != 0;
    let is_same_type = (header & IS_SAME_TYPE) != 0;
    let has_null = (header & HAS_NULL) != 0;
    let is_declared = (header & DECL_ELEMENT_TYPE) != 0;
    // Create uninitialized array
    let mut arr: [MaybeUninit<T>; N] = unsafe { MaybeUninit::uninit().assume_init() };
    // Read elements
    if is_same_type {
        let type_info = if !is_declared {
            context.read_any_typeinfo()?
        } else {
            let rs_type_id = std::any::TypeId::of::<T>();
            context.get_type_resolver().get_type_info(&rs_type_id)?
        };
        if is_track_ref {
            for elem_slot in arr.iter_mut().take(len) {
                let elem = T::fory_read_with_type_info(context, true, type_info.clone())?;
                elem_slot.write(elem);
            }
        } else if !has_null {
            for elem_slot in arr.iter_mut().take(len) {
                let elem = T::fory_read_with_type_info(context, false, type_info.clone())?;
                elem_slot.write(elem);
            }
        } else {
            for elem_slot in arr.iter_mut().take(len) {
                let flag = context.reader.read_i8()?;
                let elem = if flag == RefFlag::Null as i8 {
                    T::fory_default()
                } else {
                    T::fory_read_with_type_info(context, false, type_info.clone())?
                };
                elem_slot.write(elem);
            }
        }
    } else {
        for elem_slot in arr.iter_mut().take(len) {
            let elem = T::fory_read(context, is_track_ref, true)?;
            elem_slot.write(elem);
        }
    }
    // Safety: all elements are now initialized
    Ok(unsafe { std::ptr::read(&arr as *const _ as *const [T; N]) })
}

// Implement Serializer for fixed-size arrays [T; N] where N is a const generic parameter
impl<T: Serializer + ForyDefault, const N: usize> Serializer for [T; N] {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        if is_primitive_type::<T>() {
            primitive_list::fory_write_data(self.as_slice(), context)
        } else {
            write_collection_data(self.iter(), context, false)
        }
    }

    #[inline(always)]
    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        if is_primitive_type::<T>() {
            primitive_list::fory_write_data(self.as_slice(), context)
        } else {
            write_collection_data(self.iter(), context, has_generics)
        }
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        let id = get_primitive_type_id::<T>();
        if id != TypeId::UNKNOWN {
            primitive_list::fory_write_type_info(context, id)
        } else {
            write_collection_type_info(context, TypeId::LIST as u32)
        }
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        if is_primitive_type::<T>() {
            // Read primitive array data directly without intermediate Vec allocation
            read_primitive_array(context)
        } else {
            // Read collection data directly into array without intermediate Vec
            read_complex_array(context)
        }
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        let id = get_primitive_type_id::<T>();
        if id != TypeId::UNKNOWN {
            primitive_list::fory_read_type_info(context, id)
        } else {
            read_collection_type_info(context, TypeId::LIST as u32)
        }
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        if is_primitive_type::<T>() {
            primitive_list::fory_reserved_space::<T>()
        } else {
            // size of the array length
            mem::size_of::<u32>()
        }
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        let id = get_primitive_type_id::<T>();
        if id != TypeId::UNKNOWN {
            id
        } else {
            TypeId::LIST
        }
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(Self::fory_static_type_id() as u32)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(Self::fory_static_type_id() as u32)
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T, const N: usize> ForyDefault for [T; N]
where
    T: ForyDefault,
{
    #[inline(always)]
    fn fory_default() -> Self {
        // Create an array by calling fory_default() for each element
        // We use MaybeUninit for safe initialization

        let mut arr: [MaybeUninit<T>; N] = unsafe { MaybeUninit::uninit().assume_init() };
        for elem in &mut arr {
            elem.write(T::fory_default());
        }

        // Safety: all elements are initialized
        unsafe {
            // Transmute from [MaybeUninit<T>; N] to [T; N]
            std::ptr::read(&arr as *const _ as *const [T; N])
        }
    }
}
