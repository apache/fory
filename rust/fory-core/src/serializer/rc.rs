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
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::RefFlag;
use crate::types::TypeId;
use std::rc::Rc;

impl<T: Serializer + ForyDefault + 'static> Serializer for Rc<T> {
    fn fory_is_shared_ref() -> bool {
        true
    }

    fn fory_write(&self, context: &mut WriteContext) -> Result<(), Error> {
        if !context
            .ref_writer
            .try_write_rc_ref(&mut context.writer, self)
        {
            T::fory_write_data(self.as_ref(), context)?
        };
        Ok(())
    }

    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        // When Rc is nested inside another shared ref (like Arc<Rc<T>>),
        // the outer ref calls fory_write_data on the inner Rc.
        // We still need to track the Rc's own references here.
        self.fory_write(context)
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    fn fory_read(context: &mut ReadContext) -> Result<Self, Error> {
        let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader)?;
        match ref_flag {
            RefFlag::Null => Err(Error::InvalidRef("Rc cannot be null".into())),
            RefFlag::Ref => {
                let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
                context.ref_reader.get_rc_ref::<T>(ref_id).ok_or_else(|| {
                    Error::InvalidRef(format!("Rc reference {ref_id} not found").into())
                })
            }
            RefFlag::NotNullValue => {
                let inner = T::fory_read_data(context)?;
                Ok(Rc::new(inner))
            }
            RefFlag::RefValue => {
                let ref_id = context.ref_reader.reserve_ref_id();
                let inner = T::fory_read_data(context)?;
                let rc = Rc::new(inner);
                context.ref_reader.store_rc_ref_at(ref_id, rc.clone());
                Ok(rc)
            }
        }
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        // When Rc is nested inside another shared ref, fory_read_data is called.
        // Delegate to fory_read which handles ref tracking properly.
        Self::fory_read(context)
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    fn fory_reserved_space() -> usize {
        // Rc is a shared ref, so we just need space for the ref tracking
        // We don't recursively compute inner type's space to avoid infinite recursion
        4
    }

    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        (**self).fory_type_id_dyn(type_resolver)
    }

    fn fory_static_type_id() -> TypeId {
        T::fory_static_type_id()
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T: ForyDefault> ForyDefault for Rc<T> {
    fn fory_default() -> Self {
        Rc::new(T::fory_default())
    }
}
