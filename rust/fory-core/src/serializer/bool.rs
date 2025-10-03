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
use crate::types::TypeId;
use std::mem;

impl Serializer for bool {
    fn fory_write_data(&self, context: &mut WriteContext, _is_field: bool) {
        context.writer.write_u8(if *self { 1 } else { 0 });
    }

    fn fory_read_data(context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        Ok(context.reader.read_u8() == 1)
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
        if is_field {
            return;
        }
        let type_id = Self::fory_get_type_id(context.get_fory());
        context.writer.write_varuint32(type_id);
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
        if is_field {
            return;
        }
        let local_type_id = Self::fory_get_type_id(context.get_fory());
        let remote_type_id = context.reader.read_varuint32();
        assert_eq!(local_type_id, remote_type_id);
    }

    fn fory_reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        TypeId::BOOL as u32
    }
}
