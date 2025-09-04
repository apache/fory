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
    let accessor_expr = fields.iter().map(|field| {
        let ty = &field.ty;
        let ident = &field.ident;
        quote! {
            <#ty as fory_core::serializer::Serializer>::serialize(&self.#ident, context);
        }
    });

    let reserved_size_expr = fields.iter().map(|field| {
        let ty = &field.ty;
        // each field have one byte ref tag and two byte type id
        quote! {
            <#ty as fory_core::serializer::Serializer>::reserved_space() + fory_core::types::SIZE_OF_REF_AND_TYPE
        }
    });

    quote! {
        fn serialize(&self, context: &mut fory_core::resolver::context::WriteContext) {
            match context.get_fory().get_mode() {
                fory_core::types::Mode::SchemaConsistent => {
                    fory_core::serializer::serialize(self, context);
                },
                fory_core::types::Mode::Compatible => {
                    context.writer.i8(fory_core::types::RefFlag::NotNullValue as i8);
                    let type_id = Self::get_type_id(context.get_fory());
                    context.writer.var_uint32(type_id);
                    self.write(context);
                }
            }
        }


        fn write(&self, context: &mut fory_core::resolver::context::WriteContext) {
            let _meta_index = context.push_meta(
                std::any::TypeId::of::<Self>()
            ) as i16;
            // write fields
            #(#accessor_expr)*
        }

        fn reserved_space() -> usize {
            #(#reserved_size_expr)+*
        }
    }
}
