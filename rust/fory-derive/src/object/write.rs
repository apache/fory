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

pub fn gen(fields: &[&Field]) -> TokenStream {
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

    // let accessor_expr = fields.iter().map(|field| {
    //     let ty = &field.ty;
    //     let ident = &field.ident;
    //     quote! {
    //         // println!("before writer is:{:?}",context.writer.dump());
    //         <#ty as fory_core::serializer::Serializer>::serialize(&self.#ident, context, true);
    //         // println!("after writer is:{:?}",context.writer.dump());
    //     }
    // });

    let reserved_size_expr: Vec<_> = fields.iter().map(|field| {
        let ty = &field.ty;
        quote! {
            <#ty as fory_core::serializer::Serializer>::reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
        }
    }).collect();

    let reserved_fn_body = if reserved_size_expr.is_empty() {
        quote! { 0 }
    } else {
        quote! { #(#reserved_size_expr)+* }
    };

    quote! {
        fn serialize(&self, context: &mut fory_core::resolver::context::WriteContext, _is_field: bool) {
            match context.get_fory().get_mode() {
                fory_core::types::Mode::SchemaConsistent => {
                    context.writer.i8(fory_core::types::RefFlag::NotNullValue as i8);
                    Self::write_type_info(context, false);
                    self.write(context, true);
                },
                fory_core::types::Mode::Compatible => {
                    context.writer.i8(fory_core::types::RefFlag::NotNullValue as i8);
                    Self::write_type_info(context, false);
                    self.write(context, true);
                }
            }
        }

        fn write(&self, context: &mut fory_core::resolver::context::WriteContext, _is_field: bool) {
            // write way before
            // #(#accessor_expr)*
            // sort and write
            #sorted_serialize
        }

         fn write_type_info(context: &mut fory_core::resolver::context::WriteContext, _is_field: bool){
            let type_id = Self::get_type_id(context.get_fory());
            context.writer.var_uint32(type_id);
            if *context.get_fory().get_mode() == fory_core::types::Mode::Compatible {
                let meta_index = context.push_meta(
                    std::any::TypeId::of::<Self>()
                ) as u32;
                context.writer.var_uint32(meta_index);
            }
        }

        fn reserved_space() -> usize {
            #reserved_fn_body
        }
    }
}
