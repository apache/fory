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

use crate::models::complex::{ECommerceData, ForyCustomer, ForyOrder, ForyOrderItem, ForyProduct};
use crate::models::medium::{Company, ForyAddress, Person};
use crate::models::realworld::{ForyAPIMetrics, ForyLogEntry, ForyUserProfile, SystemData};
use crate::models::simple::{SimpleList, SimpleMap, SimpleStruct};
use crate::serializers::Serializer;
use fory_core::fory::Fory;

#[derive(Default)]
pub struct ForySerializer {
    fory: Fory,
}

impl ForySerializer {
    pub fn new() -> Self {
        let mut fory = Fory::default().compatible(true);

        // Register simple types
        fory.register::<SimpleStruct>(100).unwrap();
        fory.register::<SimpleList>(101).unwrap();
        fory.register::<SimpleMap>(102).unwrap();

        // Register medium types
        fory.register::<ForyAddress>(200).unwrap();
        fory.register::<Person>(201).unwrap();
        fory.register::<Company>(202).unwrap();

        // Register complex types
        fory.register::<ForyProduct>(300).unwrap();
        fory.register::<ForyOrderItem>(301).unwrap();
        fory.register::<ForyCustomer>(302).unwrap();
        fory.register::<ForyOrder>(303).unwrap();
        fory.register::<ECommerceData>(304).unwrap();

        // Register realworld types
        fory.register::<ForyLogEntry>(400).unwrap();
        fory.register::<ForyUserProfile>(401).unwrap();
        fory.register::<ForyAPIMetrics>(402).unwrap();
        fory.register::<SystemData>(403).unwrap();

        Self { fory }
    }
}

impl Serializer<SimpleStruct> for ForySerializer {
    fn serialize(&self, data: &SimpleStruct) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data)?)
    }

    fn deserialize(&self, data: &[u8]) -> Result<SimpleStruct, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<SimpleList> for ForySerializer {
    fn serialize(&self, data: &SimpleList) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data)?)
    }

    fn deserialize(&self, data: &[u8]) -> Result<SimpleList, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<SimpleMap> for ForySerializer {
    fn serialize(&self, data: &SimpleMap) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data)?)
    }

    fn deserialize(&self, data: &[u8]) -> Result<SimpleMap, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<Person> for ForySerializer {
    fn serialize(&self, data: &Person) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data)?)
    }

    fn deserialize(&self, data: &[u8]) -> Result<Person, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<Company> for ForySerializer {
    fn serialize(&self, data: &Company) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data)?)
    }

    fn deserialize(&self, data: &[u8]) -> Result<Company, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<ECommerceData> for ForySerializer {
    fn serialize(&self, data: &ECommerceData) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data)?)
    }

    fn deserialize(&self, data: &[u8]) -> Result<ECommerceData, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}

impl Serializer<SystemData> for ForySerializer {
    fn serialize(&self, data: &SystemData) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self.fory.serialize(data)?)
    }

    fn deserialize(&self, data: &[u8]) -> Result<SystemData, Box<dyn std::error::Error>> {
        Ok(self.fory.deserialize(data)?)
    }
}
