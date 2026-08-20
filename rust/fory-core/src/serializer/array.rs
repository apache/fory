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
    allows_missing_generics, field_ref_mode, field_types_compatible, generic_field_type, Codec,
    CodecReadType,
};
use super::collection::{
    write_collection_data, write_collection_value_data, DECL_ELEMENT_TYPE, HAS_NULL, IS_SAME_TYPE,
    TRACKING_REF,
};
use super::primitive_list;
use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::meta::FieldType;
use crate::resolver::{RefFlag, RefMode, TypeInfo, TypeResolver};
use crate::serializer::{core::read_value_type_info, Serializer};
use crate::type_id::{TypeId, SIZE_OF_REF_AND_TYPE};
use std::marker::PhantomData;
use std::mem::MaybeUninit;
use std::rc::Rc;

pub struct ArrayCodec<T, C, const N: usize, const NULLABLE: bool, const TRACK_REF: bool>(
    PhantomData<(T, C)>,
);

struct ArrayInitGuard<T, const N: usize> {
    values: [MaybeUninit<T>; N],
    initialized: usize,
}

impl<T, const N: usize> ArrayInitGuard<T, N> {
    #[inline(always)]
    fn new() -> Self {
        Self {
            values: unsafe { MaybeUninit::uninit().assume_init() },
            initialized: 0,
        }
    }

    #[inline(always)]
    fn push(&mut self, value: T) {
        self.values[self.initialized].write(value);
        self.initialized += 1;
    }

    #[inline(always)]
    unsafe fn finish(self) -> [T; N] {
        debug_assert_eq!(self.initialized, N);
        let result = std::ptr::read(self.values.as_ptr().cast::<[T; N]>());
        std::mem::forget(self);
        result
    }
}

impl<T, const N: usize> Drop for ArrayInitGuard<T, N> {
    fn drop(&mut self) {
        for value in &mut self.values[..self.initialized] {
            unsafe {
                value.assume_init_drop();
            }
        }
    }
}

pub(super) fn try_init_array<T, E, const N: usize>(
    mut read: impl FnMut() -> Result<T, E>,
) -> Result<[T; N], E> {
    let mut values = ArrayInitGuard::<T, N>::new();
    for _ in 0..N {
        values.push(read()?);
    }
    Ok(unsafe { values.finish() })
}

#[inline(always)]
fn selected_array_type_id<T: 'static, S: Serializer<Target = T>>() -> Option<TypeId> {
    primitive_list::array_type_id::<T, S>(false)
}

#[cold]
#[inline(never)]
fn array_len_mismatch(len: usize, expected: usize) -> Error {
    Error::invalid_data(format!(
        "Array length mismatch: expected {expected}, got {len}"
    ))
}

#[cold]
#[inline(never)]
fn non_polymorphic_array() -> Error {
    Error::type_error("Type inconsistent, target array element is not polymorphic")
}

#[cold]
#[inline(never)]
fn array_type_mismatch(expected: u32, actual: u32) -> Error {
    Error::type_mismatch(expected, actual)
}

#[inline(always)]
fn check_array_len(len: u32, expected: usize) -> Result<(), Error> {
    let len = len as usize;
    if len != expected {
        return Err(array_len_mismatch(len, expected));
    }
    // N is compile-time and fixed-array reads do not allocate from this wire
    // count; each concrete child body retains its own readability checks.
    Ok(())
}

#[inline(always)]
fn read_declared_items<T, C, const N: usize>(
    context: &mut ReadContext,
    element_type: &FieldType,
    has_null: bool,
) -> Result<[T; N], Error>
where
    T: 'static,
    C: Codec<T>,
{
    try_init_array(|| {
        if has_null && context.reader.read_i8()? == RefFlag::Null as i8 {
            C::default_value(context)
        } else {
            C::read_data_with_type(context, element_type)
        }
    })
}

#[inline(always)]
fn read_typed_items<T, C, const N: usize>(
    context: &mut ReadContext,
    read_type: &CodecReadType,
    has_null: bool,
) -> Result<[T; N], Error>
where
    T: 'static,
    C: Codec<T>,
{
    try_init_array(|| {
        if has_null && context.reader.read_i8()? == RefFlag::Null as i8 {
            return C::default_value(context);
        }
        match read_type {
            CodecReadType::Field(field_type) => C::read_data_with_type(context, field_type),
            CodecReadType::TypeInfo(type_info) => C::read_data_with_type_info(context, type_info),
        }
    })
}

macro_rules! array_read_declared_dyn {
    (value, $T:ty, $S:ty, $N:ident, $context:expr, $remote:expr, $ref_mode:expr, $has_null:expr, $track_ref:expr) => {
        try_init_array(|| <$S as Serializer>::read($context, $ref_mode, false))
    };
    (field, $T:ty, $C:ty, $N:ident, $context:expr, $remote:expr, $ref_mode:expr, $has_null:expr, $track_ref:expr) => {{
        let element_type = generic_field_type($remote, 0, "array")?;
        if field_ref_mode(element_type) != $ref_mode {
            return Err(array_ref_mismatch());
        }
        try_init_array(|| <$C as Codec<$T>>::read_field_with_type($context, element_type))
    }};
}

macro_rules! array_read_declared {
    (value, $T:ty, $S:ty, $N:ident, $context:expr, $remote:expr, $has_null:expr) => {
        try_init_array(|| {
            if $has_null && $context.reader.read_i8()? == RefFlag::Null as i8 {
                <$S as Serializer>::default_value($context)
            } else {
                <$S as Serializer>::read_data($context)
            }
        })
    };
    (field, $T:ty, $C:ty, $N:ident, $context:expr, $remote:expr, $has_null:expr) => {{
        let element_type = generic_field_type($remote, 0, "array")?;
        read_declared_items::<$T, $C, $N>($context, element_type, $has_null)
    }};
}

macro_rules! array_read_typed {
    (value, $T:ty, $S:ty, $N:ident, $context:expr, $has_null:expr) => {{
        let type_info = read_value_type_info::<$S>($context)?;
        try_init_array(|| {
            if $has_null && $context.reader.read_i8()? == RefFlag::Null as i8 {
                return <$S as Serializer>::default_value($context);
            }
            match type_info.as_ref() {
                Some(type_info) => {
                    <$S as Serializer>::read_with_type_info($context, RefMode::None, type_info)
                }
                None => <$S as Serializer>::read_data($context),
            }
        })
    }};
    (field, $T:ty, $C:ty, $N:ident, $context:expr, $has_null:expr) => {{
        let read_type = <$C as Codec<$T>>::read_type_info_value($context)?;
        read_typed_items::<$T, $C, $N>($context, &read_type, $has_null)
    }};
}

macro_rules! read_object_array_body {
    ($layer:ident, $T:ident, $C:ident, $N:ident, $context:expr, $remote:expr) => {{
        let context = $context;
        let len = context.reader.read_var_u32()?;
        check_array_len(len, $N)?;
        if $N == 0 {
            return try_init_array(|| unreachable!());
        }
        let header = context.reader.read_u8()?;
        let track_ref = (header & TRACKING_REF) != 0;
        let same_type = (header & IS_SAME_TYPE) != 0;
        let has_null = (header & HAS_NULL) != 0;
        let declared = (header & DECL_ELEMENT_TYPE) != 0;
        let ref_mode = if track_ref {
            RefMode::Tracking
        } else if has_null {
            RefMode::NullOnly
        } else {
            RefMode::None
        };

        if $C::IS_POLYMORPHIC || $C::IS_SHARED_REF {
            if same_type {
                if declared {
                    return array_read_declared_dyn!(
                        $layer, $T, $C, $N, context, $remote, ref_mode, has_null, track_ref
                    );
                }
                let type_info = context.read_any_type_info()?;
                return try_init_array(|| {
                    <$C as Serializer>::read_with_type_info(context, ref_mode, &type_info)
                });
            }
            return try_init_array(|| <$C as Serializer>::read(context, ref_mode, true));
        }

        if !same_type {
            return Err(non_polymorphic_array());
        }
        if declared {
            return array_read_declared!($layer, $T, $C, $N, context, $remote, has_null);
        }
        array_read_typed!($layer, $T, $C, $N, context, has_null)
    }};
}

#[inline(always)]
fn read_value_object_array<T, S, const N: usize>(context: &mut ReadContext) -> Result<[T; N], Error>
where
    T: 'static,
    S: Serializer<Target = T>,
{
    read_object_array_body!(value, T, S, N, context, ())
}

#[inline(always)]
fn read_field_object_array<T, C, const N: usize>(
    context: &mut ReadContext,
    remote_field_type: &FieldType,
) -> Result<[T; N], Error>
where
    T: 'static,
    C: Codec<T>,
{
    read_object_array_body!(field, T, C, N, context, remote_field_type)
}

#[cold]
#[inline(never)]
fn array_ref_mismatch() -> Error {
    Error::invalid_data("array header conflicts with declared element metadata")
}

impl<T, S, const N: usize, const NULLABLE: bool, const TRACK_REF: bool> Serializer
    for ArrayCodec<T, S, N, NULLABLE, TRACK_REF>
where
    T: 'static,
    S: Serializer<Target = T>,
{
    type Target = [T; N];

    const READ_DATA_ALWAYS_ADVANCES: bool = true;

    #[inline(always)]
    fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
        if let Some(type_id) = selected_array_type_id::<T, S>() {
            return primitive_list::write_data::<T, S>(value, context, type_id);
        }
        write_collection_value_data::<T, S, _, false, true>(value.iter(), context)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
        if let Some(type_id) = selected_array_type_id::<T, S>() {
            return primitive_list::read_array::<T, S, N>(context, type_id);
        }
        read_value_object_array::<T, S, N>(context)
    }

    #[inline(always)]
    fn default_value(context: &mut ReadContext) -> Result<Self::Target, Error> {
        try_init_array(|| S::default_value(context))
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        match selected_array_type_id::<T, S>() {
            Some(type_id) => primitive_list::write_type_info(context, type_id),
            None => {
                context.writer.write_u8(TypeId::LIST as u8);
                Ok(())
            }
        }
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        match selected_array_type_id::<T, S>() {
            Some(type_id) => primitive_list::read_type_info(context, type_id),
            None => {
                let remote = context.reader.read_u8()? as u32;
                if remote != TypeId::LIST as u32 {
                    return Err(array_type_mismatch(TypeId::LIST as u32, remote));
                }
                Ok(())
            }
        }
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        selected_array_type_id::<T, S>().unwrap_or(TypeId::LIST)
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        match selected_array_type_id::<T, S>() {
            Some(_) => std::mem::size_of::<T>() * N + SIZE_OF_REF_AND_TYPE,
            None => std::mem::size_of::<u32>() + SIZE_OF_REF_AND_TYPE,
        }
    }
}

impl<T, C, const N: usize, const NULLABLE: bool, const TRACK_REF: bool> Codec<[T; N]>
    for ArrayCodec<T, C, N, NULLABLE, TRACK_REF>
where
    T: 'static,
    C: Codec<T>,
{
    #[inline(always)]
    fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
        if let Some(type_id) = selected_array_type_id::<T, C>() {
            return Ok(FieldType::new_with_ref(
                type_id as u32,
                NULLABLE,
                TRACK_REF,
                Vec::new(),
            ));
        }
        Ok(FieldType::new_with_ref(
            TypeId::LIST as u32,
            NULLABLE,
            TRACK_REF,
            vec![C::field_type(type_resolver)?],
        ))
    }

    #[inline(always)]
    fn write_field(value: &[T; N], context: &mut WriteContext) -> Result<(), Error> {
        if NULLABLE || TRACK_REF {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        if selected_array_type_id::<T, C>().is_some() {
            <Self as Serializer>::write_data(value, context)
        } else {
            write_collection_data::<T, C, _, false, true>(value.iter(), context, true)
        }
    }

    #[inline(always)]
    fn read_field(context: &mut ReadContext) -> Result<[T; N], Error> {
        if (NULLABLE || TRACK_REF) && context.reader.read_i8()? == RefFlag::Null as i8 {
            return <Self as Serializer>::default_value(context);
        }
        <Self as Serializer>::read_data(context)
    }

    #[inline(always)]
    fn read_compatible(
        context: &mut ReadContext,
        local_field_type: &FieldType,
        remote_field_type: &FieldType,
    ) -> Result<Option<[T; N]>, Error> {
        if field_types_compatible(local_field_type, remote_field_type)
            || local_field_type.compatible_shape_match(remote_field_type)
            || (local_field_type.type_id == remote_field_type.type_id
                && allows_missing_generics(local_field_type.type_id)
                && (local_field_type.generics.is_empty() || remote_field_type.generics.is_empty()))
        {
            return Self::read_field_with_type(context, remote_field_type).map(Some);
        }
        Ok(None)
    }

    #[inline(always)]
    fn read_data_with_type(
        context: &mut ReadContext,
        remote_data_type: &FieldType,
    ) -> Result<[T; N], Error> {
        if let Some(type_id) = selected_array_type_id::<T, C>() {
            if remote_data_type.type_id != TypeId::LIST as u32 {
                return primitive_list::read_array::<T, C, N>(context, type_id);
            }
        }
        read_field_object_array::<T, C, N>(context, remote_data_type)
    }

    #[inline(always)]
    fn read_field_with_type(
        context: &mut ReadContext,
        remote_field_type: &FieldType,
    ) -> Result<[T; N], Error> {
        if field_ref_mode(remote_field_type) != RefMode::None
            && context.reader.read_i8()? == RefFlag::Null as i8
        {
            return <Self as Serializer>::default_value(context);
        }
        Self::read_data_with_type(context, remote_field_type)
    }

    #[inline(always)]
    fn write_with_mode(
        value: &[T; N],
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if selected_array_type_id::<T, C>().is_some() || !has_generics {
            return <Self as Serializer>::write(value, context, ref_mode, write_type_info);
        }
        if ref_mode != RefMode::None {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        if write_type_info {
            <Self as Serializer>::write_type_info(context)?;
        }
        write_collection_data::<T, C, _, false, true>(value.iter(), context, true)
    }
}

type RootArrayCodec<S, const N: usize> = ArrayCodec<<S as Serializer>::Target, S, N, false, false>;

/// Statically serializes `[S::Target; N]` at roots or recursive carrier nodes.
///
/// This zero-sized carrier composes the child serializer `S` and is not
/// registered independently.
pub struct ArraySerializer<S, const N: usize>(PhantomData<fn() -> S>);

impl<S: Serializer, const N: usize> Serializer for ArraySerializer<S, N> {
    type Target = [S::Target; N];

    const READ_DATA_ALWAYS_ADVANCES: bool = true;

    #[inline(always)]
    fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
        <RootArrayCodec<S, N> as Serializer>::write_data(value, context)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
        <RootArrayCodec<S, N> as Serializer>::read_data(context)
    }

    #[inline(always)]
    fn default_value(context: &mut ReadContext) -> Result<Self::Target, Error> {
        <RootArrayCodec<S, N> as Serializer>::default_value(context)
    }

    #[inline(always)]
    fn write(
        value: &Self::Target,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        <RootArrayCodec<S, N> as Serializer>::write(value, context, ref_mode, write_type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self::Target, Error> {
        <RootArrayCodec<S, N> as Serializer>::read(context, ref_mode, read_type_info)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Self::Target, Error> {
        <RootArrayCodec<S, N> as Serializer>::read_with_type_info(context, ref_mode, type_info)
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        <RootArrayCodec<S, N> as Serializer>::write_type_info(context)
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        <RootArrayCodec<S, N> as Serializer>::read_type_info(context)
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        <RootArrayCodec<S, N> as Serializer>::static_type_id()
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        <RootArrayCodec<S, N> as Serializer>::reserved_space()
    }
}

impl<T, const N: usize> Serializer for [T; N]
where
    T: Serializer<Target = T>,
{
    type Target = Self;

    const READ_DATA_ALWAYS_ADVANCES: bool = true;

    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        <ArraySerializer<T, N> as Serializer>::write_data(value, context)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        <ArraySerializer<T, N> as Serializer>::read_data(context)
    }

    #[inline(always)]
    fn default_value(context: &mut ReadContext) -> Result<Self, Error> {
        <ArraySerializer<T, N> as Serializer>::default_value(context)
    }

    #[inline(always)]
    fn write(
        value: &Self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        <ArraySerializer<T, N> as Serializer>::write(value, context, ref_mode, write_type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        <ArraySerializer<T, N> as Serializer>::read(context, ref_mode, read_type_info)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Self, Error> {
        <ArraySerializer<T, N> as Serializer>::read_with_type_info(context, ref_mode, type_info)
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        <ArraySerializer<T, N> as Serializer>::write_type_info(context)
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        <ArraySerializer<T, N> as Serializer>::read_type_info(context)
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        <ArraySerializer<T, N> as Serializer>::static_type_id()
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        <ArraySerializer<T, N> as Serializer>::reserved_space()
    }
}
