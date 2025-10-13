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
use std::any::Any;
use std::rc::Rc;
use std::sync::Arc;

/// Helper function to serialize a `Box<dyn Any>`
pub fn serialize_any_box(
    any_box: &Box<dyn Any>,
    fory: &Fory,
    context: &mut WriteContext,
    is_field: bool,
) {
    context.writer.write_i8(RefFlag::NotNullValue as i8);

    let concrete_type_id = (**any_box).type_id();
    let harness = context.write_any_typeinfo(fory, concrete_type_id);
    let serializer_fn = harness.get_write_data_fn();
    serializer_fn(&**any_box, fory, context, is_field);
}

/// Helper function to deserialize to `Box<dyn Any>`
pub fn deserialize_any_box(fory: &Fory, context: &mut ReadContext) -> Result<Box<dyn Any>, Error> {
    context.inc_depth()?;
    let ref_flag = context.reader.read_i8();
    if ref_flag != RefFlag::NotNullValue as i8 {
        return Err(Error::Other(anyhow::anyhow!(
            "Expected NotNullValue for Box<dyn Any>"
        )));
    }
    let harness = context.read_any_typeinfo(fory);
    let deserializer_fn = harness.get_read_data_fn();
    let result = deserializer_fn(fory, context, true);
    context.dec_depth();
    result
}

impl ForyDefault for Box<dyn Any> {
    fn fory_default() -> Self {
        Box::new(())
    }
}

impl Serializer for Box<dyn Any> {
    fn fory_write(&self, fory: &Fory, context: &mut WriteContext, is_field: bool) {
        serialize_any_box(self, fory, context, is_field);
    }

    fn fory_write_data(&self, fory: &Fory, context: &mut WriteContext, is_field: bool) {
        serialize_any_box(self, fory, context, is_field);
    }

    fn fory_read(fory: &Fory, context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        deserialize_any_box(fory, context)
    }

    fn fory_read_data(
        fory: &Fory,
        context: &mut ReadContext,
        _is_field: bool,
    ) -> Result<Self, Error> {
        deserialize_any_box(fory, context)
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        panic!("Box<dyn Any> has no static type ID - use fory_type_id_dyn")
    }

    fn fory_type_id_dyn(&self, fory: &Fory) -> u32 {
        let concrete_type_id = (**self).type_id();
        fory.get_type_resolver()
            .get_fory_type_id(concrete_type_id)
            .expect("Type not registered")
    }

    fn fory_is_polymorphic() -> bool {
        true
    }

    fn fory_write_type_info(_fory: &Fory, _context: &mut WriteContext, _is_field: bool) {
        // Box<dyn Any> is polymorphic - type info is written per element
    }

    fn fory_read_type_info(_fory: &Fory, _context: &mut ReadContext, _is_field: bool) {
        // Box<dyn Any> is polymorphic - type info is read per element
    }

    fn as_any(&self) -> &dyn Any {
        &**self
    }
}

impl ForyDefault for Rc<dyn Any> {
    fn fory_default() -> Self {
        Rc::new(())
    }
}

impl Serializer for Rc<dyn Any> {
    fn fory_write(&self, fory: &Fory, context: &mut WriteContext, is_field: bool) {
        if !context
            .ref_writer
            .try_write_rc_ref(&mut context.writer, self)
        {
            let concrete_type_id = (**self).type_id();
            let harness = context.write_any_typeinfo(fory, concrete_type_id);
            let serializer_fn = harness.get_write_data_fn();
            serializer_fn(&**self, fory, context, is_field);
        }
    }

    fn fory_write_data(&self, fory: &Fory, context: &mut WriteContext, is_field: bool) {
        self.fory_write(fory, context, is_field);
    }

    fn fory_read(fory: &Fory, context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader);

        match ref_flag {
            RefFlag::Null => Err(anyhow::anyhow!("Rc<dyn Any> cannot be null").into()),
            RefFlag::Ref => {
                let ref_id = context.ref_reader.read_ref_id(&mut context.reader);
                context
                    .ref_reader
                    .get_rc_ref::<dyn Any>(ref_id)
                    .ok_or_else(|| {
                        anyhow::anyhow!("Rc<dyn Any> reference {} not found", ref_id).into()
                    })
            }
            RefFlag::NotNullValue => {
                context.inc_depth()?;
                let harness = context.read_any_typeinfo(fory);
                let deserializer_fn = harness.get_read_data_fn();
                let boxed = deserializer_fn(fory, context, true)?;
                context.dec_depth();
                Ok(Rc::<dyn Any>::from(boxed))
            }
            RefFlag::RefValue => {
                context.inc_depth()?;
                let harness = context.read_any_typeinfo(fory);
                let deserializer_fn = harness.get_read_data_fn();
                let boxed = deserializer_fn(fory, context, true)?;
                context.dec_depth();
                let rc: Rc<dyn Any> = Rc::from(boxed);
                context.ref_reader.store_rc_ref(rc.clone());
                Ok(rc)
            }
        }
    }

    fn fory_read_data(
        fory: &Fory,
        context: &mut ReadContext,
        is_field: bool,
    ) -> Result<Self, Error> {
        Self::fory_read(fory, context, is_field)
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        panic!("Rc<dyn Any> has no static type ID - use fory_type_id_dyn")
    }

    fn fory_type_id_dyn(&self, fory: &Fory) -> u32 {
        let concrete_type_id = (**self).type_id();
        fory.get_type_resolver()
            .get_fory_type_id(concrete_type_id)
            .expect("Type not registered")
    }

    fn fory_is_polymorphic() -> bool {
        true
    }

    fn fory_write_type_info(_fory: &Fory, _context: &mut WriteContext, _is_field: bool) {
        // Rc<dyn Any> is polymorphic - type info is written per element
    }

    fn fory_read_type_info(_fory: &Fory, _context: &mut ReadContext, _is_field: bool) {
        // Rc<dyn Any> is polymorphic - type info is read per element
    }

    fn as_any(&self) -> &dyn Any {
        &**self
    }
}

impl ForyDefault for Arc<dyn Any> {
    fn fory_default() -> Self {
        Arc::new(())
    }
}

impl Serializer for Arc<dyn Any> {
    fn fory_write(&self, fory: &Fory, context: &mut WriteContext, is_field: bool) {
        if !context
            .ref_writer
            .try_write_arc_ref(&mut context.writer, self)
        {
            let concrete_type_id = (**self).type_id();
            let harness = context.write_any_typeinfo(fory, concrete_type_id);
            let serializer_fn = harness.get_write_data_fn();
            serializer_fn(&**self, fory, context, is_field);
        }
    }

    fn fory_write_data(&self, fory: &Fory, context: &mut WriteContext, is_field: bool) {
        self.fory_write(fory, context, is_field);
    }

    fn fory_read(fory: &Fory, context: &mut ReadContext, _is_field: bool) -> Result<Self, Error> {
        let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader);

        match ref_flag {
            RefFlag::Null => Err(anyhow::anyhow!("Arc<dyn Any> cannot be null").into()),
            RefFlag::Ref => {
                let ref_id = context.ref_reader.read_ref_id(&mut context.reader);
                context
                    .ref_reader
                    .get_arc_ref::<dyn Any>(ref_id)
                    .ok_or_else(|| {
                        anyhow::anyhow!("Arc<dyn Any> reference {} not found", ref_id).into()
                    })
            }
            RefFlag::NotNullValue => {
                context.inc_depth()?;
                let harness = context.read_any_typeinfo(fory);
                let deserializer_fn = harness.get_read_data_fn();
                let boxed = deserializer_fn(fory, context, true)?;
                context.dec_depth();
                Ok(Arc::<dyn Any>::from(boxed))
            }
            RefFlag::RefValue => {
                context.inc_depth()?;
                let harness = context.read_any_typeinfo(fory);
                let deserializer_fn = harness.get_read_data_fn();
                let boxed = deserializer_fn(fory, context, true)?;
                context.dec_depth();
                let arc: Arc<dyn Any> = Arc::from(boxed);
                context.ref_reader.store_arc_ref(arc.clone());
                Ok(arc)
            }
        }
    }

    fn fory_read_data(
        fory: &Fory,
        context: &mut ReadContext,
        is_field: bool,
    ) -> Result<Self, Error> {
        Self::fory_read(fory, context, is_field)
    }

    fn fory_get_type_id(_fory: &Fory) -> u32 {
        panic!("Arc<dyn Any> has no static type ID - use fory_type_id_dyn")
    }

    fn fory_type_id_dyn(&self, fory: &Fory) -> u32 {
        let concrete_type_id = (**self).type_id();
        fory.get_type_resolver()
            .get_fory_type_id(concrete_type_id)
            .expect("Type not registered")
    }

    fn fory_is_polymorphic() -> bool {
        true
    }

    fn fory_write_type_info(_fory: &Fory, _context: &mut WriteContext, _is_field: bool) {
        // Arc<dyn Any> is polymorphic - type info is written per element
    }

    fn fory_read_type_info(_fory: &Fory, _context: &mut ReadContext, _is_field: bool) {
        // Arc<dyn Any> is polymorphic - type info is read per element
    }

    fn as_any(&self) -> &dyn Any {
        &**self
    }
}
