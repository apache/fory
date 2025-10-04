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
    let mut fields = fields.iter().collect::<Vec<&Field>>();
    fields.sort_by(|a, b| a.ident.cmp(&b.ident));
    fields
}

/// Check if a type is `Box<dyn Trait>` and return the trait type if it is
pub fn is_box_dyn_trait(ty: &Type) -> Option<&TypeTraitObject> {
    if let Type::Path(TypePath { path, .. }) = ty {
        if let Some(seg) = path.segments.last() {
            if seg.ident == "Box" {
                if let PathArguments::AngleBracketed(args) = &seg.arguments {
                    if let Some(GenericArgument::Type(Type::TraitObject(trait_obj))) =
                        args.args.first()
                    {
                        return Some(trait_obj);
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
                        if let Some(bound) = trait_obj.bounds.first() {
                            if let syn::TypeParamBound::Trait(trait_bound) = bound {
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
                        if let Some(bound) = trait_obj.bounds.first() {
                            if let syn::TypeParamBound::Trait(trait_bound) = bound {
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
    }
    None
}
