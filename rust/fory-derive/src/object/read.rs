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

fn declare_var(fields: &[&Field]) -> Vec<TokenStream> {
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

fn assign_value(fields: &[&Field]) -> Vec<TokenStream> {
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

pub fn gen_read_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::read_type_info::<Self>(context, is_field)
    }
}

pub fn gen_read(fields: &[&Field]) -> TokenStream {
    // read way before:
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
    let sorted_deserialize = if fields.is_empty() {
        quote! {}
    } else {
        let declare_var_ts =
            fields
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
                    let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(context.get_fory());
                    #private_ident = fory_core::serializer::read_data::<#ty>(context, true, skip_ref_flag, false)?;
                }
            }
        });
        quote! {
             #(#declare_var_ts)*
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
        #sorted_deserialize
        Ok(Self {
            #(#field_idents),*
            // #(#assign_stmt),*
        })
    }
}

pub fn gen_deserialize(struct_ident: &Ident) -> TokenStream {
    quote! {
        let ref_flag = context.reader.i8();
        if ref_flag == (fory_core::types::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::types::RefFlag::RefValue as i8) {
            match context.get_fory().get_mode() {
                fory_core::types::Mode::SchemaConsistent => {
                    Self::read_type_info(context, false);
                    Self::read(context)
                },
                fory_core::types::Mode::Compatible => {
                    <#struct_ident as fory_core::serializer::StructSerializer>::read_compatible(context)
                },
                _ => unreachable!()
            }
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
                    let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(context.get_fory());
                    #var_name = Some(fory_core::serializer::read_data::<#ty>(context, true, skip_ref_flag, false).unwrap_or_else(|_err| {
                        // same type, err means something wrong
                        panic!("Err at deserializing {:?}: {:?}", #field_name_str, _err);
                    }));
                } else {
                    let local_nullable_type = fory_core::meta::NullableFieldType::from(local_field_type.clone());
                    let remote_nullable_type = fory_core::meta::NullableFieldType::from(_field.field_type.clone());
                    if local_nullable_type != remote_nullable_type {
                        // set default and skip bytes
                        println!("Type not match, just skip: {}", #field_name_str);
                        let read_ref_flag = fory_core::serializer::skip::get_read_ref_flag(&remote_nullable_type);
                        fory_core::serializer::skip::skip_field_value(context, &remote_nullable_type, read_ref_flag).unwrap();
                        #var_name = Some(#base_ty::default());
                    } else {
                        println!("Try to deserialize_compatible: {}", #field_name_str);
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
    let declare_ts: Vec<TokenStream> = declare_var(fields);
    let assign_ts: Vec<TokenStream> = assign_value(fields);
    quote! {
        let remote_type_id = context.reader.var_uint32();
        let meta_index = context.reader.var_uint32();
        let meta = context.get_meta(meta_index as usize);
        let fields = {
            let meta = context.get_meta(meta_index as usize);
            meta.get_field_infos().clone()
        };
        #(#declare_ts)*
        for _field in fields.iter() {
            #(#pattern_items else)* {
                println!("skip {:?}:{:?}", _field.field_name.as_str(), _field.field_type);
                let nullable_field_type = fory_core::meta::NullableFieldType::from(_field.field_type.clone());
                let read_ref_flag = fory_core::serializer::skip::get_read_ref_flag(&nullable_field_type);
                fory_core::serializer::skip::skip_field_value(context, &nullable_field_type, read_ref_flag).unwrap();
            }
        }
        Ok(Self {
            #(#assign_ts),*
        })
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
            let deserialize_tokens = nullable_generic_tree.to_deserialize_tokens(&vec![], true);
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
