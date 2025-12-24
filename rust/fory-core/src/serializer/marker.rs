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

//! Serializer implementations for marker types like `PhantomData<T>`.

use crate::error::Error;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::marker::PhantomData;

impl<T: 'static> Serializer for PhantomData<T> {
    #[inline(always)]
    fn fory_write_data(&self, _context: &mut WriteContext) -> Result<(), Error> {
        // PhantomData has no data to write
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(_context: &mut ReadContext) -> Result<Self, Error> {
        // PhantomData has no data to read
        Ok(PhantomData)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        0
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        // Use NONE - PhantomData<T> has no runtime data, skip can return early
        Ok(TypeId::NONE as u32)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        // Use NONE - PhantomData<T> has no runtime data, skip can return early
        Ok(TypeId::NONE as u32)
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId {
        // Use NONE - PhantomData<T> has no runtime data, skip can return early
        TypeId::NONE
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T: 'static> ForyDefault for PhantomData<T> {
    #[inline(always)]
    fn fory_default() -> Self {
        PhantomData
    }
}
