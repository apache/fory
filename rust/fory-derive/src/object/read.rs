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

use proc_macro2::{Ident, TokenStream};
use quote::{format_ident, quote};
use syn::Field;
use syn::Type;

use super::util::{generic_tree_to_tokens, parse_generic_tree};

fn create_private_field_name(field: &Field) -> Ident {
    format_ident!("_{}", field.ident.as_ref().expect(""))
}

fn bind(fields: &[&Field]) -> Vec<TokenStream> {
    fields
        .iter()
        .map(|field| {
            let ty = &field.ty;
            let var_name = create_private_field_name(field);
            quote! {
                let mut #var_name: Option<#ty> = None;
            }
        })
        .collect()
}

fn create(fields: &[&Field]) -> Vec<TokenStream> {
    fields
        .iter()
        .map(|field| {
            let name = &field.ident;
            let var_name = create_private_field_name(field);
            quote! {
                #name: #var_name.unwrap_or_default()
            }
        })
        .collect()
}

fn read(fields: &[&Field]) -> TokenStream {
    let assign_stmt = fields.iter().map(|field| {
        let ty = &field.ty;
        let name = &field.ident;
        quote! {
            #name: <#ty as fory_core::serializer::Serializer>::deserialize(context)?
        }
    });

    quote! {
        fn read(context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, fory_core::error::Error> {
            Ok(Self {
                #(#assign_stmt),*
            })
        }
    }
}

fn deserialize_compatible(fields: &[&Field]) -> TokenStream {
    let pattern_items = fields.iter().map(|field| {
        let ty = &field.ty;
        let var_name = create_private_field_name(field);

        let generic_tree = parse_generic_tree(ty);
        let generic_token = generic_tree_to_tokens(&generic_tree, true);

        let field_name_str = field.ident.as_ref().unwrap().to_string();

        let base_ty = match &ty {
            Type::Path(type_path) => {
                &type_path.path.segments.first().unwrap().ident
            }
            _ => panic!("Unsupported type"),
        };
        quote! {
            (ident, field_type)
                if ident == #field_name_str
                    && *field_type == #generic_token
            => {
                #var_name = Some(<#ty as fory_core::serializer::Serializer>::deserialize(context).unwrap_or_else(|_err| {
                    // println!("skip deserialize {:?}", ident);
                    #base_ty::default()
                }));
            }
        }
    });
    let bind: Vec<TokenStream> = bind(fields);
    let create: Vec<TokenStream> = create(fields);
    quote! {
        let ref_flag = context.reader.i8();
        if ref_flag == (fory_core::types::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::types::RefFlag::RefValue as i8) {
            let meta_index = context.reader.i16() as usize;
            let meta = context.get_meta(meta_index).clone();
            let fields = meta.get_field_infos();
            #(#bind)*
            for _field in fields.iter() {
                match (_field.field_name.as_str(), &_field.field_type) {
                    #(#pattern_items),*
                    _ => {
                        // skip bytes
                        println!("no need to deserialize {:?}:{:?}", _field.field_name.as_str(), _field.field_type);
                        let _ = context
                        .get_fory()
                        .get_type_resolver()
                        .get_harness((&_field.field_type).type_id as u32)
                        .unwrap_or_else(|| {
                            panic!("missing harness for type_id {}", _field.field_type.type_id);
                        })
                        .get_deserializer()(context);
                    }
                }
            }
            Ok(Self {
                #(#create),*
            })
        } else if ref_flag == (fory_core::types::RefFlag::Null as i8) {
            Err(fory_core::error::AnyhowError::msg("Try to deserialize non-option type to null"))?
        } else if ref_flag == (fory_core::types::RefFlag::Ref as i8) {
            Err(fory_core::error::Error::Ref)
        } else {
            Err(fory_core::error::AnyhowError::msg("Unknown ref flag, value:{ref_flag}"))?
        }
    }
}

pub fn gen(fields: &[&Field]) -> TokenStream {
    let read_token_stream = read(fields);
    let compatible_token_stream = deserialize_compatible(fields);

    quote! {
        fn deserialize(context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, fory_core::error::Error> {
            match context.get_fory().get_mode() {
                fory_core::types::Mode::SchemaConsistent => {
                    fory_core::serializer::deserialize::<Self>(context)
                },
                fory_core::types::Mode::Compatible => {
                    #compatible_token_stream
                }
            }
        }
        #read_token_stream
    }
}
