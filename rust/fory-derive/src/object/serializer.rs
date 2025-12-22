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

pub fn derive_serializer(
    ast: &syn::DeriveInput,
    debug_enabled: bool,
    skip_default: bool,
) -> TokenStream {
    let name = &ast.ident;
    use crate::object::util::{clear_struct_context, set_struct_context};
    set_struct_context(&name.to_string(), debug_enabled);

    // Check if ForyDefault is already derived/implemented
    let has_existing_default = has_existing_default(ast, "ForyDefault");
    let default_impl = if !has_existing_default {
        generate_default_impl(ast, skip_default)
    } else {
        quote! {}
    };

    // StructSerializer
    let (
        actual_type_id_ts,
        get_sorted_field_names_ts,
        fields_info_ts,
        variants_fields_info_ts,
        read_compatible_ts,
        enum_variant_meta_types,
    ) = match &ast.data {
        syn::Data::Struct(s) => {
            let fields = sorted_fields(&s.fields);
            (
                misc::gen_actual_type_id(),
                misc::gen_get_sorted_field_names(&fields),
                misc::gen_field_fields_info(&fields),
                quote! { Ok(Vec::new()) }, // No variants for structs
                read::gen_read_compatible(&fields),
                vec![], // No variant meta types for structs
            )
        }
        syn::Data::Enum(s) => {
            // Generate variant meta types for named variants
            let variant_meta_types =
                derive_enum::gen_all_variant_meta_types_with_enum_name(name, s);
            (
                derive_enum::gen_actual_type_id(),
                quote! { &[] },
                derive_enum::gen_field_fields_info(s),
                derive_enum::gen_variants_fields_info(name, s),
                quote! {
                    Err(fory_core::Error::not_allowed("`fory_read_compatible` should only be invoked at struct type"
                ))
                },
                variant_meta_types,
            )
        }
        syn::Data::Union(_) => {
            panic!("Union is not supported")
        }
    };
    // Serializer
    let (
        write_ts,
        write_data_ts,
        write_type_info_ts,
        read_ts,
        read_with_type_info_ts,
        read_data_ts,
        read_type_info_ts,
        reserved_space_ts,
        static_type_id_ts,
    ) = match &ast.data {
        syn::Data::Struct(s) => {
            let fields = sorted_fields(&s.fields);
            (
                write::gen_write(),
                write::gen_write_data(&fields),
                write::gen_write_type_info(),
                read::gen_read(name),
                read::gen_read_with_type_info(name),
                read::gen_read_data(&fields),
                read::gen_read_type_info(),
                write::gen_reserved_space(&fields),
                quote! { fory_core::TypeId::STRUCT },
            )
        }
        syn::Data::Enum(e) => (
            derive_enum::gen_write(e),
            derive_enum::gen_write_data(e),
            derive_enum::gen_write_type_info(),
            derive_enum::gen_read(e),
            derive_enum::gen_read_with_type_info(e),
            derive_enum::gen_read_data(e),
            derive_enum::gen_read_type_info(),
            derive_enum::gen_reserved_space(),
            quote! { fory_core::TypeId::ENUM },
        ),
        syn::Data::Union(_) => {
            panic!("Union is not supported")
        }
    };

    // Allocate a unique type ID once and share it between both functions
    let type_idx = misc::allocate_type_id();

    let gen = quote! {
        use fory_core::ForyDefault as _;

        // Generate variant meta types for enums (must be at module scope)
        #(#enum_variant_meta_types)*

        #default_impl

        impl fory_core::StructSerializer for #name {
            #[inline(always)]
            fn fory_type_index() -> u32 {
                #type_idx
            }

            #[inline(always)]
            fn fory_actual_type_id(type_id: u32, register_by_name: bool, compatible: bool) -> u32 {
                #actual_type_id_ts
            }

            fn fory_get_sorted_field_names() -> &'static [&'static str] {
                #get_sorted_field_names_ts
            }

            fn fory_fields_info(type_resolver: &fory_core::resolver::type_resolver::TypeResolver) -> Result<Vec<fory_core::meta::FieldInfo>, fory_core::error::Error> {
                #fields_info_ts
            }

            fn fory_variants_fields_info(type_resolver: &fory_core::resolver::type_resolver::TypeResolver) -> Result<Vec<(String, std::any::TypeId, Vec<fory_core::meta::FieldInfo>)>, fory_core::error::Error> {
                #variants_fields_info_ts
            }

            #[inline]
            fn fory_read_compatible(context: &mut fory_core::resolver::context::ReadContext, type_info: std::rc::Rc<fory_core::TypeInfo>) -> Result<Self, fory_core::error::Error> {
                #read_compatible_ts
            }
        }

        impl fory_core::Serializer for #name {
            #[inline(always)]
            fn fory_get_type_id(type_resolver: &fory_core::resolver::type_resolver::TypeResolver) -> Result<u32, fory_core::error::Error> {
                type_resolver.get_type_id(&std::any::TypeId::of::<Self>(), #type_idx)
                    .map_err(fory_core::error::Error::enhance_type_error::<Self>)
            }

            #[inline(always)]
            fn fory_type_id_dyn(&self, type_resolver: &fory_core::resolver::type_resolver::TypeResolver) -> Result<u32, fory_core::error::Error> {
                Self::fory_get_type_id(type_resolver)
            }

            #[inline(always)]
            fn as_any(&self) -> &dyn std::any::Any {
                self
            }

            #[inline(always)]
            fn fory_static_type_id() -> fory_core::TypeId
            where
                Self: Sized,
            {
                #static_type_id_ts
            }

            #[inline(always)]
            fn fory_reserved_space() -> usize {
                #reserved_space_ts
            }

            #[inline(always)]
            fn fory_write(&self, context: &mut fory_core::resolver::context::WriteContext, write_ref_info: bool, write_type_info: bool, _: bool) -> Result<(), fory_core::error::Error> {
                #write_ts
            }

            #[inline]
            fn fory_write_data(&self, context: &mut fory_core::resolver::context::WriteContext) -> Result<(), fory_core::error::Error> {
                #write_data_ts
            }

            #[inline(always)]
            fn fory_write_type_info(context: &mut fory_core::resolver::context::WriteContext) -> Result<(), fory_core::error::Error> {
                #write_type_info_ts
            }

            #[inline(always)]
            fn fory_read(context: &mut fory_core::resolver::context::ReadContext, read_ref_info: bool, read_type_info: bool) -> Result<Self, fory_core::error::Error> {
                #read_ts
            }

            #[inline(always)]
            fn fory_read_with_type_info(context: &mut fory_core::resolver::context::ReadContext, read_ref_info: bool, type_info: std::rc::Rc<fory_core::TypeInfo>) -> Result<Self, fory_core::error::Error> {
                #read_with_type_info_ts
            }

            #[inline]
            fn fory_read_data( context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, fory_core::error::Error> {
                #read_data_ts
            }

            #[inline(always)]
            fn fory_read_type_info(context: &mut fory_core::resolver::context::ReadContext) -> Result<(), fory_core::error::Error> {
                #read_type_info_ts
            }
        }
    };
    let code = gen.into();
    clear_struct_context();
    code
}

fn generate_default_impl(ast: &syn::DeriveInput, skip_default: bool) -> proc_macro2::TokenStream {
    let name = &ast.ident;
    // If skip_default is set, treat it as if there's an existing Default implementation
    let has_existing_default = skip_default || has_existing_default(ast, "Default");

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
                            #ident: <#ty as fory_core::ForyDefault>::fory_default()
                        }
                    }
                    _ => {
                        quote! {
                            #ident: <#ty as fory_core::ForyDefault>::fory_default()
                        }
                    }
                }
            });

            if has_existing_default {
                // User has their own Default impl (either via #[derive(Default)] or manual impl)
                // Only generate ForyDefault that delegates to Default
                quote! {
                   impl fory_core::ForyDefault for #name {
                        fn fory_default() -> Self {
                            Self::default()
                        }
                    }
                }
            } else {
                quote! {
                    impl fory_core::ForyDefault for #name {
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
            // Also skip if skip_default is set
            if has_existing_default || has_default_variant {
                // User has their own Default impl
                quote! {
                    impl fory_core::ForyDefault for #name {
                        fn fory_default() -> Self {
                            Self::default()
                        }
                    }
                }
            } else if let Some(first_variant) = e.variants.first() {
                let variant_ident = &first_variant.ident;
                let field_defaults = match &first_variant.fields {
                    syn::Fields::Unit => quote! {},
                    syn::Fields::Unnamed(fields) => {
                        let defaults =
                            (0..fields.unnamed.len()).map(|_| quote! { Default::default() });
                        quote! { (#(#defaults),*) }
                    }
                    syn::Fields::Named(fields) => {
                        let field_idents = fields.named.iter().map(|f| &f.ident);
                        let defaults = fields.named.iter().map(|_| quote! { Default::default() });
                        quote! { { #(#field_idents: #defaults),* } }
                    }
                };
                quote! {
                    impl fory_core::ForyDefault for #name {
                        fn fory_default() -> Self {
                            Self::#variant_ident #field_defaults
                        }
                    }

                    impl std::default::Default for #name {
                        fn default() -> Self {
                            Self::#variant_ident #field_defaults
                        }
                    }
                }
            } else {
                quote! {}
            }
        }
        Data::Union(_) => quote! {},
    }
}
