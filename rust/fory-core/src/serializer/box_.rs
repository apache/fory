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
use crate::serializer::{ForyDefault, Serializer};

impl<T: Serializer + ForyDefault> Serializer for Box<T> {
    fn fory_read_data(
        fory: &Fory,
        context: &mut ReadContext,
        is_field: bool,
    ) -> Result<Self, Error> {
        Ok(Box::new(T::fory_read_data(fory, context, is_field)?))
    }

    fn fory_read_type_info(fory: &Fory, context: &mut ReadContext, is_field: bool) {
        T::fory_read_type_info(fory, context, is_field);
    }

    fn fory_write_data(&self, fory: &Fory, context: &mut WriteContext, is_field: bool) {
        T::fory_write_data(self.as_ref(), fory, context, is_field)
    }

    fn fory_write_type_info(fory: &Fory, context: &mut WriteContext, is_field: bool) {
        T::fory_write_type_info(fory, context, is_field);
    }

    fn fory_reserved_space() -> usize {
        T::fory_reserved_space()
    }

    fn fory_get_type_id(fory: &Fory) -> u32 {
        T::fory_get_type_id(fory)
    }

    fn fory_type_id_dyn(&self, fory: &Fory) -> u32 {
        (**self).fory_type_id_dyn(fory)
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
