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

use super::codec::Codec;
use super::Serializer;
use crate::context::WriteContext;
use crate::error::Error;
use crate::resolver::{RefFlag, RefMode, TypeInfo};
use std::rc::Rc;

// Pointer owners borrow their pointee when writing and materialize the existing
// serializer target when reading. For str and [T], that target is String or Vec<T>;
// their backing storage is already accounted for by the body reader. Sized
// pointees keep the same direct serializer calls and shallow owner reservation.
pub(super) trait PointeeSerializer<T: ?Sized>: Serializer {
    const POINTEE_SIZE: usize;

    fn write_pointee_data(value: &T, context: &mut WriteContext) -> Result<(), Error>;

    #[inline(always)]
    fn write_pointee(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        if ref_mode != RefMode::None {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        if write_type_info {
            Self::write_type_info(context)?;
        }
        Self::write_pointee_data(value, context)
    }

    #[inline(always)]
    fn write_pointee_with_type_info(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        _type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        Self::write_pointee(value, context, ref_mode, false)
    }

    #[inline(always)]
    fn pointee_is_none(_: &T) -> bool {
        false
    }

    #[inline(always)]
    fn pointee_dynamic_type_id(_: &T) -> Result<Option<std::any::TypeId>, Error> {
        Ok(None)
    }
}

pub(super) trait PointeeCodec<T: ?Sized, V: 'static>:
    PointeeSerializer<T> + Codec<V>
{
    fn write_pointee_with_mode(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error>;

    #[inline(always)]
    fn write_pointee_field_with_type_info(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        _type_info: &Rc<TypeInfo>,
        has_generics: bool,
    ) -> Result<(), Error> {
        Self::write_pointee_with_mode(value, context, ref_mode, false, has_generics)
    }
}

impl<T: 'static, S: Serializer<Target = T>> PointeeSerializer<T> for S {
    const POINTEE_SIZE: usize = std::mem::size_of::<T>();

    #[inline(always)]
    fn write_pointee_data(value: &T, context: &mut WriteContext) -> Result<(), Error> {
        S::write_data(value, context)
    }

    #[inline(always)]
    fn write_pointee(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        S::write(value, context, ref_mode, write_type_info)
    }

    #[inline(always)]
    fn write_pointee_with_type_info(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        S::write_with_type_info(value, context, ref_mode, type_info)
    }

    #[inline(always)]
    fn pointee_is_none(value: &T) -> bool {
        S::is_none(value)
    }

    #[inline(always)]
    fn pointee_dynamic_type_id(value: &T) -> Result<Option<std::any::TypeId>, Error> {
        S::dynamic_type_id(value)
    }
}

impl<T: 'static, C: Codec<T>> PointeeCodec<T, T> for C {
    #[inline(always)]
    fn write_pointee_with_mode(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        C::write_with_mode(value, context, ref_mode, write_type_info, has_generics)
    }

    #[inline(always)]
    fn write_pointee_field_with_type_info(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
        has_generics: bool,
    ) -> Result<(), Error> {
        <C as Codec<T>>::write_with_type_info(value, context, ref_mode, type_info, has_generics)
    }
}
