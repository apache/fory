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

//! Reference tracking infrastructure for circular reference detection.
//!
//! This module provides the [`RefTracker`] type and related utilities for:
//! - **Detecting circular references** during serialization
//! - **Managing reference graphs** across Rc/Arc/Weak pointers
//! - **Future optimization**: Reference deduplication and cycle breaking
//!
//! ## Design Goals
//!
//! 1. **Cycle Detection**: Prevent infinite loops when serializing circular graphs
//! 2. **Reference Identity**: Maintain object identity across serialization boundaries
//! 3. **Performance**: Minimal overhead for acyclic graphs
//! 4. **Extensibility**: Support future optimizations like reference counting analysis
//!
//! ## Current Status
//!
//! **Phase 4 (Week 11-12)**: Basic infrastructure reserved
//! - Type definitions and interfaces
//! - Placeholder implementations
//! - Documentation and examples
//!
//! **Future Phases**: Full implementation
//! - Cycle detection algorithm
//! - Reference graph traversal
//! - Memory optimization strategies
//!
//! ## Usage Example (Future)
//!
//! ```rust,ignore
//! use fory_core::resolver::ref_tracker::RefTracker;
//!
//! let mut tracker = RefTracker::new();
//!
//! // During serialization, track visited references
//! if tracker.mark_visiting(&obj_ptr) {
//!     // First visit: serialize normally
//!     serialize_object(&obj);
//!     tracker.mark_visited(&obj_ptr);
//! } else if tracker.is_visiting(&obj_ptr) {
//!     // Cycle detected: write reference ID only
//!     write_ref_id(tracker.get_ref_id(&obj_ptr));
//! } else {
//!     // Already serialized: write reference ID
//!     write_ref_id(tracker.get_ref_id(&obj_ptr));
//! }
//! ```
//!
//! ## Integration with RefWriter/RefReader
//!
//! `RefTracker` is designed to complement the existing `RefWriter` and `RefReader`:
//!
//! - **RefWriter/RefReader**: Handle reference serialization/deserialization
//! - **RefTracker**: Detect cycles and manage traversal state
//!
//! Future integration will allow `RefWriter` to consult `RefTracker` for cycle detection
//! before writing reference data.

use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::ptr;

/// Reference tracker for circular reference detection and graph management.
///
/// This type maintains the state of reference traversal during serialization,
/// allowing detection of cycles and prevention of infinite loops.
///
/// # Current Implementation
///
/// **Phase 4**: Placeholder with basic structure
/// - Type definitions
/// - Interface methods (not yet implemented)
/// - Documentation
///
/// # Future Implementation
///
/// Will include:
/// - Cycle detection via DFS traversal state
/// - Reference ID assignment
/// - Graph topology analysis
#[derive(Debug)]
pub struct RefTracker {
    /// Map from object pointer to assigned reference ID.
    ///
    /// This tracks which objects have been encountered and assigns them
    /// unique IDs for cross-referencing.
    ref_ids: HashMap<usize, u32>,

    /// Set of objects currently being visited (on the DFS stack).
    ///
    /// If we encounter a pointer in this set, we've detected a cycle.
    visiting: HashSet<usize>,

    /// Set of objects that have been fully serialized.
    ///
    /// These can be referenced by ID without re-serialization.
    visited: HashSet<usize>,

    /// Next reference ID to assign.
    next_ref_id: u32,
}

impl RefTracker {
    /// Create a new reference tracker.
    ///
    /// # Example
    ///
    /// ```rust,ignore
    /// let tracker = RefTracker::new();
    /// ```
    pub fn new() -> Self {
        RefTracker {
            ref_ids: HashMap::new(),
            visiting: HashSet::new(),
            visited: HashSet::new(),
            next_ref_id: 0,
        }
    }

    /// Mark an object as currently being visited.
    ///
    /// Returns `true` if this is the first time visiting this object,
    /// `false` if it's already being visited (cycle detected) or already visited.
    ///
    /// # Parameters
    ///
    /// - `ptr`: Pointer to the object (cast to usize)
    ///
    /// # Returns
    ///
    /// - `true`: First visit, proceed with serialization
    /// - `false`: Cycle or already serialized, write reference ID only
    ///
    /// # Example
    ///
    /// ```rust,ignore
    /// let obj_ptr = &obj as *const _ as usize;
    /// if tracker.mark_visiting(obj_ptr) {
    ///     // Serialize the object
    /// } else {
    ///     // Write reference ID
    /// }
    /// ```
    pub fn mark_visiting(&mut self, ptr: usize) -> bool {
        if self.visited.contains(&ptr) || self.visiting.contains(&ptr) {
            false
        } else {
            self.visiting.insert(ptr);
            if !self.ref_ids.contains_key(&ptr) {
                self.ref_ids.insert(ptr, self.next_ref_id);
                self.next_ref_id += 1;
            }
            true
        }
    }

    /// Mark an object as fully visited/serialized.
    ///
    /// This should be called after successfully serializing an object.
    /// It moves the object from the "visiting" set to the "visited" set.
    ///
    /// # Parameters
    ///
    /// - `ptr`: Pointer to the object (cast to usize)
    ///
    /// # Example
    ///
    /// ```rust,ignore
    /// tracker.mark_visiting(obj_ptr);
    /// serialize_object(&obj)?;
    /// tracker.mark_visited(obj_ptr);
    /// ```
    pub fn mark_visited(&mut self, ptr: usize) {
        self.visiting.remove(&ptr);
        self.visited.insert(ptr);
    }

    /// Check if an object is currently being visited.
    ///
    /// If `true`, a cycle has been detected.
    ///
    /// # Parameters
    ///
    /// - `ptr`: Pointer to the object (cast to usize)
    ///
    /// # Returns
    ///
    /// `true` if the object is on the current DFS stack (cycle detected).
    pub fn is_visiting(&self, ptr: usize) -> bool {
        self.visiting.contains(&ptr)
    }

    /// Check if an object has been fully visited.
    ///
    /// # Parameters
    ///
    /// - `ptr`: Pointer to the object (cast to usize)
    ///
    /// # Returns
    ///
    /// `true` if the object has been fully serialized.
    pub fn is_visited(&self, ptr: usize) -> bool {
        self.visited.contains(&ptr)
    }

    /// Get the reference ID for an object.
    ///
    /// Returns `None` if the object hasn't been assigned an ID yet.
    ///
    /// # Parameters
    ///
    /// - `ptr`: Pointer to the object (cast to usize)
    ///
    /// # Returns
    ///
    /// The assigned reference ID, or `None`.
    pub fn get_ref_id(&self, ptr: usize) -> Option<u32> {
        self.ref_ids.get(&ptr).copied()
    }

    /// Reset the tracker state.
    ///
    /// Clears all tracking information, ready for a new serialization session.
    pub fn reset(&mut self) {
        self.ref_ids.clear();
        self.visiting.clear();
        self.visited.clear();
        self.next_ref_id = 0;
    }

    /// Get statistics about the reference graph.
    ///
    /// Useful for debugging and performance analysis.
    ///
    /// # Returns
    ///
    /// A tuple of (total_refs, visiting_count, visited_count).
    pub fn stats(&self) -> (usize, usize, usize) {
        (
            self.ref_ids.len(),
            self.visiting.len(),
            self.visited.len(),
        )
    }
}

impl Default for RefTracker {
    fn default() -> Self {
        Self::new()
    }
}

/// Helper function to convert a reference to a tracking pointer.
///
/// This is a safe wrapper around pointer-to-usize conversion for use
/// with RefTracker.
///
/// # Safety
///
/// The returned `usize` is only valid for the lifetime of the reference.
/// Do not use it after the reference is dropped.
///
/// # Example
///
/// ```rust,ignore
/// let obj = MyStruct { ... };
/// let ptr = ref_to_ptr(&obj);
/// tracker.mark_visiting(ptr);
/// ```
#[inline]
pub fn ref_to_ptr<T: ?Sized>(r: &T) -> usize {
    r as *const T as *const () as usize
}

/// Helper function to check if two references point to the same object.
///
/// This is equivalent to `std::ptr::eq` but works with any reference type.
///
/// # Example
///
/// ```rust,ignore
/// let obj1 = MyStruct { ... };
/// let obj2 = MyStruct { ... };
/// assert!(!same_ref(&obj1, &obj2));
/// assert!(same_ref(&obj1, &obj1));
/// ```
#[inline]
pub fn same_ref<T: ?Sized>(a: &T, b: &T) -> bool {
    ptr::eq(a as *const T, b as *const T)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ref_tracker_basic() {
        let mut tracker = RefTracker::new();

        let obj1_ptr = 0x1000;
        let obj2_ptr = 0x2000;

        // First visit to obj1
        assert!(tracker.mark_visiting(obj1_ptr));
        assert!(tracker.is_visiting(obj1_ptr));
        assert!(!tracker.is_visited(obj1_ptr));

        // Mark obj1 as visited
        tracker.mark_visited(obj1_ptr);
        assert!(!tracker.is_visiting(obj1_ptr));
        assert!(tracker.is_visited(obj1_ptr));

        // Visit obj2
        assert!(tracker.mark_visiting(obj2_ptr));
        assert!(tracker.is_visiting(obj2_ptr));
    }

    #[test]
    fn test_cycle_detection() {
        let mut tracker = RefTracker::new();

        let obj_ptr = 0x1000;

        // First visit
        assert!(tracker.mark_visiting(obj_ptr));

        // Try to visit again (cycle detected)
        assert!(!tracker.mark_visiting(obj_ptr));
        assert!(tracker.is_visiting(obj_ptr));
    }

    #[test]
    fn test_ref_id_assignment() {
        let mut tracker = RefTracker::new();

        let obj1_ptr = 0x1000;
        let obj2_ptr = 0x2000;

        tracker.mark_visiting(obj1_ptr);
        tracker.mark_visiting(obj2_ptr);

        assert_eq!(tracker.get_ref_id(obj1_ptr), Some(0));
        assert_eq!(tracker.get_ref_id(obj2_ptr), Some(1));
    }

    #[test]
    fn test_reset() {
        let mut tracker = RefTracker::new();

        tracker.mark_visiting(0x1000);
        tracker.mark_visited(0x1000);

        assert_eq!(tracker.stats(), (1, 0, 1));

        tracker.reset();
        assert_eq!(tracker.stats(), (0, 0, 0));
    }

    #[test]
    fn test_ref_to_ptr() {
        let obj = 42i32;
        let ptr1 = ref_to_ptr(&obj);
        let ptr2 = ref_to_ptr(&obj);

        // Same object should have same pointer
        assert_eq!(ptr1, ptr2);
    }

    #[test]
    fn test_same_ref() {
        let obj1 = 42i32;
        let obj2 = 42i32;

        assert!(same_ref(&obj1, &obj1));
        assert!(!same_ref(&obj1, &obj2));
    }
}
