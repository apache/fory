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
//! Most applications should import these macros from the `fory` facade crate,
//! which also provides the runtime API used by generated code. Direct
//! `fory-derive` usage is for crates that intentionally depend on the
//! lower-level `fory-core` runtime crate.
//!
//! ## Available Macros
//!
//! ### `#[derive(ForyStruct)]`, `#[derive(ForyEnum)]`, `#[derive(ForyUnion)]`
//!
//! Generates `Serializer` implementations for structs, pure enums, and tagged
//! unions with payload variants.
//!
//! **Supported Types:**
//! - `ForyStruct`: named, tuple, and unit structs
//! - `ForyEnum`: pure unit enums
//! - `ForyUnion`: enums with payload variants
//!
//! **Example:**
//! ```rust
//! use fory_derive::{ForyEnum, ForyStruct};
//! use std::collections::HashMap;
//!
//! #[derive(ForyStruct, Debug, PartialEq)]
//! struct Person {
//!     name: String,
//!     age: i32,
//!     address: Address,
//!     hobbies: Vec<String>,
//!     metadata: HashMap<String, String>,
//! }
//!
//! #[derive(ForyStruct, Debug, PartialEq)]
//! struct Address {
//!     street: String,
//!     city: String,
//! }
//!
//! #[derive(ForyEnum, Debug, PartialEq, Default)]
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
//! Generates Standard Row Format serialization and borrowed field views for a
//! named struct. The macro implements `RowValue` and the root `Row` marker.
//! Enums, unions, tuple structs, and unit structs are rejected at compile time.
//!
//! **Supported Types:**
//! - Fixed values: `bool`, `i8`, `i16`, `i32`, `i64`, `f32`, `f64`, `Date`,
//!   `Timestamp`, and `Duration`
//! - Variable values: `String` and `&str`, binary `Vec<u8>` and `&[u8]`, fixed
//!   and variable arrays, `BTreeMap`, and other derived row structs
//! - `Option<T>` for nullable fields and array elements
//! - Every field type must implement `RowValue`
//!
//! **Example:**
//! ```rust
//! use fory_core::error::Error;
//! use fory_core::row::{from_row, to_row};
//! use fory_derive::ForyRow;
//!
//! #[derive(ForyRow)]
//! struct UserProfile {
//!     id: i64,
//!     username: String,
//!     email: Option<String>,
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let bytes = to_row(&UserProfile {
//!     id: 7,
//!     username: "fory".to_owned(),
//!     email: None,
//! })?;
//! let view = from_row::<UserProfile>(&bytes)?;
//! assert_eq!(view.id()?, 7);
//! assert_eq!(view.username()?, "fory");
//! assert_eq!(view.email()?, None);
//! # Ok(())
//! # }
//! ```
//!
//! ## Generated Code
//!
//! ### For `#[derive(ForyStruct)]`, `#[derive(ForyEnum)]`, and `#[derive(ForyUnion)]`
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
//! - A `RowValue` implementation and a root `Row` marker implementation
//! - A borrowed view type whose visibility matches the source struct
//! - `RowView` backing-byte access and cheap `Copy`/`Clone` views
//! - One declaration-order field method preserving each source field's visibility
//! - Field methods returning `Result<<Field as RowValue>::View<'_>, Error>`
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
//!   By default, `ForyStruct` does NOT generate `impl Default` to avoid conflicts with existing
//!   `Default` implementations. This attribute is not valid with `target`.
//! - **`#[fory(target = path::Type)]`**: Makes the derived declaration an external structural
//!   serializer for the target type. Generated code accesses and constructs the target directly;
//!   the serializer declaration itself is never instantiated.
//! - **`#[fory(with = SerializerType)]`**: Selects a serializer whose target is the exact field
//!   value node. Use carrier serializers for exact wrapper or container nodes, and use `list`,
//!   `map`, or `tuple` metadata to select serializers recursively at child nodes.
//! - **`#[fory(default)]`**: Marks the fallible deserialization default `ForyUnion` variant.
//!   `ForyUnion` requires exactly one default variant.
//!
//! ## Field Types
//!
//! The object-format derives support a wide range of field types:
//!
//! **Primitive Types:**
//! - `bool`, `i8`, `i16`, `i32`, `i64`, `f32`, `f64`
//! - `String`
//! - `Vec<u8>` for binary data
//!
//! **Collections:**
//! - `Vec<T>` where `T` implements the appropriate trait
//! - `HashMap<K, V>` and `BTreeMap<K, V>` where keys and values implement the trait
//! - `Option<T>` for nullable values
//!
//! **Date/Time:**
//! - `fory::Date`
//! - `fory::Timestamp`
//! - `fory::Duration`
//! - `chrono::NaiveDate`, `chrono::NaiveDateTime`, and `chrono::Duration` when the `chrono` feature is enabled
//!
//! **Custom Types:**
//! - Any type that implements `Serializer`
//!
//! `ForyRow` uses the separate, exact type set documented under its macro
//! section. A row field implements `RowValue`; only derived structs, arrays,
//! and maps implement the root `Row` marker.
//!
//! Derived structs, enums, and unions can be used behind
//! `Arc<dyn Any + Send + Sync>` when the concrete type satisfies `Send + Sync`.
//! Known non-`Send + Sync` field types such as `Rc<T>` and `RefCell<T>` are not
//! eligible for that carrier.
//!
//! ## Usage with Fory
//!
//! After deriving the macros, you can use the types with the Fory serialization
//! framework:
//!
//! ```rust
//! use fory_core::{fory::Fory, error::Error};
//! use fory_derive::{ForyEnum, ForyStruct, ForyUnion};
//!
//! #[derive(ForyStruct, Debug, PartialEq)]
//! struct MyData {
//!     value: i32,
//!     text: String,
//! }
//!
//! fn main() -> Result<(), Error> {
//!     let mut fory = Fory::builder().xlang(true).build();
//!     fory.register_by_name::<MyData>("example.MyData")?;
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
//! - **`ForyRow`**: Provides lazy, borrowed access to Standard Row Format data
//! - Both macros generate optimized code with minimal runtime overhead

use fory_row::derive_row;
use proc_macro::TokenStream;
use syn::{
    parse_macro_input, spanned::Spanned, Attribute, Data, DeriveInput, Fields, LitBool, Type,
};

mod fory_row;
mod object;
mod runtime_root;
mod util;

/// Derive macro for struct serialization.
#[proc_macro_derive(ForyStruct, attributes(fory))]
pub fn proc_macro_derive_fory_struct(input: proc_macro::TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);
    if !matches!(input.data, Data::Struct(_)) {
        return syn::Error::new(
            input.ident.span(),
            "ForyStruct can only be derived for structs; use ForyEnum for pure enums or ForyUnion for data-carrying enums",
        )
        .into_compile_error()
        .into();
    }
    derive_serializer(input)
}

/// Derive macro for pure enum serialization.
#[proc_macro_derive(ForyEnum, attributes(fory))]
pub fn proc_macro_derive_fory_enum(input: proc_macro::TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);
    let Data::Enum(data_enum) = &input.data else {
        return syn::Error::new(input.ident.span(), "ForyEnum can only be derived for enums")
            .into_compile_error()
            .into();
    };
    if data_enum
        .variants
        .iter()
        .any(|variant| !matches!(variant.fields, Fields::Unit))
    {
        return syn::Error::new(
            input.ident.span(),
            "ForyEnum is only for pure unit enums; use ForyUnion for enum variants with payloads",
        )
        .into_compile_error()
        .into();
    }
    derive_serializer(input)
}

/// Derives serialization for data-carrying Rust enums.
///
/// Xlang-compatible unit and single-payload variants use the Fory `UNION`
/// representation. Native multi-field tuple and named variants use the native
/// `ENUM` representation.
#[proc_macro_derive(ForyUnion, attributes(fory))]
pub fn proc_macro_derive_fory_union(input: proc_macro::TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);
    if let Err(err) = object::derive_union::validate_input(&input) {
        return err.into_compile_error().into();
    }
    derive_serializer(input)
}

fn derive_serializer(input: DeriveInput) -> TokenStream {
    let attrs = match parse_fory_attrs(&input.attrs) {
        Ok(attrs) => attrs,
        Err(err) => return err.into_compile_error().into(),
    };
    let runtime_root = match runtime_root::resolve_runtime_root() {
        Ok(root) => root,
        Err(err) => return err.into_compile_error().into(),
    };

    object::derive_serializer(&input, attrs, runtime_root)
}

/// Derive macro for Standard Row Format serialization.
///
/// This macro accepts named structs whose fields implement `RowValue`. It
/// implements `RowValue` and the root `Row` marker, preserves field declaration
/// order, and generates a borrowed view with field methods that return `Result`.
///
/// # Example
///
/// ```rust
/// use fory_core::error::Error;
/// use fory_core::row::{from_row, to_row};
/// use fory_derive::ForyRow;
///
/// #[derive(ForyRow)]
/// struct UserProfile {
///     id: i64,
///     username: String,
///     email: Option<String>,
/// }
///
/// # fn main() -> Result<(), Error> {
/// let bytes = to_row(&UserProfile {
///     id: 7,
///     username: "fory".to_owned(),
///     email: None,
/// })?;
/// let view = from_row::<UserProfile>(&bytes)?;
/// assert_eq!(view.username()?, "fory");
/// # Ok(())
/// # }
/// ```
#[proc_macro_derive(ForyRow)]
pub fn proc_macro_derive_fory_row(input: proc_macro::TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);
    let runtime_root = match runtime_root::resolve_runtime_root() {
        Ok(root) => root,
        Err(err) => return err.into_compile_error().into(),
    };
    derive_row(&input, runtime_root)
}

/// Parsed fory attributes
pub(crate) struct ForyAttrs {
    pub debug_enabled: bool,
    pub generate_default: bool,
    pub evolving: Option<bool>,
    pub target: Option<Type>,
}

/// Parse fory attributes and return ForyAttrs
fn parse_fory_attrs(attrs: &[Attribute]) -> syn::Result<ForyAttrs> {
    let mut debug_flag: Option<bool> = None;
    let mut generate_default_flag: Option<bool> = None;
    let mut evolving_flag: Option<bool> = None;
    let mut target: Option<Type> = None;

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
                } else if meta.path.is_ident("target") {
                    if target.is_some() {
                        return Err(syn::Error::new(
                            meta.path.span(),
                            "duplicate `target` attribute",
                        ));
                    }
                    target = Some(meta.value()?.parse()?);
                } else {
                    return Err(meta.error("unsupported type-level fory attribute"));
                }
                Ok(())
            })?;
        }
    }

    if let Some(target) = &target {
        if generate_default_flag == Some(true) {
            return Err(syn::Error::new(
                target.span(),
                "`generate_default` is not valid for an external structural serializer",
            ));
        }
    }

    Ok(ForyAttrs {
        debug_enabled: debug_flag.unwrap_or(false),
        generate_default: generate_default_flag.unwrap_or(false),
        evolving: evolving_flag,
        target,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use quote::ToTokens;
    use syn::parse_quote;

    #[test]
    fn parses_external_target() {
        let input: DeriveInput = parse_quote! {
            #[fory(target = external::User)]
            struct UserSerializer {
                name: String,
            }
        };
        let attrs = parse_fory_attrs(&input.attrs).unwrap();
        assert_eq!(
            attrs.target.unwrap().to_token_stream().to_string(),
            "external :: User"
        );
    }

    #[test]
    fn rejects_external_std_default() {
        let input: DeriveInput = parse_quote! {
            #[fory(target = external::User, generate_default)]
            struct UserSerializer {
                name: String,
            }
        };
        let err = match parse_fory_attrs(&input.attrs) {
            Ok(_) => panic!("external structural serializers must reject `generate_default`"),
            Err(err) => err,
        };
        assert!(err.to_string().contains("generate_default` is not valid"));
    }
}
