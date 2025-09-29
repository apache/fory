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
use crate::types::{ForyGeneralList, RefFlag};
use anyhow::anyhow;
use std::rc::Rc;

impl<T: Serializer + 'static> Serializer for Rc<T> {
    fn read(context: &mut ReadContext) -> Result<Self, Error> {
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
                let inner = T::read(context)?;
                Ok(Rc::new(inner))
            }
            RefFlag::RefValue => {
                let inner = T::read(context)?;
                let rc = Rc::new(inner);
                context.ref_reader.store_rc_ref(rc.clone());
                Ok(rc)
            }
        }
    }

    fn read_type_info(context: &mut ReadContext, is_field: bool) {
        T::read_type_info(context, is_field);
    }

    fn write(&self, context: &mut WriteContext, is_field: bool) {
        if !context.ref_writer.try_write_rc_ref(context.writer, self) {
            T::write(self.as_ref(), context, is_field);
        }
    }

    fn write_type_info(context: &mut WriteContext, is_field: bool) {
        T::write_type_info(context, is_field);
    }

    fn reserved_space() -> usize {
        T::reserved_space()
    }

    fn get_type_id(fory: &Fory) -> u32 {
        T::get_type_id(fory)
    }

    fn is_option() -> bool {
        false
    }

    fn is_none(&self) -> bool {
        false
    }
}

impl<T: Serializer> ForyGeneralList for Rc<T> {}
