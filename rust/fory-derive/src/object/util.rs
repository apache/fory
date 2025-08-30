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

use fory_core::types::TypeId;
use proc_macro2::TokenStream;
use quote::quote;
use std::fmt;
use syn::{GenericArgument, PathArguments, Type};

pub(super) struct TypeNode {
    name: String,
    generics: Vec<TypeNode>,
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
    let children_tokens: Vec<TokenStream> = node
        .generics
        .iter()
        .map(|child| generic_tree_to_tokens(child, have_context))
        .collect();
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
        let option_type_id: i16 = TypeId::ForyOption.into();
        quote! {
            #option_type_id
        }
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
