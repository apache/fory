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

use syn::{Field, Fields, GenericArgument, PathArguments, Type, TypePath, TypeTraitObject};

pub fn sorted_fields(fields: &Fields) -> Vec<&Field> {
    let fields = fields.iter().collect::<Vec<&Field>>();
    get_sorted_fields(&fields)
}

pub fn get_sorted_fields<'a>(fields: &[&'a Field]) -> Vec<&'a Field> {
    use crate::object::util::get_sorted_field_names;

    let sorted_names = get_sorted_field_names(fields);
    let mut sorted_fields = Vec::with_capacity(fields.len());

    for name in &sorted_names {
        if let Some(field) = fields.iter().find(|f| *f.ident.as_ref().unwrap() == name) {
            sorted_fields.push(*field);
        }
    }

    sorted_fields
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
    VecRc(String),
    VecArc(String),
    HashMapRc(Box<Type>, String),
    HashMapArc(Box<Type>, String),
    // todo HashSet
}

/// Check if a type is a collection containing `Rc<dyn Trait>` or `Arc<dyn Trait>`
pub fn detect_collection_with_trait_object(ty: &Type) -> Option<CollectionTraitInfo> {
    if let Type::Path(TypePath { path, .. }) = ty {
        if let Some(seg) = path.segments.last() {
            match seg.ident.to_string().as_str() {
                "Vec" => {
                    if let PathArguments::AngleBracketed(args) = &seg.arguments {
                        if let Some(GenericArgument::Type(inner_ty)) = args.args.first() {
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
