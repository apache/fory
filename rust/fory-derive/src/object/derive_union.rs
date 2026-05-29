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

use super::util::{enum_variant_id, has_fory_unknown_attr, is_runtime_unknown_variant};
use syn::{Attribute, Data, DataEnum, DeriveInput, Fields};

pub(crate) fn validate_input(input: &DeriveInput) -> syn::Result<()> {
    let Data::Enum(data_enum) = &input.data else {
        return Err(syn::Error::new(
            input.ident.span(),
            "ForyUnion can only be derived for enums",
        ));
    };
    if data_enum
        .variants
        .iter()
        .all(|variant| matches!(variant.fields, Fields::Unit))
    {
        return Err(syn::Error::new(
            input.ident.span(),
            "ForyUnion requires at least one payload variant; use ForyEnum for pure unit enums",
        ));
    }
    if let Some(variant) = data_enum.variants.iter().find(|variant| {
        has_fory_unknown_attr(variant)
            && (!is_runtime_unknown_variant(variant) || enum_variant_id(variant).is_some())
    }) {
        return Err(syn::Error::new(
            variant.ident.span(),
            "ForyUnion unknown case must be #[fory(unknown)] Unknown(UnknownCase) without an id",
        ));
    }
    if is_typed_adt_union(data_enum) && !data_enum.variants.iter().any(is_runtime_unknown_variant) {
        return Err(syn::Error::new(
            input.ident.span(),
            "ForyUnion typed ADT unions require #[fory(unknown)] Unknown(UnknownCase)",
        ));
    }
    let non_unknown_count = data_enum
        .variants
        .iter()
        .filter(|variant| !is_runtime_unknown_variant(variant))
        .count();
    if non_unknown_count == 0 {
        return Err(syn::Error::new(
            input.ident.span(),
            "ForyUnion requires at least one non-Unknown case; Unknown is a forward-compatibility carrier and cannot be the default",
        ));
    }
    let default_count = data_enum
        .variants
        .iter()
        .filter(|variant| is_fory_default_variant(&variant.attrs))
        .count();
    if default_count != 1 {
        return Err(syn::Error::new(
            input.ident.span(),
            "ForyUnion requires exactly one #[fory(default)] variant for ForyDefault and Default semantics",
        ));
    }
    if data_enum.variants.iter().any(|variant| {
        is_runtime_unknown_variant(variant) && is_fory_default_variant(&variant.attrs)
    }) {
        return Err(syn::Error::new(
            input.ident.span(),
            "ForyUnion Unknown case cannot be marked #[fory(default)]",
        ));
    }
    validate_known_case_ids(data_enum)?;
    Ok(())
}

fn validate_known_case_ids(data_enum: &DataEnum) -> syn::Result<()> {
    let mut seen: Vec<(u32, &syn::Ident)> = Vec::new();
    let mut implicit_id = 0u32;
    for variant in &data_enum.variants {
        if is_runtime_unknown_variant(variant) {
            continue;
        }
        let case_id = enum_variant_id(variant).unwrap_or(implicit_id);
        implicit_id += 1;
        if let Some((_, first)) = seen.iter().find(|(id, _)| *id == case_id) {
            return Err(syn::Error::new(
                variant.ident.span(),
                format!(
                    "duplicate ForyUnion case id {case_id}; {} conflicts with {}",
                    variant.ident, first
                ),
            ));
        }
        seen.push((case_id, &variant.ident));
    }
    Ok(())
}

fn is_typed_adt_union(data_enum: &DataEnum) -> bool {
    let mut has_payload_case = false;
    for variant in &data_enum.variants {
        if is_runtime_unknown_variant(variant) {
            continue;
        }
        match &variant.fields {
            Fields::Unit => {}
            Fields::Unnamed(fields) if fields.unnamed.len() == 1 => has_payload_case = true,
            Fields::Named(fields) if fields.named.len() == 1 => has_payload_case = true,
            _ => return false,
        }
    }
    has_payload_case
}

fn is_fory_default_variant(attrs: &[Attribute]) -> bool {
    attrs.iter().any(|attr| {
        attr.path().is_ident("fory") && {
            let mut is_default = false;
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("default") {
                    is_default = true;
                } else if !meta.input.is_empty() {
                    let value = meta.value()?;
                    let _ = value.parse::<syn::Expr>()?;
                }
                Ok(())
            });
            is_default
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use syn::parse_quote;

    #[test]
    fn detects_runtime_unknown_variant() {
        let variant: syn::Variant = parse_quote!(
            #[fory(unknown)]
            Unknown(::fory::UnknownCase)
        );

        assert!(is_runtime_unknown_variant(&variant));
    }

    #[test]
    fn user_unknown_variant_remains_real_case() {
        let wrong_id: syn::Variant = parse_quote!(
            #[fory(id = 7)]
            Unknown(::fory::UnknownCase)
        );
        let wrong_payload: syn::Variant = parse_quote!(
            #[fory(unknown)]
            Unknown(String)
        );
        let implicit_id: syn::Variant = parse_quote!(Unknown(::fory::UnknownCase));

        assert!(!is_runtime_unknown_variant(&wrong_id));
        assert!(!is_runtime_unknown_variant(&wrong_payload));
        assert!(!is_runtime_unknown_variant(&implicit_id));
    }

    #[test]
    fn known_schema_case_zero_is_not_unknown() {
        let known_unknown_name: syn::Variant = parse_quote!(
            #[fory(id = 0)]
            Unknown(String)
        );
        let known_text: syn::Variant = parse_quote!(
            #[fory(id = 0)]
            Text(String)
        );
        let runtime_unknown: syn::Variant = parse_quote!(
            #[fory(unknown)]
            Unknown(::fory::UnknownCase)
        );
        let unknown_without_marker: syn::Variant = parse_quote!(
            #[fory(id = 0)]
            Unknown(UnknownCase)
        );

        assert!(!is_runtime_unknown_variant(&known_unknown_name));
        assert!(!is_runtime_unknown_variant(&known_text));
        assert!(!is_runtime_unknown_variant(&unknown_without_marker));
        assert!(is_runtime_unknown_variant(&runtime_unknown));
    }

    #[test]
    fn rejects_unknown_marker_with_id() {
        let input: DeriveInput = parse_quote!(
            enum BadUnion {
                #[fory(unknown, id = 0)]
                Unknown(::fory::UnknownCase),
                #[fory(id = 1, default)]
                Dog(String),
            }
        );

        let error = validate_input(&input).unwrap_err();
        assert!(error
            .to_string()
            .contains("unknown case must be #[fory(unknown)]"));
    }

    #[test]
    fn typed_adt_requires_unknown_carrier() {
        let input: DeriveInput = parse_quote!(
            enum BadUnion {
                #[fory(default)]
                Dog(String),
            }
        );

        let error = validate_input(&input).unwrap_err();
        assert!(error
            .to_string()
            .contains("ForyUnion typed ADT unions require"));
    }

    #[test]
    fn mixed_unit_payload_union_requires_unknown_carrier() {
        let input: DeriveInput = parse_quote!(
            enum BadUnion {
                #[fory(default)]
                Empty,
                Dog(String),
            }
        );

        let error = validate_input(&input).unwrap_err();
        assert!(error
            .to_string()
            .contains("ForyUnion typed ADT unions require"));
    }

    #[test]
    fn rejects_duplicate_known_ids() {
        let input: DeriveInput = parse_quote!(
            enum BadUnion {
                #[fory(unknown)]
                Unknown(::fory::UnknownCase),
                #[fory(id = 0, default)]
                Dog(String),
                #[fory(id = 0)]
                Cat(String),
            }
        );

        let error = validate_input(&input).unwrap_err();
        assert!(error.to_string().contains("duplicate ForyUnion case id 0"));
    }

    #[test]
    fn rejects_implicit_id_collision() {
        let input: DeriveInput = parse_quote!(
            enum BadUnion {
                #[fory(unknown)]
                Unknown(::fory::UnknownCase),
                #[fory(default)]
                Dog(String),
                #[fory(id = 0)]
                Cat(String),
            }
        );

        let error = validate_input(&input).unwrap_err();
        assert!(error.to_string().contains("duplicate ForyUnion case id 0"));
    }
}
