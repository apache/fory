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
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::{RefFlag, RefMode, TypeId};
use std::rc::Rc;

impl<T: Serializer + ForyDefault> Serializer for Option<T> {
    #[inline(always)]
    fn fory_write(
        &self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        match ref_mode {
            RefMode::None => {
                // Write inner directly, no null check
                if let Some(v) = self {
                    T::fory_write(v, context, RefMode::None, write_type_info, has_generics)
                } else {
                    // None with RefMode::None is a protocol error
                    Err(Error::invalid_data("Option::None with RefMode::None"))
                }
            }
            RefMode::NullOnly => {
                if let Some(v) = self {
                    context.writer.write_i8(RefFlag::NotNullValue as i8);
                    T::fory_write(v, context, RefMode::None, write_type_info, has_generics)
                } else {
                    context.writer.write_i8(RefFlag::Null as i8);
                    Ok(())
                }
            }
            RefMode::Tracking => {
                // Only handle null here, pass Tracking to inner for ref handling
                if let Some(v) = self {
                    // DON'T write flag here - inner (e.g. Rc) handles RefValue/Ref flags
                    T::fory_write(v, context, RefMode::Tracking, write_type_info, has_generics)
                } else {
                    context.writer.write_i8(RefFlag::Null as i8);
                    Ok(())
                }
            }
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
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        match ref_mode {
            RefMode::None => {
                // Read inner directly, no null check
                Ok(Some(T::fory_read(context, RefMode::None, read_type_info)?))
            }
            RefMode::NullOnly => {
                let ref_flag = context.reader.read_i8()?;
                if ref_flag == RefFlag::Null as i8 {
                    return Ok(None);
                }
                // NotNullValue - read inner without ref handling
                Ok(Some(T::fory_read(context, RefMode::None, read_type_info)?))
            }
            RefMode::Tracking => {
                let ref_flag = context.reader.read_i8()?;
                if ref_flag == RefFlag::Null as i8 {
                    return Ok(None);
                }
                // Rewind to let inner type handle the ref flag (RefValue/Ref)
                context.reader.move_back(1);
                Ok(Some(T::fory_read(
                    context,
                    RefMode::Tracking,
                    read_type_info,
                )?))
            }
        }
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: Rc<crate::TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        match ref_mode {
            RefMode::None => {
                if T::fory_is_polymorphic() {
                    Ok(Some(T::fory_read_with_type_info(
                        context,
                        RefMode::None,
                        type_info,
                    )?))
                } else {
                    Ok(Some(T::fory_read_data(context)?))
                }
            }
            RefMode::NullOnly => {
                let ref_flag = context.reader.read_i8()?;
                if ref_flag == RefFlag::Null as i8 {
                    return Ok(None);
                }
                if T::fory_is_polymorphic() {
                    Ok(Some(T::fory_read_with_type_info(
                        context,
                        RefMode::None,
                        type_info,
                    )?))
                } else {
                    Ok(Some(T::fory_read_data(context)?))
                }
            }
            RefMode::Tracking => {
                let ref_flag = context.reader.read_i8()?;
                if ref_flag == RefFlag::Null as i8 {
                    return Ok(None);
                }
                // Rewind to let inner type handle the ref flag
                context.reader.move_back(1);
                Ok(Some(T::fory_read_with_type_info(
                    context,
                    RefMode::Tracking,
                    type_info,
                )?))
            }
        }
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        if T::fory_is_polymorphic() {
            Ok(Some(T::fory_read(context, RefMode::None, true)?))
        } else {
            Ok(Some(T::fory_read_data(context)?))
        }
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        std::mem::size_of::<T>()
    }

    #[inline(always)]
    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<TypeId, Error> {
        T::fory_get_type_id(type_resolver)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<TypeId, Error> {
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
    fn fory_static_type_id() -> TypeId {
        T::fory_static_type_id()
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
