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

//! Serialization context tracking for context-aware serialization.
//!
//! This module provides the [`SerializationContext`] enum which tracks the current
//! serialization context to enable intelligent metadata handling, particularly for
//! Vec and HashMap elements.
//!
//! ## Design Rationale
//!
//! According to the Fory specification:
//!
//! ### Vec Elements Header (4 bits)
//! - **Morphic (single-type) collections**:
//!   - Bit 0 (REF_FLAG_BIT): Set if elements need reference tracking
//!   - Bit 1 (HAS_TYPE_INFO_BIT): Set if type info should be written
//!   - Bit 2 (HAS_GENERICS_BIT): Set if elements have generic parameters
//!   - Bit 3: Reserved
//!
//! - **Polymorphic (multi-type) collections**:
//!   - Type info is written per-element, no header optimization
//!
//! ### HashMap KV Header (1 byte)
//! - Similar to Vec, but with separate 4-bit headers for key and value
//!
//! ## Context-Aware Optimization
//!
//! The serialization context enables:
//!
//! 1. **Top-Level Objects** (`TopLevel`):
//!    - Always write full type information
//!    - Always track references
//!    - No optimization assumptions
//!
//! 2. **Vec Elements** (`VecElement`):
//!    - For morphic types: use 4-bit header, skip per-element type info
//!    - For polymorphic types: write type info per element
//!    - Reference tracking based on element type's `fory_is_shared_ref()`
//!
//! 3. **HashMap Keys** (`HashMapKey`):
//!    - Use 4-bit header optimization for morphic types
//!    - Keys are typically non-polymorphic (String, i32, etc.)
//!
//! 4. **HashMap Values** (`HashMapValue`):
//!    - Use 4-bit header optimization for morphic types
//!    - Handle polymorphic values with per-element type info
//!
//! 5. **Struct Fields** (`StructField`):
//!    - Type info determined by field definition
//!    - Reference tracking based on field type
//!
//! ## Example Usage
//!
//! ```rust,ignore
//! use fory_core::resolver::context::WriteContext;
//! use fory_core::resolver::serialization_context::SerializationContext;
//!
//! fn serialize_vec<T: Serializer>(
//!     vec: &Vec<T>,
//!     context: &mut WriteContext,
//! ) -> Result<(), Error> {
//!     // Write Vec header with element type characteristics
//!     let is_morphic = !T::fory_is_polymorphic();
//!     if is_morphic {
//!         let header = compute_vec_element_header::<T>();
//!         context.writer.write_u8(header);
//!     }
//!
//!     // Serialize elements with context awareness
//!     context.push_context(SerializationContext::VecElement);
//!     for elem in vec {
//!         elem.fory_write(context, should_write_ref, should_write_type, has_generics)?;
//!     }
//!     context.pop_context();
//!     Ok(())
//! }
//! ```
//!
//! ## Cross-Language Compatibility
//!
//! This context tracking is essential for Java/Rust cross-language serialization:
//!
//! - **Java List/Map** ↔ **Rust Vec/HashMap**: Header optimization reduces payload size
//! - **Java Generic Types** ↔ **Rust Generic Types**: Context determines when to write type params
//! - **Java Circular References** ↔ **Rust Rc/Arc**: Context ensures correct reference tracking

/// Represents the current serialization context for context-aware metadata handling.
///
/// The context is maintained as a stack in `WriteContext`, allowing nested structures
/// to make intelligent decisions about what metadata to emit.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SerializationContext {
    /// Top-level object serialization (entry point).
    ///
    /// Characteristics:
    /// - Always write full type information
    /// - Always enable reference tracking
    /// - No optimizations applied
    TopLevel,

    /// Serializing an element within a Vec/Array.
    ///
    /// Characteristics:
    /// - For morphic types: skip per-element type info (use Vec header)
    /// - For polymorphic types: write type info per element
    /// - Reference tracking based on element type
    VecElement,

    /// Serializing a key within a HashMap/Map.
    ///
    /// Characteristics:
    /// - Use 4-bit header optimization for morphic types
    /// - Keys are typically non-polymorphic primitives or strings
    /// - Reference tracking based on key type (rare for keys)
    HashMapKey,

    /// Serializing a value within a HashMap/Map.
    ///
    /// Characteristics:
    /// - Use 4-bit header optimization for morphic types
    /// - Handle polymorphic values with per-element type info
    /// - Reference tracking based on value type
    HashMapValue,

    /// Serializing a field within a struct/object.
    ///
    /// Characteristics:
    /// - Type info determined by field definition (struct metadata)
    /// - Reference tracking based on field type
    /// - Field metadata written once in struct header
    StructField,
}

impl SerializationContext {
    /// Returns whether this context typically requires per-element type information.
    ///
    /// This is a hint for serializers to determine if type info should be written
    /// for each element, or if it can be optimized away via a header.
    #[inline]
    pub fn requires_per_element_type_info(&self) -> bool {
        match self {
            SerializationContext::TopLevel => true,
            SerializationContext::VecElement => false, // Morphic types use header
            SerializationContext::HashMapKey => false, // Morphic types use header
            SerializationContext::HashMapValue => false, // Morphic types use header
            SerializationContext::StructField => false, // Struct metadata defines it
        }
    }

    /// Returns whether this context supports header-based optimization.
    ///
    /// Header-based optimization means using a 4-bit or 1-byte header to describe
    /// all elements' metadata, rather than writing metadata per-element.
    #[inline]
    pub fn supports_header_optimization(&self) -> bool {
        match self {
            SerializationContext::TopLevel => false,
            SerializationContext::VecElement => true,
            SerializationContext::HashMapKey => true,
            SerializationContext::HashMapValue => true,
            SerializationContext::StructField => false, // Struct has its own header
        }
    }

    /// Returns whether this context is for a collection element (Vec/HashMap).
    #[inline]
    pub fn is_collection_element(&self) -> bool {
        matches!(
            self,
            SerializationContext::VecElement
                | SerializationContext::HashMapKey
                | SerializationContext::HashMapValue
        )
    }
}

impl Default for SerializationContext {
    fn default() -> Self {
        SerializationContext::TopLevel
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_context_properties() {
        let top_level = SerializationContext::TopLevel;
        assert!(top_level.requires_per_element_type_info());
        assert!(!top_level.supports_header_optimization());
        assert!(!top_level.is_collection_element());

        let vec_elem = SerializationContext::VecElement;
        assert!(!vec_elem.requires_per_element_type_info());
        assert!(vec_elem.supports_header_optimization());
        assert!(vec_elem.is_collection_element());

        let hashmap_key = SerializationContext::HashMapKey;
        assert!(!hashmap_key.requires_per_element_type_info());
        assert!(hashmap_key.supports_header_optimization());
        assert!(hashmap_key.is_collection_element());

        let hashmap_value = SerializationContext::HashMapValue;
        assert!(!hashmap_value.requires_per_element_type_info());
        assert!(hashmap_value.supports_header_optimization());
        assert!(hashmap_value.is_collection_element());

        let struct_field = SerializationContext::StructField;
        assert!(!struct_field.requires_per_element_type_info());
        assert!(!struct_field.supports_header_optimization());
        assert!(!struct_field.is_collection_element());
    }

    #[test]
    fn test_default_is_top_level() {
        assert_eq!(
            SerializationContext::default(),
            SerializationContext::TopLevel
        );
    }
}
