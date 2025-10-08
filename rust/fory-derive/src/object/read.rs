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

use super::util::{
    classify_trait_object_field, create_wrapper_types_arc, create_wrapper_types_rc,
    generic_tree_to_tokens, parse_generic_tree, NullableTypeNode, StructField,
};

fn create_private_field_name(field: &Field) -> Ident {
    format_ident!("_{}", field.ident.as_ref().expect(""))
}

fn create_read_nullable_fn_name(field: &Field) -> Ident {
    format_ident!("fory_read_nullable_{}", field.ident.as_ref().expect(""))
}

fn declare_var(fields: &[&Field]) -> Vec<TokenStream> {
    fields
        .iter()
        .map(|field| {
            let ty = &field.ty;
            let var_name = create_private_field_name(field);
            match classify_trait_object_field(ty) {
                StructField::BoxDyn(_)
                | StructField::RcDyn(_)
                | StructField::ArcDyn(_) => {
                    quote! {
                        let mut #var_name: #ty = <#ty as fory_core::serializer::ForyDefault>::fory_default();
                    }
                }
                _ => {
                    quote! {
                        let mut #var_name: Option<#ty> = None;
                    }
                }
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
            match classify_trait_object_field(&field.ty) {
                StructField::BoxDyn(_) | StructField::RcDyn(_) | StructField::ArcDyn(_) => {
                    quote! {
                        #name: #var_name
                    }
                }
                _ => {
                    quote! {
                        #name: #var_name.unwrap_or_default()
                    }
                }
            }
        })
        .collect()
}

fn gen_read_match_arm(field: &Field, private_ident: &Ident) -> TokenStream {
    let ty = &field.ty;
    let name_str = field.ident.as_ref().unwrap().to_string();

    match classify_trait_object_field(ty) {
        StructField::BoxDyn(trait_name) => {
            let from_any_fn = format_ident!("from_any_internal_{}", trait_name);
            let helper_mod = format_ident!("__fory_trait_helpers_{}", trait_name);
            quote! {
                #name_str => {
                    let ref_flag = context.reader.read_i8();
                    if ref_flag != fory_core::types::RefFlag::NotNullValue as i8 {
                        panic!("Expected NotNullValue for trait object field");
                    }

                    let fory_type_id = context.reader.read_varuint32();

                    let harness = context.get_fory()
                        .get_type_resolver()
                        .get_harness(fory_type_id)
                        .expect("Type not registered for trait object field");

                    let deserializer_fn = harness.get_read_fn();
                    let any_box = deserializer_fn(context, true, false)?;

                    let base_type_id = fory_type_id >> 8;
                    #private_ident = #helper_mod::#from_any_fn(any_box, base_type_id)?;
                }
            }
        }
        StructField::RcDyn(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(context, true)?;
                    #private_ident = std::rc::Rc::<dyn #trait_ident>::from(wrapper);
                }
            }
        }
        StructField::ArcDyn(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(context, true)?;
                    #private_ident = std::sync::Arc::<dyn #trait_ident>::from(wrapper);
                }
            }
        }
        StructField::VecRc(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true)?;
                    #private_ident = Some(wrapper_vec.into_iter()
                        .map(|w| std::rc::Rc::<dyn #trait_ident>::from(w))
                        .collect());
                }
            }
        }
        StructField::VecArc(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true)?;
                    #private_ident = Some(wrapper_vec.into_iter()
                        .map(|w| std::sync::Arc::<dyn #trait_ident>::from(w))
                        .collect());
                }
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true)?;
                    #private_ident = Some(wrapper_map.into_iter()
                        .map(|(k, v)| (k, std::rc::Rc::<dyn #trait_ident>::from(v)))
                        .collect());
                }
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true)?;
                    #private_ident = Some(wrapper_map.into_iter()
                        .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                        .collect());
                }
            }
        }
        StructField::Forward => {
            quote! {
                #name_str => {
                    #private_ident = Some(fory_core::serializer::Serializer::fory_read(context, true)?);
                }
            }
        }
        _ => {
            quote! {
                #name_str => {
                    let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(context.get_fory());
                    #private_ident = Some(fory_core::serializer::read_ref_info_data::<#ty>(context, true, skip_ref_flag, false)?);
                }
            }
        }
    }
}

pub fn gen_read_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::read_type_info::<Self>(context, is_field)
    }
}

pub fn gen_read_data(fields: &[&Field]) -> TokenStream {
    let private_idents: Vec<Ident> = fields
        .iter()
        .map(|f| create_private_field_name(f))
        .collect();
    let sorted_read = if fields.is_empty() {
        quote! {}
    } else {
        let declare_var_ts =
            fields
                .iter()
                .zip(private_idents.iter())
                .map(|(field, private_ident)| {
                    let ty = &field.ty;
                    match classify_trait_object_field(ty) {
                        StructField::BoxDyn(_)
                        | StructField::RcDyn(_)
                        | StructField::ArcDyn(_) => {
                            quote! {
                                let mut #private_ident: #ty = <#ty as fory_core::serializer::ForyDefault>::fory_default();
                            }
                        }
                        _ => {
                            quote! {
                                let mut #private_ident: Option<#ty> = None;
                            }
                        }
                    }
                });
        let match_ts: Vec<_> = fields
            .iter()
            .zip(private_idents.iter())
            .map(|(field, private_ident)| gen_read_match_arm(field, private_ident))
            .collect();
        #[cfg(not(feature = "fields-loop-unroll"))]
        let loop_ts = quote! {
            for field_name in sorted_field_names {
                match field_name.as_str() {
                    #(#match_ts),*
                    , _ => unreachable!()
                }
            }
        };
        #[cfg(feature = "fields-loop-unroll")]
        let loop_ts = {
            let loop_item_ts = fields.iter().enumerate().map(|(i, _field)| {
                let idx = syn::Index::from(i);
                quote! {
                    let field_name = sorted_field_names.get(#idx).unwrap();
                    match field_name.as_str() {
                        #(#match_ts),*
                        , _ => { unreachable!() }
                    }
                }
            });
            quote! {
                println!("enable");
                #(#loop_item_ts)*
            }
        };
        quote! {
             #(#declare_var_ts)*
            let sorted_field_names = <Self as fory_core::serializer::StructSerializer>::fory_get_sorted_field_names(context.get_fory());
            #loop_ts
        }
    };
    let field_idents = fields
        .iter()
        .zip(private_idents.iter())
        .map(|(field, private_ident)| {
            let original_ident = &field.ident;
            let ty = &field.ty;
            match classify_trait_object_field(ty) {
                StructField::BoxDyn(_) | StructField::RcDyn(_) | StructField::ArcDyn(_) => {
                    quote! {
                        #original_ident: #private_ident
                    }
                }
                _ => {
                    quote! {
                        #original_ident: #private_ident.unwrap_or_default()
                    }
                }
            }
        });
    quote! {
        #sorted_read
        Ok(Self {
            #(#field_idents),*
        })
    }
}

fn gen_read_compatible_match_arm(field: &Field, var_name: &Ident) -> TokenStream {
    let ty = &field.ty;
    let field_name_str = field.ident.as_ref().unwrap().to_string();

    match classify_trait_object_field(ty) {
        StructField::BoxDyn(trait_name) => {
            let from_any_fn = format_ident!("from_any_internal_{}", trait_name);
            let helper_mod = format_ident!("__fory_trait_helpers_{}", trait_name);
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let ref_flag = context.reader.read_i8();
                    if ref_flag != fory_core::types::RefFlag::NotNullValue as i8 {
                        panic!("Expected NotNullValue for trait object field");
                    }
                    let fory_type_id = context.reader.read_varuint32();
                    let harness = context.get_fory()
                        .get_type_resolver()
                        .get_harness(fory_type_id)
                        .expect("Type not registered for trait object field");
                    let deserializer_fn = harness.get_read_fn();
                    let any_box = deserializer_fn(context, true, false).unwrap();
                    let base_type_id = fory_type_id >> 8;
                    #var_name = #helper_mod::#from_any_fn(any_box, base_type_id).unwrap();
                }
            }
        }
        StructField::RcDyn(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                    #var_name = Some(std::rc::Rc::<dyn #trait_ident>::from(wrapper));
                }
            }
        }
        StructField::ArcDyn(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                    #var_name = Some(std::sync::Arc::<dyn #trait_ident>::from(wrapper));
                }
            }
        }
        StructField::VecRc(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                    #var_name = Some(wrapper_vec.into_iter()
                        .map(|w| std::rc::Rc::<dyn #trait_ident>::from(w))
                        .collect());
                }
            }
        }
        StructField::VecArc(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                    #var_name = Some(wrapper_vec.into_iter()
                        .map(|w| std::sync::Arc::<dyn #trait_ident>::from(w))
                        .collect());
                }
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                    #var_name = Some(wrapper_map.into_iter()
                        .map(|(k, v)| (k, std::rc::Rc::<dyn #trait_ident>::from(v)))
                        .collect());
                }
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                    #var_name = Some(wrapper_map.into_iter()
                        .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                        .collect());
                }
            }
        }
        StructField::ContainsTraitObject => {
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(context.get_fory());
                    #var_name = Some(fory_core::serializer::read_ref_info_data::<#ty>(context, true, skip_ref_flag, false).unwrap());
                }
            }
        }
        StructField::Forward => {
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    #var_name = Some(fory_core::serializer::Serializer::fory_read(context, true).unwrap());
                }
            }
        }
        StructField::None => {
            let generic_tree = parse_generic_tree(ty);
            let generic_token = generic_tree_to_tokens(&generic_tree, true);
            let read_nullable_fn_name = create_read_nullable_fn_name(field);

            let _base_ty = match &ty {
                Type::Path(type_path) => &type_path.path.segments.first().unwrap().ident,
                _ => panic!("Unsupported type"),
            };
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let local_field_type = #generic_token;
                    if &_field.field_type == &local_field_type {
                        let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(context.get_fory());
                        #var_name = Some(fory_core::serializer::read_ref_info_data::<#ty>(context, true, skip_ref_flag, false).unwrap_or_else(|_err| {
                            panic!("Err at deserializing {:?}: {:?}", #field_name_str, _err);
                        }));
                    } else {
                        let local_nullable_type = fory_core::meta::NullableFieldType::from(local_field_type.clone());
                        let remote_nullable_type = fory_core::meta::NullableFieldType::from(_field.field_type.clone());
                        if local_nullable_type != remote_nullable_type {
                            println!("Type not match, just skip: {}", #field_name_str);
                            let read_ref_flag = fory_core::serializer::skip::get_read_ref_flag(&remote_nullable_type);
                            fory_core::serializer::skip::skip_field_value(context, &remote_nullable_type, read_ref_flag).unwrap();
                            #var_name = Some(<#ty as fory_core::serializer::ForyDefault>::fory_default());
                        } else {
                            println!("Try to deserialize_compatible: {}", #field_name_str);
                            #var_name = Some(
                                Self::#read_nullable_fn_name(
                                    context,
                                    &local_nullable_type,
                                    &remote_nullable_type
                                ).unwrap_or_else(|_err| {
                                    panic!("Err at deserializing {:?}: {:?}", #field_name_str, _err);
                                })
                            );
                        }
                    }
                }
            }
        }
    }
}

pub fn gen_read(struct_ident: &Ident) -> TokenStream {
    quote! {
        let ref_flag = context.reader.read_i8();
        if ref_flag == (fory_core::types::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::types::RefFlag::RefValue as i8) {
            match context.get_fory().get_mode() {
                fory_core::types::Mode::SchemaConsistent => {
                    <Self as fory_core::serializer::Serializer>::fory_read_type_info(context, false);
                    <Self as fory_core::serializer::Serializer>::fory_read_data(context, false)
                },
                fory_core::types::Mode::Compatible => {
                    <#struct_ident as fory_core::serializer::Serializer>::fory_read_compatible(context)
                },
                _ => unreachable!()
            }
        } else if ref_flag == (fory_core::types::RefFlag::Null as i8) {
            Ok(<Self as fory_core::serializer::ForyDefault>::fory_default())
            // Err(fory_core::error::AnyhowError::msg("Try to read non-option type to null"))?
        } else if ref_flag == (fory_core::types::RefFlag::Ref as i8) {
            Err(fory_core::error::Error::Ref)
        } else {
            Err(fory_core::error::AnyhowError::msg("Unknown ref flag, value:{ref_flag}"))?
        }
    }
}

pub fn gen_read_compatible(fields: &[&Field]) -> TokenStream {
    let pattern_items = fields.iter().map(|field| {
        let var_name = create_private_field_name(field);
        gen_read_compatible_match_arm(field, &var_name)
    });
    let declare_ts: Vec<TokenStream> = declare_var(fields);
    let assign_ts: Vec<TokenStream> = assign_value(fields);
    quote! {
        let remote_type_id = context.reader.read_varuint32();
        let meta_index = context.reader.read_varuint32();
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

pub fn gen_read_nullable(fields: &[&Field]) -> TokenStream {
    let func_tokens: Vec<TokenStream> = fields
        .iter()
        .filter_map(|field| {
            let ty = &field.ty;
            match classify_trait_object_field(ty) {
                StructField::None => {
                    let fn_name = create_read_nullable_fn_name(field);
                    let generic_tree = parse_generic_tree(ty);
                    let nullable_generic_tree = NullableTypeNode::from(generic_tree);
                    let read_tokens = nullable_generic_tree.to_read_tokens(&vec![], true);
                    Some(quote! {
                        fn #fn_name(
                            context: &mut fory_core::resolver::context::ReadContext,
                            local_nullable_type: &fory_core::meta::NullableFieldType,
                            remote_nullable_type: &fory_core::meta::NullableFieldType
                        ) -> Result<#ty, fory_core::error::Error> {
                            #read_tokens
                        }
                    })
                }
                _ => None,
            }
        })
        .collect::<Vec<_>>();
    quote! {
        #(#func_tokens)*
    }
}
