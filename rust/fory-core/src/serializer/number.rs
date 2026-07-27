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

use crate::buffer::{Reader, Writer};
use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::serializer::util::read_basic_type_info;
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use crate::types::bfloat16::bfloat16;
use crate::types::float16::float16;
use std::sync::Arc;

macro_rules! impl_num_serializer {
    (
        $ty:ty,
        $writer:expr,
        $reader:expr,
        $type_id:expr,
        $default:expr
    ) => {
        impl Serializer for $ty {
            type Target = Self;

            #[inline(always)]
            fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
                $writer(&mut context.writer, *value);
                Ok(())
            }

            #[inline(always)]
            fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
                $reader(&mut context.reader)
            }

            #[inline(always)]
            fn default_value(_: &mut ReadContext) -> Result<Self, Error> {
                Ok($default)
            }

            #[inline(always)]
            fn read_arc_any(
                context: &mut ReadContext,
            ) -> Result<Arc<dyn std::any::Any + Send + Sync>, Error> {
                Ok(Arc::new(Self::read_data(context)?))
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                std::mem::size_of::<Self>()
            }

            #[inline(always)]
            fn static_type_id() -> TypeId {
                $type_id
            }

            #[inline(always)]
            fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                context.writer.write_var_u32($type_id as u32);
                Ok(())
            }

            #[inline(always)]
            fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                read_basic_type_info::<Self>(context)
            }
        }
    };
}

impl_num_serializer!(i8, Writer::write_i8, Reader::read_i8, TypeId::INT8, 0);
impl_num_serializer!(i16, Writer::write_i16, Reader::read_i16, TypeId::INT16, 0);
impl_num_serializer!(
    i32,
    Writer::write_var_i32,
    Reader::read_var_i32,
    TypeId::VARINT32,
    0
);
impl_num_serializer!(
    i64,
    Writer::write_var_i64,
    Reader::read_var_i64,
    TypeId::VARINT64,
    0
);
impl_num_serializer!(
    f32,
    Writer::write_f32,
    Reader::read_f32,
    TypeId::FLOAT32,
    0.0
);
impl_num_serializer!(
    f64,
    Writer::write_f64,
    Reader::read_f64,
    TypeId::FLOAT64,
    0.0
);
impl_num_serializer!(
    float16,
    Writer::write_f16,
    Reader::read_f16,
    TypeId::FLOAT16,
    float16::ZERO
);
impl_num_serializer!(
    bfloat16,
    Writer::write_bf16,
    Reader::read_bf16,
    TypeId::BFLOAT16,
    bfloat16::ZERO
);
impl_num_serializer!(
    i128,
    Writer::write_i128,
    Reader::read_i128,
    TypeId::INT128,
    0
);
impl_num_serializer!(
    isize,
    Writer::write_isize,
    Reader::read_isize,
    TypeId::ISIZE,
    0
);
