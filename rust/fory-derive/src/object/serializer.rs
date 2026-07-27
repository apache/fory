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

use crate::object::util::is_default_value_variant;
use crate::object::{derive_enum, misc, read, write};
use crate::util::{extract_fields, source_fields, target_expr_path};
use crate::ForyAttrs;
use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::{Data, Fields, Type};

fn has_derive(ast: &syn::DeriveInput, trait_name: &str) -> bool {
    ast.attrs.iter().any(|attr| {
        attr.path().is_ident("derive") && {
            let mut found = false;
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident(trait_name) {
                    found = true;
                }
                Ok(())
            });
            found
        }
    })
}

pub fn derive_serializer(
    ast: &syn::DeriveInput,
    attrs: ForyAttrs,
    runtime_root: proc_macro2::TokenStream,
) -> TokenStream {
    let name = &ast.ident;
    let (impl_generics, ty_generics, where_clause) = ast.generics.split_for_impl();
    let provider_ty: Type = match syn::parse2(quote! { #name #ty_generics }) {
        Ok(provider) => provider,
        Err(err) => return err.into_compile_error().into(),
    };
    let is_external = attrs.target.is_some();
    let target_ty: Type = match attrs.target.clone() {
        Some(target) => target,
        None => provider_ty.clone(),
    };
    let target_path = match target_expr_path(&target_ty) {
        Ok(path) => path,
        Err(err) => return err.into_compile_error().into(),
    };
    let provider_path = match target_expr_path(&provider_ty) {
        Ok(path) => path,
        Err(err) => return err.into_compile_error().into(),
    };
    let schema_use = if is_external {
        gen_external_schema_use(ast, &provider_ty, &provider_path)
    } else {
        quote! {}
    };
    let write_data_attr = if is_external {
        quote! { #[inline(never)] }
    } else {
        quote! { #[inline] }
    };

    use crate::object::util::{clear_struct_context, set_struct_context};
    set_struct_context(&name.to_string(), attrs.debug_enabled);

    let default_impl = if attrs.generate_default && !has_derive(ast, "Default") {
        generate_default_impl(ast)
    } else {
        quote! {}
    };
    let send_sync = generate_send_sync_tokens(ast);

    let (
        actual_type_id,
        sorted_field_names,
        fields_info,
        variants_fields_info,
        read_compatible,
        variant_meta_types,
        write_complete,
        write_data,
        write_type_info,
        read_complete,
        read_with_type_info,
        read_data,
        read_type_info,
        default_value,
        reserved_space,
        static_type_id,
    ) = match &ast.data {
        Data::Struct(data) => {
            let source = source_fields(&data.fields);
            let fields = extract_fields(&source);
            let actual_type_id = if attrs.evolving == Some(false) {
                misc::gen_actual_type_id_no_evolving()
            } else {
                misc::gen_actual_type_id()
            };
            (
                actual_type_id,
                misc::gen_get_sorted_field_names(&fields),
                misc::gen_field_fields_info(&source),
                quote! {
                    let _ = type_resolver;
                    Ok(::std::vec::Vec::new())
                },
                read::gen_read_compatible(&data.fields, &source, &target_path),
                Vec::new(),
                write::gen_write(),
                write::gen_write_data(&source),
                write::gen_write_type_info(),
                read::gen_read(),
                read::gen_read_with_type_info(),
                read::gen_read_data(&data.fields, &source, &target_path),
                read::gen_read_type_info(),
                read::gen_default_value(&data.fields, &source, &target_path),
                write::gen_reserved_space(&source),
                quote! { fory_core::TypeId::STRUCT },
            )
        }
        Data::Enum(data) => (
            derive_enum::gen_actual_type_id(data),
            quote! { &[] },
            derive_enum::gen_field_fields_info(data),
            derive_enum::gen_variants_fields_info(name, &ast.generics, data),
            quote! {
                let _ = (context, type_info);
                Err(fory_core::Error::not_allowed(
                    "read_compatible is valid only for struct targets",
                ))
            },
            derive_enum::gen_variant_meta_types(name, &ast.generics, data),
            derive_enum::gen_write(data),
            derive_enum::gen_write_data(data, &ast.generics, &target_path),
            derive_enum::gen_write_type_info(data),
            derive_enum::gen_read(data),
            derive_enum::gen_read_with_type_info(data),
            derive_enum::gen_read_data(data, &ast.generics, &target_path),
            derive_enum::gen_read_type_info(data),
            derive_enum::gen_default_value(data, &target_path),
            derive_enum::gen_reserved_space(),
            derive_enum::gen_static_type_id(data),
        ),
        Data::Union(_) => {
            clear_struct_context();
            return syn::Error::new_spanned(name, "Rust unions are not supported")
                .into_compile_error()
                .into();
        }
    };

    let type_index = misc::allocate_type_id();
    let serializer_arc = send_sync.serializer;
    let compatible_arc = send_sync.struct_read_compatible;
    let generated = quote! {
        const _: () = {
        use #runtime_root as fory_core;

        #schema_use

        #(#variant_meta_types)*

        #default_impl

        impl #impl_generics fory_core::StructSerializer for #name #ty_generics #where_clause {
            #[inline(always)]
            fn type_index() -> u32 {
                #type_index
            }

            #[cold]
            #[inline(never)]
            fn actual_type_id(
                type_id: u32,
                register_by_name: bool,
                compatible: bool,
                xlang: bool,
            ) -> ::std::result::Result<u32, fory_core::Error> {
                #actual_type_id
            }

            fn sorted_field_names() -> &'static [&'static str] {
                #sorted_field_names
            }

            fn fields_info(
                type_resolver: &fory_core::resolver::TypeResolver,
            ) -> ::std::result::Result<
                ::std::vec::Vec<fory_core::meta::FieldInfo>,
                fory_core::Error,
            > {
                #fields_info
            }

            fn variants_fields_info(
                type_resolver: &fory_core::resolver::TypeResolver,
            ) -> ::std::result::Result<
                ::std::vec::Vec<(
                    ::std::string::String,
                    ::std::any::TypeId,
                    ::std::vec::Vec<fory_core::meta::FieldInfo>,
                )>,
                fory_core::Error,
            > {
                #variants_fields_info
            }

            // Compatible mode enters this for every structural read, so this
            // normal mode-specific path must not be marked cold.
            #[inline(never)]
            fn read_compatible(
                context: &mut fory_core::ReadContext,
                type_info: &::std::rc::Rc<fory_core::TypeInfo>,
            ) -> ::std::result::Result<Self::Target, fory_core::Error> {
                #read_compatible
            }

            #compatible_arc
        }

        impl #impl_generics fory_core::Serializer for #name #ty_generics #where_clause {
            type Target = #target_ty;

            // External structural serializers need a stable body boundary:
            // recursive carrier composition would otherwise duplicate the
            // generated body into every child monomorph. Self-owned serializers
            // retain the normal heuristic so small bodies can still inline.
            #write_data_attr
            fn write_data(
                value: &Self::Target,
                context: &mut fory_core::WriteContext,
            ) -> ::std::result::Result<(), fory_core::Error> {
                #write_data
            }

            #[inline]
            fn read_data(
                context: &mut fory_core::ReadContext,
            ) -> ::std::result::Result<Self::Target, fory_core::Error> {
                #read_data
            }

            #[inline(always)]
            fn default_value(
                context: &mut fory_core::ReadContext,
            ) -> ::std::result::Result<Self::Target, fory_core::Error> {
                #default_value
            }

            #[inline(always)]
            fn write(
                value: &Self::Target,
                context: &mut fory_core::WriteContext,
                ref_mode: fory_core::RefMode,
                write_type_info: bool,
            ) -> ::std::result::Result<(), fory_core::Error> {
                #write_complete
            }

            #[inline(always)]
            fn read(
                context: &mut fory_core::ReadContext,
                ref_mode: fory_core::RefMode,
                read_type_info: bool,
            ) -> ::std::result::Result<Self::Target, fory_core::Error> {
                #read_complete
            }

            #[inline(always)]
            fn read_with_type_info(
                context: &mut fory_core::ReadContext,
                ref_mode: fory_core::RefMode,
                type_info: &::std::rc::Rc<fory_core::TypeInfo>,
            ) -> ::std::result::Result<Self::Target, fory_core::Error> {
                #read_with_type_info
            }

            #[inline(always)]
            fn write_type_info(
                context: &mut fory_core::WriteContext,
            ) -> ::std::result::Result<(), fory_core::Error> {
                #write_type_info
            }

            #[inline(always)]
            fn read_type_info(
                context: &mut fory_core::ReadContext,
            ) -> ::std::result::Result<(), fory_core::Error> {
                #read_type_info
            }

            #[inline(always)]
            fn static_type_id() -> fory_core::TypeId {
                #static_type_id
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                #reserved_space
            }

            #serializer_arc
        }
        };
    };
    clear_struct_context();
    generated.into()
}

fn gen_external_schema_use(
    ast: &syn::DeriveInput,
    provider_ty: &Type,
    provider_path: &proc_macro2::TokenStream,
) -> proc_macro2::TokenStream {
    let (impl_generics, _, where_clause) = ast.generics.split_for_impl();
    let body = match &ast.data {
        Data::Struct(data) => {
            let field_reads = data.fields.iter().enumerate().map(|(index, field)| {
                let access = match &field.ident {
                    Some(ident) => quote! { value.#ident },
                    None => {
                        let index = syn::Index::from(index);
                        quote! { value.#index }
                    }
                };
                quote! { let _ = &#access; }
            });
            quote! {
                let _ = value;
                #(#field_reads)*
            }
        }
        Data::Enum(data) => {
            let constructors = data
                .variants
                .iter()
                .enumerate()
                .map(|(variant_index, variant)| {
                    let variant_ident = &variant.ident;
                    match &variant.fields {
                        Fields::Unit => quote! {
                            let _ = || #provider_path::#variant_ident;
                        },
                        Fields::Unnamed(fields) => {
                            let args: Vec<_> = fields
                                .unnamed
                                .iter()
                                .enumerate()
                                .map(|(field_index, field)| {
                                    let ident = format_ident!(
                                        "__fory_variant_{variant_index}_field_{field_index}"
                                    );
                                    (ident, &field.ty)
                                })
                                .collect();
                            let idents = args.iter().map(|(ident, _)| ident);
                            let params = args.iter().map(|(ident, ty)| quote! { #ident: #ty });
                            quote! {
                                let _ = |#(#params),*| {
                                    #provider_path::#variant_ident(#(#idents),*)
                                };
                            }
                        }
                        Fields::Named(fields) => {
                            let args: Vec<_> = fields
                                .named
                                .iter()
                                .enumerate()
                                .map(|(field_index, field)| {
                                    let field_ident = field.ident.as_ref().unwrap();
                                    let binding = format_ident!(
                                        "__fory_variant_{variant_index}_field_{field_index}"
                                    );
                                    (field_ident, binding, &field.ty)
                                })
                                .collect();
                            let params =
                                args.iter().map(|(_, binding, ty)| quote! { #binding: #ty });
                            let inits = args
                                .iter()
                                .map(|(field, binding, _)| quote! { #field: #binding });
                            quote! {
                                let _ = |#(#params),*| {
                                    #provider_path::#variant_ident { #(#inits),* }
                                };
                            }
                        }
                    }
                });
            let match_arms = data
                .variants
                .iter()
                .enumerate()
                .map(|(variant_index, variant)| {
                    let variant_ident = &variant.ident;
                    match &variant.fields {
                        Fields::Unit => quote! {
                            #provider_path::#variant_ident => {}
                        },
                        Fields::Unnamed(fields) => {
                            let bindings: Vec<_> = fields
                                .unnamed
                                .iter()
                                .enumerate()
                                .map(|(field_index, _)| {
                                    format_ident!(
                                        "__fory_variant_{variant_index}_field_{field_index}"
                                    )
                                })
                                .collect();
                            quote! {
                                #provider_path::#variant_ident(#(#bindings),*) => {
                                    let _ = (#(#bindings),*);
                                }
                            }
                        }
                        Fields::Named(fields) => {
                            let bindings: Vec<_> = fields
                                .named
                                .iter()
                                .enumerate()
                                .map(|(field_index, field)| {
                                    let field_ident = field.ident.as_ref().unwrap();
                                    let binding = format_ident!(
                                        "__fory_variant_{variant_index}_field_{field_index}"
                                    );
                                    (field_ident, binding)
                                })
                                .collect();
                            let patterns = bindings
                                .iter()
                                .map(|(field, binding)| quote! { #field: #binding });
                            let values = bindings.iter().map(|(_, binding)| binding);
                            quote! {
                                #provider_path::#variant_ident { #(#patterns),* } => {
                                    let _ = (#(#values),*);
                                }
                            }
                        }
                    }
                });
            // Exhaustive borrowed matching proves payload fields are used. The constructor
            // closures are never called; they exist only because rustc's dead-code lint does not
            // count pattern-only enum use as variant construction.
            quote! {
                #(#constructors)*
                match value {
                    #(#match_arms),*
                }
            }
        }
        Data::Union(_) => quote! { let _ = value; },
    };

    quote! {
        #[allow(dead_code)]
        fn __fory_use_schema #impl_generics(
            value: &#provider_ty,
        ) #where_clause {
            #body
        }
    }
}

struct SendSyncTokens {
    serializer: proc_macro2::TokenStream,
    struct_read_compatible: proc_macro2::TokenStream,
}

fn generate_send_sync_tokens(ast: &syn::DeriveInput) -> SendSyncTokens {
    if !derive_type_is_send_sync(ast) {
        return SendSyncTokens {
            serializer: quote! {},
            struct_read_compatible: quote! {},
        };
    }
    let struct_read_compatible = if matches!(ast.data, Data::Struct(_)) {
        quote! {
            #[inline]
            fn read_compatible_arc_any(
                context: &mut fory_core::ReadContext,
                type_info: &::std::rc::Rc<fory_core::TypeInfo>,
            ) -> ::std::result::Result<
                ::std::sync::Arc<dyn ::std::any::Any + Send + Sync>,
                fory_core::Error,
            > {
                let value =
                    <Self as fory_core::StructSerializer>::read_compatible(context, type_info)?;
                Ok(::std::sync::Arc::new(value))
            }
        }
    } else {
        quote! {}
    };
    SendSyncTokens {
        serializer: quote! {
            #[inline]
            fn read_arc_any(
                context: &mut fory_core::ReadContext,
            ) -> ::std::result::Result<
                ::std::sync::Arc<dyn ::std::any::Any + Send + Sync>,
                fory_core::Error,
            > {
                let value = <Self as fory_core::Serializer>::read_data(context)?;
                Ok(::std::sync::Arc::new(value))
            }
        },
        struct_read_compatible,
    }
}

fn derive_type_is_send_sync(ast: &syn::DeriveInput) -> bool {
    use crate::object::util::{
        all_type_params_send_sync, type_is_send_sync, type_param_send_sync_bounds,
    };

    if !all_type_params_send_sync(&ast.generics) {
        return false;
    }
    let send_sync_params = type_param_send_sync_bounds(&ast.generics);
    match &ast.data {
        Data::Struct(data) => data
            .fields
            .iter()
            .all(|field| type_is_send_sync(&field.ty, &send_sync_params)),
        Data::Enum(data) => data.variants.iter().all(|variant| {
            variant
                .fields
                .iter()
                .all(|field| type_is_send_sync(&field.ty, &send_sync_params))
        }),
        Data::Union(_) => false,
    }
}

fn generate_default_impl(ast: &syn::DeriveInput) -> proc_macro2::TokenStream {
    let name = &ast.ident;
    let (impl_generics, ty_generics, where_clause) = ast.generics.split_for_impl();
    let construction = match &ast.data {
        Data::Struct(data) => {
            let mut fields: Vec<_> = data
                .fields
                .iter()
                .enumerate()
                .map(|(index, field)| {
                    let value = quote! { ::std::default::Default::default() };
                    let init = match &field.ident {
                        Some(ident) => quote! { #ident: #value },
                        None => value,
                    };
                    (index, init)
                })
                .collect();
            fields.sort_by_key(|(index, _)| *index);
            let values: Vec<_> = fields.into_iter().map(|(_, value)| value).collect();
            match &data.fields {
                Fields::Named(_) => quote! { Self { #(#values),* } },
                Fields::Unnamed(_) => quote! { Self( #(#values),* ) },
                Fields::Unit => quote! { Self },
            }
        }
        Data::Enum(data) => {
            let Some(variant) = data
                .variants
                .iter()
                .find(|variant| is_default_value_variant(variant))
                .or_else(|| data.variants.first())
            else {
                return quote! {};
            };
            let ident = &variant.ident;
            match &variant.fields {
                Fields::Unit => quote! { Self::#ident },
                Fields::Unnamed(fields) => {
                    let values = fields
                        .unnamed
                        .iter()
                        .map(|_| quote! { ::std::default::Default::default() });
                    quote! { Self::#ident( #(#values),* ) }
                }
                Fields::Named(fields) => {
                    let values = fields.named.iter().map(|field| {
                        let ident = field.ident.as_ref().unwrap();
                        quote! { #ident: ::std::default::Default::default() }
                    });
                    quote! { Self::#ident { #(#values),* } }
                }
            }
        }
        Data::Union(_) => return quote! {},
    };

    quote! {
        impl #impl_generics ::std::default::Default for #name #ty_generics #where_clause {
            fn default() -> Self {
                #construction
            }
        }
    }
}
