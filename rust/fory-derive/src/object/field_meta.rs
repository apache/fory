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

//! Field-level metadata parsing for `#[fory(...)]` attributes.
//!
//! This module provides support for field-level optimization attributes:
//! - `id = N`: Field tag ID for compact encoding (>=0) or field name encoding (-1)
//! - `nullable`: Whether the field can be null (default: false, except Option/RcWeak/ArcWeak)
//! - `ref`: Whether to enable reference tracking (default: false, except Rc/Arc/RcWeak/ArcWeak)
//! - `skip`: Skip this field during serialization
//! - `encoding`: Integer wire encoding, one of `varint`, `fixed`, or `tagged`
//! - `list(element(...))`: Nested list element configuration
//! - `array`: Dense numeric/vector array schema for `Vec<T>`
//! - `bytes`: Binary blob schema for `Vec<u8>`
//! - `map(key(...), value(...))`: Nested map key/value configuration

use quote::ToTokens;
use std::collections::HashMap;
use syn::spanned::Spanned;
use syn::{Field, GenericArgument, PathArguments, Type};

/// Represents parsed `#[fory(...)]` field attributes
#[derive(Debug, Clone, Default)]
pub struct ForyFieldMeta {
    /// Field tag ID: None = use field name, Some(-1) = explicit opt-out, Some(>=0) = use tag ID
    pub id: Option<i32>,
    /// Whether the field can be null (None = use type-based default)
    pub nullable: Option<bool>,
    /// Whether to enable reference tracking (None = use type-based default)
    pub r#ref: Option<bool>,
    /// Whether to skip this field entirely
    pub skip: bool,
    /// Integer wire encoding selected by semantic field config.
    pub encoding: Option<IntEncoding>,
    /// Nested list element configuration.
    pub list: Option<ForyListMeta>,
    /// Dense numeric/vector array schema.
    pub array: bool,
    /// Binary blob schema for `Vec<u8>`.
    pub bytes: bool,
    /// Nested map key/value configuration.
    pub map: Option<ForyMapMeta>,
}

#[derive(Debug, Clone, Default)]
pub struct ForyListMeta {
    pub element: Option<Box<ForyFieldMeta>>,
}

#[derive(Debug, Clone, Default)]
pub struct ForyMapMeta {
    pub key: Option<Box<ForyFieldMeta>>,
    pub value: Option<Box<ForyFieldMeta>>,
}

/// Integer wire encoding selected by `#[fory(encoding = ...)]`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IntEncoding {
    Varint,
    Fixed,
    Tagged,
}

/// Type classification for determining default nullable/ref behavior
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FieldTypeClass {
    /// Primitives: i8, i16, i32, i64, i128, isize, u8, u16, u32, u64, u128, usize, f32, f64, bool
    Primitive,
    /// `Option<T>` - nullable by default
    Option,
    /// `Rc<T>` - ref tracking by default, non-nullable
    Rc,
    /// `Arc<T>` - ref tracking by default, non-nullable
    Arc,
    /// `RcWeak<T>` (fory type) - nullable AND ref tracking by default
    RcWeak,
    /// `ArcWeak<T>` (fory type) - nullable AND ref tracking by default
    ArcWeak,
    /// All other types (String, Vec, HashMap, user structs, etc.)
    Other,
}

impl ForyFieldMeta {
    /// Returns effective nullable value based on field type classification
    ///
    /// Defaults (for xlang compatibility - all languages use same defaults):
    /// - `Option<T>`, `RcWeak<T>`, `ArcWeak<T>`: true (can be None/dangling)
    /// - All other types: false (non-nullable by default)
    ///
    /// This ensures consistent struct hash computation across all languages for xlang serialization.
    pub fn effective_nullable(&self, type_class: FieldTypeClass) -> bool {
        self.nullable.unwrap_or(matches!(
            type_class,
            FieldTypeClass::Option | FieldTypeClass::RcWeak | FieldTypeClass::ArcWeak
        ))
    }

    /// Returns effective ref tracking value based on field type classification
    ///
    /// Defaults:
    /// - `Rc<T>`, `Arc<T>`, `RcWeak<T>`, `ArcWeak<T>`: true (shared ownership types)
    /// - All other types: false
    pub fn effective_ref(&self, type_class: FieldTypeClass) -> bool {
        self.r#ref.unwrap_or(matches!(
            type_class,
            FieldTypeClass::Rc
                | FieldTypeClass::Arc
                | FieldTypeClass::RcWeak
                | FieldTypeClass::ArcWeak
        ))
    }

    /// Returns effective field ID or -1 for field name encoding
    pub fn effective_id(&self) -> i32 {
        self.id.unwrap_or(-1)
    }

    /// Returns true if this field should use tag ID encoding
    pub fn uses_tag_id(&self) -> bool {
        self.id.is_some_and(|id| id >= 0)
    }

    pub fn element_meta(&self) -> ForyFieldMeta {
        self.list
            .as_ref()
            .and_then(|list| list.element.as_ref())
            .map(|meta| (**meta).clone())
            .unwrap_or_default()
    }

    pub fn map_key_meta(&self) -> ForyFieldMeta {
        self.map
            .as_ref()
            .and_then(|map| map.key.as_ref())
            .map(|meta| (**meta).clone())
            .unwrap_or_default()
    }

    pub fn map_value_meta(&self) -> ForyFieldMeta {
        self.map
            .as_ref()
            .and_then(|map| map.value.as_ref())
            .map(|meta| (**meta).clone())
            .unwrap_or_default()
    }
}

/// Parse `#[fory(...)]` attributes from a field
pub fn parse_field_meta(field: &Field) -> syn::Result<ForyFieldMeta> {
    let mut meta = ForyFieldMeta::default();

    for attr in &field.attrs {
        if !attr.path().is_ident("fory") {
            continue;
        }

        attr.parse_nested_meta(|nested| parse_meta_item(&mut meta, nested, true))?;
    }

    Ok(meta)
}

fn parse_meta_item(
    meta: &mut ForyFieldMeta,
    nested: syn::meta::ParseNestedMeta,
    allow_field_keys: bool,
) -> syn::Result<()> {
    if nested.path.is_ident("id") {
        if !allow_field_keys {
            return Err(syn::Error::new(
                nested.path.span(),
                "id is only valid on a struct field, not inside nested list/map config",
            ));
        }
        let lit: syn::LitInt = nested.value()?.parse()?;
        let id: i32 = lit.base10_parse()?;
        if id < -1 {
            return Err(syn::Error::new(lit.span(), "id must be >= -1"));
        }
        if meta.id.is_some() {
            return Err(syn::Error::new(nested.path.span(), "duplicate id config"));
        }
        meta.id = Some(id);
    } else if nested.path.is_ident("nullable") {
        let value = parse_bool_or_flag(&nested)?;
        if meta.nullable.is_some() {
            return Err(syn::Error::new(
                nested.path.span(),
                "duplicate nullable config",
            ));
        }
        meta.nullable = Some(value);
    } else if nested.path.is_ident("ref") {
        let value = parse_bool_or_flag(&nested)?;
        if meta.r#ref.is_some() {
            return Err(syn::Error::new(nested.path.span(), "duplicate ref config"));
        }
        meta.r#ref = Some(value);
    } else if nested.path.is_ident("skip") {
        if !allow_field_keys {
            return Err(syn::Error::new(
                nested.path.span(),
                "skip is only valid on a struct field, not inside nested list/map config",
            ));
        }
        if meta.skip {
            return Err(syn::Error::new(nested.path.span(), "duplicate skip config"));
        }
        meta.skip = true;
    } else if nested.path.is_ident("encoding") {
        let encoding = parse_encoding_value(&nested)?;
        if meta.encoding.is_some() {
            return Err(syn::Error::new(
                nested.path.span(),
                "duplicate encoding config",
            ));
        }
        meta.encoding = Some(encoding);
    } else if nested.path.is_ident("list") {
        if meta.list.is_some() {
            return Err(syn::Error::new(nested.path.span(), "duplicate list config"));
        }
        parse_list_meta(meta, nested)?;
    } else if nested.path.is_ident("array") {
        if meta.array {
            return Err(syn::Error::new(
                nested.path.span(),
                "duplicate array config",
            ));
        }
        if !nested.input.is_empty() && !nested.input.peek(syn::Token![,]) {
            return Err(syn::Error::new(
                nested.path.span(),
                "array does not accept parameters; use #[fory(array)]",
            ));
        }
        meta.array = true;
    } else if nested.path.is_ident("bytes") {
        if meta.bytes {
            return Err(syn::Error::new(
                nested.path.span(),
                "duplicate bytes config",
            ));
        }
        if !nested.input.is_empty() && !nested.input.peek(syn::Token![,]) {
            return Err(syn::Error::new(
                nested.path.span(),
                "bytes does not accept parameters; use #[fory(bytes)]",
            ));
        }
        meta.bytes = true;
    } else if nested.path.is_ident("map") {
        if meta.map.is_some() {
            return Err(syn::Error::new(nested.path.span(), "duplicate map config"));
        }
        parse_map_meta(meta, nested)?;
    } else if nested.path.is_ident("type_id") {
        return Err(syn::Error::new(
            nested.path.span(),
            "type_id is internal wire metadata and is not a field attribute",
        ));
    } else {
        return Err(syn::Error::new(
            nested.path.span(),
            "unknown fory field attribute",
        ));
    }
    Ok(())
}

fn parse_list_meta(
    meta: &mut ForyFieldMeta,
    nested: syn::meta::ParseNestedMeta,
) -> syn::Result<()> {
    let mut list_meta = meta.list.clone().unwrap_or_default();
    nested.parse_nested_meta(|item| {
        if item.path.is_ident("element") {
            if list_meta.element.is_some() {
                return Err(syn::Error::new(
                    item.path.span(),
                    "duplicate list element config",
                ));
            }
            let mut element = list_meta
                .element
                .take()
                .map(|meta| *meta)
                .unwrap_or_default();
            item.parse_nested_meta(|child| parse_meta_item(&mut element, child, false))?;
            list_meta.element = Some(Box::new(element));
            Ok(())
        } else {
            Err(syn::Error::new(
                item.path.span(),
                "list config supports only element(...)",
            ))
        }
    })?;
    meta.list = Some(list_meta);
    Ok(())
}

fn parse_map_meta(meta: &mut ForyFieldMeta, nested: syn::meta::ParseNestedMeta) -> syn::Result<()> {
    let mut map_meta = meta.map.clone().unwrap_or_default();
    nested.parse_nested_meta(|item| {
        if item.path.is_ident("key") {
            if map_meta.key.is_some() {
                return Err(syn::Error::new(
                    item.path.span(),
                    "duplicate map key config",
                ));
            }
            let mut key = map_meta.key.take().map(|meta| *meta).unwrap_or_default();
            item.parse_nested_meta(|child| parse_meta_item(&mut key, child, false))?;
            map_meta.key = Some(Box::new(key));
            Ok(())
        } else if item.path.is_ident("value") {
            if map_meta.value.is_some() {
                return Err(syn::Error::new(
                    item.path.span(),
                    "duplicate map value config",
                ));
            }
            let mut value = map_meta.value.take().map(|meta| *meta).unwrap_or_default();
            item.parse_nested_meta(|child| parse_meta_item(&mut value, child, false))?;
            map_meta.value = Some(Box::new(value));
            Ok(())
        } else {
            Err(syn::Error::new(
                item.path.span(),
                "map config supports only key(...) and value(...)",
            ))
        }
    })?;
    meta.map = Some(map_meta);
    Ok(())
}

fn parse_encoding_value(meta: &syn::meta::ParseNestedMeta) -> syn::Result<IntEncoding> {
    let value = meta.value()?;
    if value.peek(syn::LitStr) {
        let lit: syn::LitStr = value.parse()?;
        return parse_encoding_name(&lit.value(), lit.span());
    }
    let ident: syn::Ident = value.parse()?;
    parse_encoding_name(&ident.to_string(), ident.span())
}

fn parse_encoding_name(value: &str, span: proc_macro2::Span) -> syn::Result<IntEncoding> {
    match value {
        "varint" => Ok(IntEncoding::Varint),
        "fixed" => Ok(IntEncoding::Fixed),
        "tagged" => Ok(IntEncoding::Tagged),
        _ => Err(syn::Error::new(
            span,
            "encoding must be varint, fixed, or tagged",
        )),
    }
}

/// Parse a boolean value or treat standalone flag as true
fn parse_bool_or_flag(meta: &syn::meta::ParseNestedMeta) -> syn::Result<bool> {
    if meta.input.is_empty() || meta.input.peek(syn::Token![,]) {
        Ok(true) // Standalone flag like `nullable` = true
    } else {
        let lit: syn::LitBool = meta.value()?.parse()?;
        Ok(lit.value)
    }
}

/// Validates that field tag IDs are unique within a struct
#[allow(dead_code)]
pub fn validate_field_metas(fields_with_meta: &[(&Field, ForyFieldMeta)]) -> syn::Result<()> {
    let mut id_to_field: HashMap<i32, &syn::Ident> = HashMap::new();

    for (field, meta) in fields_with_meta {
        if meta.skip {
            continue;
        }

        if let Some(id) = meta.id {
            if id >= 0 {
                if let Some(existing) = id_to_field.get(&id) {
                    let field_name = field.ident.as_ref().unwrap();
                    return Err(syn::Error::new(
                        field_name.span(),
                        format!(
                            "duplicate fory field id={} on fields '{}' and '{}'",
                            id, existing, field_name
                        ),
                    ));
                }
                id_to_field.insert(id, field.ident.as_ref().unwrap());
            }
        }
    }

    Ok(())
}

/// Extract the outer type name from a type (e.g., "Option" from `Option<String>`)
fn extract_outer_type_name(ty: &Type) -> String {
    match ty {
        Type::Path(type_path) => {
            if let Some(seg) = type_path.path.segments.last() {
                seg.ident.to_string()
            } else {
                String::new()
            }
        }
        _ => String::new(),
    }
}

/// Extract the inner type from `Option<T>`
pub fn extract_option_inner_type(ty: &Type) -> Option<Type> {
    if let Type::Path(type_path) = ty {
        if let Some(seg) = type_path.path.segments.last() {
            if seg.ident == "Option" {
                if let PathArguments::AngleBracketed(args) = &seg.arguments {
                    if let Some(GenericArgument::Type(inner_ty)) = args.args.first() {
                        return Some(inner_ty.clone());
                    }
                }
            }
        }
    }
    None
}

/// Returns true if the outer type is Option, regardless of inner type
pub fn is_option_type(ty: &Type) -> bool {
    extract_outer_type_name(ty) == "Option"
}

/// Classify a field type to determine default nullable/ref behavior
pub fn classify_field_type(ty: &Type) -> FieldTypeClass {
    let type_name = extract_outer_type_name(ty);
    match type_name.as_str() {
        // Primitives
        "i8" | "i16" | "i32" | "i64" | "i128" | "isize" | "u8" | "u16" | "u32" | "u64" | "u128"
        | "usize" | "f32" | "f64" | "bool" => FieldTypeClass::Primitive,

        // Option<T>
        "Option" => {
            // Check if inner type is Rc/Arc/RcWeak/ArcWeak for combined behavior
            if let Some(inner) = extract_option_inner_type(ty) {
                let inner_class = classify_field_type(&inner);
                if matches!(
                    inner_class,
                    FieldTypeClass::Rc
                        | FieldTypeClass::Arc
                        | FieldTypeClass::RcWeak
                        | FieldTypeClass::ArcWeak
                ) {
                    return inner_class; // Option<Rc<T>> inherits Rc's ref tracking
                }
            }
            FieldTypeClass::Option
        }

        // Shared ownership types (std library)
        "Rc" => FieldTypeClass::Rc,
        "Arc" => FieldTypeClass::Arc,

        // Fory's weak reference types (nullable AND ref tracking by default)
        "RcWeak" => FieldTypeClass::RcWeak,
        "ArcWeak" => FieldTypeClass::ArcWeak,

        // All other types
        _ => FieldTypeClass::Other,
    }
}

/// Get nullable and ref flags for a field based on its type and metadata
#[allow(dead_code)]
pub fn get_field_flags(field: &Field, meta: &ForyFieldMeta) -> (bool, bool) {
    let type_class = classify_field_type(&field.ty);
    let nullable = meta.effective_nullable(type_class);
    let ref_flag = meta.effective_ref(type_class);
    (nullable, ref_flag)
}

/// Parse field metadata for all fields and validate
#[allow(dead_code)]
pub fn parse_and_validate_fields<'a>(
    fields: &'a [&'a Field],
) -> syn::Result<Vec<(&'a Field, ForyFieldMeta)>> {
    let fields_with_meta: Vec<_> = fields
        .iter()
        .map(|f| {
            let meta = parse_field_meta(f)?;
            Ok((*f, meta))
        })
        .collect::<syn::Result<_>>()?;

    validate_field_metas(&fields_with_meta)?;

    Ok(fields_with_meta)
}

/// Check if a field has the skip attribute
pub fn is_skip_field(field: &Field) -> bool {
    parse_field_meta(field).is_ok_and(|meta| meta.skip)
}

/// Convert type to string for comparison (removes whitespace)
#[allow(dead_code)]
pub fn type_to_string(ty: &Type) -> String {
    ty.to_token_stream()
        .to_string()
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use syn::parse_quote;

    #[test]
    fn test_parse_id_only() {
        let field: Field = parse_quote! {
            #[fory(id = 0)]
            name: String
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.id, Some(0));
        assert_eq!(meta.nullable, None);
        assert_eq!(meta.r#ref, None);
        assert!(!meta.skip);
    }

    #[test]
    fn test_parse_full_attributes() {
        let field: Field = parse_quote! {
            #[fory(id = 1, nullable = true, ref = false)]
            data: Vec<u8>
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.id, Some(1));
        assert_eq!(meta.nullable, Some(true));
        assert_eq!(meta.r#ref, Some(false));
    }

    #[test]
    fn test_parse_array_attribute() {
        let field: Field = parse_quote! {
            #[fory(array)]
            data: Vec<i32>
        };
        let meta = parse_field_meta(&field).unwrap();
        assert!(meta.array);
    }

    #[test]
    fn test_parse_bytes_attribute() {
        let field: Field = parse_quote! {
            #[fory(bytes)]
            data: Vec<u8>
        };
        let meta = parse_field_meta(&field).unwrap();
        assert!(meta.bytes);
    }

    #[test]
    fn test_parse_standalone_flags() {
        let field: Field = parse_quote! {
            #[fory(id = 2, nullable, ref)]
            data: String
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.id, Some(2));
        assert_eq!(meta.nullable, Some(true));
        assert_eq!(meta.r#ref, Some(true));
    }

    #[test]
    fn test_parse_skip() {
        let field: Field = parse_quote! {
            #[fory(skip)]
            secret: String
        };
        let meta = parse_field_meta(&field).unwrap();
        assert!(meta.skip);
    }

    #[test]
    fn test_validate_duplicate_ids() {
        let field1: Field = parse_quote! {
            #[fory(id = 0)]
            name: String
        };
        let field2: Field = parse_quote! {
            #[fory(id = 0)]
            other: String
        };
        let meta1 = parse_field_meta(&field1).unwrap();
        let meta2 = parse_field_meta(&field2).unwrap();

        let result = validate_field_metas(&[(&field1, meta1), (&field2, meta2)]);
        assert!(result.is_err());
    }

    #[test]
    fn test_classify_primitive_types() {
        let field: Field = parse_quote! { x: i32 };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Primitive);

        let field: Field = parse_quote! { x: f64 };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Primitive);

        let field: Field = parse_quote! { x: bool };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Primitive);
    }

    #[test]
    fn test_classify_option_types() {
        let field: Field = parse_quote! { x: Option<String> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Option);

        let field: Field = parse_quote! { x: Option<i32> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Option);
    }

    #[test]
    fn test_classify_shared_ownership_types() {
        let field: Field = parse_quote! { x: Rc<String> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Rc);

        let field: Field = parse_quote! { x: Arc<Vec<u8>> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Arc);

        let field: Field = parse_quote! { x: RcWeak<String> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::RcWeak);

        let field: Field = parse_quote! { x: ArcWeak<i32> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::ArcWeak);
    }

    #[test]
    fn test_classify_option_with_shared_types() {
        // Option<Rc<T>> should inherit Rc's ref tracking
        let field: Field = parse_quote! { x: Option<Rc<String>> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Rc);

        let field: Field = parse_quote! { x: Option<Arc<i32>> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Arc);
    }

    #[test]
    fn test_classify_other_types() {
        let field: Field = parse_quote! { x: String };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Other);

        let field: Field = parse_quote! { x: Vec<u8> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Other);

        let field: Field = parse_quote! { x: HashMap<String, i32> };
        assert_eq!(classify_field_type(&field.ty), FieldTypeClass::Other);
    }

    #[test]
    fn test_effective_nullable_defaults() {
        let meta = ForyFieldMeta::default();

        // Only Option and RcWeak/ArcWeak are nullable by default (can be None/dangling)
        assert!(meta.effective_nullable(FieldTypeClass::Option));
        assert!(meta.effective_nullable(FieldTypeClass::RcWeak));
        assert!(meta.effective_nullable(FieldTypeClass::ArcWeak));

        // All other types are non-nullable by default (xlang default)
        assert!(!meta.effective_nullable(FieldTypeClass::Primitive));
        assert!(!meta.effective_nullable(FieldTypeClass::Rc));
        assert!(!meta.effective_nullable(FieldTypeClass::Arc));
        assert!(!meta.effective_nullable(FieldTypeClass::Other));
    }

    #[test]
    fn test_effective_ref_defaults() {
        let meta = ForyFieldMeta::default();

        // Rc, Arc, and RcWeak/ArcWeak have ref tracking by default
        assert!(meta.effective_ref(FieldTypeClass::Rc));
        assert!(meta.effective_ref(FieldTypeClass::Arc));
        assert!(meta.effective_ref(FieldTypeClass::RcWeak));
        assert!(meta.effective_ref(FieldTypeClass::ArcWeak));

        // All others don't have ref tracking by default
        assert!(!meta.effective_ref(FieldTypeClass::Primitive));
        assert!(!meta.effective_ref(FieldTypeClass::Option));
        assert!(!meta.effective_ref(FieldTypeClass::Other));
    }

    #[test]
    fn test_explicit_attribute_overrides_default() {
        // Explicit nullable=true overrides default
        let meta = ForyFieldMeta {
            id: Some(0),
            nullable: Some(true),
            r#ref: None,
            skip: false,
            encoding: None,
            list: None,
            array: false,
            bytes: false,
            map: None,
        };
        assert!(meta.effective_nullable(FieldTypeClass::Primitive)); // Would be false by default

        // Explicit ref=false overrides default
        let meta = ForyFieldMeta {
            id: Some(0),
            nullable: None,
            r#ref: Some(false),
            skip: false,
            encoding: None,
            list: None,
            array: false,
            bytes: false,
            map: None,
        };
        assert!(!meta.effective_ref(FieldTypeClass::Rc)); // Would be true by default
    }

    #[test]
    fn test_parse_encoding_attribute() {
        let field: Field = parse_quote! {
            #[fory(encoding = varint)]
            value: u64
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.encoding, Some(IntEncoding::Varint));

        let field: Field = parse_quote! {
            #[fory(encoding = fixed)]
            value: u64
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.encoding, Some(IntEncoding::Fixed));

        let field: Field = parse_quote! {
            #[fory(encoding = tagged)]
            value: u64
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.encoding, Some(IntEncoding::Tagged));
    }

    #[test]
    fn test_parse_encoding_for_i32_u32() {
        // encoding="varint" for i32/u32
        let field: Field = parse_quote! {
            #[fory(encoding = varint)]
            value: i32
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.encoding, Some(IntEncoding::Varint));

        // encoding="fixed" for i32/u32
        let field: Field = parse_quote! {
            #[fory(encoding = fixed)]
            value: u32
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.encoding, Some(IntEncoding::Fixed));
    }

    #[test]
    fn test_removed_type_id_attribute() {
        let field: Field = parse_quote! {
            #[fory(type_id = "union")]
            value: i32
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err
            .to_string()
            .contains("type_id is internal wire metadata"));
    }

    #[test]
    fn test_parse_combined_attributes() {
        // nullable with encoding="tagged" (for u64)
        let field: Field = parse_quote! {
            #[fory(nullable, encoding = tagged)]
            value: Option<u64>
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.nullable, Some(true));
        assert_eq!(meta.encoding, Some(IntEncoding::Tagged));

        // encoding="fixed" for Option<i32>
        let field: Field = parse_quote! {
            #[fory(nullable, encoding = fixed)]
            value: Option<i32>
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.nullable, Some(true));
        assert_eq!(meta.encoding, Some(IntEncoding::Fixed));
    }

    #[test]
    fn test_parse_nested_list_and_map_config() {
        let field: Field = parse_quote! {
            #[fory(nullable, list(element(encoding = fixed)))]
            values: Vec<Option<i32>>
        };
        let meta = parse_field_meta(&field).unwrap();
        assert_eq!(meta.nullable, Some(true));
        assert_eq!(
            meta.list
                .as_ref()
                .and_then(|list| list.element.as_ref())
                .and_then(|element| element.encoding),
            Some(IntEncoding::Fixed)
        );

        let field: Field = parse_quote! {
            #[fory(map(key(encoding = fixed), value(nullable = true, encoding = tagged)))]
            data: HashMap<Option<i32>, Option<u64>>
        };
        let meta = parse_field_meta(&field).unwrap();
        let map = meta.map.as_ref().unwrap();
        assert_eq!(
            map.key.as_ref().and_then(|key| key.encoding),
            Some(IntEncoding::Fixed)
        );
        assert_eq!(
            map.value.as_ref().and_then(|value| value.nullable),
            Some(true)
        );
        assert_eq!(
            map.value.as_ref().and_then(|value| value.encoding),
            Some(IntEncoding::Tagged)
        );
    }

    #[test]
    fn test_reject_field_only_keys_inside_nested_config() {
        let field: Field = parse_quote! {
            #[fory(list(element(id = 1)))]
            values: Vec<i32>
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err
            .to_string()
            .contains("id is only valid on a struct field"));
    }

    #[test]
    fn test_reject_invalid_field_meta_grammar() {
        let field: Field = parse_quote! {
            #[fory(unknown = true)]
            value: i32
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err.to_string().contains("unknown fory field attribute"));

        let field: Field = parse_quote! {
            #[fory(encoding = zigzag)]
            value: i64
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err
            .to_string()
            .contains("encoding must be varint, fixed, or tagged"));

        let field: Field = parse_quote! {
            #[fory(map(element(encoding = fixed)))]
            data: HashMap<i32, i32>
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err
            .to_string()
            .contains("map config supports only key(...) and value(...)"));
    }

    #[test]
    fn test_reject_nested_field_only_and_wire_attrs() {
        let field: Field = parse_quote! {
            #[fory(map(value(skip)))]
            data: HashMap<i32, i32>
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err
            .to_string()
            .contains("skip is only valid on a struct field"));

        let field: Field = parse_quote! {
            #[fory(map(key(type_id = 123)))]
            data: HashMap<i32, i32>
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err
            .to_string()
            .contains("type_id is internal wire metadata"));
    }

    #[test]
    fn test_reject_duplicate_field_meta_config() {
        let field: Field = parse_quote! {
            #[fory(id = 1, id = 2)]
            value: i32
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err.to_string().contains("duplicate id config"));

        let field: Field = parse_quote! {
            #[fory(nullable, nullable = false)]
            value: Option<i32>
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err.to_string().contains("duplicate nullable config"));

        let field: Field = parse_quote! {
            #[fory(encoding = fixed, encoding = varint)]
            value: i32
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err.to_string().contains("duplicate encoding config"));
    }

    #[test]
    fn test_reject_duplicate_nested_config() {
        let field: Field = parse_quote! {
            #[fory(list(element(encoding = fixed), element(nullable = true)))]
            values: Vec<Option<i32>>
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err.to_string().contains("duplicate list element config"));

        let field: Field = parse_quote! {
            #[fory(map(key(encoding = fixed), key(nullable = true)))]
            data: HashMap<Option<i32>, i32>
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err.to_string().contains("duplicate map key config"));

        let field: Field = parse_quote! {
            #[fory(map(value(encoding = fixed), value(nullable = true)))]
            data: HashMap<i32, Option<i32>>
        };
        let err = parse_field_meta(&field).unwrap_err();
        assert!(err.to_string().contains("duplicate map value config"));
    }
}
