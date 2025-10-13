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

pub fn gen_actual_type_id() -> TokenStream {
    quote! {
       fory_core::serializer::enum_::actual_type_id(type_id, register_by_name, compatible)
    }
}

pub fn gen_type_def(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::type_def(fory, type_id, namespace, type_name, register_by_name)
    }
}

pub fn gen_reserved_space() -> TokenStream {
    quote! {
       4
    }
}

pub fn gen_write_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::enum_::write_type_info::<Self>(fory, context, is_field)
    }
}

pub fn gen_read_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::enum_::read_type_info::<Self>(fory, context, is_field)
    }
}

pub fn gen_write_data(data_enum: &DataEnum) -> TokenStream {
    let variant_idents: Vec<_> = data_enum.variants.iter().map(|v| &v.ident).collect();
    let variant_values: Vec<_> = (0..variant_idents.len()).map(|v| v as u32).collect();
    quote! {
        match self {
            #(
                Self::#variant_idents => {
                    context.writer.write_varuint32(#variant_values);
                }
            )*
        }
    }
}

pub fn gen_read_data(data_enum: &DataEnum) -> TokenStream {
    let variant_idents: Vec<_> = data_enum.variants.iter().map(|v| &v.ident).collect();
    let variant_values: Vec<_> = (0..variant_idents.len()).map(|v| v as u32).collect();
    quote! {
        let ordinal = context.reader.read_varuint32();
        match ordinal {
           #(
               #variant_values => Ok(Self::#variant_idents),
           )*
           _ => panic!("unknown value"),
        }
    }
}

pub fn gen_read_compatible() -> TokenStream {
    quote! {
        fory_core::serializer::enum_::read_compatible::<Self>(fory, context)
    }
}

pub fn gen_write(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::write::<Self>(self, fory, context, is_field)
    }
}

pub fn gen_read(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::read::<Self>(fory, context, is_field)
    }
}
