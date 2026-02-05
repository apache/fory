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
use crate::util::{extract_fields, source_fields};
use crate::ForyAttrs;
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

pub fn derive_serializer(ast: &syn::DeriveInput, attrs: ForyAttrs) -> TokenStream {
    let name = &ast.ident;
    let (impl_generics, ty_generics, where_clause) = ast.generics.split_for_impl();

    // Extract type parameter names from generics (e.g., "C", "T", "E")
    let type_params: std::collections::HashSet<String> = ast
        .generics
        .params
        .iter()
        .filter_map(|p| match p {
            syn::GenericParam::Type(tp) => Some(tp.ident.to_string()),
            _ => None, // Ignore lifetime and const parameters
        })
        .collect();

    use crate::object::util::{clear_struct_context, set_struct_context};
    set_struct_context(&name.to_string(), attrs.debug_enabled, type_params);

    // Check if ForyDefault is already derived/implemented
    let has_existing_default = has_existing_default(ast, "ForyDefault");
    let default_impl = if !has_existing_default {
        generate_default_impl(ast, attrs.generate_default)
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
            let source_fields = source_fields(&s.fields);
            let fields = extract_fields(&source_fields);
            let actual_type_id_ts = if attrs.evolving == Some(false) {
                misc::gen_actual_type_id_no_evolving()
            } else {
                misc::gen_actual_type_id()
            };
            (
                actual_type_id_ts,
                misc::gen_get_sorted_field_names(&fields),
                misc::gen_field_fields_info(&source_fields),
                quote! { Ok(Vec::new()) }, // No variants for structs
                read::gen_read_compatible(&source_fields),
                vec![], // No variant meta types for structs
            )
        }
        syn::Data::Enum(s) => {
            // Generate variant meta types for named variants
            let variant_meta_types =
                derive_enum::gen_all_variant_meta_types_with_enum_name(name, s);
            (
                derive_enum::gen_actual_type_id(s),
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
            let source_fields = source_fields(&s.fields);
            let fields = extract_fields(&source_fields);
            (
                write::gen_write(),
                write::gen_write_data(&source_fields),
                write::gen_write_type_info(),
                read::gen_read(name),
                read::gen_read_with_type_info(),
                read::gen_read_data(&source_fields),
                read::gen_read_type_info(),
                write::gen_reserved_space(&fields),
                quote! { fory_core::TypeId::STRUCT },
            )
        }
        syn::Data::Enum(e) => (
            derive_enum::gen_write(e),
            derive_enum::gen_write_data(e),
            derive_enum::gen_write_type_info(e),
            derive_enum::gen_read(e),
            derive_enum::gen_read_with_type_info(e),
            derive_enum::gen_read_data(e),
            derive_enum::gen_read_type_info(e),
            derive_enum::gen_reserved_space(),
            derive_enum::gen_static_type_id(e),
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

        impl #impl_generics fory_core::StructSerializer for #name #ty_generics #where_clause {
            #[inline(always)]
            fn fory_type_index() -> u32 {
                #type_idx
            }

            #[inline(always)]
            fn fory_actual_type_id(type_id: u32, register_by_name: bool, compatible: bool, xlang: bool) -> u32 {
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

        impl #impl_generics fory_core::Serializer for #name #ty_generics #where_clause {
            #[inline(always)]
            fn fory_get_type_id(type_resolver: &fory_core::resolver::type_resolver::TypeResolver) -> Result<fory_core::TypeId, fory_core::error::Error> {
                let type_id = type_resolver
                    .get_type_id(&std::any::TypeId::of::<Self>(), #type_idx)
                    .map_err(fory_core::error::Error::enhance_type_error::<Self>)?;
                Ok(type_id)
            }

            #[inline(always)]
            fn fory_type_id_dyn(&self, type_resolver: &fory_core::resolver::type_resolver::TypeResolver) -> Result<fory_core::TypeId, fory_core::error::Error> {
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
            fn fory_write(&self, context: &mut fory_core::resolver::context::WriteContext, ref_mode: fory_core::RefMode, write_type_info: bool, _: bool) -> Result<(), fory_core::error::Error> {
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
            fn fory_read(context: &mut fory_core::resolver::context::ReadContext, ref_mode: fory_core::RefMode, read_type_info: bool) -> Result<Self, fory_core::error::Error> {
                #read_ts
            }

            #[inline(always)]
            fn fory_read_with_type_info(context: &mut fory_core::resolver::context::ReadContext, ref_mode: fory_core::RefMode, type_info: std::rc::Rc<fory_core::TypeInfo>) -> Result<Self, fory_core::error::Error> {
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

fn generate_default_impl(
    ast: &syn::DeriveInput,
    generate_default: bool,
) -> proc_macro2::TokenStream {
    let name = &ast.ident;
    let (impl_generics, ty_generics, where_clause) = ast.generics.split_for_impl();

    // By default, we don't generate Default impl to avoid conflicts.
    // Only generate if generate_default is true AND there's no existing Default.
    let should_generate_default = generate_default && !has_existing_default(ast, "Default");

    match &ast.data {
        Data::Struct(s) => {
            let source_fields = source_fields(&s.fields);
            let is_tuple_struct = source_fields
                .first()
                .map(|sf| sf.is_tuple_struct)
                .unwrap_or(false);

            use super::util::{
                classify_trait_object_field, create_wrapper_types_arc, create_wrapper_types_rc,
                StructField,
            };

            // Generate field initializations with original index for sorting
            let mut indexed: Vec<_> = source_fields
                .iter()
                .map(|sf| {
                    let ty = &sf.field.ty;
                    let value = match classify_trait_object_field(ty) {
                        StructField::RcDyn(trait_name) => {
                            let types = create_wrapper_types_rc(&trait_name);
                            let wrapper_ty = types.wrapper_ty;
                            let trait_ident = types.trait_ident;
                            quote! {
                                {
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
                                {
                                    let wrapper = #wrapper_ty::default();
                                    std::sync::Arc::<dyn #trait_ident>::from(wrapper)
                                }
                            }
                        }
                        _ => {
                            quote! { <#ty as fory_core::ForyDefault>::fory_default() }
                        }
                    };
                    (sf.original_index, sf.field_init(value))
                })
                .collect();

            // For tuple structs, sort by original index
            if is_tuple_struct {
                indexed.sort_by_key(|(idx, _)| *idx);
            }

            let field_inits: Vec<_> = indexed.into_iter().map(|(_, ts)| ts).collect();
            let self_construction = crate::util::self_construction(is_tuple_struct, &field_inits);

            if should_generate_default {
                // User requested Default generation via #[fory(generate_default)]
                quote! {
                    impl #impl_generics fory_core::ForyDefault for #name #ty_generics #where_clause {
                        fn fory_default() -> Self {
                            #self_construction
                        }
                    }
                    impl #impl_generics std::default::Default for #name #ty_generics #where_clause {
                        fn default() -> Self {
                            Self::fory_default()
                        }
                    }
                }
            } else {
                // Default case: only generate ForyDefault, not Default
                // This avoids conflicts with existing Default implementations
                quote! {
                   impl #impl_generics fory_core::ForyDefault for #name #ty_generics #where_clause {
                        fn fory_default() -> Self {
                            #self_construction
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

            // Check if user has #[derive(Default)] on the enum
            let has_derived_default = has_existing_default(ast, "Default");

            if let Some(first_variant) = e.variants.first() {
                let variant_ident = &first_variant.ident;
                let field_defaults = match &first_variant.fields {
                    syn::Fields::Unit => quote! {},
                    syn::Fields::Unnamed(fields) => {
                        let defaults = fields.unnamed.iter().map(|f| {
                            let ty = &f.ty;
                            quote! { <#ty as fory_core::ForyDefault>::fory_default() }
                        });
                        quote! { (#(#defaults),*) }
                    }
                    syn::Fields::Named(fields) => {
                        let field_inits = fields.named.iter().map(|f| {
                            let ident = &f.ident;
                            let ty = &f.ty;
                            quote! { #ident: <#ty as fory_core::ForyDefault>::fory_default() }
                        });
                        quote! { { #(#field_inits),* } }
                    }
                };

                if has_derived_default || has_default_variant {
                    // User has #[derive(Default)] or #[default] attribute
                    // Only generate ForyDefault that delegates to Default
                    quote! {
                        impl #impl_generics fory_core::ForyDefault for #name #ty_generics #where_clause {
                            fn fory_default() -> Self {
                                Self::default()
                            }
                        }
                    }
                } else if should_generate_default {
                    // User requested Default generation via #[fory(generate_default)]
                    quote! {
                        impl #impl_generics fory_core::ForyDefault for #name #ty_generics #where_clause {
                            fn fory_default() -> Self {
                                Self::#variant_ident #field_defaults
                            }
                        }

                        impl #impl_generics std::default::Default for #name #ty_generics #where_clause {
                            fn default() -> Self {
                                Self::#variant_ident #field_defaults
                            }
                        }
                    }
                } else {
                    // Default case: only generate ForyDefault, not Default
                    quote! {
                        impl #impl_generics fory_core::ForyDefault for #name #ty_generics #where_clause {
                            fn fory_default() -> Self {
                                Self::#variant_ident #field_defaults
                            }
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
