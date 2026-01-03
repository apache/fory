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

use crate::buffer::{Reader, Writer};
use crate::error::Error;
use crate::types::RefFlag;
use std::any::Any;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;

/// Reference writer for tracking shared references during serialization.
///
/// RefWriter maintains a mapping from object pointer addresses to reference IDs,
/// allowing the serialization system to detect when the same object is encountered
/// multiple times and write a reference instead of serializing the object again.
/// This enables proper handling of shared references and circular references.
///
/// # Examples
///
/// ```rust
/// use fory_core::buffer::Writer;
/// use fory_core::resolver::ref_resolver::RefWriter;
/// use std::rc::Rc;
///
/// let mut ref_writer = RefWriter::new();
/// let mut buffer = vec![];
/// let mut writer = Writer::from_buffer(&mut buffer);
/// let rc = Rc::new(42);
///
/// // First encounter - returns false, indicating object should be serialized
/// assert!(!ref_writer.try_write_rc_ref(&mut writer, &rc));
///
/// // Second encounter - returns true, indicating reference was written
/// let rc2 = rc.clone();
/// assert!(ref_writer.try_write_rc_ref(&mut writer, &rc2));
/// ```
#[derive(Default)]
pub struct RefWriter {
    /// Maps pointer addresses to reference IDs
    refs: HashMap<usize, u32>,
    /// Next reference ID to assign
    next_ref_id: u32,
}

type UpdateCallback = Box<dyn FnOnce(&RefReader)>;

impl RefWriter {
    /// Creates a new RefWriter instance.
    pub fn new() -> Self {
        Self::default()
    }

    /// Attempt to write a reference for an `Rc<T>`.
    ///
    /// Returns true if a reference was written (indicating this object has been
    /// seen before), false if this is the first occurrence and the object should
    /// be serialized normally.
    ///
    /// # Arguments
    ///
    /// * `writer` - The writer to write reference information to
    /// * `rc` - The Rc to check for reference tracking
    ///
    /// # Returns
    ///
    /// * `true` if a reference was written
    /// * `false` if this is the first occurrence of the object
    #[inline]
    pub fn try_write_rc_ref<T: ?Sized>(&mut self, writer: &mut Writer, rc: &Rc<T>) -> bool {
        let ptr_addr = Rc::as_ptr(rc) as *const () as usize;

        if let Some(&ref_id) = self.refs.get(&ptr_addr) {
            writer.write_i8(RefFlag::Ref as i8);
            writer.write_varuint32(ref_id);
            true
        } else {
            let ref_id = self.next_ref_id;
            self.next_ref_id += 1;
            self.refs.insert(ptr_addr, ref_id);
            writer.write_i8(RefFlag::RefValue as i8);
            false
        }
    }

    /// Attempt to write a reference for an `Arc<T>`.
    ///
    /// Returns true if a reference was written (indicating this object has been
    /// seen before), false if this is the first occurrence and the object should
    /// be serialized normally.
    ///
    /// # Arguments
    ///
    /// * `writer` - The writer to write reference information to
    /// * `arc` - The Arc to check for reference tracking
    ///
    /// # Returns
    ///
    /// * `true` if a reference was written
    /// * `false` if this is the first occurrence of the object
    #[inline]
    pub fn try_write_arc_ref<T: ?Sized>(&mut self, writer: &mut Writer, arc: &Arc<T>) -> bool {
        let ptr_addr = Arc::as_ptr(arc) as *const () as usize;

        if let Some(&ref_id) = self.refs.get(&ptr_addr) {
            // This object has been seen before, write a reference
            writer.write_i8(RefFlag::Ref as i8);
            writer.write_varuint32(ref_id);
            true
        } else {
            // First time seeing this object, register it and return false
            let ref_id = self.next_ref_id;
            self.next_ref_id += 1;
            self.refs.insert(ptr_addr, ref_id);
            writer.write_i8(RefFlag::RefValue as i8);
            false
        }
    }

    /// Reserve a reference ID slot without storing anything.
    ///
    /// This is used for xlang compatibility where ALL objects (including struct values,
    /// not just Rc/Arc) participate in reference tracking.
    ///
    /// # Returns
    ///
    /// The reserved reference ID
    #[inline(always)]
    pub fn reserve_ref_id(&mut self) -> u32 {
        let ref_id = self.next_ref_id;
        self.next_ref_id += 1;
        ref_id
    }

    /// Clear all stored references.
    ///
    /// This is useful for reusing the RefWriter for multiple serialization operations.
    #[inline(always)]
    pub fn reset(&mut self) {
        self.refs.clear();
        self.next_ref_id = 0;
    }
}

/// Reference reader for resolving shared references during deserialization.
///
/// RefReader maintains a vector of previously deserialized objects that can be
/// referenced by ID. When a reference is encountered during deserialization,
/// the RefReader can return the previously deserialized object instead of
/// deserializing it again.
///
/// # Examples
///
/// ```rust
/// use fory_core::resolver::ref_resolver::RefReader;
/// use std::rc::Rc;
///
/// let mut ref_reader = RefReader::new();
/// let rc = Rc::new(42);
///
/// // Store an object for later reference
/// let ref_id = ref_reader.store_rc_ref(rc.clone());
///
/// // Retrieve the object by reference ID
/// let retrieved = ref_reader.get_rc_ref::<i32>(ref_id).unwrap();
/// assert!(Rc::ptr_eq(&rc, &retrieved));
/// ```
#[derive(Default)]
pub struct RefReader {
    /// Vector to store boxed objects for reference resolution
    refs: Vec<Box<dyn Any>>,
    /// Callbacks to execute when references are resolved
    callbacks: Vec<UpdateCallback>,
}

// danger but useful for multi-thread
unsafe impl Send for RefReader {}
unsafe impl Sync for RefReader {}

impl RefReader {
    /// Creates a new RefReader instance.
    pub fn new() -> Self {
        Self::default()
    }

    /// Reserve a reference ID slot without storing anything yet.
    ///
    /// Returns the reserved reference ID that will be used when storing the object later.
    #[inline(always)]
    pub fn reserve_ref_id(&mut self) -> u32 {
        let ref_id = self.refs.len() as u32;
        self.refs.push(Box::new(()));
        ref_id
    }

    /// Store an `Rc<T>` at a previously reserved reference ID.
    ///
    /// # Arguments
    ///
    /// * `ref_id` - The reference ID that was reserved
    /// * `rc` - The Rc to store
    #[inline(always)]
    pub fn store_rc_ref_at<T: 'static + ?Sized>(&mut self, ref_id: u32, rc: Rc<T>) {
        self.refs[ref_id as usize] = Box::new(rc);
    }

    /// Store an `Rc<T>` for later reference resolution during deserialization.
    ///
    /// # Arguments
    ///
    /// * `rc` - The Rc to store for later reference
    ///
    /// # Returns
    ///
    /// The reference ID that can be used to retrieve this object later
    #[inline(always)]
    pub fn store_rc_ref<T: 'static + ?Sized>(&mut self, rc: Rc<T>) -> u32 {
        let ref_id = self.refs.len() as u32;
        self.refs.push(Box::new(rc));
        ref_id
    }

    /// Store an `Arc<T>` at a previously reserved reference ID.
    ///
    /// # Arguments
    ///
    /// * `ref_id` - The reference ID that was reserved
    /// * `arc` - The Arc to store
    pub fn store_arc_ref_at<T: 'static + ?Sized>(&mut self, ref_id: u32, arc: Arc<T>) {
        self.refs[ref_id as usize] = Box::new(arc);
    }

    /// Store an `Arc<T>` for later reference resolution during deserialization.
    ///
    /// # Arguments
    ///
    /// * `arc` - The Arc to store for later reference
    ///
    /// # Returns
    ///
    /// The reference ID that can be used to retrieve this object later
    #[inline(always)]
    pub fn store_arc_ref<T: 'static + ?Sized>(&mut self, arc: Arc<T>) -> u32 {
        let ref_id = self.refs.len() as u32;
        self.refs.push(Box::new(arc));
        ref_id
    }

    /// Get an `Rc<T>` by reference ID during deserialization.
    ///
    /// # Arguments
    ///
    /// * `ref_id` - The reference ID returned by `store_rc_ref`
    ///
    /// # Returns
    ///
    /// * `Some(Rc<T>)` if the reference ID is valid and the type matches
    /// * `None` if the reference ID is invalid or the type doesn't match
    #[inline(always)]
    pub fn get_rc_ref<T: 'static + ?Sized>(&self, ref_id: u32) -> Option<Rc<T>> {
        let any_box = self.refs.get(ref_id as usize)?;
        any_box.downcast_ref::<Rc<T>>().cloned()
    }

    /// Get an `Arc<T>` by reference ID during deserialization.
    ///
    /// # Arguments
    ///
    /// * `ref_id` - The reference ID returned by `store_arc_ref`
    ///
    /// # Returns
    ///
    /// * `Some(Arc<T>)` if the reference ID is valid and the type matches
    /// * `None` if the reference ID is invalid or the type doesn't match
    #[inline(always)]
    pub fn get_arc_ref<T: 'static + ?Sized>(&self, ref_id: u32) -> Option<Arc<T>> {
        let any_box = self.refs.get(ref_id as usize)?;
        any_box.downcast_ref::<Arc<T>>().cloned()
    }

    /// Add a callback to be executed when weak references are resolved.
    ///
    /// # Arguments
    ///
    /// * `callback` - A closure that takes a reference to the RefReader
    #[inline(always)]
    pub fn add_callback(&mut self, callback: UpdateCallback) {
        self.callbacks.push(callback);
    }

    /// Read a reference flag and determine what action to take.
    ///
    /// # Arguments
    ///
    /// * `reader` - The reader to read the reference flag from
    ///
    /// # Returns
    ///
    /// The RefFlag indicating what type of reference this is
    ///
    /// # Errors
    ///
    /// Errors if an invalid reference flag value is encountered
    #[inline(always)]
    pub fn read_ref_flag(&self, reader: &mut Reader) -> Result<RefFlag, Error> {
        let flag_value = reader.read_i8()?;
        Ok(match flag_value {
            -3 => RefFlag::Null,
            -2 => RefFlag::Ref,
            -1 => RefFlag::NotNullValue,
            0 => RefFlag::RefValue,
            _ => Err(Error::invalid_ref(format!(
                "Invalid reference flag: {}",
                flag_value
            )))?,
        })
    }

    /// Read a reference ID from the reader.
    ///
    /// # Arguments
    ///
    /// * `reader` - The reader to read the reference ID from
    ///
    /// # Returns
    ///
    /// The reference ID as a u32
    #[inline(always)]
    pub fn read_ref_id(&self, reader: &mut Reader) -> Result<u32, Error> {
        reader.read_varuint32()
    }

    /// Execute all pending callbacks to resolve weak pointer references.
    ///
    /// This should be called after deserialization completes to update any weak pointers
    /// that referenced objects which were not yet available during deserialization.
    #[inline(always)]
    pub fn resolve_callbacks(&mut self) {
        let callbacks = std::mem::take(&mut self.callbacks);
        for callback in callbacks {
            callback(self);
        }
    }

    /// Clear all stored references and callbacks.
    ///
    /// This is useful for reusing the RefReader for multiple deserialization operations.
    #[inline(always)]
    pub fn reset(&mut self) {
        self.resolve_callbacks();
        self.refs.clear();
        self.callbacks.clear();
    }
}
