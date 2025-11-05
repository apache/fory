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
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::util::read_basic_type_info;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;

macro_rules! impl_unsigned_num_serializer {
    ($ty:ty, $writer:expr, $reader:expr, $field_type:expr) => {
        impl Serializer for $ty {
            #[inline(always)]
            fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
                if context.is_xlang() {
                    return Err(Error::not_allowed(
                        "Unsigned types are not supported in cross-language mode",
                    ));
                }
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
            fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
                Ok($field_type as u32)
            }

            #[inline(always)]
            fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
                Ok($field_type as u32)
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
                context.writer.write_varuint32($field_type as u32);
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

impl_unsigned_num_serializer!(u8, Writer::write_u8, Reader::read_u8, TypeId::U8);
impl_unsigned_num_serializer!(u16, Writer::write_u16, Reader::read_u16, TypeId::U16);
impl_unsigned_num_serializer!(u32, Writer::write_u32, Reader::read_u32, TypeId::U32);
impl_unsigned_num_serializer!(u64, Writer::write_u64, Reader::read_u64, TypeId::U64);
impl_unsigned_num_serializer!(
    usize,
    Writer::write_usize,
    Reader::read_usize,
    TypeId::USIZE
);
impl_unsigned_num_serializer!(u128, Writer::write_u128, Reader::read_u128, TypeId::U128);
