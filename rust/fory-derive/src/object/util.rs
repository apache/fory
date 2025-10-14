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
use std::fmt;
use syn::{Field, GenericArgument, PathArguments, Type};

thread_local! {
    static MACRO_CONTEXT: RefCell<Option<MacroContext>> = const {RefCell::new(None)};
}

struct MacroContext {
    struct_name: String,
}

pub(super) fn set_struct_context(name: &str) {
    MACRO_CONTEXT.with(|ctx| {
        *ctx.borrow_mut() = Some(MacroContext {
            struct_name: name.to_string(),
        });
    });
}

pub(super) fn clear_struct_context() {
    MACRO_CONTEXT.with(|ctx| {
        *ctx.borrow_mut() = None;
    });
}

fn get_struct_name() -> Option<String> {
    MACRO_CONTEXT.with(|ctx| ctx.borrow().as_ref().map(|c| c.struct_name.clone()))
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
    BoxDyn(String),
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
    if let Some((_, trait_name)) = is_box_dyn_trait(ty) {
        return StructField::BoxDyn(trait_name);
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
        if self.generics.is_empty() {
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
    } else {
        quote!(#ty).to_string()
    }
}

pub(super) fn parse_generic_tree(ty: &Type) -> TypeNode {
    // Handle trait objects specially - they can't be parsed as normal types
    if matches!(ty, Type::TraitObject(_)) {
        return TypeNode {
            name: "TraitObject".to_string(),
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
    // If Option, unwrap it before generating children
    let (nullable, base_node) = if node.name == "Option" {
        if let Some(inner) = node.generics.first() {
            if inner.name == "Option" {
                return quote! { compile_error!("Nested adjacent Option is not allowed!"); };
            }
            // Unwrap Option and propagate parsing
            (true, inner)
        } else {
            return quote! { compile_error!("Missing Option inner type"); };
        }
    } else {
        (!PRIMITIVE_TYPE_NAMES.contains(&node.name.as_str()), node)
    };

    // Vec<Option<primitive>> rule stays as is
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

    let get_type_id = if let Some(ts) = primitive_vec {
        ts
    } else {
        quote! {
            <#ty as fory_core::serializer::Serializer>::fory_get_type_id(fory)?
        }
    };

    quote! {
        fory_core::meta::FieldType::new(
            #get_type_id,
            #nullable,
            vec![#(#children_tokens),*] as Vec<fory_core::meta::FieldType>
        )
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

const PRIMITIVE_TYPE_NAMES: [&str; 7] = ["bool", "i8", "i16", "i32", "i64", "f32", "f64"];

fn get_primitive_type_id(ty: &str) -> u32 {
    match ty {
        "bool" => TypeId::BOOL as u32,
        "i8" => TypeId::INT8 as u32,
        "i16" => TypeId::INT16 as u32,
        "i32" => TypeId::INT32 as u32,
        "i64" => TypeId::INT64 as u32,
        "f32" => TypeId::FLOAT32 as u32,
        "f64" => TypeId::FLOAT64 as u32,
        _ => unreachable!("Unknown primitive type: {}", ty),
    }
}

pub(super) fn is_primitive_type(ty: &str) -> bool {
    PRIMITIVE_TYPE_NAMES.contains(&ty)
}

fn group_fields_by_type(fields: &[&Field]) -> FieldGroups {
    fn extract_option_inner(s: &str) -> Option<&str> {
        s.strip_prefix("Option<")?.strip_suffix(">")
    }

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
            let ident = field.ident.as_ref().unwrap().to_string();
            other_fields.push((ident, "Forward".to_string(), TypeId::UNKNOWN as u32));
        }
    }

    fn get_other_internal_type_id(ty: &str) -> u32 {
        match ty {
            "String" => TypeId::STRING as u32,
            "NaiveDate" => TypeId::LOCAL_DATE as u32,
            "NaiveDateTime" => TypeId::TIMESTAMP as u32,
            "Duration" => TypeId::DURATION as u32,
            "Decimal" => TypeId::DECIMAL as u32,
            "Vec<u8>" | "bytes" => TypeId::BINARY as u32,
            "Vec<bool>" => TypeId::BOOL_ARRAY as u32,
            "Vec<i8>" => TypeId::INT8_ARRAY as u32,
            "Vec<i16>" => TypeId::INT16_ARRAY as u32,
            "Vec<i32>" => TypeId::INT32_ARRAY as u32,
            "Vec<i64>" => TypeId::INT64_ARRAY as u32,
            "Vec<f16>" => TypeId::FLOAT16_ARRAY as u32,
            "Vec<f32>" => TypeId::FLOAT32_ARRAY as u32,
            "Vec<f64>" => TypeId::FLOAT64_ARRAY as u32,
            _ => 0,
        }
    }

    let mut group_field = |ident: String, ty: &str| {
        if PRIMITIVE_TYPE_NAMES.contains(&ty) {
            primitive_fields.push((ident, ty.to_string(), get_primitive_type_id(ty)));
        } else if get_other_internal_type_id(ty) > 0 {
            let internal_type_id = get_other_internal_type_id(ty);
            internal_type_fields.push((ident, ty.to_string(), internal_type_id));
        } else if ty.starts_with("Vec<")
            || ty.starts_with("VecDeque<")
            || ty.starts_with("LinkedList<")
            || ty.starts_with("BinaryHeap<")
        {
            list_fields.push((ident, ty.to_string(), TypeId::LIST as u32));
        } else if ty.starts_with("HashSet<") || ty.starts_with("BTreeSet<") {
            set_fields.push((ident, ty.to_string(), TypeId::SET as u32));
        } else if ty.starts_with("HashMap<") || ty.starts_with("BTreeMap<") {
            map_fields.push((ident, ty.to_string(), TypeId::MAP as u32));
        } else {
            other_fields.push((ident, ty.to_string(), TypeId::UNKNOWN as u32));
        }
    };

    for field in fields {
        let ident = field.ident.as_ref().unwrap().to_string();

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
    let sorted_names = get_sorted_field_names(fields);
    let names = sorted_names.iter().map(|name| {
        quote! { #name }
    });
    quote! {
        &[#(#names),*]
    }
}

pub(crate) fn skip_ref_flag(ty: &Type) -> bool {
    // !T::fory_is_option() && PRIMITIVE_TYPES.contains(&elem_type_id)
    PRIMITIVE_TYPE_NAMES.contains(&extract_type_name(ty).as_str())
}
