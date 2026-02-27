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
use crate::serializer::Serializer;
use crate::types::TypeId;

pub fn fory_write_data<T: Serializer>(this: &[T], context: &mut WriteContext) -> Result<(), Error> {
    // U128, USIZE, ISIZE, INT128 are Rust-specific and not supported in xlang mode
    if context.is_xlang() {
        match T::fory_static_type_id() {
            TypeId::U128 => {
                return Err(Error::not_allowed(
                    "u128 is not supported in cross-language mode",
                ));
            }
            TypeId::INT128 => {
                return Err(Error::not_allowed(
                    "i128 is not supported in cross-language mode",
                ));
            }
            TypeId::USIZE => {
                return Err(Error::not_allowed(
                    "usize is not supported in cross-language mode",
                ));
            }
            TypeId::ISIZE => {
                return Err(Error::not_allowed(
                    "isize is not supported in cross-language mode",
                ));
            }
            _ => {}
        }
    }
    let len_bytes = std::mem::size_of_val(this);
    context.writer.write_var_uint32(len_bytes as u32);

    if !this.is_empty() {
        #[cfg(target_endian = "little")]
        {
            // Fast path: direct memory copy on little-endian machines
            unsafe {
                let ptr = this.as_ptr() as *const u8;
                let slice = std::slice::from_raw_parts(ptr, len_bytes);
                context.writer.write_bytes(slice);
            }
        }
        #[cfg(target_endian = "big")]
        {
            // Slow path: element-by-element write on big-endian machines
            for item in this {
                item.write(context)?;
            }
        }
    }
    Ok(())
}

pub fn fory_write_type_info(context: &mut WriteContext, type_id: TypeId) -> Result<(), Error> {
    context.writer.write_u8(type_id as u8);
    Ok(())
}

pub fn fory_read_data<T: Serializer>(context: &mut ReadContext) -> Result<Vec<T>, Error> {
    let size_bytes = context.reader.read_varuint32()? as usize;
    if size_bytes % std::mem::size_of::<T>() != 0 {
        return Err(Error::invalid_data("Invalid data length"));
    }
    // Check that the declared byte length is actually available in the buffer
    // BEFORE allocating. Without this, a crafted size_bytes can trigger an OOM
    // allocation (Vec::with_capacity) even though read_bytes would later reject
    // the payload — but only after the damage is done.
    let remaining = context
        .reader
        .bf
        .len()
        .saturating_sub(context.reader.cursor);
    if size_bytes > remaining {
        return Err(Error::invalid_data(format!(
            "primitive list byte length {} exceeds buffer remaining {}",
            size_bytes, remaining
        )));
    }
    let len = size_bytes / std::mem::size_of::<T>();
    context.check_collection_size(len)?;
    let mut vec: Vec<T> = Vec::with_capacity(len);

    #[cfg(target_endian = "little")]
    {
        // Fast path: direct memory copy on little-endian machines
        unsafe {
            let dst_ptr = vec.as_mut_ptr() as *mut u8;
            let src = context.reader.read_bytes(size_bytes)?;
            std::ptr::copy_nonoverlapping(src.as_ptr(), dst_ptr, size_bytes);
            vec.set_len(len);
        }
    }
    #[cfg(target_endian = "big")]
    {
        // Slow path: element-by-element read on big-endian machines
        for _ in 0..len {
            vec.push(T::read(context)?);
        }
    }
    Ok(vec)
}

pub fn fory_read_type_info(context: &mut ReadContext, type_id: TypeId) -> Result<(), Error> {
    let remote_type_id = context.reader.read_u8()? as u32;
    if remote_type_id == TypeId::LIST as u32 {
        return Err(Error::type_error(
            "Vec<number> belongs to the `number_array` type, \
                and Vec<Option<number>> belongs to the `list` type. \
                You should not read data of type `list` as data of type `number_array`.",
        ));
    }
    let local_type_id = type_id as u32;
    ensure!(
        local_type_id == remote_type_id,
        Error::type_mismatch(local_type_id, remote_type_id)
    );
    Ok(())
}

pub fn fory_reserved_space<T>() -> usize {
    std::mem::size_of::<T>()
}
