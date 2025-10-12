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
    generic_tree_to_tokens, parse_generic_tree, skip_ref_flag, NullableTypeNode, StructField,
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
                StructField::ContainsTraitObject => {
                    quote! {
                        #name: #var_name.unwrap()
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

fn gen_read_field(field: &Field, private_ident: &Ident) -> TokenStream {
    let ty = &field.ty;
    match classify_trait_object_field(ty) {
        StructField::BoxDyn(trait_name) => {
            let from_any_fn = format_ident!("from_any_internal_{}", trait_name);
            let helper_mod = format_ident!("__fory_trait_helpers_{}", trait_name);
            quote! {
                let ref_flag = context.reader.read_i8();
                if ref_flag != fory_core::types::RefFlag::NotNullValue as i8 {
                    panic!("Expected NotNullValue for trait object field");
                }

                let fory_type_id = context.reader.read_varuint32();

                let harness = fory.get_type_resolver()
                    .get_harness(fory_type_id)
                    .expect("Type not registered for trait object field");

                let deserializer_fn = harness.get_read_fn();
                let any_box = deserializer_fn(fory, context, true, false)?;

                let base_type_id = fory_type_id >> 8;
                let #private_ident = #helper_mod::#from_any_fn(any_box, base_type_id)?;

            }
        }
        StructField::RcDyn(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(fory, context, true)?;
                let #private_ident = std::rc::Rc::<dyn #trait_ident>::from(wrapper);
            }
        }
        StructField::ArcDyn(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(fory, context, true)?;
                let #private_ident = std::sync::Arc::<dyn #trait_ident>::from(wrapper);
            }
        }
        StructField::VecRc(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(fory, context, true)?;
                let #private_ident = wrapper_vec.into_iter()
                    .map(|w| std::rc::Rc::<dyn #trait_ident>::from(w))
                    .collect();
            }
        }
        StructField::VecArc(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(fory, context, true)?;
                let #private_ident = wrapper_vec.into_iter()
                    .map(|w| std::sync::Arc::<dyn #trait_ident>::from(w))
                    .collect();
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(fory, context, true)?;
                let #private_ident = wrapper_map.into_iter()
                    .map(|(k, v)| (k, std::rc::Rc::<dyn #trait_ident>::from(v)))
                    .collect();
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(fory, context, true)?;
                let #private_ident = wrapper_map.into_iter()
                    .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                    .collect();
            }
        }
        StructField::Forward => {
            quote! {
                let #private_ident = fory_core::serializer::Serializer::fory_read(fory, context, true)?;
            }
        }
        _ => {
            let skip_ref_flag = skip_ref_flag(ty);
            quote! {
                let #private_ident = fory_core::serializer::read_ref_info_data::<#ty>(fory, context, true, #skip_ref_flag, false)?;
            }
        }
    }
}

pub fn gen_read_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::read_type_info::<Self>(fory, context, is_field)
    }
}

fn get_fields_loop_ts(fields: &[&Field]) -> TokenStream {
    let read_fields_ts: Vec<_> = fields
        .iter()
        .map(|field| {
            let private_ident = create_private_field_name(field);
            gen_read_field(field, &private_ident)
        })
        .collect();
    quote! {
        #(#read_fields_ts)*
    }
}

pub fn gen_read_data(fields: &[&Field]) -> TokenStream {
    let sorted_read = if fields.is_empty() {
        quote! {}
    } else {
        let loop_ts = get_fields_loop_ts(fields);
        quote! {
            #loop_ts
        }
    };
    let field_idents = fields.iter().map(|field| {
        let private_ident = create_private_field_name(field);
        let original_ident = &field.ident;
        quote! {
            #original_ident: #private_ident
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
                    let harness = fory.get_type_resolver()
                        .get_harness(fory_type_id)
                        .expect("Type not registered for trait object field");
                    let deserializer_fn = harness.get_read_fn();
                    let any_box = deserializer_fn(fory, context, true, false).unwrap();
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
                    let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(fory, context, true).unwrap();
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
                    let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(fory, context, true).unwrap();
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
                    let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(fory, context, true).unwrap();
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
                    let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(fory, context, true).unwrap();
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
                    let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(fory, context, true).unwrap();
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
                    let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(fory, context, true).unwrap();
                    #var_name = Some(wrapper_map.into_iter()
                        .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                        .collect());
                }
            }
        }
        StructField::ContainsTraitObject => {
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(fory);
                    #var_name = Some(fory_core::serializer::read_ref_info_data::<#ty>(fory, context, true, skip_ref_flag, false)?);
                }
            }
        }
        StructField::Forward => {
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    #var_name = Some(fory_core::serializer::Serializer::fory_read(fory, context, true).unwrap());
                }
            }
        }
        StructField::None => {
            let generic_tree = parse_generic_tree(ty);
            let generic_token = generic_tree_to_tokens(&generic_tree);
            let read_nullable_fn_name = create_read_nullable_fn_name(field);

            let _base_ty = match &ty {
                Type::Path(type_path) => &type_path.path.segments.first().unwrap().ident,
                _ => panic!("Unsupported type"),
            };
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let local_field_type = #generic_token;
                    if &_field.field_type == &local_field_type {
                        let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(fory);
                        #var_name = Some(fory_core::serializer::read_ref_info_data::<#ty>(fory, context, true, skip_ref_flag, false).unwrap_or_else(|_err| {
                            panic!("Err at deserializing {:?}: {:?}", #field_name_str, _err);
                        }));
                    } else {
                        let local_nullable_type = fory_core::meta::NullableFieldType::from(&local_field_type);
                        let remote_nullable_type = fory_core::meta::NullableFieldType::from(&_field.field_type);
                        if local_nullable_type != remote_nullable_type {
                            println!("Type not match, just skip: {}", #field_name_str);
                            let read_ref_flag = fory_core::serializer::skip::get_read_ref_flag(&remote_nullable_type);
                            fory_core::serializer::skip::skip_field_value(fory, context, &remote_nullable_type, read_ref_flag).unwrap();
                            #var_name = Some(<#ty as fory_core::serializer::ForyDefault>::fory_default());
                        } else {
                            println!("Try to deserialize_compatible: {}", #field_name_str);
                            #var_name = Some(
                                Self::#read_nullable_fn_name(
                                    fory,
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
            match fory.get_mode() {
                fory_core::types::Mode::SchemaConsistent => {
                    <Self as fory_core::serializer::Serializer>::fory_read_type_info(fory, context, false);
                    <Self as fory_core::serializer::Serializer>::fory_read_data(fory, context, false)
                },
                fory_core::types::Mode::Compatible => {
                    <#struct_ident as fory_core::serializer::Serializer>::fory_read_compatible(fory, context)
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

        let local_type_def = fory.get_type_resolver().get_type_info(std::any::TypeId::of::<Self>()).get_type_def();
        let high_bytes = &local_type_def[..8];
        let local_type_hash = i64::from_le_bytes(high_bytes.try_into().unwrap());
        if meta.get_hash() == local_type_hash {
            // fast path
            <Self as fory_core::serializer::Serializer>::fory_read_data(fory, context, false)
        } else {
            for _field in fields.iter() {
                #(#pattern_items else)* {
                    println!("skip {:?}:{:?}", _field.field_name.as_str(), _field.field_type);
                    let nullable_field_type = fory_core::meta::NullableFieldType::from(&_field.field_type);
                    let read_ref_flag = fory_core::serializer::skip::get_read_ref_flag(&nullable_field_type);
                    fory_core::serializer::skip::skip_field_value(fory, context, &nullable_field_type, read_ref_flag).unwrap();
                }
            }
            Ok(Self {
                #(#assign_ts),*
            })
        }
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
                            fory: &fory_core::fory::Fory,
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
