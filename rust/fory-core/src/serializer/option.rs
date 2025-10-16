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
use crate::serializer::{ForyDefault, Serializer};

impl<T: Serializer + ForyDefault> Serializer for Option<T> {
    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(Some(T::fory_read_data(context)?))
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        if let Some(v) = self {
            T::fory_write_data(v, context)
        } else {
            unreachable!("write should be call by serialize")
        }
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        std::mem::size_of::<T>()
    }

    #[inline(always)]
    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        match self {
            Some(val) => val.fory_type_id_dyn(type_resolver),
            None => T::fory_get_type_id(type_resolver),
        }
    }

    #[inline(always)]
    fn fory_is_option() -> bool {
        true
    }

    #[inline(always)]
    fn fory_is_none(&self) -> bool {
        self.is_none()
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T: ForyDefault> ForyDefault for Option<T> {
    #[inline(always)]
    fn fory_default() -> Self {
        None
    }
}
