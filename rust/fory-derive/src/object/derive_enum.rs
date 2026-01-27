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

use super::util::{enum_variant_id, is_default_value_variant, is_skip_enum_variant};
use crate::object::misc;
use crate::object::read::gen_read_field;
use crate::object::util::{get_filtered_fields_iter, get_sorted_field_names};
use crate::object::write::gen_write_field;
use crate::util::{extract_fields, source_fields};
use proc_macro2::{Ident, TokenStream};
use quote::quote;
use syn::{DataEnum, Fields};
fn temp_var_name(i: usize) -> String {
    format!("f{}", i)
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
            if xlang {
                if register_by_name {
                    fory_core::types::TypeId::NAMED_UNION as u32
                } else {
                    (type_id << 8) | (fory_core::types::TypeId::TYPED_UNION as u32)
                }
            } else {
                fory_core::serializer::enum_::actual_type_id(type_id, register_by_name, compatible)
            }
        }
    } else {
        quote! {
            let _ = xlang;
            fory_core::serializer::enum_::actual_type_id(type_id, register_by_name, compatible)
        }
    }
}

pub fn gen_field_fields_info(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        Ok(Vec::new())
    }
}

pub fn gen_variants_fields_info(enum_name: &syn::Ident, data_enum: &DataEnum) -> TokenStream {
    let variant_info: Vec<TokenStream> = data_enum
        .variants
        .iter()
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
                            std::any::TypeId::of::<#meta_type_ident>(),
                            <#meta_type_ident as fory_core::serializer::enum_::NamedEnumVariantMetaTrait>::fory_fields_info(type_resolver)?
                        )
                    }
                }
                _ => {
                    // Unit or unnamed variants - return empty field info
                    quote! {
                        (
                            #variant_name.to_string(),
                            std::any::TypeId::of::<()>(), // Placeholder type ID
                            Vec::new()
                        )
                    }
                }
            }
        })
        .collect();

    quote! {
        Ok(vec![
            #(#variant_info),*
        ])
    }
}

pub fn gen_reserved_space() -> TokenStream {
    quote! {
       4
    }
}

/// Generate all variant meta types for an enum with the enum name
pub(crate) fn gen_all_variant_meta_types_with_enum_name(
    enum_name: &syn::Ident,
    data_enum: &DataEnum,
) -> Vec<TokenStream> {
    data_enum
        .variants
        .iter()
        .filter_map(|v| {
            if let Fields::Named(fields_named) = &v.fields {
                let ident = &v.ident;
                Some(gen_named_variant_meta_type_impl_with_enum_name(
                    enum_name,
                    ident,
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
pub(crate) fn gen_named_variant_meta_type_impl_with_enum_name(
    enum_ident: &Ident,
    variant_ident: &Ident,
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

    quote! {
        struct #meta_type_ident;

        impl fory_core::serializer::enum_::NamedEnumVariantMetaTrait for #meta_type_ident {
            fn fory_get_sorted_field_names() -> &'static [&'static str] {
                &[#(#field_name_literals),*]
            }

            fn fory_fields_info(type_resolver: &fory_core::TypeResolver) -> Result<Vec<fory_core::meta::FieldInfo>, fory_core::error::Error> {
                #fields_info_ts
            }
        }
    }
}

pub fn gen_write(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::write::<Self>(self, context, ref_mode, write_type_info)
    }
}

fn xlang_variant_branches(data_enum: &DataEnum, default_variant_value: u32) -> Vec<TokenStream> {
    let is_union_compatible = is_union_compatible_enum(data_enum);

    data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            let mut tag_value = if is_union_compatible {
                enum_variant_id(v).unwrap_or(idx as u32)
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
                            Self::#ident => {
                                context.writer.write_varuint32(#tag_value);
                                // Write null flag for unit variant (no value)
                                context.writer.write_i8(fory_core::types::RefFlag::Null as i8);
                            }
                        }
                    } else {
                        quote! {
                            Self::#ident => {
                                context.writer.write_varuint32(#tag_value);
                            }
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    if is_union_compatible && fields_unnamed.unnamed.len() == 1 {
                        // Union-compatible single field: write tag + value with type info (like xwriteRef)
                        quote! {
                            Self::#ident(ref value) => {
                                context.writer.write_varuint32(#tag_value);
                                use fory_core::serializer::Serializer;
                                value.fory_write(context, fory_core::types::RefMode::Tracking, true, false)?;
                            }
                        }
                    } else {
                        quote! {
                            Self::#ident(..) => {
                                context.writer.write_varuint32(#tag_value);
                            }
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    if is_union_compatible && fields_named.named.len() == 1 {
                        // Union-compatible single field: write tag + value with type info (like xwriteRef)
                        let field_ident =
                            fields_named.named.first().unwrap().ident.as_ref().unwrap();
                        quote! {
                            Self::#ident { ref #field_ident } => {
                                context.writer.write_varuint32(#tag_value);
                                use fory_core::serializer::Serializer;
                                #field_ident.fory_write(context, fory_core::types::RefMode::Tracking, true, false)?;
                            }
                        }
                    } else {
                        quote! {
                            Self::#ident { .. } => {
                                context.writer.write_varuint32(#tag_value);
                            }
                        }
                    }
                }
            }
        })
        .collect()
}

fn rust_variant_branches(data_enum: &DataEnum, default_variant_value: u32) -> Vec<TokenStream> {
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
                        Self::#ident => {
                            context.writer.write_varuint32(#tag_value);
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    let field_idents: Vec<_> = (0..fields_unnamed.unnamed.len())
                        .map(|i| Ident::new(&temp_var_name(i), proc_macro2::Span::call_site()))
                        .collect();

                    let write_fields: Vec<_> = fields_unnamed
                        .unnamed
                        .iter()
                        .zip(field_idents.iter())
                        .map(|(f, ident)| gen_write_field(f, ident, false))
                        .collect();

                    quote! {
                        Self::#ident( #(#field_idents),* ) => {
                            context.writer.write_varuint32(#tag_value);
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

                    let write_fields: Vec<_> = source_fields
                        .iter()
                        .zip(field_idents.iter())
                        .map(|(sf, ident)| gen_write_field(sf.field, ident, false))
                        .collect();

                    quote! {
                        Self::#ident { #(#field_idents),* } => {
                            context.writer.write_varuint32(#tag_value) ;
                            #(#write_fields)*
                        }
                    }
                }
            }
        })
        .collect()
}

fn rust_compatible_variant_write_branches(
    data_enum: &DataEnum,
    default_variant_value: u32,
) -> Vec<TokenStream> {
    use crate::object::util::get_struct_name;
    let enum_name = get_struct_name().expect("enum context not set");

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
                        Self::#ident => {
                            context.writer.write_varuint32((#tag_value << 2) | 0b0);
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    // For unnamed enum variants, write using collection format (same protocol as tuple)
                    let field_idents: Vec<_> = (0..fields_unnamed.unnamed.len())
                        .map(|i| Ident::new(&temp_var_name(i), proc_macro2::Span::call_site()))
                        .collect();

                    let field_count = fields_unnamed.unnamed.len();

                    quote! {
                        Self::#ident( #(ref #field_idents),* ) => {
                            context.writer.write_varuint32((#tag_value << 2) | 0b1);
                            // Write as collection format (same as tuple)
                            context.writer.write_varuint32(#field_count as u32);
                            let header = 0u8; // No IS_SAME_TYPE flag
                            context.writer.write_u8(header);
                            use fory_core::serializer::Serializer;
                            #(
                                #field_idents.fory_write(context, fory_core::RefMode::NullOnly, true, false)?;
                            )*
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    // Generate meta type identifier for this named variant
                    let meta_type_ident = Ident::new(
                        &format!("{}_{}VariantMeta", enum_name, ident),
                        proc_macro2::Span::call_site()
                    );
                    let fields_clone = syn::Fields::Named(fields_named.clone());
                    let source_fields = source_fields(&fields_clone);
                    let field_idents: Vec<_> = source_fields
                        .iter()
                        .map(|sf| sf.field.ident.as_ref().unwrap())
                        .collect();

                    let write_fields: Vec<_> = source_fields
                        .iter()
                        .zip(field_idents.iter())
                        .map(|(sf, ident)| gen_write_field(sf.field, ident, false))
                        .collect();

                    quote! {
                        Self::#ident { #(#field_idents),* } => {
                            context.writer.write_varuint32((#tag_value << 2) | 0b10);
                            // Write type meta inline using streaming protocol
                            context.write_type_meta(std::any::TypeId::of::<#meta_type_ident>())?;
                            // Write fields same as struct
                            #(#write_fields)*
                        }
                    }
                }
            }
        })
        .collect()
}

pub fn gen_write_data(data_enum: &DataEnum) -> TokenStream {
    let default_variant_value = data_enum
        .variants
        .iter()
        .position(is_default_value_variant)
        .unwrap_or(0) as u32;

    let xlang_variant_branches: Vec<TokenStream> =
        xlang_variant_branches(data_enum, default_variant_value);
    let rust_variant_branches: Vec<TokenStream> =
        rust_variant_branches(data_enum, default_variant_value);
    let rust_compatible_variant_branches: Vec<TokenStream> =
        rust_compatible_variant_write_branches(data_enum, default_variant_value);

    quote! {
        if context.is_xlang() {
            match self {
                #(#xlang_variant_branches)*
            }
            Ok(())
        } else {
            if context.is_compatible() {
                match self {
                    #(#rust_compatible_variant_branches)*
                }
                Ok(())
            } else {
                match self {
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
                let rs_type_id = std::any::TypeId::of::<Self>();
                context.write_any_typeinfo(fory_core::types::UNKNOWN, rs_type_id)?;
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
        .any(|v| !matches!(v.fields, Fields::Unit));
    let all_variants_compatible = data_enum.variants.iter().all(|v| match &v.fields {
        Fields::Unit => true,
        Fields::Unnamed(f) => f.unnamed.len() == 1,
        Fields::Named(f) => f.named.len() == 1,
    });

    has_data_variant && all_variants_compatible
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
    default_variant_value: u32,
) -> Vec<TokenStream> {
    let is_union_compatible = is_union_compatible_enum(data_enum);

    data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            let mut tag_value = if is_union_compatible {
                enum_variant_id(v).unwrap_or(idx as u32)
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
                                Ok(Self::#ident)
                            }
                        }
                    } else {
                        quote! {
                            #tag_value => Ok(Self::#ident),
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    if is_union_compatible && fields_unnamed.unnamed.len() == 1 {
                        // Union-compatible single field: read value with ref_info=Tracking, type_info=true
                        let field_ty = &fields_unnamed.unnamed.first().unwrap().ty;
                        quote! {
                            #tag_value => {
                                use fory_core::serializer::Serializer;
                                let value = <#field_ty as Serializer>::fory_read(context, fory_core::types::RefMode::Tracking, true)?;
                                Ok(Self::#ident(value))
                            }
                        }
                    } else {
                        let default_fields: Vec<TokenStream> = fields_unnamed
                            .unnamed
                            .iter()
                            .map(|f| {
                                let ty = &f.ty;
                                quote! { <#ty as fory_core::ForyDefault>::fory_default() }
                            })
                            .collect();
                        quote! {
                            #tag_value => Ok(Self::#ident( #(#default_fields),* )),
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    if is_union_compatible && fields_named.named.len() == 1 {
                        // Union-compatible single field: read value with ref_info=Tracking, type_info=true
                        let field = fields_named.named.first().unwrap();
                        let field_ident = field.ident.as_ref().unwrap();
                        let field_ty = &field.ty;
                        quote! {
                            #tag_value => {
                                use fory_core::serializer::Serializer;
                                let value = <#field_ty as Serializer>::fory_read(context, fory_core::types::RefMode::Tracking, true)?;
                                Ok(Self::#ident { #field_ident: value })
                            }
                        }
                    } else {
                        let default_fields: Vec<TokenStream> = fields_named
                            .named
                            .iter()
                            .map(|f| {
                                let field_ident = f.ident.as_ref().unwrap();
                                let ty = &f.ty;
                                quote! { #field_ident: <#ty as fory_core::ForyDefault>::fory_default() }
                            })
                            .collect();
                        quote! {
                            #tag_value => Ok(Self::#ident { #(#default_fields),* }),
                        }
                    }
                }
            }
        })
        .collect()
}

fn rust_variant_read_branches(
    data_enum: &DataEnum,
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
                        #tag_value => Ok(Self::#ident),
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    let field_idents: Vec<Ident> = (0..fields_unnamed.unnamed.len())
                        .map(|i| Ident::new(&temp_var_name(i), proc_macro2::Span::call_site()))
                        .collect();

                    let read_fields: Vec<TokenStream> = fields_unnamed
                        .unnamed
                        .iter()
                        .enumerate()
                        .zip(field_idents.iter())
                        .map(|((idx, f), ident)| {
                            let field_name = idx.to_string();
                            gen_read_field(f, ident, &field_name)
                        })
                        .collect();

                    quote! {
                        #tag_value => {
                            #(#read_fields;)*
                            Ok(Self::#ident( #(#field_idents),* ))
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    let fields_clone = syn::Fields::Named(fields_named.clone());
                    let source_fields = source_fields(&fields_clone);

                    let field_idents: Vec<_> = source_fields
                        .iter()
                        .map(|sf| sf.field.ident.as_ref().unwrap())
                        .collect();

                    let read_fields: Vec<_> = source_fields
                        .iter()
                        .zip(field_idents.iter())
                        .map(|(sf, ident)| {
                            let field_name = ident.to_string();
                            gen_read_field(sf.field, ident, &field_name)
                        })
                        .collect();

                    quote! {
                        #tag_value => {
                            #(#read_fields;)*
                            Ok(Self::#ident { #(#field_idents),* })
                        }
                    }
                }
            }
        })
        .collect()
}

fn rust_compatible_variant_read_branches(
    data_enum: &DataEnum,
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
                    // Generate default value for this variant
                    let default_value = quote! { Self::#ident };

                    quote! {
                        #tag_value => {
                            // Unit variant should have variant_type == 0b0
                            if variant_type != 0b0 {
                                // Variant type mismatch: skip the data and use default
                                use fory_core::serializer::skip::skip_enum_variant;
                                skip_enum_variant(context, variant_type, &None)?;
                                return Ok(#default_value);
                            }
                            Ok(Self::#ident)
                        }
                    }
                }
                Fields::Unnamed(fields_unnamed) => {
                    // For unnamed enum variants, read using collection format (same protocol as tuple)
                    let field_idents: Vec<_> = (0..fields_unnamed.unnamed.len())
                        .map(|i| Ident::new(&temp_var_name(i), proc_macro2::Span::call_site()))
                        .collect();

                    let field_count = fields_unnamed.unnamed.len();

                    let read_fields: Vec<TokenStream> = fields_unnamed
                        .unnamed
                        .iter()
                        .enumerate()
                        .map(|(i, field)| {
                            let field_ident = &field_idents[i];
                            let field_ty = &field.ty;
                            quote! {
                                let #field_ident = if #i < len {
                                    use fory_core::serializer::Serializer;
                                    <#field_ty>::fory_read(context, fory_core::RefMode::NullOnly, true)?
                                } else {
                                    <#field_ty as fory_core::ForyDefault>::fory_default()
                                }
                            }
                        })
                        .collect();

                    // Generate default value for this variant
                    let default_fields: Vec<TokenStream> = fields_unnamed
                        .unnamed
                        .iter()
                        .map(|f| {
                            let ty = &f.ty;
                            quote! { <#ty as fory_core::ForyDefault>::fory_default() }
                        })
                        .collect();
                    let default_value = quote! { Self::#ident( #(#default_fields),* ) };

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
                            let len = context.reader.read_varuint32()? as usize;
                            let _header = context.reader.read_u8()?;

                            #(#read_fields;)*

                            // Skip any extra elements
                            use fory_core::serializer::skip::skip_any_value;
                            for _ in #field_count..len {
                                skip_any_value(context, true)?;
                            }

                            Ok(Self::#ident( #(#field_idents),* ))
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    use crate::util::source_fields;

                    // Sort fields to match the meta type generation
                    let fields_clone = syn::Fields::Named(fields_named.clone());
                    let source_fields = source_fields(&fields_clone);

                    // Generate compatible read logic using gen_read_compatible_with_construction
                    let compatible_read_body =
                        crate::object::read::gen_read_compatible_with_construction(
                            &source_fields,
                            Some(ident),
                        );

                    // Generate default value for this variant
                    let default_fields: Vec<TokenStream> = fields_named
                        .named
                        .iter()
                        .map(|f| {
                            let field_ident = f.ident.as_ref().unwrap();
                            let ty = &f.ty;
                            quote! { #field_ident: <#ty as fory_core::ForyDefault>::fory_default() }
                        })
                        .collect();
                    let default_value = quote! { Self::#ident { #(#default_fields),* } };

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

pub fn gen_read_data(data_enum: &DataEnum) -> TokenStream {
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
        xlang_variant_read_branches(data_enum, default_variant_value);
    let rust_variant_branches: Vec<TokenStream> =
        rust_variant_read_branches(data_enum, default_variant_value);
    let rust_compatible_variant_branches: Vec<TokenStream> =
        rust_compatible_variant_read_branches(data_enum, default_variant_value);

    // Get the default variant for compatible mode fallback
    let default_variant = data_enum
        .variants
        .iter()
        .nth(default_variant_value as usize)
        .or_else(|| data_enum.variants.first())
        .unwrap();

    let default_variant_ident = &default_variant.ident;
    let default_variant_construction = match &default_variant.fields {
        Fields::Unit => {
            quote! { Self::#default_variant_ident }
        }
        Fields::Unnamed(fields_unnamed) => {
            let default_fields: Vec<TokenStream> = fields_unnamed
                .unnamed
                .iter()
                .map(|f| {
                    let ty = &f.ty;
                    quote! { <#ty as fory_core::ForyDefault>::fory_default() }
                })
                .collect();
            quote! { Self::#default_variant_ident( #(#default_fields),* ) }
        }
        Fields::Named(fields_named) => {
            let default_fields: Vec<TokenStream> = fields_named
                .named
                .iter()
                .map(|f| {
                    let field_ident = f.ident.as_ref().unwrap();
                    let ty = &f.ty;
                    quote! { #field_ident: <#ty as fory_core::ForyDefault>::fory_default() }
                })
                .collect();
            quote! { Self::#default_variant_ident { #(#default_fields),* } }
        }
    };

    let unknown_xlang_branch = if is_union_compatible && has_data_variants {
        quote! {
            _ => {
                use fory_core::serializer::skip::skip_any_value;
                skip_any_value(context, true)?;
                if context.is_compatible() {
                    Ok(#default_variant_construction)
                } else {
                    return Err(fory_core::error::Error::unknown_enum("unknown enum value"));
                }
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

    quote! {
        if context.is_xlang() {
            let ordinal = context.reader.read_varuint32()?;
            match ordinal {
                #(#xlang_variant_branches)*
                #unknown_xlang_branch
            }
        } else {
            if context.is_compatible() {
                let encoded_tag = context.reader.read_varuint32()?;
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
                let tag = context.reader.read_varuint32()?;
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
                let expected_type_id = Self::fory_get_type_id(context.get_type_resolver())?;
                let type_info = context.read_any_typeinfo()?;
                let remote_type_id = type_info.get_type_id();
                if remote_type_id != expected_type_id {
                    return Err(fory_core::error::Error::type_mismatch(expected_type_id, remote_type_id));
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
