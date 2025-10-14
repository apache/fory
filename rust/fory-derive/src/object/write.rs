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
    classify_trait_object_field, create_wrapper_types_arc, create_wrapper_types_rc, skip_ref_flag,
    StructField,
};
use proc_macro2::TokenStream;
use quote::quote;
use syn::Field;

pub fn gen_reserved_space(fields: &[&Field]) -> TokenStream {
    let reserved_size_expr: Vec<_> = fields.iter().map(|field| {
        let ty = &field.ty;
        match classify_trait_object_field(ty) {
            StructField::BoxDyn(_) => {
                quote! {
                    fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::RcDyn(trait_name) => {
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <#wrapper_ty as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::ArcDyn(trait_name) => {
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <#wrapper_ty as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::VecRc(trait_name) => {
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::VecArc(trait_name) => {
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <Vec<#wrapper_ty> as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::HashMapRc(key_ty, trait_name) => {
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::HashMapArc(key_ty, trait_name) => {
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::Forward => {
                quote! {
                    <#ty as fory_core::serializer::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
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
        fory_core::serializer::struct_::write_type_info::<Self>(fory, context, is_field)
    }
}

fn gen_write_field(field: &Field) -> TokenStream {
    let ty = &field.ty;
    let ident = &field.ident;
    match classify_trait_object_field(ty) {
        StructField::BoxDyn(_) => {
            quote! {
                {
                    let any_ref = self.#ident.as_any();
                    let concrete_type_id = any_ref.type_id();
                    let fory_type_id = fory.get_type_resolver()
                        .get_fory_type_id(concrete_type_id)
                        .ok_or_else(|| fory_core::error::Error::TypeError("Type not registered for trait object field".into()))?;

                    context.writer.write_i8(fory_core::types::RefFlag::NotNullValue as i8)?;
                    context.writer.write_varuint32(fory_type_id);

                    let harness = fory
                        .get_type_resolver()
                        .get_harness(fory_type_id)
                        .ok_or_else(|| fory_core::error::Error::TypeError("Harness not found for trait object field".into()))?;

                    let serializer_fn = harness.get_write_fn();
                    serializer_fn(any_ref, fory, context, true)?;
                }
            }
        }
        StructField::RcDyn(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                {
                    let wrapper = #wrapper_ty::from(self.#ident.clone() as std::rc::Rc<dyn #trait_ident>);
                    fory_core::serializer::Serializer::fory_write(&wrapper, fory, context, true)?;
                }
            }
        }
        StructField::ArcDyn(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                {
                    let wrapper = #wrapper_ty::from(self.#ident.clone() as std::sync::Arc<dyn #trait_ident>);
                    fory_core::serializer::Serializer::fory_write(&wrapper, fory, context, true)?;
                }
            }
        }
        StructField::VecRc(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                {
                    let wrapper_vec: Vec<#wrapper_ty> = self.#ident.iter()
                        .map(|item| #wrapper_ty::from(item.clone() as std::rc::Rc<dyn #trait_ident>))
                        .collect();
                    fory_core::serializer::Serializer::fory_write(&wrapper_vec, fory, context, true)?;
                }
            }
        }
        StructField::VecArc(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                {
                    let wrapper_vec: Vec<#wrapper_ty> = self.#ident.iter()
                        .map(|item| #wrapper_ty::from(item.clone() as std::sync::Arc<dyn #trait_ident>))
                        .collect();
                    fory_core::serializer::Serializer::fory_write(&wrapper_vec, fory, context, true)?;
                }
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                {
                    let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = self.#ident.iter()
                        .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::rc::Rc<dyn #trait_ident>)))
                        .collect();
                    fory_core::serializer::Serializer::fory_write(&wrapper_map, fory, context, true)?;
                }
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                {
                    let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = self.#ident.iter()
                        .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::sync::Arc<dyn #trait_ident>)))
                        .collect();
                    fory_core::serializer::Serializer::fory_write(&wrapper_map, fory, context, true)?;
                }
            }
        }
        StructField::Forward => {
            quote! {
                {
                    fory_core::serializer::Serializer::fory_write(&self.#ident, fory, context, true)?;
                }
            }
        }
        _ => {
            let skip_ref_flag = skip_ref_flag(ty);
            quote! {
                fory_core::serializer::write_ref_info_data::<#ty>(&self.#ident, fory, context, true, #skip_ref_flag, false)?;
            }
        }
    }
}

pub fn gen_write_data(fields: &[&Field]) -> TokenStream {
    let write_fields_ts: Vec<_> = fields.iter().map(|field| gen_write_field(field)).collect();
    quote! {
        #(#write_fields_ts)*
        Ok(())
    }
}

pub fn gen_write() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::write::<Self>(self, fory, context, is_field)
    }
}
