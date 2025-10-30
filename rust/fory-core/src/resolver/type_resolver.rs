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
    FieldInfo, MetaString, TypeMeta, NAMESPACE_ENCODER, NAMESPACE_ENCODINGS, TYPE_NAME_ENCODER,
    TYPE_NAME_ENCODINGS,
};
use crate::serializer::{ForyDefault, Serializer, StructSerializer};
use crate::util::get_ext_actual_type_id;
use crate::{Reader, TypeId};
use std::collections::{HashSet, LinkedList};
use std::rc::Rc;
use std::vec;
use std::{any::Any, collections::HashMap};

type WriteFn = fn(
    &dyn Any,
    &mut WriteContext,
    write_ref_info: bool,
    write_type_info: bool,
    has_enerics: bool,
) -> Result<(), Error>;
type ReadFn =
    fn(&mut ReadContext, read_ref_info: bool, read_type_info: bool) -> Result<Box<dyn Any>, Error>;

type WriteDataFn = fn(&dyn Any, &mut WriteContext, has_generics: bool) -> Result<(), Error>;
type ReadDataFn = fn(&mut ReadContext) -> Result<Box<dyn Any>, Error>;
type ToSerializerFn = fn(Box<dyn Any>) -> Result<Box<dyn Serializer>, Error>;
type GetSortedFieldInfosFn = fn(&TypeResolver) -> Result<Vec<FieldInfo>, Error>;
const EMPTY_STRING: String = String::new();

#[derive(Clone, Debug)]
pub struct Harness {
    write_fn: WriteFn,
    read_fn: ReadFn,
    write_data_fn: WriteDataFn,
    read_data_fn: ReadDataFn,
    to_serializer: ToSerializerFn,
    sorted_field_infos: GetSortedFieldInfosFn,
}

impl Harness {
    pub fn new(
        write_fn: WriteFn,
        read_fn: ReadFn,
        write_data_fn: WriteDataFn,
        read_data_fn: ReadDataFn,
        to_serializer: ToSerializerFn,
        sorted_field_infos: GetSortedFieldInfosFn,
    ) -> Harness {
        Harness {
            write_fn,
            read_fn,
            write_data_fn,
            read_data_fn,
            to_serializer,
            sorted_field_infos,
        }
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
    pub fn get_to_serializer(&self) -> ToSerializerFn {
        self.to_serializer
    }
}

#[derive(Clone, Debug)]
pub struct TypeInfo {
    type_def: Rc<Vec<u8>>,
    type_meta: Rc<TypeMeta>,
    type_id: u32,
    namespace: Rc<MetaString>,
    type_name: Rc<MetaString>,
    register_by_name: bool,
    harness: Harness,
}

impl TypeInfo {
    fn new(
        type_resolver: &TypeResolver,
        type_id: u32,
        namespace: &str,
        type_name: &str,
        register_by_name: bool,
        harness: Harness,
    ) -> Result<TypeInfo, Error> {
        let namespace_meta_string =
            NAMESPACE_ENCODER.encode_with_encodings(namespace, NAMESPACE_ENCODINGS)?;
        let type_name_meta_string =
            TYPE_NAME_ENCODER.encode_with_encodings(type_name, TYPE_NAME_ENCODINGS)?;
        let type_meta = Rc::new(TypeMeta::from_fields(
            type_id,
            namespace_meta_string.clone(),
            type_name_meta_string.clone(),
            register_by_name,
            (harness.sorted_field_infos)(type_resolver)?,
        ));
        let type_def_bytes = type_meta.to_bytes()?;
        Ok(TypeInfo {
            type_def: Rc::from(type_def_bytes),
            type_meta,
            type_id,
            namespace: Rc::from(namespace_meta_string),
            type_name: Rc::from(type_name_meta_string),
            register_by_name,
            harness,
        })
    }

    fn new_lazy(
        type_id: u32,
        namespace: &str,
        type_name: &str,
        register_by_name: bool,
        harness: Harness,
    ) -> Result<TypeInfo, Error> {
        let namespace_meta_string =
            NAMESPACE_ENCODER.encode_with_encodings(namespace, NAMESPACE_ENCODINGS)?;
        let type_name_meta_string =
            TYPE_NAME_ENCODER.encode_with_encodings(type_name, TYPE_NAME_ENCODINGS)?;
        Ok(TypeInfo {
            type_def: Rc::from(vec![]),
            type_meta: Rc::new(TypeMeta::empty()),
            type_id,
            namespace: Rc::from(namespace_meta_string),
            type_name: Rc::from(type_name_meta_string),
            register_by_name,
            harness,
        })
    }

    fn new_with_empty_fields(
        type_resolver: &TypeResolver,
        type_id: u32,
        namespace: &str,
        type_name: &str,
        register_by_name: bool,
        harness: Harness,
    ) -> Result<TypeInfo, Error> {
        let namespace_meta_string =
            NAMESPACE_ENCODER.encode_with_encodings(namespace, NAMESPACE_ENCODINGS)?;
        let type_name_meta_string =
            TYPE_NAME_ENCODER.encode_with_encodings(type_name, TYPE_NAME_ENCODINGS)?;
        let meta = TypeMeta::from_fields(
            type_id,
            namespace_meta_string.clone(),
            type_name_meta_string.clone(),
            register_by_name,
            vec![],
        );
        let type_def = meta.to_bytes()?;
        let meta = TypeMeta::from_bytes(&mut Reader::new(&type_def), type_resolver)?;
        Ok(TypeInfo {
            type_def: Rc::from(type_def),
            type_meta: Rc::new(meta),
            type_id,
            namespace: Rc::from(namespace_meta_string),
            type_name: Rc::from(type_name_meta_string),
            register_by_name,
            harness,
        })
    }

    #[inline(always)]
    pub fn get_type_id(&self) -> u32 {
        self.type_id
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
    pub fn is_registered_by_name(&self) -> bool {
        self.register_by_name
    }

    #[inline(always)]
    pub fn get_harness(&self) -> &Harness {
        &self.harness
    }

    /// Create a TypeInfo from remote TypeMeta with a stub harness
    /// Used when the type doesn't exist locally during deserialization
    pub fn from_remote_meta(
        remote_meta: Rc<TypeMeta>,
        local_harness: Option<&Harness>,
    ) -> TypeInfo {
        let type_id = remote_meta.get_type_id();
        let namespace = remote_meta.get_namespace();
        let type_name = remote_meta.get_type_name();
        let type_def_bytes = remote_meta.to_bytes().unwrap_or_default();
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
                stub_to_serializer_fn,
                stub_sorted_field_infos,
            )
        };

        TypeInfo {
            type_def: Rc::from(type_def_bytes),
            type_meta: remote_meta,
            type_id,
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
    _: bool,
    _: bool,
    _: bool,
) -> Result<(), Error> {
    Err(Error::type_error(
        "Cannot serialize unknown remote type - type not registered locally",
    ))
}

fn stub_read_fn(_: &mut ReadContext, _: bool, _: bool) -> Result<Box<dyn Any>, Error> {
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

fn stub_sorted_field_infos(_: &TypeResolver) -> Result<Vec<FieldInfo>, Error> {
    Err(Error::type_error(
        "Cannot get field infos for unknown remote type",
    ))
}

/// TypeResolver is a resolver for fast type/serializer dispatch.
pub struct TypeResolver {
    type_info_map_by_id: HashMap<u32, Rc<TypeInfo>>,
    type_info_map: HashMap<std::any::TypeId, Rc<TypeInfo>>,
    type_info_map_by_name: HashMap<(String, String), Rc<TypeInfo>>,
    type_info_map_by_meta_string_name: HashMap<(Rc<MetaString>, Rc<MetaString>), Rc<TypeInfo>>,
    partial_type_infos: HashMap<std::any::TypeId, TypeInfo>,
    // Fast lookup by numeric ID for common types
    type_id_index: Vec<u32>,
    compatible: bool,
}

// Safety: TypeResolver instances are only shared through higher-level synchronization that
// guarantees thread confinement for mutations, so marking them Send/Sync preserves existing
// invariants despite internal Rc usage.
unsafe impl Send for TypeResolver {}
unsafe impl Sync for TypeResolver {}

const NO_TYPE_ID: u32 = 1000000000;

impl Default for TypeResolver {
    fn default() -> Self {
        let mut registry = TypeResolver {
            type_info_map_by_id: HashMap::new(),
            type_info_map: HashMap::new(),
            type_info_map_by_name: HashMap::new(),
            type_info_map_by_meta_string_name: HashMap::new(),
            type_id_index: Vec::new(),
            partial_type_infos: HashMap::new(),
            compatible: false,
        };
        registry.register_builtin_types().unwrap();
        registry
    }
}

impl TypeResolver {
    pub fn get_type_info(&self, type_id: &std::any::TypeId) -> Result<Rc<TypeInfo>, Error> {
        self.type_info_map.get(type_id)
                .ok_or_else(|| {
                    Error::type_error(format!(
                        "TypeId {:?} not found in type_info registry, maybe you forgot to register some types",
                        type_id
                    ))
                })
            .cloned()
    }

    #[inline(always)]
    pub fn get_type_info_by_id(&self, id: u32) -> Option<Rc<TypeInfo>> {
        self.type_info_map_by_id.get(&id).cloned()
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
    pub fn get_type_id(&self, type_id: &std::any::TypeId, id: u32) -> Result<u32, Error> {
        let id_usize = id as usize;
        if id_usize < self.type_id_index.len() {
            let type_id = self.type_id_index[id_usize];
            if type_id != NO_TYPE_ID {
                return Ok(type_id);
            }
        }
        Err(Error::type_error(format!(
            "TypeId {:?} not found in type_id_index, maybe you forgot to register some types",
            type_id
        )))
    }

    #[inline(always)]
    pub fn get_harness(&self, id: u32) -> Option<Rc<Harness>> {
        self.type_info_map_by_id
            .get(&id)
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
    pub fn get_ext_harness(&self, id: u32) -> Result<Rc<Harness>, Error> {
        self.type_info_map_by_id
            .get(&id)
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
    pub fn get_fory_type_id(&self, rust_type_id: std::any::TypeId) -> Option<u32> {
        self.type_info_map
            .get(&rust_type_id)
            .map(|info| info.get_type_id())
    }

    fn register_builtin_types(&mut self) -> Result<(), Error> {
        self.register_internal_serializer::<bool>(TypeId::BOOL)?;
        self.register_internal_serializer::<i8>(TypeId::INT8)?;
        self.register_internal_serializer::<i16>(TypeId::INT16)?;
        self.register_internal_serializer::<i32>(TypeId::INT32)?;
        self.register_internal_serializer::<i64>(TypeId::INT64)?;
        self.register_internal_serializer::<f32>(TypeId::FLOAT32)?;
        self.register_internal_serializer::<f64>(TypeId::FLOAT64)?;
        self.register_internal_serializer::<String>(TypeId::STRING)?;

        self.register_internal_serializer::<Vec<bool>>(TypeId::BOOL_ARRAY)?;
        self.register_internal_serializer::<Vec<i8>>(TypeId::INT8_ARRAY)?;
        self.register_internal_serializer::<Vec<i16>>(TypeId::INT16_ARRAY)?;
        self.register_internal_serializer::<Vec<i32>>(TypeId::INT32_ARRAY)?;
        self.register_internal_serializer::<Vec<i64>>(TypeId::INT64_ARRAY)?;
        self.register_internal_serializer::<Vec<f32>>(TypeId::FLOAT32_ARRAY)?;
        self.register_internal_serializer::<Vec<f64>>(TypeId::FLOAT64_ARRAY)?;
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

    pub fn register_by_namespace<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        self.register::<T>(0, namespace, type_name, true)
    }

    fn register<T: StructSerializer + Serializer + ForyDefault>(
        &mut self,
        id: u32,
        namespace: &str,
        type_name: &str,
        lazy: bool,
    ) -> Result<(), Error> {
        let register_by_name = !type_name.is_empty();
        if !register_by_name && id == 0 {
            return Err(Error::not_allowed(
                "Either id must be non-zero for ID registration, or type_name must be non-empty for name registration",
            ));
        }
        let actual_type_id = T::fory_actual_type_id(id, register_by_name, self.compatible);

        fn write<T2: 'static + Serializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            write_ref_info: bool,
            write_type_info: bool,
            has_generics: bool,
        ) -> Result<(), Error> {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    T2::fory_write(v, context, write_ref_info, write_type_info, has_generics)
                }
                None => Err(Error::type_error(format!(
                    "Cast type to {:?} error when writing: {:?}",
                    std::any::type_name::<T2>(),
                    T2::fory_static_type_id()
                ))),
            }
        }

        fn read<T2: 'static + Serializer + ForyDefault>(
            context: &mut ReadContext,
            read_ref_info: bool,
            read_type_info: bool,
        ) -> Result<Box<dyn Any>, Error> {
            Ok(Box::new(T2::fory_read(
                context,
                read_ref_info,
                read_type_info,
            )?))
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

        fn sorted_field_infos<T: StructSerializer>(
            type_resolver: &TypeResolver,
        ) -> Result<Vec<FieldInfo>, Error> {
            let mut fields_info = T::fory_fields_info(type_resolver)?;
            let sorted_field_names = T::fory_get_sorted_field_names();
            let mut sorted_field_infos: Vec<FieldInfo> = Vec::with_capacity(fields_info.len());
            for name in sorted_field_names.iter() {
                let mut found = false;
                for i in 0..fields_info.len() {
                    if &fields_info[i].field_name == name {
                        // swap_remove is faster
                        sorted_field_infos.push(fields_info.swap_remove(i));
                        found = true;
                        break;
                    }
                }
                if !found {
                    unreachable!("Field {} not found in fields_info", name);
                }
            }
            // assign field id in ascending order
            for (i, field_info) in sorted_field_infos.iter_mut().enumerate() {
                field_info.field_id = i as i16;
            }
            Ok(sorted_field_infos)
        }

        let harness = Harness::new(
            write::<T>,
            read::<T>,
            write_data::<T>,
            read_data::<T>,
            to_serializer::<T>,
            sorted_field_infos::<T>,
        );
        let type_info = if lazy {
            let type_info = TypeInfo::new_lazy(
                actual_type_id,
                namespace,
                type_name,
                register_by_name,
                harness,
            )?;
            self.partial_type_infos
                .insert(std::any::TypeId::of::<T>(), type_info.clone());
            type_info
        } else {
            TypeInfo::new(
                self,
                actual_type_id,
                namespace,
                type_name,
                register_by_name,
                harness,
            )?
        };
        let rs_type_id = std::any::TypeId::of::<T>();
        if self.type_info_map.contains_key(&rs_type_id) {
            return Err(Error::type_error(format!(
                "rs_struct:{:?} already registered",
                rs_type_id
            )));
        }

        // Store in main map
        self.type_info_map
            .insert(rs_type_id, Rc::new(type_info.clone()));

        // Store by ID
        self.type_info_map_by_id
            .insert(type_info.type_id, Rc::new(type_info.clone()));

        // Update type_id_index for fast lookup
        let index = T::fory_type_index() as usize;
        if index >= self.type_id_index.len() {
            self.type_id_index.resize(index + 1, NO_TYPE_ID);
        } else if self.type_id_index[index] != NO_TYPE_ID {
            return Err(Error::type_error(format!(
                "Type index {:?} already registered",
                index
            )));
        }
        self.type_id_index[index] = type_info.type_id;

        // Store by name if registered by name
        if type_info.register_by_name {
            let namespace = &type_info.namespace;
            let type_name = &type_info.type_name;
            let ms_key = (namespace.clone(), type_name.clone());
            if self.type_info_map_by_meta_string_name.contains_key(&ms_key) {
                return Err(Error::invalid_data(format!(
                    "Namespace:{:?} Name:{:?} already registered_by_name",
                    namespace, type_name
                )));
            }
            self.type_info_map_by_meta_string_name
                .insert(ms_key, Rc::new(type_info.clone()));
            let string_key = (namespace.original.clone(), type_name.original.clone());
            self.type_info_map_by_name
                .insert(string_key, Rc::new(type_info));
        }
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
        self.register_serializer::<T>(type_id as u32, type_id as u32, &EMPTY_STRING, &EMPTY_STRING)
    }

    fn register_serializer<T: Serializer + ForyDefault>(
        &mut self,
        id: u32,
        actual_type_id: u32,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        let register_by_name = !type_name.is_empty();
        if !register_by_name && id == 0 {
            return Err(Error::not_allowed(
                "Either id must be non-zero for ID registration, or type_name must be non-empty for name registration",
            ));
        }

        fn write<T2: 'static + Serializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            write_ref_info: bool,
            write_type_info: bool,
            has_generics: bool,
        ) -> Result<(), Error> {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    Ok(v.fory_write(context, write_ref_info, write_type_info, has_generics)?)
                }
                None => Err(Error::type_error(format!(
                    "Cast type to {:?} error when writing: {:?}",
                    std::any::type_name::<T2>(),
                    T2::fory_static_type_id()
                ))),
            }
        }

        fn read<T2: 'static + Serializer + ForyDefault>(
            context: &mut ReadContext,
            read_ref_info: bool,
            read_type_info: bool,
        ) -> Result<Box<dyn Any>, Error> {
            Ok(Box::new(T2::fory_read(
                context,
                read_ref_info,
                read_type_info,
            )?))
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

        fn sorted_field_infos(_: &TypeResolver) -> Result<Vec<FieldInfo>, Error> {
            Ok(vec![])
        }

        let harness = Harness::new(
            write::<T>,
            read::<T>,
            write_data::<T>,
            read_data::<T>,
            to_serializer::<T>,
            sorted_field_infos,
        );

        let type_info = TypeInfo::new_with_empty_fields(
            self,
            actual_type_id,
            namespace,
            type_name,
            register_by_name,
            harness,
        )?;

        let rs_type_id = std::any::TypeId::of::<T>();
        if self.type_info_map.contains_key(&rs_type_id) {
            return Err(Error::type_error(format!(
                "rs_struct:{:?} already registered",
                rs_type_id
            )));
        }

        // Store in main map
        self.type_info_map
            .insert(rs_type_id, Rc::new(type_info.clone()));

        // Store by ID
        self.type_info_map_by_id
            .insert(type_info.type_id, Rc::new(type_info.clone()));

        // Store by name if registered by name
        if type_info.register_by_name {
            let namespace = &type_info.namespace;
            let type_name = &type_info.type_name;
            let ms_key = (namespace.clone(), type_name.clone());
            if self.type_info_map_by_meta_string_name.contains_key(&ms_key) {
                return Err(Error::invalid_data(format!(
                    "Namespace:{:?} Name:{:?} already registered_by_name",
                    namespace, type_name
                )));
            }
            self.type_info_map_by_meta_string_name
                .insert(ms_key, Rc::new(type_info.clone()));
            let string_key = (namespace.original.clone(), type_name.original.clone());
            self.type_info_map_by_name
                .insert(string_key, Rc::new(type_info));
        }
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
        let mut type_info_map_by_id = self.type_info_map_by_id.clone();
        let mut type_info_map = self.type_info_map.clone();
        let mut type_info_map_by_name = self.type_info_map_by_name.clone();
        let mut type_info_map_by_meta_string_name = self.type_info_map_by_meta_string_name.clone();
        // Iterate over partial_type_infos and complete them
        for (type_id, partial_type_info) in self.partial_type_infos.iter() {
            let harness = partial_type_info.harness.clone();
            let sorted_field_infos = (harness.sorted_field_infos)(self)?;
            let type_meta = TypeMeta::from_fields(
                partial_type_info.type_id,
                (*partial_type_info.namespace).clone(),
                (*partial_type_info.type_name).clone(),
                partial_type_info.register_by_name,
                sorted_field_infos,
            );
            let completed_type_info = TypeInfo {
                type_def: Rc::from(type_meta.to_bytes()?),
                type_meta: Rc::new(type_meta),
                type_id: partial_type_info.type_id,
                namespace: partial_type_info.namespace.clone(),
                type_name: partial_type_info.type_name.clone(),
                register_by_name: partial_type_info.register_by_name,
                harness,
            };
            // Insert into all maps
            type_info_map_by_id.insert(
                completed_type_info.type_id,
                Rc::new(completed_type_info.clone()),
            );
            type_info_map.insert(*type_id, Rc::new(completed_type_info.clone()));
            if completed_type_info.register_by_name {
                let namespace = &completed_type_info.namespace;
                let type_name = &completed_type_info.type_name;
                let ms_key = (namespace.clone(), type_name.clone());
                type_info_map_by_meta_string_name
                    .insert(ms_key, Rc::new(completed_type_info.clone()));
                let string_key = (namespace.original.clone(), type_name.original.clone());
                type_info_map_by_name.insert(string_key, Rc::new(completed_type_info));
            }
        }
        Ok(TypeResolver {
            type_info_map_by_id: type_info_map_by_id.clone(),
            type_info_map: type_info_map.clone(),
            type_info_map_by_name: type_info_map_by_name.clone(),
            type_info_map_by_meta_string_name: type_info_map_by_meta_string_name.clone(),
            partial_type_infos: HashMap::new(),
            type_id_index: self.type_id_index.clone(),
            compatible: self.compatible,
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
    /// A shallow clone of the TypeResolver with all internal maps cloned.
    ///
    /// # See Also
    ///
    /// - [`build_final_type_resolver`](Self::build_final_type_resolver) - Builds a complete resolver
    pub(crate) fn clone(&self) -> TypeResolver {
        TypeResolver {
            type_info_map_by_id: self.type_info_map_by_id.clone(),
            type_info_map: self.type_info_map.clone(),
            type_info_map_by_name: self.type_info_map_by_name.clone(),
            type_info_map_by_meta_string_name: self.type_info_map_by_meta_string_name.clone(),
            partial_type_infos: HashMap::new(),
            type_id_index: self.type_id_index.clone(),
            compatible: self.compatible,
        }
    }
}
