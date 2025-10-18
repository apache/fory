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

use crate::ensure;
use crate::error::Error;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
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
        write_box_any(self, context, write_ref_info, write_typeinfo, has_generics)
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

    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_box_any(context, read_ref_info, read_type_info, None)
    }

    fn fory_read_with_typeinfo(
        context: &mut ReadContext,
        read_ref_info: bool,
        type_info: Arc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_box_any(context, read_ref_info, false, Some(type_info))
    }

    fn fory_read_data(_: &mut ReadContext) -> Result<Self, Error> {
        panic!(
            "fory_read_data should not be called directly on polymorphic Rc<dyn Any> trait object"
        );
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

pub fn write_box_any(
    value: &Box<dyn Any>,
    context: &mut WriteContext,
    write_ref_info: bool,
    write_typeinfo: bool,
    has_generics: bool,
) -> Result<(), Error> {
    if write_ref_info {
        context.writer.write_i8(RefFlag::NotNullValue as i8);
    }
    let concrete_type_id = (**value).type_id();
    let typeinfo = if write_typeinfo {
        context.write_any_typeinfo(concrete_type_id)?
    } else {
        context.get_type_info(&concrete_type_id)?
    };
    let serializer_fn = typeinfo.get_harness().get_write_data_fn();
    serializer_fn(&**value, context, has_generics)
}

pub fn read_box_any(
    context: &mut ReadContext,
    read_ref_info: bool,
    read_type_info: bool,
    type_info: Option<Arc<TypeInfo>>,
) -> Result<Box<dyn Any>, Error> {
    context.inc_depth()?;
    let ref_flag = if read_ref_info {
        context.reader.read_i8()?
    } else {
        RefFlag::NotNullValue as i8
    };
    if ref_flag != RefFlag::NotNullValue as i8 {
        return Err(Error::InvalidData(
            "Expected NotNullValue for Box<dyn Any>".into(),
        ));
    }
    let typeinfo = if let Some(type_info) = type_info {
        type_info
    } else {
        ensure!(
            read_type_info,
            Error::InvalidData("Type info must be read for Box<dyn Any>".into())
        );
        context.read_any_typeinfo()?
    };
    let deserializer_fn = typeinfo.get_harness().get_read_data_fn();
    let result = deserializer_fn(context);
    context.dec_depth();
    result
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
        write_ref_info: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if !write_ref_info
            || !context
                .ref_writer
                .try_write_rc_ref(&mut context.writer, self)
        {
            let concrete_type_id: std::any::TypeId = (**self).type_id();
            let write_data_fn = if write_type_info {
                let typeinfo = context.write_any_typeinfo(concrete_type_id)?;
                typeinfo.get_harness().get_write_data_fn()
            } else {
                context
                    .get_type_info(&concrete_type_id)?
                    .get_harness()
                    .get_write_data_fn()
            };
            write_data_fn(&**self, context, has_generics)?;
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

    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_rc_any(context, read_ref_info, read_type_info, None)
    }

    fn fory_read_with_typeinfo(
        context: &mut ReadContext,
        read_ref_info: bool,
        type_info: Arc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_rc_any(context, read_ref_info, false, Some(type_info))
    }

    fn fory_read_data(_: &mut ReadContext) -> Result<Self, Error> {
        panic!(
            "fory_read_data should not be called directly on polymorphic Rc<dyn {}> trait object",
            stringify!($trait_name)
        );
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

pub fn read_rc_any(
    context: &mut ReadContext,
    read_ref_info: bool,
    read_type_info: bool,
    type_info: Option<Arc<TypeInfo>>,
) -> Result<Rc<dyn Any>, Error> {
    let ref_flag = if read_ref_info {
        context.ref_reader.read_ref_flag(&mut context.reader)?
    } else {
        RefFlag::NotNullValue
    };
    match ref_flag {
        RefFlag::Null => Err(Error::InvalidRef("Rc<dyn Any> cannot be null".into())),
        RefFlag::Ref => {
            let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
            context
                .ref_reader
                .get_rc_ref::<dyn Any>(ref_id)
                .ok_or_else(|| {
                    Error::InvalidData(format!("Rc<dyn Any> reference {} not found", ref_id).into())
                })
        }
        RefFlag::NotNullValue => {
            context.inc_depth()?;
            let typeinfo = if read_type_info {
                context.read_any_typeinfo()?
            } else {
                type_info.ok_or_else(|| Error::TypeError("No type info found for read".into()))?
            };
            let read_data_fn = typeinfo.get_harness().get_read_data_fn();
            let boxed = read_data_fn(context)?;
            context.dec_depth();
            Ok(Rc::<dyn Any>::from(boxed))
        }
        RefFlag::RefValue => {
            context.inc_depth()?;
            let typeinfo = if read_type_info {
                context.read_any_typeinfo()?
            } else {
                type_info.ok_or_else(|| Error::TypeError("No type info found for read".into()))?
            };
            let read_data_fn = typeinfo.get_harness().get_read_data_fn();
            let boxed = read_data_fn(context)?;
            context.dec_depth();
            let rc: Rc<dyn Any> = Rc::from(boxed);
            context.ref_reader.store_rc_ref(rc.clone());
            Ok(rc)
        }
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
        write_ref_info: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if !write_ref_info
            || !context
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

    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_arc_any(context, read_ref_info, read_type_info, None)
    }

    fn fory_read_with_typeinfo(
        context: &mut ReadContext,
        read_ref_info: bool,
        type_info: Arc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_arc_any(context, read_ref_info, false, Some(type_info))
    }

    fn fory_read_data(_: &mut ReadContext) -> Result<Self, Error> {
        panic!(
            "fory_read_data should not be called directly on polymorphic Rc<dyn {}> trait object",
            stringify!($trait_name)
        );
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

pub fn read_arc_any(
    context: &mut ReadContext,
    read_ref_info: bool,
    read_type_info: bool,
    type_info: Option<Arc<TypeInfo>>,
) -> Result<Arc<dyn Any>, Error> {
    let ref_flag = if read_ref_info {
        context.ref_reader.read_ref_flag(&mut context.reader)?
    } else {
        RefFlag::NotNullValue
    };
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
            let typeinfo = if read_type_info {
                context.read_any_typeinfo()?
            } else {
                type_info.ok_or_else(|| {
                    Error::TypeError("No type info found for read Arc<dyn Any>".into())
                })?
            };
            let read_data_fn = typeinfo.get_harness().get_read_data_fn();
            let boxed = read_data_fn(context)?;
            context.dec_depth();
            Ok(Arc::<dyn Any>::from(boxed))
        }
        RefFlag::RefValue => {
            context.inc_depth()?;
            let typeinfo = if read_type_info {
                context.read_any_typeinfo()?
            } else {
                type_info.ok_or_else(|| {
                    Error::TypeError("No type info found for read Arc<dyn Any>".into())
                })?
            };
            let read_data_fn = typeinfo.get_harness().get_read_data_fn();
            let boxed = read_data_fn(context)?;
            context.dec_depth();
            let arc: Arc<dyn Any> = Arc::from(boxed);
            context.ref_reader.store_arc_ref(arc.clone());
            Ok(arc)
        }
    }
}
