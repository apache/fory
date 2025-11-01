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
use proc_macro2::{Ident, TokenStream};
use quote::{format_ident, quote, ToTokens};
use std::cell::RefCell;
use std::collections::HashMap;
use std::fmt;
use std::sync::OnceLock;
use syn::{Field, GenericArgument, PathArguments, Type};

thread_local! {
    static MACRO_CONTEXT: RefCell<Option<MacroContext>> = const {RefCell::new(None)};
}

#[derive(Clone)]
struct MacroContext {
    struct_name: String,
    debug_enabled: bool,
}

pub(super) fn set_struct_context(name: &str, debug_enabled: bool) {
    MACRO_CONTEXT.with(|ctx| {
        *ctx.borrow_mut() = Some(MacroContext {
            struct_name: name.to_string(),
            debug_enabled,
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

/// Global flag to check if ENABLE_FORY_DEBUG_OUTPUT environment variable is set.
static ENABLE_FORY_DEBUG_OUTPUT: OnceLock<bool> = OnceLock::new();

/// Check if ENABLE_FORY_DEBUG_OUTPUT environment variable is set.
#[inline]
fn enable_debug_output() -> bool {
    *ENABLE_FORY_DEBUG_OUTPUT.get_or_init(|| {
        std::env::var("ENABLE_FORY_DEBUG_OUTPUT")
            .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
            .unwrap_or(false)
    })
}

pub(super) fn is_debug_enabled() -> bool {
    MACRO_CONTEXT.with(|ctx| {
        ctx.borrow()
            .as_ref()
            .map(|c| c.debug_enabled)
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

pub(super) enum StructField {
    #[allow(unused_variables)]
    BoxDyn,
    RcDyn(String),
    ArcDyn(String),
    VecRc(String),
    VecArc(String),
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
                                // Inner type is not a trait object → return true
                                _ => {
                                    return true;
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
    if let Some(collection_info) = detect_collection_with_trait_object(ty) {
        return match collection_info {
            CollectionTraitInfo::VecRc(t) => StructField::VecRc(t),
            CollectionTraitInfo::VecArc(t) => StructField::VecArc(t),
            CollectionTraitInfo::HashMapRc(k, t) => StructField::HashMapRc(k, t),
            CollectionTraitInfo::HashMapArc(k, t) => StructField::HashMapArc(k, t),
        };
    }
    if contains_trait_object(ty) {
        return StructField::ContainsTraitObject;
    }
    StructField::None
}

#[derive(Debug)]
pub(super) struct TypeNode {
    pub name: String,
    pub generics: Vec<TypeNode>,
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
        } else if self.generics.is_empty() {
            write!(f, "{}", self.name)
        } else {
            write!(
                f,
                "{}<{}>",
                self.name,
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
            generics: vec![],
        };
    }

    // Handle tuples - make child generics empty
    if let Type::Tuple(_tuple) = ty {
        return TypeNode {
            name: "Tuple".to_string(),
            generics: vec![],
        };
    }

    let name = extract_type_name(ty);

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
    TypeNode { name, generics }
}

pub(super) fn generic_tree_to_tokens(node: &TypeNode) -> TokenStream {
    // Special handling for tuples: always use FieldType { LIST, nullable: true, generics: vec![UNKNOWN] }
    if node.name == "Tuple" {
        return quote! {
            fory_core::meta::FieldType::new(
                fory_core::types::TypeId::LIST as u32,
                true,
                vec![fory_core::meta::FieldType {
                    type_id: fory_core::types::TypeId::UNKNOWN as u32,
                    nullable: true,
                    generics: vec![],
                }]
            )
        };
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
                        vec![fory_core::meta::FieldType {
                            type_id: fory_core::types::TypeId::UNKNOWN as u32,
                            nullable: true,
                            generics: vec![],
                        }]
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
            <#ty as fory_core::serializer::Serializer>::fory_get_type_id(type_resolver)?
        }
    };

    quote! {
        {
            let type_id = #get_type_id;
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
            fory_core::meta::FieldType::new(type_id, #nullable, generics)
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

const PRIMITIVE_TYPE_NAMES: [&str; 11] = [
    "bool", "i8", "i16", "i32", "i64", "f32", "f64", "u8", "u16", "u32", "u64",
];

fn get_primitive_type_id(ty: &str) -> u32 {
    match ty {
        "bool" => TypeId::BOOL as u32,
        "i8" => TypeId::INT8 as u32,
        "i16" => TypeId::INT16 as u32,
        "i32" => TypeId::INT32 as u32,
        "i64" => TypeId::INT64 as u32,
        "f32" => TypeId::FLOAT32 as u32,
        "f64" => TypeId::FLOAT64 as u32,
        "u8" => TypeId::U8 as u32,
        "u16" => TypeId::U16 as u32,
        "u32" => TypeId::U32 as u32,
        "u64" => TypeId::U64 as u32,
        _ => unreachable!("Unknown primitive type: {}", ty),
    }
}

pub(super) fn is_primitive_type(ty: &str) -> bool {
    PRIMITIVE_TYPE_NAMES.contains(&ty)
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
        "NaiveDate" => return TypeId::LOCAL_DATE as u32,
        "NaiveDateTime" => return TypeId::TIMESTAMP as u32,
        "Duration" => return TypeId::DURATION as u32,
        "Decimal" => return TypeId::DECIMAL as u32,
        "Vec<u8>" | "bytes" => return TypeId::BINARY as u32,
        _ => {}
    }

    // Check primitive arrays
    match ty {
        "Vec<bool>" => return TypeId::BOOL_ARRAY as u32,
        "Vec<i8>" => return TypeId::INT8_ARRAY as u32,
        "Vec<i16>" => return TypeId::INT16_ARRAY as u32,
        "Vec<i32>" => return TypeId::INT32_ARRAY as u32,
        "Vec<i64>" => return TypeId::INT64_ARRAY as u32,
        "Vec<f16>" => return TypeId::FLOAT16_ARRAY as u32,
        "Vec<f32>" => return TypeId::FLOAT32_ARRAY as u32,
        "Vec<f64>" => return TypeId::FLOAT64_ARRAY as u32,
        "Vec<u16>" => return TypeId::U16_ARRAY as u32,
        "Vec<u32>" => return TypeId::U32_ARRAY as u32,
        "Vec<u64>" => return TypeId::U64_ARRAY as u32,
        _ => {}
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
    let type_id = TypeId::try_from(type_id_num as i16).unwrap();
    match type_id {
        TypeId::BOOL => 1,
        TypeId::INT8 => 1,
        TypeId::INT16 => 2,
        TypeId::INT32 => 4,
        TypeId::VAR_INT32 => 4,
        TypeId::INT64 => 8,
        TypeId::VAR_INT64 => 8,
        TypeId::FLOAT16 => 2,
        TypeId::FLOAT32 => 4,
        TypeId::FLOAT64 => 8,
        TypeId::U8 => 1,
        TypeId::U16 => 2,
        TypeId::U32 => 4,
        TypeId::U64 => 8,
        _ => unreachable!(),
    }
}

fn is_compress(type_id: u32) -> bool {
    [
        TypeId::INT32 as u32,
        TypeId::INT64 as u32,
        TypeId::VAR_INT32 as u32,
        TypeId::VAR_INT64 as u32,
    ]
    .contains(&type_id)
}

fn is_internal_type_id(type_id: u32) -> bool {
    [
        TypeId::STRING as u32,
        TypeId::LOCAL_DATE as u32,
        TypeId::TIMESTAMP as u32,
        TypeId::DURATION as u32,
        TypeId::DECIMAL as u32,
        TypeId::BINARY as u32,
        TypeId::BOOL_ARRAY as u32,
        TypeId::INT8_ARRAY as u32,
        TypeId::INT16_ARRAY as u32,
        TypeId::INT32_ARRAY as u32,
        TypeId::INT64_ARRAY as u32,
        TypeId::FLOAT16_ARRAY as u32,
        TypeId::FLOAT32_ARRAY as u32,
        TypeId::FLOAT64_ARRAY as u32,
    ]
    .contains(&type_id)
}

/// Group fields into serialization categories while normalizing field names to snake_case.
/// The returned groups preserve the ordering rules required by the serialization layout.
fn group_fields_by_type(fields: &[&Field]) -> FieldGroups {
    let mut primitive_fields = Vec::new();
    let mut nullable_primitive_fields = Vec::new();
    let mut internal_type_fields = Vec::new();
    let mut list_fields = Vec::new();
    let mut set_fields = Vec::new();
    let mut map_fields = Vec::new();
    let mut other_fields = Vec::new();

    // First handle Forward fields separately to avoid borrow checker issues
    for field in fields {
        if is_forward_field(&field.ty) {
            let raw_ident = field.ident.as_ref().unwrap().to_string();
            let ident = to_snake_case(&raw_ident);
            other_fields.push((ident, "Forward".to_string(), TypeId::UNKNOWN as u32));
        }
    }

    let mut group_field = |ident: String, ty: &str| {
        let type_id = get_type_id_by_name(ty);
        // Categorize based on type_id
        if PRIMITIVE_TYPE_NAMES.contains(&ty) {
            primitive_fields.push((ident, ty.to_string(), type_id));
        } else if is_internal_type_id(type_id) {
            internal_type_fields.push((ident, ty.to_string(), type_id));
        } else if type_id == TypeId::LIST as u32 {
            list_fields.push((ident, ty.to_string(), type_id));
        } else if type_id == TypeId::SET as u32 {
            set_fields.push((ident, ty.to_string(), type_id));
        } else if type_id == TypeId::MAP as u32 {
            map_fields.push((ident, ty.to_string(), type_id));
        } else {
            // User-defined type
            other_fields.push((ident, ty.to_string(), type_id));
        }
    };

    for field in fields {
        let raw_ident = field.ident.as_ref().unwrap().to_string();
        let ident = to_snake_case(&raw_ident);

        // Skip if already handled as Forward field
        if is_forward_field(&field.ty) {
            continue;
        }

        let ty: String = field
            .ty
            .to_token_stream()
            .to_string()
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect::<String>();
        // handle Option<Primitive> specially
        if let Some(inner) = extract_option_inner(&ty) {
            if PRIMITIVE_TYPE_NAMES.contains(&inner) {
                let type_id = get_primitive_type_id(inner);
                nullable_primitive_fields.push((ident, ty.to_string(), type_id));
            } else {
                group_field(ident, inner);
            }
        } else {
            group_field(ident, &ty);
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
            .then_with(|| a.2.cmp(&b.2))
            .then_with(|| a.0.cmp(&b.0))
    }

    fn type_id_then_name_sorter(
        a: &(String, String, u32),
        b: &(String, String, u32),
    ) -> std::cmp::Ordering {
        a.2.cmp(&b.2).then_with(|| a.0.cmp(&b.0))
    }

    fn name_sorter(a: &(String, String, u32), b: &(String, String, u32)) -> std::cmp::Ordering {
        a.0.cmp(&b.0)
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

pub(super) fn get_sort_fields_ts(fields: &[&Field]) -> TokenStream {
    let filterd_fields: Vec<&Field> = fields
        .iter()
        .filter(|field| !is_skip_field(field))
        .copied()
        .collect();
    let sorted_names = get_sorted_field_names(&filterd_fields);
    let names = sorted_names.iter().map(|name| {
        quote! { #name }
    });
    quote! {
        &[#(#names),*]
    }
}

fn to_snake_case(name: &str) -> String {
    if name
        .chars()
        .all(|c| c.is_lowercase() || c.is_ascii_digit() || c == '_')
    {
        return name.to_string();
    }

    let mut result = String::with_capacity(name.len() * 2);
    let mut chars = name.chars().peekable();
    let mut prev: Option<char> = None;

    while let Some(ch) = chars.next() {
        if ch == '_' {
            result.push('_');
            prev = Some(ch);
            continue;
        }

        if ch.is_uppercase() {
            if let Some(prev_ch) = prev {
                let need_underscore = (prev_ch.is_lowercase() || prev_ch.is_ascii_digit())
                    || (prev_ch.is_uppercase()
                        && chars
                            .peek()
                            .map(|next| next.is_lowercase())
                            .unwrap_or(false));
                if need_underscore && !result.ends_with('_') {
                    result.push('_');
                }
            }
            result.push(ch.to_ascii_lowercase());
        } else {
            result.push(ch);
        }
        prev = Some(ch);
    }

    result
}

pub(crate) fn compute_struct_version_hash(fields: &[&Field]) -> i32 {
    let mut field_info_map: HashMap<String, (u32, bool)> = HashMap::with_capacity(fields.len());
    for field in fields {
        let name = field.ident.as_ref().unwrap().to_string();
        let type_id = get_type_id_by_type_ast(&field.ty);
        let nullable = is_option(&field.ty);
        field_info_map.insert(name, (type_id, nullable));
    }

    let mut fingerprint = String::new();
    for name in get_sorted_field_names(fields).iter() {
        let (type_id, nullable) = field_info_map
            .get(name)
            .expect("Field metadata missing during struct hash computation");
        fingerprint.push_str(&to_snake_case(name));
        fingerprint.push(',');
        let effective_type_id = if *type_id == TypeId::UNKNOWN as u32 {
            TypeId::UNKNOWN as u32
        } else {
            *type_id
        };
        fingerprint.push_str(&effective_type_id.to_string());
        fingerprint.push(',');
        fingerprint.push_str(if *nullable { "1;" } else { "0;" });
    }

    let seed: u64 = 47;
    let (hash, _) = fory_core::meta::murmurhash3_x64_128(fingerprint.as_bytes(), seed);
    let version = (hash & 0xFFFF_FFFF) as u32;
    let version = version as i32;

    if enable_debug_output() {
        if let Some(struct_name) = get_struct_name() {
            println!(
                "[fory-debug] struct {struct_name} version fingerprint=\"{fingerprint}\" hash={version}"
            );
        } else {
            println!("[fory-debug] struct version fingerprint=\"{fingerprint}\" hash={version}");
        }
    }
    version
}

pub(crate) fn skip_ref_flag(ty: &Type) -> bool {
    // !T::fory_is_option() && PRIMITIVE_TYPES.contains(&elem_type_id)
    PRIMITIVE_TYPE_NAMES.contains(&extract_type_name(ty).as_str())
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
/// - Enum fields: skip type info (enum is morphic)
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
    field.attrs.iter().any(|attr| {
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
    fn to_snake_case_handles_common_patterns() {
        assert_eq!(to_snake_case("lowercase"), "lowercase");
        assert_eq!(to_snake_case("camelCase"), "camel_case");
        assert_eq!(to_snake_case("HTTPRequest"), "http_request");
        assert_eq!(to_snake_case("withNumbers123"), "with_numbers123");
        assert_eq!(to_snake_case("snake_case"), "snake_case");
    }

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
