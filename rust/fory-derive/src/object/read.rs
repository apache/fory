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
use syn::{Field, Type};

use super::util::{generic_tree_to_tokens, parse_generic_tree, NullableTypeNode};

fn create_private_field_name(field: &Field) -> Ident {
    format_ident!("_{}", field.ident.as_ref().expect(""))
}

fn create_deserialize_nullable_fn_name(field: &Field) -> Ident {
    format_ident!("_deserialize_nullable_{}", field.ident.as_ref().expect(""))
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
    // let assign_stmt = fields.iter().map(|field| {
    //     let ty = &field.ty;
    //     let name = &field.ident;
    //     quote! {
    //         #name: <#ty as fory_core::serializer::Serializer>::deserialize(context, true)?
    //     }
    // });
    let private_idents: Vec<Ident> = fields
        .iter()
        .map(|f| create_private_field_name(f))
        .collect();
    let sorted_deserialize = {
        // 声明私有变量
        let let_decls = fields
            .iter()
            .zip(private_idents.iter())
            .map(|(field, private_ident)| {
                let ty = &field.ty;
                quote! {
                    let mut #private_ident: #ty = Default::default();
                }
            });

        let match_ts = fields.iter().zip(private_idents.iter()).map(|(field, private_ident)| {
            let ty = &field.ty;
            let name_str = field.ident.as_ref().unwrap().to_string();
            quote! {
                #name_str => {
                    #private_ident = <#ty as fory_core::serializer::Serializer>::deserialize(context, true)?;
                }
            }
        });

        quote! {
            // 先声明变量
             #(#let_decls)*

            let sorted_field_names = <Self as fory_core::serializer::StructSerializer>::get_sorted_field_names(context.get_fory());
            for field_name in sorted_field_names {
                match field_name.as_str() {
                    #(#match_ts),*
                    , _ => unreachable!()
                }
            }
        }
    };
    let field_idents = fields
        .iter()
        .zip(private_idents.iter())
        .map(|(field, private_ident)| {
            let original_ident = &field.ident;
            quote! {
                #original_ident: #private_ident
            }
        });
    quote! {
        fn read(context: &mut fory_core::resolver::context::ReadContext, _is_field: bool) -> Result<Self, fory_core::error::Error> {
            let remote_type_id = context.reader.var_uint32();
            assert_eq!(remote_type_id, Self::get_type_id(context.get_fory()));
            if *context.get_fory().get_mode() == fory_core::types::Mode::Compatible {
                let _meta_index = context.reader.var_uint32();
            }
            // Ok(Self {
            //     #(#assign_stmt),*
            // })
            #sorted_deserialize
            Ok(Self {
                #(#field_idents),*
            })
        }
    }
}

fn deserialize_compatible(struct_ident: &Ident) -> TokenStream {
    quote! {
        let ref_flag = context.reader.i8();
        if ref_flag == (fory_core::types::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::types::RefFlag::RefValue as i8) {
            println!("deserializing here, {:?}", context.reader.slice_after_cursor());
            <#struct_ident as fory_core::serializer::StructSerializer>::read_compatible(context)
        } else if ref_flag == (fory_core::types::RefFlag::Null as i8) {
            Ok(Self::default())
            // Err(fory_core::error::AnyhowError::msg("Try to deserialize non-option type to null"))?
        } else if ref_flag == (fory_core::types::RefFlag::Ref as i8) {
            Err(fory_core::error::Error::Ref)
        } else {
            Err(fory_core::error::AnyhowError::msg("Unknown ref flag, value:{ref_flag}"))?
        }
    }
}

pub fn gen_deserialize_nullable(fields: &[&Field]) -> TokenStream {
    let func_tokens: Vec<TokenStream> = fields
        .iter()
        .map(|field| {
            let fn_name = create_deserialize_nullable_fn_name(field);
            let ty = &field.ty;
            let generic_tree = parse_generic_tree(ty);
            let nullable_generic_tree = NullableTypeNode::from(generic_tree);
            let deserialize_tokens = nullable_generic_tree.to_deserialize_tokens(&vec![]);
            quote! {
                fn #fn_name(
                    context: &mut fory_core::resolver::context::ReadContext,
                    local_nullable_type: &fory_core::meta::NullableFieldType,
                    remote_nullable_type: &fory_core::meta::NullableFieldType
                ) -> Result<#ty, fory_core::error::Error> {
                    // println!("remote:{:#?}", remote_nullable_type);
                    // println!("local:{:#?}", local_nullable_type);
                    #deserialize_tokens
                }
            }
        })
        .collect::<Vec<_>>();
    quote! {
        #(#func_tokens)*
    }
}

pub fn gen_read_compatible(fields: &[&Field], struct_ident: &Ident) -> TokenStream {
    let pattern_items = fields.iter().map(|field| {
        let ty = &field.ty;
        let var_name = create_private_field_name(field);
        let deserialize_nullable_fn_name = create_deserialize_nullable_fn_name(field);

        let generic_tree = parse_generic_tree(ty);
        // dbg!(&generic_tree);
        let generic_token = generic_tree_to_tokens(&generic_tree, true);

        let field_name_str = field.ident.as_ref().unwrap().to_string();
        let base_ty = match &ty {
            Type::Path(type_path) => {
                &type_path.path.segments.first().unwrap().ident
            }
            _ => panic!("Unsupported type"),
        };
        quote! {
            if _field.field_name.as_str() == #field_name_str {
                let local_field_type = #generic_token;
                if &_field.field_type == &local_field_type {
                    #var_name = Some(<#ty as fory_core::serializer::Serializer>::deserialize(context, true).unwrap_or_else(|_err| {
                        // same type, err means something wrong
                        panic!("Err at deserializing {:?}: {:?}", #field_name_str, _err);
                    }));
                } else {
                    let local_nullable_type = fory_core::meta::NullableFieldType::from(local_field_type.clone());
                    let remote_nullable_type = fory_core::meta::NullableFieldType::from(_field.field_type.clone());
                    if local_nullable_type != remote_nullable_type {
                        // set default and skip bytes
                        println!("Type not match, just skip: {}", #field_name_str);
                        fory_core::serializer::skip::skip_field_value(context, &remote_nullable_type, true).unwrap();
                        #var_name = Some(#base_ty::default());
                    } else {
                        println!("Try to deserialize: {}", #field_name_str);
                        #var_name = Some(
                            #struct_ident::#deserialize_nullable_fn_name(
                                context,
                                &local_nullable_type,
                                &remote_nullable_type
                            ).unwrap_or_else(|_err| {
                                // same nulable type, err means something wrong
                                panic!("Err at deserializing {:?}: {:?}", #field_name_str, _err);
                            })
                        );
                    }
                }
            }
        }
    });
    let bind: Vec<TokenStream> = bind(fields);
    let create: Vec<TokenStream> = create(fields);
    quote! {
        fn read_compatible(context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, fory_core::error::Error> {
            let remote_type_id = context.reader.var_uint32();
            let meta_index = context.reader.var_uint32();
            let meta = context.get_meta(meta_index as usize);
            let fields = {
                let meta = context.get_meta(meta_index as usize);
                meta.get_field_infos().clone()
            };
            #(#bind)*
            for _field in fields.iter() {
                #(#pattern_items else)* {
                    println!("skip {:?}:{:?}", _field.field_name.as_str(), _field.field_type);
                    let nullable_field_type = fory_core::meta::NullableFieldType::from(_field.field_type.clone());
                    fory_core::serializer::skip::skip_field_value(context, &nullable_field_type, true).unwrap();
                }
            }
            Ok(Self {
                #(#create),*
            })
        }
    }
}

pub fn gen(fields: &[&Field], struct_ident: &Ident) -> TokenStream {
    let read_token_stream = read(fields);
    let compatible_token_stream = deserialize_compatible(struct_ident);

    quote! {
        fn deserialize(context: &mut fory_core::resolver::context::ReadContext, is_field: bool) -> Result<Self, fory_core::error::Error> {
            match context.get_fory().get_mode() {
                fory_core::types::Mode::SchemaConsistent => {
                    fory_core::serializer::deserialize::<Self>(context, is_field)
                },
                fory_core::types::Mode::Compatible => {
                    #compatible_token_stream
                }
            }
        }
        #read_token_stream
    }
}
