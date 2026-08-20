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

use super::codec::{
    field_ref_mode, field_type_with_ref_flags, generic_field_type, Codec, CodecReadType,
};
use super::collection::{field_read_data_always_advances, settle_unbacked_items};
use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::meta::FieldType;
use crate::resolver::{RefFlag, RefMode, TypeInfo, TypeResolver};
use crate::serializer::{core::read_value_type_info, Serializer};
use crate::type_id::{need_to_write_type_for_field, TypeId, SIZE_OF_REF_AND_TYPE};
use std::borrow::Cow;
use std::collections::{BTreeMap, HashMap};
use std::marker::PhantomData;
use std::rc::Rc;

const MAX_CHUNK_SIZE: u8 = 255;
pub(crate) const TRACKING_KEY_REF: u8 = 0b1;
pub(crate) const KEY_NULL: u8 = 0b10;
pub(crate) const DECL_KEY_TYPE: u8 = 0b100;
pub(crate) const TRACKING_VALUE_REF: u8 = 0b1000;
pub(crate) const VALUE_NULL: u8 = 0b10000;
pub(crate) const DECL_VALUE_TYPE: u8 = 0b100000;

pub struct BTreeMapCodec<K, V, KC, VC, const NULLABLE: bool, const TRACK_REF: bool>(
    PhantomData<(K, V, KC, VC)>,
);

pub struct HashMapCodec<K, V, KC, VC, const NULLABLE: bool, const TRACK_REF: bool>(
    PhantomData<(K, V, KC, VC)>,
);

trait MapTarget<K, V>: Sized {
    fn with_capacity(capacity: usize) -> Self;
    fn insert(&mut self, key: K, value: V);
}

impl<K: Eq + std::hash::Hash, V> MapTarget<K, V> for HashMap<K, V> {
    #[inline(always)]
    fn with_capacity(capacity: usize) -> Self {
        HashMap::with_capacity(capacity)
    }

    #[inline(always)]
    fn insert(&mut self, key: K, value: V) {
        HashMap::insert(self, key, value);
    }
}

impl<K: Ord, V> MapTarget<K, V> for BTreeMap<K, V> {
    #[inline(always)]
    fn with_capacity(_: usize) -> Self {
        BTreeMap::new()
    }

    #[inline(always)]
    fn insert(&mut self, key: K, value: V) {
        BTreeMap::insert(self, key, value);
    }
}

#[inline(always)]
fn write_entry_type<T: 'static, S: Serializer<Target = T>>(
    context: &mut WriteContext,
    target_type_id: Option<std::any::TypeId>,
) -> Result<Option<Rc<TypeInfo>>, Error> {
    if let Some(target_type_id) = target_type_id {
        S::write_type_info_value(context, target_type_id).map(Some)
    } else {
        S::write_type_info(context)?;
        Ok(None)
    }
}

macro_rules! map_write_mode {
    (value, $T:ty, $S:ty, $value:expr, $context:expr, $ref_mode:expr, $write_type:expr, $has_generics:expr) => {
        <$S as Serializer>::write($value, $context, $ref_mode, $write_type)
    };
    (field, $T:ty, $C:ty, $value:expr, $context:expr, $ref_mode:expr, $write_type:expr, $has_generics:expr) => {
        <$C as Codec<$T>>::write_with_mode($value, $context, $ref_mode, $write_type, $has_generics)
    };
}

macro_rules! map_write_with_info {
    (value, $T:ty, $S:ty, $value:expr, $context:expr, $ref_mode:expr, $type_info:expr, $has_generics:expr) => {
        <$S as Serializer>::write_with_type_info($value, $context, $ref_mode, $type_info)
    };
    (field, $T:ty, $C:ty, $value:expr, $context:expr, $ref_mode:expr, $type_info:expr, $has_generics:expr) => {
        <$C as Codec<$T>>::write_with_type_info(
            $value,
            $context,
            $ref_mode,
            $type_info,
            $has_generics,
        )
    };
}

macro_rules! map_write_entry {
    ($layer:ident, $T:ty, $C:ty, $value:expr, $context:expr, $track_ref:expr, $has_generics:expr, $type_info:expr) => {{
        let ref_mode = if $track_ref {
            RefMode::Tracking
        } else {
            RefMode::None
        };
        if let Some(type_info) = $type_info {
            map_write_with_info!(
                $layer,
                $T,
                $C,
                $value,
                $context,
                ref_mode,
                type_info,
                $has_generics
            )
        } else {
            map_write_mode!(
                $layer,
                $T,
                $C,
                $value,
                $context,
                ref_mode,
                false,
                $has_generics
            )
        }
    }};
}

macro_rules! map_reserved_space {
    (value, $T:ty, $S:ty) => {
        <$S as Serializer>::reserved_space()
    };
    (field, $T:ty, $C:ty) => {
        <$C as Codec<$T>>::field_reserved_space()
    };
}

macro_rules! write_map_data_body {
    (
        $layer:ident,
        $K:ident,
        $V:ident,
        $KC:ident,
        $VC:ident,
        $iter:expr,
        $len:expr,
        $context:expr,
        $has_generics:expr
    ) => {{
        let context = $context;
        let len = $len;
        context.writer.write_var_u32(len as u32);
        if len == 0 {
            return Ok(());
        }
        context.writer.reserve(
            len.saturating_mul(
                map_reserved_space!($layer, $K, $KC)
                    .saturating_add(map_reserved_space!($layer, $V, $VC)),
            ),
        );

        let has_generics = $has_generics;
        let key_declared = has_generics && !need_to_write_type_for_field($KC::static_type_id());
        let value_declared = has_generics && !need_to_write_type_for_field($VC::static_type_id());
        let key_polymorphic = $KC::IS_POLYMORPHIC;
        let value_polymorphic = $VC::IS_POLYMORPHIC;
        let key_shared = $KC::IS_SHARED_REF;
        let value_shared = $VC::IS_SHARED_REF;
        let mut key_type = None;
        let mut value_type = None;
        let mut key_info = None;
        let mut value_info = None;
        let mut header_offset = 0;
        let mut pair_count = 0u8;
        let mut need_header = true;

        for (key, value) in $iter {
            // MAP emits both type headers before either body. Holder serializers
            // release this inspection immediately; resolved body writes
            // revalidate the value, so matching entries can still share one
            // chunk TypeInfo.
            let next_key_type = if key_polymorphic {
                $KC::dynamic_type_id(key)?
            } else {
                None
            };
            let next_value_type = if value_polymorphic {
                $VC::dynamic_type_id(value)?
            } else {
                None
            };
            let key_none = if key_polymorphic {
                next_key_type.is_none()
            } else {
                $KC::is_none(key)
            };
            let value_none = if value_polymorphic {
                next_value_type.is_none()
            } else {
                $VC::is_none(value)
            };
            if key_none || value_none {
                if pair_count != 0 {
                    context.writer.set_bytes(header_offset + 1, &[pair_count]);
                    pair_count = 0;
                    need_header = true;
                }
                if key_none && value_none {
                    context.writer.write_u8(KEY_NULL | VALUE_NULL);
                    continue;
                }
                if value_none {
                    let mut header = VALUE_NULL;
                    if key_shared {
                        header |= TRACKING_KEY_REF;
                    }
                    if key_declared && !key_polymorphic {
                        header |= DECL_KEY_TYPE;
                        context.writer.write_u8(header);
                        map_write_entry!(
                            $layer,
                            $K,
                            $KC,
                            key,
                            context,
                            key_shared,
                            has_generics,
                            None
                        )?;
                    } else {
                        context.writer.write_u8(header);
                        if key_shared {
                            map_write_mode!(
                                $layer,
                                $K,
                                $KC,
                                key,
                                context,
                                RefMode::Tracking,
                                true,
                                has_generics
                            )?;
                        } else {
                            let key_info = write_entry_type::<$K, $KC>(context, next_key_type)?;
                            map_write_entry!(
                                $layer,
                                $K,
                                $KC,
                                key,
                                context,
                                false,
                                has_generics,
                                key_info.as_ref()
                            )?;
                        }
                    }
                    continue;
                }
                let mut header = KEY_NULL;
                if value_shared {
                    header |= TRACKING_VALUE_REF;
                }
                if value_declared && !value_polymorphic {
                    header |= DECL_VALUE_TYPE;
                    context.writer.write_u8(header);
                    map_write_entry!(
                        $layer,
                        $V,
                        $VC,
                        value,
                        context,
                        value_shared,
                        has_generics,
                        None
                    )?;
                } else {
                    context.writer.write_u8(header);
                    if value_shared {
                        map_write_mode!(
                            $layer,
                            $V,
                            $VC,
                            value,
                            context,
                            RefMode::Tracking,
                            true,
                            has_generics
                        )?;
                    } else {
                        let value_info = write_entry_type::<$V, $VC>(context, next_value_type)?;
                        map_write_entry!(
                            $layer,
                            $V,
                            $VC,
                            value,
                            context,
                            false,
                            has_generics,
                            value_info.as_ref()
                        )?;
                    }
                }
                continue;
            }

            let types_changed = (key_polymorphic || value_polymorphic)
                && (next_key_type != key_type || next_value_type != value_type);
            if need_header || types_changed {
                if pair_count != 0 {
                    context.writer.set_bytes(header_offset + 1, &[pair_count]);
                    pair_count = 0;
                }
                header_offset = context.writer.len();
                context.writer.write_i16(-1);
                let mut header = 0;
                if key_shared {
                    header |= TRACKING_KEY_REF;
                }
                if value_shared {
                    header |= TRACKING_VALUE_REF;
                }
                if key_declared && !key_polymorphic {
                    header |= DECL_KEY_TYPE;
                    key_info = None;
                } else {
                    key_info = write_entry_type::<$K, $KC>(context, next_key_type)?;
                }
                if value_declared && !value_polymorphic {
                    header |= DECL_VALUE_TYPE;
                    value_info = None;
                } else {
                    value_info = write_entry_type::<$V, $VC>(context, next_value_type)?;
                }
                context.writer.set_bytes(header_offset, &[header]);
                need_header = false;
                key_type = next_key_type;
                value_type = next_value_type;
            }

            map_write_entry!(
                $layer,
                $K,
                $KC,
                key,
                context,
                key_shared,
                has_generics,
                key_info.as_ref()
            )?;
            map_write_entry!(
                $layer,
                $V,
                $VC,
                value,
                context,
                value_shared,
                has_generics,
                value_info.as_ref()
            )?;
            pair_count += 1;
            if pair_count == MAX_CHUNK_SIZE {
                context.writer.set_bytes(header_offset + 1, &[pair_count]);
                pair_count = 0;
                need_header = true;
                key_type = None;
                value_type = None;
                key_info = None;
                value_info = None;
            }
        }
        if pair_count != 0 {
            context.writer.set_bytes(header_offset + 1, &[pair_count]);
        }
        Ok(())
    }};
}

fn write_value_map_data<'a, K, V, KS, VS, I>(
    iter: I,
    len: usize,
    context: &mut WriteContext,
) -> Result<(), Error>
where
    K: 'static + 'a,
    V: 'static + 'a,
    KS: Serializer<Target = K>,
    VS: Serializer<Target = V>,
    I: Iterator<Item = (&'a K, &'a V)>,
{
    write_map_data_body!(value, K, V, KS, VS, iter, len, context, false)
}

fn write_field_map_data<'a, K, V, KC, VC, I>(
    iter: I,
    len: usize,
    context: &mut WriteContext,
    has_generics: bool,
) -> Result<(), Error>
where
    K: 'static + 'a,
    V: 'static + 'a,
    KC: Codec<K>,
    VC: Codec<V>,
    I: Iterator<Item = (&'a K, &'a V)>,
{
    write_map_data_body!(field, K, V, KC, VC, iter, len, context, has_generics)
}

enum EntryReadType<'a> {
    Direct,
    Field(Cow<'a, FieldType>),
    TypeInfo(Rc<TypeInfo>),
}

#[inline(always)]
fn read_value_entry_type<T, S>(
    context: &mut ReadContext,
    declared: bool,
) -> Result<Option<Rc<TypeInfo>>, Error>
where
    T: 'static,
    S: Serializer<Target = T>,
{
    if declared {
        return Ok(None);
    }
    read_value_type_info::<S>(context)
}

#[inline(always)]
fn read_value_entry<T, S>(
    context: &mut ReadContext,
    read_type: &Option<Rc<TypeInfo>>,
    track_ref: bool,
) -> Result<T, Error>
where
    T: 'static,
    S: Serializer<Target = T>,
{
    let ref_mode = if track_ref {
        RefMode::Tracking
    } else {
        RefMode::None
    };
    match read_type {
        None => S::read(context, ref_mode, false),
        Some(type_info) => S::read_with_type_info(context, ref_mode, type_info),
    }
}

#[inline(always)]
fn read_entry_type<'a, T: 'static, C: Codec<T>>(
    context: &mut ReadContext,
    declared: bool,
    remote_field_type: Option<&'a FieldType>,
    index: usize,
    track_ref: bool,
) -> Result<EntryReadType<'a>, Error> {
    if declared {
        return match remote_field_type {
            Some(field_type) => {
                let field_type = generic_field_type(field_type, index, "map")?;
                Ok(EntryReadType::Field(field_type_with_ref_flags(
                    field_type,
                    field_type.nullable,
                    track_ref,
                )))
            }
            None => Ok(EntryReadType::Direct),
        };
    }
    match C::read_type_info_value(context)? {
        CodecReadType::Field(mut field_type) => {
            field_type.track_ref = track_ref;
            Ok(EntryReadType::Field(Cow::Owned(field_type)))
        }
        CodecReadType::TypeInfo(type_info) => Ok(EntryReadType::TypeInfo(type_info)),
    }
}

#[inline(always)]
fn read_entry<T: 'static, C: Codec<T>>(
    context: &mut ReadContext,
    read_type: &EntryReadType<'_>,
    track_ref: bool,
) -> Result<T, Error> {
    let ref_mode = if track_ref {
        RefMode::Tracking
    } else {
        RefMode::None
    };
    match read_type {
        EntryReadType::Direct => C::read(context, ref_mode, false),
        EntryReadType::TypeInfo(type_info) => C::read_with_type_info(context, ref_mode, type_info),
        EntryReadType::Field(field_type) if track_ref => {
            C::read_field_with_type(context, field_type)
        }
        EntryReadType::Field(field_type) => C::read_data_with_type(context, field_type),
    }
}

macro_rules! map_read_mode {
    (value, $T:ty, $S:ty, $context:expr, $ref_mode:expr, $read_type:expr) => {
        <$S as Serializer>::read($context, $ref_mode, $read_type)
    };
    (field, $T:ty, $C:ty, $context:expr, $ref_mode:expr, $read_type:expr) => {
        <$C as Serializer>::read($context, $ref_mode, $read_type)
    };
}

macro_rules! map_read_entry_type {
    (value, $T:ty, $S:ty, $context:expr, $declared:expr, $remote:expr, $index:expr, $tracked:expr) => {
        read_value_entry_type::<$T, $S>($context, $declared)
    };
    (field, $T:ty, $C:ty, $context:expr, $declared:expr, $remote:expr, $index:expr, $tracked:expr) => {
        read_entry_type::<$T, $C>($context, $declared, $remote, $index, $tracked)
    };
}

macro_rules! map_read_entry {
    (value, $T:ty, $S:ty, $context:expr, $read_type:expr, $tracked:expr) => {
        read_value_entry::<$T, $S>($context, $read_type, $tracked)
    };
    (field, $T:ty, $C:ty, $context:expr, $read_type:expr, $tracked:expr) => {
        read_entry::<$T, $C>($context, $read_type, $tracked)
    };
}

macro_rules! entry_read_data_always_advances {
    (value, $T:ty, $S:ty, $read_type:expr) => {
        <$S as Serializer>::READ_DATA_ALWAYS_ADVANCES
            && $read_type
                .as_ref()
                .map_or(true, |type_info| type_info.has_exact_local_schema())
    };
    (field, $T:ty, $C:ty, $read_type:expr) => {
        <$C as Serializer>::READ_DATA_ALWAYS_ADVANCES
            && match $read_type {
                EntryReadType::Direct => true,
                EntryReadType::TypeInfo(type_info) => type_info.has_exact_local_schema(),
                EntryReadType::Field(field_type) => field_read_data_always_advances(field_type),
            }
    };
}

#[cold]
#[inline(never)]
fn invalid_map_chunk() -> Error {
    Error::invalid_data("map chunk size must be within the remaining entry count")
}

#[cold]
#[inline(never)]
fn map_memory_overflow() -> Error {
    Error::invalid_data("graph memory estimate overflows")
}

#[cold]
#[inline(never)]
fn map_type_mismatch(remote: u32) -> Error {
    Error::type_mismatch(TypeId::MAP as u32, remote)
}

macro_rules! read_map_data_body {
    (
        $layer:ident,
        $M:ident,
        $K:ident,
        $V:ident,
        $KC:ident,
        $VC:ident,
        $context:expr,
        $remote_field_type:expr
    ) => {{
        let context = $context;
        let len = context.reader.read_var_u32()?;
        let capacity = len as usize;
        let required = if <$KC as Serializer>::READ_DATA_ALWAYS_ADVANCES
            || <$VC as Serializer>::READ_DATA_ALWAYS_ADVANCES
        {
            capacity
        } else {
            capacity.saturating_sub(context.remaining_unbacked_container_items())
        };
        context.reader.check_bound(required)?;
        let elem_bytes = std::mem::size_of::<$K>()
            .checked_add(std::mem::size_of::<$V>())
            .and_then(|bytes| bytes.checked_mul(capacity))
            .ok_or_else(map_memory_overflow)?;
        context.reserve_graph_memory(elem_bytes)?;
        let mut map = $M::with_capacity(capacity);
        let mut read = 0u32;
        while read < len {
            let header = context.reader.read_u8()?;
            if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
                map.insert($KC::default_value(context)?, $VC::default_value(context)?);
                read += 1;
                continue;
            }
            let key_declared = header & DECL_KEY_TYPE != 0;
            let value_declared = header & DECL_VALUE_TYPE != 0;
            let key_tracked = header & TRACKING_KEY_REF != 0;
            let value_tracked = header & TRACKING_VALUE_REF != 0;
            if header & KEY_NULL != 0 {
                let value = if value_tracked && !value_declared {
                    map_read_mode!($layer, $V, $VC, context, RefMode::Tracking, true)?
                } else {
                    let value_type = map_read_entry_type!(
                        $layer,
                        $V,
                        $VC,
                        context,
                        value_declared,
                        $remote_field_type,
                        1,
                        value_tracked
                    )?;
                    map_read_entry!($layer, $V, $VC, context, &value_type, value_tracked)?
                };
                map.insert($KC::default_value(context)?, value);
                read += 1;
                continue;
            }
            if header & VALUE_NULL != 0 {
                let key = if key_tracked && !key_declared {
                    map_read_mode!($layer, $K, $KC, context, RefMode::Tracking, true)?
                } else {
                    let key_type = map_read_entry_type!(
                        $layer,
                        $K,
                        $KC,
                        context,
                        key_declared,
                        $remote_field_type,
                        0,
                        key_tracked
                    )?;
                    map_read_entry!($layer, $K, $KC, context, &key_type, key_tracked)?
                };
                map.insert(key, $VC::default_value(context)?);
                read += 1;
                continue;
            }
            let chunk_size = context.reader.read_u8()? as u32;
            if chunk_size == 0 {
                return Err(invalid_map_chunk());
            }
            let end = read
                .checked_add(chunk_size)
                .filter(|end| *end <= len)
                .ok_or_else(invalid_map_chunk)?;
            let key_type = map_read_entry_type!(
                $layer,
                $K,
                $KC,
                context,
                key_declared,
                $remote_field_type,
                0,
                key_tracked
            )?;
            let value_type = map_read_entry_type!(
                $layer,
                $V,
                $VC,
                context,
                value_declared,
                $remote_field_type,
                1,
                value_tracked
            )?;
            let entry_read_always_advances = key_tracked
                || value_tracked
                || entry_read_data_always_advances!($layer, $K, $KC, &key_type)
                || entry_read_data_always_advances!($layer, $V, $VC, &value_type);
            if entry_read_always_advances {
                while read < end {
                    let key = map_read_entry!($layer, $K, $KC, context, &key_type, key_tracked)?;
                    let value =
                        map_read_entry!($layer, $V, $VC, context, &value_type, value_tracked)?;
                    map.insert(key, value);
                    read += 1;
                }
            } else {
                let chunk_start = context.reader.get_cursor();
                while read < end {
                    let key = map_read_entry!($layer, $K, $KC, context, &key_type, key_tracked)?;
                    let value =
                        map_read_entry!($layer, $V, $VC, context, &value_type, value_tracked)?;
                    map.insert(key, value);
                    read += 1;
                }
                settle_unbacked_items(context, chunk_size as usize, chunk_start)?;
            }
        }
        Ok(map)
    }};
}

fn read_value_map_data<M, K, V, KS, VS>(context: &mut ReadContext) -> Result<M, Error>
where
    K: 'static,
    V: 'static,
    KS: Serializer<Target = K>,
    VS: Serializer<Target = V>,
    M: MapTarget<K, V>,
{
    read_map_data_body!(value, M, K, V, KS, VS, context, ())
}

fn read_field_map_data<M, K, V, KC, VC>(
    context: &mut ReadContext,
    remote_field_type: Option<&FieldType>,
) -> Result<M, Error>
where
    K: 'static,
    V: 'static,
    KC: Codec<K>,
    VC: Codec<V>,
    M: MapTarget<K, V>,
{
    read_map_data_body!(field, M, K, V, KC, VC, context, remote_field_type)
}

macro_rules! impl_map_codec {
    ($codec:ident, $target:ident, [$($key_bound:tt)+]) => {
        impl<K, V, KS, VS, const NULLABLE: bool, const TRACK_REF: bool> Serializer
            for $codec<K, V, KS, VS, NULLABLE, TRACK_REF>
        where
            K: $($key_bound)+ + 'static,
            V: 'static,
            KS: Serializer<Target = K>,
            VS: Serializer<Target = V>,
        {
            type Target = $target<K, V>;

            #[inline(always)]
            fn write_data(
                value: &Self::Target,
                context: &mut WriteContext,
            ) -> Result<(), Error> {
                write_value_map_data::<K, V, KS, VS, _>(
                    value.iter(),
                    value.len(),
                    context,
                )
            }

            #[inline(always)]
            fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
                read_value_map_data::<Self::Target, K, V, KS, VS>(context)
            }

            #[inline(always)]
            fn default_value(_: &mut ReadContext) -> Result<Self::Target, Error> {
                Ok(<Self::Target as MapTarget<K, V>>::with_capacity(0))
            }

            #[inline(always)]
            fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                context.writer.write_u8(TypeId::MAP as u8);
                Ok(())
            }

            #[inline(always)]
            fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                let remote = context.reader.read_u8()? as u32;
                if remote == TypeId::MAP as u32 {
                    Ok(())
                } else {
                    Err(map_type_mismatch(remote))
                }
            }

            #[inline(always)]
            fn static_type_id() -> TypeId {
                TypeId::MAP
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                std::mem::size_of::<u32>() + SIZE_OF_REF_AND_TYPE
            }

            const READ_DATA_ALWAYS_ADVANCES: bool = true;
        }

        impl<K, V, KC, VC, const NULLABLE: bool, const TRACK_REF: bool>
            Codec<$target<K, V>> for $codec<K, V, KC, VC, NULLABLE, TRACK_REF>
        where
            K: $($key_bound)+ + 'static,
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
            fn write_field(
                value: &$target<K, V>,
                context: &mut WriteContext,
            ) -> Result<(), Error> {
                if NULLABLE || TRACK_REF {
                    context.writer.write_i8(RefFlag::NotNullValue as i8);
                }
                write_field_map_data::<K, V, KC, VC, _>(
                    value.iter(),
                    value.len(),
                    context,
                    true,
                )
            }

            #[inline(always)]
            fn read_field(context: &mut ReadContext) -> Result<$target<K, V>, Error> {
                if (NULLABLE || TRACK_REF)
                    && context.reader.read_i8()? == RefFlag::Null as i8
                {
                    return Ok(
                        <$target<K, V> as MapTarget<K, V>>::with_capacity(0)
                    );
                }
                <Self as Serializer>::read_data(context)
            }

            #[inline(always)]
            fn read_data_with_type(
                context: &mut ReadContext,
                remote_data_type: &FieldType,
            ) -> Result<$target<K, V>, Error> {
                read_field_map_data::<$target<K, V>, K, V, KC, VC>(
                    context,
                    Some(remote_data_type),
                )
            }

            #[inline(always)]
            fn read_field_with_type(
                context: &mut ReadContext,
                remote_field_type: &FieldType,
            ) -> Result<$target<K, V>, Error> {
                if field_ref_mode(remote_field_type) != RefMode::None
                    && context.reader.read_i8()? == RefFlag::Null as i8
                {
                    return Ok(
                        <$target<K, V> as MapTarget<K, V>>::with_capacity(0)
                    );
                }
                Self::read_data_with_type(context, remote_field_type)
            }

            #[inline(always)]
            fn write_with_mode(
                value: &$target<K, V>,
                context: &mut WriteContext,
                ref_mode: RefMode,
                write_type_info: bool,
                has_generics: bool,
            ) -> Result<(), Error> {
                if !has_generics {
                    return <Self as Serializer>::write(
                        value,
                        context,
                        ref_mode,
                        write_type_info,
                    );
                }
                if ref_mode != RefMode::None {
                    context.writer.write_i8(RefFlag::NotNullValue as i8);
                }
                if write_type_info {
                    <Self as Serializer>::write_type_info(context)?;
                }
                write_field_map_data::<K, V, KC, VC, _>(
                    value.iter(),
                    value.len(),
                    context,
                    true,
                )
            }
        }
    };
}

impl_map_codec!(HashMapCodec, HashMap, [Eq + std::hash::Hash]);
impl_map_codec!(BTreeMapCodec, BTreeMap, [Ord]);

macro_rules! impl_map_serializer {
    ($provider:ident, $target:ident, $codec:ident, [$($key_bound:tt)+]) => {
        #[doc = concat!(
            "Statically serializes `",
            stringify!($target),
            "<KS::Target, VS::Target>` at roots or recursive carrier nodes. ",
            "This zero-sized carrier composes its key and value serializers and is not ",
            "registered independently."
        )]
        pub struct $provider<KS, VS>(PhantomData<fn() -> (KS, VS)>);

        impl<KS, VS> Serializer for $provider<KS, VS>
        where
            KS: Serializer,
            VS: Serializer,
            KS::Target: $($key_bound)+,
        {
            type Target = $target<KS::Target, VS::Target>;

            const READ_DATA_ALWAYS_ADVANCES: bool = true;

            #[inline(always)]
            fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
                <$codec<
                    KS::Target,
                    VS::Target,
                    KS,
                    VS,
                    false,
                    false,
                > as Serializer>::write_data(value, context)
            }

            #[inline(always)]
            fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
                <$codec<
                    KS::Target,
                    VS::Target,
                    KS,
                    VS,
                    false,
                    false,
                > as Serializer>::read_data(context)
            }

            #[inline(always)]
            fn default_value(context: &mut ReadContext) -> Result<Self::Target, Error> {
                <$codec<
                    KS::Target,
                    VS::Target,
                    KS,
                    VS,
                    false,
                    false,
                > as Serializer>::default_value(context)
            }

            #[inline(always)]
            fn write(
                value: &Self::Target,
                context: &mut WriteContext,
                ref_mode: RefMode,
                write_type_info: bool,
            ) -> Result<(), Error> {
                <$codec<
                    KS::Target,
                    VS::Target,
                    KS,
                    VS,
                    false,
                    false,
                > as Serializer>::write(
                    value,
                    context,
                    ref_mode,
                    write_type_info,
                )
            }

            #[inline(always)]
            fn read(
                context: &mut ReadContext,
                ref_mode: RefMode,
                read_type_info: bool,
            ) -> Result<Self::Target, Error> {
                <$codec<
                    KS::Target,
                    VS::Target,
                    KS,
                    VS,
                    false,
                    false,
                > as Serializer>::read(
                    context,
                    ref_mode,
                    read_type_info,
                )
            }

            #[inline(always)]
            fn read_with_type_info(
                context: &mut ReadContext,
                ref_mode: RefMode,
                type_info: &Rc<TypeInfo>,
            ) -> Result<Self::Target, Error> {
                <$codec<
                    KS::Target,
                    VS::Target,
                    KS,
                    VS,
                    false,
                    false,
                > as Serializer>::read_with_type_info(
                    context,
                    ref_mode,
                    type_info,
                )
            }

            #[inline(always)]
            fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                <$codec<
                    KS::Target,
                    VS::Target,
                    KS,
                    VS,
                    false,
                    false,
                > as Serializer>::write_type_info(context)
            }

            #[inline(always)]
            fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                <$codec<
                    KS::Target,
                    VS::Target,
                    KS,
                    VS,
                    false,
                    false,
                > as Serializer>::read_type_info(context)
            }

            #[inline(always)]
            fn static_type_id() -> TypeId {
                TypeId::MAP
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                std::mem::size_of::<u32>() + SIZE_OF_REF_AND_TYPE
            }
        }

        impl<K, V> Serializer for $target<K, V>
        where
            K: Serializer<Target = K> + $($key_bound)+,
            V: Serializer<Target = V>,
        {
            type Target = Self;

            const READ_DATA_ALWAYS_ADVANCES: bool = true;

            #[inline(always)]
            fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
                <$provider<K, V> as Serializer>::write_data(value, context)
            }

            #[inline(always)]
            fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
                <$provider<K, V> as Serializer>::read_data(context)
            }

            #[inline(always)]
            fn default_value(context: &mut ReadContext) -> Result<Self, Error> {
                <$provider<K, V> as Serializer>::default_value(context)
            }

            #[inline(always)]
            fn write(
                value: &Self,
                context: &mut WriteContext,
                ref_mode: RefMode,
                write_type_info: bool,
            ) -> Result<(), Error> {
                <$provider<K, V> as Serializer>::write(
                    value,
                    context,
                    ref_mode,
                    write_type_info,
                )
            }

            #[inline(always)]
            fn read(
                context: &mut ReadContext,
                ref_mode: RefMode,
                read_type_info: bool,
            ) -> Result<Self, Error> {
                <$provider<K, V> as Serializer>::read(
                    context,
                    ref_mode,
                    read_type_info,
                )
            }

            #[inline(always)]
            fn read_with_type_info(
                context: &mut ReadContext,
                ref_mode: RefMode,
                type_info: &Rc<TypeInfo>,
            ) -> Result<Self, Error> {
                <$provider<K, V> as Serializer>::read_with_type_info(
                    context,
                    ref_mode,
                    type_info,
                )
            }

            #[inline(always)]
            fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                <$provider<K, V> as Serializer>::write_type_info(context)
            }

            #[inline(always)]
            fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                <$provider<K, V> as Serializer>::read_type_info(context)
            }

            #[inline(always)]
            fn static_type_id() -> TypeId {
                TypeId::MAP
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                std::mem::size_of::<u32>() + SIZE_OF_REF_AND_TYPE
            }
        }
    };
}

impl_map_serializer!(
    HashMapSerializer,
    HashMap,
    HashMapCodec,
    [Eq + std::hash::Hash]
);
impl_map_serializer!(BTreeMapSerializer, BTreeMap, BTreeMapCodec, [Ord]);

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Config, Reader};

    #[test]
    fn empty_entries_use_root_budget() {
        let bytes = [3, DECL_KEY_TYPE | DECL_VALUE_TYPE, 3];
        let mut context = ReadContext::new(TypeResolver::default(), Config::default());
        context.remaining_graph_memory_bytes = usize::MAX;
        context.remaining_unbacked_container_items = 2;
        context.attach_reader(Reader::new(&bytes));

        let error =
            read_value_map_data::<BTreeMap<(), ()>, (), (), (), ()>(&mut context).unwrap_err();
        assert!(error.to_string().contains("max_unbacked_container_items"));
    }
}
