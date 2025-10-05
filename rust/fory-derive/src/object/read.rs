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
use crate::util::{
    detect_collection_with_trait_object, is_arc_dyn_trait, is_box_dyn_trait, is_rc_dyn_trait,
    CollectionTraitInfo,
};
use syn::{GenericArgument, PathArguments};

/// Check if a type contains trait objects anywhere in its structure
fn contains_trait_object(ty: &Type) -> bool {
    match ty {
        Type::TraitObject(_) => true,
        Type::Path(type_path) => {
            // Check for Box<dyn Trait>, Rc<dyn Trait>, Arc<dyn Trait>
            if is_box_dyn_trait(ty).is_some()
                || is_rc_dyn_trait(ty).is_some()
                || is_arc_dyn_trait(ty).is_some()
            {
                return true;
            }

            // Check generics recursively
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
            if is_box_dyn_trait(ty).is_some()
                || is_rc_dyn_trait(ty).is_some()
                || is_arc_dyn_trait(ty).is_some()
            {
                quote! {
                    let mut #var_name: #ty = Default::default();
                }
            } else {
                quote! {
                    let mut #var_name: Option<#ty> = None;
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
            if is_box_dyn_trait(&field.ty).is_some()
                || is_rc_dyn_trait(&field.ty).is_some()
                || is_arc_dyn_trait(&field.ty).is_some()
            {
                quote! {
                    #name: #var_name
                }
            } else {
                quote! {
                    #name: #var_name.unwrap_or_default()
                }
            }
        })
        .collect()
}

pub fn gen_read_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::read_type_info::<Self>(context, is_field)
    }
}

pub fn gen_read_data(fields: &[&Field]) -> TokenStream {
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
    let sorted_read = if fields.is_empty() {
        quote! {}
    } else {
        let declare_var_ts =
            fields
                .iter()
                .zip(private_idents.iter())
                .map(|(field, private_ident)| {
                    let ty = &field.ty;
                    if is_box_dyn_trait(ty).is_some()
                        || is_rc_dyn_trait(ty).is_some()
                        || is_arc_dyn_trait(ty).is_some()
                    {
                        quote! {
                            let mut #private_ident: #ty = Default::default();
                        }
                    } else {
                        quote! {
                            let mut #private_ident: Option<#ty> = None;
                        }
                    }
                });
        let match_ts = fields.iter().zip(private_idents.iter()).map(|(field, private_ident)| {
            let ty = &field.ty;
            let name_str = field.ident.as_ref().unwrap().to_string();

            if is_box_dyn_trait(ty).is_some() {
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

                        let deserializer_fn = harness.get_deserializer();
                        let any_box = deserializer_fn(context, true, false)?;

                        let base_type_id = fory_type_id >> 8;
                        #private_ident = __fory_trait_helpers::from_any_internal(any_box, base_type_id)?;
                    }
                }
            } else if let Some((_, trait_name)) = is_rc_dyn_trait(ty) {
                let wrapper_ty = quote::format_ident!("{}Rc", trait_name);
                let trait_ident = quote::format_ident!("{}", trait_name);
                quote! {
                    #name_str => {
                        let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(context, true)?;
                        #private_ident = std::rc::Rc::<dyn #trait_ident>::from(wrapper);
                    }
                }
            } else if let Some((_, trait_name)) = is_arc_dyn_trait(ty) {
                let wrapper_ty = quote::format_ident!("{}Arc", trait_name);
                let trait_ident = quote::format_ident!("{}", trait_name);
                quote! {
                    #name_str => {
                        let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(context, true)?;
                        #private_ident = std::sync::Arc::<dyn #trait_ident>::from(wrapper);
                    }
                }
            } else if let Some(collection_info) = detect_collection_with_trait_object(ty) {
                match collection_info {
                    CollectionTraitInfo::VecRc(trait_name) => {
                        let wrapper_ty = quote::format_ident!("{}Rc", trait_name);
                        let trait_ident = quote::format_ident!("{}", trait_name);
                        quote! {
                            #name_str => {
                                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true)?;
                                #private_ident = Some(wrapper_vec.into_iter()
                                    .map(|w| std::rc::Rc::<dyn #trait_ident>::from(w))
                                    .collect());
                            }
                        }
                    }
                    CollectionTraitInfo::VecArc(trait_name) => {
                        let wrapper_ty = quote::format_ident!("{}Arc", trait_name);
                        let trait_ident = quote::format_ident!("{}", trait_name);
                        quote! {
                            #name_str => {
                                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true)?;
                                #private_ident = Some(wrapper_vec.into_iter()
                                    .map(|w| std::sync::Arc::<dyn #trait_ident>::from(w))
                                    .collect());
                            }
                        }
                    }
                    CollectionTraitInfo::HashMapRc(key_ty, trait_name) => {
                        let wrapper_ty = quote::format_ident!("{}Rc", trait_name);
                        let trait_ident = quote::format_ident!("{}", trait_name);
                        quote! {
                            #name_str => {
                                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true)?;
                                #private_ident = Some(wrapper_map.into_iter()
                                    .map(|(k, v)| (k, std::rc::Rc::<dyn #trait_ident>::from(v)))
                                    .collect());
                            }
                        }
                    }
                    CollectionTraitInfo::HashMapArc(key_ty, trait_name) => {
                        let wrapper_ty = quote::format_ident!("{}Arc", trait_name);
                        let trait_ident = quote::format_ident!("{}", trait_name);
                        quote! {
                            #name_str => {
                                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true)?;
                                #private_ident = Some(wrapper_map.into_iter()
                                    .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                                    .collect());
                            }
                        }
                    }
                }
            } else {
                quote! {
                    #name_str => {
                        let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(context.get_fory());
                        #private_ident = Some(fory_core::serializer::read_ref_info_data::<#ty>(context, true, skip_ref_flag, false)?);
                    }
                }
            }
        });
        quote! {
             #(#declare_var_ts)*
            let sorted_field_names = <Self as fory_core::serializer::StructSerializer>::fory_get_sorted_field_names(context.get_fory());
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
            let ty = &field.ty;
            if is_box_dyn_trait(ty).is_some()
                || is_rc_dyn_trait(ty).is_some()
                || is_arc_dyn_trait(ty).is_some()
            {
                quote! {
                    #original_ident: #private_ident
                }
            } else {
                quote! {
                    #original_ident: #private_ident.unwrap_or_default()
                }
            }
        });
    quote! {
        #sorted_read
        Ok(Self {
            #(#field_idents),*
            // #(#assign_stmt),*
        })
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
                    <#struct_ident as fory_core::serializer::StructSerializer>::fory_read_compatible(context)
                },
                _ => unreachable!()
            }
        } else if ref_flag == (fory_core::types::RefFlag::Null as i8) {
            Ok(Self::default())
            // Err(fory_core::error::AnyhowError::msg("Try to read non-option type to null"))?
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
        let read_nullable_fn_name = create_read_nullable_fn_name(field);

        if is_box_dyn_trait(ty).is_some() {
            let field_name_str = field.ident.as_ref().unwrap().to_string();
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
                    let deserializer_fn = harness.get_deserializer();
                    let any_box = deserializer_fn(context, true, false).unwrap();
                    let base_type_id = fory_type_id >> 8;
                    #var_name = __fory_trait_helpers::from_any_internal(any_box, base_type_id).unwrap();
                }
            }
        } else if let Some((_, trait_name)) = is_rc_dyn_trait(ty) {
            let wrapper_ty = quote::format_ident!("{}Rc", trait_name);
            let trait_ident = quote::format_ident!("{}", trait_name);
            let field_name_str = field.ident.as_ref().unwrap().to_string();
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                    #var_name = Some(std::rc::Rc::<dyn #trait_ident>::from(wrapper));
                }
            }
        } else if let Some((_, trait_name)) = is_arc_dyn_trait(ty) {
            let wrapper_ty = quote::format_ident!("{}Arc", trait_name);
            let trait_ident = quote::format_ident!("{}", trait_name);
            let field_name_str = field.ident.as_ref().unwrap().to_string();
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    let wrapper = <#wrapper_ty as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                    #var_name = Some(std::sync::Arc::<dyn #trait_ident>::from(wrapper));
                }
            }
        } else if let Some(collection_info) = detect_collection_with_trait_object(ty) {
            let field_name_str = field.ident.as_ref().unwrap().to_string();
            match collection_info {
                CollectionTraitInfo::VecRc(trait_name) => {
                    let wrapper_ty = quote::format_ident!("{}Rc", trait_name);
                    let trait_ident = quote::format_ident!("{}", trait_name);
                    quote! {
                        if _field.field_name.as_str() == #field_name_str {
                            let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                            #var_name = Some(wrapper_vec.into_iter()
                                .map(|w| std::rc::Rc::<dyn #trait_ident>::from(w))
                                .collect());
                        }
                    }
                }
                CollectionTraitInfo::VecArc(trait_name) => {
                    let wrapper_ty = quote::format_ident!("{}Arc", trait_name);
                    let trait_ident = quote::format_ident!("{}", trait_name);
                    quote! {
                        if _field.field_name.as_str() == #field_name_str {
                            let wrapper_vec = <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                            #var_name = Some(wrapper_vec.into_iter()
                                .map(|w| std::sync::Arc::<dyn #trait_ident>::from(w))
                                .collect());
                        }
                    }
                }
                CollectionTraitInfo::HashMapRc(key_ty, trait_name) => {
                    let wrapper_ty = quote::format_ident!("{}Rc", trait_name);
                    let trait_ident = quote::format_ident!("{}", trait_name);
                    quote! {
                        if _field.field_name.as_str() == #field_name_str {
                            let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                            #var_name = Some(wrapper_map.into_iter()
                                .map(|(k, v)| (k, std::rc::Rc::<dyn #trait_ident>::from(v)))
                                .collect());
                        }
                    }
                }
                CollectionTraitInfo::HashMapArc(key_ty, trait_name) => {
                    let wrapper_ty = quote::format_ident!("{}Arc", trait_name);
                    let trait_ident = quote::format_ident!("{}", trait_name);
                    quote! {
                        if _field.field_name.as_str() == #field_name_str {
                            let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_read(context, true).unwrap();
                            #var_name = Some(wrapper_map.into_iter()
                                .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                                .collect());
                        }
                    }
                }
            }
        } else if contains_trait_object(ty) {
            // For complex types containing trait objects (like HashMap<String, Box<dyn Animal>>),
            // use standard deserialization instead of type tree parsing
            let field_name_str = field.ident.as_ref().unwrap().to_string();
            quote! {
                if _field.field_name.as_str() == #field_name_str {
                    // Skip type checking for fields containing trait objects
                    // and use standard deserialization
                    let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(context.get_fory());
                    #var_name = Some(fory_core::serializer::read_ref_info_data::<#ty>(context, true, skip_ref_flag, false).unwrap());
                }
            }
        } else {
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
                        #var_name = Some(fory_core::serializer::read_ref_info_data::<#ty>(context, true, skip_ref_flag, false).unwrap_or_else(|_err| {
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
                                #struct_ident::#read_nullable_fn_name(
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
        }
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
            if is_box_dyn_trait(ty).is_some() || contains_trait_object(ty) {
                // Skip both direct trait objects and complex types containing trait objects
                // They will be handled by standard deserialization
                None
            } else {
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
                        // println!("remote:{:#?}", remote_nullable_type);
                        // println!("local:{:#?}", local_nullable_type);
                        #read_tokens
                    }
                })
            }
        })
        .collect::<Vec<_>>();
    quote! {
        #(#func_tokens)*
    }
}
