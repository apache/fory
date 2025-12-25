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

use crate::util::{extract_fields, source_fields};
use proc_macro::TokenStream;
use quote::quote;

pub fn derive_row(ast: &syn::DeriveInput) -> TokenStream {
    let name = &ast.ident;
    let source_fields = match &ast.data {
        syn::Data::Struct(s) => source_fields(&s.fields),
        _ => {
            panic!("only struct be supported")
        }
    };
    let fields = extract_fields(&source_fields);

    let write_exprs = fields.iter().enumerate().map(|(index, field)| {
        let ty = &field.ty;
        let ident = field.ident.as_ref().expect("field should provide ident");

        quote! {
            let mut callback_info = struct_writer.write_start(#index);
            <#ty as fory_core::row::Row<'a>>::write(&v.#ident, struct_writer.get_writer())?;
            struct_writer.write_end(callback_info);
        }
    });

    let getter_exprs = fields.iter().enumerate().map(|(index, field)| {
        let ty = &field.ty;
        let ident = field.ident.as_ref().expect("field should provide ident");
        let getter_name: proc_macro2::Ident = syn::Ident::new(&format!("{ident}"), ident.span());

        quote! {
            pub fn #getter_name(&self) -> <#ty as fory_core::row::Row<'a>>::ReadResult {
                let bytes = self.struct_data.get_field_bytes(#index);
                <#ty as fory_core::row::Row<'a>>::cast(bytes)
            }
        }
    });

    let getter: proc_macro2::Ident = syn::Ident::new(&format!("{name}ForyRowGetter"), name.span());

    let num_fields = fields.len();

    let gen = quote! {
        struct #getter<'a> {
            struct_data: fory_core::row::StructViewer<'a>
        }

        impl<'a> #getter<'a> {
            #(#getter_exprs)*
        }

        impl<'a> fory_core::row::Row<'a> for #name {

            type ReadResult = #getter<'a>;

            fn write(v: &Self, writer: &mut fory_core::buffer::Writer) -> Result<(), fory_core::error::Error> {
                let mut struct_writer = fory_core::row::StructWriter::new(#num_fields, writer);
                #(#write_exprs);*;
                Ok(())
            }

            fn cast(bytes: &'a [u8]) -> Self::ReadResult {
                #getter{ struct_data: fory_core::row::StructViewer::new(bytes, #num_fields) }
            }
        }
    };
    gen.into()
}
