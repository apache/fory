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
use syn::Field;

use super::field_codec::{build_bindings, FieldBinding};
use super::util::{gen_struct_version_hash_ts, get_struct_name, is_debug_enabled};
use crate::util::SourceField;

/// Create a private variable name for a field during deserialization.
/// For named fields: `_field_name`
/// For tuple struct fields: `_0`, `_1`, etc.
pub(crate) fn create_private_field_name(field: &Field, index: usize) -> Ident {
    match &field.ident {
        Some(ident) => format_ident!("_{}", ident),
        None => format_ident!("_{}", index),
    }
}

pub(crate) fn declare_var(source_fields: &[SourceField<'_>]) -> Vec<TokenStream> {
    let bindings = match build_bindings(source_fields) {
        Ok(bindings) => bindings,
        Err(err) => return vec![err.to_compile_error()],
    };
    bindings
        .iter()
        .map(|binding| match binding {
            FieldBinding::Codec(binding) => binding.declare_compatible_var(),
            FieldBinding::Skipped(binding) => binding.read_default(),
        })
        .collect()
}

pub(crate) fn assign_value(source_fields: &[SourceField<'_>]) -> Vec<TokenStream> {
    let is_tuple = source_fields
        .first()
        .map(|sf| sf.is_tuple_struct)
        .unwrap_or(false);
    let bindings = match build_bindings(source_fields) {
        Ok(bindings) => bindings,
        Err(err) => return vec![err.to_compile_error()],
    };

    // Generate field value expressions with original index for sorting
    let mut indexed: Vec<_> = source_fields
        .iter()
        .zip(bindings.iter())
        .map(|(sf, binding)| {
            let value_expr = match binding {
                FieldBinding::Codec(binding) => binding.assign_value(),
                FieldBinding::Skipped(binding) => binding.assign_value(),
            };
            (sf.original_index, sf.field_init(value_expr))
        })
        .collect();

    // For tuple structs, sort by original index to construct Self(field0, field1, ...) correctly
    if is_tuple {
        indexed.sort_by_key(|(idx, _)| *idx);
    }

    indexed.into_iter().map(|(_, ts)| ts).collect()
}

pub fn gen_read_type_info() -> TokenStream {
    quote! {
        fory_core::serializer::struct_::read_type_info_fast::<Self>(context)
    }
}

fn get_source_fields_loop_ts(source_fields: &[SourceField<'_>]) -> TokenStream {
    let bindings = match build_bindings(source_fields) {
        Ok(bindings) => bindings,
        Err(err) => return err.to_compile_error(),
    };
    let read_fields_ts: Vec<_> = bindings
        .iter()
        .map(|binding| match binding {
            FieldBinding::Codec(binding) => {
                let base = binding.read_field();
                if is_debug_enabled() {
                    let struct_name = get_struct_name().expect("struct context not set");
                    let struct_name_lit =
                        syn::LitStr::new(&struct_name, proc_macro2::Span::call_site());
                    let field_name_lit = syn::LitStr::new(
                        &binding.source.field_name,
                        proc_macro2::Span::call_site(),
                    );
                    let private_ident = &binding.private_ident;
                    quote! {
                        fory_core::serializer::struct_::struct_before_read_field(
                            #struct_name_lit,
                            #field_name_lit,
                            context,
                        );
                        #base
                        fory_core::serializer::struct_::struct_after_read_field(
                            #struct_name_lit,
                            #field_name_lit,
                            (&#private_ident) as &dyn std::any::Any,
                            context,
                        );
                    }
                } else {
                    base
                }
            }
            FieldBinding::Skipped(binding) => binding.read_default(),
        })
        .collect();
    quote! {
        #(#read_fields_ts)*
    }
}

pub fn gen_read_data(source_fields: &[SourceField<'_>]) -> TokenStream {
    let fields: Vec<&Field> = source_fields.iter().map(|sf| sf.field).collect();
    // Generate runtime version hash computation that detects enum fields
    let version_hash_ts = gen_struct_version_hash_ts(&fields);
    let read_fields = if source_fields.is_empty() {
        quote! {}
    } else {
        let loop_ts = get_source_fields_loop_ts(source_fields);
        quote! {
            #loop_ts
        }
    };

    let is_tuple = source_fields
        .first()
        .map(|sf| sf.is_tuple_struct)
        .unwrap_or(false);

    // Generate field initializations, sorted by original index for tuple structs
    let mut indexed: Vec<_> = source_fields
        .iter()
        .map(|sf| {
            let private_ident = create_private_field_name(sf.field, sf.original_index);
            let value = quote! { #private_ident };
            (sf.original_index, sf.field_init(value))
        })
        .collect();

    if is_tuple {
        indexed.sort_by_key(|(idx, _)| *idx);
    }

    let field_inits: Vec<_> = indexed.into_iter().map(|(_, ts)| ts).collect();
    let self_construction = crate::util::ok_self_construction(is_tuple, &field_inits);

    quote! {
        // Read and check version hash when class version checking is enabled
        if context.is_check_struct_version() {
            let read_version = context.reader.read_i32()?;
            let type_name = std::any::type_name::<Self>();
            let local_version: i32 = #version_hash_ts;
            fory_core::meta::TypeMeta::check_struct_version(read_version, local_version, type_name)?;
        }
        #read_fields
        #self_construction
    }
}

pub fn gen_read(_struct_ident: &Ident) -> TokenStream {
    // Note: We use `Self` instead of `#struct_ident` to correctly handle generic types.
    // When the struct has generics (e.g., LeaderId<C>), using `Self` ensures the full
    // type with generics is used in the impl block.
    quote! {
        let ref_flag = if ref_mode != fory_core::RefMode::None {
            context.reader.read_i8()?
        } else {
            fory_core::RefFlag::NotNullValue as i8
        };
        if ref_flag == (fory_core::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::RefFlag::RefValue as i8) {
            // For RefValueFlag with Tracking mode, reserve a ref_id to participate in ref tracking.
            // This is needed for xlang compatibility where all objects (not just Rc/Arc)
            // participate in reference tracking when ref tracking is enabled.
            // Only reserve for Tracking mode, not NullOnly mode.
            if ref_flag == (fory_core::RefFlag::RefValue as i8) && ref_mode == fory_core::RefMode::Tracking {
                context.ref_reader.reserve_ref_id();
            }
            if context.is_compatible() {
                let type_info = if read_type_info {
                    context.read_any_type_info()?
                } else {
                    let rs_type_id = std::any::TypeId::of::<Self>();
                    context.get_type_info(&rs_type_id)?
                };
                <Self as fory_core::StructSerializer>::fory_read_compatible(context, type_info)
            } else {
                if read_type_info {
                    <Self as fory_core::Serializer>::fory_read_type_info(context)?;
                }
                <Self as fory_core::Serializer>::fory_read_data(context)
            }
        } else if ref_flag == (fory_core::RefFlag::Null as i8) {
            Ok(<Self as fory_core::ForyDefault>::fory_default())
        } else {
            Err(fory_core::error::Error::invalid_ref(format!("Unknown ref flag, value:{ref_flag}")))
        }
    }
}

pub fn gen_read_with_type_info() -> TokenStream {
    // fn fory_read_with_type_info(
    //     context: &mut ReadContext,
    //     ref_mode: RefMode,
    //     type_info: Rc<TypeInfo>,
    // ) -> Result<Self, Error>
    // Note: We use `Self` instead of `#struct_ident` to correctly handle generic types.
    quote! {
        let ref_flag = if ref_mode != fory_core::RefMode::None {
            context.reader.read_i8()?
        } else {
            fory_core::RefFlag::NotNullValue as i8
        };
        if ref_flag == (fory_core::RefFlag::NotNullValue as i8) || ref_flag == (fory_core::RefFlag::RefValue as i8) {
            if context.is_compatible() {
                <Self as fory_core::StructSerializer>::fory_read_compatible(context, type_info)
            } else {
                <Self as fory_core::Serializer>::fory_read_data(context)
            }
        } else if ref_flag == (fory_core::RefFlag::Null as i8) {
            Ok(<Self as fory_core::ForyDefault>::fory_default())
        } else {
            Err(fory_core::error::Error::invalid_ref(format!("Unknown ref flag, value:{ref_flag}")))
        }
    }
}

pub fn gen_read_compatible(source_fields: &[SourceField<'_>]) -> TokenStream {
    gen_read_compatible_with_construction(source_fields, None)
}

pub(crate) fn gen_read_compatible_with_construction(
    source_fields: &[SourceField<'_>],
    variant_ident: Option<&Ident>,
) -> TokenStream {
    let bindings = match build_bindings(source_fields) {
        Ok(bindings) => bindings,
        Err(err) => return err.to_compile_error(),
    };
    let declare_ts: Vec<TokenStream> = declare_var(source_fields);
    let assign_ts: Vec<TokenStream> = assign_value(source_fields);

    let match_arms: Vec<TokenStream> = bindings
        .iter()
        .filter_map(|binding| match binding {
            FieldBinding::Codec(binding) => Some(binding),
            FieldBinding::Skipped(_) => None,
        })
        .enumerate()
        .map(|(sorted_idx, binding)| {
            // Use sorted index for matching. The assign_field_ids function assigns
            // the sorted index to matched fields after lookup (by ID or name).
            // Fields that don't match get field_id = -1; codec incompatibility is
            // handled inside the arm by reading or skipping the remote payload.
            let field_id = sorted_idx as i16;
            let field_index = sorted_idx;
            let body = binding.read_compatible();
            quote! {
                #field_id => {
                    let local_field_type = unsafe {
                        &(*local_fields_ptr.add(#field_index)).field_type
                    };
                    #body
                }
            }
        })
        .collect();

    let skip_arm = if is_debug_enabled() {
        let struct_name = get_struct_name().expect("struct context not set");
        let struct_name_lit = syn::LitStr::new(&struct_name, proc_macro2::Span::call_site());
        quote! {
            _ => {
                let field_type = &_field.field_type;
                let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                    field_type.type_id,
                    field_type.nullable,
                );
                let field_name = _field.field_name.as_str();
                fory_core::serializer::struct_::struct_before_read_field(
                    #struct_name_lit,
                    field_name,
                    context,
                );
                fory_core::serializer::skip::skip_field_value(context, &field_type, read_ref_flag)?;
                let placeholder: &dyn std::any::Any = &();
                fory_core::serializer::struct_::struct_after_read_field(
                    #struct_name_lit,
                    field_name,
                    placeholder,
                    context,
                );
            }
        }
    } else {
        quote! {
            _ => {
                let field_type = &_field.field_type;
                let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                    field_type.type_id,
                    field_type.nullable,
                );
                fory_core::serializer::skip::skip_field_value(context, field_type, read_ref_flag)?;
            }
        }
    };

    // Generate construction based on whether this is a struct or enum variant
    let is_tuple = source_fields
        .first()
        .map(|sf| sf.is_tuple_struct)
        .unwrap_or(false);

    let construction = if let Some(variant) = variant_ident {
        // Enum variants use named syntax (struct variants) or tuple syntax (tuple variants)
        quote! {
            Ok(Self::#variant {
                #(#assign_ts),*
            })
        }
    } else {
        crate::util::ok_self_construction(is_tuple, &assign_ts)
    };

    let variant_field_remap = if let Some(variant) = variant_ident {
        let variant_name = variant.to_string();
        let enum_name = get_struct_name().expect("enum context not set");
        let meta_type_ident = Ident::new(
            &format!("{}_{}VariantMeta", enum_name, variant),
            proc_macro2::Span::call_site(),
        );
        let variant_name_lit = syn::LitStr::new(&variant_name, proc_macro2::Span::call_site());
        quote! {
            let local_variant_type_info = context
                .get_type_resolver()
                .get_type_info(&std::any::TypeId::of::<#meta_type_ident>())
                .map_err(|_| fory_core::Error::type_error(
                    concat!("Local enum variant metadata not found for ", #variant_name_lit)
                ))?;
            let local_variant_type_meta = local_variant_type_info.get_type_meta();
            let local_fields = local_variant_type_meta.get_field_infos();

            let field_index_by_name: std::collections::HashMap<_, _> = local_fields
                .iter()
                .enumerate()
                .filter(|(_, f)| !f.field_name.is_empty())
                .map(|(i, f)| (f.field_name.clone(), (i, f)))
                .collect();

            let field_index_by_id: std::collections::HashMap<_, _> = local_fields
                .iter()
                .enumerate()
                .filter(|(_, f)| f.field_id >= 0)
                .map(|(i, f)| (f.field_id, (i, f)))
                .collect();

            for field in fields.iter_mut() {
                let local_match = if field.field_id >= 0 && field.field_name.is_empty() {
                    field_index_by_id.get(&field.field_id).copied()
                } else {
                    let snake_case_name =
                        fory_core::util::to_snake_case(&field.field_name);
                    field_index_by_name.get(&snake_case_name).copied()
                };

                match local_match {
                    Some((sorted_index, local_info)) => {
                        if field.field_name.is_empty() {
                            field.field_name = local_info.field_name.clone();
                        }
                        field.field_id = sorted_index as i16;
                    }
                    None => {
                        field.field_id = -1;
                    }
                }
            }
        }
    } else {
        quote! {}
    };

    let fields_binding = if variant_ident.is_some() {
        quote! {
            let mut fields = remote_meta.get_field_infos().clone();
            #variant_field_remap
            let local_fields_ptr = local_fields.as_ptr();
        }
    } else {
        quote! {
            let local_fields_ptr = meta.get_field_infos().as_ptr();
            let fields = remote_meta.get_field_infos();
        }
    };

    quote! {
        let meta = context.get_type_resolver().get_type_meta_by_index_ref(
            &std::any::TypeId::of::<Self>(),
            <Self as fory_core::StructSerializer>::fory_type_index(),
        )?;
        let local_type_hash = meta.get_hash();
        let remote_meta = type_info.get_type_meta_ref();
        let remote_type_hash = remote_meta.get_hash();
        if remote_type_hash == local_type_hash {
            return <Self as fory_core::Serializer>::fory_read_data(context);
        }
        #fields_binding
        #(#declare_ts)*
        for _field in fields.iter() {
            match _field.field_id {
                #(#match_arms)*
                #skip_arm
            }
        }
        #construction
    }
}
