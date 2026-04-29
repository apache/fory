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

use super::field_codec::{build_bindings, FieldBinding};
use super::util::{
    gen_struct_version_hash_ts, get_field_accessor, get_struct_name, is_debug_enabled,
};
use crate::util::SourceField;
use proc_macro2::TokenStream;
use quote::quote;
use syn::Field;

pub fn gen_reserved_space(source_fields: &[SourceField<'_>]) -> TokenStream {
    let bindings = match build_bindings(source_fields) {
        Ok(bindings) => bindings,
        Err(err) => return err.to_compile_error(),
    };
    let reserved_size_expr: Vec<_> = bindings
        .iter()
        .filter_map(|binding| match binding {
            FieldBinding::Codec(binding) => Some(binding.reserved_space()),
            FieldBinding::Skipped(_) => None,
        })
        .collect();
    if reserved_size_expr.is_empty() {
        return quote! { 0 };
    }
    quote! { #(#reserved_size_expr)+* }
}

pub fn gen_write_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::write_type_info_fast::<Self>(context)
    }
}

pub fn gen_write_data(source_fields: &[SourceField<'_>]) -> TokenStream {
    let fields: Vec<&Field> = source_fields.iter().map(|sf| sf.field).collect();
    let bindings = match build_bindings(source_fields) {
        Ok(bindings) => bindings,
        Err(err) => return err.to_compile_error(),
    };
    let write_fields_ts: Vec<_> = bindings
        .iter()
        .filter_map(|binding| match binding {
            FieldBinding::Codec(binding) => {
                let base = binding.write_field();
                if is_debug_enabled() {
                    let value_ts = get_field_accessor(
                        binding.source.field,
                        binding.source.original_index,
                        true,
                    );
                    let struct_name = get_struct_name().expect("struct context not set");
                    let struct_name_lit =
                        syn::LitStr::new(&struct_name, proc_macro2::Span::call_site());
                    let field_name_lit = syn::LitStr::new(
                        &binding.source.field_name,
                        proc_macro2::Span::call_site(),
                    );
                    Some(quote! {
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
                    })
                } else {
                    Some(base)
                }
            }
            FieldBinding::Skipped(_) => None,
        })
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
