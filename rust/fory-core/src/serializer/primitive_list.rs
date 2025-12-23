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
    if context.is_xlang()
        && matches!(
            T::fory_static_type_id(),
            TypeId::U16 | TypeId::U32 | TypeId::U64 | TypeId::U128
        )
    {
        return Err(Error::not_allowed(
            "Unsigned types are not supported in cross-language mode",
        ));
    }
    let len_bytes = std::mem::size_of_val(this);
    context.writer.write_varuint32(len_bytes as u32);

    if !this.is_empty() {
        unsafe {
            let ptr = this.as_ptr() as *const u8;
            let slice = std::slice::from_raw_parts(ptr, len_bytes);
            context.writer.write_bytes(slice);
        }
    }
    Ok(())
}

pub fn fory_write_type_info(context: &mut WriteContext, type_id: TypeId) -> Result<(), Error> {
    context.writer.write_varuint32(type_id as u32);
    Ok(())
}

pub fn fory_read_data<T>(context: &mut ReadContext) -> Result<Vec<T>, Error> {
    let size_bytes = context.reader.read_varuint32()? as usize;
    if size_bytes % std::mem::size_of::<T>() != 0 {
        return Err(Error::invalid_data("Invalid data length"));
    }
    let len = size_bytes / std::mem::size_of::<T>();
    let mut vec: Vec<T> = Vec::with_capacity(len);
    unsafe {
        let dst_ptr = vec.as_mut_ptr() as *mut u8;
        let src = context.reader.read_bytes(size_bytes)?;
        std::ptr::copy_nonoverlapping(src.as_ptr(), dst_ptr, size_bytes);
        vec.set_len(len);
    }
    Ok(vec)
}

pub fn fory_read_type_info(context: &mut ReadContext, type_id: TypeId) -> Result<(), Error> {
    let remote_type_id = context.reader.read_varuint32()?;
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
