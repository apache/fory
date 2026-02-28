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

use proc_macro2::TokenStream;
use quote::quote;
use std::sync::atomic::{AtomicU32, Ordering};
use syn::Field;

use super::field_meta::{
    classify_field_type, extract_option_inner_type, is_option_type, parse_field_meta,
};
use super::util::{
    classify_trait_object_field, generic_tree_to_tokens, get_filtered_source_fields_iter,
    get_sort_fields_ts, parse_generic_tree, StructField,
};
use crate::util::SourceField;

// Global type ID counter that auto-grows from 0 at macro processing time
static TYPE_ID_COUNTER: AtomicU32 = AtomicU32::new(0);

/// Allocates a new unique type ID at macro processing time
pub fn allocate_type_id() -> u32 {
    TYPE_ID_COUNTER.fetch_add(1, Ordering::SeqCst)
}

#[allow(dead_code)]
fn hash(fields: &[&Field]) -> TokenStream {
    let props = fields.iter().enumerate().map(|(idx, field)| {
        let ty = &field.ty;
        let name = super::util::get_field_name(field, idx);
        quote! {
            (#name, <#ty as fory_core::serializer::Serializer>::fory_get_type_id())
        }
    });

    quote! {
        fn fory_hash() -> u32 {
            use std::sync::Once;
            static mut name_hash: u32 = 0u32;
            static name_hash_once: Once = Once::new();
            unsafe {
                name_hash_once.call_once(|| {
                        name_hash = fory_core::types::compute_struct_hash(vec![#(#props),*]);
                });
                name_hash
            }
        }
    }
}

pub fn gen_actual_type_id() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::actual_type_id(type_id, register_by_name, compatible)
    }
}

pub fn gen_actual_type_id_no_evolving() -> TokenStream {
    quote! {
        if register_by_name {
            fory_core::types::TypeId::NAMED_STRUCT as u32
        } else {
            fory_core::types::TypeId::STRUCT as u32
        }
    }
}

pub fn gen_get_sorted_field_names(fields: &[&Field]) -> TokenStream {
    let static_field_names = get_sort_fields_ts(fields);
    quote! {
        #static_field_names
    }
}

pub fn gen_field_fields_info(source_fields: &[SourceField<'_>]) -> TokenStream {
    let field_infos = get_filtered_source_fields_iter(source_fields).map(|sf| {
        let field = sf.field;
        let ty = &field.ty;
        let name = &sf.field_name;

        // Parse field metadata for nullable/ref tracking and field ID
        let meta = parse_field_meta(field).unwrap_or_default();
        let type_class = classify_field_type(ty);
        // For nullable, check both the classified type AND whether outer type is Option
        // This handles Option<Rc<T>> correctly - classify_field_type returns Rc for track_ref,
        // but we also need to detect that the outer wrapper is Option for nullable.
        let is_outer_option = is_option_type(ty);
        let nullable = meta.effective_nullable(type_class) || is_outer_option;
        let track_ref = meta.effective_ref(type_class);
        // Only use explicit field ID when user sets #[fory(id = N)]
        // Otherwise use -1 to indicate field name encoding should be used
        let field_id = if meta.uses_tag_id() {
            meta.effective_id() as i16
        } else {
            -1i16 // Use field name encoding when no explicit ID
        };

        match classify_trait_object_field(ty) {
            StructField::None => {
                // Check if this is an i32/u32/u64 field (or Option<i32>/Option<u32>/Option<u64>) with encoding attributes
                // In this case, we need to generate the FieldType with the correct type ID directly
                let inner_ty = extract_option_inner_type(ty).unwrap_or_else(|| ty.clone());
                let inner_ty_str = quote::ToTokens::to_token_stream(&inner_ty)
                    .to_string()
                    .replace(' ', "");

                let has_encoding =
                    (inner_ty_str == "i32" || inner_ty_str == "u32" || inner_ty_str == "u64")
                        && meta.type_id.is_some();
                let has_array_override = matches!(
                    meta.type_id,
                    Some(tid)
                        if tid == fory_core::types::TypeId::INT8_ARRAY as i16
                            || tid == fory_core::types::TypeId::UINT8_ARRAY as i16
                ) && (inner_ty_str == "Vec<u8>"
                    || inner_ty_str == "Vec<i8>"
                    || inner_ty_str.starts_with("[u8;")
                    || inner_ty_str.starts_with("[i8;"));

                if has_encoding {
                    // Generate FieldType directly with the correct type ID based on meta.type_id
                    let type_id_ts = match (inner_ty_str.as_str(), meta.type_id) {
                        // i32: VARINT32 (default) or INT32 (fixed)
                        ("i32", Some(tid)) if tid == fory_core::types::TypeId::INT32 as i16 => {
                            quote! { fory_core::types::TypeId::INT32 as u32 }
                        }
                        ("i32", _) => {
                            quote! { fory_core::types::TypeId::VARINT32 as u32 }
                        }
                        // u32: VAR_UINT32 (default) or UINT32 (fixed)
                        ("u32", Some(tid)) if tid == fory_core::types::TypeId::INT32 as i16 => {
                            quote! { fory_core::types::TypeId::UINT32 as u32 }
                        }
                        ("u32", _) => {
                            quote! { fory_core::types::TypeId::VAR_UINT32 as u32 }
                        }
                        // u64: VAR_UINT64 (default), UINT64 (fixed), or TAGGED_UINT64 (tagged)
                        ("u64", Some(tid)) if tid == fory_core::types::TypeId::INT32 as i16 => {
                            quote! { fory_core::types::TypeId::UINT64 as u32 }
                        }
                        ("u64", Some(tid))
                            if tid == fory_core::types::TypeId::TAGGED_UINT64 as i16 =>
                        {
                            quote! { fory_core::types::TypeId::TAGGED_UINT64 as u32 }
                        }
                        ("u64", _) => {
                            quote! { fory_core::types::TypeId::VAR_UINT64 as u32 }
                        }
                        _ => unreachable!(),
                    };

                    quote! {
                        fory_core::meta::FieldInfo::new_with_id(
                            #field_id,
                            #name,
                            fory_core::meta::FieldType {
                                type_id: #type_id_ts,
                                user_type_id: u32::MAX,
                                nullable: #nullable,
                                track_ref: #track_ref,
                                generics: Vec::new()
                            }
                        )
                    }
                } else if has_array_override {
                    let type_id_ts = match meta.type_id {
                        Some(tid) if tid == fory_core::types::TypeId::INT8_ARRAY as i16 => {
                            quote! { fory_core::types::TypeId::INT8_ARRAY as u32 }
                        }
                        Some(tid) if tid == fory_core::types::TypeId::UINT8_ARRAY as i16 => {
                            quote! { fory_core::types::TypeId::UINT8_ARRAY as u32 }
                        }
                        _ => unreachable!(),
                    };
                    quote! {
                        fory_core::meta::FieldInfo::new_with_id(
                            #field_id,
                            #name,
                            fory_core::meta::FieldType {
                                type_id: #type_id_ts,
                                user_type_id: u32::MAX,
                                nullable: #nullable,
                                track_ref: #track_ref,
                                generics: Vec::new()
                            }
                        )
                    }
                } else {
                    let generic_tree = parse_generic_tree(ty);
                    let generic_token = generic_tree_to_tokens(&generic_tree);
                    quote! {
                        fory_core::meta::FieldInfo::new_with_id(
                            #field_id,
                            #name,
                            {
                                let mut ft = #generic_token;
                                ft.nullable = #nullable;
                                ft.track_ref = #track_ref;
                                ft
                            }
                        )
                    }
                }
            }
            StructField::VecBox(_) | StructField::VecRc(_) | StructField::VecArc(_) => {
                quote! {
                    fory_core::meta::FieldInfo::new_with_id(#field_id, #name, fory_core::meta::FieldType {
                        type_id: fory_core::types::TypeId::LIST as u32,
                        user_type_id: u32::MAX,
                        nullable: #nullable,
                        track_ref: #track_ref,
                        generics: vec![fory_core::meta::FieldType {
                            type_id: fory_core::types::TypeId::UNKNOWN as u32,
                            user_type_id: u32::MAX,
                            nullable: false,
                            track_ref: false,
                            generics: Vec::new()
                        }]
                    })
                }
            }
            StructField::HashMapBox(key_ty, _)
            | StructField::HashMapRc(key_ty, _)
            | StructField::HashMapArc(key_ty, _) => {
                let key_generic_tree = parse_generic_tree(key_ty.as_ref());
                let key_generic_token = generic_tree_to_tokens(&key_generic_tree);
                quote! {
                    fory_core::meta::FieldInfo::new_with_id(#field_id, #name, fory_core::meta::FieldType {
                        type_id: fory_core::types::TypeId::MAP as u32,
                        user_type_id: u32::MAX,
                        nullable: #nullable,
                        track_ref: #track_ref,
                        generics: vec![
                            #key_generic_token,
                            fory_core::meta::FieldType {
                                type_id: fory_core::types::TypeId::UNKNOWN as u32,
                                user_type_id: u32::MAX,
                                nullable: false,
                                track_ref: false,
                                generics: Vec::new()
                            }
                        ]
                    })
                }
            }
            _ => {
                quote! {
                    fory_core::meta::FieldInfo::new_with_id(#field_id, #name, fory_core::meta::FieldType {
                        type_id: fory_core::types::TypeId::UNKNOWN as u32,
                        user_type_id: u32::MAX,
                        nullable: #nullable,
                        track_ref: #track_ref,
                        generics: Vec::new()
                    })
                }
            }
        }
    });

    let fields: Vec<&Field> = source_fields.iter().map(|sf| sf.field).collect();
    let static_field_names = get_sort_fields_ts(&fields);

    quote! {
        let mut field_infos: Vec<fory_core::meta::FieldInfo> = vec![#(#field_infos),*];
        let sorted_field_names = #static_field_names;
        fory_core::meta::sort_fields(&mut field_infos, sorted_field_names)?;
        Ok(field_infos)
    }
}
