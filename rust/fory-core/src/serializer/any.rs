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
use crate::serializer::util::write_dyn_data_generic;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::RefFlag;
use crate::types::TypeId;
use std::any::Any;
use std::rc::Rc;
use std::sync::Arc;

/// Helper function to deserialize to `Box<dyn Any>`
pub fn deserialize_any_box(context: &mut ReadContext) -> Result<Box<dyn Any>, Error> {
    context.inc_depth()?;
    let ref_flag = context.reader.read_i8()?;
    if ref_flag != RefFlag::NotNullValue as i8 {
        return Err(Error::InvalidRef(
            "Expected NotNullValue for Box<dyn Any>".into(),
        ));
    }
    let typeinfo = context.read_any_typeinfo()?;
    let deserializer_fn = typeinfo.get_harness().get_read_data_fn();
    let result = deserializer_fn(context);
    context.dec_depth();
    result
}

impl ForyDefault for Box<dyn Any> {
    fn fory_default() -> Self {
        Box::new(())
    }
}

impl Serializer for Box<dyn Any> {
    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_ref_info: bool,
        write_typeinfo: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if write_ref_info {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        let concrete_type_id = (**self).type_id();
        let typeinfo = if write_typeinfo {
            context.write_any_typeinfo(concrete_type_id)?
        } else {
            context.get_type_info(&concrete_type_id)?
        };
        let serializer_fn = typeinfo.get_harness().get_write_data_fn();
        serializer_fn(&**self, context, has_generics)
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        let concrete_type_id = (**self).type_id();
        let typeinfo = context.get_type_info(&concrete_type_id)?;
        let serializer_fn = typeinfo.get_harness().get_write_data_fn();
        serializer_fn(&**self, context, has_generics)
    }

    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        self.fory_write_data_generic(context, false)
    }

    fn fory_read(context: &mut ReadContext, read_ref_info: bool) -> Result<Self, Error> {
        context.inc_depth()?;
        let ref_flag = context.reader.read_i8()?;
        if ref_flag != RefFlag::NotNullValue as i8 {
            return Err(Error::InvalidRef(
                "Expected NotNullValue for Box<dyn Any>".into(),
            ));
        }
        let typeinfo = context.read_any_typeinfo()?;
        let deserializer_fn = typeinfo.get_harness().get_read_data_fn();
        let result = deserializer_fn(context);
        context.dec_depth();
        result
    }

    fn fory_read_data_with_typeinfo(
        context: &mut ReadContext,
        _: Arc<crate::resolver::type_resolver::TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        deserialize_any_box(context)
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Err(Error::TypeError(
            "Box<dyn Any> has no static type ID - use fory_type_id_dyn".into(),
        ))
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        let concrete_type_id = (**self).type_id();
        type_resolver
            .get_fory_type_id(concrete_type_id)
            .ok_or_else(|| Error::TypeError("Type not registered".into()))
    }

    fn fory_is_polymorphic() -> bool {
        true
    }

    fn fory_static_type_id() -> TypeId {
        TypeId::UNKNOWN
    }

    fn fory_write_type_info(_context: &mut WriteContext) -> Result<(), Error> {
        // Box<dyn Any> is polymorphic - type info is written per element
        Ok(())
    }

    fn fory_read_type_info(_context: &mut ReadContext) -> Result<(), Error> {
        // Box<dyn Any> is polymorphic - type info is read per element
        Ok(())
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
    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if !context
            .ref_writer
            .try_write_rc_ref(&mut context.writer, self)
        {
            let concrete_type_id: std::any::TypeId = (**self).type_id();
            if write_type_info {
                let typeinfo = context.write_any_typeinfo(concrete_type_id)?;
                let serializer_fn = typeinfo.get_harness().get_write_data_fn();
                serializer_fn(&**self, context, has_generics)?;
            } else {
                let serializer_fn = context
                    .write_any_typeinfo(concrete_type_id)?
                    .get_harness()
                    .get_write_data_fn();
                serializer_fn(&**self, context, has_generics)?;
            }
        }
        Ok(())
    }

    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_dyn_data_generic(self, context, false)
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_dyn_data_generic(self, context, has_generics)
    }

    fn fory_read(context: &mut ReadContext) -> Result<Self, Error> {
        let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader)?;

        match ref_flag {
            RefFlag::Null => Err(Error::InvalidRef("Rc<dyn Any> cannot be null".into())),
            RefFlag::Ref => {
                let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
                context
                    .ref_reader
                    .get_rc_ref::<dyn Any>(ref_id)
                    .ok_or_else(|| {
                        Error::InvalidData(
                            format!("Rc<dyn Any> reference {} not found", ref_id).into(),
                        )
                    })
            }
            RefFlag::NotNullValue => {
                context.inc_depth()?;
                let typeinfo = context.read_any_typeinfo()?;
                let deserializer_fn = typeinfo.get_harness().get_read_data_fn();
                let boxed = deserializer_fn(context)?;
                context.dec_depth();
                Ok(Rc::<dyn Any>::from(boxed))
            }
            RefFlag::RefValue => {
                context.inc_depth()?;
                let typeinfo = context.read_any_typeinfo()?;
                let deserializer_fn = typeinfo.get_harness().get_read_data_fn();
                let boxed = deserializer_fn(context)?;
                context.dec_depth();
                let rc: Rc<dyn Any> = Rc::from(boxed);
                context.ref_reader.store_rc_ref(rc.clone());
                Ok(rc)
            }
        }
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Self::fory_read(context)
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Err(Error::TypeError(
            "Rc<dyn Any> has no static type ID - use fory_type_id_dyn".into(),
        ))
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        let concrete_type_id = (**self).type_id();
        type_resolver
            .get_fory_type_id(concrete_type_id)
            .ok_or_else(|| Error::TypeError("Type not registered".into()))
    }

    fn fory_is_polymorphic() -> bool {
        true
    }

    fn fory_static_type_id() -> TypeId {
        TypeId::UNKNOWN
    }

    fn fory_write_type_info(_context: &mut WriteContext) -> Result<(), Error> {
        // Rc<dyn Any> is polymorphic - type info is written per element
        Ok(())
    }

    fn fory_read_type_info(_context: &mut ReadContext) -> Result<(), Error> {
        // Rc<dyn Any> is polymorphic - type info is read per element
        Ok(())
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
    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if !context
            .ref_writer
            .try_write_arc_ref(&mut context.writer, self)
        {
            let concrete_type_id: std::any::TypeId = (**self).type_id();
            if write_type_info {
                let typeinfo = context.write_any_typeinfo(concrete_type_id)?;
                let serializer_fn = typeinfo.get_harness().get_write_data_fn();
                serializer_fn(&**self, context, has_generics)?;
            } else {
                let serializer_fn = context
                    .write_any_typeinfo(concrete_type_id)?
                    .get_harness()
                    .get_write_data_fn();
                serializer_fn(&**self, context, has_generics)?;
            }
        }
        Ok(())
    }

    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_dyn_data_generic(self, context, false)
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_dyn_data_generic(self, context, has_generics)
    }

    fn fory_read(context: &mut ReadContext) -> Result<Self, Error> {
        let ref_flag = context.ref_reader.read_ref_flag(&mut context.reader)?;

        match ref_flag {
            RefFlag::Null => Err(Error::InvalidRef("Arc<dyn Any> cannot be null".into())),
            RefFlag::Ref => {
                let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
                context
                    .ref_reader
                    .get_arc_ref::<dyn Any>(ref_id)
                    .ok_or_else(|| {
                        Error::InvalidData(
                            format!("Arc<dyn Any> reference {} not found", ref_id).into(),
                        )
                    })
            }
            RefFlag::NotNullValue => {
                context.inc_depth()?;
                let typeinfo = context.read_any_typeinfo()?;
                let deserializer_fn = typeinfo.get_harness().get_read_data_fn();
                let boxed = deserializer_fn(context)?;
                context.dec_depth();
                Ok(Arc::<dyn Any>::from(boxed))
            }
            RefFlag::RefValue => {
                context.inc_depth()?;
                let typeinfo = context.read_any_typeinfo()?;
                let deserializer_fn = typeinfo.get_harness().get_read_data_fn();
                let boxed = deserializer_fn(context)?;
                context.dec_depth();
                let arc: Arc<dyn Any> = Arc::from(boxed);
                context.ref_reader.store_arc_ref(arc.clone());
                Ok(arc)
            }
        }
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Self::fory_read(context)
    }

    fn fory_get_type_id(_type_resolver: &TypeResolver) -> Result<u32, Error> {
        Err(Error::TypeError(
            "Arc<dyn Any> has no static type ID - use fory_type_id_dyn".into(),
        ))
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        let concrete_type_id = (**self).type_id();
        type_resolver
            .get_fory_type_id(concrete_type_id)
            .ok_or_else(|| Error::TypeError("Type not registered".into()))
    }

    fn fory_is_polymorphic() -> bool {
        true
    }

    fn fory_static_type_id() -> TypeId {
        TypeId::UNKNOWN
    }

    fn fory_write_type_info(_context: &mut WriteContext) -> Result<(), Error> {
        // Arc<dyn Any> is polymorphic - type info is written per element
        Ok(())
    }

    fn fory_read_type_info(_context: &mut ReadContext) -> Result<(), Error> {
        // Arc<dyn Any> is polymorphic - type info is read per element
        Ok(())
    }

    fn as_any(&self) -> &dyn Any {
        &**self
    }
}
