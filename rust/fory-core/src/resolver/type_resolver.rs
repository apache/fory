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

use super::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::meta::{
    MetaString, TypeMeta, NAMESPACE_ENCODER, NAMESPACE_ENCODINGS, TYPE_NAME_ENCODER,
    TYPE_NAME_ENCODINGS,
};
use crate::serializer::{ForyDefault, Serializer, StructSerializer};
use crate::types::{get_ext_actual_type_id, is_enum_type_id, RefMode};
use crate::TypeId;
use chrono::{NaiveDate, NaiveDateTime};
use std::collections::{HashSet, LinkedList};
use std::rc::Rc;
use std::vec;

use std::{any::Any, collections::HashMap};

type WriteFn = fn(
    &dyn Any,
    &mut WriteContext,
    ref_mode: RefMode,
    write_type_info: bool,
    has_enerics: bool,
) -> Result<(), Error>;
type ReadFn =
    fn(&mut ReadContext, ref_mode: RefMode, read_type_info: bool) -> Result<Box<dyn Any>, Error>;

type WriteDataFn = fn(&dyn Any, &mut WriteContext, has_generics: bool) -> Result<(), Error>;
type ReadDataFn = fn(&mut ReadContext) -> Result<Box<dyn Any>, Error>;
type ReadCompatibleFn = fn(&mut ReadContext, Rc<TypeInfo>) -> Result<Box<dyn Any>, Error>;
type ToSerializerFn = fn(Box<dyn Any>) -> Result<Box<dyn Serializer>, Error>;
type BuildTypeInfosFn = fn(&TypeResolver) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error>;
const EMPTY_STRING: String = String::new();
const INTERNAL_TYPE_ID_LIMIT: usize = 256;
const MAX_USER_TYPE_ID: u32 = 0xfffffffe;
pub(crate) const NO_USER_TYPE_ID: u32 = u32::MAX;

#[derive(Clone, Debug)]
pub struct Harness {
    write_fn: WriteFn,
    read_fn: ReadFn,
    write_data_fn: WriteDataFn,
    read_data_fn: ReadDataFn,
    read_compatible_fn: Option<ReadCompatibleFn>,
    to_serializer: ToSerializerFn,
    build_type_infos: BuildTypeInfosFn,
}

impl Harness {
    pub fn new(
        write_fn: WriteFn,
        read_fn: ReadFn,
        write_data_fn: WriteDataFn,
        read_data_fn: ReadDataFn,
        read_compatible_fn: Option<ReadCompatibleFn>,
        to_serializer: ToSerializerFn,
        build_type_infos: BuildTypeInfosFn,
    ) -> Harness {
        Harness {
            write_fn,
            read_fn,
            write_data_fn,
            read_data_fn,
            read_compatible_fn,
            to_serializer,
            build_type_infos,
        }
    }

    pub fn stub() -> Harness {
        Harness::new(
            stub_write_fn,
            stub_read_fn,
            stub_write_data_fn,
            stub_read_data_fn,
            None,
            stub_to_serializer_fn,
            stub_build_type_infos,
        )
    }

    #[inline(always)]
    pub fn get_write_fn(&self) -> WriteFn {
        self.write_fn
    }

    #[inline(always)]
    pub fn get_read_fn(&self) -> ReadFn {
        self.read_fn
    }

    #[inline(always)]
    pub fn get_write_data_fn(&self) -> WriteDataFn {
        self.write_data_fn
    }

    #[inline(always)]
    pub fn get_read_data_fn(&self) -> ReadDataFn {
        self.read_data_fn
    }

    #[inline(always)]
    pub fn get_read_compatible_fn(&self) -> Option<ReadCompatibleFn> {
        self.read_compatible_fn
    }

    #[inline(always)]
    pub fn get_to_serializer(&self) -> ToSerializerFn {
        self.to_serializer
    }

    /// Reads polymorphic data using the appropriate function based on mode.
    /// In compatible mode, uses read_compatible_fn if available to handle schema
    /// evolution. Otherwise, uses read_data_fn for direct deserialization.
    #[inline(always)]
    pub fn read_polymorphic_data(
        &self,
        context: &mut ReadContext,
        typeinfo: &Rc<TypeInfo>,
    ) -> Result<Box<dyn Any>, Error> {
        if context.is_compatible() {
            if let Some(read_compatible_fn) = self.read_compatible_fn {
                // Only clone when actually needed for compatible mode
                return read_compatible_fn(context, typeinfo.clone());
            }
        }
        (self.read_data_fn)(context)
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
            // Create a stub harness that returns errors when called
            Harness::new(
                stub_write_fn,
                stub_read_fn,
                stub_write_data_fn,
                stub_read_data_fn,
                None,
                stub_to_serializer_fn,
                stub_build_type_infos,
            )
        };

        TypeInfo {
            type_def: Rc::from(type_def_bytes),
            type_meta: remote_meta,
            type_id,
            user_type_id,
            namespace,
            type_name,
            register_by_name,
            harness,
        }
    }
}

// Stub functions for when a type doesn't exist locally
fn stub_write_fn(
    _: &dyn Any,
    _: &mut WriteContext,
    _: RefMode,
    _: bool,
    _: bool,
) -> Result<(), Error> {
    Err(Error::type_error(
        "Cannot serialize unknown remote type - type not registered locally",
    ))
}

fn stub_read_fn(_: &mut ReadContext, _: RefMode, _: bool) -> Result<Box<dyn Any>, Error> {
    Err(Error::type_error(
        "Cannot deserialize unknown remote type - type not registered locally",
    ))
}

fn stub_write_data_fn(_: &dyn Any, _: &mut WriteContext, _: bool) -> Result<(), Error> {
    Err(Error::type_error(
        "Cannot serialize unknown remote type - type not registered locally",
    ))
}

fn stub_read_data_fn(_: &mut ReadContext) -> Result<Box<dyn Any>, Error> {
    Err(Error::type_error(
        "Cannot deserialize unknown remote type - type not registered locally",
    ))
}

fn stub_to_serializer_fn(_: Box<dyn Any>) -> Result<Box<dyn Serializer>, Error> {
    Err(Error::type_error(
        "Cannot convert unknown remote type to serializer",
    ))
}

fn stub_build_type_infos(_: &TypeResolver) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error> {
    Err(Error::type_error(
        "Cannot get type infos for unknown remote type",
    ))
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
    let sorted_field_infos = T::fory_fields_info(type_resolver)?;

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
        harness: partial_info.harness.clone(),
    };

    let mut result = vec![(std::any::TypeId::of::<T>(), main_type_info)];

    // Handle enum variants in compatible mode
    // Check for ENUM, NAMED_ENUM, and UNION (Union-compatible Rust enums return UNION TypeId)
    if type_resolver.compatible && is_enum_type_id(T::fory_static_type_id()) {
        // Fields are already sorted with IDs assigned by the macro
        let variants_info = T::fory_variants_fields_info(type_resolver)?;
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
                    TypeId::ENUM as u32,
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
                    TypeId::ENUM as u32,
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
        harness: partial_info.harness.clone(),
    };

    Ok(vec![(rust_type_id, type_info)])
}

/// TypeResolver is a resolver for fast type/serializer dispatch.
pub struct TypeResolver {
    internal_type_info_by_id: Vec<Option<Rc<TypeInfo>>>,
    user_type_info_by_id: HashMap<u32, Rc<TypeInfo>>,
    type_info_map: HashMap<std::any::TypeId, Rc<TypeInfo>>,
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

// Safety: TypeResolver instances are only shared through higher-level synchronization that
// guarantees thread confinement for mutations, so marking them Send/Sync preserves existing
// invariants despite internal Rc usage.
unsafe impl Send for TypeResolver {}
unsafe impl Sync for TypeResolver {}

const NO_TYPE_ID: TypeId = TypeId::UNKNOWN;

impl Default for TypeResolver {
    fn default() -> Self {
        let mut registry = TypeResolver {
            internal_type_info_by_id: vec![None; INTERNAL_TYPE_ID_LIMIT],
            user_type_info_by_id: HashMap::new(),
            type_info_map: HashMap::new(),
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
    pub fn get_type_info(&self, type_id: &std::any::TypeId) -> Result<Rc<TypeInfo>, Error> {
        self.type_info_map
            .get(type_id)
            .ok_or_else(|| {
                Error::type_error(format!(
                    "TypeId {:?} not found in type_info registry, maybe you forgot to register some types",
                    type_id
                ))
            })
            .cloned()
    }

    #[inline(always)]
    pub fn get_type_info_by_id(&self, type_id: u32) -> Option<Rc<TypeInfo>> {
        if crate::types::is_internal_type(type_id) {
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

    #[inline(always)]
    pub fn get_harness(&self, id: u32) -> Option<Rc<Harness>> {
        self.get_type_info_by_id(id)
            .map(|info| Rc::new(info.get_harness().clone()))
    }

    #[inline(always)]
    pub fn get_name_harness(
        &self,
        namespace: Rc<MetaString>,
        type_name: Rc<MetaString>,
    ) -> Option<Rc<Harness>> {
        let key = (namespace, type_name);
        self.type_info_map_by_meta_string_name
            .get(&key)
            .map(|info| Rc::new(info.get_harness().clone()))
    }

    #[inline(always)]
    pub fn get_ext_harness(&self, _type_id: u32, user_type_id: u32) -> Result<Rc<Harness>, Error> {
        self.get_user_type_info_by_id(user_type_id)
            .map(|info| Rc::new(info.get_harness().clone()))
            .ok_or_else(|| Error::type_error("ext type must be registered in both peers"))
    }

    #[inline(always)]
    pub fn get_ext_name_harness(
        &self,
        namespace: Rc<MetaString>,
        type_name: Rc<MetaString>,
    ) -> Result<Rc<Harness>, Error> {
        let key = (namespace, type_name);
        self.type_info_map_by_meta_string_name
            .get(&key)
            .map(|info| Rc::new(info.get_harness().clone()))
            .ok_or_else(|| Error::type_error("named_ext type must be registered in both peers"))
    }

    #[inline(always)]
    pub fn get_fory_type_id(&self, rust_type_id: std::any::TypeId) -> Option<TypeId> {
        self.type_info_map
            .get(&rust_type_id)
            .map(|info| info.get_type_id())
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
        self.register_internal_serializer::<crate::float16::float16>(TypeId::FLOAT16)?;
        self.register_internal_serializer::<u8>(TypeId::UINT8)?;
        self.register_internal_serializer::<u16>(TypeId::UINT16)?;
        self.register_internal_serializer::<u32>(TypeId::VAR_UINT32)?;
        self.register_internal_serializer::<u64>(TypeId::VAR_UINT64)?;
        self.register_internal_serializer::<usize>(TypeId::USIZE)?;
        self.register_internal_serializer::<u128>(TypeId::U128)?;
        self.register_internal_serializer::<String>(TypeId::STRING)?;
        self.register_internal_serializer::<NaiveDateTime>(TypeId::TIMESTAMP)?;
        self.register_internal_serializer::<NaiveDate>(TypeId::DATE)?;

        self.register_internal_serializer::<Vec<bool>>(TypeId::BOOL_ARRAY)?;
        self.register_internal_serializer::<Vec<i8>>(TypeId::INT8_ARRAY)?;
        self.register_internal_serializer::<Vec<i16>>(TypeId::INT16_ARRAY)?;
        self.register_internal_serializer::<Vec<i32>>(TypeId::INT32_ARRAY)?;
        self.register_internal_serializer::<Vec<i64>>(TypeId::INT64_ARRAY)?;
        self.register_internal_serializer::<Vec<f32>>(TypeId::FLOAT32_ARRAY)?;
        self.register_internal_serializer::<Vec<f64>>(TypeId::FLOAT64_ARRAY)?;
        self.register_internal_serializer::<Vec<crate::float16::float16>>(TypeId::FLOAT16_ARRAY)?;
        self.register_internal_serializer::<Vec<u8>>(TypeId::BINARY)?;
        self.register_internal_serializer::<Vec<u16>>(TypeId::UINT16_ARRAY)?;
        self.register_internal_serializer::<Vec<u32>>(TypeId::UINT32_ARRAY)?;
        self.register_internal_serializer::<Vec<u64>>(TypeId::UINT64_ARRAY)?;
        self.register_internal_serializer::<Vec<usize>>(TypeId::USIZE_ARRAY)?;
        self.register_internal_serializer::<Vec<u128>>(TypeId::U128_ARRAY)?;
        self.register_internal_serializer::<Vec<isize>>(TypeId::ISIZE_ARRAY)?;
        self.register_internal_serializer::<Vec<i128>>(TypeId::INT128_ARRAY)?;
        self.register_generic_trait::<Vec<String>>()?;
        self.register_generic_trait::<LinkedList<i32>>()?;
        self.register_generic_trait::<LinkedList<String>>()?;
        self.register_generic_trait::<HashSet<String>>()?;
        self.register_generic_trait::<HashSet<i32>>()?;
        self.register_generic_trait::<HashSet<i64>>()?;
        self.register_generic_trait::<HashMap<String, String>>()?;
        self.register_generic_trait::<HashMap<String, i32>>()?;
        self.register_generic_trait::<HashMap<String, i64>>()?;

        Ok(())
    }

    pub fn register_by_id<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        id: u32,
    ) -> Result<(), Error> {
        self.register::<T>(id, &EMPTY_STRING, &EMPTY_STRING, true)
    }

    pub fn register_union_by_id<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        id: u32,
    ) -> Result<(), Error> {
        if T::fory_static_type_id() != TypeId::UNION {
            return Err(Error::not_allowed(
                "register_union_by_id requires a union-compatible enum type",
            ));
        }
        self.register::<T>(id, &EMPTY_STRING, &EMPTY_STRING, true)
    }

    pub fn register_by_namespace<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        self.register::<T>(0, namespace, type_name, true)
    }

    pub fn register_union_by_namespace<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        if T::fory_static_type_id() != TypeId::UNION {
            return Err(Error::not_allowed(
                "register_union_by_namespace requires a union-compatible enum type",
            ));
        }
        self.register::<T>(0, namespace, type_name, true)
    }

    fn register<T: StructSerializer + Serializer + ForyDefault>(
        &mut self,
        id: u32,
        namespace: &str,
        type_name: &str,
        _lazy: bool,
    ) -> Result<(), Error> {
        let register_by_name = !type_name.is_empty();
        if !register_by_name && id > MAX_USER_TYPE_ID {
            return Err(Error::not_allowed(format!(
                "type id must be in range [0, 0xfffffffe], got {}",
                id
            )));
        }
        let actual_type_id =
            T::fory_actual_type_id(id, register_by_name, self.compatible, self.xlang);
        let user_type_id = if register_by_name || crate::types::is_internal_type(actual_type_id) {
            NO_USER_TYPE_ID
        } else {
            id
        };

        fn write<T2: 'static + Serializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            ref_mode: RefMode,
            write_type_info: bool,
            has_generics: bool,
        ) -> Result<(), Error> {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => T2::fory_write(v, context, ref_mode, write_type_info, has_generics),
                None => Err(Error::type_error(format!(
                    "Cast type to {:?} error when writing: {:?}",
                    std::any::type_name::<T2>(),
                    T2::fory_static_type_id()
                ))),
            }
        }

        fn read<T2: 'static + Serializer + ForyDefault>(
            context: &mut ReadContext,
            ref_mode: RefMode,
            read_type_info: bool,
        ) -> Result<Box<dyn Any>, Error> {
            Ok(Box::new(T2::fory_read(context, ref_mode, read_type_info)?))
        }

        fn write_data<T2: 'static + Serializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            has_generics: bool,
        ) -> Result<(), Error> {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => T2::fory_write_data_generic(v, context, has_generics),
                None => Err(Error::type_error(format!(
                    "Cast type to {:?} error when writing data: {:?}",
                    std::any::type_name::<T2>(),
                    T2::fory_static_type_id()
                ))),
            }
        }

        fn read_data<T2: 'static + Serializer + ForyDefault>(
            context: &mut ReadContext,
        ) -> Result<Box<dyn Any>, Error> {
            match T2::fory_read_data(context) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
            }
        }

        fn to_serializer<T2: 'static + Serializer>(
            boxed_any: Box<dyn Any>,
        ) -> Result<Box<dyn Serializer>, Error> {
            match boxed_any.downcast::<T2>() {
                Ok(concrete) => Ok(Box::new(*concrete) as Box<dyn Serializer>),
                Err(_) => Err(Error::type_error("Failed to downcast to concrete type")),
            }
        }

        fn build_type_infos<T: StructSerializer>(
            type_resolver: &TypeResolver,
        ) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error> {
            build_struct_type_infos::<T>(type_resolver)
        }

        fn read_compatible<T2: 'static + StructSerializer + ForyDefault>(
            context: &mut ReadContext,
            type_info: Rc<TypeInfo>,
        ) -> Result<Box<dyn Any>, Error> {
            Ok(Box::new(T2::fory_read_compatible(context, type_info)?))
        }

        let harness = Harness::new(
            write::<T>,
            read::<T>,
            write_data::<T>,
            read_data::<T>,
            Some(read_compatible::<T>),
            to_serializer::<T>,
            build_type_infos::<T>,
        );
        let type_info = TypeInfo::new(
            actual_type_id,
            user_type_id,
            namespace,
            type_name,
            register_by_name,
            harness,
        )?;

        let rs_type_id = std::any::TypeId::of::<T>();
        if self.partial_type_infos.contains_key(&rs_type_id) {
            return Err(Error::type_error(format!(
                "rs_struct:{:?} already registered",
                rs_type_id
            )));
        }

        // Check if type_id conflicts with any already registered type
        // Skip check for:
        // 1. Internal types (type_id < TypeId::BOUND) as they can be shared
        // 2. Types registered by name (they use shared type IDs like NAMED_STRUCT)
        if !register_by_name
            && !crate::types::is_internal_type(actual_type_id)
            && self.user_type_info_by_id.contains_key(&user_type_id)
        {
            return Err(Error::type_error(format!(
                "Type ID {} conflicts with already registered type. Please use a different type ID.",
                actual_type_id
            )));
        }

        // Update type_id_index/user_type_id_index for fast lookup
        let index = T::fory_type_index() as usize;
        if index >= self.type_id_index.len() {
            self.type_id_index.resize(index + 1, NO_TYPE_ID);
            self.user_type_id_index.resize(index + 1, NO_USER_TYPE_ID);
            self.rust_type_id_by_index.resize(index + 1, None);
        } else if self.type_id_index[index] != NO_TYPE_ID {
            return Err(Error::type_error(format!(
                "Type index {:?} already registered",
                index
            )));
        }
        self.type_id_index[index] = type_info.type_id;
        self.user_type_id_index[index] = type_info.user_type_id;
        self.rust_type_id_by_index[index] = Some(rs_type_id);

        // Insert partial type info into id maps
        if crate::types::is_internal_type(actual_type_id) {
            let index = actual_type_id as usize;
            if index >= self.internal_type_info_by_id.len() {
                return Err(Error::not_allowed(format!(
                    "Internal type id overflow: {}",
                    actual_type_id
                )));
            }
            self.internal_type_info_by_id[index] = Some(Rc::new(type_info.clone()));
        } else if user_type_id != NO_USER_TYPE_ID {
            self.user_type_info_by_id
                .insert(user_type_id, Rc::new(type_info.clone()));
        }
        self.type_info_map
            .insert(rs_type_id, Rc::new(type_info.clone()));
        self.partial_type_infos.insert(rs_type_id, type_info);

        Ok(())
    }

    pub fn register_serializer_by_id<T: Serializer + ForyDefault>(
        &mut self,
        id: u32,
    ) -> Result<(), Error> {
        let actual_type_id = get_ext_actual_type_id(id, false);
        let static_type_id = T::fory_static_type_id();
        if static_type_id != TypeId::EXT && static_type_id != TypeId::NAMED_EXT {
            return Err(Error::not_allowed(
                "register_serializer can only be used for ext and named_ext types",
            ));
        }
        self.register_serializer::<T>(id, actual_type_id, &EMPTY_STRING, &EMPTY_STRING)
    }

    pub fn register_serializer_by_namespace<T: Serializer + ForyDefault>(
        &mut self,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        let actual_type_id = get_ext_actual_type_id(0, true);
        let static_type_id = T::fory_static_type_id();
        if static_type_id != TypeId::EXT && static_type_id != TypeId::NAMED_EXT {
            return Err(Error::not_allowed(
                "register_serializer can only be used for ext and named_ext types",
            ));
        }
        self.register_serializer::<T>(0, actual_type_id, namespace, type_name)
    }

    fn register_internal_serializer<T: Serializer + ForyDefault>(
        &mut self,
        type_id: TypeId,
    ) -> Result<(), Error> {
        let raw_id = type_id as u32;
        if raw_id >= INTERNAL_TYPE_ID_LIMIT as u32 {
            return Err(Error::not_allowed(format!(
                "Internal type id overflow: {}",
                raw_id
            )));
        }
        self.register_serializer::<T>(raw_id, raw_id, &EMPTY_STRING, &EMPTY_STRING)
    }

    fn register_serializer<T: Serializer + ForyDefault>(
        &mut self,
        id: u32,
        actual_type_id: u32,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        let register_by_name = !type_name.is_empty();
        if !register_by_name && id > MAX_USER_TYPE_ID {
            return Err(Error::not_allowed(format!(
                "type id must be in range [0, 0xfffffffe], got {}",
                id
            )));
        }

        fn write<T2: 'static + Serializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            ref_mode: RefMode,
            write_type_info: bool,
            has_generics: bool,
        ) -> Result<(), Error> {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => v.fory_write(context, ref_mode, write_type_info, has_generics),
                None => Err(Error::type_error(format!(
                    "Cast type to {:?} error when writing: {:?}",
                    std::any::type_name::<T2>(),
                    T2::fory_static_type_id()
                ))),
            }
        }

        fn read<T2: 'static + Serializer + ForyDefault>(
            context: &mut ReadContext,
            ref_mode: RefMode,
            read_type_info: bool,
        ) -> Result<Box<dyn Any>, Error> {
            Ok(Box::new(T2::fory_read(context, ref_mode, read_type_info)?))
        }

        fn write_data<T2: 'static + Serializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            has_generics: bool,
        ) -> Result<(), Error> {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => T2::fory_write_data_generic(v, context, has_generics),
                None => Err(Error::type_error(format!(
                    "Cast type to {:?} error when writing data: {:?}",
                    std::any::type_name::<T2>(),
                    T2::fory_static_type_id()
                ))),
            }
        }

        fn read_data<T2: 'static + Serializer + ForyDefault>(
            context: &mut ReadContext,
        ) -> Result<Box<dyn Any>, Error> {
            match T2::fory_read_data(context) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
            }
        }

        fn to_serializer<T2: 'static + Serializer>(
            boxed_any: Box<dyn Any>,
        ) -> Result<Box<dyn Serializer>, Error> {
            match boxed_any.downcast::<T2>() {
                Ok(concrete) => Ok(Box::new(*concrete) as Box<dyn Serializer>),
                Err(_) => Err(Error::type_error("Failed to downcast to concrete type")),
            }
        }

        fn build_type_infos<T2: 'static>(
            type_resolver: &TypeResolver,
        ) -> Result<Vec<(std::any::TypeId, TypeInfo)>, Error> {
            let partial_info = type_resolver
                .partial_type_infos
                .get(&std::any::TypeId::of::<T2>())
                .ok_or_else(|| {
                    Error::type_error(format!(
                        "Partial type info not found for serializer (type: {})",
                        std::any::type_name::<T2>()
                    ))
                })?;
            build_serializer_type_infos(partial_info, std::any::TypeId::of::<T2>())
        }

        // EXT types don't support fory_read_compatible
        let harness = Harness::new(
            write::<T>,
            read::<T>,
            write_data::<T>,
            read_data::<T>,
            None,
            to_serializer::<T>,
            build_type_infos::<T>,
        );

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
            harness,
        )?;

        let rs_type_id = std::any::TypeId::of::<T>();
        if self.partial_type_infos.contains_key(&rs_type_id) {
            return Err(Error::type_error(format!(
                "rs_struct:{:?} already registered",
                rs_type_id
            )));
        }

        // Check if type_id conflicts with any already registered type
        // Skip check for internal types as they can be shared
        if !crate::types::is_internal_type(actual_type_id)
            && user_type_id != NO_USER_TYPE_ID
            && self.user_type_info_by_id.contains_key(&user_type_id)
        {
            return Err(Error::type_error(format!(
                "Type ID {} conflicts with already registered type. Please use a different type ID.",
                actual_type_id
            )));
        }

        // Insert partial type info into id maps
        if crate::types::is_internal_type(actual_type_id) {
            let index = actual_type_id as usize;
            if index >= self.internal_type_info_by_id.len() {
                return Err(Error::not_allowed(format!(
                    "Internal type id overflow: {}",
                    actual_type_id
                )));
            }
            self.internal_type_info_by_id[index] = Some(Rc::new(type_info.clone()));
        } else if user_type_id != NO_USER_TYPE_ID {
            self.user_type_info_by_id
                .insert(user_type_id, Rc::new(type_info.clone()));
        }
        self.type_info_map
            .insert(rs_type_id, Rc::new(type_info.clone()));
        self.partial_type_infos.insert(rs_type_id, type_info);
        Ok(())
    }

    /// Register a generic trait type like List, Map, Set
    pub fn register_generic_trait<T: 'static + Serializer + ForyDefault>(
        &mut self,
    ) -> Result<(), Error> {
        let rs_type_id = std::any::TypeId::of::<T>();
        if self.type_info_map.contains_key(&rs_type_id) {
            return Err(Error::type_error(format!(
                "Type:{:?} already registered",
                rs_type_id
            )));
        }
        let type_id = T::fory_static_type_id();
        if type_id != TypeId::LIST && type_id != TypeId::MAP && type_id != TypeId::SET {
            return Err(Error::not_allowed(format!(
                "register_generic_trait can only be used for generic trait types: List, Map, Set, but got type {}",
                type_id as u32
            )));
        }
        self.register_internal_serializer::<T>(type_id)
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
    pub(crate) fn build_final_type_resolver(&self) -> Result<TypeResolver, Error> {
        // copy all type info from type_resolver to here
        let mut internal_type_info_by_id = self.internal_type_info_by_id.clone();
        let mut user_type_info_by_id = self.user_type_info_by_id.clone();
        let mut type_info_map = self.type_info_map.clone();
        let mut type_info_map_by_name = self.type_info_map_by_name.clone();
        let mut type_info_map_by_meta_string_name = self.type_info_map_by_meta_string_name.clone();
        let type_id_index = self.type_id_index.clone();
        let rust_type_id_by_index = self.rust_type_id_by_index.clone();
        let user_type_id_index = self.user_type_id_index.clone();

        // Iterate over partial_type_infos and complete them
        for (_rust_type_id, partial_type_info) in self.partial_type_infos.iter() {
            let harness = &partial_type_info.harness;
            // Call build_type_infos to get all type infos (main + enum variants)
            let type_infos = (harness.build_type_infos)(self)?;

            // Iterate through all type infos uniformly
            for (type_rust_id, type_info) in type_infos.iter() {
                // Insert into id maps
                if crate::types::is_internal_type(type_info.type_id as u32) {
                    let index = type_info.type_id as usize;
                    if index < internal_type_info_by_id.len() {
                        internal_type_info_by_id[index] = Some(Rc::new(type_info.clone()));
                    }
                } else if type_info.user_type_id != NO_USER_TYPE_ID {
                    user_type_info_by_id.insert(type_info.user_type_id, Rc::new(type_info.clone()));
                }

                // Insert into type_info_map with the TypeId
                type_info_map.insert(*type_rust_id, Rc::new(type_info.clone()));

                // Insert into name-based maps if registered by name
                if type_info.register_by_name {
                    let namespace = &type_info.namespace;
                    let type_name = &type_info.type_name;
                    let ms_key = (namespace.clone(), type_name.clone());
                    type_info_map_by_meta_string_name.insert(ms_key, Rc::new(type_info.clone()));
                    let string_key = (namespace.original.clone(), type_name.original.clone());
                    type_info_map_by_name.insert(string_key, Rc::new(type_info.clone()));
                }
            }
        }

        let type_meta_by_index: Vec<Option<Rc<crate::meta::TypeMeta>>> = rust_type_id_by_index
            .iter()
            .map(|id| {
                id.and_then(|rust_id| type_info_map.get(&rust_id).map(|info| info.get_type_meta()))
            })
            .collect();

        Ok(TypeResolver {
            internal_type_info_by_id,
            user_type_info_by_id,
            type_info_map,
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

        let type_info_map: HashMap<std::any::TypeId, Rc<TypeInfo>> = self
            .type_info_map
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

        TypeResolver {
            internal_type_info_by_id,
            user_type_info_by_id,
            type_info_map,
            type_info_map_by_name,
            type_info_map_by_meta_string_name,
            partial_type_infos: HashMap::new(),
            type_id_index: self.type_id_index.clone(),
            user_type_id_index: self.user_type_id_index.clone(),
            rust_type_id_by_index: self.rust_type_id_by_index.clone(),
            type_meta_by_index: self.type_meta_by_index.clone(),
            compatible: self.compatible,
            xlang: self.xlang,
        }
    }
}
