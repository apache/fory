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

use proc_macro2::{Ident, TokenStream};
use quote::{format_ident, quote};
use syn::{Field, Type};

use super::util::{
    classify_trait_object_field, create_wrapper_types_arc, create_wrapper_types_rc,
    extract_type_name, is_primitive_type, should_skip_type_info_for_field, skip_ref_flag,
    StructField,
};

fn create_private_field_name(field: &Field) -> Ident {
    format_ident!("_{}", field.ident.as_ref().unwrap())
}

fn need_declared_by_option(field: &Field) -> bool {
    let type_name = extract_type_name(&field.ty);
    type_name == "Option" || !is_primitive_type(type_name.as_str())
}

fn declare_var(fields: &[&Field]) -> Vec<TokenStream> {
    fields
        .iter()
        .map(|field| {
            let ty = &field.ty;
            let var_name = create_private_field_name(field);
            match classify_trait_object_field(ty) {
                StructField::BoxDyn
                | StructField::RcDyn(_)
                | StructField::ArcDyn(_) => {
                    quote! {
                        let mut #var_name: #ty = <#ty as fory_core::serializer::ForyDefault>::fory_default();
                    }
                }
                _ => {
                    if need_declared_by_option(field) {
                        quote! {
                            let mut #var_name: Option<#ty> = None;
                        }
                    } else if extract_type_name(&field.ty) == "bool" {
                        quote! {
                            let mut #var_name: bool = false;
                        }
                    } else {
                        quote! {
                            let mut #var_name: #ty = 0 as #ty;
                        }
                    }
                }
            }
        })
        .collect()
}

fn assign_value(fields: &[&Field]) -> Vec<TokenStream> {
    fields
        .iter()
        .map(|field| {
            let name = &field.ident;
            let var_name = create_private_field_name(field);
            match classify_trait_object_field(&field.ty) {
                StructField::BoxDyn | StructField::RcDyn(_) | StructField::ArcDyn(_) => {
                    quote! {
                        #name: #var_name
                    }
                }
                StructField::ContainsTraitObject => {
                    quote! {
                        #name: #var_name.unwrap()
                    }
                }
                _ => {
                    if need_declared_by_option(field) {
                        quote! {
                            #name: #var_name.unwrap_or_default()
                        }
                    } else {
                        quote! {
                            #name: #var_name
                        }
                    }
                }
            }
        })
        .collect()
}

fn gen_read_field(field: &Field, private_ident: &Ident) -> TokenStream {
    let ty = &field.ty;
    match classify_trait_object_field(ty) {
        StructField::BoxDyn => {
            quote! {
                let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, true, true)?;
            }
        }
        StructField::RcDyn(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = <#wrapper_ty as fory_core::Serializer>::fory_read(context, true, true)?;
                let #private_ident = std::rc::Rc::<dyn #trait_ident>::from(wrapper);
            }
        }
        StructField::ArcDyn(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = <#wrapper_ty as fory_core::Serializer>::fory_read(context, true, true)?;
                let #private_ident = std::sync::Arc::<dyn #trait_ident>::from(wrapper);
            }
        }
        StructField::VecRc(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::Serializer>::fory_read(context, true, false)?;
                let #private_ident = wrapper_vec.into_iter()
                    .map(|w| std::rc::Rc::<dyn #trait_ident>::from(w))
                    .collect();
            }
        }
        StructField::VecArc(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::Serializer>::fory_read(context, true, false)?;
                let #private_ident = wrapper_vec.into_iter()
                    .map(|w| std::sync::Arc::<dyn #trait_ident>::from(w))
                    .collect();
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_read(context, true, false)?;
                let #private_ident = wrapper_map.into_iter()
                    .map(|(k, v)| (k, std::rc::Rc::<dyn #trait_ident>::from(v)))
                    .collect();
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_read(context, true, false)?;
                let #private_ident = wrapper_map.into_iter()
                    .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                    .collect();
            }
        }
        StructField::Forward => {
            quote! {
                let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, true, true)?;
            }
        }
        _ => {
            let skip_ref_flag = skip_ref_flag(ty);
            let skip_type_info = should_skip_type_info_for_field(ty);
            if skip_type_info {
                // Known types (primitives, strings, collections) - skip type info at compile time
                if skip_ref_flag {
                    quote! {
                        let #private_ident = <#ty as fory_core::Serializer>::fory_read_data(context)?;
                    }
                } else {
                    quote! {
                        let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, true, false)?;
                    }
                }
            } else {
                // Custom types (struct/enum/ext) - need runtime check for enums
                if skip_ref_flag {
                    quote! {
                        let is_enum = <#ty as fory_core::Serializer>::fory_static_type_id() == fory_core::types::TypeId::ENUM;
                        let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, false, !is_enum)?;
                    }
                } else {
                    quote! {
                        let is_enum = <#ty as fory_core::Serializer>::fory_static_type_id() == fory_core::types::TypeId::ENUM;
                        let #private_ident = <#ty as fory_core::Serializer>::fory_read(context, true, !is_enum)?;
                    }
                }
            }
        }
    }
}

pub fn gen_read_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::read_type_info::<Self>(context)
    }
}

fn get_fields_loop_ts(fields: &[&Field]) -> TokenStream {
    let read_fields_ts: Vec<_> = fields
        .iter()
        .map(|field| {
            let private_ident = create_private_field_name(field);
            gen_read_field(field, &private_ident)
        })
        .collect();
    quote! {
        #(#read_fields_ts)*
    }
}

pub fn gen_read_data(fields: &[&Field]) -> TokenStream {
    let sorted_read = if fields.is_empty() {
        quote! {}
    } else {
        let loop_ts = get_fields_loop_ts(fields);
        quote! {
            #loop_ts
        }
    };
    let field_idents = fields.iter().map(|field| {
        let private_ident = create_private_field_name(field);
        let original_ident = &field.ident;
        quote! {
            #original_ident: #private_ident
        }
    });
    quote! {
        #sorted_read
        Ok(Self {
            #(#field_idents),*
        })
    }
}

fn gen_read_compatible_match_arm_body(field: &Field, var_name: &Ident) -> TokenStream {
    let ty = &field.ty;

    match classify_trait_object_field(ty) {
        StructField::BoxDyn => {
            quote! {
                #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, true, true)?);
            }
        }
        StructField::RcDyn(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = <#wrapper_ty as fory_core::Serializer>::fory_read(context, true, true)?;
                #var_name = Some(std::rc::Rc::<dyn #trait_ident>::from(wrapper));
            }
        }
        StructField::ArcDyn(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper = <#wrapper_ty as fory_core::Serializer>::fory_read(context, true, true)?;
                #var_name = Some(std::sync::Arc::<dyn #trait_ident>::from(wrapper));
            }
        }
        StructField::VecRc(trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::Serializer>::fory_read(context, true, false)?;
                #var_name = Some(wrapper_vec.into_iter()
                    .map(|w| std::rc::Rc::<dyn #trait_ident>::from(w))
                    .collect());
            }
        }
        StructField::VecArc(trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_vec = <Vec<#wrapper_ty> as fory_core::Serializer>::fory_read(context, true, false)?;
                #var_name = Some(wrapper_vec.into_iter()
                    .map(|w| std::sync::Arc::<dyn #trait_ident>::from(w))
                    .collect());
            }
        }
        StructField::HashMapRc(key_ty, trait_name) => {
            let types = create_wrapper_types_rc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_read(context, true, false)?;
                #var_name = Some(wrapper_map.into_iter()
                    .map(|(k, v)| (k, std::rc::Rc::<dyn #trait_ident>::from(v)))
                    .collect());
            }
        }
        StructField::HashMapArc(key_ty, trait_name) => {
            let types = create_wrapper_types_arc(&trait_name);
            let wrapper_ty = types.wrapper_ty;
            let trait_ident = types.trait_ident;
            quote! {
                let wrapper_map = <std::collections::HashMap<#key_ty, #wrapper_ty> as fory_core::Serializer>::fory_read(context, true, false)?;
                #var_name = Some(wrapper_map.into_iter()
                    .map(|(k, v)| (k, std::sync::Arc::<dyn #trait_ident>::from(v)))
                    .collect());
            }
        }
        StructField::ContainsTraitObject => {
            quote! {
                #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, true, true)?);
            }
        }
        StructField::Forward => {
            quote! {
                #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, true, true)?);
            }
        }
        StructField::None => {
            let _base_ty = match &ty {
                Type::Path(type_path) => &type_path.path.segments.first().unwrap().ident,
                _ => panic!("Unsupported type"),
            };
            let skip_type_info = should_skip_type_info_for_field(ty);
            if skip_type_info {
                // Known types (primitives, strings, collections) - skip type info at compile time
                // TODO field info should contain whether tracking ref, and we should pass that info to `fory_read`
                let dec_by_option = need_declared_by_option(field);
                if dec_by_option {
                    quote! {
                        if !_field.field_type.nullable {
                            #var_name = Some(<#ty as fory_core::Serializer>::fory_read_data(context)?);
                        } else {
                            #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, true, false)?);
                        }
                    }
                } else {
                    quote! {
                        if !_field.field_type.nullable {
                            #var_name = <#ty as fory_core::Serializer>::fory_read_data(context)?;
                        } else {
                            #var_name = <#ty as fory_core::Serializer>::fory_read(context, true, false)?;
                        }
                    }
                }
            } else {
                // Custom types (struct/enum/ext) - need runtime check for enums
                let dec_by_option = need_declared_by_option(field);
                if dec_by_option {
                    quote! {
                        {
                            let skip_type_info = fory_core::serializer::util::should_skip_type_info_at_runtime(_field.field_type.type_id);
                            if _field.field_type.nullable {
                                #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, true, !skip_type_info)?);
                            } else {
                                #var_name = Some(<#ty as fory_core::Serializer>::fory_read(context, false, !skip_type_info)?);
                            }
                        }
                    }
                } else {
                    quote! {
                        {
                            let skip_type_info = fory_core::serializer::util::should_skip_type_info_at_runtime(_field.field_type.type_id);
                            if !_field.field_type.nullable {
                                #var_name = <#ty as fory_core::Serializer>::fory_read(context, false, !skip_type_info)?;
                            } else {
                                #var_name = <#ty as fory_core::Serializer>::fory_read(context, true, !skip_type_info)?;
                            }
                        }
                    }
                }
            }
        }
    }
}

pub fn gen_read(struct_ident: &Ident) -> TokenStream {
    quote! {
        let ref_flag = if read_ref_info {
            context.reader.read_i8()?
        } else {
            fory_core::RefFlag::NotNullValue as i8
        };
        if ref_flag == (fory_core::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::RefFlag::RefValue as i8) {
            if context.is_compatible() {
                let type_info = if read_type_info {
                    context.read_any_typeinfo()?
                } else {
                    let rs_type_id = std::any::TypeId::of::<Self>();
                    context.get_type_info(&rs_type_id)?
                };
                <#struct_ident as fory_core::StructSerializer>::fory_read_compatible(context, type_info)
            } else {
                if read_type_info {
                    <Self as fory_core::Serializer>::fory_read_type_info(context)?;
                }
                <Self as fory_core::Serializer>::fory_read_data(context)
            }
        } else if ref_flag == (fory_core::RefFlag::Null as i8) {
            Ok(<Self as fory_core::ForyDefault>::fory_default())
        } else {
            Err(fory_core::error::Error::InvalidRef(format!("Unknown ref flag, value:{ref_flag}").into()))
        }
    }
}

pub fn gen_read_with_type_info(struct_ident: &Ident) -> TokenStream {
    // fn fory_read_with_type_info(
    //     context: &mut ReadContext,
    //     read_ref_info: bool,
    //     type_info: Arc<TypeInfo>,
    // ) -> Result<Self, Error>
    quote! {
        let ref_flag = if read_ref_info {
            context.reader.read_i8()?
        } else {
            fory_core::RefFlag::NotNullValue as i8
        };
        if ref_flag == (fory_core::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::RefFlag::RefValue as i8) {
            if context.is_compatible() {
                <#struct_ident as fory_core::StructSerializer>::fory_read_compatible(context, type_info)
            } else {
                <Self as fory_core::Serializer>::fory_read_data(context)
            }
        } else if ref_flag == (fory_core::RefFlag::Null as i8) {
            Ok(<Self as fory_core::ForyDefault>::fory_default())
        } else {
            Err(fory_core::error::Error::InvalidRef(format!("Unknown ref flag, value:{ref_flag}").into()))
        }
    }
}

pub fn gen_read_compatible(fields: &[&Field]) -> TokenStream {
    let declare_ts: Vec<TokenStream> = declare_var(fields);
    let assign_ts: Vec<TokenStream> = assign_value(fields);

    let match_arms: Vec<TokenStream> = fields
        .iter()
        .enumerate()
        .map(|(i, field)| {
            let var_name = create_private_field_name(field);
            let field_id = i as i16;
            let body = gen_read_compatible_match_arm_body(field, &var_name);
            quote! {
                #field_id => {
                    #body
                }
            }
        })
        .collect();

    quote! {
        let fields = type_info.get_type_meta().get_field_infos().clone();
        #(#declare_ts)*
        let meta = context.get_type_info(&std::any::TypeId::of::<Self>())?.get_type_meta();
        let local_type_hash = meta.get_hash();
        if meta.get_hash() == local_type_hash {
            <Self as fory_core::Serializer>::fory_read_data(context)
        } else {
            for _field in fields.iter() {
                match _field.field_id {
                    #(#match_arms)*
                    _ => {
                        let field_type = &_field.field_type;
                        let read_ref_flag = fory_core::serializer::skip::get_read_ref_flag(&field_type);
                        fory_core::serializer::skip::skip_field_value(context, &field_type, read_ref_flag)?;
                    }
                }
            }
            Ok(Self {
                #(#assign_ts),*
            })
        }
    }
}
