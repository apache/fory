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

use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::serializer::util::read_basic_type_info;
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use std::sync::Arc;

impl Serializer for bool {
    type Target = Self;

    const READ_DATA_ALWAYS_ADVANCES: bool = true;

    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(u8::from(*value));
        Ok(())
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(context.reader.read_u8()? == 1)
    }

    #[inline(always)]
    fn default_value(_: &mut ReadContext) -> Result<Self, Error> {
        Ok(false)
    }

    #[inline(always)]
    fn read_arc_any(
        context: &mut ReadContext,
    ) -> Result<Arc<dyn std::any::Any + Send + Sync>, Error> {
        Ok(Arc::new(Self::read_data(context)?))
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        std::mem::size_of::<i32>()
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::BOOL
    }

    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(TypeId::BOOL as u8);
        Ok(())
    }

    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}
