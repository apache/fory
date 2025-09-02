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
use std::{any::Any, collections::HashMap};

pub struct Harness {
    serializer: fn(&dyn Any, &mut WriteContext),
    deserializer: fn(&mut ReadContext) -> Result<Box<dyn Any>, Error>,
}

impl Harness {
    pub fn new(
        serializer: fn(&dyn Any, &mut WriteContext),
        deserializer: fn(&mut ReadContext) -> Result<Box<dyn Any>, Error>,
    ) -> Harness {
        Harness {
            serializer,
            deserializer,
        }
    }

    pub fn get_serializer(&self) -> fn(&dyn Any, &mut WriteContext) {
        self.serializer
    }

    pub fn get_deserializer(&self) -> fn(&mut ReadContext) -> Result<Box<dyn Any>, Error> {
        self.deserializer
    }
}

pub struct TypeInfo {
    type_def: Vec<u8>,
    type_id: u32,
}

impl TypeInfo {
    pub fn new<T: StructSerializer>(fory: &Fory, type_id: u32) -> TypeInfo {
        TypeInfo {
            type_def: T::type_def(fory, type_id),
            type_id,
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
    type_id_map: HashMap<std::any::TypeId, u32>,
    type_info_map: HashMap<std::any::TypeId, TypeInfo>,
}

impl TypeResolver {
    pub fn get_type_info(&self, type_id: std::any::TypeId) -> &TypeInfo {
        self.type_info_map.get(&type_id).unwrap_or_else(|| {
            panic!(
                "TypeId {:?} not found in type_info_map, maybe you forgot to register some types",
                type_id
            )
        })
    }

    pub fn register<T: StructSerializer>(&mut self, type_info: TypeInfo, id: u32) {
        fn serializer<T2: 'static + StructSerializer>(this: &dyn Any, context: &mut WriteContext) {
            let this = this.downcast_ref::<T2>();
            match this {
                Some(v) => {
                    T2::serialize(v, context);
                }
                None => todo!(),
            }
        }

        fn deserializer<T2: 'static + StructSerializer>(
            context: &mut ReadContext,
        ) -> Result<Box<dyn Any>, Error> {
            match T2::deserialize(context) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
            }
        }
        self.type_id_map.insert(std::any::TypeId::of::<T>(), id);
        self.serialize_map
            .insert(id, Harness::new(serializer::<T>, deserializer::<T>));
        self.type_info_map
            .insert(std::any::TypeId::of::<T>(), type_info);
    }

    pub fn get_harness_by_type(&self, type_id: std::any::TypeId) -> Option<&Harness> {
        self.get_harness(*self.type_id_map.get(&type_id).unwrap())
    }

    pub fn get_harness(&self, id: u32) -> Option<&Harness> {
        self.serialize_map.get(&id)
    }
}
