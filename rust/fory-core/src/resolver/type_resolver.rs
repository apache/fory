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
use crate::serializer::{Serializer, StructSerializer};
use std::cell::RefCell;
use std::sync::Arc;
use std::{any::Any, collections::HashMap};

type WriteDataFn = fn(&dyn Any, &mut WriteContext, is_field: bool);
type ReadDataFn = fn(&mut ReadContext, is_field: bool) -> Result<Box<dyn Any>, Error>;

pub type ExtWriteDataFn = dyn Fn(&dyn Any, &mut WriteContext, bool) + Send + Sync;
pub type ExtReadDataFn =
    dyn Fn(&mut ReadContext, bool) -> Result<Box<dyn Any>, Error> + Send + Sync;

pub struct Harness {
    write_fn: WriteDataFn,
    read_fn: ReadDataFn,
}

impl Harness {
    pub fn new(write_fn: WriteDataFn, read_fn: ReadDataFn) -> Harness {
        Harness { write_fn, read_fn }
    }

    pub fn get_write_data_fn(&self) -> WriteDataFn {
        self.write_fn
    }

    pub fn get_read_data_fn(&self) -> ReadDataFn {
        self.read_fn
    }
}

pub struct ExtHarness {
    write_fn: Arc<ExtWriteDataFn>,
    read_fn: Arc<ExtReadDataFn>,
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

    pub fn get_write_data_fn(&self) -> Arc<ExtWriteDataFn> {
        Arc::clone(&self.write_fn)
    }

    pub fn get_read_data_fn(&self) -> Arc<ExtReadDataFn> {
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
}

#[derive(Default)]
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

impl TypeResolver {
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

    pub fn register<T: StructSerializer + Serializer>(&mut self, type_info: &TypeInfo) {
        fn write_data<T2: 'static + Serializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            is_field: bool,
        ) {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    // write_data
                    crate::serializer::write_ref_info_data(v, context, is_field, true, true);
                }
                None => todo!(),
            }
        }

        fn read_data<T2: 'static + Serializer>(
            context: &mut ReadContext,
            is_field: bool,
        ) -> Result<Box<dyn Any>, Error> {
            // read_data
            match crate::serializer::read_ref_info_data::<T2>(context, is_field, true, true) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
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
            self.name_serializer_map
                .insert(key, Harness::new(write_data::<T>, read_data::<T>));
        } else {
            let type_id = type_info.type_id;
            if self.serializer_map.contains_key(&type_id) {
                panic!("TypeId {:?} already registered_by_id", type_id);
            }
            self.type_id_map.insert(rs_type_id, type_id);
            self.serializer_map
                .insert(type_id, Harness::new(write_data::<T>, read_data::<T>));
        }
    }

    pub fn register_serializer<T: Serializer>(&mut self, type_info: &TypeInfo) {
        let rs_type_id = std::any::TypeId::of::<T>();
        if self.type_info_cache.contains_key(&rs_type_id) {
            panic!("rs_struct:{:?} already registered", rs_type_id);
        }
        self.type_info_cache.insert(rs_type_id, type_info.clone());

        let write_fn: fn(&T, &mut WriteContext, bool) = T::fory_write_data;
        let read_fn: fn(&mut ReadContext, bool) -> Result<T, Error> = T::fory_read_data;
        if type_info.register_by_name {
            let namespace = &type_info.namespace;
            let type_name = &type_info.type_name;
            let key = (namespace.clone(), type_name.clone());
            if self.ext_name_serializer_map.contains_key(&key) {
                panic!(
                    "Namespace:{:?} Name:{:?} already registered_by_name",
                    namespace, type_name
                );
            }
            self.type_name_map.insert(rs_type_id, key.clone());
            let harness = ExtHarness::new(write_fn, read_fn);
            self.ext_name_serializer_map.insert(key, harness);
        } else {
            let type_id = type_info.type_id;
            if self.ext_serializer_map.contains_key(&type_id) {
                panic!("TypeId {:?} already registered_by_id", type_id);
            }
            self.type_id_map.insert(rs_type_id, type_id);
            let harness = ExtHarness::new(write_fn, read_fn);
            self.ext_serializer_map.insert(type_id, harness);
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

    pub fn get_ext_harness(&self, id: u32) -> &ExtHarness {
        self.ext_serializer_map
            .get(&id)
            .unwrap_or_else(|| panic!("ext type must be registered in both peers"))
    }

    pub fn get_ext_name_harness(
        &self,
        namespace: &MetaString,
        type_name: &MetaString,
    ) -> &ExtHarness {
        let key = (namespace.clone(), type_name.clone());
        self.ext_name_serializer_map
            .get(&key)
            .unwrap_or_else(|| panic!("named_ext type must be registered in both peers"))
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
}
