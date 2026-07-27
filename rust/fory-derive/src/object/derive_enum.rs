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

use super::field_codec::{build_bindings, FieldBinding};
use super::util::{
    enum_variant_id, is_default_value_variant, is_runtime_unknown_variant, is_skip_enum_variant,
};
use crate::object::misc;
use crate::object::util::{get_filtered_fields_iter, get_sorted_field_names};
use crate::util::{extract_fields, source_fields, SourceField};
use proc_macro2::{Ident, TokenStream};
use quote::quote;
use syn::{DataEnum, Fields};
fn temp_var_name(i: usize) -> String {
    format!("f{}", i)
}

fn gen_write_named_variant_fields(
    source_fields: &[crate::util::SourceField<'_>],
    field_idents: &[&syn::Ident],
) -> Vec<TokenStream> {
    match build_bindings(source_fields) {
        Ok(bindings) => bindings
            .iter()
            .zip(field_idents.iter())
            .filter_map(|(binding, ident)| match binding {
                FieldBinding::Codec(binding) => Some(binding.write_field_value(quote! { #ident })),
                FieldBinding::Skipped(_) => None,
            })
            .collect(),
        Err(err) => vec![err.to_compile_error()],
    }
}

fn unnamed_source_fields(fields: &syn::FieldsUnnamed) -> Vec<SourceField<'_>> {
    fields
        .unnamed
        .iter()
        .enumerate()
        .map(|(idx, field)| SourceField {
            original_index: idx,
            field,
            field_name: idx.to_string(),
            is_tuple_struct: true,
        })
        .collect()
}

fn variant_default_value(variant: &syn::Variant, target_path: &TokenStream) -> TokenStream {
    let ident = &variant.ident;
    match &variant.fields {
        Fields::Unit => quote! { #target_path::#ident },
        Fields::Unnamed(fields) => {
            let source = unnamed_source_fields(fields);
            let bindings = match build_bindings(&source) {
                Ok(bindings) => bindings,
                Err(err) => return err.to_compile_error(),
            };
            let defaults = bindings.iter().map(FieldBinding::default_value_expr);
            quote! { #target_path::#ident( #(#defaults),* ) }
        }
        Fields::Named(fields) => {
            let fields = Fields::Named(fields.clone());
            let source = source_fields(&fields);
            let bindings = match build_bindings(&source) {
                Ok(bindings) => bindings,
                Err(err) => return err.to_compile_error(),
            };
            let defaults = source.iter().zip(bindings.iter()).map(|(source, binding)| {
                let ident = source.field.ident.as_ref().unwrap();
                let value = binding.default_value_expr();
                quote! { #ident: #value }
            });
            quote! { #target_path::#ident { #(#defaults),* } }
        }
    }
}

fn gen_write_variant_fields(
    source_fields: &[SourceField<'_>],
    field_idents: &[Ident],
) -> Vec<TokenStream> {
    match build_bindings(source_fields) {
        Ok(bindings) => bindings
            .iter()
            .zip(field_idents.iter())
            .filter_map(|(binding, ident)| match binding {
                FieldBinding::Codec(binding) => Some(binding.write_field_value(quote! { #ident })),
                FieldBinding::Skipped(_) => None,
            })
            .collect(),
        Err(err) => vec![err.to_compile_error()],
    }
}

fn gen_write_variant_elements(
    source_fields: &[SourceField<'_>],
    field_idents: &[Ident],
) -> Vec<TokenStream> {
    match build_bindings(source_fields) {
        Ok(bindings) => bindings
            .iter()
            .zip(field_idents.iter())
            .filter_map(|(binding, ident)| match binding {
                FieldBinding::Codec(binding) => Some(binding.write_with_mode(
                    quote! { #ident },
                    quote! { fory_core::RefMode::NullOnly },
                    quote! { true },
                    quote! { false },
                )),
                FieldBinding::Skipped(_) => None,
            })
            .collect(),
        Err(err) => vec![err.to_compile_error()],
    }
}

fn gen_write_single_payload(source_fields: &[SourceField<'_>], value: TokenStream) -> TokenStream {
    match build_bindings(source_fields) {
        Ok(bindings) => match bindings.as_slice() {
            [FieldBinding::Codec(binding)] => binding.write_with_mode(
                value,
                quote! { fory_core::RefMode::Tracking },
                quote! { true },
                // The union case declaration owns the recursive payload schema.
                // Losing this flag writes redundant child type metadata that
                // monomorphic peer readers interpret as carrier body bytes.
                quote! { true },
            ),
            [FieldBinding::Skipped(_)] => {
                quote! { compile_error!("skip is not valid for union payload fields"); }
            }
            _ => quote! { compile_error!("union payload helper requires exactly one field"); },
        },
        Err(err) => err.to_compile_error(),
    }
}

fn gen_read_variant_fields(source_fields: &[SourceField<'_>]) -> (Vec<TokenStream>, Vec<Ident>) {
    match build_bindings(source_fields) {
        Ok(bindings) => {
            let read_fields = bindings
                .iter()
                .map(|binding| match binding {
                    FieldBinding::Codec(binding) => binding.read_field(),
                    FieldBinding::Skipped(binding) => binding.read_default(),
                })
                .collect();
            let private_idents = bindings
                .iter()
                .map(|binding| match binding {
                    FieldBinding::Codec(binding) => binding.private_ident.clone(),
                    FieldBinding::Skipped(binding) => binding.private_ident.clone(),
                })
                .collect();
            (read_fields, private_idents)
        }
        Err(err) => (vec![err.to_compile_error()], Vec::new()),
    }
}

fn gen_read_variant_elements(
    source_fields: &[SourceField<'_>],
) -> (Vec<TokenStream>, Vec<Ident>, usize) {
    match build_bindings(source_fields) {
        Ok(bindings) => {
            let mut serialized_index = 0usize;
            let mut read_fields = Vec::with_capacity(bindings.len());
            let mut private_idents = Vec::with_capacity(bindings.len());
            for binding in bindings {
                match binding {
                    FieldBinding::Codec(binding) => {
                        let var = binding.private_ident.clone();
                        let default_expr = binding.default_value_expr();
                        let index = serialized_index;
                        serialized_index += 1;
                        let read_element = binding.read_with_mode_expr(
                            quote! { fory_core::RefMode::NullOnly },
                            quote! { true },
                        );
                        read_fields.push(quote! {
                            let #var = if #index < len {
                                #read_element
                            } else {
                                #default_expr
                            };
                        });
                        private_idents.push(var);
                    }
                    FieldBinding::Skipped(binding) => {
                        let default_expr = binding.default_value_expr();
                        let var = binding.private_ident.clone();
                        read_fields.push(quote! {
                            let #var = #default_expr;
                        });
                        private_idents.push(var);
                    }
                }
            }
            (read_fields, private_idents, serialized_index)
        }
        Err(err) => (vec![err.to_compile_error()], Vec::new(), 0),
    }
}

fn gen_read_single_payload(source_fields: &[SourceField<'_>]) -> TokenStream {
    match build_bindings(source_fields) {
        Ok(bindings) => match bindings.as_slice() {
            [FieldBinding::Codec(binding)] => binding
                .read_with_mode_expr(quote! { fory_core::RefMode::Tracking }, quote! { true }),
            [FieldBinding::Skipped(_)] => {
                quote! { compile_error!("skip is not valid for union payload fields") }
            }
            _ => quote! { compile_error!("union payload helper requires exactly one field") },
        },
        Err(err) => err.to_compile_error(),
    }
}

/// For Union-compatible enums with data variants, return UNION TypeId in xlang mode.
pub fn gen_actual_type_id(data_enum: &DataEnum) -> TokenStream {
    let is_union_compatible = is_union_compatible_enum(data_enum);
    let has_data_variants = data_enum
        .variants
        .iter()
        .any(|v| !matches!(v.fields, Fields::Unit));

    if is_union_compatible && has_data_variants {
        // Union-compatible enum: use typed/named union IDs in xlang mode
        quote! {
            Ok(if xlang {
                if register_by_name {
                    fory_core::type_id::TypeId::NAMED_UNION as u32
                } else {
                    fory_core::type_id::TypeId::TYPED_UNION as u32
                }
            } else {
                fory_core::serializer::enum_::actual_type_id(type_id, register_by_name, compatible)
            })
        }
    } else if has_data_variants {
        quote! {
            if xlang {
                Err(fory_core::Error::not_allowed(
                    "multi-field tuple and named enum variants are not representable in xlang mode",
                ))
            } else {
                Ok(fory_core::serializer::enum_::actual_type_id(
                    type_id,
                    register_by_name,
                    compatible,
                ))
            }
        }
    } else {
        quote! {
            let _ = xlang;
            Ok(fory_core::serializer::enum_::actual_type_id(
                type_id,
                register_by_name,
                compatible,
            ))
        }
    }
}

pub fn gen_field_fields_info(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        let _ = type_resolver;
        ::std::result::Result::Ok(::std::vec::Vec::new())
    }
}

pub fn gen_variants_fields_info(
    enum_name: &syn::Ident,
    generics: &syn::Generics,
    data_enum: &DataEnum,
) -> TokenStream {
    let (_, ty_generics, _) = generics.split_for_impl();
    let variant_info: Vec<TokenStream> = data_enum
        .variants
        .iter()
        .filter(|v| !is_runtime_unknown_variant(v))
        .map(|v| {
            let variant_name = v.ident.to_string();
            match &v.fields {
                Fields::Named(_fields_named) => {
                    // Generate meta type identifier for this named variant
                    let meta_type_ident = Ident::new(
                        &format!("{}_{}VariantMeta", enum_name, v.ident),
                        proc_macro2::Span::call_site()
                    );
                    quote! {
                        (
                            #variant_name.to_string(),
                            ::std::any::TypeId::of::<#meta_type_ident #ty_generics>(),
                            <#meta_type_ident #ty_generics as fory_core::serializer::enum_::NamedEnumVariantMetaTrait>::fields_info(type_resolver)?
                        )
                    }
                }
                _ => {
                    // Unit or unnamed variants - return empty field info
                    quote! {
                        (
                            #variant_name.to_string(),
                            ::std::any::TypeId::of::<()>(), // Placeholder type ID
                            ::std::vec::Vec::new()
                        )
                    }
                }
            }
        })
        .collect();

    quote! {
        let _ = type_resolver;
        ::std::result::Result::Ok(::std::vec![
            #(#variant_info),*
        ])
    }
}

pub fn gen_reserved_space() -> TokenStream {
    quote! {
       4
    }
}

pub fn gen_default_value(data_enum: &DataEnum, target_path: &TokenStream) -> TokenStream {
    let Some(variant) = data_enum
        .variants
        .iter()
        .find(|variant| is_default_value_variant(variant))
        .or_else(|| data_enum.variants.first())
    else {
        return quote! {
            Err(fory_core::Error::type_error("an enum serializer requires one variant"))
        };
    };
    let value = variant_default_value(variant, target_path);
    quote! {
        let _ = &*context;
        Ok(#value)
    }
}

/// Generate all variant meta types for an enum with the enum name
pub(crate) fn gen_variant_meta_types(
    enum_name: &syn::Ident,
    generics: &syn::Generics,
    data_enum: &DataEnum,
) -> Vec<TokenStream> {
    data_enum
        .variants
        .iter()
        .filter_map(|v| {
            if is_runtime_unknown_variant(v) {
                return None;
            }
            if let Fields::Named(fields_named) = &v.fields {
                let ident = &v.ident;
                Some(gen_named_variant_meta(
                    enum_name,
                    ident,
                    generics,
                    fields_named,
                ))
            } else {
                None
            }
        })
        .collect()
}

/// Generate a meta type that implements NamedEnumVariantMetaTrait for a named variant
/// with enum name to avoid collisions
pub(crate) fn gen_named_variant_meta(
    enum_ident: &Ident,
    variant_ident: &Ident,
    generics: &syn::Generics,
    fields: &syn::FieldsNamed,
) -> TokenStream {
    let fields_clone = syn::Fields::Named(fields.clone());
    let source_fields = source_fields(&fields_clone);
    let fields_slice = extract_fields(&source_fields);
    let filtered_fields: Vec<_> = get_filtered_fields_iter(&fields_slice).collect();
    let sorted_field_names_vec = get_sorted_field_names(&filtered_fields);

    // Generate individual field name literals
    let field_name_literals: Vec<_> = sorted_field_names_vec
        .iter()
        .map(|name| {
            quote! { #name }
        })
        .collect();

    let fields_info_ts = misc::gen_field_fields_info(&source_fields);

    // Include enum name to make meta type unique
    let meta_type_ident = Ident::new(
        &format!("{}_{}VariantMeta", enum_ident, variant_ident),
        proc_macro2::Span::call_site(),
    );
    let (impl_generics, ty_generics, where_clause) = generics.split_for_impl();

    quote! {
        struct #meta_type_ident #impl_generics (
            ::std::marker::PhantomData<fn() -> #enum_ident #ty_generics>
        ) #where_clause;

        impl #impl_generics fory_core::serializer::enum_::NamedEnumVariantMetaTrait
            for #meta_type_ident #ty_generics #where_clause
        {
            fn sorted_field_names() -> &'static [&'static str] {
                &[#(#field_name_literals),*]
            }

            fn fields_info(type_resolver: &fory_core::resolver::TypeResolver) -> ::std::result::Result<::std::vec::Vec<fory_core::meta::FieldInfo>, fory_core::error::Error> {
                #fields_info_ts
            }
        }
    }
}

pub fn gen_write(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::write::<Self>(
            value,
            context,
            ref_mode,
            write_type_info,
        )
    }
}

fn xlang_variant_branches(
    data_enum: &DataEnum,
    target_path: &TokenStream,
    default_variant_value: u32,
) -> Vec<TokenStream> {
    let is_union_compatible = is_union_compatible_enum(data_enum);

    data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            if is_runtime_unknown_variant(v) {
                return quote! {
                    #target_path::#ident(ref unknown) => {
                        context.writer.write_var_u32(unknown.case_id());
                        fory_core::serializer::unknown_case::write_unknown_case_body(
                            context,
                            unknown,
                        )?;
                    }
                };
            }

            let mut tag_value = if is_union_compatible {
                xlang_union_case_id(data_enum, idx, v)
            } else {
                idx as u32
            };
            if is_skip_enum_variant(v) {
                tag_value = default_variant_value;
            }

            match &v.fields {
                Fields::Unit => {
                    if is_union_compatible {
                        // Union-compatible: write tag + null flag (matches Java/C++ Union with null value)
                        quote! {
                            #target_path::#ident => {
                                context.writer.write_var_u32(#tag_value);
                                // Write null flag for unit variant (no value)
                                context.writer.write_i8(fory_core::RefFlag::Null as i8);
                            }
                        }
                    } else {
                        quote! {
                            #target_path::#ident => {
                                context.writer.write_var_u32(#tag_value);
                            }
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    if is_union_compatible && fields_unnamed.unnamed.len() == 1 {
                        let source_fields = unnamed_source_fields(fields_unnamed);
                        let write_payload =
                            gen_write_single_payload(&source_fields, quote! { value });
                        quote! {
                            #target_path::#ident(ref value) => {
                                context.writer.write_var_u32(#tag_value);
                                #write_payload
                            }
                        }
                    } else {
                        quote! {
                            #target_path::#ident(..) => {
                                context.writer.write_var_u32(#tag_value);
                            }
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    if is_union_compatible && fields_named.named.len() == 1 {
                        let field_ident =
                            fields_named.named.first().unwrap().ident.as_ref().unwrap();
                        let fields_clone = syn::Fields::Named(fields_named.clone());
                        let source_fields = source_fields(&fields_clone);
                        let write_payload =
                            gen_write_single_payload(&source_fields, quote! { #field_ident });
                        quote! {
                            #target_path::#ident { ref #field_ident } => {
                                context.writer.write_var_u32(#tag_value);
                                #write_payload
                            }
                        }
                    } else {
                        quote! {
                            #target_path::#ident { .. } => {
                                context.writer.write_var_u32(#tag_value);
                            }
                        }
                    }
                }
            }
        })
        .collect()
}

fn rust_variant_branches(
    data_enum: &DataEnum,
    target_path: &TokenStream,
    default_variant_value: u32,
) -> Vec<TokenStream> {
    data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            let mut tag_value = idx as u32;
            if is_skip_enum_variant(v) {
                tag_value = default_variant_value;
            }

            match &v.fields {
                Fields::Unit => {
                    quote! {
                        #target_path::#ident => {
                            context.writer.write_var_u32(#tag_value);
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    let source_fields = unnamed_source_fields(fields_unnamed);
                    let field_idents: Vec<_> = (0..fields_unnamed.unnamed.len())
                        .map(|i| Ident::new(&temp_var_name(i), proc_macro2::Span::call_site()))
                        .collect();

                    let write_fields = gen_write_variant_fields(&source_fields, &field_idents);

                    quote! {
                        #target_path::#ident( #(#field_idents),* ) => {
                            context.writer.write_var_u32(#tag_value);
                            #(#write_fields)*
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    use crate::util::source_fields;

                    let fields_clone = syn::Fields::Named(fields_named.clone());
                    let source_fields = source_fields(&fields_clone);

                    let field_idents: Vec<_> = source_fields
                        .iter()
                        .map(|sf| sf.field.ident.as_ref().unwrap())
                        .collect();

                    let write_fields =
                        gen_write_named_variant_fields(&source_fields, &field_idents);

                    quote! {
                        #target_path::#ident { #(#field_idents),* } => {
                            context.writer.write_var_u32(#tag_value) ;
                            #(#write_fields)*
                        }
                    }
                }
            }
        })
        .collect()
}

fn compatible_variant_writes(
    data_enum: &DataEnum,
    generics: &syn::Generics,
    target_path: &TokenStream,
    default_variant_value: u32,
) -> Vec<TokenStream> {
    use crate::object::util::get_struct_name;
    let enum_name = get_struct_name().expect("enum context not set");
    let (_, ty_generics, _) = generics.split_for_impl();

    data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            let mut tag_value = idx as u32;
            if is_skip_enum_variant(v) {
                tag_value = default_variant_value;
            }

            match &v.fields {
                Fields::Unit => {
                    quote! {
                        #target_path::#ident => {
                            context.writer.write_var_u32((#tag_value << 2) | 0b0);
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    // For unnamed enum variants, write using collection format (same protocol as tuple)
                    let source_fields = unnamed_source_fields(fields_unnamed);
                    let field_idents: Vec<_> = (0..fields_unnamed.unnamed.len())
                        .map(|i| Ident::new(&temp_var_name(i), proc_macro2::Span::call_site()))
                        .collect();
                    let write_fields = gen_write_variant_elements(&source_fields, &field_idents);
                    let field_count = write_fields.len();

                    quote! {
                        #target_path::#ident( #(ref #field_idents),* ) => {
                            context.writer.write_var_u32((#tag_value << 2) | 0b1);
                            // Write as collection format (same as tuple)
                            context.writer.write_var_u32(#field_count as u32);
                            let header = 0u8; // No IS_SAME_TYPE flag
                            context.writer.write_u8(header);
                            #(#write_fields)*
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    // Generate meta type identifier for this named variant
                    let meta_type_ident = Ident::new(
                        &format!("{}_{}VariantMeta", enum_name, ident),
                        proc_macro2::Span::call_site(),
                    );
                    let fields_clone = syn::Fields::Named(fields_named.clone());
                    let source_fields = source_fields(&fields_clone);
                    let field_idents: Vec<_> = source_fields
                        .iter()
                        .map(|sf| sf.field.ident.as_ref().unwrap())
                        .collect();

                    let write_fields =
                        gen_write_named_variant_fields(&source_fields, &field_idents);

                    quote! {
                        #target_path::#ident { #(#field_idents),* } => {
                            context.writer.write_var_u32((#tag_value << 2) | 0b10);
                            // Write type meta inline using streaming protocol
                            context.write_type_meta(
                                ::std::any::TypeId::of::<#meta_type_ident #ty_generics>(),
                            )?;
                            // Write fields same as struct
                            #(#write_fields)*
                        }
                    }
                }
            }
        })
        .collect()
}

pub fn gen_write_data(
    data_enum: &DataEnum,
    generics: &syn::Generics,
    target_path: &TokenStream,
) -> TokenStream {
    let native_only = data_enum
        .variants
        .iter()
        .any(|variant| !matches!(variant.fields, Fields::Unit))
        && !is_union_compatible_enum(data_enum);
    let default_variant_value = data_enum
        .variants
        .iter()
        .position(is_default_value_variant)
        .unwrap_or(0) as u32;

    let xlang_variant_branches: Vec<TokenStream> =
        xlang_variant_branches(data_enum, target_path, default_variant_value);
    let rust_variant_branches: Vec<TokenStream> =
        rust_variant_branches(data_enum, target_path, default_variant_value);
    let rust_compatible_variant_branches: Vec<TokenStream> =
        compatible_variant_writes(data_enum, generics, target_path, default_variant_value);
    let xlang_write = if native_only {
        quote! {
            Err(fory_core::Error::not_allowed(
                "multi-field tuple and named enum variants are not representable in xlang mode",
            ))
        }
    } else {
        quote! {
            match value {
                #(#xlang_variant_branches)*
            }
            Ok(())
        }
    };

    quote! {
        if context.is_xlang() {
            #xlang_write
        } else {
            if context.is_compatible() {
                match value {
                    #(#rust_compatible_variant_branches)*
                }
                Ok(())
            } else {
                match value {
                    #(#rust_variant_branches)*
                }
                Ok(())
            }
        }
    }
}

pub fn gen_write_type_info(data_enum: &DataEnum) -> TokenStream {
    let is_union_compatible = is_union_compatible_enum(data_enum);
    let has_data_variants = data_enum
        .variants
        .iter()
        .any(|v| !matches!(v.fields, Fields::Unit));

    if is_union_compatible && has_data_variants {
        // Union-compatible with data: write typed/named union type info in xlang mode
        quote! {
            if context.is_xlang() {
                let provider_type_id = ::std::any::TypeId::of::<Self>();
                context.write_provider_type_info(
                    fory_core::type_id::UNKNOWN,
                    provider_type_id,
                )?;
                Ok(())
            } else {
                fory_core::serializer::enum_::write_type_info::<Self>(context)
            }
        }
    } else {
        quote! {
            fory_core::serializer::enum_::write_type_info::<Self>(context)
        }
    }
}

pub fn gen_read(_: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::read::<Self>(context, ref_mode, read_type_info)
    }
}

pub fn gen_read_with_type_info(_: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::read::<Self>(context, ref_mode, false)
    }
}

/// Check if enum is Union-compatible:
/// - Must have at least one data-carrying variant (single-field)
/// - All variants must be either unit or single-field
fn is_union_compatible_enum(data_enum: &DataEnum) -> bool {
    let has_data_variant = data_enum
        .variants
        .iter()
        .any(|v| !is_runtime_unknown_variant(v) && !matches!(v.fields, Fields::Unit));
    let all_variants_compatible = data_enum.variants.iter().all(|v| match &v.fields {
        _ if is_runtime_unknown_variant(v) => true,
        Fields::Unit => true,
        Fields::Unnamed(f) => f.unnamed.len() == 1,
        Fields::Named(f) => f.named.len() == 1,
    });

    has_data_variant && all_variants_compatible
}

fn xlang_union_case_id(data_enum: &DataEnum, idx: usize, variant: &syn::Variant) -> u32 {
    enum_variant_id(variant).unwrap_or_else(|| {
        data_enum
            .variants
            .iter()
            .take(idx)
            .filter(|variant| !is_runtime_unknown_variant(variant))
            .count() as u32
    })
}

/// Generate the static TypeId for enum.
/// For Union-compatible enums with data variants, return UNION TypeId
/// to ensure correct type info handling in xlang mode struct field read/write.
pub fn gen_static_type_id(data_enum: &DataEnum) -> TokenStream {
    let is_union_compatible = is_union_compatible_enum(data_enum);
    let has_data_variants = data_enum
        .variants
        .iter()
        .any(|v| !matches!(v.fields, Fields::Unit));

    if is_union_compatible && has_data_variants {
        quote! { fory_core::TypeId::UNION }
    } else {
        quote! { fory_core::TypeId::ENUM }
    }
}

fn xlang_variant_read_branches(
    data_enum: &DataEnum,
    target_path: &TokenStream,
    default_variant_value: u32,
) -> Vec<TokenStream> {
    let is_union_compatible = is_union_compatible_enum(data_enum);

    data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            if is_runtime_unknown_variant(v) {
                return quote! {};
            }

            let mut tag_value = if is_union_compatible {
                xlang_union_case_id(data_enum, idx, v)
            } else {
                idx as u32
            };
            if is_skip_enum_variant(v) {
                tag_value = default_variant_value;
            }

            match &v.fields {
                Fields::Unit => {
                    if is_union_compatible {
                        // Union-compatible: read null flag (matches Java/C++ Union with null value)
                        quote! {
                            #tag_value => {
                                let _ = context.reader.read_i8()?;
                                Ok(#target_path::#ident)
                            }
                        }
                    } else {
                        quote! {
                            #tag_value => Ok(#target_path::#ident),
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    if is_union_compatible && fields_unnamed.unnamed.len() == 1 {
                        let source_fields = unnamed_source_fields(fields_unnamed);
                        let read_payload = gen_read_single_payload(&source_fields);
                        quote! {
                            #tag_value => {
                                let value = #read_payload;
                                Ok(#target_path::#ident(value))
                            }
                        }
                    } else {
                        let default_value = variant_default_value(v, target_path);
                        quote! {
                            #tag_value => Ok(#default_value),
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    if is_union_compatible && fields_named.named.len() == 1 {
                        let field = fields_named.named.first().unwrap();
                        let field_ident = field.ident.as_ref().unwrap();
                        let fields_clone = syn::Fields::Named(fields_named.clone());
                        let source_fields = source_fields(&fields_clone);
                        let read_payload = gen_read_single_payload(&source_fields);
                        quote! {
                            #tag_value => {
                                let value = #read_payload;
                                Ok(#target_path::#ident { #field_ident: value })
                            }
                        }
                    } else {
                        let default_value = variant_default_value(v, target_path);
                        quote! {
                            #tag_value => Ok(#default_value),
                        }
                    }
                }
            }
        })
        .collect()
}

fn rust_variant_read_branches(
    data_enum: &DataEnum,
    target_path: &TokenStream,
    default_variant_value: u32,
) -> Vec<TokenStream> {
    data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            let mut tag_value = idx as u32;
            if is_skip_enum_variant(v) {
                tag_value = default_variant_value;
            }

            match &v.fields {
                Fields::Unit => {
                    quote! {
                        #tag_value => Ok(#target_path::#ident),
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    let source_fields = unnamed_source_fields(fields_unnamed);
                    let (read_fields, field_idents) = gen_read_variant_fields(&source_fields);

                    quote! {
                        #tag_value => {
                            #(#read_fields;)*
                            Ok(#target_path::#ident( #(#field_idents),* ))
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    let fields_clone = syn::Fields::Named(fields_named.clone());
                    let source_fields = source_fields(&fields_clone);

                    let (read_fields, private_idents) = gen_read_variant_fields(&source_fields);
                    let field_inits: Vec<_> = source_fields
                        .iter()
                        .zip(private_idents.iter())
                        .map(|(sf, private_ident)| {
                            let field_ident = sf.field.ident.as_ref().unwrap();
                            quote! { #field_ident: #private_ident }
                        })
                        .collect();

                    quote! {
                        #tag_value => {
                            #(#read_fields;)*
                            Ok(#target_path::#ident { #(#field_inits),* })
                        }
                    }
                }
            }
        })
        .collect()
}

fn compatible_variant_reads(
    data_enum: &DataEnum,
    generics: &syn::Generics,
    target_path: &TokenStream,
    default_variant_value: u32,
) -> Vec<TokenStream> {
    use crate::object::util::get_struct_name;
    let enum_name = get_struct_name().expect("enum context not set");
    let (_, ty_generics, _) = generics.split_for_impl();
    data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            let mut tag_value = idx as u32;
            if is_skip_enum_variant(v) {
                tag_value = default_variant_value;
            }

            match &v.fields {
                Fields::Unit => {
                    // Generate default value for this variant
                    let default_value = quote! { #target_path::#ident };

                    quote! {
                        #tag_value => {
                            // Unit variant should have variant_type == 0b0
                            if variant_type != 0b0 {
                                // Variant type mismatch: skip the data and use default
                                use fory_core::serializer::skip::skip_enum_variant;
                                skip_enum_variant(context, variant_type, &None)?;
                                return Ok(#default_value);
                            }
                            Ok(#target_path::#ident)
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    // For unnamed enum variants, read using collection format (same protocol as tuple)
                    let source_fields = unnamed_source_fields(fields_unnamed);
                    let (read_fields, field_idents, field_count) =
                        gen_read_variant_elements(&source_fields);

                    let default_value = variant_default_value(v, target_path);

                    quote! {
                        #tag_value => {
                            // Unnamed variant should have variant_type == 0b1
                            if variant_type != 0b1 {
                                // Variant type mismatch: skip the data and use default
                                use fory_core::serializer::skip::skip_enum_variant;
                                skip_enum_variant(context, variant_type, &None)?;
                                return Ok(#default_value);
                            }
                            // Read collection format (same as tuple)
                            let len = context.reader.read_var_u32()? as usize;
                            let _header = context.reader.read_u8()?;

                            #(#read_fields;)*

                            // Skip any extra elements
                            use fory_core::serializer::skip::skip_any_value;
                            for _ in #field_count..len {
                                skip_any_value(context, true)?;
                            }

                            Ok(#target_path::#ident( #(#field_idents),* ))
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    use crate::util::source_fields;

                    // Sort fields to match the meta type generation
                    let fields_clone = syn::Fields::Named(fields_named.clone());
                    let source_fields = source_fields(&fields_clone);
                    let meta_type_ident = Ident::new(
                        &format!("{}_{}VariantMeta", enum_name, ident),
                        proc_macro2::Span::call_site(),
                    );
                    let meta_type = quote! { #meta_type_ident #ty_generics };

                    // Generate compatible reads that construct the runtime target.
                    let compatible_read_body = crate::object::read::gen_read_compatible_target(
                        &fields_clone,
                        &source_fields,
                        target_path,
                        Some(ident),
                        Some(&meta_type),
                    );

                    let default_value = variant_default_value(v, target_path);

                    quote! {
                        #tag_value => {
                            if variant_type != 0b10 {
                                // Variant type mismatch: peer didn't write meta for non-named variant
                                // Skip the data and use default
                                use fory_core::serializer::skip::skip_enum_variant;
                                skip_enum_variant(context, variant_type, &None)?;
                                return Ok(#default_value);
                            }
                            // Named variant should have variant_type == 0b10
                            // Read type meta inline using streaming protocol
                            let type_info = context.read_type_meta()?;
                            // Use gen_read_compatible logic
                            #compatible_read_body
                        }
                    }
                }
            }
        })
        .collect()
}

pub fn gen_read_data(
    data_enum: &DataEnum,
    generics: &syn::Generics,
    target_path: &TokenStream,
) -> TokenStream {
    let is_union_compatible = is_union_compatible_enum(data_enum);
    let has_data_variants = data_enum
        .variants
        .iter()
        .any(|v| !matches!(v.fields, Fields::Unit));
    let default_variant_value = data_enum
        .variants
        .iter()
        .position(is_default_value_variant)
        .unwrap_or(0) as u32;

    let xlang_variant_branches: Vec<TokenStream> =
        xlang_variant_read_branches(data_enum, target_path, default_variant_value);
    let rust_variant_branches: Vec<TokenStream> =
        rust_variant_read_branches(data_enum, target_path, default_variant_value);
    let rust_compatible_variant_branches: Vec<TokenStream> =
        compatible_variant_reads(data_enum, generics, target_path, default_variant_value);

    // Get the default variant for compatible mode fallback
    let default_variant = data_enum
        .variants
        .iter()
        .nth(default_variant_value as usize)
        .or_else(|| data_enum.variants.first())
        .unwrap();

    let default_variant_construction = variant_default_value(default_variant, target_path);

    let unknown_xlang_branch = if is_union_compatible && has_data_variants {
        // ForyUnion validation guarantees xlang-compatible ADTs have the
        // runtime Unknown carrier. A skip/default fallback here would drop
        // forward-compatible payload metadata instead of preserving it.
        let variant = data_enum
            .variants
            .iter()
            .find(|variant| is_runtime_unknown_variant(variant))
            .expect("xlang-compatible ForyUnion requires Unknown(UnknownCase)");
        let ident = &variant.ident;
        quote! {
            _ => {
                let value = fory_core::serializer::unknown_case::read_unknown_case_body(
                    context,
                    ordinal,
                )?;
                Ok(#target_path::#ident(value))
            }
        }
    } else {
        quote! {
            _ => {
                // Unknown variant: in compatible mode, return default; otherwise error
                if context.is_compatible() {
                    Ok(#default_variant_construction)
                } else {
                    return Err(fory_core::error::Error::unknown_enum("unknown enum value"));
                }
            }
        }
    };
    let xlang_read = if has_data_variants && !is_union_compatible {
        quote! {
            Err(fory_core::Error::not_allowed(
                "multi-field tuple and named enum variants are not representable in xlang mode",
            ))
        }
    } else {
        quote! {
            let ordinal = context.reader.read_var_u32()?;
            match ordinal {
                #(#xlang_variant_branches)*
                #unknown_xlang_branch
            }
        }
    };
    quote! {
        if context.is_xlang() {
            #xlang_read
        } else {
            if context.is_compatible() {
                let encoded_tag = context.reader.read_var_u32()?;
                let tag = encoded_tag >> 2;
                let variant_type = encoded_tag & 0b11;

                match tag {
                    #(#rust_compatible_variant_branches)*
                    _ => {
                        // Unknown variant in compatible mode: skip the data and use default variant
                        // variant_type: 0b0 = Unit, 0b1 = Unnamed, 0b10 = Named
                        use fory_core::serializer::skip::skip_enum_variant;
                        // For named variants, we don't have type_info yet, so pass None
                        // skip_enum_variant will read it from the stream
                        skip_enum_variant(context, variant_type, &None)?;
                        Ok(#default_variant_construction)
                    }
                }
            } else {
                let tag = context.reader.read_var_u32()?;
                match tag {
                    #(#rust_variant_branches)*
                    _ => return Err(fory_core::error::Error::unknown_enum("unknown enum value")),
                }
            }
        }
    }
}

pub fn gen_read_type_info(data_enum: &DataEnum) -> TokenStream {
    // Only use UNION TypeId for Union-compatible enums (unit or single-field variants)
    let is_union_compatible = is_union_compatible_enum(data_enum);
    let has_data_variants = data_enum
        .variants
        .iter()
        .any(|v| !matches!(v.fields, Fields::Unit));

    if is_union_compatible && has_data_variants {
        // Union-compatible with data: read typed/named union type info in xlang mode
        quote! {
            if context.is_xlang() {
                let expected_type_id = context
                    .get_type_resolver()
                    .get_provider_type_info(&::std::any::TypeId::of::<Self>())?
                    .get_type_id();
                let type_info = context.read_any_type_info()?;
                let remote_type_id = type_info.get_type_id();
                if remote_type_id != expected_type_id {
                    return Err(fory_core::error::Error::type_mismatch(
                        expected_type_id as u32,
                        remote_type_id as u32,
                    ));
                }
                Ok(())
            } else {
                fory_core::serializer::enum_::read_type_info::<Self>(context)
            }
        }
    } else {
        quote! {
            fory_core::serializer::enum_::read_type_info::<Self>(context)
        }
    }
}
