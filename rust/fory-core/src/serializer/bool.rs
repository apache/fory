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
use crate::types::{Mode, TypeId};
use std::mem;

impl Serializer for bool {
    fn reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn write(&self, context: &mut WriteContext, is_field: bool) {
        if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
            context.writer.var_uint32(TypeId::BOOL as u32);
        }
        context.writer.u8(if *self { 1 } else { 0 });
    }

    fn read(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
            let remote_type_id = context.reader.var_uint32();
            assert_eq!(remote_type_id, TypeId::BOOL as u32);
        }
        Ok(context.reader.u8() == 1)
    }

    fn get_type_id(_fory: &Fory) -> u32 {
        TypeId::BOOL as u32
    }
}
