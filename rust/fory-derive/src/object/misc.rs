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

use super::field_codec::{build_bindings, FieldBinding};
use super::util::get_sort_fields_ts;
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
                        name_hash = fory_core::meta::compute_struct_hash(vec![#(#props),*]);
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
            fory_core::type_id::TypeId::NAMED_STRUCT as u32
        } else {
            fory_core::type_id::TypeId::STRUCT as u32
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
    let bindings = match build_bindings(source_fields) {
        Ok(bindings) => bindings,
        Err(err) => return err.to_compile_error(),
    };
    let field_infos = bindings.iter().filter_map(|binding| match binding {
        FieldBinding::Codec(binding) => Some(binding.field_info()),
        FieldBinding::Skipped(_) => None,
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
