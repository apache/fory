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
/// let mut writer = Writer::default();
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

impl RefWriter {
    /// Creates a new RefWriter instance.
    pub fn new() -> Self {
        Self::default()
    }

    /// Attempt to write a reference for an Rc<T>.
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
    pub fn try_write_rc_ref<T>(&mut self, writer: &mut Writer, rc: &Rc<T>) -> bool {
        let ptr_addr = Rc::as_ptr(rc) as usize;

        if let Some(&ref_id) = self.refs.get(&ptr_addr) {
            // This object has been seen before, write a reference
            writer.i8(RefFlag::Ref as i8);
            writer.u32(ref_id);
            true
        } else {
            // First time seeing this object, register it and return false
            let ref_id = self.next_ref_id;
            self.next_ref_id += 1;
            self.refs.insert(ptr_addr, ref_id);
            writer.i8(RefFlag::RefValue as i8);
            false
        }
    }

    /// Attempt to write a reference for an Arc<T>.
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
    pub fn try_write_arc_ref<T>(&mut self, writer: &mut Writer, arc: &Arc<T>) -> bool {
        let ptr_addr = Arc::as_ptr(arc) as usize;

        if let Some(&ref_id) = self.refs.get(&ptr_addr) {
            // This object has been seen before, write a reference
            writer.i8(RefFlag::Ref as i8);
            writer.u32(ref_id);
            true
        } else {
            // First time seeing this object, register it and return false
            let ref_id = self.next_ref_id;
            self.next_ref_id += 1;
            self.refs.insert(ptr_addr, ref_id);
            writer.i8(RefFlag::RefValue as i8);
            false
        }
    }

    /// Clear all stored references.
    ///
    /// This is useful for reusing the RefWriter for multiple serialization operations.
    pub fn clear(&mut self) {
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
}

impl RefReader {
    /// Creates a new RefReader instance.
    pub fn new() -> Self {
        Self::default()
    }

    /// Store an Rc<T> for later reference resolution during deserialization.
    ///
    /// # Arguments
    ///
    /// * `rc` - The Rc to store for later reference
    ///
    /// # Returns
    ///
    /// The reference ID that can be used to retrieve this object later
    pub fn store_rc_ref<T: 'static>(&mut self, rc: Rc<T>) -> u32 {
        let ref_id = self.refs.len() as u32;
        self.refs.push(Box::new(rc));
        ref_id
    }

    /// Store an Arc<T> for later reference resolution during deserialization.
    ///
    /// # Arguments
    ///
    /// * `arc` - The Arc to store for later reference
    ///
    /// # Returns
    ///
    /// The reference ID that can be used to retrieve this object later
    pub fn store_arc_ref<T: 'static>(&mut self, arc: Arc<T>) -> u32 {
        let ref_id = self.refs.len() as u32;
        self.refs.push(Box::new(arc));
        ref_id
    }

    /// Get an Rc<T> by reference ID during deserialization.
    ///
    /// # Arguments
    ///
    /// * `ref_id` - The reference ID returned by `store_rc_ref`
    ///
    /// # Returns
    ///
    /// * `Some(Rc<T>)` if the reference ID is valid and the type matches
    /// * `None` if the reference ID is invalid or the type doesn't match
    pub fn get_rc_ref<T: 'static>(&self, ref_id: u32) -> Option<Rc<T>> {
        let any_box = self.refs.get(ref_id as usize)?;
        any_box.downcast_ref::<Rc<T>>().cloned()
    }

    /// Get an Arc<T> by reference ID during deserialization.
    ///
    /// # Arguments
    ///
    /// * `ref_id` - The reference ID returned by `store_arc_ref`
    ///
    /// # Returns
    ///
    /// * `Some(Arc<T>)` if the reference ID is valid and the type matches
    /// * `None` if the reference ID is invalid or the type doesn't match
    pub fn get_arc_ref<T: 'static>(&self, ref_id: u32) -> Option<Arc<T>> {
        let any_box = self.refs.get(ref_id as usize)?;
        any_box.downcast_ref::<Arc<T>>().cloned()
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
    /// # Panics
    ///
    /// Panics if an invalid reference flag value is encountered
    pub fn read_ref_flag(&self, reader: &mut Reader) -> RefFlag {
        let flag_value = reader.i8();
        match flag_value {
            -3 => RefFlag::Null,
            -2 => RefFlag::Ref,
            -1 => RefFlag::NotNullValue,
            0 => RefFlag::RefValue,
            _ => panic!("Invalid reference flag: {}", flag_value),
        }
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
    pub fn read_ref_id(&self, reader: &mut Reader) -> u32 {
        reader.u32()
    }

    /// Clear all stored references.
    ///
    /// This is useful for reusing the RefReader for multiple deserialization operations.
    pub fn clear(&mut self) {
        self.refs.clear();
    }
}

/// Legacy RefResolver that combines both reading and writing functionality.
///
/// This type is maintained for backward compatibility but it's recommended
/// to use RefWriter and RefReader separately for better separation of concerns.
///
/// # Deprecated
///
/// Use RefWriter for serialization and RefReader for deserialization instead.
#[deprecated(note = "Use RefWriter for serialization and RefReader for deserialization")]
#[derive(Default)]
pub struct RefResolver {
    /// Maps pointer addresses to reference IDs for serialization
    write_refs: HashMap<usize, u32>,
    /// Vector to store boxed objects for deserialization
    read_refs: Vec<Box<dyn Any>>,
    /// Next reference ID to assign
    next_ref_id: u32,
}

#[allow(deprecated)]
impl RefResolver {
    pub fn new() -> Self {
        Self::default()
    }

    /// Attempt to write a reference for an Rc<T>. Returns true if reference was written,
    /// false if this is the first occurrence and should be serialized normally.
    pub fn try_write_rc_ref<T>(&mut self, writer: &mut Writer, rc: &Rc<T>) -> bool {
        let ptr_addr = Rc::as_ptr(rc) as usize;

        if let Some(&ref_id) = self.write_refs.get(&ptr_addr) {
            // This object has been seen before, write a reference
            writer.i8(RefFlag::Ref as i8);
            writer.u32(ref_id);
            true
        } else {
            // First time seeing this object, register it and return false
            let ref_id = self.next_ref_id;
            self.next_ref_id += 1;
            self.write_refs.insert(ptr_addr, ref_id);
            writer.i8(RefFlag::RefValue as i8);
            false
        }
    }

    /// Attempt to write a reference for an Arc<T>. Returns true if reference was written,
    /// false if this is the first occurrence and should be serialized normally.
    pub fn try_write_arc_ref<T>(&mut self, writer: &mut Writer, arc: &Arc<T>) -> bool {
        let ptr_addr = Arc::as_ptr(arc) as usize;

        if let Some(&ref_id) = self.write_refs.get(&ptr_addr) {
            // This object has been seen before, write a reference
            writer.i8(RefFlag::Ref as i8);
            writer.u32(ref_id);
            true
        } else {
            // First time seeing this object, register it and return false
            let ref_id = self.next_ref_id;
            self.next_ref_id += 1;
            self.write_refs.insert(ptr_addr, ref_id);
            writer.i8(RefFlag::RefValue as i8);
            false
        }
    }

    /// Store an Rc<T> for later reference resolution during deserialization
    pub fn store_rc_ref<T: 'static>(&mut self, rc: Rc<T>) -> u32 {
        let ref_id = self.read_refs.len() as u32;
        self.read_refs.push(Box::new(rc));
        ref_id
    }

    /// Store an Arc<T> for later reference resolution during deserialization
    pub fn store_arc_ref<T: 'static>(&mut self, arc: Arc<T>) -> u32 {
        let ref_id = self.read_refs.len() as u32;
        self.read_refs.push(Box::new(arc));
        ref_id
    }

    /// Get an Rc<T> by reference ID during deserialization
    pub fn get_rc_ref<T: 'static>(&self, ref_id: u32) -> Option<Rc<T>> {
        let any_box = self.read_refs.get(ref_id as usize)?;
        any_box.downcast_ref::<Rc<T>>().cloned()
    }

    /// Get an Arc<T> by reference ID during deserialization
    pub fn get_arc_ref<T: 'static>(&self, ref_id: u32) -> Option<Arc<T>> {
        let any_box = self.read_refs.get(ref_id as usize)?;
        any_box.downcast_ref::<Arc<T>>().cloned()
    }

    /// Read a reference flag and determine what action to take
    pub fn read_ref_flag(&self, reader: &mut Reader) -> RefFlag {
        let flag_value = reader.i8();
        match flag_value {
            -3 => RefFlag::Null,
            -2 => RefFlag::Ref,
            -1 => RefFlag::NotNullValue,
            0 => RefFlag::RefValue,
            _ => panic!("Invalid reference flag: {}", flag_value),
        }
    }

    /// Read a reference ID
    pub fn read_ref_id(&self, reader: &mut Reader) -> u32 {
        reader.u32()
    }

    /// Clear all stored references (useful for reusing the resolver)
    pub fn clear(&mut self) {
        self.write_refs.clear();
        self.read_refs.clear();
        self.next_ref_id = 0;
    }
}
