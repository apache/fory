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
use crate::serializer::collection::{
    read_collection_data, read_collection_type_info, write_collection_data,
    write_collection_type_info,
};

use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::collections::{BTreeSet, HashSet};
use std::mem;

impl<T: Serializer + ForyDefault + Eq + std::hash::Hash> Serializer for HashSet<T> {
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_collection_data(self, context, false)
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        write_collection_type_info(context, TypeId::SET as u32)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        read_collection_data(context)
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_collection_type_info(context, TypeId::SET as u32)
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::SET as u32)
    }

    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::SET as u32)
    }

    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        TypeId::SET
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_is_xlang_ref_type() -> bool
    where
        Self: Sized,
    {
        // HashSet<T> corresponds to Java Set<T>, which is a reference type
        true
    }
}

impl<T> ForyDefault for HashSet<T> {
    fn fory_default() -> Self {
        HashSet::new()
    }
}

impl<T: Serializer + ForyDefault + Ord> Serializer for BTreeSet<T> {
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_collection_data(self, context, false)
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        write_collection_type_info(context, TypeId::SET as u32)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        read_collection_data(context)
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_collection_type_info(context, TypeId::SET as u32)
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::SET as u32)
    }

    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::SET as u32)
    }

    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        TypeId::SET
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_is_xlang_ref_type() -> bool
    where
        Self: Sized,
    {
        // BTreeSet<T> corresponds to Java Set<T>, which is a reference type
        true
    }
}

impl<T> ForyDefault for BTreeSet<T> {
    fn fory_default() -> Self {
        BTreeSet::new()
    }
}
