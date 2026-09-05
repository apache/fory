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
use crate::meta::{
    MetaString, TypeMeta, NAMESPACE_ENCODER, NAMESPACE_ENCODINGS, TYPE_NAME_ENCODER,
    TYPE_NAME_ENCODINGS,
};
use crate::serializer::{Serializer, StructSerializer};
use crate::type_id::{get_ext_actual_type_id, is_enum_type_id};
use crate::types::{Date, Duration, Timestamp};
use crate::TypeId;
#[cfg(feature = "chrono")]
use chrono::{Duration as ChronoDuration, NaiveDate, NaiveDateTime};
use std::rc::Rc;
use std::vec;

use std::{any::Any, collections::HashMap};

#[inline(always)]
fn supports_type_def(type_id: u32) -> bool {
    matches!(
        type_id,
        x if x == TypeId::ENUM as u32
            || x == TypeId::NAMED_ENUM as u32
            || x == TypeId::STRUCT as u32
            || x == TypeId::COMPATIBLE_STRUCT as u32
            || x == TypeId::NAMED_STRUCT as u32
            || x == TypeId::NAMED_COMPATIBLE_STRUCT as u32
            || x == TypeId::EXT as u32
            || x == TypeId::NAMED_EXT as u32
            || x == TypeId::TYPED_UNION as u32
            || x == TypeId::NAMED_UNION as u32
    )
}

type WriteDataFn = fn(&dyn Any, &mut WriteContext) -> Result<(), Error>;
type ReadBoxAnyFn = fn(&mut ReadContext) -> Result<Box<dyn Any>, Error>;
type ReadRcAnyFn = fn(&mut ReadContext) -> Result<Rc<dyn Any>, Error>;
type ReadArcAnyFn = fn(&mut ReadContext) -> Result<std::sync::Arc<dyn Any + Send + Sync>, Error>;
type ReadCompatibleBoxAnyFn = fn(&mut ReadContext, &Rc<TypeInfo>) -> Result<Box<dyn Any>, Error>;
type ReadCompatibleRcAnyFn = fn(&mut ReadContext, &Rc<TypeInfo>) -> Result<Rc<dyn Any>, Error>;
type ReadCompatibleArcAnyFn =
    fn(&mut ReadContext, &Rc<TypeInfo>) -> Result<std::sync::Arc<dyn Any + Send + Sync>, Error>;
type BuildTypeInfosFn = fn(&TypeResolver) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error>;
const EMPTY_STRING: String = String::new();
const INTERNAL_TYPE_ID_LIMIT: usize = 256;
const MAX_USER_TYPE_ID: u32 = 0xfffffffe;
pub(crate) const NO_USER_TYPE_ID: u32 = u32::MAX;

#[cold]
#[inline(never)]
fn validate_named_registration(name: &str, api: &str) -> Result<(), Error> {
    if name.is_empty() {
        return Err(Error::not_allowed(format!(
            "name must be non-empty for {}",
            api
        )));
    }
    Ok(())
}

#[cold]
#[inline(never)]
fn split_named_registration<'a>(name: &'a str, api: &str) -> Result<(&'a str, &'a str), Error> {
    let (namespace, type_name) = match name.rsplit_once('.') {
        Some((namespace, name)) => (namespace, name),
        None => ("", name),
    };
    validate_named_registration(type_name, api)?;
    Ok((namespace, type_name))
}

#[cold]
#[inline(never)]
fn missing_provider_type_info(provider_type_id: &std::any::TypeId) -> Error {
    Error::type_error(format!(
        "serializer {:?} is not registered",
        provider_type_id
    ))
}

#[cold]
#[inline(never)]
fn missing_target_type_info(target_type_id: &std::any::TypeId) -> Error {
    Error::type_error(format!("target {:?} is not registered", target_type_id))
}

#[derive(Clone, Debug)]
pub struct Harness {
    target_type_id: Option<std::any::TypeId>,
    write_data_fn: WriteDataFn,
    read_box_any_fn: ReadBoxAnyFn,
    read_rc_any_fn: ReadRcAnyFn,
    read_arc_any_fn: ReadArcAnyFn,
    read_compatible_box_any_fn: Option<ReadCompatibleBoxAnyFn>,
    read_compatible_rc_any_fn: Option<ReadCompatibleRcAnyFn>,
    read_compatible_arc_any_fn: Option<ReadCompatibleArcAnyFn>,
    build_type_infos: BuildTypeInfosFn,
}

impl Harness {
    pub fn stub() -> Harness {
        Harness {
            target_type_id: None,
            write_data_fn: stub_write_data_fn,
            read_box_any_fn: stub_read_box_any_fn,
            read_rc_any_fn: stub_read_rc_any_fn,
            read_arc_any_fn: stub_read_arc_any_fn,
            read_compatible_box_any_fn: None,
            read_compatible_rc_any_fn: None,
            read_compatible_arc_any_fn: None,
            build_type_infos: stub_build_type_infos,
        }
    }

    #[inline(always)]
    pub fn target_type_id(&self) -> Option<std::any::TypeId> {
        self.target_type_id
    }

    #[inline(always)]
    pub fn write_data(&self, value: &dyn Any, context: &mut WriteContext) -> Result<(), Error> {
        (self.write_data_fn)(value, context)
    }

    #[inline(always)]
    pub fn read_box_any(
        &self,
        context: &mut ReadContext,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Box<dyn Any>, Error> {
        if context.is_compatible() {
            if let Some(read_compatible_fn) = self.read_compatible_box_any_fn {
                return read_compatible_fn(context, type_info);
            }
        }
        (self.read_box_any_fn)(context)
    }

    #[inline(always)]
    pub fn read_rc_any(
        &self,
        context: &mut ReadContext,
        type_info: &Rc<TypeInfo>,
    ) -> Result<Rc<dyn Any>, Error> {
        if context.is_compatible() {
            if let Some(read_compatible_fn) = self.read_compatible_rc_any_fn {
                return read_compatible_fn(context, type_info);
            }
        }
        (self.read_rc_any_fn)(context)
    }

    #[inline(always)]
    pub fn read_arc_any(
        &self,
        context: &mut ReadContext,
        type_info: &Rc<TypeInfo>,
    ) -> Result<std::sync::Arc<dyn Any + Send + Sync>, Error> {
        if context.is_compatible() {
            if let Some(read_compatible_fn) = self.read_compatible_arc_any_fn {
                return read_compatible_fn(context, type_info);
            }
        }
        (self.read_arc_any_fn)(context)
    }
}

#[derive(Clone, Debug)]
pub struct TypeInfo {
    type_def: Rc<Vec<u8>>,
    type_meta: Rc<TypeMeta>,
    type_id: TypeId,
    user_type_id: u32,
    namespace: Rc<MetaString>,
    type_name: Rc<MetaString>,
    register_by_name: bool,
    // False only for a remote TypeMeta paired with the local harness. Those
    // values must read through compatible schema mapping, not direct local data.
    exact_local_schema: bool,
    harness: Harness,
}

impl TypeInfo {
    fn new(
        type_id: u32,
        user_type_id: u32,
        namespace: &str,
        type_name: &str,
        register_by_name: bool,
        harness: Harness,
    ) -> Result<TypeInfo, Error> {
        let type_id = TypeId::try_from(type_id as u8)
            .map_err(|_| Error::type_error(format!("Unknown type id {}", type_id)))?;
        let namespace_meta_string =
            NAMESPACE_ENCODER.encode_with_encodings(namespace, NAMESPACE_ENCODINGS)?;
        let type_name_meta_string =
            TYPE_NAME_ENCODER.encode_with_encodings(type_name, TYPE_NAME_ENCODINGS)?;
        Ok(TypeInfo {
            type_def: Rc::from(vec![]),
            type_meta: Rc::new(TypeMeta::empty()?),
            type_id,
            user_type_id,
            namespace: Rc::from(namespace_meta_string),
            type_name: Rc::from(type_name_meta_string),
            register_by_name,
            exact_local_schema: true,
            harness,
        })
    }

    fn new_with_type_meta(type_meta: Rc<TypeMeta>, harness: Harness) -> Result<TypeInfo, Error> {
        let type_id_raw = type_meta.get_type_id();
        let type_id = TypeId::try_from(type_id_raw as u8)
            .map_err(|_| Error::type_error(format!("Unknown type id {}", type_id_raw)))?;
        let user_type_id = type_meta.get_user_type_id();
        let namespace = type_meta.get_namespace();
        let type_name = type_meta.get_type_name();
        let register_by_name = !namespace.original.is_empty() || !type_name.original.is_empty();
        let type_def_bytes = type_meta.get_bytes().to_owned();
        Ok(TypeInfo {
            type_def: Rc::from(type_def_bytes),
            type_meta,
            type_id,
            user_type_id,
            namespace,
            type_name,
            register_by_name,
            exact_local_schema: true,
            harness,
        })
    }

    #[inline(always)]
    pub fn get_type_id(&self) -> TypeId {
        self.type_id
    }

    #[inline(always)]
    pub fn get_user_type_id(&self) -> u32 {
        self.user_type_id
    }

    #[inline(always)]
    pub fn get_namespace(&self) -> Rc<MetaString> {
        self.namespace.clone()
    }

    #[inline(always)]
    pub fn get_type_name(&self) -> Rc<MetaString> {
        self.type_name.clone()
    }

    #[inline(always)]
    pub fn get_type_def(&self) -> Rc<Vec<u8>> {
        self.type_def.clone()
    }

    #[inline(always)]
    pub fn get_type_meta(&self) -> Rc<TypeMeta> {
        self.type_meta.clone()
    }

    #[inline(always)]
    pub fn get_type_meta_ref(&self) -> &TypeMeta {
        self.type_meta.as_ref()
    }

    #[inline(always)]
    pub fn is_registered_by_name(&self) -> bool {
        self.register_by_name
    }

    /// Returns whether metadata resolution selected the exact local schema.
    #[inline(always)]
    #[doc(hidden)]
    pub fn has_exact_local_schema(&self) -> bool {
        self.exact_local_schema
    }

    #[inline(always)]
    pub fn get_harness(&self) -> &Harness {
        &self.harness
    }

    /// Creates a deep clone with new Rc instances.
    /// This is safe for concurrent use from multiple threads.
    pub fn deep_clone(&self) -> TypeInfo {
        TypeInfo {
            type_def: Rc::new((*self.type_def).clone()),
            type_meta: Rc::new(self.type_meta.deep_clone()),
            type_id: self.type_id,
            user_type_id: self.user_type_id,
            namespace: Rc::new((*self.namespace).clone()),
            type_name: Rc::new((*self.type_name).clone()),
            register_by_name: self.register_by_name,
            exact_local_schema: self.exact_local_schema,
            harness: self.harness.clone(),
        }
    }

    /// Create a TypeInfo from remote TypeMeta with a stub harness
    /// Used when the type doesn't exist locally during deserialization
    pub fn from_remote_meta(
        remote_meta: Rc<TypeMeta>,
        local_harness: Option<&Harness>,
        type_id_override: Option<u32>,
        user_type_id_override: Option<u32>,
    ) -> TypeInfo {
        let type_id_raw = type_id_override.unwrap_or_else(|| remote_meta.get_type_id());
        let type_id = TypeId::try_from(type_id_raw as u8).unwrap_or(TypeId::UNKNOWN);
        let user_type_id = user_type_id_override.unwrap_or_else(|| remote_meta.get_user_type_id());
        let namespace = remote_meta.get_namespace();
        let type_name = remote_meta.get_type_name();
        let type_def_bytes = remote_meta.get_bytes().to_owned();
        let register_by_name = !namespace.original.is_empty() || !type_name.original.is_empty();

        let harness = if let Some(h) = local_harness {
            h.clone()
        } else {
            Harness::stub()
        };

        TypeInfo {
            type_def: Rc::from(type_def_bytes),
            type_meta: remote_meta,
            type_id,
            user_type_id,
            namespace,
            type_name,
            register_by_name,
            exact_local_schema: false,
            harness,
        }
    }
}

// Stub functions for when a type doesn't exist locally
#[cold]
#[inline(never)]
fn stub_write_data_fn(_: &dyn Any, _: &mut WriteContext) -> Result<(), Error> {
    Err(Error::type_error(
        "Cannot serialize unknown remote type - type not registered locally",
    ))
}

#[cold]
#[inline(never)]
fn stub_read_box_any_fn(_: &mut ReadContext) -> Result<Box<dyn Any>, Error> {
    Err(Error::type_error(
        "Cannot deserialize unknown remote type - type not registered locally",
    ))
}

#[cold]
#[inline(never)]
fn stub_read_rc_any_fn(_: &mut ReadContext) -> Result<Rc<dyn Any>, Error> {
    Err(Error::type_error(
        "Cannot deserialize unknown remote type as Rc<dyn Any> - type not registered locally",
    ))
}

#[cold]
#[inline(never)]
fn stub_read_arc_any_fn(
    _: &mut ReadContext,
) -> Result<std::sync::Arc<dyn Any + Send + Sync>, Error> {
    Err(Error::type_error(
        "Cannot deserialize unknown remote type as Arc<dyn Any + Send + Sync> - type not registered locally",
    ))
}

#[cold]
#[inline(never)]
fn stub_build_type_infos(_: &TypeResolver) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error> {
    Err(Error::type_error(
        "Cannot get type infos for unknown remote type",
    ))
}

#[cold]
#[inline(never)]
fn target_downcast_error<S: Serializer>(actual: std::any::TypeId) -> Error {
    Error::type_error(format!(
        "serializer {} expected target {} but received erased TypeId {:?}",
        std::any::type_name::<S>(),
        std::any::type_name::<S::Target>(),
        actual,
    ))
}

#[inline(always)]
fn reserve_dynamic_owner<S: Serializer>(context: &mut ReadContext) -> Result<(), Error> {
    let bytes = std::mem::size_of::<S::Target>();
    if bytes != 0 {
        context.reserve_graph_memory(bytes)?;
    }
    Ok(())
}

fn write_target_data<S: Serializer>(
    value: &dyn Any,
    context: &mut WriteContext,
) -> Result<(), Error> {
    let target = value
        .downcast_ref::<S::Target>()
        .ok_or_else(|| target_downcast_error::<S>(value.type_id()))?;
    S::write_data(target, context)
}

fn read_target_box<S: Serializer>(context: &mut ReadContext) -> Result<Box<dyn Any>, Error> {
    reserve_dynamic_owner::<S>(context)?;
    Ok(Box::new(S::read_data(context)?))
}

fn read_target_rc<S: Serializer>(context: &mut ReadContext) -> Result<Rc<dyn Any>, Error> {
    reserve_dynamic_owner::<S>(context)?;
    Ok(Rc::new(S::read_data(context)?))
}

fn read_target_arc<S: Serializer>(
    context: &mut ReadContext,
) -> Result<std::sync::Arc<dyn Any + Send + Sync>, Error> {
    reserve_dynamic_owner::<S>(context)?;
    S::read_arc_any(context)
}

fn read_compatible_target_box<S: StructSerializer>(
    context: &mut ReadContext,
    type_info: &Rc<TypeInfo>,
) -> Result<Box<dyn Any>, Error> {
    reserve_dynamic_owner::<S>(context)?;
    Ok(Box::new(S::read_compatible(context, type_info)?))
}

fn read_compatible_target_rc<S: StructSerializer>(
    context: &mut ReadContext,
    type_info: &Rc<TypeInfo>,
) -> Result<Rc<dyn Any>, Error> {
    reserve_dynamic_owner::<S>(context)?;
    Ok(Rc::new(S::read_compatible(context, type_info)?))
}

fn read_compatible_target_arc<S: StructSerializer>(
    context: &mut ReadContext,
    type_info: &Rc<TypeInfo>,
) -> Result<std::sync::Arc<dyn Any + Send + Sync>, Error> {
    reserve_dynamic_owner::<S>(context)?;
    S::read_compatible_arc_any(context, type_info)
}

/// Helper function to build type infos for struct types
fn build_struct_type_infos<T: StructSerializer>(
    type_resolver: &TypeResolver,
) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error> {
    let partial_info = type_resolver
        .partial_type_infos
        .get(&std::any::TypeId::of::<T>())
        .ok_or_else(|| {
            Error::type_error(format!(
                "Partial type info not found for struct (type: {})",
                std::any::type_name::<T>()
            ))
        })?;

    // Get sorted field infos (fields are already sorted and have IDs assigned by the macro)
    let sorted_field_infos = T::fields_info(type_resolver)?;

    // Build the main type info
    let type_meta = TypeMeta::from_fields(
        partial_info.type_id as u32,
        partial_info.user_type_id,
        (*partial_info.namespace).clone(),
        (*partial_info.type_name).clone(),
        partial_info.register_by_name,
        sorted_field_infos,
    )?;
    let type_def_bytes = type_meta.get_bytes().to_owned();
    let main_type_info = TypeInfo {
        type_def: Rc::from(type_def_bytes),
        type_meta: Rc::new(type_meta),
        type_id: partial_info.type_id,
        user_type_id: partial_info.user_type_id,
        namespace: partial_info.namespace.clone(),
        type_name: partial_info.type_name.clone(),
        register_by_name: partial_info.register_by_name,
        exact_local_schema: true,
        harness: partial_info.harness.clone(),
    };

    let mut result = vec![(std::any::TypeId::of::<T>(), main_type_info)];

    // Handle enum variants in compatible mode
    // Check for ENUM, NAMED_ENUM, and UNION (Union-compatible Rust enums return UNION TypeId)
    if type_resolver.compatible && is_enum_type_id(T::static_type_id()) {
        // Fields are already sorted with IDs assigned by the macro
        let variants_info = T::variants_fields_info(type_resolver)?;
        for (variant_name, variant_type_id, fields_info) in variants_info.into_iter() {
            // Skip empty variant info (unit/unnamed variants)
            if fields_info.is_empty() {
                continue;
            }
            // Create TypeMeta for the variant
            let variant_type_meta = if partial_info.register_by_name {
                let variant_type_name =
                    format!("{}_{}", partial_info.type_name.original, variant_name);
                let namespace_ms = NAMESPACE_ENCODER
                    .encode_with_encodings(&partial_info.namespace.original, NAMESPACE_ENCODINGS)?;
                let type_name_ms = TYPE_NAME_ENCODER
                    .encode_with_encodings(&variant_type_name, TYPE_NAME_ENCODINGS)?;
                TypeMeta::from_fields(
                    TypeId::NAMED_COMPATIBLE_STRUCT as u32,
                    NO_USER_TYPE_ID,
                    namespace_ms,
                    type_name_ms,
                    true,
                    fields_info.clone(),
                )?
            } else {
                if partial_info.user_type_id == NO_USER_TYPE_ID {
                    return Err(Error::type_error(
                        "Enum variant metadata requires a user type id",
                    ));
                }
                let variant_type_name = format!(
                    "__fory_enum_variant__{}_{}",
                    partial_info.user_type_id, variant_name
                );
                let namespace_ms = MetaString::get_empty().clone();
                let type_name_ms = TYPE_NAME_ENCODER
                    .encode_with_encodings(&variant_type_name, TYPE_NAME_ENCODINGS)?;
                if type_resolver
                    .get_type_info_by_name(&namespace_ms.original, &type_name_ms.original)
                    .is_some()
                {
                    return Err(Error::type_error(format!(
                        "Enum variant name {} conflicts with already registered type name. \
                         Please use a different type ID for the enum to avoid conflicts.",
                        variant_type_name
                    )));
                }
                TypeMeta::from_fields(
                    TypeId::NAMED_COMPATIBLE_STRUCT as u32,
                    NO_USER_TYPE_ID,
                    namespace_ms,
                    type_name_ms,
                    true,
                    fields_info,
                )?
            };

            let variant_type_info =
                TypeInfo::new_with_type_meta(Rc::new(variant_type_meta), Harness::stub())?;

            // Store the variant type_id with its TypeId
            result.push((variant_type_id, variant_type_info));
        }
    }

    Ok(result)
}

/// Helper function to build type infos for serializer types (ext types)
fn build_serializer_type_infos(
    partial_info: &TypeInfo,
    rust_type_id: std::any::TypeId,
) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error> {
    if !supports_type_def(partial_info.type_id as u32) {
        return Ok(vec![(rust_type_id, partial_info.clone())]);
    }
    // For ext types, we just build the type info with empty fields
    let type_meta = TypeMeta::from_fields(
        partial_info.type_id as u32,
        partial_info.user_type_id,
        (*partial_info.namespace).clone(),
        (*partial_info.type_name).clone(),
        partial_info.register_by_name,
        vec![],
    )?;
    let type_def_bytes = type_meta.get_bytes().to_owned();
    let type_info = TypeInfo {
        type_def: Rc::from(type_def_bytes),
        type_meta: Rc::new(type_meta),
        type_id: partial_info.type_id,
        user_type_id: partial_info.user_type_id,
        namespace: partial_info.namespace.clone(),
        type_name: partial_info.type_name.clone(),
        register_by_name: partial_info.register_by_name,
        exact_local_schema: true,
        harness: partial_info.harness.clone(),
    };

    Ok(vec![(rust_type_id, type_info)])
}

fn build_struct_provider_type_infos<S: StructSerializer>(
    type_resolver: &TypeResolver,
) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error> {
    build_struct_type_infos::<S>(type_resolver)
}

fn build_ext_provider_type_infos<S: Serializer>(
    type_resolver: &TypeResolver,
) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error> {
    let provider_type_id = std::any::TypeId::of::<S>();
    let partial_info = type_resolver
        .partial_type_infos
        .get(&provider_type_id)
        .ok_or_else(|| {
            Error::type_error(format!(
                "partial type info not found for serializer {}",
                std::any::type_name::<S>(),
            ))
        })?;
    build_serializer_type_infos(partial_info, provider_type_id)
}

fn struct_harness<S: StructSerializer>() -> Harness {
    let supports_compatible_read = matches!(
        S::static_type_id(),
        TypeId::STRUCT
            | TypeId::COMPATIBLE_STRUCT
            | TypeId::NAMED_STRUCT
            | TypeId::NAMED_COMPATIBLE_STRUCT
    );
    Harness {
        target_type_id: Some(std::any::TypeId::of::<S::Target>()),
        write_data_fn: write_target_data::<S>,
        read_box_any_fn: read_target_box::<S>,
        read_rc_any_fn: read_target_rc::<S>,
        read_arc_any_fn: read_target_arc::<S>,
        read_compatible_box_any_fn: supports_compatible_read
            .then_some(read_compatible_target_box::<S>),
        read_compatible_rc_any_fn: supports_compatible_read
            .then_some(read_compatible_target_rc::<S>),
        read_compatible_arc_any_fn: supports_compatible_read
            .then_some(read_compatible_target_arc::<S>),
        build_type_infos: build_struct_provider_type_infos::<S>,
    }
}

fn ext_harness<S: Serializer>() -> Harness {
    Harness {
        target_type_id: Some(std::any::TypeId::of::<S::Target>()),
        write_data_fn: write_target_data::<S>,
        read_box_any_fn: read_target_box::<S>,
        read_rc_any_fn: read_target_rc::<S>,
        read_arc_any_fn: read_target_arc::<S>,
        read_compatible_box_any_fn: None,
        read_compatible_rc_any_fn: None,
        read_compatible_arc_any_fn: None,
        build_type_infos: build_ext_provider_type_infos::<S>,
    }
}

#[cold]
#[inline(never)]
fn ensure_struct_category<S: StructSerializer>(union: bool, api: &str) -> Result<(), Error> {
    let type_id = S::static_type_id();
    let valid = if union {
        type_id == TypeId::UNION
    } else {
        type_id == TypeId::STRUCT || type_id == TypeId::ENUM
    };
    if valid {
        return Ok(());
    }
    Err(Error::not_allowed(format!(
        "{} cannot register serializer {} with structural category {:?}",
        api,
        std::any::type_name::<S>(),
        type_id,
    )))
}

#[cold]
#[inline(never)]
fn ensure_ext_category<S: Serializer>() -> Result<(), Error> {
    let type_id = S::static_type_id();
    if (type_id == TypeId::EXT || type_id == TypeId::NAMED_EXT) && !S::IS_WRAPPER {
        return Ok(());
    }
    if S::IS_WRAPPER {
        return Err(Error::not_allowed(format!(
            "register_serializer requires an independent EXT serializer, but {} is a transparent wrapper",
            std::any::type_name::<S>(),
        )));
    }
    Err(Error::not_allowed(format!(
        "register_serializer requires an EXT serializer, but {} declares {:?}",
        std::any::type_name::<S>(),
        type_id,
    )))
}

/// TypeResolver is a resolver for fast type/serializer dispatch.
pub struct TypeResolver {
    internal_type_info_by_id: Vec<Option<Rc<TypeInfo>>>,
    user_type_info_by_id: HashMap<u32, Rc<TypeInfo>>,
    provider_type_info_map: HashMap<std::any::TypeId, Rc<TypeInfo>>,
    target_type_info_map: HashMap<std::any::TypeId, Rc<TypeInfo>>,
    type_info_map_by_name: HashMap<(String, String), Rc<TypeInfo>>,
    type_info_map_by_meta_string_name: HashMap<(Rc<MetaString>, Rc<MetaString>), Rc<TypeInfo>>,
    partial_type_infos: HashMap<std::any::TypeId, TypeInfo>,
    // Fast lookup by numeric ID for common types
    type_id_index: Vec<TypeId>,
    // Fast lookup by type index for user type IDs
    user_type_id_index: Vec<u32>,
    // Mapping from type index to Rust TypeId for fast meta lookup
    rust_type_id_by_index: Vec<Option<std::any::TypeId>>,
    // Fast lookup by type index for TypeMeta
    type_meta_by_index: Vec<Option<Rc<crate::meta::TypeMeta>>>,
    compatible: bool,
    xlang: bool,
}

const NO_TYPE_ID: TypeId = TypeId::UNKNOWN;

impl Default for TypeResolver {
    fn default() -> Self {
        let mut registry = TypeResolver {
            internal_type_info_by_id: vec![None; INTERNAL_TYPE_ID_LIMIT],
            user_type_info_by_id: HashMap::new(),
            provider_type_info_map: HashMap::new(),
            target_type_info_map: HashMap::new(),
            type_info_map_by_name: HashMap::new(),
            type_info_map_by_meta_string_name: HashMap::new(),
            type_id_index: Vec::new(),
            user_type_id_index: Vec::new(),
            rust_type_id_by_index: Vec::new(),
            type_meta_by_index: Vec::new(),
            partial_type_infos: HashMap::new(),
            compatible: false,
            xlang: false,
        };
        registry.register_builtin_types().unwrap();
        registry
    }
}

impl TypeResolver {
    #[inline(always)]
    pub fn get_provider_type_info(
        &self,
        provider_type_id: &std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        // Provider and target identities may be the same Rust TypeId. Never
        // probe the target index from this provider-owned lookup.
        self.provider_type_info_map
            .get(provider_type_id)
            .ok_or_else(|| missing_provider_type_info(provider_type_id))
            .cloned()
    }

    #[inline(always)]
    pub fn get_target_type_info(
        &self,
        target_type_id: &std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        // Dynamic dispatch owns a target identity. Falling back to the provider
        // index could silently select a different serializer for that target.
        self.target_type_info_map
            .get(target_type_id)
            .ok_or_else(|| missing_target_type_info(target_type_id))
            .cloned()
    }

    #[inline(always)]
    pub fn get_type_info_by_id(&self, type_id: u32) -> Option<Rc<TypeInfo>> {
        if crate::type_id::is_internal_type(type_id) {
            let index = type_id as usize;
            if index < self.internal_type_info_by_id.len() {
                return self.internal_type_info_by_id[index].clone();
            }
        }
        None
    }

    #[inline(always)]
    pub fn get_user_type_info_by_id(&self, user_type_id: u32) -> Option<Rc<TypeInfo>> {
        self.user_type_info_by_id.get(&user_type_id).cloned()
    }

    #[inline(always)]
    pub fn get_type_info_by_name(&self, namespace: &str, type_name: &str) -> Option<Rc<TypeInfo>> {
        self.type_info_map_by_name
            .get(&(namespace.to_owned(), type_name.to_owned()))
            .cloned()
    }

    #[inline(always)]
    pub(crate) fn get_type_info_by_meta_string_name(
        &self,
        namespace: Rc<MetaString>,
        type_name: Rc<MetaString>,
    ) -> Option<Rc<TypeInfo>> {
        self.type_info_map_by_meta_string_name
            .get(&(namespace, type_name))
            .cloned()
    }

    /// Fast path for getting type info by numeric ID (avoids HashMap lookup by TypeId)
    #[inline(always)]
    pub fn get_type_id(&self, type_id: &std::any::TypeId, id: u32) -> Result<TypeId, Error> {
        let id_usize = id as usize;
        if id_usize < self.type_id_index.len() {
            let type_id_value = self.type_id_index[id_usize];
            if type_id_value != NO_TYPE_ID {
                return Ok(type_id_value);
            }
        }
        Err(Error::type_error(format!(
            "TypeId {:?} not found in type_id_index, maybe you forgot to register some types",
            type_id
        )))
    }

    /// Fast path for getting type info by type index (avoids HashMap lookup and TypeId::of)
    #[inline(always)]
    pub fn get_type_id_by_index(&self, index: u32) -> Result<TypeId, Error> {
        let id_usize = index as usize;
        if id_usize < self.type_id_index.len() {
            let type_id_value = self.type_id_index[id_usize];
            if type_id_value != NO_TYPE_ID {
                return Ok(type_id_value);
            }
        }
        Err(Error::type_error(format!(
            "Type index {:?} not found in type_id_index, maybe you forgot to register some types",
            index
        )))
    }

    /// Fast path for getting user type ID by type index (avoids HashMap lookup by TypeId)
    #[inline(always)]
    pub fn get_user_type_id_by_index(
        &self,
        type_id: &std::any::TypeId,
        id: u32,
    ) -> Result<u32, Error> {
        let id_usize = id as usize;
        if id_usize < self.user_type_id_index.len() {
            let user_type_id = self.user_type_id_index[id_usize];
            if user_type_id != NO_USER_TYPE_ID {
                return Ok(user_type_id);
            }
        }
        Err(Error::type_error(format!(
            "TypeId {:?} not found in user_type_id_index, maybe you forgot to register some types",
            type_id
        )))
    }

    /// Fast path for getting TypeMeta by type index (avoids HashMap lookup by TypeId)
    #[inline(always)]
    pub fn get_type_meta_by_index(
        &self,
        type_id: &std::any::TypeId,
        index: u32,
    ) -> Result<Rc<crate::meta::TypeMeta>, Error> {
        let id_usize = index as usize;
        if id_usize < self.type_meta_by_index.len() {
            if let Some(meta) = &self.type_meta_by_index[id_usize] {
                return Ok(meta.clone());
            }
        }
        Err(Error::type_error(format!(
            "TypeId {:?} not found in type_meta_by_index, maybe you forgot to register some types",
            type_id
        )))
    }

    /// Fast path for getting TypeMeta by type index without cloning Rc.
    #[inline(always)]
    pub fn get_type_meta_by_index_ref(
        &self,
        type_id: &std::any::TypeId,
        index: u32,
    ) -> Result<&crate::meta::TypeMeta, Error> {
        let id_usize = index as usize;
        if id_usize < self.type_meta_by_index.len() {
            if let Some(meta) = &self.type_meta_by_index[id_usize] {
                return Ok(meta.as_ref());
            }
        }
        Err(Error::type_error(format!(
            "TypeId {:?} not found in type_meta_by_index, maybe you forgot to register some types",
            type_id
        )))
    }

    fn register_builtin_types(&mut self) -> Result<(), Error> {
        self.register_internal_serializer::<bool>(TypeId::BOOL)?;
        self.register_internal_serializer::<i8>(TypeId::INT8)?;
        self.register_internal_serializer::<i16>(TypeId::INT16)?;
        self.register_internal_serializer::<i32>(TypeId::VARINT32)?;
        self.register_internal_serializer::<i64>(TypeId::VARINT64)?;
        self.register_internal_serializer::<isize>(TypeId::ISIZE)?;
        self.register_internal_serializer::<i128>(TypeId::INT128)?;
        self.register_internal_serializer::<f32>(TypeId::FLOAT32)?;
        self.register_internal_serializer::<f64>(TypeId::FLOAT64)?;
        self.register_internal_serializer::<crate::types::float16::float16>(TypeId::FLOAT16)?;
        self.register_internal_serializer::<crate::types::bfloat16::bfloat16>(TypeId::BFLOAT16)?;
        self.register_internal_serializer::<u8>(TypeId::UINT8)?;
        self.register_internal_serializer::<u16>(TypeId::UINT16)?;
        self.register_internal_serializer::<u32>(TypeId::VAR_UINT32)?;
        self.register_internal_serializer::<u64>(TypeId::VAR_UINT64)?;
        self.register_internal_serializer::<usize>(TypeId::USIZE)?;
        self.register_internal_serializer::<u128>(TypeId::U128)?;
        self.register_internal_serializer::<String>(TypeId::STRING)?;
        #[cfg(feature = "chrono")]
        {
            self.register_internal_serializer::<ChronoDuration>(TypeId::DURATION)?;
            self.register_internal_serializer::<NaiveDateTime>(TypeId::TIMESTAMP)?;
            self.register_internal_serializer::<NaiveDate>(TypeId::DATE)?;
        }
        self.register_internal_serializer::<Duration>(TypeId::DURATION)?;
        self.register_internal_serializer::<Timestamp>(TypeId::TIMESTAMP)?;
        self.register_internal_serializer::<Date>(TypeId::DATE)?;
        self.register_internal_serializer::<crate::types::Decimal>(TypeId::DECIMAL)?;

        self.register_internal_serializer::<Vec<bool>>(TypeId::BOOL_ARRAY)?;
        self.register_internal_serializer::<Vec<i8>>(TypeId::INT8_ARRAY)?;
        self.register_internal_serializer::<Vec<i16>>(TypeId::INT16_ARRAY)?;
        self.register_internal_serializer::<Vec<i32>>(TypeId::INT32_ARRAY)?;
        self.register_internal_serializer::<Vec<i64>>(TypeId::INT64_ARRAY)?;
        self.register_internal_serializer::<Vec<f32>>(TypeId::FLOAT32_ARRAY)?;
        self.register_internal_serializer::<Vec<f64>>(TypeId::FLOAT64_ARRAY)?;
        self.register_internal_serializer::<Vec<crate::types::float16::float16>>(
            TypeId::FLOAT16_ARRAY,
        )?;
        self.register_internal_serializer::<Vec<crate::types::bfloat16::bfloat16>>(
            TypeId::BFLOAT16_ARRAY,
        )?;
        self.register_internal_serializer::<Vec<u8>>(TypeId::BINARY)?;
        self.register_internal_serializer::<Vec<u16>>(TypeId::UINT16_ARRAY)?;
        self.register_internal_serializer::<Vec<u32>>(TypeId::UINT32_ARRAY)?;
        self.register_internal_serializer::<Vec<u64>>(TypeId::UINT64_ARRAY)?;
        self.register_internal_serializer::<Vec<usize>>(TypeId::USIZE_ARRAY)?;
        self.register_internal_serializer::<Vec<u128>>(TypeId::U128_ARRAY)?;
        self.register_internal_serializer::<Vec<isize>>(TypeId::ISIZE_ARRAY)?;
        self.register_internal_serializer::<Vec<i128>>(TypeId::INT128_ARRAY)?;

        Ok(())
    }

    pub fn register<S: StructSerializer>(&mut self, id: u32) -> Result<(), Error> {
        ensure_struct_category::<S>(false, "register")?;
        self.register_struct_type::<S>(id, &EMPTY_STRING, &EMPTY_STRING)
    }

    pub fn register_union<S: StructSerializer>(&mut self, id: u32) -> Result<(), Error> {
        ensure_struct_category::<S>(true, "register_union")?;
        self.register_struct_type::<S>(id, &EMPTY_STRING, &EMPTY_STRING)
    }

    pub fn register_by_name<S: StructSerializer>(&mut self, name: &str) -> Result<(), Error> {
        ensure_struct_category::<S>(false, "register_by_name")?;
        let (namespace, type_name) = split_named_registration(name, "register_by_name")?;
        self.register_struct_type::<S>(0, namespace, type_name)
    }

    pub fn register_union_by_name<S: StructSerializer>(&mut self, name: &str) -> Result<(), Error> {
        ensure_struct_category::<S>(true, "register_union_by_name")?;
        let (namespace, type_name) = split_named_registration(name, "register_union_by_name")?;
        self.register_struct_type::<S>(0, namespace, type_name)
    }

    #[cold]
    #[inline(never)]
    fn register_struct_type<S: StructSerializer>(
        &mut self,
        id: u32,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        let register_by_name = !type_name.is_empty();
        let actual_type_id = S::actual_type_id(id, register_by_name, self.compatible, self.xlang)?;
        let user_type_id = if register_by_name || crate::type_id::is_internal_type(actual_type_id) {
            NO_USER_TYPE_ID
        } else {
            id
        };
        let type_info = TypeInfo::new(
            actual_type_id,
            user_type_id,
            namespace,
            type_name,
            register_by_name,
            struct_harness::<S>(),
        )?;
        self.validate_registration::<S>(&type_info, id)?;

        let type_index = S::type_index() as usize;
        if type_index < self.type_id_index.len() && self.type_id_index[type_index] != NO_TYPE_ID {
            return Err(Error::type_error(format!(
                "type index {} is already registered",
                type_index
            )));
        }

        self.publish_registration::<S>(type_info);
        if type_index >= self.type_id_index.len() {
            self.type_id_index.resize(type_index + 1, NO_TYPE_ID);
            self.user_type_id_index
                .resize(type_index + 1, NO_USER_TYPE_ID);
            self.rust_type_id_by_index.resize(type_index + 1, None);
        }
        let provider_type_id = std::any::TypeId::of::<S>();
        let partial = self
            .partial_type_infos
            .get(&provider_type_id)
            .expect("published structural serializer");
        self.type_id_index[type_index] = partial.type_id;
        self.user_type_id_index[type_index] = partial.user_type_id;
        self.rust_type_id_by_index[type_index] = Some(provider_type_id);
        Ok(())
    }

    pub fn register_serializer<S: Serializer>(&mut self, id: u32) -> Result<(), Error> {
        ensure_ext_category::<S>()?;
        self.register_ext_type::<S>(
            id,
            get_ext_actual_type_id(id, false),
            &EMPTY_STRING,
            &EMPTY_STRING,
        )
    }

    pub fn register_serializer_by_name<S: Serializer>(&mut self, name: &str) -> Result<(), Error> {
        ensure_ext_category::<S>()?;
        let (namespace, type_name) = split_named_registration(name, "register_serializer_by_name")?;
        self.register_ext_type::<S>(0, get_ext_actual_type_id(0, true), namespace, type_name)
    }

    #[cold]
    #[inline(never)]
    fn register_internal_serializer<S: Serializer>(
        &mut self,
        type_id: TypeId,
    ) -> Result<(), Error> {
        if S::static_type_id() != type_id {
            return Err(Error::not_allowed(format!(
                "Fory serializer {} declares {:?}, not {:?}",
                std::any::type_name::<S>(),
                S::static_type_id(),
                type_id,
            )));
        }
        let raw_id = type_id as u32;
        if raw_id >= INTERNAL_TYPE_ID_LIMIT as u32 {
            return Err(Error::not_allowed(format!(
                "internal type id overflow: {}",
                raw_id
            )));
        }
        self.register_ext_type::<S>(raw_id, raw_id, &EMPTY_STRING, &EMPTY_STRING)
    }

    #[cold]
    #[inline(never)]
    fn register_ext_type<S: Serializer>(
        &mut self,
        id: u32,
        actual_type_id: u32,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        let register_by_name = !type_name.is_empty();
        let user_type_id = if register_by_name {
            NO_USER_TYPE_ID
        } else {
            id
        };
        let type_info = TypeInfo::new(
            actual_type_id,
            user_type_id,
            namespace,
            type_name,
            register_by_name,
            ext_harness::<S>(),
        )?;
        self.validate_registration::<S>(&type_info, id)?;
        self.publish_registration::<S>(type_info);
        Ok(())
    }

    #[cold]
    #[inline(never)]
    fn validate_registration<S: Serializer>(
        &self,
        type_info: &TypeInfo,
        requested_id: u32,
    ) -> Result<(), Error> {
        let provider_type_id = std::any::TypeId::of::<S>();
        if self.provider_type_info_map.contains_key(&provider_type_id) {
            return Err(Error::type_error(format!(
                "serializer {} is already registered",
                std::any::type_name::<S>(),
            )));
        }
        let target_type_id = std::any::TypeId::of::<S::Target>();
        if self.target_type_info_map.contains_key(&target_type_id) {
            return Err(Error::type_error(format!(
                "target {} already has a registered serializer",
                std::any::type_name::<S::Target>(),
            )));
        }
        if type_info.register_by_name {
            let key = (
                type_info.namespace.original.clone(),
                type_info.type_name.original.clone(),
            );
            if self.type_info_map_by_name.contains_key(&key)
                || self.partial_type_infos.values().any(|registered| {
                    registered.register_by_name
                        && registered.namespace.original == key.0
                        && registered.type_name.original == key.1
                })
            {
                return Err(Error::type_error(format!(
                    "type name {}::{} conflicts with an existing registration",
                    key.0, key.1
                )));
            }
        } else if !crate::type_id::is_internal_type(type_info.type_id as u32) {
            if requested_id > MAX_USER_TYPE_ID {
                return Err(Error::not_allowed(format!(
                    "type id must be in range [0, 0xfffffffe], got {}",
                    requested_id
                )));
            }
            if self
                .user_type_info_by_id
                .contains_key(&type_info.user_type_id)
            {
                return Err(Error::type_error(format!(
                    "user type id {} conflicts with an existing registration",
                    type_info.user_type_id
                )));
            }
        }
        Ok(())
    }

    fn publish_registration<S: Serializer>(&mut self, type_info: TypeInfo) {
        let provider_type_id = std::any::TypeId::of::<S>();
        let target_type_id = std::any::TypeId::of::<S::Target>();
        // Every lookup direction for one provider must observe this exact
        // TypeInfo owner so schema, harness, and streaming-meta identity agree.
        let shared = Rc::new(type_info.clone());

        self.provider_type_info_map
            .insert(provider_type_id, shared.clone());
        self.target_type_info_map
            .insert(target_type_id, shared.clone());
        if crate::type_id::is_internal_type(type_info.type_id as u32) {
            self.internal_type_info_by_id[type_info.type_id as usize] = Some(shared.clone());
        } else if type_info.user_type_id != NO_USER_TYPE_ID {
            self.user_type_info_by_id
                .insert(type_info.user_type_id, shared.clone());
        }
        if type_info.register_by_name {
            let string_key = (
                type_info.namespace.original.clone(),
                type_info.type_name.original.clone(),
            );
            let meta_key = (type_info.namespace.clone(), type_info.type_name.clone());
            self.type_info_map_by_name
                .insert(string_key, shared.clone());
            self.type_info_map_by_meta_string_name
                .insert(meta_key, shared);
        }
        self.partial_type_infos.insert(provider_type_id, type_info);
    }

    pub(crate) fn set_compatible(&mut self, compatible: bool) {
        self.compatible = compatible;
    }

    pub(crate) fn set_xlang(&mut self, xlang: bool) {
        self.xlang = xlang;
    }

    pub fn is_xlang(&self) -> bool {
        self.xlang
    }

    /// Builds the final TypeResolver by completing all partial type infos created during registration.
    ///
    /// This method processes all types that were registered with lazy initialization enabled.
    /// During registration, types are stored in `partial_type_infos` without their complete
    /// type metadata to avoid circular dependencies. This method:
    ///
    /// 1. Iterates through all partial type infos
    /// 2. Calls their `sorted_field_infos` function to get complete field information
    /// 3. Builds complete TypeMeta and serializes it to bytes
    /// 4. Inserts completed type infos into all lookup maps
    ///
    /// # Returns
    ///
    /// A new TypeResolver with all type infos fully initialized and ready for use.
    ///
    /// # Errors
    ///
    /// Returns an error if any type info fails to complete, such as when field info
    /// cannot be retrieved or type metadata cannot be serialized.
    #[cold]
    #[inline(never)]
    pub(crate) fn build_final_type_resolver(&self) -> Result<TypeResolver, Error> {
        let mut provider_type_info_map = HashMap::new();
        let mut target_type_info_map = HashMap::new();
        let mut type_info_map_by_name = HashMap::new();
        let mut type_info_map_by_meta_string_name = HashMap::new();
        let type_id_index = self.type_id_index.clone();
        let rust_type_id_by_index = self.rust_type_id_by_index.clone();
        let user_type_id_index = self.user_type_id_index.clone();

        for partial_type_info in self.partial_type_infos.values() {
            let harness = &partial_type_info.harness;
            let type_infos = (harness.build_type_infos)(self)?;
            for (provider_type_id, type_info) in type_infos {
                // Synthetic variant metadata has a stub harness and therefore
                // remains provider-only; registered provider metadata enters
                // every applicable map through this single Rc.
                let shared = Rc::new(type_info);
                if provider_type_info_map
                    .insert(provider_type_id, shared.clone())
                    .is_some()
                {
                    return Err(Error::type_error(format!(
                        "serializer TypeId {:?} conflicts while finalizing metadata",
                        provider_type_id
                    )));
                }
                if let Some(target_type_id) = shared.harness.target_type_id() {
                    if target_type_info_map
                        .insert(target_type_id, shared.clone())
                        .is_some()
                    {
                        return Err(Error::type_error(format!(
                            "target TypeId {:?} conflicts while finalizing metadata",
                            target_type_id
                        )));
                    }
                }
                if shared.register_by_name {
                    let namespace = &shared.namespace;
                    let type_name = &shared.type_name;
                    let ms_key = (namespace.clone(), type_name.clone());
                    if type_info_map_by_meta_string_name
                        .insert(ms_key, shared.clone())
                        .is_some()
                    {
                        return Err(Error::type_error(format!(
                            "Type name {}::{} conflicts with already registered type",
                            namespace.original, type_name.original
                        )));
                    }
                    let string_key = (namespace.original.clone(), type_name.original.clone());
                    type_info_map_by_name.insert(string_key, shared);
                }
            }
        }

        let internal_type_info_by_id = self
            .internal_type_info_by_id
            .iter()
            .map(|entry| {
                entry.as_ref().and_then(|partial| {
                    partial
                        .harness
                        .target_type_id()
                        .and_then(|target| target_type_info_map.get(&target).cloned())
                })
            })
            .collect();
        let mut user_type_info_by_id = HashMap::new();
        for type_info in provider_type_info_map.values() {
            if !crate::type_id::is_internal_type(type_info.type_id as u32)
                && type_info.user_type_id != NO_USER_TYPE_ID
            {
                user_type_info_by_id.insert(type_info.user_type_id, type_info.clone());
            }
        }
        let type_meta_by_index: Vec<Option<Rc<crate::meta::TypeMeta>>> = rust_type_id_by_index
            .iter()
            .map(|id| {
                id.and_then(|provider_type_id| {
                    provider_type_info_map
                        .get(&provider_type_id)
                        .map(|info| info.get_type_meta())
                })
            })
            .collect();

        Ok(TypeResolver {
            internal_type_info_by_id,
            user_type_info_by_id,
            provider_type_info_map,
            target_type_info_map,
            type_info_map_by_name,
            type_info_map_by_meta_string_name,
            partial_type_infos: HashMap::new(),
            type_id_index,
            user_type_id_index,
            rust_type_id_by_index,
            type_meta_by_index,
            compatible: self.compatible,
            xlang: self.xlang,
        })
    }

    /// Clones the TypeResolver including any partial type infos.
    ///
    /// **WARNING**: This method is restricted to `pub(crate)` visibility because it clones
    /// the TypeResolver in its current state, which may include incomplete `partial_type_infos`.
    ///
    /// # Important
    ///
    /// External code should **not** use this method directly. Instead, use
    /// [`build_final_type_resolver`](Self::build_final_type_resolver) to obtain a complete
    /// TypeResolver with all type infos fully initialized.
    ///
    /// This method is only used internally for a type resolver without partial type infos:
    ///
    /// # Returns
    ///
    /// A deep clone of the TypeResolver with all internal Rc instances recreated.
    /// This ensures thread safety when cloning from multiple threads simultaneously.
    ///
    /// # See Also
    ///
    /// - [`build_final_type_resolver`](Self::build_final_type_resolver) - Builds a complete resolver
    pub(crate) fn clone(&self) -> TypeResolver {
        // Build a mapping from old Rc<TypeInfo> pointers to new Rc<TypeInfo>
        // to ensure we reuse the same new Rc for the same original TypeInfo
        let mut type_info_mapping: HashMap<*const TypeInfo, Rc<TypeInfo>> = HashMap::new();

        // Helper closure to get or create deep-cloned TypeInfo wrapped in new Rc
        let mut get_or_clone_type_info = |rc: &Rc<TypeInfo>| -> Rc<TypeInfo> {
            let ptr = Rc::as_ptr(rc);
            if let Some(new_rc) = type_info_mapping.get(&ptr) {
                new_rc.clone()
            } else {
                let new_rc = Rc::new(rc.deep_clone());
                type_info_mapping.insert(ptr, new_rc.clone());
                new_rc
            }
        };

        // Clone all maps with deep-cloned TypeInfo in new Rc wrappers
        let internal_type_info_by_id: Vec<Option<Rc<TypeInfo>>> = self
            .internal_type_info_by_id
            .iter()
            .map(|opt| opt.as_ref().map(&mut get_or_clone_type_info))
            .collect();

        let user_type_info_by_id: HashMap<u32, Rc<TypeInfo>> = self
            .user_type_info_by_id
            .iter()
            .map(|(k, v)| (*k, get_or_clone_type_info(v)))
            .collect();

        let provider_type_info_map: HashMap<std::any::TypeId, Rc<TypeInfo>> = self
            .provider_type_info_map
            .iter()
            .map(|(k, v)| (*k, get_or_clone_type_info(v)))
            .collect();

        let target_type_info_map: HashMap<std::any::TypeId, Rc<TypeInfo>> = self
            .target_type_info_map
            .iter()
            .map(|(k, v)| (*k, get_or_clone_type_info(v)))
            .collect();

        let type_info_map_by_name: HashMap<(String, String), Rc<TypeInfo>> = self
            .type_info_map_by_name
            .iter()
            .map(|(k, v)| (k.clone(), get_or_clone_type_info(v)))
            .collect();

        // Deep clone the MetaString keys as well
        let type_info_map_by_meta_string_name: HashMap<
            (Rc<MetaString>, Rc<MetaString>),
            Rc<TypeInfo>,
        > = self
            .type_info_map_by_meta_string_name
            .iter()
            .map(|(k, v)| {
                let new_key = (Rc::new((*k.0).clone()), Rc::new((*k.1).clone()));
                (new_key, get_or_clone_type_info(v))
            })
            .collect();

        // Deep clone the TypeMeta as well
        let type_meta_by_index: Vec<Option<Rc<TypeMeta>>> = self
            .type_meta_by_index
            .iter()
            .map(|opt| opt.as_ref().map(|meta| Rc::new(meta.deep_clone())))
            .collect();

        TypeResolver {
            internal_type_info_by_id,
            user_type_info_by_id,
            provider_type_info_map,
            target_type_info_map,
            type_info_map_by_name,
            type_info_map_by_meta_string_name,
            partial_type_infos: HashMap::new(),
            type_id_index: self.type_id_index.clone(),
            user_type_id_index: self.user_type_id_index.clone(),
            rust_type_id_by_index: self.rust_type_id_by_index.clone(),
            type_meta_by_index,
            compatible: self.compatible,
            xlang: self.xlang,
        }
    }
}
