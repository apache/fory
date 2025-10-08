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

//! Serialization support for `Mutex<T>`.
//!
//! This module implements [`Serializer`] and [`ForyDefault`] for [`std::sync::Mutex<T>`].
//! It allows thread-safe mutable containers to be part of serialized graphs.
//!
//! Unlike [`Rc`] and [`Arc`], `Mutex` does not do reference counting, so this wrapper relies
//! on the serialization of the contained `T` only.
//!
//! This is commonly used together with `Arc<Mutex<T>>` in threaded graph structures.
//!
//! # Example
//! ```rust
//! use std::sync::Mutex;
//! use fory_core::serializer::{Serializer, ForyDefault};
//!
//! let mutex = Mutex::new(42);
//! // Can be serialized by the Fory framework
//! ```
//!
//! # Caveats
//!
//! - Serialization locks the mutex while reading/writing the inner value.
//! - If another thread holds the lock during serialization, this may block indefinitely.
//!   You should serialize in a quiescent state with no concurrent mutation.
//! - A poisoned mutex (from a panicked holder) will cause `.lock().unwrap()` to panic
//!   during serialization — it is assumed this is a programmer error.
use crate::error::Error;
use crate::fory::Fory;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::serializer::{ForyDefault, Serializer};
use std::sync::Mutex;

/// `Serializer` impl for `Mutex<T>`
///
/// Simply delegates to the serializer for `T`, allowing thread-safe interior mutable
/// containers to be included in serialized graphs.
impl<T: Serializer + ForyDefault> Serializer for Mutex<T> {
    fn fory_write(&self, context: &mut WriteContext, is_field: bool) {
        // Don't add ref tracking for Mutex itself, just delegate to inner type
        // The inner type will handle its own ref tracking
        let guard = self.lock().unwrap();
        T::fory_write(&*guard, context, is_field);
    }

    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
        // When called from Rc/Arc, just delegate to inner type's data serialization
        let guard = self.lock().unwrap();
        T::fory_write_data(&*guard, context, is_field);
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
        T::fory_write_type_info(context, is_field);
    }

    fn fory_reserved_space() -> usize {
        // Mutex is transparent, delegate to inner type
        T::fory_reserved_space()
    }

    fn fory_read(context: &mut ReadContext, is_field: bool) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        Ok(Mutex::new(T::fory_read(context, is_field)?))
    }

    fn fory_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        Ok(Mutex::new(T::fory_read_data(context, is_field)?))
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
        T::fory_read_type_info(context, is_field);
    }

    fn fory_get_type_id(fory: &Fory) -> u32 {
        T::fory_get_type_id(fory)
    }

    fn fory_type_id_dyn(&self, fory: &Fory) -> u32 {
        let guard = self.lock().unwrap();
        (*guard).fory_type_id_dyn(fory)
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T: ForyDefault> ForyDefault for Mutex<T> {
    fn fory_default() -> Self {
        Mutex::new(T::fory_default())
    }
}
