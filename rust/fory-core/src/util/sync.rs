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

use std::cell::UnsafeCell;
use std::ops::{Deref, DerefMut};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

pub struct Spinlock<T> {
    data: UnsafeCell<T>,
    flag: AtomicBool,
}

unsafe impl<T: Send> Send for Spinlock<T> {}
unsafe impl<T: Sync> Sync for Spinlock<T> {}

impl<T> Spinlock<T> {
    pub fn new(data: T) -> Self {
        Spinlock {
            data: UnsafeCell::new(data),
            flag: AtomicBool::new(false),
        }
    }

    pub fn lock(&self) -> SpinlockGuard<'_, T> {
        let mut spins = 0;
        while self
            .flag
            .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
            .is_err()
        {
            // Spin for a few iterations
            if spins < 10 {
                std::hint::spin_loop();
                spins += 1;
            } else {
                // Then yield to the scheduler
                thread::yield_now();
                spins = 0; // reset spin counter
            }
        }
        SpinlockGuard { lock: self }
    }

    fn unlock(&self) {
        self.flag.store(false, Ordering::Release);
    }
}

#[allow(clippy::needless_lifetimes)]
pub struct SpinlockGuard<'a, T> {
    lock: &'a Spinlock<T>,
}

#[allow(clippy::needless_lifetimes)]
impl<'a, T> Drop for SpinlockGuard<'a, T> {
    fn drop(&mut self) {
        self.lock.unlock();
    }
}

#[allow(clippy::needless_lifetimes)]
impl<'a, T> Deref for SpinlockGuard<'a, T> {
    type Target = T;
    fn deref(&self) -> &Self::Target {
        unsafe { &*self.lock.data.get() }
    }
}

#[allow(clippy::needless_lifetimes)]
impl<'a, T> DerefMut for SpinlockGuard<'a, T> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        unsafe { &mut *self.lock.data.get() }
    }
}
