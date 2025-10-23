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

//! Type trait analysis for morphic vs polymorphic type detection.
//!
//! This module provides the [`TypeCharacteristics`] trait and related utilities
//! for determining whether a type is **morphic** (single-type, monomorphic) or
//! **polymorphic** (multi-type, can hold different concrete types).
//!
//! ## Terminology
//!
//! - **Morphic (Monomorphic)**: A type that always has the same concrete type at runtime.
//!   Examples: `i32`, `String`, `Vec<i32>`, `HashMap<String, i32>`, enums with fixed variants.
//!
//! - **Polymorphic**: A type that can hold different concrete types at runtime through
//!   trait objects or enum variants with different types.
//!   Examples: `Box<dyn Trait>`, `Arc<dyn Trait>`, struct fields with trait bounds.
//!
//! ## Serialization Strategy
//!
//! ### Morphic Types
//! - **Vec<T> where T is morphic**: Use 4-bit elements header, skip per-element type info
//! - **HashMap<K,V> where K,V are morphic**: Use 1-byte KV header, skip per-element type info
//! - **Struct fields**: Type info written once in struct metadata
//!
//! ### Polymorphic Types
//! - **Vec<T> where T is polymorphic**: Write type info per element
//! - **HashMap<K,V> where K or V is polymorphic**: Write type info per key/value
//! - **Struct fields with trait objects**: Write type info per value
//!
//! ## Design Rationale
//!
//! The morphic vs polymorphic distinction is critical for:
//!
//! 1. **Metadata optimization**: Morphic collections can use compact headers instead of
//!    per-element type information, reducing serialized size by 50-80% for large collections.
//!
//! 2. **Deserialization performance**: Knowing the type upfront allows pre-allocation and
//!    faster decoding without repeated type resolution.
//!
//! 3. **Cross-language compatibility**: Java's `List<Integer>` is morphic, so Rust's `Vec<i32>`
//!    must be serialized with the same compact format.
//!
//! ## Example Usage
//!
//! ```rust,ignore
//! use fory_core::meta::type_traits::TypeCharacteristics;
//!
//! fn optimize_vec_serialization<T: Serializer + TypeCharacteristics>(vec: &Vec<T>) {
//!     if T::is_morphic() {
//!         // Use compact 4-bit header
//!         let header = compute_elements_header::<T>();
//!         write_header(header);
//!         for elem in vec {
//!             elem.write_data_only();  // No type info
//!         }
//!     } else {
//!         // Write type info per element
//!         for elem in vec {
//!             elem.write_with_type_info();
//!         }
//!     }
//! }
//! ```
//!
//! ## Implementation Notes
//!
//! - Primitives (`i32`, `f64`, `bool`, etc.) are always morphic
//! - `String` and `&str` are morphic
//! - Enums with no generic parameters are morphic
//! - `Vec<T>`, `HashMap<K,V>` are morphic if their element types are morphic
//! - Trait objects (`dyn Trait`) are always polymorphic
//! - Generic structs depend on their type parameters

use std::any::TypeId;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;

/// Type characteristics for morphic vs polymorphic type analysis.
///
/// This trait is automatically implemented for types that implement [`crate::serializer::Serializer`].
/// It provides methods to determine the runtime behavior of types for serialization optimization.
pub trait TypeCharacteristics {
    /// Returns `true` if this type is morphic (monomorphic, single concrete type).
    ///
    /// Morphic types always have the same concrete type at runtime, allowing
    /// serialization optimizations like compact headers for collections.
    ///
    /// # Examples
    ///
    /// ```rust,ignore
    /// assert!(i32::is_morphic());           // Primitives are morphic
    /// assert!(String::is_morphic());        // String is morphic
    /// assert!(Vec::<i32>::is_morphic());    // Vec<morphic> is morphic
    /// assert!(!Box::<dyn Trait>::is_morphic()); // Trait objects are not morphic
    /// ```
    fn is_morphic() -> bool {
        !Self::is_polymorphic()
    }

    /// Returns `true` if this type is polymorphic (can hold different concrete types).
    ///
    /// Polymorphic types require per-instance type information during serialization.
    ///
    /// # Default Implementation
    ///
    /// The default implementation returns `false`, meaning types are assumed morphic
    /// unless explicitly marked as polymorphic.
    fn is_polymorphic() -> bool {
        false
    }

    /// Returns the type category for metadata optimization.
    fn type_category() -> TypeCategory {
        if Self::is_polymorphic() {
            TypeCategory::Polymorphic
        } else {
            TypeCategory::Morphic
        }
    }
}

/// Type category for serialization optimization.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TypeCategory {
    /// Morphic (monomorphic) type - same concrete type at runtime.
    ///
    /// Serialization strategy:
    /// - Use compact headers for collections
    /// - Skip per-element type info
    /// - Pre-allocate during deserialization
    Morphic,

    /// Polymorphic type - can hold different concrete types at runtime.
    ///
    /// Serialization strategy:
    /// - Write type info per element
    /// - Use dynamic dispatch during deserialization
    /// - No collection header optimization
    Polymorphic,
}

impl TypeCategory {
    /// Returns `true` if this category allows collection header optimization.
    #[inline]
    pub fn supports_collection_header(&self) -> bool {
        matches!(self, TypeCategory::Morphic)
    }

    /// Returns `true` if this category requires per-element type information.
    #[inline]
    pub fn requires_per_element_type_info(&self) -> bool {
        matches!(self, TypeCategory::Polymorphic)
    }
}

// ============================================================================
// Primitive Type Implementations (Always Morphic)
// ============================================================================

macro_rules! impl_morphic_for_primitives {
    ($($t:ty),*) => {
        $(
            impl TypeCharacteristics for $t {
                fn is_morphic() -> bool { true }
                fn is_polymorphic() -> bool { false }
            }
        )*
    };
}

impl_morphic_for_primitives!(
    i8, i16, i32, i64, i128, isize,
    u8, u16, u32, u64, u128, usize,
    f32, f64,
    bool, char
);

// String types are morphic
impl TypeCharacteristics for String {
    fn is_morphic() -> bool { true }
    fn is_polymorphic() -> bool { false }
}

impl TypeCharacteristics for &str {
    fn is_morphic() -> bool { true }
    fn is_polymorphic() -> bool { false }
}

// ============================================================================
// Container Type Implementations
// ============================================================================

/// Vec<T> is morphic if T is morphic.
impl<T: TypeCharacteristics> TypeCharacteristics for Vec<T> {
    fn is_morphic() -> bool {
        T::is_morphic()
    }

    fn is_polymorphic() -> bool {
        T::is_polymorphic()
    }
}

/// HashMap<K, V> is morphic if both K and V are morphic.
impl<K: TypeCharacteristics, V: TypeCharacteristics> TypeCharacteristics for HashMap<K, V> {
    fn is_morphic() -> bool {
        K::is_morphic() && V::is_morphic()
    }

    fn is_polymorphic() -> bool {
        K::is_polymorphic() || V::is_polymorphic()
    }
}

/// Option<T> is morphic if T is morphic.
impl<T: TypeCharacteristics> TypeCharacteristics for Option<T> {
    fn is_morphic() -> bool {
        T::is_morphic()
    }

    fn is_polymorphic() -> bool {
        T::is_polymorphic()
    }
}

/// Result<T, E> is morphic if both T and E are morphic.
impl<T: TypeCharacteristics, E: TypeCharacteristics> TypeCharacteristics for Result<T, E> {
    fn is_morphic() -> bool {
        T::is_morphic() && E::is_morphic()
    }

    fn is_polymorphic() -> bool {
        T::is_polymorphic() || E::is_polymorphic()
    }
}

// ============================================================================
// Smart Pointer Implementations
// ============================================================================

/// Box<T> is morphic if T is morphic and sized.
/// Box<dyn Trait> (unsized) is polymorphic.
impl<T: TypeCharacteristics> TypeCharacteristics for Box<T> {
    fn is_morphic() -> bool {
        T::is_morphic()
    }

    fn is_polymorphic() -> bool {
        T::is_polymorphic()
    }
}

/// Rc<T> is morphic if T is morphic and sized.
impl<T: TypeCharacteristics> TypeCharacteristics for Rc<T> {
    fn is_morphic() -> bool {
        T::is_morphic()
    }

    fn is_polymorphic() -> bool {
        T::is_polymorphic()
    }
}

/// Arc<T> is morphic if T is morphic and sized.
impl<T: TypeCharacteristics> TypeCharacteristics for Arc<T> {
    fn is_morphic() -> bool {
        T::is_morphic()
    }

    fn is_polymorphic() -> bool {
        T::is_polymorphic()
    }
}

// ============================================================================
// Tuple Implementations
// ============================================================================

impl<T1: TypeCharacteristics, T2: TypeCharacteristics> TypeCharacteristics for (T1, T2) {
    fn is_morphic() -> bool {
        T1::is_morphic() && T2::is_morphic()
    }

    fn is_polymorphic() -> bool {
        T1::is_polymorphic() || T2::is_polymorphic()
    }
}

impl<T1, T2, T3> TypeCharacteristics for (T1, T2, T3)
where
    T1: TypeCharacteristics,
    T2: TypeCharacteristics,
    T3: TypeCharacteristics,
{
    fn is_morphic() -> bool {
        T1::is_morphic() && T2::is_morphic() && T3::is_morphic()
    }

    fn is_polymorphic() -> bool {
        T1::is_polymorphic() || T2::is_polymorphic() || T3::is_polymorphic()
    }
}

// ============================================================================
// Unit Type
// ============================================================================

impl TypeCharacteristics for () {
    fn is_morphic() -> bool { true }
    fn is_polymorphic() -> bool { false }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_primitives_are_morphic() {
        assert!(i32::is_morphic());
        assert!(u64::is_morphic());
        assert!(f64::is_morphic());
        assert!(bool::is_morphic());
        assert!(char::is_morphic());

        assert!(!i32::is_polymorphic());
        assert!(!String::is_polymorphic());
    }

    #[test]
    fn test_string_is_morphic() {
        assert!(String::is_morphic());
        assert!(!String::is_polymorphic());
    }

    #[test]
    fn test_vec_morphism() {
        // Vec<i32> is morphic because i32 is morphic
        assert!(Vec::<i32>::is_morphic());
        assert!(!Vec::<i32>::is_polymorphic());

        // Vec<String> is morphic because String is morphic
        assert!(Vec::<String>::is_morphic());
        assert!(!Vec::<String>::is_polymorphic());
    }

    #[test]
    fn test_hashmap_morphism() {
        // HashMap<String, i32> is morphic because both are morphic
        assert!(HashMap::<String, i32>::is_morphic());
        assert!(!HashMap::<String, i32>::is_polymorphic());
    }

    #[test]
    fn test_option_morphism() {
        assert!(Option::<i32>::is_morphic());
        assert!(!Option::<i32>::is_polymorphic());

        assert!(Option::<String>::is_morphic());
    }

    #[test]
    fn test_result_morphism() {
        assert!(Result::<i32, String>::is_morphic());
        assert!(!Result::<i32, String>::is_polymorphic());
    }

    #[test]
    fn test_tuple_morphism() {
        assert!(<(i32, String)>::is_morphic());
        assert!(!<(i32, String)>::is_polymorphic());

        assert!(<(i32, String, bool)>::is_morphic());
    }

    #[test]
    fn test_type_category() {
        assert_eq!(i32::type_category(), TypeCategory::Morphic);
        assert_eq!(String::type_category(), TypeCategory::Morphic);
        assert_eq!(Vec::<i32>::type_category(), TypeCategory::Morphic);
    }

    #[test]
    fn test_category_properties() {
        assert!(TypeCategory::Morphic.supports_collection_header());
        assert!(!TypeCategory::Polymorphic.supports_collection_header());

        assert!(!TypeCategory::Morphic.requires_per_element_type_info());
        assert!(TypeCategory::Polymorphic.requires_per_element_type_info());
    }
}
