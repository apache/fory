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
use syn::Data;

fn has_existing_default(ast: &syn::DeriveInput, trait_name: &str) -> bool {
    ast.attrs.iter().any(|attr| {
        attr.path().is_ident("derive") && {
            let mut has_default = false;
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident(trait_name) {
                    has_default = true;
                }
                Ok(())
            });
            has_default
        }
    })
}

pub fn derive_serializer(ast: &syn::DeriveInput) -> TokenStream {
    let name = &ast.ident;
    use crate::object::util::{clear_struct_context, set_struct_context};
    set_struct_context(&name.to_string());

    // Check if ForyDefault is already derived/implemented
    let has_existing_default = has_existing_default(ast, "ForyDefault");
    let default_impl = if !has_existing_default {
        generate_default_impl(ast)
    } else {
        quote! {}
    };

    // StructSerializer
    let (actual_type_id_ts, get_sorted_field_names_ts, fields_info_ts, read_compatible_ts) =
        match &ast.data {
            syn::Data::Struct(s) => {
                let fields = sorted_fields(&s.fields);
                (
                    misc::gen_actual_type_id(),
                    misc::gen_get_sorted_field_names(&fields),
                    misc::gen_field_fields_info(&fields),
                    read::gen_read_compatible(&fields),
                )
            }
            syn::Data::Enum(s) => (
                derive_enum::gen_actual_type_id(),
                quote! { &[] },
                derive_enum::gen_field_fields_info(s),
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
        write_data_ts,
        read_data_ts,
        write_ts,
        read_ts,
    ) = match &ast.data {
        syn::Data::Struct(s) => {
            let fields = sorted_fields(&s.fields);
            (
                write::gen_reserved_space(&fields),
                write::gen_write_type_info(),
                read::gen_read_type_info(),
                write::gen_write_data(&fields),
                read::gen_read_data(&fields),
                write::gen_write(),
                read::gen_read(name),
            )
        }
        syn::Data::Enum(e) => (
            derive_enum::gen_reserved_space(),
            derive_enum::gen_write_type_info(),
            derive_enum::gen_read_type_info(),
            derive_enum::gen_write_data(e),
            derive_enum::gen_read_data(e),
            derive_enum::gen_write(e),
            derive_enum::gen_read(e),
        ),
        syn::Data::Union(_) => {
            panic!("Union is not supported")
        }
    };

    // Allocate a unique type ID once and share it between both functions
    let type_idx = misc::allocate_type_id();

    let gen = quote! {
        use fory_core::serializer::ForyDefault as _;

        #default_impl

        impl fory_core::serializer::StructSerializer for #name {
            fn fory_type_index() -> u32 {
                #type_idx
            }

            fn fory_actual_type_id(type_id: u32, register_by_name: bool, compatible: bool) -> u32 {
                #actual_type_id_ts
            }

            fn fory_get_sorted_field_names() -> &'static [&'static str] {
                #get_sorted_field_names_ts
            }

            fn fory_fields_info(type_resolver: &fory_core::resolver::type_resolver::TypeResolver) -> Result<Vec<fory_core::meta::FieldInfo>, fory_core::error::Error> {
                #fields_info_ts
            }
        }
        impl fory_core::serializer::Serializer for #name {
            fn fory_get_type_id(type_resolver: &fory_core::resolver::type_resolver::TypeResolver) -> Result<u32, fory_core::error::Error> {
                type_resolver.get_type_id(&std::any::TypeId::of::<Self>(), #type_idx)
            }

            fn fory_type_id_dyn(&self, type_resolver: &fory_core::resolver::type_resolver::TypeResolver) -> Result<u32, fory_core::error::Error> {
                Self::fory_get_type_id(type_resolver)
            }

            fn as_any(&self) -> &dyn std::any::Any {
                self
            }

            fn fory_reserved_space() -> usize {
                #reserved_space_ts
            }

            fn fory_write_type_info(context: &mut fory_core::resolver::context::WriteContext, is_field: bool) -> Result<(), fory_core::error::Error> {
                #write_type_info_ts
            }

            fn fory_read_type_info(context: &mut fory_core::resolver::context::ReadContext, is_field: bool) -> Result<(), fory_core::error::Error> {
                #read_type_info_ts
            }

            fn fory_write_data(&self, context: &mut fory_core::resolver::context::WriteContext, is_field: bool) -> Result<(), fory_core::error::Error> {
                #write_data_ts
            }

            fn fory_read_data( context: &mut fory_core::resolver::context::ReadContext, is_field: bool) -> Result<Self, fory_core::error::Error> {
                #read_data_ts
            }

            fn fory_write(&self, context: &mut fory_core::resolver::context::WriteContext, is_field: bool) -> Result<(), fory_core::error::Error> {
                #write_ts
            }

            fn fory_read(context: &mut fory_core::resolver::context::ReadContext, is_field: bool) -> Result<Self, fory_core::error::Error> {
                #read_ts
            }

            fn fory_read_compatible(context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, fory_core::error::Error> {
                #read_compatible_ts
            }
        }
    };
    let code = gen.into();
    clear_struct_context();
    code
}

fn generate_default_impl(ast: &syn::DeriveInput) -> proc_macro2::TokenStream {
    let name = &ast.ident;
    let has_existing_default = has_existing_default(ast, "Default");

    match &ast.data {
        Data::Struct(s) => {
            let fields = sorted_fields(&s.fields);

            use super::util::{
                classify_trait_object_field, create_wrapper_types_arc, create_wrapper_types_rc,
                StructField,
            };

            let field_inits = fields.iter().map(|field| {
                let ident = &field.ident;
                let ty = &field.ty;

                match classify_trait_object_field(ty) {
                    StructField::RcDyn(trait_name) => {
                        let types = create_wrapper_types_rc(&trait_name);
                        let wrapper_ty = types.wrapper_ty;
                        let trait_ident = types.trait_ident;
                        quote! {
                            #ident: {
                                let wrapper = #wrapper_ty::default();
                                std::rc::Rc::<dyn #trait_ident>::from(wrapper)
                            }
                        }
                    }
                    StructField::ArcDyn(trait_name) => {
                        let types = create_wrapper_types_arc(&trait_name);
                        let wrapper_ty = types.wrapper_ty;
                        let trait_ident = types.trait_ident;
                        quote! {
                            #ident: {
                                let wrapper = #wrapper_ty::default();
                                std::sync::Arc::<dyn #trait_ident>::from(wrapper)
                            }
                        }
                    }
                    StructField::Forward => {
                        quote! {
                            #ident: <#ty as fory_core::serializer::ForyDefault>::fory_default()
                        }
                    }
                    _ => {
                        quote! {
                            #ident: <#ty as fory_core::serializer::ForyDefault>::fory_default()
                        }
                    }
                }
            });

            if has_existing_default {
                quote! {
                   impl fory_core::serializer::ForyDefault for #name {
                        fn fory_default() -> Self {
                            Self::default()
                        }
                    }
                }
            } else {
                quote! {
                    impl fory_core::serializer::ForyDefault for #name {
                        fn fory_default() -> Self {
                            Self {
                                #(#field_inits),*
                            }
                        }
                    }
                    impl std::default::Default for #name {
                        fn default() -> Self {
                            Self::fory_default()
                        }
                    }
                }
            }
        }
        Data::Enum(e) => {
            // Check if any variant has #[default] attribute (indicates user is deriving Default)
            let has_default_variant = e
                .variants
                .iter()
                .any(|v| v.attrs.iter().any(|attr| attr.path().is_ident("default")));

            // For C-like enums, implement Default by returning the first variant
            // Only if there's no #[default] attribute (which means Default is being derived)
            if !has_default_variant {
                if let Some(first_variant) = e.variants.first() {
                    let variant_ident = &first_variant.ident;
                    quote! {
                        impl fory_core::serializer::ForyDefault for #name {
                            fn fory_default() -> Self {
                                Self::#variant_ident
                            }
                        }

                        impl std::default::Default for #name {
                            fn default() -> Self {
                                Self::#variant_ident
                            }
                        }
                    }
                } else {
                    // impl fory_core::serializer::ForyDefault for #name {
                    //     fn fory_default() -> Self {
                    //         panic!("No unit-like variants found in enum {}", stringify!(#name));
                    //     }
                    // }
                    quote! {}
                }
            } else {
                quote! {
                    impl fory_core::serializer::ForyDefault for #name {
                        fn fory_default() -> Self {
                            Self::default()
                        }
                    }
                }
            }
        }
        Data::Union(_) => quote! {},
    }
}
