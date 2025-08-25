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
use crate::types::FieldId;
use std::mem;

pub fn to_u8_slice<T>(slice: &[T]) -> &[u8] {
    let byte_len = std::mem::size_of_val(slice);
    unsafe { std::slice::from_raw_parts(slice.as_ptr().cast::<u8>(), byte_len) }
}

macro_rules! impl_primitive_vec {
    ($name: ident, $ty:tt, $field_type: expr) => {
        impl Serializer for Vec<$ty> {
            fn write(&self, context: &mut WriteContext) {
                context.writer.var_int32(self.len() as i32);
                context.writer.reserve(self.len() * mem::size_of::<$ty>());
                context.writer.bytes(to_u8_slice(self));
            }

            fn read(context: &mut ReadContext) -> Result<Self, Error> {
                // length
                let len = (context.reader.var_int32() as usize);
                let is_aligned = context.reader.aligned::<$ty>();
                if is_aligned {
                    let slice = context.reader.bytes(len * mem::size_of::<$ty>());
                    Ok(
                        unsafe { std::slice::from_raw_parts(slice.as_ptr().cast::<$ty>(), len) }
                            .to_vec(),
                    )
                } else {
                    let mut result = Vec::with_capacity(len);
                    for _i in 0..len {
                        result.push(context.reader.$name());
                    }
                    Ok(result)
                }
            }

            fn reserved_space() -> usize {
                mem::size_of::<i32>()
            }

            fn get_type_id(_fory: &Fory) -> i16 {
                ($field_type).into()
            }
        }
    };
}

impl Serializer for Vec<bool> {
    fn write(&self, context: &mut WriteContext) {
        context.writer.var_int32(self.len() as i32);
        context.writer.bytes(to_u8_slice(self));
    }

    fn reserved_space() -> usize {
        mem::size_of::<u8>()
    }

    fn get_type_id(_fory: &Fory) -> i16 {
        FieldId::ForyPrimitiveBoolArray.into()
    }

    fn read(context: &mut ReadContext) -> Result<Self, Error> {
        let size = context.reader.var_int32();
        let bytes = context.reader.bytes(size as usize).to_vec();
        Ok(unsafe { mem::transmute::<Vec<u8>, Vec<bool>>(bytes) })
    }
}

impl_primitive_vec!(u8, u8, FieldId::BINARY);
impl_primitive_vec!(i16, i16, FieldId::ForyPrimitiveShortArray);
impl_primitive_vec!(i32, i32, FieldId::ForyPrimitiveIntArray);
impl_primitive_vec!(i64, i64, FieldId::ForyPrimitiveLongArray);
impl_primitive_vec!(f32, f32, FieldId::ForyPrimitiveFloatArray);
impl_primitive_vec!(f64, f64, FieldId::ForyPrimitiveDoubleArray);
