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
use std::any::TypeId as RsTypeId;
use std::collections::{LinkedList, VecDeque};
use std::mem;

use super::collection::{
    read_collection_data, read_collection_type_info, write_collection_data,
    write_collection_type_info,
};

fn check_primitive<T: 'static>() -> Option<TypeId> {
    Some(match RsTypeId::of::<T>() {
        id if id == RsTypeId::of::<bool>() => TypeId::BOOL_ARRAY,
        id if id == RsTypeId::of::<i8>() => TypeId::INT8_ARRAY,
        id if id == RsTypeId::of::<i16>() => TypeId::INT16_ARRAY,
        id if id == RsTypeId::of::<i32>() => TypeId::INT32_ARRAY,
        id if id == RsTypeId::of::<i64>() => TypeId::INT64_ARRAY,
        id if id == RsTypeId::of::<f32>() => TypeId::FLOAT32_ARRAY,
        id if id == RsTypeId::of::<f64>() => TypeId::FLOAT64_ARRAY,
        _ => return None,
    })
}

impl<T: Serializer + ForyDefault> Serializer for Vec<T> {
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        match check_primitive::<T>() {
            Some(_) => primitive_list::fory_write_data(self, context),
            None => write_collection_data(self, context, false),
        }
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        match check_primitive::<T>() {
            Some(_) => primitive_list::fory_write_data(self, context),
            None => write_collection_data(self, context, has_generics),
        }
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        match check_primitive::<T>() {
            Some(type_id) => primitive_list::fory_write_type_info(context, type_id),
            None => write_collection_type_info(context, TypeId::LIST as u32),
        }
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        match check_primitive::<T>() {
            Some(_) => primitive_list::fory_read_data(context),
            None => read_collection_data(context),
        }
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        match check_primitive::<T>() {
            Some(type_id) => primitive_list::fory_read_type_info(context, type_id),
            None => read_collection_type_info(context, TypeId::LIST as u32),
        }
    }

    fn fory_reserved_space() -> usize {
        match check_primitive::<T>() {
            Some(_) => primitive_list::fory_reserved_space::<T>(),
            None => {
                // size of the vec
                mem::size_of::<u32>()
            }
        }
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(match check_primitive::<T>() {
            Some(type_id) => type_id as u32,
            None => TypeId::LIST as u32,
        })
    }

    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(match check_primitive::<T>() {
            Some(type_id) => type_id as u32,
            None => TypeId::LIST as u32,
        })
    }

    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        match check_primitive::<T>() {
            Some(type_id) => type_id,
            None => TypeId::LIST,
        }
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T> ForyDefault for Vec<T> {
    fn fory_default() -> Self {
        Vec::new()
    }
}

impl<T: Serializer + ForyDefault> Serializer for VecDeque<T> {
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_collection_data(self, context, false)
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_collection_data(self, context, has_generics)
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        write_collection_type_info(context, TypeId::LIST as u32)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        read_collection_data(context)
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_collection_type_info(context, TypeId::LIST as u32)
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<u32>()
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::LIST as u32)
    }

    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::LIST as u32)
    }

    fn fory_static_type_id() -> TypeId {
        TypeId::LIST
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T> ForyDefault for VecDeque<T> {
    fn fory_default() -> Self {
        VecDeque::new()
    }
}

impl<T: Serializer + ForyDefault> Serializer for LinkedList<T> {
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_collection_data(self, context, false)
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_collection_data(self, context, has_generics)
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        write_collection_type_info(context, TypeId::LIST as u32)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        read_collection_data(context)
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_collection_type_info(context, TypeId::LIST as u32)
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<u32>()
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::LIST as u32)
    }

    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::LIST as u32)
    }

    fn fory_static_type_id() -> TypeId {
        TypeId::LIST
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T> ForyDefault for LinkedList<T> {
    fn fory_default() -> Self {
        LinkedList::new()
    }
}
