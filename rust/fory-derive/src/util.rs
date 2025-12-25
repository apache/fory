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

use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::{Field, Fields, GenericArgument, PathArguments, Type, TypePath, TypeTraitObject};

/// Source field with its original index and computed field name preserved.
///
/// For tuple structs, `original_index` is the field's position in the original
/// struct definition (0, 1, 2, ...), and `field_name` is the index as a string.
/// For named structs, `field_name` is the field identifier.
#[derive(Clone)]
pub struct SourceField<'a> {
    pub original_index: usize,
    pub field: &'a Field,
    pub field_name: String,
    pub is_tuple_struct: bool,
}

impl<'a> SourceField<'a> {
    /// Generate field initialization syntax for struct construction.
    /// - tuple struct: just the value
    /// - named struct: `field_name: value`
    pub fn field_init(&self, value: TokenStream) -> TokenStream {
        if self.is_tuple_struct {
            value
        } else {
            let ident = format_ident!("{}", self.field_name);
            quote! { #ident: #value }
        }
    }
}

/// Generate Self construction syntax.
/// - tuple struct: `Self(field0, field1, ...)`
/// - named struct: `Self { field0, field1, ... }`
pub fn self_construction(is_tuple_struct: bool, field_inits: &[TokenStream]) -> TokenStream {
    if is_tuple_struct {
        quote! { Self( #(#field_inits),* ) }
    } else {
        quote! { Self { #(#field_inits),* } }
    }
}

/// Generate Ok(Self(...)) construction syntax for Result return.
pub fn ok_self_construction(is_tuple_struct: bool, field_inits: &[TokenStream]) -> TokenStream {
    if is_tuple_struct {
        quote! { Ok(Self( #(#field_inits),* )) }
    } else {
        quote! { Ok(Self { #(#field_inits),* }) }
    }
}

/// Returns source fields with their original indices preserved.
///
/// For named structs, fields are sorted by type for optimal serialization.
/// For tuple structs, fields keep their original order.
/// The original index is preserved so that:
/// - For named structs: field names can be used directly
/// - For tuple structs: the original index (0, 1, 2, ...) is used as field name
pub fn source_fields(fields: &Fields) -> Vec<SourceField<'_>> {
    let fields: Vec<&Field> = fields.iter().collect();
    get_source_fields(&fields)
}

/// Returns source fields with their original indices and field names preserved.
pub fn get_source_fields<'a>(fields: &[&'a Field]) -> Vec<SourceField<'a>> {
    use crate::object::util::get_sorted_field_names;

    let is_tuple = !fields.is_empty() && fields[0].ident.is_none();
    let sorted_names = get_sorted_field_names(fields);
    let mut result = Vec::with_capacity(fields.len());

    for name in &sorted_names {
        if is_tuple {
            // For tuple structs, field name is the original index as string
            if let Ok(idx) = name.parse::<usize>() {
                if idx < fields.len() {
                    result.push(SourceField {
                        original_index: idx,
                        field: fields[idx],
                        field_name: name.clone(),
                        is_tuple_struct: true,
                    });
                }
            }
        } else {
            // For named structs, match by field identifier
            for (idx, field) in fields.iter().enumerate() {
                if field
                    .ident
                    .as_ref()
                    .map(|ident| ident == name)
                    .unwrap_or(false)
                {
                    result.push(SourceField {
                        original_index: idx,
                        field,
                        field_name: name.clone(),
                        is_tuple_struct: false,
                    });
                    break;
                }
            }
        }
    }

    result
}

/// Extract just the fields from source fields.
pub fn extract_fields<'a>(source_fields: &[SourceField<'a>]) -> Vec<&'a Field> {
    source_fields.iter().map(|sf| sf.field).collect()
}

/// Check if a type is `Box<dyn Trait>` and return the trait type and trait name if it is
pub fn is_box_dyn_trait(ty: &Type) -> Option<(&TypeTraitObject, String)> {
    if let Type::Path(TypePath { path, .. }) = ty {
        if let Some(seg) = path.segments.last() {
            if seg.ident == "Box" {
                if let PathArguments::AngleBracketed(args) = &seg.arguments {
                    if let Some(GenericArgument::Type(Type::TraitObject(trait_obj))) =
                        args.args.first()
                    {
                        // Extract trait name from the trait object
                        if let Some(syn::TypeParamBound::Trait(trait_bound)) =
                            trait_obj.bounds.first()
                        {
                            if let Some(segment) = trait_bound.path.segments.last() {
                                let trait_name = segment.ident.to_string();
                                return Some((trait_obj, trait_name));
                            }
                        }
                    }
                }
            }
        }
    }
    None
}

/// Check if a type is `Rc<dyn Trait>` and return the trait type and trait name if it is
pub fn is_rc_dyn_trait(ty: &Type) -> Option<(&TypeTraitObject, String)> {
    if let Type::Path(TypePath { path, .. }) = ty {
        if let Some(seg) = path.segments.last() {
            if seg.ident == "Rc" {
                if let PathArguments::AngleBracketed(args) = &seg.arguments {
                    if let Some(GenericArgument::Type(Type::TraitObject(trait_obj))) =
                        args.args.first()
                    {
                        // Extract trait name from the trait object
                        if let Some(syn::TypeParamBound::Trait(trait_bound)) =
                            trait_obj.bounds.first()
                        {
                            if let Some(segment) = trait_bound.path.segments.last() {
                                let trait_name = segment.ident.to_string();
                                if trait_name == "Any" {
                                    return None;
                                }
                                return Some((trait_obj, trait_name));
                            }
                        }
                    }
                }
            }
        }
    }
    None
}

/// Check if a type is `Arc<dyn Trait>` and return the trait type and trait name if it is
pub fn is_arc_dyn_trait(ty: &Type) -> Option<(&TypeTraitObject, String)> {
    if let Type::Path(TypePath { path, .. }) = ty {
        if let Some(seg) = path.segments.last() {
            if seg.ident == "Arc" {
                if let PathArguments::AngleBracketed(args) = &seg.arguments {
                    if let Some(GenericArgument::Type(Type::TraitObject(trait_obj))) =
                        args.args.first()
                    {
                        // Extract trait name from the trait object
                        if let Some(syn::TypeParamBound::Trait(trait_bound)) =
                            trait_obj.bounds.first()
                        {
                            if let Some(segment) = trait_bound.path.segments.last() {
                                let trait_name = segment.ident.to_string();
                                if trait_name == "Any" {
                                    return None;
                                }
                                return Some((trait_obj, trait_name));
                            }
                        }
                    }
                }
            }
        }
    }
    None
}

#[derive(Clone)]
pub enum CollectionTraitInfo {
    VecBox(String),
    VecRc(String),
    VecArc(String),
    HashMapBox(Box<Type>, String),
    HashMapRc(Box<Type>, String),
    HashMapArc(Box<Type>, String),
    // todo HashSet
}

/// Check if a type is a collection containing `Box<dyn Trait>`, `Rc<dyn Trait>` or `Arc<dyn Trait>`
pub fn detect_collection_with_trait_object(ty: &Type) -> Option<CollectionTraitInfo> {
    if let Type::Path(TypePath { path, .. }) = ty {
        if let Some(seg) = path.segments.last() {
            match seg.ident.to_string().as_str() {
                "Vec" => {
                    if let PathArguments::AngleBracketed(args) = &seg.arguments {
                        if let Some(GenericArgument::Type(inner_ty)) = args.args.first() {
                            if let Some((_, trait_name)) = is_box_dyn_trait(inner_ty) {
                                return Some(CollectionTraitInfo::VecBox(trait_name));
                            }
                            if let Some((_, trait_name)) = is_rc_dyn_trait(inner_ty) {
                                return Some(CollectionTraitInfo::VecRc(trait_name));
                            }
                            if let Some((_, trait_name)) = is_arc_dyn_trait(inner_ty) {
                                return Some(CollectionTraitInfo::VecArc(trait_name));
                            }
                        }
                    }
                }
                "HashMap" => {
                    if let PathArguments::AngleBracketed(args) = &seg.arguments {
                        let args_vec: Vec<_> = args.args.iter().collect();
                        if args_vec.len() == 2 {
                            if let (
                                GenericArgument::Type(key_ty),
                                GenericArgument::Type(value_ty),
                            ) = (args_vec[0], args_vec[1])
                            {
                                if let Some((_, trait_name)) = is_box_dyn_trait(value_ty) {
                                    return Some(CollectionTraitInfo::HashMapBox(
                                        Box::new(key_ty.clone()),
                                        trait_name,
                                    ));
                                }
                                if let Some((_, trait_name)) = is_rc_dyn_trait(value_ty) {
                                    return Some(CollectionTraitInfo::HashMapRc(
                                        Box::new(key_ty.clone()),
                                        trait_name,
                                    ));
                                }
                                if let Some((_, trait_name)) = is_arc_dyn_trait(value_ty) {
                                    return Some(CollectionTraitInfo::HashMapArc(
                                        Box::new(key_ty.clone()),
                                        trait_name,
                                    ));
                                }
                            }
                        }
                    }
                }
                _ => {}
            }
        }
    }
    None
}
