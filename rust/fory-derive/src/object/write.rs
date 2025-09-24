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
use syn::Field;

pub fn gen_reserved_space(fields: &[&Field]) -> TokenStream {
    let reserved_size_expr: Vec<_> = fields.iter().map(|field| {
        let ty = &field.ty;
        quote! {
            <#ty as fory_core::serializer::Serializer>::reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
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

pub fn gen_write(fields: &[&Field]) -> TokenStream {
    // let accessor_expr = fields.iter().map(|field| {
    //     let ty = &field.ty;
    //     let ident = &field.ident;
    //     quote! {
    //         <#ty as fory_core::serializer::Serializer>::serialize(&self.#ident, context, true);
    //     }
    // });
    let sorted_serialize = if fields.is_empty() {
        quote! {}
    } else {
        let match_ts = fields.iter().map(|field| {
            let ty = &field.ty;
            let ident = &field.ident;
            let name_str = ident.as_ref().unwrap().to_string();
            quote! {
                #name_str => {
                    let skip_ref_flag = fory_core::serializer::get_skip_ref_flag::<#ty>(context.get_fory());
                    fory_core::serializer::write_data::<#ty>(&self.#ident, context, true, skip_ref_flag, false);
                }
            }
        });
        quote! {
            let sorted_field_names = <Self as fory_core::serializer::StructSerializer>::get_sorted_field_names(context.get_fory());
            for field_name in sorted_field_names {
                match field_name.as_str() {
                    #(#match_ts),*
                    , _ => {unreachable!()}
                }
            }
        }
    };
    quote! {
        // write way before
        // #(#accessor_expr)*
        // sort and write
        #sorted_serialize
    }
}

pub fn gen_serialize() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::serialize::<Self>(self, context, is_field)
    }
}
