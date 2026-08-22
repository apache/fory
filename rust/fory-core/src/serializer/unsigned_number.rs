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
use std::sync::Arc;

macro_rules! impl_unsigned_serializer {
    (
        $ty:ty,
        $writer:expr,
        $reader:expr,
        $type_id:expr,
        $xlang:expr
    ) => {
        impl Serializer for $ty {
            type Target = Self;

            const READ_DATA_ALWAYS_ADVANCES: bool = true;

            #[inline(always)]
            fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
                if !$xlang && context.is_xlang() {
                    return Err(Error::not_allowed(concat!(
                        stringify!($ty),
                        " is not supported in cross-language mode"
                    )));
                }
                $writer(&mut context.writer, *value);
                Ok(())
            }

            #[inline(always)]
            fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
                $reader(&mut context.reader)
            }

            #[inline(always)]
            fn default_value(_: &mut ReadContext) -> Result<Self, Error> {
                Ok(0)
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

impl_unsigned_serializer!(u8, Writer::write_u8, Reader::read_u8, TypeId::UINT8, true);
impl_unsigned_serializer!(
    u16,
    Writer::write_u16,
    Reader::read_u16,
    TypeId::UINT16,
    true
);
impl_unsigned_serializer!(
    u32,
    Writer::write_var_u32,
    Reader::read_var_u32,
    TypeId::VAR_UINT32,
    true
);
impl_unsigned_serializer!(
    u64,
    Writer::write_var_u64,
    Reader::read_var_u64,
    TypeId::VAR_UINT64,
    true
);
impl_unsigned_serializer!(
    u128,
    Writer::write_u128,
    Reader::read_u128,
    TypeId::U128,
    false
);
impl_unsigned_serializer!(
    usize,
    Writer::write_usize,
    Reader::read_usize,
    TypeId::USIZE,
    false
);
