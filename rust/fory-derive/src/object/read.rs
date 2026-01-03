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

use super::util::{
    classify_trait_object_field, create_wrapper_types_arc, create_wrapper_types_rc,
    determine_field_ref_mode, extract_type_name, gen_struct_version_hash_ts,
    get_primitive_reader_method, get_struct_name, is_debug_enabled, is_direct_primitive_type,
    is_primitive_type, is_skip_field, should_skip_type_info_for_field, FieldRefMode, StructField,
};
use crate::util::SourceField;

/// Create a private variable name for a field during deserialization.
/// For named fields: `_field_name`
/// For tuple struct fields: `_0`, `_1`, etc.
pub(crate) fn create_private_field_name(field: &Field, index: usize) -> Ident {
    match &field.ident {
        Some(ident) => format_ident!("_{}", ident),
        None => format_ident!("_{}", index),
    }
}

fn need_declared_by_option(field: &Field) -> bool {
    let type_name = extract_type_name(&field.ty);
    type_name == "Option" || !is_primitive_type(type_name.as_str())
}

pub(crate) fn declare_var(source_fields: &[SourceField<'_>]) -> Vec<TokenStream> {
    source_fields
        .iter()
        .map(|sf| {
            let field = sf.field;
            let ty = &field.ty;
            let var_name = create_private_field_name(field, sf.original_index);
            match classify_trait_object_field(ty) {
                StructField::BoxDyn | StructField::RcDyn(_) | StructField::ArcDyn(_) => {
                    quote! {
                        let mut #var_name: #ty = <#ty as fory_core::serializer::ForyDefault>::fory_default();
                    }
                }
                _ => {
                    if need_declared_by_option(field) {
                        quote! {
                            let mut #var_name: Option<#ty> = None;
                        }
                    } else if extract_type_name(&field.ty) == "bool" {
                        quote! {
                            let mut #var_name: bool = false;
                        }
                    } else {
                        quote! {
                            let mut #var_name: #ty = 0 as #ty;
                        }
                    }
                }
            }
        })
        .collect()
}

pub(crate) fn assign_value(source_fields: &[SourceField<'_>]) -> Vec<TokenStream> {
    let is_tuple = source_fields
        .first()
        .map(|sf| sf.is_tuple_struct)
        .unwrap_or(false);

    // Generate field value expressions with original index for sorting
    let mut indexed: Vec<_> = source_fields
        .iter()
        .map(|sf| {
            let var_name = create_private_field_name(sf.field, sf.original_index);
            let value_expr = match classify_trait_object_field(&sf.field.ty) {
                StructField::BoxDyn | StructField::RcDyn(_) | StructField::ArcDyn(_) => {
                    quote! { #var_name }
                }
                StructField::ContainsTraitObject => {
                    quote! { #var_name.unwrap() }
                }
                _ => {
                    if need_declared_by_option(sf.field) {
                        let ty = &sf.field.ty;
                        quote! { #var_name.unwrap_or_else(|| <#ty as fory_core::ForyDefault>::fory_default()) }
                    } else {
                        quote! { #var_name }
                    }
                }
            };
            (sf.original_index, sf.field_init(value_expr))
        })
        .collect();

    // For tuple structs, sort by original index to construct Self(field0, field1, ...) correctly
    if is_tuple {
        indexed.sort_by_key(|(idx, _)| *idx);
    }

    indexed.into_iter().map(|(_, ts)| ts).collect()
}

pub fn gen_read_field(field: &Field, private_ident: &Ident, field_name: &str) -> TokenStream {
    let ty = &field.ty;
    if is_skip_field(field) {
        return quote! {
            let #private_ident = <#ty as fory_core::ForyDefault>::fory_default();
        };
    }

    // Determine RefMode from field meta (respects #[fory(ref=false)] attribute)
    let ref_mode = determine_field_ref_mode(field);
    let base = match classify_trait_object_field(ty) {
        StructField::BoxDyn => {
            // Box<dyn Trait> - respect field meta for ref mode
            quote! {
                let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, #ref_mode, true)?;
            }
        }
        StructField::RcDyn(trait_name) => {
            // Rc<dyn Trait> - respect field meta for ref mode
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = <#wrapper_ty as fory_core::Serializer>::fory_read(context, #ref_mode, true)?;
                let #private_ident = std::rc::Rc::<dyn #trait_ident>::from(wrapper);
            }
        }
        StructField::ArcDyn(trait_name) => {
            // Arc<dyn Trait> - respect field meta for ref mode
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = <#wrapper_ty as fory_core::Serializer>::fory_read(context, #ref_mode, true)?;
                let #private_ident = std::sync::Arc::<dyn #trait_ident>::from(wrapper);
            }
        }
        StructField::VecRc(trait_name) => {
            // Vec<Rc<dyn Trait>> - respect field meta for ref mode
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
                let #private_ident = wrapper_vec.into_iter()
                    .map(|w| std::rc::Rc::<dyn #trait_ident>::from(w))
                    .collect();
            }
        }
        StructField::VecArc(trait_name) => {
            // Vec<Arc<dyn Trait>> - respect field meta for ref mode
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
                let #private_ident = wrapper_vec.into_iter()
                    .map(|w| std::sync::Arc::<dyn #trait_ident>::from(w))
                    .collect();
            }
        }
        StructField::VecBox(_) => {
            // Vec<Box<dyn Any>> - respect field meta for ref mode
            quote! {
                let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
            }
        }
        StructField::HashMapBox(_, _) => {
            // HashMap<K, Box<dyn Any>> - respect field meta for ref mode
            quote! {
                let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            // HashMap<K, Rc<dyn Trait>> - respect field meta for ref mode
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
                let #private_ident = wrapper_map.into_iter()
                    .map(|(k, v)| (k, std::rc::Rc::<dyn #trait_ident>::from(v)))
                    .collect();
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            // HashMap<K, Arc<dyn Trait>> - respect field meta for ref mode
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
                let #private_ident = wrapper_map.into_iter()
                    .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                    .collect();
            }
        }
        StructField::Forward => {
            // Forward types (trait objects, forward references) - polymorphic, always need type info
            quote! {
                let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, #ref_mode, true)?;
            }
        }
        _ => {
            let skip_type_info = should_skip_type_info_for_field(ty);

            // Check if this is a direct primitive type that can use direct reader calls
            // Only apply when ref_mode is None (no ref tracking needed)
            if ref_mode == FieldRefMode::None && is_direct_primitive_type(ty) {
                let type_name = extract_type_name(ty);
                if type_name == "String" {
                    // String: call fory_read_data directly
                    quote! {
                        let #private_ident = <#ty as fory_core::Serializer>::fory_read_data(context)?;
                    }
                } else {
                    // Numeric primitives: use direct buffer methods
                    let reader_method = get_primitive_reader_method(&type_name);
                    let reader_ident =
                        syn::Ident::new(reader_method, proc_macro2::Span::call_site());
                    quote! {
                        let #private_ident = context.reader.#reader_ident()?;
                    }
                }
            } else if skip_type_info {
                // Known types (primitives, strings, collections) - skip type info at compile time
                if ref_mode == FieldRefMode::None {
                    quote! {
                        let #private_ident = <#ty as fory_core::Serializer>::fory_read_data(context)?;
                    }
                } else {
                    quote! {
                        let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
                    }
                }
            } else {
                // Custom types (struct/enum/ext) - always need mode-dependent type info logic
                // Determine read_type_info based on mode:
                // - compatible=true: use need_to_write_type_for_field (struct types need type info)
                // - compatible=false: use fory_is_polymorphic
                // This applies regardless of ref_mode because Java always writes type info
                // for struct-type fields in compatible mode, even for non-nullable fields.
                quote! {
                    let read_type_info = if context.is_compatible() {
                        fory_core::types::need_to_write_type_for_field(
                            <#ty as fory_core::Serializer>::fory_static_type_id()
                        )
                    } else {
                        <#ty as fory_core::Serializer>::fory_is_polymorphic()
                    };
                    let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, #ref_mode, read_type_info)?;
                }
            }
        }
    };

    if is_debug_enabled() {
        let struct_name = get_struct_name().expect("struct context not set");
        let struct_name_lit = syn::LitStr::new(&struct_name, proc_macro2::Span::call_site());
        let field_name_lit = syn::LitStr::new(field_name, proc_macro2::Span::call_site());
        quote! {
            fory_core::serializer::struct_::struct_before_read_field(
                #struct_name_lit,
                #field_name_lit,
                context,
            );
            #base
            fory_core::serializer::struct_::struct_after_read_field(
                #struct_name_lit,
                #field_name_lit,
                (&#private_ident) as &dyn std::any::Any,
                context,
            );
        }
    } else {
        base
    }
}

pub fn gen_read_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::read_type_info::<Self>(context)
    }
}

fn get_source_fields_loop_ts(source_fields: &[SourceField<'_>]) -> TokenStream {
    let read_fields_ts: Vec<_> = source_fields
        .iter()
        .map(|sf| {
            let private_ident = create_private_field_name(sf.field, sf.original_index);
            gen_read_field(sf.field, &private_ident, &sf.field_name)
        })
        .collect();
    quote! {
        #(#read_fields_ts)*
    }
}

pub fn gen_read_data(source_fields: &[SourceField<'_>]) -> TokenStream {
    let fields: Vec<&Field> = source_fields.iter().map(|sf| sf.field).collect();
    // Generate runtime version hash computation that detects enum fields
    let version_hash_ts = gen_struct_version_hash_ts(&fields);
    let read_fields = if source_fields.is_empty() {
        quote! {}
    } else {
        let loop_ts = get_source_fields_loop_ts(source_fields);
        quote! {
            #loop_ts
        }
    };

    let is_tuple = source_fields
        .first()
        .map(|sf| sf.is_tuple_struct)
        .unwrap_or(false);

    // Generate field initializations, sorted by original index for tuple structs
    let mut indexed: Vec<_> = source_fields
        .iter()
        .map(|sf| {
            let private_ident = create_private_field_name(sf.field, sf.original_index);
            let value = quote! { #private_ident };
            (sf.original_index, sf.field_init(value))
        })
        .collect();

    if is_tuple {
        indexed.sort_by_key(|(idx, _)| *idx);
    }

    let field_inits: Vec<_> = indexed.into_iter().map(|(_, ts)| ts).collect();
    let self_construction = crate::util::ok_self_construction(is_tuple, &field_inits);

    quote! {
        // Read and check version hash when class version checking is enabled
        if context.is_check_struct_version() {
            let read_version = context.reader.read_i32()?;
            let type_name = std::any::type_name::<Self>();
            let local_version: i32 = #version_hash_ts;
            fory_core::meta::TypeMeta::check_struct_version(read_version, local_version, type_name)?;
        }
        #read_fields
        #self_construction
    }
}

pub(crate) fn gen_read_compatible_match_arm_body(
    field: &Field,
    var_name: &Ident,
    field_name: &str,
) -> TokenStream {
    let ty = &field.ty;
    let field_kind = classify_trait_object_field(ty);
    let is_skip_flag = is_skip_field(field);
    // Determine RefMode from field meta (respects #[fory(ref=false)] attribute)
    let ref_mode = determine_field_ref_mode(field);

    let base = if is_skip_flag {
        match field_kind {
            StructField::None => {
                let dec_by_option = need_declared_by_option(field);
                if dec_by_option {
                    quote! {
                        #var_name = Some(<#ty as fory_core::ForyDefault>::fory_default());
                    }
                } else {
                    quote! {
                        #var_name = <#ty as fory_core::ForyDefault>::fory_default();
                    }
                }
            }
            _ => {
                quote! {
                    #var_name = Some(<#ty as fory_core::ForyDefault>::fory_default());
                }
            }
        }
    } else {
        match field_kind {
            StructField::BoxDyn => {
                // Box<dyn Trait> - respect field meta for ref mode
                quote! {
                    #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, #ref_mode, true)?);
                }
            }
            StructField::RcDyn(trait_name) => {
                // Rc<dyn Trait> - respect field meta for ref mode
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                let trait_ident = types.trait_ident;
                quote! {
                    let wrapper = <#wrapper_ty as fory_core::Serializer>::fory_read(context, #ref_mode, true)?;
                    #var_name = Some(std::rc::Rc::<dyn #trait_ident>::from(wrapper));
                }
            }
            StructField::ArcDyn(trait_name) => {
                // Arc<dyn Trait> - respect field meta for ref mode
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                let trait_ident = types.trait_ident;
                quote! {
                    let wrapper = <#wrapper_ty as fory_core::Serializer>::fory_read(context, #ref_mode, true)?;
                    #var_name = Some(std::sync::Arc::<dyn #trait_ident>::from(wrapper));
                }
            }
            StructField::VecRc(trait_name) => {
                // Vec<Rc<dyn Trait>> - respect field meta for ref mode
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                let trait_ident = types.trait_ident;
                quote! {
                    let wrapper_vec = <Vec<#wrapper_ty> as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
                    #var_name = Some(wrapper_vec.into_iter()
                        .map(|w| std::rc::Rc::<dyn #trait_ident>::from(w))
                        .collect());
                }
            }
            StructField::VecArc(trait_name) => {
                // Vec<Arc<dyn Trait>> - respect field meta for ref mode
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                let trait_ident = types.trait_ident;
                quote! {
                    let wrapper_vec = <Vec<#wrapper_ty> as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
                    #var_name = Some(wrapper_vec.into_iter()
                        .map(|w| std::sync::Arc::<dyn #trait_ident>::from(w))
                        .collect());
                }
            }
            StructField::VecBox(_) => {
                // Vec<Box<dyn Any>> uses standard Vec deserialization with polymorphic elements
                // Check nullable and ref_tracking flags from remote field info
                quote! {
                    let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                        _field.field_type.type_id,
                        _field.field_type.nullable,
                    );
                    let ref_mode = if _field.field_type.ref_tracking {
                        fory_core::RefMode::Tracking
                    } else if read_ref_flag {
                        fory_core::RefMode::NullOnly
                    } else {
                        fory_core::RefMode::None
                    };
                    if read_ref_flag || _field.field_type.ref_tracking {
                        #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, ref_mode, false)?);
                    } else {
                        #var_name = Some(<#ty as fory_core::Serializer>::fory_read_data(context)?);
                    }
                }
            }
            StructField::HashMapBox(_, _) => {
                // HashMap<K, Box<dyn Any>> uses standard HashMap deserialization with polymorphic values
                // Check nullable and ref_tracking flags from remote field info
                quote! {
                    let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                        _field.field_type.type_id,
                        _field.field_type.nullable,
                    );
                    let ref_mode = if _field.field_type.ref_tracking {
                        fory_core::RefMode::Tracking
                    } else if read_ref_flag {
                        fory_core::RefMode::NullOnly
                    } else {
                        fory_core::RefMode::None
                    };
                    if read_ref_flag || _field.field_type.ref_tracking {
                        #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, ref_mode, false)?);
                    } else {
                        #var_name = Some(<#ty as fory_core::Serializer>::fory_read_data(context)?);
                    }
                }
            }
            StructField::HashMapRc(key_ty, trait_name) => {
                // HashMap<K, Rc<dyn Trait>> - respect field meta for ref mode
                let types = create_wrapper_types_rc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                let trait_ident = types.trait_ident;
                quote! {
                    let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
                    #var_name = Some(wrapper_map.into_iter()
                        .map(|(k, v)| (k, std::rc::Rc::<dyn #trait_ident>::from(v)))
                        .collect());
                }
            }
            StructField::HashMapArc(key_ty, trait_name) => {
                // HashMap<K, Arc<dyn Trait>> - respect field meta for ref mode
                let types = create_wrapper_types_arc(&trait_name);
                let wrapper_ty = types.wrapper_ty;
                let trait_ident = types.trait_ident;
                quote! {
                    let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_read(context, #ref_mode, false)?;
                    #var_name = Some(wrapper_map.into_iter()
                        .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                        .collect());
                }
            }
            StructField::ContainsTraitObject => {
                // Struct containing trait objects - respect field meta for ref mode
                quote! {
                    #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, #ref_mode, true)?);
                }
            }
            StructField::Forward => {
                // Forward types (trait objects, forward references) - polymorphic, always need type info
                // Use remote field's ref_tracking flag for ref_mode
                quote! {
                    let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                        _field.field_type.type_id,
                        _field.field_type.nullable,
                    );
                    // Use RefMode::Tracking if remote field has ref_tracking enabled
                    let ref_mode = if _field.field_type.ref_tracking {
                        fory_core::RefMode::Tracking
                    } else if read_ref_flag {
                        fory_core::RefMode::NullOnly
                    } else {
                        fory_core::RefMode::None
                    };
                    // Forward types are polymorphic, always read type info
                    #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, ref_mode, true)?);
                }
            }
            StructField::None => {
                let skip_type_info = should_skip_type_info_for_field(ty);
                let dec_by_option = need_declared_by_option(field);
                if skip_type_info {
                    if dec_by_option {
                        quote! {
                            let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                                _field.field_type.type_id,
                                _field.field_type.nullable,
                            );
                            // Use RefMode::Tracking if remote field has ref_tracking enabled
                            let ref_mode = if _field.field_type.ref_tracking {
                                fory_core::RefMode::Tracking
                            } else if read_ref_flag {
                                fory_core::RefMode::NullOnly
                            } else {
                                fory_core::RefMode::None
                            };
                            if read_ref_flag || _field.field_type.ref_tracking {
                                #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, ref_mode, false)?);
                            } else {
                                #var_name = Some(<#ty as fory_core::Serializer>::fory_read_data(context)?);
                            }
                        }
                    } else {
                        quote! {
                            let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                                _field.field_type.type_id,
                                _field.field_type.nullable,
                            );
                            // Use RefMode::Tracking if remote field has ref_tracking enabled
                            let ref_mode = if _field.field_type.ref_tracking {
                                fory_core::RefMode::Tracking
                            } else if read_ref_flag {
                                fory_core::RefMode::NullOnly
                            } else {
                                fory_core::RefMode::None
                            };
                            if read_ref_flag || _field.field_type.ref_tracking {
                                #var_name = <#ty as fory_core::Serializer>::fory_read(context, ref_mode, false)?;
                            } else {
                                #var_name = <#ty as fory_core::Serializer>::fory_read_data(context)?;
                            }
                        }
                    }
                } else if dec_by_option {
                    quote! {
                        let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                            _field.field_type.type_id,
                            _field.field_type.nullable,
                        );
                        // Use RefMode::Tracking if remote field has ref_tracking enabled
                        let ref_mode = if _field.field_type.ref_tracking {
                            fory_core::RefMode::Tracking
                        } else if read_ref_flag {
                            fory_core::RefMode::NullOnly
                        } else {
                            fory_core::RefMode::None
                        };
                        // For ref-tracked struct types, Java writes type info after RefValue flag
                        let read_type_info = fory_core::types::need_to_write_type_for_field(
                            <#ty as fory_core::Serializer>::fory_static_type_id()
                        );
                        #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, ref_mode, read_type_info)?);
                    }
                } else {
                    quote! {
                        let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                            _field.field_type.type_id,
                            _field.field_type.nullable,
                        );
                        // Use RefMode::Tracking if remote field has ref_tracking enabled
                        let ref_mode = if _field.field_type.ref_tracking {
                            fory_core::RefMode::Tracking
                        } else if read_ref_flag {
                            fory_core::RefMode::NullOnly
                        } else {
                            fory_core::RefMode::None
                        };
                        // For ref-tracked struct types, Java writes type info after RefValue flag
                        let read_type_info = fory_core::types::need_to_write_type_for_field(
                            <#ty as fory_core::Serializer>::fory_static_type_id()
                        );
                        #var_name = <#ty as fory_core::Serializer>::fory_read(context, ref_mode, read_type_info)?;
                    }
                }
            }
        }
    };

    if is_debug_enabled() {
        let struct_name = get_struct_name().expect("struct context not set");
        let struct_name_lit = syn::LitStr::new(&struct_name, proc_macro2::Span::call_site());
        let field_name_lit = syn::LitStr::new(field_name, proc_macro2::Span::call_site());
        quote! {
            fory_core::serializer::struct_::struct_before_read_field(
                #struct_name_lit,
                #field_name_lit,
                context,
            );
            #base
            fory_core::serializer::struct_::struct_after_read_field(
                #struct_name_lit,
                #field_name_lit,
                (&#var_name) as &dyn std::any::Any,
                context,
            );
        }
    } else {
        quote! {
            #base
        }
    }
}

pub fn gen_read(_struct_ident: &Ident) -> TokenStream {
    // Note: We use `Self` instead of `#struct_ident` to correctly handle generic types.
    // When the struct has generics (e.g., LeaderId<C>), using `Self` ensures the full
    // type with generics is used in the impl block.
    quote! {
        let ref_flag = if ref_mode != fory_core::RefMode::None {
            context.reader.read_i8()?
        } else {
            fory_core::RefFlag::NotNullValue as i8
        };
        if ref_flag == (fory_core::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::RefFlag::RefValue as i8) {
            // For RefValueFlag with Tracking mode, reserve a ref_id to participate in ref tracking.
            // This is needed for xlang compatibility where all objects (not just Rc/Arc)
            // participate in reference tracking when ref tracking is enabled.
            // Only reserve for Tracking mode, not NullOnly mode.
            if ref_flag == (fory_core::RefFlag::RefValue as i8) && ref_mode == fory_core::RefMode::Tracking {
                context.ref_reader.reserve_ref_id();
            }
            if context.is_compatible() {
                let type_info = if read_type_info {
                    context.read_any_typeinfo()?
                } else {
                    let rs_type_id = std::any::TypeId::of::<Self>();
                    context.get_type_info(&rs_type_id)?
                };
                <Self as fory_core::StructSerializer>::fory_read_compatible(context, type_info)
            } else {
                if read_type_info {
                    <Self as fory_core::Serializer>::fory_read_type_info(context)?;
                }
                <Self as fory_core::Serializer>::fory_read_data(context)
            }
        } else if ref_flag == (fory_core::RefFlag::Null as i8) {
            Ok(<Self as fory_core::ForyDefault>::fory_default())
        } else {
            Err(fory_core::error::Error::invalid_ref(format!("Unknown ref flag, value:{ref_flag}")))
        }
    }
}

pub fn gen_read_with_type_info() -> TokenStream {
    // fn fory_read_with_type_info(
    //     context: &mut ReadContext,
    //     ref_mode: RefMode,
    //     type_info: Rc<TypeInfo>,
    // ) -> Result<Self, Error>
    // Note: We use `Self` instead of `#struct_ident` to correctly handle generic types.
    quote! {
        let ref_flag = if ref_mode != fory_core::RefMode::None {
            context.reader.read_i8()?
        } else {
            fory_core::RefFlag::NotNullValue as i8
        };
        if ref_flag == (fory_core::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::RefFlag::RefValue as i8) {
            if context.is_compatible() {
                <Self as fory_core::StructSerializer>::fory_read_compatible(context, type_info)
            } else {
                <Self as fory_core::Serializer>::fory_read_data(context)
            }
        } else if ref_flag == (fory_core::RefFlag::Null as i8) {
            Ok(<Self as fory_core::ForyDefault>::fory_default())
        } else {
            Err(fory_core::error::Error::invalid_ref(format!("Unknown ref flag, value:{ref_flag}")))
        }
    }
}

pub fn gen_read_compatible(source_fields: &[SourceField<'_>]) -> TokenStream {
    gen_read_compatible_with_construction(source_fields, None)
}

pub(crate) fn gen_read_compatible_with_construction(
    source_fields: &[SourceField<'_>],
    variant_ident: Option<&Ident>,
) -> TokenStream {
    let declare_ts: Vec<TokenStream> = declare_var(source_fields);
    let assign_ts: Vec<TokenStream> = assign_value(source_fields);

    let match_arms: Vec<TokenStream> = source_fields
        .iter()
        .enumerate()
        .map(|(sorted_idx, sf)| {
            let var_name = create_private_field_name(sf.field, sf.original_index);
            // Use sorted index for matching. The assign_field_ids function assigns
            // the sorted index to matched fields after lookup (by ID or name).
            // Fields that don't match or have type mismatches get field_id = -1.
            let field_id = sorted_idx as i16;
            let body = gen_read_compatible_match_arm_body(sf.field, &var_name, &sf.field_name);
            quote! {
                #field_id => {
                    #body
                }
            }
        })
        .collect();

    let skip_arm = if is_debug_enabled() {
        let struct_name = get_struct_name().expect("struct context not set");
        let struct_name_lit = syn::LitStr::new(&struct_name, proc_macro2::Span::call_site());
        quote! {
            _ => {
                let field_type = &_field.field_type;
                let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                    field_type.type_id,
                    field_type.nullable,
                );
                let field_name = _field.field_name.as_str();
                fory_core::serializer::struct_::struct_before_read_field(
                    #struct_name_lit,
                    field_name,
                    context,
                );
                fory_core::serializer::skip::skip_field_value(context, &field_type, read_ref_flag)?;
                let placeholder: &dyn std::any::Any = &();
                fory_core::serializer::struct_::struct_after_read_field(
                    #struct_name_lit,
                    field_name,
                    placeholder,
                    context,
                );
            }
        }
    } else {
        quote! {
            _ => {
                let field_type = &_field.field_type;
                let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                    field_type.type_id,
                    field_type.nullable,
                );
                fory_core::serializer::skip::skip_field_value(context, field_type, read_ref_flag)?;
            }
        }
    };

    // Generate construction based on whether this is a struct or enum variant
    let is_tuple = source_fields
        .first()
        .map(|sf| sf.is_tuple_struct)
        .unwrap_or(false);

    let construction = if let Some(variant) = variant_ident {
        // Enum variants use named syntax (struct variants) or tuple syntax (tuple variants)
        quote! {
            Ok(Self::#variant {
                #(#assign_ts),*
            })
        }
    } else {
        crate::util::ok_self_construction(is_tuple, &assign_ts)
    };

    quote! {
        let fields = type_info.get_type_meta().get_field_infos().clone();
        #(#declare_ts)*
        let meta = context.get_type_info(&std::any::TypeId::of::<Self>())?.get_type_meta();
        let local_type_hash = meta.get_hash();
        let remote_type_hash = type_info.get_type_meta().get_hash();
        if remote_type_hash == local_type_hash {
            <Self as fory_core::Serializer>::fory_read_data(context)
        } else {
            for _field in fields.iter() {
                match _field.field_id {
                    #(#match_arms)*
                    #skip_arm
                }
            }
            #construction
        }
    }
}
