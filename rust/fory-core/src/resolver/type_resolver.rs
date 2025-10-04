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
use crate::fory::Fory;
use crate::meta::{
    MetaString, NAMESPACE_ENCODER, NAMESPACE_ENCODINGS, TYPE_NAME_ENCODER, TYPE_NAME_ENCODINGS,
};
use crate::serializer::{Serializer, StructSerializer};
use std::cell::RefCell;
use std::sync::Arc;
use std::{any::Any, collections::HashMap};

type SerializerFn = fn(&dyn Any, &mut WriteContext, is_field: bool);
type DeserializerFn =
    fn(&mut ReadContext, is_field: bool, skip_ref_flag: bool) -> Result<Box<dyn Any>, Error>;
type ToSerializerFn = fn(Box<dyn Any>) -> Result<Box<dyn crate::serializer::Serializer>, Error>;

pub type ExtWriteFn = dyn Fn(&dyn Any, &mut WriteContext, bool) + Send + Sync;
pub type ExtReadFn = dyn Fn(&mut ReadContext, bool) -> Result<Box<dyn Any>, Error> + Send + Sync;

pub struct Harness {
    serializer: SerializerFn,
    deserializer: DeserializerFn,
    to_serializer: ToSerializerFn,
}

impl Harness {
    pub fn new(
        serializer: SerializerFn,
        deserializer: DeserializerFn,
        to_serializer: ToSerializerFn,
    ) -> Harness {
        Harness {
            serializer,
            deserializer,
            to_serializer,
        }
    }

    pub fn get_serializer(&self) -> SerializerFn {
        self.serializer
    }

    pub fn get_deserializer(&self) -> DeserializerFn {
        self.deserializer
    }

    pub fn get_to_serializer(&self) -> ToSerializerFn {
        self.to_serializer
    }
}

pub struct ExtHarness {
    write_fn: Arc<ExtWriteFn>,
    read_fn: Arc<ExtReadFn>,
}

impl ExtHarness {
    pub fn new<T, W, R>(write_fn: W, read_fn: R) -> ExtHarness
    where
        T: 'static,
        W: Fn(&T, &mut WriteContext, bool) + Send + Sync + 'static,
        R: Fn(&mut ReadContext, bool) -> Result<T, Error> + Send + Sync + 'static,
    {
        Self {
            write_fn: Arc::new(move |obj, ctx, is_field| {
                let obj = obj.downcast_ref::<T>().unwrap();
                write_fn(obj, ctx, is_field);
            }),
            read_fn: Arc::new(move |ctx, is_field| Ok(Box::new(read_fn(ctx, is_field)?))),
        }
    }

    pub fn get_write_fn(&self) -> Arc<ExtWriteFn> {
        Arc::clone(&self.write_fn)
    }

    pub fn get_read_fn(&self) -> Arc<ExtReadFn> {
        Arc::clone(&self.read_fn)
    }
}

#[derive(Clone, Debug)]
pub struct TypeInfo {
    type_def: Vec<u8>,
    type_id: u32,
    namespace: MetaString,
    type_name: MetaString,
    register_by_name: bool,
}

impl TypeInfo {
    pub fn new<T: StructSerializer>(
        fory: &Fory,
        type_id: u32,
        namespace: &str,
        type_name: &str,
        register_by_name: bool,
    ) -> TypeInfo {
        let namespace_metastring = NAMESPACE_ENCODER
            .encode_with_encodings(namespace, NAMESPACE_ENCODINGS)
            .unwrap();
        let type_name_metastring = TYPE_NAME_ENCODER
            .encode_with_encodings(type_name, TYPE_NAME_ENCODINGS)
            .unwrap();
        TypeInfo {
            type_def: T::fory_type_def(
                fory,
                type_id,
                namespace_metastring.clone(),
                type_name_metastring.clone(),
                register_by_name,
            ),
            type_id,
            namespace: namespace_metastring,
            type_name: type_name_metastring,
            register_by_name,
        }
    }

    pub fn new_with_empty_def<T: Serializer>(
        _fory: &Fory,
        type_id: u32,
        namespace: &str,
        type_name: &str,
        register_by_name: bool,
    ) -> TypeInfo {
        let namespace_metastring = NAMESPACE_ENCODER
            .encode_with_encodings(namespace, NAMESPACE_ENCODINGS)
            .unwrap();
        let type_name_metastring = TYPE_NAME_ENCODER
            .encode_with_encodings(type_name, TYPE_NAME_ENCODINGS)
            .unwrap();
        TypeInfo {
            type_def: vec![],
            type_id,
            namespace: namespace_metastring,
            type_name: type_name_metastring,
            register_by_name,
        }
    }

    pub fn get_type_id(&self) -> u32 {
        self.type_id
    }

    pub fn get_namespace(&self) -> &MetaString {
        &self.namespace
    }

    pub fn get_type_name(&self) -> &MetaString {
        &self.type_name
    }

    pub fn get_type_def(&self) -> &Vec<u8> {
        &self.type_def
    }
}

pub struct TypeResolver {
    serializer_map: HashMap<u32, Harness>,
    name_serializer_map: HashMap<(MetaString, MetaString), Harness>,
    ext_serializer_map: HashMap<u32, ExtHarness>,
    ext_name_serializer_map: HashMap<(MetaString, MetaString), ExtHarness>,
    type_id_map: HashMap<std::any::TypeId, u32>,
    type_name_map: HashMap<std::any::TypeId, (MetaString, MetaString)>,
    type_info_cache: HashMap<std::any::TypeId, TypeInfo>,
    // Fast lookup by numeric ID for common types
    type_id_index: Vec<u32>,
    sorted_field_names_map: RefCell<HashMap<std::any::TypeId, Vec<String>>>,
}

const NO_TYPE_ID: u32 = 1000000000;

impl Default for TypeResolver {
    fn default() -> Self {
        let mut resolver = TypeResolver {
            serializer_map: HashMap::new(),
            name_serializer_map: HashMap::new(),
            ext_serializer_map: HashMap::new(),
            ext_name_serializer_map: HashMap::new(),
            type_id_map: HashMap::new(),
            type_name_map: HashMap::new(),
            type_info_cache: HashMap::new(),
            type_id_index: Vec::new(),
            sorted_field_names_map: RefCell::new(HashMap::new()),
        };
        resolver.register_builtin_types();
        resolver
    }
}

impl TypeResolver {
    fn register_builtin_types(&mut self) {
        use crate::types::TypeId;

        macro_rules! register_basic_type {
            ($ty:ty, $type_id:expr) => {{
                let type_info = TypeInfo {
                    type_def: vec![],
                    type_id: $type_id as u32,
                    namespace: NAMESPACE_ENCODER
                        .encode_with_encodings("", NAMESPACE_ENCODINGS)
                        .unwrap(),
                    type_name: TYPE_NAME_ENCODER
                        .encode_with_encodings("", TYPE_NAME_ENCODINGS)
                        .unwrap(),
                    register_by_name: false,
                };
                self.register_serializer::<$ty>(&type_info);
            }};
        }

        register_basic_type!(bool, TypeId::BOOL);
        register_basic_type!(i8, TypeId::INT8);
        register_basic_type!(i16, TypeId::INT16);
        register_basic_type!(i32, TypeId::INT32);
        register_basic_type!(i64, TypeId::INT64);
        register_basic_type!(f32, TypeId::FLOAT32);
        register_basic_type!(f64, TypeId::FLOAT64);
        register_basic_type!(String, TypeId::STRING);

        register_basic_type!(Vec<bool>, TypeId::BOOL_ARRAY);
        register_basic_type!(Vec<i8>, TypeId::INT8_ARRAY);
        register_basic_type!(Vec<i16>, TypeId::INT16_ARRAY);
        register_basic_type!(Vec<i32>, TypeId::INT32_ARRAY);
        register_basic_type!(Vec<i64>, TypeId::INT64_ARRAY);
        register_basic_type!(Vec<f32>, TypeId::FLOAT32_ARRAY);
        register_basic_type!(Vec<f64>, TypeId::FLOAT64_ARRAY);
    }

    pub fn get_type_info(&self, type_id: std::any::TypeId) -> &TypeInfo {
        self.type_info_cache.get(&type_id).unwrap_or_else(|| {
            panic!(
                "TypeId {:?} not found in type_info_map, maybe you forgot to register some types",
                type_id
            )
        })
    }

    /// Fast path for getting type info by numeric ID (avoids HashMap lookup by TypeId)
    pub fn get_type_id(&self, type_id: &std::any::TypeId, id: u32) -> u32 {
        let id_usize = id as usize;
        if id_usize < self.type_id_index.len() {
            let type_id = self.type_id_index[id_usize];
            if type_id != NO_TYPE_ID {
                return type_id;
            }
        }
        panic!(
            "TypeId {:?} not found in type_id_index, maybe you forgot to register some types",
            type_id
        )
    }

    pub fn register<T: StructSerializer + Serializer + Default>(&mut self, type_info: &TypeInfo) {
        fn serializer<T2: 'static + Serializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            is_field: bool,
        ) {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    let skip_ref_flag =
                        crate::serializer::get_skip_ref_flag::<T2>(context.get_fory());
                    crate::serializer::write_ref_info_data(
                        v,
                        context,
                        is_field,
                        skip_ref_flag,
                        true,
                    );
                }
                None => todo!(),
            }
        }

        fn deserializer<T2: 'static + Serializer + Default>(
            context: &mut ReadContext,
            is_field: bool,
            skip_ref_flag: bool,
        ) -> Result<Box<dyn Any>, Error> {
            match crate::serializer::read_ref_info_data::<T2>(
                context,
                is_field,
                skip_ref_flag,
                true,
            ) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
            }
        }

        fn to_serializer<T2: 'static + Serializer>(
            boxed_any: Box<dyn Any>,
        ) -> Result<Box<dyn crate::serializer::Serializer>, Error> {
            match boxed_any.downcast::<T2>() {
                Ok(concrete) => Ok(Box::new(*concrete) as Box<dyn crate::serializer::Serializer>),
                Err(_) => Err(Error::Other(anyhow::anyhow!(
                    "Failed to downcast to concrete type"
                ))),
            }
        }

        let rs_type_id = std::any::TypeId::of::<T>();
        if self.type_info_cache.contains_key(&rs_type_id) {
            panic!("rs_struct:{:?} already registered", rs_type_id);
        }
        self.type_info_cache.insert(rs_type_id, type_info.clone());
        let index = T::fory_type_index() as usize;
        if index >= self.type_id_index.len() {
            self.type_id_index.resize(index + 1, NO_TYPE_ID);
        } else if self.type_id_index.get(index).unwrap() != &NO_TYPE_ID {
            panic!("please:{:?} already registered", type_info.type_id);
        }
        self.type_id_index[index] = type_info.type_id;

        if type_info.register_by_name {
            let namespace = &type_info.namespace;
            let type_name = &type_info.type_name;
            let key = (namespace.clone(), type_name.clone());
            if self.name_serializer_map.contains_key(&key) {
                panic!(
                    "Namespace:{:?} Name:{:?} already registered_by_name",
                    namespace, type_name
                );
            }
            self.type_name_map.insert(rs_type_id, key.clone());
            self.name_serializer_map.insert(
                key,
                Harness::new(serializer::<T>, deserializer::<T>, to_serializer::<T>),
            );
        } else {
            let type_id = type_info.type_id;
            if self.serializer_map.contains_key(&type_id) {
                panic!("TypeId {:?} already registered_by_id", type_id);
            }
            self.type_id_map.insert(rs_type_id, type_id);
            self.serializer_map.insert(
                type_id,
                Harness::new(serializer::<T>, deserializer::<T>, to_serializer::<T>),
            );
        }
    }

    pub fn register_serializer<T: Serializer + Default>(&mut self, type_info: &TypeInfo) {
        fn serializer<T2: 'static + Serializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            is_field: bool,
        ) {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    v.fory_write(context, is_field);
                }
                None => todo!(),
            }
        }

        fn deserializer<T2: 'static + Serializer + Default>(
            context: &mut ReadContext,
            is_field: bool,
            skip_ref_flag: bool,
        ) -> Result<Box<dyn Any>, Error> {
            if skip_ref_flag {
                match T2::fory_read_data(context, is_field) {
                    Ok(v) => Ok(Box::new(v)),
                    Err(e) => Err(e),
                }
            } else {
                match T2::fory_read(context, is_field) {
                    Ok(v) => Ok(Box::new(v)),
                    Err(e) => Err(e),
                }
            }
        }

        fn to_serializer<T2: 'static + Serializer>(
            boxed_any: Box<dyn Any>,
        ) -> Result<Box<dyn crate::serializer::Serializer>, Error> {
            match boxed_any.downcast::<T2>() {
                Ok(concrete) => Ok(Box::new(*concrete) as Box<dyn crate::serializer::Serializer>),
                Err(_) => Err(Error::Other(anyhow::anyhow!(
                    "Failed to downcast to concrete type"
                ))),
            }
        }

        let rs_type_id = std::any::TypeId::of::<T>();
        if self.type_info_cache.contains_key(&rs_type_id) {
            panic!("rs_struct:{:?} already registered", rs_type_id);
        }
        self.type_info_cache.insert(rs_type_id, type_info.clone());
        if type_info.register_by_name {
            let namespace = &type_info.namespace;
            let type_name = &type_info.type_name;
            let key = (namespace.clone(), type_name.clone());
            if self.name_serializer_map.contains_key(&key) {
                panic!(
                    "Namespace:{:?} Name:{:?} already registered_by_name",
                    namespace, type_name
                );
            }
            self.type_name_map.insert(rs_type_id, key.clone());
            self.name_serializer_map.insert(
                key,
                Harness::new(serializer::<T>, deserializer::<T>, to_serializer::<T>),
            );
        } else {
            let type_id = type_info.type_id;
            if self.serializer_map.contains_key(&type_id) {
                panic!("TypeId {:?} already registered_by_id", type_id);
            }
            self.type_id_map.insert(rs_type_id, type_id);
            self.serializer_map.insert(
                type_id,
                Harness::new(serializer::<T>, deserializer::<T>, to_serializer::<T>),
            );
        }
    }

    pub fn get_harness_by_type(&self, type_id: std::any::TypeId) -> Option<&Harness> {
        self.get_harness(*self.type_id_map.get(&type_id).unwrap())
    }

    pub fn get_harness(&self, id: u32) -> Option<&Harness> {
        self.serializer_map.get(&id)
    }

    pub fn get_name_harness(
        &self,
        namespace: &MetaString,
        type_name: &MetaString,
    ) -> Option<&Harness> {
        let key = (namespace.clone(), type_name.clone());
        self.name_serializer_map.get(&key)
    }

    pub fn get_ext_harness(&self, id: u32) -> Option<&ExtHarness> {
        self.ext_serializer_map.get(&id)
    }

    pub fn get_ext_name_harness(
        &self,
        namespace: &MetaString,
        type_name: &MetaString,
    ) -> Option<&ExtHarness> {
        let key = (namespace.clone(), type_name.clone());
        self.ext_name_serializer_map.get(&key)
    }

    pub fn get_sorted_field_names<T: StructSerializer>(
        &self,
        type_id: std::any::TypeId,
    ) -> Option<Vec<String>> {
        let map = self.sorted_field_names_map.borrow();
        map.get(&type_id).cloned()
    }

    pub fn set_sorted_field_names<T: StructSerializer>(&self, field_names: &[String]) {
        let mut map = self.sorted_field_names_map.borrow_mut();
        map.insert(std::any::TypeId::of::<T>(), field_names.to_owned());
    }

    pub fn get_fory_type_id(&self, rust_type_id: std::any::TypeId) -> Option<u32> {
        if let Some(type_info) = self.type_info_cache.get(&rust_type_id) {
            Some(type_info.get_type_id())
        } else {
            self.type_id_map.get(&rust_type_id).copied()
        }
    }
}
