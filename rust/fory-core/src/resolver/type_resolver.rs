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
use crate::serializer::StructSerializer;
use std::cell::RefCell;
use std::{any::Any, collections::HashMap};

type SerializerFn = fn(&dyn Any, &mut WriteContext, is_field: bool);
type DeserializerFn =
    fn(&mut ReadContext, is_field: bool, skip_ref_flag: bool) -> Result<Box<dyn Any>, Error>;

pub struct Harness {
    serializer: SerializerFn,
    deserializer: DeserializerFn,
}

impl Harness {
    pub fn new(serializer: SerializerFn, deserializer: DeserializerFn) -> Harness {
        Harness {
            serializer,
            deserializer,
        }
    }

    pub fn get_serializer(&self) -> fn(&dyn Any, &mut WriteContext, is_field: bool) {
        self.serializer
    }

    pub fn get_deserializer(&self) -> DeserializerFn {
        self.deserializer
    }
}

#[derive(Clone, Debug)]
pub struct TypeInfo {
    type_def: Vec<u8>,
    type_id: u32,
    namespace: String,
    type_name: String,
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
        TypeInfo {
            type_def: T::fory_type_def(fory, type_id, namespace, type_name, register_by_name),
            type_id,
            namespace: namespace.to_owned(),
            type_name: type_name.to_owned(),
            register_by_name,
        }
    }

    pub fn get_type_id(&self) -> u32 {
        self.type_id
    }

    pub fn get_type_def(&self) -> &Vec<u8> {
        &self.type_def
    }
}

#[derive(Default)]
pub struct TypeResolver {
    serialize_map: HashMap<u32, Harness>,
    name_serialize_map: HashMap<(String, String), Harness>,
    type_id_map: HashMap<std::any::TypeId, u32>,
    type_name_map: HashMap<std::any::TypeId, (String, String)>,
    type_info_map: HashMap<std::any::TypeId, TypeInfo>,
    // Fast lookup by numeric ID for common types
    type_id_index: Vec<u32>,
    sorted_field_names_map: RefCell<HashMap<std::any::TypeId, Vec<String>>>,
}

const NO_TYPE_ID: u32 = 1000000000;

impl TypeResolver {
    pub fn get_type_info(&self, type_id: std::any::TypeId) -> &TypeInfo {
        self.type_info_map.get(&type_id).unwrap_or_else(|| {
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

    pub fn register<T: StructSerializer>(&mut self, type_info: &TypeInfo) {
        fn serializer<T2: 'static + StructSerializer>(
            this: &dyn Any,
            context: &mut WriteContext,
            is_field: bool,
        ) {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    let skip_ref_flag =
                        crate::serializer::get_skip_ref_flag::<T2>(context.get_fory());
                    crate::serializer::write_info_data(v, context, is_field, skip_ref_flag, true);
                }
                None => todo!(),
            }
        }

        fn deserializer<T2: 'static + StructSerializer>(
            context: &mut ReadContext,
            is_field: bool,
            skip_ref_flag: bool,
        ) -> Result<Box<dyn Any>, Error> {
            match crate::serializer::read_info_data::<T2>(context, is_field, skip_ref_flag, true) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
            }
        }
        let rs_type_id = std::any::TypeId::of::<T>();
        if self.type_info_map.contains_key(&rs_type_id) {
            panic!("rs_struct:{:?} already registered", type_info.type_id);
        }
        self.type_info_map.insert(rs_type_id, (*type_info).clone());

        let index = T::fory_type_index() as usize;
        if index >= self.type_id_index.len() {
            self.type_id_index.resize(index + 1, NO_TYPE_ID);
        }
        self.type_id_index[index] = type_info.type_id;

        if type_info.register_by_name {
            let namespace = type_info.namespace.clone();
            let type_name = type_info.type_name.clone();
            if self
                .name_serialize_map
                .contains_key(&(namespace.clone(), type_name.clone()))
            {
                panic!("TypeId {:?} already registered_by_name", type_info.type_id);
            }
            self.type_name_map
                .insert(rs_type_id, (namespace.clone(), type_name.clone()));
            self.name_serialize_map.insert(
                (namespace, type_name),
                Harness::new(serializer::<T>, deserializer::<T>),
            );
        } else {
            let type_id = type_info.type_id;
            if self.serialize_map.contains_key(&type_id) {
                panic!("TypeId {:?} already registered_by_id", type_id);
            }

            self.type_id_map.insert(rs_type_id, type_id);
            self.serialize_map
                .insert(type_id, Harness::new(serializer::<T>, deserializer::<T>));
        }
    }

    pub fn get_harness_by_type(&self, type_id: std::any::TypeId) -> Option<&Harness> {
        self.get_harness(*self.type_id_map.get(&type_id).unwrap())
    }

    pub fn get_harness(&self, id: u32) -> Option<&Harness> {
        self.serialize_map.get(&id)
    }

    pub fn get_name_harness(&self, namespace: &str, type_name: &str) -> Option<&Harness> {
        self.name_serialize_map
            .get(&(namespace.to_owned(), type_name.to_owned()))
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
