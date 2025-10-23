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

//! Context-aware serialization strategies.
//!
//! This module provides the [`ContextAwareSerializer`] trait and related utilities
//! for intelligent metadata management based on the current serialization context.
//!
//! ## Design Philosophy
//!
//! Traditional serialization writes full metadata (type info, reference flags) for
//! every value. Context-aware serialization optimizes this by:
//!
//! 1. **Detecting the context**: Is this a top-level object, a Vec element, a struct field?
//! 2. **Analyzing the type**: Is it morphic (single-type) or polymorphic (multi-type)?
//! 3. **Choosing the strategy**: Write full metadata, partial metadata, or no metadata.
//!
//! ## Serialization Strategies by Context
//!
//! ### Top-Level Objects (`SerializationContext::TopLevel`)
//! ```text
//! [RefFlag(1B)] [TypeInfo(var)] [Value(var)]
//! ```
//! - Always write full metadata
//! - Always track references
//! - No optimization assumptions
//!
//! ### Vec Elements (`SerializationContext::VecElement`)
//!
//! **Morphic types** (e.g., `Vec<i32>`):
//! ```text
//! Vec header: [ElementsHeader(4bit)] [Length(varint)]
//! Elements:   [Value1] [Value2] [Value3] ...
//! ```
//! - Type info written once in header
//! - Skip per-element type info
//! - Reference tracking based on header flags
//!
//! **Polymorphic types** (e.g., `Vec<Box<dyn Trait>>`):
//! ```text
//! Vec header: [Length(varint)]
//! Elements:   [TypeInfo1][Value1] [TypeInfo2][Value2] ...
//! ```
//! - Write type info per element
//! - No header optimization
//!
//! ### HashMap Keys/Values (`SerializationContext::HashMapKey/Value`)
//!
//! **Morphic types** (e.g., `HashMap<String, i32>`):
//! ```text
//! Chunk header: [KVHeader(1B)] [ChunkSize(1B)]
//! Pairs:        [Key1][Val1] [Key2][Val2] ...
//! ```
//! - KV header (1 byte): 4 bits for key, 4 bits for value
//! - Skip per-pair type info
//!
//! **Polymorphic types**:
//! ```text
//! Chunk header: [ChunkSize(1B)]
//! Pairs:        [KTypeInfo1][Key1][VTypeInfo1][Val1] ...
//! ```
//! - Write type info per key/value
//!
//! ### Struct Fields (`SerializationContext::StructField`)
//! ```text
//! Struct header: [StructMeta(var)]
//! Fields:        [Field1Value] [Field2Value] ...
//! ```
//! - Type info defined in struct metadata
//! - Fields write only values
//! - Reference tracking per field definition
//!
//! ## Usage Example
//!
//! ```rust,ignore
//! use fory_core::serializer::context_aware::ContextAwareSerializer;
//! use fory_core::resolver::context::WriteContext;
//! use fory_core::resolver::serialization_context::SerializationContext;
//!
//! fn serialize_value<T: ContextAwareSerializer>(
//!     value: &T,
//!     context: &mut WriteContext,
//! ) -> Result<(), Error> {
//!     // The value will automatically choose the right strategy
//!     // based on context.current_context()
//!     value.fory_write_context_aware(context, true, true, false)
//! }
//! ```
//!
//! ## Performance Benefits
//!
//! For a `Vec<i32>` with 1000 elements:
//! - **Traditional**: 1000 × (1B RefFlag + 4B TypeInfo + 4B Value) = 9KB
//! - **Context-aware**: 4bit header + 1000 × 4B Value = 4KB
//! - **Savings**: 55% reduction
//!
//! ## Cross-Language Compatibility
//!
//! This strategy is designed to match Java's serialization behavior:
//! - Java `ArrayList<Integer>` → Rust `Vec<i32>` (both use compact header)
//! - Java `List<Object>` → Rust `Vec<Box<dyn Any>>` (both write per-element type info)

use crate::error::Error;
use crate::meta::type_traits::TypeCharacteristics;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::serialization_context::SerializationContext;
use crate::serializer::{ForyDefault, Serializer};

/// Context-aware serialization strategy trait.
///
/// This trait extends [`Serializer`] with context-aware methods that automatically
/// choose the optimal serialization strategy based on:
/// - Current serialization context (top-level, Vec element, struct field, etc.)
/// - Type characteristics (morphic vs polymorphic)
/// - Reference tracking requirements
///
/// # Automatic Implementation
///
/// This trait is automatically implemented for all types that implement both
/// [`Serializer`] and [`TypeCharacteristics`].
pub trait ContextAwareSerializer: Serializer + TypeCharacteristics + ForyDefault + Sized {
    /// Write value with context-aware strategy.
    ///
    /// This method analyzes the current context and type characteristics to
    /// determine the optimal serialization strategy:
    ///
    /// - **Top-level**: Always write full metadata
    /// - **Collection element (morphic)**: Skip type info (written in header)
    /// - **Collection element (polymorphic)**: Write type info per element
    /// - **Struct field**: Follow struct metadata definition
    ///
    /// # Parameters
    ///
    /// - `context`: Serialization context with current state
    /// - `write_ref_info`: Whether to write reference tracking info (may be ignored based on context)
    /// - `write_type_info`: Whether to write type info (may be ignored based on context)
    /// - `has_generics`: Whether this type has generic parameters
    ///
    /// # Returns
    ///
    /// `Ok(())` on success, or an error if serialization fails.
    fn fory_write_context_aware(
        &self,
        context: &mut WriteContext,
        write_ref_info: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        let current_context = context.current_context();

        match current_context {
            SerializationContext::TopLevel => {
                // Top-level: always write full metadata
                self.fory_write(context, write_ref_info, write_type_info, has_generics)
            }

            SerializationContext::VecElement
            | SerializationContext::HashMapKey
            | SerializationContext::HashMapValue => {
                // Collection element: strategy depends on type characteristics
                if Self::is_polymorphic() {
                    // Polymorphic: write type info per element
                    self.fory_write(context, write_ref_info, true, has_generics)
                } else {
                    // Morphic: type info in header, skip here
                    if Self::fory_is_shared_ref() {
                        // Shared refs always need ref tracking
                        self.fory_write(context, true, false, has_generics)
                    } else {
                        // Non-shared: just write data
                        Self::fory_write_data_generic(self, context, has_generics)
                    }
                }
            }

            SerializationContext::StructField => {
                // Struct field: type info in struct metadata
                // Only write ref info if needed
                if Self::fory_is_shared_ref() {
                    self.fory_write(context, true, false, has_generics)
                } else {
                    Self::fory_write_data_generic(self, context, has_generics)
                }
            }
        }
    }

    /// Read value with context-aware strategy.
    ///
    /// This is the deserialization counterpart of [`fory_write_context_aware`].
    /// It determines what metadata to read based on the current context.
    ///
    /// # Parameters
    ///
    /// - `context`: Deserialization context with current state
    /// - `read_ref_info`: Whether to read reference tracking info
    /// - `read_type_info`: Whether to read type info
    ///
    /// # Returns
    ///
    /// The deserialized value, or an error.
    fn fory_read_context_aware(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        // For now, delegate to standard read
        // Future optimization: context-aware deserialization
        Self::fory_read(context, read_ref_info, read_type_info)
    }
}

/// Automatic implementation for all sized types that are both Serializer and TypeCharacteristics.
impl<T> ContextAwareSerializer for T where T: Serializer + TypeCharacteristics + ForyDefault + Sized {}

/// Metadata flags for context-aware serialization.
///
/// These flags are used to determine what metadata should be written/read
/// based on the context and type characteristics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MetadataFlags {
    /// Whether to write/read reference tracking information.
    pub write_ref_info: bool,

    /// Whether to write/read type information.
    pub write_type_info: bool,

    /// Whether this type has generic parameters.
    pub has_generics: bool,
}

impl MetadataFlags {
    /// Compute metadata flags for a given context and type.
    ///
    /// This is a helper function to determine what metadata should be written
    /// based on the serialization context and type characteristics.
    pub fn for_context<T: TypeCharacteristics + Serializer>(
        context: &SerializationContext,
    ) -> Self {
        match context {
            SerializationContext::TopLevel => MetadataFlags {
                write_ref_info: true,
                write_type_info: true,
                has_generics: false, // Determined at call site
            },

            SerializationContext::VecElement
            | SerializationContext::HashMapKey
            | SerializationContext::HashMapValue => {
                if T::is_polymorphic() {
                    // Polymorphic collection elements: write type info
                    MetadataFlags {
                        write_ref_info: T::fory_is_shared_ref(),
                        write_type_info: true,
                        has_generics: false,
                    }
                } else {
                    // Morphic collection elements: skip type info (in header)
                    MetadataFlags {
                        write_ref_info: T::fory_is_shared_ref(),
                        write_type_info: false,
                        has_generics: false,
                    }
                }
            }

            SerializationContext::StructField => {
                // Struct fields: type info in struct metadata
                MetadataFlags {
                    write_ref_info: T::fory_is_shared_ref(),
                    write_type_info: false,
                    has_generics: false,
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_metadata_flags_top_level() {
        let context = SerializationContext::TopLevel;
        let flags = MetadataFlags::for_context::<i32>(&context);

        assert!(flags.write_ref_info);
        assert!(flags.write_type_info);
    }

    #[test]
    fn test_metadata_flags_vec_element_morphic() {
        let context = SerializationContext::VecElement;
        let flags = MetadataFlags::for_context::<i32>(&context);

        // i32 is morphic, so skip type info
        assert!(!flags.write_ref_info); // i32 is not a shared ref
        assert!(!flags.write_type_info); // Morphic: type info in header
    }

    #[test]
    fn test_metadata_flags_struct_field() {
        let context = SerializationContext::StructField;
        let flags = MetadataFlags::for_context::<String>(&context);

        assert!(!flags.write_ref_info); // String is not a shared ref
        assert!(!flags.write_type_info); // Struct metadata defines it
    }
}
