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

use crate::error::Error;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::primitive_list;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::collections::{LinkedList, VecDeque};
use std::mem;

use super::collection::{
    read_collection_data, read_collection_type_info, read_vec_data, write_collection_data,
    write_collection_type_info,
};

#[inline(always)]
pub(super) fn get_primitive_type_id<T: Serializer>() -> TypeId {
    if T::fory_is_wrapper_type() {
        return TypeId::UNKNOWN;
    }
    match T::fory_static_type_id() {
        TypeId::BOOL => TypeId::BOOL_ARRAY,
        TypeId::INT8 => TypeId::INT8_ARRAY,
        TypeId::INT16 => TypeId::INT16_ARRAY,
        // Handle both INT32 and VARINT32 (i32 uses VARINT32 in xlang mode)
        TypeId::INT32 | TypeId::VARINT32 => TypeId::INT32_ARRAY,
        // Handle INT64, VARINT64, and TAGGED_INT64 (i64 uses VARINT64 in xlang mode)
        TypeId::INT64 | TypeId::VARINT64 | TypeId::TAGGED_INT64 => TypeId::INT64_ARRAY,
        TypeId::FLOAT16 => TypeId::FLOAT16_ARRAY,
        TypeId::FLOAT32 => TypeId::FLOAT32_ARRAY,
        TypeId::FLOAT64 => TypeId::FLOAT64_ARRAY,
        TypeId::UINT8 => TypeId::BINARY,
        TypeId::UINT16 => TypeId::UINT16_ARRAY,
        // Handle both UINT32 and VAR_UINT32 (u32 uses VAR_UINT32 in xlang mode)
        TypeId::UINT32 | TypeId::VAR_UINT32 => TypeId::UINT32_ARRAY,
        // Handle UINT64, VAR_UINT64, and TAGGED_UINT64 (u64 uses VAR_UINT64 in xlang mode)
        TypeId::UINT64 | TypeId::VAR_UINT64 | TypeId::TAGGED_UINT64 => TypeId::UINT64_ARRAY,
        TypeId::U128 => TypeId::U128_ARRAY,
        TypeId::INT128 => TypeId::INT128_ARRAY,
        TypeId::USIZE => TypeId::USIZE_ARRAY,
        TypeId::ISIZE => TypeId::ISIZE_ARRAY,
        _ => TypeId::UNKNOWN,
    }
}

#[inline(always)]
pub(super) fn is_primitive_type<T: Serializer>() -> bool {
    if T::fory_is_wrapper_type() {
        return false;
    }
    matches!(
        T::fory_static_type_id(),
        TypeId::BOOL
            | TypeId::INT8
            | TypeId::INT16
            | TypeId::INT32
            | TypeId::VARINT32
            | TypeId::INT64
            | TypeId::VARINT64
            | TypeId::TAGGED_INT64
            | TypeId::INT128
            | TypeId::FLOAT16
            | TypeId::FLOAT32
            | TypeId::FLOAT64
            | TypeId::UINT8
            | TypeId::UINT16
            | TypeId::UINT32
            | TypeId::VAR_UINT32
            | TypeId::UINT64
            | TypeId::VAR_UINT64
            | TypeId::TAGGED_UINT64
            | TypeId::U128,
    )
}

impl<T: Serializer + ForyDefault> Serializer for Vec<T> {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        if is_primitive_type::<T>() {
            primitive_list::fory_write_data(self, context)
        } else {
            write_collection_data(self, context, false)
        }
    }

    #[inline(always)]
    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        if is_primitive_type::<T>() {
            primitive_list::fory_write_data(self, context)
        } else {
            write_collection_data(self, context, has_generics)
        }
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        let id = get_primitive_type_id::<T>();
        if id != TypeId::UNKNOWN {
            primitive_list::fory_write_type_info(context, id)
        } else {
            write_collection_type_info(context, TypeId::LIST as u32)
        }
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        if is_primitive_type::<T>() {
            primitive_list::fory_read_data(context)
        } else {
            read_vec_data(context)
        }
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        let id = get_primitive_type_id::<T>();
        if id != TypeId::UNKNOWN {
            primitive_list::fory_read_type_info(context, id)
        } else {
            read_collection_type_info(context, TypeId::LIST as u32)
        }
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        if is_primitive_type::<T>() {
            primitive_list::fory_reserved_space::<T>()
        } else {
            // size of the vec
            mem::size_of::<u32>()
        }
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        let id = get_primitive_type_id::<T>();
        if id != TypeId::UNKNOWN {
            Ok(id)
        } else {
            Ok(TypeId::LIST)
        }
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        let id = get_primitive_type_id::<T>();
        if id != TypeId::UNKNOWN {
            Ok(id)
        } else {
            Ok(TypeId::LIST)
        }
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        let id = get_primitive_type_id::<T>();
        if id != TypeId::UNKNOWN {
            id
        } else {
            TypeId::LIST
        }
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T> ForyDefault for Vec<T> {
    #[inline(always)]
    fn fory_default() -> Self {
        Vec::new()
    }
}

impl<T: Serializer + ForyDefault> Serializer for VecDeque<T> {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_collection_data(self, context, false)
    }

    #[inline(always)]
    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_collection_data(self, context, has_generics)
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        write_collection_type_info(context, TypeId::LIST as u32)
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        read_collection_data(context)
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_collection_type_info(context, TypeId::LIST as u32)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        mem::size_of::<u32>()
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::LIST)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::LIST)
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId {
        TypeId::LIST
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T> ForyDefault for VecDeque<T> {
    #[inline(always)]
    fn fory_default() -> Self {
        VecDeque::new()
    }
}

impl<T: Serializer + ForyDefault> Serializer for LinkedList<T> {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_collection_data(self, context, false)
    }

    #[inline(always)]
    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_collection_data(self, context, has_generics)
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        write_collection_type_info(context, TypeId::LIST as u32)
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        read_collection_data(context)
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_collection_type_info(context, TypeId::LIST as u32)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        mem::size_of::<u32>()
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::LIST)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::LIST)
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId {
        TypeId::LIST
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T> ForyDefault for LinkedList<T> {
    #[inline(always)]
    fn fory_default() -> Self {
        LinkedList::new()
    }
}
