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
use crate::types::RefFlag;
use anyhow::anyhow;
use std::rc::Rc;

impl<T: Serializer + 'static> Serializer for Rc<T> {
    fn fory_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader);

        match ref_flag {
            RefFlag::Null => Err(anyhow!("Rc cannot be null").into()),
            RefFlag::Ref => {
                let ref_id = context.ref_reader.read_ref_id(&mut context.reader);
                context
                    .ref_reader
                    .get_rc_ref::<T>(ref_id)
                    .ok_or_else(|| anyhow!("Rc reference {} not found", ref_id).into())
            }
            RefFlag::NotNullValue => {
                let inner = T::fory_read_data(context, is_field)?;
                Ok(Rc::new(inner))
            }
            RefFlag::RefValue => {
                let inner = T::fory_read_data(context, is_field)?;
                let rc = Rc::new(inner);
                context.ref_reader.store_rc_ref(rc.clone());
                Ok(rc)
            }
        }
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
        T::fory_read_type_info(context, is_field);
    }

    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
        if !context.ref_writer.try_write_rc_ref(context.writer, self) {
            T::fory_write_data(self.as_ref(), context, is_field);
        }
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
        T::fory_write_type_info(context, is_field);
    }

    fn fory_reserved_space() -> usize {
        T::fory_reserved_space()
    }

    fn fory_get_type_id(fory: &Fory) -> u32 {
        T::fory_get_type_id(fory)
    }
}
