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

//! Weak pointer serialization support for `Rc` and `Arc`.
//!
//! This module provides [`RcWeak<T>`] and [`ArcWeak<T>`] wrapper types that integrate
//! Rust's `std::rc::Weak` / `std::sync::Weak` into the Fory serialization framework,
//! with full support for:
//
//! - **Reference identity tracking** — weak pointers serialize as references to their
//!   corresponding strong pointers, ensuring shared and circular references in the graph
//!   are preserved without duplication.
//! - **Null weak pointers** — if the strong pointer has been dropped or was never set,
//!   the weak will serialize as a `Null` flag.
//! - **Forward references during deserialization** — if the strong pointer appears later
//!   in the serialized data, the weak will be resolved after deserialization using
//!   `RefReader` callbacks.
//!
//! ## When to use
//!
//! Use these wrappers when your graph structure contains parent/child relationships
//! or other shared edges where a strong pointer would cause a reference cycle.
//! Storing a weak pointer avoids owning the target strongly, but serialization
//! will preserve the link by reference ID.
//!
//! ## Example — Parent/Child Graph
//!
//! ```rust,ignore
//! use fory_core::RcWeak;
//! use fory_core::Fory;
//! use std::cell::RefCell;
//! use std::rc::Rc;
//! use fory_derive::ForyObject;
//!
//! #[derive(ForyObject)]
//! struct Node {
//!     value: i32,
//!     parent: RcWeak<RefCell<Node>>,
//!     children: Vec<Rc<RefCell<Node>>>,
//! }
//!
//! let mut fory = Fory::default();
//! fory.register::<Node>(2000);
//!
//! let parent = Rc::new(RefCell::new(Node {
//!     value: 1,
//!     parent: RcWeak::new(),
//!     children: vec![],
//! }));
//!
//! let child1 = Rc::new(RefCell::new(Node {
//!     value: 2,
//!     parent: RcWeak::from(&parent),
//!     children: vec![],
//! }));
//!
//! let child2 = Rc::new(RefCell::new(Node {
//!     value: 3,
//!     parent: RcWeak::from(&parent),
//!     children: vec![],
//! }));
//!
//! parent.borrow_mut().children.push(child1);
//! parent.borrow_mut().children.push(child2);
//!
//! let serialized = fory.serialize(&parent);
//! let deserialized: Rc<RefCell<Node>> = fory.deserialize(&serialized).unwrap();
//!
//! assert_eq!(deserialized.borrow().children.len(), 2);
//! for child in &deserialized.borrow().children {
//!     let upgraded_parent = child.borrow().parent.upgrade().unwrap();
//!     assert!(Rc::ptr_eq(&deserialized, &upgraded_parent));
//! }
//! ```
//!
//! ## Example — Arc for Multi-Threaded Graphs
//!
//! ```rust,ignore
//! use fory_core::ArcWeak;
//! use fory_core::Fory;
//! use std::sync::{Arc, Mutex};
//! use fory_derive::ForyObject;
//!
//! #[derive(ForyObject)]
//! struct Node {
//!     value: i32,
//!     parent: ArcWeak<Mutex<Node>>,
//! }
//!
//! let mut fory = Fory::default();
//! fory.register::<Node>(2001);
//!
//! let parent = Arc::new(Mutex::new(Node { value: 1, parent: ArcWeak::new() }));
//! let child = Arc::new(Mutex::new(Node { value: 2, parent: ArcWeak::from(&parent) }));
//!
//! let serialized = fory.serialize(&child);
//! let deserialized: Arc<Mutex<Node>> = fory.deserialize(&serialized).unwrap();
//! assert_eq!(deserialized.lock().unwrap().value, 2);
//! ```
//!
//! ## Notes
//!
//! - These types share the same `UnsafeCell` across clones, so updating a weak in one clone
//!   will update all of them.
//! - During serialization, weak pointers **never serialize the target object's data directly**
//!   — they only emit a reference to the already-serialized strong pointer, or `Null`.
//! - During deserialization, unresolved references will be patched up by `RefReader::add_callback`
//!   once the strong pointer becomes available.

use crate::error::Error;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::{ForyDefault, Serializer};
use crate::types::{RefFlag, RefMode, TypeId};
use std::cell::UnsafeCell;
use std::rc::Rc;
use std::sync::Arc;

/// A serializable wrapper around `std::rc::Weak<T>`.
///
/// `RcWeak<T>` is designed for use in graph-like structures where nodes may need to hold
/// non-owning references to other nodes (e.g., parent pointers), and you still want them
/// to round-trip through serialization while preserving reference identity.
///
/// Unlike a raw `Weak<T>`, cloning `RcWeak` keeps all clones pointing to the same
/// internal `UnsafeCell`, so updates via deserialization callbacks affect all copies.
///
/// # Example
/// See module-level docs for a complete graph example.
///
/// # Null handling
/// If the target `Rc<T>` has been dropped or never assigned, `upgrade()` returns `None`
/// and serialization will write a `RefFlag::Null` instead of a reference ID.
pub struct RcWeak<T: ?Sized> {
    // Use Rc<UnsafeCell> so that clones share the same cell
    inner: Rc<UnsafeCell<std::rc::Weak<T>>>,
}

impl<T: ?Sized> std::fmt::Debug for RcWeak<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RcWeak")
            .field("strong_count", &self.strong_count())
            .field("weak_count", &self.weak_count())
            .finish()
    }
}

impl<T> RcWeak<T> {
    pub fn new() -> Self {
        RcWeak {
            inner: Rc::new(UnsafeCell::new(std::rc::Weak::new())),
        }
    }
}

impl<T: ?Sized> RcWeak<T> {
    pub fn upgrade(&self) -> Option<Rc<T>> {
        unsafe { (*self.inner.get()).upgrade() }
    }

    pub fn strong_count(&self) -> usize {
        unsafe { (*self.inner.get()).strong_count() }
    }

    pub fn weak_count(&self) -> usize {
        unsafe { (*self.inner.get()).weak_count() }
    }

    pub fn ptr_eq(&self, other: &Self) -> bool {
        unsafe { std::rc::Weak::ptr_eq(&*self.inner.get(), &*other.inner.get()) }
    }

    pub fn update(&self, weak: std::rc::Weak<T>) {
        unsafe {
            *self.inner.get() = weak;
        }
    }

    pub fn from_std(weak: std::rc::Weak<T>) -> Self {
        RcWeak {
            inner: Rc::new(UnsafeCell::new(weak)),
        }
    }
}

impl<T: ?Sized> PartialEq for RcWeak<T> {
    fn eq(&self, other: &Self) -> bool {
        self.ptr_eq(other)
    }
}

impl<T: ?Sized> Eq for RcWeak<T> {}

impl<T> Default for RcWeak<T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<T: ?Sized> Clone for RcWeak<T> {
    fn clone(&self) -> Self {
        // Clone the Rc, not the inner Weak - this way clones share the same cell!
        RcWeak {
            inner: self.inner.clone(),
        }
    }
}

impl<T: ?Sized> From<&Rc<T>> for RcWeak<T> {
    fn from(rc: &Rc<T>) -> Self {
        RcWeak::from_std(Rc::downgrade(rc))
    }
}

unsafe impl<T: ?Sized> Send for RcWeak<T> where std::rc::Weak<T>: Send {}
unsafe impl<T: ?Sized> Sync for RcWeak<T> where std::rc::Weak<T>: Sync {}

/// A serializable wrapper around `std::sync::Weak<T>` (thread-safe).
///
/// `ArcWeak<T>` works exactly like [`RcWeak<T>`] but is intended for use with
/// multi-threaded shared graphs where strong pointers are `Arc<T>`.
///
/// All clones of an `ArcWeak<T>` share the same `UnsafeCell` so deserialization
/// updates propagate to all copies.
///
/// # Example
/// See module-level docs for an `Arc<Mutex<Node>>` usage example.
pub struct ArcWeak<T: ?Sized> {
    // Use Arc<UnsafeCell> so that clones share the same cell
    inner: Arc<UnsafeCell<std::sync::Weak<T>>>,
}

impl<T: ?Sized> std::fmt::Debug for ArcWeak<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ArcWeak")
            .field("strong_count", &self.strong_count())
            .field("weak_count", &self.weak_count())
            .finish()
    }
}

impl<T> ArcWeak<T> {
    pub fn new() -> Self {
        ArcWeak {
            inner: Arc::new(UnsafeCell::new(std::sync::Weak::new())),
        }
    }
}

impl<T: ?Sized> ArcWeak<T> {
    pub fn upgrade(&self) -> Option<Arc<T>> {
        unsafe { (*self.inner.get()).upgrade() }
    }

    pub fn strong_count(&self) -> usize {
        unsafe { (*self.inner.get()).strong_count() }
    }

    pub fn weak_count(&self) -> usize {
        unsafe { (*self.inner.get()).weak_count() }
    }

    pub fn ptr_eq(&self, other: &Self) -> bool {
        unsafe { std::sync::Weak::ptr_eq(&*self.inner.get(), &*other.inner.get()) }
    }

    pub fn update(&self, weak: std::sync::Weak<T>) {
        unsafe {
            *self.inner.get() = weak;
        }
    }

    pub fn from_std(weak: std::sync::Weak<T>) -> Self {
        ArcWeak {
            inner: Arc::new(UnsafeCell::new(weak)),
        }
    }
}

impl<T: ?Sized> PartialEq for ArcWeak<T> {
    fn eq(&self, other: &Self) -> bool {
        self.ptr_eq(other)
    }
}

impl<T: ?Sized> Eq for ArcWeak<T> {}

impl<T> Default for ArcWeak<T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<T: ?Sized> Clone for ArcWeak<T> {
    fn clone(&self) -> Self {
        // Clone the Arc, not the inner Weak - this way clones share the same cell!
        ArcWeak {
            inner: self.inner.clone(),
        }
    }
}

impl<T: ?Sized> From<&Arc<T>> for ArcWeak<T> {
    fn from(arc: &Arc<T>) -> Self {
        ArcWeak::from_std(Arc::downgrade(arc))
    }
}

unsafe impl<T: ?Sized + Send + Sync> Send for ArcWeak<T> {}
unsafe impl<T: ?Sized + Send + Sync> Sync for ArcWeak<T> {}

impl<T: Serializer + ForyDefault + 'static> Serializer for RcWeak<T> {
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
        // Weak pointers require track_ref to be enabled on the Fory instance
        if !context.is_track_ref() {
            return Err(Error::invalid_ref(
                "RcWeak requires track_ref to be enabled. Use Fory::default().track_ref(true)",
            ));
        }
        // Weak MUST use ref tracking - otherwise read value will be lost
        if ref_mode != RefMode::Tracking {
            return Err(Error::invalid_ref(
                "RcWeak requires RefMode::Tracking for serialization",
            ));
        }
        if let Some(rc) = self.upgrade() {
            if !context
                .ref_writer
                .try_write_rc_ref(&mut context.writer, &rc)
            {
                if write_type_info {
                    T::fory_write_type_info(context)?;
                }
                T::fory_write_data_generic(&*rc, context, has_generics)?;
            }
        } else {
            context.writer.write_i8(RefFlag::Null as i8);
        }
        Ok(())
    }

    fn fory_write_data_generic(&self, _: &mut WriteContext, _: bool) -> Result<(), Error> {
        Err(Error::not_allowed(
            "RcWeak<T> should be written using `fory_write` to handle reference tracking properly",
        ))
    }

    fn fory_write_data(&self, _: &mut WriteContext) -> Result<(), Error> {
        Err(Error::not_allowed(
            "RcWeak<T> should be written using `fory_write` to handle reference tracking properly",
        ))
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    fn fory_read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        // Weak MUST track refs
        debug_assert!(
            ref_mode == RefMode::Tracking,
            "RcWeak requires RefMode::Tracking"
        );
        read_rc_weak::<T>(context, read_type_info, None)
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        typeinfo: Rc<TypeInfo>,
    ) -> Result<Self, Error> {
        debug_assert!(
            ref_mode == RefMode::Tracking,
            "RcWeak requires RefMode::Tracking"
        );
        read_rc_weak::<T>(context, false, Some(typeinfo))
    }

    fn fory_read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Err(Error::not_allowed("RcWeak<T> should be written using `fory_read/fory_read_with_type_info` to handle reference tracking properly"))
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    fn fory_reserved_space() -> usize {
        // RcWeak is a shared ref, return a const to avoid infinite recursion
        4
    }

    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        if let Some(rc) = self.upgrade() {
            (*rc).fory_type_id_dyn(type_resolver)
        } else {
            T::fory_get_type_id(type_resolver)
        }
    }

    fn fory_static_type_id() -> TypeId {
        T::fory_static_type_id()
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

fn read_rc_weak<T: Serializer + ForyDefault + 'static>(
    context: &mut ReadContext,
    read_type_info: bool,
    type_info: Option<Rc<TypeInfo>>,
) -> Result<RcWeak<T>, Error> {
    // Always read ref flag - Weak requires tracking
    let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader)?;
    match ref_flag {
        RefFlag::Null => Ok(RcWeak::new()),
        RefFlag::RefValue => {
            context.inc_depth()?;
            let data = if let Some(type_info) = type_info {
                T::fory_read_with_type_info(context, RefMode::None, type_info)?
            } else {
                if read_type_info {
                    context.read_any_typeinfo()?;
                }
                T::fory_read_data(context)?
            };
            context.dec_depth();
            let rc = Rc::new(data);
            let ref_id = context.ref_reader.store_rc_ref(rc);
            let rc = context.ref_reader.get_rc_ref::<T>(ref_id).unwrap();
            Ok(RcWeak::from(&rc))
        }
        RefFlag::Ref => {
            let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
            if let Some(rc) = context.ref_reader.get_rc_ref::<T>(ref_id) {
                Ok(RcWeak::from(&rc))
            } else {
                let result_weak = RcWeak::new();
                let callback_weak = result_weak.clone();
                context.ref_reader.add_callback(Box::new(move |ref_reader| {
                    if let Some(rc) = ref_reader.get_rc_ref::<T>(ref_id) {
                        callback_weak.update(Rc::downgrade(&rc));
                    }
                }));

                Ok(result_weak)
            }
        }
        RefFlag::NotNullValue => Err(Error::invalid_ref("RcWeak can't hold a strong ref value")),
    }
}

impl<T: ForyDefault> ForyDefault for RcWeak<T> {
    fn fory_default() -> Self {
        RcWeak::new()
    }
}

impl<T: Serializer + ForyDefault + Send + Sync + 'static> Serializer for ArcWeak<T> {
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
        // Weak pointers require track_ref to be enabled on the Fory instance
        if !context.is_track_ref() {
            return Err(Error::invalid_ref(
                "ArcWeak requires track_ref to be enabled. Use Fory::default().track_ref(true)",
            ));
        }
        // Weak MUST use ref tracking - otherwise read value will be lost
        if ref_mode != RefMode::Tracking {
            return Err(Error::invalid_ref(
                "ArcWeak requires RefMode::Tracking for serialization",
            ));
        }
        if let Some(arc) = self.upgrade() {
            if !context
                .ref_writer
                .try_write_arc_ref(&mut context.writer, &arc)
            {
                if write_type_info {
                    T::fory_write_type_info(context)?;
                }
                T::fory_write_data_generic(&*arc, context, has_generics)?;
            }
        } else {
            context.writer.write_i8(RefFlag::Null as i8);
        }
        Ok(())
    }

    fn fory_write_data_generic(&self, _: &mut WriteContext, _: bool) -> Result<(), Error> {
        Err(Error::not_allowed(
            "ArcWeak<T> should be written using `fory_write` to handle reference tracking properly",
        ))
    }

    fn fory_write_data(&self, _: &mut WriteContext) -> Result<(), Error> {
        Err(Error::not_allowed(
            "ArcWeak<T> should be written using `fory_write` to handle reference tracking properly",
        ))
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    fn fory_read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        // Weak MUST track refs
        debug_assert!(
            ref_mode == RefMode::Tracking,
            "ArcWeak requires RefMode::Tracking"
        );
        read_arc_weak(context, read_type_info, None)
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        typeinfo: Rc<TypeInfo>,
    ) -> Result<Self, Error> {
        debug_assert!(
            ref_mode == RefMode::Tracking,
            "ArcWeak requires RefMode::Tracking"
        );
        read_arc_weak::<T>(context, false, Some(typeinfo))
    }

    fn fory_read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Err(Error::not_allowed("ArcWeak<T> should be written using `fory_read/fory_read_with_type_info` to handle reference tracking properly"))
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    fn fory_reserved_space() -> usize {
        // ArcWeak is a shared ref, return a const to avoid infinite recursion
        4
    }

    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        if let Some(arc) = self.upgrade() {
            (*arc).fory_type_id_dyn(type_resolver)
        } else {
            T::fory_get_type_id(type_resolver)
        }
    }

    fn fory_static_type_id() -> TypeId {
        T::fory_static_type_id()
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

fn read_arc_weak<T: Serializer + ForyDefault + 'static>(
    context: &mut ReadContext,
    read_type_info: bool,
    type_info: Option<Rc<TypeInfo>>,
) -> Result<ArcWeak<T>, Error> {
    // Always read ref flag - Weak requires tracking
    let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader)?;
    match ref_flag {
        RefFlag::Null => Ok(ArcWeak::new()),
        RefFlag::RefValue => {
            context.inc_depth()?;
            let data = if let Some(type_info) = type_info {
                T::fory_read_with_type_info(context, RefMode::None, type_info)?
            } else {
                if read_type_info {
                    context.read_any_typeinfo()?;
                }
                T::fory_read_data(context)?
            };
            context.dec_depth();
            let arc = Arc::new(data);
            let ref_id = context.ref_reader.store_arc_ref(arc);
            let arc = context.ref_reader.get_arc_ref::<T>(ref_id).unwrap();
            let weak = ArcWeak::from(&arc);
            Ok(weak)
        }
        RefFlag::Ref => {
            let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
            let weak = ArcWeak::new();

            if let Some(arc) = context.ref_reader.get_arc_ref::<T>(ref_id) {
                weak.update(Arc::downgrade(&arc));
            } else {
                // Capture the raw pointer to the UnsafeCell so we can update it in the callback
                let weak_ptr = weak.inner.get();
                context.ref_reader.add_callback(Box::new(move |ref_reader| {
                    if let Some(arc) = ref_reader.get_arc_ref::<T>(ref_id) {
                        unsafe {
                            *weak_ptr = Arc::downgrade(&arc);
                        }
                    }
                }));
            }
            Ok(weak)
        }
        RefFlag::NotNullValue => Err(Error::invalid_ref("ArcWeak can't hold a strong ref value")),
    }
}

impl<T: ForyDefault> ForyDefault for ArcWeak<T> {
    fn fory_default() -> Self {
        ArcWeak::new()
    }
}
