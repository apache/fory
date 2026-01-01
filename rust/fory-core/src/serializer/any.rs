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
use crate::types::{RefFlag, RefMode, TypeId, LIST, MAP, SET};
use std::any::Any;
use std::rc::Rc;
use std::sync::Arc;

/// Check if the type info represents a generic container type (LIST, SET, MAP).
/// These types cannot be deserialized polymorphically via `Box<dyn Any>` because
/// different generic instantiations (e.g., `Vec<A>`, `Vec<B>`) share the same type ID.
#[inline]
fn check_generic_container_type(type_info: &TypeInfo) -> Result<(), Error> {
    let type_id = type_info.get_type_id();
    if type_id == LIST || type_id == SET || type_id == MAP {
        return Err(Error::type_error(
            "Cannot deserialize generic container types (Vec, HashSet, HashMap) polymorphically \
            via Box/Rc/Arc/Weak<dyn Any>. The serialization protocol does not preserve the element type \
            information needed to distinguish between different generic instantiations \
            (e.g., Vec<StructA> vs Vec<StructB>). Consider wrapping the container in a \
            named struct type instead.",
        ));
    }
    Ok(())
}

/// Helper function to deserialize to `Box<dyn Any>`
pub fn deserialize_any_box(context: &mut ReadContext) -> Result<Box<dyn Any>, Error> {
    context.inc_depth()?;
    let ref_flag = context.reader.read_i8()?;
    if ref_flag != RefFlag::NotNullValue as i8 {
        return Err(Error::invalid_ref("Expected NotNullValue for Box<dyn Any>"));
    }
    let typeinfo = context.read_any_typeinfo()?;
    // Check for generic container types which cannot be deserialized polymorphically
    check_generic_container_type(&typeinfo)?;
    let result = typeinfo
        .get_harness()
        .read_polymorphic_data(context, &typeinfo);
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
        ref_mode: RefMode,
        write_typeinfo: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_box_any(
            self.as_ref(),
            context,
            ref_mode,
            write_typeinfo,
            has_generics,
        )
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
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_box_any(context, ref_mode, read_type_info, None)
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_box_any(context, ref_mode, false, Some(type_info))
    }

    fn fory_read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Err(Error::not_allowed(
            "fory_read_data should not be called directly on polymorphic Rc<dyn Any> trait object",
        ))
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Err(Error::type_error(
            "Box<dyn Any> has no static type ID - use fory_type_id_dyn",
        ))
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        let concrete_type_id = (**self).type_id();
        type_resolver
            .get_fory_type_id(concrete_type_id)
            .ok_or_else(|| Error::type_error("Type not registered"))
    }

    fn fory_concrete_type_id(&self) -> std::any::TypeId {
        (**self).type_id()
    }

    fn fory_is_polymorphic() -> bool {
        true
    }

    fn fory_is_shared_ref() -> bool {
        false
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
    value: &dyn Any,
    context: &mut WriteContext,
    ref_mode: RefMode,
    write_typeinfo: bool,
    has_generics: bool,
) -> Result<(), Error> {
    if ref_mode != RefMode::None {
        context.writer.write_i8(RefFlag::NotNullValue as i8);
    }
    let concrete_type_id = value.type_id();
    let typeinfo = if write_typeinfo {
        context.write_any_typeinfo(TypeId::UNKNOWN as u32, concrete_type_id)?
    } else {
        context.get_type_info(&concrete_type_id)?
    };
    let serializer_fn = typeinfo.get_harness().get_write_data_fn();
    serializer_fn(value, context, has_generics)
}

pub fn read_box_any(
    context: &mut ReadContext,
    ref_mode: RefMode,
    read_type_info: bool,
    type_info: Option<Rc<TypeInfo>>,
) -> Result<Box<dyn Any>, Error> {
    context.inc_depth()?;
    let ref_flag = if ref_mode != RefMode::None {
        context.reader.read_i8()?
    } else {
        RefFlag::NotNullValue as i8
    };
    if ref_flag != RefFlag::NotNullValue as i8 {
        return Err(Error::invalid_data(
            "Expected NotNullValue for Box<dyn Any>",
        ));
    }
    let typeinfo = if let Some(type_info) = type_info {
        type_info
    } else {
        ensure!(
            read_type_info,
            Error::invalid_data("Type info must be read for Box<dyn Any>")
        );
        context.read_any_typeinfo()?
    };
    // Check for generic container types which cannot be deserialized polymorphically
    check_generic_container_type(&typeinfo)?;
    let result = typeinfo
        .get_harness()
        .read_polymorphic_data(context, &typeinfo);
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
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if ref_mode == RefMode::None
            || !context
                .ref_writer
                .try_write_rc_ref(&mut context.writer, self)
        {
            let concrete_type_id: std::any::TypeId = (**self).type_id();
            let write_data_fn = if write_type_info {
                let typeinfo =
                    context.write_any_typeinfo(TypeId::UNKNOWN as u32, concrete_type_id)?;
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
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_rc_any(context, ref_mode, read_type_info, None)
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_rc_any(context, ref_mode, false, Some(type_info))
    }

    fn fory_read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Err(Error::not_allowed(format!(
            "fory_read_data should not be called directly on polymorphic Rc<dyn {}> trait object",
            stringify!($trait_name)
        )))
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Err(Error::type_error(
            "Rc<dyn Any> has no static type ID - use fory_type_id_dyn",
        ))
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        let concrete_type_id = (**self).type_id();
        type_resolver
            .get_fory_type_id(concrete_type_id)
            .ok_or_else(|| Error::type_error("Type not registered"))
    }

    fn fory_concrete_type_id(&self) -> std::any::TypeId {
        (**self).type_id()
    }

    fn fory_is_shared_ref() -> bool {
        true
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
    ref_mode: RefMode,
    read_type_info: bool,
    type_info: Option<Rc<TypeInfo>>,
) -> Result<Rc<dyn Any>, Error> {
    let ref_flag = if ref_mode != RefMode::None {
        context.ref_reader.read_ref_flag(&mut context.reader)?
    } else {
        RefFlag::NotNullValue
    };
    match ref_flag {
        RefFlag::Null => Err(Error::invalid_ref("Rc<dyn Any> cannot be null")),
        RefFlag::Ref => {
            let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
            context
                .ref_reader
                .get_rc_ref::<dyn Any>(ref_id)
                .ok_or_else(|| {
                    Error::invalid_data(format!("Rc<dyn Any> reference {} not found", ref_id))
                })
        }
        RefFlag::NotNullValue => {
            context.inc_depth()?;
            let typeinfo = if read_type_info {
                context.read_any_typeinfo()?
            } else {
                type_info.ok_or_else(|| Error::type_error("No type info found for read"))?
            };
            // Check for generic container types which cannot be deserialized polymorphically
            check_generic_container_type(&typeinfo)?;
            let boxed = typeinfo
                .get_harness()
                .read_polymorphic_data(context, &typeinfo)?;
            context.dec_depth();
            Ok(Rc::<dyn Any>::from(boxed))
        }
        RefFlag::RefValue => {
            context.inc_depth()?;
            let typeinfo = if read_type_info {
                context.read_any_typeinfo()?
            } else {
                type_info.ok_or_else(|| Error::type_error("No type info found for read"))?
            };
            // Check for generic container types which cannot be deserialized polymorphically
            check_generic_container_type(&typeinfo)?;
            let boxed = typeinfo
                .get_harness()
                .read_polymorphic_data(context, &typeinfo)?;
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
        ref_mode: RefMode,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if ref_mode == RefMode::None
            || !context
                .ref_writer
                .try_write_arc_ref(&mut context.writer, self)
        {
            let concrete_type_id: std::any::TypeId = (**self).type_id();
            if write_type_info {
                let typeinfo =
                    context.write_any_typeinfo(TypeId::UNKNOWN as u32, concrete_type_id)?;
                let serializer_fn = typeinfo.get_harness().get_write_data_fn();
                serializer_fn(&**self, context, has_generics)?;
            } else {
                let serializer_fn = context
                    .get_type_info(&concrete_type_id)?
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
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_arc_any(context, ref_mode, read_type_info, None)
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_arc_any(context, ref_mode, false, Some(type_info))
    }

    fn fory_read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Err(Error::not_allowed(format!(
            "fory_read_data should not be called directly on polymorphic Rc<dyn {}> trait object",
            stringify!($trait_name)
        )))
    }

    fn fory_get_type_id(_type_resolver: &TypeResolver) -> Result<u32, Error> {
        Err(Error::type_error(
            "Arc<dyn Any> has no static type ID - use fory_type_id_dyn",
        ))
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        let concrete_type_id = (**self).type_id();
        type_resolver
            .get_fory_type_id(concrete_type_id)
            .ok_or_else(|| Error::type_error("Type not registered"))
    }

    fn fory_concrete_type_id(&self) -> std::any::TypeId {
        (**self).type_id()
    }

    fn fory_is_polymorphic() -> bool {
        true
    }

    fn fory_is_shared_ref() -> bool {
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
    ref_mode: RefMode,
    read_type_info: bool,
    type_info: Option<Rc<TypeInfo>>,
) -> Result<Arc<dyn Any>, Error> {
    let ref_flag = if ref_mode != RefMode::None {
        context.ref_reader.read_ref_flag(&mut context.reader)?
    } else {
        RefFlag::NotNullValue
    };
    match ref_flag {
        RefFlag::Null => Err(Error::invalid_ref("Arc<dyn Any> cannot be null")),
        RefFlag::Ref => {
            let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
            context
                .ref_reader
                .get_arc_ref::<dyn Any>(ref_id)
                .ok_or_else(|| {
                    Error::invalid_data(format!("Arc<dyn Any> reference {} not found", ref_id))
                })
        }
        RefFlag::NotNullValue => {
            context.inc_depth()?;
            let typeinfo = if read_type_info {
                context.read_any_typeinfo()?
            } else {
                type_info
                    .ok_or_else(|| Error::type_error("No type info found for read Arc<dyn Any>"))?
            };
            // Check for generic container types which cannot be deserialized polymorphically
            check_generic_container_type(&typeinfo)?;
            let boxed = typeinfo
                .get_harness()
                .read_polymorphic_data(context, &typeinfo)?;
            context.dec_depth();
            Ok(Arc::<dyn Any>::from(boxed))
        }
        RefFlag::RefValue => {
            context.inc_depth()?;
            let typeinfo = if read_type_info {
                context.read_any_typeinfo()?
            } else {
                type_info
                    .ok_or_else(|| Error::type_error("No type info found for read Arc<dyn Any>"))?
            };
            // Check for generic container types which cannot be deserialized polymorphically
            check_generic_container_type(&typeinfo)?;
            let boxed = typeinfo
                .get_harness()
                .read_polymorphic_data(context, &typeinfo)?;
            context.dec_depth();
            let arc: Arc<dyn Any> = Arc::from(boxed);
            context.ref_reader.store_arc_ref(arc.clone());
            Ok(arc)
        }
    }
}
