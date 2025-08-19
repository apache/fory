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
use crate::serializer::{Serializer, StructSerializer};
use crate::types::FieldType;
use chrono::{NaiveDate, NaiveDateTime};
use std::any::TypeId;
use std::collections::HashSet;
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
            type_def: T::type_def(fory),
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

pub struct TypeResolver {
    serialize_map: HashMap<u32, Harness>,
    type_id_map: HashMap<TypeId, u32>,
    type_info_map: HashMap<TypeId, TypeInfo>,
}
macro_rules! register_harness {
    ($ty:ty, $id:expr, $map:expr) => {{
        fn serializer(this: &dyn std::any::Any, context: &mut WriteContext) {
            let this = this.downcast_ref::<$ty>();
            match this {
                Some(v) => <$ty>::serialize(v, context),
                None => todo!(""),
            }
        }

        fn deserializer(context: &mut ReadContext) -> Result<Box<dyn std::any::Any>, Error> {
            match <$ty>::deserialize(context) {
                Ok(v) => Ok(Box::new(v)),
                Err(e) => Err(e),
            }
        }

        $map.insert($id as u32, Harness::new(serializer, deserializer));
    }};
}

impl Default for TypeResolver {
    fn default() -> Self {
        let mut serialize_map = HashMap::new();

        register_harness!(i8, FieldType::INT8, serialize_map);
        register_harness!(u8, FieldType::UINT8, serialize_map);
        register_harness!(i16, FieldType::INT16, serialize_map);
        register_harness!(u16, FieldType::UINT16, serialize_map);
        register_harness!(i32, FieldType::INT32, serialize_map);
        register_harness!(u32, FieldType::UINT32, serialize_map);
        register_harness!(u64, FieldType::UINT64, serialize_map);
        register_harness!(i64, FieldType::INT64, serialize_map);
        register_harness!(f32, FieldType::FLOAT, serialize_map);
        register_harness!(f64, FieldType::DOUBLE, serialize_map);

        register_harness!(bool, FieldType::BOOL, serialize_map);

        register_harness!(String, FieldType::STRING, serialize_map);

        register_harness!(NaiveDate, FieldType::DATE, serialize_map);
        register_harness!(NaiveDateTime, FieldType::TIMESTAMP, serialize_map);

        TypeResolver {
            serialize_map,
            type_id_map: HashMap::new(),
            type_info_map: HashMap::new(),
        }
    }
}

impl TypeResolver {
    pub fn get_type_info(&self, type_id: TypeId) -> &TypeInfo {
        self.type_info_map.get(&type_id).unwrap()
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
        self.type_id_map.insert(TypeId::of::<T>(), id);
        self.serialize_map
            .insert(id, Harness::new(serializer::<T>, deserializer::<T>));
        self.type_info_map.insert(TypeId::of::<T>(), type_info);
    }

    pub fn get_harness_by_type(&self, type_id: TypeId) -> Option<&Harness> {
        self.get_harness(*self.type_id_map.get(&type_id).unwrap())
    }

    pub fn get_harness(&self, id: u32) -> Option<&Harness> {
        self.serialize_map.get(&id)
    }
}
