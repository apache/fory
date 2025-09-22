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
        Vec::new()
    }
}

pub fn gen_write(data_enum: &DataEnum) -> TokenStream {
    let variant_idents: Vec<_> = data_enum.variants.iter().map(|v| &v.ident).collect();
    let variant_values: Vec<_> = (0..variant_idents.len()).map(|v| v as u32).collect();

    quote! {
        fn write(&self, context: &mut fory_core::resolver::context::WriteContext, _is_field: bool) {
            match self {
                #(
                    Self::#variant_idents => {
                        context.writer.var_uint32(#variant_values);
                    }
                )*
            }
        }

        fn write_type_info(context: &mut fory_core::resolver::context::WriteContext, is_field: bool){
            let local_type_id = Self::get_type_id(context.get_fory());
            let internal_type_id = local_type_id & 0xff;
            if internal_type_id == fory_core::types::TypeId::NAMED_ENUM as u32 {
                todo!();
                let type_id = Self::get_type_id(context.get_fory());
                context.writer.var_uint32(type_id);
            } else {
                if !is_field {
                    let type_id = Self::get_type_id(context.get_fory());
                    context.writer.var_uint32(type_id);
                }
            }
        }

        fn reserved_space() -> usize {
            4
        }
    }
}

pub fn gen_read(data_enum: &DataEnum) -> TokenStream {
    let variant_idents: Vec<_> = data_enum.variants.iter().map(|v| &v.ident).collect();
    let variant_values: Vec<_> = (0..variant_idents.len()).map(|v| v as u32).collect();

    quote! {
       fn read(
           context: &mut fory_core::resolver::context::ReadContext,
       ) -> Result<Self, fory_core::error::Error> {
           let ordinal = context.reader.var_uint32();
           match ordinal {
               #(
                   #variant_values => Ok(Self::#variant_idents),
               )*
               _ => panic!("unknown value"),
           }
       }

       fn read_type_info(context: &mut fory_core::resolver::context::ReadContext, is_field: bool) {
            let local_type_id = Self::get_type_id(context.get_fory());
            let internal_type_id = local_type_id & 0xff;
            if internal_type_id == fory_core::types::TypeId::NAMED_ENUM as u32 {
                todo!();
                let remote_type_id = context.reader.var_uint32();
                assert_eq!(remote_type_id, local_type_id);
            } else {
                if !is_field {
                    let remote_type_id = context.reader.var_uint32();
                    assert_eq!(remote_type_id, local_type_id);
                }
            }
        }
    }
}

pub fn gen_actual_type_id() -> TokenStream {
    quote! {
        if register_by_name {
            fory_core::types::TypeId::NAMED_ENUM as u32
        } else {
            (type_id << 8) + fory_core::types::TypeId::ENUM as u32
        }
    }
}

pub fn gen_read_compatible(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        fn read_compatible(context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, fory_core::error::Error> {
            <Self as fory_core::serializer::Serializer>::read(context)
        }
    }
}
