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

//! Internal field codecs used by macro-generated serializers.
//!
//! User-owned custom serialization belongs in [`crate::Serializer`]. Codecs are
//! Fory-owned building blocks that allow generated code to apply field-local and
//! nested collection configuration without creating wrapper value types.

use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::meta::FieldType;
use crate::resolver::{RefFlag, RefMode, TypeResolver};
use crate::serializer::{ForyDefault, Serializer};
use crate::type_id::{self, need_to_write_type_for_field, TypeId, SIZE_OF_REF_AND_TYPE, UNKNOWN};
use std::any::Any;
use std::collections::HashMap;
use std::hash::Hash;
use std::marker::PhantomData;
use std::rc::Rc;
use std::sync::Arc;

const TRACKING_REF: u8 = 0b1;
const HAS_NULL: u8 = 0b10;
const DECL_ELEMENT_TYPE: u8 = 0b100;
const IS_SAME_TYPE: u8 = 0b1000;

const TRACKING_KEY_REF: u8 = 0b1;
const KEY_NULL: u8 = 0b10;
const DECL_KEY_TYPE: u8 = 0b100;
const TRACKING_VALUE_REF: u8 = 0b1000;
const VALUE_NULL: u8 = 0b10000;
const DECL_VALUE_TYPE: u8 = 0b100000;

const MAX_CHUNK_SIZE: u8 = 255;

#[inline(always)]
pub fn field_ref_mode(field_type: &FieldType) -> RefMode {
    if field_type.track_ref {
        RefMode::Tracking
    } else if crate::serializer::util::field_need_write_ref_into(
        field_type.type_id,
        field_type.nullable,
    ) {
        RefMode::NullOnly
    } else {
        RefMode::None
    }
}

#[inline(always)]
fn field_read_type_info<T: Serializer>(context: &ReadContext, field_type: &FieldType) -> bool {
    if context.is_compatible() {
        crate::serializer::util::field_need_read_type_info(field_type.type_id)
    } else {
        T::fory_is_polymorphic()
    }
}

#[inline(always)]
fn field_write_type_info<T: Serializer>(context: &WriteContext) -> bool {
    if context.is_compatible() {
        need_to_write_type_for_field(T::fory_static_type_id())
    } else {
        T::fory_is_polymorphic()
    }
}

#[inline(always)]
fn serializer_static_field_type_id<T: Serializer>() -> u32 {
    let type_id = T::fory_static_type_id() as u32;
    if type_id == TypeId::TYPED_UNION as u32 || type_id == TypeId::NAMED_UNION as u32 {
        TypeId::UNION as u32
    } else {
        type_id
    }
}

#[inline(always)]
fn serializer_ref_mode<T: Serializer, const NULLABLE: bool, const TRACK_REF: bool>() -> RefMode {
    if TRACK_REF {
        RefMode::Tracking
    } else if crate::serializer::util::field_need_write_ref_into(
        serializer_static_field_type_id::<T>(),
        NULLABLE,
    ) {
        RefMode::NullOnly
    } else {
        RefMode::None
    }
}

#[inline(always)]
fn serializer_read_type_info<T: Serializer>(context: &ReadContext) -> bool {
    if context.is_compatible() {
        crate::serializer::util::field_need_read_type_info(serializer_static_field_type_id::<T>())
    } else {
        T::fory_is_polymorphic()
    }
}

#[inline(always)]
fn codec_read_type_info<T, C>(context: &ReadContext, field_type: &FieldType) -> bool
where
    T: 'static,
    C: Codec<T>,
{
    if context.is_compatible() {
        field_type.type_id == type_id::UNKNOWN
            || crate::serializer::util::field_need_read_type_info(field_type.type_id)
    } else {
        C::is_polymorphic()
    }
}

#[inline(always)]
fn codec_static_field_type_id<T, C>() -> u32
where
    T: 'static,
    C: Codec<T>,
{
    let type_id = C::static_type_id() as u32;
    if type_id == TypeId::TYPED_UNION as u32 || type_id == TypeId::NAMED_UNION as u32 {
        TypeId::UNION as u32
    } else {
        type_id
    }
}

#[inline(always)]
fn codec_read_type_info_static<T, C>(context: &ReadContext) -> bool
where
    T: 'static,
    C: Codec<T>,
{
    if context.is_compatible() {
        let type_id = codec_static_field_type_id::<T, C>();
        type_id == type_id::UNKNOWN || crate::serializer::util::field_need_read_type_info(type_id)
    } else {
        C::is_polymorphic()
    }
}

#[inline(always)]
fn codec_write_type_info<T, C>(context: &WriteContext) -> bool
where
    T: 'static,
    C: Codec<T>,
{
    if context.is_compatible() {
        need_to_write_type_for_field(C::static_type_id())
    } else {
        C::is_polymorphic()
    }
}

#[inline(always)]
fn same_numeric_family(local: u32, remote: u32) -> bool {
    matches!(
        (local, remote),
        (type_id::INT32, type_id::INT32 | type_id::VARINT32)
            | (type_id::VARINT32, type_id::INT32 | type_id::VARINT32)
            | (
                type_id::INT64,
                type_id::INT64 | type_id::VARINT64 | type_id::TAGGED_INT64
            )
            | (
                type_id::VARINT64,
                type_id::INT64 | type_id::VARINT64 | type_id::TAGGED_INT64
            )
            | (
                type_id::TAGGED_INT64,
                type_id::INT64 | type_id::VARINT64 | type_id::TAGGED_INT64
            )
            | (type_id::UINT32, type_id::UINT32 | type_id::VAR_UINT32)
            | (type_id::VAR_UINT32, type_id::UINT32 | type_id::VAR_UINT32)
            | (
                type_id::UINT64,
                type_id::UINT64 | type_id::VAR_UINT64 | type_id::TAGGED_UINT64
            )
            | (
                type_id::VAR_UINT64,
                type_id::UINT64 | type_id::VAR_UINT64 | type_id::TAGGED_UINT64
            )
            | (
                type_id::TAGGED_UINT64,
                type_id::UINT64 | type_id::VAR_UINT64 | type_id::TAGGED_UINT64
            )
    )
}

#[inline(always)]
fn collection_type_with_fallback_generics(type_id: u32) -> bool {
    type_id == type_id::LIST || type_id == type_id::SET || type_id == type_id::MAP
}

#[inline(always)]
pub fn field_types_compatible(local: &FieldType, remote: &FieldType) -> bool {
    if local.compatible_fingerprint() == remote.compatible_fingerprint() {
        return true;
    }
    if local.type_id == remote.type_id
        && collection_type_with_fallback_generics(local.type_id)
        && (local.generics.is_empty() || remote.generics.is_empty())
    {
        return true;
    }
    false
}

#[inline(always)]
fn generic_field_type<'a>(
    field_type: &'a FieldType,
    index: usize,
    owner: &str,
) -> Result<&'a FieldType, Error> {
    field_type.generics.get(index).ok_or_else(|| {
        Error::invalid_data(format!(
            "{owner} field metadata is missing generic type at index {index}"
        ))
    })
}

#[inline(always)]
fn field_type_for_serializer<T: Serializer>(
    type_resolver: &TypeResolver,
    nullable: bool,
    track_ref: bool,
) -> Result<FieldType, Error> {
    let mut type_id = T::fory_get_type_id(type_resolver)? as u32;
    let mut user_type_id = u32::MAX;
    if !type_id::is_internal_type(type_id) {
        let type_info = T::fory_get_type_info(type_resolver)?;
        type_id = type_info.get_type_id() as u32;
        user_type_id = type_info.get_user_type_id();
        if type_id == TypeId::TYPED_UNION as u32 || type_id == TypeId::NAMED_UNION as u32 {
            type_id = TypeId::UNION as u32;
            user_type_id = u32::MAX;
        }
    } else if type_id == TypeId::TYPED_UNION as u32 || type_id == TypeId::NAMED_UNION as u32 {
        type_id = TypeId::UNION as u32;
    }
    Ok(FieldType::new_with_user_type_id(
        type_id,
        user_type_id,
        nullable,
        track_ref,
        Vec::new(),
    ))
}

pub trait Codec<T: 'static>: 'static {
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error>;

    #[inline(always)]
    fn reserved_space() -> usize {
        std::mem::size_of::<T>()
    }

    fn write_field(value: &T, context: &mut WriteContext) -> Result<(), Error>;

    fn read_field(context: &mut ReadContext) -> Result<T, Error>;

    fn read_compatible(
        context: &mut ReadContext,
        local_field_type: &FieldType,
        remote_field_type: &FieldType,
    ) -> Result<Option<T>, Error> {
        if !field_types_compatible(local_field_type, remote_field_type) {
            return Ok(None);
        }
        Self::read_field_with_type(context, remote_field_type).map(Some)
    }

    fn write_data(value: &T, context: &mut WriteContext) -> Result<(), Error>;

    fn read_data(context: &mut ReadContext) -> Result<T, Error>;

    #[inline(always)]
    fn read_data_with_type(
        context: &mut ReadContext,
        _remote_data_type: &FieldType,
    ) -> Result<T, Error> {
        Self::read_data(context)
    }

    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<T, Error>;

    fn write_with_mode(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error>;

    fn read_with_mode(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<T, Error>;

    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: std::rc::Rc<crate::TypeInfo>,
    ) -> Result<T, Error>;

    fn default_value() -> T;

    fn write_type_info(context: &mut WriteContext) -> Result<(), Error>;

    fn read_type_info(context: &mut ReadContext) -> Result<(), Error>;

    #[inline(always)]
    fn read_type_info_as_field_type(context: &mut ReadContext) -> Result<FieldType, Error> {
        Self::read_type_info(context)?;
        Self::field_type(context.get_type_resolver())
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::UNKNOWN
    }

    #[inline(always)]
    fn is_option() -> bool {
        false
    }

    #[inline(always)]
    fn is_none(_value: &T) -> bool {
        false
    }

    #[inline(always)]
    fn is_polymorphic() -> bool {
        false
    }

    #[inline(always)]
    fn is_shared_ref() -> bool {
        false
    }

    #[inline(always)]
    fn concrete_type_id(value: &T) -> std::any::TypeId {
        let _ = value;
        std::any::TypeId::of::<T>()
    }
}

pub struct SerializerCodec<T, const NULLABLE: bool, const TRACK_REF: bool>(PhantomData<T>);

impl<T, const NULLABLE: bool, const TRACK_REF: bool> Codec<T>
    for SerializerCodec<T, NULLABLE, TRACK_REF>
where
    T: Serializer + ForyDefault,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        field_type_for_serializer::<T>(type_resolver, NULLABLE, TRACK_REF)
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        T::fory_reserved_space() + SIZE_OF_REF_AND_TYPE
    }

    #[inline(always)]
    fn write_field(value: &T, context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write(
            value,
            context,
            serializer_ref_mode::<T, NULLABLE, TRACK_REF>(),
            field_write_type_info::<T>(context),
            false,
        )
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<T, Error> {
        T::fory_read(
            context,
            serializer_ref_mode::<T, NULLABLE, TRACK_REF>(),
            serializer_read_type_info::<T>(context),
        )
    }

    #[inline(always)]
    fn write_data(value: &T, context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_data_generic(value, context, false)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<T, Error> {
        T::fory_read_data(context)
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<T, Error> {
        T::fory_read(
            context,
            field_ref_mode(remote_field_type),
            field_read_type_info::<T>(context, remote_field_type),
        )
    }

    #[inline(always)]
    fn write_with_mode(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        T::fory_write(value, context, ref_mode, write_type_info, has_generics)
    }

    #[inline(always)]
    fn read_with_mode(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<T, Error> {
        T::fory_read(context, ref_mode, read_type_info)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: std::rc::Rc<crate::TypeInfo>,
    ) -> Result<T, Error> {
        T::fory_read_with_type_info(context, ref_mode, type_info)
    }

    #[inline(always)]
    fn default_value() -> T {
        T::fory_default()
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        T::fory_static_type_id()
    }

    #[inline(always)]
    fn is_option() -> bool {
        T::fory_is_option()
    }

    #[inline(always)]
    fn is_none(value: &T) -> bool {
        value.fory_is_none()
    }

    #[inline(always)]
    fn is_polymorphic() -> bool {
        T::fory_is_polymorphic()
    }

    #[inline(always)]
    fn is_shared_ref() -> bool {
        T::fory_is_shared_ref()
    }

    #[inline(always)]
    fn concrete_type_id(value: &T) -> std::any::TypeId {
        value.fory_concrete_type_id()
    }
}

pub struct CollectionSerializerCodec<
    T,
    E,
    EC,
    const TYPE_ID: u8,
    const NULLABLE: bool,
    const TRACK_REF: bool,
>(PhantomData<(T, E, EC)>);

impl<T, E, EC, const TYPE_ID: u8, const NULLABLE: bool, const TRACK_REF: bool> Codec<T>
    for CollectionSerializerCodec<T, E, EC, TYPE_ID, NULLABLE, TRACK_REF>
where
    T: Serializer + ForyDefault,
    E: 'static,
    EC: Codec<E>,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        Ok(FieldType::new_with_ref(
            TYPE_ID as u32,
            NULLABLE,
            TRACK_REF,
            vec![EC::field_type(type_resolver)?],
        ))
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        T::fory_reserved_space() + SIZE_OF_REF_AND_TYPE
    }

    #[inline(always)]
    fn write_field(value: &T, context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write(
            value,
            context,
            serializer_ref_mode::<T, NULLABLE, TRACK_REF>(),
            field_write_type_info::<T>(context),
            true,
        )
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<T, Error> {
        T::fory_read(
            context,
            serializer_ref_mode::<T, NULLABLE, TRACK_REF>(),
            serializer_read_type_info::<T>(context),
        )
    }

    #[inline(always)]
    fn write_data(value: &T, context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_data_generic(value, context, true)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<T, Error> {
        T::fory_read_data(context)
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<T, Error> {
        T::fory_read(
            context,
            field_ref_mode(remote_field_type),
            field_read_type_info::<T>(context, remote_field_type),
        )
    }

    #[inline(always)]
    fn write_with_mode(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        _has_generics: bool,
    ) -> Result<(), Error> {
        T::fory_write(value, context, ref_mode, write_type_info, true)
    }

    #[inline(always)]
    fn read_with_mode(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<T, Error> {
        T::fory_read(context, ref_mode, read_type_info)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: std::rc::Rc<crate::TypeInfo>,
    ) -> Result<T, Error> {
        T::fory_read_with_type_info(context, ref_mode, type_info)
    }

    #[inline(always)]
    fn default_value() -> T {
        T::fory_default()
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(TYPE_ID);
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        let remote = context.reader.read_u8()?;
        if remote != TYPE_ID {
            return Err(Error::type_mismatch(TYPE_ID as u32, remote as u32));
        }
        Ok(())
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::try_from(TYPE_ID).unwrap_or(TypeId::UNKNOWN)
    }
}

pub struct MapSerializerCodec<T, K, V, KC, VC, const NULLABLE: bool, const TRACK_REF: bool>(
    PhantomData<(T, K, V, KC, VC)>,
);

impl<T, K, V, KC, VC, const NULLABLE: bool, const TRACK_REF: bool> Codec<T>
    for MapSerializerCodec<T, K, V, KC, VC, NULLABLE, TRACK_REF>
where
    T: Serializer + ForyDefault,
    K: 'static,
    V: 'static,
    KC: Codec<K>,
    VC: Codec<V>,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        Ok(FieldType::new_with_ref(
            TypeId::MAP as u32,
            NULLABLE,
            TRACK_REF,
            vec![
                KC::field_type(type_resolver)?,
                VC::field_type(type_resolver)?,
            ],
        ))
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        T::fory_reserved_space() + SIZE_OF_REF_AND_TYPE
    }

    #[inline(always)]
    fn write_field(value: &T, context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write(
            value,
            context,
            serializer_ref_mode::<T, NULLABLE, TRACK_REF>(),
            field_write_type_info::<T>(context),
            true,
        )
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<T, Error> {
        T::fory_read(
            context,
            serializer_ref_mode::<T, NULLABLE, TRACK_REF>(),
            serializer_read_type_info::<T>(context),
        )
    }

    #[inline(always)]
    fn write_data(value: &T, context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_data_generic(value, context, true)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<T, Error> {
        T::fory_read_data(context)
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<T, Error> {
        T::fory_read(
            context,
            field_ref_mode(remote_field_type),
            field_read_type_info::<T>(context, remote_field_type),
        )
    }

    #[inline(always)]
    fn write_with_mode(
        value: &T,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        _has_generics: bool,
    ) -> Result<(), Error> {
        T::fory_write(value, context, ref_mode, write_type_info, true)
    }

    #[inline(always)]
    fn read_with_mode(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<T, Error> {
        T::fory_read(context, ref_mode, read_type_info)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: std::rc::Rc<crate::TypeInfo>,
    ) -> Result<T, Error> {
        T::fory_read_with_type_info(context, ref_mode, type_info)
    }

    #[inline(always)]
    fn default_value() -> T {
        T::fory_default()
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(TypeId::MAP as u8);
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        let remote = context.reader.read_u8()? as u32;
        if remote != TypeId::MAP as u32 {
            return Err(Error::type_mismatch(TypeId::MAP as u32, remote));
        }
        Ok(())
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::MAP
    }
}

pub struct OptionCodec<T, C, const TRACK_REF: bool>(PhantomData<(T, C)>);

impl<T, C, const TRACK_REF: bool> Codec<Option<T>> for OptionCodec<T, C, TRACK_REF>
where
    T: 'static,
    C: Codec<T>,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        let mut field_type = C::field_type(type_resolver)?;
        field_type.nullable = true;
        field_type.track_ref = TRACK_REF;
        Ok(field_type)
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        C::reserved_space() + 1
    }

    #[inline(always)]
    fn write_field(value: &Option<T>, context: &mut WriteContext) -> Result<(), Error> {
        Self::write_with_mode(
            value,
            context,
            if TRACK_REF {
                RefMode::Tracking
            } else {
                RefMode::NullOnly
            },
            codec_write_type_info::<T, C>(context),
            false,
        )
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<Option<T>, Error> {
        Self::read_with_mode(
            context,
            if TRACK_REF {
                RefMode::Tracking
            } else {
                RefMode::NullOnly
            },
            codec_read_type_info_static::<T, C>(context),
        )
    }

    #[inline(always)]
    fn write_data(value: &Option<T>, context: &mut WriteContext) -> Result<(), Error> {
        let value = value.as_ref().ok_or_else(|| {
            Error::invalid_data("Option::None cannot be written as non-null data")
        })?;
        C::write_data(value, context)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Option<T>, Error> {
        Ok(Some(C::read_data(context)?))
    }

    #[inline(always)]
    fn read_data_with_type(
        context: &mut ReadContext,
        remote_data_type: &FieldType,
    ) -> Result<Option<T>, Error> {
        Ok(Some(C::read_data_with_type(context, remote_data_type)?))
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<Option<T>, Error> {
        let ref_mode = field_ref_mode(remote_field_type);
        let read_type_info = codec_read_type_info::<T, C>(context, remote_field_type);
        if ref_mode != RefMode::None {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Ok(None);
            }
            if ref_mode == RefMode::Tracking {
                context.reader.move_back(1);
                return Ok(Some(C::read_with_mode(
                    context,
                    RefMode::Tracking,
                    read_type_info,
                )?));
            }
        }
        if read_type_info || C::is_polymorphic() || C::is_shared_ref() {
            Ok(Some(C::read_with_mode(
                context,
                RefMode::None,
                read_type_info,
            )?))
        } else {
            Ok(Some(C::read_data_with_type(context, remote_field_type)?))
        }
    }

    #[inline(always)]
    fn write_with_mode(
        value: &Option<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        match ref_mode {
            RefMode::None => Self::write_data(value, context),
            RefMode::NullOnly => {
                if let Some(value) = value {
                    context.writer.write_i8(RefFlag::NotNullValue as i8);
                    C::write_with_mode(value, context, RefMode::None, write_type_info, has_generics)
                } else {
                    context.writer.write_i8(RefFlag::Null as i8);
                    Ok(())
                }
            }
            RefMode::Tracking => {
                if let Some(value) = value {
                    C::write_with_mode(
                        value,
                        context,
                        RefMode::Tracking,
                        write_type_info,
                        has_generics,
                    )
                } else {
                    context.writer.write_i8(RefFlag::Null as i8);
                    Ok(())
                }
            }
        }
    }

    #[inline(always)]
    fn read_with_mode(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Option<T>, Error> {
        match ref_mode {
            RefMode::None => Ok(Some(C::read_with_mode(
                context,
                RefMode::None,
                read_type_info,
            )?)),
            RefMode::NullOnly => {
                let ref_flag = context.reader.read_i8()?;
                if ref_flag == RefFlag::Null as i8 {
                    return Ok(None);
                }
                Ok(Some(C::read_with_mode(
                    context,
                    RefMode::None,
                    read_type_info,
                )?))
            }
            RefMode::Tracking => {
                let ref_flag = context.reader.read_i8()?;
                if ref_flag == RefFlag::Null as i8 {
                    return Ok(None);
                }
                context.reader.move_back(1);
                Ok(Some(C::read_with_mode(
                    context,
                    RefMode::Tracking,
                    read_type_info,
                )?))
            }
        }
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: std::rc::Rc<crate::TypeInfo>,
    ) -> Result<Option<T>, Error> {
        match ref_mode {
            RefMode::None => Ok(Some(C::read_with_type_info(
                context,
                RefMode::None,
                type_info,
            )?)),
            RefMode::NullOnly => {
                let ref_flag = context.reader.read_i8()?;
                if ref_flag == RefFlag::Null as i8 {
                    return Ok(None);
                }
                Ok(Some(C::read_with_type_info(
                    context,
                    RefMode::None,
                    type_info,
                )?))
            }
            RefMode::Tracking => {
                let ref_flag = context.reader.read_i8()?;
                if ref_flag == RefFlag::Null as i8 {
                    return Ok(None);
                }
                context.reader.move_back(1);
                Ok(Some(C::read_with_type_info(
                    context,
                    RefMode::Tracking,
                    type_info,
                )?))
            }
        }
    }

    #[inline(always)]
    fn default_value() -> Option<T> {
        None
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        C::write_type_info(context)
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        C::read_type_info(context)
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        C::static_type_id()
    }

    #[inline(always)]
    fn is_option() -> bool {
        true
    }

    #[inline(always)]
    fn is_none(value: &Option<T>) -> bool {
        value.is_none()
    }

    #[inline(always)]
    fn is_polymorphic() -> bool {
        C::is_polymorphic()
    }

    #[inline(always)]
    fn is_shared_ref() -> bool {
        C::is_shared_ref()
    }
}

macro_rules! signed_int_codec {
    ($name:ident, $ty:ty, $default_type:expr, $fixed_type:expr, $tagged_type:expr, $write_fixed:ident, $read_fixed:ident, $write_var:ident, $read_var:ident, $write_tagged:ident, $read_tagged:ident) => {
        pub struct $name<const WIRE_TYPE_ID: u8, const NULLABLE: bool, const TRACK_REF: bool>;

        impl<const WIRE_TYPE_ID: u8, const NULLABLE: bool, const TRACK_REF: bool> Codec<$ty>
            for $name<WIRE_TYPE_ID, NULLABLE, TRACK_REF>
        {
            #[inline(always)]
            fn field_type(_: &TypeResolver) -> Result<FieldType, Error> {
                Ok(FieldType::new_with_ref(
                    WIRE_TYPE_ID as u32,
                    NULLABLE,
                    TRACK_REF,
                    Vec::new(),
                ))
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                std::mem::size_of::<$ty>() + 1
            }

            #[inline(always)]
            fn write_field(value: &$ty, context: &mut WriteContext) -> Result<(), Error> {
                if NULLABLE {
                    context.writer.write_i8(RefFlag::NotNullValue as i8);
                }
                Self::write_data(value, context)
            }

            #[inline(always)]
            fn read_field(context: &mut ReadContext) -> Result<$ty, Error> {
                if NULLABLE {
                    let ref_flag = context.reader.read_i8()?;
                    if ref_flag == RefFlag::Null as i8 {
                        return Ok(<$ty as ForyDefault>::fory_default());
                    }
                }
                Self::read_data(context)
            }

            #[inline(always)]
            fn write_data(value: &$ty, context: &mut WriteContext) -> Result<(), Error> {
                match WIRE_TYPE_ID as u32 {
                    x if x == $fixed_type => context.writer.$write_fixed(*value),
                    x if x == $tagged_type => context.writer.$write_tagged(*value),
                    _ => context.writer.$write_var(*value),
                }
                Ok(())
            }

            #[inline(always)]
            fn read_data(context: &mut ReadContext) -> Result<$ty, Error> {
                match WIRE_TYPE_ID as u32 {
                    x if x == $fixed_type => context.reader.$read_fixed(),
                    x if x == $tagged_type => context.reader.$read_tagged(),
                    _ => context.reader.$read_var(),
                }
            }

            #[inline(always)]
            fn read_data_with_type(
                context: &mut ReadContext,
                remote_data_type: &FieldType,
            ) -> Result<$ty, Error> {
                match remote_data_type.type_id {
                    x if x == $fixed_type => context.reader.$read_fixed(),
                    x if x == $tagged_type => context.reader.$read_tagged(),
                    _ => context.reader.$read_var(),
                }
            }

            #[inline(always)]
            fn read_field_with_type(
                context: &mut ReadContext,
                remote_field_type: &FieldType,
            ) -> Result<$ty, Error> {
                if field_ref_mode(remote_field_type) != RefMode::None {
                    let ref_flag = context.reader.read_i8()?;
                    if ref_flag == RefFlag::Null as i8 {
                        return Ok(<$ty as ForyDefault>::fory_default());
                    }
                }
                match remote_field_type.type_id {
                    x if x == $fixed_type => context.reader.$read_fixed(),
                    x if x == $tagged_type => context.reader.$read_tagged(),
                    _ => context.reader.$read_var(),
                }
            }

            #[inline(always)]
            fn write_with_mode(
                value: &$ty,
                context: &mut WriteContext,
                ref_mode: RefMode,
                write_type_info: bool,
                _has_generics: bool,
            ) -> Result<(), Error> {
                if ref_mode != RefMode::None {
                    context.writer.write_i8(RefFlag::NotNullValue as i8);
                }
                if write_type_info {
                    Self::write_type_info(context)?;
                }
                Self::write_data(value, context)
            }

            #[inline(always)]
            fn read_with_mode(
                context: &mut ReadContext,
                ref_mode: RefMode,
                read_type_info: bool,
            ) -> Result<$ty, Error> {
                if ref_mode != RefMode::None {
                    let ref_flag = context.reader.read_i8()?;
                    if ref_flag == RefFlag::Null as i8 {
                        return Ok(<$ty as ForyDefault>::fory_default());
                    }
                }
                if read_type_info {
                    let remote_field_type = Self::read_type_info_as_field_type(context)?;
                    return Self::read_data_with_type(context, &remote_field_type);
                }
                Self::read_data(context)
            }

            #[inline(always)]
            fn read_with_type_info(
                context: &mut ReadContext,
                ref_mode: RefMode,
                _type_info: std::rc::Rc<crate::TypeInfo>,
            ) -> Result<$ty, Error> {
                Self::read_with_mode(context, ref_mode, false)
            }

            #[inline(always)]
            fn default_value() -> $ty {
                <$ty as ForyDefault>::fory_default()
            }

            #[inline(always)]
            fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                context.writer.write_var_u32(WIRE_TYPE_ID as u32);
                Ok(())
            }

            #[inline(always)]
            fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                let remote = context.reader.read_var_u32()?;
                if !same_numeric_family($default_type, remote) {
                    return Err(Error::type_mismatch($default_type, remote));
                }
                Ok(())
            }

            #[inline(always)]
            fn read_type_info_as_field_type(context: &mut ReadContext) -> Result<FieldType, Error> {
                let remote = context.reader.read_var_u32()?;
                if !same_numeric_family($default_type, remote) {
                    return Err(Error::type_mismatch($default_type, remote));
                }
                Ok(FieldType::new(remote, false, Vec::new()))
            }

            #[inline(always)]
            fn static_type_id() -> TypeId {
                TypeId::try_from(WIRE_TYPE_ID).unwrap_or(TypeId::UNKNOWN)
            }
        }
    };
}

signed_int_codec!(
    I32Codec,
    i32,
    type_id::VARINT32,
    type_id::INT32,
    UNKNOWN,
    write_i32,
    read_i32,
    write_var_i32,
    read_var_i32,
    write_var_i32,
    read_var_i32
);
signed_int_codec!(
    I64Codec,
    i64,
    type_id::VARINT64,
    type_id::INT64,
    type_id::TAGGED_INT64,
    write_i64,
    read_i64,
    write_var_i64,
    read_var_i64,
    write_tagged_i64,
    read_tagged_i64
);
signed_int_codec!(
    U32Codec,
    u32,
    type_id::VAR_UINT32,
    type_id::UINT32,
    UNKNOWN,
    write_u32,
    read_u32,
    write_var_u32,
    read_var_u32,
    write_var_u32,
    read_var_u32
);
signed_int_codec!(
    U64Codec,
    u64,
    type_id::VAR_UINT64,
    type_id::UINT64,
    type_id::TAGGED_UINT64,
    write_u64,
    read_u64,
    write_var_u64,
    read_var_u64,
    write_tagged_u64,
    read_tagged_u64
);

pub struct VecCodec<T, C, const NULLABLE: bool, const TRACK_REF: bool>(PhantomData<(T, C)>);

impl<T, C, const NULLABLE: bool, const TRACK_REF: bool> Codec<Vec<T>>
    for VecCodec<T, C, NULLABLE, TRACK_REF>
where
    T: 'static,
    C: Codec<T>,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        let element_type = C::field_type(type_resolver)?;
        Ok(FieldType::new_with_ref(
            TypeId::LIST as u32,
            NULLABLE,
            TRACK_REF,
            vec![element_type],
        ))
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        std::mem::size_of::<u32>() + SIZE_OF_REF_AND_TYPE
    }

    #[inline(always)]
    fn write_field(value: &Vec<T>, context: &mut WriteContext) -> Result<(), Error> {
        if NULLABLE {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        Self::write_data(value, context)
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<Vec<T>, Error> {
        if NULLABLE {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Ok(Vec::new());
            }
        }
        Self::read_data(context)
    }

    fn write_data(value: &Vec<T>, context: &mut WriteContext) -> Result<(), Error> {
        let len = value.len();
        context.writer.write_var_u32(len as u32);
        if len == 0 {
            return Ok(());
        }
        if C::is_polymorphic() || C::is_shared_ref() {
            return write_vec_dynamic::<T, C>(value, context);
        }
        let mut header = IS_SAME_TYPE;
        let mut has_null = false;
        if C::is_option() {
            for item in value {
                if C::is_none(item) {
                    has_null = true;
                    break;
                }
            }
        }
        if has_null {
            header |= HAS_NULL;
        }
        if !need_to_write_type_for_field(C::static_type_id()) {
            header |= DECL_ELEMENT_TYPE;
            context.writer.write_u8(header);
        } else {
            context.writer.write_u8(header);
            C::write_type_info(context)?;
        }
        context.writer.reserve(len * C::reserved_space());
        if has_null {
            for item in value {
                if C::is_none(item) {
                    context.writer.write_i8(RefFlag::Null as i8);
                    continue;
                }
                context.writer.write_i8(RefFlag::NotNullValue as i8);
                C::write_data(item, context)?;
            }
        } else {
            for item in value {
                C::write_data(item, context)?;
            }
        }
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<Vec<T>, Error> {
        let len = context.reader.read_var_u32()?;
        if len == 0 {
            return Ok(Vec::new());
        }
        let max = context.max_collection_size();
        if len > max {
            return Err(Error::size_limit_exceeded(format!(
                "Collection size {} exceeds limit {}",
                len, max
            )));
        }
        let header = context.reader.read_u8()?;
        if C::is_polymorphic() || C::is_shared_ref() {
            let field_type = Self::field_type(context.get_type_resolver())?;
            return read_vec_dynamic_items::<T, C>(context, len, header, &field_type);
        }
        if (header & IS_SAME_TYPE) == 0 {
            return Err(Error::type_error(
                "Type inconsistent, target collection element type is not polymorphic",
            ));
        }
        if (header & DECL_ELEMENT_TYPE) == 0 {
            C::read_type_info(context)?;
        }
        let has_null = (header & HAS_NULL) != 0;
        let mut vec = Vec::with_capacity(len as usize);
        if has_null {
            for _ in 0..len {
                let flag = context.reader.read_i8()?;
                if flag == RefFlag::Null as i8 {
                    vec.push(C::default_value());
                } else {
                    vec.push(C::read_data(context)?);
                }
            }
        } else {
            for _ in 0..len {
                vec.push(C::read_data(context)?);
            }
        }
        Ok(vec)
    }

    fn read_data_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<Vec<T>, Error> {
        let len = context.reader.read_var_u32()?;
        if len == 0 {
            return Ok(Vec::new());
        }
        let max = context.max_collection_size();
        if len > max {
            return Err(Error::size_limit_exceeded(format!(
                "Collection size {} exceeds limit {}",
                len, max
            )));
        }
        let header = context.reader.read_u8()?;
        let has_null = (header & HAS_NULL) != 0;
        let is_same_type = (header & IS_SAME_TYPE) != 0;
        let is_declared = (header & DECL_ELEMENT_TYPE) != 0;
        if C::is_polymorphic() || C::is_shared_ref() {
            return read_vec_dynamic_items::<T, C>(context, len, header, remote_field_type);
        }
        if !is_same_type {
            return Err(Error::type_error(
                "Type inconsistent, target collection element type is not polymorphic",
            ));
        }
        let element_type = if is_declared {
            generic_field_type(remote_field_type, 0, "list")?.clone()
        } else {
            C::read_type_info_as_field_type(context)?
        };
        let mut vec = Vec::with_capacity(len as usize);
        if has_null {
            for _ in 0..len {
                let flag = context.reader.read_i8()?;
                if flag == RefFlag::Null as i8 {
                    vec.push(C::default_value());
                } else {
                    vec.push(C::read_data_with_type(context, &element_type)?);
                }
            }
        } else {
            for _ in 0..len {
                vec.push(C::read_data_with_type(context, &element_type)?);
            }
        }
        Ok(vec)
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<Vec<T>, Error> {
        if field_ref_mode(remote_field_type) != RefMode::None {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Ok(Vec::new());
            }
        }
        Self::read_data_with_type(context, remote_field_type)
    }

    #[inline(always)]
    fn write_with_mode(
        value: &Vec<T>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        _has_generics: bool,
    ) -> Result<(), Error> {
        if ref_mode != RefMode::None {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        if write_type_info {
            Self::write_type_info(context)?;
        }
        Self::write_data(value, context)
    }

    #[inline(always)]
    fn read_with_mode(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Vec<T>, Error> {
        if ref_mode != RefMode::None {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Ok(Vec::new());
            }
        }
        if read_type_info {
            Self::read_type_info(context)?;
        }
        Self::read_data(context)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        _type_info: std::rc::Rc<crate::TypeInfo>,
    ) -> Result<Vec<T>, Error> {
        Self::read_with_mode(context, ref_mode, false)
    }

    #[inline(always)]
    fn default_value() -> Vec<T> {
        Vec::new()
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(TypeId::LIST as u8);
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        let remote = context.reader.read_u8()? as u32;
        if remote != TypeId::LIST as u32 {
            return Err(Error::type_mismatch(TypeId::LIST as u32, remote));
        }
        Ok(())
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::LIST
    }
}

fn write_vec_dynamic<T, C>(value: &Vec<T>, context: &mut WriteContext) -> Result<(), Error>
where
    T: 'static,
    C: Codec<T>,
{
    let mut has_null = false;
    let mut is_same_type = true;
    let mut first_type_id: Option<std::any::TypeId> = None;
    for item in value {
        if C::is_none(item) {
            has_null = true;
        } else if C::is_polymorphic() && is_same_type {
            let concrete_id = C::concrete_type_id(item);
            if let Some(first_id) = first_type_id {
                if first_id != concrete_id {
                    is_same_type = false;
                }
            } else {
                first_type_id = Some(concrete_id);
            }
        }
    }
    if C::is_polymorphic() && is_same_type && first_type_id.is_none() {
        is_same_type = false;
    }
    let mut header = 0u8;
    if has_null {
        header |= HAS_NULL;
    }
    if !need_to_write_type_for_field(C::static_type_id()) {
        header |= DECL_ELEMENT_TYPE;
    }
    if is_same_type {
        header |= IS_SAME_TYPE;
    }
    if C::is_shared_ref() {
        header |= TRACKING_REF;
    }
    context.writer.write_u8(header);
    if is_same_type && (header & DECL_ELEMENT_TYPE) == 0 {
        if C::is_polymorphic() {
            let type_id = first_type_id.ok_or_else(|| {
                Error::type_error(
                    "Unable to determine concrete type for polymorphic collection elements",
                )
            })?;
            context.write_any_type_info(C::static_type_id() as u32, type_id)?;
        } else {
            C::write_type_info(context)?;
        }
    }
    let elem_ref_mode = if C::is_shared_ref() {
        RefMode::Tracking
    } else if has_null {
        RefMode::NullOnly
    } else {
        RefMode::None
    };
    if is_same_type {
        for item in value {
            if elem_ref_mode == RefMode::None {
                C::write_data(item, context)?;
            } else {
                C::write_with_mode(item, context, elem_ref_mode, false, true)?;
            }
        }
    } else {
        for item in value {
            C::write_with_mode(item, context, elem_ref_mode, true, true)?;
        }
    }
    Ok(())
}

fn read_vec_dynamic_items<T, C>(
    context: &mut ReadContext,
    len: u32,
    header: u8,
    remote_field_type: &FieldType,
) -> Result<Vec<T>, Error>
where
    T: 'static,
    C: Codec<T>,
{
    let is_track_ref = (header & TRACKING_REF) != 0;
    let has_null = (header & HAS_NULL) != 0;
    let is_same_type = (header & IS_SAME_TYPE) != 0;
    let is_declared = (header & DECL_ELEMENT_TYPE) != 0;
    let elem_ref_mode = if is_track_ref {
        RefMode::Tracking
    } else if has_null {
        RefMode::NullOnly
    } else {
        RefMode::None
    };
    let mut vec = Vec::with_capacity(len as usize);
    if is_same_type {
        if C::is_polymorphic() {
            if is_declared {
                return Err(Error::invalid_data(
                    "polymorphic collection element metadata must be written in the payload",
                ));
            }
            let type_info = context.read_any_type_info()?;
            for _ in 0..len {
                vec.push(C::read_with_type_info(
                    context,
                    elem_ref_mode,
                    type_info.clone(),
                )?);
            }
        } else {
            let element_type = if is_declared {
                generic_field_type(remote_field_type, 0, "list")?.clone()
            } else {
                C::read_type_info_as_field_type(context)?
            };
            for _ in 0..len {
                if C::is_shared_ref() {
                    vec.push(C::read_with_mode(context, elem_ref_mode, false)?);
                } else {
                    vec.push(C::read_data_with_type(context, &element_type)?);
                }
            }
        }
    } else {
        for _ in 0..len {
            vec.push(C::read_with_mode(context, elem_ref_mode, true)?);
        }
    }
    Ok(vec)
}

pub struct HashMapCodec<K, V, KC, VC, const NULLABLE: bool, const TRACK_REF: bool>(
    PhantomData<(K, V, KC, VC)>,
);

impl<K, V, KC, VC, const NULLABLE: bool, const TRACK_REF: bool> Codec<HashMap<K, V>>
    for HashMapCodec<K, V, KC, VC, NULLABLE, TRACK_REF>
where
    K: Eq + Hash + 'static,
    V: 'static,
    KC: Codec<K>,
    VC: Codec<V>,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        Ok(FieldType::new_with_ref(
            TypeId::MAP as u32,
            NULLABLE,
            TRACK_REF,
            vec![
                KC::field_type(type_resolver)?,
                VC::field_type(type_resolver)?,
            ],
        ))
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        std::mem::size_of::<u32>() + SIZE_OF_REF_AND_TYPE
    }

    #[inline(always)]
    fn write_field(value: &HashMap<K, V>, context: &mut WriteContext) -> Result<(), Error> {
        if NULLABLE {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        Self::write_data(value, context)
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<HashMap<K, V>, Error> {
        if NULLABLE {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Ok(HashMap::new());
            }
        }
        Self::read_data(context)
    }

    fn write_data(value: &HashMap<K, V>, context: &mut WriteContext) -> Result<(), Error> {
        let len = value.len();
        context.writer.write_var_u32(len as u32);
        if len == 0 {
            return Ok(());
        }
        if KC::is_polymorphic()
            || KC::is_shared_ref()
            || VC::is_polymorphic()
            || VC::is_shared_ref()
        {
            return write_map_dynamic::<K, V, KC, VC>(value, context);
        }
        let key_declared = !need_to_write_type_for_field(KC::static_type_id());
        let value_declared = !need_to_write_type_for_field(VC::static_type_id());
        let mut header_offset = 0;
        let mut pair_counter: u8 = 0;
        let mut need_write_header = true;

        for (key, value) in value {
            let key_is_none = KC::is_none(key);
            let value_is_none = VC::is_none(value);
            if key_is_none || value_is_none {
                if !need_write_header && pair_counter > 0 {
                    context.writer.set_bytes(header_offset + 1, &[pair_counter]);
                    pair_counter = 0;
                    need_write_header = true;
                }
                if key_is_none && value_is_none {
                    context.writer.write_u8(KEY_NULL | VALUE_NULL);
                    continue;
                }
                if value_is_none {
                    let mut header = VALUE_NULL;
                    if KC::is_shared_ref() {
                        header |= TRACKING_KEY_REF;
                    }
                    if key_declared && !KC::is_polymorphic() {
                        header |= DECL_KEY_TYPE;
                        context.writer.write_u8(header);
                    } else {
                        context.writer.write_u8(header);
                        write_map_entry_type::<K, KC>(key, context)?;
                    }
                    write_map_entry_data::<K, KC>(key, context, KC::is_shared_ref())?;
                    continue;
                }
                let mut header = KEY_NULL;
                if VC::is_shared_ref() {
                    header |= TRACKING_VALUE_REF;
                }
                if value_declared && !VC::is_polymorphic() {
                    header |= DECL_VALUE_TYPE;
                    context.writer.write_u8(header);
                } else {
                    context.writer.write_u8(header);
                    write_map_entry_type::<V, VC>(value, context)?;
                }
                write_map_entry_data::<V, VC>(value, context, VC::is_shared_ref())?;
                continue;
            }

            if need_write_header {
                header_offset = context.writer.len();
                context.writer.write_i16(-1);
                let mut header = 0u8;
                if KC::is_shared_ref() {
                    header |= TRACKING_KEY_REF;
                }
                if VC::is_shared_ref() {
                    header |= TRACKING_VALUE_REF;
                }
                if key_declared && !KC::is_polymorphic() {
                    header |= DECL_KEY_TYPE;
                } else {
                    write_map_entry_type::<K, KC>(key, context)?;
                }
                if value_declared && !VC::is_polymorphic() {
                    header |= DECL_VALUE_TYPE;
                } else {
                    write_map_entry_type::<V, VC>(value, context)?;
                }
                context.writer.set_bytes(header_offset, &[header]);
                need_write_header = false;
            }
            write_map_entry_data::<K, KC>(key, context, KC::is_shared_ref())?;
            write_map_entry_data::<V, VC>(value, context, VC::is_shared_ref())?;
            pair_counter += 1;
            if pair_counter == MAX_CHUNK_SIZE {
                context.writer.set_bytes(header_offset + 1, &[pair_counter]);
                pair_counter = 0;
                need_write_header = true;
            }
        }
        if pair_counter > 0 {
            context.writer.set_bytes(header_offset + 1, &[pair_counter]);
        }
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<HashMap<K, V>, Error> {
        let len = context.reader.read_var_u32()?;
        if len == 0 {
            return Ok(HashMap::new());
        }
        let max = context.max_collection_size();
        if len > max {
            return Err(Error::size_limit_exceeded(format!(
                "Map size {} exceeds limit {}",
                len, max
            )));
        }
        if KC::is_polymorphic()
            || KC::is_shared_ref()
            || VC::is_polymorphic()
            || VC::is_shared_ref()
        {
            let field_type = Self::field_type(context.get_type_resolver())?;
            return read_map_dynamic::<K, V, KC, VC>(context, len, &field_type);
        }
        read_map_static::<K, V, KC, VC>(context, len)
    }

    fn read_data_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<HashMap<K, V>, Error> {
        let len = context.reader.read_var_u32()?;
        if len == 0 {
            return Ok(HashMap::new());
        }
        let max = context.max_collection_size();
        if len > max {
            return Err(Error::size_limit_exceeded(format!(
                "Map size {} exceeds limit {}",
                len, max
            )));
        }
        if KC::is_polymorphic()
            || KC::is_shared_ref()
            || VC::is_polymorphic()
            || VC::is_shared_ref()
        {
            return read_map_dynamic::<K, V, KC, VC>(context, len, remote_field_type);
        }
        let mut map = HashMap::with_capacity(len as usize);
        let mut len_counter = 0;
        while len_counter < len {
            let header = context.reader.read_u8()?;
            if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
                map.insert(KC::default_value(), VC::default_value());
                len_counter += 1;
                continue;
            }
            let key_declared = (header & DECL_KEY_TYPE) != 0;
            let value_declared = (header & DECL_VALUE_TYPE) != 0;
            let track_key_ref = (header & TRACKING_KEY_REF) != 0;
            let track_value_ref = (header & TRACKING_VALUE_REF) != 0;
            if header & KEY_NULL != 0 {
                let value_type =
                    map_entry_type::<V, VC>(context, value_declared, remote_field_type, 1)?;
                let value = read_map_entry_data::<V, VC>(context, &value_type, track_value_ref)?;
                map.insert(KC::default_value(), value);
                len_counter += 1;
                continue;
            }
            if header & VALUE_NULL != 0 {
                let key_type =
                    map_entry_type::<K, KC>(context, key_declared, remote_field_type, 0)?;
                let key = read_map_entry_data::<K, KC>(context, &key_type, track_key_ref)?;
                map.insert(key, VC::default_value());
                len_counter += 1;
                continue;
            }
            let chunk_size = context.reader.read_u8()?;
            let key_type = map_entry_type::<K, KC>(context, key_declared, remote_field_type, 0)?;
            let value_type =
                map_entry_type::<V, VC>(context, value_declared, remote_field_type, 1)?;
            let cur_len = len_counter + chunk_size as u32;
            if cur_len > len {
                return Err(Error::invalid_data(format!(
                    "current length {} exceeds total length {}",
                    cur_len, len
                )));
            }
            for _ in 0..chunk_size {
                let key = read_map_entry_data::<K, KC>(context, &key_type, track_key_ref)?;
                let value = read_map_entry_data::<V, VC>(context, &value_type, track_value_ref)?;
                map.insert(key, value);
            }
            len_counter = cur_len;
        }
        Ok(map)
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<HashMap<K, V>, Error> {
        if field_ref_mode(remote_field_type) != RefMode::None {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Ok(HashMap::new());
            }
        }
        Self::read_data_with_type(context, remote_field_type)
    }

    #[inline(always)]
    fn write_with_mode(
        value: &HashMap<K, V>,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        _has_generics: bool,
    ) -> Result<(), Error> {
        if ref_mode != RefMode::None {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        if write_type_info {
            Self::write_type_info(context)?;
        }
        Self::write_data(value, context)
    }

    #[inline(always)]
    fn read_with_mode(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<HashMap<K, V>, Error> {
        if ref_mode != RefMode::None {
            let ref_flag = context.reader.read_i8()?;
            if ref_flag == RefFlag::Null as i8 {
                return Ok(HashMap::new());
            }
        }
        if read_type_info {
            Self::read_type_info(context)?;
        }
        Self::read_data(context)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        _type_info: std::rc::Rc<crate::TypeInfo>,
    ) -> Result<HashMap<K, V>, Error> {
        Self::read_with_mode(context, ref_mode, false)
    }

    #[inline(always)]
    fn default_value() -> HashMap<K, V> {
        HashMap::new()
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(TypeId::MAP as u8);
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        let remote = context.reader.read_u8()? as u32;
        if remote != TypeId::MAP as u32 {
            return Err(Error::type_mismatch(TypeId::MAP as u32, remote));
        }
        Ok(())
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::MAP
    }
}

struct MapEntryReadType {
    field_type: Option<FieldType>,
    type_info: Option<std::rc::Rc<crate::TypeInfo>>,
}

fn read_map_static<K, V, KC, VC>(
    context: &mut ReadContext,
    len: u32,
) -> Result<HashMap<K, V>, Error>
where
    K: Eq + Hash + 'static,
    V: 'static,
    KC: Codec<K>,
    VC: Codec<V>,
{
    let mut map = HashMap::with_capacity(len as usize);
    let mut len_counter = 0u32;
    while len_counter < len {
        let header = context.reader.read_u8()?;
        if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
            map.insert(KC::default_value(), VC::default_value());
            len_counter += 1;
            continue;
        }
        let key_declared = (header & DECL_KEY_TYPE) != 0;
        let value_declared = (header & DECL_VALUE_TYPE) != 0;
        let track_key_ref = (header & TRACKING_KEY_REF) != 0;
        let track_value_ref = (header & TRACKING_VALUE_REF) != 0;

        if header & KEY_NULL != 0 {
            let value_type =
                read_map_static_entry_type::<V, VC>(context, value_declared, track_value_ref)?;
            let value =
                read_map_static_entry_data::<V, VC>(context, value_type.as_ref(), track_value_ref)?;
            map.insert(KC::default_value(), value);
            len_counter += 1;
            continue;
        }
        if header & VALUE_NULL != 0 {
            let key_type =
                read_map_static_entry_type::<K, KC>(context, key_declared, track_key_ref)?;
            let key =
                read_map_static_entry_data::<K, KC>(context, key_type.as_ref(), track_key_ref)?;
            map.insert(key, VC::default_value());
            len_counter += 1;
            continue;
        }

        let chunk_size = context.reader.read_u8()?;
        let key_type = read_map_static_entry_type::<K, KC>(context, key_declared, track_key_ref)?;
        let value_type =
            read_map_static_entry_type::<V, VC>(context, value_declared, track_value_ref)?;
        let cur_len = len_counter + chunk_size as u32;
        if cur_len > len {
            return Err(Error::invalid_data(format!(
                "current length {} exceeds total length {}",
                cur_len, len
            )));
        }
        for _ in 0..chunk_size {
            let key =
                read_map_static_entry_data::<K, KC>(context, key_type.as_ref(), track_key_ref)?;
            let value =
                read_map_static_entry_data::<V, VC>(context, value_type.as_ref(), track_value_ref)?;
            map.insert(key, value);
        }
        len_counter = cur_len;
    }
    Ok(map)
}

#[inline(always)]
fn read_map_static_entry_type<T, C>(
    context: &mut ReadContext,
    declared: bool,
    track_ref: bool,
) -> Result<Option<FieldType>, Error>
where
    T: 'static,
    C: Codec<T>,
{
    if declared {
        Ok(None)
    } else if track_ref {
        C::read_type_info(context)?;
        Ok(None)
    } else {
        C::read_type_info_as_field_type(context).map(Some)
    }
}

#[inline(always)]
fn read_map_static_entry_data<T, C>(
    context: &mut ReadContext,
    entry_type: Option<&FieldType>,
    track_ref: bool,
) -> Result<T, Error>
where
    T: 'static,
    C: Codec<T>,
{
    if track_ref {
        C::read_with_mode(context, RefMode::Tracking, false)
    } else if let Some(entry_type) = entry_type {
        C::read_data_with_type(context, entry_type)
    } else {
        C::read_data(context)
    }
}

fn read_map_dynamic<K, V, KC, VC>(
    context: &mut ReadContext,
    len: u32,
    remote_field_type: &FieldType,
) -> Result<HashMap<K, V>, Error>
where
    K: Eq + Hash + 'static,
    V: 'static,
    KC: Codec<K>,
    VC: Codec<V>,
{
    let mut map = HashMap::with_capacity(len as usize);
    let mut len_counter = 0u32;
    while len_counter < len {
        let header = context.reader.read_u8()?;
        if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
            map.insert(KC::default_value(), VC::default_value());
            len_counter += 1;
            continue;
        }
        let key_declared = (header & DECL_KEY_TYPE) != 0;
        let value_declared = (header & DECL_VALUE_TYPE) != 0;
        let track_key_ref = (header & TRACKING_KEY_REF) != 0;
        let track_value_ref = (header & TRACKING_VALUE_REF) != 0;

        if header & KEY_NULL != 0 {
            let value_type = read_map_dynamic_entry_type::<V, VC>(
                context,
                value_declared,
                remote_field_type,
                1,
            )?;
            let value =
                read_map_dynamic_entry_data::<V, VC>(context, &value_type, track_value_ref)?;
            map.insert(KC::default_value(), value);
            len_counter += 1;
            continue;
        }
        if header & VALUE_NULL != 0 {
            let key_type =
                read_map_dynamic_entry_type::<K, KC>(context, key_declared, remote_field_type, 0)?;
            let key = read_map_dynamic_entry_data::<K, KC>(context, &key_type, track_key_ref)?;
            map.insert(key, VC::default_value());
            len_counter += 1;
            continue;
        }

        let chunk_size = context.reader.read_u8()?;
        let key_type =
            read_map_dynamic_entry_type::<K, KC>(context, key_declared, remote_field_type, 0)?;
        let value_type =
            read_map_dynamic_entry_type::<V, VC>(context, value_declared, remote_field_type, 1)?;
        let cur_len = len_counter + chunk_size as u32;
        if cur_len > len {
            return Err(Error::invalid_data(format!(
                "current length {} exceeds total length {}",
                cur_len, len
            )));
        }
        for _ in 0..chunk_size {
            let key = read_map_dynamic_entry_data::<K, KC>(context, &key_type, track_key_ref)?;
            let value =
                read_map_dynamic_entry_data::<V, VC>(context, &value_type, track_value_ref)?;
            map.insert(key, value);
        }
        len_counter = cur_len;
    }
    Ok(map)
}

#[inline(always)]
fn read_map_dynamic_entry_type<T, C>(
    context: &mut ReadContext,
    declared: bool,
    remote_field_type: &FieldType,
    generic_index: usize,
) -> Result<MapEntryReadType, Error>
where
    T: 'static,
    C: Codec<T>,
{
    if declared {
        return Ok(MapEntryReadType {
            field_type: Some(generic_field_type(remote_field_type, generic_index, "map")?.clone()),
            type_info: None,
        });
    }
    if C::is_polymorphic() {
        Ok(MapEntryReadType {
            field_type: None,
            type_info: Some(context.read_any_type_info()?),
        })
    } else {
        Ok(MapEntryReadType {
            field_type: Some(C::read_type_info_as_field_type(context)?),
            type_info: None,
        })
    }
}

#[inline(always)]
fn read_map_dynamic_entry_data<T, C>(
    context: &mut ReadContext,
    read_type: &MapEntryReadType,
    track_ref: bool,
) -> Result<T, Error>
where
    T: 'static,
    C: Codec<T>,
{
    let ref_mode = if track_ref {
        RefMode::Tracking
    } else {
        RefMode::None
    };
    if let Some(type_info) = read_type.type_info.as_ref() {
        return C::read_with_type_info(context, ref_mode, type_info.clone());
    }
    if track_ref {
        return C::read_with_mode(context, ref_mode, false);
    }
    let field_type = read_type
        .field_type
        .as_ref()
        .ok_or_else(|| Error::invalid_data("map entry field metadata is missing"))?;
    C::read_data_with_type(context, field_type)
}

fn write_map_dynamic<K, V, KC, VC>(
    value: &HashMap<K, V>,
    context: &mut WriteContext,
) -> Result<(), Error>
where
    K: Eq + Hash + 'static,
    V: 'static,
    KC: Codec<K>,
    VC: Codec<V>,
{
    let key_declared = !need_to_write_type_for_field(KC::static_type_id());
    let value_declared = !need_to_write_type_for_field(VC::static_type_id());
    let key_is_polymorphic = KC::is_polymorphic();
    let value_is_polymorphic = VC::is_polymorphic();
    let key_is_shared_ref = KC::is_shared_ref();
    let value_is_shared_ref = VC::is_shared_ref();
    let mut current_key_type_id: Option<std::any::TypeId> = None;
    let mut current_value_type_id: Option<std::any::TypeId> = None;
    let mut header_offset = 0;
    let mut pair_counter: u8 = 0;
    let mut need_write_header = true;

    for (key, value) in value {
        let key_is_none = KC::is_none(key);
        let value_is_none = VC::is_none(value);
        if key_is_none || value_is_none {
            if pair_counter > 0 {
                context.writer.set_bytes(header_offset + 1, &[pair_counter]);
                pair_counter = 0;
                need_write_header = true;
            }
            if key_is_none && value_is_none {
                context.writer.write_u8(KEY_NULL | VALUE_NULL);
                continue;
            }
            if value_is_none {
                let mut header = VALUE_NULL;
                if key_is_shared_ref {
                    header |= TRACKING_KEY_REF;
                }
                if key_declared && !key_is_polymorphic {
                    header |= DECL_KEY_TYPE;
                    context.writer.write_u8(header);
                } else {
                    context.writer.write_u8(header);
                    write_map_entry_type::<K, KC>(key, context)?;
                }
                write_map_entry_data::<K, KC>(key, context, key_is_shared_ref)?;
                continue;
            }
            let mut header = KEY_NULL;
            if value_is_shared_ref {
                header |= TRACKING_VALUE_REF;
            }
            if value_declared && !value_is_polymorphic {
                header |= DECL_VALUE_TYPE;
                context.writer.write_u8(header);
            } else {
                context.writer.write_u8(header);
                write_map_entry_type::<V, VC>(value, context)?;
            }
            write_map_entry_data::<V, VC>(value, context, value_is_shared_ref)?;
            continue;
        }

        let key_type_id = if key_is_polymorphic {
            Some(KC::concrete_type_id(key))
        } else {
            None
        };
        let value_type_id = if value_is_polymorphic {
            Some(VC::concrete_type_id(value))
        } else {
            None
        };
        let types_changed = (key_is_polymorphic || value_is_polymorphic)
            && (key_type_id != current_key_type_id || value_type_id != current_value_type_id);

        if need_write_header || types_changed {
            if pair_counter > 0 {
                context.writer.set_bytes(header_offset + 1, &[pair_counter]);
                pair_counter = 0;
            }
            header_offset = context.writer.len();
            context.writer.write_i16(-1);
            let mut header = 0u8;
            if key_is_shared_ref {
                header |= TRACKING_KEY_REF;
            }
            if value_is_shared_ref {
                header |= TRACKING_VALUE_REF;
            }
            if key_declared && !key_is_polymorphic {
                header |= DECL_KEY_TYPE;
            } else {
                write_map_entry_type::<K, KC>(key, context)?;
            }
            if value_declared && !value_is_polymorphic {
                header |= DECL_VALUE_TYPE;
            } else {
                write_map_entry_type::<V, VC>(value, context)?;
            }
            context.writer.set_bytes(header_offset, &[header]);
            need_write_header = false;
            current_key_type_id = key_type_id;
            current_value_type_id = value_type_id;
        }

        write_map_entry_data::<K, KC>(key, context, key_is_shared_ref)?;
        write_map_entry_data::<V, VC>(value, context, value_is_shared_ref)?;
        pair_counter += 1;
        if pair_counter == MAX_CHUNK_SIZE {
            context.writer.set_bytes(header_offset + 1, &[pair_counter]);
            pair_counter = 0;
            need_write_header = true;
            current_key_type_id = None;
            current_value_type_id = None;
        }
    }
    if pair_counter > 0 {
        context.writer.set_bytes(header_offset + 1, &[pair_counter]);
    }
    Ok(())
}

#[inline(always)]
fn write_map_entry_type<T, C>(value: &T, context: &mut WriteContext) -> Result<(), Error>
where
    T: 'static,
    C: Codec<T>,
{
    if C::is_polymorphic() {
        context.write_any_type_info(C::static_type_id() as u32, C::concrete_type_id(value))?;
        Ok(())
    } else {
        C::write_type_info(context)
    }
}

#[inline(always)]
fn write_map_entry_data<T, C>(
    value: &T,
    context: &mut WriteContext,
    track_ref: bool,
) -> Result<(), Error>
where
    T: 'static,
    C: Codec<T>,
{
    if track_ref {
        C::write_with_mode(value, context, RefMode::Tracking, false, true)
    } else {
        C::write_data(value, context)
    }
}

#[inline(always)]
fn map_entry_type<T, C>(
    context: &mut ReadContext,
    declared: bool,
    remote_field_type: &FieldType,
    generic_index: usize,
) -> Result<FieldType, Error>
where
    T: 'static,
    C: Codec<T>,
{
    if declared {
        Ok(generic_field_type(remote_field_type, generic_index, "map")?.clone())
    } else {
        C::read_type_info_as_field_type(context)
    }
}

#[inline(always)]
fn read_map_entry_data<T, C>(
    context: &mut ReadContext,
    remote_entry_type: &FieldType,
    track_ref: bool,
) -> Result<T, Error>
where
    T: 'static,
    C: Codec<T>,
{
    if track_ref {
        C::read_with_mode(context, RefMode::Tracking, false)
    } else {
        C::read_data_with_type(context, remote_entry_type)
    }
}

#[inline(always)]
fn any_field_type<const NULLABLE: bool, const TRACK_REF: bool>() -> FieldType {
    FieldType::new_with_ref(TypeId::UNKNOWN as u32, NULLABLE, TRACK_REF, Vec::new())
}

#[inline(always)]
fn any_ref_mode<const NULLABLE: bool, const TRACK_REF: bool>() -> RefMode {
    if TRACK_REF {
        RefMode::Tracking
    } else if NULLABLE {
        RefMode::NullOnly
    } else {
        RefMode::None
    }
}

macro_rules! any_codec {
    ($name:ident, $ty:ty) => {
        pub struct $name<const NULLABLE: bool, const TRACK_REF: bool>;

        impl<const NULLABLE: bool, const TRACK_REF: bool> Codec<$ty>
            for $name<NULLABLE, TRACK_REF>
        {
            #[inline(always)]
            fn field_type(_: &TypeResolver) -> Result<FieldType, Error> {
                Ok(any_field_type::<NULLABLE, TRACK_REF>())
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                <$ty as Serializer>::fory_reserved_space() + SIZE_OF_REF_AND_TYPE
            }

            #[inline(always)]
            fn write_field(value: &$ty, context: &mut WriteContext) -> Result<(), Error> {
                <$ty as Serializer>::fory_write(
                    value,
                    context,
                    any_ref_mode::<NULLABLE, TRACK_REF>(),
                    field_write_type_info::<$ty>(context),
                    false,
                )
            }

            #[inline(always)]
            fn read_field(context: &mut ReadContext) -> Result<$ty, Error> {
                <$ty as Serializer>::fory_read(
                    context,
                    any_ref_mode::<NULLABLE, TRACK_REF>(),
                    codec_read_type_info_static::<$ty, Self>(context),
                )
            }

            #[inline(always)]
            fn write_data(value: &$ty, context: &mut WriteContext) -> Result<(), Error> {
                <$ty as Serializer>::fory_write_data_generic(value, context, false)
            }

            #[inline(always)]
            fn read_data(context: &mut ReadContext) -> Result<$ty, Error> {
                <$ty as Serializer>::fory_read_data(context)
            }

            #[inline(always)]
            fn read_field_with_type(
                context: &mut ReadContext,
                remote_field_type: &FieldType,
            ) -> Result<$ty, Error> {
                <$ty as Serializer>::fory_read(
                    context,
                    field_ref_mode(remote_field_type),
                    codec_read_type_info::<$ty, Self>(context, remote_field_type),
                )
            }

            #[inline(always)]
            fn write_with_mode(
                value: &$ty,
                context: &mut WriteContext,
                ref_mode: RefMode,
                write_type_info: bool,
                has_generics: bool,
            ) -> Result<(), Error> {
                <$ty as Serializer>::fory_write(
                    value,
                    context,
                    ref_mode,
                    write_type_info,
                    has_generics,
                )
            }

            #[inline(always)]
            fn read_with_mode(
                context: &mut ReadContext,
                ref_mode: RefMode,
                read_type_info: bool,
            ) -> Result<$ty, Error> {
                <$ty as Serializer>::fory_read(context, ref_mode, read_type_info)
            }

            #[inline(always)]
            fn read_with_type_info(
                context: &mut ReadContext,
                ref_mode: RefMode,
                type_info: Rc<crate::TypeInfo>,
            ) -> Result<$ty, Error> {
                <$ty as Serializer>::fory_read_with_type_info(context, ref_mode, type_info)
            }

            #[inline(always)]
            fn default_value() -> $ty {
                <$ty as ForyDefault>::fory_default()
            }

            #[inline(always)]
            fn write_type_info(_context: &mut WriteContext) -> Result<(), Error> {
                Ok(())
            }

            #[inline(always)]
            fn read_type_info(_context: &mut ReadContext) -> Result<(), Error> {
                Ok(())
            }

            #[inline(always)]
            fn static_type_id() -> TypeId {
                TypeId::UNKNOWN
            }

            #[inline(always)]
            fn is_polymorphic() -> bool {
                true
            }

            #[inline(always)]
            fn is_shared_ref() -> bool {
                <$ty as Serializer>::fory_is_shared_ref()
            }

            #[inline(always)]
            fn concrete_type_id(value: &$ty) -> std::any::TypeId {
                <$ty as Serializer>::fory_concrete_type_id(value)
            }
        }
    };
}

any_codec!(AnyBoxCodec, Box<dyn Any>);
any_codec!(AnyRcCodec, Rc<dyn Any>);
any_codec!(AnyArcCodec, Arc<dyn Any>);
