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

use crate::object::read::gen_read_field;
use crate::object::util::{
    classify_trait_object_field, create_wrapper_types_arc, create_wrapper_types_rc,
    get_struct_name, get_type_id_by_type_ast, is_debug_enabled, should_skip_type_info_for_field,
    skip_ref_flag, StructField,
};
use fory_core::TypeId;
use proc_macro2::{Ident, TokenStream};
use quote::quote;
use syn::{DataEnum, Field, Fields};

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

// borrowed from object/write.rs
fn gen_write_field(field: &Field, ident: &Ident) -> TokenStream {
    let ty = &field.ty;
    let base = match classify_trait_object_field(ty) {
        StructField::BoxDyn => {
            quote! {
                <#ty as fory_core::Serializer>::fory_write(&#ident, context, true, true, false)?;
            }
        }
        StructField::RcDyn(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = #wrapper_ty::from(#ident.clone() as std::rc::Rc<dyn #trait_ident>);
                <#wrapper_ty as fory_core::Serializer>::fory_write(&wrapper, context, true, true, false)?;
            }
        }
        StructField::ArcDyn(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = #wrapper_ty::from(#ident.clone() as std::sync::Arc<dyn #trait_ident>);
                <#wrapper_ty as fory_core::Serializer>::fory_write(&wrapper, context, true, true, false)?;
            }
        }
        StructField::VecRc(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec: Vec<#wrapper_ty> = #ident.iter()
                    .map(|item| #wrapper_ty::from(item.clone() as std::rc::Rc<dyn #trait_ident>))
                    .collect();
                <Vec<#wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_vec, context, true, false, true)?;
            }
        }
        StructField::VecArc(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec: Vec<#wrapper_ty> = #ident.iter()
                    .map(|item| #wrapper_ty::from(item.clone() as std::sync::Arc<dyn #trait_ident>))
                    .collect();
                <Vec<#wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_vec, context, true, false, true)?;
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = #ident.iter()
                    .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::rc::Rc<dyn #trait_ident>)))
                    .collect();
                <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_map, context, true, false, true)?;
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map: std::collections::HashMap<#key_ty, #wrapper_ty> = #ident.iter()
                    .map(|(k, v)| (k.clone(), #wrapper_ty::from(v.clone() as std::sync::Arc<dyn #trait_ident>)))
                    .collect();
                <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_write(&wrapper_map, context, true, false, true)?;
            }
        }
        StructField::Forward => {
            quote! {
                <#ty as fory_core::Serializer>::fory_write(&#ident, context, true, true, false)?;
            }
        }
        _ => {
            let skip_ref_flag = skip_ref_flag(ty);
            let skip_type_info = should_skip_type_info_for_field(ty);
            let type_id = get_type_id_by_type_ast(ty);
            if type_id == TypeId::LIST as u32
                || type_id == TypeId::SET as u32
                || type_id == TypeId::MAP as u32
            {
                quote! {
                    <#ty as fory_core::Serializer>::fory_write(&#ident, context, true, false, true)?;
                }
            } else {
                // Known types (primitives, strings, collections) - skip type info at compile time
                // For custom types that we can't determine at compile time (like enums),
                // we need to check at runtime whether to skip type info
                if skip_type_info {
                    if skip_ref_flag {
                        quote! {
                            <#ty as fory_core::Serializer>::fory_write_data(&#ident, context)?;
                        }
                    } else {
                        quote! {
                            <#ty as fory_core::Serializer>::fory_write(&#ident, context, true, false, false)?;
                        }
                    }
                } else if skip_ref_flag {
                    quote! {
                        let is_enum = <#ty as fory_core::Serializer>::fory_static_type_id() == fory_core::types::TypeId::ENUM;
                        <#ty as fory_core::Serializer>::fory_write(&#ident, context, false, !is_enum, false)?;
                    }
                } else {
                    quote! {
                        let is_enum = <#ty as fory_core::Serializer>::fory_static_type_id() == fory_core::types::TypeId::ENUM;
                        <#ty as fory_core::Serializer>::fory_write(&#ident, context, true, !is_enum, false)?;
                    }
                }
            }
        }
    };

    if is_debug_enabled() {
        let struct_name = get_struct_name().expect("struct context not set");
        let struct_name_lit = syn::LitStr::new(&struct_name, proc_macro2::Span::call_site());
        let field_name = field.ident.as_ref().unwrap().to_string();
        let field_name_lit = syn::LitStr::new(&field_name, proc_macro2::Span::call_site());
        quote! {
            fory_core::serializer::struct_::struct_before_write_field(
                #struct_name_lit,
                #field_name_lit,
                (&self.#ident) as &dyn std::any::Any,
                context,
            );
            #base
            fory_core::serializer::struct_::struct_after_write_field(
                #struct_name_lit,
                #field_name_lit,
                (&self.#ident) as &dyn std::any::Any,
                context,
            );
        }
    } else {
        base
    }
}

pub fn gen_write_data(data_enum: &DataEnum) -> TokenStream {
    let xlang_variant_branches: Vec<TokenStream> = data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            let tag_value = idx as u32;

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
            let tag_value = idx as u32;

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
                        .map(|(f, ident)| gen_write_field(f, ident))
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
                        .map(|(f, ident)| gen_write_field(f, ident))
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
    let xlang_variant_branches: Vec<TokenStream> = data_enum
        .variants
        .iter()
        .enumerate()
        .map(|(idx, v)| {
            let ident = &v.ident;
            let tag_value = idx as u32;

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
            let tag_value = idx as u32;

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
                    let field_idents: Vec<_> = fields_named
                        .named
                        .iter()
                        .map(|f| f.ident.as_ref().unwrap())
                        .collect();

                    let read_fields: Vec<_> = fields_named
                        .named
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
