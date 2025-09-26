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

use crate::object::{derive_enum, misc, read, write};
use crate::util::sorted_fields;
use proc_macro::TokenStream;
use quote::quote;

pub fn derive_serializer(ast: &syn::DeriveInput) -> TokenStream {
    let name = &ast.ident;
    // StructSerializer
    let (actual_type_id_ts, get_sorted_field_names_ts, type_def_ts, read_compatible_ts) =
        match &ast.data {
            syn::Data::Struct(s) => {
                let fields = sorted_fields(&s.fields);
                (
                    misc::gen_actual_type_id(),
                    misc::gen_get_sorted_field_names(&fields),
                    misc::gen_type_def(&fields),
                    read::gen_read_compatible(&fields, name),
                )
            }
            syn::Data::Enum(s) => (
                derive_enum::gen_actual_type_id(),
                quote! { unreachable!() },
                derive_enum::gen_type_def(s),
                derive_enum::gen_read_compatible(),
            ),
            syn::Data::Union(_) => {
                panic!("Union is not supported")
            }
        };
    // Serializer
    let (
        reserved_space_ts,
        write_type_info_ts,
        read_type_info_ts,
        write_ts,
        read_ts,
        serialize_ts,
        deserialize_ts,
    ) = match &ast.data {
        syn::Data::Struct(s) => {
            let fields = sorted_fields(&s.fields);
            (
                write::gen_reserved_space(&fields),
                write::gen_write_type_info(),
                read::gen_read_type_info(),
                write::gen_write(&fields),
                read::gen_read(&fields),
                write::gen_serialize(),
                read::gen_deserialize(name),
            )
        }
        syn::Data::Enum(e) => (
            derive_enum::gen_reserved_space(),
            derive_enum::gen_write_type_info(),
            derive_enum::gen_read_type_info(),
            derive_enum::gen_write(e),
            derive_enum::gen_read(e),
            derive_enum::gen_serialize(e),
            derive_enum::gen_deserialize(e),
        ),
        syn::Data::Union(_) => {
            panic!("Union is not supported")
        }
    };
    // extra
    let (deserialize_nullable_ts,) = match &ast.data {
        syn::Data::Struct(s) => {
            let fields = sorted_fields(&s.fields);
            (read::gen_deserialize_nullable(&fields),)
        }
        syn::Data::Enum(_s) => (quote! {},),
        syn::Data::Union(_) => {
            panic!("Union is not supported")
        }
    };

    // Allocate a unique type ID once and share it between both functions
    let type_idx = misc::allocate_type_id();

    let gen = quote! {
        impl fory_core::serializer::StructSerializer for #name {
            fn fory_type_index() -> u32 {
                #type_idx
            }

            fn fory_actual_type_id(type_id: u32, register_by_name: bool, mode: &fory_core::types::Mode) -> u32 {
                #actual_type_id_ts
            }

            fn fory_get_sorted_field_names(fory: &fory_core::fory::Fory) -> Vec<String> {
                #get_sorted_field_names_ts
            }

            fn fory_type_def(fory: &fory_core::fory::Fory, type_id: u32, namespace: &str, type_name: &str, register_by_name: bool) -> Vec<u8> {
                #type_def_ts
            }

            fn fory_read_compatible(context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, fory_core::error::Error> {
                #read_compatible_ts
            }
        }
        impl fory_core::types::ForyGeneralList for #name {}
        impl fory_core::serializer::Serializer for #name {
            fn fory_get_type_id(fory: &fory_core::fory::Fory) -> u32 {
                fory.get_type_resolver().get_type_id(&std::any::TypeId::of::<Self>(), #type_idx)
            }

            fn fory_reserved_space() -> usize {
                #reserved_space_ts
            }

            fn fory_write_type_info(context: &mut fory_core::resolver::context::WriteContext, is_field: bool) {
                #write_type_info_ts
            }

            fn fory_read_type_info(context: &mut fory_core::resolver::context::ReadContext, is_field: bool) {
                #read_type_info_ts
            }

            fn fory_write(&self, context: &mut fory_core::resolver::context::WriteContext, is_field: bool) {
                #write_ts
            }

            fn fory_read(context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, fory_core::error::Error> {
                #read_ts
            }

            fn fory_serialize(&self, context: &mut fory_core::resolver::context::WriteContext, is_field: bool) {
                #serialize_ts
            }

            fn fory_deserialize(context: &mut fory_core::resolver::context::ReadContext, is_field: bool) -> Result<Self, fory_core::error::Error> {
                #deserialize_ts
            }
        }
        impl #name {
            #deserialize_nullable_ts
        }
    };
    gen.into()
}
