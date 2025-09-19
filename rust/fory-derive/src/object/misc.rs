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

use super::util::{generic_tree_to_tokens, get_sort_fields_ts, parse_generic_tree};

// Global type ID counter that auto-grows from 0 at macro processing time
static TYPE_ID_COUNTER: AtomicU32 = AtomicU32::new(0);

/// Allocates a new unique type ID at macro processing time
pub fn allocate_type_id() -> u32 {
    TYPE_ID_COUNTER.fetch_add(1, Ordering::SeqCst)
}

fn hash(fields: &[&Field]) -> TokenStream {
    let props = fields.iter().map(|field| {
        let ty = &field.ty;
        let name = format!("{}", field.ident.as_ref().expect("should be field name"));
        quote! {
            (#name, <#ty as fory_core::serializer::Serializer>::get_type_id())
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

fn type_def(fields: &[&Field]) -> TokenStream {
    let field_infos = fields.iter().map(|field| {
        let ty = &field.ty;
        let name = format!("{}", field.ident.as_ref().expect("should be field name"));
        let generic_tree = parse_generic_tree(ty);
        let generic_token = generic_tree_to_tokens(&generic_tree, false);
        quote! {
            fory_core::meta::FieldInfo::new(#name, #generic_token)
        }
    });
    quote! {
         let sorted_field_names = <Self as fory_core::serializer::StructSerializer>::get_sorted_field_names(fory);
        let field_infos = vec![#(#field_infos),*];
        let mut sorted_field_infos = Vec::with_capacity(field_infos.len());
        for name in &sorted_field_names {
            if let Some(info) = field_infos.iter().find(|f| &f.field_name == name) {
                sorted_field_infos.push(info.clone());
            } else {
                panic!("Field {} not found in field_infos", name);
            }
        }
        let meta = fory_core::meta::TypeMeta::from_fields(
            type_id,
            namespace,
            type_name,
            register_by_name,
            sorted_field_infos,
        );
        meta.to_bytes().unwrap()
    }
}

pub fn gen_in_struct_impl(fields: &[&Field]) -> TokenStream {
    let _hash_token_stream = hash(fields);
    let type_def_token_stream = type_def(fields);

    quote! {
        #type_def_token_stream
    }
}

pub fn gen_actual_type_id() -> TokenStream {
    quote! {
        (type_id << 8) + fory_core::types::TypeId::COMPATIBLE_STRUCT as u32
    }
}

pub fn gen(type_id: u32) -> TokenStream {
    quote! {
        fn get_type_id(fory: &fory_core::fory::Fory) -> u32 {
            fory.get_type_resolver().get_type_id(&std::any::TypeId::of::<Self>(), #type_id)
        }
    }
}

pub fn gen_type_index(type_id: u32) -> TokenStream {
    quote! {
        fn type_index() -> u32 {
            #type_id
        }
    }
}

pub fn gen_sort_fields(fields: &[&Field]) -> TokenStream {
    let create_sorted_field_names = get_sort_fields_ts(fields);
    quote! {
        let sorted_field_names = match fory.get_type_resolver().get_sorted_field_names::<Self>(std::any::TypeId::of::<Self>()) {
            Some(result) => result,
            None => {
                #create_sorted_field_names
                fory.set_sorted_field_names::<Self>(&sorted_field_names);
                sorted_field_names
            }
        };
        println!("sorted_field_names: {:?}", sorted_field_names);
        sorted_field_names
    }
}
