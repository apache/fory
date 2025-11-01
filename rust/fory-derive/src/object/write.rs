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
    classify_trait_object_field, compute_struct_version_hash, create_wrapper_types_arc,
    create_wrapper_types_rc, get_struct_name, get_type_id_by_type_ast, is_debug_enabled,
    is_skip_field, should_skip_type_info_for_field, skip_ref_flag, StructField,
};
use fory_core::types::TypeId;
use proc_macro2::{Ident, TokenStream};
use quote::quote;
use syn::Field;

pub fn gen_reserved_space(fields: &[&Field]) -> TokenStream {
    let reserved_size_expr: Vec<_> = fields.iter().map(|field| {
        let ty = &field.ty;
        match classify_trait_object_field(ty) {
            StructField::BoxDyn => {
                quote! {
                    fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::RcDyn(trait_name) => {
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <#wrapper_ty as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::ArcDyn(trait_name) => {
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <#wrapper_ty as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::VecRc(trait_name) => {
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <Vec<#wrapper_ty> as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::VecArc(trait_name) => {
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <Vec<#wrapper_ty> as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::HashMapRc(key_ty, trait_name) => {
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::HashMapArc(key_ty, trait_name) => {
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                quote! {
                    <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::Forward => {
                quote! {
                    <#ty as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            _ => {
                quote! {
                    <#ty as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
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
        fory_core::serializer::struct_::write_type_info::<Self>(context)
    }
}

pub fn gen_write_field(field: &Field, ident: &Ident, use_self: bool) -> TokenStream {
    let ty = &field.ty;
    let value_ts = if use_self {
        quote! { self.#ident }
    } else {
        quote! { #ident }
    };
    let base = match classify_trait_object_field(ty) {
        StructField::BoxDyn => {
            quote! {
                <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, true, true, false)?;
            }
        }
        StructField::RcDyn(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = #wrapper_ty::from(#value_ts.clone() as std::rc::Rc<dyn #trait_ident>);
                <#wrapper_ty as fory_core::Serializer>::fory_write(&wrapper, context, true, true, false)?;
            }
        }
        StructField::ArcDyn(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = #wrapper_ty::from(#value_ts.clone() as std::sync::Arc<dyn #trait_ident>);
                <#wrapper_ty as fory_core::Serializer>::fory_write(&wrapper, context, true, true, false)?;
            }
        }
        StructField::VecRc(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec: Vec<#wrapper_ty> = #value_ts.iter()
                    .map(|item| #wrapper_ty::from(item.clone() as std::rc::Rc<dyn #trait_ident>))
                    .collect();
                <Vec<#wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_vec, context, true, false, true)?;
            }
        }
        StructField::VecArc(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec: Vec<#wrapper_ty> = #value_ts.iter()
                    .map(|item| #wrapper_ty::from(item.clone() as std::sync::Arc<dyn #trait_ident>))
                    .collect();
                <Vec<#wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_vec, context, true, false, true)?;
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = #value_ts.iter()
                    .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::rc::Rc<dyn #trait_ident>)))
                    .collect();
                <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_map, context, true, false, true)?;
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = #value_ts.iter()
                    .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::sync::Arc<dyn #trait_ident>)))
                    .collect();
                <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_map, context, true, false, true)?;
            }
        }
        StructField::Forward => {
            quote! {
                <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, true, true, false)?;
            }
        }
        _ => {
            let skip_ref_flag = skip_ref_flag(ty);
            let skip_type_info = should_skip_type_info_for_field(ty);
            let type_id = get_type_id_by_type_ast(ty);
            if type_id == TypeId::LIST as u32
                || type_id == TypeId::SET as u32
                || type_id == TypeId::MAP as u32
            {
                quote! {
                    <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, true, false, true)?;
                }
            } else {
                // Known types (primitives, strings, collections) - skip type info at compile time
                // For custom types that we can't determine at compile time (like enums),
                // we need to check at runtime whether to skip type info
                if skip_type_info {
                    if skip_ref_flag {
                        quote! {
                            <#ty as fory_core::Serializer>::fory_write_data(&#value_ts, context)?;
                        }
                    } else {
                        quote! {
                            <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, true, false, false)?;
                        }
                    }
                } else if skip_ref_flag {
                    quote! {
                        let need_type_info = fory_core::serializer::util::field_need_write_type_info::<#ty>();
                        <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, false, need_type_info, false)?;
                    }
                } else {
                    quote! {
                        let need_type_info = fory_core::serializer::util::field_need_write_type_info::<#ty>();
                        <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, true, need_type_info, false)?;
                    }
                }
            }
        }
    };

    if is_debug_enabled() && use_self {
        let struct_name = get_struct_name().expect("struct context not set");
        let struct_name_lit = syn::LitStr::new(&struct_name, proc_macro2::Span::call_site());
        let field_name = field.ident.as_ref().unwrap().to_string();
        let field_name_lit = syn::LitStr::new(&field_name, proc_macro2::Span::call_site());
        quote! {
            fory_core::serializer::struct_::struct_before_write_field(
                #struct_name_lit,
                #field_name_lit,
                (&self.#ident) as &dyn std::any::Any,
                context,
            );
            #base
            fory_core::serializer::struct_::struct_after_write_field(
                #struct_name_lit,
                #field_name_lit,
                (&self.#ident) as &dyn std::any::Any,
                context,
            );
        }
    } else {
        base
    }
}

pub fn gen_write_data(fields: &[&Field]) -> TokenStream {
    let write_fields_ts: Vec<_> = fields
        .iter()
        .filter(|field| !is_skip_field(field))
        .map(|field| {
            let ident = field.ident.as_ref().unwrap();
            gen_write_field(field, ident, true)
        })
        .collect();

    let version_hash = compute_struct_version_hash(fields);
    quote! {
        // Write version hash when class version checking is enabled
        if context.is_check_struct_version() {
            context.writer.write_i32(#version_hash);
        }
        #(#write_fields_ts)*
        Ok(())
    }
}

pub fn gen_write() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::write::<Self>(self, context, write_ref_info, write_type_info)
    }
}
