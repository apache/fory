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

use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::resolver::{RefMode, TypeInfo};
use crate::serializer::codec::OptionCodec;
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use std::marker::PhantomData;
use std::rc::Rc;

/// Fory-owned static composition for `Option<S::Target>`.
pub struct OptionSerializer<S>(PhantomData<fn() -> S>);

type RootSerializer<S> = OptionCodec<<S as Serializer>::Target, S, false>;

impl<S: Serializer> Serializer for OptionSerializer<S> {
    type Target = Option<S::Target>;

    #[inline(always)]
    fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
        <RootSerializer<S> as Serializer>::write_data(value, context)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
        <RootSerializer<S> as Serializer>::read_data(context)
    }

    #[inline(always)]
    fn default_value(context: &mut ReadContext) -> Result<Self::Target, Error> {
        <RootSerializer<S> as Serializer>::default_value(context)
    }

    #[inline(always)]
    fn write(
        value: &Self::Target,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        <RootSerializer<S> as Serializer>::write(value, context, ref_mode, write_type_info)
    }

    #[inline(always)]
    fn write_type_info_value(
        context: &mut WriteContext,
        target_type_id: std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        <RootSerializer<S> as Serializer>::write_type_info_value(context, target_type_id)
    }

    #[inline(always)]
    fn write_with_type_info(
        value: &Self::Target,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        <RootSerializer<S> as Serializer>::write_with_type_info(value, context, ref_mode, type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self::Target, Error> {
        <RootSerializer<S> as Serializer>::read(context, ref_mode, read_type_info)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Self::Target, Error> {
        <RootSerializer<S> as Serializer>::read_with_type_info(context, ref_mode, type_info)
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        <RootSerializer<S> as Serializer>::write_type_info(context)
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        <RootSerializer<S> as Serializer>::read_type_info(context)
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        <RootSerializer<S> as Serializer>::static_type_id()
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        <RootSerializer<S> as Serializer>::reserved_space()
    }

    const IS_OPTIONAL: bool = true;

    const IS_POLYMORPHIC: bool = <RootSerializer<S> as Serializer>::IS_POLYMORPHIC;

    const IS_SHARED_REF: bool = <RootSerializer<S> as Serializer>::IS_SHARED_REF;

    const IS_WRAPPER: bool = true;

    const REQUIRES_SCOPED_ACCESS: bool = S::REQUIRES_SCOPED_ACCESS;

    const READ_DATA_ALWAYS_ADVANCES: bool = S::READ_DATA_ALWAYS_ADVANCES;

    #[inline(always)]
    fn is_none(value: &Self::Target) -> bool {
        value.is_none()
    }

    #[inline(always)]
    fn dynamic_type_id(value: &Self::Target) -> Result<Option<std::any::TypeId>, Error> {
        match value {
            Some(value) => S::dynamic_type_id(value),
            None => Ok(None),
        }
    }
}

impl<T> Serializer for Option<T>
where
    T: Serializer<Target = T>,
{
    type Target = Self;

    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        OptionSerializer::<T>::write_data(value, context)
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        OptionSerializer::<T>::read_data(context)
    }

    #[inline(always)]
    fn default_value(context: &mut ReadContext) -> Result<Self, Error> {
        OptionSerializer::<T>::default_value(context)
    }

    #[inline(always)]
    fn write(
        value: &Self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        OptionSerializer::<T>::write(value, context, ref_mode, write_type_info)
    }

    #[inline(always)]
    fn write_type_info_value(
        context: &mut WriteContext,
        target_type_id: std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        OptionSerializer::<T>::write_type_info_value(context, target_type_id)
    }

    #[inline(always)]
    fn write_with_type_info(
        value: &Self,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        OptionSerializer::<T>::write_with_type_info(value, context, ref_mode, type_info)
    }

    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        OptionSerializer::<T>::read(context, ref_mode, read_type_info)
    }

    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Self, Error> {
        OptionSerializer::<T>::read_with_type_info(context, ref_mode, type_info)
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        OptionSerializer::<T>::write_type_info(context)
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        OptionSerializer::<T>::read_type_info(context)
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        OptionSerializer::<T>::static_type_id()
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        OptionSerializer::<T>::reserved_space()
    }

    const IS_OPTIONAL: bool = true;

    const IS_POLYMORPHIC: bool = OptionSerializer::<T>::IS_POLYMORPHIC;

    const IS_SHARED_REF: bool = OptionSerializer::<T>::IS_SHARED_REF;

    const IS_WRAPPER: bool = true;

    const REQUIRES_SCOPED_ACCESS: bool = OptionSerializer::<T>::REQUIRES_SCOPED_ACCESS;

    const READ_DATA_ALWAYS_ADVANCES: bool = T::READ_DATA_ALWAYS_ADVANCES;

    #[inline(always)]
    fn is_none(value: &Self) -> bool {
        value.is_none()
    }

    #[inline(always)]
    fn dynamic_type_id(value: &Self) -> Result<Option<std::any::TypeId>, Error> {
        OptionSerializer::<T>::dynamic_type_id(value)
    }
}
