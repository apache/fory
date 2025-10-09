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
    MetaString, TypeMeta, NAMESPACE_ENCODER, NAMESPACE_ENCODINGS, TYPE_NAME_ENCODER,
    TYPE_NAME_ENCODINGS,
};
use crate::serializer::{ForyDefault, Serializer, StructSerializer};
use std::sync::RwLock;
use std::{any::Any, collections::HashMap};

type WriteFn = fn(&dyn Any, fory: &Fory, &mut WriteContext, is_field: bool);
type ReadFn = fn(
    fory: &Fory,
    &mut ReadContext,
    is_field: bool,
    skip_ref_flag: bool,
) -> Result<Box<dyn Any>, Error>;

type WriteDataFn = fn(&dyn Any, ory: &Fory, &mut WriteContext, is_field: bool);
type ReadDataFn = fn(fory: &Fory, &mut ReadContext, is_field: bool) -> Result<Box<dyn Any>, Error>;
type ToSerializerFn = fn(Box<dyn Any>) -> Result<Box<dyn Serializer>, Error>;

#[derive(Clone)]
pub struct Harness {
    write_fn: WriteFn,
    read_fn: ReadFn,
    write_data_fn: WriteDataFn,
    read_data_fn: ReadDataFn,
    to_serializer: ToSerializerFn,
}

impl Harness {
    pub fn new(
        write_fn: WriteFn,
        read_fn: ReadFn,
        write_data_fn: WriteDataFn,
        read_data_fn: ReadDataFn,
        to_serializer: ToSerializerFn,
    ) -> Harness {
        Harness {
            write_fn,
            read_fn,
            write_data_fn,
            read_data_fn,
            to_serializer,
        }
    }

    pub fn get_write_fn(&self) -> WriteFn {
        self.write_fn
    }

    pub fn get_read_fn(&self) -> ReadFn {
        self.read_fn
    }

    pub fn get_write_data_fn(&self) -> WriteDataFn {
        self.write_data_fn
    }

    pub fn get_read_data_fn(&self) -> ReadDataFn {
        self.read_data_fn
    }

    pub fn get_to_serializer(&self) -> ToSerializerFn {
        self.to_serializer
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

    pub fn new_with_empty_fields<T: Serializer>(
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
        let meta = TypeMeta::from_fields(
            type_id,
            namespace_metastring.clone(),
            type_name_metastring.clone(),
            register_by_name,
            vec![],
        );
        let type_def = meta.to_bytes().unwrap();
        TypeInfo {
            type_def,
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

    pub fn is_registered_by_name(&self) -> bool {
        self.register_by_name
    }
}

pub struct TypeResolver {
    serializer_map: HashMap<u32, Harness>,
    name_serializer_map: HashMap<(MetaString, MetaString), Harness>,
    type_id_map: HashMap<std::any::TypeId, u32>,
    type_name_map: HashMap<std::any::TypeId, (MetaString, MetaString)>,
    type_info_cache: HashMap<std::any::TypeId, TypeInfo>,
    // Fast lookup by numeric ID for common types
    type_id_index: Vec<u32>,
    sorted_field_names_map: RwLock<HashMap<std::any::TypeId, Vec<String>>>,
}

const NO_TYPE_ID: u32 = 1000000000;

impl Default for TypeResolver {
    fn default() -> Self {
        let mut resolver = TypeResolver {
            serializer_map: HashMap::new(),
            name_serializer_map: HashMap::new(),
            type_id_map: HashMap::new(),
            type_name_map: HashMap::new(),
            type_info_cache: HashMap::new(),
            type_id_index: Vec::new(),
            sorted_field_names_map: RwLock::new(HashMap::new()),
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

    pub fn register<T: StructSerializer + Serializer + ForyDefault>(
        &mut self,
        type_info: &TypeInfo,
    ) {
        fn write<T2: 'static + Serializer>(
            this: &dyn Any,
            fory: &Fory,
            context: &mut WriteContext,
            is_field: bool,
        ) {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    let skip_ref_flag = crate::serializer::get_skip_ref_flag::<T2>(fory);
                    crate::serializer::write_ref_info_data(
                        v,
                        fory,
                        context,
                        is_field,
                        skip_ref_flag,
                        true,
                    );
                }
                None => todo!(),
            }
        }

        fn read<T2: 'static + Serializer + ForyDefault>(
            fory: &Fory,
            context: &mut ReadContext,
            is_field: bool,
            skip_ref_flag: bool,
        ) -> Result<Box<dyn Any>, Error> {
            match crate::serializer::read_ref_info_data::<T2>(
                fory,
                context,
                is_field,
                skip_ref_flag,
                true,
            ) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
            }
        }

        fn write_data<T2: 'static + Serializer>(
            this: &dyn Any,
            fory: &Fory,
            context: &mut WriteContext,
            is_field: bool,
        ) {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    T2::fory_write_data(v, fory, context, is_field);
                }
                None => todo!(),
            }
        }

        fn read_data<T2: 'static + Serializer + ForyDefault>(
            fory: &Fory,
            context: &mut ReadContext,
            is_field: bool,
        ) -> Result<Box<dyn Any>, Error> {
            match T2::fory_read_data(fory, context, is_field) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
            }
        }

        fn to_serializer<T2: 'static + Serializer>(
            boxed_any: Box<dyn Any>,
        ) -> Result<Box<dyn Serializer>, Error> {
            match boxed_any.downcast::<T2>() {
                Ok(concrete) => Ok(Box::new(*concrete) as Box<dyn Serializer>),
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
                Harness::new(
                    write::<T>,
                    read::<T>,
                    write_data::<T>,
                    read_data::<T>,
                    to_serializer::<T>,
                ),
            );
        } else {
            let type_id = type_info.type_id;
            if self.serializer_map.contains_key(&type_id) {
                panic!("TypeId {:?} already registered_by_id", type_id);
            }
            self.type_id_map.insert(rs_type_id, type_id);
            self.serializer_map.insert(
                type_id,
                Harness::new(
                    write::<T>,
                    read::<T>,
                    write_data::<T>,
                    read_data::<T>,
                    to_serializer::<T>,
                ),
            );
        }
    }

    pub fn register_serializer<T: Serializer + ForyDefault>(&mut self, type_info: &TypeInfo) {
        fn write<T2: 'static + Serializer>(
            this: &dyn Any,
            fory: &Fory,
            context: &mut WriteContext,
            is_field: bool,
        ) {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    v.fory_write(fory, context, is_field);
                }
                None => todo!(),
            }
        }

        fn read<T2: 'static + Serializer + ForyDefault>(
            fory: &Fory,
            context: &mut ReadContext,
            is_field: bool,
            skip_ref_flag: bool,
        ) -> Result<Box<dyn Any>, Error> {
            if skip_ref_flag {
                match T2::fory_read_data(fory, context, is_field) {
                    Ok(v) => Ok(Box::new(v)),
                    Err(e) => Err(e),
                }
            } else {
                match T2::fory_read(fory, context, is_field) {
                    Ok(v) => Ok(Box::new(v)),
                    Err(e) => Err(e),
                }
            }
        }

        fn write_data<T2: 'static + Serializer>(
            this: &dyn Any,
            fory: &Fory,
            context: &mut WriteContext,
            is_field: bool,
        ) {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    T2::fory_write_data(v, fory, context, is_field);
                }
                None => todo!(),
            }
        }

        fn read_data<T2: 'static + Serializer + ForyDefault>(
            fory: &Fory,
            context: &mut ReadContext,
            is_field: bool,
        ) -> Result<Box<dyn Any>, Error> {
            match T2::fory_read_data(fory, context, is_field) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
            }
        }

        fn to_serializer<T2: 'static + Serializer>(
            boxed_any: Box<dyn Any>,
        ) -> Result<Box<dyn Serializer>, Error> {
            match boxed_any.downcast::<T2>() {
                Ok(concrete) => Ok(Box::new(*concrete) as Box<dyn Serializer>),
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
                Harness::new(
                    write::<T>,
                    read::<T>,
                    write_data::<T>,
                    read_data::<T>,
                    to_serializer::<T>,
                ),
            );
        } else {
            let type_id = type_info.type_id;
            if self.serializer_map.contains_key(&type_id) {
                panic!("TypeId {:?} already registered_by_id", type_id);
            }
            self.type_id_map.insert(rs_type_id, type_id);
            self.serializer_map.insert(
                type_id,
                Harness::new(
                    write::<T>,
                    read::<T>,
                    write_data::<T>,
                    read_data::<T>,
                    to_serializer::<T>,
                ),
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

    pub fn get_ext_harness(&self, id: u32) -> &Harness {
        self.serializer_map
            .get(&id)
            .unwrap_or_else(|| panic!("ext type must be registered in both peers"))
    }

    pub fn get_ext_name_harness(&self, namespace: &MetaString, type_name: &MetaString) -> &Harness {
        let key = (namespace.clone(), type_name.clone());
        self.name_serializer_map
            .get(&key)
            .expect("named_ext type must be registered in both peers")
    }

    pub fn get_sorted_field_names<T: StructSerializer>(
        &self,
        type_id: std::any::TypeId,
    ) -> Option<Vec<String>> {
        let map = self.sorted_field_names_map.read().unwrap();
        map.get(&type_id).cloned()
    }

    pub fn set_sorted_field_names<T: StructSerializer>(&self, field_names: &[String]) {
        let mut map = self.sorted_field_names_map.write().unwrap();
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
