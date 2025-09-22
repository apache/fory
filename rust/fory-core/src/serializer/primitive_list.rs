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
use crate::fory::Fory;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::Serializer;
use crate::types::TypeId;

macro_rules! impl_primitive_vec {
    ($name:ident, $ty:ty, $field_type:expr) => {
        impl Serializer for Vec<$ty> {
            fn write(&self, context: &mut WriteContext, _is_field: bool) {
                let len_bytes = self.len() * std::mem::size_of::<$ty>();
                context.writer.var_uint32(len_bytes as u32);
                context.writer.reserve(len_bytes);

                if !self.is_empty() {
                    unsafe {
                        let ptr = self.as_ptr() as *const u8;
                        let slice = std::slice::from_raw_parts(ptr, len_bytes);
                        context.writer.bytes(slice);
                    }
                }
            }

            fn write_type_info(context: &mut WriteContext, is_field: bool) {
                if *context.get_fory().get_mode() == crate::types::Mode::Compatible && !is_field {
                    context.writer.var_uint32($field_type as u32);
                }
            }

            fn read(context: &mut ReadContext) -> Result<Self, Error> {
                let size_bytes = context.reader.var_uint32() as usize;
                if size_bytes % std::mem::size_of::<$ty>() != 0 {
                    panic!("Invalid data length");
                }
                let len = size_bytes / std::mem::size_of::<$ty>();
                let mut vec: Vec<$ty> = Vec::with_capacity(len);
                unsafe {
                    let dst_ptr = vec.as_mut_ptr() as *mut u8;
                    let src = context.reader.bytes(size_bytes);
                    std::ptr::copy_nonoverlapping(src.as_ptr(), dst_ptr, size_bytes);
                    vec.set_len(len);
                }
                Ok(vec)
            }

            fn read_type_info(context: &mut ReadContext, is_field: bool) {
                if *context.get_fory().get_mode() == crate::types::Mode::Compatible && !is_field {
                    let remote_type_id = context.reader.var_uint32();
                    assert_eq!(remote_type_id, $field_type as u32);
                }
            }

            fn reserved_space() -> usize {
                std::mem::size_of::<$ty>()
            }

            fn get_type_id(_fory: &Fory) -> u32 {
                $field_type as u32
            }
        }
    };
}

impl_primitive_vec!(bool, bool, TypeId::BOOL_ARRAY);
impl_primitive_vec!(u8, u8, TypeId::BINARY);
impl_primitive_vec!(i8, i8, TypeId::INT8_ARRAY);
impl_primitive_vec!(i16, i16, TypeId::INT16_ARRAY);
impl_primitive_vec!(i32, i32, TypeId::INT32_ARRAY);
impl_primitive_vec!(i64, i64, TypeId::INT64_ARRAY);
impl_primitive_vec!(f32, f32, TypeId::FLOAT32_ARRAY);
impl_primitive_vec!(f64, f64, TypeId::FLOAT64_ARRAY);
