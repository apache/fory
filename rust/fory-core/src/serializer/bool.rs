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
use crate::serializer::{read_type_info, write_type_info, ForyDefault, Serializer};
use crate::types::TypeId;
use std::mem;

impl Serializer for bool {
    #[inline(always)]
    fn fory_write_data(
        &self,
        _fory: &Fory,
        context: &mut WriteContext,
        _is_field: bool,
    ) -> Result<(), Error> {
        context.writer.write_u8(if *self { 1 } else { 0 });
        Ok(())
    }

    #[inline(always)]
    fn fory_read_data(
        _fory: &Fory,
        context: &mut ReadContext,
        _is_field: bool,
    ) -> Result<Self, Error> {
        Ok(context.reader.read_u8()? == 1)
    }

    #[inline(always)]
    fn fory_read_data_into(
        _fory: &Fory,
        context: &mut ReadContext,
        _is_field: bool,
        output: &mut Self,
    ) -> Result<(), Error> {
        *output = context.reader.read_u8()? == 1;
        Ok(())
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    #[inline(always)]
    fn fory_get_type_id(_fory: &Fory) -> Result<u32, Error> {
        Ok(TypeId::BOOL as u32)
    }

    fn fory_type_id_dyn(&self, _fory: &Fory) -> Result<u32, Error> {
        Ok(TypeId::BOOL as u32)
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    #[inline(always)]
    fn fory_write_type_info(
        fory: &Fory,
        context: &mut WriteContext,
        is_field: bool,
    ) -> Result<(), Error> {
        write_type_info::<Self>(fory, context, is_field)
    }

    #[inline(always)]
    fn fory_read_type_info(
        fory: &Fory,
        context: &mut ReadContext,
        is_field: bool,
    ) -> Result<(), Error> {
        read_type_info::<Self>(fory, context, is_field)
    }
}

impl ForyDefault for bool {
    #[inline(always)]
    fn fory_default() -> Self {
        false
    }
}
