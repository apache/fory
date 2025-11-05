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

//! Serialization support for `RefCell<T>`.
//!
//! This module implements `Serializer` and `ForyDefault` for `std::cell::RefCell<T>`.
//! It allows mutable reference containers to be part of serialized graphs, which is often
//! necessary when modeling object structures with interior mutability (e.g. parent/child links).
//!
//! Unlike `Rc` and `Arc`, `RefCell` does not do reference counting, so this wrapper relies
//! on the serialization of the contained `T` only.
//!
//! This is commonly used together with `Rc<RefCell<T>>` in graph structures.
//!
//! # Example
//! ```rust
//! use std::cell::RefCell;
//! let cell = RefCell::new(42);
//! // Can be serialized by the Fory framework
//! ```
use crate::error::Error;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::cell::RefCell;
use std::rc::Rc;

/// `Serializer` impl for `RefCell<T>`
///
/// Simply delegates to the serializer for `T`, allowing interior mutable
/// containers to be included in serialized graphs.
impl<T: Serializer + ForyDefault> Serializer for RefCell<T> {
    #[inline(always)]
    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        Ok(RefCell::new(T::fory_read(
            context,
            read_ref_info,
            read_type_info,
        )?))
    }

    #[inline(always)]
    fn fory_read_with_type_info(
        context: &mut ReadContext,
        read_ref_info: bool,
        type_info: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        Ok(RefCell::new(T::fory_read_with_type_info(
            context,
            read_ref_info,
            type_info,
        )?))
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(RefCell::new(T::fory_read_data(context)?))
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    #[inline(always)]
    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_ref_info: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        // Don't add ref tracking for RefCell itself, just delegate to inner type
        // The inner type will handle its own ref tracking
        T::fory_write(
            &*self.borrow(),
            context,
            write_ref_info,
            write_type_info,
            has_generics,
        )
    }

    #[inline(always)]
    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        T::fory_write_data_generic(&*self.borrow(), context, has_generics)
    }

    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_data(&*self.borrow(), context)
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    #[inline(always)]
    fn fory_reserved_space(type_resolver: &TypeResolver) -> usize {
        // RefCell is transparent, delegate to inner type
        T::fory_reserved_space(type_resolver)
    }

    #[inline(always)]
    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        (*self.borrow()).fory_type_id_dyn(type_resolver)
    }

    #[inline(always)]
    fn fory_static_type_id(type_resolver: &TypeResolver) -> TypeId
    where
        Self: Sized,
    {
        T::fory_static_type_id(type_resolver)
    }

    #[inline(always)]
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

impl<T: ForyDefault> ForyDefault for RefCell<T> {
    fn fory_default() -> Self {
        RefCell::new(T::fory_default())
    }
}
