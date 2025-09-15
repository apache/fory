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

use fory_core::types::{TypeId, BASIC_TYPE_NAMES, CONTAINER_TYPE_NAMES, PRIMITIVE_ARRAY_TYPE_MAP};
use proc_macro2::TokenStream;
use quote::quote;
use std::fmt;
use syn::{parse_str, GenericArgument, PathArguments, Type};

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
                            let res1 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                None
                            } else {
                                Some(<$ty as fory_core::serializer::Serializer>::read(context)
                                    .map_err(fory_core::error::Error::from)?)
                            };
                            Ok::<Option<$ty>, fory_core::error::Error>(res1)
                        }
                    } else {
                        quote! {
                            let res2 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                $ty::default()
                            } else {
                                <$ty as fory_core::serializer::Serializer>::read(context)
                                    .map_err(fory_core::error::Error::from)?
                            };
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
    pub(super) fn to_deserialize_tokens(&self, generic_path: &Vec<i8>) -> TokenStream {
        let tokens = if let Some(primitive_ty_name) = try_primitive_vec_type_name(self) {
            let ty_type: Type = parse_str(&primitive_ty_name).expect("Invalid primitive type name");
            let nullable = self.nullable;
            if nullable {
                quote! {
                    let res1 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                        None
                    } else {
                        Some(<#ty_type as fory_core::serializer::Serializer>::read(context)
                            .map_err(fory_core::error::Error::from)?)
                    };
                    Ok::<Option<#ty_type>, fory_core::error::Error>(res1)
                }
            } else {
                quote! {
                    let res2 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                        Vec::default()
                    } else {
                        <#ty_type as fory_core::serializer::Serializer>::read(context)
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
                    if self.nullable {
                        quote! {
                            let v = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                None
                            } else {
                                Some(fory_core::serializer::collection::read_collection(context)?)
                            };
                            Ok::<#ty, fory_core::error::Error>(v)
                        }
                    } else {
                        quote! {
                            let v = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                Vec::default()
                            } else {
                                fory_core::serializer::collection::read_collection(context)?
                            };
                            Ok::<#ty, fory_core::error::Error>(v)
                        }
                    }
                }
                "HashSet" => {
                    new_path.push(0);
                    if self.nullable {
                        quote! {
                            let s = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                None
                            } else {
                                Some(fory_core::serializer::collection::read_collection(context)?)
                            };
                            Ok::<#ty, fory_core::error::Error>(s)
                        }
                    } else {
                        quote! {
                            let s = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                HashSet::default()
                            } else {
                                fory_core::serializer::collection::read_collection(context)?
                            };
                            Ok::<#ty, fory_core::error::Error>(s)
                        }
                    }
                }
                "HashMap" => {
                    let key_generic_node = self.generics.first().unwrap();
                    let val_generic_node = self.generics.get(1).unwrap();
                    new_path.push(0);
                    new_path.pop();
                    new_path.push(1);
                    let key_ty: Type = parse_str(&key_generic_node.to_string()).unwrap();
                    let val_ty: Type = parse_str(&val_generic_node.to_string()).unwrap();
                    if self.nullable {
                        quote! {
                            let m = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                None
                            } else {
                                Some(<HashMap<#key_ty, #val_ty> as fory_core::serializer::Serializer>::read(context)?)
                            };
                            Ok::<#ty, fory_core::error::Error>(m)
                        }
                    } else {
                        quote! {
                            let m = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                                HashMap::default()
                            } else {
                                <HashMap<#key_ty, #val_ty> as fory_core::serializer::Serializer>::read(context)?
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
            if self.nullable {
                quote! {
                    const STRUCT_ID: u32 = fory_core::types::TypeId::STRUCT as u32;
                    const ENUM_ID: u32 = fory_core::types::TypeId::ENUM as u32;
                    let res1 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                        None
                    } else {
                        let type_id = cur_remote_nullable_type.type_id;
                        let internal_id = type_id & 0xff;
                        Some(match internal_id {
                            STRUCT_ID => {
                                #nullable_ty::read_compatible(context, type_id)
                                    .map_err(fory_core::error::Error::from)?
                            }
                            ENUM_ID => {
                                <#nullable_ty as fory_core::serializer::Serializer>::read(context)
                                .map_err(fory_core::error::Error::from)?
                            }
                            _ => unreachable!(),
                        })
                    };
                    Ok::<#ty, fory_core::error::Error>(res1)
                }
            } else {
                quote! {
                    const STRUCT_ID: u32 = fory_core::types::TypeId::STRUCT as u32;
                    const ENUM_ID: u32 = fory_core::types::TypeId::ENUM as u32;
                    let res2 = if cur_remote_nullable_type.nullable && ref_flag == (fory_core::types::RefFlag::Null as i8) {
                        #ty::default()
                    } else {
                        let type_id = cur_remote_nullable_type.type_id;
                        let internal_id = type_id & 0xff;
                        // assert_eq!(internal_id as i16, fory_core::types::TypeId::STRUCT as i16);
                        match internal_id {
                            STRUCT_ID => {
                                #nullable_ty::read_compatible(context, type_id)
                                    .map_err(fory_core::error::Error::from)?
                            }
                            ENUM_ID => {
                                <#nullable_ty as fory_core::serializer::Serializer>::read(context)
                                .map_err(fory_core::error::Error::from)?
                            }
                            _ => unreachable!(),
                        }
                    };
                    Ok::<#ty, fory_core::error::Error>(res2)
                }
            }
        };
        let mut cur_remote_nullable_type = quote! { remote_nullable_type };
        for idx in generic_path {
            cur_remote_nullable_type = quote! {
                #cur_remote_nullable_type.generics.get(#idx as usize).unwrap()
            };
        }
        quote! {
            let cur_remote_nullable_type = &#cur_remote_nullable_type;
            let ref_flag = context.reader.i8();
            #tokens
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
    } else {
        quote!(#ty).to_string()
    }
}

pub(super) fn parse_generic_tree(ty: &Type) -> TypeNode {
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
    if node.name == "Option" && node.generics.first().unwrap().name == "Option" {
        return quote! {
            compile_error!("adjacent Options are not supported");
        };
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
            context.fory
        }
    } else {
        quote! {
            fory
        }
    };
    let get_type_id = if node.name == "Option" {
        let option_type_id = TypeId::ForyOption as u32;
        quote! { #option_type_id }
    } else if let Some(ts) = primitive_vec {
        ts
    } else {
        quote! {
            <#ty as fory_core::serializer::Serializer>::get_type_id(#param)
        }
    };
    quote! {
        fory_core::meta::FieldType::new(
            #get_type_id,
            vec![#(#children_tokens),*] as Vec<fory_core::meta::FieldType>
        )
    }
}
