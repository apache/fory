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
use crate::fory::Fory;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::primitive_list;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::any::TypeId as RsTypeId;
use std::collections::{LinkedList, VecDeque};
use std::mem;

use super::collection::{
    read_collection, read_collection_type_info, write_collection, write_collection_type_info,
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
    fn fory_write_data(&self, fory: &Fory, context: &mut WriteContext, is_field: bool) {
        match check_primitive::<T>() {
            Some(_) => {
                primitive_list::fory_write_data(self, context);
            }
            None => {
                write_collection(self, fory, context, is_field);
            }
        }
    }

    fn fory_write_type_info(_fory: &Fory, context: &mut WriteContext, is_field: bool) {
        match check_primitive::<T>() {
            Some(type_id) => {
                primitive_list::fory_write_type_info(context, is_field, type_id);
            }
            None => {
                write_collection_type_info(context, is_field, TypeId::LIST as u32);
            }
        }
    }

    fn fory_read_data(
        fory: &Fory,
        context: &mut ReadContext,
        _is_field: bool,
    ) -> Result<Self, Error> {
        match check_primitive::<T>() {
            Some(_) => primitive_list::fory_read_data(context),
            None => read_collection(fory, context),
        }
    }

    fn fory_read_type_info(_fory: &Fory, context: &mut ReadContext, is_field: bool) {
        match check_primitive::<T>() {
            Some(type_id) => primitive_list::fory_read_type_info(context, is_field, type_id),
            None => read_collection_type_info(context, is_field, TypeId::LIST as u32),
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

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        match check_primitive::<T>() {
            Some(type_id) => type_id as u32,
            None => TypeId::LIST as u32,
        }
    }

    fn fory_type_id_dyn(&self, _fory: &Fory) -> u32 {
        match check_primitive::<T>() {
            Some(type_id) => type_id as u32,
            None => TypeId::LIST as u32,
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
    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
        write_collection(self, context, is_field);
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
        write_collection_type_info(context, is_field, TypeId::LIST as u32);
    }

    fn fory_read_data(context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        read_collection(context)
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
        read_collection_type_info(context, is_field, TypeId::LIST as u32);
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<u32>()
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        TypeId::LIST as u32
    }

    fn fory_type_id_dyn(&self, _fory: &Fory) -> u32 {
        TypeId::LIST as u32
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
    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
        write_collection(self, context, is_field);
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
        write_collection_type_info(context, is_field, TypeId::LIST as u32);
    }

    fn fory_read_data(context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        read_collection(context)
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
        read_collection_type_info(context, is_field, TypeId::LIST as u32);
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<u32>()
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        TypeId::LIST as u32
    }

    fn fory_type_id_dyn(&self, _fory: &Fory) -> u32 {
        TypeId::LIST as u32
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
