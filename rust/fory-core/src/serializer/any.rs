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
use crate::resolver::context::{ReadContext, WriteContext};
use crate::serializer::Serializer;
use crate::types::TypeId;
use std::any::Any;

impl Serializer for Box<dyn Any> {
    fn reserved_space() -> usize {
        0
    }

    fn write(&self, _context: &mut WriteContext) {
        panic!("unreachable")
    }

    fn read(_context: &mut ReadContext) -> Result<Self, Error> {
        panic!("unreachable")
    }

    fn get_type_id(_fory: &Fory) -> u32 {
        TypeId::ForyAny as u32
    }

    fn serialize(&self, context: &mut WriteContext) {
        context
            .get_fory()
            .get_type_resolver()
            .get_harness_by_type(self.as_ref().type_id())
            .unwrap()
            .get_serializer()(self.as_ref(), context);
    }

    fn deserialize(_context: &mut ReadContext) -> Result<Self, Error> {
        todo!()
        // let reset_cursor = context.reader.reset_cursor_to_here();
        // // ref flag
        // let ref_flag = context.reader.i8();
        //
        // if ref_flag == (RefFlag::NotNullValue as i8) || ref_flag == (RefFlag::RefValue as i8) {
        //     if context.get_fory().get_mode().eq(&Mode::Compatible) {
        //         let type_id = context.meta_resolver.get(meta_index as usize).get_type_id();
        //         reset_cursor(&mut context.reader);
        //         context
        //             .get_fory()
        //             .get_type_resolver()
        //             .get_harness(type_id)
        //             .unwrap()
        //             .get_deserializer()(context)
        //     } else {
        //         let type_id = context.reader.i16();
        //         reset_cursor(&mut context.reader);
        //         context
        //             .get_fory()
        //             .get_type_resolver()
        //             .get_harness(type_id)
        //             .unwrap()
        //             .get_deserializer()(context)
        //     }
        // } else if ref_flag == (RefFlag::Null as i8) {
        //     Err(anyhow!("Try to deserialize `any` to null"))?
        // } else if ref_flag == (RefFlag::Ref as i8) {
        //     reset_cursor(&mut context.reader);
        //     Err(Error::Ref)
        // } else {
        //     reset_cursor(&mut context.reader);
        //     Err(anyhow!("Unknown ref flag, value:{ref_flag}"))?
        // }
    }
}
