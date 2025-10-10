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
use fory_core::types::{TypeId, BASIC_TYPE_NAMES, CONTAINER_TYPE_NAMES, PRIMITIVE_ARRAY_TYPE_MAP};
use proc_macro2::{Ident, TokenStream};
use quote::{format_ident, quote, ToTokens};
use std::cell::RefCell;
use std::fmt;
use syn::{parse_str, Field, GenericArgument, PathArguments, Type};

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
    name: String,
    generics: Vec<TypeNode>,
}

#[derive(Debug)]
pub(super) struct NullableTypeNode {
    name: String,
    generics: Vec<NullableTypeNode>,
    nullable: bool,
}

macro_rules! basic_type_deserialize {
    ($name:expr, $nullable:expr; $( ($ty_str:expr, $ty:ty) ),* $(,)?) => {
        match $name {
            $(
                $ty_str => {
                    if $nullable {
                        quote! {
                            <$ty as fory_core::serializer::Serializer>::fory_read_type_info(context, true);
                            let res1 = Some(<$ty as fory_core::serializer::Serializer>::fory_read_data(context, true)
                                .map_err(fory_core::error::Error::from)?);
                            Ok::<Option<$ty>, fory_core::error::Error>(res1)
                        }
                    } else {
                        quote! {
                            <$ty as fory_core::serializer::Serializer>::fory_read_type_info(context, true);
                            let res2 = <$ty as fory_core::serializer::Serializer>::fory_read_data(context, true)
                                .map_err(fory_core::error::Error::from)?;
                            Ok::<$ty, fory_core::error::Error>(res2)
                        }
                    }
                }
            )*
            _ => unreachable!(),
        }
    };
}

pub fn try_primitive_vec_type(node: &TypeNode) -> Option<TokenStream> {
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

pub fn try_vec_of_option_primitive(node: &TypeNode) -> Option<TokenStream> {
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

pub fn try_primitive_vec_type_name(node: &NullableTypeNode) -> Option<String> {
    if node.name != "Vec" {
        return None;
    }
    let child = node.generics.first()?;
    for (generic_name, _, ty_name) in PRIMITIVE_ARRAY_TYPE_MAP {
        if child.name == *generic_name {
            return Some(ty_name.to_string());
        }
    }
    None
}

impl NullableTypeNode {
    pub(super) fn to_read_tokens(
        &self,
        generic_path: &Vec<i8>,
        read_ref_flag: bool,
    ) -> TokenStream {
        let tokens = if let Some(primitive_ty_name) = try_primitive_vec_type_name(self) {
            let ty_type: Type = parse_str(&primitive_ty_name).expect("Invalid primitive type name");
            let nullable = self.nullable;
            if nullable {
                quote! {
                    let res1 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                        None
                    } else {
                        <#ty_type as fory_core::serializer::Serializer>::fory_read_type_info(context, true);
                        Some(<#ty_type as fory_core::serializer::Serializer>::fory_read_data(context, true)
                            .map_err(fory_core::error::Error::from)?)
                    };
                    Ok::<Option<#ty_type>, fory_core::error::Error>(res1)
                }
            } else {
                quote! {
                    let res2 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                        Vec::default()
                    } else {
                        <#ty_type as fory_core::serializer::Serializer>::fory_read_type_info(context, true);
                        <#ty_type as fory_core::serializer::Serializer>::fory_read_data(context, true)
                            .map_err(fory_core::error::Error::from)?
                    };
                    Ok::<#ty_type, fory_core::error::Error>(res2)
                }
            }
        } else if BASIC_TYPE_NAMES.contains(&self.name.as_str()) {
            basic_type_deserialize!(self.name.as_str(), self.nullable;
                ("bool", bool),
                ("i8", i8),
                ("i16", i16),
                ("i32", i32),
                ("i64", i64),
                ("f32", f32),
                ("f64", f64),
                ("String", String),
                ("NaiveDate", chrono::NaiveDate),
                ("NaiveDateTime", chrono::NaiveDateTime),
            )
        } else if CONTAINER_TYPE_NAMES.contains(&self.name.as_str()) {
            let ty = parse_str::<Type>(&self.to_string()).unwrap();
            let mut new_path = generic_path.clone();
            match self.name.as_str() {
                "Vec" => {
                    new_path.push(0);
                    let generic_node = self.generics.first().unwrap();
                    let element_tokens = generic_node.to_read_tokens(&new_path, false);
                    let element_ty: Type = parse_str(&generic_node.to_string()).unwrap();
                    let vec_ts = quote! {
                        let length = context.reader.read_varuint32() as usize;
                        if length == 0 {
                            Vec::default()
                        } else {
                            let mut v = Vec::with_capacity(length);
                            let header = context.reader.read_u8();
                            let has_null = (header & fory_core::serializer::collection::HAS_NULL) != 0;
                            let is_same_type = (header & fory_core::serializer::collection::IS_SAME_TYPE) != 0;
                            let read_ref_flag = !(is_same_type && !has_null);
                            for _ in 0..length {
                                let ref_flag = if read_ref_flag {
                                    context.reader.read_i8()
                                } else {
                                    fory_core::types::RefFlag::NotNullValue as i8
                                };
                                let element = if ref_flag == fory_core::types::RefFlag::Null as i8 {
                                    <#element_ty as fory_core::serializer::ForyDefault>::fory_default()
                                } else {
                                    #element_tokens?
                                };
                                v.push(element);
                            }
                            v
                        }
                    };
                    if self.nullable {
                        quote! {
                            let v = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                None
                            } else {
                                Some({#vec_ts})
                            };
                            Ok::<#ty, fory_core::error::Error>(v)
                        }
                    } else {
                        quote! {
                            let v = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                Vec::default()
                            } else {
                                #vec_ts
                            };
                            Ok::<#ty, fory_core::error::Error>(v)
                        }
                    }
                }
                "HashSet" => {
                    new_path.push(0);
                    let generic_node = self.generics.first().unwrap();
                    let element_tokens = generic_node.to_read_tokens(&new_path, false);
                    let element_ty: Type = parse_str(&generic_node.to_string()).unwrap();
                    let set_ts = quote! {
                        let length = context.reader.read_varuint32() as usize;
                        if length == 0 {
                            HashSet::default()
                        } else {
                            let mut s = HashSet::with_capacity(length);
                            let header = context.reader.read_u8();
                            let has_null = (header & fory_core::serializer::collection::HAS_NULL) != 0;
                            let is_same_type = (header & fory_core::serializer::collection::IS_SAME_TYPE) != 0;
                            let read_ref_flag = !(is_same_type && !has_null);
                            for _ in 0..length {
                                let ref_flag = if read_ref_flag {
                                    context.reader.read_i8()
                                } else {
                                    fory_core::types::RefFlag::NotNullValue as i8
                                };
                                let element = if ref_flag == fory_core::types::RefFlag::Null as i8 {
                                    <#element_ty as fory_core::serializer::ForyDefault>::fory_default()
                                } else {
                                    #element_tokens?
                                };
                                s.insert(element);
                            }
                            s
                        }
                    };
                    if self.nullable {
                        quote! {
                            let s = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                None
                            } else {
                                Some({#set_ts})
                            };
                            Ok::<#ty, fory_core::error::Error>(s)
                        }
                    } else {
                        quote! {
                            let s = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                HashSet::default()
                            } else {
                                #set_ts
                            };
                            Ok::<#ty, fory_core::error::Error>(s)
                        }
                    }
                }
                "HashMap" => {
                    let key_generic_node = self.generics.first().unwrap();
                    let val_generic_node = self.generics.get(1).unwrap();

                    new_path.push(0);
                    let key_tokens = key_generic_node.to_read_tokens(&new_path, false);
                    new_path.pop();
                    new_path.push(1);
                    let val_tokens = val_generic_node.to_read_tokens(&new_path, false);

                    let key_ty: Type = parse_str(&key_generic_node.to_string()).unwrap();
                    let val_ty: Type = parse_str(&val_generic_node.to_string()).unwrap();

                    let map_ts = quote! {
                        let length = context.reader.read_varuint32();
                        let mut map = HashMap::with_capacity(length as usize);
                        if length == 0 {
                            map
                        } else {
                            let mut len_counter = 0;
                            loop {
                                if len_counter == length {
                                    break;
                                }
                                let header = context.reader.read_u8();
                                if header & fory_core::serializer::map::KEY_NULL != 0 && header & fory_core::serializer::map::VALUE_NULL != 0 {
                                    map.insert(<#key_ty as fory_core::serializer::ForyDefault>::fory_default(), <#val_ty as fory_core::serializer::ForyDefault>::fory_default());
                                    len_counter += 1;
                                    continue;
                                }
                                if header & fory_core::serializer::map::KEY_NULL != 0 {
                                    let value: #val_ty = {#val_tokens}?;
                                    map.insert(<#key_ty as fory_core::serializer::ForyDefault>::fory_default(), value);
                                    len_counter += 1;
                                    continue;
                                }
                                if header & fory_core::serializer::map::VALUE_NULL != 0 {
                                    let key: #key_ty = {#key_tokens}?;
                                    map.insert(key, <#val_ty as fory_core::serializer::ForyDefault>::fory_default());
                                    len_counter += 1;
                                    continue;
                                }
                                let chunk_size = context.reader.read_u8();
                                <#key_ty as fory_core::serializer::Serializer>::fory_read_type_info(context, true);
                                <#val_ty as fory_core::serializer::Serializer>::fory_read_type_info(context, true);
                                for _ in (0..chunk_size).enumerate() {
                                    let key: #key_ty = {#key_tokens}?;
                                    let value: #val_ty = {#val_tokens}?;
                                    map.insert(key, value);
                                }
                                len_counter += chunk_size as u32;
                            }
                            map
                        }
                    };
                    if self.nullable {
                        quote! {
                            let m = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                None
                            } else {
                                Some({#map_ts})
                            };
                            Ok::<#ty, fory_core::error::Error>(m)
                        }
                    } else {
                        quote! {
                            let m = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                HashMap::default()
                            } else {
                                #map_ts
                            };
                            Ok::<#ty, fory_core::error::Error>(m)
                        }
                    }
                }
                _ => quote! { compile_error!("Unsupported type for container"); },
            }
        } else {
            // struct or enum
            let nullable_ty = parse_str::<Type>(&self.nullable_ty_string()).unwrap();
            let ty = parse_str::<Type>(&self.to_string()).unwrap();
            let ts = if self.nullable {
                quote! {
                    let res1 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                        None
                    } else {
                        let type_id = cur_remote_nullable_type.type_id;
                        let internal_id = type_id & 0xff;
                        Some(
                            if internal_id == COMPATIBLE_STRUCT_ID
                                || internal_id == NAMED_COMPATIBLE_STRUCT_ID
                                || internal_id == ENUM_ID
                                || internal_id == NAMED_ENUM_ID
                                || internal_id == EXT_ID
                                || internal_id == NAMED_EXT_ID
                            {
                                <#nullable_ty as fory_core::serializer::Serializer>::fory_read_compatible(context)
                                    .map_err(fory_core::error::Error::from)?
                            } else {
                                unimplemented!()
                            }
                        )
                    };
                    Ok::<#ty, fory_core::error::Error>(res1)
                }
            } else {
                quote! {
                    let res2 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                        <#ty as fory_core::serializer::ForyDefault>::fory_default()
                    } else {
                        let type_id = cur_remote_nullable_type.type_id;
                        let internal_id = type_id & 0xff;
                        if internal_id == COMPATIBLE_STRUCT_ID
                            || internal_id == NAMED_COMPATIBLE_STRUCT_ID
                            || internal_id == ENUM_ID
                            || internal_id == NAMED_ENUM_ID
                            || internal_id == EXT_ID
                            || internal_id == NAMED_EXT_ID
                        {
                            <#nullable_ty as fory_core::serializer::Serializer>::fory_read_compatible(context)
                                .map_err(fory_core::error::Error::from)?
                        } else {
                            unimplemented!()
                        }
                    };
                    Ok::<#ty, fory_core::error::Error>(res2)
                }
            };
            quote! {
                const COMPATIBLE_STRUCT_ID: u32 = fory_core::types::TypeId::COMPATIBLE_STRUCT as u32;
                const ENUM_ID: u32 = fory_core::types::TypeId::ENUM as u32;
                const NAMED_COMPATIBLE_STRUCT_ID: u32 = fory_core::types::TypeId::NAMED_COMPATIBLE_STRUCT as u32;
                const NAMED_ENUM_ID: u32 = fory_core::types::TypeId::NAMED_ENUM as u32;
                const EXT_ID: u32 = fory_core::types::TypeId::EXT as u32;
                const NAMED_EXT_ID: u32 = fory_core::types::TypeId::NAMED_EXT as u32;
                #ts
            }
        };
        let mut cur_remote_nullable_type = quote! { remote_nullable_type };
        for idx in generic_path {
            cur_remote_nullable_type = quote! {
                #cur_remote_nullable_type.generics.get(#idx as usize).unwrap()
            };
        }
        let read_ref_flag_ts = if read_ref_flag {
            quote! {
                let read_ref_flag = fory_core::serializer::skip::get_read_ref_flag(cur_remote_nullable_type);
                let ref_flag = if read_ref_flag {
                    context.reader.read_i8()
                } else {
                    fory_core::types::RefFlag::NotNullValue as i8
                };
                if ref_flag == fory_core::types::RefFlag::Null as i8 {
                    Ok(Default::default())
                } else {
                    #tokens
                }
            }
        } else {
            quote! { #tokens }
        };
        quote! {
            let cur_remote_nullable_type = &#cur_remote_nullable_type;
            #read_ref_flag_ts
        }
    }

    pub(super) fn from(node: TypeNode) -> Self {
        if node.name == "Option" {
            let inner = NullableTypeNode::from(node.generics.into_iter().next().unwrap());
            NullableTypeNode {
                name: inner.name,
                generics: inner.generics,
                nullable: true,
            }
        } else {
            let generics = node
                .generics
                .into_iter()
                .map(NullableTypeNode::from)
                .collect();

            NullableTypeNode {
                name: node.name,
                generics,
                nullable: false,
            }
        }
    }

    pub(super) fn nullable_ty_string(&self) -> String {
        if self.generics.is_empty() {
            self.name.clone()
        } else {
            format!(
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

impl fmt::Display for NullableTypeNode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let inner_type = if self.generics.is_empty() {
            self.name.clone()
        } else {
            format!(
                "{}<{}>",
                self.name,
                self.generics
                    .iter()
                    .map(|g| g.to_string())
                    .collect::<Vec<_>>()
                    .join(",")
            )
        };

        if self.nullable {
            write!(f, "Option<{}>", inner_type)
        } else {
            write!(f, "{}", inner_type)
        }
    }
}

fn extract_type_name(ty: &Type) -> String {
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

pub(super) fn generic_tree_to_tokens(node: &TypeNode, have_context: bool) -> TokenStream {
    if node.name == "Option" {
        if let Some(first_generic) = node.generics.first() {
            if first_generic.name == "Option" {
                return quote! {
                    compile_error!("adjacent Options are not supported");
                };
            }
        }
    }

    if let Some(ts) = try_vec_of_option_primitive(node) {
        return ts;
    }
    let primitive_vec = try_primitive_vec_type(node);

    let children_tokens: Vec<TokenStream> = if primitive_vec.is_none() {
        node.generics
            .iter()
            .map(|child| generic_tree_to_tokens(child, have_context))
            .collect()
    } else {
        vec![]
    };
    let ty: syn::Type = syn::parse_str(&node.to_string()).unwrap();
    let param = if have_context {
        quote! {
            context.get_fory()
        }
    } else {
        quote! {
            fory
        }
    };
    let get_type_id = if node.name == "Option" {
        let option_type_id = TypeId::ForyNullable as u32;
        quote! { #option_type_id }
    } else if let Some(ts) = primitive_vec {
        ts
    } else {
        quote! {
            <#ty as fory_core::serializer::Serializer>::fory_get_type_id(#param)
        }
    };
    quote! {
        fory_core::meta::FieldType::new(
            #get_type_id,
            vec![#(#children_tokens),*] as Vec<fory_core::meta::FieldType>
        )
    }
}

type FieldGroup = Vec<(String, String, u32)>;
type FieldGroups = (FieldGroup, FieldGroup, FieldGroup, FieldGroup);
pub(super) fn get_sort_fields_ts(fields: &[&Field]) -> TokenStream {
    fn group_fields(fields: &[&Field]) -> FieldGroups {
        const PRIMITIVE_TYPE_NAMES: [&str; 7] = ["bool", "i8", "i16", "i32", "i64", "f32", "f64"];
        const FINAL_TYPE_NAMES: [&str; 3] = ["String", "NaiveDate", "NaiveDateTime"];
        const PRIMITIVE_ARRAY_NAMES: [&str; 7] = [
            "Vec<bool>",
            "Vec<i8>",
            "Vec<i16>",
            "Vec<i32>",
            "Vec<i64>",
            "Vec<f32>",
            "Vec<f64>",
        ];

        fn extract_option_inner(s: &str) -> Option<&str> {
            s.strip_prefix("Option<")?.strip_suffix(">")
        }

        macro_rules! match_ty {
            ($ty:expr, $(($name:expr, $ret:expr)),+ $(,)?) => {
                $(
                    if $ty == $name {
                        $ret as u32
                    } else
                )+
                {
                    unreachable!("Unknown type: {}", $ty);
                }
            };
        }

        fn get_primitive_type_id(ty: &str) -> u32 {
            match_ty!(
                ty,
                ("bool", TypeId::BOOL),
                ("i8", TypeId::INT8),
                ("i16", TypeId::INT16),
                ("i32", TypeId::INT32),
                ("i64", TypeId::INT64),
                ("f32", TypeId::FLOAT32),
                ("f64", TypeId::FLOAT64),
            )
        }

        let mut primitive_fields = Vec::new();
        let mut nullable_primitive_fields = Vec::new();
        let mut final_fields = Vec::new();
        let mut collection_fields = Vec::new();
        let mut map_fields = Vec::new();
        let mut struct_or_enum_fields = Vec::new();

        // First handle Forward fields separately to avoid borrow checker issues
        for field in fields {
            if is_forward_field(&field.ty) {
                let ident = field.ident.as_ref().unwrap().to_string();
                collection_fields.push((ident, "Forward".to_string(), TypeId::LIST as u32));
            }
        }

        let mut group_field = |ident: String, ty: &str| {
            if PRIMITIVE_TYPE_NAMES.contains(&ty) {
                let type_id = get_primitive_type_id(ty);
                primitive_fields.push((ident, ty.to_string(), type_id));
            } else if FINAL_TYPE_NAMES.contains(&ty) || PRIMITIVE_ARRAY_NAMES.contains(&ty) {
                let type_id = match_ty!(
                    ty,
                    ("String", TypeId::STRING),
                    ("NaiveDate", TypeId::LOCAL_DATE),
                    ("NaiveDateTime", TypeId::TIMESTAMP),
                    ("Vec<u8>", TypeId::BINARY),
                    ("Vec<bool>", TypeId::BOOL_ARRAY),
                    ("Vec<i8>", TypeId::INT8_ARRAY),
                    ("Vec<i16>", TypeId::INT16_ARRAY),
                    ("Vec<i32>", TypeId::INT32_ARRAY),
                    ("Vec<i64>", TypeId::INT64_ARRAY),
                    ("Vec<f32>", TypeId::FLOAT32_ARRAY),
                    ("Vec<f64>", TypeId::FLOAT64_ARRAY),
                );
                final_fields.push((ident, ty.to_string(), type_id));
            } else if ty.starts_with("Vec<")
                || ty.starts_with("VecDeque<")
                || ty.starts_with("LinkedList<")
                || ty.starts_with("BinaryHeap<")
            {
                collection_fields.push((ident, ty.to_string(), TypeId::LIST as u32));
            } else if ty.starts_with("HashSet<") || ty.starts_with("BTreeSet<") {
                collection_fields.push((ident, ty.to_string(), TypeId::SET as u32));
            } else if ty.starts_with("HashMap<") || ty.starts_with("BTreeMap<") {
                map_fields.push((ident, ty.to_string(), TypeId::MAP as u32));
            } else {
                struct_or_enum_fields.push((ident, ty.to_string(), 0));
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
                    // continue to handle Option<not Primitive>
                    // already avoid Option<Option<T>> at compile-time
                    group_field(ident, inner);
                }
            } else {
                group_field(ident, &ty);
            }
        }

        for field in fields {
            if is_box_dyn_trait(&field.ty).is_some() {
                let ident = field.ident.as_ref().unwrap().to_string();
                if let Some(pos) = struct_or_enum_fields.iter().position(|x| x.0 == ident) {
                    struct_or_enum_fields[pos].2 = TypeId::UNKNOWN as u32;
                }
            }
        }

        fn sorter(a: &(String, String, u32), b: &(String, String, u32)) -> std::cmp::Ordering {
            a.2.cmp(&b.2).then_with(|| a.0.cmp(&b.0))
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

        fn numeric_sorter(
            a: &(String, String, u32),
            b: &(String, String, u32),
        ) -> std::cmp::Ordering {
            let compress_a = is_compress(a.2);
            let compress_b = is_compress(b.2);
            let size_a = get_primitive_type_size(a.2);
            let size_b = get_primitive_type_size(b.2);
            compress_a
                .cmp(&compress_b)
                .then_with(|| size_b.cmp(&size_a))
                .then_with(|| a.0.cmp(&b.0))
        }

        primitive_fields.sort_by(numeric_sorter);
        nullable_primitive_fields.sort_by(numeric_sorter);
        primitive_fields.extend(nullable_primitive_fields);
        collection_fields.sort_by(sorter);
        map_fields.sort_by(sorter);
        let container_fields = {
            let mut container_fields = collection_fields;
            container_fields.extend(map_fields);
            container_fields
        };
        (
            primitive_fields,
            final_fields,
            container_fields,
            struct_or_enum_fields,
        )
    }

    fn gen_vec_token_stream(fields: &[(String, String, u32)]) -> TokenStream {
        let names = fields.iter().map(|(name, _, _)| {
            quote! { #name.to_string() }
        });
        quote! {
            vec![#(#names),*]
        }
    }

    fn gen_vec_tuple_token_stream(fields: &[(String, String, u32)]) -> TokenStream {
        let names = fields.iter().map(|(name, _, type_id)| {
            quote! { (#type_id, #name.to_string()) }
        });
        quote! {
            vec![#(#names),*]
        }
    }

    let (all_primitive_fields, final_fields, container_fields, struct_or_enum_fields) =
        group_fields(fields);

    let all_primitive_field_names_declare_extend_ts = {
        if all_primitive_fields.is_empty() {
            (quote! {}, quote! {})
        } else {
            let all_primitive_field_names_ts = gen_vec_token_stream(&all_primitive_fields);
            (
                quote! {
                    let all_primitive_field_names: Vec<String> = #all_primitive_field_names_ts;
                },
                quote! {
                    sorted_field_names.extend(all_primitive_field_names);
                },
            )
        }
    };
    let container_field_names_declare_extend_ts = {
        if container_fields.is_empty() {
            (quote! {}, quote! {})
        } else {
            let container_field_names_ts = gen_vec_token_stream(&container_fields);
            (
                quote! {
                    let container_field_names: Vec<String> = #container_field_names_ts;
                },
                quote! {
                    sorted_field_names.extend(container_field_names);
                },
            )
        }
    };
    let sorter_ts = quote! {
        |a: &(u32, String), b: &(u32, String)| a.0.cmp(&b.0).then_with(|| a.1.cmp(&b.1))
    };
    let final_fields_declare_extend_ts = {
        if final_fields.is_empty() && struct_or_enum_fields.is_empty() {
            (quote! {}, quote! {})
        } else {
            let final_fields_ts = gen_vec_tuple_token_stream(&final_fields);
            (
                quote! {
                    let mut final_fields: Vec<(u32, String)> = #final_fields_ts;
                },
                quote! {
                    final_fields.sort_by(#sorter_ts);
                    let final_field_names: Vec<String> = final_fields.iter().map(|(_, name)| name.clone()).collect();
                    sorted_field_names.extend(final_field_names);
                },
            )
        }
    };
    let other_fields_declare_extend_ts = {
        if struct_or_enum_fields.is_empty() {
            (quote! {}, quote! {})
        } else {
            (
                quote! {
                    let mut other_fields: Vec<(u32, String)> = vec![];
                },
                quote! {
                    other_fields.sort_by(#sorter_ts);
                    let other_field_names: Vec<String> = other_fields.iter().map(|(_, name)| name.clone()).collect();
                    sorted_field_names.extend(other_field_names);
                },
            )
        }
    };
    let trait_object_fields_ts = {
        let trait_obj_fields: Vec<_> = struct_or_enum_fields
            .iter()
            .filter(|(_, _, type_id)| *type_id == fory_core::types::TypeId::UNKNOWN as u32)
            .collect();

        if trait_obj_fields.is_empty() {
            quote! {}
        } else {
            let names = trait_obj_fields.iter().map(|(name, _, type_id)| {
                quote! {
                    final_fields.push((#type_id, #name.to_string()));
                }
            });
            quote! {
                #(#names)*
            }
        }
    };

    let group_sort_enum_other_fields = {
        if struct_or_enum_fields.is_empty() {
            quote! {}
        } else {
            let ts = struct_or_enum_fields
                .iter()
                .filter(|(_, _, type_id)| *type_id != fory_core::types::TypeId::UNKNOWN as u32)
                .map(|(name, ty, _)| {
                    let ty_type: Type = syn::parse_str(ty).unwrap();
                    quote! {
                        let field_type_id = <#ty_type as fory_core::serializer::Serializer>::fory_get_type_id(fory);
                        let internal_id = field_type_id & 0xff;
                        if internal_id == fory_core::types::TypeId::COMPATIBLE_STRUCT as u32
                            || internal_id == fory_core::types::TypeId::NAMED_COMPATIBLE_STRUCT as u32
                            || internal_id == fory_core::types::TypeId::STRUCT as u32
                            || internal_id == fory_core::types::TypeId::NAMED_STRUCT as u32
                            || internal_id == fory_core::types::TypeId::EXT as u32
                            || internal_id == fory_core::types::TypeId::NAMED_EXT as u32
                        {
                            other_fields.push((field_type_id, #name.to_string()));
                        } else if internal_id == fory_core::types::TypeId::ENUM as u32 || internal_id == fory_core::types::TypeId::NAMED_ENUM as u32 {
                            final_fields.push((field_type_id, #name.to_string()));
                        } else {
                            unimplemented!("unknown internal_id when group_sort_enum_other_fields");
                        }
                    }
                })
                .collect::<Vec<_>>();
            quote! {
                {
                    #(#ts)*
                }
            }
        }
    };

    let (all_primitive_declare, all_primitive_extend) = all_primitive_field_names_declare_extend_ts;
    let (container_declare, container_extend) = container_field_names_declare_extend_ts;
    let (final_declare, final_extend) = final_fields_declare_extend_ts;
    let (other_declare, other_extend) = other_fields_declare_extend_ts;

    quote! {
        let sorted_field_names = {
            #all_primitive_declare
            #final_declare
            #other_declare
            #container_declare

            #trait_object_fields_ts
            #group_sort_enum_other_fields

            let mut sorted_field_names: Vec<String> = Vec::new();
            #all_primitive_extend
            #final_extend
            #other_extend
            #container_extend

            sorted_field_names
        };
    }
}
