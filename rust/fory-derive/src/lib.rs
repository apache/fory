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

//! # Fory Derive Macros
//!
//! This crate provides procedural macros for the Fory serialization framework.
//! It generates serialization and deserialization code for Rust types.
//!
//! ## Available Macros
//!
//! ### `#[derive(ForyObject)]`
//!
//! Generates object serialization code for structs and enums. This macro
//! implements the `Serializer` trait, enabling full object graph serialization
//! with reference handling.
//!
//! **Supported Types:**
//! - Structs with named fields
//! - Structs with unnamed fields (tuple structs)
//! - Unit structs
//! - Enums with variants
//!
//! **Example:**
//! ```rust
//! use fory_derive::ForyObject;
//! use std::collections::HashMap;
//!
//! #[derive(ForyObject, Debug, PartialEq)]
//! struct Person {
//!     name: String,
//!     age: i32,
//!     address: Address,
//!     hobbies: Vec<String>,
//!     metadata: HashMap<String, String>,
//! }
//!
//! #[derive(ForyObject, Debug, PartialEq)]
//! struct Address {
//!     street: String,
//!     city: String,
//! }
//!
//! #[derive(ForyObject, Debug, PartialEq, Default)]
//! enum Status {
//!     #[default]
//!     Active,
//!     Inactive,
//!     Suspended,
//! }
//! ```
//!
//! ### `#[derive(ForyRow)]`
//!
//! Generates row-based serialization code for structs. This macro implements
//! the `Row` trait, enabling zero-copy deserialization for maximum performance.
//!
//! **Supported Types:**
//! - Structs with named fields only
//! - All field types must implement the `Row` trait
//!
//! **Example:**
//! ```rust
//! use fory_derive::ForyRow;
//! use std::collections::BTreeMap;
//!
//! #[derive(ForyRow)]
//! struct UserProfile {
//!     id: i64,
//!     username: String,
//!     email: String,
//!     scores: Vec<i32>,
//!     preferences: BTreeMap<String, String>,
//!     is_active: bool,
//! }
//! ```
//!
//! ## Generated Code
//!
//! ### For `#[derive(ForyObject)]`
//!
//! The macro generates:
//! - `Serializer` trait implementation
//! - Serialization methods for writing data to buffers
//! - Deserialization methods for reading data from buffers
//! - Type ID management for cross-language compatibility
//!
//! ### For `#[derive(ForyRow)]`
//!
//! The macro generates:
//! - `Row` trait implementation
//! - A getter struct for zero-copy field access
//! - Field accessor methods that return references to the underlying data
//! - Efficient serialization without object allocation
//!
//! ## Attributes
//!
//! - **`#[fory(debug)]` / `#[fory(debug = true)]`**: Enables per-field debug instrumentation
//!   for the annotated struct, allowing you to install custom hooks via
//!   `fory_core::serializer::struct_`.
//! - **`#[fory(evolving = false)]`**: Disables compatible struct type IDs for the annotated
//!   struct, forcing STRUCT/NAMED_STRUCT even when compatible mode is enabled.
//! - **`#[fory(skip)]`**: Marks an individual field (or enum variant) to be ignored by the
//!   generated serializer, retaining compatibility with previous releases.
//! - **`#[fory(generate_default)]`**: Enables the macro to generate `Default` implementation.
//!   By default, `ForyObject` does NOT generate `impl Default` to avoid conflicts with existing
//!   `Default` implementations. Use this attribute when you want the macro to generate both
//!   `ForyDefault` and `Default` for you.
//!
//! ## Field Types
//!
//! Both macros support a wide range of field types:
//!
//! **Primitive Types:**
//! - `bool`, `i8`, `i16`, `i32`, `i64`, `f32`, `f64`
//! - `String`, `&str` (in row format)
//! - `Vec<u8>` for binary data
//!
//! **Collections:**
//! - `Vec<T>` where `T` implements the appropriate trait
//! - `HashMap<K, V>` and `BTreeMap<K, V>` where keys and values implement the trait
//! - `Option<T>` for nullable values
//!
//! **Date/Time:**
//! - `chrono::NaiveDate`
//! - `chrono::NaiveDateTime`
//!
//! **Custom Types:**
//! - Any type that implements `Serializer` (for `Fory`) or `Row` (for `ForyRow`)
//!
//! ## Usage with Fory
//!
//! After deriving the macros, you can use the types with the Fory serialization
//! framework:
//!
//! ```rust
//! use fory_core::{fory::Fory, error::Error};
//! use fory_derive::ForyObject;
//!
//! #[derive(ForyObject, Debug, PartialEq)]
//! struct MyData {
//!     value: i32,
//!     text: String,
//! }
//!
//! fn main() -> Result<(), Error> {
//!     let mut fory = Fory::default();
//!     fory.register::<MyData>(100);
//!     
//!     let data = MyData {
//!         value: 42,
//!         text: "Hello, Fory!".to_string(),
//!     };
//!     
//!     let serialized = fory.serialize(&data)?;
//!     let deserialized: MyData = fory.deserialize(&serialized)?;
//!     
//!     assert_eq!(data, deserialized);
//!     Ok(())
//! }
//! ```
//!
//! ## Performance Considerations
//!
//! - **`Fory`**: Best for complex object graphs with references and nested structures
//! - **`ForyRow`**: Best for high-throughput scenarios requiring zero-copy access
//! - Both macros generate optimized code with minimal runtime overhead
//! - Field access in row format is extremely fast as it involves no allocations

use fory_row::derive_row;
use proc_macro::TokenStream;
use syn::{parse_macro_input, spanned::Spanned, Attribute, DeriveInput, LitBool};

mod fory_row;
mod object;
mod util;

/// Derive macro for object serialization.
///
/// This macro generates code to implement the `Serializer` trait for the
/// annotated type, enabling full object graph serialization with reference
/// handling and cross-language compatibility.
///
/// # Example
///
/// ```rust
/// use fory_derive::ForyObject;
///
/// #[derive(ForyObject, Debug, PartialEq)]
/// struct Person {
///     name: String,
///     age: i32,
///     address: Address,
/// }
///
/// #[derive(ForyObject, Debug, PartialEq)]
/// struct Address {
///     street: String,
///     city: String,
/// }
/// ```
#[proc_macro_derive(ForyObject, attributes(fory))]
pub fn proc_macro_derive_fory_object(input: proc_macro::TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);

    // Check if this is being applied to a trait (which is not possible with derive macros)
    // Derive macros can only be applied to structs, enums, and unions
    let attrs = match parse_fory_attrs(&input.attrs) {
        Ok(attrs) => attrs,
        Err(err) => return err.into_compile_error().into(),
    };

    object::derive_serializer(&input, attrs)
}

/// Derive macro for row-based serialization.
///
/// This macro generates code to implement the `Row` trait for the annotated
/// type, enabling zero-copy deserialization for maximum performance in
/// high-throughput scenarios.
///
/// # Example
///
/// ```rust
/// use fory_derive::ForyRow;
///
/// #[derive(ForyRow)]
/// struct UserProfile {
///     id: i64,
///     username: String,
///     email: String,
///     is_active: bool,
/// }
/// ```
#[proc_macro_derive(ForyRow)]
pub fn proc_macro_derive_fory_row(input: proc_macro::TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);
    derive_row(&input)
}

/// Parsed fory attributes
pub(crate) struct ForyAttrs {
    pub debug_enabled: bool,
    pub generate_default: bool,
    pub evolving: Option<bool>,
}

/// Parse fory attributes and return ForyAttrs
fn parse_fory_attrs(attrs: &[Attribute]) -> syn::Result<ForyAttrs> {
    let mut debug_flag: Option<bool> = None;
    let mut generate_default_flag: Option<bool> = None;
    let mut evolving_flag: Option<bool> = None;

    for attr in attrs {
        if attr.path().is_ident("fory") {
            attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("debug") {
                    let value = if meta.input.is_empty() {
                        true
                    } else {
                        let lit: LitBool = meta.value()?.parse()?;
                        lit.value
                    };
                    debug_flag = match debug_flag {
                        Some(existing) if existing != value => {
                            return Err(syn::Error::new(
                                meta.path.span(),
                                "conflicting `debug` attribute values",
                            ));
                        }
                        Some(_) => debug_flag,
                        None => Some(value),
                    };
                } else if meta.path.is_ident("generate_default") {
                    let value = if meta.input.is_empty() {
                        true
                    } else {
                        let lit: LitBool = meta.value()?.parse()?;
                        lit.value
                    };
                    generate_default_flag = match generate_default_flag {
                        Some(existing) if existing != value => {
                            return Err(syn::Error::new(
                                meta.path.span(),
                                "conflicting `generate_default` attribute values",
                            ));
                        }
                        Some(_) => generate_default_flag,
                        None => Some(value),
                    };
                } else if meta.path.is_ident("evolving") {
                    let value = if meta.input.is_empty() {
                        true
                    } else {
                        let lit: LitBool = meta.value()?.parse()?;
                        lit.value
                    };
                    evolving_flag = match evolving_flag {
                        Some(existing) if existing != value => {
                            return Err(syn::Error::new(
                                meta.path.span(),
                                "conflicting `evolving` attribute values",
                            ));
                        }
                        Some(_) => evolving_flag,
                        None => Some(value),
                    };
                }
                Ok(())
            })?;
        }
    }

    Ok(ForyAttrs {
        debug_enabled: debug_flag.unwrap_or(false),
        generate_default: generate_default_flag.unwrap_or(false),
        evolving: evolving_flag,
    })
}
