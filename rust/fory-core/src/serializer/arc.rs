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
use crate::serializer::{ForyDefault, Serializer};
use crate::types::RefFlag;
use anyhow::anyhow;
use std::sync::Arc;

impl<T: Serializer + ForyDefault + Send + Sync + 'static> Serializer for Arc<T> {
    fn fory_is_shared_ref() -> bool {
        true
    }

    fn fory_write(&self, context: &mut WriteContext, is_field: bool) {
        if !context.ref_writer.try_write_arc_ref(context.writer, self) {
            T::fory_write_data(self.as_ref(), context, is_field);
        }
    }

    fn fory_write_data(&self, _context: &mut WriteContext, _is_field: bool) {
        panic!("Should not call Rc::fory_write_data directly, use Rc::fory_write instead");
    }

    fn fory_write_type_info(context: &mut WriteContext, is_field: bool) {
        T::fory_write_type_info(context, is_field);
    }

    fn fory_read(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader);

        match ref_flag {
            RefFlag::Null => Err(anyhow!("Arc cannot be null").into()),
            RefFlag::Ref => {
                let ref_id = context.ref_reader.read_ref_id(&mut context.reader);
                context
                    .ref_reader
                    .get_arc_ref::<T>(ref_id)
                    .ok_or_else(|| anyhow!("Arc reference {} not found", ref_id).into())
            }
            RefFlag::NotNullValue => {
                let inner = T::fory_read_data(context, is_field)?;
                Ok(Arc::new(inner))
            }
            RefFlag::RefValue => {
                let inner = T::fory_read_data(context, is_field)?;
                let arc = Arc::new(inner);
                context.ref_reader.store_arc_ref(arc.clone());
                Ok(arc)
            }
        }
    }

    fn fory_read_data(_context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        panic!("Should not call Rc::fory_read_data directly, use Rc::fory_read instead");
    }

    fn fory_read_type_info(context: &mut ReadContext, is_field: bool) {
        T::fory_read_type_info(context, is_field);
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

impl<T: ForyDefault> ForyDefault for Arc<T> {
    fn fory_default() -> Self {
        Arc::new(T::fory_default())
    }
}
