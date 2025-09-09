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
use syn::DataEnum;

pub fn gen_type_def(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        fn type_def(fory: &fory_core::fory::Fory, type_id: u32) -> Vec<u8> {
            Vec::new()
        }
    }
}

pub fn gen_write(data_enum: &DataEnum) -> TokenStream {
    let variant_idents: Vec<_> = data_enum.variants.iter().map(|v| &v.ident).collect();
    let variant_values: Vec<_> = (0..variant_idents.len()).map(|v| v as i32).collect();

    quote! {
        fn write(&self, context: &mut fory_core::resolver::context::WriteContext) {
            match self {
                #(
                    Self::#variant_idents => {
                        context.writer.var_int32(#variant_values);
                    }
                )*
            }
        }

        fn reserved_space() -> usize {
            4
        }
    }
}

pub fn gen_read(data_enum: &DataEnum) -> TokenStream {
    let variant_idents: Vec<_> = data_enum.variants.iter().map(|v| &v.ident).collect();
    let variant_values: Vec<_> = (0..variant_idents.len()).map(|v| v as i32).collect();

    quote! {
       fn read(
           context: &mut fory_core::resolver::context::ReadContext,
       ) -> Result<Self, fory_core::error::Error> {
           let v = context.reader.var_int32();
           match v {
               #(
                   #variant_values => Ok(Self::#variant_idents),
               )*
               _ => panic!("unknown value"),
           }
       }
    }
}
