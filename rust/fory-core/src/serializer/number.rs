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

use crate::float16::float16;

use crate::buffer::{Reader, Writer};
use crate::error::Error;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::util::read_basic_type_info;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;

macro_rules! impl_num_serializer {
    ($ty:ty, $writer:expr, $reader:expr, $field_type:expr) => {
        impl Serializer for $ty {
            #[inline(always)]
            fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
                $writer(&mut context.writer, *self);
                Ok(())
            }

            #[inline(always)]
            fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
                $reader(&mut context.reader)
            }

            #[inline(always)]
            fn fory_reserved_space() -> usize {
                std::mem::size_of::<$ty>()
            }

            #[inline(always)]
            fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
                Ok($field_type)
            }

            #[inline(always)]
            fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
                Ok($field_type)
            }

            #[inline(always)]
            fn fory_static_type_id() -> TypeId {
                $field_type
            }

            #[inline(always)]
            fn as_any(&self) -> &dyn std::any::Any {
                self
            }

            #[inline(always)]
            fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                context.writer.write_var_uint32($field_type as u32);
                Ok(())
            }

            #[inline(always)]
            fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                read_basic_type_info::<Self>(context)
            }
        }
        impl ForyDefault for $ty {
            #[inline(always)]
            fn fory_default() -> Self {
                0 as $ty
            }
        }
    };
}

impl_num_serializer!(i8, Writer::write_i8, Reader::read_i8, TypeId::INT8);
impl_num_serializer!(i16, Writer::write_i16, Reader::read_i16, TypeId::INT16);
impl_num_serializer!(
    i32,
    Writer::write_varint32,
    Reader::read_varint32,
    TypeId::VARINT32
);
impl_num_serializer!(
    i64,
    Writer::write_varint64,
    Reader::read_varint64,
    TypeId::VARINT64
);
impl_num_serializer!(f32, Writer::write_f32, Reader::read_f32, TypeId::FLOAT32);
impl_num_serializer!(f64, Writer::write_f64, Reader::read_f64, TypeId::FLOAT64);

// Custom implementation for float16 (cannot use 0 as float16)
impl Serializer for float16 {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        Writer::write_f16(&mut context.writer, *self);
        Ok(())
    }
    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Reader::read_f16(&mut context.reader)
    }
    #[inline(always)]
    fn fory_reserved_space() -> usize {
        std::mem::size_of::<float16>()
    }
    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::FLOAT16)
    }
    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::FLOAT16)
    }
    #[inline(always)]
    fn fory_static_type_id() -> TypeId {
        TypeId::FLOAT16
    }
    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_var_uint32(TypeId::FLOAT16 as u32);
        Ok(())
    }
    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

impl ForyDefault for float16 {
    #[inline(always)]
    fn fory_default() -> Self {
        float16::ZERO
    }
}
impl_num_serializer!(i128, Writer::write_i128, Reader::read_i128, TypeId::INT128);
impl_num_serializer!(
    isize,
    Writer::write_isize,
    Reader::read_isize,
    TypeId::ISIZE
);
