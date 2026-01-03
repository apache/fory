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
    determine_field_ref_mode, extract_type_name, gen_struct_version_hash_ts, get_field_accessor,
    get_field_name, get_filtered_source_fields_iter, get_primitive_writer_method, get_struct_name,
    get_type_id_by_type_ast, is_debug_enabled, is_direct_primitive_type, FieldRefMode, StructField,
};
use crate::util::SourceField;
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
            StructField::VecBox(_) => {
                // Vec<Box<dyn Any>> uses standard Vec serialization
                quote! {
                    <#ty as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
                }
            }
            StructField::HashMapBox(_, _) => {
                // HashMap<K, Box<dyn Any>> uses standard HashMap serialization
                quote! {
                    <#ty as fory_core::Serializer>::fory_reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
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

/// Generate write code for a field using index-based access (supports tuple structs)
pub fn gen_write_field_with_index(field: &Field, index: usize, use_self: bool) -> TokenStream {
    let value_ts = get_field_accessor(field, index, use_self);
    let field_name = get_field_name(field, index);
    gen_write_field_impl(field, &value_ts, &field_name, use_self)
}

pub fn gen_write_field(field: &Field, ident: &Ident, use_self: bool) -> TokenStream {
    let value_ts = if use_self {
        quote! { self.#ident }
    } else {
        quote! { #ident }
    };
    let field_name = ident.to_string();
    gen_write_field_impl(field, &value_ts, &field_name, use_self)
}

fn gen_write_field_impl(
    field: &Field,
    value_ts: &TokenStream,
    field_name: &str,
    use_self: bool,
) -> TokenStream {
    let ty = &field.ty;
    // Determine RefMode from field meta (respects #[fory(ref=false)] attribute)
    let ref_mode = determine_field_ref_mode(field);
    let base = match classify_trait_object_field(ty) {
        StructField::BoxDyn => {
            // Box<dyn Trait> - respect field meta for ref mode
            quote! {
                <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, #ref_mode, true, false)?;
            }
        }
        StructField::RcDyn(trait_name) => {
            // Rc<dyn Trait> - respect field meta for ref mode
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = #wrapper_ty::from(#value_ts.clone() as std::rc::Rc<dyn #trait_ident>);
                <#wrapper_ty as fory_core::Serializer>::fory_write(&wrapper, context, #ref_mode, true, false)?;
            }
        }
        StructField::ArcDyn(trait_name) => {
            // Arc<dyn Trait> - respect field meta for ref mode
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = #wrapper_ty::from(#value_ts.clone() as std::sync::Arc<dyn #trait_ident>);
                <#wrapper_ty as fory_core::Serializer>::fory_write(&wrapper, context, #ref_mode, true, false)?;
            }
        }
        StructField::VecRc(trait_name) => {
            // Vec<Rc<dyn Trait>> - respect field meta for ref mode
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec: Vec<#wrapper_ty> = #value_ts.iter()
                    .map(|item| #wrapper_ty::from(item.clone() as std::rc::Rc<dyn #trait_ident>))
                    .collect();
                <Vec<#wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_vec, context, #ref_mode, false, true)?;
            }
        }
        StructField::VecArc(trait_name) => {
            // Vec<Arc<dyn Trait>> - respect field meta for ref mode
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec: Vec<#wrapper_ty> = #value_ts.iter()
                    .map(|item| #wrapper_ty::from(item.clone() as std::sync::Arc<dyn #trait_ident>))
                    .collect();
                <Vec<#wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_vec, context, #ref_mode, false, true)?;
            }
        }
        StructField::VecBox(_) => {
            // Vec<Box<dyn Any>> - respect field meta for ref mode
            if ref_mode == FieldRefMode::None {
                quote! {
                    <#ty as fory_core::Serializer>::fory_write_data_generic(&#value_ts, context, true)?;
                }
            } else {
                quote! {
                    <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, #ref_mode, false, true)?;
                }
            }
        }
        StructField::HashMapBox(_, _) => {
            // HashMap<K, Box<dyn Any>> - respect field meta for ref mode
            if ref_mode == FieldRefMode::None {
                quote! {
                    <#ty as fory_core::Serializer>::fory_write_data_generic(&#value_ts, context, true)?;
                }
            } else {
                quote! {
                    <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, #ref_mode, false, true)?;
                }
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            // HashMap<K, Rc<dyn Trait>> - respect field meta for ref mode
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = #value_ts.iter()
                    .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::rc::Rc<dyn #trait_ident>)))
                    .collect();
                <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_map, context, #ref_mode, false, true)?;
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            // HashMap<K, Arc<dyn Trait>> - respect field meta for ref mode
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = #value_ts.iter()
                    .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::sync::Arc<dyn #trait_ident>)))
                    .collect();
                <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_map, context, #ref_mode, false, true)?;
            }
        }
        StructField::Forward => {
            // Forward types (trait objects, forward references) - polymorphic, always need type info
            quote! {
                <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, #ref_mode, true, false)?;
            }
        }
        _ => {
            let type_id = get_type_id_by_type_ast(ty);

            // Check if this is a direct primitive type that can use direct writer calls
            // Only apply when ref_mode is None (no ref tracking needed)
            if ref_mode == FieldRefMode::None && is_direct_primitive_type(ty) {
                let type_name = extract_type_name(ty);
                if type_name == "String" {
                    // String: call fory_write_data directly
                    quote! {
                        <#ty as fory_core::Serializer>::fory_write_data(&#value_ts, context)?;
                    }
                } else {
                    // Numeric primitives: use direct buffer methods
                    let writer_method = get_primitive_writer_method(&type_name);
                    let writer_ident =
                        syn::Ident::new(writer_method, proc_macro2::Span::call_site());
                    // For primitives:
                    // - use_self=true: #value_ts is `self.field`, which is T (copy happens automatically)
                    // - use_self=false: #value_ts is `field` from pattern match on &self, which is &T
                    let value_expr = if use_self {
                        quote! { #value_ts }
                    } else {
                        quote! { *#value_ts }
                    };
                    quote! {
                        context.writer.#writer_ident(#value_expr);
                    }
                }
            } else if type_id == TypeId::LIST as u32
                || type_id == TypeId::SET as u32
                || type_id == TypeId::MAP as u32
            {
                // For collections - respect field meta for ref mode
                if ref_mode == FieldRefMode::None {
                    quote! {
                        <#ty as fory_core::Serializer>::fory_write_data_generic(&#value_ts, context, true)?;
                    }
                } else {
                    quote! {
                        <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, #ref_mode, false, true)?;
                    }
                }
            } else {
                // Custom types (struct/enum/ext) - always need mode-dependent type info logic
                // Determine write_type_info based on mode:
                // - compatible=true: use need_to_write_type_for_field (struct types need type info)
                // - compatible=false: use fory_is_polymorphic
                // This applies regardless of ref_mode because Java always writes type info
                // for struct-type fields in compatible mode, even for non-nullable fields.
                quote! {
                    let write_type_info = if context.is_compatible() {
                        fory_core::types::need_to_write_type_for_field(
                            <#ty as fory_core::Serializer>::fory_static_type_id()
                        )
                    } else {
                        <#ty as fory_core::Serializer>::fory_is_polymorphic()
                    };
                    <#ty as fory_core::Serializer>::fory_write(&#value_ts, context, #ref_mode, write_type_info, false)?;
                }
            }
        }
    };

    if is_debug_enabled() && use_self {
        let struct_name = get_struct_name().expect("struct context not set");
        let struct_name_lit = syn::LitStr::new(&struct_name, proc_macro2::Span::call_site());
        let field_name_lit = syn::LitStr::new(field_name, proc_macro2::Span::call_site());
        quote! {
            fory_core::serializer::struct_::struct_before_write_field(
                #struct_name_lit,
                #field_name_lit,
                (&#value_ts) as &dyn std::any::Any,
                context,
            );
            #base
            fory_core::serializer::struct_::struct_after_write_field(
                #struct_name_lit,
                #field_name_lit,
                (&#value_ts) as &dyn std::any::Any,
                context,
            );
        }
    } else {
        base
    }
}

pub fn gen_write_data(source_fields: &[SourceField<'_>]) -> TokenStream {
    let fields: Vec<&Field> = source_fields.iter().map(|sf| sf.field).collect();
    let write_fields_ts: Vec<_> = get_filtered_source_fields_iter(source_fields)
        .map(|sf| gen_write_field_with_index(sf.field, sf.original_index, true))
        .collect();

    let version_hash_ts = gen_struct_version_hash_ts(&fields);
    quote! {
        if context.is_check_struct_version() {
            let version_hash: i32 = #version_hash_ts;
            context.writer.write_i32(version_hash);
        }
        #(#write_fields_ts)*
        Ok(())
    }
}

pub fn gen_write() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::write::<Self>(self, context, ref_mode, write_type_info)
    }
}
