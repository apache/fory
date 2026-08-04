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

use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::meta::FieldInfo;
use crate::resolver::{RefFlag, RefMode, TypeInfo, TypeResolver};
use crate::serializer::struct_;
use crate::type_id::{self, TypeId};
use std::any::Any;
use std::rc::Rc;
use std::sync::Arc;

#[cold]
#[inline(never)]
fn default_unavailable<S: Serializer>() -> Result<S::Target, Error> {
    Err(Error::type_error(format!(
        "serializer {} has no default for target {}",
        std::any::type_name::<S>(),
        std::any::type_name::<S::Target>(),
    )))
}

/// Static serialization behavior for one runtime [`Serializer::Target`].
///
/// Implementations define type-level serialization behavior and are never
/// instantiated by Fory. Ordinary local types use `Target = Self`.
pub trait Serializer: Sized + 'static {
    type Target: Sized + 'static;

    /// Write the target body only.
    fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error>;

    /// Read the target body only.
    fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error>;

    /// Construct a target for a null or missing local value.
    #[inline(always)]
    fn default_value(context: &mut ReadContext) -> Result<Self::Target, Error> {
        let _ = context;
        default_unavailable::<Self>()
    }

    /// Write a complete value, including the requested reference and type
    /// information envelopes, then write its body.
    #[doc(hidden)]
    #[inline(always)]
    fn write(
        value: &Self::Target,
        context: &mut WriteContext,
        ref_mode: RefMode,
        write_type_info: bool,
    ) -> Result<(), Error> {
        if ref_mode != RefMode::None {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        if write_type_info {
            Self::write_type_info(context)?;
        }
        Self::write_data(value, context)
    }

    /// Resolve and emit metadata for one dynamically selected concrete target.
    ///
    /// Homogeneous collection and map owners retain the returned [`TypeInfo`]
    /// and pass it to every body in that wire chunk.
    #[doc(hidden)]
    #[inline(always)]
    fn write_type_info_value(
        context: &mut WriteContext,
        target_type_id: std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        context.write_target_type_info(Self::static_type_id() as u32, target_type_id)
    }

    /// Write a value using concrete type metadata already emitted by its
    /// containing collection or map owner.
    #[doc(hidden)]
    #[inline(always)]
    fn write_with_type_info(
        value: &Self::Target,
        context: &mut WriteContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<(), Error> {
        let _ = type_info;
        Self::write(value, context, ref_mode, false)
    }

    /// Read a complete value, including the requested reference and type
    /// information envelopes, then read its body.
    #[doc(hidden)]
    #[inline(always)]
    fn read(
        context: &mut ReadContext,
        ref_mode: RefMode,
        read_type_info: bool,
    ) -> Result<Self::Target, Error> {
        if ref_mode != RefMode::None {
            let flag = context.reader.read_i8()?;
            if flag == RefFlag::Null as i8 {
                return Self::default_value(context);
            }
        }
        if read_type_info {
            Self::read_type_info(context)?;
        }
        Self::read_data(context)
    }

    #[doc(hidden)]
    #[inline(always)]
    fn read_with_type_info(
        context: &mut ReadContext,
        ref_mode: RefMode,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Self::Target, Error> {
        let _ = type_info;
        Self::read(context, ref_mode, false)
    }

    #[doc(hidden)]
    #[inline(always)]
    fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.write_provider_type_info(
            Self::static_type_id() as u32,
            std::any::TypeId::of::<Self>(),
        )?;
        Ok(())
    }

    #[doc(hidden)]
    #[inline(always)]
    fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        context.read_any_type_info()?;
        Ok(())
    }

    /// Read directly into the final owner for sync dynamic carriers.
    #[cold]
    #[inline(never)]
    fn read_arc_any(context: &mut ReadContext) -> Result<Arc<dyn Any + Send + Sync>, Error> {
        let _ = context;
        Err(Error::type_error(format!(
            "target {} cannot be represented as Arc<dyn Any + Send + Sync>",
            std::any::type_name::<Self::Target>(),
        )))
    }

    #[doc(hidden)]
    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::EXT
    }

    #[doc(hidden)]
    #[inline(always)]
    fn reserved_space() -> usize {
        std::mem::size_of::<Self::Target>()
    }

    #[doc(hidden)]
    const IS_OPTIONAL: bool = false;

    #[doc(hidden)]
    const IS_POLYMORPHIC: bool = false;

    #[doc(hidden)]
    const IS_SHARED_REF: bool = false;

    #[doc(hidden)]
    const IS_WRAPPER: bool = Self::IS_SHARED_REF;

    #[doc(hidden)]
    const REQUIRES_SCOPED_ACCESS: bool = false;

    /// Whether every successful `read_data` call consumes at least one input byte.
    ///
    /// Custom serializers are conservative by default. Implementations may opt in
    /// only when this property holds for every value they can read.
    #[doc(hidden)]
    const READ_DATA_ALWAYS_ADVANCES: bool = false;

    #[doc(hidden)]
    #[inline(always)]
    fn is_none(value: &Self::Target) -> bool {
        let _ = value;
        false
    }

    /// Return the concrete target selected by a polymorphic value.
    ///
    /// `None` means that the value is absent and has no concrete target.
    #[doc(hidden)]
    #[inline(always)]
    fn dynamic_type_id(value: &Self::Target) -> Result<Option<std::any::TypeId>, Error> {
        let _ = value;
        Ok(Some(std::any::TypeId::of::<Self::Target>()))
    }
}

#[inline(always)]
pub(super) fn read_value_type_info<S: Serializer>(
    context: &mut ReadContext,
) -> Result<Option<Rc<TypeInfo>>, Error> {
    // Static built-in carrier headers are compact type IDs, not registered
    // serializers. Compatible metadata and native polymorphic collection/map
    // groups require a retained TypeInfo for their body reads; native static
    // serializers keep their direct validated path.
    if (context.is_compatible() || S::IS_POLYMORPHIC)
        && !type_id::is_internal_type(S::static_type_id() as u32)
    {
        return context.read_any_type_info().map(Some);
    }
    S::read_type_info(context)?;
    Ok(None)
}

/// Schema metadata and compatible reads for derive-generated serializers.
pub trait StructSerializer: Serializer {
    fn type_index() -> u32;

    #[cold]
    #[inline(never)]
    fn actual_type_id(
        type_id: u32,
        register_by_name: bool,
        compatible: bool,
        xlang: bool,
    ) -> Result<u32, Error> {
        let _ = xlang;
        Ok(struct_::actual_type_id(
            type_id,
            register_by_name,
            compatible,
        ))
    }

    fn fields_info(type_resolver: &TypeResolver) -> Result<Vec<FieldInfo>, Error>;

    fn variants_fields_info(
        type_resolver: &TypeResolver,
    ) -> Result<Vec<(String, std::any::TypeId, Vec<FieldInfo>)>, Error>;

    fn sorted_field_names() -> &'static [&'static str];

    fn read_compatible(
        context: &mut ReadContext,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Self::Target, Error>;

    #[cold]
    #[inline(never)]
    fn read_compatible_arc_any(
        context: &mut ReadContext,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Arc<dyn Any + Send + Sync>, Error> {
        let _ = context;
        let _ = type_info;
        Err(Error::type_error(format!(
            "target {} cannot be represented as Arc<dyn Any + Send + Sync>",
            std::any::type_name::<Self::Target>(),
        )))
    }
}

#[inline(always)]
pub fn write_data<S: Serializer>(
    value: &S::Target,
    context: &mut WriteContext,
) -> Result<(), Error> {
    S::write_data(value, context)
}

#[inline(always)]
pub fn read_data<S: Serializer>(context: &mut ReadContext) -> Result<S::Target, Error> {
    S::read_data(context)
}
