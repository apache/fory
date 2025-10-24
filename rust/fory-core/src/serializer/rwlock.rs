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

//! Serialization support for `RwLock<T>`.
//!
//! This module implements [`Serializer`] and [`ForyDefault`] for [`std::sync::RwLock<T>`].
//! It allows thread-safe read-write lock containers to be part of serialized graphs.
//!
//! Unlike [`std::rc::Rc`] and [`std::sync::Arc`], `RwLock` does not do reference counting, so this wrapper relies
//! on the serialization of the contained `T` only.
//!
//! This is commonly used together with `Arc<RwLock<T>>` in multi-threaded applications
//! where reads are more frequent than writes.
//!
//! # Example
//! ```rust
//! use std::sync::RwLock;
//! use fory_core::{Serializer, ForyDefault};
//!
//! let rwlock = RwLock::new(42);
//! // Can be serialized by the Fory framework
//! ```
//!
//! # Caveats
//!
//! - Serialization locks the RwLock for reading while accessing the inner value.
//! - If a write lock is held during serialization, this will block until the write completes.
//! - You should serialize in a quiescent state with no concurrent writes for best performance.
//! - A poisoned RwLock (from a panicked holder) will cause `.read().unwrap()` to panic
//!   during serialization — it is assumed this is a programmer error.

use crate::error::Error;
use crate::meta::type_traits::TypeCharacteristics;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::rc::Rc;
use std::sync::RwLock;

/// `Serializer` impl for `RwLock<T>`
///
/// Simply delegates to the serializer for `T`, allowing thread-safe read-write lock
/// containers to be included in serialized graphs.
impl<T: Serializer + ForyDefault> Serializer for RwLock<T> {
    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_ref_data: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        // Don't add ref tracking for RwLock itself, just delegate to inner type
        // The inner type will handle its own ref tracking
        let guard = self.read().unwrap();
        T::fory_write(
            &*guard,
            context,
            write_ref_data,
            write_type_info,
            has_generics,
        )
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        T::fory_write_data_generic(&*self.read().unwrap(), context, has_generics)
    }

    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        // When called from Rc/Arc, just delegate to inner type's data serialization
        let guard = self.read().unwrap();
        T::fory_write_data(&*guard, context)
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    fn fory_reserved_space() -> usize {
        // RwLock is transparent, delegate to inner type
        T::fory_reserved_space()
    }

    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        Ok(RwLock::new(T::fory_read(
            context,
            read_ref_info,
            read_type_info,
        )?))
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        read_ref_info: bool,
        type_info: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        Ok(RwLock::new(T::fory_read_with_type_info(
            context,
            read_ref_info,
            type_info,
        )?))
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(RwLock::new(T::fory_read_data(context)?))
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        let guard = self.read().unwrap();
        (*guard).fory_type_id_dyn(type_resolver)
    }

    fn fory_static_type_id() -> TypeId {
        T::fory_static_type_id()
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T: ForyDefault> ForyDefault for RwLock<T> {
    fn fory_default() -> Self {
        RwLock::new(T::fory_default())
    }
}

// ============================================================================
// TypeCharacteristics Implementation for RwLock<T>
// ============================================================================

/// RwLock<T> inherits type characteristics from T.
///
/// Since RwLock is a transparent wrapper for thread-safe read-write locking,
/// it preserves the morphic/polymorphic nature of the contained type.
///
/// # Examples
///
/// ```rust,ignore
/// use std::sync::RwLock;
/// use fory_core::meta::type_traits::TypeCharacteristics;
///
/// // RwLock<Vec<i32>> is morphic because Vec<i32> is morphic
/// assert!(RwLock::<Vec<i32>>::is_morphic());
/// ```
impl<T: TypeCharacteristics> TypeCharacteristics for RwLock<T> {
    fn is_morphic() -> bool {
        T::is_morphic()
    }

    fn is_polymorphic() -> bool {
        T::is_polymorphic()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fory::Fory;

    #[test]
    fn test_rwlock_basic_serialization() {
        let fory = Fory::default();
        let rwlock = RwLock::new(42i32);

        let serialized = fory.serialize(&rwlock).unwrap();
        let deserialized: RwLock<i32> = fory.deserialize(&serialized).unwrap();

        assert_eq!(*deserialized.read().unwrap(), 42);
    }

    #[test]
    fn test_rwlock_string_serialization() {
        let fory = Fory::default();
        let rwlock = RwLock::new(String::from("Hello, RwLock!"));

        let serialized = fory.serialize(&rwlock).unwrap();
        let deserialized: RwLock<String> = fory.deserialize(&serialized).unwrap();

        assert_eq!(*deserialized.read().unwrap(), "Hello, RwLock!");
    }

    #[test]
    fn test_rwlock_vec_serialization() {
        let fory = Fory::default();
        let rwlock = RwLock::new(vec![1, 2, 3, 4, 5]);

        let serialized = fory.serialize(&rwlock).unwrap();
        let deserialized: RwLock<Vec<i32>> = fory.deserialize(&serialized).unwrap();

        assert_eq!(*deserialized.read().unwrap(), vec![1, 2, 3, 4, 5]);
    }

    #[test]
    fn test_rwlock_type_characteristics() {
        // RwLock<i32> should be morphic
        assert!(RwLock::<i32>::is_morphic());
        assert!(!RwLock::<i32>::is_polymorphic());

        // RwLock<String> should be morphic
        assert!(RwLock::<String>::is_morphic());
        assert!(!RwLock::<String>::is_polymorphic());

        // RwLock<Vec<i32>> should be morphic
        assert!(RwLock::<Vec<i32>>::is_morphic());
        assert!(!RwLock::<Vec<i32>>::is_polymorphic());
    }
}
