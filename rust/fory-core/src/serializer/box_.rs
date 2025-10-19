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

impl<T: Serializer + ForyDefault> Serializer for Box<T> {
    fn fory_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        Ok(Box::new(T::fory_read_data(context, is_field)?))
    }

    fn fory_read_data_into(
        context: &mut ReadContext,
        is_field: bool,
        output: &mut Self,
    ) -> Result<(), Error> {
        *output = Box::new(T::fory_read_data(context, is_field)?);
        Ok(())
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) -> Result<(), Error> {
        T::fory_read_type_info(context, is_field)
    }

    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) -> Result<(), Error> {
        T::fory_write_data(self.as_ref(), context, is_field)
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) -> Result<(), Error> {
        T::fory_write_type_info(context, is_field)
    }

    fn fory_reserved_space() -> usize {
        T::fory_reserved_space()
    }

    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        (**self).fory_type_id_dyn(type_resolver)
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T: ForyDefault> ForyDefault for Box<T> {
    fn fory_default() -> Self {
        Box::new(T::fory_default())
    }
}
