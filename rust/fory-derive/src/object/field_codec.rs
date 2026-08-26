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

use super::field_meta::{
    classify_field_type, extract_option_inner_type, is_option_type, parse_field_meta,
    ForyFieldMeta, IntEncoding,
};
use super::read::create_private_field_name;
use super::util::{trait_object_is_any_send_sync, trait_object_is_any_without_auto_traits};
use crate::util::{is_arc_dyn_trait, is_box_dyn_trait, is_rc_dyn_trait, SourceField};
use proc_macro2::TokenStream;
use quote::{format_ident, quote, ToTokens};
use syn::{GenericArgument, PathArguments, Type};

struct CodecSelection {
    ty: TokenStream,
}

impl CodecSelection {
    fn plain(ty: TokenStream) -> Self {
        Self { ty }
    }
}

pub(crate) struct ResolvedField<'a> {
    pub source: &'a SourceField<'a>,
    pub private_ident: syn::Ident,
    pub codec_ty: TokenStream,
    pub value_ty: &'a Type,
    pub field_id: i32,
    pub has_selected_provider: bool,
    pub read_always_advances: TokenStream,
}

impl<'a> ResolvedField<'a> {
    #[inline]
    pub fn codec_call(&self) -> TokenStream {
        let codec_ty = &self.codec_ty;
        let value_ty = self.value_ty;
        quote! { <#codec_ty as fory_core::serializer::codec::Codec<#value_ty>> }
    }

    #[inline]
    fn serializer_call(&self) -> TokenStream {
        let codec_ty = &self.codec_ty;
        quote! { <#codec_ty as fory_core::Serializer> }
    }

    pub fn reserved_space(&self) -> TokenStream {
        let call = self.codec_call();
        quote! { #call::field_reserved_space() }
    }

    pub fn write_field(&self) -> TokenStream {
        let access = super::util::get_field_accessor(self.source.field, self.source.original_index);
        self.write_field_value(quote! { &#access })
    }

    pub fn write_field_value(&self, value: TokenStream) -> TokenStream {
        let call = self.codec_call();
        quote! {
            #call::write_field(#value, context)?;
        }
    }

    pub fn write_with_mode(
        &self,
        value: TokenStream,
        ref_mode: TokenStream,
        write_type_info: TokenStream,
        has_generics: TokenStream,
    ) -> TokenStream {
        let call = self.codec_call();
        quote! {
            #call::write_with_mode(
                #value,
                context,
                #ref_mode,
                #write_type_info,
                #has_generics,
            )?;
        }
    }

    pub fn read_field(&self) -> TokenStream {
        let var = &self.private_ident;
        let call = self.codec_call();
        quote! {
            let #var = #call::read_field(context)?;
        }
    }

    pub fn read_with_mode_expr(
        &self,
        ref_mode: TokenStream,
        read_type_info: TokenStream,
    ) -> TokenStream {
        let call = self.serializer_call();
        quote! { #call::read(context, #ref_mode, #read_type_info)? }
    }

    pub fn default_value_expr(&self) -> TokenStream {
        let call = self.serializer_call();
        quote! { #call::default_value(context)? }
    }

    pub fn declare_compatible_var(&self) -> TokenStream {
        let var = &self.private_ident;
        let ty = self.value_ty;
        quote! {
            let mut #var: ::std::option::Option<#ty> = ::std::option::Option::None;
        }
    }

    pub fn assign_value(&self) -> TokenStream {
        let var = &self.private_ident;
        let default = self.default_value_expr();
        quote! {
            match #var {
                ::std::option::Option::Some(value) => value,
                ::std::option::Option::None => #default,
            }
        }
    }

    pub fn read_compatible_direct(&self) -> TokenStream {
        let var = &self.private_ident;
        let call = self.codec_call();
        quote! {
            #var = ::std::option::Option::Some(#call::read_field(context)?);
        }
    }

    pub fn read_compatible_conversion(&self) -> TokenStream {
        let var = &self.private_ident;
        // A selected serializer targeting a Rust scalar can still be an EXT-shaped schema leaf. Keep it on the
        // selected codec path instead of bypassing its body through scalar conversion.
        if !self.has_selected_provider {
            if let Some(read_scalar) = compatible_scalar_reader_for(self.value_ty) {
                let call = self.serializer_call();
                let local_type = if extract_option_inner_type(self.value_ty).is_some() {
                    quote! { local_field_type.type_id }
                } else {
                    quote! { #call::static_type_id() as u32 }
                };
                return quote! {
                    #var = ::std::option::Option::Some(#read_scalar(
                        context,
                        #local_type,
                        _field,
                    ).map_err(|err| fory_core::Error::invalid_data(
                        format!("compatible field '{}': {}", _field.field_name.as_str(), err)
                    ))?);
                };
            }
        }
        let call = self.codec_call();
        quote! {
            let remote_field_type = &_field.field_type;
            if let Some(value) = #call::read_compatible(
                context,
                local_field_type,
                remote_field_type,
            ).map_err(|err| fory_core::Error::invalid_data(
                format!("compatible field '{}': {}", _field.field_name.as_str(), err)
            ))? {
                #var = ::std::option::Option::Some(value);
            } else {
                return Err(fory_core::Error::invalid_data(format!(
                    "compatible field '{}' cannot convert remote type {} to local type {}",
                    _field.field_name.as_str(),
                    remote_field_type.type_id,
                    local_field_type.type_id,
                )));
            }
        }
    }

    pub fn compatible_needs_local_field_type(&self) -> bool {
        self.has_selected_provider
            || compatible_scalar_reader_for(self.value_ty).is_none()
            || extract_option_inner_type(self.value_ty).is_some()
    }

    pub fn field_info(&self) -> TokenStream {
        let field_id = self.field_id;
        let name = &self.source.field_name;
        let call = self.codec_call();
        quote! {{
            fory_core::meta::FieldInfo::new_with_id(
                #field_id,
                #name,
                #call::field_type(type_resolver)?
            )
        }}
    }
}

pub(crate) struct SkippedField {
    pub private_ident: syn::Ident,
    pub codec_ty: TokenStream,
}

impl SkippedField {
    pub fn read_default(&self) -> TokenStream {
        let var = &self.private_ident;
        let codec_ty = &self.codec_ty;
        quote! {
            let #var =
                <#codec_ty as fory_core::Serializer>::default_value(context)?;
        }
    }

    pub fn default_value_expr(&self) -> TokenStream {
        let codec_ty = &self.codec_ty;
        quote! {{
            <#codec_ty as fory_core::Serializer>::default_value(context)?
        }}
    }

    pub fn assign_value(&self) -> TokenStream {
        let var = &self.private_ident;
        quote! { #var }
    }
}

pub(crate) enum FieldBinding<'a> {
    Codec(ResolvedField<'a>),
    Skipped(SkippedField),
}

impl FieldBinding<'_> {
    pub fn default_value_expr(&self) -> TokenStream {
        match self {
            Self::Codec(binding) => binding.default_value_expr(),
            Self::Skipped(binding) => binding.default_value_expr(),
        }
    }
}

pub(crate) fn build_bindings<'a>(
    source_fields: &'a [SourceField<'a>],
) -> syn::Result<Vec<FieldBinding<'a>>> {
    source_fields
        .iter()
        .map(|source| {
            let meta = parse_field_meta(source.field)?;
            let private_ident = create_private_field_name(source.field, source.original_index);
            let type_class = classify_field_type(&source.field.ty);
            let nullable = meta.effective_nullable(type_class) || is_option_type(&source.field.ty);
            let track_ref = meta.effective_ref(type_class);
            let selection = codec_selection_for(&source.field.ty, &meta, nullable, track_ref)?;
            if meta.skip {
                return Ok(FieldBinding::Skipped(SkippedField {
                    private_ident,
                    codec_ty: selection.ty,
                }));
            }
            let field_id = if meta.uses_tag_id() {
                meta.effective_id()
            } else {
                -1
            };
            let field_read_always_advances = if track_ref || is_option_type(&source.field.ty) {
                quote! { true }
            } else if meta.with.is_some() || codec_body_is_known(&source.field.ty) {
                let codec_ty = &selection.ty;
                quote! { <#codec_ty as fory_core::Serializer>::READ_DATA_ALWAYS_ADVANCES }
            } else {
                quote! { false }
            };
            Ok(FieldBinding::Codec(ResolvedField {
                source,
                private_ident,
                codec_ty: selection.ty,
                value_ty: &source.field.ty,
                field_id,
                has_selected_provider: meta.with.is_some(),
                read_always_advances: field_read_always_advances,
            }))
        })
        .collect()
}

pub(crate) fn struct_read_data_always_advances(
    source_fields: &[SourceField<'_>],
) -> syn::Result<TokenStream> {
    let bindings = build_bindings(source_fields)?;
    let fields = bindings.iter().filter_map(|binding| match binding {
        FieldBinding::Codec(field) => Some(&field.read_always_advances),
        FieldBinding::Skipped(_) => None,
    });
    Ok(quote! { false #(|| #fields)* })
}

fn codec_body_is_known(ty: &Type) -> bool {
    if matches!(ty, Type::Array(_) | Type::Tuple(_)) {
        return true;
    }
    let Some((name, _)) = type_name_and_args(ty) else {
        return false;
    };
    matches!(
        name.as_str(),
        "bool"
            | "i8"
            | "i16"
            | "i32"
            | "i64"
            | "i128"
            | "isize"
            | "u8"
            | "u16"
            | "u32"
            | "u64"
            | "u128"
            | "usize"
            | "f32"
            | "f64"
            | "float16"
            | "bfloat16"
            | "String"
            | "Decimal"
            | "Timestamp"
            | "Date"
            | "Duration"
            | "NaiveDateTime"
            | "NaiveDate"
            | "ChronoDuration"
            | "Vec"
            | "LinkedList"
            | "VecDeque"
            | "BinaryHeap"
            | "BTreeSet"
            | "HashSet"
            | "BTreeMap"
            | "HashMap"
    )
}

#[cfg(test)]
pub(crate) fn codec_type_for(
    ty: &Type,
    meta: &ForyFieldMeta,
    nullable: bool,
    track_ref: bool,
) -> syn::Result<TokenStream> {
    Ok(codec_selection_for(ty, meta, nullable, track_ref)?.ty)
}

fn codec_selection_for(
    ty: &Type,
    meta: &ForyFieldMeta,
    nullable: bool,
    track_ref: bool,
) -> syn::Result<CodecSelection> {
    if is_dynamic_trait_carrier(ty) {
        if let Some(provider) = &meta.with {
            return Err(syn::Error::new_spanned(
                provider,
                "with cannot select a serializer for a dynamic Any or application-trait node",
            ));
        }
        validate_no_shape(
            ty,
            meta,
            "dynamic Any and application-trait nodes do not accept structural field config",
        )?;
        return dynamic_codec_for(ty, nullable, track_ref);
    }

    if let Some(provider) = &meta.with {
        return selected_codec_for(ty, provider, nullable, track_ref);
    }

    if let Some(inner) = extract_option_inner_type(ty) {
        let child_meta = transparent_child_meta(meta);
        let child_ty = codec_for_child(&inner, &child_meta)?.ty;
        return Ok(CodecSelection::plain(quote! {
            fory_core::serializer::codec::OptionCodec<
                #inner,
                #child_ty,
                #track_ref
            >
        }));
    }

    if let Some((name, Some(args))) = type_name_and_args(ty) {
        if is_transparent_carrier(&name) {
            let inner = single_type_arg(args, ty, &name)?;
            let child_meta = transparent_child_meta(meta);
            let child_ty = codec_for_child(inner, &child_meta)?.ty;
            let codec_ident = format_ident!("{name}Codec");
            return Ok(CodecSelection::plain(quote! {
                fory_core::serializer::codec::#codec_ident<
                    #inner,
                    #child_ty,
                    #nullable,
                    #track_ref
                >
            }));
        }
    }

    if let Type::Array(array) = ty {
        validate_array_meta(ty, meta)?;
        let elem_ty = array.elem.as_ref();
        let elem_meta = meta.element_meta();
        let child = codec_for_child(elem_ty, &elem_meta)?;
        let child_ty = child.ty;
        let len = &array.len;
        return Ok(CodecSelection {
            ty: quote! {
                fory_core::serializer::codec::ArrayCodec<
                    #elem_ty,
                    #child_ty,
                    #len,
                    #nullable,
                    #track_ref
                >
            },
        });
    }

    if let Type::Tuple(tuple) = ty {
        return tuple_codec_for(tuple, ty, meta, nullable, track_ref);
    }

    if let Some((name, Some(args))) = type_name_and_args(ty) {
        if is_one_child_collection(&name) {
            validate_collection_meta(ty, meta, &name)?;
            let elem_ty = single_type_arg(args, ty, &name)?;
            if name == "Vec" {
                validate_vec_schema(elem_ty, meta)?;
            }
            let elem_meta = meta.element_meta();
            let child = codec_for_child(elem_ty, &elem_meta)?;
            let child_ty = child.ty;
            let codec_ident = format_ident!("{name}Codec");
            let structural_list = !meta.array && !meta.bytes;
            let dense_array = meta.array;
            let vec_schema_args = if name == "Vec" {
                quote! { #structural_list, #dense_array, }
            } else {
                quote! {}
            };
            return Ok(CodecSelection {
                ty: quote! {
                    fory_core::serializer::codec::#codec_ident<
                        #elem_ty,
                        #child_ty,
                        #vec_schema_args
                        #nullable,
                        #track_ref
                    >
                },
            });
        }
        if is_map(&name) {
            validate_map_meta(ty, meta, &name)?;
            let (key_ty, value_ty) = two_type_args(args, ty, &name)?;
            let key = codec_for_child(key_ty, &meta.map_key_meta())?;
            let value = codec_for_child(value_ty, &meta.map_value_meta())?;
            let key_codec = key.ty;
            let value_codec = value.ty;
            let codec_ident = format_ident!("{name}Codec");
            return Ok(CodecSelection {
                ty: quote! {
                    fory_core::serializer::codec::#codec_ident<
                        #key_ty,
                        #value_ty,
                        #key_codec,
                        #value_codec,
                        #nullable,
                        #track_ref
                    >
                },
            });
        }
    }

    validate_leaf_meta(ty, meta)?;
    if let Some(codec) = integer_codec_type(ty, meta, nullable, track_ref) {
        return Ok(CodecSelection::plain(codec));
    }
    Ok(CodecSelection::plain(quote! {
        fory_core::serializer::codec::SerializerCodec<#ty, #nullable, #track_ref>
    }))
}

// Stable derive cannot resolve aliases. Recurse only through syntactically
// visible carrier constructors; every other selected type stays a leaf for
// SerializerCodec's cold schema validation.
fn selected_codec_for(
    target: &Type,
    provider: &Type,
    nullable: bool,
    track_ref: bool,
) -> syn::Result<CodecSelection> {
    if is_dynamic_trait_carrier(target) {
        if is_dynamic_trait_carrier(provider) {
            // Emit the selected self-provider's codec. The generated
            // `Codec<Target>` bound then lets Rust prove that the selected
            // dynamic carrier and trait exactly match the field type.
            return dynamic_codec_for(provider, nullable, track_ref);
        }
        return Err(syn::Error::new_spanned(
            provider,
            "a dynamic Any or application-trait node must use its ordinary self-provider",
        ));
    }

    if let Some((provider_name, provider_args)) = type_name_and_args(provider) {
        if provider_name == "ArraySerializer" {
            let (child_provider, len) = array_serializer_args(provider_args, provider)?;
            let Type::Array(target_array) = target else {
                return Err(carrier_target_error(provider, target, "[T; N]"));
            };
            let child = selected_child_codec(target_array.elem.as_ref(), child_provider)?;
            let child_ty = child.ty;
            let elem_ty = target_array.elem.as_ref();
            return Ok(CodecSelection::plain(quote! {
                fory_core::serializer::codec::ArrayCodec<
                    #elem_ty,
                    #child_ty,
                    #len,
                    #nullable,
                    #track_ref
                >
            }));
        }

        if let Some(arity) = tuple_serializer_arity(&provider_name) {
            if !(1..=22).contains(&arity) {
                return Err(syn::Error::new_spanned(
                    provider,
                    "tuple carrier serializers support arities 1 through 22",
                ));
            }
            let providers = exact_type_args(provider_args, provider, &provider_name, arity)?;
            return selected_tuple_codec(target, provider, &providers, nullable, track_ref);
        }

        if let Some(target_name) = canonical_named_carrier(&provider_name) {
            if is_map(target_name) {
                let providers = exact_type_args(provider_args, provider, &provider_name, 2)?;
                return selected_map_codec(
                    target,
                    provider,
                    target_name,
                    providers[0],
                    providers[1],
                    nullable,
                    track_ref,
                );
            }
            let providers = exact_type_args(provider_args, provider, &provider_name, 1)?;
            return selected_one_child_codec(
                target,
                provider,
                target_name,
                providers[0],
                nullable,
                track_ref,
            );
        }

        if is_named_carrier(&provider_name) {
            if is_map(&provider_name) {
                let providers = exact_type_args(provider_args, provider, &provider_name, 2)?;
                return selected_map_codec(
                    target,
                    provider,
                    &provider_name,
                    providers[0],
                    providers[1],
                    nullable,
                    track_ref,
                );
            }
            let providers = exact_type_args(provider_args, provider, &provider_name, 1)?;
            return selected_one_child_codec(
                target,
                provider,
                &provider_name,
                providers[0],
                nullable,
                track_ref,
            );
        }
    }

    if let Type::Array(provider_array) = provider {
        let Type::Array(target_array) = target else {
            return Err(carrier_target_error(provider, target, "[T; N]"));
        };
        let child = selected_child_codec(target_array.elem.as_ref(), provider_array.elem.as_ref())?;
        let child_ty = child.ty;
        let elem_ty = target_array.elem.as_ref();
        let len = &provider_array.len;
        return Ok(CodecSelection::plain(quote! {
            fory_core::serializer::codec::ArrayCodec<
                #elem_ty,
                #child_ty,
                #len,
                #nullable,
                #track_ref
            >
        }));
    }

    if let Type::Tuple(provider_tuple) = provider {
        if provider_tuple.elems.is_empty() {
            return leaf_selected_codec(provider, nullable, track_ref);
        }
        let providers: Vec<_> = provider_tuple.elems.iter().collect();
        return selected_tuple_codec(target, provider, &providers, nullable, track_ref);
    }

    leaf_selected_codec(provider, nullable, track_ref)
}

fn selected_child_codec(target: &Type, provider: &Type) -> syn::Result<CodecSelection> {
    let class = classify_field_type(target);
    let meta = ForyFieldMeta::default();
    selected_codec_for(
        target,
        provider,
        meta.effective_nullable(class) || is_option_type(target),
        meta.effective_ref(class),
    )
}

fn leaf_selected_codec(
    provider: &Type,
    nullable: bool,
    track_ref: bool,
) -> syn::Result<CodecSelection> {
    Ok(CodecSelection::plain(quote! {
        fory_core::serializer::codec::SerializerCodec<
            #provider,
            #nullable,
            #track_ref
        >
    }))
}

fn selected_one_child_codec(
    target: &Type,
    provider: &Type,
    carrier: &str,
    child_provider: &Type,
    nullable: bool,
    track_ref: bool,
) -> syn::Result<CodecSelection> {
    let (target_name, target_args) = type_name_and_args(target)
        .ok_or_else(|| carrier_target_error(provider, target, &format!("{carrier}<T>")))?;
    if target_name != carrier {
        return Err(carrier_target_error(
            provider,
            target,
            &format!("{carrier}<T>"),
        ));
    }
    let target_children = exact_type_args(target_args, target, carrier, 1)?;
    let child_target = target_children[0];
    let child = selected_child_codec(child_target, child_provider)?;
    let child_ty = child.ty;

    if carrier == "Option" {
        return Ok(CodecSelection::plain(quote! {
            fory_core::serializer::codec::OptionCodec<
                #child_target,
                #child_ty,
                #track_ref
            >
        }));
    }

    let codec_ident = format_ident!("{carrier}Codec");
    if is_transparent_carrier(carrier) {
        return Ok(CodecSelection::plain(quote! {
            fory_core::serializer::codec::#codec_ident<
                #child_target,
                #child_ty,
                #nullable,
                #track_ref
            >
        }));
    }

    let vec_schema_args = if carrier == "Vec" {
        quote! { false, false, }
    } else {
        quote! {}
    };
    Ok(CodecSelection::plain(quote! {
        fory_core::serializer::codec::#codec_ident<
            #child_target,
            #child_ty,
            #vec_schema_args
            #nullable,
            #track_ref
        >
    }))
}

fn selected_map_codec(
    target: &Type,
    provider: &Type,
    carrier: &str,
    key_provider: &Type,
    value_provider: &Type,
    nullable: bool,
    track_ref: bool,
) -> syn::Result<CodecSelection> {
    let (target_name, target_args) = type_name_and_args(target)
        .ok_or_else(|| carrier_target_error(provider, target, &format!("{carrier}<K, V>")))?;
    if target_name != carrier {
        return Err(carrier_target_error(
            provider,
            target,
            &format!("{carrier}<K, V>"),
        ));
    }
    let targets = exact_type_args(target_args, target, carrier, 2)?;
    let key_target = targets[0];
    let value_target = targets[1];
    let key_codec = selected_child_codec(key_target, key_provider)?.ty;
    let value_codec = selected_child_codec(value_target, value_provider)?.ty;
    let codec_ident = format_ident!("{carrier}Codec");
    Ok(CodecSelection::plain(quote! {
        fory_core::serializer::codec::#codec_ident<
            #key_target,
            #value_target,
            #key_codec,
            #value_codec,
            #nullable,
            #track_ref
        >
    }))
}

fn selected_tuple_codec(
    target: &Type,
    provider: &Type,
    providers: &[&Type],
    nullable: bool,
    track_ref: bool,
) -> syn::Result<CodecSelection> {
    if providers.len() > 22 {
        return Err(syn::Error::new_spanned(
            provider,
            "tuple carrier serializers support arities 1 through 22",
        ));
    }
    let Type::Tuple(target_tuple) = target else {
        return Err(carrier_target_error(
            provider,
            target,
            &format!("a {}-element tuple", providers.len()),
        ));
    };
    if target_tuple.elems.len() != providers.len() {
        return Err(syn::Error::new_spanned(
            target,
            format!(
                "selected tuple serializer has arity {}, but the target tuple has arity {}",
                providers.len(),
                target_tuple.elems.len(),
            ),
        ));
    }
    let codec_ident = format_ident!("Tuple{}Codec", providers.len());
    let mut args = Vec::with_capacity(providers.len() * 2);
    for (target, provider) in target_tuple.elems.iter().zip(providers) {
        let codec = selected_child_codec(target, provider)?.ty;
        args.push(quote! { #target });
        args.push(codec);
    }
    Ok(CodecSelection::plain(quote! {
        fory_core::serializer::codec::#codec_ident<
            #(#args,)*
            #nullable,
            #track_ref
        >
    }))
}

fn carrier_target_error(provider: &Type, target: &Type, expected: &str) -> syn::Error {
    syn::Error::new_spanned(
        target,
        format!(
            "carrier serializer {} requires target {expected}",
            provider.to_token_stream()
        ),
    )
}

fn canonical_named_carrier(name: &str) -> Option<&'static str> {
    match name {
        "OptionSerializer" => Some("Option"),
        "BoxSerializer" => Some("Box"),
        "RcSerializer" => Some("Rc"),
        "ArcSerializer" => Some("Arc"),
        "RcWeakSerializer" => Some("RcWeak"),
        "ArcWeakSerializer" => Some("ArcWeak"),
        "RefCellSerializer" => Some("RefCell"),
        "MutexSerializer" => Some("Mutex"),
        "VecSerializer" => Some("Vec"),
        "VecDequeSerializer" => Some("VecDeque"),
        "LinkedListSerializer" => Some("LinkedList"),
        "HashSetSerializer" => Some("HashSet"),
        "BTreeSetSerializer" => Some("BTreeSet"),
        "BinaryHeapSerializer" => Some("BinaryHeap"),
        "HashMapSerializer" => Some("HashMap"),
        "BTreeMapSerializer" => Some("BTreeMap"),
        _ => None,
    }
}

fn is_named_carrier(name: &str) -> bool {
    name == "Option"
        || is_transparent_carrier(name)
        || is_one_child_collection(name)
        || is_map(name)
}

fn tuple_serializer_arity(name: &str) -> Option<usize> {
    name.strip_prefix("Tuple")?
        .strip_suffix("Serializer")?
        .parse()
        .ok()
}

fn exact_type_args<'a>(
    args: Option<&'a syn::punctuated::Punctuated<GenericArgument, syn::token::Comma>>,
    ty: &Type,
    owner: &str,
    expected: usize,
) -> syn::Result<Vec<&'a Type>> {
    let Some(args) = args else {
        return Err(syn::Error::new_spanned(
            ty,
            format!("{owner} requires exactly {expected} type argument(s)"),
        ));
    };
    if args.len() != expected {
        return Err(syn::Error::new_spanned(
            ty,
            format!("{owner} requires exactly {expected} type argument(s)"),
        ));
    }
    args.iter()
        .map(|arg| match arg {
            GenericArgument::Type(ty) => Ok(ty),
            _ => Err(syn::Error::new_spanned(
                arg,
                format!("{owner} requires exactly {expected} type argument(s)"),
            )),
        })
        .collect()
}

fn array_serializer_args<'a>(
    args: Option<&'a syn::punctuated::Punctuated<GenericArgument, syn::token::Comma>>,
    ty: &Type,
) -> syn::Result<(&'a Type, TokenStream)> {
    let Some(args) = args else {
        return Err(syn::Error::new_spanned(
            ty,
            "ArraySerializer requires one serializer type and one const length",
        ));
    };
    if args.len() != 2 {
        return Err(syn::Error::new_spanned(
            ty,
            "ArraySerializer requires one serializer type and one const length",
        ));
    }
    let mut args = args.iter();
    let child = match args.next().expect("length checked") {
        GenericArgument::Type(child) => child,
        arg => {
            return Err(syn::Error::new_spanned(
                arg,
                "ArraySerializer requires one serializer type and one const length",
            ));
        }
    };
    let len = match args.next().expect("length checked") {
        GenericArgument::Const(len) => quote! { #len },
        // An unbraced const identifier is syntactically ambiguous to syn and is
        // parsed as a type. Rust resolves it as the const generic at compile time.
        GenericArgument::Type(len) => quote! { #len },
        arg => {
            return Err(syn::Error::new_spanned(
                arg,
                "ArraySerializer requires one serializer type and one const length",
            ));
        }
    };
    Ok((child, len))
}

fn codec_for_child(ty: &Type, meta: &ForyFieldMeta) -> syn::Result<CodecSelection> {
    let class = classify_field_type(ty);
    codec_selection_for(
        ty,
        meta,
        meta.effective_nullable(class) || is_option_type(ty),
        meta.effective_ref(class),
    )
}

fn transparent_child_meta(meta: &ForyFieldMeta) -> ForyFieldMeta {
    let mut child = meta.clone();
    child.id = -1;
    child.nullable = None;
    child.r#ref = None;
    child.skip = false;
    child
}

fn is_dynamic_trait_carrier(ty: &Type) -> bool {
    any_trait_object_for(ty, "Box").is_some()
        || any_trait_object_for(ty, "Rc").is_some()
        || any_trait_object_for(ty, "Arc").is_some()
        || is_box_dyn_trait(ty).is_some()
        || is_rc_dyn_trait(ty).is_some()
        || is_arc_dyn_trait(ty).is_some()
}

fn dynamic_codec_for(ty: &Type, nullable: bool, track_ref: bool) -> syn::Result<CodecSelection> {
    if let Some(trait_obj) = any_trait_object_for(ty, "Box") {
        if trait_object_is_any_without_auto_traits(trait_obj) {
            return Ok(CodecSelection::plain(quote! {
                fory_core::serializer::codec::AnyBoxCodec<#nullable, #track_ref>
            }));
        }
        return Err(syn::Error::new_spanned(
            ty,
            "Box<dyn Any> is the supported owned Any carrier",
        ));
    }
    if let Some(trait_obj) = any_trait_object_for(ty, "Rc") {
        if trait_object_is_any_without_auto_traits(trait_obj) {
            return Ok(CodecSelection::plain(quote! {
                fory_core::serializer::codec::AnyRcCodec<#nullable, #track_ref>
            }));
        }
        return Err(syn::Error::new_spanned(
            ty,
            "Rc<dyn Any> is the supported single-thread Any carrier",
        ));
    }
    if let Some(trait_obj) = any_trait_object_for(ty, "Arc") {
        if trait_object_is_any_send_sync(trait_obj) {
            return Ok(CodecSelection::plain(quote! {
                fory_core::serializer::codec::AnyArcCodec<#nullable, #track_ref>
            }));
        }
        return Err(syn::Error::new_spanned(
            ty,
            "Arc<dyn Any> must include Send + Sync",
        ));
    }
    if let Some((_, trait_name)) = is_box_dyn_trait(ty) {
        let codec_ident = format_ident!("{trait_name}BoxCodec");
        return Ok(CodecSelection::plain(
            quote! { #codec_ident<#nullable, #track_ref> },
        ));
    }
    if let Some((_, trait_name)) = is_rc_dyn_trait(ty) {
        let codec_ident = format_ident!("{trait_name}RcCodec");
        return Ok(CodecSelection::plain(
            quote! { #codec_ident<#nullable, #track_ref> },
        ));
    }
    if let Some((_, trait_name)) = is_arc_dyn_trait(ty) {
        let codec_ident = format_ident!("{trait_name}ArcCodec");
        return Ok(CodecSelection::plain(
            quote! { #codec_ident<#nullable, #track_ref> },
        ));
    }
    Err(syn::Error::new_spanned(
        ty,
        "unsupported dynamic trait carrier",
    ))
}

fn tuple_codec_for(
    tuple: &syn::TypeTuple,
    ty: &Type,
    meta: &ForyFieldMeta,
    nullable: bool,
    track_ref: bool,
) -> syn::Result<CodecSelection> {
    validate_tuple_meta(ty, meta, tuple.elems.len())?;
    if tuple.elems.is_empty() {
        return Ok(CodecSelection::plain(quote! {
            fory_core::serializer::codec::SerializerCodec<#ty, #nullable, #track_ref>
        }));
    }
    let codec_ident = format_ident!("Tuple{}Codec", tuple.elems.len());
    let mut args = Vec::with_capacity(tuple.elems.len() * 2);
    for (index, elem_ty) in tuple.elems.iter().enumerate() {
        let child = codec_for_child(elem_ty, &meta.tuple_element_meta(index))?;
        let child_ty = child.ty;
        args.push(quote! { #elem_ty });
        args.push(child_ty);
    }
    Ok(CodecSelection {
        ty: quote! {
            fory_core::serializer::codec::#codec_ident<
                #(#args,)*
                #nullable,
                #track_ref
            >
        },
    })
}

fn validate_no_shape(ty: &Type, meta: &ForyFieldMeta, message: &str) -> syn::Result<()> {
    if has_shape(meta) || meta.encoding.is_some() {
        return Err(syn::Error::new_spanned(ty, message));
    }
    Ok(())
}

fn validate_collection_meta(ty: &Type, meta: &ForyFieldMeta, name: &str) -> syn::Result<()> {
    if meta.map.is_some() || meta.tuple.is_some() || meta.encoding.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            format!("{name} accepts only list(element(...)) collection config"),
        ));
    }
    if (meta.array || meta.bytes) && name != "Vec" {
        return Err(syn::Error::new_spanned(
            ty,
            "array and bytes are valid only for Vec fields",
        ));
    }
    Ok(())
}

fn validate_vec_schema(elem_ty: &Type, meta: &ForyFieldMeta) -> syn::Result<()> {
    let elem_name = type_name_and_args(elem_ty)
        .map(|(name, _)| name)
        .unwrap_or_else(|| elem_ty.to_token_stream().to_string().replace(' ', ""));
    if meta.bytes && elem_name != "u8" {
        return Err(syn::Error::new_spanned(
            elem_ty,
            "bytes schema requires Vec<u8>",
        ));
    }
    if meta.array
        && !matches!(
            elem_name.as_str(),
            "bool"
                | "i8"
                | "i16"
                | "i32"
                | "i64"
                | "i128"
                | "isize"
                | "u8"
                | "u16"
                | "u32"
                | "u64"
                | "u128"
                | "usize"
                | "float16"
                | "Float16"
                | "bfloat16"
                | "BFloat16"
                | "f32"
                | "f64"
        )
    {
        return Err(syn::Error::new_spanned(
            elem_ty,
            "array schema requires a canonical number or bool Vec element type",
        ));
    }
    Ok(())
}

fn validate_map_meta(ty: &Type, meta: &ForyFieldMeta, name: &str) -> syn::Result<()> {
    if meta.list.is_some()
        || meta.tuple.is_some()
        || meta.encoding.is_some()
        || meta.array
        || meta.bytes
    {
        return Err(syn::Error::new_spanned(
            ty,
            format!("{name} accepts only map(key(...), value(...)) config"),
        ));
    }
    Ok(())
}

fn validate_array_meta(ty: &Type, meta: &ForyFieldMeta) -> syn::Result<()> {
    if meta.map.is_some()
        || meta.tuple.is_some()
        || meta.encoding.is_some()
        || meta.array
        || meta.bytes
    {
        return Err(syn::Error::new_spanned(
            ty,
            "fixed arrays accept only list(element(...)) config",
        ));
    }
    Ok(())
}

fn validate_tuple_meta(ty: &Type, meta: &ForyFieldMeta, arity: usize) -> syn::Result<()> {
    if meta.list.is_some()
        || meta.map.is_some()
        || meta.encoding.is_some()
        || meta.array
        || meta.bytes
    {
        return Err(syn::Error::new_spanned(
            ty,
            "tuple fields accept only tuple(element(index = N, ...)) config",
        ));
    }
    if arity > 22 {
        return Err(syn::Error::new_spanned(
            ty,
            "tuple serialization supports arities 1 through 22",
        ));
    }
    if let Some(tuple) = &meta.tuple {
        if arity == 0 {
            return Err(syn::Error::new_spanned(
                ty,
                "tuple(...) is not valid for the unit tuple",
            ));
        }
        if let Some(element) = tuple.elements.iter().find(|element| element.index >= arity) {
            return Err(syn::Error::new_spanned(
                ty,
                format!(
                    "tuple element index {} is outside tuple arity {}",
                    element.index, arity
                ),
            ));
        }
    }
    Ok(())
}

fn validate_leaf_meta(ty: &Type, meta: &ForyFieldMeta) -> syn::Result<()> {
    if meta.list.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            "list(...) requires a one-child collection",
        ));
    }
    if meta.map.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            "map(...) requires HashMap or BTreeMap",
        ));
    }
    if meta.tuple.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            "tuple(...) requires a Rust tuple",
        ));
    }
    if meta.array || meta.bytes {
        return Err(syn::Error::new_spanned(ty, "array and bytes require Vec"));
    }
    Ok(())
}

fn has_shape(meta: &ForyFieldMeta) -> bool {
    meta.list.is_some() || meta.map.is_some() || meta.tuple.is_some() || meta.array || meta.bytes
}

fn is_transparent_carrier(name: &str) -> bool {
    matches!(
        name,
        "Box" | "Rc" | "Arc" | "RcWeak" | "ArcWeak" | "RefCell" | "Mutex"
    )
}

fn is_one_child_collection(name: &str) -> bool {
    matches!(
        name,
        "Vec" | "VecDeque" | "LinkedList" | "HashSet" | "BTreeSet" | "BinaryHeap"
    )
}

fn is_map(name: &str) -> bool {
    matches!(name, "HashMap" | "BTreeMap")
}

fn integer_codec_type(
    ty: &Type,
    meta: &ForyFieldMeta,
    nullable: bool,
    track_ref: bool,
) -> Option<TokenStream> {
    let type_name = type_name_and_args(ty)
        .map(|(name, _)| name)
        .unwrap_or_else(|| ty.to_token_stream().to_string().replace(' ', ""));
    let encoding = meta.encoding.unwrap_or(IntEncoding::Varint);
    match type_name.as_str() {
        "i32" => {
            let wire = match encoding {
                IntEncoding::Fixed => quote! { { fory_core::type_id::TypeId::INT32 as u8 } },
                IntEncoding::Varint => {
                    quote! { { fory_core::type_id::TypeId::VARINT32 as u8 } }
                }
                IntEncoding::Tagged => {
                    return Some(quote! {
                        compile_error!("encoding = tagged is only valid for 64-bit integer fields")
                    });
                }
            };
            Some(quote! { fory_core::serializer::codec::I32Codec<#wire, #nullable, #track_ref> })
        }
        "i64" => {
            let wire = match encoding {
                IntEncoding::Fixed => quote! { { fory_core::type_id::TypeId::INT64 as u8 } },
                IntEncoding::Varint => {
                    quote! { { fory_core::type_id::TypeId::VARINT64 as u8 } }
                }
                IntEncoding::Tagged => {
                    quote! { { fory_core::type_id::TypeId::TAGGED_INT64 as u8 } }
                }
            };
            Some(quote! { fory_core::serializer::codec::I64Codec<#wire, #nullable, #track_ref> })
        }
        "u32" => {
            let wire = match encoding {
                IntEncoding::Fixed => quote! { { fory_core::type_id::TypeId::UINT32 as u8 } },
                IntEncoding::Varint => {
                    quote! { { fory_core::type_id::TypeId::VAR_UINT32 as u8 } }
                }
                IntEncoding::Tagged => {
                    return Some(quote! {
                        compile_error!("encoding = tagged is only valid for 64-bit integer fields")
                    });
                }
            };
            Some(quote! { fory_core::serializer::codec::U32Codec<#wire, #nullable, #track_ref> })
        }
        "u64" => {
            let wire = match encoding {
                IntEncoding::Fixed => quote! { { fory_core::type_id::TypeId::UINT64 as u8 } },
                IntEncoding::Varint => {
                    quote! { { fory_core::type_id::TypeId::VAR_UINT64 as u8 } }
                }
                IntEncoding::Tagged => {
                    quote! { { fory_core::type_id::TypeId::TAGGED_UINT64 as u8 } }
                }
            };
            Some(quote! { fory_core::serializer::codec::U64Codec<#wire, #nullable, #track_ref> })
        }
        _ if meta.encoding.is_some() => Some(quote! {
            compile_error!("encoding is only valid for i32, i64, u32, and u64 fields")
        }),
        _ => None,
    }
}

fn compatible_scalar_reader_for(ty: &Type) -> Option<TokenStream> {
    if let Some(inner) = extract_option_inner_type(ty) {
        return compatible_scalar_reader_name(&inner, true);
    }
    compatible_scalar_reader_name(ty, false)
}

fn compatible_scalar_reader_name(ty: &Type, option: bool) -> Option<TokenStream> {
    let name = type_name_and_args(ty)
        .map(|(name, _)| name)
        .unwrap_or_else(|| ty.to_token_stream().to_string().replace(' ', ""));
    let reader = match (name.as_str(), option) {
        ("bool", false) => quote! { fory_core::serializer::codec::read_bool_compatible_scalar },
        ("bool", true) => {
            quote! { fory_core::serializer::codec::read_bool_option_compatible_scalar }
        }
        ("String", false) => {
            quote! { fory_core::serializer::codec::read_string_compatible_scalar }
        }
        ("String", true) => {
            quote! { fory_core::serializer::codec::read_string_option_compatible_scalar }
        }
        ("i8", false) => quote! { fory_core::serializer::codec::read_i8_compatible_scalar },
        ("i8", true) => quote! { fory_core::serializer::codec::read_i8_option_compatible_scalar },
        ("i16", false) => quote! { fory_core::serializer::codec::read_i16_compatible_scalar },
        ("i16", true) => {
            quote! { fory_core::serializer::codec::read_i16_option_compatible_scalar }
        }
        ("i32", false) => quote! { fory_core::serializer::codec::read_i32_compatible_scalar },
        ("i32", true) => {
            quote! { fory_core::serializer::codec::read_i32_option_compatible_scalar }
        }
        ("i64", false) => quote! { fory_core::serializer::codec::read_i64_compatible_scalar },
        ("i64", true) => {
            quote! { fory_core::serializer::codec::read_i64_option_compatible_scalar }
        }
        ("u8", false) => quote! { fory_core::serializer::codec::read_u8_compatible_scalar },
        ("u8", true) => quote! { fory_core::serializer::codec::read_u8_option_compatible_scalar },
        ("u16", false) => quote! { fory_core::serializer::codec::read_u16_compatible_scalar },
        ("u16", true) => {
            quote! { fory_core::serializer::codec::read_u16_option_compatible_scalar }
        }
        ("u32", false) => quote! { fory_core::serializer::codec::read_u32_compatible_scalar },
        ("u32", true) => {
            quote! { fory_core::serializer::codec::read_u32_option_compatible_scalar }
        }
        ("u64", false) => quote! { fory_core::serializer::codec::read_u64_compatible_scalar },
        ("u64", true) => {
            quote! { fory_core::serializer::codec::read_u64_option_compatible_scalar }
        }
        ("f32", false) => quote! { fory_core::serializer::codec::read_f32_compatible_scalar },
        ("f32", true) => {
            quote! { fory_core::serializer::codec::read_f32_option_compatible_scalar }
        }
        ("f64", false) => quote! { fory_core::serializer::codec::read_f64_compatible_scalar },
        ("f64", true) => {
            quote! { fory_core::serializer::codec::read_f64_option_compatible_scalar }
        }
        ("float16" | "Float16", false) => {
            quote! { fory_core::serializer::codec::read_float16_compatible_scalar }
        }
        ("float16" | "Float16", true) => {
            quote! { fory_core::serializer::codec::read_float16_option_compatible_scalar }
        }
        ("bfloat16" | "BFloat16", false) => {
            quote! { fory_core::serializer::codec::read_bfloat16_compatible_scalar }
        }
        ("bfloat16" | "BFloat16", true) => {
            quote! { fory_core::serializer::codec::read_bfloat16_option_compatible_scalar }
        }
        ("Decimal", false) => {
            quote! { fory_core::serializer::codec::read_decimal_compatible_scalar }
        }
        ("Decimal", true) => {
            quote! { fory_core::serializer::codec::read_decimal_option_compatible_scalar }
        }
        _ => return None,
    };
    Some(reader)
}

fn type_name_and_args(
    ty: &Type,
) -> Option<(
    String,
    Option<&syn::punctuated::Punctuated<GenericArgument, syn::token::Comma>>,
)> {
    let Type::Path(type_path) = ty else {
        return None;
    };
    let seg = type_path.path.segments.last()?;
    let PathArguments::AngleBracketed(args) = &seg.arguments else {
        return Some((seg.ident.to_string(), None));
    };
    Some((seg.ident.to_string(), Some(&args.args)))
}

fn single_type_arg<'a>(
    args: &'a syn::punctuated::Punctuated<GenericArgument, syn::token::Comma>,
    ty: &Type,
    owner: &str,
) -> syn::Result<&'a Type> {
    args.iter()
        .find_map(|arg| match arg {
            GenericArgument::Type(ty) => Some(ty),
            _ => None,
        })
        .ok_or_else(|| syn::Error::new_spanned(ty, format!("{owner} requires one type argument")))
}

fn two_type_args<'a>(
    args: &'a syn::punctuated::Punctuated<GenericArgument, syn::token::Comma>,
    ty: &Type,
    owner: &str,
) -> syn::Result<(&'a Type, &'a Type)> {
    let mut iter = args.iter().filter_map(|arg| match arg {
        GenericArgument::Type(ty) => Some(ty),
        _ => None,
    });
    let first = iter
        .next()
        .ok_or_else(|| syn::Error::new_spanned(ty, format!("{owner} requires key type")))?;
    let second = iter
        .next()
        .ok_or_else(|| syn::Error::new_spanned(ty, format!("{owner} requires value type")))?;
    Ok((first, second))
}

fn any_trait_object_for<'a>(ty: &'a Type, owner: &str) -> Option<&'a syn::TypeTraitObject> {
    let Type::Path(type_path) = ty else {
        return None;
    };
    let segment = type_path.path.segments.last()?;
    if segment.ident != owner {
        return None;
    }
    let PathArguments::AngleBracketed(args) = &segment.arguments else {
        return None;
    };
    let inner = args.args.iter().find_map(|arg| match arg {
        GenericArgument::Type(ty) => Some(ty),
        _ => None,
    })?;
    let Type::TraitObject(trait_obj) = inner else {
        return None;
    };
    if trait_obj.bounds.iter().any(|bound| {
        matches!(
            bound,
            syn::TypeParamBound::Trait(trait_bound)
                if trait_bound.path.segments.last().is_some_and(|segment| segment.ident == "Any")
        )
    }) {
        Some(trait_obj)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use syn::parse_quote;

    fn selected_codec(target: Type, provider: Type) -> String {
        let class = classify_field_type(&target);
        let meta = ForyFieldMeta {
            with: Some(provider),
            ..Default::default()
        };
        codec_type_for(
            &target,
            &meta,
            meta.effective_nullable(class) || is_option_type(&target),
            meta.effective_ref(class),
        )
        .unwrap()
        .to_string()
    }

    fn selected_error(target: Type, provider: Type) -> String {
        let class = classify_field_type(&target);
        let meta = ForyFieldMeta {
            with: Some(provider),
            ..Default::default()
        };
        codec_type_for(
            &target,
            &meta,
            meta.effective_nullable(class) || is_option_type(&target),
            meta.effective_ref(class),
        )
        .unwrap_err()
        .to_string()
    }

    #[test]
    fn lowers_nested_external_list() {
        let ty: Type = parse_quote!(Vec<Vec<External>>);
        let field: syn::Field = parse_quote!(
            #[fory(list(element(list(element(with = ExternalSerializer)))))]
            value: Vec<Vec<External>>
        );
        let meta = parse_field_meta(&field).unwrap();
        let codec = codec_type_for(&ty, &meta, false, false)
            .unwrap()
            .to_string();
        assert!(codec.contains("VecCodec"));
        assert!(codec.contains("ExternalSerializer"));
        assert!(codec.contains("true , false , false , false"));
    }

    #[test]
    fn rejects_tuple_index_outside_arity() {
        let ty: Type = parse_quote!((String, i32));
        let field: syn::Field = parse_quote!(
            #[fory(tuple(element(index = 2, with = ExternalSerializer)))]
            value: (String, i32)
        );
        let meta = parse_field_meta(&field).unwrap();
        let error = codec_type_for(&ty, &meta, false, false).unwrap_err();
        assert!(error.to_string().contains("outside tuple arity 2"));
    }

    #[test]
    fn lowers_leaf_provider() {
        let codec = selected_codec(parse_quote!(External), parse_quote!(ExternalSerializer));
        assert_eq!(
            codec,
            quote! {
                fory_core::serializer::codec::SerializerCodec<
                    ExternalSerializer,
                    false,
                    false
                >
            }
            .to_string()
        );
    }

    #[test]
    fn lowers_all_transparent_serializers() {
        for (target, provider, codec_name) in [
            (
                "Option<External>",
                "OptionSerializer<ExternalSerializer>",
                "OptionCodec",
            ),
            (
                "Box<External>",
                "BoxSerializer<ExternalSerializer>",
                "BoxCodec",
            ),
            (
                "Rc<External>",
                "RcSerializer<ExternalSerializer>",
                "RcCodec",
            ),
            (
                "Arc<External>",
                "ArcSerializer<ExternalSerializer>",
                "ArcCodec",
            ),
            (
                "RcWeak<External>",
                "RcWeakSerializer<ExternalSerializer>",
                "RcWeakCodec",
            ),
            (
                "ArcWeak<External>",
                "ArcWeakSerializer<ExternalSerializer>",
                "ArcWeakCodec",
            ),
            (
                "RefCell<External>",
                "RefCellSerializer<ExternalSerializer>",
                "RefCellCodec",
            ),
            (
                "Mutex<External>",
                "MutexSerializer<ExternalSerializer>",
                "MutexCodec",
            ),
        ] {
            let codec = selected_codec(
                syn::parse_str(target).unwrap(),
                syn::parse_str(provider).unwrap(),
            );
            assert!(codec.contains(codec_name), "{codec}");
            assert!(
                codec.contains("SerializerCodec < ExternalSerializer"),
                "{codec}"
            );
            let provider_name = provider.split('<').next().expect("provider name");
            assert!(
                !codec.contains(&format!("SerializerCodec < {provider_name}")),
                "{codec}"
            );
        }
    }

    #[test]
    fn lowers_all_collection_serializers() {
        for (target, provider, codec_name) in [
            (
                "Vec<External>",
                "VecSerializer<ExternalSerializer>",
                "VecCodec",
            ),
            (
                "VecDeque<External>",
                "VecDequeSerializer<ExternalSerializer>",
                "VecDequeCodec",
            ),
            (
                "LinkedList<External>",
                "LinkedListSerializer<ExternalSerializer>",
                "LinkedListCodec",
            ),
            (
                "HashSet<External>",
                "HashSetSerializer<ExternalSerializer>",
                "HashSetCodec",
            ),
            (
                "BTreeSet<External>",
                "BTreeSetSerializer<ExternalSerializer>",
                "BTreeSetCodec",
            ),
            (
                "BinaryHeap<External>",
                "BinaryHeapSerializer<ExternalSerializer>",
                "BinaryHeapCodec",
            ),
        ] {
            let codec = selected_codec(
                syn::parse_str(target).unwrap(),
                syn::parse_str(provider).unwrap(),
            );
            assert!(codec.contains(codec_name), "{codec}");
            assert!(
                codec.contains("SerializerCodec < ExternalSerializer"),
                "{codec}"
            );
        }
    }

    #[test]
    fn direct_vec_schema_mode() {
        let direct = selected_codec(
            parse_quote!(Vec<External>),
            parse_quote!(fory::VecSerializer<ExternalSerializer>),
        );
        assert!(direct.contains("false , false , false , false"), "{direct}");

        let ty: Type = parse_quote!(Vec<External>);
        let field: syn::Field = parse_quote!(
            #[fory(list(element(with = ExternalSerializer)))]
            value: Vec<External>
        );
        let meta = parse_field_meta(&field).unwrap();
        let explicit = codec_type_for(&ty, &meta, false, false)
            .unwrap()
            .to_string();
        assert!(
            explicit.contains("true , false , false , false"),
            "{explicit}"
        );
    }

    #[test]
    fn lowers_nested_selected_carriers() {
        let codec = selected_codec(
            parse_quote!(Vec<Option<External>>),
            parse_quote!(fory::VecSerializer<fory::OptionSerializer<ExternalSerializer>>),
        );
        assert!(codec.contains("VecCodec < Option < External >"), "{codec}");
        assert!(codec.contains("OptionCodec < External"), "{codec}");
        assert!(
            codec.contains("SerializerCodec < ExternalSerializer"),
            "{codec}"
        );
        assert!(
            !codec.contains("SerializerCodec < fory :: VecSerializer"),
            "{codec}"
        );
    }

    #[test]
    fn lowers_map_serializers() {
        for (target, provider, codec_name) in [
            (
                "HashMap<Key, External>",
                "HashMapSerializer<KeySerializer, ExternalSerializer>",
                "HashMapCodec",
            ),
            (
                "BTreeMap<Key, External>",
                "BTreeMapSerializer<KeySerializer, ExternalSerializer>",
                "BTreeMapCodec",
            ),
        ] {
            let codec = selected_codec(
                syn::parse_str(target).unwrap(),
                syn::parse_str(provider).unwrap(),
            );
            assert!(codec.contains(codec_name), "{codec}");
            assert!(codec.contains("SerializerCodec < KeySerializer"), "{codec}");
            assert!(
                codec.contains("SerializerCodec < ExternalSerializer"),
                "{codec}"
            );
        }
    }

    #[test]
    fn lowers_nested_map_value() {
        let codec = selected_codec(
            parse_quote!(HashMap<Key, Vec<External>>),
            parse_quote!(
                HashMapSerializer<KeySerializer, VecSerializer<ExternalSerializer>>
            ),
        );
        assert!(
            codec.contains("HashMapCodec < Key , Vec < External >"),
            "{codec}"
        );
        assert!(codec.contains("VecCodec < External"), "{codec}");
        assert!(
            codec.contains("SerializerCodec < ExternalSerializer"),
            "{codec}"
        );
    }

    #[test]
    fn lowers_array_serializer_const() {
        let codec = selected_codec(
            parse_quote!([External; N]),
            parse_quote!(ArraySerializer<ExternalSerializer, M>),
        );
        assert!(codec.contains("ArrayCodec < External"), "{codec}");
        assert!(
            codec.contains("SerializerCodec < ExternalSerializer"),
            "{codec}"
        );
        assert!(codec.contains(", M , false , false"), "{codec}");
    }

    #[test]
    fn lowers_tuple_serializers() {
        for arity in 1..=22 {
            let target_elements = (0..arity)
                .map(|index| format!("T{index}"))
                .collect::<Vec<_>>()
                .join(", ");
            let provider_elements = (0..arity)
                .map(|index| format!("S{index}"))
                .collect::<Vec<_>>()
                .join(", ");
            let target = if arity == 1 {
                format!("({target_elements},)")
            } else {
                format!("({target_elements})")
            };
            let provider = format!("Tuple{arity}Serializer<{provider_elements}>");
            let codec = selected_codec(
                syn::parse_str(&target).unwrap(),
                syn::parse_str(&provider).unwrap(),
            );
            assert!(codec.contains(&format!("Tuple{arity}Codec")), "{codec}");
            for index in 0..arity {
                assert!(
                    codec.contains(&format!("SerializerCodec < S{index}")),
                    "{codec}"
                );
            }
        }
    }

    #[test]
    fn lowers_ordinary_carriers() {
        let codec = selected_codec(
            parse_quote!(Vec<Option<External>>),
            parse_quote!(Vec<Option<External>>),
        );
        assert!(codec.contains("VecCodec < Option < External >"), "{codec}");
        assert!(codec.contains("OptionCodec < External"), "{codec}");
        assert!(codec.contains("SerializerCodec < External"), "{codec}");

        let codec = selected_codec(
            parse_quote!(HashMap<Key, External>),
            parse_quote!(HashMap<Key, External>),
        );
        assert!(codec.contains("HashMapCodec < Key , External"), "{codec}");

        let codec = selected_codec(parse_quote!([External; N]), parse_quote!([External; N]));
        assert!(codec.contains("ArrayCodec < External"), "{codec}");

        let codec = selected_codec(parse_quote!((Key, External)), parse_quote!((Key, External)));
        assert!(codec.contains("Tuple2Codec < Key"), "{codec}");
    }

    #[test]
    fn lowers_nested_dynamic() {
        let codec = selected_codec(
            parse_quote!(Vec<Box<dyn Animal>>),
            parse_quote!(VecSerializer<Box<dyn Animal>>),
        );
        assert!(codec.contains("VecCodec < Box < dyn Animal >"), "{codec}");
        assert!(codec.contains("AnimalBoxCodec"), "{codec}");
        assert!(!codec.contains("SerializerCodec < Box < dyn Animal"));
    }

    #[test]
    fn dynamic_provider_is_preserved() {
        let codec = selected_codec(
            parse_quote!(Vec<Box<dyn Animal>>),
            parse_quote!(VecSerializer<Box<dyn Plant>>),
        );
        assert!(codec.contains("PlantBoxCodec"), "{codec}");
        assert!(!codec.contains("AnimalBoxCodec"), "{codec}");
    }

    #[test]
    fn carrier_alias_uses_leaf_codec() {
        let codec = selected_codec(
            parse_quote!(Vec<External>),
            parse_quote!(Users<ExternalSerializer>),
        );
        // The leaf adapter's cold field-schema validation owns the targeted
        // rejection because stable derive cannot resolve this alias.
        assert!(codec.contains("SerializerCodec < Users < ExternalSerializer >"));
        assert!(!codec.contains("VecCodec"));
    }

    #[test]
    fn rejects_malformed_carrier_arity() {
        for (target, provider, expected) in [
            (
                "Vec<External>",
                "VecSerializer",
                "VecSerializer requires exactly 1 type argument",
            ),
            (
                "Vec<External>",
                "VecSerializer<A, B>",
                "VecSerializer requires exactly 1 type argument",
            ),
            (
                "HashMap<Key, External>",
                "HashMapSerializer<KeySerializer>",
                "HashMapSerializer requires exactly 2 type argument",
            ),
            (
                "[External; 4]",
                "ArraySerializer<ExternalSerializer>",
                "ArraySerializer requires one serializer type and one const length",
            ),
            (
                "(A, B)",
                "Tuple2Serializer<SA>",
                "Tuple2Serializer requires exactly 2 type argument",
            ),
        ] {
            let error = selected_error(
                syn::parse_str(target).unwrap(),
                syn::parse_str(provider).unwrap(),
            );
            assert!(error.contains(expected), "{error}");
        }
    }

    #[test]
    fn rejects_carrier_target_shape() {
        let error = selected_error(
            parse_quote!(Option<External>),
            parse_quote!(VecSerializer<ExternalSerializer>),
        );
        assert!(error.contains("requires target Vec<T>"), "{error}");

        let error = selected_error(parse_quote!((A,)), parse_quote!(Tuple2Serializer<SA, SB>));
        assert!(error.contains("target tuple has arity 1"), "{error}");
    }
}
