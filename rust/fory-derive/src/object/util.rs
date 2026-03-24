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

use crate::util::{
    detect_collection_with_trait_object, is_arc_dyn_trait, is_box_dyn_trait, is_rc_dyn_trait,
    CollectionTraitInfo,
};
use fory_core::types::{TypeId, PRIMITIVE_ARRAY_TYPE_MAP};
use fory_core::util::to_snake_case;
use proc_macro2::{Ident, TokenStream};
use quote::{format_ident, quote, ToTokens};
use std::cell::RefCell;
use std::fmt;
use syn::{Field, GenericArgument, Index, PathArguments, Type};

/// Get field name for a field, handling both named and tuple struct fields.
/// For named fields, returns the field name.
/// For tuple struct fields, returns the index as a string (e.g., "0", "1").
pub(super) fn get_field_name(field: &Field, index: usize) -> String {
    match &field.ident {
        Some(ident) => ident.to_string(),
        None => index.to_string(),
    }
}

/// Get the field accessor token for a field.
/// For named fields: `self.field_name`
/// For tuple struct fields: `self.0`, `self.1`, etc.
pub(super) fn get_field_accessor(field: &Field, index: usize, use_self: bool) -> TokenStream {
    let prefix = if use_self {
        quote! { self. }
    } else {
        quote! {}
    };

    match &field.ident {
        Some(ident) => quote! { #prefix #ident },
        None => {
            let idx = Index::from(index);
            quote! { #prefix #idx }
        }
    }
}

/// Check if this is a tuple struct (all fields are unnamed)
pub fn is_tuple_struct(fields: &[&Field]) -> bool {
    !fields.is_empty() && fields[0].ident.is_none()
}

thread_local! {
    static MACRO_CONTEXT: RefCell<Option<MacroContext>> = const {RefCell::new(None)};
}

#[derive(Clone)]
struct MacroContext {
    struct_name: String,
    debug_enabled: bool,
    /// Type parameter names extracted from the struct/enum generics (e.g., "C", "T", "E")
    type_params: std::collections::HashSet<String>,
}

/// Set the macro context with struct name, debug flag, and type parameters.
///
/// `type_params` should contain the names of all type parameters from the struct/enum
/// generics (e.g., for `struct Vote<C: RaftTypeConfig>`, pass `{"C"}`).
pub(super) fn set_struct_context(
    name: &str,
    debug_enabled: bool,
    type_params: std::collections::HashSet<String>,
) {
    MACRO_CONTEXT.with(|ctx| {
        *ctx.borrow_mut() = Some(MacroContext {
            struct_name: name.to_string(),
            debug_enabled,
            type_params,
        });
    });
}

pub(super) fn clear_struct_context() {
    MACRO_CONTEXT.with(|ctx| {
        *ctx.borrow_mut() = None;
    });
}

pub(super) fn get_struct_name() -> Option<String> {
    MACRO_CONTEXT.with(|ctx| ctx.borrow().as_ref().map(|c| c.struct_name.clone()))
}

pub(super) fn is_debug_enabled() -> bool {
    MACRO_CONTEXT.with(|ctx| {
        ctx.borrow()
            .as_ref()
            .map(|c| c.debug_enabled)
            .unwrap_or(false)
    })
}

/// Check if a type name is a type parameter of the current struct/enum.
pub(super) fn is_type_parameter(name: &str) -> bool {
    MACRO_CONTEXT.with(|ctx| {
        ctx.borrow()
            .as_ref()
            .map(|c| c.type_params.contains(name))
            .unwrap_or(false)
    })
}

pub(super) fn contains_trait_object(ty: &Type) -> bool {
    match ty {
        Type::TraitObject(_) => true,
        Type::Path(type_path) => {
            if is_box_dyn_trait(ty).is_some()
                || is_rc_dyn_trait(ty).is_some()
                || is_arc_dyn_trait(ty).is_some()
            {
                return true;
            }

            if let Some(seg) = type_path.path.segments.last() {
                if let PathArguments::AngleBracketed(args) = &seg.arguments {
                    return args.args.iter().any(|arg| {
                        if let GenericArgument::Type(inner_ty) = arg {
                            contains_trait_object(inner_ty)
                        } else {
                            false
                        }
                    });
                }
            }
            false
        }
        _ => false,
    }
}

pub(super) struct WrapperTypes {
    pub wrapper_ty: Ident,
    pub trait_ident: Ident,
}

pub(super) fn create_wrapper_types_rc(trait_name: &str) -> WrapperTypes {
    WrapperTypes {
        wrapper_ty: format_ident!("{}Rc", trait_name),
        trait_ident: format_ident!("{}", trait_name),
    }
}

pub(super) fn create_wrapper_types_arc(trait_name: &str) -> WrapperTypes {
    WrapperTypes {
        wrapper_ty: format_ident!("{}Arc", trait_name),
        trait_ident: format_ident!("{}", trait_name),
    }
}

#[allow(dead_code)]
pub(super) enum StructField {
    BoxDyn,
    RcDyn(String),
    ArcDyn(String),
    VecBox(String),
    VecRc(String),
    VecArc(String),
    HashMapBox(Box<Type>, String),
    HashMapRc(Box<Type>, String),
    HashMapArc(Box<Type>, String),
    ContainsTraitObject,
    Forward,
    None,
}

fn is_forward_field(ty: &Type) -> bool {
    let struct_name = match get_struct_name() {
        Some(name) => name,
        None => return false,
    };
    is_forward_field_internal(ty, &struct_name)
}

fn is_forward_field_internal(ty: &Type, struct_name: &str) -> bool {
    match ty {
        Type::TraitObject(_) => true,

        Type::Path(type_path) => {
            if let Some(seg) = type_path.path.segments.last() {
                // Direct match: type is the struct itself
                if seg.ident == struct_name {
                    return true;
                }

                // Special cases for weak pointers
                if seg.ident == "RcWeak" || seg.ident == "ArcWeak" {
                    return true;
                }

                // Check smart pointers: Rc<T> / Arc<T>
                // Only return true if:
                // 1. Inner type is Rc<dyn Any> (polymorphic)
                // 2. Inner type references the containing struct (forward reference)
                if seg.ident == "Rc" || seg.ident == "Arc" {
                    if let PathArguments::AngleBracketed(args) = &seg.arguments {
                        if let Some(GenericArgument::Type(inner_ty)) = args.args.first() {
                            match inner_ty {
                                // Inner type is trait object
                                Type::TraitObject(trait_obj) => {
                                    if trait_obj
                                        .bounds
                                        .iter()
                                        .any(|b| b.to_token_stream().to_string() == "Any")
                                    {
                                        // Rc<dyn Any> → return true
                                        return true;
                                    } else {
                                        // Rc<dyn SomethingElse> → return false
                                        return false;
                                    }
                                }
                                // Inner type is not a trait object - recursively check
                                // if it references the containing struct
                                _ => {
                                    return is_forward_field_internal(inner_ty, struct_name);
                                }
                            }
                        }
                    }
                }

                // Recursively check other generic args
                if let PathArguments::AngleBracketed(args) = &seg.arguments {
                    for arg in &args.args {
                        if let GenericArgument::Type(inner_ty) = arg {
                            if is_forward_field_internal(inner_ty, struct_name) {
                                return true;
                            }
                        }
                    }
                }
            }

            false
        }

        _ => false,
    }
}

pub(super) fn classify_trait_object_field(ty: &Type) -> StructField {
    // Check collections FIRST - they should be handled as collections, not forward refs
    // This is important because is_forward_field would match Vec<Box<dyn Any>> as forward
    if let Some(collection_info) = detect_collection_with_trait_object(ty) {
        return match collection_info {
            CollectionTraitInfo::VecBox(t) => StructField::VecBox(t),
            CollectionTraitInfo::VecRc(t) => StructField::VecRc(t),
            CollectionTraitInfo::VecArc(t) => StructField::VecArc(t),
            CollectionTraitInfo::HashMapBox(k, t) => StructField::HashMapBox(k, t),
            CollectionTraitInfo::HashMapRc(k, t) => StructField::HashMapRc(k, t),
            CollectionTraitInfo::HashMapArc(k, t) => StructField::HashMapArc(k, t),
        };
    }
    if is_forward_field(ty) {
        return StructField::Forward;
    }
    if let Some((_, _)) = is_box_dyn_trait(ty) {
        return StructField::BoxDyn;
    }
    if let Some((_, trait_name)) = is_rc_dyn_trait(ty) {
        return StructField::RcDyn(trait_name);
    }
    if let Some((_, trait_name)) = is_arc_dyn_trait(ty) {
        return StructField::ArcDyn(trait_name);
    }
    if contains_trait_object(ty) {
        return StructField::ContainsTraitObject;
    }
    StructField::None
}

#[derive(Debug)]
pub(super) struct TypeNode {
    /// Simple type name, used for type matching (e.g., "LeaderId", "Vec")
    pub name: String,
    /// Full type path string, used for code generation (e.g., "C::LeaderId")
    pub full_path: String,
    pub generics: Vec<TypeNode>,
    /// For arrays, store the original type string "[T; N]" to preserve length info
    pub original_type_str: Option<String>,
}

pub(super) fn try_primitive_vec_type(node: &TypeNode) -> Option<TokenStream> {
    if node.name != "Vec" {
        return None;
    }
    let child = node.generics.first()?;
    for (ty_name, type_id, _) in PRIMITIVE_ARRAY_TYPE_MAP {
        if child.name == *ty_name {
            return Some(quote! { #type_id });
        }
    }
    None
}

pub(super) fn try_vec_of_option_primitive(node: &TypeNode) -> Option<TokenStream> {
    if node.name != "Vec" {
        return None;
    }
    let child = node.generics.first()?;
    if child.name != "Option" {
        return None;
    }
    let grandchild = child.generics.first()?;
    for (ty_name, _, _) in PRIMITIVE_ARRAY_TYPE_MAP {
        if grandchild.name == *ty_name {
            return Some(quote! {
                compile_error!("Vec<Option<primitive>> is not allowed!");
            });
        }
    }

    None
}

impl fmt::Display for TypeNode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.name == "Tuple" {
            // Format as Rust tuple syntax: (T1, T2, T3)
            write!(
                f,
                "({})",
                self.generics
                    .iter()
                    .map(|g| g.to_string())
                    .collect::<Vec<_>>()
                    .join(", ")
            )
        } else if self.name == "Array" {
            // For arrays, use the original type string if available (e.g., "[f32; 4]")
            if let Some(ref original) = self.original_type_str {
                write!(f, "{}", original)
            } else {
                // Fallback - shouldn't happen if properly constructed
                write!(f, "Array")
            }
        } else if self.generics.is_empty() {
            // Use full_path to preserve associated type paths like C::LeaderId
            write!(f, "{}", self.full_path)
        } else {
            // Use full_path for the base type, recursively format generics
            write!(
                f,
                "{}<{}>",
                self.full_path,
                self.generics
                    .iter()
                    .map(|g| g.to_string())
                    .collect::<Vec<_>>()
                    .join(",")
            )
        }
    }
}

pub(super) fn extract_type_name(ty: &Type) -> String {
    if let Type::Path(type_path) = ty {
        type_path.path.segments.last().unwrap().ident.to_string()
    } else if matches!(ty, Type::TraitObject(_)) {
        "TraitObject".to_string()
    } else if matches!(ty, Type::Tuple(_)) {
        "Tuple".to_string()
    } else if matches!(ty, Type::Array(_)) {
        "Array".to_string()
    } else {
        quote!(#ty).to_string()
    }
}

/// Extracts the full type path string, preserving associated types like `C::LeaderId`
pub(super) fn extract_full_type_path(ty: &Type) -> String {
    if let Type::Path(type_path) = ty {
        // Build the full path from all segments without generic arguments
        type_path
            .path
            .segments
            .iter()
            .map(|seg| seg.ident.to_string())
            .collect::<Vec<_>>()
            .join("::")
    } else if matches!(ty, Type::TraitObject(_)) {
        "TraitObject".to_string()
    } else if matches!(ty, Type::Tuple(_)) {
        "Tuple".to_string()
    } else if matches!(ty, Type::Array(_)) {
        "Array".to_string()
    } else {
        quote!(#ty).to_string()
    }
}

#[allow(dead_code)]
pub(super) fn is_option(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(seg) = type_path.path.segments.last() {
            if seg.ident == "Option" {
                return true;
            }
        }
    }
    false
}

pub(super) fn parse_generic_tree(ty: &Type) -> TypeNode {
    // Handle trait objects specially - they can't be parsed as normal types
    if matches!(ty, Type::TraitObject(_)) {
        return TypeNode {
            name: "TraitObject".to_string(),
            full_path: "TraitObject".to_string(),
            generics: vec![],
            original_type_str: None,
        };
    }

    // Handle tuples - make child generics empty
    if let Type::Tuple(_tuple) = ty {
        return TypeNode {
            name: "Tuple".to_string(),
            full_path: "Tuple".to_string(),
            generics: vec![],
            original_type_str: None,
        };
    }

    // Handle arrays - extract element type and preserve original type string
    if let Type::Array(array) = ty {
        let elem_node = parse_generic_tree(&array.elem);
        // Preserve the original type string including the length (e.g., "[f32; 4]")
        let original_type_str = quote!(#ty).to_string().replace(' ', "");
        return TypeNode {
            name: "Array".to_string(),
            full_path: "Array".to_string(),
            generics: vec![elem_node],
            original_type_str: Some(original_type_str),
        };
    }

    let name = extract_type_name(ty);
    let full_path = extract_full_type_path(ty);

    let generics = if let Type::Path(type_path) = ty {
        if let PathArguments::AngleBracketed(args) =
            &type_path.path.segments.last().unwrap().arguments
        {
            args.args
                .iter()
                .filter_map(|arg| {
                    if let GenericArgument::Type(ty) = arg {
                        Some(parse_generic_tree(ty))
                    } else {
                        None
                    }
                })
                .collect()
        } else {
            vec![]
        }
    } else {
        vec![]
    };
    TypeNode {
        name,
        full_path,
        generics,
        original_type_str: None,
    }
}

pub(super) fn generic_tree_to_tokens(node: &TypeNode) -> TokenStream {
    // Special handling for type parameters (e.g., C, T, E)
    // Type parameters should use UNKNOWN type ID since they are not concrete types.
    // This prevents `C::LeaderId` generating code like `<C as Serializer>::fory_get_type_id()` which
    // would require the type parameter to implement Serializer.
    if is_type_parameter(&node.name) {
        return quote! {
            fory_core::meta::FieldType::new(
                fory_core::types::TypeId::UNKNOWN as u32,
                true,
                vec![]
            )
        };
    }

    // Special handling for tuples: always use FieldType { LIST, nullable: true, generics: vec![UNKNOWN] }
    if node.name == "Tuple" {
        return quote! {
            fory_core::meta::FieldType::new(
                fory_core::types::TypeId::LIST as u32,
                true,
                vec![fory_core::meta::FieldType::new(
                    fory_core::types::TypeId::UNKNOWN as u32,
                    true,
                    vec![]
                )]
            )
        };
    }

    // Special handling for arrays: treat them as lists with element type generic
    if node.name == "Array" {
        if let Some(elem_node) = node.generics.first() {
            let elem_token = generic_tree_to_tokens(elem_node);
            // Check if element is primitive to determine the correct type ID
            let is_primitive_elem = PRIMITIVE_TYPE_NAMES.contains(&elem_node.name.as_str());
            if is_primitive_elem {
                // For primitive arrays, use primitive array type ID
                let type_id_token = match elem_node.name.as_str() {
                    "bool" => quote! { fory_core::types::TypeId::BOOL_ARRAY as u32 },
                    "i8" => quote! { fory_core::types::TypeId::INT8_ARRAY as u32 },
                    "i16" => quote! { fory_core::types::TypeId::INT16_ARRAY as u32 },
                    "i32" => quote! { fory_core::types::TypeId::INT32_ARRAY as u32 },
                    "i64" => quote! { fory_core::types::TypeId::INT64_ARRAY as u32 },
                    "i128" => quote! { fory_core::types::TypeId::INT128_ARRAY as u32 },
                    "float16" => quote! { fory_core::types::TypeId::FLOAT16_ARRAY as u32 },
                    "f32" => quote! { fory_core::types::TypeId::FLOAT32_ARRAY as u32 },
                    "f64" => quote! { fory_core::types::TypeId::FLOAT64_ARRAY as u32 },
                    "u8" => quote! { fory_core::types::TypeId::BINARY as u32 },
                    "u16" => quote! { fory_core::types::TypeId::UINT16_ARRAY as u32 },
                    "u32" => quote! { fory_core::types::TypeId::UINT32_ARRAY as u32 },
                    "u64" => quote! { fory_core::types::TypeId::UINT64_ARRAY as u32 },
                    "u128" => quote! { fory_core::types::TypeId::U128_ARRAY as u32 },
                    _ => quote! { fory_core::types::TypeId::LIST as u32 },
                };
                return quote! {
                    fory_core::meta::FieldType::new(
                        #type_id_token,
                        false,
                        vec![]
                    )
                };
            } else {
                // For non-primitive arrays, use LIST type ID with element type as generic
                return quote! {
                    fory_core::meta::FieldType::new(
                        fory_core::types::TypeId::LIST as u32,
                        false,
                        vec![#elem_token]
                    )
                };
            }
        } else {
            // Array without element type info - shouldn't happen
            return quote! { compile_error!("Array missing element type"); };
        }
    }

    // If Option, unwrap it before generating children
    let (nullable, base_node) = if node.name == "Option" {
        if let Some(inner) = node.generics.first() {
            if inner.name == "Option" {
                return quote! { compile_error!("Nested adjacent Option is not allowed!"); };
            }
            // Special handling for Option<Tuple>
            if inner.name == "Tuple" {
                return quote! {
                    fory_core::meta::FieldType::new(
                        fory_core::types::TypeId::LIST as u32,
                        true,
                        vec![fory_core::meta::FieldType::new(
                            fory_core::types::TypeId::UNKNOWN as u32,
                            true,
                            vec![]
                        )]
                    )
                };
            }
            // Unwrap Option and propagate parsing
            (true, inner)
        } else {
            return quote! { compile_error!("Missing Option inner type"); };
        }
    } else {
        (!PRIMITIVE_TYPE_NAMES.contains(&node.name.as_str()), node)
    };

    // If Rc or Arc, unwrap to inner type - these are reference wrappers
    // that don't add type info to the field type (handled by track_ref flag)
    let base_node = if base_node.name == "Rc" || base_node.name == "Arc" {
        if let Some(inner) = base_node.generics.first() {
            inner
        } else {
            base_node
        }
    } else {
        base_node
    };

    // `Vec<Option<primitive>>` rule stays as is
    if let Some(ts) = try_vec_of_option_primitive(base_node) {
        return ts;
    }

    // Try primitive Vec type
    let primitive_vec = try_primitive_vec_type(base_node);

    // Recursively generate children token streams
    let children_tokens: Vec<TokenStream> = if primitive_vec.is_none() {
        base_node
            .generics
            .iter()
            .map(generic_tree_to_tokens)
            .collect()
    } else {
        vec![]
    };

    // Build the syn::Type from the DISPLAY of base_node, not the original node if Option
    let ty: syn::Type = syn::parse_str(&base_node.to_string()).unwrap();

    // Get type ID
    let get_type_id = if let Some(ts) = primitive_vec {
        ts
    } else {
        quote! {
            <#ty as fory_core::serializer::Serializer>::fory_get_type_id(type_resolver)? as u32
        }
    };

    quote! {
        {
            let mut type_id = #get_type_id;
            let mut user_type_id = u32::MAX;
            let mut generics = vec![#(#children_tokens),*] as Vec<fory_core::meta::FieldType>;
            // For tuples and sets, if no generic info is available, add UNKNOWN element
            // This handles type aliases to tuples where we can't detect the tuple at macro time
            if (type_id == fory_core::types::TypeId::LIST as u32
                || type_id == fory_core::types::TypeId::SET as u32)
                && generics.is_empty() {
                generics.push(fory_core::meta::FieldType::new(
                    fory_core::types::TypeId::UNKNOWN as u32,
                    true,
                    vec![]
                ));
            }
            let is_custom = !fory_core::types::is_internal_type(type_id);
            if is_custom {
                let type_info = <#ty as fory_core::serializer::Serializer>::fory_get_type_info(type_resolver)?;
                type_id = type_info.get_type_id() as u32;
                user_type_id = type_info.get_user_type_id();
                if type_id == fory_core::types::TypeId::TYPED_UNION as u32
                    || type_id == fory_core::types::TypeId::NAMED_UNION as u32 {
                    type_id = fory_core::types::TypeId::UNION as u32;
                    user_type_id = u32::MAX;
                }
                if type_resolver.is_xlang() && generics.len() > 0 {
                    return Err(fory_core::error::Error::unsupported("serialization of generic structs and enums is not supported in xlang mode"));
                } else {
                    generics = vec![];
                }
            } else if type_id == fory_core::types::TypeId::TYPED_UNION as u32
                || type_id == fory_core::types::TypeId::NAMED_UNION as u32 {
                type_id = fory_core::types::TypeId::UNION as u32;
            }
            fory_core::meta::FieldType {
                type_id,
                user_type_id,
                nullable: #nullable,
                track_ref: false,
                generics,
            }
        }
    }
}

type FieldGroup = Vec<(String, String, u32)>;
type FieldGroups = (
    FieldGroup,
    FieldGroup,
    FieldGroup,
    FieldGroup,
    FieldGroup,
    FieldGroup,
    FieldGroup,
);

fn extract_option_inner(s: &str) -> Option<&str> {
    s.strip_prefix("Option<")?.strip_suffix(">")
}

const PRIMITIVE_TYPE_NAMES: [&str; 14] = [
    "bool", "i8", "i16", "i32", "i64", "i128", "float16", "f32", "f64", "u8", "u16", "u32", "u64",
    "u128",
];

fn get_primitive_type_id(ty: &str) -> u32 {
    match ty {
        "bool" => TypeId::BOOL as u32,
        "i8" => TypeId::INT8 as u32,
        "i16" => TypeId::INT16 as u32,
        // Use VARINT32 for i32 to match Java xlang mode and Rust type resolver registration
        "i32" => TypeId::VARINT32 as u32,
        // Use VARINT64 for i64 to match Java xlang mode and Rust type resolver registration
        "i64" => TypeId::VARINT64 as u32,
        "float16" => TypeId::FLOAT16 as u32,
        "f32" => TypeId::FLOAT32 as u32,
        "f64" => TypeId::FLOAT64 as u32,
        "u8" => TypeId::UINT8 as u32,
        "u16" => TypeId::UINT16 as u32,
        // Use VAR_UINT32 for u32 to match Rust type resolver registration
        "u32" => TypeId::VAR_UINT32 as u32,
        // Use VAR_UINT64 for u64 to match Rust type resolver registration
        "u64" => TypeId::VAR_UINT64 as u32,
        "u128" => TypeId::U128 as u32,
        "i128" => TypeId::INT128 as u32,
        _ => unreachable!("Unknown primitive type: {}", ty),
    }
}

pub(super) fn is_primitive_type(ty: &str) -> bool {
    PRIMITIVE_TYPE_NAMES.contains(&ty)
}

/// Mapping of primitive type names to their writer and reader method names
/// Order: (type_name, writer_method, reader_method)
static PRIMITIVE_IO_METHODS: &[(&str, &str, &str)] = &[
    ("bool", "write_bool", "read_bool"),
    ("i8", "write_i8", "read_i8"),
    ("i16", "write_i16", "read_i16"),
    ("i32", "write_varint32", "read_varint32"),
    ("i64", "write_varint64", "read_varint64"),
    ("f32", "write_f32", "read_f32"),
    ("f64", "write_f64", "read_f64"),
    ("u8", "write_u8", "read_u8"),
    ("u16", "write_u16", "read_u16"),
    ("u32", "write_u32", "read_u32"),
    ("u64", "write_u64", "read_u64"),
    ("usize", "write_usize", "read_usize"),
    ("u128", "write_u128", "read_u128"),
];

/// Check if a type is a direct primitive type (numeric or String, not wrapped in Option, Vec, etc.)
pub(super) fn is_direct_primitive_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(seg) = type_path.path.segments.last() {
            // Check if it's a simple type path without generics
            if matches!(seg.arguments, PathArguments::None) {
                let type_name = seg.ident.to_string();
                // Check for String type
                if type_name == "String" {
                    return true;
                }
                // Check for numeric primitive types
                return PRIMITIVE_IO_METHODS
                    .iter()
                    .any(|(name, _, _)| *name == type_name.as_str());
            }
        }
    }
    false
}

/// Get the writer method name for a primitive numeric type
/// Panics if type_name is not a primitive type
pub(super) fn get_primitive_writer_method(type_name: &str) -> &'static str {
    PRIMITIVE_IO_METHODS
        .iter()
        .find(|(name, _, _)| *name == type_name)
        .map(|(_, writer, _)| *writer)
        .unwrap_or_else(|| panic!("type_name '{}' must be a primitive type", type_name))
}

/// Get the writer method name for a primitive numeric type, considering encoding attributes.
///
/// For i32 fields:
/// - type_id=VARINT32 (default): write_varint32
/// - type_id=INT32: write_i32 (fixed 4-byte)
///
/// For u32 fields:
/// - type_id=VARINT32/VAR_UINT32 (default): write_var_uint32
/// - type_id=INT32/UINT32: write_u32 (fixed 4-byte)
///
/// For u64 fields:
/// - type_id=VARINT32/VAR_UINT64 (default): write_var_uint64
/// - type_id=INT32/UINT64: write_u64 (fixed 8-byte)
/// - type_id=TAGGED_UINT64: write_tagged_u64
pub(super) fn get_primitive_writer_method_with_encoding(
    type_name: &str,
    meta: &super::field_meta::ForyFieldMeta,
) -> &'static str {
    use fory_core::types::TypeId;

    // Handle i32 with type_id
    if type_name == "i32" {
        if let Some(type_id) = meta.type_id {
            if type_id == TypeId::INT32 as i16 {
                return "write_i32"; // Fixed 4-byte encoding
            }
        }
        return "write_varint32"; // Variable-length (default)
    }

    // Handle u32 with type_id
    if type_name == "u32" {
        if let Some(type_id) = meta.type_id {
            if type_id == TypeId::INT32 as i16 || type_id == TypeId::UINT32 as i16 {
                return "write_u32"; // Fixed 4-byte encoding
            }
        }
        return "write_var_uint32"; // Variable-length (default)
    }

    // Handle u64 with type_id
    if type_name == "u64" {
        if let Some(type_id) = meta.type_id {
            if type_id == TypeId::INT32 as i16 || type_id == TypeId::UINT64 as i16 {
                return "write_u64"; // Fixed 8-byte encoding
            } else if type_id == TypeId::TAGGED_UINT64 as i16 {
                return "write_tagged_u64"; // Tagged variable-length
            }
        }
        return "write_var_uint64"; // Variable-length (default)
    }

    // For other types, use the default method from PRIMITIVE_IO_METHODS
    get_primitive_writer_method(type_name)
}

/// Get the reader method name for a primitive numeric type
/// Panics if type_name is not a primitive type
pub(super) fn get_primitive_reader_method(type_name: &str) -> &'static str {
    PRIMITIVE_IO_METHODS
        .iter()
        .find(|(name, _, _)| *name == type_name)
        .map(|(_, _, reader)| *reader)
        .unwrap_or_else(|| panic!("type_name '{}' must be a primitive type", type_name))
}

/// Get the reader method name for a primitive numeric type, considering encoding attributes.
///
/// For i32 fields:
/// - type_id=VARINT32 (default): read_varint32
/// - type_id=INT32: read_i32 (fixed 4-byte)
///
/// For u32 fields:
/// - type_id=VARINT32/VAR_UINT32 (default): read_varuint32
/// - type_id=INT32/UINT32: read_u32 (fixed 4-byte)
///
/// For u64 fields:
/// - type_id=VARINT32/VAR_UINT64 (default): read_varuint64
/// - type_id=INT32/UINT64: read_u64 (fixed 8-byte)
/// - type_id=TAGGED_UINT64: read_tagged_u64
pub(super) fn get_primitive_reader_method_with_encoding(
    type_name: &str,
    meta: &super::field_meta::ForyFieldMeta,
) -> &'static str {
    use fory_core::types::TypeId;

    // Handle i32 with type_id
    if type_name == "i32" {
        if let Some(type_id) = meta.type_id {
            if type_id == TypeId::INT32 as i16 {
                return "read_i32"; // Fixed 4-byte encoding
            }
        }
        return "read_varint32"; // Variable-length (default)
    }

    // Handle u32 with type_id
    if type_name == "u32" {
        if let Some(type_id) = meta.type_id {
            if type_id == TypeId::INT32 as i16 || type_id == TypeId::UINT32 as i16 {
                return "read_u32"; // Fixed 4-byte encoding
            }
        }
        return "read_varuint32"; // Variable-length (default)
    }

    // Handle u64 with type_id
    if type_name == "u64" {
        if let Some(type_id) = meta.type_id {
            if type_id == TypeId::INT32 as i16 || type_id == TypeId::UINT64 as i16 {
                return "read_u64"; // Fixed 8-byte encoding
            } else if type_id == TypeId::TAGGED_UINT64 as i16 {
                return "read_tagged_u64"; // Tagged variable-length
            }
        }
        return "read_varuint64"; // Variable-length (default)
    }

    // For other types, use the default method from PRIMITIVE_IO_METHODS
    get_primitive_reader_method(type_name)
}

/// Check if a type is `Option<i32>`, `Option<u32>`, or `Option<u64>` that needs encoding-aware handling
/// based on the field metadata (type_id attribute).
pub(super) fn is_option_encoding_primitive(
    ty: &Type,
    meta: &super::field_meta::ForyFieldMeta,
) -> bool {
    if let Some(inner_name) = get_option_inner_primitive_name(ty) {
        // For i32/u32/u64, check if type_id is set
        if (inner_name == "i32" || inner_name == "u32" || inner_name == "u64")
            && meta.type_id.is_some()
        {
            return true;
        }
    }
    false
}

/// Get the inner primitive name if the type is `Option<primitive>`
/// Returns Some("u32"), Some("u64"), etc. for `Option<u32>`, `Option<u64>`, etc.
pub(super) fn get_option_inner_primitive_name(ty: &Type) -> Option<&'static str> {
    use syn::PathArguments;
    if let Type::Path(type_path) = ty {
        if let Some(seg) = type_path.path.segments.last() {
            if seg.ident == "Option" {
                if let PathArguments::AngleBracketed(args) = &seg.arguments {
                    if let Some(syn::GenericArgument::Type(Type::Path(inner_path))) =
                        args.args.first()
                    {
                        if let Some(inner_seg) = inner_path.path.segments.last() {
                            let inner_name = inner_seg.ident.to_string();
                            // Return static string for known primitives
                            return PRIMITIVE_IO_METHODS
                                .iter()
                                .find(|(name, _, _)| *name == inner_name.as_str())
                                .map(|(name, _, _)| *name);
                        }
                    }
                }
            }
        }
    }
    None
}

pub(crate) fn get_type_id_by_type_ast(ty: &Type) -> u32 {
    let ty_str: String = ty
        .to_token_stream()
        .to_string()
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>();
    get_type_id_by_name(&ty_str)
}

/// Get the type ID for a given type string.
///
/// Returns:
/// - `type_id` for known types (primitives, internal types, collections)
/// - `UNKNOWN` for unknown/user-defined types
pub(crate) fn get_type_id_by_name(ty: &str) -> u32 {
    let ty = extract_option_inner(ty).unwrap_or(ty);
    // Check primitive types
    if PRIMITIVE_TYPE_NAMES.contains(&ty) {
        return get_primitive_type_id(ty);
    }

    // Check internal types
    match ty {
        "String" => return TypeId::STRING as u32,
        "NaiveDate" => return TypeId::DATE as u32,
        "NaiveDateTime" => return TypeId::TIMESTAMP as u32,
        "Duration" => return TypeId::DURATION as u32,
        "Decimal" => return TypeId::DECIMAL as u32,
        "Vec<u8>" | "bytes" => return TypeId::BINARY as u32,
        _ => {}
    }

    // Check primitive arrays (Vec)
    match ty {
        "Vec<bool>" => return TypeId::BOOL_ARRAY as u32,
        "Vec<i8>" => return TypeId::INT8_ARRAY as u32,
        "Vec<i16>" => return TypeId::INT16_ARRAY as u32,
        "Vec<i32>" => return TypeId::INT32_ARRAY as u32,
        "Vec<i64>" => return TypeId::INT64_ARRAY as u32,
        "Vec<i128>" => return TypeId::INT128_ARRAY as u32,
        "Vec<float16>" => return TypeId::FLOAT16_ARRAY as u32,
        "Vec<f32>" => return TypeId::FLOAT32_ARRAY as u32,
        "Vec<f64>" => return TypeId::FLOAT64_ARRAY as u32,
        "Vec<u16>" => return TypeId::UINT16_ARRAY as u32,
        "Vec<u32>" => return TypeId::UINT32_ARRAY as u32,
        "Vec<u64>" => return TypeId::UINT64_ARRAY as u32,
        "Vec<u128>" => return TypeId::U128_ARRAY as u32,
        _ => {}
    }

    // Check primitive arrays (fixed-size arrays [T; N])
    // These will be serialized similarly to Vec but with fixed size
    if ty.starts_with('[') && ty.contains(';') {
        // Extract the element type from [T; N]
        if let Some(elem_ty) = ty.strip_prefix('[').and_then(|s| s.split(';').next()) {
            match elem_ty {
                "bool" => return TypeId::BOOL_ARRAY as u32,
                "i8" => return TypeId::INT8_ARRAY as u32,
                "i16" => return TypeId::INT16_ARRAY as u32,
                "i32" => return TypeId::INT32_ARRAY as u32,
                "i64" => return TypeId::INT64_ARRAY as u32,
                "i128" => return TypeId::INT128_ARRAY as u32,
                "float16" => return TypeId::FLOAT16_ARRAY as u32,
                "f32" => return TypeId::FLOAT32_ARRAY as u32,
                "f64" => return TypeId::FLOAT64_ARRAY as u32,
                "u16" => return TypeId::UINT16_ARRAY as u32,
                "u32" => return TypeId::UINT32_ARRAY as u32,
                "u64" => return TypeId::UINT64_ARRAY as u32,
                "u128" => return TypeId::U128_ARRAY as u32,
                _ => {
                    // Non-primitive array elements, treat as LIST
                    return TypeId::LIST as u32;
                }
            }
        }
    }

    // Check collection types
    if ty.starts_with("Vec<")
        || ty.starts_with("VecDeque<")
        || ty.starts_with("LinkedList<")
        || ty.starts_with("BinaryHeap<")
    {
        return TypeId::LIST as u32;
    }

    if ty.starts_with("HashSet<") || ty.starts_with("BTreeSet<") {
        return TypeId::SET as u32;
    }

    if ty.starts_with("HashMap<") || ty.starts_with("BTreeMap<") {
        return TypeId::MAP as u32;
    }

    // Check tuple types (represented as "Tuple" by extract_type_name or starts with '(')
    if ty == "Tuple" || ty.starts_with('(') {
        return TypeId::LIST as u32;
    }

    // Unknown type
    TypeId::UNKNOWN as u32
}

fn get_primitive_type_size(type_id_num: u32) -> i32 {
    if type_id_num > u8::MAX as u32 {
        return 0;
    }
    let type_id = TypeId::try_from(type_id_num as u8).unwrap();
    match type_id {
        TypeId::BOOL => 1,
        TypeId::INT8 => 1,
        TypeId::INT16 => 2,
        TypeId::INT32 => 4,
        TypeId::VARINT32 => 4,
        TypeId::INT64 => 8,
        TypeId::VARINT64 => 8,
        TypeId::TAGGED_INT64 => 8,
        TypeId::FLOAT8 => 1,
        TypeId::FLOAT16 => 2,
        TypeId::BFLOAT16 => 2,
        TypeId::FLOAT32 => 4,
        TypeId::FLOAT64 => 8,
        TypeId::INT128 => 16,
        TypeId::UINT8 => 1,
        TypeId::UINT16 => 2,
        TypeId::UINT32 => 4,
        TypeId::VAR_UINT32 => 4,
        TypeId::UINT64 => 8,
        TypeId::VAR_UINT64 => 8,
        TypeId::TAGGED_UINT64 => 8,
        TypeId::U128 => 16,
        TypeId::USIZE => std::mem::size_of::<usize>() as i32,
        TypeId::ISIZE => std::mem::size_of::<isize>() as i32,
        _ => unreachable!(),
    }
}

fn is_compress(type_id: u32) -> bool {
    // Variable-length and tagged types are marked as compressible
    // This must match Java's Types.isCompressedType() for xlang compatibility
    [
        // Signed compressed types
        TypeId::VARINT32 as u32,
        TypeId::VARINT64 as u32,
        TypeId::TAGGED_INT64 as u32,
        // Unsigned compressed types
        TypeId::VAR_UINT32 as u32,
        TypeId::VAR_UINT64 as u32,
        TypeId::TAGGED_UINT64 as u32,
    ]
    .contains(&type_id)
}

fn is_internal_type_id(type_id: u32) -> bool {
    [
        TypeId::STRING as u32,
        TypeId::DATE as u32,
        TypeId::TIMESTAMP as u32,
        TypeId::DURATION as u32,
        TypeId::DECIMAL as u32,
        TypeId::BINARY as u32,
        TypeId::BOOL_ARRAY as u32,
        TypeId::INT8_ARRAY as u32,
        TypeId::INT16_ARRAY as u32,
        TypeId::INT32_ARRAY as u32,
        TypeId::INT64_ARRAY as u32,
        TypeId::INT128_ARRAY as u32,
        TypeId::FLOAT8_ARRAY as u32,
        TypeId::FLOAT16_ARRAY as u32,
        TypeId::BFLOAT16_ARRAY as u32,
        TypeId::FLOAT32_ARRAY as u32,
        TypeId::FLOAT64_ARRAY as u32,
        TypeId::UINT16_ARRAY as u32,
        TypeId::UINT32_ARRAY as u32,
        TypeId::UINT64_ARRAY as u32,
        TypeId::U128_ARRAY as u32,
    ]
    .contains(&type_id)
}

/// Group fields into serialization categories while normalizing field names to snake_case.
/// The returned groups preserve the ordering rules required by the serialization layout.
fn group_fields_by_type(fields: &[&Field]) -> FieldGroups {
    use super::field_meta::parse_field_meta;

    let mut primitive_fields = Vec::new();
    let mut nullable_primitive_fields = Vec::new();
    let mut internal_type_fields = Vec::new();
    let mut list_fields = Vec::new();
    let mut set_fields = Vec::new();
    let mut map_fields = Vec::new();
    let mut other_fields = Vec::new();

    // First handle Forward fields separately to avoid borrow checker issues
    for (idx, field) in fields.iter().enumerate() {
        if is_forward_field(&field.ty) {
            let raw_ident = get_field_name(field, idx);
            let ident = to_snake_case(&raw_ident);
            // Forward fields don't have explicit IDs; sort by name.
            other_fields.push((ident.clone(), ident, TypeId::UNKNOWN as u32));
        }
    }

    for (idx, field) in fields.iter().enumerate() {
        let raw_ident = get_field_name(field, idx);
        let ident = to_snake_case(&raw_ident);

        // Skip if already handled as Forward field
        if is_forward_field(&field.ty) {
            continue;
        }

        // Parse field metadata to get encoding attributes and field ID
        let meta = parse_field_meta(field).unwrap_or_default();
        let sort_key = if meta.uses_tag_id() {
            meta.effective_id().to_string()
        } else {
            ident.clone()
        };

        let ty: String = field
            .ty
            .to_token_stream()
            .to_string()
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect::<String>();

        // Closure to group non-option fields, considering encoding attributes
        let mut group_field =
            |ident: String, sort_key: String, ty_str: &str, is_primitive: bool| {
                let base_type_id = get_type_id_by_name(ty_str);
                // Adjust type ID based on encoding attributes for u32/u64 fields
                let type_id = adjust_type_id_for_encoding(base_type_id, &meta);

                // Categorize based on type_id
                if is_primitive {
                    primitive_fields.push((ident, sort_key, type_id));
                } else if is_internal_type_id(type_id) {
                    internal_type_fields.push((ident, sort_key, type_id));
                } else if type_id == TypeId::LIST as u32 {
                    list_fields.push((ident, sort_key, type_id));
                } else if type_id == TypeId::SET as u32 {
                    set_fields.push((ident, sort_key, type_id));
                } else if type_id == TypeId::MAP as u32 {
                    map_fields.push((ident, sort_key, type_id));
                } else {
                    // User-defined type
                    other_fields.push((ident, sort_key, type_id));
                }
            };

        // handle Option<Primitive> specially
        if let Some(inner) = extract_option_inner(&ty) {
            if PRIMITIVE_TYPE_NAMES.contains(&inner) {
                // Get base type ID and adjust for encoding attributes
                let base_type_id = get_primitive_type_id(inner);
                let type_id = adjust_type_id_for_encoding(base_type_id, &meta);
                nullable_primitive_fields.push((ident, sort_key, type_id));
            } else {
                group_field(ident, sort_key, inner, false);
            }
        } else if PRIMITIVE_TYPE_NAMES.contains(&ty.as_str()) {
            group_field(ident, sort_key, &ty, true);
        } else {
            group_field(ident, sort_key, &ty, false);
        }
    }

    fn numeric_sorter(a: &(String, String, u32), b: &(String, String, u32)) -> std::cmp::Ordering {
        let compress_a = is_compress(a.2);
        let compress_b = is_compress(b.2);
        let size_a = get_primitive_type_size(a.2);
        let size_b = get_primitive_type_size(b.2);
        compress_a
            .cmp(&compress_b)
            .then_with(|| size_b.cmp(&size_a))
            // Use descending type_id order to match Java's COMPARATOR_BY_PRIMITIVE_TYPE_ID
            .then_with(|| b.2.cmp(&a.2))
            // Field identifier (tag ID or name) as tie-breaker
            .then_with(|| a.1.cmp(&b.1))
            // Deterministic fallback for duplicate identifiers
            .then_with(|| a.0.cmp(&b.0))
    }

    fn type_id_then_name_sorter(
        a: &(String, String, u32),
        b: &(String, String, u32),
    ) -> std::cmp::Ordering {
        a.2.cmp(&b.2)
            .then_with(|| a.1.cmp(&b.1))
            .then_with(|| a.0.cmp(&b.0))
    }

    fn name_sorter(a: &(String, String, u32), b: &(String, String, u32)) -> std::cmp::Ordering {
        a.1.cmp(&b.1).then_with(|| a.0.cmp(&b.0))
    }

    primitive_fields.sort_by(numeric_sorter);
    nullable_primitive_fields.sort_by(numeric_sorter);
    internal_type_fields.sort_by(type_id_then_name_sorter);
    list_fields.sort_by(name_sorter);
    set_fields.sort_by(name_sorter);
    map_fields.sort_by(name_sorter);
    other_fields.sort_by(name_sorter);

    (
        primitive_fields,
        nullable_primitive_fields,
        internal_type_fields,
        list_fields,
        set_fields,
        map_fields,
        other_fields,
    )
}

pub(crate) fn get_sorted_field_names(fields: &[&Field]) -> Vec<String> {
    // For tuple structs, preserve the original field order.
    // Tuple struct field names are "0", "1", "2", etc., which are positional.
    // Sorting would break schema evolution when adding fields in the middle
    // (e.g., (f64, u8) -> (f64, u8, f64) would change sorted order).
    if is_tuple_struct(fields) {
        return fields
            .iter()
            .enumerate()
            .map(|(idx, field)| get_field_name(field, idx))
            .collect();
    }

    // For named structs, sort by type for optimal memory layout
    let (
        primitive_fields,
        nullable_primitive_fields,
        internal_type_fields,
        list_fields,
        set_fields,
        map_fields,
        other_fields,
    ) = group_fields_by_type(fields);

    let mut all_fields = primitive_fields;
    all_fields.extend(nullable_primitive_fields);
    all_fields.extend(internal_type_fields);
    all_fields.extend(list_fields);
    all_fields.extend(set_fields);
    all_fields.extend(map_fields);
    all_fields.extend(other_fields);

    all_fields.into_iter().map(|(name, _, _)| name).collect()
}

pub(crate) fn get_filtered_fields_iter<'a>(
    fields: &'a [&'a Field],
) -> impl Iterator<Item = &'a Field> {
    fields.iter().filter(|field| !is_skip_field(field)).copied()
}

pub(super) fn get_filtered_source_fields_iter<'a>(
    source_fields: &'a [crate::util::SourceField<'a>],
) -> impl Iterator<Item = &'a crate::util::SourceField<'a>> {
    source_fields.iter().filter(|sf| !is_skip_field(sf.field))
}

pub(super) fn get_sort_fields_ts(fields: &[&Field]) -> TokenStream {
    let filterd_fields: Vec<&Field> = get_filtered_fields_iter(fields).collect();
    let sorted_names = get_sorted_field_names(&filterd_fields);
    let names = sorted_names.iter().map(|name| {
        quote! { #name }
    });
    quote! {
        &[#(#names),*]
    }
}

/// Field metadata for fingerprint computation.
struct FieldFingerprintInfo {
    /// Field name (snake_case) or field ID as string
    name_or_id: String,
    /// Whether the field has explicit nullable=true/false set via #[fory(nullable)]
    explicit_nullable: Option<bool>,
    /// Whether reference tracking is enabled
    track_ref: bool,
    /// The type ID (UNKNOWN for user-defined types including enums/unions)
    type_id: u32,
    /// Whether the field type is `Option<T>`
    is_option_type: bool,
}

/// Adjusts type ID based on encoding attributes for i32/u32/u64 fields.
///
/// The type_id in meta represents the desired encoding:
/// - VARINT32: variable-length for i32/u32
/// - INT32: fixed 4-byte for i32, u32
/// - TAGGED_UINT64: tagged variable-length for u64
fn adjust_type_id_for_encoding(base_type_id: u32, meta: &super::field_meta::ForyFieldMeta) -> u32 {
    // If no explicit type_id is set, use the base type_id
    let Some(explicit_type_id) = meta.type_id else {
        return base_type_id;
    };

    if explicit_type_id == TypeId::UNION as i16 {
        return TypeId::UNION as u32;
    }

    if explicit_type_id == TypeId::INT8_ARRAY as i16
        || explicit_type_id == TypeId::UINT8_ARRAY as i16
    {
        let explicit = explicit_type_id as u32;
        if base_type_id == TypeId::BINARY as u32
            || base_type_id == TypeId::INT8_ARRAY as u32
            || base_type_id == TypeId::UINT8_ARRAY as u32
        {
            return explicit;
        }
    }

    // Handle i32 fields
    if base_type_id == TypeId::VARINT32 as u32 {
        if explicit_type_id == TypeId::INT32 as i16 {
            return TypeId::INT32 as u32; // Fixed 4-byte encoding
        }
        return base_type_id; // VARINT32 (default)
    }

    // Handle u32 fields
    if base_type_id == TypeId::VAR_UINT32 as u32 {
        if explicit_type_id == TypeId::INT32 as i16 {
            return TypeId::UINT32 as u32; // Fixed 4-byte encoding
        }
        return base_type_id; // VAR_UINT32 (default)
    }

    // Handle u64 fields
    if base_type_id == TypeId::VAR_UINT64 as u32 {
        if explicit_type_id == TypeId::INT32 as i16 {
            return TypeId::UINT64 as u32; // Fixed 8-byte encoding
        } else if explicit_type_id == TypeId::TAGGED_UINT64 as i16 {
            return TypeId::TAGGED_UINT64 as u32; // Tagged variable-length
        }
        return base_type_id; // VAR_UINT64 (default)
    }

    base_type_id
}

/// Computes struct fingerprint string at compile time (during proc-macro execution).
///
/// **Fingerprint Format:** `<field_name_or_id>,<type_id>,<ref>,<nullable>;`
/// Fields are sorted by name lexicographically.
fn compute_struct_fingerprint(fields: &[&Field]) -> String {
    use super::field_meta::{classify_field_type, parse_field_meta};

    let mut field_infos: Vec<FieldFingerprintInfo> = Vec::with_capacity(fields.len());

    for (idx, field) in fields.iter().enumerate() {
        let meta = parse_field_meta(field).unwrap_or_default();
        if meta.skip {
            continue;
        }

        let name = get_field_name(field, idx);
        let field_id = meta.effective_id();
        let name_or_id = if field_id >= 0 {
            field_id.to_string()
        } else {
            to_snake_case(&name)
        };

        let type_class = classify_field_type(&field.ty);
        let track_ref = meta.effective_ref(type_class);
        let explicit_nullable = meta.nullable;

        // Get compile-time TypeId, considering encoding attributes for u32/u64 fields
        let base_type_id = get_type_id_by_type_ast(&field.ty);
        let type_id = adjust_type_id_for_encoding(base_type_id, &meta);

        // Check if field type is Option<T>
        let ty_str: String = field
            .ty
            .to_token_stream()
            .to_string()
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect();
        let is_option_type = ty_str.starts_with("Option<");

        field_infos.push(FieldFingerprintInfo {
            name_or_id,
            explicit_nullable,
            track_ref,
            type_id,
            is_option_type,
        });
    }

    // Sort field infos by name_or_id lexicographically (matches Java/C++ behavior)
    field_infos.sort_by(|a, b| a.name_or_id.cmp(&b.name_or_id));

    // Build fingerprint string
    let mut fingerprint = String::new();
    for info in &field_infos {
        let ref_flag = if info.track_ref { "1" } else { "0" };
        let nullable = match info.explicit_nullable {
            Some(true) => true,
            Some(false) => false,
            None => info.is_option_type,
        };
        let nullable_flag = if nullable { "1" } else { "0" };

        // User-defined types (UNKNOWN) and unions use 0 in fingerprint, matching Java behavior
        let effective_type_id = if info.type_id == TypeId::UNKNOWN as u32
            || info.type_id == TypeId::UNION as u32
            || info.type_id == TypeId::TYPED_UNION as u32
            || info.type_id == TypeId::NAMED_UNION as u32
        {
            0
        } else {
            info.type_id
        };

        fingerprint.push_str(&info.name_or_id);
        fingerprint.push(',');
        fingerprint.push_str(&effective_type_id.to_string());
        fingerprint.push(',');
        fingerprint.push_str(ref_flag);
        fingerprint.push(',');
        fingerprint.push_str(nullable_flag);
        fingerprint.push(';');
    }

    fingerprint
}

/// Generates TokenStream for struct version hash (computed at compile time).
pub(crate) fn gen_struct_version_hash_ts(fields: &[&Field]) -> TokenStream {
    let fingerprint = compute_struct_fingerprint(fields);
    let (hash, _) = fory_core::meta::murmurhash3_x64_128(fingerprint.as_bytes(), 47);
    let version_hash = (hash & 0xFFFF_FFFF) as i32;

    quote! {
        {
            const VERSION_HASH: i32 = #version_hash;
            if fory_core::util::ENABLE_FORY_DEBUG_OUTPUT {
                println!(
                    "[rust][fory-debug] struct {} version fingerprint=\"{}\" hash={}",
                    std::any::type_name::<Self>(),
                    #fingerprint,
                    VERSION_HASH
                );
            }
            VERSION_HASH
        }
    }
}

/// Represents the determined RefMode for a field
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum FieldRefMode {
    None,
    NullOnly,
    Tracking,
}

impl ToTokens for FieldRefMode {
    fn to_tokens(&self, tokens: &mut proc_macro2::TokenStream) {
        let ts = match self {
            FieldRefMode::None => quote! { fory_core::RefMode::None },
            FieldRefMode::NullOnly => quote! { fory_core::RefMode::NullOnly },
            FieldRefMode::Tracking => quote! { fory_core::RefMode::Tracking },
        };
        tokens.extend(ts);
    }
}

/// Determine the RefMode for a field based on field meta attributes and type.
/// This respects `#[fory(ref=false)]` and `#[fory(nullable)]` attributes.
pub(crate) fn determine_field_ref_mode(field: &syn::Field) -> FieldRefMode {
    use super::field_meta::{classify_field_type, parse_field_meta};

    let meta = parse_field_meta(field).unwrap_or_default();
    let type_class = classify_field_type(&field.ty);
    let nullable = meta.effective_nullable(type_class);
    let track_ref = meta.effective_ref(type_class);

    if track_ref {
        FieldRefMode::Tracking
    } else if nullable {
        FieldRefMode::NullOnly
    } else {
        FieldRefMode::None
    }
}

/// Determine whether to skip writing type info for a struct field based on its type.
///
/// According to xlang_serialization_spec.md:
/// - Primitive fields: skip type info
/// - Nullable primitive fields (`Option<primitive>`): skip type info
/// - Internal type fields (String, Date, arrays): skip type info
/// - List/Set/Map fields: skip type info
/// - Struct fields: WRITE type info
/// - Ext fields: WRITE type info
/// - Enum fields: skip type info (enum is dynamic)
///
/// Returns true if type info should be skipped, false if it should be written.
pub(crate) fn should_skip_type_info_for_field(ty: &Type) -> bool {
    let type_id = get_type_id_by_type_ast(ty);
    if [
        TypeId::STRUCT as u32,
        TypeId::COMPATIBLE_STRUCT as u32,
        TypeId::NAMED_STRUCT as u32,
        TypeId::NAMED_COMPATIBLE_STRUCT as u32,
        TypeId::EXT as u32,
        TypeId::NAMED_EXT as u32,
        TypeId::UNKNOWN as u32,
    ]
    .contains(&type_id)
    {
        // Struct and Ext types need type info
        return false;
    }
    // Primitive, nullable primitive, internal types, List/Set/Map skip type info
    true
}

pub(crate) fn is_skip_field(field: &syn::Field) -> bool {
    super::field_meta::is_skip_field(field)
}

pub(crate) fn is_skip_enum_variant(variant: &syn::Variant) -> bool {
    variant.attrs.iter().any(|attr| {
        attr.path().is_ident("fory") && {
            let mut skip = false;
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("skip") {
                    skip = true;
                }
                Ok(())
            });
            skip
        }
    })
}

pub(crate) fn enum_variant_id(variant: &syn::Variant) -> Option<u32> {
    for attr in &variant.attrs {
        if !attr.path().is_ident("fory") {
            continue;
        }
        let mut id = None;
        let _ = attr.parse_nested_meta(|meta| {
            if meta.path.is_ident("id") {
                if let Ok(value) = meta.value() {
                    if let Ok(lit) = value.parse::<syn::LitInt>() {
                        if let Ok(parsed) = lit.base10_parse::<u32>() {
                            id = Some(parsed);
                        }
                    }
                }
            }
            Ok(())
        });
        if id.is_some() {
            return id;
        }
    }
    None
}

pub(crate) fn is_default_value_variant(variant: &syn::Variant) -> bool {
    variant
        .attrs
        .iter()
        .any(|attr| attr.path().is_ident("default"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use syn::parse_quote;

    #[test]
    fn group_fields_normalizes_names_and_preserves_ordering() {
        let fields: Vec<syn::Field> = vec![
            parse_quote!(pub camelCase: i32),
            parse_quote!(pub optionalValue: Option<i64>),
            parse_quote!(pub simpleString: String),
            parse_quote!(pub listItems: Vec<String>),
            parse_quote!(pub setItems: HashSet<i32>),
            parse_quote!(pub mapValues: HashMap<String, i32>),
            parse_quote!(pub customType: CustomType),
        ];
        let field_refs: Vec<&syn::Field> = fields.iter().collect();

        let (
            primitive_fields,
            nullable_primitive_fields,
            internal_type_fields,
            list_fields,
            set_fields,
            map_fields,
            other_fields,
        ) = group_fields_by_type(&field_refs);

        let primitive_names: Vec<&str> = primitive_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(primitive_names, vec!["camel_case"]);

        let nullable_names: Vec<&str> = nullable_primitive_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(nullable_names, vec!["optional_value"]);

        let internal_names: Vec<&str> = internal_type_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(internal_names, vec!["simple_string"]);

        let list_names: Vec<&str> = list_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(list_names, vec!["list_items"]);

        let set_names: Vec<&str> = set_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(set_names, vec!["set_items"]);

        let map_names: Vec<&str> = map_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(map_names, vec!["map_values"]);

        let other_names: Vec<&str> = other_fields
            .iter()
            .map(|(name, _, _)| name.as_str())
            .collect();
        assert_eq!(other_names, vec!["custom_type"]);

        let sorted_names = get_sorted_field_names(&field_refs);
        assert_eq!(
            sorted_names,
            vec![
                "camel_case".to_string(),
                "optional_value".to_string(),
                "simple_string".to_string(),
                "list_items".to_string(),
                "set_items".to_string(),
                "map_values".to_string(),
                "custom_type".to_string(),
            ]
        );
    }
}
