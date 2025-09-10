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
use crate::types::{ForyGeneralList, TypeId};

macro_rules! impl_num_serializer {
    ($name: ident, $ty:tt, $field_type: expr) => {
        impl Serializer for $ty {
            fn write(&self, context: &mut WriteContext) {
                context.writer.$name(*self);
            }

            fn read(context: &mut ReadContext) -> Result<Self, Error> {
                Ok(context.reader.$name())
            }

            fn reserved_space() -> usize {
                std::mem::size_of::<$ty>()
            }

            fn get_type_id(_fory: &Fory) -> u32 {
                ($field_type) as u32
            }
        }
    };
}
impl ForyGeneralList for u16 {}
impl ForyGeneralList for u32 {}
impl ForyGeneralList for u64 {}

impl_num_serializer!(i8, i8, TypeId::INT8);
impl_num_serializer!(i16, i16, TypeId::INT16);
impl_num_serializer!(var_int32, i32, TypeId::INT32);
impl_num_serializer!(var_int64, i64, TypeId::INT64);
impl_num_serializer!(f32, f32, TypeId::FLOAT32);
impl_num_serializer!(f64, f64, TypeId::FLOAT64);
