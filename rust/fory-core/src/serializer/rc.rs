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
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::{ForyDefault, Serializer};
use crate::types::{RefFlag, RefMode, TypeId};
use std::rc::Rc;

impl<T: Serializer + ForyDefault + 'static> Serializer for Rc<T> {
    fn fory_is_shared_ref() -> bool {
        true
    }

    fn fory_write(
        &self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        match ref_mode {
            RefMode::None => {
                // No ref flag - write inner directly
                if write_type_info {
                    T::fory_write_type_info(context)?;
                }
                T::fory_write_data_generic(self, context, has_generics)
            }
            RefMode::NullOnly => {
                // Only null check, no ref tracking
                context.writer.write_i8(RefFlag::NotNullValue as i8);
                if write_type_info {
                    T::fory_write_type_info(context)?;
                }
                T::fory_write_data_generic(self, context, has_generics)
            }
            RefMode::Tracking => {
                // Full ref tracking with RefWriter
                if context
                    .ref_writer
                    .try_write_rc_ref(&mut context.writer, self)
                {
                    // Already written as ref - done
                    return Ok(());
                }
                // First occurrence - write type info and data
                if write_type_info {
                    T::fory_write_type_info(context)?;
                }
                T::fory_write_data_generic(self, context, has_generics)
            }
        }
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        if T::fory_is_shared_ref() {
            return Err(Error::not_allowed(
                "Rc<T> where T is a shared ref type is not allowed for serialization.",
            ));
        }
        T::fory_write_data_generic(&**self, context, has_generics)
    }

    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        self.fory_write_data_generic(context, false)
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    fn fory_read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_rc(context, ref_mode, read_type_info, None)
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        typeinfo: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_rc(context, ref_mode, false, Some(typeinfo))
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        if T::fory_is_shared_ref() {
            return Err(Error::not_allowed(
                "Rc<T> where T is a shared ref type is not allowed for deserialization.",
            ));
        }
        let inner = T::fory_read_data(context)?;
        Ok(Rc::new(inner))
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    fn fory_reserved_space() -> usize {
        // Rc is a shared ref, so we just need space for the ref tracking
        // We don't recursively compute inner type's space to avoid infinite recursion
        4
    }

    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        (**self).fory_type_id_dyn(type_resolver)
    }

    fn fory_static_type_id() -> TypeId {
        T::fory_static_type_id()
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

fn read_rc<T: Serializer + ForyDefault + 'static>(
    context: &mut ReadContext,
    ref_mode: RefMode,
    read_type_info: bool,
    typeinfo: Option<Rc<TypeInfo>>,
) -> Result<Rc<T>, Error> {
    match ref_mode {
        RefMode::None => {
            // No ref flag - read inner directly
            let inner = read_rc_inner::<T>(context, read_type_info, typeinfo)?;
            Ok(Rc::new(inner))
        }
        RefMode::NullOnly => {
            // Read NotNullValue flag (Null not allowed for Rc)
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Err(Error::invalid_ref("Rc cannot be null"));
            }
            let inner = read_rc_inner::<T>(context, read_type_info, typeinfo)?;
            Ok(Rc::new(inner))
        }
        RefMode::Tracking => {
            // Full ref tracking
            let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader)?;
            match ref_flag {
                RefFlag::Null => Err(Error::invalid_ref("Rc cannot be null")),
                RefFlag::Ref => {
                    let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
                    context.ref_reader.get_rc_ref::<T>(ref_id).ok_or_else(|| {
                        Error::invalid_ref(format!("Rc reference {ref_id} not found"))
                    })
                }
                RefFlag::NotNullValue => {
                    let inner = read_rc_inner::<T>(context, read_type_info, typeinfo)?;
                    Ok(Rc::new(inner))
                }
                RefFlag::RefValue => {
                    let ref_id = context.ref_reader.reserve_ref_id();
                    let inner = read_rc_inner::<T>(context, read_type_info, typeinfo)?;
                    let rc = Rc::new(inner);
                    context.ref_reader.store_rc_ref_at(ref_id, rc.clone());
                    Ok(rc)
                }
            }
        }
    }
}

fn read_rc_inner<T: Serializer + ForyDefault + 'static>(
    context: &mut ReadContext,
    read_type_info: bool,
    typeinfo: Option<Rc<TypeInfo>>,
) -> Result<T, Error> {
    // Read type info if needed, then read data directly
    // No recursive ref handling needed since Rc<T> only wraps allowed types
    if let Some(typeinfo) = typeinfo {
        return T::fory_read_with_type_info(context, RefMode::None, typeinfo);
    }
    if read_type_info {
        T::fory_read_type_info(context)?;
    }
    T::fory_read_data(context)
}

impl<T: ForyDefault> ForyDefault for Rc<T> {
    fn fory_default() -> Self {
        Rc::new(T::fory_default())
    }
}
