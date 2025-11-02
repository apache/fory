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

use super::util::{is_default_value_variant, is_skip_enum_variant};
use crate::object::read::gen_read_field;

use crate::object::write::gen_write_field;
use proc_macro2::{Ident, TokenStream};
use quote::quote;
use syn::{DataEnum, Fields};

fn temp_var_name(i: usize) -> String {
    format!("f{}", i)
}

pub fn gen_actual_type_id() -> TokenStream {
    quote! {
       fory_core::serializer::enum_::actual_type_id(type_id, register_by_name, compatible)
    }
}

pub fn gen_field_fields_info(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        Ok(Vec::new())
    }
}

pub fn gen_reserved_space() -> TokenStream {
    quote! {
       4
    }
}

pub fn gen_write(_data_enum: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::write::<Self>(self, context, write_ref_info, write_type_info)
    }
}

pub fn gen_write_data(data_enum: &DataEnum) -> TokenStream {
    let default_variant_value = data_enum
        .variants
        .iter()
        .position(is_default_value_variant)
        .unwrap_or(0) as u32;
    let xlang_variant_branches: Vec<TokenStream> = data_enum
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
                Fields::Unnamed(_) => {
                    quote! {
                        Self::#ident(..) => {
                            context.writer.write_varuint32(#tag_value);
                        }
                    }
                }
                Fields::Named(_) => {
                    quote! {
                        Self::#ident { .. } => {
                            context.writer.write_varuint32(#tag_value);
                        }
                    }
                }
            }
        })
        .collect();

    let rust_variant_branches: Vec<TokenStream> = data_enum
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
                    let mut sorted_fields: Vec<_> = fields_named.named.iter().collect();
                    sorted_fields.sort_by(|a, b| {
                        a.ident
                            .as_ref()
                            .unwrap()
                            .to_string()
                            .cmp(&b.ident.as_ref().unwrap().to_string())
                    });

                    let field_idents: Vec<_> = sorted_fields
                        .iter()
                        .map(|f| f.ident.as_ref().unwrap())
                        .collect();

                    let write_fields: Vec<_> = sorted_fields
                        .iter()
                        .zip(field_idents.iter())
                        .map(|(f, ident)| gen_write_field(f, ident, false))
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
        .collect();

    quote! {
        if context.is_xlang() {
            match self {
                #(#xlang_variant_branches)*
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

pub fn gen_write_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::enum_::write_type_info::<Self>(context)
    }
}

pub fn gen_read(_: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::read::<Self>(context, read_ref_info, read_type_info)
    }
}

pub fn gen_read_with_type_info(_: &DataEnum) -> TokenStream {
    quote! {
        fory_core::serializer::enum_::read::<Self>(context, read_ref_info, false)
    }
}

pub fn gen_read_data(data_enum: &DataEnum) -> TokenStream {
    let default_variant_value = data_enum
        .variants
        .iter()
        .position(is_default_value_variant)
        .unwrap_or(0) as u32;
    let xlang_variant_branches: Vec<TokenStream> = data_enum
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
                    let default_fields: Vec<TokenStream> = fields_unnamed
                        .unnamed
                        .iter()
                        .map(|_| {
                            quote! { Default::default() }
                        })
                        .collect();

                    quote! {
                        #tag_value => Ok(Self::#ident( #(#default_fields),* )),
                    }
                }
                Fields::Named(fields_named) => {
                    let default_fields: Vec<TokenStream> = fields_named
                        .named
                        .iter()
                        .map(|f| {
                            let field_ident = f.ident.as_ref().unwrap();
                            quote! { #field_ident: Default::default() }
                        })
                        .collect();

                    quote! {
                        #tag_value => Ok(Self::#ident { #(#default_fields),* }),
                    }
                }
            }
        })
        .collect();

    let rust_variant_branches: Vec<TokenStream> = data_enum
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
                        .zip(field_idents.iter())
                        .map(|(f, ident)| gen_read_field(f, ident))
                        .collect();

                    quote! {
                        #tag_value => {
                            #(#read_fields;)*
                            Ok(Self::#ident( #(#field_idents),* ))
                        }
                    }
                }
                Fields::Named(fields_named) => {
                    let mut sorted_fields: Vec<_> = fields_named.named.iter().collect();
                    sorted_fields.sort_by(|a, b| {
                        a.ident
                            .as_ref()
                            .unwrap()
                            .to_string()
                            .cmp(&b.ident.as_ref().unwrap().to_string())
                    });

                    let field_idents: Vec<_> = sorted_fields
                        .iter()
                        .map(|f| f.ident.as_ref().unwrap())
                        .collect();

                    let read_fields: Vec<_> = sorted_fields
                        .iter()
                        .zip(field_idents.iter())
                        .map(|(f, ident)| gen_read_field(f, ident))
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
        .collect();

    quote! {
        if context.is_xlang() {
            let ordinal = context.reader.read_varuint32()?;
            match ordinal {
                #(#xlang_variant_branches)*
                _ => return Err(fory_core::error::Error::unknown_enum("unknown enum value")),
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

pub fn gen_read_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::enum_::read_type_info::<Self>(context)
    }
}
