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
use crate::serializer::Serializer;
use crate::type_id::TypeId;
use std::marker::PhantomData;

impl<T: 'static> Serializer for PhantomData<T> {
    type Target = Self;

    #[inline(always)]
    fn write_data(_: &Self, _: &mut WriteContext) -> Result<(), Error> {
        Ok(())
    }

    #[inline(always)]
    fn read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Ok(PhantomData)
    }

    #[inline(always)]
    fn default_value(_: &mut ReadContext) -> Result<Self, Error> {
        Ok(PhantomData)
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        0
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::NONE
    }
}
