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

use super::util::{
    classify_trait_object_field, create_wrapper_types_arc, create_wrapper_types_rc,
    TraitObjectField,
};
use proc_macro2::TokenStream;
use quote::quote;
use syn::Field;

pub fn gen_reserved_space(fields: &[&Field]) -> TokenStream {
    let reserved_size_expr: Vec<_> = fields.iter().map(|field| {
        let ty = &field.ty;
        match classify_trait_object_field(ty) {
            TraitObjectField::BoxDyn(_) => {
                quote! {
                    fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            TraitObjectField::RcDyn(trait_name) => {
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <#wrapper_ty as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            TraitObjectField::ArcDyn(trait_name) => {
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <#wrapper_ty as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            TraitObjectField::VecRc(trait_name) => {
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            TraitObjectField::VecArc(trait_name) => {
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            TraitObjectField::HashMapRc(key_ty, trait_name) => {
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            TraitObjectField::HashMapArc(key_ty, trait_name) => {
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            _ => {
                quote! {
                    <#ty as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
        }
    }).collect();
    if reserved_size_expr.is_empty() {
        quote! { 0 }
    } else {
        quote! { #(#reserved_size_expr)+* }
    }
}

pub fn gen_write_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::write_type_info::<Self>(context, is_field)
    }
}

fn gen_write_match_arm(field: &Field) -> TokenStream {
    let ty = &field.ty;
    let ident = &field.ident;
    let name_str = ident.as_ref().unwrap().to_string();

    match classify_trait_object_field(ty) {
        TraitObjectField::BoxDyn(_) => {
            quote! {
                #name_str => {
                    let any_ref = self.#ident.as_any();
                    let concrete_type_id = any_ref.type_id();
                    let fory_type_id = context.get_fory()
                        .get_type_resolver()
                        .get_fory_type_id(concrete_type_id)
                        .expect("Type not registered for trait object field");

                    context.writer.write_i8(fory_core::types::RefFlag::NotNullValue as i8);
                    context.writer.write_varuint32(fory_type_id);

                    let harness = context.get_fory()
                        .get_type_resolver()
                        .get_harness(fory_type_id)
                        .expect("Harness not found for trait object field");

                    let serializer_fn = harness.get_write_fn();
                    serializer_fn(any_ref, context, true);
                }
            }
        }
        TraitObjectField::RcDyn(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper = #wrapper_ty::from(self.#ident.clone() as std::rc::Rc<dyn #trait_ident>);
                    fory_core::serializer::Serializer::fory_write(&wrapper, context, true);
                }
            }
        }
        TraitObjectField::ArcDyn(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper = #wrapper_ty::from(self.#ident.clone() as std::sync::Arc<dyn #trait_ident>);
                    fory_core::serializer::Serializer::fory_write(&wrapper, context, true);
                }
            }
        }
        TraitObjectField::VecRc(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper_vec: Vec<#wrapper_ty> = self.#ident.iter()
                        .map(|item| #wrapper_ty::from(item.clone() as std::rc::Rc<dyn #trait_ident>))
                        .collect();
                    fory_core::serializer::Serializer::fory_write(&wrapper_vec, context, true);
                }
            }
        }
        TraitObjectField::VecArc(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper_vec: Vec<#wrapper_ty> = self.#ident.iter()
                        .map(|item| #wrapper_ty::from(item.clone() as std::sync::Arc<dyn #trait_ident>))
                        .collect();
                    fory_core::serializer::Serializer::fory_write(&wrapper_vec, context, true);
                }
            }
        }
        TraitObjectField::HashMapRc(key_ty, trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = self.#ident.iter()
                        .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::rc::Rc<dyn #trait_ident>)))
                        .collect();
                    fory_core::serializer::Serializer::fory_write(&wrapper_map, context, true);
                }
            }
        }
        TraitObjectField::HashMapArc(key_ty, trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                #name_str => {
                    let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = self.#ident.iter()
                        .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::sync::Arc<dyn #trait_ident>)))
                        .collect();
                    fory_core::serializer::Serializer::fory_write(&wrapper_map, context, true);
                }
            }
        }
        _ => {
            quote! {
                #name_str => {
                    let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(context.get_fory());
                    fory_core::serializer::write_ref_info_data::<#ty>(&self.#ident, context, true, skip_ref_flag, false);
                }
            }
        }
    }
}

pub fn gen_write_data(fields: &[&Field]) -> TokenStream {
    let sorted_serialize = if fields.is_empty() {
        quote! {}
    } else {
        let match_ts = fields.iter().map(|field| gen_write_match_arm(field));
        quote! {
            let sorted_field_names = <Self as fory_core::serializer::StructSerializer>::fory_get_sorted_field_names(context.get_fory());
            for field_name in sorted_field_names {
                match field_name.as_str() {
                    #(#match_ts),*
                    , _ => {unreachable!()}
                }
            }
        }
    };
    quote! {
        #sorted_serialize
    }
}

pub fn gen_write() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::write::<Self>(self, context, is_field)
    }
}
