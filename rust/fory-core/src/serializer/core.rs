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
use crate::meta::FieldInfo;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::TypeInfo;
use crate::serializer::{bool, struct_};
use crate::types::{RefFlag, TypeId};
use crate::TypeResolver;
use std::any::Any;
use std::rc::Rc;

pub trait ForyDefault: Sized {
    fn fory_default() -> Self;
}

// We can't add blanket impl for all T: Default because it conflicts with other impls.
// For example, upstream crates may add a new impl of trait `std::default::Default` for
// type `std::rc::Rc<(dyn std::any::Any + 'static)>` in future versions.
// impl<T: Default + Sized> ForyDefault for T {
//     fn fory_default() -> Self {
//         Default::default()
//     }
// }

pub trait Serializer: 'static {
    /// Entry point of the serialization.
    ///
    /// # Parameters
    ///
    /// * `write_ref_info` - When `true`, WRITES reference flag (null/not-null/ref). When `false`, SKIPS writing ref flag.
    /// * `write_type_info` - When `true`, WRITES type information. When `false`, SKIPS writing type info.
    /// * `has_generics` - Indicates if the type has generic parameters (used for collection meta).
    ///
    /// # Notes
    ///
    /// Serializer for `option/rc/arc/weak` should override this method.
    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_ref_info: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error>
    where
        Self: Sized,
    {
        if write_ref_info {
            // skip check option/pointer, the Serializer for such types will override `fory_write`.
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        if write_type_info {
            // Serializer for dynamic types should override `fory_write` to write actual typeinfo.
            Self::fory_write_type_info(context)?;
        }
        self.fory_write_data_generic(context, has_generics)
    }

    /// Write the data into the buffer. Need to be implemented for collection/map.
    /// For other types, just forward to `fory_write_data`.
    #[allow(unused_variables)]
    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        self.fory_write_data(context)
    }

    /// Write the data into the buffer. Need to be implemented.
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error>;

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error>
    where
        Self: Sized,
    {
        // Serializer for internal types should overwrite this method for faster performance.
        let rs_type_id = std::any::TypeId::of::<Self>();
        context.write_any_typeinfo(Self::fory_static_type_id() as u32, rs_type_id)?;
        Ok(())
    }

    /// Entry point of deserialization.
    ///
    /// # Parameters
    ///
    /// * `read_ref_info` - When `true`, READS reference flag from buffer. When `false`, SKIPS reading ref flag.
    /// * `read_type_info` - When `true`, READS type information from buffer. When `false`, SKIPS reading type info.
    ///
    /// # Notes
    ///
    /// Unlike `fory_write`, read doesn't need `has_generics` - it's only used for writing meta.
    /// The meta info is already written in the buffer, so read can parse it directly to decide how to read the data.
    /// Serializer for `option/rc/arc/weak` should override this method.
    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        if read_ref_info {
            let ref_flag = context.reader.read_i8()?;
            match ref_flag {
                flag if flag == RefFlag::Null as i8 => Ok(Self::fory_default()),
                flag if flag == RefFlag::NotNullValue as i8 || flag == RefFlag::RefValue as i8 => {
                    if read_type_info {
                        Self::fory_read_type_info(context)?;
                    }
                    Self::fory_read_data(context)
                }
                flag if flag == RefFlag::Ref as i8 => {
                    Err(Error::invalid_ref("Invalid ref, current type is not a ref"))
                }
                other => Err(Error::invalid_data(format!("Unknown ref flag: {}", other))),
            }
        } else {
            if read_type_info {
                Self::fory_read_type_info(context)?;
            }
            Self::fory_read_data(context)
        }
    }

    /// Deserialization with pre-read type information.
    ///
    /// # Parameters
    ///
    /// * `read_ref_info` - When `true`, READS reference flag from buffer. When `false`, SKIPS reading ref flag.
    /// * `type_info` - Type information that has already been read ahead. DO NOT read type info again from buffer.
    ///
    /// # Notes
    ///
    /// The type info has already been read and is passed as an argument, so this method should NOT read type info from the buffer.
    /// Default implementation ignores the typeinfo, only for morphic types supported by fory directly
    /// or ext type registered by user. Dynamic trait types or reference types should override this method.
    #[allow(unused_variables)]
    fn fory_read_with_type_info(
        context: &mut ReadContext,
        read_ref_info: bool,
        type_info: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        // Default implementation ignores the provided typeinfo because the static type matches.
        // Honor the supplied `read_ref_info` flag so callers can control whether ref metadata is present.
        Self::fory_read(context, read_ref_info, false)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault;

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error>
    where
        Self: Sized,
    {
        // Serializer for internal types should overwrite this method for faster performance.
        context.read_any_typeinfo()?;
        Ok(())
    }

    #[inline(always)]
    fn fory_is_option() -> bool
    where
        Self: Sized,
    {
        false
    }

    #[inline(always)]
    fn fory_is_none(&self) -> bool {
        false
    }

    #[inline(always)]
    fn fory_is_polymorphic() -> bool
    where
        Self: Sized,
    {
        false
    }

    #[inline(always)]
    fn fory_is_shared_ref() -> bool
    where
        Self: Sized,
    {
        false
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        // set to ext to simplify the user defined serializer.
        // serializer for other types will override this method.
        TypeId::EXT
    }

    #[inline(always)]
    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error>
    where
        Self: Sized,
    {
        Ok(type_resolver
            .get_type_info(&std::any::TypeId::of::<Self>())?
            .get_type_id())
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error>;

    #[inline(always)]
    fn fory_concrete_type_id(&self) -> std::any::TypeId {
        std::any::TypeId::of::<Self>()
    }

    /// The possible max memory size of the type.
    /// Used to reserve the buffer space to avoid reallocation, which may hurt performance.
    #[inline(always)]
    fn fory_reserved_space() -> usize
    where
        Self: Sized,
    {
        0
    }

    fn as_any(&self) -> &dyn Any;
}

pub trait StructSerializer: Serializer + 'static {
    #[allow(unused_variables)]
    fn fory_fields_info(type_resolver: &TypeResolver) -> Result<Vec<FieldInfo>, Error> {
        Ok(Vec::default())
    }

    fn fory_type_index() -> u32 {
        unimplemented!()
    }

    fn fory_actual_type_id(type_id: u32, register_by_name: bool, compatible: bool) -> u32 {
        struct_::actual_type_id(type_id, register_by_name, compatible)
    }

    fn fory_get_sorted_field_names() -> &'static [&'static str] {
        &[]
    }

    // only used by struct
    fn fory_read_compatible(
        context: &mut ReadContext,
        type_info: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized;
}
