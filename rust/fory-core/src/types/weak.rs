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

//! Weak pointer runtime carriers for `Rc` and `Arc`.
//!
//! This module provides [`RcWeak<T>`] and [`ArcWeak<T>`] wrapper types that integrate
//! Rust's `std::rc::Weak` / `std::sync::Weak` into the Fory type system.

use std::cell::UnsafeCell;
use std::rc::Rc;
use std::sync::Arc;

/// A serializable runtime wrapper around `std::rc::Weak<T>`.
///
/// `RcWeak<T>` is designed for graph-like structures where nodes may need to hold
/// non-owning references to other nodes, and you still want them to round-trip
/// through serialization while preserving reference identity.
pub struct RcWeak<T: ?Sized> {
    // Use Rc<UnsafeCell> so that clones share the same cell.
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

/// A serializable runtime wrapper around `std::sync::Weak<T>`.
///
/// `ArcWeak<T>` works like [`RcWeak<T>`] but is intended for multi-threaded shared
/// graphs where strong pointers are `Arc<T>`.
pub struct ArcWeak<T: ?Sized> {
    // Use Arc<UnsafeCell> so that clones share the same cell.
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
