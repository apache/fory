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
use crate::serializer::Serializer;
use crate::types::{ForyGeneralList, Mode};

impl<T: Serializer> Serializer for Option<T> {
    fn read(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(Some(T::read(context)?))
    }

    fn read_type_info(context: &mut ReadContext, is_field: bool) {
        if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
            T::read_type_info(context, is_field);
        }
    }

    fn write(&self, context: &mut WriteContext, is_field: bool) {
        if let Some(v) = self {
            T::write(v, context, is_field)
        } else {
            unreachable!("write should be call by serialize")
        }
    }

    fn write_type_info(context: &mut WriteContext, is_field: bool) {
        if *context.get_fory().get_mode() == Mode::Compatible {
            T::write_type_info(context, is_field);
        }
    }

    fn reserved_space() -> usize {
        std::mem::size_of::<T>()
    }

    fn get_type_id(fory: &Fory) -> u32 {
        T::get_type_id(fory)
    }

    fn is_option() -> bool {
        true
    }

    fn is_none(&self) -> bool {
        self.is_none()
    }
}

impl<T: Serializer> ForyGeneralList for Option<T> {}
