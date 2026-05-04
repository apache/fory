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
use super::util::get_type_id_by_type_ast;
use crate::util::{is_arc_dyn_trait, is_box_dyn_trait, is_rc_dyn_trait, SourceField};
use proc_macro2::TokenStream;
use quote::{format_ident, quote, ToTokens};
use syn::{GenericArgument, PathArguments, Type};

pub(crate) struct ResolvedField<'a> {
    pub source: &'a SourceField<'a>,
    pub private_ident: syn::Ident,
    pub dispatch: FieldDispatch,
    pub value_ty: &'a Type,
    pub field_id: i16,
}

pub(crate) enum FieldDispatch {
    Codec {
        codec_ty: TokenStream,
    },
    Serializer {
        field_type: TokenStream,
        has_generics: bool,
    },
}

impl<'a> ResolvedField<'a> {
    #[inline]
    pub fn codec_call(&self) -> TokenStream {
        match &self.dispatch {
            FieldDispatch::Codec { codec_ty } => {
                let value_ty = self.value_ty;
                quote! { <#codec_ty as fory_core::serializer::codec::Codec<#value_ty>> }
            }
            FieldDispatch::Serializer { .. } => {
                quote! { compile_error!("serializer-dispatched field has no codec call") }
            }
        }
    }

    pub fn reserved_space(&self) -> TokenStream {
        match &self.dispatch {
            FieldDispatch::Codec { .. } => {
                let call = self.codec_call();
                quote! { #call::reserved_space() }
            }
            FieldDispatch::Serializer { .. } => {
                let ty = self.value_ty;
                quote! {
                    <#ty as fory_core::Serializer>::fory_reserved_space()
                        + fory_core::type_id::SIZE_OF_REF_AND_TYPE
                }
            }
        }
    }

    pub fn write_field(&self) -> TokenStream {
        let access =
            super::util::get_field_accessor(self.source.field, self.source.original_index, true);
        self.write_value_field(quote! { &#access })
    }

    pub fn write_value_field(&self, value: TokenStream) -> TokenStream {
        match &self.dispatch {
            FieldDispatch::Codec { .. } => {
                let call = self.codec_call();
                quote! {
                    #call::write_field(#value, context)?;
                }
            }
            FieldDispatch::Serializer { has_generics, .. } => {
                let ty = self.value_ty;
                if serializer_field_can_use_data_path(self.source.field) {
                    quote! {
                        <#ty as fory_core::Serializer>::fory_write_data_generic(
                            #value,
                            context,
                            #has_generics
                        )?;
                    }
                } else {
                    let ref_mode = serializer_ref_mode_for_field(self.source.field);
                    quote! {
                        let write_type_info = if context.is_compatible() {
                            fory_core::serializer::util::field_need_write_type_info(
                                <#ty as fory_core::Serializer>::fory_static_type_id()
                            )
                        } else {
                            <#ty as fory_core::Serializer>::fory_is_polymorphic()
                        };
                        <#ty as fory_core::Serializer>::fory_write(
                            #value,
                            context,
                            #ref_mode,
                            write_type_info,
                            #has_generics
                        )?;
                    }
                }
            }
        }
    }

    pub fn write_value_with_mode(
        &self,
        value: TokenStream,
        ref_mode: TokenStream,
        write_type_info: TokenStream,
    ) -> TokenStream {
        match &self.dispatch {
            FieldDispatch::Codec { .. } => {
                let call = self.codec_call();
                quote! {
                    #call::write_with_mode(
                        #value,
                        context,
                        #ref_mode,
                        #write_type_info,
                        false,
                    )?;
                }
            }
            FieldDispatch::Serializer { has_generics, .. } => {
                let ty = self.value_ty;
                quote! {
                    <#ty as fory_core::Serializer>::fory_write(
                        #value,
                        context,
                        #ref_mode,
                        #write_type_info,
                        #has_generics,
                    )?;
                }
            }
        }
    }

    pub fn read_field(&self) -> TokenStream {
        let var = &self.private_ident;
        match &self.dispatch {
            FieldDispatch::Codec { .. } => {
                let call = self.codec_call();
                quote! {
                    let #var = #call::read_field(context)?;
                }
            }
            FieldDispatch::Serializer { .. } => {
                let ty = self.value_ty;
                if serializer_field_can_use_data_path(self.source.field) {
                    quote! {
                        let #var = <#ty as fory_core::Serializer>::fory_read_data(context)?;
                    }
                } else {
                    let ref_mode = serializer_ref_mode_for_field(self.source.field);
                    quote! {
                        let read_type_info = if context.is_compatible() {
                            fory_core::serializer::util::field_need_read_type_info(
                                <#ty as fory_core::Serializer>::fory_static_type_id() as u32
                            )
                        } else {
                            <#ty as fory_core::Serializer>::fory_is_polymorphic()
                        };
                        let #var = <#ty as fory_core::Serializer>::fory_read(
                            context,
                            #ref_mode,
                            read_type_info
                        )?;
                    }
                }
            }
        }
    }

    pub fn read_with_mode_expr(
        &self,
        ref_mode: TokenStream,
        read_type_info: TokenStream,
    ) -> TokenStream {
        match &self.dispatch {
            FieldDispatch::Codec { .. } => {
                let call = self.codec_call();
                quote! {
                    #call::read_with_mode(context, #ref_mode, #read_type_info)?
                }
            }
            FieldDispatch::Serializer { .. } => {
                let ty = self.value_ty;
                quote! {
                    <#ty as fory_core::Serializer>::fory_read(context, #ref_mode, #read_type_info)?
                }
            }
        }
    }

    pub fn declare_compatible_var(&self) -> TokenStream {
        let var = &self.private_ident;
        let ty = self.value_ty;
        quote! {
            let mut #var: Option<#ty> = None;
        }
    }

    pub fn assign_value(&self) -> TokenStream {
        let var = &self.private_ident;
        let default_expr = default_expr_for_type(self.value_ty);
        quote! {
            #var.unwrap_or_else(|| #default_expr)
        }
    }

    pub fn read_compatible(&self) -> TokenStream {
        let var = &self.private_ident;
        match &self.dispatch {
            FieldDispatch::Codec { .. } => {
                let call = self.codec_call();
                quote! {
                    let remote_field_type = &_field.field_type;
                    if let Some(value) = #call::read_compatible(
                        context,
                        local_field_type,
                        remote_field_type,
                    )? {
                        #var = Some(value);
                    } else {
                        let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                            remote_field_type.type_id,
                            remote_field_type.nullable,
                        );
                        fory_core::serializer::skip::skip_field_value(context, remote_field_type, read_ref_flag)?;
                    }
                }
            }
            FieldDispatch::Serializer { .. } => {
                let ty = self.value_ty;
                quote! {
                    let remote_field_type = &_field.field_type;
                    if fory_core::serializer::codec::field_types_compatible(
                        local_field_type,
                        remote_field_type,
                    ) {
                        let read_ref_mode =
                            fory_core::serializer::codec::field_ref_mode(remote_field_type);
                        let read_type_info = if context.is_compatible() {
                            fory_core::serializer::util::field_need_read_type_info(
                                remote_field_type.type_id
                            )
                        } else {
                            <#ty as fory_core::Serializer>::fory_is_polymorphic()
                        };
                        #var = Some(<#ty as fory_core::Serializer>::fory_read(
                            context,
                            read_ref_mode,
                            read_type_info,
                        )?);
                    } else {
                        let read_ref_flag = fory_core::serializer::util::field_need_write_ref_into(
                            remote_field_type.type_id,
                            remote_field_type.nullable,
                        );
                        fory_core::serializer::skip::skip_field_value(context, remote_field_type, read_ref_flag)?;
                    }
                }
            }
        }
    }

    pub fn field_info(&self) -> TokenStream {
        let field_id = self.field_id;
        let name = &self.source.field_name;
        match &self.dispatch {
            FieldDispatch::Codec { .. } => {
                let call = self.codec_call();
                quote! {
                    fory_core::meta::FieldInfo::new_with_id(
                        #field_id,
                        #name,
                        #call::field_type(type_resolver)?
                    )
                }
            }
            FieldDispatch::Serializer { field_type, .. } => {
                quote! {
                    fory_core::meta::FieldInfo::new_with_id(
                        #field_id,
                        #name,
                        #field_type
                    )
                }
            }
        }
    }
}

pub(crate) struct SkippedField<'a> {
    pub source: &'a SourceField<'a>,
    pub private_ident: syn::Ident,
}

impl<'a> SkippedField<'a> {
    pub fn read_default(&self) -> TokenStream {
        let var = &self.private_ident;
        let default_expr = default_expr_for_type(&self.source.field.ty);
        quote! {
            let #var = #default_expr;
        }
    }

    pub fn assign_value(&self) -> TokenStream {
        let var = &self.private_ident;
        quote! { #var }
    }
}

pub(crate) enum FieldBinding<'a> {
    Codec(ResolvedField<'a>),
    Skipped(SkippedField<'a>),
}

pub(crate) fn build_bindings<'a>(
    source_fields: &'a [SourceField<'a>],
) -> syn::Result<Vec<FieldBinding<'a>>> {
    source_fields
        .iter()
        .map(|source| {
            let meta = parse_field_meta(source.field)?;
            let private_ident = create_private_field_name(source.field, source.original_index);
            if meta.skip {
                return Ok(FieldBinding::Skipped(SkippedField {
                    source,
                    private_ident,
                }));
            }
            let type_class = classify_field_type(&source.field.ty);
            let is_outer_option = is_option_type(&source.field.ty);
            let nullable = meta.effective_nullable(type_class) || is_outer_option;
            let track_ref = meta.effective_ref(type_class);
            let field_id = if meta.uses_tag_id() {
                meta.effective_id() as i16
            } else {
                -1
            };
            let dispatch = field_dispatch_for(&source.field.ty, &meta, nullable, track_ref)?;
            Ok(FieldBinding::Codec(ResolvedField {
                source,
                private_ident,
                dispatch,
                value_ty: &source.field.ty,
                field_id,
            }))
        })
        .collect()
}

fn field_dispatch_for(
    ty: &Type,
    meta: &ForyFieldMeta,
    nullable: bool,
    track_ref: bool,
) -> syn::Result<FieldDispatch> {
    if meta.array {
        let codec_ty = codec_type_for(ty, meta, nullable, track_ref)?;
        return Ok(FieldDispatch::Serializer {
            field_type: quote! {
                <#codec_ty as fory_core::serializer::codec::Codec<#ty>>::field_type(type_resolver)?
            },
            has_generics: true,
        });
    }
    if meta.encoding.is_none()
        && meta.list.is_none()
        && !meta.array
        && meta.map.is_none()
        && is_container_type(ty)
        && !is_vec_type(ty)
        && !contains_custom_trait_object(ty)
        && !contains_exact_any_object(ty)
    {
        return Ok(FieldDispatch::Serializer {
            field_type: field_type_expr_for(ty, nullable, track_ref)?,
            has_generics: true,
        });
    }
    Ok(FieldDispatch::Codec {
        codec_ty: codec_type_for(ty, meta, nullable, track_ref)?,
    })
}

pub(crate) fn codec_type_for(
    ty: &Type,
    meta: &ForyFieldMeta,
    nullable: bool,
    track_ref: bool,
) -> syn::Result<TokenStream> {
    if let Some(inner) = extract_option_inner_type(ty) {
        let inner_meta = ForyFieldMeta {
            nullable: Some(false),
            r#ref: None,
            ..meta.clone()
        };
        let inner_codec = codec_type_for(&inner, &inner_meta, false, false)?;
        return Ok(quote! {
            fory_core::serializer::codec::OptionCodec<#inner, #inner_codec, #track_ref>
        });
    }

    if let Some((name, Some(args))) = type_name_and_args(ty) {
        if name == "Vec" {
            if meta.encoding.is_some() {
                return Err(syn::Error::new_spanned(
                    ty,
                    "encoding is only valid on integer values; use list(element(encoding = ...)) for Vec elements",
                ));
            }
            if meta.map.is_some() {
                return Err(syn::Error::new_spanned(
                    ty,
                    "map(...) config is only valid for HashMap fields",
                ));
            }
            let elem_ty = single_type_arg(args, ty, "Vec")?;
            if meta.bytes {
                if meta.array || meta.list.is_some() {
                    return Err(syn::Error::new_spanned(
                        ty,
                        "bytes cannot be combined with array or list(...) config",
                    ));
                }
                if vec_element_type_name(elem_ty).as_deref() != Some("u8") {
                    return Err(syn::Error::new_spanned(ty, "bytes schema requires Vec<u8>"));
                }
                return Ok(quote! {
                    fory_core::serializer::codec::SerializerCodec<#ty, #nullable, #track_ref>
                });
            }
            if meta.array {
                if meta.list.is_some() {
                    return Err(syn::Error::new_spanned(
                        ty,
                        "array cannot be combined with list(...) config",
                    ));
                }
                let type_id = primitive_array_type_id_for_vec_element(elem_ty)?;
                return Ok(quote! {
                    fory_core::serializer::codec::PrimitiveArrayVecCodec<
                        #elem_ty,
                        #type_id,
                        #nullable,
                        #track_ref
                    >
                });
            }
            if meta.list.is_none() {
                let elem_meta = ForyFieldMeta::default();
                let elem_class = classify_field_type(elem_ty);
                let elem_codec = codec_type_for(
                    elem_ty,
                    &elem_meta,
                    elem_meta.effective_nullable(elem_class) || is_option_type(elem_ty),
                    elem_meta.effective_ref(elem_class),
                )?;
                return Ok(quote! {
                    fory_core::serializer::codec::VecCodec<#elem_ty, #elem_codec, #nullable, #track_ref>
                });
            }
            let elem_meta = meta.element_meta();
            let elem_class = classify_field_type(elem_ty);
            let elem_nullable = elem_meta.effective_nullable(elem_class) || is_option_type(elem_ty);
            let elem_track_ref = elem_meta.effective_ref(elem_class);
            let elem_codec = codec_type_for(elem_ty, &elem_meta, elem_nullable, elem_track_ref)?;
            return Ok(quote! {
                fory_core::serializer::codec::VecCodec<#elem_ty, #elem_codec, #nullable, #track_ref>
            });
        }
        if name == "HashMap" {
            if meta.encoding.is_some() {
                return Err(syn::Error::new_spanned(
                    ty,
                    "encoding is only valid on integer values; use map(key(...), value(...)) for HashMap entries",
                ));
            }
            if meta.list.is_some() {
                return Err(syn::Error::new_spanned(
                    ty,
                    "list(...) config is only valid for Vec fields",
                ));
            }
            if meta.array {
                return Err(syn::Error::new_spanned(
                    ty,
                    "array config is only valid for Vec fields",
                ));
            }
            if meta.bytes {
                return Err(syn::Error::new_spanned(
                    ty,
                    "bytes config is only valid for Vec<u8> fields",
                ));
            }
            let (key_ty, value_ty) = two_type_args(args, ty, "HashMap")?;
            if meta.map.is_none() {
                let key_meta = ForyFieldMeta::default();
                let value_meta = ForyFieldMeta::default();
                let key_class = classify_field_type(key_ty);
                let value_class = classify_field_type(value_ty);
                let key_codec = codec_type_for(
                    key_ty,
                    &key_meta,
                    key_meta.effective_nullable(key_class) || is_option_type(key_ty),
                    key_meta.effective_ref(key_class),
                )?;
                let value_codec = codec_type_for(
                    value_ty,
                    &value_meta,
                    value_meta.effective_nullable(value_class) || is_option_type(value_ty),
                    value_meta.effective_ref(value_class),
                )?;
                if contains_custom_trait_object(key_ty)
                    || contains_custom_trait_object(value_ty)
                    || contains_exact_any_object(key_ty)
                    || contains_exact_any_object(value_ty)
                {
                    return Ok(quote! {
                        fory_core::serializer::codec::HashMapCodec<#key_ty, #value_ty, #key_codec, #value_codec, #nullable, #track_ref>
                    });
                }
                return Ok(quote! {
                    fory_core::serializer::codec::MapSerializerCodec<
                        #ty,
                        #key_ty,
                        #value_ty,
                        #key_codec,
                        #value_codec,
                        #nullable,
                        #track_ref
                    >
                });
            }
            let key_meta = meta.map_key_meta();
            let value_meta = meta.map_value_meta();
            if key_meta.array {
                return Err(syn::Error::new_spanned(
                    key_ty,
                    "array schema is not valid for map keys",
                ));
            }
            let key_class = classify_field_type(key_ty);
            let value_class = classify_field_type(value_ty);
            let key_codec = codec_type_for(
                key_ty,
                &key_meta,
                key_meta.effective_nullable(key_class) || is_option_type(key_ty),
                key_meta.effective_ref(key_class),
            )?;
            let value_codec = codec_type_for(
                value_ty,
                &value_meta,
                value_meta.effective_nullable(value_class) || is_option_type(value_ty),
                value_meta.effective_ref(value_class),
            )?;
            return Ok(quote! {
                fory_core::serializer::codec::HashMapCodec<#key_ty, #value_ty, #key_codec, #value_codec, #nullable, #track_ref>
            });
        }
        if name == "HashSet" {
            if meta.encoding.is_some() {
                return Err(syn::Error::new_spanned(
                    ty,
                    "encoding is only valid on integer values",
                ));
            }
            if meta.list.is_some() {
                return Err(syn::Error::new_spanned(
                    ty,
                    "list(...) config is only valid for Vec fields",
                ));
            }
            if meta.map.is_some() {
                return Err(syn::Error::new_spanned(
                    ty,
                    "map(...) config is only valid for HashMap fields",
                ));
            }
            if meta.array {
                return Err(syn::Error::new_spanned(
                    ty,
                    "array config is only valid for Vec fields",
                ));
            }
            if meta.bytes {
                return Err(syn::Error::new_spanned(
                    ty,
                    "bytes config is only valid for Vec<u8> fields",
                ));
            }
            let elem_ty = single_type_arg(args, ty, "HashSet")?;
            let elem_meta = ForyFieldMeta::default();
            let elem_class = classify_field_type(elem_ty);
            let elem_codec = codec_type_for(
                elem_ty,
                &elem_meta,
                elem_meta.effective_nullable(elem_class) || is_option_type(elem_ty),
                elem_meta.effective_ref(elem_class),
            )?;
            return Ok(quote! {
                fory_core::serializer::codec::CollectionSerializerCodec<
                    #ty,
                    #elem_ty,
                    #elem_codec,
                    { fory_core::type_id::TypeId::SET as u8 },
                    #nullable,
                    #track_ref
                >
            });
        }
        if is_serializer_backed_collection(&name) {
            validate_serializer_backed_collection_meta(ty, meta, &name)?;
            let elem_ty = single_type_arg(args, ty, &name)?;
            let elem_meta = ForyFieldMeta::default();
            let elem_class = classify_field_type(elem_ty);
            let elem_codec = codec_type_for(
                elem_ty,
                &elem_meta,
                elem_meta.effective_nullable(elem_class) || is_option_type(elem_ty),
                elem_meta.effective_ref(elem_class),
            )?;
            let type_id = serializer_backed_collection_type_id(&name);
            return Ok(quote! {
                fory_core::serializer::codec::CollectionSerializerCodec<
                    #ty,
                    #elem_ty,
                    #elem_codec,
                    #type_id,
                    #nullable,
                    #track_ref
                >
            });
        }
        if is_serializer_backed_map(&name) {
            validate_serializer_backed_map_meta(ty, meta, &name)?;
            let (key_ty, value_ty) = two_type_args(args, ty, &name)?;
            let key_meta = ForyFieldMeta::default();
            let value_meta = ForyFieldMeta::default();
            let key_class = classify_field_type(key_ty);
            let value_class = classify_field_type(value_ty);
            let key_codec = codec_type_for(
                key_ty,
                &key_meta,
                key_meta.effective_nullable(key_class) || is_option_type(key_ty),
                key_meta.effective_ref(key_class),
            )?;
            let value_codec = codec_type_for(
                value_ty,
                &value_meta,
                value_meta.effective_nullable(value_class) || is_option_type(value_ty),
                value_meta.effective_ref(value_class),
            )?;
            return Ok(quote! {
                fory_core::serializer::codec::MapSerializerCodec<
                    #ty,
                    #key_ty,
                    #value_ty,
                    #key_codec,
                    #value_codec,
                    #nullable,
                    #track_ref
                >
            });
        }
    }

    if let Type::Array(array) = ty {
        if meta.encoding.is_some() {
            return Err(syn::Error::new_spanned(
                ty,
                "encoding is only valid on integer values",
            ));
        }
        if meta.list.is_some() {
            return Err(syn::Error::new_spanned(
                ty,
                "list(...) config is only valid for Vec fields",
            ));
        }
        if meta.map.is_some() {
            return Err(syn::Error::new_spanned(
                ty,
                "map(...) config is only valid for HashMap fields",
            ));
        }
        if meta.array {
            return Err(syn::Error::new_spanned(
                ty,
                "array config is only valid for Vec fields",
            ));
        }
        if meta.bytes {
            return Err(syn::Error::new_spanned(
                ty,
                "bytes config is only valid for Vec<u8> fields",
            ));
        }
        if !is_primitive_array_type(ty) {
            let elem_ty = array.elem.as_ref();
            let elem_meta = ForyFieldMeta::default();
            let elem_class = classify_field_type(elem_ty);
            let elem_codec = codec_type_for(
                elem_ty,
                &elem_meta,
                elem_meta.effective_nullable(elem_class) || is_option_type(elem_ty),
                elem_meta.effective_ref(elem_class),
            )?;
            return Ok(quote! {
                fory_core::serializer::codec::CollectionSerializerCodec<
                    #ty,
                    #elem_ty,
                    #elem_codec,
                    { fory_core::type_id::TypeId::LIST as u8 },
                    #nullable,
                    #track_ref
                >
            });
        }
    }

    if is_exact_any(ty, "Box") {
        return Ok(quote! { fory_core::serializer::codec::AnyBoxCodec<#nullable, #track_ref> });
    }
    if is_exact_any(ty, "Rc") {
        return Ok(quote! { fory_core::serializer::codec::AnyRcCodec<#nullable, #track_ref> });
    }
    if is_exact_any(ty, "Arc") {
        return Ok(quote! { fory_core::serializer::codec::AnyArcCodec<#nullable, #track_ref> });
    }

    if let Some((_, trait_name)) = is_box_dyn_trait(ty) {
        let codec_ident = format_ident!("{}BoxCodec", trait_name);
        return Ok(quote! { #codec_ident<#nullable, #track_ref> });
    }
    if let Some((_, trait_name)) = is_rc_dyn_trait(ty) {
        let codec_ident = format_ident!("{}RcCodec", trait_name);
        return Ok(quote! { #codec_ident<#nullable, #track_ref> });
    }
    if let Some((_, trait_name)) = is_arc_dyn_trait(ty) {
        let codec_ident = format_ident!("{}ArcCodec", trait_name);
        return Ok(quote! { #codec_ident<#nullable, #track_ref> });
    }

    if meta.list.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            "list(...) config is only valid for Vec fields",
        ));
    }
    if meta.map.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            "map(...) config is only valid for HashMap fields",
        ));
    }
    if meta.array {
        return Err(syn::Error::new_spanned(
            ty,
            "array config is only valid for Vec fields",
        ));
    }
    if meta.bytes {
        return Err(syn::Error::new_spanned(
            ty,
            "bytes config is only valid for Vec<u8> fields",
        ));
    }

    if let Some(codec) = integer_codec_type(ty, meta, nullable, track_ref) {
        return Ok(codec);
    }

    Ok(quote! { fory_core::serializer::codec::SerializerCodec<#ty, #nullable, #track_ref> })
}

fn is_vec_type(ty: &Type) -> bool {
    matches!(type_name_and_args(ty), Some((name, _)) if name == "Vec")
}

fn integer_codec_type(
    ty: &Type,
    meta: &ForyFieldMeta,
    nullable: bool,
    track_ref: bool,
) -> Option<TokenStream> {
    let type_name = ty.to_token_stream().to_string().replace(' ', "");
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
        _ => {
            if meta.encoding.is_some() {
                Some(quote! {
                    compile_error!("encoding is only valid for i32, i64, u32, and u64 fields")
                })
            } else {
                None
            }
        }
    }
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

fn vec_element_type_name(ty: &Type) -> Option<String> {
    Some(ty.to_token_stream().to_string().replace(' ', ""))
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

fn is_container_type(ty: &Type) -> bool {
    if matches!(ty, Type::Array(_) | Type::Tuple(_)) {
        return true;
    }
    let Some((name, _)) = type_name_and_args(ty) else {
        return false;
    };
    matches!(
        name.as_str(),
        "Vec"
            | "VecDeque"
            | "LinkedList"
            | "HashSet"
            | "BTreeSet"
            | "BinaryHeap"
            | "HashMap"
            | "BTreeMap"
    )
}

fn contains_custom_trait_object(ty: &Type) -> bool {
    if !is_exact_any(ty, "Box") && is_box_dyn_trait(ty).is_some() {
        return true;
    }
    if is_rc_dyn_trait(ty).is_some() || is_arc_dyn_trait(ty).is_some() {
        return true;
    }
    if let Some(inner) = extract_option_inner_type(ty) {
        return contains_custom_trait_object(&inner);
    }
    if let Type::Array(array) = ty {
        return contains_custom_trait_object(array.elem.as_ref());
    }
    let Some((_, Some(args))) = type_name_and_args(ty) else {
        return false;
    };
    args.iter().any(|arg| {
        if let GenericArgument::Type(ty) = arg {
            contains_custom_trait_object(ty)
        } else {
            false
        }
    })
}

fn contains_exact_any_object(ty: &Type) -> bool {
    if is_exact_any(ty, "Box") || is_exact_any(ty, "Rc") || is_exact_any(ty, "Arc") {
        return true;
    }
    if let Some(inner) = extract_option_inner_type(ty) {
        return contains_exact_any_object(&inner);
    }
    if let Type::Array(array) = ty {
        return contains_exact_any_object(array.elem.as_ref());
    }
    let Some((_, Some(args))) = type_name_and_args(ty) else {
        return false;
    };
    args.iter().any(|arg| {
        if let GenericArgument::Type(ty) = arg {
            contains_exact_any_object(ty)
        } else {
            false
        }
    })
}

fn field_type_expr_for(ty: &Type, nullable: bool, track_ref: bool) -> syn::Result<TokenStream> {
    if let Some(inner) = extract_option_inner_type(ty) {
        return field_type_expr_for(&inner, true, track_ref);
    }

    if let Type::Array(array) = ty {
        let type_id = get_type_id_by_type_ast(ty);
        if fory_core::type_id::PRIMITIVE_ARRAY_TYPES.contains(&type_id) {
            return Ok(field_type_literal(type_id, nullable, track_ref, Vec::new()));
        }
        let elem_ty = array.elem.as_ref();
        let elem_type = nested_field_type_expr(elem_ty)?;
        return Ok(field_type_literal(
            fory_core::type_id::TypeId::LIST as u32,
            nullable,
            track_ref,
            vec![elem_type],
        ));
    }

    if matches!(ty, Type::Tuple(_)) {
        return Ok(field_type_literal(
            fory_core::type_id::TypeId::LIST as u32,
            nullable,
            track_ref,
            vec![quote! {
                fory_core::meta::FieldType::new(
                    fory_core::type_id::TypeId::UNKNOWN as u32,
                    true,
                    Vec::new(),
                )
            }],
        ));
    }

    if let Some((name, Some(args))) = type_name_and_args(ty) {
        match name.as_str() {
            "Vec" => {
                let elem_ty = single_type_arg(args, ty, "Vec")?;
                let elem_type = nested_field_type_expr(elem_ty)?;
                return Ok(field_type_literal(
                    fory_core::type_id::TypeId::LIST as u32,
                    nullable,
                    track_ref,
                    vec![elem_type],
                ));
            }
            "VecDeque" | "LinkedList" => {
                let elem_ty = single_type_arg(args, ty, &name)?;
                let elem_type = nested_field_type_expr(elem_ty)?;
                return Ok(field_type_literal(
                    fory_core::type_id::TypeId::LIST as u32,
                    nullable,
                    track_ref,
                    vec![elem_type],
                ));
            }
            "HashSet" | "BTreeSet" | "BinaryHeap" => {
                let elem_ty = single_type_arg(args, ty, &name)?;
                let elem_type = nested_field_type_expr(elem_ty)?;
                return Ok(field_type_literal(
                    fory_core::type_id::TypeId::SET as u32,
                    nullable,
                    track_ref,
                    vec![elem_type],
                ));
            }
            "HashMap" | "BTreeMap" => {
                let (key_ty, value_ty) = two_type_args(args, ty, &name)?;
                let key_type = nested_field_type_expr(key_ty)?;
                let value_type = nested_field_type_expr(value_ty)?;
                return Ok(field_type_literal(
                    fory_core::type_id::TypeId::MAP as u32,
                    nullable,
                    track_ref,
                    vec![key_type, value_type],
                ));
            }
            _ => {}
        }
    }

    Ok(serializer_field_type_expr(ty, nullable, track_ref))
}

fn nested_field_type_expr(ty: &Type) -> syn::Result<TokenStream> {
    let meta = ForyFieldMeta::default();
    let class = classify_field_type(ty);
    let nullable = meta.effective_nullable(class) || is_option_type(ty);
    let track_ref = meta.effective_ref(class);
    let codec_ty = codec_type_for(ty, &meta, nullable, track_ref)?;
    Ok(quote! {
        <#codec_ty as fory_core::serializer::codec::Codec<#ty>>::field_type(type_resolver)?
    })
}

fn field_type_literal(
    type_id: u32,
    nullable: bool,
    track_ref: bool,
    generics: Vec<TokenStream>,
) -> TokenStream {
    quote! {
        fory_core::meta::FieldType::new_with_ref(
            #type_id,
            #nullable,
            #track_ref,
            vec![#(#generics),*],
        )
    }
}

fn serializer_field_type_expr(ty: &Type, nullable: bool, track_ref: bool) -> TokenStream {
    quote! {
        <fory_core::serializer::codec::SerializerCodec<#ty, #nullable, #track_ref>
            as fory_core::serializer::codec::Codec<#ty>>::field_type(type_resolver)?
    }
}

fn serializer_ref_mode_for_field(field: &syn::Field) -> TokenStream {
    let (nullable, track_ref) = serializer_field_markers(field);
    if track_ref {
        quote! { fory_core::RefMode::Tracking }
    } else if nullable {
        quote! { fory_core::RefMode::NullOnly }
    } else {
        quote! { fory_core::RefMode::None }
    }
}

fn serializer_field_can_use_data_path(field: &syn::Field) -> bool {
    let (nullable, track_ref) = serializer_field_markers(field);
    !nullable && !track_ref
}

fn serializer_field_markers(field: &syn::Field) -> (bool, bool) {
    let meta = parse_field_meta(field).unwrap_or_default();
    let type_class = classify_field_type(&field.ty);
    let nullable = meta.effective_nullable(type_class) || is_option_type(&field.ty);
    let track_ref = meta.effective_ref(type_class);
    (nullable, track_ref)
}

fn is_serializer_backed_collection(name: &str) -> bool {
    matches!(name, "VecDeque" | "LinkedList" | "BTreeSet" | "BinaryHeap")
}

fn primitive_array_type_id_for_vec_element(ty: &Type) -> syn::Result<TokenStream> {
    let type_name = ty.to_token_stream().to_string().replace(' ', "");
    match type_name.as_str() {
        "bool" => Ok(quote! { { fory_core::type_id::TypeId::BOOL_ARRAY as u8 } }),
        "i8" => Ok(quote! { { fory_core::type_id::TypeId::INT8_ARRAY as u8 } }),
        "i16" => Ok(quote! { { fory_core::type_id::TypeId::INT16_ARRAY as u8 } }),
        "i32" => Ok(quote! { { fory_core::type_id::TypeId::INT32_ARRAY as u8 } }),
        "i64" => Ok(quote! { { fory_core::type_id::TypeId::INT64_ARRAY as u8 } }),
        "u8" => Ok(quote! { { fory_core::type_id::TypeId::UINT8_ARRAY as u8 } }),
        "u16" => Ok(quote! { { fory_core::type_id::TypeId::UINT16_ARRAY as u8 } }),
        "u32" => Ok(quote! { { fory_core::type_id::TypeId::UINT32_ARRAY as u8 } }),
        "u64" => Ok(quote! { { fory_core::type_id::TypeId::UINT64_ARRAY as u8 } }),
        "float16" | "Float16" => Ok(quote! { { fory_core::type_id::TypeId::FLOAT16_ARRAY as u8 } }),
        "bfloat16" | "BFloat16" => {
            Ok(quote! { { fory_core::type_id::TypeId::BFLOAT16_ARRAY as u8 } })
        }
        "f32" => Ok(quote! { { fory_core::type_id::TypeId::FLOAT32_ARRAY as u8 } }),
        "f64" => Ok(quote! { { fory_core::type_id::TypeId::FLOAT64_ARRAY as u8 } }),
        _ => Err(syn::Error::new_spanned(
            ty,
            "array requires a non-null number or bool Vec element type",
        )),
    }
}

fn serializer_backed_collection_type_id(name: &str) -> TokenStream {
    match name {
        "BTreeSet" | "BinaryHeap" => quote! { { fory_core::type_id::TypeId::SET as u8 } },
        _ => quote! { { fory_core::type_id::TypeId::LIST as u8 } },
    }
}

fn validate_serializer_backed_collection_meta(
    ty: &Type,
    meta: &ForyFieldMeta,
    name: &str,
) -> syn::Result<()> {
    if meta.encoding.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            "encoding is only valid on integer values",
        ));
    }
    if meta.list.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            format!("list(...) config is only valid for Vec fields, not {name}"),
        ));
    }
    if meta.map.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            format!("map(...) config is only valid for map fields, not {name}"),
        ));
    }
    if meta.array {
        return Err(syn::Error::new_spanned(
            ty,
            format!("array config is only valid for Vec fields, not {name}"),
        ));
    }
    if meta.bytes {
        return Err(syn::Error::new_spanned(
            ty,
            format!("bytes config is only valid for Vec<u8> fields, not {name}"),
        ));
    }
    Ok(())
}

fn is_serializer_backed_map(name: &str) -> bool {
    matches!(name, "BTreeMap")
}

fn validate_serializer_backed_map_meta(
    ty: &Type,
    meta: &ForyFieldMeta,
    name: &str,
) -> syn::Result<()> {
    if meta.encoding.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            "encoding is only valid on integer values; use map(key(...), value(...)) for map entries",
        ));
    }
    if meta.list.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            format!("list(...) config is only valid for list fields, not {name}"),
        ));
    }
    if meta.map.is_some() {
        return Err(syn::Error::new_spanned(
            ty,
            format!("map(...) config is currently supported only for HashMap fields, not {name}"),
        ));
    }
    if meta.array {
        return Err(syn::Error::new_spanned(
            ty,
            format!("array config is only valid for Vec fields, not {name}"),
        ));
    }
    if meta.bytes {
        return Err(syn::Error::new_spanned(
            ty,
            format!("bytes config is only valid for Vec<u8> fields, not {name}"),
        ));
    }
    Ok(())
}

fn is_exact_any(ty: &Type, owner: &str) -> bool {
    let Some((name, Some(args))) = type_name_and_args(ty) else {
        return false;
    };
    if name != owner {
        return false;
    }
    let Some(GenericArgument::Type(Type::TraitObject(trait_obj))) = args.first() else {
        return false;
    };
    trait_obj.bounds.iter().any(|bound| {
        if let syn::TypeParamBound::Trait(trait_bound) = bound {
            trait_bound
                .path
                .segments
                .last()
                .is_some_and(|seg| seg.ident == "Any")
        } else {
            false
        }
    })
}

fn is_primitive_array_type(ty: &Type) -> bool {
    fory_core::type_id::PRIMITIVE_ARRAY_TYPES.contains(&get_type_id_by_type_ast(ty))
}

pub(crate) fn default_expr_for_type(ty: &Type) -> TokenStream {
    if let Some((_, trait_name)) = is_rc_dyn_trait(ty) {
        let wrapper_ty = format_ident!("{}Rc", trait_name);
        let trait_ident = format_ident!("{}", trait_name);
        return quote! {
            {
                let wrapper = <#wrapper_ty as fory_core::ForyDefault>::fory_default();
                std::rc::Rc::<dyn #trait_ident>::from(wrapper)
            }
        };
    }
    if let Some((_, trait_name)) = is_arc_dyn_trait(ty) {
        let wrapper_ty = format_ident!("{}Arc", trait_name);
        let trait_ident = format_ident!("{}", trait_name);
        return quote! {
            {
                let wrapper = <#wrapper_ty as fory_core::ForyDefault>::fory_default();
                std::sync::Arc::<dyn #trait_ident>::from(wrapper)
            }
        };
    }
    quote! { <#ty as fory_core::ForyDefault>::fory_default() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use syn::parse_quote;

    #[test]
    fn array_codec_accepts_numeric_vec() {
        let ty: Type = parse_quote! { Vec<i32> };
        let meta = ForyFieldMeta {
            array: true,
            ..Default::default()
        };

        assert!(codec_type_for(&ty, &meta, false, false).is_ok());
    }

    #[test]
    fn array_codec_rejects_non_numeric_vec() {
        let ty: Type = parse_quote! { Vec<String> };
        let meta = ForyFieldMeta {
            array: true,
            ..Default::default()
        };

        let err = codec_type_for(&ty, &meta, false, false).unwrap_err();
        assert!(err
            .to_string()
            .contains("array requires a non-null number or bool Vec element type"));
    }

    #[test]
    fn array_codec_rejects_nullable_elements() {
        let ty: Type = parse_quote! { Vec<Option<i32>> };
        let meta = ForyFieldMeta {
            array: true,
            ..Default::default()
        };

        let err = codec_type_for(&ty, &meta, false, false).unwrap_err();
        assert!(err
            .to_string()
            .contains("array requires a non-null number or bool Vec element type"));
    }

    #[test]
    fn array_codec_rejects_map_keys() {
        let ty: Type = parse_quote! { HashMap<Vec<i32>, String> };
        let meta = ForyFieldMeta {
            map: Some(super::super::field_meta::ForyMapMeta {
                key: Some(Box::new(ForyFieldMeta {
                    array: true,
                    ..Default::default()
                })),
                value: None,
            }),
            ..Default::default()
        };

        let err = codec_type_for(&ty, &meta, false, false).unwrap_err();
        assert!(err
            .to_string()
            .contains("array schema is not valid for map keys"));
    }

    #[test]
    fn bytes_codec_accepts_vec_u8() {
        let ty: Type = parse_quote! { Vec<u8> };
        let meta = ForyFieldMeta {
            bytes: true,
            ..Default::default()
        };

        assert!(codec_type_for(&ty, &meta, false, false).is_ok());
    }

    #[test]
    fn bytes_codec_rejects_non_u8_vec() {
        let ty: Type = parse_quote! { Vec<i32> };
        let meta = ForyFieldMeta {
            bytes: true,
            ..Default::default()
        };

        let err = codec_type_for(&ty, &meta, false, false).unwrap_err();
        assert!(err.to_string().contains("bytes schema requires Vec<u8>"));
    }
}
