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
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::{RefFlag, TypeId};
use std::rc::Rc;

impl<T: Serializer + ForyDefault> Serializer for Option<T> {
    #[inline(always)]
    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_ref_info: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if let Some(v) = self {
            // pass has_generics to nested collection/map serializers
            T::fory_write(v, context, write_ref_info, write_type_info, has_generics)
        } else {
            if write_ref_info {
                context.writer.write_u8(RefFlag::Null as u8);
            }
            // no value, skip write type info
            Ok(())
        }
    }

    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        if let Some(v) = self {
            T::fory_write_data(v, context)
        } else {
            unreachable!("write should be call by serialize")
        }
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        if read_ref_info {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                // null value won't write type info, so we can ignore `read_type_info`
                return Ok(None);
            }
            if T::fory_is_shared_ref() {
                // shared ref types always write ref flag, so we can ignore `read_type_info`
                context.reader.move_back(1); // rewind to re-read ref flag in nested read
                return Ok(Some(T::fory_read(context, true, read_type_info)?));
            }
        }
        Ok(Some(T::fory_read(context, false, read_type_info)?))
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        read_ref_info: bool,
        type_info: Rc<crate::TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        if read_ref_info {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Ok(None);
            }
        }
        if T::fory_is_polymorphic() {
            // Type info already resolved by caller
            Ok(Some(T::fory_read_with_type_info(
                context, false, type_info,
            )?))
        } else {
            Ok(Some(T::fory_read_data(context)?))
        }
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        if T::fory_is_polymorphic() {
            Ok(Some(T::fory_read(context, false, true)?))
        } else {
            Ok(Some(T::fory_read_data(context)?))
        }
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    #[inline(always)]
    fn fory_reserved_space(_: &TypeResolver) -> usize {
        std::mem::size_of::<T>()
    }

    #[inline(always)]
    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        match self {
            Some(val) => val.fory_type_id_dyn(type_resolver),
            None => T::fory_get_type_id(type_resolver),
        }
    }

    #[inline(always)]
    fn fory_is_option() -> bool {
        true
    }

    #[inline(always)]
    fn fory_is_none(&self) -> bool {
        self.is_none()
    }

    #[inline(always)]
    fn fory_static_type_id(type_resolver: &TypeResolver) -> TypeId {
        T::fory_static_type_id(type_resolver)
    }

    fn fory_is_wrapper_type() -> bool
    where
        Self: Sized,
    {
        true
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T: ForyDefault> ForyDefault for Option<T> {
    #[inline(always)]
    fn fory_default() -> Self {
        None
    }
}
