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
use crate::serializer::collection::{
    read_collection, read_collection_type_info, write_collection, write_collection_type_info,
};

use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::collections::BinaryHeap;
use std::mem;

impl<T: Serializer + ForyDefault + Ord> Serializer for BinaryHeap<T> {
    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
        write_collection(self, context, is_field);
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
        write_collection_type_info(context, is_field, TypeId::SET as u32);
    }

    fn fory_read_data(context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        read_collection(context)
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
        read_collection_type_info(context, is_field, TypeId::SET as u32)
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        TypeId::SET as u32
    }

    fn fory_type_id_dyn(&self, _fory: &Fory) -> u32 {
        TypeId::SET as u32
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T: Ord> ForyDefault for BinaryHeap<T> {
    fn fory_default() -> Self {
        BinaryHeap::new()
    }
}
