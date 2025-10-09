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
use crate::error::Error;
use crate::fory::Fory;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::{read_type_info, write_type_info, ForyDefault, Serializer};
use crate::types::TypeId;

macro_rules! impl_num_serializer {
    ($ty:ty, $writer:expr, $reader:expr, $field_type:expr) => {
        impl Serializer for $ty {
            fn fory_write_data(&self, context: &mut WriteContext, _is_field: bool) {
                $writer(&mut context.writer, *self);
            }

            fn fory_read_data(context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
                Ok($reader(&mut context.reader))
            }

            fn fory_reserved_space() -> usize {
                std::mem::size_of::<$ty>()
            }

            fn fory_get_type_id(_fory: &Fory) -> u32 {
                $field_type as u32
            }

            fn fory_type_id_dyn(&self, _fory: &Fory) -> u32 {
                $field_type as u32
            }

            fn as_any(&self) -> &dyn std::any::Any {
                self
            }

            fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
                write_type_info::<Self>(context, is_field);
            }

            fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
                read_type_info::<Self>(context, is_field);
            }
        }
        impl ForyDefault for $ty {
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
    TypeId::INT32
);
impl_num_serializer!(
    i64,
    Writer::write_varint64,
    Reader::read_varint64,
    TypeId::INT64
);
impl_num_serializer!(f32, Writer::write_f32, Reader::read_f32, TypeId::FLOAT32);
impl_num_serializer!(f64, Writer::write_f64, Reader::read_f64, TypeId::FLOAT64);
